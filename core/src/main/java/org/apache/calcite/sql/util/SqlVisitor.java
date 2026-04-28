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
package org.apache.calcite.sql.util;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;

/**
 * Visitor class, follows the
 * {@link org.apache.calcite.util.Glossary#VISITOR_PATTERN visitor pattern}.
 *
 * <p>The type parameter <code>R</code> is the return type of each <code>
 * visit()</code> method. If the methods do not need to return a value, use
 * {@link Void}.
 *
 * @see SqlBasicVisitor
 * @see SqlNode#accept(SqlVisitor)
 * @see SqlOperator#acceptCall
 * R代表返回值的类型
 * @param <R> Return type
 */
// SqlVisitor 是处理 SQL 抽象语法树（AST）的核心接口。它采用了经典的 访问者模式（Visitor Pattern）。
// SqlVisitor 的核心作用是解耦 SQL 语法树的结构与对其进行的操作。
// SQL 解析后会生成一颗由 SqlNode 及其子类构成的树。如果我们需要对这棵树进行遍历（例如：查找所有的表名、修改某个函数调用、或者将语法树转换回 SQL 字符串），我们不需要在每个 SqlNode 类中增加业务逻辑，而是创建一个实现 SqlVisitor 接口的类。
// 参数 <R>：代表访问方法的返回值类型。如果不需要返回值，通常指定为 Void。
// 分发机制：通过 SqlNode.accept(SqlVisitor) 方法，系统会根据节点的实际类型（如 SqlLiteral 或 SqlCall）自动调用访客中对应的 visit 方法。
public interface SqlVisitor<R> {
  //~ Methods ----------------------------------------------------------------

  /**
   * Visits a literal.
   *
   * @param literal Literal
   * @see SqlLiteral#accept(SqlVisitor)
   */
  // 参数：SqlLiteral（SQL 字面量，如数字 10、字符串 'abc'、布尔值 TRUE）。
  // 作用：当遍历到常量值节点时触发。
  // 典型用途：提取 SQL 中的常量、进行常量折叠优化。
  R visit(SqlLiteral literal);

  /**
   * Visits a call to a {@link SqlOperator}.
   *
   * @param call Call
   * @see SqlCall#accept(SqlVisitor)
   */
  // 参数：SqlCall（SQL 调用，包括函数调用如 SUM(x)、操作符如 a + b、甚至整个 SELECT 语句）。
  // 作用：访问语法树中最常见的节点。大多数 SQL 指令都是以 SqlCall 形式存在的。
  // 典型用途：分析函数依赖、重写查询逻辑。
  R visit(SqlCall call);

  /**
   * Visits a list of {@link SqlNode} objects.
   *
   * @param nodeList list of nodes
   * @see SqlNodeList#accept(SqlVisitor)
   */
  // 参数：SqlNodeList（SQL 节点列表）。
  // 作用：访问一组节点，例如 SELECT 后面的多个投影列、GROUP BY 后的多个字段。
  // 典型用途：递归地遍历列表中的每一个元素。
  R visit(SqlNodeList nodeList);

  /**
   * Visits an identifier.
   *
   * @param id identifier
   * @see SqlIdentifier#accept(SqlVisitor)
   */
  // 参数：SqlIdentifier（标识符，如表名 users、列名 id）。
  // 典型用途：列名解析、表名替换、元数据校验。
  R visit(SqlIdentifier id);

  /**
   * Visits a datatype specification.
   *
   * @param type datatype specification
   * @see SqlDataTypeSpec#accept(SqlVisitor)
   */
  // 参数：SqlDataTypeSpec（数据类型定义，如 CAST(x AS VARCHAR(10)) 中的 VARCHAR(10)）。
  // 作用：处理 SQL 中显式声明的数据类型定义。
  R visit(SqlDataTypeSpec type);

  /**
   * Visits a dynamic parameter.
   *
   * @param param Dynamic parameter
   * @see SqlDynamicParam#accept(SqlVisitor)
   */
  // 参数：SqlDynamicParam（动态参数，即 SQL 中的占位符 ?）。
  // 作用：处理参数化查询中的变量部分。
  R visit(SqlDynamicParam param);

  /**
   * Visits an interval qualifier.
   *
   * @param intervalQualifier Interval qualifier
   * @see SqlIntervalQualifier#accept(SqlVisitor)
   */
  // 参数：SqlIntervalQualifier（时间间隔修饰符，如 INTERVAL '1' DAY 中的 DAY）。
  // 作用：处理时间间隔的精度和单位。
  R visit(SqlIntervalQualifier intervalQualifier);

  /** Asks a {@code SqlNode} to accept this visitor. */
  // 参数：SqlNode（所有 SQL 节点的基类）。
  // 当你手里有一个泛型的 SqlNode 但不知道具体子类型时，调用此方法可以利用多态性跳转到正确的 visit 重载方法上。
  default R visitNode(SqlNode n) {
    return n.accept(this);
  }
}
