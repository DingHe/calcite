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
package org.apache.calcite.sql.fun;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.util.UnmodifiableArrayList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * A <code>SqlCase</code> is a node of a parse tree which represents a case
 * statement. It warrants its own node type just because we have a lot of
 * methods to put somewhere.
 */
// SqlCase 类用于表示 SQL 解析树中的 CASE 表达式节点。它继承自 SqlCall，是处理条件分支逻辑的核心抽象。
// SqlCase 的核心作用是封装 SQL 的条件选择逻辑。它支持 SQL 标准中的两种 CASE 形式：
// 简单 CASE (Simple CASE)：
// CASE value WHEN e1 THEN r1 WHEN e2 THEN r2 ELSE r3 END
// 搜索 CASE (Searched CASE)：
// CASE WHEN condition1 THEN r1 WHEN condition2 THEN r2 ELSE r3 END
// 在 Calcite 内部实现中，它倾向于将“简单 CASE”转换为“搜索 CASE”。例如，CASE x WHEN 1 THEN 'a' END 会被标准化为 CASE WHEN x = 1 THEN 'a' END。这种统一处理简化了后续的优化和转换逻辑。
public class SqlCase extends SqlCall {
  // 对应简单 CASE 中的被比较表达式。
  // 注意：如果是搜索 CASE，此属性为 null。
  @Nullable SqlNode value;
  // 存储所有的 WHEN 条件子句。一个表达式列表，对应 WHEN 后面的条件。
  SqlNodeList whenList;
  // 存储与 whenList 一一对应的结果表达式。
  // 当对应的 WHEN 条件满足时，返回该列表中的对应值。
  SqlNodeList thenList;
  // 存储 ELSE 子句的表达式。
  // 如果 SQL 中未显式写 ELSE，Calcite 通常会将其默认设置为 SqlLiteral 的 NULL。
  @Nullable SqlNode elseExpr;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a SqlCase expression.
   *
   * @param pos Parser position
   * @param value The value (null for boolean case)
   * @param whenList List of all WHEN expressions
   * @param thenList List of all THEN expressions
   * @param elseExpr The implicit or explicit ELSE expression
   */
  public SqlCase(SqlParserPos pos, @Nullable SqlNode value, SqlNodeList whenList,
      SqlNodeList thenList, @Nullable SqlNode elseExpr) {
    super(pos);
    this.value = value;
    this.whenList = whenList;
    this.thenList = thenList;
    this.elseExpr = elseExpr;
  }

  /**
   * Creates a call to the switched form of the CASE operator. For example:
   *
   * <blockquote><code>CASE value<br>
   * WHEN whenList[0] THEN thenList[0]<br>
   * WHEN whenList[1] THEN thenList[1]<br>
   * ...<br>
   * ELSE elseClause<br>
   * END</code></blockquote>
   */
  // 用于创建“转换后”的 CASE 表达式。
  // 核心逻辑：如果 value 不为空（简单 CASE），它会自动遍历 whenList，将每个条件 e 改写为 value = e（使用 EQUALS 操作符）或 value IN (e)（如果 e 是列表）。
  // 最后将 value 设为 null，从而将简单 CASE 转换为搜索 CASE。
  public static SqlCase createSwitched(SqlParserPos pos, @Nullable SqlNode value,
      SqlNodeList whenList, SqlNodeList thenList, @Nullable SqlNode elseClause) {
    if (null != value) {
      for (int i = 0; i < whenList.size(); i++) {
        SqlNode e = whenList.get(i);
        final SqlCall call;
        if (e instanceof SqlNodeList) {
          call = SqlStdOperatorTable.IN.createCall(pos, value, e);
        } else {
          call = SqlStdOperatorTable.EQUALS.createCall(pos, value, e);
        }
        whenList.set(i, call);
      }
    }

    if (null == elseClause) {
      elseClause = SqlLiteral.createNull(pos);
    }

    return new SqlCase(pos, null, whenList, thenList, elseClause);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public SqlKind getKind() {
    return SqlKind.CASE;
  }

  @Override public SqlOperator getOperator() {
    return SqlStdOperatorTable.CASE;
  }

  @SuppressWarnings("nullness")
  @Override public List<SqlNode> getOperandList() {
    return UnmodifiableArrayList.of(value, whenList, thenList, elseExpr);
  }

  @SuppressWarnings("assignment.type.incompatible")
  @Override public void setOperand(int i, @Nullable SqlNode operand) {
    switch (i) {
    case 0:
      value = operand;
      break;
    case 1:
      whenList = (SqlNodeList) operand;
      break;
    case 2:
      thenList = (SqlNodeList) operand;
      break;
    case 3:
      elseExpr = operand;
      break;
    default:
      throw new AssertionError(i);
    }
  }

  public @Nullable SqlNode getValueOperand() {
    return value;
  }

  public SqlNodeList getWhenOperands() {
    return whenList;
  }

  public SqlNodeList getThenOperands() {
    return thenList;
  }

  public @Nullable SqlNode getElseOperand() {
    return elseExpr;
  }
}
