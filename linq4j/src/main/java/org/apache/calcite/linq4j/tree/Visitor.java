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
 * Node visitor.
 *
 * @param <R> Return type
 */
// Visitor<R> 接口是实现**访问者模式（Visitor Pattern）**的核心定义。它为遍历和处理复杂的表达式树（Expression Tree）提供了一套标准的行为规范。
// Visitor<R> 接口的主要作用是实现算法与数据结构的分离。
// 统一的遍历入口：由于表达式树由多种不同类型的节点（如二元运算、方法调用、循环语句等）组成，Visitor 为每一种节点类型都定义了一个处理方法。
// 解耦：如果你需要对表达式树进行某些操作（例如：将树转换成 SQL 字符串、进行常量折叠优化、统计节点数量等），你不需要修改节点类本身，只需实现一个新的 Visitor 即可。
// 泛型支持 <R>：通过泛型参数 R，访问者可以灵活地定义返回值的类型。例如，转换成字符串时 R 是 String，进行求值时 R 是 Object。
public interface Visitor<R> {
  R visit(BinaryExpression binaryExpression);
  R visit(BlockStatement blockStatement);
  R visit(ClassDeclaration classDeclaration);
  R visit(ConditionalExpression conditionalExpression);
  R visit(ConditionalStatement conditionalStatement);
  R visit(ConstantExpression constantExpression);
  R visit(ConstructorDeclaration constructorDeclaration);
  R visit(DeclarationStatement declarationStatement);
  R visit(DefaultExpression defaultExpression);
  R visit(DynamicExpression dynamicExpression);
  R visit(FieldDeclaration fieldDeclaration);
  R visit(ForStatement forStatement);
  R visit(ForEachStatement forEachStatement);
  R visit(FunctionExpression functionExpression);
  R visit(GotoStatement gotoStatement);
  R visit(IndexExpression indexExpression);
  R visit(InvocationExpression invocationExpression);
  R visit(LabelStatement labelStatement);
  R visit(LambdaExpression lambdaExpression);
  R visit(ListInitExpression listInitExpression);
  R visit(MemberExpression memberExpression);
  R visit(MemberInitExpression memberInitExpression);
  R visit(MethodCallExpression methodCallExpression);
  R visit(MethodDeclaration methodDeclaration);
  R visit(NewArrayExpression newArrayExpression);
  R visit(NewExpression newExpression);
  R visit(ParameterExpression parameterExpression);
  R visit(SwitchStatement switchStatement);
  R visit(TernaryExpression ternaryExpression);
  R visit(ThrowStatement throwStatement);
  R visit(TryStatement tryStatement);
  R visit(TypeBinaryExpression typeBinaryExpression);
  R visit(UnaryExpression unaryExpression);
  R visit(WhileStatement whileStatement);
}
