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
package org.apache.calcite.linq4j.tree;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds context for evaluating expressions.
 */
// Evaluator 类扮演着**表达式求值上下文（Evaluation Context）**的核心角色。
// 它主要用于在运行时解析和计算由表达式树（Expression Tree）定义的逻辑。
// Evaluator 的核心作用是管理变量的绑定关系并驱动求值过程。
// 环境容器：它像一个“栈”一样存储了 ParameterExpression（参数表达式，即变量名）与它们对应的实际运行时 @Nullable Object（数值）之间的映射。
// 运行时状态机：在遍历表达式树（AST）时，当遇到需要获取变量值的节点时，Evaluator 负责提供这些值。
// 递归入口：它是启动 Node 树求值的引擎，通过它可以触发整个树的计算并返回最终结果。
class Evaluator {
  // 存储当前作用域内的所有参数对象（变量定义）
  // 实现细节：使用 ArrayList 维护。
  // 它记录了当前求值栈中所有可访问的变量名。由于支持嵌套作用域，同一个参数名可能会多次出现（虽然在合法的 linq4j 树中较少见，但物理存储上允许压栈）。
  final List<ParameterExpression> parameters = new ArrayList<>();
  // 存储与 parameters 列表中相同索引位置的参数所对应的实际运行时数值。
  // 实现细节：允许存储 null 值，这符合 SQL 和 Java 处理可空对象的需求。
  final List<@Nullable Object> values = new ArrayList<>();
  // 初始化时 parameters 和 values 列表均为空。
  Evaluator() {
  }
  // 将一个新的变量及其对应的值压入环境栈。
  // 在处理像 LambdaExpression 或循环体时，当进入一个新的作用域，需要将参数绑定到具体的输入值上。此方法确保了后续的求值逻辑可以访问到这个新绑定的变量。
  void push(ParameterExpression parameter, @Nullable Object value) {
    parameters.add(parameter);
    values.add(value);
  }
  // 弹出最近压入的 n 个变量绑定。
  void pop(int n) {
    while (n > 0) {
      parameters.remove(parameters.size() - 1);
      values.remove(values.size() - 1);
      --n;
    }
  }
  // 查找并返回指定参数的当前运行时值。
  // 它从列表的末尾向开头遍历（即遵循 LIFO 后进先出原则），确保获取的是当前最深层作用域（最晚绑定）的变量值。
  @Nullable Object peek(ParameterExpression param) {
    for (int i = parameters.size() - 1; i >= 0; i--) {
      if (parameters.get(i) == param) {
        return values.get(i);
      }
    }
    throw new RuntimeException("parameter " + param + " not on stack");
  }

  // 计算指定节点的最终值。
  @Nullable Object evaluate(Node expression) {
    return ((AbstractNode) expression).evaluate(this);
  }

  void clear() {
    parameters.clear();
    values.clear();
  }
}
