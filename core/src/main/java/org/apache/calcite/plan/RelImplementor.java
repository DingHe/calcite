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

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.sql.validate.SqlConformance;

/**
 * This is a marker interface for a callback used to convert a tree of
 * {@link RelNode relational expressions} into a plan. Calling
 * conventions typically have their own protocol for walking over a
 * tree, and correspondingly have their own implementors
 */
// 扮演着“算子树转译/物理实现大管家”的顶层抽象角色。
// 转译生态的顶层标志接口（Marker Interface）
// 正如其源码注释所述，这是一个标志性回调接口。它的主要目的是定义一个通用的契约，用于将一条由 RelNode（关系代数表达式）组成的抽象语法树，最终平滑地翻译/实现（Implement）为某种具体的物理执行计划。
// 多流派物理实现的“总纲”：
// 在 Calcite 中，不同的物理调用约定（Convention）有着完全不同的物理执行哲学和树遍历协议。因此，不同的物理流派都会去扩展这个接口，衍生出属于自己流派的专属大管家。
// EnumerableRelImplementor：用于内存迭代流派（EnumerableConvention）。它在内部维护了一个极其庞大的 Java 抽象语法树（AST）和变量池，负责把算子树翻译成货真价实的 Java 源代码。
// JdbcImplementor：用于 JDBC 方言流派（JdbcConvention）。它负责把一棵物理算子树，逆向翻译回对应数据库方言的 SQL 字符串（如 Oracle 或 MySQL 识别的 SQL）。
public interface RelImplementor {
  /** Returns the desired SQL conformance. */
  // 获取当前代码生成或计划转译阶段所期望遵循的 SQL 兼容性/规范标准（SQL Conformance）。
  SqlConformance getConformance();
}
