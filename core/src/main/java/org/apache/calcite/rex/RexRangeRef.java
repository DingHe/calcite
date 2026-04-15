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

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * Reference to a range of columns.
 *
 * <p>This construct is used only during the process of translating a
 * {@link org.apache.calcite.sql.SqlNode SQL} tree to a
 * {@link org.apache.calcite.rel.RelNode rel}/{@link RexNode rex}
 * tree. <em>Regular {@link RexNode rex} trees do not contain this
 * construct.</em>
 *
 * <p>While translating a join of EMP(EMPNO, ENAME, DEPTNO) to DEPT(DEPTNO2,
 * DNAME) we create <code>RexRangeRef(DeptType,3)</code> to represent the pair
 * of columns (DEPTNO2, DNAME) which came from DEPT. The type has 2 columns, and
 * therefore the range represents columns {3, 4} of the input.
 *
 * <p>Suppose we later create a reference to the DNAME field of this
 * RexRangeRef; it will return a <code>{@link RexInputRef}(5,Integer)</code>,
 * and the {@link org.apache.calcite.rex.RexRangeRef} will disappear.
 */
// 是一个临时性的中间对象
// RexRangeRef 代表对一组连续列（列范围）的引用。
// 瞬时性（Transient）：它仅在将 SQL 树（SqlNode）转换为关系表达式树（RelNode/RexNode）的转换过程中存在。在最终生成的标准关系表达式树中，它是不会出现的。
// 块引用：当你处理类似 JOIN 或者 SELECT * 这种涉及多个字段的操作时，Calcite 会先用 RexRangeRef 把某张表的所有列作为一个“块”整体引用。
// 自动展开：一旦转换逻辑需要访问这个块中的具体某一列时，RexRangeRef 就会被“打散”并替换为具体的 RexInputRef，随后该对象消失。
public class RexRangeRef extends RexNode {
  //~ Instance fields --------------------------------------------------------
  // 定义该范围所包含的结构类型。
  // 这通常是一个 RecordType（记录类型），包含了该范围内所有列的子类型信息。例如，如果引用的是一张有 3 个字段的表，这个 type 就会描述这 3 个字段的类型。
  private final RelDataType type;
  // 定义该列范围在输入流中的起始偏移量。
  // 示例：假设左表有 3 列（索引 0, 1, 2），右表有 2 列。引用右表的 RexRangeRef 的 offset 就是 3。它代表了索引为 $\{3, 4\}$ 的列集合。
  private final int offset;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a range reference.
   *
   * @param rangeType Type of the record returned
   * @param offset    Offset of the first column within the input record
   */
  RexRangeRef(
      RelDataType rangeType,
      int offset) {
    this.type = rangeType;
    this.offset = offset;
  }

  //~ Methods ----------------------------------------------------------------

  @Override public RelDataType getType() {
    return type;
  }

  public int getOffset() {
    return offset;
  }

  @Override public <R> R accept(RexVisitor<R> visitor) {
    return visitor.visitRangeRef(this);
  }

  @Override public <R, P> R accept(RexBiVisitor<R, P> visitor, P arg) {
    return visitor.visitRangeRef(this, arg);
  }

  @Override public boolean equals(@Nullable Object obj) {
    return this == obj
        || obj instanceof RexRangeRef
        && type.equals(((RexRangeRef) obj).type)
        && offset == ((RexRangeRef) obj).offset;
  }

  @Override public int hashCode() {
    return Objects.hash(type, offset);
  }

  @Override public String toString() {
    return "offset(" + offset + ")";
  }
}
