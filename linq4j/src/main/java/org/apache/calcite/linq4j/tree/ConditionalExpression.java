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
import java.util.List;
import java.util.Objects;

/**
 * Represents an expression that has a conditional operator.
 *
 * <p>With an odd number of expressions
 * {c0, e0, c1, e1, ..., c<sub>n-1</sub>, e<sub>n-1</sub>, e<sub>n</sub>}
 * represents "if (c0) e0 else if (c1) e1 ... else e<sub>n</sub>";
 * with an even number of expressions
 * {c0, e0, c1, e1, ..., c<sub>n-1</sub>, e<sub>n-1</sub>}
 * represents
 * "if (c0) e0 else if (c1) e1 ... else if (c<sub>n-1</sub>) e<sub>n-1</sub>".
 */
// ConditionalExpression 是一个用于表示条件逻辑控制流的类。虽然它的名字叫 "Expression"，但它在生成 Java 代码时主要被解析为 if-else 结构。
// 核心作用是表示多重分支条件选择。
// 逻辑表示：它不仅仅表示简单的 if-else，还支持 if-else if-else 链。
// 列表结构：它通过一个 List<Node> 来存储条件和执行体。列表的奇偶性决定了逻辑的完整性：
// 奇数个元素：{c0, e0, c1, e1, ..., en}。最后多出来的 en 节点被视为最终的 else 块。
// 偶数个元素：{c0, e0, c1, e1}。所有的节点都是成对出现的 if (condition) { execution }，没有最终的兜底 else。
// 代码生成映射：它负责将这些抽象的 Node 节点按照 Java 语法规则拼接成标准的条件分支语句块。
public class ConditionalExpression extends AbstractNode {
  // 存储条件表达式（Conditions）和对应的执行块（Expressions/Blocks）。
  // 存储规则：
  // 索引为 0, 2, 4... 的元素通常是布尔类型的 Node（条件）。
  // 索引为 1, 3, 5... 的元素是对应条件满足时执行的 Node。
  // 如果列表长度为奇数，最后一个元素是 else 块。
  final List<Node> expressionList;

  public ConditionalExpression(List<Node> expressionList, Type type) {
    super(ExpressionType.Conditional, type);
    assert expressionList != null : "expressionList should not be null";
    this.expressionList = expressionList;
  }
  // 支持只读遍历。
  // 说明：调用访问者的 visit(this) 方法。这允许 Calcite 的其他组件（如类型检查器或解释器）处理这个分支节点。
  @Override public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
  // 作用：将节点转化为 Java 源代码。
  @Override void accept(ExpressionWriter writer, int lprec, int rprec) {
    // 循环处理对偶节点
    for (int i = 0; i < expressionList.size(); i += 2) {
      // 如果是第一次循环（i=0），写入 if (；否则写入 else if (
      writer.append(i > 0 ? " else if (" : "if (")
          .append(expressionList.get(i))
          .append(") ")
          .append(Blocks.toBlock(expressionList.get(i + 1)));
    }
    // 如果余数为 1，说明最后一个元素是 else 部分。
    if (expressionList.size() % 2 == 1) {
      writer.append(" else ")
          .append(
              Blocks.toBlock(expressionList.get(expressionList.size() - 1)));
    }
  }

  @Override public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }

    ConditionalExpression that = (ConditionalExpression) o;

    if (!expressionList.equals(that.expressionList)) {
      return false;
    }

    return true;
  }

  @Override public int hashCode() {
    return Objects.hash(nodeType, type, expressionList);
  }
}
