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

import org.apache.calcite.util.mapping.IntPair;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static java.util.Objects.requireNonNull;

/** Non-root node in a {@link Lattice}. */
// 专门用来表示多维连接树（Join Tree）中的非根节点（即维表节点 / Dimension Table Node）。
// 与作为根节点的事实表（LatticeRootNode）不同，LatticeChildNode 代表的表必须有且仅有一个父节点，并且明确记录了它是通过哪些列与父节点进行等值连接（Equi-Join）的。
// LatticeChildNode 的核心作用是显式地建模星型/雪花模型中，从属维表节点的父子连接双向关系以及具体的关联路径。
// 在多维分析中，一个复杂的雪花模型会形成一个有向无环图或树状拓扑。LatticeChildNode 允许 Calcite 在已经冻结的、不可变的格子树中：
//向上追溯（Upward Traversal）：通过 parent 属性一路向根节点（事实表）追溯，从而知道自己处于多维路径的哪一个分支层级。
// 连接条件隔离：通过 link 属性精确知晓当前表与父表之间的多列等值映射，用于后续生成改写后的物理 SQL 连接。
public class LatticeChildNode extends LatticeNode {
  // 指向当前节点在树状结构中的直接父节点（上级表节点）。
  public final LatticeNode parent;
  // 存储当前表（子表）与父表之间等值连接条件的列索引映射对集合。
  public final ImmutableList<IntPair> link;

  LatticeChildNode(LatticeSpace space, LatticeNode parent,
      MutableNode mutableNode) {
    super(space, parent, mutableNode);
    this.parent = requireNonNull(parent, "parent");
    this.link = ImmutableList.copyOf(requireNonNull(mutableNode.step, "step").keys);
  }
  // 当优化器判定在某个物化改写或查询中激活或使用了当前维表节点时，该方法负责递归、有序地激活整条连接路径链条。
  @Override void use(List<LatticeNode> usedNodes) {
    // 首先检查传入的“已被使用的节点列表” usedNodes 中是否已经包含了自己。
    // 如果已经包含，则说明该节点以及它的上层路径此前已经被激活过，直接跳过（防止在多列重复关联或图遍历时发生死循环）
    if (!usedNodes.contains(this)) {
      // 核心递归溯源点。如果自己尚未被标记使用，它会先调用其父节点的 use 方法。
      // 这意味着激活信号会沿着树干一路向上逆流，直到触发 LatticeRootNode（根节点/事实表）的激活。
      parent.use(usedNodes);
      usedNodes.add(this);
    }
  }
}
