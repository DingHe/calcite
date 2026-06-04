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
package org.apache.calcite.adapter.enumerable;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.metadata.RelMdCollation;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/** Implementation of {@link org.apache.calcite.rel.core.Project} in
 * {@link org.apache.calcite.adapter.enumerable.EnumerableConvention enumerable calling convention}. */
// EnumerableProject 继承自 Project 并实现了 EnumerableRel 接口。它在 Calcite 中扮演着“投影物理算子”的角色。
// 它对应 SQL 语句中的 SELECT 投影子句。负责从上游算子（Input）交付的数据行中，提取指定的列、过滤掉不需要的列，或者对列进行表达式计算（如 SELECT id, price * qty, UPPER(name)）。
// 当 Calcite 的 Volcano 优化器（或 Hep 优化器）将逻辑执行计划（Logical Plan）转换为可执行的物理执行计划（Physical Plan）时，逻辑层面的 LogicalProject 会通过物理转换规则（如 EnumerableProjectRule）被改写为 EnumerableProject。
// 它打上了 EnumerableConvention.INSTANCE（可枚举调用约定）的烙印，代表该算子最终会通过流式迭代、生成 Java 代码（Linq4j）的方式在单机内存中直接运行。
public class EnumerableProject extends Project implements EnumerableRel {
  /**
   * Creates an EnumerableProject.
   *
   * <p>Use {@link #create} unless you know what you're doing.
   *
   * @param cluster  Cluster this relational expression belongs to
   * @param traitSet Traits of this relational expression
   * @param input    Input relational expression
   * @param projects List of expressions for the input columns
   * @param rowType  Output row type
   */
  public EnumerableProject(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode input, // 上游子节点算子。
      List<? extends RexNode> projects, // 对应的 Rex 表达式集合。
      RelDataType rowType) {
    super(cluster, traitSet, ImmutableList.of(), input, projects, rowType, ImmutableSet.of());
    // 过断言 assert 确保当前算子的调用约定绝对是 EnumerableConvention 物理约定，作为防止规约错乱的工业护栏。
    assert getConvention() instanceof EnumerableConvention;
  }

  @Deprecated // to be removed before 2.0
  public EnumerableProject(RelOptCluster cluster, RelTraitSet traitSet,
      RelNode input, List<? extends RexNode> projects, RelDataType rowType,
      int flags) {
    this(cluster, traitSet, input, projects, rowType);
    Util.discard(flags);
  }

  /** Creates an EnumerableProject, specifying row type rather than field
   * names. */
  // 外部调用创建 EnumerableProject 的标准官方推荐入口。
  public static EnumerableProject create(final RelNode input,
      final List<? extends RexNode> projects, RelDataType rowType) {
    final RelOptCluster cluster = input.getCluster();
    final RelMetadataQuery mq = cluster.getMetadataQuery();
    final RelTraitSet traitSet =
        cluster.traitSet().replace(EnumerableConvention.INSTANCE)
            .replaceIfs(RelCollationTraitDef.INSTANCE,
                // 如果上游算子（input）本来是有序的数据流（例如已经排好序的 EnumerableSort），
                // 那么当前 Project 如果只是单纯提取列，其结果集可能依然能维持原有的物理顺序。
                // 该方法动态计算投影变换后的新排序特性（Collation）
                () -> RelMdCollation.project(mq, input, projects));
    return new EnumerableProject(cluster, traitSet, input, projects, rowType);
  }

  @Override public EnumerableProject copy(RelTraitSet traitSet, RelNode input,
      List<RexNode> projects, RelDataType rowType) {
    return new EnumerableProject(getCluster(), traitSet, input,
        projects, rowType);
  }

  @Override public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
    // EnumerableCalcRel is always better
    // 内幕原因：在可枚举（Enumerable）适配器中，Calcite 认为单独执行一个 Project 性能并不是最优的。
    // 如果紧接着有 Filter（条件过滤），最好的做法是将它们合并（Merge）。因此，Calcite 提供了一个更强大的“万能复合算子”——EnumerableCalc（计算算子）。
    // 运作链条：在优化器的微调阶段，通常会由 EnumerableCalcRule 或 ProjectToCalcRule 强制将 EnumerableProject 与 EnumerableFilter 合并转化成一个 EnumerableCalc。
    // 也就是说，在最终生成的 Java 执行代码里，你基本看不到 EnumerableProject 的身影，它通常被就地融合成为了 EnumerableCalc 内部的 Program。
    throw new UnsupportedOperationException();
  }
  // 自顶向下的物理特性传导机制。
  // 在 Calcite 的 Volcano 优化器进行基于代价的搜索（CBO）时，物理算子树不仅要在节点间传输数据，还要传输物理特性（RelTrait），其中最核心的就是排序特性（RelCollation）。
  // 核心使命是：当下游（父算子）对当前算子提出硬性的排序特性诉求时，当前算子去评估自己能否“透传”这个要求，并反向计算出上游（子算子）需要满足什么排序条件。
  // RelTraitSet required（入参）： 游父算子对当前 Project 算子强行提出的物理特性诉求。
  // 工业场景：比如 SQL 语句最外层有一个 ORDER BY emp_id，其顶层的 EnumerableSort 算子就会对它的直接孩子（也就是当前的 Project 算子）下达死命令：要求交盘过来的数据流必须包含“按 emp_id 升序”的 RelCollation 排序特性。
  // Pair<RelTraitSet, List<RelTraitSet>>（返回值）：如果物理上能够满足下游的苛刻要求，则返回一个精心计算出的“特性对”；如果物理上绝对无法满足，则直接返回 null。
  // Pair.left（当前节点的 TraitSet）：当前 Project 算子在完美顺应下游要求后，自身最终向优化器宣告的物理特性集。
  // Pair.right（子节点的 TraitSet 列表）：一个列表，里面包含了当前算子为了达成目标，反向对它的各个孩子（输入子算子）提出的物理特性要求。因为 Project 是单目算子（只有一个 input），所以这个 List 长度永远为 1。

  @Override public @Nullable Pair<RelTraitSet, List<RelTraitSet>> passThroughTraits(
      RelTraitSet required) {
    return EnumerableTraitsUtils.passThroughTraitsForProject(required, exps,
        input.getRowType(), input.getCluster().getTypeFactory(), traitSet);
  }
  // 自底向上的物理特性衍生/推导机制。
  @Override public @Nullable Pair<RelTraitSet, List<RelTraitSet>> deriveTraits(
      final RelTraitSet childTraits, final int childId) {
    return EnumerableTraitsUtils.deriveTraitsForProject(childTraits, childId, exps,
        input.getRowType(), input.getCluster().getTypeFactory(), traitSet);
  }
}
