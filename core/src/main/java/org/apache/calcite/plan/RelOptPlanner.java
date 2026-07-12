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
import org.apache.calcite.rel.metadata.CachingRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexExecutor;
import org.apache.calcite.util.CancelFlag;
import org.apache.calcite.util.trace.CalciteTrace;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A <code>RelOptPlanner</code> is a query optimizer: it transforms a relational
 * expression into a semantically equivalent relational expression, according to
 * a given set of rules and a cost model.
 */
// RelOptPlanner 是 Calcite 中的查询优化器（Query Optimizer）核心接口。
// 基本职责是：根据给定的优化规则（Rules）、代价模型（Cost Model）以及节点的物理特征（Traits），将一棵逻辑关系代数树（RelNode）转换成一棵在语义上等价、但在执行效率上最优的物理关系代数树。
// 主要有两个著名的实现类：
// HepPlanner：基于启发式（Heuristic）规则的优化器，按照固定的顺序循环应用规则，直到达到定点（不再变化）或超过最大迭代次数。
// VolcanoPlanner：基于动态规划和成本驱动（Cost-Based, CBO）的优化器，利用了著名的 Volcano/Cascades 框架模型，在庞大的等价搜索空间（Memo）中寻找代价最低的执行计划。
public interface RelOptPlanner {
  //~ Static fields/initializers ---------------------------------------------

  Logger LOGGER = CalciteTrace.getPlannerTracer();

  //~ Methods ----------------------------------------------------------------

  /**
   * Sets the root node of this query.
   * @param rel Relational expression
   */
  // 设置需要进行优化的关系代数树的根节点（Root Node）。
  // 这是优化开始前的起点。
  void setRoot(RelNode rel);

  /**
   * Returns the root node of this query.
   * @return Root node
   */
  // 获取当前正在被优化的关系代数树的根节点。
  @Nullable RelNode getRoot();

  /**
   * Registers a rel trait definition. If the {@link RelTraitDef} has already
   * been registered, does nothing.
   * @return whether the RelTraitDef was added, as per
   * {@link java.util.Collection#add}
   */
  // 向优化器注册一个关系特征定义（如：排序特征 RelCollationTraitDef、数据分布特征 Convention 等）。如果该特征已经注册，则不执行任何操作。
  boolean addRelTraitDef(RelTraitDef relTraitDef);

  /**
   * Clear all the registered RelTraitDef.
   */
  // 清空优化器中当前所有已注册的特征定义（RelTraitDef）。
  void clearRelTraitDefs();

  /**
   * Returns the list of active trait types.
   */
  // 获取当前优化器中所有处于激活状态的特征定义（RelTraitDef）列表
  List<RelTraitDef> getRelTraitDefs();

  /**
   * Removes all internal state, including all registered rules,
   * materialized views, and lattices.
   */
  // 重置优化器。清除所有的内部状态，包括已注册的规则、物化视图映射、格（Lattice）结构以及 Memo 等。
  void clear();

  /**
   * Returns the list of all registered rules.
   */
  // 返回当前优化器中所有已经注册并准备生效的转换规则（RelOptRule）列表。
  List<RelOptRule> getRules();

  /**
   * Registers a rule.
   *
   * <p>If the rule has already been registered, does nothing.
   * This method determines if the given rule is a
   * {@link org.apache.calcite.rel.convert.ConverterRule} and pass the
   * ConverterRule to all
   * {@link #addRelTraitDef(RelTraitDef) registered} RelTraitDef
   * instances.
   * @return whether the rule was added, as per
   * {@link java.util.Collection#add}
   */
  // 向优化器注册一条新规则。如果该规则是 ConverterRule（物理特征转换规则），
  // 优化器会自动将其分发到与之关联的 RelTraitDef 中，用来自动生成物理转换节点（如 EnumerableConvention 转换）。
  boolean addRule(RelOptRule rule);

