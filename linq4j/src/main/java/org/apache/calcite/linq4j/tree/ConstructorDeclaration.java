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
 * Declaration of a constructor.
 */
// 在 Calcite 的 Linq4j 抽象语法树（AST）中，ConstructorDeclaration 的主要作用是在内存中以结构化对象的形式，完整抽象和代表 Java 类中的“构造函数（构造方法）”。
// 当 Calcite 在运行期需要动态生成一个全新的类（通常是为了实现某些特定的物理算子计算逻辑、动态的数据清洗、或者特定的迭代器接口）时，该类往往需要进行状态初始化。
// 例如，动态生成的类可能需要通过构造函数把外部的上下文变量（如 DataContext、或者外部传进来的核心状态数组）赋值给内部的私有字段：
//
public class ConstructorDeclaration extends MemberDeclaration {
  // 构造函数修饰符。使用 java.lang.reflect.Modifier 的位掩码编码（如 Modifier.PUBLIC、Modifier.PRIVATE 等）。
  public final int modifier;
  // 构造函数所属的类类型。
  // 在 Java 中，构造函数没有返回值，它的名称与类名一致。因此，这里借用 resultType 属性来存储当前构造函数所依附的那个宿主类类型（即类名）。
  public final Type resultType;
  // 构造函数的形参列表。一个有序集合，存放着初始化该类时所需传入的所有参数（如 [DataContext context]）。
  public final List<ParameterExpression> parameters;
  // 构造函数体。由大括号 { ... } 包裹的初始化逻辑代码块（如执行 this.x = x; 等赋值语句）。
  public final BlockStatement body;
  /** Cached hash code for the expression. */
  private int hash;

  public ConstructorDeclaration(int modifier, Type declaredAgainst,
      List<ParameterExpression> parameters, BlockStatement body) {
    assert parameters != null : "parameters should not be null";
    assert body != null : "body should not be null";
    assert declaredAgainst != null : "declaredAgainst should not be null";
    this.modifier = modifier;
    this.resultType = declaredAgainst;
    this.parameters = parameters;
    this.body = body;
  }

  @Override public MemberDeclaration accept(Shuttle shuttle) {
    shuttle = shuttle.preVisit(this);
    // do not visit parameters
    final BlockStatement body = this.body.accept(shuttle);
    return shuttle.visit(this, body);
  }

  @Override public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  @Override public void accept(ExpressionWriter writer) {
    String modifiers = Modifier.toString(modifier);
    writer.append(modifiers);
    if (!modifiers.isEmpty()) {
      writer.append(' ');
    }
    //noinspection unchecked
    writer
        .append(resultType)
        .list("(", ", ", ")",
            () -> (Iterator) parameters.stream().map(parameter -> {
              final String modifiers1 =
                  Modifier.toString(parameter.modifier);
              return modifiers1 + (modifiers1.isEmpty() ? "" : " ")
                  + Types.className(parameter.getType()) + " "
                  + parameter.name;
            }).iterator())
        .append(' ').append(body);
    writer.newlineAndIndent();
  }

  @Override public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    ConstructorDeclaration that = (ConstructorDeclaration) o;

    if (modifier != that.modifier) {
      return false;
    }
    if (!body.equals(that.body)) {
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
    int result = hash;
    if (result == 0) {
      result = Objects.hash(modifier, resultType, parameters, body);
      if (result == 0) {
        result = 1;
      }
      hash = result;
    }
    return result;
  }
}
