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
package org.apache.calcite.adapter.jdbc;

import org.apache.calcite.avatica.AvaticaUtils;
import org.apache.calcite.avatica.MetaImpl;
import org.apache.calcite.avatica.SqlType;
import org.apache.calcite.linq4j.function.Experimental;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeImpl;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.schema.Function;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.SchemaVersion;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.Wrapper;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlDialectFactory;
import org.apache.calcite.sql.SqlDialectFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import javax.sql.DataSource;

import static java.util.Objects.requireNonNull;

/**
 * Implementation of {@link Schema} that is backed by a JDBC data source.
 *
 * <p>The tables in the JDBC data source appear to be tables in this schema;
 * queries against this schema are executed against those tables, pushing down
 * as much as possible of the query logic to SQL.
 */
// JdbcSchema 是 JDBC 适配器（JDBC Adapter）的核心类。
// 它实现了 Calcite 的 Schema 接口，充当了本地 Calcite SQL 引擎与外部关系型数据库（如 MySQL, Oracle, PostgreSQL 等）之间的桥梁。
// JdbcSchema 的主要作用是将一个外部的 JDBC 数据源映射为 Calcite 内部的一个模式（Schema）。
// 元数据映射（Metadata Mapping）：它通过 JDBC 的 DatabaseMetaData 自动读取外部数据库中的表结构、字段、数据类型，并将其转换为 Calcite 内部的 RelDataType 和 Table。
// 计算下推（Push-down）：查询 JdbcSchema 中的表时，Calcite 会尽可能将 SQL 算子（如 Filter, Project, Join, Aggregate）下推并翻译成对应数据库的方言（Dialect），让外部数据库执行，以提高性能。
// 统一视图：它允许用户使用统一的 Calcite SQL 语法跨越多个不同的异构 JDBC 数据源进行联邦查询。
public class JdbcSchema implements Schema, Wrapper {
  private static final Logger LOGGER = LoggerFactory.getLogger(JdbcSchema.class);
  // Java 标准数据源，用于获取数据库物理连接 Connection。
  final DataSource dataSource;
  // 外部数据库的 Catalog（目录）名称。
  final @Nullable String catalog;
  // 外部数据库的 Schema（模式）名称模式。
  final @Nullable String schema;
  // SQL 方言（如 MysqlSqlDialect, OracleSqlDialect）。决定了 Calcite 如何将逻辑计划翻译成特定数据库可识别的 SQL 字符串。
  public final SqlDialect dialect;
  // Calcite 物理计划的调用约定。标记这些算子属于 JDBC 物理执行节点，用于代价优化器（CBO）识别。
  final JdbcConvention convention;
  // 模式内表名称到 JdbcTable 对象的缓存映射。
  private @Nullable ImmutableMap<String, JdbcTable> tableMap;
  // 标记当前 Schema 是否是一个静态快照。如果是快照，它不会在运行期间动态刷新表列表。
  private final boolean snapshot;
  // 一个线程本地变量（ThreadLocal），标记为 @Experimental，允许在特殊测试或上下文中覆盖默认的元数据获取逻辑。
  @Experimental
  public static final ThreadLocal<@Nullable Foo> THREAD_METADATA = new ThreadLocal<>();
  // 比较数据库 JDBC 版本（如 4.0 对比 4.1）的排序器工具。
  private static final Ordering<Iterable<Integer>> VERSION_ORDERING =
      Ordering.<Integer>natural().lexicographical();

  /**
   * Creates a JDBC schema.
   *
   * @param dataSource Data source
   * @param dialect SQL dialect
   * @param convention Calling convention
   * @param catalog Catalog name, or null
   * @param schema Schema name pattern
   */
  public JdbcSchema(DataSource dataSource, SqlDialect dialect,
      JdbcConvention convention, @Nullable String catalog, @Nullable String schema) {
    this(dataSource, dialect, convention, catalog, schema, null);
  }

  private JdbcSchema(DataSource dataSource, SqlDialect dialect,
      JdbcConvention convention, @Nullable String catalog, @Nullable String schema,
      @Nullable ImmutableMap<String, JdbcTable> tableMap) {
    this.dataSource = requireNonNull(dataSource, "dataSource");
    this.dialect = requireNonNull(dialect, "dialect");
    this.convention = convention;
    this.catalog = catalog;
    this.schema = schema;
    this.tableMap = tableMap;
    this.snapshot = tableMap != null;
  }

