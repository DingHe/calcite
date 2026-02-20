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
public class RexLambda extends RexNode {
  //~ Instance fields --------------------------------------------------------

  private final List<RexLambdaRef> parameters; //保存了 Lambda 表达式的参数列表，每个参数通过 RexLambdaRef 表示
  private final RexNode expression; //是 Lambda 表达式的主体部分，表示 Lambda 的实际计算或操作，它是一个 RexNode 对象，可能是其他类型的表达式节点（如常量、运算符等）

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
