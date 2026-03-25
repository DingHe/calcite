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
 * Function with two parameters returning a native {@code boolean} value.
 *
 * @param <T0> Type of argument #0
 * @param <T1> Type of argument #1
 */
// 在 Apache Calcite 的 Linq4j 框架中，Predicate2<T0, T1> 是专门用于处理双参数条件判断的函数式接口。它继承自 Function<Boolean>，
// 但其核心在于返回原生 boolean 类型，是 SQL 中各种“过滤条件”和“匹配逻辑”的物理实现。
// Predicate2 的核心作用是作为二元断言（Binary Assertion），决定两组数据是否满足某种逻辑：
// 关联匹配（Join Condition）：在执行非等值 Join（Non-Equi Join）时，Predicate2<LeftRow, RightRow> 负责判断左表的一行与右表的一行是否满足连接条件。
// 关联子查询过滤：处理相关子查询（Correlated Subquery）时，判断外部查询的变量与内部查询的行是否匹配。
// 复合逻辑判断：在生成的 Java 代码中，用于执行如 a > b 或 ST_Intersects(geom1, geom2) 等涉及两个操作数的逻辑判断。
// 性能优化：通过直接返回原生 boolean，避免了 Boolean 对象的频繁装箱和拆箱，提高了在高频过滤场景下的执行效率。
public interface Predicate2<T0, T1> extends Function<Boolean> {
  /**
   * Predicate that always evaluates to {@code true}.
   *
   * @see org.apache.calcite.linq4j.function.Functions#truePredicate1()
   */
  Predicate2<Object, Object> TRUE = (v0, v1) -> true;

  /**
   * Predicate that always evaluates to {@code false}.
   *
   * @see org.apache.calcite.linq4j.function.Functions#falsePredicate1()
   */
  Predicate2<Object, Object> FALSE = (v0, v1) -> false;

  boolean apply(T0 v0, T1 v1);
}
