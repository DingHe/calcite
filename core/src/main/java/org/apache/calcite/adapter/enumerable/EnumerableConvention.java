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
package org.apache.calcite.adapter.enumerable;

import org.apache.calcite.plan.Contexts;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTrait;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.RelFactories;

import org.checkerframework.checker.nullness.qual.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * Family of calling conventions that return results as an
 * {@link org.apache.calcite.linq4j.Enumerable}.
 */
// 在 Apache Calcite 的世界里，EnumerableConvention 是最为重要且最常用的物理调用约定（Convention）。
// 如果说 BindableConvention 是“解释执行”，那么 EnumerableConvention 就是“编译执行”。
// EnumerableConvention 代表了 Calcite 官方推荐的内存级 Java 执行引擎。
// 其核心作用如下：
// 代码生成（Code Generation）： 它是 Calcite linq4j 框架的核心。标记为 ENUMERABLE 的算子在执行前，会被动态地转化为 Java 源代码（Java AST），然后通过 Janino 编译器编译成字节码。
// 物理落地： 当一个逻辑 SQL 计划（Logical Plan）找不到特定的后端（如 MySQL 或 Spark）执行时，Calcite 通常会将其转换为 Enumerable 计划，在本地 JVM 内存中完成计算。
// 标准化输出： 它产出的结果实现了 Enumerable<Object[]> 接口，这是一种类似于 Java Iterable 但功能更强大的延迟加载集合接口。
// 该类采用 单例枚举（enum） 模式实现，确保全局唯一性。
public enum EnumerableConvention implements Convention {
  // EnumerableConvention 的唯一单例。
  INSTANCE;

  /** Cost of an enumerable node versus implementing an equivalent node in a
   * "typical" calling convention. */
  // 代价乘数。
  public static final double COST_MULTIPLIER = 1.0d;

  @Override public String toString() {
    return getName();
  }
  // 定义对应的算子接口。
  // 返回 EnumerableRel.class。所有属于此约定的物理算子（如 EnumerableFilter, EnumerableJoin）都必须实现该接口。
  @Override public Class getInterface() {
    return EnumerableRel.class;
  }
  // 获取约定的文本标识。
  @Override public String getName() {
    return "ENUMERABLE";
  }
  // 物理属性强制转换（核心方法）。
  @Override public @Nullable RelNode enforce(
      final RelNode input,
      final RelTraitSet required) {
    RelNode rel = input;
    // 转换约定： 如果输入的算子（input）还不属于 ENUMERABLE 约定，它会调用 convert 方法强制将其转换为 ENUMERABLE 节点。
    if (input.getConvention() != INSTANCE) {
      rel =
          ConventionTraitDef.INSTANCE.convert(input.getCluster().getPlanner(),
              input, INSTANCE, true);
      requireNonNull(rel,
          () -> "Unable to convert input to " + INSTANCE + ", input = " + input);
    }
    RelCollation collation = required.getCollation();
    // 强制排序： 它会检查 required（要求的特征集）中是否包含排序（Collation）要求。
    // 如果有且当前 input 不满足，它会自动在计划中插入一个 EnumerableSort 算子。
    if (collation != null && collation != RelCollations.EMPTY) {
      rel = EnumerableSort.create(rel, collation, null, null);
    }
    return rel;
  }

  @Override public RelTraitDef getTraitDef() {
    return ConventionTraitDef.INSTANCE;
  }
  // 仅当 this == trait 时返回 true。物理执行环境必须精确匹配，不支持模糊替代。
  @Override public boolean satisfies(RelTrait trait) {
    return this == trait;
  }

  @Override public void register(RelOptPlanner planner) {}

  @Override public boolean canConvertConvention(Convention toConvention) {
    return false;
  }

  @Override public boolean useAbstractConvertersForConversion(RelTraitSet fromTraits,
      RelTraitSet toTraits) {
    return true;
  }
  // 提供算子工厂集合。
  // 返回一组预定义的工厂（如 ENUMERABLE_PROJECT_FACTORY 等）。当优化器在自动转换或重写计划时需要创建新的 Enumerable 节点时，会调用这里提供的工厂方法。
  @Override public RelFactories.Struct getRelFactories() {
    return RelFactories.Struct.fromContext(
            Contexts.of(
                EnumerableRelFactories.ENUMERABLE_TABLE_SCAN_FACTORY,
                EnumerableRelFactories.ENUMERABLE_PROJECT_FACTORY,
                EnumerableRelFactories.ENUMERABLE_FILTER_FACTORY,
                EnumerableRelFactories.ENUMERABLE_SORT_FACTORY));
  }
}
