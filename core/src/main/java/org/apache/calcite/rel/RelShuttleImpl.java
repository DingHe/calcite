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

import org.apache.calcite.linq4j.Ord;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Basic implementation of {@link RelShuttle} that calls
 * {@link RelNode#accept(RelShuttle)} on each child, and
 * {@link RelNode#copy(org.apache.calcite.plan.RelTraitSet, java.util.List)} if
 * any children change.
 */
// 在实际开发中，如果直接实现 RelShuttle 接口，你必须手动编写处理 17 个方法的模板代码，并自己控制向子节点递归的逻辑。
// 而继承 RelShuttleImpl 后，你只需要重写你真正关心的几个算子方法，其余算子会自动沿用默认的深度优先遍历逻辑。
// RelShuttleImpl 承载了关系代数树在遍历和重构时的行为规范和通用递归传导逻辑。它的核心设计哲学可以概括为两点：
// 自动维护深度优先遍历（DFS）
// 当穿梭器（Shuttle）到达某个父节点时，RelShuttleImpl 默认会去自动调用其子节点的 accept 方法，使得遍历能够沿着代数树自顶向下、由浅入深地传播。
// 写时复制（Copy-on-Write）与树的不可变性
// Calcite 的 RelNode 树在设计上倾向于是不可变（Immutable）的。当一个子节点被重写改动后，你不能直接把新子节点强塞给旧父节点。
// RelShuttleImpl 帮我们规范了这一逻辑：当检测到任何一个子节点发生实质性改变时，它会自动调用父节点的 parent.copy(...) 方法，克隆生成一个挂载了新子节点的、全新的父节点对象，并逐层向上返回。 这种自底向上的克隆链路，保证了整棵树的拓扑结构能正确刷新。
public class RelShuttleImpl implements RelShuttle {
  // 运行时的节点依赖栈（通常作为上下文记录器）
  // 当遍历流向下钻访问子节点时，当前正在处理的父节点会被临时 .push() 压入栈顶；当子节点全部访问完毕、准备返回时，该父节点再被 .pop() 弹出。
  // 通过这个栈，任何子节点在自定义裁剪或重写时，都可以通过查看 stack 快速知道自己的“祖先是谁”、“长辈节点是谁”，从而做出符合上下文的优化决策。
  protected final Deque<RelNode> stack = new ArrayDeque<>();

  /**
   * Visits a particular child of a parent.
   */
  // 精准穿梭、清洗并转换某一个指定的子节点。
  // RelNode parent：当前正在被穿梭器（Shuttle）访问的父节点算子（例如 LogicalProject、LogicalJoin）
  // int i：当前正在处理的子节点在父节点输入列表（parent.getInputs()）中的索引下标位置（从 0 开始）。
  // RelNode child：当前需要下钻访问的、修改前的原始子节点（即 parent 的第 i 个孩子）。
  protected RelNode visitChild(RelNode parent, int i, RelNode child) {
    // 在穿梭器准备钻进子节点内部之前，将当前的 parent 节点推入运行时维护的临时双端队列 stack 的栈顶。
    stack.push(parent);
    try {
      // 触发多态路由，进入子节点的清洗链路。
      RelNode child2 = child.accept(this);
      if (child2 != child) {
        // 复制旧输入列表
        final List<RelNode> newInputs = new ArrayList<>(parent.getInputs());
        // 替换改动后的子节点
        newInputs.set(i, child2);
        // 传入原有的物理属性集（TraitSet）和刚刚洗牌替换后的 newInputs，在内存中克隆并实例化出一个全新的父节点对象并返回给更上层。
        return parent.copy(parent.getTraitSet(), newInputs);
      }
      return parent;
    } finally {
      stack.pop();
    }
  }
  // 批量处理多子节点算子（如 Join 有左、右两棵子树，Union 有多个输入分支）。
  protected RelNode visitChildren(RelNode rel) {
    for (Ord<RelNode> input : Ord.zip(rel.getInputs())) {
      rel = visitChild(rel, input.i, input.e);
    }
    return rel;
  }

  @Override public RelNode visit(LogicalAggregate aggregate) {
    return visitChild(aggregate, 0, aggregate.getInput());
  }

  @Override public RelNode visit(LogicalMatch match) {
    return visitChild(match, 0, match.getInput());
  }

  @Override public RelNode visit(TableScan scan) {
    return scan;
  }

  @Override public RelNode visit(TableFunctionScan scan) {
    return visitChildren(scan);
  }

  @Override public RelNode visit(LogicalValues values) {
    return values;
  }

  @Override public RelNode visit(LogicalFilter filter) {
    return visitChild(filter, 0, filter.getInput());
  }

  @Override public RelNode visit(LogicalCalc calc) {
    return visitChildren(calc);
  }

  @Override public RelNode visit(LogicalProject project) {
    return visitChild(project, 0, project.getInput());
  }

  @Override public RelNode visit(LogicalJoin join) {
    return visitChildren(join);
  }

  @Override public RelNode visit(LogicalCorrelate correlate) {
    return visitChildren(correlate);
  }

  @Override public RelNode visit(LogicalUnion union) {
    return visitChildren(union);
  }

  @Override public RelNode visit(LogicalIntersect intersect) {
    return visitChildren(intersect);
  }

  @Override public RelNode visit(LogicalMinus minus) {
    return visitChildren(minus);
  }

  @Override public RelNode visit(LogicalSort sort) {
    return visitChildren(sort);
  }

  @Override public RelNode visit(LogicalExchange exchange) {
    return visitChildren(exchange);
  }

  @Override public RelNode visit(LogicalTableModify modify) {
    return visitChildren(modify);
  }

  @Override public RelNode visit(RelNode other) {
    return visitChildren(other);
  }
}
