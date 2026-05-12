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
package org.apache.calcite.sql.parser;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.TimeUnit;
import org.apache.calcite.config.CharLiteralStyle;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlUnresolvedFunction;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.util.Glossary;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

import static org.apache.calcite.util.Static.RESOURCE;

/**
 * Abstract base for parsers generated from CommonParser.jj.
 */
// SqlAbstractParserImpl 是 Apache Calcite 中 SQL 解析器的抽象基类。
// 该类是所有由 JavaCC（或其扩展模板 CommonParser.jj）生成的解析器实现的父类。它的主要作用包括：
// 统一接口：为不同的 SQL 方言解析器提供统一的调用接口。
// 上下文管理：管理解析过程中的状态（如动态参数数量、词法状态等）。
// 元数据维护：定义和提取 SQL 关键字、保留字、函数名等元数据信息。
// 工厂方法封装：提供创建 SqlNode（如 SqlCall）的辅助工具方法。
public abstract class SqlAbstractParserImpl {
  //~ Static fields/initializers ---------------------------------------------
  // 存储 SQL-92 标准定义的所有保留字集合。
  // 解析器以此为基准判断某个词是否可以作为普通标识符。
  private static final ImmutableSet<String> SQL_92_RESERVED_WORD_SET =
      ImmutableSet.of(
          "ABSOLUTE",
          "ACTION",
          "ADD",
          "ALL",
          "ALLOCATE",
          "ALTER",
          "AND",
          "ANY",
          "ARE",
          "AS",
          "ASC",
          "ASSERTION",
          "AT",
          "AUTHORIZATION",
          "AVG",
          "BEGIN",
          "BETWEEN",
          "BIT",
          "BIT_LENGTH",
          "BOTH",
          "BY",
          "CALL",
          "CASCADE",
          "CASCADED",
          "CASE",
          "CAST",
          "CATALOG",
          "CHAR",
          "CHARACTER",
          "CHARACTER_LENGTH",
          "CHAR_LENGTH",
          "CHECK",
          "CLOSE",
          "COALESCE",
          "COLLATE",
          "COLLATION",
          "COLUMN",
          "COMMIT",
          "CONDITION",
          "CONNECT",
          "CONNECTION",
          "CONSTRAINT",
          "CONSTRAINTS",
          "CONTAINS",
          "CONTINUE",
          "CONVERT",
          "CORRESPONDING",
          "COUNT",
          "CREATE",
          "CROSS",
          "CURRENT",
          "CURRENT_DATE",
          "CURRENT_PATH",
          "CURRENT_TIME",
          "CURRENT_TIMESTAMP",
          "CURRENT_USER",
          "CURSOR",
          "DATE",
          "DAY",
          "DEALLOCATE",
          "DEC",
          "DECIMAL",
          "DECLARE",
          "DEFAULT",
          "DEFERRABLE",
          "DEFERRED",
          "DELETE",
          "DESC",
          "DESCRIBE",
          "DESCRIPTOR",
          "DETERMINISTIC",
          "DIAGNOSTICS",
          "DISCONNECT",
          "DISTINCT",
          "DOMAIN",
          "DOUBLE",
          "DROP",
          "ELSE",
          "END",
          "ESCAPE",
          "EXCEPT",
          "EXCEPTION",
          "EXEC",
          "EXECUTE",
          "EXISTS",
          "EXTERNAL",
          "EXTRACT",
          "FALSE",
          "FETCH",
          "FIRST",
          "FLOAT",
          "FOR",
          "FOREIGN",
          "FOUND",
          "FROM",
          "FULL",
          "FUNCTION",
          "GET",
          "GLOBAL",
          "GO",
          "GOTO",
          "GRANT",
          "GROUP",
          "HAVING",
          "HOUR",
          "IDENTITY",
          "IMMEDIATE",
          "IN",
          "INADD",
          "INDICATOR",
          "INITIALLY",
          "INNER",
          "INOUT",
          "INPUT",
          "INSENSITIVE",
          "INSERT",
          "INT",
          "INTEGER",
          "INTERSECT",
          "INTERVAL",
          "INTO",
          "IS",
          "ISOLATION",
          "JOIN",
          "KEY",
          "LANGUAGE",
          "LAST",
          "LEADING",
          "LEFT",
          "LEVEL",
          "LIKE",
          "LOCAL",
          "LOWER",
          "MATCH",
          "MAX",
          "MIN",
          "MINUTE",
          "MODULE",
          "MONTH",
          "NAMES",
          "NATIONAL",
          "NATURAL",
          "NCHAR",
          "NEXT",
          "NO",
          "NOT",
          "NULL",
          "NULLIF",
          "NUMERIC",
          "OCTET_LENGTH",
          "OF",
          "ON",
          "ONLY",
          "OPEN",
          "OPTION",
          "OR",
          "ORDER",
          "OUT",
          "OUTADD",
          "OUTER",
          "OUTPUT",
          "OVERLAPS",
          "PAD",
          "PARAMETER",
          "PARTIAL",
          "PATH",
          "POSITION",
          "PRECISION",
          "PREPARE",
          "PRESERVE",
          "PRIMARY",
          "PRIOR",
          "PRIVILEGES",
          "PROCEDURE",
          "PUBLIC",
          "READ",
          "REAL",
          "REFERENCES",
          "RELATIVE",
          "RESTRICT",
          "RETURN",
          "RETURNS",
          "REVOKE",
          "RIGHT",
          "ROLLBACK",
          "ROUTINE",
          "ROWS",
          "SCHEMA",
          "SCROLL",
          "SECOND",
          "SECTION",
          "SELECT",
          "SESSION",
          "SESSION_USER",
          "SET",
          "SIZE",
          "SMALLINT",
          "SOME",
          "SPACE",
          "SPECIFIC",
          "SQL",
          "SQLCODE",
          "SQLERROR",
          "SQLEXCEPTION",
          "SQLSTATE",
          "SQLWARNING",
          "SUBSTRING",
          "SUM",
          "SYSTEM_USER",
          "TABLE",
          "TEMPORARY",
          "THEN",
          "TIME",
          "TIMESTAMP",
          "TIMEZONE_HOUR",
          "TIMEZONE_MINUTE",
          "TO",
          "TRAILING",
          "TRANSACTION",
          "TRANSLATE",
          "TRANSLATION",
          "TRIM",
          "TRUE",
          "UNION",
          "UNIQUE",
          "UNKNOWN",
          "UPDATE",
          "UPPER",
          "USAGE",
          "USER",
          "USING",
          "VALUE",
          "VALUES",
          "VARCHAR",
          "VARYING",
          "VIEW",
          "WHEN",
          "WHENEVER",
          "WHERE",
          "WITH",
          "WORK",
          "WRITE",
          "YEAR",
          "ZONE");

