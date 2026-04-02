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

import org.apache.calcite.sql.pretty.SqlPrettyWriter;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

/** Configuration for {@link SqlWriter} and {@link SqlPrettyWriter}. */
// 定义了如何将 SQL 抽象语法树（AST，即 SqlNode 对象）序列化回 SQL 字符串时的格式化规则。
// SqlWriterConfig 的核心作用是 控制 SQL 的“漂亮打印”（Pretty Printing）格式。
// 由于 Calcite 经常需要将内部的 SqlNode 转换为特定数据库方言（Dialect）的 SQL 语句，这个接口提供了丰富的配置项，决定了生成的 SQL 是压缩在一行，还是按照特定的缩进、换行规则排列。
// 它使用了 immutables 库，这意味着配置对象是不可变的，每次修改属性都会返回一个新的配置实例。
// Immutables 是一个用于 Java 语言的注解处理器库，它的核心目标是自动生成高质量、类型安全且不可变的（Immutable）值对象（Value Objects）。
// Immutables 的工作原理
// 定义接口或抽象类：你只需要定义一个接口（如 SqlWriterConfig），并在其上添加 @Value.Immutable 注解。
// 编译时生成：在编译期间，库会扫描注解并生成一个以 Immutable 开头的实现类（例如 ImmutableSqlWriterConfig）。
// 使用生成的类：在代码中，你直接使用生成的实现类。
// Immutables 库就像是一个自动化的“Java Bean 增强器”，它提倡“不可变性”编程思想，让 Java 开发在处理数据对象时像 Scala 或 Kotlin 一样简洁高效。
@Value.Immutable
public interface SqlWriterConfig {
  /** Returns the dialect. */
  // 作用：指定 SQL 方言。
  // 详细：决定了标识符如何引用、特定的函数名、以及某些语法在不同数据库（如 Oracle, MySQL, Postgres）下的差异。
  @Nullable SqlDialect dialect();

  /** Sets {@link #dialect()}. */
  SqlWriterConfig withDialect(@Nullable SqlDialect dialect);

  /** Returns whether to print keywords (SELECT, AS, etc.) in lower-case.
   * Default is false: keywords are printed in upper-case. */
  // 作用：关键字大小写。
  // 详细：默认 false。如果为 true，SELECT、FROM 等关键字会显示为小写。
  @Value.Default default boolean keywordsLowerCase() {
    return false;
  }

  /** Sets {@link #keywordsLowerCase}. */
  SqlWriterConfig withKeywordsLowerCase(boolean keywordsLowerCase);

  /** Returns whether to quote all identifiers, even those which would be
   * correct according to the rules of the {@link SqlDialect} if quotation
   * marks were omitted. Default is true. */
  // 作用：强制引用标识符。
  // 详细：默认 true。若为 false，则仅在方言要求（如包含空格或关键字）时才加引号。
  @Value.Default default boolean quoteAllIdentifiers() {
    return true;
  }

  /** Sets {@link #quoteAllIdentifiers}. */
  SqlWriterConfig withQuoteAllIdentifiers(boolean quoteAllIdentifiers);

  /** Returns the number of spaces indentation. Default is 4. */
  // 作用：缩进空格数。
  // 详细：默认 4 个空格。
  @Value.Default default int indentation() {
    return 4;
  }

  /** Sets {@link #indentation}. */
  SqlWriterConfig withIndentation(int indentation);

  /** Returns whether a clause (FROM, WHERE, GROUP BY, HAVING, WINDOW,
   * ORDER BY) starts a new line. Default is true. SELECT is always at the
   * start of a line. */
  // 作用：子句（如 FROM, WHERE）是否另起一行。默认 true。
  @Value.Default default boolean clauseStartsLine() {
    return true;
  }

  /** Sets {@link #clauseStartsLine}. */
  SqlWriterConfig withClauseStartsLine(boolean clauseStartsLine);

  /** Returns whether a clause (FROM, WHERE, GROUP BY, HAVING, WINDOW,
   * ORDER BY) is followed by a new line. Default is false. */
  // 作用：子句关键字后是否立即换行。默认 false。
  @Value.Default default boolean clauseEndsLine() {
    return false;
  }

  /** Sets {@link #clauseEndsLine()}. */
  SqlWriterConfig withClauseEndsLine(boolean clauseEndsLine);

  /** Returns whether each item in a SELECT list, GROUP BY list, or ORDER BY
   * list is on its own line.
   *
   * <p>Default is false;
   * this property is superseded by {@link #selectFolding()},
   * {@link #groupByFolding()}, {@link #orderByFolding()}. */
  // 作用：SELECT 列表项是否独占一行。已被 selectFolding 取代。
  @Value.Default default boolean selectListItemsOnSeparateLines() {
    return false;
  }

