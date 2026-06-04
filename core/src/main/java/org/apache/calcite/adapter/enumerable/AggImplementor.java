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
package org.apache.calcite.adapter.enumerable;

import org.apache.calcite.linq4j.tree.Expression;

import java.lang.reflect.Type;
import java.util.List;

/**
 * Implements an aggregate function by generating expressions to
 * initialize, add to, and get a result from, an accumulator.
 *
 * @see org.apache.calcite.adapter.enumerable.StrictAggImplementor
 * @see org.apache.calcite.adapter.enumerable.StrictWinAggImplementor
 * @see org.apache.calcite.adapter.enumerable.RexImpTable.CountImplementor
 * @see org.apache.calcite.adapter.enumerable.RexImpTable.SumImplementor
 */
// 最核心的聚合函数代码生成接口。它定义了如何将一个逻辑上的聚合函数（如 SUM、COUNT、AVG 或用户自定义的 UDAF）翻译为 JVM 可执行的物理 Java 代码。
// 在关系型数据库或大数据引擎中，聚合函数的物理执行通常遵循 “初始化 -> 迭代累加 -> 最终输出” 的状态机模型。Calcite 的 Enumerable 算子通过动态生成 Java 代码来实现高性能的数据处理，而 AggImplementor 就是这个代码生成流水线上的“核心指挥官”。
// 主要作用是通过操作 Linq4j 语法树（AST），指导编译器如何围绕“累加器（Accumulator）”生成生命周期代码：
// 状态声明（getStateType）：定义这个聚合函数需要什么样的中间状态缓冲区。
// 状态初始化（implementReset）：生成将累加器重置为初始状态的代码（如 sum = 0）。
// 数据流迭代（implementAdd）：生成每来一条新数据时，如何将新值塞入或更新到累加器中的代码（如 sum = sum + new_value）。
// 结果榨取（implementResult）：生成当所有数据迭代完毕后，如何从累加器中计算并提取最终结果的代码（如 avg = sum / count）。
public interface AggImplementor {
  /**
   * Returns the types of the intermediate variables used by the aggregate
   * implementation.
   * <p>For instance, for "concatenate to string" this can be
   * {@link java.lang.StringBuilder}.
   * Calcite calls this method before all other {@code implement*} methods.
   *
   * @param info Aggregate context
   * @return Types of the intermediate variables used by the aggregate
   *   implementation
   */
  // 入参：AggContext info（聚合函数的上下文大管家，包含该聚合函数的元数据，如参数类型、返回类型、是否是 Distinct 聚合等）。
  // 返回值：List<Type>（一个代表中间状态变量类型的 Java Type 列表）。
  // 作用：声明累加器（Accumulator）的物理数据类型和维度。
  // 该方法告诉代码生成框架，这个聚合函数需要开辟几个、什么类型的局部变量或成员变量来充当中间状态缓冲区。
  // 典型示例：
  //
  //对于 COUNT 算子，中间状态只需要一个基础类型：[long.class]。
  //
  //对于 AVG（平均值）算子，它在底层需要同时维护“总和”和“总次数”，因此通常会返回包含两个元素的列表：[double.class, long.class]。
  List<Type> getStateType(AggContext info);

  /**
   * Implements reset of the intermediate variables to the initial state.
   * {@link AggResetContext#accumulator()} should be used to reference
   * the state variables.
   * For instance, to zero the count, use the following code:
   * 重置中间状态
   * <blockquote><code>reset.currentBlock().add(<br>
   *   Expressions.statement(<br>
   *     Expressions.assign(reset.accumulator().get(0),<br>
   *       Expressions.constant(0)));</code></blockquote>
   *
   * @param info Aggregate context
   * @param reset Reset context
   */
  // AggContext info：聚合函数全局上下文。
  // AggResetContext reset：重置操作的专属上下文。可以通过 reset.accumulator() 捞到刚才声明的中间状态变量引用，通过 reset.currentBlock() 拿到当前正在构建的 Java 代码块（BlockBuilder）。
  // 通过直接修改 reset 内部的代码块来生成代码
  // 作用：生成初始化或重置累加器的 Java 代码。
  // 在单批次聚合开始前，或者在开窗函数（Window Function）切换分区（Partition）时，累加器必须清空。该方法就是用来往 Java 代码块里注入清空/赋初值的表达式。
  // 代码生成效果：如果是 SUM 算子，此方法会在生成的 Java 类中塞入类似 this.accumulator[0] = 0; 或 sum_accumulator = 0.0; 的语句。
  void implementReset(AggContext info, AggResetContext reset);

  /**
   * Updates intermediate values to account for the newly added value.
   * {@link AggResetContext#accumulator()} should be used to reference
   * the state variables.
   * @param info Aggregate context
   * @param add Add context
   */
  // AggContext info：聚合函数全局上下文。
  // AggAddContext add：迭代累加操作的专属上下文。通过 add.arguments() 可以拿到当前行输入进来的物理操作数表达式（即要被聚合的列数据）。
  // 作用：核心迭代器——生成将当前行数据累加进中间状态的代码。
  // 当引擎在运行期遍历数据集时，每扫描到一行，就会执行该方法生成的代码片段。它负责把输入列的值融入到累加器中。
  // 代码生成效果：
  //
  //如果是 COUNT，生成的代码类似于：accumulator_count++;
  //
  //如果是 SUM(age)，生成的代码类似于：if (next_row_age != null) { accumulator_sum += next_row_age; }
  void implementAdd(AggContext info, AggAddContext add);

  /**
   * Calculates the resulting value based on the intermediate variables.
   * Note: this method must NOT destroy the intermediate variables as
   * calcite might reuse the state when calculating sliding aggregates.
   * {@link AggResetContext#accumulator()} should be used to reference
   * the state variables.
   * @param info Aggregate context
   * @param result Result context
   * @return Expression that is a result of calculating final value of
   *   the aggregate being implemented
   */
  // AggContext info：聚合函数全局上下文。
  // AggResultContext result：结果提取操作的专属上下文。
  // 返回值：Expression（代表最终计算结果的 Linq4j 表达式节点）。
  // 作用：终局榨取——根据中间状态计算并返回最终的物理表达式。
  // 代码生成效果：
  //
  //对于 SUM 或 COUNT，其最终结果就是累加器本身，因此直接把中间变量的 Expression 原封不动返回即可。
  //
  //对于 AVG，该方法会生成一个除法表达式，在返回的 AST 节点中落实：return accumulator_sum / accumulator_count;。
  // 这个表达式最终会被外层包装并作为该聚合函数的最终输出吐给下游算子。
  Expression implementResult(AggContext info, AggResultContext result);
}
