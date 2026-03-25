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

import org.checkerframework.framework.qual.Covariant;

/**
 * Exposes the enumerator, which supports a simple iteration over a collection.
 *
 * <p>Analogous to LINQ's System.Collections.IEnumerable (both generic
 * and non-generic variants).
 *
 * <p>Also implements {@link Iterable}, to enable use in Java foreach loops.
 *
 * @param <T> Element type
 */
// Enumerable<T> 是整个框架的核心接口。如果说 RawEnumerable 是“极简版”的数据源，那么 Enumerable 就是“全功能版”的查询引擎。
// 它通过多重继承，将 Java 的集合习惯、.NET 的 LINQ 风格以及 Calcite 的动态查询能力完美融合。
// Enumerable<T> 是一个功能大集合，它的作用可以概括为以下三点：
// 算子库入口：它是所有 LINQ 算子（如 where, select, join, groupBy）的载体。当你拿到一个 Enumerable 对象时，你可以像写 SQL 一样进行链式调用。
// Java 原生支持：因为它继承了 Iterable<T>，所以你可以直接在 Java 的 for-each 循环中使用它，或者将其转换为标准的 Java 集合。
// 桥接逻辑（Bridge）：它连接了“内存迭代执行”（Enumerable）和“表达式树转换执行”（Queryable）两种不同的执行模式。
@Covariant(0)
public interface Enumerable<T>
    extends RawEnumerable<T>, Iterable<T>, ExtendedEnumerable<T> {
  /**
   * Converts this Enumerable to a Queryable.
   *
   * @see EnumerableDefaults#asQueryable(Enumerable)
   */
  @Override Queryable<T> asQueryable();

}