  public static JdbcSchema create(
      SchemaPlus parentSchema,
      String name,
      DataSource dataSource,
      @Nullable String catalog,
      @Nullable String schema) {
    return create(parentSchema, name, dataSource,
        SqlDialectFactoryImpl.INSTANCE, catalog, schema);
  }
  // 核心作用是：将一个外部的 JDBC 数据源（如 MySQL、PostgreSQL）集成到 Calcite 的元数据层中，并为其绑定相应的物理执行约定。
  public static JdbcSchema create(
      // 父节点 Schema。Calcite 的 Schema 是树状结构，通常 parent 是 Root。
      SchemaPlus parentSchema,
      // 该 JDBC Schema 在 Calcite 中的别名（如 "MYSQL_DB"）。
      String name,
      // 标准连接池对象（如 Druid, HikariCP），用于实际连接数据库。
      DataSource dataSource,
      // 方言工厂。用于根据数据库连接信息判断该数据库的 SQL 语法特性。
      SqlDialectFactory dialectFactory,
      // 对应数据库实例中的 Catalog 和 Schema 名称。
      @Nullable String catalog,
      @Nullable String schema) {
    // 构建一个 Java 表达式树（Expression Tree）
    // Calcite 的 Enumerable 约定会生成 Java 代码。
    // 为了在生成的代码中能够找到这个 JdbcSchema 对象（从而拿到 DataSource），需要记录一个“路径表达式”。比如：root.getSubSchema("MYSQL_DB")。
    final Expression expression =
        Schemas.subSchemaExpression(parentSchema, name, JdbcSchema.class);
    // 探测目标数据库类型。
    final SqlDialect dialect = createDialect(dialectFactory, dataSource);
    // 创建调用约定 (JdbcConvention)
    final JdbcConvention convention =
        JdbcConvention.of(dialect, expression, name);
    // 实例化该数据源专属的物理特征对象。
    return new JdbcSchema(dataSource, dialect, convention, catalog, schema);
  }

  /**
   * Creates a JdbcSchema, taking credentials from a map.
   *
   * @param parentSchema Parent schema
   * @param name Name
   * @param operand Map of property/value pairs
   * @return A JdbcSchema
   */
  public static JdbcSchema create(
      SchemaPlus parentSchema,
      String name,
      Map<String, Object> operand) {
    DataSource dataSource;
    try {
      final String dataSourceName = (String) operand.get("dataSource");
      if (dataSourceName != null) {
        dataSource =
            AvaticaUtils.instantiatePlugin(DataSource.class, dataSourceName);
      } else {
        final String jdbcUrl = (String) requireNonNull(operand.get("jdbcUrl"), "jdbcUrl");
        final String jdbcDriver = (String) operand.get("jdbcDriver");
        final String jdbcUser = (String) operand.get("jdbcUser");
        final String jdbcPassword = (String) operand.get("jdbcPassword");
        dataSource = dataSource(jdbcUrl, jdbcDriver, jdbcUser, jdbcPassword);
      }
    } catch (Exception e) {
      throw new RuntimeException("Error while reading dataSource", e);
    }
    String jdbcCatalog = (String) operand.get("jdbcCatalog");
    String jdbcSchema = (String) operand.get("jdbcSchema");
    String sqlDialectFactory = (String) operand.get("sqlDialectFactory");

    if (sqlDialectFactory == null || sqlDialectFactory.isEmpty()) {
      return JdbcSchema.create(
          parentSchema, name, dataSource, jdbcCatalog, jdbcSchema);
    } else {
      SqlDialectFactory factory =
          AvaticaUtils.instantiatePlugin(SqlDialectFactory.class,
              sqlDialectFactory);
      return JdbcSchema.create(parentSchema, name, dataSource, factory,
          jdbcCatalog, jdbcSchema);
    }
  }

