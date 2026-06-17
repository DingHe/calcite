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
package org.apache.calcite.sql2rel;

import org.apache.calcite.rel.RelHomogeneousShuttle;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexSubQuery;

import org.checkerframework.checker.initialization.qual.NotOnlyInitialized;
import org.checkerframework.checker.initialization.qual.UnderInitialization;

/**
 * Shuttle that finds references to a given {@link CorrelationId} within a tree
 * of {@link RelNode}s.
 */
// 非常经典的双重遍历器（Dual-Shuttle）设计。它结合了关系代数算子树（RelNode）与行表达式树（RexNode）的遍历，
// 专门用于在整棵算子树及其嵌套的子查询中寻找、拦截、甚至重写对相关变量（CorrelationId）的字段访问（RexFieldAccess）。
// 穿透整个算子树及其内部嵌套的子查询，精准捕捉并处理所有类似于 $cor0.field_X 的表达式。
// 在 Calcite 中，SQL 树是由两种截然不同的节点交织而成的：
// RelNode（关系代数算子，如 Project、Filter），它们构成主干树。
// RexNode（行级标量表达式，如 a + b、或者相关变量字段访问 RexFieldAccess），它们寄生在 RelNode 内部。
// RexSubQuery（子查询），它作为一种特殊的 RexNode 寄生在表达式里，但它的内部又包含了一整棵全新的 RelNode 算子树。
// 如果只用普通的 RelShuttle，它只能遍历算子，碰不到算子内部的表达式；如果只用普通的 RexShuttle，它一旦碰到子查询，就无法深入到子查询内部的算子树中。
// orrelationReferenceFinder 作为一个 抽象类，通过内部嵌套一个 MyRexVisitor，完美实现了 RelNode -> RexNode -> RexSubQuery -> RelNode 的无限穿透循环遍历。
// 它暴露了一个抽象方法 handle，允许子类在捕获到相关变量访问时，自定义处理逻辑（例如在字段裁剪时记录索引，或者在重写树时替换变量名称）。
// CorrelationReferenceFinder (RelShuttle) ──负责扫描大树干──► [ 算子层: Filter/Project ]
//                                                                       │
//     ▲                                                           next.accept(rexVisitor)
//     │                                                                 │
//     │                                                                 ▼
//     │                                                    MyRexVisitor (RexShuttle) ──负责扫描行表达式
//     │                                                                 │
//     │                                                          遇到 RexSubQuery?
//     │                                                                 │
//     └─────────── subQuery.rel.accept(finder) ─────────────────────────┘
public abstract class CorrelationReferenceFinder extends RelHomogeneousShuttle {
  // 内部表达式遍历器 MyRexVisitor 的实例。
  // 当外部类 CorrelationReferenceFinder（它本身是个 RelShuttle）遍历到某个算子节点时，它会把遍历表达式的接力棒交回给这个 rexVisitor，由它去盘点算子内部的 RexNode 表达式。
  // 类型校验框架注解，用于处理和内部类相互持有引用时的初始化顺序。
  @NotOnlyInitialized
  private final MyRexVisitor rexVisitor;

  /** Creates CorrelationReferenceFinder. */
  protected CorrelationReferenceFinder() {
    rexVisitor = new MyRexVisitor(this);
  }
  // 留给子类实现的终极回调核心。
  // 当遍历器在整棵树的任何一个角落（不管是当前层还是深层子查询内），只要抓到某个表达式正在访问相关变量（比如 $cor0.dept_id），就会立刻触发此方法。
  protected abstract RexNode handle(RexFieldAccess fieldAccess);

  @Override public RelNode visit(RelNode other) {
    // 首先按照标准的 RelShuttle 路由，递归让当前算子的所有下游子算子（Inputs）先接受当前 Finder 的洗礼，返回重构或遍历后的新算子 next
    RelNode next = super.visit(other);
    // 关键交接点。当前算子层级的树干遍历完后，调用 accept(rexVisitor)。
    // 这会强行让当前算子把其内部包含的所有行表达式（如 Project 的 projects 列表、Filter 的 condition 条件）全部暴露出来，交给 rexVisitor 去进行微观表达式级的扫描。
    return next.accept(rexVisitor);
  }

  /**
   * Replaces alternative names of correlation variable to its canonical name.
   */
  private static class MyRexVisitor extends RexShuttle {
    // 持有外层包装类 CorrelationReferenceFinder 的引用。
    @NotOnlyInitialized
    private final CorrelationReferenceFinder finder;

    private MyRexVisitor(@UnderInitialization CorrelationReferenceFinder finder) {
      this.finder = finder;
    }

    @Override public RexNode visitFieldAccess(RexFieldAccess fieldAccess) {
      // 拦截形如 对象.字段 的表达式。
      // 检查被访问的宿主对象 fieldAccess.getReferenceExpr() 是不是一个相关变量 RexCorrelVariable（即判断它是不是 $cor0 这样的外层化身）。
      // 如果是，说明成功捕获目标！立刻调头去调用外层的 finder.handle(fieldAccess)，并将外层重写或处理后的新表达式直接作为当前节点的返回值。
      if (fieldAccess.getReferenceExpr() instanceof RexCorrelVariable) {
        return finder.handle(fieldAccess);
      }
      return super.visitFieldAccess(fieldAccess);
    }
    // 实现穿透子查询（SubQuery）的破壁神技。
    @Override public RexNode visitSubQuery(RexSubQuery subQuery) {
      final RelNode r = subQuery.rel.accept(finder); // look inside sub-queries
      // 把子查询内部包裹的那棵关系代数算子树 subQuery.rel 剥离出来，重新调用外层的 finder（即 CorrelationReferenceFinder 本身） 去遍历它
      if (r != subQuery.rel) {
        subQuery = subQuery.clone(r);
      }
      return super.visitSubQuery(subQuery);
    }
  }
}
