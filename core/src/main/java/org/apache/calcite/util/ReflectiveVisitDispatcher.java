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
package org.apache.calcite.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Interface for looking up methods relating to reflective visitation. One
 * possible implementation would cache the results.
 *
 * <p>Type parameter 'R' is the base class of visitoR class; type parameter 'E'
 * is the base class of visiteE class.
 *
 * <p>TODO: obsolete {@link ReflectUtil#lookupVisitMethod}, and use caching in
 * implementing that method.
 *
 * @param <E> Argument type
 * @param <R> Return type
 */
// 在编译器和 SQL 优化器开发中，访问者模式（Visitor Pattern）几乎无处不在（例如遍历抽象语法树 AST、关系代数树 RelNode 或行表达式 RexNode）。
// 传统访问者模式的痛点
// 标准的 GoF 访问者模式严重依赖于静态双重分派（Double Dispatching）。这意味着：
// 每一个被访问的节点类（Visitee，如 LogicalFilter, LogicalProject）都必须死死实现一个 accept(Visitor v) 方法。
// 每一个访问者类（Visitor）都必须在接口里写死一系列长相相似的方法，如 visit(LogicalFilter f), visit(LogicalProject p)。
// 致命限制：如果你引入了一个自定义的扩展节点类，但你没办法去改动底层的核心组件源码（无法在其类里添加 accept 方法），整个静态访问者模式就会彻底失效。
// Calcite 创新性地引入了反射式访问者模式（Reflective Visitor Pattern）。通过这个接口的实现类（通常是结合了高频缓存的缓存分发器），被访问的、类（Visitee）不再需要实现任何 accept 方法。
// 当系统拿到一个抽象的 RelNode 时，ReflectiveVisitDispatcher 会在运行时动态获取该节点的实际具体类型（Concrete Class），然后通过反射在 Visitor 内部寻找最精准、最匹配的 visit(具体类型) 方法并触发调用。这带来了极强的框架可扩展性（Extensibility）和解耦性。
// <R extends ReflectiveVisitor> 指定了访问者（Visitor）的基类/顶层接口。 限制传入的访问者对象必须是 ReflectiveVisitor 的子类。这是一种标记性约束，确保该分派器只为属于 Calcite 反射体系内的访问者服务。
// <E extends Object> 指定了被访问者（Visitee / Element）的基类，定义了被遍历的算子树或表达式树的根类型。例如在遍历关系代数时，E 通常是 RelNode；在遍历表达式时，E 通常是 RexNode。
public interface ReflectiveVisitDispatcher<R extends ReflectiveVisitor,
    E extends Object> {
  //~ Methods ----------------------------------------------------------------

  /**
   * Looks up a visit method taking additional parameters beyond the
   * overloaded visitee type.
   *
   * @param visitorClass             class of object whose visit method is to be
   *                                 invoked
   * @param visiteeClass             class of object to be passed as a parameter
   *                                 to the visit method
   * @param visitMethodName          name of visit method
   * @param additionalParameterTypes list of additional parameter types
   * @return method found, or null if none found
   */
  // 在运行时精确查找一个符合特定多参数特征的访问控制方法。不仅比对被访问节点的类型，还要比对访问时附带传入的其他上下文参数类型。
  @Nullable Method lookupVisitMethod(
      // 访问者（Visitor）的具体类类型。系统将在这个类及其父类中搜寻目标方法。
      Class<? extends R> visitorClass,
      // 当前被访问节点（Visitee）的具体类类型。它对应了目标 visit 方法的第一个入参。
      Class<? extends E> visiteeClass,
      // 期望的方法名。虽然默认通常是 "visit"，但该参数允许自定义（如 "gather" 或 "derive"）。
      String visitMethodName,
      // 一个包含额外参数类型的列表。对应了目标 visit 方法除了第一个参数之外的后续参数列表。
      List<Class> additionalParameterTypes);

  /**
   * Looks up a visit method.
   *
   * @param visitorClass    class of object whose visit method is to be invoked
   * @param visiteeClass    class of object to be passed as a parameter to the
   *                        visit method
   * @param visitMethodName name of visit method
   * @return method found, or null if none found
   */
  // 最常用的简化检索方法。专门用于寻找只接收“被访问节点”这一个参数的标准 visit 方法。例如：寻找 MyPlannerVisitor.visit(LogicalProject rel)。
  @Nullable Method lookupVisitMethod(
      // 访问者的具体 Class。
      Class<? extends R> visitorClass,
      // 被访问节点的具体 Class。
      Class<? extends E> visiteeClass,
      // 方法名（如 "visit"）。
      String visitMethodName);

  /**
   * Implements the {@link org.apache.calcite.util.Glossary#VISITOR_PATTERN} via
   * reflection. The basic technique is taken from <a
   * href="http://www.javaworld.com/javaworld/javatips/jw-javatip98.html">a
   * Javaworld article</a>. For an example of how to use it, see
   * {@code ReflectVisitorTest}.
   *
   * <p>Visit method lookup follows the same rules as if compile-time resolution
   * for VisitorClass.visit(VisiteeClass) were performed. An ambiguous match due
   * to multiple interface inheritance results in an IllegalArgumentException. A
   * non-match is indicated by returning false.
   *
   * @param visitor         object whose visit method is to be invoked
   * @param visitee         object to be passed as a parameter to the visit
   *                        method
   * @param visitMethodName name of visit method, e.g. "visit"
   * @return true if a matching visit method was found and invoked
   */
  // 直接执行驱动器
  // 内部通常会先调用上述的 lookupVisitMethod 动态捞出正确的重载方法，接着利用反射当场将该方法执行掉（Invoke）。
  boolean invokeVisitor(
      R visitor,
      E visitee,
      String visitMethodName);
}
