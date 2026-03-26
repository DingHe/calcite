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
package org.apache.calcite.interpreter;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTrait;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Calling convention that returns results as an
 * {@link org.apache.calcite.linq4j.Enumerable} of object arrays.
 * <p>The relational expression needs to implement
 * {@link org.apache.calcite.runtime.ArrayBindable}.
 * Unlike {@link org.apache.calcite.adapter.enumerable.EnumerableConvention},
 * no code generation is required.
 */
// 在 Apache Calcite 的架构中，BindableConvention 是物理执行层中一种非常特殊的“调用约定”。它与常见的 EnumerableConvention（基于代码生成）并行，但实现逻辑完全不同。
// BindableConvention 代表了一种**基于解释执行（Interpreted Execution）**的物理约定。
// 其核心作用和特点如下：
// 无需代码生成： 与 EnumerableConvention 不同，它不需要在运行时生成并编译 Java 代码。它通过 Calcite 内部的解释器（Interpreter）直接运行算子逻辑。
// 快速启动： 由于省去了编译环节，对于简单的查询或小规模数据，它的启动速度极快，适用于交互式查询。
// 数据格式： 它产出的结果是 BindableRel 类型，最终表现为一组 Object[] 数组。
// 轻量化： 它主要用于 Calcite 自带的快速解释器（org.apache.calcite.interpreter），是测试和快速验证逻辑的理想选择。
// 由于 BindableConvention 被实现为一个单例枚举（enum），它天然地保证了特征（Trait）的唯一性。
public enum BindableConvention implements Convention {
  // BindableConvention 的唯一单例
  // 在优化器中，所有引用“解释执行约定”的地方都直接使用这个实例，便于通过 == 进行快速比较。
  INSTANCE;

  /** Cost of a bindable node versus implementing an equivalent node in a
   * "typical" calling convention. */
  // 代价系数乘数。
  // Calcite 默认认为解释执行（Bindable）的效率比代码生成执行（Enumerable）要低。因此，在计算代价（Cost）时，通常会将基础代价乘以 2.0。这意味着如果存在其他更高效的物理路径，优化器会优先选择其他路径。
  public static final double COST_MULTIPLIER = 2.0d;
  // 返回该约定的字符串表示。
  @Override public String toString() {
    return getName();
  }
  // 获取此约定关联的基础关系表达式接口。
  // 返回 BindableRel.class。这意味着所有标记为 BindableConvention 的算子都必须实现 BindableRel 接口，该接口定义了如何绑定参数并产生数据流。
  @Override public Class getInterface() {
    return BindableRel.class;
  }
  // 返回约定的唯一标识名称。
  @Override public String getName() {
    return "BINDABLE";
  }
  // 强制转换物理属性。
  @Override public @Nullable RelNode enforce(RelNode input, RelTraitSet required) {
    return null;
  }

  @Override public RelTraitDef getTraitDef() {
    return ConventionTraitDef.INSTANCE;
  }
  // 判断是否满足给定的特征。
  // 实现为 this == trait。因为 Bindable 是物理层的明确约定，它不具备“向上兼容”的偏序属性，必须完全匹配才算满足。
  @Override public boolean satisfies(RelTrait trait) {
    return this == trait;
  }
  // 向优化器注册该约定。
  @Override public void register(RelOptPlanner planner) {}
  // 是否支持直接转换为目标约定。
  // 返回 false。它不主动声明到其他约定的转换路径，转换通常由 ConverterRule 独立处理。
  @Override public boolean canConvertConvention(Convention toConvention) {
    return false;
  }
  // 返回 false。这意味着优化器在处理 Bindable 转换时，会寻求更具体、更明确的转换规则，而不是依赖通用的抽象层
  @Override public boolean useAbstractConvertersForConversion(RelTraitSet fromTraits,
      RelTraitSet toTraits) {
    return false;
  }
}
