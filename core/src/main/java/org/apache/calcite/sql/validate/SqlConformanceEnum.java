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

import org.apache.calcite.sql.fun.SqlLibrary;

/**
 * Enumeration of built-in SQL compatibility modes.
 */
// SqlConformanceEnum 是 SqlConformance 接口的默认枚举实现。它在 Apache Calcite 中扮演着“配置字典”的角色，预定义了多种主流数据库方言和 SQL 标准的兼容性行为。
// 这个枚举类的核心作用是 提供开箱即用的 SQL 兼容性预设。
// 如果你需要 Calcite 像 MySQL 一样处理 SQL，或者像 Oracle 一样严格要求 FROM 子句，你不需要手动实现 SqlConformance 接口的几十个方法，只需要引用 SqlConformanceEnum.MYSQL_5 或 SqlConformanceEnum.ORACLE_10。它通过内建的逻辑分支（switch-case），定义了每种方言在语法解析和语义验证阶段的具体开关。
public enum SqlConformanceEnum implements SqlConformance {
  /** Calcite's default SQL behavior. */
  // Calcite 默认行为，平衡了标准与实用性。
  DEFAULT,

  /** Conformance value that allows just about everything supported by
   * Calcite. */
  // 宽松模式，尽可能接受各种语法。
  LENIENT,

  /** Conformance value that allows anything supported by any dialect.
   * Even more liberal than {@link #LENIENT}. */
  // 极其狂热的兼容模式，用于 Babel 解析器，旨在接受所有已知方言的语法。
  BABEL,

  /** Conformance value that instructs Calcite to use SQL semantics strictly
   * consistent with the SQL:92 standard. */
  // 分别严格遵守 SQL:92、SQL:99、SQL:2003 标准。
  STRICT_92,

  /** Conformance value that instructs Calcite to use SQL semantics strictly
   * consistent with the SQL:99 standard. SQL:99 是 SQL 标准的另一个版本，类似于 STRICT_92，但它定义了一些更新的特性和改进的语法 */
  STRICT_99,

  /** Conformance value that instructs Calcite to use SQL semantics
   * consistent with the SQL:99 standard, but ignoring its more
   * inconvenient or controversial dicta.  */
  // 务实模式，遵循标准但避开了一些反人类或难以实现的规定。
  PRAGMATIC_99,

  /** Conformance value that instructs Calcite to use SQL semantics
   * consistent with BigQuery. */
  // 这些特定数据库的具体行为。
  BIG_QUERY,

  /** Conformance value that instructs Calcite to use SQL semantics
   * consistent with MySQL version 5.x. */
  MYSQL_5,

  /** Conformance value that instructs Calcite to use SQL semantics
   * consistent with Oracle version 10. */
  ORACLE_10,

  /** Conformance value that instructs Calcite to use SQL semantics
   * consistent with Oracle version 12.
   *
   * <p>As {@link #ORACLE_10} except for {@link #isApplyAllowed()}. */
  ORACLE_12,

  /** Conformance value that instructs Calcite to use SQL semantics strictly
   * consistent with the SQL:2003 standard. 。SQL:2003 是 SQL 标准的另一个版本，类似于 STRICT_92 和 STRICT_99，但它定义了不同的 SQL 特性和改进*/
  STRICT_2003,

  /** Conformance value that instructs Calcite to use SQL semantics
   * consistent with the SQL:2003 standard, but ignoring its more
   * inconvenient or controversial dicta. 与 SQL:2003 标准兼容，但忽略一些不太方便或有争议的规定。类似于 PRAGMATIC_99，这是对 SQL:2003 标准的一种宽松实现，忽略了部分不实用或有争议的规定*/
  PRAGMATIC_2003,

  /** Conformance value that instructs Calcite to use SQL semantics
   * consistent with Presto. */
  PRESTO,

  /** Conformance value that instructs Calcite to use SQL semantics
   * consistent with Microsoft SQL Server version 2008. */
  SQL_SERVER_2008;
  // 仅 BABEL 返回 true。决定是否启用最宽松的解析逻辑。
  @Override public boolean isLiberal() {
    switch (this) {
    case BABEL:
      return true;
    default:
      return false;
    }
  }

  // 允许 'alias' 这种带引号的列别名。适用于 MYSQL_5、BIG_QUERY 等。
  @Override public boolean allowCharLiteralAlias() {
    switch (this) {
    case BABEL:
    case BIG_QUERY:
    case LENIENT:
    case MYSQL_5:
    case SQL_SERVER_2008:
      return true;
    default:
      return false;
    }
  }


