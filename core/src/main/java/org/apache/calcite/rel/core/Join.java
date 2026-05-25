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
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.BiRel;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.hint.Hintable;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexChecker;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Relational expression that combines two relational expressions according to
 * some condition.
 *
 * <p>Each output row has columns from the left and right inputs.
 * The set of output rows is a subset of the cartesian product of the two
 * inputs; precisely which subset depends on the join condition.
 */
// 在关系代数中，Join 对应的是 连接算子，也就是标准 SQL 中的 INNER JOIN、LEFT JOIN、RIGHT JOIN、FULL JOIN 以及特有的 SEMI JOIN / ANTI JOIN
// 核心职责是：
// 拓扑双流合并：它接收来自 BiRel 的左、右两个输入子树（left 和 right），根据给定的条件将两股数据流结合在一起，形成一个更宽的输出行（RowType）。
// 连接条件剖析（Equi-Join Extraction）：作为高频连接的基础载体，它持有一个高度优化的 JoinInfo，能够自动提取出条件中的等值部分（如 a.id = b.id）和非等值部分（如 a.age > b.age），直接指导物理层选择 HashJoin 还是 NestedLoopJoin。
// 架构承上启下：它定义了通用的校验、代价评估、Schema 派生规范。其具体逻辑实现为 LogicalJoin，物理层对应 EnumerableHashJoin、JdbcJoin 或者是 Spark 模块的 SparkJoin。
//
public abstract class Join extends BiRel implements Hintable {
  //~ Instance fields --------------------------------------------------------
  // 当前连接操作持有的 Join 判定条件表达式。
  // 一个行级布尔树。负责对左右表笛卡尔积后的临时行进行断言判断（例如：=($0, $5) 表示左表的第 0 列等于右表的第 5 列）
  protected final RexNode condition;
  // 该连接算子向外暴露或传递给右子树的关联变量（Correlation ID）集合
  // 主要应用于嵌套循环关联连接（Correlated Join）场景，记录需要从左表传递到右表作为变量参数的 ID。
  protected final ImmutableSet<CorrelationId> variablesSet;
  // 当前 Join 算子持有的 SQL 提示（Hints）列表。
  // 用于拦截诸如 /*+ BROADCAST(right) */ 或 /*+ MERGE(left, right) */ 这样的用户显式物理指引。
  protected final ImmutableList<RelHint> hints;

  /**
   * Values must be of enumeration {@link JoinRelType}, except that
   * {@link JoinRelType#RIGHT} is disallowed.
   */
  //join的类型
  // 可以是 INNER（内连接）、LEFT（左外连接）、FULL（全外连接）、SEMI（半连接）或 ANTI（反连接）。注释特别指出禁止直接声明 RIGHT，因为 Calcite 内部通常会在规范化阶段将右外连接自动改写置换为左外连接，以简化优化规则的设计。
  protected final JoinRelType joinType;
  // 针对连接条件进行深度预剖析的缓存工具包。
  // 它在 Join 初始化完成时，自动将 condition 拆解为 leftKeys（左表的等值列索引列表）和 rightKeys（右表的等值列索引列表），从而让物理优化规则能够瞬间判定当前连接是否能走高效的 Hash Join。
  protected final JoinInfo joinInfo;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a Join.
   *
   * @param cluster          Cluster
   * @param traitSet         Trait set
   * @param hints            Hints
   * @param left             Left input
   * @param right            Right input
   * @param condition        Join condition
   * @param joinType         Join type
   * @param variablesSet     variables that are set by the
   *                         LHS and used by the RHS and are not available to
   *                         nodes above this Join in the tree
   */
  protected Join(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      List<RelHint> hints,
      RelNode left,
      RelNode right,
      RexNode condition,
      Set<CorrelationId> variablesSet,
      JoinRelType joinType) {
    super(cluster, traitSet, left, right);
    this.condition = Objects.requireNonNull(condition, "condition");
    this.variablesSet = ImmutableSet.copyOf(variablesSet);
    this.joinType = Objects.requireNonNull(joinType, "joinType");
    this.joinInfo = JoinInfo.of(left, right, condition);
    this.hints = ImmutableList.copyOf(hints);
  }

