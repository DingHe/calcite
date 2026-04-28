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
package org.apache.calcite.sql.validate;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.fun.SqlAbstractGroupFunction;
import org.apache.calcite.sql.util.SqlBasicVisitor;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Visitor that can find aggregate and windowed aggregate functions.
 *
 * @see AggFinder */
// 专门用于在 SQL 语法树（AST）中搜寻并识别聚合函数和窗口聚合函数。
// AggVisitor 的主要作用是探测和分类 SQL 表达式中的聚合行为。
// 在 SQL 验证阶段，系统需要知道一个表达式是否包含聚合函数（如 SUM, COUNT），以此来决定该查询是否需要执行 GROUP BY 逻辑，或者检查聚合函数是否被错误地嵌套（例如 SUM(AVG(x)) 通常是不允许的）。
// 够识别以下几种类型：
// 普通聚合函数：如 SUM(sal)。
// 窗口聚合函数：带 OVER 子句的函数，如 SUM(sal) OVER (partition by deptno)。
// 分组函数：如流处理中的窗口函数 TUMBLE、HOP 等。
// 分组辅助函数：如 TUMBLE_START。
abstract class AggVisitor extends SqlBasicVisitor<Void> {
  // 操作符表，用于查找函数和操作符的定义。
  // 在处理尚未完全解析的用户自定义函数（UDF）时，需要用它来查找该函数名是否对应一个聚合操作符。
  protected final SqlOperatorTable opTab;
  /** Whether to find windowed aggregates. */
  // 控制标志，是否寻找窗口聚合函数（带 OVER 子句的）。
  protected final boolean over;
  // 委派对象。
  // 如果设置了 delegate（通常是一个 AggFinder），当发现一个聚合函数后，会调用此委派对象去处理该函数的参数，这常用于检测聚合函数的非法嵌套。
  protected final @Nullable AggFinder delegate;
  /** Whether to find regular (non-windowed) aggregates. */
  // 控制标志，是否寻找普通的（非窗口）聚合函数。
  protected final boolean aggregate;
  /** Whether to find group functions (e.g. {@code TUMBLE})
   * or group auxiliary functions (e.g. {@code TUMBLE_START}). */
  // 控制标志，是否寻找分组函数（如 TUMBLE）或分组辅助函数（如 TUMBLE_START）。
  protected final boolean group;
  // 名称匹配器。
  // 用于在 opTab 中查找函数时决定是否对大小写敏感。
  protected final SqlNameMatcher nameMatcher;

  /**
   * Creates an AggVisitor.
   *
   * @param opTab Operator table
   * @param over Whether to find windowed function calls {@code agg(x) OVER
   *             windowSpec}
   * @param aggregate Whether to find non-windowed aggregate calls
   * @param group Whether to find group functions (e.g. {@code TUMBLE})
   * @param delegate Finder to which to delegate when processing the arguments
   * @param nameMatcher Whether to match the agg function names case-sensitively
   */
  AggVisitor(SqlOperatorTable opTab, boolean over, boolean aggregate,
      boolean group, @Nullable AggFinder delegate, SqlNameMatcher nameMatcher) {
    this.group = group;
    this.over = over;
    this.aggregate = aggregate;
    this.delegate = delegate;
    this.opTab = Objects.requireNonNull(opTab, "opTab");
    this.nameMatcher = Objects.requireNonNull(nameMatcher, "nameMatcher");
  }
  // 定义了识别聚合函数的逻辑规则：
  @Override public Void visit(SqlCall call) {
    final SqlOperator operator = call.getOperator();
    // If nested aggregates disallowed or found an aggregate at invalid level
    // 直接识别聚合操作符：
    // 排除 SqlAbstractGroupFunction（如 GROUPING）和 requiresOver（必须带 OVER 的函数）。
    if (operator.isAggregator()
        && !(operator instanceof SqlAbstractGroupFunction)
        && !operator.requiresOver()) {
      if (delegate != null) {
        return operator.acceptCall(delegate, call);
      }
      if (aggregate) {
        return found(call);
      }
    }
    // 识别分组函数：
    if (group && operator.isGroup()) {
      return found(call);
    }
    // User-defined function may not be resolved yet.
    // 处理未解析的用户自定义函数（UDF）：
    if (operator instanceof SqlFunction) {
      final SqlFunction sqlFunction = (SqlFunction) operator;
      // 如果 operator 是 SqlFunction 且是 UDF 类型，则通过 opTab 进行重载查找。
      if (sqlFunction.getFunctionType().isUserDefinedNotSpecificFunction()) {
        final List<SqlOperator> list = new ArrayList<>();
        final SqlIdentifier identifier = sqlFunction.getSqlIdentifier();
        if (identifier != null) {
          opTab.lookupOperatorOverloads(identifier,
              sqlFunction.getFunctionType(), SqlSyntax.FUNCTION, list,
              nameMatcher);
          // 遍历查找到的重载列表，如果有任何一个是聚合函数且不要求 OVER，且 aggregate 标志为 true，则调用 found(call)。
          for (SqlOperator operator2 : list) {
            if (operator2.isAggregator() && !operator2.requiresOver()) {
              // If nested aggregates disallowed or found aggregate at invalid
              // level
              if (aggregate) {
                found(call);
              }
            }
          }
        }
      }
    }
    // 如果 call 是一个查询（SqlKind.QUERY），则返回 null，不进入子查询内部搜索聚合。
    if (call.isA(SqlKind.QUERY)) {
      // don't traverse into queries
      return null;
    }
    // 如果是这种类型的调用（有序集聚合），若 aggregate 为 true，调用 found(call)。
    if (call.getKind() == SqlKind.WITHIN_GROUP) {
      if (aggregate) {
        return found(call);
      }
    }
    if (call.getKind() == SqlKind.OVER) {
      if (over) {
        return found(call);
      } else {
        // an aggregate function over a window is not an aggregate!
        return null;
      }
    }
    return super.visit(call);
  }

  protected abstract Void found(SqlCall call);
}
