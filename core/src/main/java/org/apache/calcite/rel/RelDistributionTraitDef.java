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
import org.apache.calcite.rel.core.Exchange;
import org.apache.calcite.rel.logical.LogicalExchange;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Definition of the distribution trait.
 *
 * <p>Distribution is a physical property (i.e. a trait) because it can be
 * changed without loss of information. The converter to do this is the
 * {@link Exchange} operator.
 */
// RelDistributionTraitDef 是 Apache Calcite 优化器框架中用于管理“数据物理分布特质（Distribution）”的核心定义类
// 在分布式或并行计算引擎（如 Apache Hive, Trino, BlazingSQL 等基于 Calcite 构建的系统）中，数据在网络集群节点之间的物理分布状态是一项决定性能生死存亡的指标（例如：数据是按某列 Hash 分片、Broadcast 全局广播，还是全部集中在单点 Singleton）。
// RelDistributionTraitDef 的核心任务就是定义分布式特征维度的元数据，并在算子之间物理分布不匹配时，通过强行插入网络数据交换算子（Exchange / Shuffle）来重塑数据分布。
// 分布式物理状态建模（Distribution Modeling）：
//与排序类似，数据分布也是一种无损数据信息（Without loss of information）的物理属性。你可以通过在网络间挪动、重组数据（Shuffle），将一种分布状态改变为另一种分布状态。该类正是分布式维度在 Calcite 优化器（Memo 空间）中的最高元数据统帅。
// 动态挂载 Exchange 算子（Shuffle Enforcer）：
//如果父算子（例如一个分布式 HashJoin 算子）要求其左、右两个子输入流必须按照相同的关联键进行 HASH_DISTRIBUTED（哈希分片）对齐，而底层算子吐出的数据处于任意分布状态（ANY），RelDistributionTraitDef 就会在 CBO 优化期间出手，在它们之间强行塞入一个物理网络交换节点（Exchange），在分布式执行计划中产生实际的 Shuffle 动作。

public class RelDistributionTraitDef extends RelTraitDef<RelDistribution> {
  public static final RelDistributionTraitDef INSTANCE =
      new RelDistributionTraitDef();

  private RelDistributionTraitDef() {
  }

  @Override public Class<RelDistribution> getTraitClass() {
    return RelDistribution.class;
  }

  @Override public String getSimpleName() {
    return "dist";
  }

  @Override public RelDistribution getDefault() {
    return RelDistributions.ANY;
  }
  // Apache Calcite 优化器处理分布式执行计划时的核心拦截器与 Enforcer（物理特质强加者）。
  // 当上层分布式算子（如 HashJoin、Aggregate）对输入数据流有特定的集群分布要求（如按 Key 哈希分布、广播分布），而底层的子算子（rel）无法提供时，优化器就会调度此方法，在原算子树上方强行插入一个网络数据交换算子（Exchange），从而在物理执行计划中触发实际的 Shuffle 动作。
  @Override public @Nullable RelNode convert(RelOptPlanner planner,
      // 输入的、当前物理分布状态不达标的原始算子。
      RelNode rel,
      // 期望转换到的目标分布式特质（如 HASH_DISTRIBUTED、BROADCAST_DISTRIBUTED、SINGLETON）
      RelDistribution toDistribution,
      boolean allowInfiniteCostConverters) {
    // 如果目标分布特质是 RelDistributions.ANY，代表上层算子非常宽容，无论数据在分布式集群中怎么乱飞、怎么摆放，它都能直接接收。
    if (toDistribution == RelDistributions.ANY) {
      return rel;
    }

    // Create a logical Exchange, then ask the planner to convert its remaining
    // traits (e.g. convert it to an EnumerableSortRel if rel is enumerable
    // convention)
    // 动态孵化并创建网络数据交换节点（Shuffle 节点）
    final Exchange exchange = LogicalExchange.create(rel, toDistribution);
    RelNode newRel = planner.register(exchange, rel);
    final RelTraitSet newTraitSet = rel.getTraitSet().replace(toDistribution);
    if (!newRel.getTraitSet().equals(newTraitSet)) {
      newRel = planner.changeTraits(newRel, newTraitSet);
    }
    return newRel;
  }

  @Override public boolean canConvert(RelOptPlanner planner, RelDistribution fromTrait,
      RelDistribution toTrait) {
    return true;
  }
}