  @Deprecated // to be removed before 2.0
  protected Join(
      RelOptCluster cluster, RelTraitSet traitSet, RelNode left,
      RelNode right, RexNode condition, Set<CorrelationId> variablesSet,
      JoinRelType joinType) {
    this(cluster, traitSet, ImmutableList.of(), left, right,
        condition, variablesSet, joinType);
  }

  @Deprecated // to be removed before 2.0
  protected Join(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode left,
      RelNode right,
      RexNode condition,
      JoinRelType joinType,
      Set<String> variablesStopped) {
    this(cluster, traitSet, ImmutableList.of(), left, right, condition,
        CorrelationId.setOf(variablesStopped), joinType);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public RelNode accept(RexShuttle shuttle) {
    RexNode condition = shuttle.apply(this.condition);
    if (this.condition == condition) {
      return this;
    }
    return copy(traitSet, condition, left, right, joinType, isSemiJoinDone());
  }

  public RexNode getCondition() {
    return condition;
  }

  public JoinRelType getJoinType() {
    return joinType;
  }

  @Override public boolean isValid(Litmus litmus, @Nullable Context context) {
    if (!super.isValid(litmus, context)) {
      return false;
    }
    if (getRowType().getFieldCount()
        != getSystemFieldList().size()
        + left.getRowType().getFieldCount()
        + (joinType.projectsRight() ? right.getRowType().getFieldCount() : 0)) {
      return litmus.fail("field count mismatch");
    }
    if (condition != null) {
      if (condition.getType().getSqlTypeName() != SqlTypeName.BOOLEAN) {
        return litmus.fail("condition must be boolean: {}",
            condition.getType());
      }
      // The input to the condition is a row type consisting of system
      // fields, left fields, and right fields. Very similar to the
      // output row type, except that fields have not yet been made due
      // due to outer joins.
      RexChecker checker =
          new RexChecker(
              getCluster().getTypeFactory().builder()
                  .addAll(getSystemFieldList())
                  .addAll(getLeft().getRowType().getFieldList())
                  .addAll(getRight().getRowType().getFieldList())
                  .build(),
              context, litmus);
      condition.accept(checker);
      if (checker.getFailureCount() > 0) {
        return litmus.fail(checker.getFailureCount()
            + " failures in condition " + condition);
      }
    }
    return litmus.succeed();
  }

  @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
      RelMetadataQuery mq) {
    // Maybe we should remove this for semi-join?
    if (isSemiJoin()) {
      // REVIEW jvs 9-Apr-2006:  Just for now...
      return planner.getCostFactory().makeTinyCost();
    }
    double rowCount = mq.getRowCount(this);
    return planner.getCostFactory().makeCost(rowCount, 0, 0);
  }

  // CHECKSTYLE: IGNORE 1
  /** @deprecated Use {@link RelMdUtil#getJoinRowCount(RelMetadataQuery, Join, RexNode)}. */
  @Deprecated // to be removed before 2.0
  public static double estimateJoinedRows(
      Join joinRel,
      RexNode condition) {
    final RelMetadataQuery mq = joinRel.getCluster().getMetadataQuery();
    return Util.first(RelMdUtil.getJoinRowCount(mq, joinRel, condition), 1D);
  }

  @Override public double estimateRowCount(RelMetadataQuery mq) {
    return Util.first(RelMdUtil.getJoinRowCount(mq, this, condition), 1D);
  }

  @Override public Set<CorrelationId> getVariablesSet() {
    return variablesSet;
  }

