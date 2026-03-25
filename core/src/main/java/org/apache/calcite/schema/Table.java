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

import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlNode;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Table.
 *
 * <p>The typical way for a table to be created is when Calcite interrogates a
 * user-defined schema in order to validate names appearing in a SQL query.
 * Calcite finds the schema by calling {@link Schema#getSubSchema(String)} on
 * the connection's root schema, then gets a table by calling
 * {@link Schema#getTable(String)}.
 *
 * <p>Note that a table does not know its name. It is in fact possible for
 * a table to be used more than once, perhaps under multiple names or under
 * multiple schemas. (Compare with the
 * <a href="http://en.wikipedia.org/wiki/Inode">i-node</a> concept in the UNIX
 * filesystem.)
 *
 * <p>A particular table instance may also implement {@link Wrapper},
 * to give access to sub-objects.
 * @see TableMacro
 */
// 在 Calcite 的世界观里，Table 不仅仅是数据库里的一张物理表。
// 抽象元数据层：它是对“数据源”的一种抽象。无论底层是 MySQL 的 B-Tree、CSV 文件、Elasticsearch 的索引，还是内存中的集合，
// 只要实现了 Table 接口，Calcite 就能理解它的结构并对其执行 SQL。
// 解耦命名与实体：如源码注释所述，Table 实例本身不知道自己的名字（类似 Unix 的 i-node）。一个 Table 对象可以被挂载在不同的 Schema 下，或者在同一 Schema 下拥有多个别名。
// 桥接逻辑与物理：它提供了 SQL 校验（Validation）所需的列信息、优化器（Optimizer）所需的统计信息，以及执行引擎所需的表类型信息。

public interface Table {
  /** Returns this table's row type.
   *
   * <p>This is a struct type whose
   * fields describe the names and types of the columns in this table.
   *
   * <p>The implementer must use the type factory provided. This ensures that
   * the type is converted into a canonical form; other equal types in the same
   * query will use the same object.
   *
   * @param typeFactory Type factory with which to create the type
   * @return Row type
   */
  // 定义表的“行类型”（Schema 结构）。
  // 返回一个 RelDataType 对象，它本质上是一个结构体（Struct），包含了表中所有列的名称、类型、精度以及是否可为空。
  RelDataType getRowType(RelDataTypeFactory typeFactory);

  /** Returns a provider of statistics about this table. */
  // 提供该表的统计信息。
  // 返回一个 Statistic 对象。该对象包含：
  // Row Count：表的估算行数。
  // Keys：哪些列是主键或唯一键。
  // Collation：数据是否按特定顺序排列。
  // Referential Constraints：外键约束。
  // 重要性：这是 CBO（基于代价的优化器） 的粮食。优化器根据行数决定是使用 Hash Join 还是 Nested Loop Join。
  Statistic getStatistic();

  /** Type of table. */
  // 定义表的物理/逻辑分类。
  // 回 Schema.TableType 枚举值，常见的包括：
  // TABLE：普通的持久化表。
  // VIEW：视图。
  // STREAM：流式表（用于流计算）。
  // TEMPORARY_TABLE：临时表。
  // 重要性：它决定了元数据驱动程序（如 JDBC DatabaseMetaData）如何向客户端暴露这张表。
  Schema.TableType getJdbcTableType();

  /**
   * Determines whether the given {@code column} has been rolled up.
   * */
  // 判断指定列是否已经过“上卷”（Rolled Up）处理。
  // 上卷概念：在 OLAP（如 Druid 或 Kylin）中，原始数据常被预聚合。例如，原始数据有秒级时间戳，但表里只存储了按天聚合后的数据。
  boolean isRolledUp(String column);

  /**
   * Determines whether the given rolled up column can be used inside the given aggregate function.
   * You can assume that {@code isRolledUp(column)} is {@code true}.
   * 上卷的列是否可以应用到给定的聚合函数
   * @param column The column name for which {@code isRolledUp} is true
   * @param call The aggregate call
   * @param parent Parent node of {@code call} in the {@link SqlNode} tree
   * @param config Config settings. May be null
   * @return true iff the given aggregate call is valid
   */
  // 功能：判断上卷列是否可以用于特定的聚合函数。
  // column：列名。
  // call：SQL 聚合调用（如 SUM(col)）。
  boolean rolledUpColumnValidInsideAgg(String column, SqlCall call,
      @Nullable SqlNode parent, @Nullable CalciteConnectionConfig config);
}
