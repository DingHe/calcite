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
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.util.SqlShuttle;
import org.apache.calcite.sql.util.SqlVisitor;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

import static java.util.Objects.requireNonNull;

/**
 * Refinement to {@link SqlShuttle} which maintains a stack of scopes.
 *
 * <p>Derived class should override {@link #visitScoped(SqlCall)} rather than
 * {@link SqlVisitor#visit(SqlCall)}.
 */
// 继承自 SqlShuttle。如果说 SqlShuttle 提供了修改树的能力，那么 SqlScopedShuttle 则在此基础上增加了感知上下文（Scope）的能力。
// 在 SQL 验证过程中，同一个表达式在不同的位置其含义可能完全不同。例如，SELECT 子句中的列引用与 WHERE 子句或 HAVING 子句中的列引用，其可见的范围（Scope）是不一样的。
// SqlScopedShuttle 的核心作用是：在递归遍历 SQL 语法树时，自动维护一个“作用域栈（Scope Stack）”。
// 它能确保当你访问某个 SqlCall（如子查询或函数）时，能够获取到该节点对应的 SqlValidatorScope。
// 开发者在实现具体的改写逻辑时，可以直接调用 getScope() 获取当前的上下文信息，从而进行准确的标识符解析或类型检查。
public abstract class SqlScopedShuttle extends SqlShuttle {
  //~ Instance fields --------------------------------------------------------
  // 存储 SqlValidatorScope 对象的栈。随着遍历深入到具有独立作用域的节点（如 SqlSelect），新的 Scope 会被压入栈顶；
  // 当遍历完该节点返回时，对应的 Scope 会被弹出。它始终保证栈顶是当前最精确的上下文。
  private final Deque<SqlValidatorScope> scopes = new ArrayDeque<>();

  //~ Constructors -----------------------------------------------------------
  // 参数：initialScope —— 初始作用域。
  protected SqlScopedShuttle(SqlValidatorScope initialScope) {
    scopes.push(initialScope);
  }

  //~ Methods ----------------------------------------------------------------
  // 参数：call —— 当前访问的调用节点。
  @Override public final @Nullable SqlNode visit(SqlCall call) {
    // 获取父级 Scope：首先调用 getScope() 拿到当前的上下文。
    SqlValidatorScope oldScope = getScope();
    // 获取子级 Scope：调用 oldScope.getOperandScope(call)。
    // 这是关键，验证器会根据 call 的类型（比如它是不是一个子查询）来判断是否需要进入一个新的、更窄的作用域。
    SqlValidatorScope newScope = oldScope.getOperandScope(call);
    scopes.push(newScope);
    // 调用 visitScoped(call)（这是派生类应该扩展的方法）
    SqlNode result = visitScoped(call);
    scopes.pop();
    return result;
  }

  /**
   * Visits an operator call. If the call has entered a new scope, the base
   * class will have already modified the scope.
   */
  protected @Nullable SqlNode visitScoped(SqlCall call) {
    return super.visit(call);
  }

  /**
   * Returns the current scope.
   */
  protected SqlValidatorScope getScope() {
    return requireNonNull(scopes.peek(), "scopes.peek()");
  }
}
