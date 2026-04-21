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
package org.apache.calcite.linq4j.tree;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Length field of a RecordType.
 */
// ArrayLengthRecordField 是一个非常特殊的元数据类。它实现了 Types.RecordField 接口，主要用于将 Java 数组的“长度（length）”属性虚拟化为一个记录字段。
// 通常情况下，RecordField 代表一个类中的成员变量（Field）。但 Java 数组的 length 并不是一个普通的成员变量，而是通过虚拟机指令（或 java.lang.reflect.Array.getLength）获取的。
// 在 Calcite 进行代码生成或反射式访问时，如果某个对象是数组，该类提供了一种统一的接口，将数组的“长度”视为该对象的一个虚拟字段（Field）。这使得 Calcite 可以像读取对象的属性（如 user.name）一样读取数组的长度（如 array.length）。
public class ArrayLengthRecordField implements Types.RecordField {
  // 存储该字段的名称。
  // 通常会被设置为 "length"，用于在元数据层面标识这个字段。
  private final String fieldName;
  // 存储声明该字段的类。
  // 在这种情况下，它通常代表该数组的类型（例如 int[].class 或 Object[].class）。
  private final Class clazz;

  public ArrayLengthRecordField(String fieldName, Class clazz) {
    assert fieldName != null : "fieldName should not be null";
    assert clazz != null : "clazz should not be null";
    this.fieldName = fieldName;
    this.clazz = clazz;
  }

  @Override public boolean nullable() {
    return false;
  }

  @Override public String getName() {
    return fieldName;
  }

  @Override public Type getType() {
    return int.class;
  }

  @Override public int getModifiers() {
    return 0;
  }

  @Override public Object get(@Nullable Object o) throws IllegalAccessException {
    return Array.getLength(requireNonNull(o, "o"));
  }

  @Override public Type getDeclaringClass() {
    return clazz;
  }

  @Override public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    ArrayLengthRecordField that = (ArrayLengthRecordField) o;

    if (!clazz.equals(that.clazz)) {
      return false;
    }
    if (!fieldName.equals(that.fieldName)) {
      return false;
    }

    return true;
  }

  @Override public int hashCode() {
    return Objects.hash(fieldName, clazz);
  }
}
