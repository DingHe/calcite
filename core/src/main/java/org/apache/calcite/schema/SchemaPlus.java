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
package org.apache.calcite.schema;

import org.apache.calcite.materialize.Lattice;
import org.apache.calcite.rel.type.RelProtoDataType;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Extension to the {@link Schema} interface.
 * 对schema接口的拓展
 * <p>Given a user-defined schema that implements the {@link Schema} interface,
 * Calcite creates a wrapper that implements the {@code SchemaPlus} interface.
 * This provides extra functionality, such as access to tables that have been
 * added explicitly.
 *
 * <p>A user-defined schema does not need to implement this interface, but by
 * the time a schema is passed to a method in a user-defined schema or
 * user-defined table, it will have been wrapped in this interface.
 *
 * <p>SchemaPlus is intended to be used by users but not instantiated by them.
 * Users should only use the SchemaPlus they are given by the system.
 * The purpose of SchemaPlus is to expose to user code, in a read only manner,
 * some of the extra information about schemas that Calcite builds up when a
 * schema is registered. It appears in several SPI calls as context; for example
 * {@link SchemaFactory#create(SchemaPlus, String, java.util.Map)} contains a
 * parent schema that might be a wrapped instance of a user-defined
 * {@link Schema}, or indeed might not.
 */
// 在 Apache Calcite 的元数据框架中，SchemaPlus 是对基础 Schema 接口的一个极其重要的功能增强扩展。
// SchemaPlus 的核心身份是一个装饰器（Decorator）或包装器（Wrapper）。
// 系统级包装：用户在实现自定义适配器时通常只实现 Schema 接口。但当这个 Schema 被注册到 Calcite 系统中时，Calcite 会自动将其包装成一个 SchemaPlus 实例。
// 写操作入口：基础的 Schema 接口主要是只读的（只有 getTable, getFunctions 等），而 SchemaPlus 提供了 add(...) 系列方法，允许动态地向模式中注入表、函数、子模式、类型甚至 Lattice（格）。
// 上下文交互：在 SchemaFactory 创建过程中，系统会传入一个 SchemaPlus 作为父节点，方便开发者构建层级化的元数据树。
// 状态管理：它负责管理缓存状态、父子引用关系以及路径寻址。
public interface SchemaPlus extends Schema {
  /**
   * Returns the parent schema, or null if this schema has no parent.
   */
  // 如果当前是根模式（Root Schema），则返回 null。这允许在元数据树中向上溯源。
  @Nullable SchemaPlus getParentSchema();

  /**
   * Returns the name of this schema.
   *
   * <p>The name must not be null, and must be unique within its parent.
   * The root schema is typically named "".
   */
  // 获取当前模式的名称。
  // 在同一个父模式下，该名称必须唯一。通常根模式的名称为空字符串 ""。
  String getName();

  // override with stricter return
  // 获取子模式。
  @Override @Nullable SchemaPlus getSubSchema(String name);
   //添加schema
  /** Adds a schema as a sub-schema of this schema, and returns the wrapped
   * object. */
  // 添加一个子模式。
  SchemaPlus add(String name, Schema schema);
  // 动态添加一张表。常用于在运行时手动注册内存表或视图。
  /** Adds a table to this schema. */
  void add(String name, Table table);
  //删除表
  /** Removes a table from this schema, used e.g. to clean-up temporary tables. */
  // 从模式中移除指定的表。
  default boolean removeTable(String name) {
    // Default implementation provided for backwards compatibility, to be removed before 2.0
    return false;
  }

  // 添加一个标量函数、聚合函数或表函数。
  /** Adds a function to this schema. */
  void add(String name, Function function);

  /** Adds a type to this schema.  */
  // 添加一个命名的自定义数据类型。
  void add(String name, RelProtoDataType type);

  /** Adds a lattice to this schema. */
  // Lattice 是 Calcite 用于物化视图推荐和星型模型优化的决策结构。
  void add(String name, Lattice lattice);
  // 对于 SchemaPlus，通常返回 true，因为它设计的初衷就是为了支持动态 add 操作。
  @Override boolean isMutable();

  /** Returns an underlying object. */
  <T extends Object> @Nullable T unwrap(Class<T> clazz);
  // 设置该模式的搜索路径。
  // 定义了在解析 SQL 时，如果对象名不带前缀，应该去哪些其他 Schema 路径下搜索。
  void setPath(ImmutableList<ImmutableList<String>> path);
  // 开启或关闭该模式的元数据缓存。
  void setCacheEnabled(boolean cache);
  // 查询当前是否启用了缓存。如果启用，Calcite 可能会缓存 getTableNames 等调用的结果。
  boolean isCacheEnabled();
}
