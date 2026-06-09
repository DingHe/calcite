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
package org.apache.calcite.linq4j;

import org.apache.calcite.linq4j.tree.Expression;

import java.lang.reflect.Type;

/**
 * Defines methods to create and execute queries that are described by a
 * {@link Queryable} object.
 *
 * <p>Analogous to LINQ's System.Linq.QueryProvider.
 */
// QueryProvider 的核心作用是解析并执行表达式树（Expression Tree）。
// 查询的“工厂”与“执行引擎”：它不仅负责将逻辑上的 Expression 包装成可操作的 Queryable 对象，
// 还负责最终将这些表达式转化为物理操作（如内存迭代、SQL 调用或 API 请求）。
// 解耦声明与执行：用户在编写 .where().select() 时，只是在构建一颗表达式树。
// QueryProvider 的存在使得这颗树可以被延迟执行，或者被翻译成其他语言（如 SQL）。
// 支持单值与集合返回：它既能处理返回一组数据的查询（executeQuery），也能处理返回聚合结果（如 COUNT, MAX）的查询（execute）。

public interface QueryProvider {
  /**
   * Constructs a {@link Queryable} object that can evaluate the query
   * represented by a specified expression tree.
   *
   * <p>NOTE: The {@link org.apache.calcite.linq4j.Queryable#getExpression()}
   * property of the returned {@link Queryable} object is equal to
   * {@code expression}.
   *
   * @param expression Expression
   * @param rowType Row type
   * @param <T> Row type
   *
   * @return Queryable
   */
  // 将一个现有的表达式树“封装”成一个可查询的对象
  // 作用：根据指定的表达式和行类型（Class），构造一个 Queryable 对象。
  <T> Queryable<T> createQuery(Expression expression, Class<T> rowType);

  /**
   * Constructs a {@link Queryable} object that can evaluate the query
   * represented by a specified expression tree. The row type may contain
   * generic information.
   *
   * @param expression Expression
   * @param rowType Row type
   * @param <T> Row type
   *
   * @return Queryable
   */
  // 作用：与上一个方法相同，但支持更复杂的 Type（如带有泛型信息的类型）。
  <T> Queryable<T> createQuery(Expression expression, Type rowType);

  /**
   * Executes the query represented by a specified expression tree.
   *
   * <p>This method executes queries that return a single value
   * (instead of an enumerable sequence of values). Expression trees that
   * represent queries that return enumerable results are executed when the
   * {@link Queryable} object that contains the expression tree is
   * enumerated.
   *
   * <p>The Queryable standard query operator methods that return singleton
   * results call {@code execute}. They pass it a
   * {@link org.apache.calcite.linq4j.tree.MethodCallExpression}
   * that represents a linq4j query.
   */
  // 作用：执行表达式树，并返回指定 Class 类型的结果。
  // 场景：当你调用 queryable.count()、queryable.first() 或 queryable.max() 时，这些方法内部会调用 execute。
  <T> T execute(Expression expression, Class<T> type);

  /**
   * Executes the query represented by a specified expression tree.
   * The row type may contain type parameters.
   */
  // 作用：执行表达式树，支持返回带有泛型信息的单值对象。
  <T> T execute(Expression expression, Type type);

  /**
   * Executes a queryable, and returns an enumerator over the
   * rows that it yields.
   *
   * @param queryable Queryable
   *
   * @return Enumerator over rows
   */
  // 作用：将整个 Queryable（包含其内部完整的表达式树）转化为一个实时的迭代器 Enumerator。
  // 详解：当用户开始遍历（迭代）一个 Queryable 对象时（例如通过 foreach），系统会触发此方法。
  <T> Enumerator<T> executeQuery(Queryable<T> queryable);
}
