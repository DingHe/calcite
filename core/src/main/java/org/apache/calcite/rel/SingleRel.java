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
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;

import com.google.common.collect.ImmutableList;

import java.util.List;

/**
 * Abstract base class for relational expressions with a single input.
 *
 * <p>It is not required that single-input relational expressions use this
 * class as a base class. However, default implementations of methods make life
 * easier.
 */
// SingleRel 是 Apache Calcite 项目中一个非常实用、高频使用的抽象基类。
// 它继承自 AbstractRelNode，专门用来为一元关系运算符（即只有一个输入节点的算子）提供骨架实现。
// 在 SQL 和关系代数中，有大量的算子只需要处理一个上游的数据流。例如：
// Filter（过滤）：基于一个输入表进行条件筛选。
// Project（投影）：基于一个输入表进行列的修剪和计算。
// Sort / Limit（排序/限流）：对一个输入流的数据进行排序或截取。
// Window（窗口计算）：在一个输入流上附加窗口函数。
public abstract class SingleRel extends AbstractRelNode {
  //~ Instance fields --------------------------------------------------------
  // 当前关系算子的唯一输入节点（子算子）。
  // 使用 protected 修饰，方便子类（如 LogicalProject、LogicalFilter）直接访问和读取这个上游节点。
  protected RelNode input;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a <code>SingleRel</code>.
   *
   * @param cluster Cluster this relational expression belongs to
   * @param input   Input relational expression
   */
  protected SingleRel(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode input) {
    super(cluster, traits);
    this.input = input;
  }

  //~ Methods ----------------------------------------------------------------
  // 便捷获取当前算子的唯一输入节点。
  public RelNode getInput() {
    return input;
  }
  // 覆盖父类方法，将唯一的 input 节点包装成列表返回。
  @Override public List<RelNode> getInputs() {
    return ImmutableList.of(input);
  }
  // 估算当前算子输出的数据行数（Row Count）
  // 它的基类 AbstractRelNode 默认死板地返回 1.0。而 SingleRel 完美重写了这一逻辑，默认直接返回 mq.getRowCount(input)（即直接沿用上游子节点的行数
  @Override public double estimateRowCount(RelMetadataQuery mq) {
    // Not necessarily correct, but a better default than AbstractRelNode's 1.0
    return mq.getRowCount(input);
  }
  // 实现访问者模式，让传入的遍历器（RelVisitor）访问其子节点。
  @Override public void childrenAccept(RelVisitor visitor) {
    visitor.visit(input, 0, this);
  }
  // 收集、描述当前节点独有的属性，用于生成执行计划或摘要。
  @Override public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .input("input", getInput());
  }

  @Override public void replaceInput(
      int ordinalInParent,
      RelNode rel) {
    assert ordinalInParent == 0;
    this.input = rel;
    recomputeDigest();
  }
  // 默认的数据行类型（Schema）推导逻辑。
  @Override protected RelDataType deriveRowType() {
    return input.getRowType();
  }
}
