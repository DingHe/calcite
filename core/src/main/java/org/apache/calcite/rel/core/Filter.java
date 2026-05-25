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
import org.apache.calcite.rel.RelInput;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.hint.Hintable;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexChecker;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.util.Litmus;

import com.google.common.collect.ImmutableList;

import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Relational expression that iterates over its input
 * and returns elements for which <code>condition</code> evaluates to
 * <code>true</code>.
 *
 * <p>If the condition allows nulls, then a null value is treated the same as
 * false.
 *
 * @see org.apache.calcite.rel.logical.LogicalFilter
 */
// 在关系代数中，Filter 对应的是 选择算子（Selection），也就是标准 SQL 中的 WHERE 子句 或 HAVING 子句。
// 核心职责是：
// 行级数据断言与过滤：它拦截上游算子（如 TableScan 或 Project）吐出的每一行数据，并利用其持有的布尔条件表达式进行计算。只有计算结果为 true 的行才允许向下游传递，若为 false 或 NULL 则直接丢弃。
// 基数收缩（Selectivity Evaluation）：它是优化器计算过滤率（Selectivity）的载体。一条复杂的过滤条件会直接决定整个算子树后续处理的数据吞吐量。
// 作为抽象基础框架：它不限定具体的物理引擎，其具体的逻辑实现为 LogicalFilter，而物理实现则可以是 JdbcFilter、EnumerableFilter 或 SparkFilter 等。

public abstract class Filter extends SingleRel implements Hintable {
  //~ Instance fields --------------------------------------------------------
  // 当前过滤算子所持有的过滤条件表达式（布尔树）。
  // 这是一个行级表达式（Row Expression），通常由逻辑谓词（如 AND、OR、NOT）和比较算子（如 =, >, LIKE）组装而成。比如 SQL 中的 WHERE age > 18 AND status = 'ACTIVE'，在内部就会被解析并存储为该属性。
  protected final RexNode condition;

  // 该过滤算子持有的 SQL 提示（Hints）列表。
  // 记录了用户显式指定的、针对过滤阶段的提示。例如部分分布式数据库中用于控制过滤行为的强制索引提示等。
  protected final ImmutableList<RelHint> hints;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a filter.
   *
   * @param cluster   Cluster that this relational expression belongs to
   * @param traits    the traits of this rel
   * @param hints     Hints for this node
   * @param child     input relational expression
   * @param condition boolean expression which determines whether a row is
   *                  allowed to pass
   */
  @SuppressWarnings("method.invocation.invalid")
  protected Filter(
      RelOptCluster cluster,
      RelTraitSet traits,
      List<RelHint> hints,
      RelNode child,
      RexNode condition) {
    super(cluster, traits, child);
    this.condition = requireNonNull(condition, "condition");
    // 强行确保传入的布尔表达式被扁平化（Flat）处理过。即连续的 AND 或 OR（如 a AND b AND c）必须展现为单层的多路节点，而不是嵌套的二叉树，从而方便优化器高效解析。
    assert RexUtil.isFlat(condition) : "RexUtil.isFlat should be true for condition " + condition;
    assert isValid(Litmus.THROW, null);
    this.hints = ImmutableList.copyOf(hints);
  }

  /**
   * Creates a filter.
   *
   * @param cluster   Cluster that this relational expression belongs to
   * @param traits    the traits of this rel
   * @param child     input relational expression
   * @param condition boolean expression which determines whether a row is
   *                  allowed to pass
   */
  protected Filter(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode child,
      RexNode condition) {
    this(cluster, traits, ImmutableList.of(), child, condition);
  }

  /**
   * Creates a Filter by parsing serialized output.
   */
  protected Filter(RelInput input) {
    this(input.getCluster(), input.getTraitSet(), input.getInput(),
        requireNonNull(input.getExpression("condition"), "condition"));
  }

  //~ Methods ----------------------------------------------------------------

  @Override public final RelNode copy(RelTraitSet traitSet,
      List<RelNode> inputs) {
    return copy(traitSet, sole(inputs), getCondition());
  }

  public abstract Filter copy(RelTraitSet traitSet, RelNode input,
      RexNode condition);

  @Override public RelNode accept(RexShuttle shuttle) {
    RexNode condition = shuttle.apply(this.condition);
    if (this.condition == condition) {
      return this;
    }
    return copy(traitSet, getInput(), condition);
  }

