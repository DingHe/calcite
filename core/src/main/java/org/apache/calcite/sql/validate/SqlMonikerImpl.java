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
package org.apache.calcite.sql.validate;

import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * A generic implementation of {@link SqlMoniker}.
 */
// SqlMonikerImpl 的核心作用是实现 SQL 标识符的“轻量级”表示。
// 在 SQL 校验和自动补全的过程中，Calcite 需要频繁地传递和比较表名、列名等。
// 如果直接使用 SqlIdentifier 或 RelOptTable，对象开销较大且包含许多不必要的上下文（如解析位置）。
// SqlMonikerImpl 通过将标识符简化为 名称列表 和 类型枚举，提供了一个易于存储、比较和打印的实现。
public class SqlMonikerImpl implements SqlMoniker {
  //~ Instance fields --------------------------------------------------------
  // 存储该对象的完全限定名。
  // 使用 Google Guava 的 ImmutableList 保证了安全性。例如，对于 sales.public.emp 表，该列表存储为 ["sales", "public", "emp"]。
  private final ImmutableList<String> names;
  // 标识该对象的类型。
  // 区分该标识符代表的是 COLUMN（列）、TABLE（表）、SCHEMA（架构）还是 FUNCTION（函数）等。
  private final SqlMonikerType type;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a moniker with an array of names.
   */
  public SqlMonikerImpl(List<String> names, SqlMonikerType type) {
    this.names = ImmutableList.copyOf(names);
    this.type = Objects.requireNonNull(type, "type");
  }

  /**
   * Creates a moniker with a single name.
   */
  public SqlMonikerImpl(String name, SqlMonikerType type) {
    this(ImmutableList.of(name), type);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public boolean equals(@Nullable Object obj) {
    return this == obj
        || obj instanceof SqlMonikerImpl
        && type == ((SqlMonikerImpl) obj).type
        && names.equals(((SqlMonikerImpl) obj).names);
  }

  @Override public int hashCode() {
    return Objects.hash(type, names);
  }

  @Override public SqlMonikerType getType() {
    return type;
  }

  @Override public List<String> getFullyQualifiedNames() {
    return names;
  }

  @Override public SqlIdentifier toIdentifier() {
    return new SqlIdentifier(names, SqlParserPos.ZERO);
  }

  @Override public String toString() {
    return Util.sepList(names, ".");
  }

  @Override public String id() {
    return type + "(" + this + ")";
  }
}