  //~ Enums ------------------------------------------------------------------

  /**
   * Type-safe enum for context of acceptable expressions.
   */
  // 用于在 SQL 解析过程中进行语法上下文校验。
  // SQL 解析是一个从顶向下的过程。当解析器递归到某一个语法节点时，它需要知道当前位置“合法”的表达式类型是什么。例如，在 WHERE 子句后面可以接普通的逻辑表达式，但通常不能直接接一个不带括号的 UNION 查询
  // 每个常量代表一种“准入规则”：
  protected enum ExprContext {
    /**
     * Accept any kind of expression in this context.
     */
    // 全通配。
    // 在这个上下文中，任何类型的 SqlNode 都是合法的。
    ACCEPT_ALL,

    /**
     * Accept any kind of expression in this context, with the exception of
     * CURSOR constructors.
     */
    // 接受除游标（CURSOR）构造以外的所有表达式。
    ACCEPT_NONCURSOR,

    /**
     * Accept  only query expressions in this context.
     *
     * <p>Valid: "SELECT x FROM a",
     * "SELECT x FROM a UNION SELECT y FROM b",
     * "TABLE a",
     * "VALUES (1, 2), (3, 4)",
     * "(SELECT x FROM a UNION SELECT y FROM b) INTERSECT SELECT z FROM c",
     * "(SELECT x FROM a UNION SELECT y FROM b) ORDER BY 1 LIMIT 10".
     * Invalid: "e CROSS JOIN d".
     * Debatable: "(SELECT x FROM a)".
     */
    // 仅接受查询表达式。
    // 范围：包括 SELECT、UNION、VALUES、TABLE 语句以及带 ORDER BY / LIMIT 的查询。
    // 注意：它不接受像 e CROSS JOIN d 这样的裸连接表达式（因为连接通常属于 FROM 子句的一部分，而不是独立的查询）。
    ACCEPT_QUERY,

