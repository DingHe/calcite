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
package org.apache.calcite.schema;

import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.util.ImmutableBitSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Statistics about a {@link Table}.
 *
 * <p>Each of the methods may return {@code null} meaning "not known".
 *
 * @see Statistics
 */
// 在 Apache Calcite 的元数据层中，Statistic 接口扮演着“优化器之眼”的角色。
// 它不直接参与 SQL 的解析，但它是 基于代价的优化器（CBO, Cost-Based Optimizer） 做出正确决策的生命线。
// Statistic 接口定义了关于 Table（表）的统计信息和结构化约束。
// 辅助代价估算：通过提供行数，优化器可以计算不同执行计划的代价（CPU/内存/网络），从而选择最快的方案（例如选择小表作为 Join 的 Build 侧）。
// 优化规则触发：通过提供唯一键（Key）和外键约束信息，优化器可以执行诸如“消除重复（Distinct Elimination）”或“外连接转内连接”等逻辑优化。
// 数据分布感知：在分布式系统（如 Flink/Spark）中，通过提供分片（Distribution）和排序（Collation）信息，减少不必要的 Shuffle 操作。
public interface Statistic {
  /** Returns the approximate number of rows in the table. */
  // 基础行数统计
  // 返回该表的近似行数。
  // 这是 CBO 最依赖的指标。如果返回 null，Calcite 通常会使用一个默认的启发式常数（如 100 行）。返回 Double 类型是为了支持非常巨大的表或采样估算。
  default @Nullable Double getRowCount() {
    return null;
  }

  /** Returns whether the given set of columns is a unique key, or a superset
   * of a unique key, of the table.
   */
  // 唯一性约束 (Keys)
  // 参数：columns - 一个位图（BitSet），表示一组列的索引集合。
  // 作用：判断给定的这组列是否构成该表的唯一键（或者包含唯一键的超集）。
  // 应用：如果优化器知道某几列是唯一的，那么在执行 GROUP BY 或 DISTINCT 时，如果这些列都在其中，就可以直接移除去重算子。
  default boolean isKey(ImmutableBitSet columns) {
    return false;
  }

  /** Returns a list of unique keys, or null if no key exist. */
  // 作用：主动返回该表已知的所有唯一键列表。
  default @Nullable List<ImmutableBitSet> getKeys() {
    return null;
  }

  /** Returns the collection of referential constraints (foreign-keys)
   * for this table. */
  // 参照完整性 (Foreign Keys)
  // 返回该表定义的**引用约束（外键）**集合。
  // 应用：在多表关联查询中，外键约束可以触发“关联消除”优化（Join Elimination）。例如：如果查询 Orders 表并关联 Customers 表，且外键保证 Orders 中的 customer_id 必定存在于 Customers 且不为空，优化器可能会直接删掉对 Customers 表的扫描。
  default @Nullable List<RelReferentialConstraint> getReferentialConstraints() {
    return null;
  }

  /** Returns the collections of columns on which this table is sorted. */
  // 描述数据的物理排序方式。
  // 应用：如果底层存储（如 Parquet 文件或聚簇索引）已经按 id 排序，Statistic 会返回这个排序信息。
  // 优化器看到后，在处理 ORDER BY id 或 SortMergeJoin 时，就可以直接省去昂贵的排序（Sort）算子。
  default @Nullable List<RelCollation> getCollations() {
    return null;
  }

  /** Returns the distribution of the data in this table. */
  // 作用：描述数据在分布式节点之间的分区方式（如 Hash 分区、Range 分区或 Single 节点）。
  // 应用：在分布式 SQL 引擎中极其重要。如果两个表已经按相同的列进行了 Hash 分区，优化器会决定执行本地 Join（Colocated Join），从而避免海量数据在网络中 Shuffle。
  default @Nullable RelDistribution getDistribution()  {
    return null;
  }
}
