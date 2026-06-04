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
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.hint.Hintable;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexLocalRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.rex.RexProgramBuilder;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * <code>Calc</code> is an abstract base class for implementations of
 * {@link org.apache.calcite.rel.logical.LogicalCalc}.
 */
// Calc 类的核心作用：多合一的高性能“万能算子”
// 在关系代数中，传统的算子划分是非常原子化的。例如：
// Filter：专门负责处理 WHERE 过滤条件。
// Project：专门负责处理 SELECT 列裁剪与投影。
// 但是，在实际执行阶段，如果数据流先经过一个 Filter 滤掉一部分行，接着马上流入 Project 进行列计算，数据会被反复读取、装箱、拆箱。
// Calc 算子的诞生就是为了彻底打破这个壁垒。它是一个将 Filter（条件过滤）和 Project（投影计算）完美融合为一体的“万能复合算子”。它内部通过包裹一个极其精密的指令集对象 RexProgram，可以在单次遍历（Single Pass）中，同时完成：
// 行级过滤（Filtering）。
// 中间表达式计算（Expression Evaluation，如常数折叠、多列标量计算）。
// 最终列投影输出（Projecting）。
public abstract class Calc extends SingleRel implements Hintable {
  //~ Instance fields --------------------------------------------------------
  // SQL 提示（Hints）集合。
  // 物理内幕：它实现了 Hintable 接口。如果在底层的 SQL 中写了类似 SELECT /*+ NO_INDEX, MAX_EXEC_TIME(100) */ 的 Hint 指令，这些元数据会被一路向下透传，最终安全地存储在 hints 列表中，供底层的物理执行引擎在运行时进行特定的策略干预。
  protected final ImmutableList<RelHint> hints;
  // 当前计算节点的“灵魂执行程序”。
  // 物理内幕：这是一个高度优化的指令集对象（RexProgram）。它内部不直接存储庞大的树状表达式，而是将所有的通用表达式、中间计算变量、输入引用全部打平放入一个公共的扁平化 List<RexNode> exprs 数组里，下游的过滤条件（Condition）和投影输出（Projects）全部通过数组下标（即 RexLocalRef 局部引用）来存取，从而实现了极高的内存复用率和计算效率。
  protected final RexProgram program;

  //~ Constructors -----------------------------------------------------------
  /**
   * Creates a Calc.
   *
   * @param cluster Cluster
   * @param traits Traits
   * @param hints Hints of this relational expression
   * @param child Input relation
   * @param program Calc program
   */
  @SuppressWarnings("method.invocation.invalid")
  protected Calc(
      RelOptCluster cluster,
      RelTraitSet traits,
      List<RelHint> hints,
      RelNode child,
      RexProgram program) {
    super(cluster, traits, child);
    // 极其关键。
    // Calc 节点的最终输出行结构（包含哪些列、什么类型）完全由它包裹的 program 决定，因此直接从 program 中提取输出类型赋值给自身的 rowType。
    this.rowType = program.getOutputRowType();
    this.program = program;
    this.hints = ImmutableList.copyOf(hints);
    assert isValid(Litmus.THROW, null);
  }

  @Deprecated // to be removed before 2.0
  protected Calc(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode child,
      RexProgram program) {
    this(cluster, traits, ImmutableList.of(), child, program);
  }

  @Deprecated // to be removed before 2.0
  protected Calc(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode child,
      RexProgram program,
      List<RelCollation> collationList) {
    this(cluster, traits, ImmutableList.of(), child, program);
    Util.discard(collationList);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public final Calc copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return copy(traitSet, sole(inputs), program);
  }

  /**
   * Creates a copy of this {@code Calc}.
   *
   * @param traitSet Traits
   * @param child Input relation
   * @param program Calc program
   * @return New {@code Calc} if any parameter differs from the value of this
   *   {@code Calc}, or just {@code this} if all the parameters are the same
   *
   * @see #copy(org.apache.calcite.plan.RelTraitSet, java.util.List)
   */
  public abstract Calc copy(
      RelTraitSet traitSet,
      RelNode child,
      RexProgram program);

