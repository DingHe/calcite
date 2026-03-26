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

import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;

/**
 * Extension to {@link Table} that specifies how it is to be translated to
 * a {@link org.apache.calcite.rel.RelNode relational expression}.
 * <p>It is optional for a Table to implement this interface. If Table does
 * not implement this interface, it will be converted to an
 * {@link org.apache.calcite.adapter.enumerable.EnumerableTableScan}.
 * Generally a Table will implement this interface to
 * create a particular subclass of RelNode, and also register rules that act
 * on that particular subclass of RelNode.
 */
// TranslatableTable 接口是实现**自定义数据源（Custom Adapter）**的关键跳板。
// 它决定了当 SQL 语句引用某张表时，Calcite 优化器应该如何将其从静态的“元数据表”转化为动态执行的“关系算子”。
// TranslatableTable 的核心作用是自定义逻辑扫描算子（Table Scan）的生成过程。
// 在 Calcite 中，如果一个 Table 没有实现这个接口，优化器会默认将其转化为 EnumerableTableScan（即简单的全表扫描内存迭代）。
// 实现此接口后，你可以让表直接转化为特定的物理算子（例如 JdbcTableScan、CassandraTableScan 或 ElasticsearchTableScan）。
public interface TranslatableTable extends Table {
  /** Converts this table into a {@link RelNode relational expression}. */
  RelNode toRel(
      RelOptTable.ToRelContext context,
      RelOptTable relOptTable);
}
