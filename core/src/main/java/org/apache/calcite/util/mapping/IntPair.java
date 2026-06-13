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
package org.apache.calcite.util.mapping;

import org.apache.calcite.runtime.Utilities;
import org.apache.calcite.util.Util;

import com.google.common.base.Function;
import com.google.common.collect.Ordering;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.AbstractList;
import java.util.Comparator;
import java.util.List;

/**
 * An immutable pair of integers.
 *
 * @see Mapping#iterator()
 */
// IntPair 的主要作用是存储和操作一对不可变的整数（int）映射关系。
// 在关系型数据库查询优化中，经常需要对字段（Field/Column）的索引进行重映射、对齐和转换。例如：
// 连接条件对齐：在处理 Join 条件时（如 t1.col2 = t2.col5），需要将左表的字段索引 2 和右表的字段索引 5 组合绑定起来。
// 投影重映射（Mapping）：当算子发生下推（Push Down）或合并（Merge）时，底层的物理字段索引和上层的逻辑字段索引需要一对一地建立映射字典，IntPair 就是构成该字典的最基本单元。
// 由于两个数值被分别命名为 source（源）和 target（目标），它天然适合表达带有方向性的数据映射流。
public class IntPair {
  /** Function that swaps source and target fields of an {@link IntPair}. */
  @Deprecated
  // 字段对调函数。接收一个 IntPair 实例，将其 source 和 target 的值互换，并返回一个新的 IntPair 实例。
  // 由于现代 Java 开发中更倾向于使用 Lambda 表达式（如 x -> IntPair.of(x.target, x.source)），此常量带有 @Deprecated 标记。
  public static final Function<IntPair, IntPair> SWAP =
      new Function<IntPair, IntPair>() {
        @Override public IntPair apply(IntPair pair) {
          return of(pair.target, pair.source);
        }
      };

  /** Ordering that compares pairs lexicographically: first by their source,
   * then by their target. */
  // 字典序排序器。
  // 基于 Guava 的 Ordering 实现，内部重写了 compare 逻辑：首先对比两者的 source 属性（升序）；如果 source 相同，则进一步对比它们的 target 属性（升序）。
  public static final Ordering<IntPair> ORDERING =
      Ordering.from(
          new Comparator<IntPair>() {
            @Override public int compare(IntPair o1, IntPair o2) {
              int c = Integer.compare(o1.source, o2.source);
              if (c == 0) {
                c = Integer.compare(o1.target, o2.target);
              }
              return c;
            }
          });

  /** Function that returns the left (source) side of a pair. */
  @Deprecated
  // 左侧字段提取函数。接收一个 IntPair 实例并返回其 source 的包装整型（Integer）。目前已被 Lambda 表达式取代。
  public static final Function<IntPair, Integer> LEFT =
      new Function<IntPair, Integer>() {
        @Override public Integer apply(IntPair pair) {
          return pair.source;
        }
      };

  /** Function that returns the right (target) side of a pair. */
  @Deprecated
  // 右侧字段提取函数。接收一个 IntPair 实例并返回其 target 的包装整型（Integer）。目前已被 Lambda 表达式取代。
  public static final Function<IntPair, Integer> RIGHT =
      new Function<IntPair, Integer>() {
        @Override public Integer apply(IntPair pair) {
          return pair.target;
        }
      };

  //~ Instance fields --------------------------------------------------------
  // 映射的源端（左侧）整数值，常用于代表原始列的索引。由于带有 final 关键字，一旦初始化即不可修改。
  public final int source;
  // 映射的目的端（右侧）整数值，常用于代表重定向或转换后的目标列索引。同样为 final 属性。
  public final int target;

  //~ Constructors -----------------------------------------------------------

  public IntPair(int source, int target) {
    this.source = source;
    this.target = target;
  }

  //~ Methods ----------------------------------------------------------------

  public static IntPair of(int left, int right) {
    return new IntPair(left, right);
  }

  @Override public String toString() {
    return source + "-" + target;
  }

  @Override public boolean equals(@Nullable Object obj) {
    if (obj instanceof IntPair) {
      IntPair that = (IntPair) obj;
      return (this.source == that.source) && (this.target == that.target);
    }
    return false;
  }

  @Override public int hashCode() {
    return Utilities.hash(source, target);
  }

  /**
   * Converts two lists into a list of {@link IntPair}s,
   * whose length is the lesser of the lengths of the
   * source lists.
   *
   * @param lefts Left list
   * @param rights Right list
   * @return List of pairs
   */
  public static List<IntPair> zip(List<? extends Number> lefts,
      List<? extends Number> rights) {
    return zip(lefts, rights, false);
  }

  /**
   * Converts two lists into a list of {@link IntPair}s.
   *
   * <p>The length of the combined list is the lesser of the lengths of the
   * source lists. But typically the source lists will be the same length.
   *
   * @param lefts Left list
   * @param rights Right list
   * @param strict Whether to fail if lists have different size
   * @return List of pairs
   */
  // 将两个代表数字的 List 压缩绑定为一个由 IntPair 组成的 List。
  public static List<IntPair> zip(
      final List<? extends Number> lefts,
      final List<? extends Number> rights,
      boolean strict) {
    final int size;
    if (strict) {
      if (lefts.size() != rights.size()) {
        throw new AssertionError();
      }
      size = lefts.size();
    } else {
      size = Math.min(lefts.size(), rights.size());
    }
    return new AbstractList<IntPair>() {
      @Override public IntPair get(int index) {
        return IntPair.of(lefts.get(index).intValue(),
            rights.get(index).intValue());
      }

      @Override public int size() {
        return size;
      }
    };
  }

  /** Returns the left side of a list of pairs. */
  // 从一个 IntPair 集合中，批量提取出所有 Pair 的左侧（source）数值列表。
  public static List<Integer> left(final List<IntPair> pairs) {
    return Util.transform(pairs, x -> x.source);
  }

  /** Returns the right side of a list of pairs. */
  // 从一个 IntPair 集合中，批量提取出所有 Pair 的右侧（target）数值列表。
  public static List<Integer> right(final List<IntPair> pairs) {
    return Util.transform(pairs, x -> x.target);
  }
}