    /**
     * Accept only query expressions or joins in this context.
     *
     * <p>Valid: "(SELECT x FROM a)",
     * "e CROSS JOIN d",
     * "((SELECT x FROM a) CROSS JOIN d)",
     * "((e CROSS JOIN d) LEFT JOIN c)".
     * Invalid: "e, d",
     * "SELECT x FROM a",
     * "(e)".
     */
    // 接受查询表达式或连接（Join）表达式。
    // 应用场景：通常用于 FROM 子句或括号嵌套的表引用中，这里既可以是子查询，也可以是连接表。
    ACCEPT_QUERY_OR_JOIN,

    /**
     * Accept only non-query expressions in this context.
     */
    // 仅接受非查询表达式。
    // 范围：例如常量、标识符、函数调用等，但不允许直接出现 SELECT ...。
    ACCEPT_NON_QUERY,

    /**
     * Accept only parenthesized queries or non-query expressions in this
     * context.
     */
    // 含义：接受带括号的子查询或非查询表达式。
    ACCEPT_SUB_QUERY,

    /**
     * Accept only CURSOR constructors, parenthesized queries, or non-query
     * expressions in this context.
     */
    // 接受游标构造（CURSOR(SELECT ...)）、带括号的查询或非查询表达式。
    ACCEPT_CURSOR;

    @Deprecated // to be removed before 2.0
    public static final ExprContext ACCEPT_SUBQUERY = ACCEPT_SUB_QUERY;

    @Deprecated // to be removed before 2.0
    public static final ExprContext ACCEPT_NONQUERY = ACCEPT_NON_QUERY;
    // 检查解析出来的 SqlNode 是否符合当前的上下文规则，如果不符合则立即抛出解析异常。
    public void throwIfNotCompatible(SqlNode e) {
      switch (this) {
      // 场景 A：不允许直接出现查询语句的上下文
      case ACCEPT_NON_QUERY:
      case ACCEPT_SUB_QUERY:
      case ACCEPT_CURSOR:
        // 如果 e 是一个查询（如 SELECT, UNION, VALUES 等）
        if (e.isA(SqlKind.QUERY)) {
          throw SqlUtil.newContextException(e.getParserPosition(),
              RESOURCE.illegalQueryExpression());
        }
        break;
      // 场景 B：强制要求必须是查询语句的上下文
      case ACCEPT_QUERY:
        // 如果 e 不是查询类型
        if (!e.isA(SqlKind.QUERY)) {
          throw SqlUtil.newContextException(e.getParserPosition(),
              RESOURCE.illegalNonQueryExpression());
        }
        break;
      // 场景 C：FROM 子句风格的上下文
      case ACCEPT_QUERY_OR_JOIN:
        // 既不是查询，也不是 JOIN 节点
        if (!e.isA(SqlKind.QUERY) && e.getKind() != SqlKind.JOIN) {
          throw SqlUtil.newContextException(e.getParserPosition(),
              RESOURCE.expectedQueryOrJoinExpression());
        }
        break;
      default:
        // 对于 ACCEPT_ALL 等，不做任何限制
        break;
      }
    }
  }

