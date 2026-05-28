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

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.DeriveMode;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.util.Pair;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Physical node in a planner that is capable of doing
 * physical trait propagation and derivation.
 * <p>How to use?
 *
 * <ol>
 * <li>Enable top-down optimization by setting
 * {@link org.apache.calcite.plan.volcano.VolcanoPlanner#setTopDownOpt(boolean)}.
 * </li>
 *
 * <li>Let your convention's rel interface extends {@link PhysicalNode},
 * see {@link org.apache.calcite.adapter.enumerable.EnumerableRel} as
 * an example.</li>
 *
 * <li>Each physical operator overrides any one of the two methods:
 * {@link PhysicalNode#passThrough(RelTraitSet)} or
 * {@link PhysicalNode#passThroughTraits(RelTraitSet)} depending on
 * your needs.</li>
 *
 * <li>Choose derive mode for each physical operator by overriding
 * {@link PhysicalNode#getDeriveMode()}.</li>
 *
 * <li>If the derive mode is {@link DeriveMode#OMAKASE}, override
 * method {@link PhysicalNode#derive(List)} in the physical operator,
 * otherwise, override {@link PhysicalNode#derive(RelTraitSet, int)}
 * or {@link PhysicalNode#deriveTraits(RelTraitSet, int)}.</li>
 *
 * <li>Mark your enforcer operator by overriding {@link RelNode#isEnforcer()},
 * see {@link Sort#isEnforcer()} as an example. This is important,
 * because it can help {@code VolcanoPlanner} avoid unnecessary
 * trait propagation and derivation, therefore improve optimization
 * efficiency.</li>
 *
 * <li>Implement {@link Convention#enforce(RelNode, RelTraitSet)}
 * in your convention, which generates appropriate physical enforcer.
 * See {@link org.apache.calcite.adapter.enumerable.EnumerableConvention}
 * as example. Simply return {@code null} if you don't want physical
 * trait enforcement.</li>
 * </ol>
 */
// PhysicalNode 接口是 Apache Calcite 优化器框架在演进到现代化自顶向下（Top-Down）优化架构时的核心基石。
// 在传统的 Volcano 优化器（自底向上）中，物理特征的满足通常依赖于规则被动地去匹配，效率较低。
// Calcite 在吸收了 Cascades 优化器架构的精髓后，引入了自顶向下的优化机制（通过 VolcanoPlanner.setTopDownOpt(boolean) 开启）。
// PhysicalNode 接口就是为了在自顶向下优化模式下，实现物理特征（RelTrait，如排序 Collation、分布式 Distribution）的动态向下传递（Pass-through）与自底向上动态派生（Derivation）而专门设计的。
// 核心作用
// 支持自顶向下的特征请求传递（Pass-through 机制）：
//当父节点对当前节点提出具体的物理特征要求（例如：一个物理 Join 要求它的左孩子必须按 ID 列排序）时，当前物理节点能够通过该接口接收这个 required 特征，并转化为对它自身下层子节点的物理特征要求，实现特征请求从根节点向叶子节点的纵向穿透传递。
// 支持自底向上的特征动态派生（Derivation 机制）：
//当某个子节点的物理特征发生改变或被确定时（例如：下层的 TableScan 忽然声明自己自带物理主键排序），当前物理节点能够捕获这个变化，并动态计算/派生出当前节点以及其他兄弟子节点应该具备的新特征，实现物理状态的向上反馈与横向推导。
// 避免无效的强制转换算子（Enforcer）开销：
//有了特征的穿透与派生，优化器可以在算子树内部“顺水推舟”地对齐特征，只有在迫不得已时才去调用 Convention.enforce 织入物理强制算子（如 Sort 或 Exchange），从而大幅削减 CBO 搜索空间，提高商业级优化器的爆破与搜索效率。
public interface PhysicalNode extends RelNode {

  /**
   * Pass required traitset from parent node to child nodes,
   * returns new node after traits is passed down.
   */
  // 物理特征自顶向下传递的“总大闸/总工厂”方法。
  // 在深入源码之前，我们可以通过下面这个简单的拓扑传递流，来直观理解该方法在优化器运行期扮演的角色：
  //Top-Down 请求降临：父节点说：“我需要你输出 required 特征（例如：按 ID 排序）”。
  //协商与元数据计算：调用 passThroughTraits 算一笔账，得知自己要变身为什么特质（p.left），以及两个孩子分别要对齐什么特质（p.right）。
  //强制转译下发：对左孩子和右孩子分别调用 RelOptRule.convert。
  //克隆变身：调用 copy 诞生出全套特质对齐的全新物理算子，递交回优化器。
  // 参数：RelTraitSet required  上层父节点传递下来的、强制要求当前物理节点必须输出的物理特质集合（如特定的排序 RelCollation 或数据分布式样 RelDistribution）。
  // 返回值：@Nullable RelNode  返回一个自身特质以及所有子节点特质均已对齐 required 要求的、全新的物理算子节点。
  default @Nullable RelNode passThrough(RelTraitSet required) {
    // 这是一次“策略问询”。当前节点拿着父节点的 required 军令状，去计算两样至关重要的道具并打包封装进二元组 p 中：
    // p.left（自身新令牌）：当前节点承接并融合了父节点要求后，自己决定升级成的全新 RelTraitSet。
    // p.right（孩子指标书）：一个 List<RelTraitSet>，声明了当前节点为了顺利完工，对旗下每一个子节点分别追加、索要的物理特征约束。
    Pair<RelTraitSet, List<RelTraitSet>> p = passThroughTraits(required);
    if (p == null) {
      return null;
    }
    // 要求子类在返回 p.right（对孩子的指标书）时，其 List 长度必须与当前算子实际持有的输入子节点数量（size）丝毫不差地严格一一对应
    int size = getInputs().size();
    assert size == p.right.size();
    List<RelNode> list = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      // 真正实施自顶向下特质对齐的物理动作
      // RelOptRule.convert(...)：通知优化器内核，在第 i 个孩子的算子树上方，强制施加一层特征校准转译。
      // 如果这个孩子当前的特质与目标指标不符，优化器会在后续搜索中，通过在其上方动态织入 Enforcer 算子（如物理 Sort 或物理 Exchange）来强行把这个孩子拨正到对齐状态。
      RelNode n = RelOptRule.convert(getInput(i), p.right.get(i)); //对每个输入的特征做转换
      list.add(n);
    }
    return copy(p.left, list);
  }

  /**
   * Pass required traitset from parent node to child nodes,
   * returns a pair of traits after traits is passed down.
   * <p>Pair.left: the new traitset;
   * Pair.right: the list of required traitsets for child nodes.
   */
  // passThroughTraits 是物理节点（PhysicalNode）用来向下传递并协商物理特征要求的策略接口
  // required 代表上层父节点强制要求当前节点必须具备并输出的物理特质集合（Required TraitSet）。
  // RelCollation（排序特质）：父节点要求当前节点输出的数据必须按照某些列的特定顺序（升序/降序）排列（例如：为了做 MergeJoin，父节点要求输入流必须按 tenant_id, create_time 排序）。
  // RelDistribution（分布式特质）：在分布式/联邦查询引擎中，父节点要求当前算子输出的数据必须满足某种数据分发模式（例如：Hash 分布、Broadcast 广播分布、或者单点 Singleton 分布）。
  // 参数作用：它是父节点对当前节点下达的“硬性物理指标”。当前节点必须以此指标为输入，评估自身物理上能否实现，以及如果能实现，该如何去“刁难”自己的孩子节点。
  // 返回值深层解读
  // Pair.left（即 RelTraitSet） 代表含义：当前算子变身后的全新特征集。作用：当前节点在成功承接并融入了父节点的 required 要求后，自己决定升级或转译成的最终 RelTraitSet 身份令牌。
  // Pair.right（即 List<RelTraitSet>） 代表含义：下层全体孩子节点分别被要求的特征集列表。约束铁律：这个 List 的大小必须严格等于当前算子实际持有的输入子节点数量（即 this.getInputs().size()）。
  default @Nullable Pair<RelTraitSet, List<RelTraitSet>> passThroughTraits(
      RelTraitSet required) {
    throw new RuntimeException(getClass().getName()
        + "#passThroughTraits() is not implemented.");
  }

  /**
   * Derive traitset from child node, returns new node after
   * traits derivation.
   */
  // 负责的是自底向上（Bottom-Up）的物理特征派生与推导
  // 当某个子节点的物理特征被确定或发生改变时，父节点需要捕获这个变化，并动态计算出自身以及其他兄弟子节点应该如何进行特质对齐。
  // derive 方法就是这一推导链路的“总执行工厂”。它同样是一个默认方法（default），采用了模板方法模式，将通用的算子树克隆和子节点强制校准逻辑固化在接口底层。
  // 参数：RelTraitSet childTraits 触发当前派生动作的某一个具体子节点当前最新的物理特质集合（如该子节点刚刚通过规则确定了自身的物理排序 RelCollation 或分布式样 RelDistribution）。
  // 参数：int childId 该变动子节点在当前父算子中所处的物理槽位索引（从 0 开始）。明确告诉当前算子是哪一个孩子发生了变化（例如对于一个二元 Join，childId = 1 意味着右孩子特质发生了更新，当前算子需要根据右孩子的现状去推导左孩子和自身）。
  // 返回一个自身特质已根据孩子变化完成动态升级、且所有旁系兄弟孩子特征也同步对齐的、全新的物理算子节点。
  default @Nullable RelNode derive(RelTraitSet childTraits, int childId) {
    // 调用当前物理算子子类切实重写的 deriveTraits 助手方法。
    // p.left（自身新令牌）：当前节点在感知到孩子的特征变动后，自己决定顺水推舟衍生出的全新 RelTraitSet。
    // p.right（全体孩子期望状态）：一个 List<RelTraitSet>，声明了当前算子为了配合这次变动，要求所有子节点（包括触发变动的节点和所有旁系兄弟节点）最终应该对齐的完备特征列表。
    Pair<RelTraitSet, List<RelTraitSet>> p = deriveTraits(childTraits, childId);
    if (p == null) {
      return null;
    }
    // 它要求子类在派生出 p.right（孩子特征列表）时，其长度必须与当前算子实际持有的输入子节点数量（size）严格一致。
    int size = getInputs().size();
    assert size == p.right.size();
    List<RelNode> list = new ArrayList<>(size);
    // 全体子节点特征强制校准
    // 真正实施自底向上特征横向对齐的物理动作（联动效应）。
    for (int i = 0; i < size; i++) {
      RelNode node = getInput(i);
      node = RelOptRule.convert(node, p.right.get(i));
      list.add(node);
    }
    return copy(p.left, list);
  }

  /**
   * Derive traitset from child node, returns a pair of traits after
   * traits derivation.
   *
   * <p>Pair.left: the new traitset;
   * Pair.right: the list of required traitsets for child nodes.
   */
  // 自底向上（Bottom-Up）物理特征派生与推导的“核心账本接口”。与前面负责向下传递需求的 passThroughTraits 遥相呼应，
  // deriveTraits 负责在已知某个孩子物理特征的前提下，反向推导父节点自身以及其他兄弟孩子节点应当如何对齐特征。
  default @Nullable Pair<RelTraitSet, List<RelTraitSet>> deriveTraits(
      RelTraitSet childTraits, int childId) {
    throw new RuntimeException(getClass().getName()
        + "#deriveTraits() is not implemented.");
  }

  /**
   * Given a list of child traitsets,
   * inputTraits.size() == getInput().size(),
   * returns node list after traits derivation. This method is called
   * ONLY when the derive mode is OMAKASE.
   */
  // List<List<RelTraitSet>> inputTraits 个嵌套的二维列表。理解它的关键在于：它不是单点状态的汇报，而是所有子节点可行性物理特征的“笛卡尔积矩阵”。
  // 可以将其拆解为两个维度：
  // 外层 List：其大小 inputTraits.size() 严格等于当前算子的子节点数量 getInput().size()。外层列表的第 $i$ 个元素，代表第 $i$ 个孩子节点。
  // 内层 List<RelTraitSet>：第 $i$ 个孩子在 Memo（等价组）中当前被探索出来的所有可能具备的物理特质集合（Multiple TraitSets）。
  default List<RelNode> derive(List<List<RelTraitSet>> inputTraits) {
    throw new RuntimeException(getClass().getName()
        + "#derive() is not implemented.");
  }

  /**
   * Returns mode of derivation.
   */
  default DeriveMode getDeriveMode() {
    return DeriveMode.LEFT_FIRST;
  }
}
