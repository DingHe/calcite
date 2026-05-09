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

import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.sql.util.SqlVisitor;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

import static java.util.Objects.requireNonNull;

/**
 * An operator describing a query. (Not a query itself.)
 *
 * <p>Operands are:
 *
 * <ul>
 * <li>0: distinct ({@link SqlLiteral})</li>
 * <li>1: selectClause ({@link SqlNodeList})</li>
 * <li>2: fromClause ({@link SqlCall} to "join" operator)</li>
 * <li>3: whereClause ({@link SqlNode})</li>
 * <li>4: havingClause ({@link SqlNode})</li>
 * <li>5: groupClause ({@link SqlNode})</li>
 * <li>6: windowClause ({@link SqlNodeList})</li>
 * <li>7: orderClause ({@link SqlNode})</li>
 * </ul>
 */
// 代表 “SELECT 语法结构本身”
// 核心作用是：
// 元数据定义：定义了 SELECT 语句在 Calcite 抽象语法树（AST）中的结构规范。它规定了一个 SELECT 语句应该包含哪些部分（如 FROM、WHERE 等）以及它们的顺序。
// 工厂模式：负责创建 SqlSelect 实例。当解析器（Parser）识别到一个 SELECT 语句时，会通过这个 Operator 来构建节点。
// 反解析逻辑控制：它包含了将 SqlSelect 树节点重新转换回 SQL 字符串（unparse）的核心算法，确保输出的 SQL 语法正确、美观。
// 非表达式定义：在 Calcite 中，SELECT 本身被视为一种特殊的“操作符调用”，其实际的参数（Operands）就是 FROM、WHERE 等子句。
public class SqlSelectOperator extends SqlOperator {
  // 该类的唯一单例实例。因为 SELECT 操作符的逻辑是全局通用的，没必要多次实例化。
  public static final SqlSelectOperator INSTANCE =
      new SqlSelectOperator();

  //~ Constructors -----------------------------------------------------------
  // 初始化操作符。
  // 优先级为 2（较低的优先级）。
  // ReturnTypes.SCOPE：表示该操作符返回的是一个作用域（Scope），而非简单的标量值。
  private SqlSelectOperator() {
    super("SELECT", SqlKind.SELECT, 2, true, ReturnTypes.SCOPE, null, null);
  }

  //~ Methods ----------------------------------------------------------------
  // SELECT 不是简单的二元或一元运算，它有极其复杂的结构（多子句），因此被归类为“特殊语法”。
  @Override public SqlSyntax getSyntax() {
    return SqlSyntax.SPECIAL;
  }
  // 根据传入的操作数数组创建一个具体的 SqlSelect 对象。
  // 它将 operands 数组中的元素（如关键字列表、选择列表、FROM 子句等）按顺序解包，并调用 SqlSelect 的构造函数。
  // 核心任务是将一个扁平的操作数数组（SqlNode[] operands） 转换为一个结构化的 SqlSelect 对象
  // 在 SQL 解析器的后期或在构造合成节点时，Calcite 往往会将所有的子句（如 WHERE、FROM 等）放入一个统一的 SqlNode 数组中。
  // createCall 的作用就是解析这个数组，并将数组中的元素映射到 SqlSelect 对象对应的属性上（如将 operands[3] 映射为 where 子句）
  @Override public SqlCall createCall(
      // 函数限定符（如 DISTINCT 在某些函数调用中的作用）。
      // 这里执行了 assert functionQualifier == null。
      // 因为 SELECT 不是一个普通函数，它的 DISTINCT 包含在 operands[0]（keywordList）中，所以此参数必须为 null。
      @Nullable SqlLiteral functionQualifier,
      SqlParserPos pos,
      @Nullable SqlNode... operands) {
    assert functionQualifier == null;
    return new SqlSelect(pos,
        (SqlNodeList) operands[0],
        requireNonNull((SqlNodeList) operands[1], "selectList"),
        operands[2],
        operands[3],
        (SqlNodeList) operands[4],
        operands[5],
        (SqlNodeList) operands[6],
        operands[7],
        (SqlNodeList) operands[8],
        operands[9],
        operands[10],
        (SqlNodeList) operands[11]);
  }

