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
package org.apache.calcite.rel.core;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.BiRel;
import org.apache.calcite.rel.RelInput;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.hint.Hintable;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Litmus;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * A relational operator that performs nested-loop joins.
 *
 * <p>It behaves like a kind of {@link org.apache.calcite.rel.core.Join},
 * but works by setting variables in its environment and restarting its
 * right-hand input.
 *
 * <p>Correlate is not a join since: typical rules should not match Correlate.
 *
 * <p>A Correlate is used to represent a correlated query. One
 * implementation strategy is to de-correlate the expression.
 *
 * <table>
 *   <caption>Mapping of physical operations to logical ones</caption>
 *   <tr><th>Physical operation</th><th>Logical operation</th></tr>
 *   <tr><td>NestedLoops</td><td>Correlate(A, B, regular)</td></tr>
 *   <tr><td>NestedLoopsOuter</td><td>Correlate(A, B, outer)</td></tr>
 *   <tr><td>NestedLoopsSemi</td><td>Correlate(A, B, semi)</td></tr>
 *   <tr><td>NestedLoopsAnti</td><td>Correlate(A, B, anti)</td></tr>
 *   <tr><td>HashJoin</td><td>EquiJoin(A, B)</td></tr>
 *   <tr><td>HashJoinOuter</td><td>EquiJoin(A, B, outer)</td></tr>
 *   <tr><td>HashJoinSemi</td><td>SemiJoin(A, B, semi)</td></tr>
 *   <tr><td>HashJoinAnti</td><td>SemiJoin(A, B, anti)</td></tr>
 * </table>
 * @see CorrelationId
 */
// Correlate 是一个抽象双输入（BiRel）逻辑/物理关系代数运算符。它专门用来建模和描述 SQL 中的关联子查询（Correlated Subquery）。
// 行为本质：嵌套循环（Nested-Loop）
// 虽然它看起来和 Join（连接）算子非常相似，都拥有左输入（Left Input）和右输入（Right Input），但其底层的执行语义截然不同：
// 普通 Join：左右两侧是独立的数据流，通过某种算法（如 Hash Join）在连接条件上进行碰撞。
// Correlate：扮演着驱动轮与从动轮的关系。它采取嵌套循环策略——外层（左输入）每吐出一行数据，
// Correlate 就会将这行数据的相关字段设置为上下文变量环境（CorrelationId），然后强行宣告右输入（子查询）重置并重新执行一遍（Restart/Rescan）。
public abstract class Correlate extends BiRel implements Hintable {
  //~ Instance fields --------------------------------------------------------
  // 关联变量的唯一标识符（ID）
  // 代表了外部查询（左输入）当前正在循环的“那一行数据”的变量名（例如 $cor0）。右输入算子树内部（如深层的 Filter）就是通过这个 ID 来引用外层数据的。
  protected final CorrelationId correlationId;
  // 左输入中被右输入依赖的列索引位图集合。
  // 使用 ImmutableBitSet 标记哪些列是关联所必需的。例如左表有 5 列，但子查询内部只需要用到第 1 和第 3 列，则该位图会记录 {1, 3}。这为物理执行时只传递必要的字段提供了元数据支持。
  protected final ImmutableBitSet requiredColumns;
  // 连接的语义类型。
  // 支持 INNER（内连接）、LEFT（左外连接）、SEMI（半连接，如 EXISTS）和 ANTI（反连接，如 NOT EXISTS）。它决定了嵌套循环在匹配成功或失败时如何输出最终行。
  protected final JoinRelType joinType;
  // SQL 方言或用户指定的优化器提示（Hints）列表。
  protected final ImmutableList<RelHint> hints;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a Correlate.
   *
   * @param cluster      Cluster this relational expression belongs to
   * @param left         Left input relational expression
   * @param right        Right input relational expression
   * @param correlationId Variable name for the row of left input
   * @param requiredColumns Set of columns that are used by correlation
   * @param joinType Join type
   */
  @SuppressWarnings("method.invocation.invalid")
  protected Correlate(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      List<RelHint> hints,
      RelNode left,
      RelNode right,
      CorrelationId correlationId,
      ImmutableBitSet requiredColumns,
      JoinRelType joinType) {
    super(cluster, traitSet, left, right);
    assert !joinType.generatesNullsOnLeft() : "Correlate has invalid join type " + joinType;
    this.joinType = requireNonNull(joinType, "joinType");
    this.correlationId = requireNonNull(correlationId, "correlationId");
    this.requiredColumns = requireNonNull(requiredColumns, "requiredColumns");
    this.hints = ImmutableList.copyOf(hints);
    assert isValid(Litmus.THROW, null);
  }

