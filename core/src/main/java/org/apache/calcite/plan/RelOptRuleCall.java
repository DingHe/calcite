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
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.hint.Hintable;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.trace.CalciteTrace;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A <code>RelOptRuleCall</code> is an invocation of a {@link RelOptRule} with a
 * set of {@link RelNode relational expression}s as arguments.
 */
// RelOptRuleCall 是 Apache Calcite 优化器框架中用于驱动和上下文管理的核心抽象类。
// 在理解了 RelOptRuleOperand 如何定义匹配模式之后，我们可以把 RelOptRuleCall 看作是模式匹配成功后诞生的“战利品容器”与“执行通行证”。
// 在 Calcite 的代价模型优化器（VolcanoPlanner）或启发式优化器（HepPlanner）运行期间：
// 它是匹配成功的“物证上下文”：当优化器发现一个规则（RelOptRule）的算子模式完全匹配上了当前的局部算子树时，它就会实例化一个 RelOptRuleCall。这个对象内部精确地平铺绑定了究竟是哪几个具体的 RelNode 节点分别对齐并满足了操作数的要求。
// 它是规则执行（onMatch）的入参：开发者在编写自定义优化规则的 onMatch(RelOptRuleCall call) 方法时，就是通过这个 call 来安全地剥离出匹配到的各个算子节点（如通过 call.rel(0) 拿到 Join 算子，call.rel(1) 拿到 Filter 算子），并对其进行逻辑转换。
// 它是新算子树诞生并重回优化器的安全通道：它提供了一组 transformTo(...) 方法。当规则利用变换生成了性能更好、等价的新算子树（如 NewJoin）后，需要通过此方法把新树安全地塞回优化器的核心大池子（Memo 容器）中。它在底层会自动接管物理特征（Traits）的向上传播以及 SQL Hint 提示的无缝拷贝。

public abstract class RelOptRuleCall {
  //~ Static fields/initializers ---------------------------------------------

  protected static final Logger LOGGER = CalciteTrace.getPlannerTracer();

  /**
   * Generator for {@link #id} values.
   */
  // 全局静态计数器。每诞生一个 Call 实例，该值自增 1，用于发放唯一的 id。
  private static int nextId = 0;

  //~ Instance fields --------------------------------------------------------
  // 当前 Call 的唯一标识 ID。在规则触发、冲突防重和全局性能排查日志中极其重要。
  public final int id;
  // 模式树的根操作数。当前优化规则最外层的那个操作数节点。
  protected final RelOptRuleOperand operand0;
  // 特殊算子子节点映射表。
  // 如果操作数使用了 childPolicy = ANY（意味着下层有未知个、类型任意的子节点），优化器在此用 Map 记录这些通过 ANY 吞进来的实际子算子集合。
  protected Map<RelNode, List<RelNode>> nodeInputs;//  A map that associates each RelNode (relational expression node) with its list of inputs
  // 触发该 Call 的优化规则本身。
  public final RelOptRule rule; //  representing the specific rule that this call is invoking. Rules define transformations on parts of the relational tree.
  // 最核心的战利品数组。
  // 按索引平铺存放当前匹配成功的所有 RelNode 算子实例。rels[0] 永远是这棵匹配子树的根算子。
  public final RelNode[] rels; //  the relational expression tree that match the rule’s pattern
  // 指向当前正在工作的优化器实例（如 VolcanoPlanner）。
  private final RelOptPlanner planner;
  // 记录匹配子树根节点（rels[0]）的所有父算子节点。
  // 在某些复杂的等价图结构和流向控制中需要用到。
  private final @Nullable List<RelNode> parents; //A list of parent RelNode objects associated with the first relational expression in rels

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a RelOptRuleCall.
   *
   * @param planner      Planner
   * @param operand      Root operand
   * @param rels         Array of relational expressions which matched each
   *                     operand
   * @param nodeInputs   For each node which matched with
   *                     {@code matchAnyChildren}=true, a list of the node's
   *                     inputs
   * @param parents      list of parent RelNodes corresponding to the first
   *                     relational expression in the array argument, if known;
   *                     otherwise, null
   */
  protected RelOptRuleCall(
      RelOptPlanner planner,
      RelOptRuleOperand operand,
      RelNode[] rels,
      Map<RelNode, List<RelNode>> nodeInputs,  //关系表达式跟它输入之间的关系
      @Nullable List<RelNode> parents) {
    this.id = nextId++;
    this.planner = planner;
    this.operand0 = operand;
    this.nodeInputs = nodeInputs;
    this.rule = operand.getRule();
    this.rels = rels;
    this.parents = parents;
    assert rels.length == rule.operands.size();
  }