  /**
   * Removes a rule.
   * @return true if the rule was present, as per
   * {@link java.util.Collection#remove(Object)}
   */
  // 从优化器中移除一条指定的规则。
  boolean removeRule(RelOptRule rule);

  /**
   * Provides the Context created when this planner was constructed.
   *
   * @return Never null; either an externally defined context, or a dummy
   * context that returns null for each requested interface
   */
  // 获取在 Planner 构造时传入的上下文环境（Context）。
  // Context 通常用于在组件之间传递一些全局配置（例如安全凭证、会话变量、各种功能开关等）。
  Context getContext();

  /**
   * Sets the exclusion filter to use for this planner. Rules which match the
   * given pattern will not be fired regardless of whether or when they are
   * added to the planner.
   * @param exclusionFilter pattern to match for exclusion; null to disable
   *                        filtering
   */
  // 设置一个基于规则描述（Description）的排除过滤器（正则表达式）。
  // 匹配上该正则的 Rule 将会被拦截，绝对不会被触发。
  // 可用于动态禁用某些特定的优化规则。
  void setRuleDescExclusionFilter(@Nullable Pattern exclusionFilter);

  /**
   * Does nothing.
   *
   * @deprecated Previously, this method installed the cancellation-checking
   * flag for this planner, but is now deprecated. Now, you should add a
   * {@link CancelFlag} to the {@link Context} passed to the constructor.
   *
   * @param cancelFlag flag which the planner should periodically check
   */
  // 以前用于为优化器安装一个轮询检查的取消标记（以应对查询超时或手动取消）。目前已废弃，Calcite 推荐改用通过 Context 传入 CancelFlag。
  @Deprecated // to be removed before 2.0
  void setCancelFlag(CancelFlag cancelFlag);

  /**
   * Changes a relational expression to an equivalent one with a different set
   * of traits.
   * @param rel Relational expression (may or may not have been registered; must
   *   not have the desired traits)
   * @param toTraits Trait set to convert the relational expression to
   * @return Relational expression with desired traits. Never null, but may be
   *   abstract
   */
  // 请求优化器将一个现有的关系表达式 rel 转换为具备另一套物理特征 toTraits 的等价节点。通常用于强制在树的某些特定位置进行物理形态转化（如从 Logical 转化为 Enumerable）。
  RelNode changeTraits(RelNode rel, RelTraitSet toTraits);

  /**
   * Negotiates an appropriate planner to deal with distributed queries. The
   * idea is that the schemas decide among themselves which has the most
   * knowledge. Right now, the local planner retains control.
   */
  // 针对分布式或多数据源查询，供优化器之间相互“协商”出一个最合适的代理优化器来接管计划。目前在默认实现中，通常由本地优化器保持对全局的控制权。
  RelOptPlanner chooseDelegate();

  /**
   * Defines a pair of relational expressions that are equivalent.
   * 添加物化视图
   * <p>Typically {@code tableRel} is a
   * {@link org.apache.calcite.rel.logical.LogicalTableScan} representing a
   * table that is a materialized view and {@code queryRel} is the SQL
   * expression that populates that view. The intention is that
   * {@code tableRel} is cheaper to evaluate and therefore if the query being
   * optimized uses (or can be rewritten to use) {@code queryRel} as a
   * sub-expression then it can be optimized by using {@code tableRel}
   * instead.
   */
  // 向优化器注册一个物化视图。包含物化视图的物理扫描节点和它对应的原始逻辑 SQL 树。Volcano 优化器会尝试利用它来进行自动的物化视图改写（Materialization Rewriting），用现成的计算结果替代高昂的物化路径。
  void addMaterialization(RelOptMaterialization materialization);

  /**
   * Returns the materializations that have been registered with the planner.
   */
  // 获取所有当前已登记的物化视图结构。
  List<RelOptMaterialization> getMaterializations();

