/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.plan;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.Converter;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.tools.RelBuilderFactory;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A <code>RelOptRule</code> transforms an expression into another. It has a
 * list of {@link RelOptRuleOperand}s, which determine whether the rule can be
 * applied to a particular section of the tree.
 * <p>The optimizer figures out which rules are applicable, then calls
 * {@link #onMatch} on each of them.
 */
// 在 Calcite 中，关系代数树（RelNode Tree）的等价变换和优化全部由 Rule（规则）来驱动。
// 等价树形重塑：Rule 的核心职责是捕捉查询计划树中的某一特定子树结构（如 Filter 紧挨着 Project），将其转换成逻辑上等价但物理上更优、更高效的新子树结构（如将 Filter 下推到 Project 下方，甚至直接下推入 TableScan）。
// 声明式模式匹配（Pattern Matching）：Rule 并不盲目处理整个拓扑。
// 它内部通过包裹一棵由 RelOptRuleOperand 组成的操作数树（Operand Tree），向优化器声明：“我只对具备特定类型（Class）、特定物理特征（Trait）以及满足特定过滤条件（Predicate）的算子局部拓扑感兴趣。”
// 两阶段驱动机制：
// 阶段一（matches）：由优化器根据其内部操作数树，在 Memo 空间搜索并进行初步的结构和条件过滤。
// 阶段二（onMatch）：一旦匹配完全成功，触发此回调，真正出手改写算子，孵化并向优化器提交（transformTo）新算子。
public abstract class RelOptRule {
  //~ Static fields/initializers ---------------------------------------------

  //~ Instance fields --------------------------------------------------------

  /**
   * Description of rule, must be unique within planner. Default is the name
   * of the class sans package name, but derived classes are encouraged to
   * override.
   */
  // 该规则在优化器内部的唯一文本描述/名称
  protected final String description;

  /**
   * Root of operand tree.
   */
  // 操作数树的根节点（Root Operand）
  // 定义了该规则期望捕获的算子树最顶层节点的特征（如要求最顶层必须是一个 LogicalJoin）。它是规则匹配的第一个入口
  private final RelOptRuleOperand operand;

  /** Factory for a builder for relational expressions.
   *
   * <p>The actual builder is available via {@link RelOptRuleCall#builder()}. */
  // 关系表达式构建器工厂。
  public final RelBuilderFactory relBuilderFactory;

  /**
   * Flattened list of operands.把RelOptRuleOperand平铺
   */
  // 平铺后的一维操作数列表
  // 通过前序遍历将树状的 operand 及其所有子孙操作数完全拉平。
  // 优化器在运行时以此一维列表作为行索引，可以极快地通过数组下标（ordinalInRule）对各个匹配到的 RelNode 实施 $\mathcal{O}(1)$ 的随机存取。
  public final List<RelOptRuleOperand> operands;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a rule.
   *
   * @param operand root operand, must not be null
   */
  // 最简构造器。仅传入根操作数，默认绑定逻辑算子构建工厂 RelFactories.LOGICAL_BUILDER，其规则描述由系统自动推测
  protected RelOptRule(RelOptRuleOperand operand) {
    this(operand, RelFactories.LOGICAL_BUILDER, null);
  }

  /**
   * Creates a rule with an explicit description.
   *
   * @param operand     root operand, must not be null
   * @param description Description, or null to guess description
   */
  // 带自定义名称的构造器。允许显式传入独特的 description 字符串，同时默认绑定逻辑算子构建工厂。
  protected RelOptRule(RelOptRuleOperand operand, String description) {
    this(operand, RelFactories.LOGICAL_BUILDER, description);
  }

  /**
   * Creates a rule with an explicit description.
   *
   * @param operand     root operand, must not be null
   * @param description Description, or null to guess description
   * @param relBuilderFactory Builder for relational expressions
   */
  // 核心全功能构造器（前两个构造器最终都流向它）
  protected RelOptRule(RelOptRuleOperand operand,
      RelBuilderFactory relBuilderFactory, @Nullable String description) {
    this.operand = Objects.requireNonNull(operand, "operand");
    this.relBuilderFactory = Objects.requireNonNull(relBuilderFactory, "relBuilderFactory");
    if (description == null) {
      description = guessDescription(getClass().getName());
    }
    // 严厉核准规则名称合法性（必须字母开头，禁止包含特殊怪异符号）。
    if (!description.matches("[A-Za-z][-A-Za-z0-9_.(),\\[\\]\\s:]*")) {
      throw new RuntimeException("Rule description '" + description
          + "' is not valid");
    }
    this.description = description;
    this.operands = flattenOperands(operand);
    // 编译并锁定每个操作数的匹配求解顺序
    assignSolveOrder(operands);
  }

  //~ Methods for creating operands ------------------------------------------

  /**
   * Creates an operand that matches a relational expression that has no
   * children.
   *
   * @param clazz Class of relational expression to match (must not be null)
   * @param operandList Child operands
   * @param <R> Class of relational expression to match
   * @return Operand that matches a relational expression that has no
   *   children
   *
   * @deprecated Use {@link RelRule.OperandBuilder#operand(Class)}
   */
  // 它是 Calcite 早期版本（1.x 及更早）中用于构建规则操作数树（Operand Tree）的核心重载工具之一。
  // 为优化规则（Rule）声明一个局部的算子匹配节点，并为该节点绑定一组子算子匹配规则（operandList）。
  @Deprecated // to be removed before 2.0
  public static <R extends RelNode> RelOptRuleOperand operand(
      Class<R> clazz, // 目标算子的反射 Class 令牌，用于匹配时进行 instanceof 或类型对齐检查。
      RelOptRuleOperandChildren operandList) { // 孩子操作数集合。它不仅包裹了子节点的 RelOptRuleOperand 列表，还携带了匹配策略（如 SOME 严格按顺序匹配、UNORDERED 乱序匹配等）。
    return new RelOptRuleOperand(clazz, null, r -> true,
        operandList.policy, operandList.operands);
  }

  /**
   * Creates an operand that matches a relational expression that has no
   * children.
   *
   * @param clazz Class of relational expression to match (must not be null)
   * @param trait Trait to match, or null to match any trait
   * @param operandList Child operands
   * @param <R> Class of relational expression to match
   * @return Operand that matches a relational expression that has no
   *   children
   *
   * @deprecated Use {@link RelRule.OperandBuilder#operand(Class)}
   */
  @Deprecated // to be removed before 2.0
  public static <R extends RelNode> RelOptRuleOperand operand(
      Class<R> clazz,
      RelTrait trait,
      RelOptRuleOperandChildren operandList) {
    return new RelOptRuleOperand(clazz, trait, r -> true,
        operandList.policy, operandList.operands);
  }

  /**
   * Creates an operand that matches a relational expression that has a
   * particular trait and predicate.
   *
   * @param clazz Class of relational expression to match (must not be null)
   * @param trait Trait to match, or null to match any trait
   * @param predicate Additional match predicate
   * @param operandList Child operands
   * @param <R> Class of relational expression to match
   * @return Operand that matches a relational expression that has a
   *   particular trait and predicate
   *
   * @deprecated Use {@link RelRule.OperandBuilder#operand(Class)}
   */
  @Deprecated // to be removed before 2.0
  public static <R extends RelNode> RelOptRuleOperand operandJ(
      Class<R> clazz,
      RelTrait trait,
      Predicate<? super R> predicate,
      RelOptRuleOperandChildren operandList) {
    return new RelOptRuleOperand(clazz, trait, predicate, operandList.policy,
        operandList.operands);
  }

  // CHECKSTYLE: IGNORE 1
  /** @deprecated Use {@link #operandJ} */
  @SuppressWarnings("Guava")
  @Deprecated // to be removed before 2.0
  public static <R extends RelNode> RelOptRuleOperand operand(
      Class<R> clazz,
      RelTrait trait,
      com.google.common.base.Predicate<? super R> predicate,
      RelOptRuleOperandChildren operandList) {
    return operandJ(clazz, trait, (Predicate<? super R>) predicate::apply,
        operandList);
  }

  /**
   * Creates an operand that matches a relational expression that has no
   * children.
   *
   * @param clazz Class of relational expression to match (must not be null)
   * @param trait Trait to match, or null to match any trait
   * @param predicate Additional match predicate
   * @param first First operand
   * @param rest Rest operands
   * @param <R> Class of relational expression to match
   * @return Operand
   *
   * @deprecated Use {@link RelRule.OperandBuilder#operand(Class)}
   */
  @Deprecated // to be removed before 2.0
  public static <R extends RelNode> RelOptRuleOperand operandJ(
      Class<R> clazz,
      RelTrait trait,
      Predicate<? super R> predicate,
      RelOptRuleOperand first,
      RelOptRuleOperand... rest) {
    return operandJ(clazz, trait, predicate, some(first, rest));
  }

  @SuppressWarnings("Guava")
  @Deprecated // to be removed before 2.0
  public static <R extends RelNode> RelOptRuleOperand operand(
      Class<R> clazz,
      RelTrait trait,
      com.google.common.base.Predicate<? super R> predicate,
      RelOptRuleOperand first,
      RelOptRuleOperand... rest) {
    return operandJ(clazz, trait, (Predicate<? super R>) predicate::apply,
        first, rest);
  }

  /**
   * Creates an operand that matches a relational expression with a given
   * list of children.
   *
   * <p>Shorthand for <code>operand(clazz, some(...))</code>.
   *
   * <p>If you wish to match a relational expression that has no children
   * (that is, a leaf node), write <code>operand(clazz, none())</code>.
   *
   * <p>If you wish to match a relational expression that has any number of
   * children, write <code>operand(clazz, any())</code>.
   *
   * @param clazz Class of relational expression to match (must not be null)
   * @param first First operand
   * @param rest Rest operands
   * @param <R> Class of relational expression to match
   * @return Operand that matches a relational expression with a given
   *   list of children
   *
   * @deprecated Use {@link RelRule.OperandBuilder#operand(Class)}
   */
  @Deprecated // to be removed before 2.0
  public static <R extends RelNode> RelOptRuleOperand operand(
      Class<R> clazz,
      RelOptRuleOperand first,
      RelOptRuleOperand... rest) {
    return operand(clazz, some(first, rest));
  }

  /**
   * Creates an operand for a converter rule.
   *
   * @param clazz    Class of relational expression to match (must not be null)
   * @param trait    Trait to match, or null to match any trait
   * @param predicate Predicate to apply to relational expression
   */
  @Deprecated // to be removed before 2.0
  protected static <R extends RelNode> ConverterRelOptRuleOperand
      convertOperand(Class<R> clazz, Predicate<? super R> predicate,
      RelTrait trait) {
    return new ConverterRelOptRuleOperand(clazz, trait, predicate);
  }

  // CHECKSTYLE: IGNORE 1
  /** @deprecated Use {@link #convertOperand(Class, Predicate, RelTrait)}. */
  @SuppressWarnings("Guava")
  @Deprecated // to be removed before 2.0
  protected static <R extends RelNode> ConverterRelOptRuleOperand
      convertOperand(Class<R> clazz,
      com.google.common.base.Predicate<? super R> predicate,
      RelTrait trait) {
    return new ConverterRelOptRuleOperand(clazz, trait, predicate::apply);
  }

  //~ Methods for creating lists of child operands ---------------------------

  /**
   * Creates a list of child operands that matches child relational
   * expressions in the order they appear.
   *
   * @param first First child operand
   * @param rest  Remaining child operands (may be empty)
   * @return List of child operands that matches child relational
   *   expressions in the order
   *
   * @deprecated Use {@link RelRule.OperandDetailBuilder#inputs}
   */
  @Deprecated // to be removed before 2.0
  public static RelOptRuleOperandChildren some(
      RelOptRuleOperand first,
      RelOptRuleOperand... rest) {
    return new RelOptRuleOperandChildren(RelOptRuleOperandChildPolicy.SOME,
        Lists.asList(first, rest));
  }


  /**
   * Creates a list of child operands that matches child relational
   * expressions in any order.
   *
   * <p>This is useful when matching a relational expression which
   * can have a variable number of children. For example, the rule to
   * eliminate empty children of a Union would have operands
   *
   * <blockquote>Operand(Union, true, Operand(Empty))</blockquote>
   *
   * <p>and given the relational expressions
   *
   * <blockquote>Union(LogicalFilter, Empty, LogicalProject)</blockquote>
   *
   * <p>would fire the rule with arguments
   *
   * <blockquote>{Union, Empty}</blockquote>
   *
   * <p>It is up to the rule to deduce the other children, or indeed the
   * position of the matched child.
   *
   * @param first First child operand
   * @param rest  Remaining child operands (may be empty)
   * @return List of child operands that matches child relational
   *   expressions in any order
   */
  @Deprecated // to be removed before 2.0
  public static RelOptRuleOperandChildren unordered(
      RelOptRuleOperand first,
      RelOptRuleOperand... rest) {
    return new RelOptRuleOperandChildren(
        RelOptRuleOperandChildPolicy.UNORDERED,
        Lists.asList(first, rest));
  }

  /**
   * Creates an empty list of child operands.
   *
   * @return Empty list of child operands
   *
   * @deprecated Use {@link RelRule.OperandDetailBuilder#noInputs()}
   */
  @Deprecated // to be removed before 2.0
  public static RelOptRuleOperandChildren none() {
    return RelOptRuleOperandChildren.LEAF_CHILDREN;
  }

  /**
   * Creates a list of child operands that signifies that the operand matches
   * any number of child relational expressions.
   *
   * @return List of child operands that signifies that the operand matches
   *   any number of child relational expressions
   *
   * @deprecated Use {@link RelRule.OperandDetailBuilder#anyInputs()}
   */
  @Deprecated // to be removed before 2.0
  public static RelOptRuleOperandChildren any() {
    return RelOptRuleOperandChildren.ANY_CHILDREN;
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Creates a flattened list of this operand and its descendants in prefix
   * order.
   *
   * @param rootOperand Root operand
   * @return Flattened list of operands
   */
  private List<RelOptRuleOperand> flattenOperands(
      @UnderInitialization RelOptRule this,
      RelOptRuleOperand rootOperand) {
    final List<RelOptRuleOperand> operandList = new ArrayList<>();

    // Flatten the operands into a list.
    rootOperand.setRule(this); //设置匹配的规则为this
    rootOperand.setParent(null); //根操作数的父操作数为null
    rootOperand.ordinalInParent = 0;
    rootOperand.ordinalInRule = operandList.size();
    operandList.add(rootOperand);
    flattenRecurse(operandList, rootOperand);
    return ImmutableList.copyOf(operandList);
  }

  /**
   * Adds the operand and its descendants to the list in prefix order.
   * 递归把子操作数平铺
   * @param operandList   Flattened list of operands
   * @param parentOperand Parent of this operand
   */
  private void flattenRecurse(
      @UnderInitialization RelOptRule this,
      List<RelOptRuleOperand> operandList,
      RelOptRuleOperand parentOperand) {
    int k = 0;
    for (RelOptRuleOperand operand : parentOperand.getChildOperands()) {
      operand.setRule(this);
      operand.setParent(parentOperand);
      operand.ordinalInParent = k++;
      operand.ordinalInRule = operandList.size();
      operandList.add(operand);
      flattenRecurse(operandList, operand);
    }
  }

  /**
   * Builds each operand's solve-order. Start with itself, then its parent, up
   * to the root, then the remaining operands in prefix order.
   */
  // 是 Calcite 优化器（尤其是 VolcanoPlanner）在规则匹配阶段能够实现高效“上下文感知拓扑匹配”的隐形功臣。
  // 当优化器在 Memo 空间中发现某一个算子（比如一个 LogicalFilter）刚好对齐了规则中的某一个非根操作数（Operand）时，它不能盲目宣布匹配成功，而是需要顺着某种“导航路线”去验证它的上游父节点、下游子节点以及旁系兄弟节点是否也全部对齐。
  // 核心任务就是：为规则中的每一个操作数，量身定制并编译出一个最符合其局部视角的“图探索导航数组”（solveOrder）
  // 为什么需要 solveOrder？
  // 假设一个规则期望捕获的拓扑结构是：Join(Filter, Project)。这里一共有 3 个操作数，拉平后的顺序（ordinalInRule）通常是：0: Join (根节点)1: Filter (左孩子)2: Project (右孩子)
  // 在优化器运行期间，匹配的触发入口是多维度的、动态的：
  // 如果优化器最先注意到一个 Join 算子：它需要以 Join 为起点，先向下看左孩子是不是 Filter，再看右孩子是不是 Project。此时它的探索路线应该是 [0, 1, 2]。
  // 如果优化器此时在 Memo 中新孵化或扫描到一个 Filter 算子：它会反向查找：“有没有哪个规则正好需要 Filter ？” 查到了这个规则。
  // 此时，优化器必须以 Filter（编号 1）为第一视角起点。它需要先向上逆流追溯它的父亲是不是 Join（编号 0），确认父亲身份后，再顺着父亲去看另一个右孩子是不是 Project（编号 2）。
  // 此时它的探索路线就必须变成 [1, 0, 2]。如果每个操作数都死板地按照固定顺序 [0, 1, 2] 去猜，优化器就无法在 $\mathcal{O}(1)`$ 的时间复杂度内通过局部节点的变动反向激活动态匹配。

  // 算法核心物理逻辑（“先直系，后旁系”）
  // 该方法为每个操作数生成 solveOrder 数组时，
  // 遵循一个铁律：以当前操作数自身为首发站，沿着亲属树逆流而上直到根节点（直系血亲优先），
  // 然后再按照前序遍历的自然顺序把剩下的其他节点补全（旁系亲属垫后）
  private static void assignSolveOrder(List<RelOptRuleOperand> operands) {
    // 外层循环：为每一个操作数定制阵列
    for (RelOptRuleOperand operand : operands) {
      // 遍历该规则下所有已经拉平的操作数。
      // 每个操作数身上都有一个独立的 int[] solveOrder 数组，数组的长度正好等于当前规则中所有操作数的总数
      operand.solveOrder = new int[operands.size()];
      int m = 0;
      // 第一阶段：填充直系血亲链（自底向上攀爬）
      for (RelOptRuleOperand o = operand; o != null; o = o.getParent()) {
        // 首发站是操作数自己。将自己的全局一维序号 o.ordinalInRule 塞入数组第一个槽位。
        operand.solveOrder[m++] = o.ordinalInRule; //从自身开始到父节点，ordinalInRule表示在规则中的序号
      }
      // 第二阶段：填充旁系血亲链（查漏补缺）
      // 算法开启一重全量循环 k（从 0 到 operands.size() - 1，即前序遍历的标准顺序）。
      for (int k = 0; k < operands.size(); k++) {
        boolean exists = false;
        for (int n = 0; n < m; n++) {
          // 检查编号 k 是不是已经在第一阶段被当作“直系血亲”填进去了（exists = true）。
          if (operand.solveOrder[n] == k) {
            exists = true;
            break;
          }
        }
        // 如果 exists == false，说明编号 k 属于当前节点的兄弟节点或者叔表亲节点（旁系分支）。
        // 将其按前序遍历的剩余自然顺序依次追加填入数组。
        if (!exists) {
          operand.solveOrder[m++] = k;
        }
      }

      // Assert: operand appears once in the sort-order.
      assert m == operands.size();
    }
  }

  /**
   * Returns the root operand of this rule.
   * 返回规则的操作数
   * @return the root operand of this rule
   */
  public RelOptRuleOperand getOperand() {
    return operand;
  }

  /**
   * Returns a flattened list of operands of this rule.
   *
   * @return flattened list of operands
   */
  public List<RelOptRuleOperand> getOperands() {
    return ImmutableList.copyOf(operands);
  }

  @Override public int hashCode() {
    // Conventionally, hashCode() and equals() should use the same
    // criteria, whereas here we only look at the description. This is
    // okay, because the planner requires all rule instances to have
    // distinct descriptions.
    return description.hashCode();
  }

  @Override public boolean equals(@Nullable Object obj) {
    return (obj instanceof RelOptRule)
        && equals((RelOptRule) obj);
  }

  /**
   * Returns whether this rule is equal to another rule.
   *
   * <p>The base implementation checks that the rules have the same class and
   * that the operands are equal; derived classes can override.
   *
   * @param that Another rule
   * @return Whether this rule is equal to another rule
   */
  @SuppressWarnings("NonOverridingEquals")
  protected boolean equals(RelOptRule that) {
    // Include operands and class in the equality criteria just in case
    // they have chosen a poor description.
    return this == that
        || this.getClass() == that.getClass()
        && this.description.equals(that.description)
        && this.operand.equals(that.operand);
  }

  /**
   * Returns whether this rule could possibly match the given operands.
   * 返回该规则是否匹配给定的操作数
   * <p>This method is an opportunity to apply side-conditions to a rule. The
   * {@link RelOptPlanner} calls this method after matching all operands of
   * the rule, and before calling {@link #onMatch(RelOptRuleCall)}.
   *
   * <p>In implementations of {@link RelOptPlanner} which may queue up a
   * matched {@link RelOptRuleCall} for a long time before calling
   * {@link #onMatch(RelOptRuleCall)}, this method is beneficial because it
   * allows the planner to discard rules earlier in the process.
   *
   * <p>The default implementation of this method returns <code>true</code>.
   * It is acceptable for any implementation of this method to give a false
   * positives, that is, to say that the rule matches the operands but have
   * {@link #onMatch(RelOptRuleCall)} subsequently not generate any
   * successors.
   *
   * <p>The following script is useful to identify rules which commonly
   * produce no successors. You should override this method for these rules:
   *
   * <blockquote>
   * <pre><code>awk '
   * /Apply rule/ {rule=$4; ruleCount[rule]++;}
   * /generated 0 successors/ {ruleMiss[rule]++;}
   * END {
   *   printf "%-30s %s %s\n", "Rule", "Fire", "Miss";
   *   for (i in ruleCount) {
   *     printf "%-30s %5d %5d\n", i, ruleCount[i], ruleMiss[i];
   *   }
   * } ' FarragoTrace.log</code></pre>
   * </blockquote>
   *
   * @param call Rule call which has been determined to match all operands of
   *             this rule
   * @return whether this RelOptRule matches a given RelOptRuleCall
   *
   * matches方法在Apache Calcite中的作用是用于决定一个规则（RelOptRule）是否适用。该方法会在规则应用之前被调用，
   * 以检查规则的所有操作数（operands）是否匹配特定的条件。它为优化器提供了一个机会，来在执行规则转换之前应用额外的限制条件。
   *
   * 具体功能：
   * 条件检查：matches方法在RelOptPlanner（优化器）检测到规则可能匹配时被调用。
   * 此时，优化器已经通过匹配操作数（operands）来确认规则能够应用。如果我们希望在规则匹配的基础上，
   * 增加一些额外的限制（例如特定的属性、状态等），可以在此方法中实现。
   * 控制规则的执行：通过在matches方法中返回true或false，你可以控制规则是否真的适用。
   * 默认实现返回true，意味着所有条件都认为规则是可以应用的。如果有更多的特定检查，这个方法可以被重写。
   * 为啥默认直接返回true：
   * 默认情况下，matches方法返回true，意味着规则的匹配完全依赖于操作数（RelOptRuleOperand）的匹配。
   * 如果用户不需要额外的限制条件，直接返回true即可。
   * 这样设计的目的是为了简单化规则的应用过程。如果没有任何特殊要求，规则只要通过了操作数匹配就可以直接应用。
   * 什么时候需要重写matches方法：
   * 当你希望在匹配操作数之外增加一些额外的限制，比如你可能希望操作数匹配之外，还要检查操作数中的某些属性是否满足特定条件，
   * 或者查询的上下文环境是否符合特定要求。
   * 例如，如果你有一个规则只想在某种查询模式下应用，可以在matches方法中实现这个逻辑，而不仅仅依赖于操作数的类型匹配。
   * 总结：
   * matches方法是一个灵活的入口，允许开发者为规则应用增加自定义逻辑。默认返回true是为了让那些不需要特殊条件的规则简化实现。
   */
  public boolean matches(RelOptRuleCall call) {
    return true;
  }

  /**
   * Receives notification about a rule match. At the time that this method is
   * called, {@link RelOptRuleCall#rels call.rels} holds the set of relational
   * expressions which match the operands to the rule; <code>
   * call.rels[0]</code> is the root expression.
   *
   * <p>Typically a rule would check that the nodes are valid matches, creates
   * a new expression, then calls back {@link RelOptRuleCall#transformTo} to
   * register the expression.
   *
   * @param call Rule call
   * @see #matches(RelOptRuleCall)
   *
   * 在Apache Calcite中的onMatch方法是规则（RelOptRule）的核心部分。它在规则匹配成功后调用，用于执行规则的实际转换逻辑。
   * 这意味着一旦优化器确认规则可以应用，并且所有操作数匹配成功，onMatch方法就会被触发，以进行具体的操作。
   *
   * 具体功能：
   * 规则转换的核心逻辑：onMatch方法是在匹配成功后实际执行转换的地方。它负责根据匹配的操作数（operands）执行特定的转换操作，
   * 将现有的RelNode（关系表达式节点）转换成一个新的节点，通常是更优化的节点。
   *
   * 生成新的表达式：规则的主要目的之一是将查询树中的一个或多个RelNode转换为更优化的形式。
   * onMatch方法将获取匹配的RelNode，并生成一个替代的、经过优化的RelNode。
   *
   * 调用时机：
   *
   * 当优化器通过matches方法确定规则适用，并且所有操作数已经匹配成功时，onMatch方法会被调用。
   * 优化器会在整个查询计划（或部分计划）中不断地调用不同的规则，直到找到一个可以应用的规则，并调用onMatch来完成转换。
   * 调用流程：
   * 规则匹配（Operands Matching）：首先，RelOptRuleOperand会定义匹配规则的条件。
   * 优化器会遍历查询计划中的RelNode，看看是否有节点符合规则要求。如果节点匹配，那么该规则可以被认为是可应用的。
   *
   * matches方法（可选）：匹配操作数之后，优化器可能会调用matches方法，检查是否有额外的条件需要满足。默认matches返回true，但可以重写此方法以增加更多的规则约束。
   *
   * onMatch方法：一旦所有匹配条件（操作数匹配和matches方法的返回值）都满足，优化器就会调用onMatch方法。此时，它会将匹配到的RelNode传递给onMatch方法进行处理。
   *
   * 生成替代节点：onMatch方法根据当前匹配的RelNode生成一个替代的RelNode，通常是更优的查询表达式。
   * 这种替代操作可能包括更改查询的执行顺序、选择不同的执行策略，或者用更优化的表达式来替换当前的表达式。
   *
   * 将新节点提交给优化器：onMatch方法将生成的替代节点通过call.transformTo(newRelNode)提交给优化器。优化器将用这个新节点替换原来的节点。
   */
  public abstract void onMatch(RelOptRuleCall call);

  /**
   * Returns the convention of the result of firing this rule, null if
   * not known.
   * 返回执行该规则后输出的调用约定
   * @return Convention of the result of firing this rule, null if
   *   not known
   */
  public @Nullable Convention getOutConvention() {
    return null;
  }

  /**
   * Returns the trait which will be modified as a result of firing this rule,
   * or null if the rule is not a converter rule.
   * 如果是converter rule，则返回执行规则后输出的特征
   * @return Trait which will be modified as a result of firing this rule,
   *   or null if the rule is not a converter rule
   */
  public @Nullable RelTrait getOutTrait() {
    return null;
  }

  /**
   * Returns the description of this rule.
   *
   * <p>It must be unique (for rules that are not equal) and must consist of
   * only the characters A-Z, a-z, 0-9, '_', '.', '(', ')', '-', ',', '[', ']', ':', ' '.
   * It must start with a letter. */
  @Override public final String toString() {
    return description;
  }

  /**
   * Converts a relation expression to a given set of traits, if it does not
   * already have those traits.
   * 把关系表达式转为toTraits特征
   * @param rel      Relational expression to convert
   * @param toTraits desired traits
   * @return a relational expression with the desired traits; never null
   */
  public static RelNode convert(RelNode rel, RelTraitSet toTraits) {
    return convert(rel.getCluster().getPlanner(), rel, toTraits);
  }

  public static RelNode convert(RelOptPlanner planner, RelNode rel, RelTraitSet toTraits) {
    RelTraitSet outTraits = rel.getTraitSet();
    for (int i = 0; i < toTraits.size(); i++) {
      RelTrait toTrait = toTraits.getTrait(i);
      if (toTrait != null) {
        outTraits = outTraits.replace(i, toTrait); //替换原始位置的特征
      }
    }

    if (rel.getTraitSet().matches(outTraits)) { //如果不变，直接返回
      return rel;
    }

    return planner.changeTraits(rel, outTraits); //通过优化器的方法修改rel的特征
  }

  /**
   * Converts one trait of a relational expression, if it does not
   * already have that trait.
   *
   * @param rel      Relational expression to convert
   * @param toTrait  Desired trait
   * @return a relational expression with the desired trait; never null
   */
  public static RelNode convert(RelNode rel, @Nullable RelTrait toTrait) {
    return convert(rel.getCluster().getPlanner(), rel, toTrait);
  }

  public static RelNode convert(RelOptPlanner planner, RelNode rel, @Nullable RelTrait toTrait) {
    RelTraitSet outTraits = rel.getTraitSet();
    if (toTrait != null) {
      outTraits = outTraits.replace(toTrait);
    }

    if (rel.getTraitSet().matches(outTraits)) {
      return rel;
    }

    return planner.changeTraits(rel, outTraits.simplify());
  }

  /**
   * Converts a list of relational expressions.
   *
   * @param rels     Relational expressions
   * @param trait   Trait to add to each relational expression
   * @return List of converted relational expressions, never null
   */
  protected static List<RelNode> convertList(List<RelNode> rels,
      final RelTrait trait) {
    return Util.transform(rels,
        rel -> convert(rel, rel.getTraitSet().replace(trait)));
  }

  /**
   * Deduces a name for a rule by taking the name of its class and returning
   * the segment after the last '.' or '$'.
   *
   * <p>Examples:
   * <ul>
   * <li>"com.foo.Bar" yields "Bar";</li>
   * <li>"com.flatten.Bar$Baz" yields "Baz";</li>
   * <li>"com.foo.Bar$1" yields "1" (which as an integer is an invalid
   * name, and writer of the rule is encouraged to give it an
   * explicit name).</li>
   * </ul>
   *
   * @param className Name of the rule's class
   * @return Last segment of the class
   */
  static String guessDescription(String className) {
    String description = className;
    int punc =
        Math.max(
            className.lastIndexOf('.'),
            className.lastIndexOf('$'));
    if (punc >= 0) {
      description = className.substring(punc + 1);
    }
    if (description.matches("[0-9]+")) {
      throw new RuntimeException("Derived description of rule class "
          + className + " is an integer, not valid. "
          + "Supply a description manually.");
    }
    return description;
  }

  /**
   * Operand to an instance of the converter rule.
   */
  // Calcite 优化器在进行跨物理流派/特质转换（Trait Conversion）时的“防追尾/防死循环护栏”。
  // 当你在不同的存储引擎（如 JdbcToEnumerable, SparkToFlink）或不同的物理特征（如把无序数据变成 Hash 分布）之间设计转换规则时，
  // 优化器全靠这个特殊的操作数（Operand）来阻断无限套娃的灾难。
  protected static class ConverterRelOptRuleOperand extends RelOptRuleOperand {
    // 直接调用了父类 RelOptRuleOperand 的构造函数，但它剥夺了开发者的部分自由度，强行写入了两个关键设定：
    // RelOptRuleOperandChildPolicy.ANY：将孩子节点的匹配策略强行锁死为 ANY。这意味着该转换算子下方无论挂载了多少个、什么类型的孩子，该操作数在第一阶段都一律放行。
    // ImmutableList.of()：将子操作数列表强行设为空集合。
    // 设计意图：因为转换算子（Converter）在物理世界中的核心职责是充当物理流派之间的“独木桥”，它本身不具备复杂的树状子拓扑。它只需要紧紧盯着它的直系孩子即可，因此其操作数结构极其简单、扁平。
    <R extends RelNode> ConverterRelOptRuleOperand(Class<R> clazz, RelTrait in,
        Predicate<? super R> predicate) {
      super(clazz, in, predicate, RelOptRuleOperandChildPolicy.ANY,
          ImmutableList.of());
    }

    @Override public boolean matches(RelNode rel) {
      // Don't apply converters to converters that operate
      // on the same RelTraitDef -- otherwise we get
      // an n^2 effect.
      // 当优化器推荐了一个算子 rel 过来时，先用 instanceof 盘查它。
      // 如果它不是转换算子，直接放行；如果它本身已经是一个 Converter 转换算子了，警报拉响，进入第二步深挖。
      if (rel instanceof Converter) {
        if (((ConverterRule) getRule()).getTraitDef()
            == ((Converter) rel).getTraitDef()) {
          return false;
        }
      }
      return super.matches(rel);
    }
  }
}