  //~ Instance fields --------------------------------------------------------
  // 记录解析过程中遇到的动态参数（即问号 ?）的总数
  protected int nDynamicParams;
  // 存储正在解析的原始 SQL 文本，主要用于在异常中显示报错上下文。
  protected @Nullable String originalSql;
  // 存储解析过程中产生的非致命性警告信息列表。
  protected final List<CalciteContextException> warnings = new ArrayList<>();

  //~ Methods ----------------------------------------------------------------

  /**
   * Returns immutable set of all reserved words defined by SQL-92.
   * @see Glossary#SQL92 SQL-92 Section 5.2
   */
  // 返回 SQL-92 保留字的不可变集合。
  public static Set<String> getSql92ReservedWords() {
    return SQL_92_RESERVED_WORD_SET;
  }

  /**
   * Creates a call.
   *
   * @param funName           Name of function
   * @param pos               Position in source code
   * @param funcType          Type of function
   * @param functionQualifier Qualifier
   * @param operands          Operands to call
   * @return Call
   */
  // 核心作用是将解析过程中识别到的函数名和参数列表封装成一个临时的“函数调用”对象（SqlCall）
  @SuppressWarnings("argument.type.incompatible")
  protected SqlCall createCall(
      // 函数或操作符的标识符。
      // 作用：代表 SQL 中的函数名（如 ABS, COUNT, MY_CUSTOM_FUNC）。它是一个 SqlIdentifier，说明它可以是单部分名称，也可以是带限定符的多部分名称（如 schema.func）。
      SqlIdentifier funName,
      SqlParserPos pos,
      // 函数分类。
      // 用于标记该函数的类型，例如是用户自定义函数 (UDF)、系统内置函数、还是特定的表函数等。这有助于后续的函数查找（Lookup）逻辑。
      SqlFunctionCategory funcType,
      // 函数限定符。
      // 作用：主要用于处理类似 DISTINCT 或 ALL 这样的修饰词。例如在 COUNT(DISTINCT x) 中，DISTINCT 就会通过这个参数传递。
      SqlLiteral functionQualifier,
      // 操作数（参数）列表。
      // 作用：这是一个可迭代对象，包含了传递给函数的所有参数（如 f(a, b) 中的 a 和 b）。这些参数在 AST 中也是 SqlNode 节点。
      Iterable<? extends SqlNode> operands) {
    return createCall(funName, pos, funcType, functionQualifier,
        Iterables.toArray(operands, SqlNode.class));
  }

  /**
   * Creates a call.
   *
   * @param funName           Name of function
   * @param pos               Position in source code
   * @param funcType          Type of function
   * @param functionQualifier Qualifier
   * @param operands          Operands to call
   * @return Call
   */
  // 实际执行对象创建逻辑的核心实现。
  // 在 SQL 解析阶段，解析器（Parser）只负责识别语法结构，而不知道某个函数是否真的存在。因此，这个方法的作用是创建一个“占位符”节点。
  protected SqlCall createCall(
      // 函数的名称标识符（如 ABS 或 MY_SCHEMA.MY_FUNC）
      // 后续在验证阶段（Validation）查找具体函数定义（SqlOperator）的唯一凭据。
      SqlIdentifier funName,
      SqlParserPos pos,
      // 函数分类枚举。
      SqlFunctionCategory funcType,
      // 函数限定符。
      // 例子：在 COUNT(DISTINCT x) 中，这个参数代表 DISTINCT 关键字。
      SqlLiteral functionQualifier,
      // 操作数（参数）数组。
      SqlNode[] operands) {
    // Create a placeholder function.  Later, during
    // validation, it will be resolved into a real function reference.
    // 创建未解析函数的占位符
    // 这是一个关键类。因为此时解析器不知道函数的返回类型、参数类型或推导逻辑，所以它创建了一个“未解析”的函数对象。
    SqlOperator fun = new SqlUnresolvedFunction(funName, null, null, null, null,
        funcType);
    // 会返回一个 SqlCall 对象（通常是 SqlBasicCall）。
    // 结果：最终生成的 SqlCall 节点被插入到 AST（抽象语法树）中。
    return fun.createCall(functionQualifier, pos, operands);
  }

