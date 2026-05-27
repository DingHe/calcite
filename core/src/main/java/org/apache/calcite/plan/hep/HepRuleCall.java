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
package org.apache.calcite.plan.hep;

import org.apache.calcite.plan.RelHintsPropagator;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HepRuleCall implements {@link RelOptRuleCall} for a {@link HepPlanner}. It
 * remembers transformation results so that the planner can choose which one (if
 * any) should replace the original expression.
 */
// HepRuleCall 是 Apache Calcite 项目中的一个核心类，它是 RelOptRuleCall 的具体子类，专门服务于 HepPlanner（启发式优化器）。
// Calcite 官方对 HepPlanner 的定位是 启发式优化器（Heuristic Planner），
// 它与 VolcanoPlanner（基于代价的优化器）最大的区别在于：它没有复杂的、并存多条等价路径的 Memo 备忘录结构。
// HepPlanner 倾向于“一旦发现更好的变换，就尽快执行就地替换”。
// HepRuleCall 的核心作用
// 启发式变换的结果收集器：当某条优化规则（RelOptRule）在 HepPlanner 遍历算子树时被成功触发，就会诞生一个 HepRuleCall。当规则内部调用 transformTo 生成一棵或多棵等价的新算子树时，HepRuleCall 会将这些新树全部收集到内部的 results 容器中。
// 就地替换的缓冲跳板：由于一条规则在 onMatch 内部可能会多次调用 transformTo（例如尝试了多种不同的改写策略），HepRuleCall 负责将这些策略产物临时缓存起来。当规则执行完毕后，HepPlanner 会从这个 results 列表中挑出最终的幸运儿，将原来的老算子树根节点（rels[0]）彻底剜除并就地替换。
public class HepRuleCall extends RelOptRuleCall {
  //~ Instance fields --------------------------------------------------------
  // 核心结果缓冲区。
  // 专门用来存放优化规则在本次触发（Call）执行期间，通过 transformTo 方法产生的所有在语义上与原算子树等价的全新算子树根节点。
  // 为什么是 List 结构：因为同一个规则、在同一次匹配中，允许根据不同的微调策略生成多棵等价树丢给优化器（虽然 HepPlanner 在绝大部分标准规则下，列表中通常只包含一个最终的优化结果）。
  private List<RelNode> results;

  //~ Constructors -----------------------------------------------------------

  HepRuleCall(
      RelOptPlanner planner,
      RelOptRuleOperand operand,
      RelNode[] rels,
      Map<RelNode, List<RelNode>> nodeChildren,
      @Nullable List<RelNode> parents) {
    super(planner, operand, rels, nodeChildren, parents);

    results = new ArrayList<>();
  }

  //~ Methods ----------------------------------------------------------------
  // 实现父类的核心转型接口，完成新树的合法化校验、Hint 挂载并收入缓冲区。
  @Override public void transformTo(RelNode rel, Map<RelNode, RelNode> equiv,
      RelHintsPropagator handler) {
    // 首先死死锁定本次匹配子树的老根节点 rel0。
    final RelNode rel0 = rels[0];
    // 执行极严格的行类型（Row Type）强校验。
    // 检查新生成的算子树 rel 返回的字段类型、顺序、命名是否与原本的老算子 rel0 完完全全一模一样。
    // 如果在逻辑转换过程中开发者不小心把字段弄丢了或换了顺序，这一步会直接抛出断言或运行时异常。因为启发式优化必须保证绝对的语义等价。
    RelOptUtil.verifyTypeEquivalence(rel0, rel, rel0);
    // 触发 Hint 传播机制。委派传入的处理器，将老算子 rel0 头顶上的 SQL Hint 智能地克隆、刷涂到新算子 rel 的头顶上，并返回挂载好 Hint 的最终新算子。
    rel = handler.propagate(rel0, rel);
    // 将大功告成的新算子树安全地收纳进 results 列表中，等待 HepPlanner 随后的收割。
    results.add(rel);
    // 极其重要的一步：无情失效当前集群的元数据缓存。由于算子树的拓扑结构发生了剧烈改变（如 Filter 沉到了 Join 下方），原本缓存的行数（Row Count）、代价（Cost）等元数据已经全部作废。
    // 这一行代码会强行清空缓存，逼迫下一个查询或下一条规则在计算元数据时必须重新做实时推导，确保代价数据的绝对准确。
    rel0.getCluster().invalidateMetadataQuery();
  }

  List<RelNode> getResults() {
    return results;
  }
}
