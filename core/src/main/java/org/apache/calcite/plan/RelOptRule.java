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
 *  Rule是把一个表达式转为另一个，RelOptReleOperand是决定Rule是否能使用，通过onMatch方法调用转换规则
 * <p>The optimizer figures out which rules are applicable, then calls
 * {@link #onMatch} on each of them.
 */
public abstract class RelOptRule {
  //~ Static fields/initializers ---------------------------------------------

  //~ Instance fields --------------------------------------------------------

  /**
   * Description of rule, must be unique within planner. Default is the name
   * of the class sans package name, but derived classes are encouraged to
   * override.该规则的描述信息，通常用于日志和调试
   */
  protected final String description;

  /**
   * Root of operand tree.  它定义了规则应用的条件和结构，帮助优化器高效识别和优化查询计划的特定部分，决定规则是否能使用
   */
  private final RelOptRuleOperand operand;

  /** Factory for a builder for relational expressions.
   *
   * <p>The actual builder is available via {@link RelOptRuleCall#builder()}. */
  public final RelBuilderFactory relBuilderFactory;

  /**
   * Flattened list of operands.把RelOptRuleOperand平铺
   */
  public final List<RelOptRuleOperand> operands;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a rule.
   *
   * @param operand root operand, must not be null
   */
  protected RelOptRule(RelOptRuleOperand operand) {
    this(operand, RelFactories.LOGICAL_BUILDER, null); //RelFactories.LOGICAL_BUILDER返回RelBuilderFactory工厂
  }

  /**
   * Creates a rule with an explicit description.
   *
   * @param operand     root operand, must not be null
   * @param description Description, or null to guess description
   */
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
  protected RelOptRule(RelOptRuleOperand operand,
      RelBuilderFactory relBuilderFactory, @Nullable String description) {
    this.operand = Objects.requireNonNull(operand, "operand");
    this.relBuilderFactory = Objects.requireNonNull(relBuilderFactory, "relBuilderFactory");
    if (description == null) {
      description = guessDescription(getClass().getName());
    }
    if (!description.matches("[A-Za-z][-A-Za-z0-9_.(),\\[\\]\\s:]*")) {
      throw new RuntimeException("Rule description '" + description
          + "' is not valid");
    }
    this.description = description;
    this.operands = flattenOperands(operand);
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
  @Deprecated // to be removed before 2.0
  public static <R extends RelNode> RelOptRuleOperand operand(
      Class<R> clazz,
      RelOptRuleOperandChildren operandList) {
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

   * 具体的操作流程：
   * 对于每一个操作数，solveOrder 首先记录该操作数自身和其所有父操作数的编号。
   * 接着，将其他未出现的操作数编号按顺序补充到 solveOrder 数组中。
   * 最终，每个操作数的 solveOrder 数组都包含了匹配和求解的完整顺序，从当前操作数开始，再逐步解决相关联的其他操作数。
   * 这样做的原因是，在优化规则匹配的过程中，可能需要逐步匹配多个操作数，并确保每个操作数都按照正确的上下文关系来求解，从而确保规则匹配和转换的正确性
   */
  private static void assignSolveOrder(List<RelOptRuleOperand> operands) {
    for (RelOptRuleOperand operand : operands) {
      operand.solveOrder = new int[operands.size()];
      int m = 0;
      for (RelOptRuleOperand o = operand; o != null; o = o.getParent()) {
        operand.solveOrder[m++] = o.ordinalInRule; //从自身开始到父节点，ordinalInRule表示在规则中的序号
      }
      for (int k = 0; k < operands.size(); k++) {
        boolean exists = false;
        for (int n = 0; n < m; n++) {   //主要查找其他操作数是否已经排序了
          if (operand.solveOrder[n] == k) {
            exists = true;
            break;
          }
        }
        if (!exists) {  //如果不排序，则按顺序复制，也就是原来的顺序赋值
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

  /** 转换规则定义的操作数
   * Operand to an instance of the converter rule.
   */
  protected static class ConverterRelOptRuleOperand extends RelOptRuleOperand {
    <R extends RelNode> ConverterRelOptRuleOperand(Class<R> clazz, RelTrait in,
        Predicate<? super R> predicate) {
      super(clazz, in, predicate, RelOptRuleOperandChildPolicy.ANY,
          ImmutableList.of());
    }

    @Override public boolean matches(RelNode rel) {
      // Don't apply converters to converters that operate
      // on the same RelTraitDef -- otherwise we get
      // an n^2 effect.
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
