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

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlKind;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Represents a lambda expression.
 */
// 在 Apache Calcite 中，RexLambda 类是行表达式（Row Expression）体系中用于支持函数式编程特性的核心类。它封装了一个完整的 Lambda 表达式定义。
// RexLambda 代表一个 Lambda 表达式（也称为匿名函数）。
// 定义函数式逻辑：它将一组参数（Parameters）与一个表达式主体（Body/Expression）绑定在一起。
// 高阶函数支持：它通常作为参数传递给高阶函数（High-order Functions）。例如，在 SQL 中处理数组或复杂类型时，FILTER(arr, x -> x > 5) 里的 x -> x > 5 部分就会在 Calcite 内部表示为一个 RexLambda。

public class RexLambda extends RexNode {
  //~ Instance fields --------------------------------------------------------
  // 存储 Lambda 表达式的参数列表。
  // 这是一个由 RexLambdaRef 对象组成的列表。每个 RexLambdaRef 定义了参数的名称、类型和在该 Lambda 内部的索引位置。它使用 ImmutableList 存储，确保了线程安全和不可变性。
  private final List<RexLambdaRef> parameters;
  // 存储 Lambda 表达式的主体（Body）。
  private final RexNode expression;

  //~ Constructors -----------------------------------------------------------

  RexLambda(List<RexLambdaRef> parameters, RexNode expression) {
    this.parameters = ImmutableList.copyOf(parameters);
    this.expression = Objects.requireNonNull(expression, "expression");
  }

  //~ Methods ----------------------------------------------------------------

  @Override public RelDataType getType() {
    return expression.getType();
  }

  @Override public SqlKind getKind() {
    return SqlKind.LAMBDA;
  }

  @Override public <R> R accept(RexVisitor<R> visitor) {
    return visitor.visitLambda(this);
  }

  @Override public <R, P> R accept(RexBiVisitor<R, P> visitor, P arg) {
    return visitor.visitLambda(this, arg);
  }

  public RexNode getExpression() {
    return expression;
  }

  public List<RexLambdaRef> getParameters() {
    return parameters;
  }

  @Override public boolean equals(@Nullable Object o) {
    return this == o
        || o instanceof RexLambda
        && expression.equals(((RexLambda) o).expression)
        && parameters.equals(((RexLambda) o).parameters);
  }

  @Override public int hashCode() {
    return Objects.hash(expression, parameters);
  }

  @Override public String toString() {
    if (digest == null) {
      StringBuilder sb = new StringBuilder();
      sb.append("(");
      for (Ord<RexLambdaRef> ord : Ord.zip(parameters)) {
        final RexLambdaRef parameter = ord.e;
        if (ord.i != 0) {
          sb.append(", ");
        }
        sb.append(parameter.getName());
      }
      sb.append(") -> ");
      sb.append(expression);
      digest = sb.toString();
    }
    return digest;
  }
}