  /**
   * Defines a lattice.
   * <p>The lattice may have materializations; it is not necessary to call
   * {@link #addMaterialization} for these; they are registered implicitly.
   */
  // 向优化器定义一个格（Lattice）结构。格可以隐式且自动地将其关联的星型模型（Star Table）物化视图注册进来。
  void addLattice(RelOptLattice lattice);

  /**
   * Retrieves a lattice, given its star table.
   */
  // 根据传入的星型元数据表（Star Table），检索并返回与其关联的格（Lattice）对象。
  @Nullable RelOptLattice getLattice(RelOptTable table);

  /**
   * Finds the most efficient expression to implement this query.
   *
   * @throws CannotPlanException if cannot find a plan
   */
  // 核心触发入口。
  // 启动优化引擎，利用注册的所有规则和代价模型进行空间搜索，最终返回一棵代价最低、可以直接交付执行的物理关系代数树。
  // 如果搜索失败或无法规划，将抛出 CannotPlanException。
  RelNode findBestExp();

  /**
   * Returns the factory that creates
   * {@link org.apache.calcite.plan.RelOptCost}s.
   */
  // 获取用来创建关系表达式代价评估器（RelOptCost）的工厂。
  RelOptCostFactory getCostFactory();

  /**
   * Computes the cost of a RelNode. In most cases, this just dispatches to
   * {@link RelMetadataQuery#getCumulativeCost}.
   * @param rel Relational expression of interest
   * @param mq Metadata query
   * @return estimated cost
   */
  // 计算特定节点 rel 的累积成本。
  // 在具体实现中，它会将此请求派发给元数据查询系统（RelMetadataQuery#getCumulativeCost），综合考虑 CPU、Memory、I/O 等多维开销。
  @Nullable RelOptCost getCost(RelNode rel, RelMetadataQuery mq);

  // CHECKSTYLE: IGNORE 2
  /** @deprecated Use {@link #getCost(RelNode, RelMetadataQuery)}
   * or, better, call {@link RelMetadataQuery#getCumulativeCost(RelNode)}. */
  // 旧版计算成本的方法，未传入元数据上下文。已废弃，请改用上面带有 RelMetadataQuery 的重载方法。
  @Deprecated // to be removed before 2.0
  @Nullable RelOptCost getCost(RelNode rel);

  /**
   * Registers a relational expression in the expression bank.
   *
   * <p>After it has been registered, you may not modify it.
   *
   * <p>The expression must not already have been registered. If you are not
   * sure whether it has been registered, call
   * {@link #ensureRegistered(RelNode, RelNode)}.
   *
   * @param rel      Relational expression to register (must not already be
   *                 registered)
   * @param equivRel Relational expression it is equivalent to (may be null)
   * @return the same expression, or an equivalent existing expression
   */
  // 将一个新生成的 RelNode 注册到优化器的内部表达式库中。如果指定了 equivRel，则当前 rel 会被强行划入与 equivRel 相同的等价类（Equivalence Class）中，代表它们返回相同的数据。注册后的节点不可被修改。
  RelNode register(
      RelNode rel,
      @Nullable RelNode equivRel);

  /**
   * Registers a relational expression if it is not already registered.
   *
   * <p>If {@code equivRel} is specified, {@code rel} is placed in the same
   * equivalence set. It is OK if {@code equivRel} has different traits;
   * {@code rel} will end up in a different subset of the same set.
   *
   * <p>It is OK if {@code rel} is a subset.
   *
   * @param rel      Relational expression to register
   * @param equivRel Relational expression it is equivalent to (may be null)
   * @return Registered relational expression
   */
  // 安全的幂等注册。如果 rel 已经注册过，直接返回已注册的节点；如果没注册过，则调用 register 执行注册，并放入 equivRel 所在的等价集中。
  RelNode ensureRegistered(RelNode rel, @Nullable RelNode equivRel);

  /**
   * Determines whether a relational expression has been registered.
   * @param rel expression to test
   * @return whether rel has been registered
   */
  // 判断传入的关系表达式 rel 是否已经被优化器注册管理。
  boolean isRegistered(RelNode rel);

