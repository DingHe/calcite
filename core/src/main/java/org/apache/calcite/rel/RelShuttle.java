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

import org.apache.calcite.rel.core.TableFunctionScan;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalCalc;
import org.apache.calcite.rel.logical.LogicalCorrelate;
import org.apache.calcite.rel.logical.LogicalExchange;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalIntersect;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalMatch;
import org.apache.calcite.rel.logical.LogicalMinus;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.logical.LogicalTableModify;
import org.apache.calcite.rel.logical.LogicalUnion;
import org.apache.calcite.rel.logical.LogicalValues;

/**
 * Visitor that has methods for the common logical relational expressions.
 */
// Calcite 遍历、转换和重构关系代数树（RelNode AST）的重要基石
// RelShuttle 接口本质上是 访问者模式（Visitor Pattern） 在关系代数节点（RelNode）树遍历中的一种高级变体实现。
// 为什么叫 Shuttle（穿梭器）而不是 Visitor（访问者）？
// 在 Calcite 中，普通的 RelVisitor 通常只用于“只读”遍历（例如打印执行计划、统计节点数量），它的 visit 方法不返回值（或者说只用于触发内部状态改变）。
// 而 RelShuttle（穿梭器）的设计目标是“边穿梭，边重构”。它的每一个 visit 方法都强制返回一个 RelNode。这意味着你在穿梭遍历整棵 AST 树时，可以动态地用一个新修改的算子节点去替换掉原本的旧节点，从而实现整棵关系代数树的局部重写或整体转换。
// 优化器规则（Planner Rules）：在复杂的优化阶段，利用 Shuttle 自顶向下或自底向上地重写某些算子。
// 节点替换与去关联（De-correlation）：将逻辑算子（如 LogicalProject）重写或替换为特定存储引擎的物理算子，或者用来在去关联化时重置变量。
// 字段裁剪（Field Trimming）：虽然我们之前看的 RelFieldTrimmer 采用了反射多方法，但很多轻量级的字段、索引重写也经常通过继承 RelShuttle 的基类（如 RelHomogeneousShuttle 及其子类）来完成。
public interface RelShuttle {
  RelNode visit(TableScan scan);

  RelNode visit(TableFunctionScan scan);

  RelNode visit(LogicalValues values);

  RelNode visit(LogicalFilter filter);

  RelNode visit(LogicalCalc calc);

  RelNode visit(LogicalProject project);

  RelNode visit(LogicalJoin join);

  RelNode visit(LogicalCorrelate correlate);

  RelNode visit(LogicalUnion union);

  RelNode visit(LogicalIntersect intersect);

  RelNode visit(LogicalMinus minus);

  RelNode visit(LogicalAggregate aggregate);

  RelNode visit(LogicalMatch match);

  RelNode visit(LogicalSort sort);

  RelNode visit(LogicalExchange exchange);

  RelNode visit(LogicalTableModify modify);

  RelNode visit(RelNode other);
}
