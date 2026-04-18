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

import org.apache.calcite.linq4j.function.Experimental;
import org.apache.calcite.sql.fun.SqlLibrary;

/**
 * Enumeration of valid SQL compatibility modes.
 *
 * <p>For most purposes, one of the built-in compatibility modes in enum
 * {@link SqlConformanceEnum} will suffice.
 *
 * <p>If you wish to implement this interface to build your own conformance,
 * we strongly recommend that you extend {@link SqlAbstractConformance},
 * or use a {@link SqlDelegatingConformance},
 * so that you won't be broken by future changes.
 *
 * @see SqlConformanceEnum
 * @see SqlAbstractConformance
 * @see SqlDelegatingConformance
 */
// SqlConformance 接口是实现 SQL 方言兼容性 的核心。
// 由于不同的数据库（如 MySQL、Oracle、BigQuery）在 SQL 语法和语义上存在细微差别，Calcite 通过这个接口来控制验证器（Validator）和解析器（Parser）的行为，以适配不同的 SQL 标准或特定数据库习惯。
// 主要作用包括：
// 语法开关：决定解析器是否允许某些非标准的语法（例如：是否允许使用 != 代替 <>）。
// 语义控制：决定 SQL 逻辑的解释方式（例如：GROUP BY 1 是指按第一列分组，还是按常量 1 分组）。
// 兼容性模拟：通过组合不同的返回值，Calcite 可以模拟出类似 MySQL、Oracle 或 PostgreSQL 的行为，使得同一个查询引擎能处理多种风格的 SQL。
public interface SqlConformance {
  /** Short-cut for {@link SqlConformanceEnum#DEFAULT}. */
  @SuppressWarnings("unused")
  // 默认兼容性模式。
  @Deprecated // to be removed before 2.0
  SqlConformanceEnum DEFAULT = SqlConformanceEnum.DEFAULT;
  /** Short-cut for {@link SqlConformanceEnum#STRICT_92}. */
  @SuppressWarnings("unused")
  // 严格遵守 SQL-92、SQL-99 或 SQL-2003 标准。
  @Deprecated // to be removed before 2.0
  SqlConformanceEnum STRICT_92 = SqlConformanceEnum.STRICT_92;
  /** Short-cut for {@link SqlConformanceEnum#STRICT_99}. */
  @SuppressWarnings("unused")
  @Deprecated // to be removed before 2.0
  SqlConformanceEnum STRICT_99 = SqlConformanceEnum.STRICT_99;
  /** Short-cut for {@link SqlConformanceEnum#PRAGMATIC_99}. */
  @SuppressWarnings("unused")
  @Deprecated // to be removed before 2.0
  // 追求实用的 SQL 标准模式（略微宽松）。
  SqlConformanceEnum PRAGMATIC_99 = SqlConformanceEnum.PRAGMATIC_99;
  /** Short-cut for {@link SqlConformanceEnum#ORACLE_10}. */
  @SuppressWarnings("unused")
  @Deprecated // to be removed before 2.0
  // 模拟 Oracle 10 的兼容性。
  SqlConformanceEnum ORACLE_10 = SqlConformanceEnum.ORACLE_10;
  /** Short-cut for {@link SqlConformanceEnum#STRICT_2003}. */
  @SuppressWarnings("unused")
  @Deprecated // to be removed before 2.0
  // 代表严格遵守 ISO/IEC 9075:2003 (SQL:2003) 标准的兼容性模式
  SqlConformanceEnum STRICT_2003 = SqlConformanceEnum.STRICT_2003;
  /** Short-cut for {@link SqlConformanceEnum#PRAGMATIC_2003}. */
  @SuppressWarnings("unused")
  // 代表务实型（Pragmatic）的 SQL:2003 兼容性模式。
  @Deprecated // to be removed before 2.0
  SqlConformanceEnum PRAGMATIC_2003 = SqlConformanceEnum.PRAGMATIC_2003;

