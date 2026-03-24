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

/**
 * A <code>SqlCollation</code> is an object representing a <code>Collate</code>
 * statement. It is immutable.
 */
// 在 Apache Calcite 中，SqlCollation 是处理字符串比较、排序规则（Collation）和字符集的核心类。
// 它严格遵循 SQL:1999 标准，用于解决“当两个不同排序规则的字符串相遇时，应该听谁的”这一复杂问题。
// SqlCollation 的核心作用是定义字符串的“比较指纹”。
// 定义排序规则：它规定了字符串在进行 =、<、> 比较或 ORDER BY 排序时，是否区分大小写、是否考虑重音符号以及遵循哪种语言（Locale）的习惯。
// 不可变性：该类设计为不可变对象，确保在多线程和优化器转换过程中的元数据安全。
// SqlCollation 是 Calcite 字符处理的“裁判”。它不仅记录了数据是怎么排序的（Locale/Strength），更重要的是通过 getCoercibilityDyadic 逻辑，在复杂的 SQL 表达式中自动协调不同来源数据的排序冲突。

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
  // Coercibility（强制性） 是一个决定 “当两个不同校对规则（Collation）的字符串进行比较或运算时，谁听谁的” 的优先级机制。
  // 简单来说，当 SQL 语句中出现 WHERE column = 'string' 这种操作时，如果列的校对规则和字符串的校对规则不一致，数据库需要一个规则来决定使用哪种校对方式。
  public enum Coercibility {
    /** Strongest coercibility. */
    // 手动显式指定的校对规则
    // 例如 SELECT 'a' COLLATE utf8mb4_bin
    EXPLICIT,
    // 列（Column）定义的校对规则
    // 例如 表结构中的字段：username
    // 变量（Variable）的校对规则
    // 例如 @my_var
    IMPLICIT,
    // 可强制转换。优先级低。
    // 字符串字面量（Literal）或动态参数。
    COERCIBLE,
    /** Weakest coercibility. */
    // 无排序规则。
    // 两个不同排序规则的隐式列操作后的结果。
    NONE
  }

  //~ Instance fields --------------------------------------------------------
  // 排序规则的唯一标识符（如 UTF-8$en_US$primary）。
  protected final String collationName;
  // 包装后的字符集，确保序列化安全。
  protected final SerializableCharset wrappedCharset;
  // 地区设置（如 en_US），决定特定语言的排序行为。
  protected final Locale locale;
  // 排序强度。通常指 ICU 排序强度（Primary, Secondary, Tertiary 等），控制是否忽略大小写。
  protected final String strength;
  // 该对象的强制性级别，决定了在表达式计算中的胜出权。
  private final Coercibility coercibility;

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
  // 生成格式为 字符集$地区$强度 的字符串
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
   * @see Glossary#SQL99 SQL:1999 Part 2 Section 4.2.3 Table 2
   */
  // 用于二元操作符（如连接符 ||），推导出结果的排序规则。
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
  // 同上，但如果推导结果为 NONE，则抛出异常（报错提示排序规则不兼容）。
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
  // 用于比较操作，返回胜出的排序规则名称字符串。
  public static String getCoercibilityDyadicComparison(
      SqlCollation col1,
      SqlCollation col2) {
    return getCoercibilityDyadicOperatorThrows(col1, col2).collationName;
  }

  /**
   * Returns the result for {@link #getCoercibilityDyadicComparison} and
   * {@link #getCoercibilityDyadicOperator}.
   */
  // 输入两个 SqlCollation，返回胜出的那一个。例如：
  // EXPLICIT vs IMPLICIT -> 返回 EXPLICIT。
  // IMPLICIT vs IMPLICIT (名称不同) -> 返回 NONE（冲突）。
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
  // 将该对象写回到 SQL 方言字符串中。
  public void unparse(
      SqlWriter writer) {
    writer.keyword("COLLATE");
    writer.identifier(collationName, false);
  }
  // 从包装类中还原 java.nio.charset.Charset
  @JsonIgnore
  public Charset getCharset() {
    return wrappedCharset.getCharset();
  }
  // 获取名称。
  public final String getCollationName() {
    return collationName;
  }
  // 获取强制性等级。
  public final SqlCollation.Coercibility getCoercibility() {
    return coercibility;
  }
  // 获取地区
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
