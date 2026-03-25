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
 * Represents a collection of objects that have a common key.
 *
 * @param <K> Key type
 * @param <V> Element type
 */
// Grouping<K, V> 接口是实现 SQL GROUP BY 逻辑 或 LINQ groupBy 算子 的核心数据结构。它代表了一个“具有相同键的值集合”。
// Grouping<K, V> 的本质是一个带标签的序列。
// 分组结果的单元：在执行分组操作（GroupBy）时，原始数据集会被拆分成多个小组。每一个小组就是一个 Grouping 实例。
// 键值映射：它持有该分组的共同特征（键 K），同时它本身又是一个可以迭代的容器（值集合 V）。
// 作为 Key 携带者：通过 getKey() 告知外界这组数据属于谁（比如“部门 ID 为 10 的所有员工”）。
// 作为 Enumerable 容器：因为它继承了 Enumerable<V>，所以你可以直接对某个分组进行二次过滤、求和（Sum）、平均值（Average）等聚合运算。
@Covariant(0)
public interface Grouping<K, V> extends Enumerable<V> {
  /**
   * Gets the key of this Grouping.
   */
  K getKey();
}
