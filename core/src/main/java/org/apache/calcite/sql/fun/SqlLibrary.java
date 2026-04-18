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
package org.apache.calcite.sql.fun;

import org.apache.calcite.config.CalciteConnectionProperty;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;

import static org.apache.calcite.util.Util.filter;
import static org.apache.calcite.util.Util.first;

import static java.util.Objects.requireNonNull;

/**
 * A library is a collection of SQL functions and operators.
 *
 * <p>Typically, such collections are associated with a particular dialect or
 * database. For example, {@link SqlLibrary#ORACLE} is a collection of functions
 * that are in the Oracle database but not the SQL standard.
 *
 * <p>In {@link SqlLibraryOperatorTableFactory} this annotation is applied to
 * function definitions to include them in a particular library. It allows
 * an operator to belong to more than one library.
 *
 * @see LibraryOperator
 */
// 定义了 SQL 函数和操作符的逻辑分组。在 Calcite 处理多方言（Multi-dialect）支持时，这个类起到了“功能目录”的作用。
// SqlLibrary 的核心作用是管理 SQL 函数库的集合。
// 在 SQL 中，除了标准 SQL（ISO 标准）定义的函数外，各个数据库厂商（如 Oracle, MySQL, BigQuery）都有大量专有的内置函数（例如 MySQL 的 IFNULL，Oracle 的 DECODE）。
//Calcite 使用 SqlLibrary 来实现以下目标：
// 按方言隔离函数：允许开发者指定只加载特定数据库的函数库，避免函数冲突。
// 函数继承与复用：通过 parent 机制，允许一个库继承另一个库的特性（例如 Redshift 继承自 PostgreSQL）。
// 解析控制：在配置 Calcite 连接时，可以通过 fun 属性（如 fun=mysql,oracle）动态加载多个函数库。
public enum SqlLibrary {
  /** The standard operators. */
  // 标准 SQL 操作符。
  STANDARD("", "standard"),
  // 地理空间操作符（GIS 相关）
  /** Geospatial operators. */
  SPATIAL("s", "spatial"),
  /** A collection of operators that could be used in all libraries;
   * does not include STANDARD and SPATIAL. */
  // 一个伪库，代表除了标准和空间库之外的所有库的集合。
  ALL("*", "all"),
  /** A collection of operators that are in Google BigQuery but not in standard
   * SQL. */
  // 分别对应各数据库方言特有的函数库。
  BIG_QUERY("b", "bigquery"),
  /** Calcite-specific extensions. */
  // Calcite 自定义的扩展函数。
  CALCITE("c", "calcite"),
  /** A collection of operators that are in Apache Hive but not in standard
   * SQL. */
  HIVE("h", "hive"),
  /** A collection of operators that are in Microsoft SQL Server (MSSql) but not
   * in standard SQL. */
  MSSQL("q", "mssql"),
  /** A collection of operators that are in MySQL but not in standard SQL. */
  MYSQL("m", "mysql"),
  /** A collection of operators that are in Oracle but not in standard SQL. */
  ORACLE("o", "oracle"),
  /** A collection of operators that are in PostgreSQL but not in standard
   * SQL. */
  POSTGRESQL("p", "postgresql"),
  /** A collection of operators that are in Redshift
   * but not in standard SQL or PostgreSQL. */
  REDSHIFT("r", "redshift", POSTGRESQL),
  /** A collection of operators that are in Snowflake
   * but not in standard SQL. */
  SNOWFLAKE("f", "snowflake"),
  /** A collection of operators that are in Apache Spark but not in standard
   * SQL. */
  SPARK("s", "spark");

  /** Map from {@link Enum#name() name} and {@link #fun} to library. */
  public static final Map<String, SqlLibrary> MAP;

  /** Map of libraries to the set of libraries whose {@link SqlLibrary#parent}
   * link points to them. */
  // 反向继承表
  // 记录了哪些库把当前库当作 parent。例如，通过它可以查到 POSTGRESQL 有哪些继承者（如 REDSHIFT）。
  private static final Map<SqlLibrary, Set<SqlLibrary>> INHERITOR_MAP;

  /** Abbreviation for the library used in SQL reference. */
  // 库的缩写
  // 主要用于生成 SQL 参考文档中的标记（如 o 代表 Oracle），方便查阅。
  public final String abbrev;

  /** Name of this library when it appears in the connect string;
   * see {@link CalciteConnectionProperty#FUN}. */
  // 在连接字符串中使用的名称。
  // 与 CalciteConnectionProperty.FUN 对应。当你配置连接属性 fun=mysql 时，Calcite 会根据这个字符串找到对应的 SqlLibrary。
  public final String fun;

  /** The current library will by default inherit functions from parent. */
  // 父级库。
  // 定义了继承关系。例如 REDSHIFT 的 parent 是 POSTGRESQL。当加载子库时，通常会自动包含父库的函数。
  public final @Nullable SqlLibrary parent;

