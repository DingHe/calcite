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

import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * Declaration of a field.
 */
// 在 Calcite 的 Linq4j 表达式树（AST）中，FieldDeclaration 专门用来抽象和代表 Java 类内部的“成员变量（字段）声明”。
// 当 Calcite 决定了最终的物理计划后，往往需要动态生成一段 Java 源码并现场编译执行。
// 在这个动态生成类的过程中，如果需要给这个类定义一个或多个成员变量，就会在语法树中塞入 FieldDeclaration 节点。
// 例如，当你需要生成如下的 Java 字段代码时：
// Linq4j 就会通过一个 FieldDeclaration 实例来在内存中结构化地表达它：
// 将修饰符拆解为数字位：Modifier.PUBLIC | Modifier.FINAL
// 将类型和名字打包为：一个 ParameterExpression（int id）
// 将赋值部分打包为：一个 Expression（常量 0）
public class FieldDeclaration extends MemberDeclaration {
  // 字段修饰符。
  // 使用 Java 标准反射库中的 java.lang.reflect.Modifier 编码（如 public, private, static, final）。例如 17 代表 public final（$1 + 16$）。
  public final int modifier;
  // 核心变量定义。
  // 在 Linq4j 中，字段的“类型”和“变量名”被复用抽象为一个 ParameterExpression 表达式（包含 .type 和 .name）。它绝不能为空。
  public final ParameterExpression parameter;
  // 字段的初始化赋值表达式（可选）。
  // 可以为 null。如果为 null，代表该字段声明时没有初始值（如 int a;）；如果不为 null，代表带有右值（如 = 0;）。
  public final @Nullable Expression initializer;

  public FieldDeclaration(int modifier, ParameterExpression parameter,
      @Nullable Expression initializer) {
    assert parameter != null : "parameter should not be null";
    this.modifier = modifier;
    this.parameter = parameter;
    this.initializer = initializer;
  }
  // 允许利用 Shuttle（双向穿梭器）来遍历并修改当前字段声明树的拓扑结构。
  //
  @Override public MemberDeclaration accept(Shuttle shuttle) {
    // 先让穿梭器在进入前做一次前置盘查。
    shuttle = shuttle.preVisit(this);
    // do not visit parameter - visit may not return a ParameterExpression
    // 核心递归点。
    // 如果当前字段带有初始值（initializer != null），则让 shuttle 深入到初始值表达式的内部去进行访问和潜在的修改，并返回全新（或未变）的 initializer 表达式。
    // 为什么不 visit 参数（parameter）：源码注释特意强调了 // do not visit parameter。
    // 因为 shuttle.visit 返回的节点类型是通用的 Expression，而字段的 parameter 属性在语法上严格要求必须是 ParameterExpression（带有确定的名字和类型）。为了防止穿梭器乱改导致类型降级垮塌，此处故意切断了对 parameter 的递归。
    final Expression initializer =
        this.initializer == null ? null : this.initializer.accept(shuttle);
    // 最后将新（或旧）的初始值与自身打包，由 shuttle 决定是否需要生成一个新的 FieldDeclaration 返回。
    return shuttle.visit(this, initializer);
  }

  @Override public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
  //在writer写入字段声明,例如 int a = 0;
  @Override public void accept(ExpressionWriter writer) {
    String modifiers = Modifier.toString(modifier);
    writer.append(modifiers);
    if (!modifiers.isEmpty()) {
      writer.append(' ');
    }
    writer.append(parameter.type).append(' ').append(parameter.name);
    if (initializer != null) {
      writer.append(" = ").append(initializer);
    }
    writer.append(';');
    writer.newlineAndIndent();
  }

  @Override public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    FieldDeclaration that = (FieldDeclaration) o;

    if (modifier != that.modifier) {
      return false;
    }
    if (initializer != null ? !initializer.equals(that.initializer) : that
        .initializer != null) {
      return false;
    }
    if (!parameter.equals(that.parameter)) {
      return false;
    }

    return true;
  }

  @Override public int hashCode() {
    return Objects.hash(modifier, parameter, initializer);
  }
}
