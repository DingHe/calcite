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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a named parameter expression.
 */
// ParameterExpression 的核心作用是 代表一个命名的参数或局部变量。
// 在 Calcite 将 SQL 转换为 Java 代码的过程中，它需要处理各种变量。例如，在 Lambda 表达式 (Employee e) -> e.deptno 中，e 就是一个 ParameterExpression。
// 关键职能包括：
// 定义变量标识符：为生成的 Java 代码提供合法的变量名（如 p0, p1 或自定义名称）。
// 持有类型信息：明确该变量在 Java 运行时的物理类型（如 int, String 或自定义的 POJO 类）。
// 闭包与作用域管理：在构建复杂的表达式树时，通过引用同一个 ParameterExpression 实例，确保生成的代码中指向的是同一个变量。
public class ParameterExpression extends Expression {
  // 全局序列生成器。
  // 这是一个静态的线程安全计数器。当用户创建一个参数但没有指定名称时，类会使用它来生成唯一的默认名称（如 p0, p1, p2...），确保在同一个代码块中变量名不冲突。
  private static final AtomicInteger SEQ = new AtomicInteger();

  public final int modifier; //修饰符
  public final String name; //参数名称

  public ParameterExpression(Type type) {
    this(0, type, "p" + SEQ.getAndIncrement());
  }

  public ParameterExpression(int modifier, Type type, String name) {
    super(ExpressionType.Parameter, type);
    assert name != null : "name should not be null";
    assert Character.isJavaIdentifierStart(name.charAt(0))
      : "parameter name should be valid java identifier: "
        + name + ". The first character is invalid.";
    this.modifier = modifier;
    this.name = name;
  }
  //什么也没做，直接返回this
  @Override public Expression accept(Shuttle shuttle) {
    return shuttle.visit(this);
  }
  //返回 null
  @Override public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
  //通过查找evaluator的站，找到this，则返回值
  @Override public @Nullable Object evaluate(Evaluator evaluator) {
    return evaluator.peek(this);
  }

  @Override void accept(ExpressionWriter writer, int lprec, int rprec) {
    writer.append(name);  //把变量名称写入到writer
  }

  String declString() {
    return declString(type);
  }
  //生成的java代码
  String declString(Type type) {
    final String modifiers = Modifier.toString(modifier);
    return modifiers + (modifiers.isEmpty() ? "" : " ") + Types.className(type)
        + " " + name;
  }

  @Override public boolean equals(@Nullable Object o) {
    return this == o;
  }

  @Override public int hashCode() {
    return System.identityHashCode(this);
  }
}
