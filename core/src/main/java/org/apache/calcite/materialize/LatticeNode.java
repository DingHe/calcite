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

import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.util.mapping.IntPair;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

import static java.util.Objects.requireNonNull;

/** Source relation of a lattice.
 *
 * <p>Relations form a tree; all relations except the root relation
 * (the fact table) have precisely one parent and an equi-join
 * condition on one or more pairs of columns linking to it. */
// 门用来描述和构建星型/雪花模型中表与表之间连接关系（Join）的树状拓扑结构
// LatticeNode 的核心作用是作为格子模型树（Lattice Tree）中的结构节点，代表多表等值连接（Equi-Join）图中的一个关系流（Relation）。
// 在 Calcite 构筑的多维星型模型中，数据源被组织成一棵树。
// 根节点（Root Node）：通常是事实表（Fact Table）。它没有父节点，作为整棵多维拓扑树的起点。
// 非根节点（Child Node / Dimension Node）：属于维表（Dimension Table）。每一个维表节点有且仅有一个父节点，并且通过一个或多个列的等值连接条件（Equi-join condition）与父节点相连。
// 在构建格子模型的过程中，Calcite 会先使用一个可变的临时结构 MutableNode 进行拓扑组装。
// 一旦树的形态确定，就会通过构造函数将其转化为不可变的 LatticeNode 树（或者其子类 LatticeChildNode），从而供优化器做线程安全的物化视图匹配与改写。
public abstract class LatticeNode {
  // 当前节点所封装的底层表元数据（对 LatticeTable 的引用）。
  // 代表这棵树中当前节点具体对应的物理表/逻辑表。
  public final LatticeTable table;
  // 当前表（包括它的所有子树节点）在整棵 Lattice 宽表模型中所占据的起始列索引（全局列偏移量）。
  // Calcite 会把整棵多维表树平铺（Flatten）看作一张包含所有维度的虚拟大宽表。startCol 标记了当前表在虚拟大宽表中列的起始边界。
  final int startCol;
  // 当前表（包括它的所有子树节点）在虚拟大宽表模型中所占据的结束列索引（不包含边界本身）。
  // 与 startCol 配合使用，当前节点及其子树所占用的总列数就是 endCol - startCol。
  final int endCol;
  // 当前表在查询上下文或 SQL 中的别名（可为空）
  // 当同一张维表在树中被多次连接时（例如“时间维表”既连接为“下单时间”又连接为“支付时间”），别名可以用来区分它们。
  public final @Nullable String alias;
  // 当前节点所拥有的所有直接子节点（下级维表）的列表
  // 使用了 Guava 的 ImmutableList 保证结构不可变。如果是雪花模型，维表后面还会延伸连接更外层的维表，这些都会存储在 children 中。
  private final ImmutableList<LatticeChildNode> children;
  // 当前节点及其整棵子树的规范化摘要字符串（Digest）。
  public final String digest;

  /** Creates a LatticeNode.
   *
   * <p>The {@code parent} and {@code mutableNode} arguments are used only
   * during construction. */
  // 主要职责是将一个在图构建阶段可以任意修改的、临时的可变节点（MutableNode），深度优先（DFS）递归地冻结并转化为一个不可变的、线程安全的运行时拓扑节点 LatticeNode，同时计算出整棵连接树的规范化唯一指纹摘要（digest）。
  // LatticeSpace space：晶格命名空间管理器。它是一个全局元数据上下文，内部维护了各种表、列的映射关系，并提供诸如获取简写表名（simpleName）、获取物理列名（fieldName）等工具方法。
  // @Nullable LatticeNode parent：当前正在构建的节点的父节点。如果当前节点是根节点（事实表），则 parent 为 null。如果当前节点是维表节点，则 parent 指向拉起它的上层节点。
  // MutableNode mutableNode：对应当前节点的可变源节点。它包含了尚未冻结的原始连接树结构、子节点列表以及中间状态。
  LatticeNode(LatticeSpace space, @Nullable LatticeNode parent, MutableNode mutableNode) {
    // 将可变节点中的基础元数据和列范围状态“快照化”到不可变对象中。
    this.table = requireNonNull(mutableNode.table);
    this.startCol = mutableNode.startCol;
    this.endCol = mutableNode.endCol;
    this.alias = mutableNode.alias;
    checkArgument(startCol >= 0);
    checkArgument(endCol > startCol);
    // 开始构建当前节点的 digest（结构指纹），并把“自己是通过父表的哪些列关联上来的”这一连接特征记录下来。
    final StringBuilder sb = new StringBuilder()
        // 首先在摘要最前面拼上当前表的名字（例如 EMPS）
        .append(space.simpleName(table));
    if (parent != null) {
      // 如果自己不是根节点（是一张维表），就意味着它是通过某种 Join 条件挂载到父表上的，必须把 Join 关系编入指纹。
      // 用冒号分隔表名与连接列
      sb.append(':');
      int i = 0;
      // mutableNode.step.keys：这里的 step 代表连接步长，keys 包含了具体的等值连接列对（IntPair，包含 source 和 target 列索引）。
      for (IntPair p : requireNonNull(mutableNode.step, "mutableNode.step").keys) {
        if (i++ > 0) {
          sb.append(",");
        }
        sb.append(space.fieldName(parent.table, p.source));
      }
    }
    if (mutableNode.children.isEmpty()) {
      // 情况 A：没有子节点（叶子节点）
      this.children = ImmutableList.of();
    } else {
      // 情况 B：存在子节点（拥有下一级维表分支）
      sb.append(" (");
      final ImmutableList.Builder<LatticeChildNode> b = ImmutableList.builder();
      int i = 0;
      for (MutableNode mutableChild : mutableNode.children) {
        if (i++ > 0) {
          sb.append(' ');
        }
        @SuppressWarnings({"argument.type.incompatible", "assignment.type.incompatible"})
        final @Initialized LatticeChildNode node =
            new LatticeChildNode(space, this, mutableChild);
        sb.append(node.digest);
        b.add(node);
      }
      this.children = b.build();
      sb.append(")");
    }
    // 最终指纹固化
    this.digest = sb.toString();

  }

  @Override public String toString() {
    return digest;
  }
  // 快捷获取当前节点底层的 Calcite 优化器标准表对象（RelOptTable）
  public RelOptTable relOptTable() {
    return table.t;
  }
  // 用于在晶格图的遍历或优化改写激活时，通知节点它正在被使用，并将自身或相关节点收集到 usedNodes 列表中
  abstract void use(List<LatticeNode> usedNodes);
  // 将整棵层次分明的树状结构平铺展开（展平）成一个线性的列表。
  void flattenTo(ImmutableList.Builder<LatticeNode> builder) {
    builder.add(this);
    for (LatticeChildNode child : children) {
      child.flattenTo(builder);
    }
  }
  // 递归探测并推导从根节点（事实表）出发，到达树中任何一个维表节点的所有连接路径（Paths）。
  // 基于典型的回溯/深度优先搜索算法
  void createPathsRecurse(LatticeSpace space, List<Step> steps,
      List<Path> paths) {
    // 将当前已经走过的步长集合 steps 注册到空间管理器中，生成一条有效的路径（Path）并放入全局结果集 paths。
    paths.add(space.addPath(steps));
    // 深度向下探索：遍历所有的子节点
    for (LatticeChildNode child : children) {
      steps.add(space.addEdge(table, child.table, child.link));
      child.createPathsRecurse(space, steps, paths);
      steps.remove(steps.size() - 1);
    }
  }

}