  /**
   * Returns metadata about this parser: keywords, etc.
   */
  // 获取解析器的元数据实现。
  public abstract Metadata getMetadata();

  /**
   * Removes or transforms misleading information from a parse exception or
   * error, and converts to {@link SqlParseException}.
   *
   * @param ex dirty excn
   * @return clean excn
   */
  // 将底层解析引擎抛出的原始异常（如 JavaCC 产生的异常）转换为 Calcite 统一的 SqlParseException。
  public abstract SqlParseException normalizeException(@Nullable Throwable ex);

  protected abstract SqlParserPos getPos() throws Exception;

  /**
   * Reinitializes parser with new input.
   * @param reader provides new input
   */
  // 重新初始化解析器，使其可以从新的字符流中读取数据。
  // CHECKSTYLE: IGNORE 1
  public abstract void ReInit(Reader reader);

  /**
   * Parses a SQL expression ending with EOF and constructs a
   * parse tree.
   * @return constructed parse tree.
   */
  // 解析单个 SQL 表达式（如 1 + 2）直至流末尾。
  public abstract SqlNode parseSqlExpressionEof() throws Exception;

  /**
   * Parses a SQL statement ending with EOF and constructs a
   * parse tree.
   * @return constructed parse tree.
   */
  // 解析单个完整的 SQL 语句（如 SELECT...）直至流末尾。
  // 语句是一个完整的 SQL 指令，它由一个或多个表达式、关键字和子句组成，用于执行数据库操作，语句通常代表一个数据库操作（例如查询、插入、更新或删除数据等），并可能包含多个表达式、子查询等
  public abstract SqlNode parseSqlStmtEof() throws Exception;

  /**
   * Parses a list of SQL statements separated by semicolon and constructs a
   * parse tree. The semicolon is required between statements, but is
   * optional at the end.
   * @return constructed list of SQL statements.
   */
  // 解析一组以分号分隔的 SQL 语句。
  // 解析用分号分割的sql语句列表，分号在语句之间必须有，在末尾可以忽略
  public abstract SqlNodeList parseSqlStmtList() throws Exception;

  /**
   * Sets the tab stop size.
   *
   * @param tabSize Tab stop size
   */
  // 设置解析器处理制表符（Tab）的宽度，用于计算准确的报错列位置。
  public abstract void setTabSize(int tabSize);

  /**
   * Sets the casing policy for quoted identifiers.
   * @param quotedCasing Casing to set.
   */
  // 设置带引号标识符的大小写策略。
  public abstract void setQuotedCasing(Casing quotedCasing);

  /**
   * Sets the casing policy for unquoted identifiers.
   * @param unquotedCasing Casing to set.
   */
  // 设置不带引号标识符的大小写策略。
  public abstract void setUnquotedCasing(Casing unquotedCasing);

  /**
   * Sets the maximum length for sql identifier.
   */
  // 设置标识符允许的最大长度。
  public abstract void setIdentifierMaxLength(int identifierMaxLength);

  /**
   * Sets the map from identifier to time unit.
   */
  // 设置时间单位代码。
  @Deprecated // to be removed before 2.0
  public void setTimeUnitCodes(Map<String, TimeUnit> timeUnitCodes) {
  }

  /**
   * Sets the SQL language conformance level.
   */
  // 设置解析器遵循的 SQL 兼容性标准。
  public abstract void setConformance(SqlConformance conformance);

