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

import java.lang.reflect.Type;
import java.util.Objects;

/**
 * Abstract implementation of {@link Node}.
 */
// AbstractNode 是一个至关重要的基类。它实现了 Node 接口，为表达式树中的所有具体节点（如常量、二元运算、方法调用等）提供了通用的行为和属性。
// 统一存储元数据：它强制所有节点都必须具备“节点操作类型（ExpressionType）”和“数据类型（Type）”这两个基本属性。
// 默认行为定义：它实现了 toString、equals 和 hashCode 等通用方法，简化了子类的开发。
// 定义解析规范：它引入了带优先级的 accept 方法重载，这是 Calcite 能够正确处理括号逻辑的关键。
// 异常屏障：对于一些可选的功能（如求值 evaluate 或转换 shuttle），它提供了默认的抛出异常实现，强制要求支持这些功能的子类必须进行重写。
public abstract class AbstractNode implements Node {
  // 标识该节点代表的操作类型。
  // 引用了ExpressionType 枚举（如 Add, Assign, Call）。通过这个属性，程序可以在不使用 instanceof 的情况下快速判断节点的逻辑功能。
  public final ExpressionType nodeType;
  // 标识该表达式返回的结果类型。
  // 详细：使用 Java 标准的 java.lang.reflect.Type。例如，一个 1 + 1 的节点，其 type 通常是 int.class。这在代码生成阶段决定了变量声明的类型。
  public final Type type;

  AbstractNode(ExpressionType nodeType, Type type) {
    this.type = type;
    this.nodeType = nodeType;
  }

  /**
   * Gets the node type of this Expression.
   */
  public ExpressionType getNodeType() {
    return nodeType;
  }

  /**
   * Gets the static type of the expression that this Expression
   * represents.
   */
  public Type getType() {
    return type;
  }
  // 将节点转换成可读的 Java 源代码字符串。
  @Override public String toString() {
    ExpressionWriter writer = new ExpressionWriter(true);
    accept(writer, 0, 0);
    return writer.toString();
  }
  // 转发给带优先级参数的 accept 方法，初始优先级设为 0, 0（表示最外层，不需要括号）
  @Override public void accept(ExpressionWriter writer) {
    accept(writer, 0, 0);
  }
  // 同样转发给带优先级的 accept，用于某些特定场景下的简单触发。
  void accept0(ExpressionWriter writer) {
    accept(writer, 0, 0);
  }
  // 子类（如 BinaryExpression）必须重写此方法。
  void accept(ExpressionWriter writer, int lprec, int rprec) {
    throw new RuntimeException(
        "un-parse not supported: " + getClass() + ":" + nodeType);
  }
  // 实现自 Node 接口，用于树的重写。
  // 实现：默认抛出“不支持访问”的异常。只有实现了重写逻辑的子类才会覆盖它。
  @Override public Node accept(Shuttle shuttle) {
    throw new RuntimeException(
        "visit not supported: " + getClass() + ":" + nodeType);
  }
  // 在内存中直接计算该节点的值。
  // 场景：当 Calcite 不生成代码而是选择直接解释执行（Interpreter 模式）时会用到。
  public @Nullable Object evaluate(Evaluator evaluator) {
    throw new RuntimeException(
        "evaluation not supported: " + getClass() + ":" + nodeType);
  }

  @Override public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    AbstractNode that = (AbstractNode) o;

    if (nodeType != that.nodeType) {
      return false;
    }
    if (type != null ? !type.equals(that.type) : that.type != null) {
      return false;
    }

    return true;
  }

  @Override public int hashCode() {
    return Objects.hash(nodeType, type);
  }
}
