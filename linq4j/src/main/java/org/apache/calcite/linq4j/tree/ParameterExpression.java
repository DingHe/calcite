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

/** 表达式树中传递的一个参数。它类似于函数或方法的参数，ParameterExpression x = Expressions.parameter(Integer.class, "x")，// 创建一个名为 'x' 的参数，类型为 Integer
 * Represents a named parameter expression. 通过ExpressionType.Parameter表示参数表达式
 */
public class ParameterExpression extends Expression {
  private static final AtomicInteger SEQ = new AtomicInteger(); //用于生成函数名

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
