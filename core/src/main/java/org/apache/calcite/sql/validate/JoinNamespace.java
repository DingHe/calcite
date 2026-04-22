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

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlNode;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Namespace representing the row type produced by joining two relations.
 */
// 专门用于处理 SQL 中的 JOIN（连接） 操作
// 负责定义两个数据源连接后产生的结果集结构。
// 在 SQL 校验阶段，每个 FROM 子句中的数据源（如单表、子查询、JOIN 等）都被抽象为一个 SqlValidatorNamespace。
// JoinNamespace 的核心作用是：
// 类型合成：根据 JOIN 的左右操作数（Left/Right Operands）和 JOIN 类型（INNER, LEFT, FULL 等），合成一个新的行类型（Row Type）。
// 空值推导（Nullability Analysis）：这是该类最重要的逻辑。它决定了在执行外连接（Outer Join）时，哪些原本不可为空（NOT NULL）的列在结果集中需要变成可为空（NULLABLE）。
class JoinNamespace extends AbstractNamespace {
  //~ Instance fields --------------------------------------------------------
  // 存储解析树中的 SqlJoin 节点。
  private final SqlJoin join;

  //~ Constructors -----------------------------------------------------------

  JoinNamespace(SqlValidatorImpl validator, SqlJoin join) {
    super(validator, null);
    this.join = join;
  }

  //~ Methods ----------------------------------------------------------------
  // 定义了 JOIN 之后结果集的行类型
  @Override protected RelDataType validateImpl(RelDataType targetRowType) {
    // 1. 获取左右两侧的命名空间并推导它们的行类型
    RelDataType leftType =
        validator.getNamespaceOrThrow(join.getLeft()).getRowType();
    RelDataType rightType =
        validator.getNamespaceOrThrow(join.getRight()).getRowType();
    final RelDataTypeFactory typeFactory = validator.getTypeFactory();
    switch (join.getJoinType()) {
    case LEFT:
      rightType = typeFactory.createTypeWithNullability(rightType, true);
      break;
    case RIGHT:
      leftType = typeFactory.createTypeWithNullability(leftType, true);
      break;
    case FULL:
      leftType = typeFactory.createTypeWithNullability(leftType, true);
      rightType = typeFactory.createTypeWithNullability(rightType, true);
      break;
    // LEFT SEMI JOIN and LEFT ANTI JOIN can only come from Babel.
    case LEFT_SEMI_JOIN:
    case LEFT_ANTI_JOIN:
      return typeFactory.createJoinType(leftType);
    default:
      break;
    }
    return typeFactory.createJoinType(leftType, rightType);
  }

  @Override public @Nullable SqlNode getNode() {
    return join;
  }
}