  @Override public boolean isGroupByAlias() {
    switch (this) {
    case BABEL:
    case LENIENT:
    case BIG_QUERY:
    case MYSQL_5:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean isGroupByOrdinal() {
    switch (this) {
    case BABEL:
    case BIG_QUERY:
    case LENIENT:
    case MYSQL_5:
    case PRESTO:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean isHavingAlias() {
    switch (this) {
    case BABEL:
    case LENIENT:
    case BIG_QUERY:
    case MYSQL_5:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean isSortByOrdinal() {
    switch (this) {
    case DEFAULT:
    case BABEL:
    case LENIENT:
    case BIG_QUERY:
    case MYSQL_5:
    case ORACLE_10:
    case ORACLE_12:
    case STRICT_92:
    case PRAGMATIC_99:
    case PRAGMATIC_2003:
    case SQL_SERVER_2008:
    case PRESTO:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean isSortByAlias() {
    switch (this) {
    case DEFAULT:
    case BABEL:
    case LENIENT:
    case BIG_QUERY:
    case MYSQL_5:
    case ORACLE_10:
    case ORACLE_12:
    case STRICT_92:
    case SQL_SERVER_2008:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean isSortByAliasObscures() {
    return this == SqlConformanceEnum.STRICT_92;
  }
  // 只有 ORACLE 和 STRICT 系列要求必须有 FROM。
  @Override public boolean isFromRequired() {
    switch (this) {
    case ORACLE_10:
    case ORACLE_12:
    case STRICT_92:
    case STRICT_99:
    case STRICT_2003:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean splitQuotedTableName() {
    switch (this) {
    case BIG_QUERY:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean allowHyphenInUnquotedTableName() {
    switch (this) {
    case BIG_QUERY:
      return true;
    default:
      return false;
    }
  }
  // 是否允许 !=。大多数方言（LENIENT、MYSQL、PRESTO 等）都支持。
  @Override public boolean isBangEqualAllowed() {
    switch (this) {
    case LENIENT:
    case BABEL:
    case BIG_QUERY:
    case MYSQL_5:
    case ORACLE_10:
    case ORACLE_12:
    case PRESTO:
      return true;
    default:
      return false;
    }
  }
  // 是否允许 MINUS（Oracle 风格）代替 EXCEPT。
  @Override public boolean isMinusAllowed() {
    switch (this) {
    case BABEL:
    case LENIENT:
    case ORACLE_10:
    case ORACLE_12:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean isRegexReplaceCaptureGroupDollarIndexed() {
    switch (this) {
    case BIG_QUERY:
      return false;
    default:
      return true;
    }
  }
  // 是否允许 % 取模。MYSQL、PRESTO、BIG_QUERY 支持。
  @Override public boolean isPercentRemainderAllowed() {
    switch (this) {
    case BABEL:
    case LENIENT:
    case BIG_QUERY:
    case MYSQL_5:
    case PRESTO:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean isApplyAllowed() {
    switch (this) {
    case BABEL:
    case LENIENT:
    case SQL_SERVER_2008:
    case ORACLE_12:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean isInsertSubsetColumnsAllowed() {
    switch (this) {
    case BABEL:
    case LENIENT:
    case PRAGMATIC_99:
    case PRAGMATIC_2003:
    case BIG_QUERY:
      return true;
    default:
      return false;
    }
  }
  // 允许无参函数带括号，如 CURRENT_DATE()。
  @Override public boolean allowNiladicParentheses() {
    switch (this) {
    case BABEL:
    case LENIENT:
    case MYSQL_5:
    case BIG_QUERY:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean allowExplicitRowValueConstructor() {
    switch (this) {
    case DEFAULT:
    case LENIENT:
    case PRESTO:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean allowExtend() {
    switch (this) {
    case BABEL:
    case LENIENT:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean isLimitStartCountAllowed() {
    switch (this) {
    case BABEL:
    case LENIENT:
    case MYSQL_5:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean isOffsetLimitAllowed() {
    switch (this) {
    case BABEL:
    case LENIENT:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean allowGeometry() {
    switch (this) {
    case BABEL:
    case LENIENT:
    case MYSQL_5:
    case SQL_SERVER_2008:
    case PRESTO:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean shouldConvertRaggedUnionTypesToVarying() {
    switch (this) {
    case PRAGMATIC_99:
    case PRAGMATIC_2003:
    case BIG_QUERY:
    case MYSQL_5:
    case ORACLE_10:
    case ORACLE_12:
    case SQL_SERVER_2008:
    case PRESTO:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean allowExtendedTrim() {
    switch (this) {
    case BABEL:
    case LENIENT:
    case MYSQL_5:
    case SQL_SERVER_2008:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean allowPluralTimeUnits() {
    switch (this) {
    case BABEL:
    case LENIENT:
      return true;
    default:
      return false;
    }
  }

  @Override public boolean allowQualifyingCommonColumn() {
    switch (this) {
    case ORACLE_10:
    case ORACLE_12:
    case STRICT_92:
    case STRICT_99:
    case STRICT_2003:
    case PRESTO:
      return false;
    default:
      return true;
    }
  }

  @Override public boolean allowAliasUnnestItems() {
    switch (this) {
    case PRESTO:
      return true;
    default:
      return false;
    }
  }
  // 允许使用 VALUE 代替 VALUES。
  @Override public boolean isValueAllowed() {
    switch (this) {
    case BABEL:
    case LENIENT:
    case MYSQL_5:
      return true;
    default:
      return false;
    }
  }

  @Override public SqlLibrary semantics() {
    switch (this) {
    case BIG_QUERY:
      return SqlLibrary.BIG_QUERY;
    case MYSQL_5:
      return SqlLibrary.MYSQL;
    case ORACLE_12:
    case ORACLE_10:
      return SqlLibrary.ORACLE;
    default:
      return SqlLibrary.STANDARD;
    }
  }

  @Override public boolean allowLenientCoercion() {
    /* This allows for the following:
     - coercion from string to array
     - coercion from boolean to integers
     */
    switch (this) {
    case BABEL:
    case BIG_QUERY:
    case MYSQL_5:
      return true;
    default:
      return false;
    }
  }
}
