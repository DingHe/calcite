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

import java.lang.reflect.Type;

/**
 * Statement.
 */
// 在 Java 语法体系以及 Linq4j 的抽象语法树中，代码节点被严格划分为两大阵营：
// Expression（表达式）：有返回值，可以嵌套。例如 a + b、invokingMethod()、常量 10。
// Statement（语句）：没有返回值，是执行的基本单位，必须以分号结尾或作为结构化控制块。例如 if (...) { ... }、for (...) { ... }、return expr;。
// Statement 类就是所有“语句型”语法节点的最高抽象鼻祖（基类）。
// 它的具体子类衍生出了 Java 中几乎所有的行为控制和流转结构：
// BlockStatement：由大括号 { ... } 包裹的代码块。
// DeclarationStatement：局部变量声明语句（如 int a = 1;）。
// ConditionalStatement：条件分支语句（如 if-else）。
// GotoStatement：跳转语句（如 return、break、throw）。
public abstract class Statement extends AbstractNode {
  protected Statement(ExpressionType nodeType, Type type) {
    super(nodeType, type);
  }

  @Override final void accept(ExpressionWriter writer, int lprec, int rprec) {
    assert lprec == 0;
    assert rprec == 0;
    accept0(writer);
  }
  //语句只能是语句，不能变成表达式
  // Make return type more specific. A statement can only become a different
  // kind of statement; it can't become an expression.
  @Override public abstract Statement accept(Shuttle shuttle);
}