  public RexNode getCondition() {
    return condition;
  }

  /** Returns whether this Filter contains any windowed-aggregate functions. */
  public final boolean containsOver() {
    return RexOver.containsOver(condition);
  }

  // 关系代数节点（RelNode）和行表达式（RexNode）在被规则修改或重新构建后，必须确保它们的内部结构和类型是合规、无畸变的。
  // isValid 方法就像一个安检关卡，在执行计划优化或生成的关键节点对 Filter 算子进行全面的健康检查
  @Override public boolean isValid(Litmus litmus, @Nullable Context context) {
    // 检查 Filter 的根条件表达式是否是一个仅仅用来改变可空性（Nullability）的 CAST 操作（例如：将一个本就为布尔类型的字段，强转为带有 Nullable 属性的布尔类型，而没有实质性的过滤逻辑）。
    if (RexUtil.isNullabilityCast(getCluster().getTypeFactory(), condition)) {
      return litmus.fail("Cast for just nullability not allowed");
    }
    // RexChecker 是 Calcite 专门用来校验行级表达式（RexNode）是否合法的访问者（Visitor）。
    final RexChecker checker =
        new RexChecker(getInput().getRowType(), context, litmus);
    condition.accept(checker);
    if (checker.getFailureCount() > 0) {
      return litmus.fail(null);
    }
    return litmus.succeed();
  }

  @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
      RelMetadataQuery mq) {
    // 经过当前 Filter 算子过滤后，最终残存并吐给下游上层算子的数据行数
    double dRows = mq.getRowCount(this);
    // 当前算子耗费的 CPU 算力得分。
    // 硬核设计内幕（为什么取的是 getInput() 的行数？）：
    //这是此方法中最核心的数据库设计哲学。
    //假设上游算子（getInput()）一共灌进来 10,000 条数据，过滤条件是 age > 60。最终只有 100 条数据满足条件被吐给下游。
    // 那么，Rows 代价（输出行数）是 100。
    //但是，CPU 代价绝对不是 100，而是 10,000。
    //因为不管某一行数据最终能不能留下来，它都必须在 CPU 寄存器里老老实实地执行一遍布尔谓词（age > 60）的求值计算。 每一个流入的数据行，都要消耗一次 CPU 比较指令。因此，Filter 算子的 CPU 代价完全取决于上游灌进来的总数据量（即 getInput() 的行数）。
    double dCpu = mq.getRowCount(getInput());
    double dIo = 0;
    return planner.getCostFactory().makeCost(dRows, dCpu, dIo);
  }
  // 准确预测经过当前过滤条件清洗后，究竟还能剩下多少行数据
  // 每当优化器（CBO）向 Filter 算子询问“你大概会吐出多少条数据”时，这个方法就会被触发。它是整个优化树计算链中极其重要的一环。
  // $$\text{Filter 输出行数} = \text{输入行数 (child.getRowCount())} \times \text{条件选择率 (Selectivity)}$$
  @Override public double estimateRowCount(RelMetadataQuery mq) {
    return RelMdUtil.estimateFilteredRows(getInput(), condition, mq);
  }

  @Deprecated // to be removed before 2.0
  public static double estimateFilteredRows(RelNode child, RexProgram program) {
    final RelMetadataQuery mq = child.getCluster().getMetadataQuery();
    return RelMdUtil.estimateFilteredRows(child, program, mq);
  }

  @Deprecated // to be removed before 2.0
  public static double estimateFilteredRows(RelNode child, RexNode condition) {
    final RelMetadataQuery mq = child.getCluster().getMetadataQuery();
    return RelMdUtil.estimateFilteredRows(child, condition, mq);
  }

  @Override public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .item("condition", condition);
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
    Filter o = (Filter) obj;
    return traitSet.equals(o.traitSet)
        && hints.equals(o.hints)
        && input.deepEquals(o.input)
        && condition.equals(o.condition)
        && getRowType().equalsSansFieldNames(o.getRowType());
  }

  @API(since = "1.24", status = API.Status.INTERNAL)
  protected int deepHashCode0() {
    return Objects.hash(traitSet, hints, input.deepHashCode(), condition);
  }

  @Override public ImmutableList<RelHint> getHints() {
    return hints;
  }
}
