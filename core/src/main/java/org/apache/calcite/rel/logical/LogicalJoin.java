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
import org.apache.calcite.rel.RelInput;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttle;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexNode;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Sub-class of {@link org.apache.calcite.rel.core.Join}
 * not targeted at any particular engine or calling convention.
 *
 * <p>Some rules:
 *
 * <ul>
 * <li>{@link org.apache.calcite.rel.rules.JoinExtractFilterRule} converts an
 * {@link LogicalJoin inner join} to a {@link LogicalFilter filter} on top of a
 * {@link LogicalJoin cartesian inner join}.
 *
 * <li>{@code net.sf.farrago.fennel.rel.FennelCartesianJoinRule}
 * implements a LogicalJoin as a cartesian product.
 *
 * </ul>
 */
// Join 是一个连接操作的抽象框架。而 LogicalJoin 则是它的纯逻辑形态实现，属于 Convention.NONE（无物理执行流派）。
// 解除物理引擎绑定：它仅在逻辑层代表 SQL 中的各种连接行为（如 INNER JOIN、LEFT JOIN 等）。此时，它完全不关心后续会通过哪种具体的物理算法来实现（例如是走内存的 EnumerableHashJoin、EnumerableNestedLoopJoin，还是下推给远程数据库生成物理 JDBC JOIN）。
// 连接优化規則的操纵舞台：它是各种等价改写规则（如连接重排序 JoinCommuteRule、过滤条件上提/下推规则、连接转化为半连接规则等）直接操作的核心对象。
// 状态控制与防死循环机制：它通过内部特有的状态属性，专门解决了半连接（Semi-Join）等非局部性（Non-local）优化规则在反复匹配时可能导致的死循环火花问题。
public final class LogicalJoin extends Join {
  //~ Instance fields --------------------------------------------------------

