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
package org.apache.calcite.rex;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;

/**
 * Visitor pattern for traversing a tree of {@link RexNode} objects.
 *
 * @see org.apache.calcite.util.Glossary#VISITOR_PATTERN
 * @see RexShuttle
 * @see RexVisitorImpl
 *
 * @param <R> Return type
 */
// RexVisitor 是 Apache Calcite 中的一个核心接口，采用了经典的访问者模式（Visitor Pattern）。
// 它专门用于遍历和处理 RexNode（行表达式节点）构成的树状结构。
// 在 Calcite 中，SQL 语句中的表达式（如 a + b > 10）被解析并转化为一系列的 RexNode。
// 由于 RexNode 是一个复杂的树状结构（例如 RexCall 包含子节点），我们需要一种优雅的方式来对这些节点进行操作，而不需要编写大量的 if (node instanceof ...) 语句。
// RexVisitor 的作用包括：
// 解耦逻辑与结构：将表达式的遍历逻辑（由 RexNode 的 accept 方法负责）与具体的业务处理逻辑（由 RexVisitor 的实现类负责）分离。
// 统一接口：为各种不同类型的表达式节点提供统一的访问入口。
// 类型安全：通过泛型 <R> 定义访问后的返回值类型，支持从表达式树中提取信息（如列名、常量值）或将其转换为其他格式。
public interface RexVisitor<R> {
  //~ Methods ----------------------------------------------------------------
  // 访问输入引用。
  // 代表对底层数据流中某一列的引用（通常由索引表示，如 $0）。
  R visitInputRef(RexInputRef inputRef);
  // 访问局部引用。
  // 通常用于程序化的 Rex 表达式列表中，引用之前计算出的表达式。
  R visitLocalRef(RexLocalRef localRef);
  // 访问常量值。
  // 代表 SQL 中的字面量，如 123, 'abc', TRUE 或 NULL。
  R visitLiteral(RexLiteral literal);
  // 访问函数或操作符调用。
  // 这是最常用的方法。代表 a + b, CAST(x AS INT), CASE... 等。它通常包含子节点（操作数）。
  R visitCall(RexCall call);
  // 访问窗口函数（Window Function）。
  // 代表带有 OVER 子句的调用，如 RANK() OVER (PARTITION BY ...)。
  R visitOver(RexOver over);
  // 访问相关变量。
  // 用于子查询中，引用外部查询的列（Correlation variable）。
  R visitCorrelVariable(RexCorrelVariable correlVariable);
  // 访问动态参数。
  // 代表 SQL 中的占位符 ?。
  R visitDynamicParam(RexDynamicParam dynamicParam);
  // 访问范围引用。
  // 代表对某一范围内的行或列集合的引用。
  R visitRangeRef(RexRangeRef rangeRef);
  // 访问字段成员。
  // 代表对结构化类型（如 ROW）或特定对象内部字段的访问。
  R visitFieldAccess(RexFieldAccess fieldAccess);
  // 访问子查询。
  // 代表出现在表达式位置的子查询，如 WHERE x IN (SELECT ...)。
  R visitSubQuery(RexSubQuery subQuery);
  // 访问表输入引用。
  // 在处理多表关联或特定的元数据操作时，引用特定表的列。
  R visitTableInputRef(RexTableInputRef fieldRef);
  // 访问模式匹配中的字段引用。
  // 用于 MATCH_RECOGNIZE 子句中的模式定义。
  R visitPatternFieldRef(RexPatternFieldRef fieldRef);
  // 访问 Lambda 表达式。
  // 用于支持高阶函数或特定算子中的匿名函数逻辑。
  R visitLambda(RexLambda lambda);
  // 访问 Lambda 表达式内部的变量引用。
  R visitLambdaRef(RexLambdaRef lambdaRef);

  /** Visits a list and writes the results to another list. */
  // 作用：遍历表达式集合，并将每个节点的处理结果（返回值）存入指定的列表 out。
  // 用途：当你需要转换一组表达式并保留结果时使用。
  default void visitList(Iterable<? extends RexNode> exprs, List<R> out) {
    for (RexNode expr : exprs) {
      out.add(expr.accept(this));
    }
  }

  /** Visits a list and returns a list of the results.
   * The resulting list is immutable and does not contain nulls. */
  // 遍历表达式集合，返回一个包含所有处理结果的不可变列表。
  default List<R> visitList(Iterable<? extends RexNode> exprs) {
    final List<R> out = new ArrayList<>();
    visitList(exprs, out);
    return ImmutableList.copyOf(out);
  }

  /** Visits a list of expressions. */
  // 逐个访问列表中的表达式，但不关心返回值。
  default void visitEach(Iterable<? extends RexNode> exprs) {
    for (RexNode expr : exprs) {
      expr.accept(this);
    }
  }
}
