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
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlOperandTypeInference;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.util.PrecedenceClimbingParser;
import org.apache.calcite.util.Util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.function.Predicate;

/** SqlSpecialOperator
 * Generic operator for nodes with special syntax.
 */
// 专门用于处理那些**不符合标准 SQL 语法结构（如前缀、后缀、中缀或函数式语法）**的特殊操作符
// SqlSpecialOperator 的核心作用是为 “非标准”或“复杂”语法节点 提供一个通用的表示方式。
// 在 SQL 解析过程中，有些表达式的结构非常独特，例如：
// CAST(a AS INT)
// CASE WHEN ... THEN ... END
// EXTRACT(HOUR FROM ...)
// 这些语法不能简单地归类为 a + b（中缀）或 f(a, b)（函数），它们需要特殊的解析逻辑和规约规则。SqlSpecialOperator 允许 Calcite 在解析阶段将这些复杂的标记流（Token Sequence）手动规约为一个 SqlNode 树。
// SqlSpecialOperator 是 Calcite 处理 SQL 语法糖 和 复杂关键字组合 的扩展机制。
// 它不强迫开发者将语法塞进“函数”或“算符”的死胡同，而是提供了一个基于 Token 序列规约 的灵活框架，让开发者能够处理任何奇形怪状的 SQL 结构。
public class SqlSpecialOperator extends SqlOperator {
  //~ Constructors -----------------------------------------------------------
  // 最简构造函数。
  // name 操作符名称；kind 属于哪个 SQL 类型（枚举）。
  public SqlSpecialOperator(
      String name,
      SqlKind kind) {
    this(name, kind, 2);
  }
  // 允许指定优先级。
  public SqlSpecialOperator(
      String name,
      SqlKind kind,
      int prec) {
    this(name, kind, prec, true, null, null, null);
  }
  // 全参数构造函数，用于精细控制。
  // returnTypeInference: 返回类型推导逻辑。
  // operandTypeInference: 操作数类型推导逻辑。
  // operandTypeChecker: 操作数类型检查器（验证输入是否合法）。
  public SqlSpecialOperator(
      String name,
      SqlKind kind,
      int prec,
      boolean leftAssoc,
      @Nullable SqlReturnTypeInference returnTypeInference,
      @Nullable SqlOperandTypeInference operandTypeInference,
      @Nullable SqlOperandTypeChecker operandTypeChecker) {
    super(
        name,
        kind,
        prec,
        leftAssoc,
        returnTypeInference,
        operandTypeInference,
        operandTypeChecker);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public SqlSyntax getSyntax() {
    return SqlSyntax.SPECIAL;
  }

  /**
   * Reduces a list of operators and arguments according to the rules of
   * precedence and associativity. Returns the ordinal of the node which
   * replaced the expression.
   *
   * <p>The default implementation throws
   * {@link UnsupportedOperationException}.
   *
   * @param ordinal indicating the ordinal of the current operator in the list
   *                on which a possible reduction can be made
   * @param list    List of alternating
   *     {@link org.apache.calcite.sql.parser.SqlParserUtil.ToTreeListItem} and
   *     {@link SqlNode}
   * @return ordinal of the node which replaced the expression
   */
  // 该类最重要的扩展点。它负责将一个 Token 序列（包含操作符和节点）“规约”成一个单一的 SqlNode。
  public ReduceResult reduceExpr(
      int ordinal,
      TokenSequence list) {
    throw Util.needToImplement(this);
  }

  /** List of tokens: the input to a parser. Every token is either an operator
   * ({@link SqlOperator}) or an expression ({@link SqlNode}), and every token
   * has a position. */
  // 表示解析过程中的 Token 流。
  public interface TokenSequence {
    // 返回序列中 Token 的数量。
    int size();
    // 获取第 i 个位置的操作符对象。
    SqlOperator op(int i);
    // 获取第 i 个位置在源代码中的位置信息（行、列）。
    SqlParserPos pos(int i);
    // 判断第 i 个位置是否是操作符（反之则是 SqlNode 表达式）。
    boolean isOp(int i);
    // 获取第 i 个位置的 SqlNode。
    SqlNode node(int i);
    // 核心操作：将序列中从 start 到 end 的部分替换为一个新的 SqlNode（实现规约）。
    void replaceSublist(int start, int end, SqlNode e);

    /** Creates a parser whose token sequence is a copy of a subset of this
     * token sequence. */
    // 返回一个基于优先级的爬升解析器（PrecedenceClimbingParser），用于处理复杂的嵌套子表达式。
    PrecedenceClimbingParser parser(int start,
        Predicate<PrecedenceClimbingParser.Token> predicate);
  }

  /** Result of applying
   * {@link org.apache.calcite.util.PrecedenceClimbingParser.Special#apply}.
   * Tells the caller which range of tokens to replace, and with what. */
  public static class ReduceResult {
    public final int startOrdinal;
    public final int endOrdinal;
    public final SqlNode node;

    public ReduceResult(int startOrdinal, int endOrdinal, SqlNode node) {
      this.startOrdinal = startOrdinal;
      this.endOrdinal = endOrdinal;
      this.node = node;
    }
  }
}
