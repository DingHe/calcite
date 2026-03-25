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

/**
 * Describes a lambda expression. This captures a block of code that is similar
 * to a Java method body.
 */
// 在 Apache Calcite 的 linq4j 模块中，LambdaExpression（Lambda 表达式）是连接函数式编程与表达式树的关键桥梁。
// 它模拟了 Java 8 引入的 Lambda 语法（如 (x) -> x + 1），使得查询逻辑可以像数据一样被传递和解析。
// LambdaExpression 的核心作用是封装一个可执行的代码块及其参数定义。
// 表示匿名函数：它代表了一个具有输入参数和主体逻辑的匿名方法。在 Calcite 中，当你编写 LINQ 查询（如 .where(emp -> emp.deptno == 10)）时，那个 emp -> ... 就会被构建为一个 LambdaExpression。
// 支持高级序列操作：它是 Enumerable 接口中许多算子（如 select, where, aggregate）的核心参数类型。
// 代码生成的目标：在将 LINQ 树转换回 Java 源码时，它负责生成对应的 Lambda 语法或匿名内部类。
// 没有重写AbstractNode的方法accept(ExpressionWriter writer, int lprec, int rprec)，是因为构建的时候直接传入的类型type实际上就是函数
public class LambdaExpression extends Expression {
  // ExpressionType nodeType：通常固定为 ExpressionType.Lambda。
  // Class type：注意这里使用的是 Class 而不是宽泛的 Type。它通常代表该 Lambda 适配的函数式接口类型（例如 Predicate.class 或 Function.class）。
  public LambdaExpression(ExpressionType nodeType, Class type) {
    super(nodeType, type);
  }
  // 作用：支持**穿梭器（Shuttle）**遍历与转换。
  // 典型场景：如果你想在表达式树中全局替换某个变量名，或者将所有的 a + b 逻辑优化为其他形式，Shuttle 会递归进入 LambdaExpression 的主体进行修改。
  @Override public Expression accept(Shuttle shuttle) {
    return shuttle.visit(this);
  }
  // 作用：支持**访问者（Visitor）**遍历。
  // 静态检查：检查 Lambda 表达式是否引用了外部不可访问的变量。
  @Override public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

}
