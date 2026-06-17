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
package org.apache.calcite.rex;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Default implementation of {@link RexVisitor}, which visits each node but does
 * nothing while it's there.
 * @param <R> Return type from each {@code visitXxx} method.
 */
// Apache Calcite 中用于处理行表达式（Row Expressions，即 RexNode）的一个极其经典且核心的基类。
// 在 Calcite 中，RexNode 代表 SQL 中的表达式、函数调用或过滤条件（如 a.id = 10 或 price * 1.08）。这些表达式在内存中通常被组织为一棵 表达式抽象语法树（Expression AST）。
// RexVisitorImpl 是接口 RexVisitor 的默认骨架实现（Default Implementation）。它的核心设计哲学是：“只负责全面走通/遍历整棵表达式树，但在到达节点时不执行任何实质业务逻辑（默认返回 null）”。
public class RexVisitorImpl<@Nullable R> implements RexVisitor<R> {
  //~ Instance fields --------------------------------------------------------
  // 控制遍历的纵深策略开关（是否深层递归）。
  // 如果 deep = true：当遇到复合节点（如函数调用 RexCall、窗口函数 RexOver）时，访问者会自动继续向下遍历它里面的所有子节点、操作数或参数。
  // 如果 deep = false：访问者到达复合节点时就会立刻止步（直接返回 null），不会去触碰它的叶子节点。
  protected final boolean deep;

  //~ Constructors -----------------------------------------------------------

  protected RexVisitorImpl(boolean deep) {
    this.deep = deep;
  }

  //~ Methods ----------------------------------------------------------------
  // 第一类：原子叶子节点（默认不做任何事，原位返回 null）
  @Override public R visitInputRef(RexInputRef inputRef) {
    return null;
  }

  @Override public R visitLocalRef(RexLocalRef localRef) {
    return null;
  }

  @Override public R visitLiteral(RexLiteral literal) {
    return null;
  }

  @Override public R visitOver(RexOver over) {
    R r = visitCall(over);
    if (!deep) {
      return null;
    }
    final RexWindow window = over.getWindow();
    for (RexFieldCollation orderKey : window.orderKeys) {
      orderKey.left.accept(this);
    }
    visitEach(window.partitionKeys);
    window.getLowerBound().accept(this);
    window.getUpperBound().accept(this);
    return r;
  }

  @Override public R visitCorrelVariable(RexCorrelVariable correlVariable) {
    return null;
  }
  // 第二类：复合分支节点（包含核心的递归路由逻辑）
  // 最核心的分支遍历方法。 负责访问所有的操作符和函数调用（如 +, -, AND, OR, SUBSTRING）。
  @Override public R visitCall(RexCall call) {
    if (!deep) {
      return null;
    }

    R r = null;
    for (RexNode operand : call.operands) {
      r = operand.accept(this);
    }
    return r;
  }

  @Override public R visitDynamicParam(RexDynamicParam dynamicParam) {
    return null;
  }

  @Override public R visitRangeRef(RexRangeRef rangeRef) {
    return null;
  }

  @Override public R visitFieldAccess(RexFieldAccess fieldAccess) {
    if (!deep) {
      return null;
    }
    final RexNode expr = fieldAccess.getReferenceExpr();
    return expr.accept(this);
  }

  @Override public R visitSubQuery(RexSubQuery subQuery) {
    if (!deep) {
      return null;
    }

    R r = null;
    for (RexNode operand : subQuery.operands) {
      r = operand.accept(this);
    }
    return r;
  }

  @Override public R visitTableInputRef(RexTableInputRef ref) {
    return null;
  }

  @Override public R visitPatternFieldRef(RexPatternFieldRef fieldRef) {
    return null;
  }

  @Override public R visitLambda(RexLambda lambda) {
    return null;
  }

  @Override public R visitLambdaRef(RexLambdaRef lambdaRef) {
    return null;
  }

  /**
   * Visits an array of expressions, returning the logical 'and' of their
   * results.
   *
   * <p>If any of them returns false, returns false immediately; if they all
   * return true, returns true.
   *
   * @see #visitArrayOr
   * @see RexShuttle#visitArray
   */
  // 第三类：公共静态工具方法（用于短路逻辑评估）
  // Calcite 在类中特意留了两个静态工具方法，专门用于服务返回值为 Boolean 的特定 Visitor（例如：检查表达式是否安全、是否包含不可确定性函数等场景）。
  public static boolean visitArrayAnd(
      RexVisitor<Boolean> visitor,
      List<RexNode> exprs) {
    for (RexNode expr : exprs) {
      final boolean b = expr.accept(visitor);
      if (!b) {
        return false;
      }
    }
    return true;
  }

  /**
   * Visits an array of expressions, returning the logical 'or' of their
   * results.
   *
   * <p>If any of them returns true, returns true immediately; if they all
   * return false, returns false.
   *
   * @see #visitArrayAnd
   * @see RexShuttle#visitArray
   */
  public static boolean visitArrayOr(
      RexVisitor<Boolean> visitor,
      List<RexNode> exprs) {
    for (RexNode expr : exprs) {
      final boolean b = expr.accept(visitor);
      if (b) {
        return true;
      }
    }
    return false;
  }
}