  /**
   * Parses string to array literal.
   */
  // 专门用于解析数组字面量的逻辑。
  public abstract SqlNode parseArray() throws SqlParseException;

  /**
   * Sets the SQL text that is being parsed.
   */
  public void setOriginalSql(String originalSql) {
    this.originalSql = originalSql;
  }

  /**
   * Returns the SQL text.
   */
  public @Nullable String getOriginalSql() {
    return originalSql;
  }

  /**
   * Change parser state.
   *
   * @param state New state
   */
  // 切换解析器的词法状态（如从普通模式切换到处理反引号模式）。
  public abstract void switchTo(LexicalState state);

  //~ Inner Interfaces -------------------------------------------------------

  /** Valid starting states of the parser.
   *
   * <p>(There are other states that the parser enters during parsing, such as
   * being inside a multi-line comment.)
   *
   * <p>The starting states generally control the syntax of quoted
   * identifiers. */
  public enum LexicalState {
    /** Starting state where quoted identifiers use brackets, like Microsoft SQL
     * Server. 这是默认的词法状态，通常会使用方括号 [] 来表示引用标识符。
     * 这种状态下，SQL 标识符（如表名、列名）用方括号括起来，如 SELECT [column] FROM [table]。
     * 类似的 SQL 方言有 Microsoft SQL Server*/
    DEFAULT,

    /** Starting state where quoted identifiers use double-quotes, like
     * Oracle and PostgreSQL.Double Quote Identifiers， 在该状态下，标识符使用双引号 " 来包围。例如，SELECT "column" FROM "table"。这是常见的 SQL 方言（如 Oracle 和 PostgreSQL）中的标识符语法 */
    DQID,

    /** Starting state where quoted identifiers use back-ticks, like MySQL.Backtick Identifiers，在该状态下，标识符使用反引号 ` 来包围。例如，SELECT columnFROMtable``。这种语法常见于 MySQL 中 */
    BTID,

    /** Starting state where quoted identifiers use back-ticks,
     * unquoted identifiers that are part of table names may contain hyphens,
     * and character literals may be enclosed in single- or double-quotes,
     * like BigQuery. */
    BQID; //这种语法是 Google BigQuery 中使用的标识符语法

    /** Returns the corresponding parser state with the given configuration
     * (in particular, quoting style). */
    public static LexicalState forConfig(SqlParser.Config config) {
      switch (config.quoting()) {
      case BRACKET:
        return DEFAULT;
      case DOUBLE_QUOTE:
        return DQID;
      case BACK_TICK_BACKSLASH:
        return BQID;
      case BACK_TICK:
        if (config.conformance().allowHyphenInUnquotedTableName()
            && config.charLiteralStyles().equals(
                EnumSet.of(CharLiteralStyle.BQ_SINGLE,
                    CharLiteralStyle.BQ_DOUBLE))) {
          return BQID;
        }
        if (!config.conformance().allowHyphenInUnquotedTableName()
            && config.charLiteralStyles().equals(
                EnumSet.of(CharLiteralStyle.STANDARD))) {
          return BTID;
        }
        // fall through
      default:
        throw new AssertionError(config);
      }
    }
  }

  /**
   * Metadata about the parser. For example:
   *
   * <ul>
   * <li>"KEY" is a keyword: it is meaningful in certain contexts, such as
   * "CREATE FOREIGN KEY", but can be used as an identifier, as in <code>
   * "CREATE TABLE t (key INTEGER)"</code>.
   * <li>"SELECT" is a reserved word. It can not be used as an identifier.
   * <li>"CURRENT_USER" is the name of a context variable. It cannot be used
   * as an identifier.
   * <li>"ABS" is the name of a reserved function. It cannot be used as an
   * identifier.
   * <li>"DOMAIN" is a reserved word as specified by the SQL:92 standard.
   * </ul>
   */
  public interface Metadata {
    /** 是否是非保留关键字
     * Returns true if token is a keyword but not a reserved word. For
     * example, "KEY".
     */
    boolean isNonReservedKeyword(String token);

