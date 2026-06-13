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
package org.apache.calcite.materialize;

import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.util.Util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/** Table registered in the graph. */
// LatticeTable 是用于多维数据模型（Lattice，格子模型）以及物化视图自动推荐/改写机制中的一个核心元数据类
// 当 Calcite 处理复杂的星型模型（Star Schema）或雪花模型（Snowflake Schema）时，它会将多张表通过外键连接关系抽象为一张“图（Graph）”。在这个图结构中，每一个节点就代表一张表，而 LatticeTable 就是对这个图节点（Vertex）的封装。
// LatticeTable 的核心作用是在格子模型（Lattice Graph）中唯一标识并封装一张结构化的表。
// 在传统数据仓库（如 OLAP 多维分析）中，一张表可能在同一个连接图或同一个复杂查询中被多次引用（例如，在一笔交易中，“时间维表”可能既作为“下单时间”被关联，又作为“支付时间”被关联）。
// LatticeTable 的引入能够通过表的全局限定名（Qualified Name）来抽象和定位底层的物理/逻辑表。
// 它为高级物化（Materialization）管理器提供了标准统一的标识，使得算法在计算图的拓扑结构、依赖路径以及进行等价性判断（equals/hashCode）时，能够快速高效地识别是不是同一张底层的元数据表。
public class LatticeTable {
  // 指向 Calcite 优化器内部标准的表元数据对象（RelOptTable）
  // 最核心的底层对象。它包含了该表在优化器内部的一切真实信息，例如：完整的命名空间路径、表的行类型（Row Type）、列字段列表、表的数据量统计信息（Stats）以及如何在执行计划中扫描该表（TableScan）。
  public final RelOptTable t;
  // 该表在当前上下文中的简短别名。
  // 通过提取表完整限定名（Qualified Name）的最后一项来生成。例如，如果表的完整路径是 ["hive", "sales", "emp"]，那么别名就是 "emp"
  public final String alias;

  LatticeTable(RelOptTable table) {
    t = Objects.requireNonNull(table, "table");
    alias = Objects.requireNonNull(Util.last(table.getQualifiedName()));
  }

  @Override public int hashCode() {
    return t.getQualifiedName().hashCode();
  }

  @Override public boolean equals(@Nullable Object obj) {
    return this == obj
        || obj instanceof LatticeTable
        && t.getQualifiedName().equals(
            ((LatticeTable) obj).t.getQualifiedName());
  }

  @Override public String toString() {
    return t.getQualifiedName().toString();
  }

  RelDataTypeField field(int i) {
    return t.getRowType().getFieldList().get(i);
  }
}
