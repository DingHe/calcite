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
import org.apache.calcite.util.ImmutableNullableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Parse tree node that represents an {@code ORDER BY} on a query other than a
 * {@code SELECT} (e.g. {@code VALUES} or {@code UNION}).
 *
 * <p>It is a purely syntactic operator, and is eliminated by
 * {@link org.apache.calcite.sql.validate.SqlValidatorImpl#performUnconditionalRewrites}
 * and replaced with the ORDER_OPERAND of SqlSelect.
 */
// SqlOrderBy 是 Apache Calcite SQL AST（抽象语法树）中非常关键的一个类。
// 代表ORDER BY OFFSET FETCH
// 它并不是“真正语义上的 ORDER BY 节点”，而是一个“语法包装节点（syntactic wrapper）”
// SqlOrderBy 代表的是一种顶层或包装式的排序操作。
// 主要解决： 非 SELECT 查询上的 ORDER BY
// 例如 VALUES (1), (2) ORDER BY 1
// SELECT * FROM A UNION SELECT * FROM B ORDER BY ID  ，这里的并不是 SELECT 自身的一部分。
public class SqlOrderBy extends SqlCall {
  // 静态常量，定义了 ORDER BY 的操作符元数据。它指定了该节点的语法类型、优先级以及如何创建调用（createCall）
  public static final SqlSpecialOperator OPERATOR = new Operator() {
    @SuppressWarnings("argument.type.incompatible")
    @Override public SqlCall createCall(@Nullable SqlLiteral functionQualifier,
        SqlParserPos pos, @Nullable SqlNode... operands) {
      return new SqlOrderBy(pos, operands[0], (SqlNodeList) operands[1],
          operands[2], operands[3]);
    }
  };
  // 排序的对象（原始查询）
  // 例子：在 (SELECT...) UNION (SELECT...) ORDER BY x 中，query 就是整个 UNION 调用部分。
  public final SqlNode query;
  // 排序字段列表。
  // 包含一个或多个排序表达式（如 SqlIdentifier 或 SqlBasicCall 如 DESC 修饰的字段）
  public final SqlNodeList orderList;
  // 表示 OFFSET 子句，定义跳过多少行。如果是 null，表示不跳过。
  public final @Nullable SqlNode offset;
  // 表示 FETCH 或 LIMIT 子句，定义获取多少行。如果是 null，表示获取全部。
  public final @Nullable SqlNode fetch;

  //~ Constructors -----------------------------------------------------------
  // 初始化节点。
  // 接收解析位置 pos，以及上述的四个核心组件（查询体、排序列表、偏移量、获取量）。
  public SqlOrderBy(SqlParserPos pos, SqlNode query, SqlNodeList orderList,
      @Nullable SqlNode offset, @Nullable SqlNode fetch) {
    super(pos);
    this.query = query;
    this.orderList = orderList;
    this.offset = offset;
    this.fetch = fetch;
  }

  //~ Methods ----------------------------------------------------------------

  @Override public SqlKind getKind() {
    return SqlKind.ORDER_BY;
  }

  @Override public SqlOperator getOperator() {
    return OPERATOR;
  }

  @SuppressWarnings("nullness")
  @Override public List<SqlNode> getOperandList() {
    return ImmutableNullableList.of(query, orderList, offset, fetch);
  }

  /** Definition of {@code ORDER BY} operator. */
  private static class Operator extends SqlSpecialOperator {
    private Operator() {
      // NOTE:  make precedence lower then SELECT to avoid extra parens
      super("ORDER BY", SqlKind.ORDER_BY, 0);
    }

    @Override public SqlSyntax getSyntax() {
      return SqlSyntax.POSTFIX;
    }

    @Override public void unparse(
        SqlWriter writer,
        SqlCall call,
        int leftPrec,
        int rightPrec) {
      SqlOrderBy orderBy = (SqlOrderBy) call;
      final SqlWriter.Frame frame =
          writer.startList(SqlWriter.FrameTypeEnum.ORDER_BY);
      orderBy.query.unparse(writer, getLeftPrec(), getRightPrec());
      if (orderBy.orderList != SqlNodeList.EMPTY) {
        writer.sep(getName());
        writer.list(SqlWriter.FrameTypeEnum.ORDER_BY_LIST, SqlWriter.COMMA,
            orderBy.orderList);
      }
      if (orderBy.offset != null || orderBy.fetch != null) {
        writer.fetchOffset(orderBy.fetch, orderBy.offset);
      }
      writer.endList(frame);
    }
  }
}