    /** 是否是上下文变量，例如CURRENT_USER
     * Returns whether token is the name of a context variable such as
     * "CURRENT_USER".
     */
    boolean isContextVariableName(String token);

    /**是否是保留的函数名
     * Returns whether token is a reserved function name such as
     * "CURRENT_USER".
     */
    boolean isReservedFunctionName(String token);

    /**是否是关键字，在 SQL 标准中具有特定语法作用的词汇，通常是命令、操作符等。SELECT、FROM、WHERE、INSERT、UPDATE
     * Returns whether token is a keyword. (That is, a non-reserved keyword,
     * a context variable, or a reserved function name.)
     */
    boolean isKeyword(String token);

    /** 是否是保留字，在 SQL 中被保留以供将来可能使用的词汇，当前不一定有语法作用，CURRENT_TIME、USER、CONSTRAINT、PRIMARY
     * Returns whether token is a reserved word.
     */
    boolean isReservedWord(String token);

    /**
     * Returns whether token is a reserved word as specified by the SQL:92
     * standard.
     */
    boolean isSql92ReservedWord(String token);

    /**
     * Returns comma-separated list of JDBC keywords.
     */
    String getJdbcKeywords();

    /**
     * Returns a list of all tokens in alphabetical order.
     */
    List<String> getTokens();
  }

  //~ Inner Classes ----------------------------------------------------------

  /**
   * Default implementation of the {@link Metadata} interface.
   */
  public static class MetadataImpl implements Metadata {
    private final Set<String> reservedFunctionNames = new HashSet<>(); //存储 SQL 中的保留函数名的集合
    private final Set<String> contextVariableNames = new HashSet<>(); //存储 SQL 中的上下文变量名集合。上下文变量是 SQL 语句中的特殊标识符（例如 CURRENT_USER），它们在查询执行时会根据当前的上下文返回不同的值
    private final Set<String> nonReservedKeyWordSet = new HashSet<>(); //存储 SQL 中的非保留关键字集合。非保留关键字在特定上下文中可能有特殊含义，但在其他地方可以作为普通标识符使用。例如，KEY 是关键字，但在某些 SQL 方言中可以作为表的列名

    /**
     * Set of all tokens.
     */
    private final NavigableSet<String> tokenSet = new TreeSet<>(); //存储 SQL 解析器识别的所有符号（包括关键字、标识符、函数名等），用于构建整个解析器的符号库

    /**
     * Immutable list of all tokens, in alphabetical order.
     */
    private final List<String> tokenList;//包含所有 SQL 解析器识别的符号。这个列表是从 tokenSet 中生成的，确保符号按顺序排列
    private final Set<String> reservedWords = new HashSet<>(); //存储SQL中所有的保留字。保留字是不能作为标识符使用的关键字。比如 SQL-92 标准定义的保留字，如 SELECT, FROM, WHERE 等
    private final String sql92ReservedWords;

    /**
     * Creates a MetadataImpl.
     *
     * @param sqlParser Parser
     */
    public MetadataImpl(SqlAbstractParserImpl sqlParser) {
      initList(sqlParser, reservedFunctionNames, "ReservedFunctionName");
      initList(sqlParser, contextVariableNames, "ContextVariable");
      initList(sqlParser, nonReservedKeyWordSet, "NonReservedKeyWord");
      tokenList = ImmutableList.copyOf(tokenSet);
      sql92ReservedWords = constructSql92ReservedWordList();
      Set<String> reservedWordSet = new TreeSet<>();
      reservedWordSet.addAll(tokenSet);
      reservedWordSet.removeAll(nonReservedKeyWordSet);
      reservedWords.addAll(reservedWordSet);
    }