  /**
   * Tells this planner that a schema exists. This is the schema's chance to
   * tell the planner about all of the special transformation rules.
   */
  // 通知优化器某个特定的 Schema 存在，这给予了该 Schema 将自己特有的转换规则或物理算子扩展规则注册进优化器的机会。
  void registerSchema(RelOptSchema schema);

  /**
   * Adds a listener to this planner.
   *
   * @param newListener new listener to be notified of events
   */
  // 优化器添加一个事件监听器，用于监听和回调 Rule 触发、节点转换、等价类合并等内部事件，常用于诊断、调试和可视化。
  void addListener(RelOptListener newListener);

  /**
   * Gives this planner a chance to register one or more
   * {@link RelMetadataProvider}s in the chain which will be used to answer
   * metadata queries.
   *
   * <p>Planners which use their own relational expressions internally
   * to represent concepts such as equivalence classes will generally need to
   * supply corresponding metadata providers.
   *
   * @param list receives planner's custom providers, if any
   */
  // 向优化器的元数据链中注册自定义的元数据提供者。
  @Deprecated // to be removed before 2.0
  void registerMetadataProviders(List<RelMetadataProvider> list);

  /**
   * Gets a timestamp for a given rel's metadata. This timestamp is used by
   * {@link CachingRelMetadataProvider} to decide whether cached metadata has
   * gone stale.
   *
   * @param rel rel of interest
   * @return timestamp of last change which might affect metadata derivation
   */
  // 获取节点元数据的时间戳，供缓存系统判断缓存的元数据是否已经过期、失效。
  @Deprecated // to be removed before 2.0
  long getRelMetadataTimestamp(RelNode rel);

  /**
   * Prunes a node from the planner.
   * <p>When a node is pruned, the related pending rule
   * calls are cancelled, and future rules will not fire.
   * This can be used to reduce the search space.
   *
   * @param rel the node to prune.
   */
  // 关系树修剪。 将一个节点 rel 从优化器中彻底剪枝。此时，该节点上所有挂起的、未执行的 Rule Call 都会被强行取消，且未来绝不会在其上触发任何新规则。该方法常用于剪掉明显不可能最优的路径分支，从而大幅度缩小优化器的搜索空间。
  void prune(RelNode rel);

  /**
   * Registers a class of RelNode. If this class of RelNode has been seen
   * before, does nothing.
   *
   * @param node Relational expression
   */
  // 在优化器中记录并注册该 RelNode 的类（Class）。如果此前已遇见过该类的节点，则不执行任何操作。
  void registerClass(RelNode node);

  /**
   * Creates an empty trait set. It contains all registered traits, and the
   * default values of any traits that have them.
   *
   * <p>The empty trait set acts as the prototype (a kind of factory) for all
   * subsequently created trait sets.
   *
   * @return Empty trait set
   */
  // 创建一个空的物理特征集合（RelTraitSet）。它包含了所有当前已注册特征的默认初值。这个空集合常作为原型（Prototype）工厂，用于派生和组合出其他复杂的特征集。
  RelTraitSet emptyTraitSet();

  /** Sets the object that can execute scalar expressions. */
  void setExecutor(@Nullable RexExecutor executor);

  /** Returns the executor used to evaluate constant expressions. */
  @Nullable RexExecutor getExecutor();

  /** Called when a relational expression is copied to a similar expression. */
  void onCopy(RelNode rel, RelNode newRel);

  // CHECKSTYLE: IGNORE 1
  /** @deprecated Use {@link RexExecutor} */
  @Deprecated // to be removed before 2.0
  interface Executor extends RexExecutor {
  }

  /**
   * Thrown by {@link org.apache.calcite.plan.RelOptPlanner#findBestExp()}.
   */
  class CannotPlanException extends RuntimeException {
    public CannotPlanException(String message) {
      super(message);
    }
  }
}
