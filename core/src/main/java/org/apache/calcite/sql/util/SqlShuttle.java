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

import java.util.ArrayList;
import java.util.List;

/**
 * Basic implementation of {@link SqlVisitor} which returns each leaf node
 * unchanged.
 *
 * <p>This class is useful as a base class for classes which implement the
 * {@link SqlVisitor} interface and have {@link SqlNode} as the return type. The
 * derived class can override whichever methods it chooses.
 */
// SqlShuttle 是 Apache Calcite 框架中一个非常核心的工具类。它通过实现访问者模式（Visitor Pattern）来提供对 SQL 语法树（AST）的遍历与改写能力。
// 在 Calcite 中，SqlNode 是所有 SQL 语法树节点的基类。处理这些节点通常有两种方式：
// SqlVisitor: 用于“只读”遍历，通常返回聚合结果（如统计节点数量）。
// SqlShuttle: 专门用于“变换”遍历。它的 visit 方法返回值类型固定为 SqlNode。
// 核心哲学：不可变性（Immutability）
// SqlShuttle 的设计遵循“写时复制”原则。当你遍历语法树时：
// 如果子节点没有变化，它会返回原始的节点对象，避免内存开销。
// 如果子节点发生了改变，它会递归地创建父节点的新副本，最终构建出一棵部分或全部更新后的新树。
// 这使得它非常适合用于：SQL 重写、标识符全限定化（Fully-qualify）、表达式简化、脱敏处理等场景。
public class SqlShuttle extends SqlBasicVisitor<@Nullable SqlNode> {
  //~ Methods ----------------------------------------------------------------
  // 这些节点没有子节点，默认实现直接返回自身。
  @Override public @Nullable SqlNode visit(SqlLiteral literal) {
    return literal;
  }

  @Override public @Nullable SqlNode visit(SqlIdentifier id) {
    return id;
  }

  @Override public @Nullable SqlNode visit(SqlDataTypeSpec type) {
    return type;
  }

  @Override public @Nullable SqlNode visit(SqlDynamicParam param) {
    return param;
  }

  @Override public @Nullable SqlNode visit(SqlIntervalQualifier intervalQualifier) {
    return intervalQualifier;
  }
  // 作用：访问 SQL 调用（如函数调用 SUM(x)，操作符 a + b，甚至是 SELECT 语句本身）。
  @Override public @Nullable SqlNode visit(final SqlCall call) {
    // Handler creates a new copy of 'call' only if one or more operands
    // change.
    CallCopyingArgHandler argHandler = new CallCopyingArgHandler(call, false);
    call.getOperator().acceptCall(this, call, false, argHandler);
    return argHandler.result();
  }
  // 访问节点列表（如 SELECT 后的多个字段，或 IN 子句后的集合）
  @Override public @Nullable SqlNode visit(SqlNodeList nodeList) {
    boolean update = false;
    final List<@Nullable SqlNode> newList = new ArrayList<>(nodeList.size());
    for (SqlNode operand : nodeList) {
      SqlNode clonedOperand;
      if (operand == null) {
        clonedOperand = null;
      } else {
        clonedOperand = operand.accept(this);
        if (clonedOperand != operand) {
          update = true;
        }
      }
      newList.add(clonedOperand);
    }
    if (update) {
      return SqlNodeList.of(nodeList.getParserPosition(), newList);
    } else {
      return nodeList;
    }
  }

  //~ Inner Classes ----------------------------------------------------------

  /**
   * Implementation of
   * {@link org.apache.calcite.sql.util.SqlBasicVisitor.ArgHandler}
   * that deep-copies {@link SqlCall}s and their operands.
   */
  // CallCopyingArgHandler 是 SqlShuttle 实现“写时复制（Copy-on-Write）”逻辑的核心机制。
  // 它的主要任务是监控 SqlCall 的每一个操作数（Operand），并根据操作数是否被修改来决定是返回原始对象还是创建一个全新的对象。
  protected class CallCopyingArgHandler implements ArgHandler<@Nullable SqlNode> {
    // 这是一个状态开关。
    // 初始为 false，一旦发现任何一个子操作数被修改过，就标记为 true。
    boolean update;
    // 一个临时数组，用于存储处理后的操作数。
    // 即使操作数没变，也会存入原始引用。
    @Nullable SqlNode[] clonedOperands;
    // 正在处理的原始 SqlCall 对象
    private final SqlCall call;
    // 一个强制开关。
    // 如果为 true，即使子节点完全没变，最终也会创建一个新的 SqlCall 副本。
    private final boolean alwaysCopy;

    public CallCopyingArgHandler(SqlCall call, boolean alwaysCopy) {
      this.call = call;
      this.update = false;
      // 获取原始操作数列表，并将其转存到 clonedOperands 数组中
      final List<@Nullable SqlNode> operands = (List<@Nullable SqlNode>) call.getOperandList();
      this.clonedOperands = operands.toArray(new SqlNode[0]);
      this.alwaysCopy = alwaysCopy;
    }

    @Override public SqlNode result() {
      if (update || alwaysCopy) {
        return call.getOperator().createCall(
            call.getFunctionQuantifier(),
            call.getParserPosition(),
            clonedOperands);
      } else {
        return call;
      }
    }
    // 递归遍历过程中被调用的核心方法
    @Override public @Nullable SqlNode visitChild(
        SqlVisitor<@Nullable SqlNode> visitor,
        SqlNode expr,
        int i,
        @Nullable SqlNode operand) {
      if (operand == null) {
        return null;
      }
      // 1. 递归调用 Shuttle 本身来处理当前操作数
      SqlNode newOperand = operand.accept(SqlShuttle.this);
      // 2. 核心比对逻辑
      if (newOperand != operand) {
        // 发现变化，标记当前 SqlCall 需要被克隆
        update = true;
      }
      clonedOperands[i] = newOperand;
      return newOperand;
    }
  }
}