    /**
     * Initializes lists of keywords.
     */
    private void initList(
        @UnderInitialization MetadataImpl this,
        SqlAbstractParserImpl parserImpl,
        Set<String> keywords,
        String name) {
      parserImpl.ReInit(new StringReader("1"));
      try {
        Object o = virtualCall(parserImpl, name); //希望这里的调用一定抛出SqlParseException，否则不符合逻辑，下面接着抛出异常
        throw new AssertionError("expected call to fail, got " + o);
      } catch (SqlParseException parseException) {
        // First time through, build the list of all tokens.
        final String[] tokenImages = parseException.getTokenImages();
        if (tokenSet.isEmpty()) {
          for (String token : tokenImages) {
            String tokenVal = SqlParserUtil.getTokenVal(token);
            if (tokenVal != null) {
              tokenSet.add(tokenVal);
            }
          }
        }

        // Add the tokens which would have been expected in this
        // syntactic context to the list we're building.
        final int[][] expectedTokenSequences =
            parseException.getExpectedTokenSequences();
        for (final int[] tokens : expectedTokenSequences) {
          assert tokens.length == 1;
          final int tokenId = tokens[0];
          String token = tokenImages[tokenId];
          String tokenVal = SqlParserUtil.getTokenVal(token);
          if (tokenVal != null) {
            keywords.add(tokenVal);
          }
        }
      } catch (Throwable e) {
        throw new RuntimeException("While building token lists", e);
      }
    }

    /**
     * Uses reflection to invoke a method on this parser. The method must be
     * public and have no parameters.
     * 通过反射调用以name为名字的方法
     * @param parserImpl Parser
     * @param name       Name of method. For example "ReservedFunctionName".
     * @return Result of calling method
     */
    private @Nullable Object virtualCall(
        @UnderInitialization MetadataImpl this,
        SqlAbstractParserImpl parserImpl,
        String name) throws Throwable {
      Class<?> clazz = parserImpl.getClass();
      try {
        final Method method = clazz.getMethod(name);
        return method.invoke(parserImpl);
      } catch (InvocationTargetException e) {
        Throwable cause = e.getCause();
        throw parserImpl.normalizeException(cause);
      }
    }

    /**
     * Builds a comma-separated list of JDBC reserved words.
     */
    private String constructSql92ReservedWordList(
        @UnderInitialization MetadataImpl this) {
      StringBuilder sb = new StringBuilder();
      TreeSet<String> jdbcReservedSet = new TreeSet<>();
      jdbcReservedSet.addAll(tokenSet);
      jdbcReservedSet.removeAll(SQL_92_RESERVED_WORD_SET);
      jdbcReservedSet.removeAll(nonReservedKeyWordSet);
      int j = 0;
      for (String jdbcReserved : jdbcReservedSet) {
        if (j++ > 0) {
          sb.append(",");
        }
        sb.append(jdbcReserved);
      }
      return sb.toString();
    }

    @Override
    public List<String> getTokens() {
      return tokenList;
    }

    @Override
    public boolean isSql92ReservedWord(String token) {
      return SQL_92_RESERVED_WORD_SET.contains(token);
    }

    @Override
    public String getJdbcKeywords() {
      return sql92ReservedWords;
    }

    @Override
    public boolean isKeyword(String token) {
      return isNonReservedKeyword(token)
          || isReservedFunctionName(token)
          || isContextVariableName(token)
          || isReservedWord(token);
    }

    @Override
    public boolean isNonReservedKeyword(String token) {
      return nonReservedKeyWordSet.contains(token);
    }

    @Override
    public boolean isReservedFunctionName(String token) {
      return reservedFunctionNames.contains(token);
    }

    @Override
    public boolean isContextVariableName(String token) {
      return contextVariableNames.contains(token);
    }

    @Override
    public boolean isReservedWord(String token) {
      return reservedWords.contains(token);
    }
  }
}
