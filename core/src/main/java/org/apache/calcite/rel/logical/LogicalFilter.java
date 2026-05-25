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
package org.apache.calcite.rel.logical;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelDistributionTraitDef;
import org.apache.calcite.rel.RelInput;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttle;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.RelMdCollation;
import org.apache.calcite.rel.metadata.RelMdDistribution;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexNode;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Sub-class of {@link org.apache.calcite.rel.core.Filter}
 * not targeted at any particular engine or calling convention.
 */
// Filter 是一个过滤抽象框架。而 LogicalFilter 则是它的纯逻辑形态实现，属于 Convention.NONE（无物理执行流派）
// 核心职责是：
// 解除物理绑定：它仅在逻辑层代表 SQL 中的 WHERE 或 HAVING 的过滤行为。在这个阶段，它完全不关心这些数据是在内存中过滤（Enumerable 模式），还是被转换成底层的物理 SQL（JDBC 模式）。
// 支撑子查询与关联变量（Correlation）：作为逻辑层节点，它专门设计了对 CorrelationId 的支持，能够完美表达和承载关联子查询（Correlated Subquery，如 WHERE EXISTS (SELECT 1 FROM t2 WHERE t2.id = t1.id)）中的跨层变量传递。
// 提供改写跳板：它是优化器（Planner）进行逻辑等价改写（如谓词下推、过滤器合并）的基础媒介。当所有逻辑层改写完毕后，它会被 Rule 转换成特定引擎的物理过滤节点。
public final class LogicalFilter extends Filter {
  // 当前过滤算子所定义或引入的关联变量（Correlation ID）集合。
  // 在复杂的关联子查询中，外层查询的某一行数据需要作为变量传递给内层子查询。这个属性就是用来记录当前 Filter 节点向外暴露、或供内部表达式引用的关联变量 ID。如果该集合不为空，说明当前过滤逻辑涉及到了分布式或跨层的数据依赖。
  private final ImmutableSet<CorrelationId> variablesSet;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a LogicalFilter.
   *
   * <p>Use {@link #create} unless you know what you're doing.
   *
   * @param cluster   Cluster that this relational expression belongs to
   * @param child     Input relational expression
   * @param condition Boolean expression which determines whether a row is
   *                  allowed to pass
   * @param variablesSet Correlation variables set by this relational expression
   *                     to be used by nested expressions
   */
  public LogicalFilter(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      List<RelHint> hints,
      RelNode child,
      RexNode condition,
      ImmutableSet<CorrelationId> variablesSet) {
    super(cluster, traitSet, hints, child, condition);
    this.variablesSet = Objects.requireNonNull(variablesSet, "variablesSet");
  }

  /**
   * Creates a LogicalFilter.
   *
   * <p>Use {@link #create} unless you know what you're doing.
   *
   * @param cluster   Cluster that this relational expression belongs to
   * @param child     Input relational expression
   * @param condition Boolean expression which determines whether a row is
   *                  allowed to pass
   * @param variablesSet Correlation variables set by this relational expression
   *                     to be used by nested expressions
   */
  public LogicalFilter(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode child,
      RexNode condition,
      ImmutableSet<CorrelationId> variablesSet) {
    this(cluster, traitSet, ImmutableList.of(), child, condition, variablesSet);
  }

  @Deprecated // to be removed before 2.0
  public LogicalFilter(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode child,
      RexNode condition) {
    this(cluster, traitSet, child, condition, ImmutableSet.of());
  }

  @Deprecated // to be removed before 2.0
  public LogicalFilter(
      RelOptCluster cluster,
      RelNode child,
      RexNode condition) {
    this(cluster, cluster.traitSetOf(Convention.NONE), child, condition,
        ImmutableSet.of());
  }

  /**
   * Creates a LogicalFilter by parsing serialized output.
   */
  public LogicalFilter(RelInput input) {
    super(input);
    this.variablesSet = ImmutableSet.of();
  }

  /** Creates a LogicalFilter. */
  public static LogicalFilter create(final RelNode input, RexNode condition) {
    return create(input, condition, ImmutableSet.of());
  }

  /** Creates a LogicalFilter. */
  public static LogicalFilter create(final RelNode input, RexNode condition,
      ImmutableSet<CorrelationId> variablesSet) {
    final RelOptCluster cluster = input.getCluster();
    final RelMetadataQuery mq = cluster.getMetadataQuery();
    final RelTraitSet traitSet = cluster.traitSetOf(Convention.NONE)
        .replaceIfs(RelCollationTraitDef.INSTANCE,
            () -> RelMdCollation.filter(mq, input))
        .replaceIf(RelDistributionTraitDef.INSTANCE,
            () -> RelMdDistribution.filter(mq, input));
    return new LogicalFilter(cluster, traitSet, input, condition, variablesSet);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public Set<CorrelationId> getVariablesSet() {
    return variablesSet;
  }

  @Override public LogicalFilter copy(RelTraitSet traitSet, RelNode input,
      RexNode condition) {
    assert traitSet.containsIfApplicable(Convention.NONE);
    return new LogicalFilter(getCluster(), traitSet, hints, input, condition,
        variablesSet);
  }

  @Override public RelNode accept(RelShuttle shuttle) {
    return shuttle.visit(this);
  }

  @Override public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .itemIf("variablesSet", variablesSet, !variablesSet.isEmpty());
  }

  @Override public boolean deepEquals(@Nullable Object obj) {
    return deepEquals0(obj)
        && variablesSet.equals(((LogicalFilter) obj).variablesSet);
  }

  @Override public int deepHashCode() {
    return Objects.hash(deepHashCode0(), variablesSet);
  }

  @Override public RelNode withHints(List<RelHint> hintList) {
    return new LogicalFilter(getCluster(), traitSet, hintList, input, condition, variablesSet);
  }
}
