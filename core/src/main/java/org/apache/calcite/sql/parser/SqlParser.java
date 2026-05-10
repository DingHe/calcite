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
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.avatica.util.TimeUnit;
import org.apache.calcite.config.CharLiteralStyle;
import org.apache.calcite.config.Lex;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rel.type.TimeFrameSet;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.parser.impl.SqlParserImpl;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.sql.validate.SqlDelegatingConformance;
import org.apache.calcite.util.SourceStringReader;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A <code>SqlParser</code> parses a SQL statement.
 */
// SqlParser 的主要职责是将 SQL 字符串（文本） 转换为 SqlNode 抽象语法树（AST）。
// 语法解析入口：它是用户程序与 Calcite 解析引擎交互的主要接口。
// 词法与语法控制：通过配置，它可以模拟不同数据库（如 Oracle, MySQL, Postgres）的词法行为（大小写敏感度、引用符号等）。
// 错误处理：捕捉 SQL 中的语法错误并将其规范化为 SqlParseException，提供错误位置信息。
// 支持多模式解析：不仅可以解析完整的 DML/DDL 语句，还可以单独解析 SQL 表达式或 SQL 语句列表。
@Value.Enclosing
@SuppressWarnings("deprecation")
public class SqlParser {
  // 默认标识符的最大长度为 128 个字符。
  public static final int DEFAULT_IDENTIFIER_MAX_LENGTH = 128;

  /** Default value of {@link Config#timeUnitCodes()}.
   * The map is empty, which means that there are no abbreviations other than
   * the time unit names ("YEAR", "SECOND", etc.) */
  // 原本用于映射时间单位缩写
  @Deprecated // to be removed before 2.0
  public static final ImmutableMap<String, TimeUnit> DEFAULT_IDENTIFIER_TIMEUNIT_MAP =
      ImmutableMap.of();
  // 已废弃。默认是否允许 !=（叹号等号）语法，现在由 SqlConformance 控制。
  @Deprecated // to be removed before 2.0
  public static final boolean DEFAULT_ALLOW_BANG_EQUAL =
      SqlConformanceEnum.DEFAULT.isBangEqualAllowed();

  // 底层的实际解析器实现（通常是由 JavaCC 生成的 SqlParserImpl）
  //~ Instance fields --------------------------------------------------------
  private final SqlAbstractParserImpl parser;