  @Deprecated // to be removed before 2.0
  public Calc copy(
      RelTraitSet traitSet,
      RelNode child,
      RexProgram program,
      List<RelCollation> collationList) {
    Util.discard(collationList);
    return copy(traitSet, child, program);
  }
  //  判定当前的计算节点内部是否包含了窗口聚合函数（Window / OLAP Function，如 ROW_NUMBER() OVER (...)）。
  /** Returns whether this Calc contains any windowed-aggregate functions. */
  public final boolean containsOver() {
    return RexOver.containsOver(program);
  }

  @Override public boolean isValid(Litmus litmus, @Nullable Context context) {
    if (!RelOptUtil.equal(
        "program's input type",
        program.getInputRowType(),
        "child's output type",
        getInput().getRowType(), litmus)) {
      return litmus.fail(null);
    }
    if (!program.isValid(litmus, context)) {
      return litmus.fail(null);
    }
    if (!program.isNormalized(litmus, getCluster().getRexBuilder())) {
      return litmus.fail(null);
    }
    return litmus.succeed();
  }

  public RexProgram getProgram() {
    return program;
  }

  @Override public ImmutableList<RelHint> getHints() {
    return hints;
  }
  // 估算该计算操作的行数，通常在优化器中用于成本估算
  @Override public double estimateRowCount(RelMetadataQuery mq) {
    return RelMdUtil.estimateFilteredRows(getInput(), program, mq);
  }

  @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
      RelMetadataQuery mq) {
    double dRows = mq.getRowCount(this);
    double dCpu = mq.getRowCount(getInput())
        * program.getExprCount();
    double dIo = 0;
    return planner.getCostFactory().makeCost(dRows, dCpu, dIo);
  }

  @Override public RelWriter explainTerms(RelWriter pw) {
    return program.explainCalc(super.explainTerms(pw));
  }
  // 允许访问和修改计算程序中的表达式，适用于树遍历和重写操作
  // Calc 是一个极其核心的物理/逻辑关系算子，它将 Project（投影）和 Filter（过滤）合二为一，其内部核心的计算逻辑全部封装在 RexProgram 中。
  // 核心作用是：允许外界通过传入一个 RexShuttle（表达式转换器），
  // 对当前 Calc 算子内部维护的整套表达式进行全方位的批量转换（如列索引重映射、常量折叠等），并在表达式发生改变时，按需构建出一个挂载了全新 RexProgram 的新 Calc 算子。
  @Override public RelNode accept(RexShuttle shuttle) {
    List<RexNode> oldExprs = program.getExprList();
    List<RexNode> exprs = shuttle.apply(oldExprs);
    List<RexLocalRef> oldProjects = program.getProjectList();
    List<RexLocalRef> projects = shuttle.apply(oldProjects);
    RexLocalRef oldCondition = program.getCondition();
    RexNode condition;
    if (oldCondition != null) {
      condition = shuttle.apply(oldCondition);
      assert condition instanceof RexLocalRef
          : "Invalid condition after rewrite. Expected RexLocalRef, got "
          + condition;
    } else {
      condition = null;
    }
    if (exprs == oldExprs
        && projects == oldProjects
        && condition == oldCondition) {
      return this;
    }

    final RexBuilder rexBuilder = getCluster().getRexBuilder();
    final RelDataType rowType =
        RexUtil.createStructType(
            rexBuilder.getTypeFactory(),
            projects,
            getRowType().getFieldNames(),
            null);
    final RexProgram newProgram =
        RexProgramBuilder.create(
            rexBuilder, program.getInputRowType(), exprs, projects,
            condition, rowType, true, null)
        .getProgram(false);
    return copy(traitSet, getInput(), newProgram);
  }
}