  protected RelOptRuleCall(
      RelOptPlanner planner,
      RelOptRuleOperand operand,
      RelNode[] rels,
      Map<RelNode, List<RelNode>> nodeInputs) {
    this(planner, operand, rels, nodeInputs, null);
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Returns the root operand matched by this rule.
   *
   * @return root operand
   */
  public RelOptRuleOperand getOperand0() {
    return operand0;
  }

  /**
   * Returns the invoked planner rule.
   *
   * @return planner rule
   */
  public RelOptRule getRule() {
    return rule;
  }

  /**
   * Returns a list of matched relational expressions.
   *
   * @return matched relational expressions
   * @deprecated Use {@link #getRelList()} or {@link #rel(int)}
   */
  @Deprecated // to be removed before 2.0
  public RelNode[] getRels() {
    return rels;
  }

  /**
   * Returns a list of matched relational expressions.
   *
   * @return matched relational expressions
   * @see #rel(int)
   */
  public List<RelNode> getRelList() {
    return ImmutableList.copyOf(rels);
  }

  /**
   * Retrieves the {@code ordinal}th matched relational expression. This
   * corresponds to the {@code ordinal}th operand of the rule.
   *
   * @param ordinal Ordinal
   * @param <T>     Type
   * @return Relational expression
   */
  public <T extends RelNode> T rel(int ordinal) {
    //noinspection unchecked
    return (T) rels[ordinal];
  }

  /**
   * Returns the children of a given relational expression node matched in a
   * rule.
   *
   * <p>If the policy of the operand which caused the match is not
   * {@link org.apache.calcite.plan.RelOptRuleOperandChildPolicy#ANY},
   * the children will have their
   * own operands and therefore be easily available in the array returned by
   * the {@link #getRelList()} method, so this method returns null.
   *
   * <p>This method is for
   * {@link org.apache.calcite.plan.RelOptRuleOperandChildPolicy#ANY},
   * which is generally used when a node can have a variable number of
   * children, and hence where the matched children are not retrievable by any
   * other means.
   *
   * <p>Warning: it produces wrong result for {@code unordered(...)} case.
   *
   * @param rel Relational expression
   * @return Children of relational expression
   */
  public @Nullable List<RelNode> getChildRels(RelNode rel) {
    return nodeInputs.get(rel);
  }

  /** Assigns the input relational expressions of a given relational expression,
   * as seen by this particular call. Is only called when the operand is
   * {@link RelRule.OperandDetailBuilder#anyInputs() any}. */
  protected void setChildRels(RelNode rel, List<RelNode> inputs) {
    if (nodeInputs.isEmpty()) {
      nodeInputs = new HashMap<>();
    }
    nodeInputs.put(rel, inputs);
  }

  /**
   * Returns the planner.
   *
   * @return planner
   */
  public RelOptPlanner getPlanner() {
    return planner;
  }

  /**
   * Determines whether the rule is excluded by any root node hint.
   * @return true iff rule should be excluded
   */
  // Calcite 优化器中用于实现 SQL Hint（提示）强行干预/熔断优化规则 的核心审查卡点。
  // SELECT /*+ DISABLE_REUSE_FIXED_LOOP */ * FROM emp; -- 显式禁用某条优化规则
  // 当优化器成功匹配了一组算子并创建了 RelOptRuleCall 上下文后，在真正把控制权交给你写的 onMatch 逻辑之前，它会默默地调用 isRuleExcluded() 来做一次排他性体检。
  // 如果这个方法返回了 true，整个优化规则就会被无情地就地熔断（直接跳过，不执行转换）。
  public boolean isRuleExcluded() {
    //  1. 遍历当前规则Call所匹配到的所有关系算子
    for (RelNode rel : rels) {
      // 2. 检查当前算子是否具备持有 Hint 的资格
      if (!(rel instanceof Hintable)) {
        continue;
      }
      //  3. 剥离出集群全局的 Hint 策略控制器，深入盘查该算子上的 Hint 是否拉黑了当前的 rule
      if (rel.getCluster()
              .getHintStrategies()
              .isRuleExcluded((Hintable) rel, rule)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the current RelMetadataQuery
   * to be used for instance by
   * {@link RelOptRule#onMatch(RelOptRuleCall)}.
   */
  public RelMetadataQuery getMetadataQuery() {
    return rel(0).getCluster().getMetadataQuery();
  }

  /**
   * Returns a list of parents of the first relational expression.
   */
  public @Nullable List<RelNode> getParents() {
    return parents;
  }

  /**
   * Registers that a rule has produced an equivalent relational expression.
   * <p>Called by the rule whenever it finds a match. The implementation of
   * this method guarantees that the original relational expression (that is,
   * <code>this.rels[0]</code>) has its traits propagated to the new
   * relational expression (<code>rel</code>) and its unregistered children.
   * Any trait not specifically set in the RelTraitSet returned by <code>
   * rel.getTraits()</code> will be copied from <code>
   * this.rels[0].getTraitSet()</code>.
   *
   * <p>The hints of the root relational expression of
   * the rule call(<code>this.rels[0]</code>)
   * are copied to the new relational expression(<code>rel</code>)
   * with specified handler {@code handler}.
   *
   * @param rel    跟call.rels[0]的相等的新的根关系表达式 Relational expression equivalent to the root relational
   *                expression of the rule call, {@code call.rels(0)}
   * @param equiv   其他相等关系映射 Map of other equivalences
   * @param handler Handler to customize the relational expression that registers
   *                into the planner, the first parameter is the root relational expression
   *                and the second parameter is the new relational expression
   */
  // transformTo 是整个 Apache Calcite 优化器框架中最核心、最具威力的接口。
  // 当我们在自定义规则（RelOptRule）的 onMatch(RelOptRuleCall call) 方法中完成了逻辑推导，并创造出了一个性能更好、等价的新算子树时，我们并不能直接替换原有算子，而是必须通过调用该方法，将新算子树的根节点 rel 正式移交给优化器引擎。
  // 该方法显式承诺：任何在新算子 rel.getTraits() 中没有被明确指定的特征，
  // 底层的优化器引擎都会雷打不动地从老算子的根节点（this.rels[0].getTraitSet()）中强行拷贝一份丢给新算子和它那些尚未注册的子节点。
  // 这保证了逻辑转换不会导致物理计划的特征丢失。
  // 等价关系注册与代价空间开辟 (Memo Registration)
  // 这是 Volcano 优化器的核心逻辑。调用该方法后，优化器会将新树 rel 塞进核心的 Memo（备忘录群组） 中，
  // 将其与老算子 this.rels[0] 归为同一个 等价集合（Equivalence Set / RelSet）。
  // 随后，优化器会立刻对新算子树展开递归的代价（Cost）计算。如果新树的整体 CPU/IO 代价低于老树，在最终生成物理执行计划时，老树对应的分支就会被无情抛弃，新树成功上位。
  // 在 HepPlanner（启发式优化器）的实现中：它的 transformTo 非常直接，发现等价新树后，会直接在原有的算子树拓扑结构上执行“伤筋动骨”的就地无条件替换。
  // 在 VolcanoPlanner（代价模型优化器）的实现中：它的 transformTo 绝不会破坏老树。
  // 它会在底层的图结构（Memo）中并存两条路径，让新老算子同台竞技，直到最后通过动态规划（Dynamic Programming）算法选出代价最低的那条完美路径。
  public abstract void transformTo(
      // 含义：新生成的、与原本匹配子树的根节点（即 this.rels[0]）在语义上完全等价的全新关系表达式算子树的根。
      RelNode rel,
      // 其他局部节点的等价映射表。
      // 有时候一个优化规则非常大，不仅把根节点改了，还顺便把子树内部的某些中间节点也顺手优化成了别的新算子。
      // 你可以通过这个 Map 告诉优化器：“除了老根和新根等价外，老内部节点 A 和新内部节点 B 也是等价的”，帮助优化器更精细地做 Memo 剪枝。
      Map<RelNode, RelNode> equiv,
      // SQL Hint（提示）的传播器。
      // 用于定制如何将老算子身上的 Hint 复制、过滤并播种到新算子树上。
      RelHintsPropagator handler);

  /**
   * Registers that a rule has produced an equivalent relational expression,
   * with specified equivalences.
   *
   * <p>The hints are copied with filter strategies from
   * the root relational expression of the rule call(<code>this.rels[0]</code>)
   * to the new relational expression(<code>rel</code>).
   *
   * @param rel   Relational expression equivalent to the root relational
   *              expression of the rule call, {@code call.rels(0)}
   * @param equiv Map of other equivalences
   */
  // 在自定义优化规则时，大部分场景下我们只需要处理两件事：告诉优化器新旧算子的核心等价关系，以及让框架用默认的方式把 SQL Hint（提示）从旧算子复制到新算子。
  // 这个方法通过固定委派机制，帮开发者省去了手动配置 Hint 传播器的麻烦。
  public void transformTo(RelNode rel, Map<RelNode, RelNode> equiv) {
    transformTo(rel, equiv, RelOptUtil::propagateRelHints);
  }

  /**
   * Registers that a rule has produced an equivalent relational expression,
   * but no other equivalences.
   *
   * <p>The hints are copied with filter strategies from
   * the root relational expression of the rule call(<code>this.rels[0]</code>)
   * to the new relational expression(<code>rel</code>).
   *
   * @param rel Relational expression equivalent to the root relational
   *            expression of the rule call, {@code call.rels(0)}
   */
  public final void transformTo(RelNode rel) {
    transformTo(rel, ImmutableMap.of());
  }

  /**
   * Registers that a rule has produced an equivalent relational expression,
   * but no other equivalences.
   *
   * <p>The hints of the root relational expression of
   * the rule call(<code>this.rels[0]</code>)
   * are copied to the new relational expression(<code>rel</code>)
   * with specified handler {@code handler}.
   *
   * @param rel     Relational expression equivalent to the root relational
   *                expression of the rule call, {@code call.rels(0)}
   * @param handler Handler to customize the relational expression that registers
   *                into the planner, the first parameter is the root relational expression
   *                and the second parameter is the new relational expression
   *
   */
  public final void transformTo(RelNode rel, RelHintsPropagator handler) {
    transformTo(rel, ImmutableMap.of(), handler);
  }

  /** Creates a {@link org.apache.calcite.tools.RelBuilder} to be used by
   * code within the call. The {@link RelOptRule#relBuilderFactory} argument contains policies
   * such as what implementation of {@link Filter} to create. */
  // 核心使命是：为当前规则的转换环境，快速孵化出一个开箱即用的算子流式构建器（RelBuilder）。
  // 1. rel(0) 拿到当前匹配子树的根逻辑算子，并提取出它所在的全局集群环境（RelOptCluster）
  // 2. 委派当前规则自带的构建工厂（relBuilderFactory），在当前集群环境下创建 RelBuilder 实例
  public RelBuilder builder() {
    return rule.relBuilderFactory.create(rel(0).getCluster(), null);
  }
}