  /**
   * Returns a suitable SQL dialect for the given data source.
   *
   * @param dataSource The data source
   *
   * @deprecated Use {@link #createDialect(SqlDialectFactory, DataSource)} instead
   */
  @Deprecated // to be removed before 2.0
  public static SqlDialect createDialect(DataSource dataSource) {
    return createDialect(SqlDialectFactoryImpl.INSTANCE, dataSource);
  }

  /** Returns a suitable SQL dialect for the given data source. */
  public static SqlDialect createDialect(SqlDialectFactory dialectFactory,
      DataSource dataSource) {
    return JdbcUtils.DialectPool.INSTANCE.get(dialectFactory, dataSource);
  }

  /** Creates a JDBC data source with the given specification. */
  public static DataSource dataSource(String url, @Nullable String driverClassName,
      @Nullable String username, @Nullable String password) {
    if (url.startsWith("jdbc:hsqldb:")) {
      // Prevent hsqldb from screwing up java.util.logging.
      System.setProperty("hsqldb.reconfig_logging", "false");
    }
    return JdbcUtils.DataSourcePool.INSTANCE.get(url, driverClassName, username,
        password);
  }

  @Override public boolean isMutable() {
    return false;
  }

  @Override public Schema snapshot(SchemaVersion version) {
    return new JdbcSchema(dataSource, dialect, convention, catalog, schema,
        tableMap);
  }

  // Used by generated code.
  public DataSource getDataSource() {
    return dataSource;
  }
  // 用于生成java代码
  @Override public Expression getExpression(@Nullable SchemaPlus parentSchema, String name) {
    requireNonNull(parentSchema, "parentSchema must not be null for JdbcSchema");
    return Schemas.subSchemaExpression(parentSchema, name, JdbcSchema.class);
  }

  protected Multimap<String, Function> getFunctions() {
    // TODO: populate map from JDBC metadata
    return ImmutableMultimap.of();
  }

  @Override public final Collection<Function> getFunctions(String name) {
    return getFunctions().get(name); // never null
  }

  @Override public final Set<String> getFunctionNames() {
    return getFunctions().keySet();
  }
  // 实现了将远程数据库的物理表结构“映射”到 Calcite 内存中的过程。简单来说，它就是 Calcite 连接外部数据库的探针。
  // 通过 JDBC 连接获取指定 Catalog 和 Schema 下的所有表元数据，并将它们转换成 Calcite 识别的 JdbcTable 对象。
  // 这些 JdbcTable 随后会被放入 ImmutableMap 中，当用户写 SQL 查询某张表时，Calcite 就会从这个 Map 中查找对应的表定义。
  private ImmutableMap<String, JdbcTable> computeTables() {
    Connection connection = null;
    ResultSet resultSet = null;
    try {
      // 建立连接并确定范围
      // getCatalogSchema 会智能判断当前的上下文环境，确定我们要扫描哪一个库（Catalog）和哪一个模式（Schema）。这是为了防止扫描到整个数据库集群的所有表，导致性能崩溃。
      connection = dataSource.getConnection();
      final Pair<@Nullable String, @Nullable String> catalogSchema = getCatalogSchema(connection);
      final String catalog = catalogSchema.left;
      final String schema = catalogSchema.right;
      final Iterable<MetaImpl.MetaTable> tableDefs;
      Foo threadMetadata = THREAD_METADATA.get();
      // 检查 THREAD_METADATA。如果当前线程已经注入了元数据（通常用于测试或特定优化场景），直接从 threadMetadata 获取。
      if (threadMetadata != null) {
        tableDefs = threadMetadata.apply(catalog, schema);
      } else {
        final List<MetaImpl.MetaTable> tableDefList = new ArrayList<>();
        final DatabaseMetaData metaData = connection.getMetaData();
        // 调用标准的 JDBC 接口
        resultSet = metaData.getTables(catalog, schema, null, null);
        while (resultSet.next()) {
          // 依次获取结果集中的第 1（Catalog）、2（Schema）、3（TableName）、4（TableType）个字段。
          final String catalogName = resultSet.getString(1);
          final String schemaName = resultSet.getString(2);
          final String tableName = resultSet.getString(3);
          final String tableTypeName = resultSet.getString(4);
          tableDefList.add(
              new MetaImpl.MetaTable(catalogName, schemaName, tableName,
                  tableTypeName));
        }
        tableDefs = tableDefList;
      }

      final ImmutableMap.Builder<String, JdbcTable> builder =
          ImmutableMap.builder();
      // 表类型清洗与转换
      // 不同的数据库对表类型的命名不规范。
      // 例如 Phoenix 可能返回 SYSTEM TABLE（中间有空格），代码会将其转换为 SYSTEM_TABLE 并转大写，以便匹配 Calcite 的 TableType 枚举。
      // 容错处理： 如果遇到无法识别的类型（如 PostgreSQL 的某些特殊表），会将其标记为 OTHER，并记录一条日志，而不是直接抛出异常导致失败。
      for (MetaImpl.MetaTable tableDef : tableDefs) {
        // Clean up table type. In particular, this ensures that 'SYSTEM TABLE',
        // returned by Phoenix among others, maps to TableType.SYSTEM_TABLE.
        // We know enum constants are upper-case without spaces, so we can't
        // make things worse.
        //
        // PostgreSQL returns tableTypeName==null for pg_toast* tables
        // This can happen if you start JdbcSchema off a "public" PG schema
        // The tables are not designed to be queried by users, however we do
        // not filter them as we keep all the other table types.
        final String tableTypeName2 =
            tableDef.tableType == null
            ? null
            : tableDef.tableType.toUpperCase(Locale.ROOT).replace(' ', '_');
        final TableType tableType =
            Util.enumVal(TableType.OTHER, tableTypeName2);
        if (tableType == TableType.OTHER  && tableTypeName2 != null) {
          LOGGER.info("Unknown table type: {}", tableTypeName2);
        }
        final JdbcTable table =
            new JdbcTable(this, tableDef.tableCat, tableDef.tableSchem,
                tableDef.tableName, tableType);
        builder.put(tableDef.tableName, table);
      }
      return builder.build();
    } catch (SQLException e) {
      throw new RuntimeException(
          "Exception while reading tables", e);
    } finally {
      close(connection, null, resultSet);
    }
  }

