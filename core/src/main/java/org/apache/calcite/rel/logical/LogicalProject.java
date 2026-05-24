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
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelInput;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttle;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.RelMdCollation;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Sub-class of {@link org.apache.calcite.rel.core.Project} not
 * targeted at any particular engine or calling convention.
 */
// 在 Calcite 的架构中，关系算子被划分为不同的物理流派（Convention）。
// LogicalProject 属于 Convention.NONE（逻辑算子流派）。
// 它的核心职责是：作为 SQL 刚被解析、校验后生成的“纯逻辑形态”的投影算子（SELECT）
// 它不绑定任何具体的存储引擎或执行框架（如 Spark、Flink 或 JDBC 物理算子）。在 SQL 刚转为关系代数树时，所有的投影都会先被实例化为 LogicalProject。随后，优化器（Planner）会运用各种等价改写规则，最终将它转化为特定计算引擎的物理算子（例如 EnumerableProject 或 SparkProject）。

public final class LogicalProject extends Project {
  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a LogicalProject.
   *
   * <p>Use {@link #create} unless you know what you're doing.
   *
   * @param cluster  Cluster this relational expression belongs to
   * @param traitSet Traits of this relational expression
   * @param hints    Hints of this relational expression
   * @param input    Input relational expression
   * @param projects List of expressions for the input columns
   * @param rowType  Output row type
   * @param variablesSet Correlation variables set by this relational expression
   *                     to be used by nested expressions
   */
  // 底层真正用于实例化逻辑投影算子的全参数构造器。
  public LogicalProject(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      List<RelHint> hints,
      RelNode input,
      List<? extends RexNode> projects,
      RelDataType rowType,
      Set<CorrelationId> variablesSet) {
    super(cluster, traitSet, hints, input, projects, rowType, variablesSet);
    assert traitSet.containsIfApplicable(Convention.NONE);
  }

  @Deprecated // to be removed before 2.0
  public LogicalProject(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      List<RelHint> hints,
      RelNode input,
      List<? extends RexNode> projects,
      RelDataType rowType) {
    this(cluster, traitSet, hints, input, projects, rowType, ImmutableSet.of());
  }

  @Deprecated // to be removed before 2.0
  public LogicalProject(RelOptCluster cluster, RelTraitSet traitSet,
      RelNode input, List<? extends RexNode> projects, RelDataType rowType) {
    this(cluster, traitSet, ImmutableList.of(), input, projects, rowType, ImmutableSet.of());
  }

  @Deprecated // to be removed before 2.0
  public LogicalProject(RelOptCluster cluster, RelTraitSet traitSet,
      RelNode input, List<? extends RexNode> projects, RelDataType rowType,
      int flags) {
    this(cluster, traitSet, ImmutableList.of(), input, projects, rowType, ImmutableSet.of());
    Util.discard(flags);
  }

  @Deprecated // to be removed before 2.0
  public LogicalProject(RelOptCluster cluster, RelNode input,
      List<RexNode> projects, @Nullable List<? extends @Nullable String> fieldNames, int flags) {
    this(cluster, cluster.traitSetOf(RelCollations.EMPTY),
        ImmutableList.of(), input, projects,
        RexUtil.createStructType(cluster.getTypeFactory(), projects,
            fieldNames, null), ImmutableSet.of());
    Util.discard(flags);
  }

  /**
   * Creates a LogicalProject by parsing serialized output.
   */
  // 用于从存储介质、网络流（如 JSON/XML 形式的执行计划）中反序列化并还原出 LogicalProject 算子实体。
  public LogicalProject(RelInput input) {
    super(input);
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Creates a LogicalProject.
   *
   * @deprecated Use {@link #create(RelNode, List, List, List, Set)} instead
   */
  @Deprecated // to be removed before 2.0
  public static LogicalProject create(final RelNode input, List<RelHint> hints,
      final List<? extends RexNode> projects,
      @Nullable List<? extends @Nullable String> fieldNames) {
    return create(input, hints, projects, fieldNames, ImmutableSet.of());
  }

  /** Creates a LogicalProject. */
  public static LogicalProject create(final RelNode input, List<RelHint> hints,
      final List<? extends RexNode> projects,
      @Nullable List<? extends @Nullable String> fieldNames,
      final Set<CorrelationId> variablesSet) {
    final RelOptCluster cluster = input.getCluster();
    final RelDataType rowType =
        RexUtil.createStructType(cluster.getTypeFactory(), projects,
            fieldNames, SqlValidatorUtil.F_SUGGESTER);
    return create(input, hints, projects, rowType, variablesSet);
  }

  /**
   * Creates a LogicalProject, specifying row type rather than field names.
   *
   * @deprecated Use {@link #create(RelNode, List, List, RelDataType, Set)} instead
   */
  @Deprecated // to be removed before 2.0
  public static LogicalProject create(final RelNode input, List<RelHint> hints,
      final List<? extends RexNode> projects, RelDataType rowType) {
    return create(input, hints, projects, rowType, ImmutableSet.of());
  }

  /** Creates a LogicalProject, specifying row type rather than field names. */
  public static LogicalProject create(final RelNode input, List<RelHint> hints,
      final List<? extends RexNode> projects, RelDataType rowType,
      final Set<CorrelationId> variablesSet) {
    // 获取输入算子所在的优化器集群上下文（包含类型工厂、全局配置等）
    final RelOptCluster cluster = input.getCluster();
    // 能够动态计算和查阅整个算子树的各种统计信息（如：某棵子树吐出的行数、占用的 CPU、以及数据的排序特征）。
    final RelMetadataQuery mq = cluster.getMetadataQuery();
    // 动态重构物理特质（RelTraitSet）
    final RelTraitSet traitSet =
        // 强制将当前算子的流派（Convention）标记为 NONE（即纯逻辑算子形态）。
        cluster.traitSet().replace(Convention.NONE)
            // 动态推导并保留排序特征（Collation）。
            // 在关系代数中，投影（SELECT）虽然会裁剪或改变列的顺序，但它并不一定会破坏数据原有的有序性。
            // 内部逻辑是：去问元数据引擎 mq：“底层的输入节点（input）本来是有序的吗？如果有，当经历了当前的 projects 表达式链投影后，原先的有序性还能残存下来吗？”
            .replaceIfs(RelCollationTraitDef.INSTANCE,
                () -> RelMdCollation.project(mq, input, projects));
    return new LogicalProject(cluster, traitSet, hints, input, projects, rowType, variablesSet);
  }

  @Override public LogicalProject copy(RelTraitSet traitSet, RelNode input,
      List<RexNode> projects, RelDataType rowType) {
    return new LogicalProject(getCluster(), traitSet, hints, input, projects, rowType,
        variablesSet);
  }

  @Override public RelNode accept(RelShuttle shuttle) {
    return shuttle.visit(this);
  }

  @Override public RelNode withHints(List<RelHint> hintList) {
    return new LogicalProject(getCluster(), traitSet, hintList,
        input, getProjects(), getRowType(), variablesSet);
  }

  @Override public boolean deepEquals(@Nullable Object obj) {
    return deepEquals0(obj);
  }

  @Override public int deepHashCode() {
    return deepHashCode0();
  }
}
