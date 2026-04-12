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
import org.apache.calcite.sql.util.SqlString;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import java.util.function.Consumer;

/**
 * A <code>SqlWriter</code> is the target to construct a SQL statement from a
 * parse tree. It deals with dialect differences; for example, Oracle quotes
 * identifiers as <code>"scott"</code>, while SQL Server quotes them as <code>
 * [scott]</code>.
 */
// SqlWriter 是 Apache Calcite 中一个至关重要的接口。它主要负责将 Calcite 的 SQL 抽象语法树（SqlNode AST） 重新转换（序列化）为 SQL 字符串。
// SqlWriter 的核心作用是实现 SQL 的方言化（Dialect-aware）生成和格式化。
// 方言处理：不同的数据库对标识符的引用（如 Oracle 用 "scott"，SQL Server 用 [scott]）和语法支持不同。SqlWriter 配合 SqlDialect 确保生成的 SQL 能够在特定数据库上运行。
// 格式化控制：它维护了一个状态机，控制换行、缩进、空格、大小写以及括号的使用。
// 层次化构建：通过“帧（Frame）”的概念，处理复杂的嵌套结构（如子查询、函数调用、FROM 列表），确保每个层级的缩进和分隔符（如逗号）正确生成。

public interface SqlWriter {
  //~ Enums ------------------------------------------------------------------

  /**
   * Style of formatting sub-queries.
   */
  // 子查询风格
  enum SubQueryStyle {
    /**
     * Julian's style of sub-query nesting. Like this:
     *
     * <blockquote><pre>SELECT *
     * FROM (
     *     SELECT *
     *     FROM t
     * )
     * WHERE condition</pre></blockquote>
     */
    // Julian Hyde 风格。左括号紧跟在 FROM 等关键字后，内容在新行缩进。
    HYDE,

    /**
     * Damian's style of sub-query nesting. Like this:
     *
     * <blockquote><pre>SELECT *
     * FROM
     * (   SELECT *
     *     FROM t
     * )
     * WHERE condition</pre></blockquote>
     */
    // Damian Black 风格。左括号单独占据一行。
    BLACK
  }

  /**
   * Enumerates the types of frame.
   */
  // 帧类型枚举
  // 定义了 SQL 不同部分的上下文，决定了缩进逻辑。
  enum FrameTypeEnum implements FrameType {
    /**
     * SELECT query (or UPDATE or DELETE). The items in the list are the
     * clauses: FROM, WHERE, etc.
     */
    // 顶层查询帧（含 FROM, WHERE 等子句）
    SELECT,

    /**
     * Simple list.
     */
    // 简单列表。
    SIMPLE,

    /**
     * Comma-separated list surrounded by parentheses.
     * The parentheses are present even if the list is empty.
     */
    PARENTHESES,

    /**
     * The SELECT clause of a SELECT statement.
     */
    SELECT_LIST,

    /**
     * The WINDOW clause of a SELECT statement.
     */
    WINDOW_DECL_LIST,

    /**
     * The SET clause of an UPDATE statement.
     */
    UPDATE_SET_LIST,

    /**
     * Function declaration.
     */
    FUN_DECL,

    /**
     * Function call or datatype declaration.
     *
     * <p>Examples:
     * <ul>
     * <li><code>SUBSTRING('foobar' FROM 1 + 2 TO 4)</code></li>
     * <li><code>DECIMAL(10, 5)</code></li>
     * </ul>
     */
    FUN_CALL,

    /**
     * Window specification.
     *
     * <p>Examples:
     * <ul>
     * <li><code>SUM(x) OVER (ORDER BY hireDate ROWS 3 PRECEDING)</code></li>
     * <li><code>WINDOW w1 AS (ORDER BY hireDate), w2 AS (w1 PARTITION BY gender
     * RANGE BETWEEN INTERVAL '1' YEAR PRECEDING AND '2' MONTH
     * PRECEDING)</code></li>
     * </ul>
     */
    WINDOW,

    /**
     * ORDER BY clause of a SELECT statement. The "list" has only two items:
     * the query and the order by clause, with ORDER BY as the separator.
     */
    ORDER_BY,

    /**
     * ORDER BY list.
     *
     * <p>Example:
     * <ul>
     * <li><code>ORDER BY x, y DESC, z</code></li>
     * </ul>
     */
    ORDER_BY_LIST,

    /**
     * WITH clause of a SELECT statement. The "list" has only two items:
     * the WITH clause and the query, with AS as the separator.
     */
    WITH,

    /**
     * The body query of WITH.
     */
    WITH_BODY,