  /** Returns [major, minor] version from a database metadata. */
  private static List<Integer> version(DatabaseMetaData metaData) throws SQLException {
    return ImmutableList.of(metaData.getJDBCMajorVersion(),
        metaData.getJDBCMinorVersion());
  }

  /** Returns a pair of (catalog, schema) for the current connection. */
  // 核心作用是：在执行元数据扫描前，确定当前数据库连接究竟指向哪一个逻辑库（Catalog）和模式（Schema）
  // 在分布式或多租户数据库环境中，如果不能准确锁定这两个值，metaData.getTables 可能会扫描出成千上万张表，导致内存溢出或性能剧降。
  private Pair<@Nullable String, @Nullable String> getCatalogSchema(Connection connection)
      throws SQLException {
    final DatabaseMetaData metaData = connection.getMetaData();
    final List<Integer> version41 = ImmutableList.of(4, 1); // JDBC 4.1
    String catalog = this.catalog;
    String schema = this.schema;
    final boolean jdbc41OrAbove =
        VERSION_ORDERING.compare(version(metaData), version41) >= 0;
    if (catalog == null && jdbc41OrAbove) {
      // From JDBC 4.1, catalog and schema can be retrieved from the connection
      // object, hence try to get it from there if it was not specified by user
      catalog = connection.getCatalog();
    }
    if (schema == null && jdbc41OrAbove) {
      schema = connection.getSchema();
      if ("".equals(schema)) {
        schema = null; // PostgreSQL returns useless "" sometimes
      }
    }
    if ((catalog == null || schema == null)
        && metaData.getDatabaseProductName().equals("PostgreSQL")) {
      final String sql = "select current_database(), current_schema()";
      try (Statement statement = connection.createStatement();
           ResultSet resultSet = statement.executeQuery(sql)) {
        if (resultSet.next()) {
          catalog = resultSet.getString(1);
          schema = resultSet.getString(2);
        }
      }
    }
    return Pair.of(catalog, schema);
  }

  @Override public @Nullable Table getTable(String name) {
    return getTableMap(false).get(name);
  }

  private synchronized ImmutableMap<String, JdbcTable> getTableMap(
      boolean force) {
    if (force || tableMap == null) {
      tableMap = computeTables();
    }
    return tableMap;
  }

