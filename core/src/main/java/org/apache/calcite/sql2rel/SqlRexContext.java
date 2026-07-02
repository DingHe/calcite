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

import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexRangeRef;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.validate.SqlValidator;

/**
 * Contains the context necessary for a {@link SqlRexConvertlet} to convert a
 * {@link SqlNode} expression into a {@link RexNode}.
 */
// SqlRexContext 的主要作用是为 SqlRexConvertlet（SQL 到行表达式转换器）提供转换时所需的全部上下文环境和元数据支持。
// 在 Calcite 中，从 SQL 语法树（SqlNode）向关系代数树（RelNode）转换时，SQL 中的标量表达式、函数调用、字面量等（例如 a + 1 或 SUBSTR(name, 1, 3)）需要被转换为行表达式（RexNode）。
// 为了完成这一转换，转换器不仅需要知道当前节点本身，还需要知道：
// 当前查询是否是聚合查询、GROUP BY 的字段有哪些？
// 如何将一个嵌套的 SqlNode 子表达式继续向下转换？
// 如何利用类型工厂（RelDataTypeFactory）和表达式构建器（RexBuilder）来生成合法的类型和节点？
// SqlRexContext 就像一个百宝箱，把这些底层工具和当前查询的上下文状态封装在一起，传递给具体的转换逻辑使用。
public interface SqlRexContext {
  //~ Methods ----------------------------------------------------------------

  /**
   * Converts an expression from {@link SqlNode} to {@link RexNode} format.
   * 把SqlNode表达式转为RexNode表达式
   * @param expr Expression to translate
   * @return Converted expression
   */
  // 将一个通用的 SqlNode 表达式递归转换成 RexNode 行表达式
  // 在转换一个复杂的 SQL 表达式时（例如 score * 10 + bonus），当前转换器处理到 + 号时，需要分别去转换它的左子节点（score * 10）和右子节点（bonus）。
  // 这时转换器就可以通过调用 context.convertExpression(leftNode) 来实现嵌套、递归的转换。
  RexNode convertExpression(SqlNode expr);

  /**
   * If the operator call occurs in an aggregate query, returns the number of
   * columns in the GROUP BY clause. For example, for "SELECT count(*) FROM emp
   * GROUP BY deptno, gender", returns 2.
   * If the operator call occurs in window aggregate query, then returns 1 if
   * the window is guaranteed to be non-empty, or 0 if the window might be
   * empty.
   *
   * <p>Returns 0 if the query is implicitly "GROUP BY ()" because of an
   * aggregate expression. For example, "SELECT sum(sal) FROM emp".
   *
   * <p>Returns -1 if the query is not an aggregate query.
   * @return 0 if the query is implicitly GROUP BY (), -1 if the query is not
   * and aggregate query
   *
   * @see org.apache.calcite.sql.SqlOperatorBinding#getGroupCount()
   */
  // 获取当前聚合查询中 GROUP BY 子句的列数量。
  // 普通聚合：如果当前查询是类似 SELECT count(*) FROM emp GROUP BY deptno, gender 的聚合查询，该方法返回 2。
  // 隐式全局聚合：如果是隐式全局聚合（例如 SELECT sum(sal) FROM emp，没有显式写 GROUP BY），则返回 0。
  // 窗口函数聚合：如果该调用发生在窗口函数（Window Aggregate）中，当窗口被保证非空时返回 1，可能为空时返回 0。
  // 非聚合查询：如果当前查询完全不是聚合查询，则返回 -1。
  // 典型用途：帮助某些系统函数或自定义函数在转换时，判断自己是否处于聚合环境中，从而决定产生不同的关系代数表达形式。
  int getGroupCount();

  /**
   * Returns the {@link RexBuilder} to use to create {@link RexNode} objects.
   */
  // 获取 RexBuilder（行表达式构建器实例）。
  RexBuilder getRexBuilder();

  /**
   * Returns the expression used to access a given IN or EXISTS
   * {@link SqlSelect sub-query}.
   *
   * @param call IN or EXISTS expression
   * @return Expression used to access current row of sub-query
   */
  // 获取用于访问给定的 IN 或 EXISTS 子查询当前行的范围引用（RexRangeRef）。
  // 当 SQL 表达式中包含 IN (SELECT ...) 或者 EXISTS (SELECT ...) 这类关联/非关联子查询（SqlSelect sub-query）时，
  // Calcite 在将其转化为关系代数时，需要一个特定的引用来代表子查询产生的临时行范围。该方法专门用来注册或获取这个子查询表达式对应的 RexRangeRef。
  RexRangeRef getSubQueryExpr(SqlCall call);

  /**
   * Returns the type factory.
   */
  // 获取 RelDataTypeFactory（关系数据类型工厂）。
  RelDataTypeFactory getTypeFactory();

  /**
   * Returns the factory which supplies default values for INSERT, UPDATE, and
   * NEW.
   */
  // 获取初始化表达式工厂（InitializerExpressionFactory）
  // 门用于为 INSERT、UPDATE 或 NEW 语句中的列提供默认值（Default Value）。例如，当执行 INSERT 语句且某些列未指定值时，
  // 或者需要根据某些隐式规则（如自增列、当前时间戳 DEFAULT CURRENT_TIMESTAMP）生成初始值表达式时，转换器会调用此方法来获取默认值的生成策略。
  InitializerExpressionFactory getInitializerExpressionFactory();

  /**
   * Returns the validator.
   */
  // 获取验证器（SqlValidator）实例。
  SqlValidator getValidator();

  /**
   * Converts a literal.
   */
  // 将一个 SQL 字面量（SqlLiteral）转换为行表达式字面量（RexNode）
  // 专门针对常量的快捷转换方法。例如将 SQL 语法树中的整数字面量 100、字符串字面量 'abc' 或布尔值 TRUE，转换成关系代数层面上带有具体物理类型和值的 RexLiteral 节点。
  RexNode convertLiteral(SqlLiteral literal);
}
