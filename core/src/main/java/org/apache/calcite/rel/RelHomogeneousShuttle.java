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
 * Visits all the relations in a homogeneous way: always redirects calls to
 * {@code accept(RelNode)}.
 */
// 要想理解这个类，首先需要看懂它的名字：Homogeneous（同质的、统一的）。
// 消除算子的“异质性”
// 在标准的 RelShuttleImpl 中，不同类型的算子会有不同的特殊照顾。例如：
//碰到 LogicalProject 默认去访问它的第 0 个孩子。
//碰到 LogicalJoin 默认去遍历所有的左右孩子。
//碰到 TableScan 直接触底返回，不进行任何下钻。
// 这种设计被称为“异质处理”。但是在很多实际的架构场景中，开发者编写自定义穿梭器时，不希望针对每一种特定算子去写一套特定逻辑，
// 而是希望将所有算子一视同仁，当成最普通的、抽象的 RelNode 统一拦截并处理。
// RelHomogeneousShuttle 拦截了所有 16 个具体算子的 visit 方法，
// 并且做了一件极其统一的事：把所有具体算子全部向上转型（Upcast）为 RelNode，然后强行调用 visit(RelNode other) 方法。
// 这样一来，开发者如果继承 RelHomogeneousShuttle，只需要重写唯一一个 visit(RelNode other) 方法，整棵树上所有的算子（不论是 Scan、Project 还是 Join）在被穿梭访问时，都会被集中路由、拦截到你写的这一个方法里。
public class RelHomogeneousShuttle extends RelShuttleImpl {
  @Override public RelNode visit(LogicalAggregate aggregate) {
    return visit((RelNode) aggregate);
  }

  @Override public RelNode visit(LogicalMatch match) {
    return visit((RelNode) match);
  }

  @Override public RelNode visit(TableScan scan) {
    return visit((RelNode) scan);
  }

  @Override public RelNode visit(TableFunctionScan scan) {
    return visit((RelNode) scan);
  }

  @Override public RelNode visit(LogicalValues values) {
    return visit((RelNode) values);
  }

  @Override public RelNode visit(LogicalFilter filter) {
    return visit((RelNode) filter);
  }

  @Override public RelNode visit(LogicalProject project) {
    return visit((RelNode) project);
  }

  @Override public RelNode visit(LogicalJoin join) {
    return visit((RelNode) join);
  }

  @Override public RelNode visit(LogicalCorrelate correlate) {
    return visit((RelNode) correlate);
  }

  @Override public RelNode visit(LogicalUnion union) {
    return visit((RelNode) union);
  }

  @Override public RelNode visit(LogicalIntersect intersect) {
    return visit((RelNode) intersect);
  }

  @Override public RelNode visit(LogicalMinus minus) {
    return visit((RelNode) minus);
  }

  @Override public RelNode visit(LogicalSort sort) {
    return visit((RelNode) sort);
  }

  @Override public RelNode visit(LogicalExchange exchange) {
    return visit((RelNode) exchange);
  }

  @Override public RelNode visit(LogicalCalc calc) {
    return visit((RelNode) calc);
  }

  @Override public RelNode visit(LogicalTableModify modify) {
    return visit((RelNode) modify);
  }
}