  /**
   * Whether this dialect supports features from a wide variety of
   * dialects. This is enabled for the Babel parser, disabled otherwise.
   */
  // 是否支持极其广泛的方言特性。
  // 这通常用于 Babel 解析器（Calcite 的一个混合方言解析器）。开启后，解析器会变得非常“大度”，尝试接受各种数据库的专有语法。
  boolean isLiberal();

  /**
   * Whether this dialect allows character literals as column aliases.
   *
   * <p>For example,
   *
   * <blockquote><pre>
   *   SELECT empno, sal + comm AS 'remuneration'
   *   FROM Emp</pre></blockquote>
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#BIG_QUERY},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#MYSQL_5},
   * {@link SqlConformanceEnum#SQL_SERVER_2008};
   * false otherwise.
   */
  // 是否允许使用字符串字面量（单引号包裹）作为列别名。
  // SELECT empno AS 'ID'。在 MySQL 中有效，在严格 SQL 标准中无效。
  boolean allowCharLiteralAlias();

  /**
   * Whether to allow aliases from the {@code SELECT} clause to be used as
   * column names in the {@code GROUP BY} clause.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#BIG_QUERY},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#MYSQL_5};
   * false otherwise.
   */
  // 是否允许在 GROUP BY 子句中使用 SELECT 列表中的别名
  boolean isGroupByAlias();

  /**
   * Whether {@code GROUP BY 2} is interpreted to mean 'group by the 2nd column
   * in the select list'.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#BIG_QUERY},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#MYSQL_5},
   * {@link SqlConformanceEnum#PRESTO};
   * false otherwise.
   */
  // 是否支持 GROUP BY 整数索引。
  // GROUP BY 2 表示按 SELECT 列表中的第 2 列分组。
  boolean isGroupByOrdinal();

  /**
   * Whether to allow aliases from the {@code SELECT} clause to be used as
   * column names in the {@code HAVING} clause.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#BIG_QUERY},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#MYSQL_5};
   * false otherwise.
   */
  // 是否允许在 HAVING 子句中使用别名。
  boolean isHavingAlias();

  /**
   * Whether '{@code ORDER BY 2}' is interpreted to mean 'sort by the 2nd
   * column in the select list'.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#DEFAULT},
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#MYSQL_5},
   * {@link SqlConformanceEnum#ORACLE_10},
   * {@link SqlConformanceEnum#ORACLE_12},
   * {@link SqlConformanceEnum#PRAGMATIC_99},
   * {@link SqlConformanceEnum#PRAGMATIC_2003},
   * {@link SqlConformanceEnum#PRESTO},
   * {@link SqlConformanceEnum#SQL_SERVER_2008},
   * {@link SqlConformanceEnum#STRICT_92};
   * false otherwise.
   */
  // 是否支持 ORDER BY 整数索引（如 ORDER BY 1）。
  boolean isSortByOrdinal();

  /**
   * Whether '{@code ORDER BY x}' is interpreted to mean 'sort by the select
   * list item whose alias is x' even if there is a column called x.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#DEFAULT},
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#BIG_QUERY},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#MYSQL_5},
   * {@link SqlConformanceEnum#ORACLE_10},
   * {@link SqlConformanceEnum#ORACLE_12},
   * {@link SqlConformanceEnum#SQL_SERVER_2008},
   * {@link SqlConformanceEnum#STRICT_92};
   * false otherwise.
   */
  // 是否支持按别名排序。如果列名和别名冲突，开启此项会优先选择别名。
  boolean isSortByAlias();

  /**
   * Whether "empno" is invalid in "select empno as x from emp order by empno"
   * because the alias "x" obscures it.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#STRICT_92};
   * false otherwise.
   */
  // 别名是否会遮蔽原始列名。
  // 在 STRICT_92 模式下，如果定义了别名 x 指向列 c1，那么在 ORDER BY 中直接使用 c1 可能被视为无效，必须使用 x。
  boolean isSortByAliasObscures();

  /**
   * Whether {@code FROM} clause is required in a {@code SELECT} statement.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#ORACLE_10},
   * {@link SqlConformanceEnum#ORACLE_12},
   * {@link SqlConformanceEnum#STRICT_92},
   * {@link SqlConformanceEnum#STRICT_99},
   * {@link SqlConformanceEnum#STRICT_2003};
   * false otherwise.
   */
  // SELECT 语句是否必须包含 FROM 子句。
  // Oracle 强制要求（如 SELECT 1 FROM DUAL），而 MySQL/PostgreSQL 允许 SELECT 1。
  boolean isFromRequired();

