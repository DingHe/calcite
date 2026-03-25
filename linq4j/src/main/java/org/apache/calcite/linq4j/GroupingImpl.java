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

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Implementation of {@link Grouping}.
 *
 * @param <K> Key type
 * @param <V> Value type
 */
// GroupingImpl<K, V> 是接口 Grouping<K, V> 的标准参考实现。它不仅是一个数据容器，还巧妙地通过多重继承，让分组结果能够同时作为“可迭代序列”和“键值对映射单元”使用。
// GroupingImpl 的核心作用是封装分组计算的产物。
// 物理存储层：它是分组操作后的具体物理表现。当 groupBy 算子完成扫描后，它会将具有相同 Key 的所有元素存入 GroupingImpl 内部的 List 中。
// 多身份适配：
// 作为 Grouping：提供了获取分组键（Key）的能力。
// 作为 Enumerable：通过继承 AbstractEnumerable，它允许用户直接对这一个小组的数据进行二次 LINQ 查询。
// 作为 Map.Entry：它实现了标准 Java 的 Map.Entry 接口，这意味着它可以直接被放入 Map 中，或者作为 Map 迭代的一部分，极大方便了与 Java 集合框架的交互。
@SuppressWarnings("type.argument.type.incompatible")
class GroupingImpl<K extends Object, V> extends AbstractEnumerable<V>
    implements Grouping<K, V>, Map.Entry<K, Enumerable<V>> {
  // 存储该分组的唯一键。
  private final K key;
  // 在内存中存储该分组对应的所有元素列表。
  private final List<V> values;

  GroupingImpl(K key, List<V> values) {
    this.key = Objects.requireNonNull(key, "key");
    this.values = Objects.requireNonNull(values, "values");
  }

  @Override public String toString() {
    return key + ": " + values;
  }

  /** {@inheritDoc}
   *
   * <p>Computes hash code consistent with
   * {@link java.util.Map.Entry#hashCode()}. */
  @Override public int hashCode() {
    return key.hashCode() ^ values.hashCode();
  }

  @Override public boolean equals(@Nullable Object obj) {
    return obj instanceof GroupingImpl
           && key.equals(((GroupingImpl) obj).key)
           && values.equals(((GroupingImpl) obj).values);
  }

  // implement Map.Entry
  @Override public Enumerable<V> getValue() {
    return Linq4j.asEnumerable(values);
  }

  // implement Map.Entry
  @Override public Enumerable<V> setValue(Enumerable<V> value) {
    // immutable
    throw new UnsupportedOperationException();
  }

  // implement Map.Entry
  // implement Grouping
  @Override public K getKey() {
    return key;
  }

  @Override public Enumerator<V> enumerator() {
    return Linq4j.enumerator(values);
  }
}
