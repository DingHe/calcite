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
package org.apache.calcite.materialize;

import org.apache.calcite.util.Litmus;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;

/** Root node in a {@link Lattice}. It has no parent. */
// 专门用来表示整棵连接树（Join Tree）的根节点（即事实表 / Fact Table Node）。
// LatticeRootNode 的核心作用是作为多维晶格模型（Lattice Graph）的中心锚点和全局管理者。
// LatticeRootNode 通过对子树进行展平、提取路径，向优化器提供了如下关键能力：
// 全局拓扑快照：它将嵌套的树结构展平，使得优化器可以通过一个线性列表（descendants）快速访问树中的任意维表节点。
// 包含关系判定：它是物化视图改写决策的关键。通过比对两条 SQL 生成的 LatticeRootNode 的路径集合，Calcite 可以瞬间判断出“物化视图 A 包含的维度是否完全覆盖了当前查询 B 的维度”，进而决定能否进行物化改写。
public class LatticeRootNode extends LatticeNode {
  /** Descendants, in prefix order. This root node is at position 0. */
  // 以前序遍历（Prefix Order / Pre-order）顺序存储的、包含根节点自身在内的所有后代节点的不可变列表。
  // 位置 0 的节点永远是当前这个根节点（事实表）本身。通过这个属性，外部组件无需再写复杂的递归算法，直接遍历这个 List 就能拿到整棵拓扑树上的所有表。
  public final ImmutableList<LatticeNode> descendants;
  // 存储从该根节点（事实表）出发，到达树中所有后代维表节点的所有关联路径（Path）集合。
  // 每一条 Path 都是由若干条“边（Step）”串联起来的维度路径（例如 事实表 -> 客户表 -> 城市表）。这些路径代表了当前晶格模型所支持的全部维度分析能力。
  final ImmutableList<Path> paths;

  @SuppressWarnings("method.invocation.invalid")
  LatticeRootNode(LatticeSpace space, MutableNode mutableNode) {
    super(space, null, mutableNode);

    final ImmutableList.Builder<LatticeNode> b = ImmutableList.builder();
    flattenTo(b);
    this.descendants = b.build();
    this.paths = createPaths(space);
  }

  private ImmutableList<Path> createPaths(LatticeSpace space) {
    final List<Step> steps = new ArrayList<>();
    final List<Path> paths = new ArrayList<>();
    createPathsRecurse(space, steps, paths);
    assert steps.isEmpty();
    return ImmutableList.copyOf(paths);
  }

  @Override void use(List<LatticeNode> usedNodes) {
    if (!usedNodes.contains(this)) {
      usedNodes.add(this);
    }
  }

  /** Validates that nodes form a tree; each node except the first references
   * a predecessor. */
  boolean isValid(Litmus litmus) {
    for (int i = 0; i < descendants.size(); i++) {
      LatticeNode node = descendants.get(i);
      if (i == 0) {
        if (node != this) {
          return litmus.fail("node 0 should be root");
        }
      } else {
        if (!(node instanceof LatticeChildNode)) {
          return litmus.fail("node after 0 should be child");
        }
        final LatticeChildNode child = (LatticeChildNode) node;
        if (!descendants.subList(0, i).contains(child.parent)) {
          return litmus.fail("parent not in preceding list");
        }
      }
    }
    return litmus.succeed();
  }


  /** Whether this node's graph is a super-set of (or equal to) another node's
   * graph. */
  public boolean contains(LatticeRootNode node) {
    return paths.containsAll(node.paths);
  }

}
