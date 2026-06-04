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

import org.apache.calcite.linq4j.tree.BlockStatement;
import org.apache.calcite.plan.DeriveMode;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.PhysicalNode;
import org.apache.calcite.util.Pair;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * A relational expression of one of the
 * {@link org.apache.calcite.adapter.enumerable.EnumerableConvention} calling
 */
// EnumerableRel 是 Apache Calcite 适配器体系（Adapter）中最核心、最著名的一个物理算子基接口。
// 在大数据执行引擎或多源联邦查询（Federated Query）中，任何一个查询不论其逻辑计划多么精妙，最终都必须落地为能够实际抓取数据、运行在 CPU 上的可执行代码。
// EnumerableRel 接口就是这一“将关系代数转译为物理机器码”的转折点。
// 所有属于 EnumerableConvention（内存迭代调用约定）的物理算子（例如 EnumerableProject、EnumerableJoin、EnumerableFilter）都必须继承该接口。
// 核心作用
// 作为代码生成（Code Generation）的骨架总指挥：
// EnumerableRel 最大的特色是通过生成 Java 源代码（利用 Linq4j 框架表达式树）在运行时编译并动态执行查询。
// 每个子类算子在优化完成后，都要通过该接口交出自己这一层所对应的 Java 代码片段。
// 连接“自顶向下优化”与“底层物理执行”的桥梁：
// 继承了现代 Top-Down 物理节点接口 PhysicalNode。也就是说，它是带有自顶向下特征传递能力的、随时可以用于生成最终内存迭代器（Enumerable 序列）的物理算子。
public interface EnumerableRel
    extends PhysicalNode {

  //~ Methods ----------------------------------------------------------------
  // 默认直接返回 null。
  // 这意味着对大部分常规 Enumerable 物理算子来说，框架默认不帮它们强行击穿特征。
  // 如果个别算子（如 EnumerableSort）需要支持自顶向下的特质对齐，需要单独去重写覆盖它。
  @Override default @Nullable Pair<RelTraitSet, List<RelTraitSet>> passThroughTraits(
      RelTraitSet required) {
    return null;
  }

  @Override default @Nullable Pair<RelTraitSet, List<RelTraitSet>> deriveTraits(
      RelTraitSet childTraits, int childId) {
    return null;
  }

  @Override default DeriveMode getDeriveMode() {
    return DeriveMode.LEFT_FIRST;
  }

  /**
   * Creates a plan for this expression according to a calling convention.
   *
   * @param implementor Implementor
   * @param pref Preferred representation for rows in result expression
   * @return Plan for this expression according to a calling convention
   */
  // 整个 Enumerable 适配器的灵魂核心方法（核心代码生成工厂）
  // 当 VolcanoPlanner 决定了最终的物理执行计划后，会开启全量物理树的深度优先遍历，轮流回调每一个物理算子的 implement 方法。
  // implementor：Java 代码生成的上下文大管家，内部持有一个全局变量池、类定义生成器，算子通过它可以相互协作，拼接、组装出一条由 BlockStatement（Java 块语句）构成的完整流式处理流水线。
  // pref：上层算子对当前算子输出的数据行格式（物理类型）提出的期望/偏好倾向（参见下文 Prefer 的解说）。
  Result implement(EnumerableRelImplementor implementor, Prefer pref);

  /** Preferred physical type. */
  enum Prefer {
    /** Records must be represented as arrays. 记录是数组的形式*/
    ARRAY,
    /** Consumer would prefer that records are represented as arrays, but can
     * accommodate records represented as objects. 记录是数组的形式，但是可以接受对象的形式*/
    ARRAY_NICE,
    /** Records must be represented as objects. */
    CUSTOM,
    /** Consumer would prefer that records are represented as objects, but can
     * accommodate records represented as arrays. 记录是对象的形式，但也可以接受数组*/
    CUSTOM_NICE,
    /** Consumer has no preferred representation. */
    ANY;

    public JavaRowFormat preferCustom() {
      return prefer(JavaRowFormat.CUSTOM);
    }

    public JavaRowFormat preferArray() {
      return prefer(JavaRowFormat.ARRAY);
    }

    public JavaRowFormat prefer(JavaRowFormat format) {
      switch (this) {
      case CUSTOM:
        return JavaRowFormat.CUSTOM;
      case ARRAY:
        return JavaRowFormat.ARRAY;
      default:
        return format;
      }
    }

    public Prefer of(JavaRowFormat format) {
      switch (format) {
      case ARRAY:
        return ARRAY;
      default:
        return CUSTOM;
      }
    }
  }

  /** Result of implementing an enumerable relational expression by generating
   * Java code. */
  class Result {
    public final BlockStatement block;

    /**
     * Describes the Java type returned by this relational expression, and the
     * mapping between it and the fields of the logical row type.
     */
    public final PhysType physType;
    public final JavaRowFormat format;

    public Result(BlockStatement block, PhysType physType,
        JavaRowFormat format) {
      this.block = block;
      this.physType = physType;
      this.format = format;
    }
  }
}
