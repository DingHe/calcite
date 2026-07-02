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
package org.apache.calcite.sql2rel;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

/**
 * Provides context for {@link InitializerExpressionFactory} methods.
 */
// InitializerContext 的主要作用是为 InitializerExpressionFactory（列初始化表达式工厂）提供完整的生命周期上下文支持，涵盖了从“列的定义文本”到“行表达式（RexNode）”的完整转换流程。
// 当我们在数据库中创建表（DDL 阶段）或向表中插入数据时，经常会遇到以下场景：
// 默认值（Default Value）：例如 CREATE TABLE t (id INT, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)。
// 生成列 / 计算列（Generated/Computed Column）：例如 CREATE TABLE t (a INT, b INT AS (a + 1) VIRTUAL)。
// 对于这些列，它们在元数据中最初可能只是一个 SQL 字符串片段（如 "a + 1"）。为了能在后续的查询或插入优化中使用这些表达式，
// Calcite 需要一个上下文来将这段字符串经历 解析（Parse） -> 校验（Validate） -> 转换（Convert） 的全套流程。InitializerContext 就是专门用来桥接并提供这三步能力的环境容器。

public interface InitializerContext {
  // 获取行表达式构建器（RexBuilder）实例。
  RexBuilder getRexBuilder();

  /**
   * Parses a column computation expression for a table.
   *
   * <p>Usually this expression is declared in the {@code CREATE TABLE}
   * statement, e.g.
   *
   * <blockquote><pre>{@code
   *   create table t(
   *     a int not null,
   *     b varchar(5) as (my_udf(a)) virtual,
   *     c int not null as (a + 1));
   * }</pre></blockquote>
   *
   * <p>You can use the string format expression "my_udf(a)" and "a + 1"
   * as the initializer expression of column b and c.
   *
   * <p>Calcite doesn't really need this now because the DDL nodes
   * can be executed directly from {@code SqlNode}s, but we still provide the way
   * to initialize from a SQL-like string, because a string can be used to persist easily and
   * the column expressions are important part of the table metadata.
   *
   * @param config parse config
   * @param expr   the SQL-style column expression
   * @return a {@code SqlNode} instance
   */
  // 将 SQL 文本格式的列计算/初始化表达式解析为语法树节点（SqlNode）
  default SqlNode parseExpression(SqlParser.Config config, String expr) {
    SqlParser parser = SqlParser.create(expr, config);
    try {
      return parser.parseExpression();
    } catch (SqlParseException e) {
      throw new RuntimeException("Failed to parse expression " + expr, e);
    }
  }

  /**
   * Validate the expression with a base table row type. The expression may reference the fields
   * of the row type defines.
   *
   * @param rowType the table row type
   * @param expr    the expression
   * @return a validated {@code SqlNode}, usually it transforms
   * from a {@code SqlUnresolvedFunction} to a resolved one
   */
  // 结合表的基础行结构（Row Type），对输入的表达式节点进行语义校验。
  // 设计初衷：解析出来的 SqlNode 只是单纯的语法结构，Calcite 还不知道表达式里的变量指代什么。例如表达式 a + 1，Calcite 需要知道 a 是不是当前表里的一列、它的类型是什么。
  // 通过传入当前表的标准行类型 rowType（包含表的所有列名和对应类型），该方法对 expr 进行校验。在校验过程中，它会将诸如未解析的函数（SqlUnresolvedFunction）绑定并转换为确定类型的具体函数，同时完成隐式类型提升或符号绑定，返回一个已验证（Validated）的 SqlNode。
  SqlNode validateExpression(RelDataType rowType, SqlNode expr);

  /**
   * Converts a {@code SqlNode} to {@code RexNode}.
   *
   * <p>Caution that the {@code SqlNode} must be validated,
   * you can use {@link #validateExpression} to validate if the {@code SqlNode}
   * is un-validated.
   *
   * @param expr the expression of sql node to convert
   * @return a converted {@code RexNode} instance
   */
  // 将一个已经过验证的 SqlNode 表达式转换为关系代数层面的行表达式（RexNode）。
  // 方法要求传入的 SqlNode 必须是已经调用 validateExpression 校验过的。如果传入未验证的节点，会导致转换失败或抛出异常。
  RexNode convertExpression(SqlNode expr);
}
