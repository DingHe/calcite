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

import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.AbstractRelNode;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.metadata.DelegatingMetadataRel;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

import static java.util.Objects.requireNonNull;

/**
 * HepRelVertex wraps a real {@link RelNode} as a vertex in a DAG representing
 * the entire query expression.
 */
// HepRelVertex 继承自 AbstractRelNode 并实现了 DelegatingMetadataRel 接口。
// 核心作用是：在 HepPlanner 维护的有向无环图（DAG）中，充当图的“顶点（Vertex）”，用来包裹和隔离真正的关系代数算子（RelNode）。
// 为什么要引入一个“顶点”包装层？
// 在启发式优化过程中，一个算子节点可能会被频繁地替换、重写（例如，一个 LogicalFilter 被优化规则重写为另一个加入了索引过滤的 PhysicalFilter）。
// 如果没有 HepRelVertex 这一层包装：
// 每次子节点发生改变，所有引用了该子节点的父节点都必须被迫跟着修改它们内部的 inputs 指针，这会导致全图级联更新，性能极差且极易出错。
// 引入 HepRelVertex 后：
// 所有的父节点引用的其实都是这个稳定的“窗口/外壳”（HepRelVertex）。
// 优化规则产生新的算子时，优化器只需要把这个外壳内部的指针指向新算子即可。对父节点而言，它们持有的 HepRelVertex 引用完全不发生改变，完美实现了局部算子替换的隔离性。
public class HepRelVertex extends AbstractRelNode implements DelegatingMetadataRel {
  //~ Instance fields --------------------------------------------------------

  /**
   * Wrapped rel currently chosen for implementation of expression.
   */
  // 当前被包裹的真实关系表达式算子。
  private RelNode currentRel;

  //~ Constructors -----------------------------------------------------------

  HepRelVertex(RelNode rel) {
    super(rel.getCluster(), rel.getTraitSet());
    currentRel = requireNonNull(rel, "rel");
    // 进行强防线校验：禁止顶点套顶点。顶点只能包裹真实的业务算子，防止出现无穷委派的“洋葱圈”结构。
    checkArgument(!(rel instanceof HepRelVertex));
  }

  //~ Methods ----------------------------------------------------------------
  // 打印或解释执行计划树。
  @Override public void explain(RelWriter pw) {
    currentRel.explain(pw);
  }

  @Override public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    assert traitSet.equals(this.traitSet);
    assert inputs.equals(this.getInputs());
    return this;
  }

  @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
      RelMetadataQuery mq) {
    // HepRelMetadataProvider is supposed to intercept this
    // and redirect to the real rels. But sometimes it doesn't.
    return planner.getCostFactory().makeTinyCost();
  }

  @Override public double estimateRowCount(RelMetadataQuery mq) {
    return mq.getRowCount(currentRel);
  }

  @Override protected RelDataType deriveRowType() {
    return currentRel.getRowType();
  }

  /**
   * Replaces the implementation for this expression with a new one.
   *
   * @param newRel new expression
   */
  void replaceRel(RelNode newRel) {
    currentRel = newRel;
  }

  /**
   * Returns current implementation chosen for this vertex.
   */
  public RelNode getCurrentRel() {
    return currentRel;
  }

  @Override public RelNode stripped() {
    return currentRel;
  }

  /**
   * Returns {@link RelNode} for metadata.
   */
  @Override public RelNode getMetadataDelegateRel() {
    return currentRel;
  }

  @Override public boolean deepEquals(@Nullable Object obj) {
    return this == obj
        || (obj instanceof HepRelVertex
            && currentRel == ((HepRelVertex) obj).currentRel);
  }

  @Override public int deepHashCode() {
    return currentRel.getId();
  }

  @Override public String getDigest() {
    return "HepRelVertex(" + currentRel + ')';
  }
}
