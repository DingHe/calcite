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
package org.apache.calcite.rel.type;

import org.apache.calcite.linq4j.Ord;

import com.google.common.collect.ImmutableList;

import java.util.List;

/**
 * Type of the cartesian product of two or more sets of records.
 *
 * <p>Its fields are those of its constituent records, but unlike a
 * {@link RelRecordType}, those fields' names are not necessarily distinct.
 */
// 在 Apache Calcite 的类型系统中，RelCrossType 是一个相对特殊的实现类。它专门用于表示**笛卡尔积（Cartesian Product）**操作产生的中间结果类型。
// RelCrossType 的核心作用是描述多个记录集（Sets of Records）合并后的复合类型。
// 笛卡尔积的产物：当执行 CROSS JOIN 或没有 Join 条件的连接时，产生的中间结果就是这种类型。
// 允许重名列：与标准的 RelRecordType（记录类型）不同，RelCrossType 明确允许其包含的原始字段名称不唯一。例如，表 A 有 id 列，表 B 也有 id 列，它们的笛卡尔积类型会同时包含这两个 id，而不会像普通 Record 类型那样强制要求字段名必须不同。
// 物理集合的堆叠：它更像是一个容器，把参与连接的多个 RelDataType 完整地保留并堆叠在一起。
public class RelCrossType extends RelDataTypeImpl {
  //~ Instance fields --------------------------------------------------------

  private final ImmutableList<RelDataType> types;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a cartesian product type. This should only be called from a
   * factory method.
   */
  @SuppressWarnings("method.invocation.invalid")
  public RelCrossType(
      List<RelDataType> types,
      List<RelDataTypeField> fields) {
    super(fields);
    this.types = ImmutableList.copyOf(types);
    assert types.size() >= 1;
    for (RelDataType type : types) {
      assert !(type instanceof RelCrossType);
    }
    computeDigest();
  }

  //~ Methods ----------------------------------------------------------------

  @Override public boolean isStruct() {
    return false;
  }

  /**
   * Returns the contained types.
   *
   * @return data types.
   */
  public List<RelDataType> getTypes() {
    return types;
  }

  @Override protected void generateTypeString(StringBuilder sb, boolean withDetail) {
    sb.append("CrossType(");
    for (Ord<RelDataType> type : Ord.zip(types)) {
      if (type.i > 0) {
        sb.append(", ");
      }
      if (withDetail) {
        sb.append(type.e.getFullTypeString());
      } else {
        sb.append(type.e.toString());
      }
    }
    sb.append(")");
  }
}
