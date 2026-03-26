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

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rex.RexNode;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * A table that can be modified.
 * <p>NOTE: The current API is inefficient and experimental. It will change
 * without notice.
 *
 * @see ModifiableView
 */
// ModifiableTable 接口定义了可写表的核心标准。如果一个自定义数据源（如 HBase, MongoDB 或自定义的文件系统）支持 INSERT、UPDATE 或 DELETE 操作，
// 那么该数据源对应的 Table 类通常需要实现这个接口。
// ModifiableTable 扩展了 QueryableTable，其核心作用是打通 SQL 变更语句与底层存储之间的执行链路。
// 声明写能力：它告诉 Calcite 优化器，这张表不仅可以被查询（SELECT），还可以被修改。
// 物理变更映射：它负责将逻辑上的变更操作（例如 SQL 中的 INSERT INTO ...）转化为 Calcite 内部的逻辑算子 TableModify。
// 轻量级修改支持：通过提供直接操作底层集合（Collection）的入口，支持在某些简单场景下直接绕过复杂的 SQL 优化逻辑进行数据更新。
public interface ModifiableTable extends QueryableTable {
  /** Returns the modifiable collection.
   * Modifying the collection will change the table's contents. */
  // 返回一个可直接操作的集合对象。
  // 核心逻辑：如果该方法返回了一个 Collection，那么对这个返回集合进行的任何 add、remove 或 clear 操作，都应该直接反映到真实的底层数据存储中。
  // 适用于内存数据库或简单的基于集合的存储引擎。
  // 在执行简单的 INSERT 操作且不需要复杂的物理计划转换时，Calcite 可能会利用此集合直接同步数据。
  // 对于分布式系统（如 HDFS 或远程数据库），通常此方法返回 null，因为它们无法通过简单的 Java 集合接口进行修改。
  @Nullable Collection getModifiableCollection();

  /** Creates a relational expression that modifies this table. */
  // 该接口中最核心的方法，负责构建物理变更算子。
  // 将逻辑修改请求转化为一个具体的关系表达式节点（RelNode），即 TableModify。
  // 该节点会被加入到 Calcite 的算子树中，最终由执行引擎（如物理扫描或 JDBC 提交）去执行具体的写入操作
  TableModify toModificationRel(
      RelOptCluster cluster,
      RelOptTable table,
      Prepare.CatalogReader catalogReader,
      RelNode child,
      TableModify.Operation operation,
      @Nullable List<String> updateColumnList,
      @Nullable List<RexNode> sourceExpressionList,
      boolean flattened);
}
