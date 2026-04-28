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
package org.apache.calcite.sql.util;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Basic implementation of {@link SqlVisitor} which does nothing at each node.
 *
 * <p>This class is useful as a base class for classes which implement the
 * {@link SqlVisitor} interface. The derived class can override whichever
 * methods it chooses.
 *
 * @param <R> Return type
 */
// SqlBasicVisitor 是 SqlVisitor 接口的一个空实现类或基类。它提供了访问者模式的默认骨架，旨在简化开发者对 SQL 语法树（AST）的操作。
// SqlBasicVisitor 的主要作用是作为所有 SQL 访问器的基类。
// 默认行为：它对大多数节点的访问方法都返回 null，或者仅仅是执行简单的递归遍历（例如 SqlCall 和 SqlNodeList）。
// 简化开发：如果你只需要处理语法树中的某一种节点（例如只对 SqlIdentifier 感兴趣），你不需要实现 SqlVisitor 接口中所有的 7+ 个方法，而只需继承 SqlBasicVisitor 并重写你感兴趣的方法。
// 控制遍历顺序：它通过默认实现定义了如何深入遍历子节点（深度优先遍历）。
public class SqlBasicVisitor<@Nullable R> implements SqlVisitor<R> {
  //~ Methods ----------------------------------------------------------------
  // 访问字面量（常量）。
  // 默认返回 null。它是叶子节点，没有子节点可以继续遍历。
  @Override public R visit(SqlLiteral literal) {
    return null;
  }
  // 访问 SQL 调用（如函数、操作符、SELECT 语句）。
  // 它通过 call.getOperator().acceptCall(this, call) 将访问权交回给操作符。操作符通常会使用 ArgHandler 来递归地让访问者访问其所有的操作数（子节点）。
  @Override public R visit(SqlCall call) {
    return call.getOperator().acceptCall(this, call);
  }

  @Override public R visit(SqlNodeList nodeList) {
    R result = null;
    for (int i = 0; i < nodeList.size(); i++) {
      SqlNode node = nodeList.get(i);
      result = node.accept(this);
    }
    return result;
  }

  @Override public R visit(SqlIdentifier id) {
    return null;
  }

  @Override public R visit(SqlDataTypeSpec type) {
    return null;
  }

  @Override public R visit(SqlDynamicParam param) {
    return null;
  }

  @Override public R visit(SqlIntervalQualifier intervalQualifier) {
    return null;
  }

  //~ Inner Interfaces -------------------------------------------------------

  /** Argument handler.
   *
   * @param <R> result type */
  // 定义了如何处理 SqlCall 的参数
  public interface ArgHandler<R> {
    /** Returns the result of visiting all children of a call to an operator,
     * then the call itself.
     *
     * <p>Typically the result will be the result of the last child visited, or
     * (if R is {@link Boolean}) whether all children were visited
     * successfully. */
    // 在访问完所有参数后，返回最终的结果。
    R result();

    /** Visits a particular operand of a call, using a given visitor. */
    // 定义如何访问特定的某一个子节点（第 i 个操作数）。
    R visitChild(
        SqlVisitor<R> visitor,
        SqlNode expr,
        int i,
        @Nullable SqlNode operand);
  }

  //~ Inner Classes ----------------------------------------------------------

  /**
   * Default implementation of {@link ArgHandler} which merely calls
   * {@link SqlNode#accept} on each operand.
   *
   * @param <R> result type
   */
  public static class ArgHandlerImpl<@Nullable R> implements ArgHandler<R> {
    private static final ArgHandler<?> INSTANCE = new ArgHandlerImpl<>();

    @SuppressWarnings("unchecked")
    public static <R> ArgHandler<R> instance() {
      return (ArgHandler<R>) INSTANCE;
    }

    @Override public R result() {
      return null;
    }

    @Override public R visitChild(
        SqlVisitor<R> visitor,
        SqlNode expr,
        int i,
        @Nullable SqlNode operand) {
      if (operand == null) {
        return null;
      }
      return operand.accept(visitor);
    }
  }
}