  // NOTE jvs 14-Mar-2006:  Normally we don't use state like this
  // to control rule firing, but due to the non-local nature of
  // semijoin optimizations, it's pretty much required.
  // 指示当前逻辑连接节点是否已经尝试或完成过半连接（Semi-Join）的改写优化。
  // 设计内幕：半连接优化（例如将一个普通的 Inner Join 根据子查询特征转换为 Semi-Join）往往不是局部的，它需要审查两张甚至多张表的关联。优化器规则（如 JoinAddRedundantSemiJoinRule）在匹配并改写出新节点后，为了防止后续其他规则重复匹配、陷入无限死循环，会将此属性设为 true。
  // 它像一个“断路器”，在复杂的图空间搜索中保护优化器不至于崩溃。
  private final boolean semiJoinDone;
  // 当前连接算子持有的系统级隐藏列/默认字段列表。
  private final ImmutableList<RelDataTypeField> systemFieldList;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a LogicalJoin.
   *
   * <p>Use {@link #create} unless you know what you're doing.
   *
   * @param cluster          Cluster
   * @param traitSet         Trait set
   * @param hints            Hints
   * @param left             Left input
   * @param right            Right input
   * @param condition        Join condition
   * @param joinType         Join type
   * @param variablesSet     Set of variables that are set by the
   *                         LHS and used by the RHS and are not available to
   *                         nodes above this LogicalJoin in the tree
   * @param semiJoinDone     Whether this join has been translated to a
   *                         semi-join
   * @param systemFieldList  List of system fields that will be prefixed to
   *                         output row type; typically empty but must not be
   *                         null
   * @see #isSemiJoinDone()
   */
  public LogicalJoin(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      List<RelHint> hints,
      RelNode left, //左节点
      RelNode right, //右节点
      RexNode condition, //关联条件
      Set<CorrelationId> variablesSet,
      JoinRelType joinType,
      boolean semiJoinDone,
      ImmutableList<RelDataTypeField> systemFieldList) {
    super(cluster, traitSet, hints, left, right, condition, variablesSet, joinType);
    this.semiJoinDone = semiJoinDone;
    this.systemFieldList = requireNonNull(systemFieldList, "systemFieldList");
  }

  @Deprecated // to be removed before 2.0
  public LogicalJoin(RelOptCluster cluster, RelTraitSet traitSet,
      RelNode left, RelNode right, RexNode condition, Set<CorrelationId> variablesSet,
      JoinRelType joinType, boolean semiJoinDone,
      ImmutableList<RelDataTypeField> systemFieldList) {
    this(cluster, traitSet, ImmutableList.of(), left, right, condition,
        variablesSet, joinType, semiJoinDone, systemFieldList);
  }

  @Deprecated // to be removed before 2.0
  public LogicalJoin(RelOptCluster cluster, RelTraitSet traitSet, RelNode left,
      RelNode right, RexNode condition, JoinRelType joinType,
      Set<String> variablesStopped, boolean semiJoinDone,
      ImmutableList<RelDataTypeField> systemFieldList) {
    this(cluster, traitSet, ImmutableList.of(), left, right, condition,
        CorrelationId.setOf(variablesStopped), joinType, semiJoinDone,
        systemFieldList);
  }

  @Deprecated // to be removed before 2.0
  public LogicalJoin(RelOptCluster cluster, RelNode left, RelNode right,
      RexNode condition, JoinRelType joinType, Set<String> variablesStopped) {
    this(cluster, cluster.traitSetOf(Convention.NONE), ImmutableList.of(),
        left, right, condition, CorrelationId.setOf(variablesStopped),
        joinType, false, ImmutableList.of());
  }

  @Deprecated // to be removed before 2.0
  public LogicalJoin(RelOptCluster cluster, RelNode left, RelNode right,
      RexNode condition, JoinRelType joinType, Set<String> variablesStopped,
      boolean semiJoinDone, ImmutableList<RelDataTypeField> systemFieldList) {
    this(cluster, cluster.traitSetOf(Convention.NONE), ImmutableList.of(),
        left, right, condition, CorrelationId.setOf(variablesStopped), joinType,
        semiJoinDone, systemFieldList);
  }

  /**
   * Creates a LogicalJoin by parsing serialized output.
   */
  public LogicalJoin(RelInput input) {
    this(input.getCluster(), input.getCluster().traitSetOf(Convention.NONE),
        new ArrayList<>(),
        input.getInputs().get(0), input.getInputs().get(1),
        requireNonNull(input.getExpression("condition"), "condition"),
        ImmutableSet.of(),
        requireNonNull(input.getEnum("joinType", JoinRelType.class), "joinType"),
        false,
        ImmutableList.of());
  }

  /** Creates a LogicalJoin. */
  public static LogicalJoin create(RelNode left, RelNode right, List<RelHint> hints,
      RexNode condition, Set<CorrelationId> variablesSet, JoinRelType joinType) {
    return create(left, right, hints, condition, variablesSet, joinType, false,
        ImmutableList.of());
  }

  /** Creates a LogicalJoin, flagged with whether it has been translated to a
   * semi-join. */
  public static LogicalJoin create(RelNode left, RelNode right, List<RelHint> hints,
      RexNode condition, Set<CorrelationId> variablesSet, JoinRelType joinType,
      boolean semiJoinDone, ImmutableList<RelDataTypeField> systemFieldList) {
    final RelOptCluster cluster = left.getCluster();
    final RelTraitSet traitSet = cluster.traitSetOf(Convention.NONE);
    return new LogicalJoin(cluster, traitSet, hints, left, right, condition,
        variablesSet, joinType, semiJoinDone, systemFieldList);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public LogicalJoin copy(RelTraitSet traitSet, RexNode conditionExpr,
      RelNode left, RelNode right, JoinRelType joinType, boolean semiJoinDone) {
    assert traitSet.containsIfApplicable(Convention.NONE);
    return new LogicalJoin(getCluster(),
        getCluster().traitSetOf(Convention.NONE), hints, left, right, conditionExpr,
        variablesSet, joinType, semiJoinDone, systemFieldList);
  }

  @Override public RelNode accept(RelShuttle shuttle) {
    return shuttle.visit(this);
  }

  @Override public RelWriter explainTerms(RelWriter pw) {
    // Don't ever print semiJoinDone=false. This way, we
    // don't clutter things up in optimizers that don't use semi-joins.
    return super.explainTerms(pw)
        .itemIf("semiJoinDone", semiJoinDone, semiJoinDone);
  }

  @Override public boolean deepEquals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    return deepEquals0(obj)
        && semiJoinDone == ((LogicalJoin) obj).semiJoinDone
        && systemFieldList.equals(((LogicalJoin) obj).systemFieldList);
  }

  @Override public int deepHashCode() {
    return Objects.hash(deepHashCode0(), semiJoinDone, systemFieldList);
  }

  @Override public boolean isSemiJoinDone() {
    return semiJoinDone;
  }

  @Override public List<RelDataTypeField> getSystemFieldList() {
    return systemFieldList;
  }

  @Override public RelNode withHints(List<RelHint> hintList) {
    return new LogicalJoin(getCluster(), traitSet, hintList,
        left, right, condition, variablesSet, joinType, semiJoinDone, systemFieldList);
  }
}
