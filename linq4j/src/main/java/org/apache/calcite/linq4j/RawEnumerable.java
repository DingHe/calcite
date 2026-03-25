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
 * Exposes the enumerator, which supports a simple iteration over a collection,
 * without the extension methods.
 *
 * <p>Just the bare methods, to make it easier to implement. Code that requires
 * the extension methods can use the static methods in {@link Extensions}.
 *
 * <p>Analogous to LINQ's System.Collections.IEnumerable (both generic
 * and non-generic variants), without the extension methods.
 *
 * @param <T> Element type
 * @see Enumerable
 */
// 数据源的核心契约：它的唯一作用是提供一个 Enumerator<T>（枚举器/迭代器），从而允许程序对一组数据进行单向线性遍历。
// 类比标准库：它对应于 Java 标准库中的 java.lang.Iterable
// 架构解耦：Calcite 将“获取数据的能力”与“查询转换的能力（如 where, select, join 等扩展方法）”分离开来。
// RawEnumerable 只负责前者，这使得自定义数据源的实现变得极其简单——你只需要实现一个返回枚举器的方法，而不需要去实现成百上千个复杂的 LINQ 算子。
@Covariant(0)
public interface RawEnumerable<T> {
  /**
   * Returns an enumerator that iterates through a collection.
   */
  // Enumerator 是 linq4j 中类似于 java.util.Iterator 的组件，
  // 但它拥有更明确的生命周期（如 moveNext(), current(), reset(), 以及继承自 AutoCloseable 的 close()）。
  Enumerator<T> enumerator();
}