  SqlLibrary(String abbrev, String fun) {
    this(abbrev, fun, null);
  }

  SqlLibrary(String abbrev, String fun, @Nullable SqlLibrary parent) {
    this.abbrev = requireNonNull(abbrev, "abbrev");
    this.fun = requireNonNull(fun, "fun");
    this.parent = parent;
    checkArgument(fun.equals(name().toLowerCase(Locale.ROOT).replace("_", "")));
  }

  @SuppressWarnings("SwitchStatementWithTooFewBranches")
  public List<SqlLibrary> children() {
    switch (this) {
    case ALL:
      return ImmutableList.of(BIG_QUERY, CALCITE, HIVE, MSSQL, MYSQL, ORACLE,
          POSTGRESQL, REDSHIFT, SNOWFLAKE, SPARK);
    default:
      return ImmutableList.of();
    }
  }

  /** Returns the libraries that inherit this library's functions,
   * because their {@link #parent} field points to this.
   *
   * <p>For example, {@link #REDSHIFT} inherits from {@link #POSTGRESQL}.
   * Never returns null. */
  // 返回所有直接继承自该库的库。
  public Set<SqlLibrary> inheritors() {
    return first(INHERITOR_MAP.get(this), ImmutableSet.of());
  }

  /** Looks up a value.
   * Returns null if not found.
   * You can use upper- or lower-case name. */
  // 根据名称查找库。
  // 支持大小写不敏感的匹配，既可以匹配枚举名，也可以匹配 fun 字段。
  public static @Nullable SqlLibrary of(String name) {
    return MAP.get(name);
  }

  /** Parses a comma-separated string such as "standard,oracle". */
  // 解析逗号分隔的库名称字符串。
  // 将类似 "standard,mysql" 的字符串转换为 SqlLibrary 对象的列表。常用于处理连接参数。
  public static List<SqlLibrary> parse(String libraryNameList) {
    final ImmutableList.Builder<SqlLibrary> list = ImmutableList.builder();
    if (!libraryNameList.isEmpty()) {
      for (String libraryName : libraryNameList.split(",")) {
        @Nullable SqlLibrary library = SqlLibrary.of(libraryName);
        if (library == null) {
          throw new IllegalArgumentException("unknown library '" + libraryName
              + "'");
        }
        list.add(library);
      }
    }
    return list.build();
  }

  /** Expands libraries in place.
   *
   * <p>Preserves order, and ensures that no library occurs more than once. */
  // 递归展开库。
  // 如果列表中包含 ALL，它会调用 children() 将其展开为所有具体的方言库，并去重和保持顺序。
  public static List<SqlLibrary> expand(
      Iterable<? extends SqlLibrary> libraries) {
    // LinkedHashSet ensures that libraries are added only once, and order is
    // preserved.
    final Set<SqlLibrary> set = new LinkedHashSet<>();
    libraries.forEach(library -> addExpansion(set, library));
    return ImmutableList.copyOf(set);
  }

  private static void addExpansion(Set<SqlLibrary> set, SqlLibrary library) {
    if (set.add(library)) {
      library.children().forEach(subLibrary -> addExpansion(set, subLibrary));
    }
  }

  /** Expands libraries in place. If any library is a child of 'all', ensures
   * that 'all' is in the list. */
  // 向上扩展。
  // 如果列表中包含任何属于 ALL 子集的方言库，确保 ALL 也会被加入到集合中。
  public static List<SqlLibrary> expandUp(
      Iterable<? extends SqlLibrary> libraries) {
    // LinkedHashSet ensures that libraries are added only once, and order is
    // preserved.
    final Set<SqlLibrary> set = new LinkedHashSet<>();
    libraries.forEach(library -> addParent(set, library));
    return ImmutableList.copyOf(set);
  }
  // expandUp 方法的内部辅助函数。
  private static void addParent(Set<SqlLibrary> set, SqlLibrary library) {
    if (ALL.children().contains(library)) {
      set.add(ALL);
    }
    set.add(library);
  }

  static {
    final ImmutableMap.Builder<String, SqlLibrary> builder =
        ImmutableMap.builder();
    final List<SqlLibrary> libraries = Arrays.asList(values());
    for (SqlLibrary library : libraries) {
      builder.put(library.name(), library);
      builder.put(library.fun, library);
    }
    MAP = builder.build();
    final ImmutableMap.Builder<SqlLibrary, Set<SqlLibrary>> map =
        ImmutableMap.builder();
    for (SqlLibrary library : libraries) {
      map.put(library,
          ImmutableSet.copyOf(
              filter(libraries,
                  inheritor -> inheritor.parent == library)));
    }
    INHERITOR_MAP = map.build();
  }
}