  @Deprecated // to be removed before 2.0
  protected Correlate(
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
   * Creates a Correlate by parsing serialized output.
   *
   * @param input Input representation
   */
  protected Correlate(RelInput input) {
    this(
        input.getCluster(), input.getTraitSet(), input.getInputs().get(0),
        input.getInputs().get(1),
        new CorrelationId(
            requireNonNull((Integer) input.get("correlation"), "correlation")),
        input.getBitSet("requiredColumns"),
        requireNonNull(input.getEnum("joinType", JoinRelType.class), "joinType"));
  }

  //~ Methods ----------------------------------------------------------------

  @Override public boolean isValid(Litmus litmus, @Nullable Context context) {
    ImmutableBitSet leftColumns = ImmutableBitSet.range(left.getRowType().getFieldCount());
    return super.isValid(litmus, context)
        && litmus.check(leftColumns.contains(requiredColumns),
        "Required columns {} not subset of left columns {}", requiredColumns, leftColumns)
        && RelOptUtil.notContainsCorrelation(left, correlationId, litmus);
  }

  @Override public Correlate copy(RelTraitSet traitSet, List<RelNode> inputs) {
    assert inputs.size() == 2;
    return copy(traitSet,
        inputs.get(0),
        inputs.get(1),
        correlationId,
        requiredColumns,
        joinType);
  }

  public abstract Correlate copy(RelTraitSet traitSet,
      RelNode left, RelNode right, CorrelationId correlationId,
      ImmutableBitSet requiredColumns, JoinRelType joinType);

  public JoinRelType getJoinType() {
    return joinType;
  }

  @Override protected RelDataType deriveRowType() {
    switch (joinType) {
    case LEFT:
    case INNER:
      return SqlValidatorUtil.deriveJoinRowType(left.getRowType(),
          right.getRowType(), joinType,
          getCluster().getTypeFactory(), null,
          ImmutableList.of());
    case ANTI:
    case SEMI:
      return left.getRowType();
    default:
      throw new IllegalStateException("Unknown join type " + joinType);
    }
  }

  @Override public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .item("correlation", correlationId)
        .item("joinType", joinType.lowerName)
        .item("requiredColumns", requiredColumns);
  }

  /**
   * Returns the correlating expressions.
   *
   * @return correlating expressions
   */
  public CorrelationId getCorrelationId() {
    return correlationId;
  }

  @Override public String getCorrelVariable() {
    return correlationId.getName();
  }

  /**
   * Returns the required columns in left relation required for the correlation
   * in the right.
   *
   * @return columns in left relation required for the correlation in the right
   */
  public ImmutableBitSet getRequiredColumns() {
    return requiredColumns;
  }

  @Override public Set<CorrelationId> getVariablesSet() {
    return ImmutableSet.of(correlationId);
  }

  @Override public double estimateRowCount(RelMetadataQuery mq) {
    double leftRowCount = mq.getRowCount(left);
    switch (joinType) {
    case SEMI:
    case ANTI:
      return leftRowCount;
    default:
      return leftRowCount * mq.getRowCount(right);
    }
  }

  @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
      RelMetadataQuery mq) {
    double rowCount = mq.getRowCount(this);

    final double rightRowCount = mq.getRowCount(right);
    final double leftRowCount = mq.getRowCount(left);
    if (Double.isInfinite(leftRowCount) || Double.isInfinite(rightRowCount)) {
      return planner.getCostFactory().makeInfiniteCost();
    }

    Double restartCount = mq.getRowCount(getLeft());
    if (restartCount == null) {
      return planner.getCostFactory().makeInfiniteCost();
    }
    // RelMetadataQuery.getCumulativeCost(getRight()); does not work for
    // RelSubset, so we ask planner to cost-estimate right relation
    RelOptCost rightCost = planner.getCost(getRight(), mq);
    if (rightCost == null) {
      return planner.getCostFactory().makeInfiniteCost();
    }
    RelOptCost rescanCost =
        rightCost.multiplyBy(Math.max(1.0, restartCount - 1));

    return planner.getCostFactory().makeCost(
        rowCount /* generate results */ + leftRowCount /* scan left results */,
        0, 0).plus(rescanCost);
  }

  @Override public ImmutableList<RelHint> getHints() {
    return hints;
  }
}
