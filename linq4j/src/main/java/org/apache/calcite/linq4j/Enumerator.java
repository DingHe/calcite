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
package org.apache.calcite.linq4j;

import org.checkerframework.framework.qual.Covariant;

/**
 * Supports a simple iteration over a collection.
 *
 * <p>Analogous to LINQ's System.Collections.Enumerator. Unlike LINQ, if the
 * underlying collection has been modified it is only optional that an
 * implementation of the Enumerator interface detects it and throws a
 * {@link java.util.ConcurrentModificationException}.
 *
 * @param <T> Element type
 */
// 在 Java 标准库中，我们习惯使用 java.util.Iterator（迭代器）来遍历集合。然而，Calcite 在执行物理计划、拉取行数据（Row Data）时，并没有采用原生的 Iterator，而是引入了自定义的 Enumerator（枚举器）。
//为什么 Calcite 不直接用 Java 的 Iterator？
// 语义对齐 .NET LINQ：Calcite 的前身和部分底层设计（Linq4j）旨在将 .NET 优雅的 LINQ 表达式查询能力移植到 Java 中，Enumerator 的指针游标模型更贴合 LINQ 的流式转换设计。
// 游标模型差异（解耦“移动”与“获取”）：
// Java 的 Iterator.next() 是一个复合操作：它将指针向下移动一位，并且同时返回新位置的数据。
// Calcite 的 Enumerator 采用分离模型：用 moveNext() 负责单纯的指针移动，用 current() 负责单纯的数据获取。这种分离在处理复杂的流式计算、多层算子嵌套、甚至是条件分支和假冷启动时，逻辑控制会更加灵活和精准。

@Covariant(0)
public interface Enumerator<T> extends AutoCloseable {
  /**
   * Gets the current element in the collection.
   *
   * <p>After an enumerator is created or after the {@link #reset} method is
   * called, the {@link #moveNext} method must be called to advance the
   * enumerator to the first element of the collection before reading the
   * value of the {@code current} property; otherwise, {@code current} is
   * undefined.
   *
   * <p>This method also throws {@link java.util.NoSuchElementException} if
   * the last call to {@code moveNext} returned {@code false}, which indicates
   * the end of the collection.
   *
   * <p>This method does not move the position of the enumerator, and
   * consecutive calls to {@code current} return the same object until either
   * {@code moveNext} or {@code reset} is called.
   *
   * <p>An enumerator remains valid as long as the collection remains
   * unchanged. If changes are made to the collection, such as adding,
   * modifying, or deleting elements, the enumerator is irrecoverably
   * invalidated. The next call to {@code moveNext} or {@code reset} may,
   * at the discretion of the implementation, throw a
   * {@link java.util.ConcurrentModificationException}. If the collection is
   * modified between {@code moveNext} and {@code current}, {@code current}
   * returns the element that it is set to, even if the enumerator is already
   * invalidated.
   *
   * @return Current element
   *
   * @throws java.util.ConcurrentModificationException if collection
   *          has been modified
   *
   * @throws java.util.NoSuchElementException if {@code moveToNext}
   *          has not been called, has not been called since the most
   *          recent call to {@code reset}, or returned false
   */
  // 获取集合或数据流中当前光标指向的元素
  // 冷启动限制：当一个 Enumerator 刚刚被 new 出来，或者刚刚执行完 reset() 方法时，游标处于第一个元素之前的“真空期”。
  // 此时绝对不能直接调用 current()，否则其行为是未定义的，或将直接抛出 NoSuchElementException。必须先调用 moveNext() 成功后，才能读取 current()。
  // 幂等性（无副作用）：该方法不会改变游标的任何物理位置。这意味着，在不调用 moveNext() 或 reset() 的情况下，无论你连续调用多少次 current()，它都必须吐出完全相同的那个对象。
  T current();

  /**
   * Advances the enumerator to the next element of the collection.
   *
   * <p>After an enumerator is created or after the {@code reset} method is
   * called, an enumerator is positioned before the first element of the
   * collection, and the first call to the {@code moveNext} method moves the
   * enumerator over the first element of the collection.
   *
   * <p>If {@code moveNext} passes the end of the collection, the enumerator
   * is positioned after the last element in the collection and
   * {@code moveNext} returns {@code false}. When the enumerator is at this
   * position, subsequent calls to {@code moveNext} also return {@code false}
   * until {@code #reset} is called.
   *
   * <p>An enumerator remains valid as long as the collection remains
   * unchanged. If changes are made to the collection, such as adding,
   * modifying, or deleting elements, the enumerator is irrecoverably
   * invalidated. The next call to {@code moveNext} or {@link #reset} may,
   * at the discretion of the implementation, throw a
   * {@link java.util.ConcurrentModificationException}.
   *
   * @return {@code true} if the enumerator was successfully advanced to the
   *         next element; {@code false} if the enumerator has passed the end of
   *         the collection
   */
  // 驱动游标，向后移动一个位置指向下一个元素。
  // 返回 true：说明成功移动到了有效的数据行，此时可以安全地调用 current() 来提取此行数据。
  boolean moveNext();

  /**
   * Sets the enumerator to its initial position, which is before the first
   * element in the collection.
   *
   * <p>An enumerator remains valid as long as the collection remains
   * unchanged. If changes are made to the collection, such as adding,
   * modifying, or deleting elements, the enumerator is irrecoverably
   * invalidated. The next call to {@link #moveNext} or {@code reset} may,
   * at the discretion of the implementation, throw a
   * {@link java.util.ConcurrentModificationException}.
   *
   * <p>This method is optional; it may throw
   * {@link UnsupportedOperationException}.
   *
   * <p><b>Notes to Implementers</b>
   *
   * <p>All calls to Reset must result in the same state for the enumerator.
   * The preferred implementation is to move the enumerator to the beginning
   * of the collection, before the first element. This invalidates the
   * enumerator if the collection has been modified since the enumerator was
   * created, which is consistent with {@link #moveNext()} and
   * {@link #current()}.
   */
  // 作用：强行将当前游标重置回最初的初始位置（即第一个元素之前的“真空期”位置）。
  void reset();

  /**
   * Closes this enumerable and releases resources.
   *
   * <p>This method is idempotent. Calling it multiple times has the same effect
   * as calling it once.
   */
  // 作用：关闭枚举器并释放与之关联的任何物理或逻辑资源。
  @Override void close();
}
