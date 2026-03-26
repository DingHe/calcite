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

import org.apache.calcite.rel.RelDistributions;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.util.mapping.Mappings;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * RelTrait represents the manifestation of a relational expression trait within
 * a trait definition. For example, a {@code CallingConvention.JAVA} is a trait
 * of the {@link ConventionTraitDef} trait definition.
 *
 * <h2><a id="EqualsHashCodeNote">Note about equals() and hashCode()</a></h2>
 *
 * <p>If all instances of RelTrait for a particular RelTraitDef are defined in
 * an {@code enum} and no new RelTraits can be introduced at runtime, you need
 * not override {@link #hashCode()} and {@link #equals(Object)}. If, however,
 * new RelTrait instances are generated at runtime (e.g. based on state external
 * to the planner), you must implement {@link #hashCode()} and
 * {@link #equals(Object)} for proper {@link RelTraitDef#canonize canonization}
 * of your RelTrait objects.
 */
// 在 Calcite 的执行计划优化过程中，RelTrait（关系表达式特征） 用于描述一个关系表达式（RelNode）所具有的物理属性。
// 如果把 RelNode 比作一个待加工的零件，那么 RelTrait 就是这个零件的“规格说明书”。它告诉优化器这个算子产出的数据是：
// 什么格式的？（例如：属于哪个查询引擎，由 Convention 定义）
// 是否有序？（例如：按哪些列排序，由 RelCollation 定义）
// 如何分布的？（例如：在分布式系统中是 Hash 分布还是单机，由 RelDistribution 定义）
// 其核心意义在于： 优化器（如 VolcanoPlanner）通过比对算子要求的特征（Required Traits）和算子实际拥有的特征（Provided Traits），来决定是否需要插入额外的转换算子（如 Sort 算子或 Exchange 算子）。
public interface RelTrait {
  //~ Methods ----------------------------------------------------------------

  /**
   * Returns the RelTraitDef that defines this RelTrait.
   * @return the RelTraitDef that defines this RelTrait
   */
  // 获取定义该特征的“定义器”。
  // 每一个 RelTrait 必须属于某一个具体的 RelTraitDef（特征类别）。例如，一个表示“按 ID 排序”的 RelCollation 实例，其 getTraitDef() 返回的是 RelCollationTraitDef.INSTANCE。这建立了“实例”与“类别”的绑定关系。
  RelTraitDef getTraitDef();

  /**
   * See <a href="#EqualsHashCodeNote">note about equals() and hashCode()</a>.
   */
  // 返回特征的哈希值。
  @Override int hashCode();

  /**
   * See <a href="#EqualsHashCodeNote">note about equals() and hashCode()</a>.
   */
  // 作用： 判断两个特征是否相等。
  @Override boolean equals(@Nullable Object o);

  /**
   * Returns whether this trait satisfies a given trait.
   * <p>A trait satisfies another if it is the same or stricter. For example,
   * {@code ORDER BY x, y} satisfies {@code ORDER BY x}.
   * <p>A trait's {@code satisfies} relation must be a partial order (reflexive,
   * anti-symmetric, transitive). Many traits cannot be "loosened"; their
   * {@code satisfies} is an equivalence relation, where only X satisfies X.
   * <p>If a trait has multiple values
   * (see {@link org.apache.calcite.plan.RelCompositeTrait})
   * a collection (T0, T1, ...) satisfies T if any Ti satisfies T.
   *
   * @param trait Given trait
   * @return Whether this trait subsumes a given trait
   */
  // 作用： 判断当前特征是否“满足”给定的特征。
  // 这是最关键的逻辑。“满足”不等于“相等”。
  // 偏序关系： 它表现为一种包含或更强的关系。
  // 例子： 如果当前特征是 ORDER BY a, b（更严格），它一定满足目标要求的 ORDER BY a（较宽松）。
  // 等价关系： 对于某些特征（如 Convention 转换协议），满足关系通常退化为相等关系，即只有 X 满足 X。
  boolean satisfies(RelTrait trait);

  /**
   * Returns a succinct name for this trait. The planner may use this String
   * to describe the trait.
   */
  // 作用： 返回特征的简洁字符串表示。
  // 主要用于 Debug 和生成执行计划的可读文本（如 Explain 命令的输出）。例如，一个排序特征可能输出为 [0 ASC, 1 DESC]。
  @Override String toString();

  /**
   * Registers a trait instance with the planner.
   *
   * <p>This is an opportunity to add rules that relate to that trait. However,
   * typical implementations will do nothing.
   * @param planner Planner
   */
  // 作用： 将特征实例注册到优化器中。
  // 当一个新类型的特征进入优化器视野时，它可以在此处顺便注册一些相关的优化规则（RelOptRule）。
  // 典型应用： JdbcConvention 在注册时，会向优化器添加将逻辑算子转换为 JDBC 物理算子的规则。
  void register(RelOptPlanner planner);

  /**
   * Applies a mapping to this trait.
   *
   * <p>Some traits may be changed if the columns order is changed by a mapping
   * of the {@link Project} operator.
   * <p>For example, if relation {@code SELECT a, b ORDER BY a, b} is sorted by
   * columns [0, 1], then the project {@code SELECT b, a} over this relation
   * will be sorted by columns [1, 0]. In the same time project {@code SELECT b}
   * will not be sorted at all because it doesn't contain the collation
   * prefix and this method will return an empty collation.
   *
   * <p>Other traits are independent from the columns remapping. For example
   * {@link Convention} or {@link RelDistributions#SINGLETON}.
   *
   * @param mapping   Mapping
   * @return trait with mapping applied
   */
  // 作用： 当算子的列发生映射（如 Project 投影）时，转换特征。
  // 场景： 原始数据是 [a, b]，按 a 排序（索引 0）。经过 SELECT b, a 投影后，列的索引变了。
  // 逻辑： 该方法会根据 mapping 重新计算特征。在上例中，排序特征会自动从“索引 0”映射为“索引 1”。如果列被删除了，该方法可能返回一个空特征。
  default <T extends RelTrait> T apply(Mappings.TargetMapping mapping) {
    return (T) this;
  }

  /**
   * Returns whether this trait is the default trait value.
   */
  // 判断当前特征是否为该类型的默认值
  // 通过比较当前实例是否等于 getTraitDef().getDefault() 来实现。例如，在排序特征中，EMPTY（无序）通常就是默认值。
  default boolean isDefault() {
    return this == getTraitDef().getDefault();
  }
}