    /**
     * OFFSET clause.
     *
     * <p>Example:
     * <ul>
     * <li><code>OFFSET 10 ROWS</code></li>
     * </ul>
     */
    OFFSET,

    /**
     * FETCH clause.
     *
     * <p>Example:
     * <ul>
     * <li><code>FETCH FIRST 3 ROWS ONLY</code></li>
     * </ul>
     */
    FETCH,

    /**
     * GROUP BY list.
     *
     * <p>Example:
     * <ul>
     * <li><code>GROUP BY x, FLOOR(y)</code></li>
     * </ul>
     */
    GROUP_BY_LIST,

    /**
     * Sub-query list. Encloses a SELECT, UNION, EXCEPT, INTERSECT query
     * with optional ORDER BY.
     *
     * <p>Example:
     * <ul>
     * <li><code>GROUP BY x, FLOOR(y)</code></li>
     * </ul>
     */
    SUB_QUERY(true),

    /**
     * Set operation.
     *
     * <p>Example:
     * <ul>
     * <li><code>SELECT * FROM a UNION SELECT * FROM b</code></li>
     * </ul>
     */
    SETOP,

    /**
     * VALUES clause.
     *
     * <p>Example:
     *
     * <blockquote><pre>VALUES (1, 'a'),
     *   (2, 'b')</pre></blockquote>
     */
    VALUES,

    /**
     * FROM clause (containing various kinds of JOIN).
     */
    FROM_LIST,

    /**
     * Pair-wise join.
     */
    JOIN(false),

    /**
     * WHERE clause.
     */
    WHERE_LIST,

    /**
     * Compound identifier.
     *
     * <p>Example:
     * <ul>
     * <li><code>"A"."B"."C"</code></li>
     * </ul>
     */
    IDENTIFIER(false),

    /**
     * Alias ("AS"). No indent.
     */
    AS(false),

    /**
     * CASE expression.
     */
    CASE,

    /**
     * Same behavior as user-defined frame type.
     */
    OTHER;

    private final boolean needsIndent;

    /**
     * Creates a list type.
     */
    FrameTypeEnum() {
      this(true);
    }

    /**
     * Creates a list type.
     */
    FrameTypeEnum(boolean needsIndent) {
      this.needsIndent = needsIndent;
    }

    @Override public boolean needsIndent() {
      return needsIndent;
    }

    /**
     * Creates a frame type.
     *
     * @param name Name
     * @return frame type
     */
    public static FrameType create(final String name) {
      return new FrameType() {
        @Override public String getName() {
          return name;
        }

        @Override public boolean needsIndent() {
          return true;
        }
      };
    }

    @Override public String getName() {
      return name();
    }
  }

  /** Comma operator.
   *
   * <p>Defined in {@code SqlWriter} because it is only used while converting
   * {@link SqlNode} to SQL;
   * see {@link SqlWriter#list(FrameTypeEnum, SqlBinaryOperator, SqlNodeList)}.
   *
   * <p>The precedence of the comma operator is low but not zero. For
   * instance, this ensures parentheses in
   * {@code select x, (select * from foo order by z), y from t}. */
  // 表示逗号操作符。由于逗号在 SQL 生成中具有低优先级且作为分隔符，特在此定义
  SqlBinaryOperator COMMA =
      new SqlBinaryOperator(",", SqlKind.OTHER, 2, false, null, null, null);

  //~ Methods ----------------------------------------------------------------

  /**
   * Resets this writer so that it can format another expression. Does not
   * affect formatting preferences (see {@link #resetSettings()}
   */
  // 重置 Writer 状态以生成新的表达式，但保留当前的格式化设置。
  void reset();

  /**
   * Resets all properties to their default values.
   */
  // 将所有格式化属性（如缩进空格数、是否大写等）恢复为默认值。
  void resetSettings();

  /**
   * Returns the dialect of SQL.
   *
   * @return SQL dialect
   */
  // 获取当前关联的 SQL 方言对象（SqlDialect）
  SqlDialect getDialect();

  /**
   * Returns the contents of this writer as a 'certified kocher' SQL string.
   *
   * @return SQL string
   */
  // 将当前 Writer 内部缓存的所有内容导出为最终的 SqlString。
  SqlString toSqlString();

  /**
   * Prints a literal, exactly as provided. Does not attempt to indent or
   * convert to upper or lower case. Does not add quotation marks. Adds
   * preceding whitespace if necessary.
   */
  // 原样打印字符串。不处理缩进、大小写或引号。用于打印已知常量。
  @Pure
  void literal(String s);

  /**
   * Prints a sequence of keywords. Must not start or end with space, but may
   * contain a space. For example, <code>keyword("SELECT")</code>, <code>
   * keyword("CHARACTER SET")</code>.
   */
  // 打印 SQL 关键字。Writer 会根据配置决定是否将其转换为大写/小写。
  @Pure
  void keyword(String s);

