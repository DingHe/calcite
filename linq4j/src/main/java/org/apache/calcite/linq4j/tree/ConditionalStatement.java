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
// ConditionalStatement 是 Apache Calcite 项目子模块 Linq4j 中的一个核心类，继承自上文提到的 Statement（语句基类）。
// 它专门用来在内存中抽象和表达 Java 中的条件分支控制语句（即 if - else if - else 结构）。
// 在 Calcite 的 Linq4j 抽象语法树（AST）中，ConditionalStatement 的主要职责是将复杂的、多分支的 if-else 条件控制流，以一种高度紧凑的“扁平化列表”结构存储在内存中，并支持将其动态渲染为标准的 Java 源代码。
// 核心亮点：扁平化的交替存储设计
//传统的 AST 表达 if-else 时，往往采用嵌套的树状结构（即 if 节点里面包含一个 else 节点，else 节点又是一个全新的 if 节点）。这样做会导致树的层次极深，遍历和解析非常痛苦。
// Linq4j 采取了一种非常聪明的交替存储设计（Alternating List）。它将所有的条件表达式（Condition）和执行代码块（Block）通通塞进一个一维列表 expressionList 中，通过列表元素的奇偶数量来决定是否包含最后的 else 兜底分支：
// 情况 A：列表长度为偶数（如 {c0, e0, c1, e1}）代表只有 if 和 else if，没有最后的无条件 else 块。
// $$\text{渲染结果} \rightarrow \text{if (c0) \{e0\} else if (c1) \{e1\}}$$
// 典型应用场景
// 当 Calcite 在动态生成过滤逻辑（Filter）、分支选择、或者在内存中对 CASE WHEN ... THEN ... ELSE ... 的 SQL 语义进行代码生成（CodeGen）时，底层就会疯狂构建 ConditionalStatement 节点。
public class ConditionalStatement extends Statement {
  // 核心条件/行为混合交替列表。
  // 里面存放的节点类型必须是 Node。按 [条件0, 行为块0, 条件1, 行为块1, ... (可选的尾部行为块)] 的规则排列。绝不能为空。
  public final List<Node> expressionList;

  public ConditionalStatement(List<Node> expressionList) {
    super(ExpressionType.Conditional, Void.TYPE);
    assert expressionList != null : "expressionList should not be null";
    this.expressionList = expressionList;
  }

  @Override public Statement accept(Shuttle shuttle) {
    shuttle = shuttle.preVisit(this);
    List<Node> list = Expressions.acceptNodes(expressionList, shuttle);
    return shuttle.visit(this, list);
  }

  @Override public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  @Override void accept0(ExpressionWriter writer) {
    for (int i = 0; i < expressionList.size() - 1; i += 2) {
      if (i > 0) {
        writer.backUp();
        writer.append(" else ");
      }
      writer.append("if (")
          .append(expressionList.get(i))
          .append(") ")
          .append(Blocks.toBlock(expressionList.get(i + 1)));
    }
    if (expressionList.size() % 2 == 1) {
      writer.backUp();
      writer.append(" else ")
          .append(Blocks.toBlock(last(expressionList)));
    }
  }

  private static <E> E last(List<E> collection) {
    return collection.get(collection.size() - 1);
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

    ConditionalStatement that = (ConditionalStatement) o;

    if (!expressionList.equals(that.expressionList)) {
      return false;
    }

    return true;
  }

  @Override public int hashCode() {
    return Objects.hash(nodeType, type, expressionList);
  }
}
