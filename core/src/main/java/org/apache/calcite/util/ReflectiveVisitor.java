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

/**
 * Object which can be a target for a reflective visitation (see
 * {@link ReflectUtil#invokeVisitor(ReflectiveVisitor, Object, Class, String)}.
 *
 * <p>This is a tagging interface: it has no methods, and is not even required
 * in order to use reflective visitation, but serves to advise users of the
 * class of the intended use of the class and refer them to auxiliary classes.
 */
// ReflectiveVisitor 的核心作用是作为一个标记接口（Tagging Interface）。
// 在标准的访问者模式（Visitor Pattern）中，如果你想遍历一组复杂的节点（例如 Calcite 中的各种关系算子 RelNode 或 SQL 语法树节点 SqlNode），
// 你必须在 Visitor 接口中为每一个具体的子类硬编码一个 visit 方法（如 visit(LogicalProject), visit(LogicalFilter)），并且每个子类都需要实现一个 accept(Visitor) 方法。
// 这种方式虽然高效，但扩展性极差——每增加一个新节点类，就必须修改所有的 Visitor 接口及其实现。
// 为了打破这种死板的绑定，Calcite 引入了反射访问者模式。
// 通过借助于工具类 ReflectiveUtil.invokeVisitor(...)，Visitor 的实现类只需要直接定义符合特定命名规范的方法（例如 public void visit(LogicalProject node)），
// 而不需要在接口里强行声明它们。
// 在运行时，Calcite 会利用 Java 的反射机制（Reflection），根据当前遍历到的对象的具体运行时类型，动态地去 Visitor 中寻找并调用匹配的 visit 方法。
public interface ReflectiveVisitor {
}
