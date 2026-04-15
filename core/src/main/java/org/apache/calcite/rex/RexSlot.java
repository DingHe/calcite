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
package org.apache.calcite.rex;

import org.apache.calcite.rel.type.RelDataType;

import java.util.AbstractList;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Abstract base class for {@link RexInputRef}, {@link RexLocalRef} and {@link RexLambdaRef}.
 */
// RexSlot 是一个承上启下的抽象基类。它继承自 RexVariable，并为那些通过索引（Index）而非仅仅通过名称来定位的变量提供了基础实现。
// RexSlot 的核心作用是定义了基于位置引用的表达式。
// 在关系代数中，很多时候我们引用一个字段不是通过它的名字（如 "ENAME"），而是通过它在输入行中的偏移量/索引（例如：第 0 个字段）。RexSlot 统一了这种“槽位（Slot）”引用的行为。
//
public abstract class RexSlot extends RexVariable {
  //~ Instance fields --------------------------------------------------------
  // 存储字段在底层数据结构中的索引位置（下标）
  // 这是该类的核心数据。它是一个从 0 开始的整数，代表了该变量对应于输入行或环境中的第几个位置。例如，在一个包含 (id, name) 的行中，id 的索引为 0，name 的索引为 1。
  protected final int index;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a slot.
   *
   * @param index Index of the field in the underlying rowtype
   * @param type  Type of the column
   */
  protected RexSlot(
      String name,
      int index,
      RelDataType type) {
    super(name, type);
    assert index >= 0;
    this.index = index;
  }

  //~ Methods ----------------------------------------------------------------

  public int getIndex() {
    return index;
  }

  /**
   * Thread-safe list that populates itself if you make a reference beyond
   * the end of the list. Useful if you are using the same entries repeatedly.
   * Once populated, accesses are very efficient.
   */
  // 用于高效地管理和生成大量的变量名称（如 $0, $1, $2...）
  // 在 Calcite 运行过程中，可能会产生数以千计的 RexInputRef。为了节省内存并提高效率，Calcite 不会为每个引用都重复创建字符串，而是通过这个列表动态生成并缓存变量名。
  protected static class SelfPopulatingList
      extends CopyOnWriteArrayList<String> {
    // 名称前缀（例如 $）。
    private final String prefix;

    SelfPopulatingList(final String prefix, final int initialSize) {
      super(fromTo(prefix, 0, initialSize));
      this.prefix = prefix;
    }
    // 创建一个虚拟的只读列表。
    // 它并不真正存储字符串，而是重写了 get(int index) 方法，在访问时才拼接 prefix + index。这种延迟计算的方式在批量填充 CopyOnWriteArrayList 时非常高效。
    private static AbstractList<String> fromTo(
        final String prefix,
        final int start,
        final int end) {
      return new AbstractList<String>() {
        @Override public String get(int index) {
          return prefix + (index + start);
        }

        @Override public int size() {
          return end - start;
        }
      };
    }
    // 线程安全地获取指定索引处的字符串。
    @Override public String get(int index) {
      for (;;) {
        try {
          return super.get(index);
        } catch (IndexOutOfBoundsException e) {
          if (index < 0) {
            throw new IllegalArgumentException();
          }
          // Double-checked locking, but safe because CopyOnWriteArrayList.array
          // is marked volatile, and size() uses array.length.
          synchronized (this) {
            final int size = size();
            if (index >= size) {
              addAll(fromTo(prefix, size, Math.max(index + 1, size * 2)));
            }
          }
        }
      }
    }
  }
}