  /** Sets {@link #selectListItemsOnSeparateLines}. */
  SqlWriterConfig withSelectListItemsOnSeparateLines(
      boolean selectListItemsOnSeparateLines);

  /** Returns the line-folding policy for lists in the SELECT, GROUP BY and
   * ORDER clauses, for items in the SET clause of UPDATE, and for items in
   * VALUES.
   *
   * @see #foldLength()
   *
   * <p>If not set, the values of
   * {@link #selectListItemsOnSeparateLines()},
   * {@link #valuesListNewline()},
   * {@link #updateSetListNewline()},
   * {@link #windowDeclListNewline()} are used. */
  // 它返回一个 LineFolding 枚举值，定义了在 SELECT、GROUP BY、ORDER BY、UPDATE SET 以及 VALUES 子句中，列表项的换行行为。
  @Nullable LineFolding lineFolding();

  /** Sets {@link #lineFolding()}. */
  SqlWriterConfig withLineFolding(@Nullable LineFolding lineFolding);

  /** Returns the line-folding policy for the SELECT clause.
   * If not set, the value of {@link #lineFolding()} is used. */
  // 作用：获取 SELECT 列表中字段的折叠策略。
  // 如果你明确设置了 selectFolding，则使用该值。
  // 如果未设置（为 null），它会**回退（Fallback）**到全局的 lineFolding() 策略。
  @Nullable LineFolding selectFolding();

  /** Sets {@link #selectFolding()}. */
  SqlWriterConfig withSelectFolding(@Nullable LineFolding lineFolding);

  /** Returns the line-folding policy for the FROM clause (and JOIN).
   * If not set, the value of {@link #lineFolding()} is used. */
  // 作用：获取 FROM 子句及其关联的 JOIN 操作的折叠策略。
  // 默认值：注意这里使用了 @Value.Default，默认返回 LineFolding.TALL。
  // 设计意图：Calcite 默认认为多表关联（JOIN）时，每个表占一行（TALL 模式）是最清晰的读法。
  @Value.Default default LineFolding fromFolding() {
    return LineFolding.TALL;
  }

  /** Sets {@link #fromFolding()}. */
  SqlWriterConfig withFromFolding(LineFolding lineFolding);

  /** Returns the line-folding policy for the WHERE clause.
   * If not set, the value of {@link #lineFolding()} is used. */
  // 作用：获取 WHERE 子句中过滤条件的折叠策略。
  @Nullable LineFolding whereFolding();

  /** Sets {@link #whereFolding()}. */
  SqlWriterConfig withWhereFolding(@Nullable LineFolding lineFolding);

  /** Returns the line-folding policy for the GROUP BY clause.
   * If not set, the value of {@link #lineFolding()} is used. */
  @Nullable LineFolding groupByFolding();

  /** Sets {@link #groupByFolding()}. */
  SqlWriterConfig withGroupByFolding(@Nullable LineFolding lineFolding);

  /** Returns the line-folding policy for the HAVING clause.
   * If not set, the value of {@link #lineFolding()} is used. */
  @Nullable LineFolding havingFolding();

  /** Sets {@link #havingFolding()}. */
  SqlWriterConfig withHavingFolding(@Nullable LineFolding lineFolding);

  /** Returns the line-folding policy for the WINDOW clause.
   * If not set, the value of {@link #lineFolding()} is used. */
  @Nullable LineFolding windowFolding();

  /** Sets {@link #windowFolding()}. */
  SqlWriterConfig withWindowFolding(@Nullable LineFolding lineFolding);

  /** Returns the line-folding policy for the MATCH_RECOGNIZE clause.
   * If not set, the value of {@link #lineFolding()} is used. */
  @Nullable LineFolding matchFolding();

  /** Sets {@link #matchFolding()}. */
  SqlWriterConfig withMatchFolding(@Nullable LineFolding lineFolding);

  /** Returns the line-folding policy for the ORDER BY clause.
   * If not set, the value of {@link #lineFolding()} is used. */
  @Nullable LineFolding orderByFolding();

  /** Sets {@link #orderByFolding()}. */
  SqlWriterConfig withOrderByFolding(@Nullable LineFolding lineFolding);

  /** Returns the line-folding policy for the OVER clause or a window
   * declaration. If not set, the value of {@link #lineFolding()} is used. */
  @Nullable LineFolding overFolding();

  /** Sets {@link #overFolding()}. */
  SqlWriterConfig withOverFolding(@Nullable LineFolding lineFolding);

