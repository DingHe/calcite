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
 * Function with one parameter.
 *
 * @param <R> Result type
 * @param <T0> Type of parameter 0
 */
// 在 Apache Calcite 的 Linq4j 框架中，Function1<T0, R> 是最频繁被使用的函数式接口。它继承自 Function<R>，代表一个接收一个参数并返回一个结果的标准转换逻辑。
// Function1 是 Calcite 执行引擎中“数据流水线（Data Pipeline）”的核心：
// 转换器（Transformer）：它是 SELECT 子句中字段映射的物理体现。例如，SELECT upper(name) 会被转化为一个 Function1<String, String>，其内部逻辑是调用 String.toUpperCase()。
// 投影（Projection）：在执行 Enumerable.select 操作时，每一行数据（通常是 Object[]）都会通过一个 Function1 转换为目标格式。
// 谓词包装：虽然 Calcite 有专门的 Predicate1，但在某些底层实现中，返回 Boolean 的 Function1 也可以充当过滤条件。

public interface Function1<T0, R> extends Function<R> {
  /**
   * The identity function.
   *
   * @see Functions#identitySelector()
   */
  // 定义了一个恒等函数（Identity Function）
  // 语义：输入什么，就原样返回什么 ($f(x) = x$)。
  // 应用场景：在进行某些不需要改变数据的转换操作（如 SELECT * 或某些逻辑占位）时，直接使用这个预定义的静态常量，可以避免重复创建对象，提高性能并减少 GC 压力。
  Function1<Object, Object> IDENTITY = v0 -> v0;
  // 参数 (T0 a0)：接收一个类型为 T0 的输入参数。
  // 返回值 (R)：返回处理后的类型为 R 的结果。
  // 详解：在 Calcite 生成的 Java 代码中，这个方法通常包含了 SQL 标量函数（Scalar Functions）的具体实现。
  R apply(T0 a0);
}
