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
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.util.ImmutableBitSet;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * A relational operator that performs nested-loop joins.
 *
 * <p>It behaves like a kind of {@link org.apache.calcite.rel.core.Join},
 * but works by setting variables in its environment and restarting its
 * right-hand input.
 *
 * <p>A LogicalCorrelate is used to represent a correlated query. One
 * implementation strategy is to de-correlate the expression.
 *
 * @see org.apache.calcite.rel.core.CorrelationId
 */
// LogicalCorrelate 是关联子查询在逻辑计划阶段（Logical Plan）的具象化节点。它处于 Convention.NONE（无物理约定）的时空中，专门用于在逻辑层面上对算子树进行打平、重写与去关联化。
// LogicalCorrelate 的核心作用是在逻辑优化阶段作为关联子查询的“标准容器”。
// 逻辑阶段的代言人：与基类 Correlate 的抽象性不同，LogicalCorrelate 拥有明确的逻辑物理属性。它的 TraitSet 携带的是 Convention.NONE。这代表它是一个纯逻辑概念，无法直接在任何底层引擎（如 Flink、Spark 或 JDBC）上被物理执行。
// 承上启下的转换桥梁：如果去关联化成功，它会被替换为普通的 LogicalJoin；如果优化器保留了它，它最终会通过物理转换规则（如 EnumerableCorrelateRule），被翻译为具有具体执行代码的物理算子（如 EnumerableCorrelate）。
//
public final class LogicalCorrelate extends Correlate {
  //~ Instance fields --------------------------------------------------------

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a LogicalCorrelate.
   *
   * @param cluster      cluster this relational expression belongs to
   * @param left         left input relational expression
   * @param right        right input relational expression
   * @param correlationId variable name for the row of left input
   * @param requiredColumns Required columns
   * @param joinType     join type
   */
  public LogicalCorrelate(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      List<RelHint> hints,
      RelNode left,
      RelNode right,
      CorrelationId correlationId,
      ImmutableBitSet requiredColumns,
      JoinRelType joinType) {
    super(
        cluster,
        traitSet,
        hints,
        left,
        right,
        correlationId,
        requiredColumns,
        joinType);
  }

  @Deprecated // to be removed before 2.0
  public LogicalCorrelate(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode left,
      RelNode right,
      CorrelationId correlationId,
      ImmutableBitSet requiredColumns,
      JoinRelType joinType) {
    this(cluster, traitSet, ImmutableList.of(), left, right,
        correlationId, requiredColumns, joinType);
  }

  /**
   * Creates a LogicalCorrelate by parsing serialized output.
   */
  public LogicalCorrelate(RelInput input) {
    this(input.getCluster(), input.getTraitSet(), ImmutableList.of(),
        input.getInputs().get(0),
        input.getInputs().get(1),
        new CorrelationId(
            (Integer) requireNonNull(input.get("correlation"), "correlation")),
        input.getBitSet("requiredColumns"),
        requireNonNull(input.getEnum("joinType", JoinRelType.class), "joinType"));
  }

  /** Creates a LogicalCorrelate. */
  public static LogicalCorrelate create(RelNode left, RelNode right, List<RelHint> hints,
      CorrelationId correlationId, ImmutableBitSet requiredColumns,
      JoinRelType joinType) {
    final RelOptCluster cluster = left.getCluster();
    final RelTraitSet traitSet = cluster.traitSetOf(Convention.NONE);
    return new LogicalCorrelate(cluster, traitSet, hints, left, right, correlationId,
        requiredColumns, joinType);
  }

  @Deprecated // to be removed before 2.0
  public static LogicalCorrelate create(RelNode left, RelNode right,
      CorrelationId correlationId, ImmutableBitSet requiredColumns,
      JoinRelType joinType) {
    return create(left, right, ImmutableList.of(), correlationId, requiredColumns, joinType);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public LogicalCorrelate copy(RelTraitSet traitSet,
      RelNode left, RelNode right, CorrelationId correlationId,
      ImmutableBitSet requiredColumns, JoinRelType joinType) {
    assert traitSet.containsIfApplicable(Convention.NONE);
    return new LogicalCorrelate(getCluster(), traitSet, hints, left, right,
        correlationId, requiredColumns, joinType);
  }

  @Override public RelNode accept(RelShuttle shuttle) {
    return shuttle.visit(this);
  }

  @Override public RelNode withHints(List<RelHint> hintList) {
    return new LogicalCorrelate(getCluster(), traitSet, hintList, left, right,
        correlationId, requiredColumns, joinType);
  }
}
