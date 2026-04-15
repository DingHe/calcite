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

import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlKind;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Variable which references a column of a table occurrence in a relational plan.
 *
 * <p>This object is used by
 * {@link org.apache.calcite.rel.metadata.BuiltInMetadata.ExpressionLineage}
 * and {@link org.apache.calcite.rel.metadata.BuiltInMetadata.AllPredicates}.
 *
 * <p>Given a relational expression, its purpose is to be able to reference uniquely
 * the provenance of a given expression. For that, it uses a unique table reference
 * (contained in a {@link RelTableRef}) and an column index within the table.
 *
 * <p>For example, {@code A.#0.$3 + 2} column {@code $3} in the {@code 0}
 * occurrence of table {@code A} in the plan.
 *
 * <p>Note that this kind of {@link RexNode} is an auxiliary data structure with
 * a very specific purpose and should not be used in relational expressions.
 */
// RexTableInputRef 的主要作用是提供表达式的血缘追踪（Expression Lineage）和唯一溯源。
// 唯一性引用：在复杂的 SQL 优化过程中，同一个表可能会出现多次（例如 Self-Join）。普通的 RexInputRef 只能告诉你“引用了当前算子的第 N 列”，但无法直观地告诉你“这对应于原始基础表 A 的第 2 次出现的第 3 列”。
// 血缘与谓词追踪：它主要用于元数据分析（如 ExpressionLineage 和 AllPredicates）。通过它，优化器可以跨越多个 Join 或 Project 算子，直接定位到该数据最原始的出处。
public class RexTableInputRef extends RexInputRef {
  // 指定了该引用具体属于哪一张表的哪一次出现。
  private final RelTableRef tableRef;

  private RexTableInputRef(RelTableRef tableRef, int index, RelDataType type) {
    super(index, type);
    this.tableRef = tableRef;
    this.digest = tableRef.toString() + ".$" + index;
  }

  //~ Methods ----------------------------------------------------------------

  @Override public boolean equals(@Nullable Object obj) {
    return this == obj
        || obj instanceof RexTableInputRef
        && tableRef.equals(((RexTableInputRef) obj).tableRef)
        && index == ((RexTableInputRef) obj).index;
  }

  @Override public int hashCode() {
    return Objects.hashCode(digest);
  }

  public RelTableRef getTableRef() {
    return tableRef;
  }

  public List<String> getQualifiedName() {
    return tableRef.getQualifiedName();
  }

  public int getIdentifier() {
    return tableRef.getEntityNumber();
  }
  // 用于根据表引用、列索引和类型创建对象。
  public static RexTableInputRef of(RelTableRef tableRef, int index, RelDataType type) {
    return new RexTableInputRef(tableRef, index, type);
  }

  public static RexTableInputRef of(RelTableRef tableRef, RexInputRef ref) {
    return new RexTableInputRef(tableRef, ref.getIndex(), ref.getType());
  }

  @Override public <R> R accept(RexVisitor<R> visitor) {
    return visitor.visitTableInputRef(this);
  }

  @Override public <R, P> R accept(RexBiVisitor<R, P> visitor, P arg) {
    return visitor.visitTableInputRef(this, arg);
  }

  @Override public SqlKind getKind() {
    return SqlKind.TABLE_INPUT_REF;
  }

  /** Identifies uniquely a table by its qualified name and its entity number
   * (occurrence). */
  public static class RelTableRef implements Comparable<RelTableRef> {
    // 指向 Calcite 定义的表对象。
    private final RelOptTable table;
    // 该表在计划中出现的序号（Occurrence）。例如，如果 EMP 表在查询中被引用了两次，第一次序号为 0，第二次为 1。
    private final int entityNumber;
    // 缓存的字符串标识，格式通常为 [表名].#序号（如 A.#0）
    private final String digest;

    private RelTableRef(RelOptTable table, int entityNumber) {
      this.table = table;
      this.entityNumber = entityNumber;
      this.digest = table.getQualifiedName() + ".#" + entityNumber;
    }

    //~ Methods ----------------------------------------------------------------

    @Override public boolean equals(@Nullable Object obj) {
      return this == obj
          || obj instanceof RelTableRef
          && table.getQualifiedName().equals(((RelTableRef) obj).getQualifiedName())
          && entityNumber == ((RelTableRef) obj).entityNumber;
    }

    @Override public int hashCode() {
      return digest.hashCode();
    }

    public RelOptTable getTable() {
      return table;
    }

    public List<String> getQualifiedName() {
      return table.getQualifiedName();
    }

    public int getEntityNumber() {
      return entityNumber;
    }

    @Override public String toString() {
      return digest;
    }

    public static RelTableRef of(RelOptTable table, int entityNumber) {
      return new RelTableRef(table, entityNumber);
    }

    @Override public int compareTo(RelTableRef o) {
      return digest.compareTo(o.digest);
    }
  }
}