  /**
   * Whether to split a quoted table name. If true, {@code `x.y.z`} is parsed as
   * if the user had written {@code `x`.`y`.`z`}.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BIG_QUERY};
   * false otherwise.
   */
  // 是否自动拆分带引号的表名。
  // 开启后，`x.y.z` 会被解析为三部分：数据库 x，模式 y，表 z。主要用于 BigQuery。
  boolean splitQuotedTableName();

  /**
   * Whether to allow hyphens in an unquoted table name.
   *
   * <p>If true, {@code SELECT * FROM foo-bar.baz-buzz} is valid, and is parsed
   * as if the user had written {@code SELECT * FROM `foo-bar`.`baz-buzz`}.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BIG_QUERY};
   * false otherwise.
   */
  // 是否允许表名中出现连字符 -
  // SELECT * FROM my-table。
  boolean allowHyphenInUnquotedTableName();

  /**
   * Whether the bang-equal token != is allowed as an alternative to &lt;&gt; in
   * the parser.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#MYSQL_5},
   * {@link SqlConformanceEnum#ORACLE_10},
   * {@link SqlConformanceEnum#ORACLE_12},
   * {@link SqlConformanceEnum#PRESTO};
   * false otherwise.
   */
  // 是否允许使用 != 运算符。
  boolean isBangEqualAllowed();

  /**
   * Whether the "%" operator is allowed by the parser as an alternative to the
   * {@code mod} function.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#MYSQL_5},
   * {@link SqlConformanceEnum#PRESTO};
   * false otherwise.
   */
  // 是否允许使用 % 取模运算符（代替 MOD 函数）。
  boolean isPercentRemainderAllowed();

  /**
   * Whether {@code MINUS} is allowed as an alternative to {@code EXCEPT} in
   * the parser.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#ORACLE_10},
   * {@link SqlConformanceEnum#ORACLE_12};
   * false otherwise.
   *
   * <p>Note: MySQL does not support {@code MINUS} or {@code EXCEPT} (as of
   * version 5.5).
   */
  // 是否允许使用 MINUS（Oracle 风格）代替 EXCEPT（标准 SQL）。
  boolean isMinusAllowed();

  /**
   * Whether this dialect uses {@code $} (dollar) for indexing capturing groups
   * in the replacement string of regular expression functions such as
   * {@code REGEXP_REPLACE}. If false, the dialect uses {@code \\} (backslash)
   * for indexing capturing groups.
   *
   * <p>For example, {@code REGEXP_REPLACE("abc", "a(.)c", "X\\1")} in BigQuery
   * is equivalent to {@code REGEXP_REPLACE("abc", "a(.)c", "X$1")} in MySQL;
   * both produce the result "Xb".
   *
   * <p>Among the built-in conformance levels, false in
   * {@link SqlConformanceEnum#BIG_QUERY};
   * true otherwise.
   */
  // 正则表达式替换函数中，捕获组索引是否使用 $。
  // MySQL 使用 $1，而 BigQuery 使用 \\1。
  boolean isRegexReplaceCaptureGroupDollarIndexed();

  /**
   * Whether {@code CROSS APPLY} and {@code OUTER APPLY} operators are allowed
   * in the parser.
   *
   * <p>{@code APPLY} invokes a table-valued function for each row returned
   * by a table expression. It is syntactic sugar:<ul>
   *
   * <li>{@code SELECT * FROM emp CROSS APPLY TABLE(promote(empno)}<br>
   * is equivalent to<br>
   * {@code SELECT * FROM emp CROSS JOIN LATERAL TABLE(promote(empno)}
   *
   * <li>{@code SELECT * FROM emp OUTER APPLY TABLE(promote(empno)}<br>
   * is equivalent to<br>
   * {@code SELECT * FROM emp LEFT JOIN LATERAL TABLE(promote(empno)} ON true
   * </ul>
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#ORACLE_12},
   * {@link SqlConformanceEnum#SQL_SERVER_2008};
   * false otherwise.
   */
  // 是否支持 CROSS APPLY 和 OUTER APPLY。
  // 这是 SQL Server 和 Oracle 的特性，类似于标准 SQL 的 LATERAL JOIN。
  boolean isApplyAllowed();

