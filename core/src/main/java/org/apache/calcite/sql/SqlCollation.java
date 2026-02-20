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

import org.apache.calcite.config.CalciteSystemProperty;
import org.apache.calcite.sql.parser.SqlParserUtil;
import org.apache.calcite.util.Glossary;
import org.apache.calcite.util.SerializableCharset;
import org.apache.calcite.util.Util;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import java.io.Serializable;
import java.nio.charset.Charset;
import java.text.Collator;
import java.util.Locale;

import static org.apache.calcite.util.Static.RESOURCE;

/** SqlCollation 是 Apache Calcite 中表示 SQL 中 COLLATE 语句的类。
 * 它用于定义和处理字符集排序规则（Collation）及其强制类型（Coercibility）。
 * 排序规则用于控制字符串比较的顺序、大小写敏感性以及语言特定的排序方式。在 SQL 查询中，COLLATE 语句用于指定字符串的比较规则
 * A <code>SqlCollation</code> is an object representing a <code>Collate</code>
 * statement. It is immutable.
 */
public class SqlCollation implements Serializable {
  public static final SqlCollation COERCIBLE =
      new SqlCollation(Coercibility.COERCIBLE);
  public static final SqlCollation IMPLICIT =
      new SqlCollation(Coercibility.IMPLICIT);

  //~ Enums ------------------------------------------------------------------

  /**
   * <blockquote>A &lt;character value expression&gt; consisting of a column
   * reference has the coercibility characteristic Implicit, with collating
   * sequence as defined when the column was created. A &lt;character value
   * expression&gt; consisting of a value other than a column (e.g., a host
   * variable or a literal) has the coercibility characteristic Coercible,
   * with the default collation for its character repertoire. A &lt;character
   * value expression&gt; simply containing a &lt;collate clause&gt; has the
   * coercibility characteristic Explicit, with the collating sequence
   * specified in the &lt;collate clause&gt;.</blockquote>
   *
   * @see Glossary#SQL99 SQL:1999 Part 2 Section 4.2.3
   */
  public enum Coercibility {
    /** Strongest coercibility. */
    EXPLICIT, //字段有明确的排序规则，并且强制要求使用指定的排序规则，例如SELECT name COLLATE 'utf8_general_ci'
    IMPLICIT, //此类强制性表示排序规则是通过数据库结构或配置隐式推断的，例如，如果一个列的默认排序规则是 UTF-8，那么如果没有显式指定，查询会默认使用这个排序规则
    COERCIBLE, //允许将字段转换为其他排序规则
    /** Weakest coercibility. */
    NONE //意味着字段没有排序规则，或无法根据上下文推断出排序规则
  }

  //~ Instance fields --------------------------------------------------------

  protected final String collationName; //排序规则的名称，表示当前排序规则的唯一标识符
  protected final SerializableCharset wrappedCharset; //包装的字符集（Charset），用于存储排序规则所使用的字符集
  protected final Locale locale; //语言环境（Locale），用于确定与排序规则相关的地区性设置
  protected final String strength; //排序强度（strength），用于指定排序时考虑的优先级（如是否区分大小写）
  private final Coercibility coercibility; //强制性（Coercibility），表示操作数之间的排序规则兼容性

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a SqlCollation with the default collation name and the given
   * coercibility.
   *
   * @param coercibility Coercibility
   */
  public SqlCollation(Coercibility coercibility) {
    this(
        CalciteSystemProperty.DEFAULT_COLLATION.value(),
        coercibility);
  }

  /**
   * Creates a Collation by its name and its coercibility.
   *
   * @param collation    Collation specification
   * @param coercibility Coercibility
   */
  @JsonCreator
  public SqlCollation(
      @JsonProperty("collationName") String collation,
      @JsonProperty("coercibility") Coercibility coercibility) {
    this.coercibility = coercibility;
    SqlParserUtil.ParsedCollation parseValues =
        SqlParserUtil.parseCollation(collation);
    Charset charset = parseValues.getCharset();
    this.wrappedCharset = SerializableCharset.forCharset(charset);
    this.locale = parseValues.getLocale();
    this.strength = parseValues.getStrength().toLowerCase(Locale.ROOT);
    this.collationName = generateCollationName(charset);
  }

