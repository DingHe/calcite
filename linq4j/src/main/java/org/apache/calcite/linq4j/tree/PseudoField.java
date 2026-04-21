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

/**
 * Contains the parts of the {@link java.lang.reflect.Field} class needed
 * for code generation, but might be implemented differently.
 */
// PseudoField 的字面意思是 “伪字段” 或 “虚拟字段”。
// 在 Java 代码生成（Code Generation）的过程中，我们经常需要处理类成员变量（Field）。通常情况下，我们会使用 Java 反射包中的 java.lang.reflect.Field。但是，直接使用原生 Field 类有两个局限性：
// 物理依赖性：原生 Field 要求该字段必须已经存在于某个已加载的 JVM 类中。
// 实现灵活性：在生成动态代码时，某个“字段”可能在物理上还不存在，或者它是一个通过特殊手段（如 Map 映射、动态代理或生成的字节码）访问的虚拟属性。
// PseudoField 的主要作用是：
// 作为 java.lang.reflect.Field 的一个抽象代理或适配器。
// 它提取了字段在代码生成时最关键的元数据（名称、类型、修饰符），使得 Calcite 的表达式树（Expression Tree）可以统一处理“真实的反射字段”和“自定义生成的虚拟字段”，而无需强依赖于物理存在的 Field 对象。
public interface PseudoField {
  // 获取该字段的名称。
  // 在生成 Java 源代码或进行表达式解析时，需要知道字段的标识符。例如，如果代码是 obj.myField，该方法将返回字符串 "myField"。
  String getName();
  // 获取该字段的数据类型。
  // 返回一个 java.lang.reflect.Type 对象。
  // 这在类型检查和变量声明时至关重要。它能告诉编译器或生成器该字段是基本类型（如 int）、类类型（如 String）还是泛型类型。
  Type getType();
  // 获取该字段的 Java 语言修饰符。
  // 返回一个整数，其含义与 java.lang.reflect.Modifier 类中的常量一致。它代表了字段的访问权限和特性（如 public, private, static, final 等）。
  int getModifiers();
  // 获取指定对象实例中该字段的值。
  // 参数：o —— 要从中提取字段值的对象实例。如果字段是 static 的，该参数可以为 null。
  @Nullable Object get(@Nullable Object o) throws IllegalAccessException;
  // 获取声明该字段的类或接口。
  Type getDeclaringClass();
}
