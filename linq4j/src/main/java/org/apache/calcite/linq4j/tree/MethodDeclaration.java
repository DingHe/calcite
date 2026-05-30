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
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Declaration of a method.
 */
// 专门用来描述和构建类内部的方法（函数）。
// 在 Calcite 的 Linq4j 表达式树（AST）中，MethodDeclaration 的作用是在内存中以结构化对象的形式，完整抽象和代表 Java 类中的一个“方法声明”。
// Calcite 拥有强大的运行时代码生成能力（Code Generation）。
// 当它把逻辑或物理执行计划（如某一个特定的物理 Join 或 Aggregation 算子）翻译成最终的 Java 源码时，
// 必须动态生成一个包含特定计算逻辑的方法（最经典的就是生成迭代器里的 next()、hasNext() 方法，或者自定义的 eval() 算术求值方法）。
public class MethodDeclaration extends MemberDeclaration {
  // 方法修饰符。与字段类似，采用 java.lang.reflect.Modifier 的位掩码编码（如 Modifier.PUBLIC | Modifier.STATIC）。
  public final int modifier;
  // 方法名称。例如字符串 "eval"、"hasNext"。绝不能为空。
  public final String name;
  // 返回值类型。使用 Java 标称类型接口 Type（可以是 Class<?> 如 int.class，或者是泛型类型）。绝不能为空。
  public final Type resultType;
  // 方法的形参列表。
  // 一个有序集合，里面存放着该方法接收的所有入参（包含参数类型和参数名，如 [int a, String b]）。
  public final List<ParameterExpression> parameters;
  // 方法体（函数体）。
  // 由 { ... } 包裹的核心执行代码块，内部可以包含多条 Java 语句（Statement）。
  public final BlockStatement body; //函数体

  public MethodDeclaration(int modifier, String name, Type resultType,
      List<ParameterExpression> parameters, BlockStatement body) {
    assert name != null : "name should not be null";
    assert resultType != null : "resultType should not be null";
    assert parameters != null : "parameters should not be null";
    assert body != null : "body should not be null";
    this.modifier = modifier;
    this.name = name;
    this.resultType = resultType;
    this.parameters = parameters;
    this.body = body;
  }
  // 允许利用 Shuttle（穿梭器）深度遍历并改写当前方法内部的代码逻辑（方法体）。
  @Override public MemberDeclaration accept(Shuttle shuttle) {
    // 首先让穿梭器对方法节点本身进行前置访问。
    shuttle = shuttle.preVisit(this);
    // do not visit parameters
    // 核心递归点。让穿梭器深入到当前方法的 body（方法体）内部。
    // 方法体里的多行代码、表达式都会被穿梭器过一遍，如果里面有需要改写的逻辑，会在此处完成并返回一个新的 BlockStatement。
    final BlockStatement body = this.body.accept(shuttle);
    // 最终将新（或未变）的方法体与自身打包，由 shuttle 决定是否生成并返回一个新的 MethodDeclaration。
    return shuttle.visit(this, body);
  }

  @Override public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
  //生成代码
  @Override public void accept(ExpressionWriter writer) {
    String modifiers = Modifier.toString(modifier);
    writer.append(modifiers);
    if (!modifiers.isEmpty()) {
      writer.append(' ');
    }
    //noinspection unchecked
    writer
        .append(resultType)
        .append(' ')
        .append(name)
        .list("(", ", ", ")",
            () -> (Iterator) parameters.stream().map(ParameterExpression::declString).iterator())
        .append(' ')
        .append(body);
    writer.newlineAndIndent();
  }

  @Override public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    MethodDeclaration that = (MethodDeclaration) o;

    if (modifier != that.modifier) {
      return false;
    }
    if (!body.equals(that.body)) {
      return false;
    }
    if (!name.equals(that.name)) {
      return false;
    }
    if (!parameters.equals(that.parameters)) {
      return false;
    }
    if (!resultType.equals(that.resultType)) {
      return false;
    }

    return true;
  }

  @Override public int hashCode() {
    return Objects.hash(modifier, name, resultType, parameters, body);
  }
}