  /** Returns the line-folding policy for the VALUES expression.
   * If not set, the value of {@link #lineFolding()} is used. */
  @Nullable LineFolding valuesFolding();

  /** Sets {@link #valuesFolding()}. */
  SqlWriterConfig withValuesFolding(@Nullable LineFolding lineFolding);

  /** Returns the line-folding policy for the SET clause of an UPDATE statement.
   * If not set, the value of {@link #lineFolding()} is used. */
  @Nullable LineFolding updateSetFolding();

  /** Sets {@link #updateSetFolding()}. */
  SqlWriterConfig withUpdateSetFolding(@Nullable LineFolding lineFolding);

  /**
   * Returns whether to use a fix for SELECT list indentations.
   *
   * <ul>
   * <li>If set to "false":
   *
   * <blockquote><pre>
   * SELECT
   *     A as A,
   *         B as B,
   *         C as C,
   *     D
   * </pre></blockquote>
   *
   * <li>If set to "true" (the default):
   *
   * <blockquote><pre>
   * SELECT
   *     A as A,
   *     B as B,
   *     C as C,
   *     D
   * </pre></blockquote>
   * </ul>
   */
  // 作用：修复 SELECT 列表的缩进对齐。
  // 详细：默认 true，使多行字段名左对齐。
  @Value.Default default boolean selectListExtraIndentFlag() {
    return true;
  }

  /** Sets {@link #selectListExtraIndentFlag}. */
  SqlWriterConfig withSelectListExtraIndentFlag(boolean selectListExtraIndentFlag);

  /** Returns whether each declaration in a WINDOW clause should be on its own
   * line.
   *
   * <p>Default is true;
   * this property is superseded by {@link #windowFolding()}. */
  // 作用：决定在一个 WINDOW 子句中，如果定义了多个窗口（例如 WINDOW w1 AS (...), w2 AS (...)），每个定义是否应该独占一行。
  @Value.Default default boolean windowDeclListNewline() {
    return true;
  }

  /** Sets {@link #windowDeclListNewline}. */
  SqlWriterConfig withWindowDeclListNewline(boolean windowDeclListNewline);

  /** Returns whether each row in a VALUES clause should be on its own
   * line.
   *
   * <p>Default is true;
   * this property is superseded by {@link #valuesFolding()}. */
  // 作用：决定 VALUES 子句中的每一行数据（Row）是否应该另起一行。
  @Value.Default default boolean valuesListNewline() {
    return true;
  }

  /** Sets {@link #valuesListNewline}. */
  SqlWriterConfig withValuesListNewline(boolean valuesListNewline);

  /** Returns whether each assignment in the SET clause of an UPDATE or MERGE
   * statement should be on its own line.
   *
   * <p>Default is true;
   * this property is superseded by {@link #updateSetFolding()}. */
  @Value.Default default boolean updateSetListNewline() {
    return true;
  }

  /** Sets {@link #updateSetListNewline}. */
  SqlWriterConfig withUpdateSetListNewline(boolean updateSetListNewline);

  /** Returns whether a WINDOW clause should start its own line. */
  // 作用：WINDOW 子句是否起始于新行。
  @Value.Default default boolean windowNewline() {
    return false;
  }

  /** Sets {@link #windowNewline}. */
  SqlWriterConfig withWindowNewline(boolean windowNewline);

  /** Returns whether commas in SELECT, GROUP BY and ORDER clauses should
   * appear at the start of the line. Default is false. */
  // 作用：控制逗号的位置。
  // 详细：若设为 true，在 SELECT 等列表中，逗号会出现在行首。这在大型 SQL 中很流行，因为方便屏蔽某一行。
  @Value.Default default boolean leadingComma() {
    return false;
  }

  /** Sets {@link #leadingComma()}. */
  SqlWriterConfig withLeadingComma(boolean leadingComma);

  /** Returns the sub-query style.
   * Default is {@link SqlWriter.SubQueryStyle#HYDE}. */
  // 作用：定义子查询的排版风格。
  // 默认值：HYDE（Calcite 特有的风格）。
  @Value.Default default SqlWriter.SubQueryStyle subQueryStyle() {
    return SqlWriter.SubQueryStyle.HYDE;
  }

  /** Sets {@link #subQueryStyle}. */
  SqlWriterConfig withSubQueryStyle(SqlWriter.SubQueryStyle subQueryStyle);

  /** Returns whether to print a newline before each AND or OR (whichever is
   * higher level) in WHERE clauses.
   *
   * <p>NOTE: Ignored when alwaysUseParentheses is set to true. */
  // 作用：WHERE 子句中的 AND 或 OR 之前是否强制换行。
  @Value.Default default boolean whereListItemsOnSeparateLines() {
    return false;
  }

