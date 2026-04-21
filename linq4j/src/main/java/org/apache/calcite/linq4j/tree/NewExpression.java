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
 * Represents a constructor call.
 * <p>If {@link #memberDeclarations} is not null (even if empty) represents
 * an anonymous class.
 */
// NewExpression 的核心作用是 在内存中代表一个 Java 构造函数调用表达式（即 new 操作）。
// 当 Calcite 将 SQL 优化并转化为可执行的 Java 代码时，它并不会直接生成字符串形式的源码，而是先构建一棵表达式树。NewExpression 就是这棵树上的一个节点，用来描述以下两种场景：
// 1、普通对象实例化：例如 new Employee(1, "Alice")。
// 2、匿名内部类实例化：例如 new Comparator() { ... }。
// 通过这个类，Calcite 可以在运行时动态地组装、修改并最终通过 ExpressionWriter 输出合法的 Java 源代码。

public class NewExpression extends Expression {
  @SuppressWarnings("HidingField")
  // 表示要构造的对象的类型。
  public final Type type;
  // 构造函数的参数列表。
  // 这是一个表达式列表。由于参数本身也可以是复杂的计算逻辑，所以它们被封装为 Expression 对象。
  public final List<Expression> arguments;
  // 匿名类的成员声明。
  // 如果该字段不为 null（即使列表为空），则表示这是一个匿名内部类。列表内包含了该内部类定义的方法、字段等。
  public final @Nullable List<MemberDeclaration> memberDeclarations;
  /** Cached hash code for the expression. */
  private int hash;

  public NewExpression(Type type, List<Expression> arguments,
      @Nullable List<MemberDeclaration> memberDeclarations) {
    super(ExpressionType.New, type);
    this.type = type;
    this.arguments = arguments;
    this.memberDeclarations = memberDeclarations;
  }
  // 允许 Shuttle（转换器）遍历并修改该节点。
  @Override public Expression accept(Shuttle shuttle) {
    shuttle = shuttle.preVisit(this);
    final List<Expression> arguments =
        Expressions.acceptExpressions(this.arguments, shuttle);
    final List<MemberDeclaration> memberDeclarations =
        this.memberDeclarations == null
            ? null
            : Expressions.acceptMemberDeclarations(this.memberDeclarations,
                shuttle);
    return shuttle.visit(this, arguments, memberDeclarations);
  }

  @Override public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
  //生成代码
  @Override void accept(ExpressionWriter writer, int lprec, int rprec) {
    writer.append("new ").append(type).list("(\n", ",\n", ")", arguments);
    if (memberDeclarations != null) {
      writer.list("{\n", "", "}", memberDeclarations);
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

    NewExpression that = (NewExpression) o;

    if (arguments != null ? !arguments.equals(that.arguments)
        : that.arguments != null) {
      return false;
    }
    if (memberDeclarations
        != null ? !memberDeclarations.equals(that.memberDeclarations)
        : that.memberDeclarations != null) {
      return false;
    }
    if (!type.equals(that.type)) {
      return false;
    }

    return true;
  }

  @Override public int hashCode() {
    int result = hash;
    if (result == 0) {
      result =
          Objects.hash(nodeType, super.type, type, arguments, memberDeclarations);
      if (result == 0) {
        result = 1;
      }
      hash = result;
    }
    return result;
  }
}