  /**
   * Whether to allow {@code INSERT} (or {@code UPSERT}) with no column list
   * but fewer values than the target table.
   *
   * <p>The N values provided are assumed to match the first N columns of the
   * table, and for each of the remaining columns, the default value of the
   * column is used. It is an error if any of these columns has no default
   * value.
   *
   * <p>The default value of a column is specified by the {@code DEFAULT}
   * clause in the {@code CREATE TABLE} statement, or is {@code NULL} if the
   * column is not declared {@code NOT NULL}.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#PRAGMATIC_99},
   * {@link SqlConformanceEnum#PRAGMATIC_2003};
   * false otherwise.
   */
  // INSERT 语句在不写列名列表时，是否允许提供的 VALUES 数量少于表字段数。
  // 如果开启，缺少的列将自动使用默认值填充。
  boolean isInsertSubsetColumnsAllowed();

  /**
   * Whether directly alias array items in UNNEST.
   *
   * <p>E.g. in UNNEST(a_array, b_array) AS T(a, b),
   * a and b will be aliases of elements in a_array and b_array
   * respectively.
   *
   * <p>Without this flag set, T will be the alias
   * of the element in a_array and a, b will be the top level
   * fields of T if T is a STRUCT type.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#PRESTO};
   * false otherwise.
   */
  // 在 UNNEST 操作中，是否允许直接给数组元素起别名。
  // 主要针对 Presto 方言。
  boolean allowAliasUnnestItems();

  /**
   * Whether to allow parentheses to be specified in calls to niladic functions
   * and procedures (that is, functions and procedures with no parameters).
   *
   * <p>For example, {@code CURRENT_DATE} is a niladic system function. In
   * standard SQL it must be invoked without parentheses:
   *
   * <blockquote><code>VALUES CURRENT_DATE</code></blockquote>
   *
   * <p>If {@code allowNiladicParentheses}, the following syntax is also valid:
   *
   * <blockquote><code>VALUES CURRENT_DATE()</code></blockquote>
   *
   * <p>Of the popular databases, MySQL, Apache Phoenix and VoltDB allow this
   * behavior;
   * Apache Hive, HSQLDB, IBM DB2, Microsoft SQL Server, Oracle, PostgreSQL do
   * not.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#MYSQL_5};
   * false otherwise.
   */
  // 无参函数是否允许带括号。
  // CURRENT_DATE() 在标准 SQL 中不带括号，但在 MySQL 中可以带。
  boolean allowNiladicParentheses();

  /**
   * Whether to allow SQL syntax "{@code ROW(expr1, expr2, expr3)}".
   *
   * <p>The equivalent syntax in standard SQL is
   * "{@code (expr1, expr2, expr3)}".
   *
   * <p>Standard SQL does not allow this because the type is not
   * well-defined. However, PostgreSQL allows this behavior.
   *
   * <p>Standard SQL allows row expressions in other contexts, for instance
   * inside {@code VALUES} clause.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#DEFAULT},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#PRESTO};
   * false otherwise.
   */
  // 是否允许使用 ROW(e1, e2) 语法。
  boolean allowExplicitRowValueConstructor();

  /**
   * Whether to allow mixing table columns with extended columns in
   * {@code INSERT} (or {@code UPSERT}).
   *
   * <p>For example, suppose that the declaration of table {@code T} has columns
   * {@code A} and {@code B}, and you want to insert data of column
   * {@code C INTEGER} not present in the table declaration as an extended
   * column. You can specify the columns in an {@code INSERT} statement as
   * follows:
   *
   * <blockquote>
   *   <code>INSERT INTO T (A, B, C INTEGER) VALUES (1, 2, 3)</code>
   * </blockquote>
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#LENIENT};
   * false otherwise.
   */
  // 是否允许在 INSERT 语句中通过 EXTEND 语法定义临时扩展列。
  boolean allowExtend();

