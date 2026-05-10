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
package org.apache.calcite.config;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;

import com.google.common.collect.ImmutableSet;

import java.util.Objects;
import java.util.Set;

/** Named, built-in lexical policy. A lexical policy describes how
 * identifiers are quoted, whether they are converted to upper- or
 * lower-case when they are read, and whether they are matched
 * case-sensitively. */
// Lex 是一个非常关键的枚举类，它定义了 SQL 解析时的词法策略（Lexical Policy）。
// Lex 类的主要作用是提供预定义的词法配置组合，用于模拟不同主流数据库引擎的标识符处理行为。
// 在 SQL 解析过程中，不同数据库对“标识符”（Identifier，如表名、列名）的处理规则大相径庭。Lex 类通过封装一系列配置参数，决定了：
// 引用符号：用什么符号来包裹标识符（如双引号、反引号或方括号）。
// 大小写转换：未加引号的标识符是否自动转大写（如 Oracle）或保持原样。
// 匹配规则：在元数据查找时，标识符比较是否区分大小写。
// 字符串风格：支持什么样的字符字面量（Char Literal）定义。
public enum Lex {
  /** Lexical policy similar to BigQuery.
   * The case of identifiers is preserved whether or not they quoted;
   * after which, identifiers are matched case-insensitively.
   * Back-ticks allow identifiers to contain non-alphanumeric characters;
   * a back-tick is escaped using a backslash.
   * Character literals may be enclosed in single or double quotes. */
  BIG_QUERY(Quoting.BACK_TICK_BACKSLASH, Casing.UNCHANGED, Casing.UNCHANGED,
      false, CharLiteralStyle.BQ_SINGLE, CharLiteralStyle.BQ_DOUBLE),

  /** Lexical policy similar to Oracle. The case of identifiers enclosed in
   * double-quotes is preserved; unquoted identifiers are converted to
   * upper-case; after which, identifiers are matched case-sensitively. */
  ORACLE(Quoting.DOUBLE_QUOTE, Casing.TO_UPPER, Casing.UNCHANGED, true,
      CharLiteralStyle.STANDARD),

  /** Lexical policy similar to MySQL. (To be precise: MySQL on Windows;
   * MySQL on Linux uses case-sensitive matching, like the Linux file system.)
   * The case of identifiers is preserved whether or not they quoted;
   * after which, identifiers are matched case-insensitively.
   * Back-ticks allow identifiers to contain non-alphanumeric characters;
   * a back-tick is escaped using a back-tick. */
  MYSQL(Quoting.BACK_TICK, Casing.UNCHANGED, Casing.UNCHANGED, false,
      CharLiteralStyle.STANDARD),

  /** Lexical policy similar to MySQL with ANSI_QUOTES option enabled. (To be
   * precise: MySQL on Windows; MySQL on Linux uses case-sensitive matching,
   * like the Linux file system.) The case of identifiers is preserved whether
   * or not they quoted; after which, identifiers are matched
   * case-insensitively. Double quotes allow identifiers to contain
   * non-alphanumeric characters. */
  MYSQL_ANSI(Quoting.DOUBLE_QUOTE, Casing.UNCHANGED, Casing.UNCHANGED, false,
      CharLiteralStyle.STANDARD),

  /** Lexical policy similar to Microsoft SQL Server.
   * The case of identifiers is preserved whether or not they are quoted;
   * after which, identifiers are matched case-insensitively.
   * Brackets allow identifiers to contain non-alphanumeric characters. */
  SQL_SERVER(Quoting.BRACKET, Casing.UNCHANGED, Casing.UNCHANGED, false,
      CharLiteralStyle.STANDARD),

  /** Lexical policy similar to Java.
   * The case of identifiers is preserved whether or not they are quoted;
   * after which, identifiers are matched case-sensitively.
   * Unlike Java, back-ticks allow identifiers to contain non-alphanumeric
   * characters; a back-tick is escaped using a back-tick. */
  JAVA(Quoting.BACK_TICK, Casing.UNCHANGED, Casing.UNCHANGED, true,
      CharLiteralStyle.STANDARD);
  // 定义标识符的引用符号类型。
  // 常见值：DOUBLE_QUOTE ("), BACK_TICK (`), BRACKET ([])。
  public final Quoting quoting;
  // 作用：定义未加引号的标识符在解析后的转换策略。
  // 常见值：TO_UPPER (转大写), UNCHANGED (保持原样)。
  public final Casing unquotedCasing;
  // 作用：定义加了引号的标识符在解析后的转换策略。通常大多数数据库都设为 UNCHANGED。
  public final Casing quotedCasing;
  // 作用：定义标识符在后续校验（Validation）和匹配时是否大小写敏感。
  public final boolean caseSensitive;
  // 作用：定义该词法策略下支持的字符字面量风格。
  @SuppressWarnings("ImmutableEnumChecker")
  public final Set<CharLiteralStyle> charLiteralStyles;

  Lex(Quoting quoting,
      Casing unquotedCasing,
      Casing quotedCasing,
      boolean caseSensitive,
      CharLiteralStyle... charLiteralStyles) {
    this.quoting = Objects.requireNonNull(quoting, "quoting");
    this.unquotedCasing = Objects.requireNonNull(unquotedCasing, "unquotedCasing");
    this.quotedCasing = Objects.requireNonNull(quotedCasing, "quotedCasing");
    this.caseSensitive = caseSensitive;
    this.charLiteralStyles = ImmutableSet.copyOf(charLiteralStyles);
  }
}
