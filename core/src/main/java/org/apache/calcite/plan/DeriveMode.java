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

/**
 * The mode of trait derivation.
 */
// 专门在自顶向下（Top-Down）优化架构中，
// 为物理算子（PhysicalNode）指定物理特质（如排序 Collation、数据分布 Distribution）自底向上派生时的搜索策略（Strategy Mode）
public enum DeriveMode {
  /**
   * Uses the left most child's traits to decide what
   * traits to require from the other children. This
   * generally applies to most operators.
   */
  // 使用最左侧孩子的特质，来决定其他孩子需要满足什么特质。这通常适用于绝大多数算子。
  // Calcite 物理算子的默认派生模式。
  // 最经典的例子是标准的流式哈希连接（Hash Join）和大部分流式算子。Hash Join 通常以左孩子（流式驱动侧）的物理分布或排序为基准。如果左孩子汇报自己确定了“按 ID 列 Hash 分布”，那么父算子直接采用 LEFT_FIRST 策略，不再等右孩子汇报，直接去“命令”右孩子也必须按 ID 列 Hash 分布来对齐。
  // 这极大地简化了双副本算子的推导线。
  LEFT_FIRST,

  /**
   * Uses the right most child's traits to decide what
   * traits to require from the other children. Operators
   * like index nested loop join may find this useful.
   */
  // 使用最右侧孩子的特质，来决定其他孩子需要满足什么特质。像索引嵌套循环连接（Index Nested Loop Join）这样的算子会发现这很有用。
  // 在索引嵌套循环连接（Index Nested Loop Join）中，右表通常是一张带有物理索引的外部表。优化器必须先看右表（Index 1）能提供什么样的索引扫描特质（比如右表只能通过 user_id 的索引吐出有序数据）。左表（Index 0）必须无条件去配合右表的索引字段进行数据分发或过滤。因此，这种算子会声明 RIGHT_FIRST，以右孩子的特质作为向上派生和横向对齐的源头。
  RIGHT_FIRST,

  /**
   * Iterates over each child, uses current child's traits
   * to decide what traits to require from the other
   * children. It includes both LEFT_FIRST and RIGHT_FIRST.
   * System that doesn't enable join commutativity should
   * consider this option. Special customized operators
   * like a Join who has 3 inputs may find this useful too.
   */
  // 遍历每一个孩子，轮流使用当前孩子的特质来决定其他孩子需要满足什么特质。
  // 它同时包含了 LEFT_FIRST 和 RIGHT_FIRST。没有开启“连接交换律（Join Commutativity）”的系统应当考虑这个选项。拥有 3 个输入的特殊自定义算子也会发现它很有用。
  // 未开启 Join 交换律的引擎：在某些定制化计算引擎中，出于某些限制没有开启 Join 左右树互换的改写规则（Join Commutativity）。为了不漏掉最优解，Join 算子必须两边都试一遍，即无论是左树还是右树先产生分布式特质，都要能触发另一侧的对齐。
  BOTH,

  /**
   * Leave it to you, you decide what you cook. This will
   * allow planner to pass all the traits from all the
   * children, the user decides how to make use of these
   * traits and whether to derive new rel nodes.
   */
  // 源码注释：全权委托给你，你来决定你“烹饪”什么。这允许优化器将所有孩子节点的所有可行特质（矩阵）通通传递给你，由用户决定如何利用这些特质、以及是否派生出新的关系代数节点。
  // 机制改变：一旦声明为此模式，优化器将关闭单点触发的 deriveTraits(childTraits, childId) 路由，转而唯一调用 PhysicalNode 中参数为二维矩阵的 derive(List<List<RelTraitSet>> inputTraits) 方法
  OMAKASE,

  /**
   * Trait derivation is prohibited.
   */
  // 禁止特质派生。
  // 核心性能裁剪开关。明确告诉优化器：“我是物理特征死板、一成不变的算子，或者我是最底层的叶子节点，别人别想从我这里折腾出什么衍生特征，请不要在我身上浪费任何派生算力。”
  // 物理表扫描（PhysicalTableScan / JdbcTableScan）：作为算子树的叶子节点，它们没有孩子，完全不存在“根据孩子的特征变化来推导自己”的场景，因此必须声明为 PROHIBITED。
  // 物理强制算子（Sort / Exchange / Enforcer）：这些算子本身就是为了强制对齐特征而强行织入树中的（例如 Sort 明确就是要把数据变成指定的 Order）。它们的物理特征输出是绝对固定的，不需要也不允许根据下层再做出动态衍生改变，声明为 PROHIBITED 可以充当防死循环和无效搜索的硬核护栏。
  PROHIBITED
}