  /**
   * Prints a string, preceded by whitespace if necessary.
   */
  // 打印普通字符串，必要时会自动在前添加空格。
  @Pure
  void print(String s);

  /**
   * Prints an integer.
   *
   * @param x Integer
   */
  // 打印一个整数。
  @Pure
  void print(int x);

  /**
   * Prints an identifier, quoting as necessary.
   *
   * @param name   The identifier name
   * @param quoted Whether this identifier was quoted in the original sql statement,
   *               this may not be the only factor to decide whether this identifier
   *               should be quoted
   */
  // 打印标识符。会根据方言和 quoted 参数决定是否添加引号（如双引号或方括号）。
  void identifier(String name, boolean quoted);

  /**
   * Prints a dynamic parameter (e.g. {@code ?} for default JDBC)
   */
  // 打印动态参数占位符（如 JDBC 的 ?）。
  void dynamicParam(int index);

  /**
   * Prints the OFFSET/FETCH clause.
   */
  // 专门处理打印标准 SQL 的 FETCH NEXT 和 OFFSET 子句。
  void fetchOffset(@Nullable SqlNode fetch, @Nullable SqlNode offset);

  /**
   * Prints the TOP(n) clause.
   *
   * @see #fetchOffset
   */
  // 专门处理特定方言（如 SQL Server）的 TOP(n) 语法。
  void topN(@Nullable SqlNode fetch, @Nullable SqlNode offset);

  /**
   * Prints a new line, and indents.
   */
  // 强制强制换行并按当前层级缩进。
  void newlineAndIndent();

  /**
   * Returns whether this writer should quote all identifiers, even those
   * that do not contain mixed-case identifiers or punctuation.
   *
   * @return whether to quote all identifiers
   */
  // 是否对所有标识符加引号（即使不是保留字）。
  boolean isQuoteAllIdentifiers();

  /**
   * Returns whether this writer should start each clause (e.g. GROUP BY) on
   * a new line.
   *
   * @return whether to start each clause on a new line
   */
  // 每个主子句（如 GROUP BY）是否应该另起一行。
  boolean isClauseStartsLine();

  /**
   * Returns whether the items in the SELECT clause should each be on a
   * separate line.
   *
   * @return whether to put each SELECT clause item on a new line
   */
  // SELECT 的每个字段是否占用独立行。
  boolean isSelectListItemsOnSeparateLines();

  /**
   * Returns whether to output all keywords (e.g. SELECT, GROUP BY) in lower
   * case.
   *
   * @return whether to output SQL keywords in lower case
   */
  // 关键字是否使用小写（默认通常是大写）。
  boolean isKeywordsLowerCase();

  /**
   * Starts a list which is a call to a function.
   *
   * @see #endFunCall(Frame)
   */
  // 开始一个函数调用帧，打印函数名和左括号。
  @Pure
  Frame startFunCall(String funName);

  /**
   * Ends a list which is a call to a function.
   *
   * @param frame Frame
   * @see #startFunCall(String)
   */
  // 结束函数调用帧，打印右括号。
  @Pure
  void endFunCall(Frame frame);

  /**
   * Starts a list.
   */
  // 开始一个列表，指定开始符和结束符。
  // 如果你是从 SqlWriter 接口内部的方法来看，整个 SQL 构建的结构化入口通常是第一个 startList 的调用。
  @Pure
  Frame startList(String open, String close);

  /**
   * Starts a list with no opening string.
   *
   * @param frameType Type of list. For example, a SELECT list will be
   * governed according to SELECT-list formatting preferences.
   */
  // 据预定义的帧类型开始一个无起始符的列表。
  @Pure
  Frame startList(FrameTypeEnum frameType);

  /**
   * Starts a list.
   *
   * @param frameType Type of list. For example, a SELECT list will be
   *                  governed according to SELECT-list formatting preferences.
   * @param open      String to start the list; typically "(" or the empty
   *                  string.
   * @param close     String to close the list
   */
  // 最完整的启动方法，结合了帧类型和起止符。
  @Pure
  Frame startList(FrameType frameType, String open, String close);

  /**
   * Ends a list.
   *
   * @param frame The frame which was created by {@link #startList}.
   */
  // 结束指定的帧，恢复之前的缩进层级。
  @Pure
  void endList(@Nullable Frame frame);

  /**
   * Writes a list.
   */
  // 函数式接口，在一个帧内执行特定的写入动作。
  @Pure
  SqlWriter list(FrameTypeEnum frameType, Consumer<SqlWriter> action);