  /**
   * Whether to allow the SQL syntax "{@code LIMIT start, count}".
   *
   * <p>The equivalent syntax in standard SQL is
   * "{@code OFFSET start ROW FETCH FIRST count ROWS ONLY}",
   * and in PostgreSQL "{@code LIMIT count OFFSET start}".
   *
   * <p>MySQL and CUBRID allow this behavior.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#MYSQL_5};
   * false otherwise.
   */
  // 是否支持 LIMIT offset, count 语法（MySQL 风格）。
  boolean isLimitStartCountAllowed();

  /**
   * Whether to allow the SQL syntax "{@code OFFSET start LIMIT count}"
   * (that is, {@code OFFSET} before {@code LIMIT},
   * in addition to {@code LIMIT} before {@code OFFSET}
   * and {@code OFFSET} before {@code FETCH}).
   *
   * <p>The equivalent syntax in standard SQL is
   * "{@code OFFSET start ROW FETCH FIRST count ROWS ONLY}".
   *
   * <p>Trino allows this behavior.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#LENIENT};
   * false otherwise.
   */
  // 是否允许 OFFSET 在 LIMIT 之前。
  boolean isOffsetLimitAllowed();

  /**
   * Whether to allow geo-spatial extensions, including the GEOMETRY type.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#MYSQL_5},
   * {@link SqlConformanceEnum#PRESTO},
   * {@link SqlConformanceEnum#SQL_SERVER_2008};
   * false otherwise.
   */
  // 是否允许地理空间扩展（如 GEOMETRY 类型）。
  boolean allowGeometry();

  /**
   * Whether the least restrictive type of a number of CHAR types of different
   * lengths should be a VARCHAR type. And similarly BINARY to VARBINARY.
   *
   * <p>For example, consider the query
   *
   * <blockquote><pre>SELECT 'abcde' UNION SELECT 'xyz'</pre></blockquote>
   *
   * <p>The input columns have types {@code CHAR(5)} and {@code CHAR(3)}, and
   * we need a result type that is large enough for both:
   * <ul>
   * <li>Under strict SQL:2003 behavior, its column has type {@code CHAR(5)},
   *     and the value in the second row will have trailing spaces.
   * <li>With lenient behavior, its column has type {@code VARCHAR(5)}, and the
   *     values have no trailing spaces.
   * </ul>
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#PRAGMATIC_99},
   * {@link SqlConformanceEnum#PRAGMATIC_2003},
   * {@link SqlConformanceEnum#MYSQL_5},
   * {@link SqlConformanceEnum#ORACLE_10},
   * {@link SqlConformanceEnum#ORACLE_12},
   * {@link SqlConformanceEnum#PRESTO},
   * {@link SqlConformanceEnum#SQL_SERVER_2008};
   * false otherwise.
   */
  // UNION 操作中，不同长度的 CHAR 是否应转换为 VARCHAR。
  // 开启后可以防止短字符串被自动填充空格
  boolean shouldConvertRaggedUnionTypesToVarying();

  /**
   * Whether TRIM should support more than one trim character.
   *
   * <p>For example, consider the query
   *
   * <blockquote><pre>SELECT TRIM('eh' FROM 'hehe__hehe')</pre></blockquote>
   *
   * <p>Under strict behavior, if the length of trim character is not 1,
   * TRIM throws an exception, and the query fails.
   * However many implementations (in databases such as MySQL and SQL Server)
   * trim all the characters, resulting in a return value of '__'.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#MYSQL_5},
   * {@link SqlConformanceEnum#SQL_SERVER_2008};
   * false otherwise.
   */
  // TRIM 函数是否支持修剪多个字符（如 TRIM('abc' FROM '...abc')）
  boolean allowExtendedTrim();

