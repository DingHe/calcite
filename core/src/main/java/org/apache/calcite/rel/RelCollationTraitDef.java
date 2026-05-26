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
package org.apache.calcite.rel;

import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.logical.LogicalSort;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Definition of the ordering trait.
 *
 * <p>Ordering is a physical property (i.e. a trait) because it can be changed
 * without loss of information. The converter to do this is the
 * {@link org.apache.calcite.rel.core.Sort} operator.
 *
 * <p>Unlike other current traits, a {@link RelNode} can have more than one
 * value of this trait simultaneously. For example,
 * <code>LogicalTableScan(table=TIME_BY_DAY)</code> might be sorted by
 * <code>{the_year, the_month, the_date}</code> and also by
 * <code>{time_id}</code>. We have to allow a RelNode to belong to more than
 * one RelSubset (these RelSubsets are always in the same set).
 */
// RelCollationTraitDef 是 Apache Calcite 优化器框架中用于管理“排序规则/排序特质（Collation）”的核心定义类。
// 在 SQL 引擎中，数据的有序性（Ordering/Collation） 是一种极其关键的物理属性（例如：数据是否已经按照 emp_id ASC, dept_id DESC 排好序）。
// RelCollationTraitDef 的核心任务就是定义排序维度的元数据，并在算子树的特质不匹配时，动态插入物理排序算子（Sort）来强制让数据流满足上层期望的有序状态。
// 显式声明排序的多重特质（Multiple Traits）：
// 与调用约定（Convention，一个算子同一时间只能属于一个流派）不同，Calcite 允许一个关系代数节点（RelNode）同时拥有多个不同的排序特质。例如，数据表扫描算子物理上可能同时满足按 (year, month, day) 排序，又由于主键索引的存在，同时满足按 (time_id) 排序。该类通过重写特定方法支撑了这种“一子多特质”的物理建模。
// 动态孵化排序算子（Enforcer）：
// 排序是一种无损数据信息（Without loss of information） 的物理属性。如果父算子（如 MergeJoin）要求输入流必须按 dept_id ASC 排序，而子算子输出的数据是无序的，RelCollationTraitDef 就会在 CBO 优化期间出手，在它们之间强行插入一个 LogicalSort 或物理 Sort 算子，作为特质强制执行器（Enforcer）。
public class RelCollationTraitDef extends RelTraitDef<RelCollation> {
  public static final RelCollationTraitDef INSTANCE =
      new RelCollationTraitDef();

  private RelCollationTraitDef() {
  }

  @Override public Class<RelCollation> getTraitClass() {
    return RelCollation.class;
  }

  @Override public String getSimpleName() {
    return "sort";
  }

  @Override public boolean multiple() {
    return true;
  }

  @Override public RelCollation getDefault() {
    return RelCollations.EMPTY;
  }

  // Calcite 优化器中物理特质强制执行器（Enforcer）的核心实现。
  // 当父算子要求数据必须具备某种特定的物理有序性（toCollation），而子算子（rel）无法提供时，优化器就会调用此方法，在原算子上方强行挂载一个物理排序算子（Sort），强行对数据流进行重塑。
  // 优化器发现底层的 TableScan 无法满足上层 MergeJoin 要求的有序特质时，该方法直接出手，在 TableScan 上方拦截并组装一个全新的 LogicalSort 节点，使整棵子树对上层表现出具备 toCollation 的全新特质状态。
  @Override public @Nullable RelNode convert(
      RelOptPlanner planner,
      RelNode rel, // 输入的原始算子（当前可能无序或排序不满足要求
      RelCollation toCollation, // 上层期望的目标排序特质（如：按第 0 列升序 [0 ASC]）
      boolean allowInfiniteCostConverters) {
    // 如果传入的 toCollation 的内部字段排序定义为空（即 RelCollations.EMPTY，代表无序），说明上层压根不需要数据有序
    if (toCollation.getFieldCollations().isEmpty()) {
      // An empty sort doesn't make sense.
      return null;
    }

    // Create a logical sort, then ask the planner to convert its remaining
    // traits (e.g. convert it to an EnumerableSortRel if rel is enumerable
    // convention)
    // 调用静态工厂方法 LogicalSort.create 真正开始改写算子树
    // 将传入的原始算子 rel 作为它的孩子节点（input）
    // 将目标有序特质 toCollation 注入进去
    final Sort sort = LogicalSort.create(rel, toCollation, null, null);
    // 将新算子向优化器（Memo 空间）报到
    // 注册时传入 rel 作为关联参考，告诉优化器这个新产生的 sort 算子与原本的 rel 处于相同的等价集合（RelSet）中
    RelNode newRel = planner.register(sort, rel);
    // 以原算子的特质集合为基准，将其中的排序维度（Sort Trait） 强行替换为目标 toCollation，生成一个期望的特质大集合 newTraitSet。
    final RelTraitSet newTraitSet = rel.getTraitSet().replace(toCollation);
    // 检查注册返回后的 newRel 算子身上的物理特质是否和我们期望的目标特质完全一致。
    if (!newRel.getTraitSet().equals(newTraitSet)) {
      newRel = planner.changeTraits(newRel, newTraitSet);
    }
    return newRel;
  }

  @Override public boolean canConvert(
      RelOptPlanner planner, RelCollation fromTrait, RelCollation toTrait) {
    return true;
  }
}
