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
package org.apache.calcite.linq4j.function;

/**
 * Function with one parameter returning a native {@code boolean} value.
 *
 * @param <T0> Type of argument #0
 */
// 在 Apache Calcite 的 Linq4j 框架中，Predicate1<T0> 是用于执行单参数逻辑判断的核心接口。它继承自 Function<Boolean>，是 SQL 中 WHERE 子句、HAVING 子句以及所有单表过滤逻辑的物理实现基础。
// Predicate1 在 Calcite 执行引擎中扮演着“过滤器”的角色：
// 行级过滤：在执行 Enumerable.where 操作时，每一行数据都会经过 Predicate1 的检验。只有返回 true 的行才能进入下一个算子。
// 性能优化：通过直接返回原生 boolean 类型，避免了频繁创建 Boolean 对象（装箱/拆箱），这在大规模数据扫描时能显著降低内存抖动和 CPU 开销。
// 逻辑表示：它将 SQL 中的逻辑表达式（如 age > 18 或 name LIKE 'A%'）转化为一段可执行的 Java 判定代码。
public interface Predicate1<T0> extends Function<Boolean> {
  /**
   * Predicate that always evaluates to {@code true}.
   *
   * @see Functions#truePredicate1()
   */
  Predicate1<Object> TRUE = v0 -> true;

  /**
   * Predicate that always evaluates to {@code false}.
   *
   * @see Functions#falsePredicate1()
   */
  Predicate1<Object> FALSE = v0 -> false;

  boolean apply(T0 v0);
}
