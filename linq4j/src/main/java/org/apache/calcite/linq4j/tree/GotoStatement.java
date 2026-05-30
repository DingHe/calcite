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

import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Represents an unconditional jump. This includes return statements, break and
 * continue statements, and other jumps.
 */
// GotoStatement 是 Apache Calcite 项目子模块 Linq4j 中的核心组件，继承自 Statement（语句基类）。
// 虽然它在名字上带有 "Goto"，但它在功能上并不是单纯代表传统的 goto 语句，而是所有“无条件跳转与控制流转向”语法结构的统一抽象。
// 在 Calcite 的 Linq4j 抽象语法树（AST）中，GotoStatement 的主要职责是在内存中统一抽象描述一切能够改变代码正常线性执行顺序的“跳转行为”。
// 为了减少语法树节点的复杂度和类的膨胀，Linq4j 并没有为 return、break、continue 独立创建各自的 Class，而是通过 GotoStatement 配合一个内部枚举 GotoExpressionKind 进行了高内聚的泛化设计。
// 典型应用场景
//当 Calcite 动态生成可枚举物理算子（EnumerableRel）的计算代码时，在方法体（如 BlockStatement）的最后，必须要返回计算结果（例如 return true; 或 return accumulator;），此时底层就会无一例外地创建并插入一个 GotoStatement 节点。
public class GotoStatement extends Statement {
  // 跳转类型枚举。
  // 决定了当前节点的真实具体语义。其可选值包括：Break、Continue、Goto、Return、Sequence。其内部通常自带有代码关键字的前缀字符串（如 .prefix 为 "return"）。
  public final GotoExpressionKind kind;
  // 跳转的目标标签（Label）。可以为 null。只有当 kind 为 Goto 或者带标签的 break/continue 时，该属性才有用，用来指定要跳转到哪个代码块标记去。
  public final @Nullable LabelTarget labelTarget;
  // 跳转时携带的表达式值。可以为 null。
  // 最典型的就是 return 语句后面跟着的返回值表达式（如 return a + b; 中的 a + b）。
  // 如果是单纯的 break; 或 continue;，该属性固定为 null。
  public final @Nullable Expression expression;

  GotoStatement(GotoExpressionKind kind, @Nullable LabelTarget labelTarget,
      @Nullable Expression expression) {
    super(ExpressionType.Goto,
        expression == null ? Void.TYPE : expression.getType());
    assert kind != null : "kind should not be null";
    this.kind = kind;
    this.labelTarget = labelTarget;
    this.expression = expression;

    switch (kind) {
    case Break:
    case Continue:
      assert expression == null;
      break;
    case Goto:
      assert expression == null;
      assert labelTarget != null;
      break;
    case Return:
    case Sequence:
      assert labelTarget == null;
      break;
    default:
      throw new RuntimeException("unexpected: " + kind);
    }
  }

  @Override public Statement accept(Shuttle shuttle) {
    shuttle = shuttle.preVisit(this);
    Expression expression1 =
        expression == null ? null : expression.accept(shuttle);
    return shuttle.visit(this, expression1);
  }

  @Override public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  @Override void accept0(ExpressionWriter writer) {
    writer.append(kind.prefix);
    if (labelTarget != null) {
      writer.append(' ').append(labelTarget.name);
    }
    if (expression != null) {
      if (!kind.prefix.isEmpty()) {
        writer.append(' ');
      }
      switch (kind) {
      case Sequence:
        // don't indent for sequence
        expression.accept(writer, 0, 0);
        break;
      default:
        writer.begin();
        expression.accept(writer, 0, 0);
        writer.end();
      }
    }
    writer.append(';').newlineAndIndent();
  }

  @Override public @Nullable Object evaluate(Evaluator evaluator) {
    switch (kind) {
    case Return:
    case Sequence:
      // NOTE: We ignore control flow. This is only correct if "return"
      // is the last statement in the block.
      return requireNonNull(expression, "expression").evaluate(evaluator);
    default:
      throw new AssertionError("evaluate not implemented");
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

    GotoStatement that = (GotoStatement) o;

    if (expression != null ? !expression.equals(that.expression) : that
        .expression != null) {
      return false;
    }
    if (kind != that.kind) {
      return false;
    }
    if (labelTarget != null ? !labelTarget.equals(that.labelTarget) : that
        .labelTarget != null) {
      return false;
    }

    return true;
  }

  @Override public int hashCode() {
    return Objects.hash(nodeType, type, kind, labelTarget, expression);
  }
}
