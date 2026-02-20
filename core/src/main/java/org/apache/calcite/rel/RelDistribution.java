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
package org.apache.calcite.rel;

import org.apache.calcite.plan.RelMultipleTrait;
import org.apache.calcite.util.mapping.Mappings;

import java.util.List;

/**
 * Description of the physical distribution of a relational expression.
 *
 * <p>TBD:
 * <ul>
 *   <li>Can we shorten {@link Type#HASH_DISTRIBUTED} to HASH, etc.</li>
 *   <li>Do we need {@link RelDistributions}.DEFAULT?</li>
 *   <li>{@link RelDistributionTraitDef#convert}
 *       does not create specific physical operators as it does in Drill. Drill
 *       will need to create rules; or we could allow "converters" to be
 *       registered with the planner that are not trait-defs.
 * </ul>
 */
public interface RelDistribution extends RelMultipleTrait {
  /** Returns the type of distribution. */
  Type getType(); //获取当前分布的类型。RelDistribution 使用一个枚举类型 Type 来定义不同的分布策略

  /**
   * Returns the ordinals of the key columns.
   *
   * <p>Order is important for some types (RANGE); other types (HASH) consider
   * it unimportant but impose an arbitrary order; other types (BROADCAST,
   * SINGLETON) never have keys.
   */
  List<Integer> getKeys(); //获取用于数据分布的键列的序号。某些类型的分布（例如 RANGE 和 HASH）需要键来决定数据如何分配，而其他类型（如 BROADCAST 或 SINGLETON）则没有特定的键

  /**
   * Applies mapping to this distribution trait.
   *
   * <p>Mapping can change the distribution trait only if it depends on distribution keys.
   *
   * <p>For example if relation is HASH distributed by keys [0, 1], after applying
   * a mapping (3, 2, 1, 0), the relation will have a distribution HASH(2,3) because
   * distribution keys changed their ordinals.
   *
   * <p>If mapping eliminates one of the distribution keys, the {@link Type#ANY}
   * distribution will be returned.
   *
   * <p>If distribution doesn't have keys (BROADCAST or SINGLETON), method will return
   * the same distribution.
   *
   * @param mapping   Mapping
   * @return distribution with mapping applied
   */
  @Override RelDistribution apply(Mappings.TargetMapping mapping); //将给定的映射应用到当前分布特性上。映射会改变分布特性，特别是当分布依赖于某些键时。

  /** Type of distribution. */
  enum Type {
    //只有一个实例的流，所有记录都被一个实例处理。这个分布适用于数据量较小的情况，所有数据都在一个计算单元中处理
    /** There is only one instance of the stream. It sees all records. */
    SINGLETON("single"),
    //数据被哈希到多个实例中，每个实例处理那些哈希值相同的记录。记录按照指定的键进行哈希分配，每个记录只出现在一个实例中
    /** There are multiple instances of the stream, and each instance contains
     * records whose keys hash to a particular hash value. Instances are
     * disjoint; a given record appears on exactly one stream. */
    HASH_DISTRIBUTED("hash"),
    //数据根据范围分配到不同的实例中，每个实例处理一段范围内的记录。每个记录出现在一个实例中。
    /** There are multiple instances of the stream, and each instance contains
     * records whose keys fall into a particular range. Instances are disjoint;
     * a given record appears on exactly one stream. */
    RANGE_DISTRIBUTED("range"),
    //数据被随机分配到多个实例中，记录分配到实例时不考虑键值，所有实例的记录随机分配。
    /** There are multiple instances of the stream, and each instance contains
     * randomly chosen records. Instances are disjoint; a given record appears
     * on exactly one stream. */
    RANDOM_DISTRIBUTED("random"),
    //数据按照轮询方式分配到不同的实例中，每个实例依次接受一个记录。每个记录只出现在一个实例中。
    /** There are multiple instances of the stream, and records are assigned
     * to instances in turn. Instances are disjoint; a given record appears
     * on exactly one stream. */
    ROUND_ROBIN_DISTRIBUTED("rr"),
    //所有记录都会在每个实例中都有一份拷贝，每个实例都看到所有记录。适用于小数据量或者需要全局数据的情况。
    /** There are multiple instances of the stream, and all records appear in
     * each instance. */
    BROADCAST_DISTRIBUTED("broadcast"),
    //不是一种有效的分布类型，表示消费者能够接受任意分布方式。
    /** Not a valid distribution, but indicates that a consumer will accept any
     * distribution. */
    ANY("any");

    public final String shortName;

    Type(String shortName) {
      this.shortName = shortName;
    }
  }
}