  /**
   * Creates a Collation by its coercibility, locale, charset and strength.
   */
  public SqlCollation(
      Coercibility coercibility,
      Locale locale,
      Charset charset,
      String strength) {
    this.coercibility = coercibility;
    charset = SqlUtil.getCharset(charset.name());
    this.wrappedCharset = SerializableCharset.forCharset(charset);
    this.locale = locale;
    this.strength = strength.toLowerCase(Locale.ROOT);
    this.collationName = generateCollationName(charset);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public boolean equals(@Nullable Object o) {
    return this == o
        || o instanceof SqlCollation
        && collationName.equals(((SqlCollation) o).collationName);
  }

  @Override public int hashCode() {
    return collationName.hashCode();
  }
  //生成排序规则的名称，基于字符集的名称、语言环境和排序强度
  protected String generateCollationName(
      @UnderInitialization SqlCollation this,
      Charset charset) {
    return charset.name().toUpperCase(Locale.ROOT) + "$" + String.valueOf(locale) + "$" + strength;
  }

  /**
   * Returns the collating sequence (the collation name) and the coercibility
   * for the resulting value of a dyadic operator.
   *
   * @param col1 first operand for the dyadic operation
   * @param col2 second operand for the dyadic operation
   * @return the resulting collation sequence. The "no collating sequence"
   * result is returned as null.
   * //返回两个操作数进行二元操作时的排序规则。如果无法推断出排序规则，则返回 null
   * @see Glossary#SQL99 SQL:1999 Part 2 Section 4.2.3 Table 2
   */
  public static @Nullable SqlCollation getCoercibilityDyadicOperator(
      SqlCollation col1,
      SqlCollation col2) {
    return getCoercibilityDyadic(col1, col2);
  }

  /**
   * Returns the collating sequence (the collation name) and the coercibility
   * for the resulting value of a dyadic operator.
   *
   * @param col1 first operand for the dyadic operation
   * @param col2 second operand for the dyadic operation
   * @return the resulting collation sequence
   *
   * @throws org.apache.calcite.runtime.CalciteException from
   *   {@link org.apache.calcite.runtime.CalciteResource#invalidCompare} or
   *   {@link org.apache.calcite.runtime.CalciteResource#differentCollations}
   *   if no collating sequence can be deduced
   *
   * @see Glossary#SQL99 SQL:1999 Part 2 Section 4.2.3 Table 2
   */
  public static SqlCollation getCoercibilityDyadicOperatorThrows(
      SqlCollation col1,
      SqlCollation col2) {
    SqlCollation ret = getCoercibilityDyadic(col1, col2);
    if (null == ret) {
      throw RESOURCE.invalidCompare(
          col1.collationName,
          "" + col1.coercibility,
          col2.collationName,
          "" + col2.coercibility).ex();
    }
    return ret;
  }

  /**
   * Returns the collating sequence (the collation name) to use for the
   * resulting value of a comparison.
   *
   * @param col1 first operand for the dyadic operation
   * @param col2 second operand for the dyadic operation
   *
   * @return the resulting collation sequence. If no collating
   * sequence could be deduced throws a
   * {@link org.apache.calcite.runtime.CalciteResource#invalidCompare}
   *
   * @see Glossary#SQL99 SQL:1999 Part 2 Section 4.2.3 Table 3
   */
  public static String getCoercibilityDyadicComparison(
      SqlCollation col1,
      SqlCollation col2) {
    return getCoercibilityDyadicOperatorThrows(col1, col2).collationName;
  }

  /**
   * Returns the result for {@link #getCoercibilityDyadicComparison} and
   * {@link #getCoercibilityDyadicOperator}.
   */
  protected static @Nullable SqlCollation getCoercibilityDyadic(
      SqlCollation col1,
      SqlCollation col2) {
    assert null != col1;
    assert null != col2;
    final Coercibility coercibility1 = col1.getCoercibility();
    final Coercibility coercibility2 = col2.getCoercibility();
    switch (coercibility1) {
    case COERCIBLE:
      switch (coercibility2) {
      case COERCIBLE:
        return col2;
      case IMPLICIT:
        return col2;
      case NONE:
        return null;
      case EXPLICIT:
        return col2;
      default:
        throw Util.unexpected(coercibility2);
      }
    case IMPLICIT:
      switch (coercibility2) {
      case COERCIBLE:
        return col1;
      case IMPLICIT:
        if (col1.collationName.equals(col2.collationName)) {
          return col2;
        }
        return null;
      case NONE:
        return null;
      case EXPLICIT:
        return col2;
      default:
        throw Util.unexpected(coercibility2);
      }
    case NONE:
      switch (coercibility2) {
      case COERCIBLE:
      case IMPLICIT:
      case NONE:
        return null;
      case EXPLICIT:
        return col2;
      default:
        throw Util.unexpected(coercibility2);
      }
    case EXPLICIT:
      switch (coercibility2) {
      case COERCIBLE:
      case IMPLICIT:
      case NONE:
        return col1;
      case EXPLICIT:
        if (col1.collationName.equals(col2.collationName)) {
          return col2;
        }
        throw RESOURCE.differentCollations(
            col1.collationName,
            col2.collationName).ex();
      default:
        throw Util.unexpected(coercibility2);
      }
    default:
      throw Util.unexpected(coercibility1);
    }
  }

  @Override public String toString() {
    return "COLLATE " + collationName;
  }

  public void unparse(
      SqlWriter writer) {
    writer.keyword("COLLATE");
    writer.identifier(collationName, false);
  }

  @JsonIgnore
  public Charset getCharset() {
    return wrappedCharset.getCharset();
  }

  public final String getCollationName() {
    return collationName;
  }

  public final SqlCollation.Coercibility getCoercibility() {
    return coercibility;
  }

  public final Locale getLocale() {
    return locale;
  }

  /**
   * Returns the {@link Collator} to compare values having the current
   * collation, or {@code null} if no specific {@link Collator} is needed, in
   * which case {@link String#compareTo} will be used.
   */
  @Pure
  @JsonIgnore
  public @Nullable Collator getCollator() {
    return null;
  }
}