  /** Sets {@link #whereListItemsOnSeparateLines}. */
  SqlWriterConfig withWhereListItemsOnSeparateLines(
      boolean whereListItemsOnSeparateLines);

  /** Returns whether expressions should always be included in parentheses.
   * Default is false. */
  // 作用：是否在每个表达式两端强制加上括号。
  // 默认值：false（仅在必要时加括号）。
  @Value.Default default boolean alwaysUseParentheses() {
    return false;
  }

  /** Sets {@link #alwaysUseParentheses}. */
  SqlWriterConfig withAlwaysUseParentheses(boolean alwaysUseParentheses);

  /** Returns the maximum line length. Default is zero, which means there is
   * no maximum. */
  @Value.Default default int lineLength() {
    return 0;
  }

  /** Sets {@link #lineLength}. */
  SqlWriterConfig withLineLength(int lineLength);

  /** Returns the line length at which items are chopped or folded (for clauses
   * that have chosen {@link LineFolding#CHOP} or {@link LineFolding#FOLD}).
   * Default is 80. */
  @Value.Default default int foldLength() {
    return 80;
  }

  /** Sets {@link #foldLength()}. */
  SqlWriterConfig withFoldLength(int lineLength);

  /** Returns whether the WHEN, THEN and ELSE clauses of a CASE expression
   * appear at the start of a new line. The default is false. */
  // 作用：控制 CASE 表达式。
  // 详细：若为 true，WHEN、THEN 和 ELSE 都会从新行开始。
  @Value.Default default boolean caseClausesOnNewLines() {
    return false;
  }

  /** Sets {@link #caseClausesOnNewLines}. */
  SqlWriterConfig withCaseClausesOnNewLines(boolean caseClausesOnNewLines);

  /** Policy for how to do deal with long lines.
   *
   * <p>The following examples all have
   * {@link #clauseEndsLine ClauseEndsLine=true},
   * {@link #indentation Indentation=4}, and
   * {@link #foldLength FoldLength=25} (so that the long {@code SELECT}
   * clause folds but the shorter {@code GROUP BY} clause does not).
   *
   * <p>Note that {@link #clauseEndsLine ClauseEndsLine} is observed in
   * STEP and TALL modes, and in CHOP mode when a line is long.
   *
   * <table border=1>
   * <caption>SQL formatted with each Folding value</caption>
   * <tr>
   *   <th>Folding</th>
   *   <th>Example</th>
   * </tr>
   *
   * <tr>
   *   <td>WIDE</td>
   *   <td><pre>
   * SELECT abc, def, ghi, jkl, mno, pqr
   * FROM t
   * GROUP BY abc, def</pre></td>
   * </tr>
   *
   * <tr>
   *   <td>STEP</td>
   *   <td><pre>
   * SELECT
   *     abc, def, ghi, jkl, mno, pqr
   * FROM t
   * GROUP BY
   *     abc, def</pre></td>
   * </tr>
   *
   * <tr>
   *   <td>FOLD</td>
   *   <td><pre>
   * SELECT abc, def, ghi,
   *     jkl, mno, pqr
   * FROM t
   * GROUP BY abc, def</pre></td>
   * </tr>
   *
   * <tr>
   *   <td>CHOP</td>
   *   <td><pre>
   * SELECT
   *     abc,
   *     def,
   *     ghi,
   *     jkl,
   *     mno,
   *     pqr
   * FROM t
   * GROUP BY abc, def</pre></td>
   * </tr>
   *
   * <tr>
   *   <td>TALL</td>
   *   <td><pre>
   * SELECT
   *     abc,
   *     def,
   *     ghi,
   *     jkl,
   *     mno,
   *     pqr
   * FROM t
   * GROUP BY
   *     abc,
   *     def</pre></td>
   * </tr>
   * </table>
   */
  enum LineFolding {
    /** Do not wrap. Items are on the same line, regardless of length. */
    WIDE,

    /** As {@link #WIDE} but start a new line if {@link #clauseEndsLine()}. */
    STEP,

    /** Wrap if long. Items are on the same line, but if the line's length
     * exceeds {@link #foldLength()}, move items to the next line. */
    FOLD,

    /** Chop down if long. Items are on the same line, but if the line grows
     * longer than {@link #foldLength()}, put all items on separate lines. */
    CHOP,

    /** Wrap always. Items are on separate lines. */
    TALL
  }

  /**
   * Create a default SqlWriterConfig object.
   *
   * @return The config.
   */
  static SqlWriterConfig of() {
    return ImmutableSqlWriterConfig.of();
  }
}
