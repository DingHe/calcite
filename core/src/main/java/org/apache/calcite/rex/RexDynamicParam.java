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
 * Dynamic parameter reference in a row-expression.
 */
// RexDynamicParam 代表行表达式中的占位符参数。
// 对应 SQL 中的问号：在执行预编译语句（Prepared Statement）时，SQL 中的 ? 占位符在 Calcite 的关系表达式（RexNode）层级中就被表示为 RexDynamicParam。
// 延迟赋值：与 RexLiteral（常量）不同，RexDynamicParam 在查询计划生成和优化阶段没有具体的值。它的值只在最终执行（Execution）阶段由外部传入。
// 类型安全：虽然它的值是动态的，但 Calcite 的校验器（Validator）会推断出该参数应该具备的 RelDataType，从而保证表达式树的类型一致性。
public class RexDynamicParam extends RexVariable {
  //~ Instance fields --------------------------------------------------------
  // 表示该动态参数在整个 SQL 语句或表达式列表中的位置索引。
  // 这是一个从 0 开始的整数。例如，在 SQL SELECT * FROM emp WHERE id = ? AND age > ? 中，第一个问号对应的 index 是 0，第二个是 1。
  // 这个索引是程序在执行阶段将具体值绑定到参数上的关键依据。
  private final int index;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a dynamic parameter.
   *
   * @param type  inferred type of parameter
   * @param index 0-based index of dynamic parameter in statement
   */
  public RexDynamicParam(
      RelDataType type,
      int index) {
    super("?" + index, type);
    this.index = index;
  }

  //~ Methods ----------------------------------------------------------------

  @Override public SqlKind getKind() {
    return SqlKind.DYNAMIC_PARAM;
  }

  public int getIndex() {
    return index;
  }

  @Override public <R> R accept(RexVisitor<R> visitor) {
    return visitor.visitDynamicParam(this);
  }

  @Override public <R, P> R accept(RexBiVisitor<R, P> visitor, P arg) {
    return visitor.visitDynamicParam(this, arg);
  }

  @Override public boolean equals(@Nullable Object obj) {
    return this == obj
        || obj instanceof RexDynamicParam
        && type.equals(((RexDynamicParam) obj).type)
        && index == ((RexDynamicParam) obj).index;
  }

  @Override public int hashCode() {
    return Objects.hash(type, index);
  }
}
