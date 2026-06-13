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
package org.apache.calcite.tools;

import org.apache.calcite.plan.RelOptLattice;
import org.apache.calcite.plan.RelOptMaterialization;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;

import java.util.List;

/**
 * Program that transforms a relational expression into another relational
 * expression.
 * <p>A planner is a sequence of programs, each of which is sometimes called
 * a "phase".
 *
 * <p>The most typical program is an invocation of the volcano planner with a
 * particular {@link org.apache.calcite.tools.RuleSet}.
 */
// 负责抽象和定义查询优化器（Optimizer）执行流程中的“单个优化阶段（Phase）”。
// Program 翻译过来是“程序”或“阶段计划”，它的核心作用是：将一个关系代数表达式（RelNode）转换为另一个在语义上等价、但结构或物理执行方式不同的新关系代数表达式（RelNode）。
// 在企业级的 SQL 优化器中，把一段逻辑抽象的 SQL 变成最终最高效的物理执行计划，通常不可能通过“毕其功于一役”的方式单次完成。整个优化过程是非常复杂的，通常会被切分成多个连续的、职责单一的“阶段（Phases）”。
// 在 Calcite 中，一个优化器（Planner）本质上就是一组按顺序执行的 Program 序列。例如，一个标准的数据仓库优化流水线可能包含以下几个 Program 阶段：
// 阶段一（物化视图改写）：运行一个 Program，专门尝试把用户的原始查询改写到现有的物化视图或 Lattice 上。
// 阶段二（逻辑子查询解耦）：运行第二个 Program，专门把 IN 或 EXISTS 的子查询展开并转化为标准的 JOIN。
// 阶段三（基于代价的物理图优化）：运行第三个 Program，启动 Volcano（火山）模型，结合统计信息和 CBO 算法，在成百上千个规则（RuleSet）中选择最快的物理算子（如 HashJoin 还是 MergeJoin）并决定最佳的表关联顺序（Join Order）。
public interface Program {
  // RelOptPlanner planner 当前正在运行的优化器实例（通常是 VolcanoPlanner 或 HepPlanner）
  // RelNode rel  输入的关系代数表达式树（AST 逻辑算子树）
  // RelTraitSet requiredOutputTraits 期望该优化阶段最终输出的物理特征集合（Traits） 特征包括但不限于：调用方言/物理引擎（Convention）（如 Spark 算子还是 Flink 算子）、数据排序属性（Collation）、数据分布式打散特征（Distribution）。该参数告诉当前 Program：“无论你内部怎么折腾，最后请尽可能帮我把这棵树转化为符合这些物理特征要求的节点。”
  // List<RelOptMaterialization> materializations 当前数据库系统中所有可用的、注册过的物化视图（Materialization）列表
  // List<RelOptLattice> lattices 当前优化上下文中所有可用、且已经构建好的晶格多维模型（Lattice）列表。
  RelNode run(RelOptPlanner planner, RelNode rel,
      RelTraitSet requiredOutputTraits,
      List<RelOptMaterialization> materializations,
      List<RelOptLattice> lattices);
}
