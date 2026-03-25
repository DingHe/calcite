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

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Table that can be scanned without creating an intermediate relational
 * expression.
 */
// ScannableTable 是实现**自定义数据源（Adapter）**最简单、最直接的接口。
// 它属于“物理层”接口，直接定义了数据如何从外部系统流入 Calcite 的执行引擎。
// ScannableTable 的核心作用是提供一种最基础的全表扫描能力。
// 数据读取入口：它告诉 Calcite 如何读取该表的所有行。
// 无需中间逻辑：与其他复杂的表接口（如 FilterableTable 或 TranslatableTable）不同，ScannableTable 不支持在数据源端进行过滤或投影。
// 它只是简单地把数据“全部倒出来”，由 Calcite 内置的 Enumerable 算子在内存中完成后续的过滤（Filter）和选择（Project）。
// 快速适配：如果你有一个简单的 CSV 文件、内存中的 List 或者是某个简单的 API，实现这个接口是让它们支持 SQL 查询的最快方式。
// DataContext root：这是 Calcite 运行时的上下文对象。通过它，你可以获取查询时的变量、当前连接的配置、或者自定义的参数（例如从 SQL 传入的变量）。
// Enumerable 是 Calcite 仿照 LINQ 设计的一个迭代器接口（位于 org.apache.calcite.linq4j）。它不仅能遍历数据，还支持转换操作。
// Object[] 代表一行数据。数组中的每个元素对应表定义（RelDataType）中的一列。
public interface ScannableTable extends Table {
  /** Returns an enumerator over the rows in this Table. Each row is represented
   * as an array of its column values. */
  Enumerable<@Nullable Object[]> scan(DataContext root);
}
