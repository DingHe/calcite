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

import java.lang.reflect.Type;

/**
 * Analogous to LINQ's System.Linq.Expression.
 */
// 是所有表达式类节点（如算术运算、函数调用、常量、变量引用等）的直接父类。
// 定义“表达式”语义：在 linq4j 中，Node 是所有树节点的基类（包括 Statement 语句），而 Expression 专门代表有返回值的代码片段。
// 强制转换机制：它重写了 accept(Shuttle) 方法，要求所有子类必须返回 Expression 类型，这保证了在进行树转换（Tree Transformation）时，表达式节点替换后依然是表达式。
// 提供简化接口：它定义了节点简化的潜能接口（canReduce），为后续的表达式优化（如常量折叠）留下了扩展空间。
// Expression 是 Calcite 生成“流式查询代码”的构建块。
// 代码生成（Code Gen）：当你看到 queryable.where(...).select(...) 这样的代码被生成时，每一个 Lambda 表达式内部的逻辑都是由 Expression 的子类（如 ParameterExpression, MethodCallExpression）构成的。
// 与 Statement 的区别：
// Expression：例如 a + b，它有值，可以作为另一个方法的参数。
// Statement：例如 int a = 1;，它没有值，代表一个动作。
public abstract class Expression extends AbstractNode {

  /**
   * Creates an Expression.
   *
   * <p>The type of the expression may, at the caller's discretion, be a
   * regular class (because {@link Class} implements {@link Type}) or it may
   * be a different implementation that retains information about type
   * parameters.
   *
   * @param nodeType Node type
   * @param type Type of the expression
   */
  // 初始化表达式节点的基本元数据。
  // ExpressionType nodeType：来自 ExpressionType 枚举，定义了该表达式的具体操作（如 Add, Call, Constant 等）。
  // Type type：Java 反射类型，定义了该表达式执行后的返回类型（例如 Integer.class 或自定义的 POJO 类）。
  protected Expression(ExpressionType nodeType, Type type) {
    super(nodeType, type);
    assert nodeType != null;
    assert type != null;
  }
  // 作用：支持**穿梭器模式（Shuttle Pattern）**进行节点重写。
  // 返回值类型修改：注意它重写了 Node 接口中的 accept(Shuttle)。基类 Node 返回的是 Node，而这里将其协变为 Expression。
  // 意义：这是一种强类型约束。当一个 Shuttle 访问一个表达式节点并尝试替换它时，替换后的新节点必须也是一个 Expression（即必须有返回值），
  // 而不能是一个 Statement（如 if 语句或 for 循环），从而维持了语法树的正确性。
  @Override // More specific return type.
  public abstract Expression accept(Shuttle shuttle);

  /**
   * Indicates that the node can be reduced to a simpler node. If this
   * returns true, Reduce() can be called to produce the reduced form.
   */
  // 作用：指示该节点是否可以被简化（Reduce）为更简单的节点。
  // 如果一个节点表示 1 + 1，它的子类（如 BinaryExpression）理论上可以重写此方法返回 true。
  public boolean canReduce() {
    return false;
  }
}
