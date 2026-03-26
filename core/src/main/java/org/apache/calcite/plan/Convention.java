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
package org.apache.calcite.plan;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.RelFactories;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Calling convention trait.
 */
// 在 Apache Calcite 中，Convention（调用约定）是一个极其关键的接口，它继承自 RelTrait。
// 如果说 RelTrait 是通用的属性描述，那么 Convention 就是最核心的物理属性。
// Convention 代表了一个关系表达式（RelNode）所在的运行环境或执行引擎。
// 在优化器将逻辑计划（Logical Plan）转换为物理计划（Physical Plan）的过程中，Convention 起到了“分水岭”的作用：
// 标识执行系统： 它标识了算子是在 JVM 中执行（EnumerableConvention）、在 JDBC 数据库中执行（JdbcConvention）、还是在某个特定的分布式系统（如 Spark、Flink）中执行。
// 规则匹配的基石： 优化器根据 Convention 来决定哪些转换规则（Rule）可以应用。通常只有属于相同 Convention 的算子才能直接连接。
// 转换驱动： 当两个不同 Convention 的算子需要交互时（例如：从 JDBC 读取数据并在内存中聚合），Convention 定义了如何通过“转换器（Converter）”跨越边界。
public interface Convention extends RelTrait {
  /**
   * Convention that for a relational expression that does not support any
   * convention. It is not implementable, and has to be transformed to
   * something else in order to be implemented.
   *
   * <p>Relational expressions generally start off in this form.
   *
   * <p>Such expressions always have infinite cost.
   */
  // 表示“无约定”或“纯逻辑”状态。
  // 大多数关系表达式在刚创建时（逻辑计划阶段）都标记为 NONE。
  // 这种状态的算子是不可直接执行的，必须经过优化器转换成某种具体的物理约定（如 ENUMERABLE）后才能运行。它的代价（Cost）通常被视为无穷大。
  Convention NONE = new Impl("NONE", RelNode.class);
  // 获取该约定对应的关系表达式接口类。
  // 每种约定通常对应一组特定的子类。例如，JdbcConvention 对应的可能是 JdbcRel 接口。这有助于在编译或转换时进行类型检查。
  Class getInterface();
  // 返回该约定的名称（如 "JDBC", "ENUMERABLE", "SPARK"）。
  String getName();

  /**
   * Given an input and required traits, returns the corresponding
   * enforcer rel nodes, like physical Sort, Exchange etc.
   * @param input The input RelNode
   * @param required The required traits
   * @return Physical enforcer that satisfies the required traitSet,
   * or {@code null} if trait enforcement is not allowed or the
   * required traitSet can't be satisfied.
   */
  // 强制执行物理属性约束。
  // 如果给定的 input 算子不满足 required 中的物理要求（比如缺少排序），该方法会尝试在该约定内返回一个“执行器（Enforcer）”算子（如物理排序 Sort 或数据交换 Exchange）。
  // 如果该约定不支持这种强制转换，则返回 null。
  default @Nullable RelNode enforce(RelNode input, RelTraitSet required) {
    throw new RuntimeException(getClass().getName()
        + "#enforce() is not implemented.");
  }

  /**
   * Returns whether we should convert from this convention to
   * {@code toConvention}. Used by {@link ConventionTraitDef}.
   * @param toConvention Desired convention to convert to
   * @return Whether we should convert from this convention to toConvention
   */
  // 判断当前约定是否支持直接转换为另一种约定。
  // 例如，一个特殊的插件可能支持将自己内部的算子直接推送到 JDBC 约定中。如果返回 true，优化器会考虑这种路径。
  default boolean canConvertConvention(Convention toConvention) {
    return false;
  }

  /**
   * Returns whether we should convert from this trait set to the other trait
   * set.
   * 是否应该从fromTraits转换到toTraits
   * <p>The convention decides whether it wants to handle other trait
   * conversions, e.g. collation, distribution, etc.  For a given convention, we
   * will only add abstract converters to handle the trait (convention,
   * collation, distribution, etc.) conversions if this function returns true.
   *
   * @param fromTraits Traits of the RelNode that we are converting from
   * @param toTraits Target traits
   * @return Whether we should add converters
   */
  // 是否允许使用抽象转换器（AbstractConverter）来处理转换。
  // 如果返回 true，优化器会在 fromTraits 和 toTraits 之间插入一个占位符 AbstractConverter，之后再由具体的 Rule 负责将其细化为具体的算子。
  default boolean useAbstractConvertersForConversion(RelTraitSet fromTraits,
      RelTraitSet toTraits) {
    return false;
  }

  /** Return RelFactories struct for this convention. It can be used to
   * build RelNode. */
  // 获取该约定推荐使用的算子工厂。
  default RelFactories.Struct getRelFactories() {
    return RelFactories.DEFAULT_STRUCT;
  }

  /**
   * Default implementation.
   */
  // 默认实现
  // Impl 是 Convention 接口的一个基本实现，开发者可以通过继承或直接实例化它来定义新的约定。
  class Impl implements Convention {
    private final String name;
    private final Class<? extends RelNode> relClass;

    public Impl(String name, Class<? extends RelNode> relClass) {
      this.name = name;
      this.relClass = relClass;
    }

    @Override public String toString() {
      return getName();
    }

    @Override public void register(RelOptPlanner planner) {}

    @Override public boolean satisfies(RelTrait trait) {
      return this == trait;
    }

    @Override public Class getInterface() {
      return relClass;
    }

    @Override public String getName() {
      return name;
    }

    @Override public RelTraitDef getTraitDef() {
      return ConventionTraitDef.INSTANCE;
    }

    @Override public @Nullable RelNode enforce(final RelNode input,
        final RelTraitSet required) {
      return null;
    }

    @Override public boolean canConvertConvention(Convention toConvention) {
      return false;
    }

    @Override public boolean useAbstractConvertersForConversion(RelTraitSet fromTraits,
        RelTraitSet toTraits) {
      return false;
    }
  }
}
