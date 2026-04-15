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

import java.util.List;
import java.util.Objects;

/**
 * Local variable.
 *
 * <p>Identity is based upon type and index. We want multiple references to the
 * same slot in the same context to be equal. A side effect is that references
 * to slots in different contexts which happen to have the same index and type
 * will be considered equal; this is not desired, but not too damaging, because
 * of the immutability.
 *
 * <p>Variables are immutable.
 */
// RexLocalRef 代表对“局部变量”或“中间结果”的引用。
// 中间表达式引用：在 Calcite 的 RexProgram 结构中，为了避免重复计算复杂的表达式，系统会将复杂的计算逻辑展平为一个列表。RexLocalRef 就用来指向这个列表中的某个中间计算结果。
// 解耦计算与引用：普通的 RexInputRef 直接引用上游算子的输出，而 RexLocalRef 引用的是在当前程序上下文内部已经定义好的表达式。
// 内存优化：它支持在同一个上下文（Context）中对同一槽位（Slot）的多次引用，从而提高表达式树的复用率。
public class RexLocalRef extends RexSlot {
  //~ Static fields/initializers ---------------------------------------------

  // array of common names, to reduce memory allocations
  // 缓存局部变量的显示名称。
  // 使用 SelfPopulatingList 生成前缀为 $t 的名称（例如 $t0, $t1, $t2）。这里的 t 代表 Temporary（临时）。这种设计减少了生成大量字符串时的内存开销。
  @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
  private static final List<String> NAMES = new SelfPopulatingList("$t", 30);

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a local variable.
   *
   * @param index Index of the field in the underlying row type
   * @param type  Type of the column
   */
  // index (来自 RexSlot)：表示该局部变量在当前 RexProgram 表达式列表中的索引位置。
  public RexLocalRef(int index, RelDataType type) {
    super(createName(index), index, type);
    assert type != null;
    assert index >= 0;
  }

  //~ Methods ----------------------------------------------------------------

  @Override public SqlKind getKind() {
    return SqlKind.LOCAL_REF;
  }

  @Override public boolean equals(@Nullable Object obj) {
    return this == obj
        || obj instanceof RexLocalRef
        && Objects.equals(this.type, ((RexLocalRef) obj).type)
        && this.index == ((RexLocalRef) obj).index;
  }

  @Override public int hashCode() {
    return Objects.hash(type, index);
  }

  @Override public <R> R accept(RexVisitor<R> visitor) {
    return visitor.visitLocalRef(this);
  }

  @Override public <R, P> R accept(RexBiVisitor<R, P> visitor, P arg) {
    return visitor.visitLocalRef(this, arg);
  }

  private static String createName(int index) {
    return NAMES.get(index);
  }
}
