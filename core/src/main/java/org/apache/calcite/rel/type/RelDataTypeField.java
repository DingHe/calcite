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

import java.util.Map;

/**
 * RelDataTypeField represents the definition of a field in a structured
 * {@link RelDataType}.
 * 结构化数据类型的field，继承map的entry
 * <p>Extends the {@link java.util.Map.Entry} interface to allow convenient
 * inter-operation with Java collections classes. In any implementation of this
 * interface, {@link #getKey()} must be equivalent to {@link #getName()}
 * and {@link #getValue()} must be equivalent to {@link #getType()}.
 */
// 在 Apache Calcite 的元数据模型中，RelDataTypeField 是构建关系型结构的基础单元。
// 如果把 RelDataType（当它作为 RowType 时）看作一张“表”的结构，那么 RelDataTypeField 就是这张表中的一个**“列定义”**。
// RelDataTypeField 的主要作用是描述结构化类型（如 SQL 中的行、记录或 UDT）中的单个字段。
// 身份定义：它不仅包含了列的名称，还包含了列的物理位置（索引）和数据类型。
// 集合互操作性：它继承了 Map.Entry<String, RelDataType> 接口。这意味着你可以像处理普通的键值对（Key-Value）一样处理字段，其中 Key 是字段名，Value 是字段类型。
// 这种设计极大地方便了在 Java 集合框架（如 List 或 Map）中对表结构进行转换和操作。
public interface RelDataTypeField extends Map.Entry<String, RelDataType> {

  /**
   * Function to transform a set of {@link RelDataTypeField} to
   * a set of {@link Integer} of the field keys.
   *
   * @deprecated Use {@code RelDataTypeField::getIndex}
   */
  //根据field获取对应的索引
  @Deprecated // to be removed before 2.0
  @SuppressWarnings("nullability")
  class ToFieldIndex
      implements com.google.common.base.Function<RelDataTypeField, Integer> {
    @Override public Integer apply(RelDataTypeField o) {
      return o.getIndex();
    }
  }

  /**
   * Function to transform a set of {@link RelDataTypeField} to
   * a set of {@link String} of the field names.
   *
   * @deprecated Use {@code RelDataTypeField::getName}
   */
  //根据field获取名称
  @Deprecated // to be removed before 2.0
  @SuppressWarnings("nullability")
  class ToFieldName
      implements com.google.common.base.Function<RelDataTypeField, String> {
    @Override public String apply(RelDataTypeField o) {
      return o.getName();
    }
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Gets the name of this field, which is unique within its containing type.
   *
   * @return field name
   */
  // 获取字段的名称。
  String getName();

  /**
   * Gets the ordinal of this field within its containing type.
   *
   * @return 0-based ordinal
   */
  // 获取字段在该行结构中的物理位置（索引）
  // 索引是从 0 开始的整数。例如，在 SELECT a, b, c 中，a 的索引是 0，b 是 1。这个索引在生成执行计划和访问物理数据缓冲区时至关重要。
  int getIndex();

  /**
   * Gets the type of this field.
   *
   * @return field type
   */
  // 获取该字段的数据类型。
  RelDataType getType();

  /**
   * Returns true if this is a dynamic star field.
   */
  // 判断该字段是否为“动态星号”字段。
  // 在处理像 SELECT * FROM STREAM 这种 Schema 动态变化的数据源时，Calcite 会使用一种特殊的动态类型。如果该字段代表的是这种可以自动展开的星号占位符，则返回 true。
  boolean isDynamicStar();
}
