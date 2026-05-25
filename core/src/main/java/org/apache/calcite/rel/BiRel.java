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

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.runtime.FlatLists;

import java.util.List;

/**
 * Abstract base class for relational expressions with a two inputs.
 *
 * <p>It is not required that two-input relational expressions use this
 * class as a base class. However, default implementations of methods make life
 * easier.
 */
// 在关系代数和 SQL 抽象语法树（AST）中，绝大多数算子要么只有一个输入（如 Filter, Project，对应 SingleRel），要么需要将两个数据源结合起来处理。
// 核心职责是：
// 统一双输入规范：它显式地在代码层面锁定了“左子节点（left）”和“右子节点（right）”的物理结构，并为上层屏蔽了处理多子节点列表的复杂性。
// 简化子类开发：正如它的 JavaDoc 所言，Calcite 并不强制要求所有双输入算子必须继承它，但继承它可以直接免费获得诸如“子节点遍历（childrenAccept）”、“节点替换（replaceInput）”以及“计划打印（explainTerms）”的默认完美实现，极大减轻了开发自定义数据源适配器时的负担。
public abstract class BiRel extends AbstractRelNode {
  //当前二元算子的左侧输入子节点（左表/左流）
  // 代表关系代数左树的根节点。例如在 SELECT * FROM A JOIN B 中，left 指向的就是代表 A 表的数据流算子。
  // 在 Hash Join 中，左表通常被用作探测表（Probe Table）或根据代价模型决定作为随动表。
  protected RelNode left;
  protected RelNode right; //右节点

  protected BiRel(
      RelOptCluster cluster, RelTraitSet traitSet, RelNode left,
      RelNode right) {
    super(cluster, traitSet);
    this.left = left;
    this.right = right;
  }

  @Override public void childrenAccept(RelVisitor visitor) {
    visitor.visit(left, 0, this);
    visitor.visit(right, 1, this);
  }

  @Override public List<RelNode> getInputs() {
    return FlatLists.of(left, right);
  }

  public RelNode getLeft() {
    return left;
  }

  public RelNode getRight() {
    return right;
  }

  @Override public void replaceInput(
      int ordinalInParent,
      RelNode p) {
    switch (ordinalInParent) {
    case 0:
      this.left = p;
      break;
    case 1:
      this.right = p;
      break;
    default:
      throw new IndexOutOfBoundsException("Input " + ordinalInParent);
    }
    recomputeDigest();
  }

  @Override public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .input("left", left)
        .input("right", right);
  }
}