  @Override public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .item("condition", condition)
        .item("joinType", joinType.lowerName)
        .itemIf("variablesSet", variablesSet, !variablesSet.isEmpty())
        .itemIf(
            "systemFields",
            getSystemFieldList(),
            !getSystemFieldList().isEmpty());
  }

  @API(since = "1.24", status = API.Status.INTERNAL)
  @EnsuresNonNullIf(expression = "#1", result = true)
  protected boolean deepEquals0(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    Join o = (Join) obj;
    return traitSet.equals(o.traitSet)
        && left.deepEquals(o.left)
        && right.deepEquals(o.right)
        && condition.equals(o.condition)
        && joinType == o.joinType
        && hints.equals(o.hints)
        && getRowType().equalsSansFieldNames(o.getRowType());
  }

  @API(since = "1.24", status = API.Status.INTERNAL)
  protected int deepHashCode0() {
    return Objects.hash(traitSet,
        left.deepHashCode(), right.deepHashCode(),
        condition, joinType, hints);
  }

  @Override protected RelDataType deriveRowType() {
    return SqlValidatorUtil.deriveJoinRowType(left.getRowType(),
        right.getRowType(), joinType, getCluster().getTypeFactory(), null,
        getSystemFieldList());
  }

  /**
   * Returns whether this LogicalJoin has already spawned a
   * {@code SemiJoin} via
   * {@link org.apache.calcite.rel.rules.JoinAddRedundantSemiJoinRule}.
   *
   * <p>The base implementation returns false.
   *
   * @return whether this join has already spawned a semi join
   */
  public boolean isSemiJoinDone() {
    return false;
  }

  /**
   * Returns whether this Join is a semijoin.
   *
   * @return true if this Join's join type is semi.
   */
  public boolean isSemiJoin() {
    return joinType == JoinRelType.SEMI;
  }

  /**
   * Returns a list of system fields that will be prefixed to
   * output row type.
   *
   * @return list of system fields
   */
  public List<RelDataTypeField> getSystemFieldList() {
    return Collections.emptyList();
  }

  @Deprecated // to be removed before 2.0
  public static RelDataType deriveJoinRowType(
      RelDataType leftType,
      RelDataType rightType,
      JoinRelType joinType,
      RelDataTypeFactory typeFactory,
      @Nullable List<String> fieldNameList,
      List<RelDataTypeField> systemFieldList) {
    return SqlValidatorUtil.deriveJoinRowType(leftType, rightType, joinType,
        typeFactory, fieldNameList, systemFieldList);
  }

  @Deprecated // to be removed before 2.0
  public static RelDataType createJoinType(
      RelDataTypeFactory typeFactory,
      RelDataType leftType,
      RelDataType rightType,
      List<String> fieldNameList,
      List<RelDataTypeField> systemFieldList) {
    return SqlValidatorUtil.createJoinType(typeFactory, leftType, rightType,
        fieldNameList, systemFieldList);
  }

  @Override public final Join copy(RelTraitSet traitSet, List<RelNode> inputs) {
    assert inputs.size() == 2;
    return copy(traitSet, getCondition(), inputs.get(0), inputs.get(1),
        joinType, isSemiJoinDone());
  }

  /**
   * Creates a copy of this join, overriding condition, system fields and
   * inputs.
   *
   * <p>General contract as {@link RelNode#copy}.
   *
   * @param traitSet      Traits
   * @param conditionExpr Condition
   * @param left          Left input
   * @param right         Right input
   * @param joinType      Join type
   * @param semiJoinDone  Whether this join has been translated to a
   *                      semi-join
   * @return Copy of this join
   */
  public abstract Join copy(RelTraitSet traitSet, RexNode conditionExpr,
      RelNode left, RelNode right, JoinRelType joinType, boolean semiJoinDone);

  /**
   * Analyzes the join condition.
   *
   * @return Analyzed join condition
   */
  public JoinInfo analyzeCondition() {
    return joinInfo;
  }

  @Override public ImmutableList<RelHint> getHints() {
    return hints;
  }
}
