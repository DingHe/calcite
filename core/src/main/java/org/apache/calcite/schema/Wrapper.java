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
package org.apache.calcite.schema;

import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Mix-in interface that allows you to find sub-objects.
 */
// 在 Apache Calcite 的架构体系中，Wrapper 接口是一个极其关键的扩展机制（Extension Mechanism）。
// 它模仿了 JDBC 4.0 中的 java.sql.Wrapper 模式，旨在解决复杂对象层级中的“能力发现”问题。
// Wrapper 被称为 Mix-in（混入）接口，其核心作用是允许对象在运行时动态地暴露其隐藏的能力或子对象。
// 解耦类型检查：在 Calcite 中，一个 Table 对象可能被多层装饰器（Decorator）包裹。
// 如果你想知道这个表是否支持“可变性”（即是否实现了 ModifiableTable），直接使用 instanceof 可能会因为包装类的存在而失败。
// 动态扩展：它允许开发者在不改变原有类继承体系的情况下，通过 unwrap 方法“剥开”外壳，获取内部特定的接口实现。
// 统一的接口探测：它为 Calcite 的各个组件（Schema, Table, Function 等）提供了一种标准化的方式来询问：“你是否支持 X 功能？如果支持，请把那个接口实例给我。”
public interface Wrapper {
  /** Finds an instance of an interface implemented by this object,
   * or returns null if this object does not support that interface. */
  // 参数类型：Class<C> aClass —— 你希望获取的接口或类的类型。
  // 返回值：如果当前对象本身实现了该接口，或者它包裹的对象实现了该接口，则返回该实例；否则返回 null。
  // 这是 Wrapper 模式的灵魂。它类似于一种“安全的强制类型转换”。
  <C extends Object> @Nullable C unwrap(Class<C> aClass);

  /** Finds an instance of an interface implemented by this object,
   * or throws NullPointerException if this object does not support
   * that interface. */
  // 返回值：非空的接口实例。
  @API(since = "1.27", status = API.Status.INTERNAL)
  default <C extends Object> C unwrapOrThrow(Class<C> aClass) {
    return requireNonNull(unwrap(aClass),
        () -> "Can't unwrap " + aClass + " from " + this);
  }

  /** Finds an instance of an interface implemented by this object,
   * or returns {@link Optional#empty()} if this object does not support
   * that interface. */
  // 这是 unwrap 的函数式编程友好版本。
  // 适用场景：适合与 Java Stream 或 Lambda 表达式配合使用。
  @API(since = "1.27", status = API.Status.INTERNAL)
  default <C extends Object> Optional<C> maybeUnwrap(Class<C> aClass) {
    return Optional.ofNullable(unwrap(aClass));
  }
}