  RelProtoDataType getRelDataType(String catalogName, String schemaName,
      String tableName) throws SQLException {
    Connection connection = null;
    try {
      connection = dataSource.getConnection();
      DatabaseMetaData metaData = connection.getMetaData();
      return getRelDataType(metaData, catalogName, schemaName, tableName);
    } finally {
      close(connection, null, null);
    }
  }
  // Calcite JDBC 适配器中负责 “类型映射（Type Mapping）” 的核心逻辑。
  // 它的任务是将远程数据库（如 MySQL 或 Oracle）的物理列信息转换为 Calcite 内部的标准类型系统（RelDataType）。
  // getRelDataType 的主要职责是：通过 JDBC 的 getColumns 元数据接口，扫描指定表的每一列，获取其名称、数据类型、精度（Precision）、标度（Scale）以及是否允许为空（Nullable），最终构建出该表的 “行类型（Row Type）” 结构。
  RelProtoDataType getRelDataType(DatabaseMetaData metaData, String catalogName,
      String schemaName, String tableName) throws SQLException {
    final ResultSet resultSet =
        metaData.getColumns(catalogName, schemaName, tableName, null);

    // Temporary type factory, just for the duration of this method. Allowable
    // because we're creating a proto-type, not a type; before being used, the
    // proto-type will be copied into a real type factory.
    final RelDataTypeFactory typeFactory =
        new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
    final RelDataTypeFactory.Builder fieldInfo = typeFactory.builder();
    while (resultSet.next()) {
      final String columnName = requireNonNull(resultSet.getString(4), "columnName");
      final int dataType = resultSet.getInt(5);
      final String typeString = resultSet.getString(6);
      final int precision;
      final int scale;
      switch (SqlType.valueOf(dataType)) {
      case TIMESTAMP:
      case TIME:
        precision = resultSet.getInt(9); // SCALE
        scale = 0;
        break;
      default:
        precision = resultSet.getInt(7); // SIZE
        scale = resultSet.getInt(9); // SCALE
        break;
      }
      RelDataType sqlType =
          sqlType(typeFactory, dataType, precision, scale, typeString);
      boolean nullable = resultSet.getInt(11) != DatabaseMetaData.columnNoNulls;
      fieldInfo.add(columnName, sqlType).nullable(nullable);
    }
    resultSet.close();
    return RelDataTypeImpl.proto(fieldInfo.build());
  }

  private static RelDataType sqlType(RelDataTypeFactory typeFactory, int dataType,
      int precision, int scale, @Nullable String typeString) {
    // Fall back to ANY if type is unknown
    final SqlTypeName sqlTypeName =
        Util.first(SqlTypeName.getNameForJdbcType(dataType), SqlTypeName.ANY);
    switch (sqlTypeName) {
    case ARRAY:
      RelDataType component = null;
      if (typeString != null && typeString.endsWith(" ARRAY")) {
        // E.g. hsqldb gives "INTEGER ARRAY", so we deduce the component type
        // "INTEGER".
        final String remaining =
            typeString.substring(0, typeString.length() - " ARRAY".length());
        component = parseTypeString(typeFactory, remaining);
      }
      if (component == null) {
        component =
            typeFactory.createTypeWithNullability(
                typeFactory.createSqlType(SqlTypeName.ANY), true);
      }
      return typeFactory.createArrayType(component, -1);
    default:
      break;
    }
    if (precision >= 0
        && scale >= 0
        && sqlTypeName.allowsPrecScale(true, true)) {
      return typeFactory.createSqlType(sqlTypeName, precision, scale);
    } else if (precision >= 0 && sqlTypeName.allowsPrecNoScale()) {
      return typeFactory.createSqlType(sqlTypeName, precision);
    } else {
      assert sqlTypeName.allowsNoPrecNoScale();
      return typeFactory.createSqlType(sqlTypeName);
    }
  }

