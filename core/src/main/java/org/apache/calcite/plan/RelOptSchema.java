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
package org.apache.calcite.plan;

import org.apache.calcite.rel.type.RelDataTypeFactory;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * A <code>RelOptSchema</code> is a set of {@link RelOptTable} objects.
 */
// RelOptSchema 是一个至关重要的接口。它处于查询优化层（Optimization Layer），主要负责将逻辑上的表名映射为优化器可以理解的表对象。
// RelOptSchema 的核心作用是 “表（Table）的集合及其元数据的访问入口”。
// 在 SQL 优化过程中，当 Calcite 处理类似于 SELECT * FROM sales.emps 的语句时，优化器需要知道 sales.emps 到底代表什么。RelOptSchema 就扮演了这个“目录”的角色：
// 桥梁作用：它连接了底层的元数据层（如数据字典、Catalog）和上层的查询优化器（Planner）。
// 提供优化信息：它不仅提供表结构，还允许 Schema 向优化器注册特定的转换规则（Rules）。
// 类型支持：确保所有从该 Schema 获取的表都使用统一的类型工厂（Type Factory）。
// 理解 RelOptSchema 与普通的 Schema 之间的关系，本质上是理解 “优化器视角” 与 “元数据存储视角” 的区别。
// Schema 是数据的原始定义，而 RelOptSchema 是为了优化器（Planner）专门包装后的接口。
// Schema (位于 org.apache.calcite.schema 包)：
// 这是最基础的元数据接口。它直接反映了数据源的结构（比如数据库里的库、表、函数）。它是给“人”或者给“目录系统”看的，侧重于存储结构。
// RelOptSchema (位于 org.apache.calcite.plan 包)：
// 这是优化器专用的接口。优化器在进行查询重写或成本计算时，并不直接操作原始的 Schema，而是通过 RelOptSchema 来访问。
// 关键点：RelOptTable 相比普通的 Table，多了很多优化器关心的“料”，比如行数估计、索引信息以及该表属于哪个逻辑集群（RelOptCluster）。
public interface RelOptSchema {
  //~ Methods ----------------------------------------------------------------

  /**
   * Retrieves a {@link RelOptTable} based upon a member access.
   *
   * <p>For example, the Saffron expression <code>salesSchema.emps</code>
   * would be resolved using a call to <code>salesSchema.getTableForMember(new
   * String[]{"emps" })</code>.
   *
   * <p>Note that name.length is only greater than 1 for queries originating
   * from JDBC.
   *
   * @param names Qualified name
   */
  // 根据给定的限定名称（Qualified Name）检索并返回一个 RelOptTable 对象。
  // names：一个字符串列表。例如，对于 schema.table，列表内容为 ["schema", "table"]；对于简单的 emps，列表为 ["emps"]。
  // 返回值：返回一个 RelOptTable 实例。如果找不到该表，则返回 null。
  @Nullable RelOptTable getTableForMember(List<String> names);

  /**
   * Returns the {@link RelDataTypeFactory type factory} used to generate
   * types for this schema.
   */
  // 获取用于为该 Schema 生成数据类型的 类型工厂（RelDataTypeFactory）。
  // 在 Calcite 中，所有的字段类型（如 INTEGER, VARCHAR(20)）都需要通过工厂来创建和管理。
  RelDataTypeFactory getTypeFactory();

  /**
   * Registers all of the rules supported by this schema. Only called by
   * {@link RelOptPlanner#registerSchema}.
   */
  // 向指定的优化器（Planner）注册该 Schema 所支持的优化规则（Rules）。
  // planner：当前的优化器实例（例如 VolcanoPlanner）。
  void registerRules(RelOptPlanner planner) throws Exception;
}
