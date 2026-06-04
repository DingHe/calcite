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
package org.apache.calcite.rex;

import java.util.List;

/** Can reduce expressions, writing a literal for each into a list. */
// Calcite 表达式优化层（Rex 层）与物理/动态代码执行层之间的重要桥梁
// RexExecutor 的核心作用是实现表达式的“常数折叠”（Constant Folding）与逻辑化简。
// 在 SQL 优化阶段，经常会出现一些在编译期/优化期就能直接算出结果的表达式。
// 例如：显式常数计算：WHERE age > 10 + 5 $\rightarrow$ 可以优化为 WHERE age > 15
// 系统函数调用：WHERE create_time < CURRENT_DATE $\rightarrow$ 可以优化为 WHERE create_time < '2026-06-01'
// 逻辑熔断：WHERE 1 = 1 AND name = 'Alice' $\rightarrow$ 可以优化为 WHERE name = 'Alice'
// 当优化器（如 RexSimplify 或 RexReduceExpressionsRule）遇到这些常数表达式时，就会把这些表达式打包，丢给 RexExecutor 的实现类（最典型的是 RexExecutorImpl）。实现类通常会利用 Janino 编译器 在内存中将这些表达式动态编译成一段标准的 Java 字节码并执行它，拿到真实的计算结果，再把结果变成一个字面量（RexLiteral）还给优化器。
// 通过这种方式，Calcite 成功实现了编译期常量消除，极大地减轻了分布式执行引擎在运行时的计算负担。
public interface RexExecutor {

  /** Reduces expressions, and writes their results into {@code reducedValues}.
   *
   * <p>If an expression cannot be reduced, writes the original expression.
   * For example, {@code CAST('abc' AS INTEGER)} gives an error when executed, so the executor
   * ignores the error and writes the original expression.
   *
   * @param rexBuilder Rex builder
   * @param constExps Expressions to be reduced
   * @param reducedValues List to which reduced expressions are appended
   */
  // RexBuilder rexBuilder： 作用：Calcite 的表达式构建工厂。
  // 必要性：当 Executor 算出了实际结果（比如算出了整型数字 15），它需要利用这个 rexBuilder 将原生的 Java 对象包装成 Calcite 统一的语法树节点——字面量对象 RexLiteral（例如包装为 RexLiteral(15, INTEGER)）。
  // List<RexNode> constExps：作用：待化简/输入的常数表达式列表。
  // 设计内幕：为了追求极致的性能，Calcite 采用了批量（Batch）化简的设计。优化器不会一个一个地提交表达式，而是把当前算子中所有可能是常数的表达式一次性塞进这个 List 提交给 Executor。Executor 会将它们整合成一个 Java 类进行单次编译执行，最大程度减少了动态编译字节码带来的性能开销。
  // List<RexNode> reducedValues：
  // 作用：化简结果的输出容器（承载返回值）。
  // 设计内幕：这是一个典型的“通过参数带回返回值”的写回型（Write-back）容器。该 List 的物理长度在执行完毕后，必须与输入端 constExps 的长度完全一致，且元素顺序一一对应。
  void reduce(RexBuilder rexBuilder, List<RexNode> constExps, List<RexNode> reducedValues);
}
