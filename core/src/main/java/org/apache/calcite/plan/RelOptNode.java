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

import org.apache.calcite.rel.type.RelDataType;

import java.util.List;

/**
 * Node in a planner.
 */
// RelOptNode 是 “关系优化器节点（Relational Optimization Node）” 的缩写。
// 在 Calcite 内核中，它是所有参与成本优化、等价变形的关系表达式节点（Relational Expression）的顶级始祖接口。
// RelOptNode 的核心使命是：抹平物理算子与逻辑算子的差异，站在优化器（Cost-Based Optimizer, CBO）的全局视角，抽象出节点之间相互连接、识别、比对、归属所需的最小通用核心拓扑特征。
// 无论是基于规则的优化器（HepPlanner）还是基于成本的 Volcano 优化器（VolcanoPlanner），优化过程本质上都是把一棵最初由 AST 翻译过来的“关系算子树”，
// 塞进一个由等价算子集合（RelSet / RelSubset）密织成的巨型有向无环图（DAG）中。
// 优化器在图里反复横跳、应用规则（RelOptRule）时，根本不需要关心某个节点具体是做过滤还是做投影，
// 它只需要调用 RelOptNode 提供的这些核心拓扑与元数据方法，来完成图的裁剪、等价替换和最优路径搜索。
public interface RelOptNode {
  /**
   * Returns the ID of this relational expression, unique among all relational
   * expressions created since the server was started.
   * @return Unique ID
   */
  // 返回该关系表达式的唯一 ID。这个 ID 在整个 Calcite 进程（Server）启动后的整个生命周期内是全局绝对唯一的。
  // 快速哈希与判等：优化器在处理成千上万个算子形变时，需要极其频繁地将节点放入 Set 或 Map 中。比较两个对象的指针或者字符串极慢，而一个全局唯一的自增整型 id 允许优化器以 $O(1)$ 的极致速度进行哈希寻址和去重
  int getId();

  /**
   * Returns a string which concisely describes the definition of this
   * relational expression. Two relational expressions are equivalent if
   * their digests and {@link #getRowType()} (except the field names) are the same.
   * digests和rowType相等则认为两节点相等
   * <p>The digest does not contain the relational expression's identity --
   * that would prevent similar relational expressions from ever comparing
   * equal -- but does include the identity of children (on the assumption
   * that children have already been normalized).
   *
   * <p>If you want a descriptive string which contains the identity, call
   * {@link Object#toString()}, which always returns "rel#{id}:{digest}".
   *
   * @return Digest string of this {@code RelNode}
   */
  // 返回一个能简洁描述该关系表达式定义的摘要字符串（Digest）。
  // 等价性铁律：在 Calcite 中，两个关系表达式如果它们的 Digest 完全相同，且它们的 getRowType()（行数据类型，不计字段名）也完全相同，
  // 优化器就判定这两个节点在语义上是绝对等价（Equivalent）的。
  String getDigest();

  /**
   * Retrieves this RelNode's traits. Note that although the RelTraitSet
   * returned is modifiable, it <b>must not</b> be modified during
   * optimization. It is legal to modify the traits of a RelNode before or
   * after optimization, although doing so could render a tree of RelNodes
   * unimplementable. If a RelNode's traits need to be modified during
   * optimization, clone the RelNode and change the clone's traits.
   * @return this RelNode's trait set
   */
  // 获取该关系节点当前所绑定的特征集合（Trait Set）。
  // 如果需要在优化过程中改变算子的特征（例如将一个逻辑算子改写为分布式物理算子），正确的做法是：克隆（Clone）该节点，并在克隆体上修改物理特征。
  // 什么是特征（Trait）：
  // Calcite 傲视全行业的动态物理改写核心。特征包括但不限于：
  // Convention（约定度）：它是逻辑节点（LOGICAL）、内存阵列迭代节点（ENUMERABLE）还是 Flink 流式物理算子（FLINK_PHYSICAL）？
  // Collation（排序列）：当前节点吐出的数据，是否已经天然按照某个字段有序（如 ORDER BY 或 Index Scan 产生的物理有序）？
  RelTraitSet getTraitSet();

  // TODO: We don't want to require that nodes have very detailed row type. It
  // may not even be known at planning time.
  // 获取当前关系节点输出行的强类型数据结构描述（Row Type）
  RelDataType getRowType();

  /**
   * Returns a string which describes the relational expression and, unlike
   * {@link #getDigest()}, also includes the identity. Typically returns
   * "rel#{id}:{digest}".
   * 返回格式rel#{id}:{digest}
   * @return String which describes the relational expression and, unlike
   *   {@link #getDigest()}, also includes the identity
   */
  // 返回该关系表达式的描述性字符串。
  @Deprecated // to be removed before 2.0
  String getDescription();

  /**
   * Returns an array of this relational expression's inputs. If there are no
   * inputs, returns an empty list, not {@code null}.
   * @return Array of this relational expression's inputs
   */
  // 返回当前关系表达式的所有物理输入节点（子节点，Inputs）的数组列表。
  // 如果当前节点是一个叶子节点（例如 LogicalTableScan 物理表扫描，它不需要任何输入），该方法必须返回一个空列表（Collections.emptyList()），而绝对不允许返回 null。
  List<? extends RelOptNode> getInputs();

  /**
   * Returns the cluster this relational expression belongs to.
   * @return cluster
   */
  // 返回当前关系表达式所归属的优化器集群环境上下文（RelOptCluster）
  RelOptCluster getCluster();
}
