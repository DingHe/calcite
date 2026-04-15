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
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.runtime.PairList;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.util.Pair;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Variable which references a field of an input relational expression.
 *
 * <p>Fields of the input are 0-based. If there is more than one input, they are
 * numbered consecutively. For example, if the inputs to a join are
 *
 * <ul>
 * <li>Input #0: EMP(EMPNO, ENAME, DEPTNO) and</li>
 * <li>Input #1: DEPT(DEPTNO AS DEPTNO2, DNAME)</li>
 * </ul>
 *
 * <p>then the fields are:
 *
 * <ul>
 * <li>Field #0: EMPNO</li>
 * <li>Field #1: ENAME</li>
 * <li>Field #2: DEPTNO (from EMP)</li>
 * <li>Field #3: DEPTNO2 (from DEPT)</li>
 * <li>Field #4: DNAME</li>
 * </ul>
 *
 * <p>So <code>RexInputRef(3, Integer)</code> is the correct reference for the
 * field DEPTNO2.
 */
// RexInputRef 是一个至关重要的类。它是 RexNode 家族中最常用的节点之一。
// RexInputRef 代表对输入关系表达式（Relational Expression）中某个字段的引用。
// 位置引用：Calcite 的关系代数内部并不主要通过“列名”来关联数据，而是通过偏移量（Index）。例如，如果上游算子输出 3 列，那么这三列分别被编号为 0, 1, 2。RexInputRef(0, ...) 就代表引用第一列。
// 连续编号机制：在涉及多个输入的算子（如 Join）中，字段是连续编号的。如果左表有 3 列，右表有 2 列，那么引用右表的第一列时，索引就是 3。
// 核心纽带：它是连接逻辑表达式与底层数据流的“管道”，定义了数据从哪里（哪个索引位置）流向当前的计算逻辑。
public class RexInputRef extends RexSlot {
  //~ Static fields/initializers ---------------------------------------------

  // list of common names, to reduce memory allocations
  // 缓存常用的列名字符串。
  // 它初始化了一个 SelfPopulatingList（在前文 RexSlot 中提到过），前缀为 $。
  // 例如，索引为 0 的列名是 $0。这种缓存机制极大地减少了在高频率创建引用时的内存分配和垃圾回收（GC）压力。
  @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
  private static final List<String> NAMES = new SelfPopulatingList("$", 30);

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates an input variable.
   *
   * @param index Index of the field in the underlying row-type
   * @param type  Type of the column
   */
  public RexInputRef(int index, RelDataType type) {
    super(createName(index), index, type);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public boolean equals(@Nullable Object obj) {
    return this == obj
        || obj instanceof RexInputRef
        && index == ((RexInputRef) obj).index;
  }

  @Override public int hashCode() {
    return index;
  }

  /**
   * Creates a reference to a given field in a row type.
   */
  public static RexInputRef of(int index, RelDataType rowType) {
    return of(index, rowType.getFieldList());
  }

  /**
   * Creates a reference to a given field in a list of fields.
   */
  public static RexInputRef of(int index, List<RelDataTypeField> fields) {
    return new RexInputRef(index, fields.get(index).getType());
  }

  /**
   * Creates a reference to a given field in a list of fields.
   */
  public static Pair<RexNode, String> of2(
      int index,
      List<RelDataTypeField> fields) {
    final RelDataTypeField field = fields.get(index);
    return Pair.of(new RexInputRef(index, field.getType()),
        field.getName());
  }

  /**
   * Adds to a PairList a reference to a given field in a list of fields.
   */
  public static void add2(PairList<RexNode, String> list,
      int index,
      List<RelDataTypeField> fields) {
    final RelDataTypeField field = fields.get(index);
    list.add(new RexInputRef(index, field.getType()),
        field.getName());
  }

  @Override public SqlKind getKind() {
    return SqlKind.INPUT_REF;
  }

  @Override public <R> R accept(RexVisitor<R> visitor) {
    return visitor.visitInputRef(this);
  }

  @Override public <R, P> R accept(RexBiVisitor<R, P> visitor, P arg) {
    return visitor.visitInputRef(this, arg);
  }

  /**
   * Creates a name for an input reference, of the form "$index". If the index
   * is low, uses a cache of common names, to reduce gc.
   */
  public static String createName(int index) {
    return NAMES.get(index);
  }
}