  /**
   * Writes a list separated by a binary operator
   * ({@link SqlStdOperatorTable#AND AND},
   * {@link SqlStdOperatorTable#OR OR}, or
   * {@link #COMMA COMMA}).
   */
  // 自动处理列表打印，使用指定的二元操作符（如 AND, OR, COMMA）作为分隔符连接 SqlNodeList 中的元素。
  @Pure
  SqlWriter list(FrameTypeEnum frameType, SqlBinaryOperator sepOp,
      SqlNodeList list);

  /**
   * Writes a list separator, unless the separator is "," and this is the
   * first occurrence in the list.
   *
   * @param sep List separator, typically ",".
   */
  // 打印分隔符。如果分隔符是逗号且是列表第一项，则可能忽略
  @Pure
  void sep(String sep);

  /**
   * Writes a list separator.
   *
   * @param sep        List separator, typically ","
   * @param printFirst Whether to print the first occurrence of the separator
   */
  // 打印分隔符，可显式指定是否在第一个元素前也打印。
  @Pure
  void sep(String sep, boolean printFirst);

  /**
   * Sets whether whitespace is needed before the next token.
   */
  // 手动标记下一个标记前是否需要空格。
  @Pure
  void setNeedWhitespace(boolean needWhitespace);

  /**
   * Returns the offset for each level of indentation. Default 4.
   */
  // 获取每一级缩进的空格数（默认 4）。
  int getIndentation();

  /**
   * Returns whether to enclose all expressions in parentheses, even if the
   * operator has high enough precedence that the parentheses are not
   * required.
   *
   * <p>For example, the parentheses are required in the expression <code>(a +
   * b) * c</code> because the '*' operator has higher precedence than the '+'
   * operator, and so without the parentheses, the expression would be
   * equivalent to <code>a + (b * c)</code>. The fully-parenthesized
   * expression, <code>((a + b) * c)</code> is unambiguous even if you don't
   * know the precedence of every operator.
   */
  // 是否在每个表达式外都强制加括号（忽略优先级）。
  boolean isAlwaysUseParentheses();

  /**
   * Returns whether we are currently in a query context (SELECT, INSERT,
   * UNION, INTERSECT, EXCEPT, and the ORDER BY operator).
   */
  // 检查当前是否处于查询上下文（如 SELECT 内部）。
  boolean inQuery();

  //~ Inner Interfaces -------------------------------------------------------

  /**
   * A Frame is a piece of generated text which shares a common indentation
   * level.
   *
   * <p>Every frame has a beginning, a series of clauses and separators, and
   * an end. A typical frame is a comma-separated list. It begins with a "(",
   * consists of expressions separated by ",", and ends with a ")".
   *
   * <p>A select statement is also a kind of frame. The beginning and end are
   * empty strings, but it consists of a sequence of clauses. "SELECT",
   * "FROM", "WHERE" are separators.
   *
   * <p>A frame is current between a call to one of the
   * {@link SqlWriter#startList} methods and the call to
   * {@link SqlWriter#endList(Frame)}. If other code starts a frame in the meantime,
   * the sub-frame is put onto a stack.
   */
  // 从文档注释中我们可以看到，Frame 代表了生成 SQL 文本中的一个逻辑块。这个块内的所有内容共享一个共同的缩进级别。
  // 你可以把 Frame 想象成一个“作用域”：当你进入一个帧时，缩进可能会增加；当你离开这个帧时，缩进会恢复到之前的状态。
  interface Frame {
  }

  /** Frame type. */
  // 主要职责是定义 SQL 生成过程中特定语法块（帧）的格式化行为，特别是关于名称标识和缩进逻辑。
  // FrameType 定义了一个“帧（Frame）”的分类属性。在 SQL 序列化过程中，SqlWriter 使用“帧”来管理嵌套结构（如子查询、函数调用、FROM 列表等）。FrameType 告诉 Writer：
  // 这个代码块在逻辑上属于什么类型（用于调试或方言判断）。
  // 当这个块内的内容需要换行时，是否应该增加缩进（Indent）。
  interface FrameType {
    /**
     * Returns the name of this frame type.
     *
     * @return name
     */
    // 返回该帧类型的唯一名称或标识符
    // 在 FrameTypeEnum 实现中，这通常返回枚举项的名称（如 "SELECT", "JOIN", "WHERE_LIST"）。
    String getName();

    /**
     * Returns whether this frame type should cause the code be further
     * indented.
     *
     * @return whether to further indent code within a frame of this type
     */
    // 指示该类型的帧在发生换行时，其内部内容是否需要进一步缩进。
    boolean needsIndent();
  }
}
