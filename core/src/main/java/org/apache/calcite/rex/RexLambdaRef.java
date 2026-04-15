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

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlKind;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * Variable that references a field of a lambda expression.
 */
// RexLambdaRef 是 RexSlot 的具体实现类之一
// 主要用于支持 SQL 或关系表达式中的 Lambda 表达式（匿名函数）。
// RexLambdaRef 代表对 Lambda 表达式参数（或其字段）的引用。
// 上下文环境：在 SQL 中，某些高级函数（如 FILTER, TRANSFORM 或某些自定义的高阶函数）会接受 Lambda 表达式作为参数。
// 引用机制：类似于 RexInputRef 引用输入流的列，RexLambdaRef 用于在 Lambda 函数体内部引用传入的参数。
// 索引定位：它继承自 RexSlot，因此通过一个 index（索引）来标识它引用的是 Lambda 参数列表中的第几个参数。
public class RexLambdaRef extends RexSlot {

  public RexLambdaRef(int index, String name, RelDataType type) {
    super(name, index, type);
  }
  // 返回该节点的类型种类。
  @Override public SqlKind getKind() {
    return SqlKind.LAMBDA_REF;
  }
  // 支持单参数访问者模式。
  @Override public <R> R accept(RexVisitor<R> visitor) {
    return visitor.visitLambdaRef(this);
  }
  // 支持带负载（Payload）的访问者模式。
  @Override public <R, P> R accept(RexBiVisitor<R, P> visitor, P arg) {
    return (R) null;
  }

  @Override public boolean equals(final @Nullable Object obj) {
    return this == obj
        || obj instanceof RexLambdaRef
        && index == ((RexLambdaRef) obj).index
        && type.equals(((RexLambdaRef) obj).type);
  }

  @Override public int hashCode() {
    return Objects.hash(type, index);
  }
}
