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
 * Declaration of a member of a class.
 */
// 在 Apache Calcite 的子项目 Linq4j 中，MemberDeclaration 是其抽象语法树（AST，Abstract Syntax Tree）中的一个关键节点类。
// 虽然这段代码看起来极其精简，但它在整个代码生成（Code Generation）和表达式树转换中扮演着非常核心的角色。
// Linq4j 是 Calcite 用于动态生成 Java 代码的底层框架。
// 在优化器（如 VolcanoPlanner）决定了最终的物理执行计划后，Calcite 需要将这些物理算子转换成真正的 Java 字节码或源码来执行，
// 这个过程大量使用了 Linq4j 的树状结构。
// 在 Java 语法中，一个完整的类（Class）是由多个成员（Members）组成的。MemberDeclaration（成员声明）就是用来抽象和代表类体内部定义的任何元素。
// MethodDeclaration（方法声明）：类内部定义的方法（如 public int eval(...)）。
// FieldDeclaration（字段/变量声明）：类内部定义的成员变量。
// ConstructorDeclaration（构造函数声明）：类的构造方法。
public abstract class MemberDeclaration implements Node {
  // 实现并强制要求子类支持“访问者模式（Visitor Pattern）”，用于对语法树进行遍历、改写或类型转换。
  @Override public abstract MemberDeclaration accept(Shuttle shuttle);
}
