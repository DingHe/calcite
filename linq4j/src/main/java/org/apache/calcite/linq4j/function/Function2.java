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
 * Function with two parameters.
 *
 * @param <R> Result type
 * @param <T0> Type of argument #0
 * @param <T1> Type of argument #1
 */
// 在 Apache Calcite 的 Linq4j 框架中，Function2<T0, T1, R> 是处理双元逻辑的核心接口。它继承自 Function<R>，代表一个接收两个参数并返回一个结果的标准函数。
// Function2 在 Calcite 的执行引擎中扮演着“连接者”与“聚合者”的角色：
// 关联逻辑（Join & Correlation）：在执行 Join 操作时，通常需要一个函数来处理左右两表的记录并生成结果行。这个合并过程通常由 Function2<LeftRow, RightRow, ResultRow> 完成。
// 二元运算（Binary Operations）：SQL 中的算术运算（如 a + b, a * b）或比较运算（如 a > b），在物理层都会被转化为 Function2。
// 折叠与归约（Reduce / Fold）：在聚合计算中，将当前累加器的值（Accumulator）与新输入的行数据（Input）结合，产生更新后的累加器值，这是典型的 Function2 应用场景。
public interface Function2<T0, T1, R> extends Function<R> {
  // 参数 (T0 v0)：接收第一个输入参数（例如 Join 的左侧行或加法的左操作数）。
  // 参数 (T1 v1)：接收第二个输入参数（例如 Join 的右侧行或加法的右操作数）。
  // 返回值 (R)：返回处理后的结果对象。
  R apply(T0 v0, T1 v1);
}