  /**
   * Creates a call to the <code>SELECT</code> operator.
   *
   * @deprecated Use {@link #createCall(SqlLiteral, SqlParserPos, SqlNode...)}.
   */
  @Deprecated // to be removed before 2.0
  public SqlSelect createCall(
      SqlNodeList keywordList,
      SqlNodeList selectList,
      SqlNode fromClause,
      SqlNode whereClause,
      SqlNodeList groupBy,
      SqlNode having,
      SqlNodeList windowDecls,
      SqlNode qualify,
      SqlNodeList orderBy,
      SqlNode offset,
      SqlNode fetch,
      SqlNodeList hints,
      SqlParserPos pos) {
    return new SqlSelect(
        pos,
        keywordList,
        selectList,
        fromClause,
        whereClause,
        groupBy,
        having,
        windowDecls,
        qualify,
        orderBy,
        offset,
        fetch,
        hints);
  }
  // 重写了父类的 acceptCall 方法，决定了访问者（Visitor）如何遍历 SqlSelect 节点。
  // 它是 Calcite 遍历 AST（抽象语法树）实现静态分析、重写或校验的关键入口。
  // acceptCall 的职责是定义：当访问者来到一个 SELECT 节点时，它应该继续访问哪些子节点（操作数）？
  @Override public <R> void acceptCall(
      SqlVisitor<R> visitor, // 执行访问逻辑的对象（例如验证器、SQL 重写器）
      SqlCall call, // 当前的 SqlSelect 实例。
      boolean onlyExpressions, // 核心开关。如果为 true，表示访问者只关心“纯粹的表达式”（如 1 + 1 或 col_a），而不关心“子句结构”（如 FROM、GROUP BY）。
      SqlBasicVisitor.ArgHandler<R> argHandler) { // 参数处理器。它负责定义在访问每个操作数之前和之后要执行的回调逻辑。
    // 当 onlyExpressions = false 时
    // 访问者处于“结构化遍历”模式。
    // SELECT 运算符的参数都不是直接的表达式
    if (!onlyExpressions) {
      // None of the arguments to the SELECT operator are expressions.
      super.acceptCall(visitor, call, onlyExpressions, argHandler);
    }
  }
  // 定义了如何将 Calcite 内存中的 SqlSelect 树对象重新组装成人类可读的 SQL 字符串。
  @SuppressWarnings("deprecation")
  @Override public void unparse(
      SqlWriter writer,
      SqlCall call,
      int leftPrec,
      int rightPrec) {
    SqlSelect select = (SqlSelect) call;
    final SqlWriter.Frame selectFrame =
        writer.startList(SqlWriter.FrameTypeEnum.SELECT);
    writer.sep("SELECT");
    // 如果查询包含优化器提示（如 /*+ HASH_JOIN */），将其放在 SELECT 之后。
    if (select.hasHints()) {
      writer.sep("/*+");
      castNonNull(select.hints).unparse(writer, 0, 0);
      writer.print("*/");
      writer.newlineAndIndent();
    }
    // 处理关键字与分页 (TopN)
    // 遍历 keywordList 写入 DISTINCT 或 ALL。
    for (int i = 0; i < select.keywordList.size(); i++) {
      final SqlNode keyword = select.keywordList.get(i);
      keyword.unparse(writer, 0, 0);
    }
    // 处理某些方言特有的 SELECT TOP 10 语法。
    writer.topN(select.fetch, select.offset);
    final SqlNodeList selectClause = select.selectList;
    // 处理 SELECT 列表
    writer.list(SqlWriter.FrameTypeEnum.SELECT_LIST, SqlWriter.COMMA,
        selectClause);
    // 处理 FROM 子句（关键的优先级逻辑）
    if (select.from != null) {
      // Calcite SQL requires FROM but MySQL does not.
      writer.sep("FROM");

      // for FROM clause, use precedence just below join operator to make
      // sure that an un-joined nested select will be properly
      // parenthesized
      final SqlWriter.Frame fromFrame =
          writer.startList(SqlWriter.FrameTypeEnum.FROM_LIST);
      select.from.unparse(
          writer,
          SqlJoin.COMMA_OPERATOR.getLeftPrec() - 1,
          SqlJoin.COMMA_OPERATOR.getRightPrec() - 1);
      writer.endList(fromFrame);
    }
    // 处理 WHERE 子句（展开逻辑）
    SqlNode where = select.where;
    if (where != null) {
      writer.sep("WHERE");

      if (!writer.isAlwaysUseParentheses()) {
        SqlNode node = where;

        // decide whether to split on ORs or ANDs
        SqlBinaryOperator whereSep = SqlStdOperatorTable.AND;
        // 如果不是强制用括号，则尝试将嵌套的 AND/OR 展开
        if ((node instanceof SqlCall)
            && node.getKind() == SqlKind.OR) {
          whereSep = SqlStdOperatorTable.OR;
        }

        // unroll whereClause
        final List<SqlNode> list = new ArrayList<>(0);
        while (node.getKind() == whereSep.kind) {
          assert node instanceof SqlCall;
          final SqlCall call1 = (SqlCall) node;
          list.add(0, call1.operand(1));
          node = call1.operand(0);
        }
        list.add(0, node);

        // unparse in a WHERE_LIST frame
        writer.list(SqlWriter.FrameTypeEnum.WHERE_LIST, whereSep,
            new SqlNodeList(list, where.getParserPosition()));
      } else {
        where.unparse(writer, 0, 0);
      }
    }
    // 处理 GROUP BY 与 DISTINCT
    if (select.groupBy != null) {
      SqlNodeList groupBy =
          select.groupBy.size() == 0 ? SqlNodeList.SINGLETON_EMPTY
              : select.groupBy;
      // if the DISTINCT keyword of GROUP BY is present it can be the only item
      if (groupBy.size() == 1 && groupBy.get(0) != null
          && groupBy.get(0).getKind() == SqlKind.GROUP_BY_DISTINCT) {
        writer.sep("GROUP BY DISTINCT");
        List<SqlNode> operandList = ((SqlCall) groupBy.get(0)).getOperandList();
        groupBy = new SqlNodeList(operandList, groupBy.getParserPosition());
      } else {
        writer.sep("GROUP BY");
      }
      writer.list(SqlWriter.FrameTypeEnum.GROUP_BY_LIST, SqlWriter.COMMA,
          groupBy);
    }
    // 依次处理 HAVING、WINDOW（窗口函数定义）、QUALIFY（窗口过滤）、ORDER BY。
    if (select.having != null) {
      writer.sep("HAVING");
      select.having.unparse(writer, 0, 0);
    }
    if (select.windowDecls.size() > 0) {
      writer.sep("WINDOW");
      writer.list(SqlWriter.FrameTypeEnum.WINDOW_DECL_LIST, SqlWriter.COMMA,
          select.windowDecls);
    }
    if (select.qualify != null) {
      writer.sep("QUALIFY");
      select.qualify.unparse(writer, 0, 0);
    }
    if (select.orderBy != null && select.orderBy.size() > 0) {
      writer.sep("ORDER BY");
      writer.list(SqlWriter.FrameTypeEnum.ORDER_BY_LIST, SqlWriter.COMMA,
          select.orderBy);
    }
    writer.fetchOffset(select.fetch, select.offset);
    writer.endList(selectFrame);
  }

  @Override public boolean argumentMustBeScalar(int ordinal) {
    return ordinal == SqlSelect.WHERE_OPERAND;
  }
}
