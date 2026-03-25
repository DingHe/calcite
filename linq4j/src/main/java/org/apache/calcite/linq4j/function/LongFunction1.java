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
 * Function that takes one parameter and returns a native {@code long} value.
 *
 * @param <T0> Type of argument #0
 */
// 在 Apache Calcite 的 Linq4j 框架中，LongFunction1<T0> 是一个专为性能优化而设计的特化函数式接口。它继承自 Function<Long>，但其核心逻辑在于处理原生类型（Primitive Type）。
// LongFunction1 的核心作用是消除自动装箱（Autoboxing）带来的性能开销：
// 高性能计算：在处理大规模数据扫描、聚合（如 COUNT 或 SUM(bigint)）或哈希计算时，频繁地在 Long 对象和 long 原生类型之间转换会产生大量的临时对象，增加 GC 压力。
// 原生类型支持：它直接返回 Java 的原生 long，这对于处理时间戳、大整数 ID 以及高精度的数值运算至关重要。
// 执行引擎优化：Calcite 的 Enumerable 算子在生成字节码时，会优先选择带有原生返回类型的接口，以确保生成的 Java 代码运行效率接近原生代码。
public interface LongFunction1<T0> extends Function<Long> {
  long apply(T0 v0);
}
