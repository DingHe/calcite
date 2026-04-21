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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;

/**
 * Represents a call to either a static or an instance method.
 */
// MethodCallExpression 的核心作用是描述并生成 Java 方法调用的指令。
// 在 Calcite 将 SQL 转化为 Enumerable（可枚举）算子的过程中，绝大多数逻辑（如字符串处理、日期计算、自定义函数调用）最终都会变成 Java 方法的调用。
// 例如，SQL 中的 UPPER(name) 会被转换成一个 MethodCallExpression，其内容类似于 SqlFunctions.upper(name)。
// 该类不仅存储了方法的元数据（Method），还记录了调用者（target）和参数（args），并支持将其渲染成源代码字符串或通过反射直接执行。

public class MethodCallExpression extends Expression {
  // 存储 Java 反射包中的 Method 对象。
  // 包含了方法的名称、返回类型、参数列表以及修饰符（是否静态等）
  public final Method method;
  // 调用该方法的对象表达式（即主语）
  // 如果是实例方法（如 list.size()），则指向 list 的表达式。
  // 如果是静态方法（如 Math.max(a, b)），该值为 null。
  public final @Nullable Expression targetExpression;
  // 方法调用的参数列表。
  // 这是一个表达式列表，每个元素代表一个传递给方法的参数。
  public final List<Expression> expressions; //参数
  /** Cached hash code for the expression. */
  private int hash;

  MethodCallExpression(Type returnType, Method method,
      @Nullable Expression targetExpression, List<Expression> expressions) {
    super(ExpressionType.Call, returnType);
    assert expressions != null : "expressions should not be null";
    assert method != null : "method should not be null";
    assert (targetExpression == null) == Modifier.isStatic(
        method.getModifiers());
    assert Types.toClass(returnType) == method.getReturnType();
    this.method = method;
    this.targetExpression = targetExpression;
    this.expressions = expressions;
  }

  MethodCallExpression(Method method, @Nullable Expression targetExpression,
      List<Expression> expressions) {
    this(method.getReturnType(), method, targetExpression, expressions);
  }

  @Override public Expression accept(Shuttle shuttle) {
    shuttle = shuttle.preVisit(this);
    Expression targetExpression =
        this.targetExpression == null
            ? null
            : this.targetExpression.accept(shuttle);
    List<Expression> expressions =
        Expressions.acceptExpressions(this.expressions, shuttle);
    return shuttle.visit(this, targetExpression, expressions);
  }

  @Override public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  @Override public @Nullable Object evaluate(Evaluator evaluator) {
    final Object target;
    if (targetExpression == null) {
      target = null;
    } else {
      target = targetExpression.evaluate(evaluator);
    }
    final @Nullable Object[] args = new Object[expressions.size()];
    for (int i = 0; i < expressions.size(); i++) {
      Expression expression = expressions.get(i);
      args[i] = expression.evaluate(evaluator);
    }
    try {
      return method.invoke(target, args);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException("error while evaluating " + this, e);
    }
  }

  @Override void accept(ExpressionWriter writer, int lprec, int rprec) {
    if (writer.requireParentheses(this, lprec, rprec)) {
      return;
    }
    if (targetExpression != null) {
      // instance method，实例方法要实例化对象
      targetExpression.accept(writer, lprec, nodeType.lprec);
    } else {
      // static method，静态方法，直接通过类型调用
      writer.append(method.getDeclaringClass());
    }
    writer.append('.').append(method.getName()).append('(');
    int k = 0;
    for (Expression expression : expressions) {
      if (k++ > 0) {
        writer.append(", ");
      }
      expression.accept(writer, 0, 0);
    }
    writer.append(')');
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

    MethodCallExpression that = (MethodCallExpression) o;

    if (!expressions.equals(that.expressions)) {
      return false;
    }
    if (!method.equals(that.method)) {
      return false;
    }
    if (targetExpression != null ? !targetExpression.equals(that
        .targetExpression) : that.targetExpression != null) {
      return false;
    }

    return true;
  }

  @Override public int hashCode() {
    int result = hash;
    if (result == 0) {
      result =
          Objects.hash(nodeType, type, method, targetExpression, expressions);
      if (result == 0) {
        result = 1;
      }
      hash = result;
    }
    return result;
  }
}