  //~ Constructors -----------------------------------------------------------
  // 根据传入的 Config 对象初始化底层 parser 的状态（如制表符大小、大小写转换策略、最大长度、一致性级别等）
  private SqlParser(SqlAbstractParserImpl parser,
      Config config) {
    this.parser = parser;
    parser.setTabSize(1);
    parser.setQuotedCasing(config.quotedCasing());
    parser.setUnquotedCasing(config.unquotedCasing());
    parser.setIdentifierMaxLength(config.identifierMaxLength());
    parser.setTimeUnitCodes(config.timeUnitCodes());
    parser.setConformance(config.conformance());
    parser.switchTo(SqlAbstractParserImpl.LexicalState.forConfig(config));
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Creates a <code>SqlParser</code> to parse the given string using
   * Calcite's parser implementation.
   *
   * <p>The default lexical policy is similar to Oracle.
   *
   * @see Lex#ORACLE
   *
   * @param s An SQL statement or expression to parse.
   * @return A parser
   */
  // 使用默认配置解析 SQL 字符串 s
  public static SqlParser create(String s) {
    return create(s, config());
  }

  /**
   * Creates a <code>SqlParser</code> to parse the given string using the
   * parser implementation created from given {@link SqlParserImplFactory}
   * with given quoting syntax and casing policies for identifiers.
   *
   * @param sql A SQL statement or expression to parse
   * @param config The parser configuration (identifier max length, etc.)
   * @return A parser
   */
  // 根据指定配置创建解析器。
  // 它使用 SourceStringReader 包装 SQL，以便在报错时能准确返回原始语句。
  public static SqlParser create(String sql, Config config) {
    return create(new SourceStringReader(sql), config);
  }

  /**
   * Creates a <code>SqlParser</code> to parse the given string using the
   * parser implementation created from given {@link SqlParserImplFactory}
   * with given quoting syntax and casing policies for identifiers.
   *
   * <p>Unlike
   * {@link #create(java.lang.String, org.apache.calcite.sql.parser.SqlParser.Config)},
   * the parser is not able to return the original query string, but will
   * instead return "?".
   *
   * @param reader The source for the SQL statement or expression to parse
   * @param config The parser configuration (identifier max length, etc.)
   * @return A parser
   */
  // 最底层的创建方法。通过 config.parserFactory() 获取解析器工厂并创建底层解析器实例。
  public static SqlParser create(Reader reader, Config config) {
    SqlAbstractParserImpl parser =
        config.parserFactory().getParser(reader);

    return new SqlParser(parser, config);
  }

  /**
   * Parses a SQL expression.
   *
   * @throws SqlParseException if there is a parse error
   */
  // 专门用于解析 SQL 表达式（例如 a + 5），而不是完整的 SELECT 语句。
  public SqlNode parseExpression() throws SqlParseException {
    try {
      return parser.parseSqlExpressionEof();
    } catch (Throwable ex) {
      if (ex instanceof CalciteContextException) {
        final String originalSql = parser.getOriginalSql();
        if (originalSql != null) {
          ((CalciteContextException) ex).setOriginalStatement(originalSql);
        }
      }
      throw parser.normalizeException(ex);
    }
  }

  /** Normalizes a SQL exception. */
  // 内部私有辅助方法。如果异常是上下文异常，它会将原始 SQL 语句注入异常中，并调用 normalizeException 进行规范化处理。
  private SqlParseException handleException(Throwable ex) {
    if (ex instanceof CalciteContextException) {
      final String originalSql = parser.getOriginalSql();
      if (originalSql != null) {
        ((CalciteContextException) ex).setOriginalStatement(originalSql);
      }
    }
    return parser.normalizeException(ex);
  }

  /**
   * Parses a <code>SELECT</code> statement.
   *
   * @return A {@link org.apache.calcite.sql.SqlSelect} for a regular <code>
   * SELECT</code> statement; a {@link org.apache.calcite.sql.SqlBinaryOperator}
   * for a <code>UNION</code>, <code>INTERSECT</code>, or <code>EXCEPT</code>.
   * @throws SqlParseException if there is a parse error
   */
  // 解析一个 查询语句（如 SELECT, UNION, INTERSECT）。
  // 它调用底层 parseSqlStmtEof。
  public SqlNode parseQuery() throws SqlParseException {
    try {
      return parser.parseSqlStmtEof();
    } catch (Throwable ex) {
      throw handleException(ex);
    }
  }

  /**
   * Parses a <code>SELECT</code> statement and reuses parser.
   *
   * @param sql sql to parse
   * @return A {@link org.apache.calcite.sql.SqlSelect} for a regular <code>
   * SELECT</code> statement; a {@link org.apache.calcite.sql.SqlBinaryOperator}
   * for a <code>UNION</code>, <code>INTERSECT</code>, or <code>EXCEPT</code>.
   * @throws SqlParseException if there is a parse error
   */
  // 重用当前解析器实例来解析一段新的 SQL 字符串（通过 ReInit 重新初始化底层流），提高性能。
  public SqlNode parseQuery(String sql) throws SqlParseException {
    parser.ReInit(new StringReader(sql));
    return parseQuery();
  }

  /**
   * Parses an SQL statement.
   *
   * @return top-level SqlNode representing stmt
   * @throws SqlParseException if there is a parse error
   */
  // 目前内部直接调用 parseQuery()，用于解析顶层 SQL 语句
  public SqlNode parseStmt() throws SqlParseException {
    return parseQuery();
  }

  /**
   * Parses a list of SQL statements separated by semicolon.
   * The semicolon is required between statements, but is
   * optional at the end.
   *
   * @return list of SqlNodeList representing the list of SQL statements
   * @throws SqlParseException if there is a parse error
   */
  // 解析由分号分隔的一组 SQL 语句，返回 SqlNodeList。
  public SqlNodeList parseStmtList() throws SqlParseException {
    try {
      return parser.parseSqlStmtList();
    } catch (Throwable ex) {
      throw handleException(ex);
    }
  }

  /**
   * Get the parser metadata.
   *
   * @return {@link SqlAbstractParserImpl.Metadata} implementation of
   *     underlying parser.
   */
  // 获取底层解析器的元数据（如支持的保留字、关键字列表等）。
  public SqlAbstractParserImpl.Metadata getMetadata() {
    return parser.getMetadata();
  }

  /**
   * Returns the warnings that were generated by the previous invocation
   * of the parser.
   */
  // 获取解析过程中产生的警告信息（由 CalciteContextException 组成）。
  public List<CalciteContextException> getWarnings() {
    return parser.warnings;
  }

  /** Returns a default {@link Config}. */
  // 获取默认的配置对象 Config.DEFAULT。
  public static Config config() {
    return Config.DEFAULT;
  }

  /**
   * Builder for a {@link Config}.
   *
   * @deprecated Use {@link #config()}
   */
  @Deprecated // to be removed before 2.0
  public static ConfigBuilder configBuilder() {
    return new ConfigBuilder();
  }

  /**
   * Builder for a {@link Config} that starts with an existing {@code Config}.
   *
   * @deprecated Use {@code config}, and modify it using its mutator methods
   */
  @Deprecated // to be removed before 2.0
  public static ConfigBuilder configBuilder(Config config) {
    return new ConfigBuilder().setConfig(config);
  }

  /**
   * Interface to define the configuration for a SQL parser.
   */
  @Value.Immutable
  @SuppressWarnings("deprecation")
  public interface Config {
    /** Default configuration. */
    Config DEFAULT = ImmutableSqlParser.Config.of();
    // 限制标识符长度。
    @Value.Default default int identifierMaxLength() {
      return DEFAULT_IDENTIFIER_MAX_LENGTH;
    }

    /** Sets {@link #identifierMaxLength()}. */
    Config withIdentifierMaxLength(int identifierMaxLength);
    // 定义被引号包裹和未被包裹的标识符在解析时如何处理大小写（如：转大写、转小写、保持不变）
    @Value.Default default Casing quotedCasing() {
      return Casing.UNCHANGED;
    }

    /** Sets {@link #quotedCasing()}. */
    Config withQuotedCasing(Casing casing);
    // 定义被引号包裹和未被包裹的标识符在解析时如何处理大小写（如：转大写、转小写、保持不变）
    @Value.Default default Casing unquotedCasing() {
      return Casing.TO_UPPER;
    }

    /** Sets {@link #unquotedCasing()}. */
    Config withUnquotedCasing(Casing casing);

    // 定义标识符的引用符号（如反引号 ` 或双引号 "）
    @Value.Default default Quoting quoting() {
      return Quoting.DOUBLE_QUOTE;
    }

    /** Sets {@link #quoting()}. */
    Config withQuoting(Quoting quoting);
    // 是否大小写敏感。
    @Value.Default default boolean caseSensitive() {
      return true;
    }

    /** Sets {@link #caseSensitive()}. */
    Config withCaseSensitive(boolean caseSensitive);
    // 定义 SQL 兼容性标准（如 DEFAULT, LENIENT, BABEL 等）
    @Value.Default default SqlConformance conformance() {
      return SqlConformanceEnum.DEFAULT;
    }

    /** Sets {@link #conformance()}. */
    Config withConformance(SqlConformance conformance);

    @Deprecated // to be removed before 2.0
    @SuppressWarnings("deprecation")
    @Value.Default default boolean allowBangEqual() {
      return DEFAULT_ALLOW_BANG_EQUAL;
    }

    /** Returns which character literal styles are supported. */
    // 定义支持的字符串字面量风格。
    @Value.Default default Set<CharLiteralStyle> charLiteralStyles() {
      return ImmutableSet.of(CharLiteralStyle.STANDARD);
    }

    /** Sets {@link #charLiteralStyles()}. */
    Config withCharLiteralStyles(Iterable<CharLiteralStyle> charLiteralStyles);

    /** Returns a mapping from abbreviations to time units.
     *
     * <p>For example, if the map contains the entry
     * ("Y", {@link TimeUnit#YEAR}) then you can write
     * "{@code EXTRACT(S FROM orderDate)}".
     *
     * @deprecated This property is deprecated, and has no effect. All
     * non-standard time units are now parsed as identifiers, and resolved in
     * the validator. You can define custom time frames using
     * {@link RelDataTypeSystem#deriveTimeFrameSet(TimeFrameSet)}. To alias a
     * time frame, use {@link TimeFrameSet.Builder#addAlias(String, String)}. */
    @Deprecated // to be removed before 2.0
    @Value.Default default Map<String, TimeUnit> timeUnitCodes() {
      return DEFAULT_IDENTIFIER_TIMEUNIT_MAP;
    }

    /** Sets {@link #timeUnitCodes()}. */
    Config withTimeUnitCodes(Map<String, ? extends TimeUnit> timeUnitCodes);
    // 指定用于创建底层解析器的工厂类（可以通过此项替换为自定义的 JavaCC 解析器）。
    @Value.Default default SqlParserImplFactory parserFactory() {
      return SqlParserImpl.FACTORY;
    }

    /** Sets {@link #parserFactory()}. */
    Config withParserFactory(SqlParserImplFactory factory);
    // 通过预定义的 Lex 策略（如 Lex.MYSQL, Lex.ORACLE）一次性设置上述多个属性。
    default Config withLex(Lex lex) {
      return withCaseSensitive(lex.caseSensitive)
          .withUnquotedCasing(lex.unquotedCasing)
          .withQuotedCasing(lex.quotedCasing)
          .withQuoting(lex.quoting)
          .withCharLiteralStyles(lex.charLiteralStyles);
    }
  }

  /** Builder for a {@link Config}. */
  @Deprecated // to be removed before 2.0
  public static class ConfigBuilder {
    private Config config = Config.DEFAULT;

    private ConfigBuilder() {}

    /** Sets configuration to a given {@link Config}. */
    public ConfigBuilder setConfig(Config config) {
      this.config = config;
      return this;
    }

    public ConfigBuilder setQuotedCasing(Casing quotedCasing) {
      return setConfig(config.withQuotedCasing(quotedCasing));
    }

    public ConfigBuilder setUnquotedCasing(Casing unquotedCasing) {
      return setConfig(config.withUnquotedCasing(unquotedCasing));
    }

    public ConfigBuilder setQuoting(Quoting quoting) {
      return setConfig(config.withQuoting(quoting));
    }

    public ConfigBuilder setCaseSensitive(boolean caseSensitive) {
      return setConfig(config.withCaseSensitive(caseSensitive));
    }

    public ConfigBuilder setIdentifierMaxLength(int identifierMaxLength) {
      return setConfig(config.withIdentifierMaxLength(identifierMaxLength));
    }

    public ConfigBuilder setIdentifierTimeUnitMap(
        ImmutableMap<String, TimeUnit> identifierTimeUnitMap) {
      return setConfig(config.withTimeUnitCodes(identifierTimeUnitMap));
    }

    @SuppressWarnings("unused")
    @Deprecated // to be removed before 2.0
    public ConfigBuilder setAllowBangEqual(final boolean allowBangEqual) {
      if (allowBangEqual != config.conformance().isBangEqualAllowed()) {
        return setConformance(
            new SqlDelegatingConformance(config.conformance()) {
              @Override public boolean isBangEqualAllowed() {
                return allowBangEqual;
              }
            });
      }
      return this;
    }

    public ConfigBuilder setConformance(SqlConformance conformance) {
      return setConfig(config.withConformance(conformance));
    }

    public ConfigBuilder setCharLiteralStyles(
        Set<CharLiteralStyle> charLiteralStyles) {
      return setConfig(config.withCharLiteralStyles(charLiteralStyles));
    }

    public ConfigBuilder setParserFactory(SqlParserImplFactory factory) {
      return setConfig(config.withParserFactory(factory));
    }

    public ConfigBuilder setLex(Lex lex) {
      return setConfig(config.withLex(lex));
    }

    /** Builds a {@link Config}. */
    public Config build() {
      return config;
    }
  }
}
