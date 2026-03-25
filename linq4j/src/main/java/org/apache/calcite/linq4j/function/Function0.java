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
 * Function with no parameters.
 *
 * @param <R> Result type
 */
// 在 Apache Calcite 的 Linq4j 子项目中，Function0 是最简单的函数式接口。它继承自你之前看到的 Function<R> 根接口，代表一个无参数的执行单元。
// Function0 的核心作用是作为延迟求值（Lazy Evaluation）或常量提供者的抽象：
// 生产者模式：它不接受任何输入，只负责产出（Produce）一个结果。
// 解耦计算与触发：在 SQL 执行计划生成过程中，某些值（如当前系统时间 CURRENT_TIMESTAMP 或某个动态参数）不需要立即计算，而是包装在 Function0 中，直到真正需要数据时才调用 apply()。
// 适配代码生成：Calcite 的 Enumerable 算子在生成 Java 代码时，经常需要这种无参回调来初始化某些上下文状态。
// 在生成的代码中，如果一个算子需要从外部获取一个动态变化的值，它会持有 Function0 的引用并调用此方法。
public interface Function0<R> extends Function<R> {
  // 参数：无。
  // 返回值 (R)：返回泛型指定的类型。
  // 执行逻辑并返回一个类型为 R 的对象。
  R apply();
}
