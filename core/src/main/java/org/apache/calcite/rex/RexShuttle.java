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

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Passes over a row-expression, calling a handler method for each node,
 * appropriate to the type of the node.
 * <p>Like {@link RexVisitor}, this is an instance of the
 * {@link org.apache.calcite.util.Glossary#VISITOR_PATTERN Visitor Pattern}. Use
 * <code> RexShuttle</code> if you would like your methods to return a
 * value.
 */
// 基于经典的访问者模式（Visitor Pattern）实现，专门用于在行表达式树（RexNode）中进行深度的遍历（Pass Over）、清洗与重写（Transformation/Rewrite）。
// 在 Calcite 中，SQL 的 WHERE 过滤条件、SELECT 投影计算、JOIN 的关联键等在逻辑计划阶段都会被表示为 RexNode（Row Expression，行表达式）构成的树状结构。
// 当优化器或校验器需要对表达式树进行修改时（例如：把 a + 1 统一改成 a + 2，或者把某个列引用 RexInputRef 的偏移索引全部加 1），就必须使用 RexShuttle。
// RexShuttle：实现了 RexVisitor<RexNode> 接口。它的所有 visitXxx 方法雷打不动地返回一个新的（或原有的）RexNode。它的定位是“表达式变型金刚”。
public class RexShuttle implements RexVisitor<RexNode> {
  //~ Methods ----------------------------------------------------------------
  // 遍历并重写窗口聚合函数表达式（例如 SUM(val) OVER (PARTITION BY ...)）。
  @Override public RexNode visitOver(RexOver over) {
    // 创建一个长度为 1 的布尔数组，作为跨方法传递的“修改标记”（Mutation Flag）。
    // 为什么要用数组？：因为在 Java 中，基本数据类型（如 boolean）是值传递，在方法间传递无法保存修改状态。通过将其包装在数组中，可以把它作为一个引用对象传给辅助方法（如 visitList）。
    // 一旦底层的任何一个子参数被重写，底层代码就会执行 update[0] = true，从而将修改信号“捎带”回当前方法。
    boolean[] update = {false};
    // 遍历并重写该窗口函数的内部参数列表（例如 SUM(val) 中的 val）。
    List<RexNode> clonedOperands = visitList(over.operands, update);
    // 遍历并重写该窗口函数的窗口规范定义（RexWindow）。
    // 调用专门处理窗口规范的 visitWindow 方法，去深层检查和重写类似 PARTITION BY, ORDER BY, ROWS BETWEEN... 这样的边界逻辑。返回的 window 是处理过后的窗口规范对象。
    RexWindow window = visitWindow(over.getWindow());
    // 判定当前这个 RexOver 节点自身是否需要被重写（重建）
    if (update[0] || (window != over.getWindow())) {
      // REVIEW jvs 8-Mar-2005:  This doesn't take into account
      // the fact that a rewrite may have changed the result type.
      // To do that, we would need to take a RexBuilder and
      // watch out for special operators like CAST and NEW where
      // the type is embedded in the original call.
      return new RexOver(
          over.getType(),
          over.getAggOperator(),
          clonedOperands,
          window,
          over.isDistinct(),
          over.ignoreNulls());
    } else {
      return over;
    }
  }
  // 专门用于遍历和重写窗口规范（RexWindow）的方法。它不仅延续了 Shuttle “按需浅拷贝”的优良传统，还在末尾融入了一段针对标准 SQL 语义的精妙优化。
  // 在 SQL 中，窗口规范（即 OVER (PARTITION BY... ORDER BY... ROWS/RANGE...)）是一个复合结构，这段代码的任务就是解构并检查其中的每一个组件。
  public RexWindow visitWindow(RexWindow window) {
    // 依然是一个长度为 1 的布尔数组，用来在接下来的多个批量遍历方法中充当“全局无绳信号枪”。
    boolean[] update = {false};
    // 遍历排序列（ORDER BY）
    // 顺次遍历窗口的排序列。如果某个排序列的内部表达式被重写，update[0] 会被自动标记为 true。
    List<RexFieldCollation> clonedOrderKeys =
        visitFieldCollations(window.orderKeys, update);
    // 遍历分区列（PARTITION BY）
    List<RexNode> clonedPartitionKeys =
        visitList(window.partitionKeys, update);
    // 遍历窗口边界（ROWS/RANGE BETWEEN ...）
    final RexWindowBound lowerBound = window.getLowerBound().accept(this);
    final RexWindowBound upperBound = window.getUpperBound().accept(this);
    // 判定整个窗口规范是否完美保持了原样。如果是，则直接返回原对象引用，避免发生任何内存分配。
    if (lowerBound == null
        || upperBound == null
        || !update[0]
        && lowerBound == window.getLowerBound()
        && upperBound == window.getUpperBound()) {
      return window;
    }
    // 在 SQL 中，ROWS 代表物理行，RANGE 代表逻辑值范围。
    boolean rows = window.isRows();
    // 代码首先判断：当前边界是不是 BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING（从无限往前到无限往后，即覆盖全分区）。
    // 数学等价性：如果一个窗口的边界是从分区的起点一直划到分区的终点，那么不管你是用 ROWS 计行数，还是用 RANGE 计数值，它们框选出来的数据集是绝对一模一样的（整个分区的全量数据）。
    // 优化选择：既然物理行和逻辑范围在此时完全等价，Calcite 的代码注释明确指出 "but we prefer 'RANGE'"（但我们更倾向于使用 RANGE）。因此，它强行将 rows = false。
    // 为什么倾向于 RANGE？：因为在物理执行和优化阶段，RANGE 类型的全量分区窗口更容易被优化器识别并转化为更高效的全局流式聚合或批处理连接（Hash / Merge Aggregation），从而免去一行行去数物理 Row 边界的运行时开销。
    if (lowerBound.isUnbounded() && lowerBound.isPreceding()
        && upperBound.isUnbounded() && upperBound.isFollowing()) {
      // RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
      //   is equivalent to
      // ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
      //   but we prefer "RANGE"
      rows = false;
    }
    return new RexWindow(
        clonedPartitionKeys,
        clonedOrderKeys,
        lowerBound,
        upperBound,
        rows,
        window.getExclude());
  }

  @Override public RexNode visitSubQuery(RexSubQuery subQuery) {
    boolean[] update = {false};
    List<RexNode> clonedOperands = visitList(subQuery.operands, update);
    if (update[0]) {
      return subQuery.clone(subQuery.getType(), clonedOperands);
    } else {
      return subQuery;
    }
  }

  @Override public RexNode visitTableInputRef(RexTableInputRef ref) {
    return ref;
  }

  @Override public RexNode visitPatternFieldRef(RexPatternFieldRef fieldRef) {
    return fieldRef;
  }

  @Override public RexNode visitCall(final RexCall call) {
    boolean[] update = {false};
    List<RexNode> clonedOperands = visitList(call.operands, update);
    if (update[0]) {
      // REVIEW jvs 8-Mar-2005:  This doesn't take into account
      // the fact that a rewrite may have changed the result type.
      // To do that, we would need to take a RexBuilder and
      // watch out for special operators like CAST and NEW where
      // the type is embedded in the original call.
      return call.clone(call.getType(), clonedOperands);
    } else {
      return call;
    }
  }

  /**
   * Visits each of an array of expressions and returns an array of the
   * results.
   *
   * @param exprs  Array of expressions
   * @param update If not null, sets this to true if any of the expressions
   *               was modified
   * @return Array of visited expressions
   */
  protected RexNode[] visitArray(RexNode[] exprs, boolean @Nullable [] update) {
    RexNode[] clonedOperands = new RexNode[exprs.length];
    for (int i = 0; i < exprs.length; i++) {
      RexNode operand = exprs[i];
      RexNode clonedOperand = operand.accept(this);
      if ((clonedOperand != operand) && (update != null)) {
        update[0] = true;
      }
      clonedOperands[i] = clonedOperand;
    }
    return clonedOperands;
  }

  /**
   * Visits each of a list of expressions and returns a list of the
   * results.
   *
   * @param exprs  List of expressions
   * @param update If not null, sets this to true if any of the expressions
   *               was modified
   * @return Array of visited expressions
   */
  // 批量遍历一个表达式列表（List<RexNode>），让列表中的每个表达式节点都接受当前 Shuttle 的洗礼，并同步监控、记录整个列表中是否有任何一个节点发生了改变。
  // List<? extends RexNode> exprs 传入的待遍历表达式列表。它使用了 Java 泛型的上界通配符（? extends RexNode），这意味着它可以接收 RexNode 的任何子类列表（例如 List<RexInputRef> 或 List<RexLiteral>），提升了方法的通用性。
  // boolean @Nullable [] update 一个可选的（通过 @Nullable 注解标识，允许为 null）、长度为 1 的布尔数组。它充当一个跨方法调用的“全局修改计数器”。
  protected List<RexNode> visitList(
      List<? extends RexNode> exprs, boolean @Nullable [] update) {
    ImmutableList.Builder<RexNode> clonedOperands = ImmutableList.builder();
    // 顺次取出传入列表中的每一个表达式节点（operand），准备进行深层遍历。
    for (RexNode operand : exprs) {
      RexNode clonedOperand = operand.accept(this);
      if ((clonedOperand != operand) && (update != null)) {
        update[0] = true;
      }
      clonedOperands.add(clonedOperand);
    }
    return clonedOperands.build();
  }

  /**
   * Visits each of a list of field collations and returns a list of the
   * results.
   *
   * @param collations List of field collations
   * @param update     If not null, sets this to true if any of the expressions
   *                   was modified
   * @return Array of visited field collations
   */
  protected List<RexFieldCollation> visitFieldCollations(
      List<RexFieldCollation> collations, boolean @Nullable [] update) {
    ImmutableList.Builder<RexFieldCollation> clonedOperands =
        ImmutableList.builder();
    for (RexFieldCollation collation : collations) {
      RexNode clonedOperand = collation.left.accept(this);
      if ((clonedOperand != collation.left) && (update != null)) {
        update[0] = true;
        collation =
            new RexFieldCollation(clonedOperand, requireNonNull(collation.right));
      }
      clonedOperands.add(collation);
    }
    return clonedOperands.build();
  }

  @Override public RexNode visitCorrelVariable(RexCorrelVariable variable) {
    return variable;
  }

  @Override public RexNode visitFieldAccess(RexFieldAccess fieldAccess) {
    RexNode before = fieldAccess.getReferenceExpr();
    RexNode after = before.accept(this);

    if (before == after) {
      return fieldAccess;
    } else {
      return new RexFieldAccess(
          after,
          fieldAccess.getField());
    }
  }

  @Override public RexNode visitInputRef(RexInputRef inputRef) {
    return inputRef;
  }

  @Override public RexNode visitLocalRef(RexLocalRef localRef) {
    return localRef;
  }
  //处理字面量，直接返回
  @Override public RexNode visitLiteral(RexLiteral literal) {
    return literal;
  }

  @Override public RexNode visitDynamicParam(RexDynamicParam dynamicParam) {
    return dynamicParam;
  }

  @Override public RexNode visitRangeRef(RexRangeRef rangeRef) {
    return rangeRef;
  }

  @Override public RexNode visitLambda(RexLambda lambda) {
    lambda.getExpression().accept(this);
    return lambda;
  }

  @Override public RexNode visitLambdaRef(RexLambdaRef lambdaRef) {
    return lambdaRef;
  }

  /**
   * Applies this shuttle to each expression in a list.
   *检查表达式是否有需要修改的地方
   * @return whether any of the expressions changed
   */
  public final <T extends @Nullable RexNode> boolean mutate(List<T> exprList) {
    int changeCount = 0;
    for (int i = 0; i < exprList.size(); i++) {
      T expr = exprList.get(i);
      T expr2 = (T) apply(expr); // Avoid NPE if expr is null
      if (expr != expr2) {
        ++changeCount;
        exprList.set(i, expr2);
      }
    }
    return changeCount > 0;
  }

  /**
   * Applies this shuttle to each expression in a list and returns the
   * resulting list. Does not modify the initial list.
   *
   * <p>Returns null if and only if {@code exprList} is null.
   */
  public final <T extends @Nullable RexNode> @PolyNull List<T> apply(@PolyNull List<T> exprList) {
    if (exprList == null) {
      return exprList;
    }
    final List<T> list2 = new ArrayList<>(exprList);
    if (mutate(list2)) {
      return list2;
    } else {
      return exprList;
    }
  }

  /**
   * Applies this shuttle to an expression, or returns null if the expression
   * is null.
   */
  public final @PolyNull RexNode apply(@PolyNull RexNode expr) {
    return (expr == null) ? expr : expr.accept(this);
  }
}
