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

import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTrait;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.plan.RelTraitSet;

/**
 * Calling convention that returns results as an
 * {@link org.apache.calcite.linq4j.Enumerable} of object arrays.
 * <p>Unlike enumerable convention, no code generation is required.
 */
// 在 Apache Calcite 的架构中，InterpretableConvention（解释执行约定）是一个相对特殊且轻量级的物理特征。它主要用于 Calcite 的**内置解释器（Interpreter）**模块。
// InterpretableConvention 的主要职责是提供一种无需生成代码即可执行查询的路径。
// 执行方式： 与 EnumerableConvention（生成 Java 源码并编译）不同，它直接利用 Calcite 内部定义的节点处理器（如 Nodes.FilterNode, Nodes.ProjectNode）对数据进行逐行解释处理。
// 桥梁作用： 它产出的结果格式与 Enumerable 兼容（即 Enumerable<Object[]>），但其内部实现完全基于解释逻辑。
// 应用场景： 主要用于单元测试、快速验证逻辑计划，或者在不希望引入 Janino 编译器开销的超轻量级环境中使用。
// 与 BindableConvention的不同：
// 前者返回返回 EnumerableRel.class，后者返回BindableRel.class。
// 执行组件，前者使用 org.apache.calcite.interpreter.Interpreter 模块，后者使用使用 ArrayBindable 接口手动绑定参数。
public enum InterpretableConvention implements Convention {
  INSTANCE;

  @Override public String toString() {
    return getName();
  }

  @Override public Class getInterface() {
    return EnumerableRel.class;
  }

  @Override public String getName() {
    return "INTERPRETABLE";
  }

  @Override public RelTraitDef getTraitDef() {
    return ConventionTraitDef.INSTANCE;
  }

  @Override public boolean satisfies(RelTrait trait) {
    return this == trait;
  }

  @Override public void register(RelOptPlanner planner) {}

  @Override public boolean canConvertConvention(Convention toConvention) {
    return false;
  }

  @Override public boolean useAbstractConvertersForConversion(RelTraitSet fromTraits,
      RelTraitSet toTraits) {
    return false;
  }
}
