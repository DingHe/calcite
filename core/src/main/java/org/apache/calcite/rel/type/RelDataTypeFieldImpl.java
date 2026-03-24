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

import org.apache.calcite.sql.type.SqlTypeName;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Default implementation of {@link RelDataTypeField}.
 */
// RelDataTypeFieldImpl 是 Apache Calcite 中 RelDataTypeField 接口的标准默认实现类。
// 数据载体：它是列元数据的具体存储实体。在 Calcite 构建逻辑执行计划（RelNode）的过程中，每当需要描述结果集的某一列时，都会实例化此类。
// 不可变性保障：该类通过 final 关键字确保了字段在创建后不可修改，这符合函数式编程的思想，方便在多线程环境中共享元数据。
// Map 语义实现：它具体实现了继承自 Map.Entry 的逻辑，使得开发者可以将表结构作为 KV 键值对进行遍历。
//
public class RelDataTypeFieldImpl implements RelDataTypeField, Serializable {
  //~ Instance fields --------------------------------------------------------
  // 存储该列的数据类型。
  private final RelDataType type;
  // 存储字段的名称。
  // 这是用户在 SQL 中引用该列时使用的标识符（如 "USER_ID"）
  private final String name;
  // 存储字段在行结构中的索引位置。
  private final int index;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a RelDataTypeFieldImpl.
   */
  public RelDataTypeFieldImpl(
      String name,
      int index,
      RelDataType type) {
    this.name = requireNonNull(name, "name");
    this.index = index;
    this.type = requireNonNull(type, "type");
  }

  //~ Methods ----------------------------------------------------------------

  @Override public int hashCode() {
    return Objects.hash(index, name, type);
  }

  @Override public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RelDataTypeFieldImpl)) {
      return false;
    }
    RelDataTypeFieldImpl that = (RelDataTypeFieldImpl) obj;
    return this.index == that.index
        && this.name.equals(that.name)
        && this.type.equals(that.type);
  }

  // implement RelDataTypeField
  @Override public String getName() {
    return name;
  }

  // implement RelDataTypeField
  @Override public int getIndex() {
    return index;
  }

  // implement RelDataTypeField
  @Override public RelDataType getType() {
    return type;
  }

  // implement Map.Entry
  @Override public final String getKey() {
    return getName();
  }

  // implement Map.Entry
  @Override public final RelDataType getValue() {
    return getType();
  }

  // implement Map.Entry
  @Override public RelDataType setValue(RelDataType value) {
    throw new UnsupportedOperationException();
  }

  // for debugging
  @Override public String toString() {
    return "#" + index + ": " + name + " " + type;
  }

  @Override public boolean isDynamicStar() {
    return type.getSqlTypeName() == SqlTypeName.DYNAMIC_STAR; //判断sql类型是否为*
  }

}