  /** Given "INTEGER", returns BasicSqlType(INTEGER).
   * Given "VARCHAR(10)", returns BasicSqlType(VARCHAR, 10).
   * Given "NUMERIC(10, 2)", returns BasicSqlType(NUMERIC, 10, 2). */
  private static RelDataType parseTypeString(RelDataTypeFactory typeFactory,
      String typeString) {
    int precision = -1;
    int scale = -1;
    int open = typeString.indexOf("(");
    if (open >= 0) {
      int close = typeString.indexOf(")", open);
      if (close >= 0) {
        String rest = typeString.substring(open + 1, close);
        typeString = typeString.substring(0, open);
        int comma = rest.indexOf(",");
        if (comma >= 0) {
          precision = Integer.parseInt(rest.substring(0, comma));
          scale = Integer.parseInt(rest.substring(comma));
        } else {
          precision = Integer.parseInt(rest);
        }
      }
    }
    try {
      final SqlTypeName typeName = SqlTypeName.valueOf(typeString);
      return typeName.allowsPrecScale(true, true)
          ? typeFactory.createSqlType(typeName, precision, scale)
          : typeName.allowsPrecScale(true, false)
          ? typeFactory.createSqlType(typeName, precision)
          : typeFactory.createSqlType(typeName);
    } catch (IllegalArgumentException e) {
      return typeFactory.createTypeWithNullability(
          typeFactory.createSqlType(SqlTypeName.ANY), true);
    }
  }

  @Override public Set<String> getTableNames() {
    // This method is called during a cache refresh. We can take it as a signal
    // that we need to re-build our own cache.
    return getTableMap(!snapshot).keySet();
  }

  protected Map<String, RelProtoDataType> getTypes() {
    // TODO: populate map from JDBC metadata
    return ImmutableMap.of();
  }

  @Override public @Nullable RelProtoDataType getType(String name) {
    return getTypes().get(name);
  }

  @Override public Set<String> getTypeNames() {
    //noinspection RedundantCast
    return (Set<String>) getTypes().keySet();
  }
  //不支持子schema
  @Override public @Nullable Schema getSubSchema(String name) {
    // JDBC does not support sub-schemas.
    return null;
  }

  @Override public Set<String> getSubSchemaNames() {
    return ImmutableSet.of();
  }

  @Override public <T extends Object> @Nullable T unwrap(Class<T> clazz) {
    if (clazz.isInstance(this)) {
      return clazz.cast(this);
    }
    if (clazz == DataSource.class) {
      return clazz.cast(getDataSource());
    }
    return null;
  }


  private static void close(
      @Nullable Connection connection,
      @Nullable Statement statement,
      @Nullable ResultSet resultSet) {
    if (resultSet != null) {
      try {
        resultSet.close();
      } catch (SQLException e) {
        // ignore
      }
    }
    if (statement != null) {
      try {
        statement.close();
      } catch (SQLException e) {
        // ignore
      }
    }
    if (connection != null) {
      try {
        connection.close();
      } catch (SQLException e) {
        // ignore
      }
    }
  }

  /** Schema factory that creates a
   * {@link org.apache.calcite.adapter.jdbc.JdbcSchema}.
   *
   * <p>This allows you to create a jdbc schema inside a model.json file, like
   * this:
   *
   * <blockquote><pre>
   * {
   *   "version": "1.0",
   *   "defaultSchema": "FOODMART_CLONE",
   *   "schemas": [
   *     {
   *       "name": "FOODMART_CLONE",
   *       "type": "custom",
   *       "factory": "org.apache.calcite.adapter.jdbc.JdbcSchema$Factory",
   *       "operand": {
   *         "jdbcDriver": "com.mysql.jdbc.Driver",
   *         "jdbcUrl": "jdbc:mysql://localhost/foodmart",
   *         "jdbcUser": "foodmart",
   *         "jdbcPassword": "foodmart"
   *       }
   *     }
   *   ]
   * }</pre></blockquote>
   */
  public static class Factory implements SchemaFactory {
    public static final Factory INSTANCE = new Factory();

    private Factory() {}

    @Override public Schema create(
        SchemaPlus parentSchema,
        String name,
        Map<String, Object> operand) {
      return JdbcSchema.create(parentSchema, name, operand);
    }
  }

  /** Do not use. */
  @Experimental
  public interface Foo
      extends BiFunction<@Nullable String, @Nullable String, Iterable<MetaImpl.MetaTable>> {
  }
}
