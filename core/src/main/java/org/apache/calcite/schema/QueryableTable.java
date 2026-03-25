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

import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.linq4j.tree.Expression;

import java.lang.reflect.Type;

/**
 * Extension to {@link Table} that can translate itself to a {@link Queryable}.
 */
// 在 Apache Calcite 的架构中，QueryableTable 是一个比 ScannableTable 更高级的接口。它主要用于 Linq4j（Calcite 仿照 .NET LINQ 开发的 Java 实现）驱动的查询场景，
// 其核心逻辑是将表视为一个可以进行流式计算的 Java 集合。
// QueryableTable 的核心作用是将 SQL 表转换为一个可执行的表达式树（Expression Tree）。
// 强类型集成：与 ScannableTable 返回通用的 Object[] 不同，QueryableTable 通常与具体的 Java 类（POJO）绑定。
// 支持 LINQ 查询：它使得表可以像 List 或 Set 一样，在 Java 代码中使用 .where().select() 这种链式编程风格进行查询。
// 物理层与代码层的桥梁：它是将逻辑元数据（Table）转化为物理执行代码（Expression）的关键组件。

public interface QueryableTable extends Table {
  /** Converts this table into a {@link Queryable}. */
  // 作用：将当前表转换为一个 Queryable 对象。
  // 详解：这是执行层调用的入口。它返回的 Queryable<T> 对象允许用户在 Java 层面直接对该表调用聚合、过滤等方法。
  // queryProvider：查询提供者，负责解析和执行 Queryable 对象。
  // schema：该表所属的父级 Schema 对象。
  // tableName：该表在 Schema 中的唯一名称。
  <T> Queryable<T> asQueryable(QueryProvider queryProvider, SchemaPlus schema,
      String tableName);

  /** Returns the element type of the collection that will implement this
   * table. */
  // 返回值：返回该表集合中元素的 Java 类型（java.lang.reflect.Type）。
  // 作用：确定行数据的具体类型。
  // 如果表映射到一个 POJO（如 User.class），则返回该类。
  // 如果表是通用的行，通常返回 Object[] 或 TypedRow。
  // 这个方法决定了生成的 Java 代码中，迭代器泛型 <T> 的具体类型。
  Type getElementType();

  /** Generates an expression with which this table can be referenced in
   * generated code.
   * 生成表的Expression表达式
   * @param schema Schema
   * @param tableName Table name (unique within schema)
   * @param clazz The desired collection class; for example {@code Queryable}.
   */
  // schema：所属 Schema。
  // tableName：表名。
  // clazz：期望的集合类（通常是 Queryable.class）。
  Expression getExpression(SchemaPlus schema, String tableName, Class clazz);
}