  /**
   * Whether interval literals should allow plural time units
   * such as "YEARS" and "DAYS" in interval literals.
   *
   * <p>Under strict behavior, {@code INTERVAL '2' DAY} is valid
   * and {@code INTERVAL '2' DAYS} is invalid;
   * PostgreSQL allows both; Oracle only allows singular time units.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#LENIENT};
   * false otherwise.
   */
  // 时间间隔字面量是否允许复数形式（如 INTERVAL '2' DAYS）。
  boolean allowPluralTimeUnits();

  /**
   * Whether to allow a qualified common column in a query that has a
   * NATURAL join or a join with a USING clause.
   *
   * <p>For example, in the query
   *
   * <blockquote><pre>SELECT emp.deptno
   * FROM emp
   * JOIN dept USING (deptno)</pre></blockquote>
   *
   * <p>{@code deptno} is the common column. A qualified common column
   * such as {@code emp.deptno} is not allowed in Oracle, but is allowed
   * in PostgreSQL.
   *
   * <p>Among the built-in conformance levels, false in
   * {@link SqlConformanceEnum#ORACLE_10},
   * {@link SqlConformanceEnum#ORACLE_12},
   * {@link SqlConformanceEnum#PRESTO},
   * {@link SqlConformanceEnum#STRICT_92},
   * {@link SqlConformanceEnum#STRICT_99},
   * {@link SqlConformanceEnum#STRICT_2003};
   * true otherwise.
   */
  // 使用 USING 或 NATURAL JOIN 时，是否允许限定公共列（如 emp.deptno）。
  boolean allowQualifyingCommonColumn();

  /**
   * Whether {@code VALUE} is allowed as an alternative to {@code VALUES} in
   * the parser.
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * {@link SqlConformanceEnum#LENIENT},
   * {@link SqlConformanceEnum#MYSQL_5};
   * false otherwise.
   */
  // 是否允许使用 VALUE 关键字代替 VALUES
  boolean isValueAllowed();

  /**
   * Controls the behavior of operators that are part of Standard SQL but
   * nevertheless have different behavior in different databases.
   *
   * <p>Consider the {@code SUBSTRING} operator. In ISO standard SQL, negative
   * start indexes are converted to 1; in Google BigQuery, negative start
   * indexes are treated as offsets from the end of the string. For example,
   * {@code SUBSTRING('abcde' FROM -3 FOR 2)} returns {@code 'ab'} in standard
   * SQL and 'cd' in BigQuery.
   *
   * <p>If you specify {@code conformance=BIG_QUERY} in your connection
   * parameters, {@code SUBSTRING} will give the BigQuery behavior. Similarly
   * MySQL and Oracle.
   *
   * <p>Among the built-in conformance levels:
   * <ul>
   * <li>{@link SqlConformanceEnum#BIG_QUERY} returns
   *     {@link SqlLibrary#BIG_QUERY};
   * <li>{@link SqlConformanceEnum#MYSQL_5} returns {@link SqlLibrary#MYSQL};
   * <li>{@link SqlConformanceEnum#ORACLE_10} and
   *     {@link SqlConformanceEnum#ORACLE_12} return {@link SqlLibrary#ORACLE};
   * <li>otherwise returns {@link SqlLibrary#STANDARD}.
   * </ul>
   */
  // 定义某些内置算子的底层语义逻辑。
  // SUBSTRING 在标准 SQL 和 BigQuery 中对负数索引的处理完全不同，通过此方法返回对应的库标识。
  SqlLibrary semantics();

  /**
   * Whether to allow lenient type coercions.
   *
   * <p>Coercions include:
   * <ul>
   *
   * <li>Coercion of string literal to array literal. For example,
   * {@code SELECT ARRAY[0,1,2] == '{0,1,2}'}
   *
   * <li>Casting {@code BOOLEAN} values to one of the following numeric types:
   * {@code TINYINT}, {@code SMALLINT}, {@code INTEGER}, {@code BIGINT}.
   *
   * </ul>
   *
   * <p>Among the built-in conformance levels, true in
   * {@link SqlConformanceEnum#BABEL},
   * false otherwise.
   */
  // 是否允许宽松的类型强转。
  // 允许将字符串字面量赋给数组，或者将 BOOLEAN 转为 INT。
  @Experimental
  boolean allowLenientCoercion();
}
