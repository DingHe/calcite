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

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlKind;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;

import static java.util.Objects.requireNonNull;

/**
 * Row expression.
 *
 * <p>Every row-expression has a type.
 * (Compare with {@link org.apache.calcite.sql.SqlNode}, which is created before
 * validation, and therefore types may not be available.)
 *
 * <p>Some common row-expressions are: {@link RexLiteral} (constant value),
 * {@link RexVariable} (variable), {@link RexCall} (call to operator with
 * operands). Expressions are generally created using a {@link RexBuilder}
 * factory.
 * <p>All sub-classes of RexNode are immutable.
 */
// 在 Apache Calcite 框架中，RexNode（Row Expression Node）是核心组件之一。它是所有行级表达式的基类。
// RexNode 代表关系代数表达式中的一个逻辑表达式。
// 执行层面的抽象：与 SqlNode（代表 SQL 语法的抽象语法树）不同，RexNode 是在校验（Validation）之后生成的。它已经绑定了具体的数据类型（RelDataType）。
// 计算逻辑：它用于描述 SQL 中的 SELECT 子句、WHERE 条件、JOIN 关联条件或 GROUP BY 中的计算逻辑。
// 不可变性（Immutable）：RexNode 及其子类设计为不可变，这确保了在查询优化（Planning）过程中，表达式节点可以安全地被共享和复用。
public abstract class RexNode {

  //~ Instance fields --------------------------------------------------------

  // Effectively final. Set in each sub-class constructor, and never re-set.
  // 存储该表达式的摘要字符串（唯一标识）。
  protected @MonotonicNonNull String digest;

  //~ Methods ----------------------------------------------------------------
  // 获取该表达式的返回类型。
  // 这是 RexNode 与 SqlNode 的核心区别。每一个 RexNode 都必须知道它计算后的结果是什么类型（如 INTEGER, VARCHAR, BOOLEAN 等）。
  public abstract RelDataType getType();

  /**
   * Returns whether this expression always returns true. (Such as if this
   * expression is equal to the literal <code>TRUE</code>.)
   */
  // 判断该表达式是否恒为 TRUE。
  // 基类默认返回 false。常用于优化器在处理 WHERE 条件时，如果发现条件恒为真，则可以简化过滤逻辑。
  public boolean isAlwaysTrue() {
    return false;
  }

  /**
   * Returns whether this expression always returns false. (Such as if this
   * expression is equal to the literal <code>FALSE</code>.)
   */
  // 判断该表达式是否恒为 FALSE。
  // 基类默认返回 false。如果过滤条件恒为假，优化器可以直接返回空结果集，避免扫描数据。
  public boolean isAlwaysFalse() {
    return false;
  }
  // 检查该节点是否属于特定的 SqlKind。
  public boolean isA(SqlKind kind) {
    return getKind() == kind;
  }
  // 检查该节点是否属于给定的一组 SqlKind 类别。
  public boolean isA(Collection<SqlKind> kinds) {
    return getKind().belongsTo(kinds);
  }

  /**
   * Returns the kind of node this is.
   *
   * @return Node kind, never null
   */
  // 获取该节点的类型种类。
  public SqlKind getKind() {
    return SqlKind.OTHER;
  }

  @Override public String toString() {
    return requireNonNull(digest, "digest");
  }

  /** Returns the number of nodes in this expression.
   *
   * <p>Leaf nodes, such as {@link RexInputRef} or {@link RexLiteral}, have
   * a count of 1. Calls have a count of 1 plus the sum of their operands.
   *
   * <p>Node count is a measure of expression complexity that is used by some
   * planner rules to prevent deeply nested expressions.
   */
  // 返回该表达式树中的节点总数。
  // 叶子节点（如常量 RexLiteral 或变量引用 RexInputRef）返回 1。
  // 调用节点（RexCall）返回 1 加上其所有操作数（operands）的节点数总和。
  public int nodeCount() {
    return 1;
  }

  /**
   * Accepts a visitor, dispatching to the right overloaded
   * {@link RexVisitor#visitInputRef visitXxx} method.
   *
   * <p>Also see {@link RexUtil#apply(RexVisitor, java.util.List, RexNode)},
   * which applies a visitor to several expressions simultaneously.
   */
  // 接受一个访问者（Visitor）。
  // 标准的访问者模式实现。由于 RexNode 是一个复杂的树状结构，通过此方法可以方便地对表达式进行遍历、转换或重写，而无需使用大量的 instanceof 判断。
  public abstract <R> R accept(RexVisitor<R> visitor);

  /**
   * Accepts a visitor with a payload, dispatching to the right overloaded
   * {@link RexBiVisitor#visitInputRef(RexInputRef, Object)} visitXxx} method.
   */
  // 接受一个带有额外参数的访问者。
  // 与单参数 accept 类似，但允许在遍历过程中传递一个状态对象或负载数据（Payload），增强了遍历时的灵活性。
  public abstract <R, P> R accept(RexBiVisitor<R, P> visitor, P arg);

  /** {@inheritDoc}
   *
   * <p>Every node must implement {@link #equals} based on its content
   */
  @Override public abstract boolean equals(@Nullable Object obj);

  /** {@inheritDoc}
   *
   * <p>Every node must implement {@link #hashCode} consistent with
   * {@link #equals}
   */
  @Override public abstract int hashCode();
}
