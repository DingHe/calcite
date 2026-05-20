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
package org.apache.calcite.sql;

import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.sql.util.SqlVisitor;
import org.apache.calcite.util.ImmutableNullableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Parse tree node for "{@code FOR SYSTEM_TIME AS OF}" temporal clause.
 */
// 专门用来表示 SQL 抽象语法树（AST）中时态查询（Temporal Query）规范的节点类。它继承自 SqlCall。
// 核心作用是解析、存储并渲染 SQL 标准中的快照/时态子句（即 FOR SYSTEM_TIME AS OF）
// 在标准 SQL:2011 中，引入了对历史版本数据查询的支持。例如，当用户编写如下 SQL 时：
// SELECT * FROM employees FOR SYSTEM_TIME AS OF TIMESTAMP '2026-05-19 10:00:00'
// Calcite 的解析器会捕捉到这一特定时态规范，并将其转换为一个 SqlSnapshot 对象。它将查询目标（表引用）与时间锚点（历史时刻）绑定在一起。
// 这使优化器能够知晓该查询需要穿透到数据库的历史快照层（或流处理中的某个水位线/时间戳），去获取该指定时间点的数据状态。
public class SqlSnapshot extends SqlCall {
  // 表示“表引用节点”在内部操作数列表中的固定索引位置（第 0 位）。
  private static final int OPERAND_TABLE_REF = 0;
  // 表示“时间表达式节点”在内部操作数列表中的固定索引位置（第 1 位）。
  private static final int OPERAND_PERIOD = 1;

  //~ Instance fields -------------------------------------------
  // 表引用对象。存储需要建立快照的目标节点，它通常是一个表名 SqlIdentifier，或者一个带别名的表引用 SqlCall（如 employees AS e）。
  private SqlNode tableRef;
  // 时间段/时间戳点对象。存储 AS OF 后面紧跟的时间或版本表达式，可以是具体的历史时间戳字面量（如 TIMESTAMP '2026-05-19 10:00:00'）、系统变量或动态参数 ?。
  private SqlNode period;

  /** Creates a SqlSnapshot. */
  public SqlSnapshot(SqlParserPos pos, SqlNode tableRef, SqlNode period) {
    super(pos);
    this.tableRef = Objects.requireNonNull(tableRef, "tableRef");
    this.period = Objects.requireNonNull(period, "period");
  }

  // ~ Methods

  @Override public SqlOperator getOperator() {
    return SqlSnapshotOperator.INSTANCE;
  }

  @Override public List<SqlNode> getOperandList() {
    return ImmutableNullableList.of(tableRef, period);
  }

  public SqlNode getTableRef() {
    return tableRef;
  }

  public SqlNode getPeriod() {
    return period;
  }

  @Override public void setOperand(int i, @Nullable SqlNode operand) {
    switch (i) {
    case OPERAND_TABLE_REF:
      tableRef = Objects.requireNonNull(operand, "operand");
      break;
    case OPERAND_PERIOD:
      period = Objects.requireNonNull(operand, "operand");
      break;
    default:
      throw new AssertionError(i);
    }
  }

  @Override public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
    getOperator().unparse(writer, this, 0, 0);
  }

  /**
   * An operator describing a FOR SYSTEM_TIME specification.
   */
  public static class SqlSnapshotOperator extends SqlOperator {

    public static final SqlSnapshotOperator INSTANCE = new SqlSnapshotOperator();

    private SqlSnapshotOperator() {
      super("SNAPSHOT", SqlKind.SNAPSHOT, 2, true, null, null, null);
    }

    @Override public SqlSyntax getSyntax() {
      return SqlSyntax.SPECIAL;
    }

    @SuppressWarnings("argument.type.incompatible")
    @Override public SqlCall createCall(
        @Nullable SqlLiteral functionQualifier,
        SqlParserPos pos,
        @Nullable SqlNode... operands) {
      assert functionQualifier == null;
      assert operands.length == 2;
      return new SqlSnapshot(pos, operands[0], operands[1]);
    }

    @Override public <R> void acceptCall(
        SqlVisitor<R> visitor,
        SqlCall call,
        boolean onlyExpressions,
        SqlBasicVisitor.ArgHandler<R> argHandler) {
      if (onlyExpressions) {
        List<SqlNode> operands = call.getOperandList();
        // skip the first operand
        for (int i = 1; i < operands.size(); i++) {
          argHandler.visitChild(visitor, call, i, operands.get(i));
        }
      } else {
        super.acceptCall(visitor, call, false, argHandler);
      }
    }

    @Override public void unparse(
        SqlWriter writer,
        SqlCall call,
        int leftPrec,
        int rightPrec) {
      final SqlSnapshot snapshot = (SqlSnapshot) call;
      SqlNode tableRef = snapshot.tableRef;

      if (tableRef instanceof SqlBasicCall
          && ((SqlBasicCall) tableRef).getOperator() instanceof SqlAsOperator) {
        SqlBasicCall basicCall = (SqlBasicCall) tableRef;
        basicCall.operand(0).unparse(writer, 0, 0);
        writer.setNeedWhitespace(true);
        writeForSystemTimeAsOf(writer, snapshot);
        writer.keyword("AS");
        basicCall.operand(1).unparse(writer, 0, 0);
      } else {
        tableRef.unparse(writer, 0, 0);
        writeForSystemTimeAsOf(writer, snapshot);
      }
    }

    private static void writeForSystemTimeAsOf(SqlWriter writer, SqlSnapshot snapshot) {
      writer.keyword("FOR SYSTEM_TIME AS OF");
      snapshot.period.unparse(writer, 0, 0);
    }
  }
}
