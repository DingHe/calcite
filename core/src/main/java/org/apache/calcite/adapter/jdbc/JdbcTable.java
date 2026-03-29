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

import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.java.AbstractQueryableTable;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.ColumnMetaData;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.prepare.Prepare.CatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.core.TableModify.Operation;
import org.apache.calcite.rel.logical.LogicalTableModify;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.runtime.ResultSetEnumerable;
import org.apache.calcite.schema.ModifiableTable;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.AbstractTableQueryable;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWriterConfig;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.pretty.SqlPrettyWriter;
import org.apache.calcite.sql.util.SqlString;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.base.Suppliers;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Queryable that gets its data from a table within a JDBC connection.
 *
 * <p>The idea is not to read the whole table, however. The idea is to use
 * this as a building block for a query, by applying Queryable operators
 * such as
 * {@link org.apache.calcite.linq4j.Queryable#where(org.apache.calcite.linq4j.function.Predicate2)}.
 * The resulting queryable can then be converted to a SQL query, which can be
 * executed efficiently on the JDBC server.
 */
// 在 Apache Calcite 的 JDBC 适配器中，JdbcTable 是最核心的实现类。它充当了 Calcite 逻辑世界与外部关系型数据库（通过 JDBC 连接）之间的桥梁。
// JdbcTable 的主要职责是将一个外部 JDBC 数据库中的表（或视图）包装成 Calcite 可以识别的 Table 对象：
// 元数据映射：它持有外部数据库表的名称、模式（Schema）以及字段类型信息。
// 查询转换（Push-down 的起点）：它实现了 TranslatableTable，能够将自己转化为 JdbcTableScan。这是 SQL 下推的关键，使得 Calcite 优化器可以将过滤、排序、聚合等操作直接交给底层的 MySQL/Oracle 等数据库处理。
// 直接数据读取：它实现了 ScannableTable，即使不经过复杂的优化器，也能通过 scan 方法直接执行 SQL 并获取结果。
// 写操作支持：通过实现 ModifiableTable，它支持对外部数据库进行 INSERT、UPDATE、DELETE 等 DML 操作。
public class JdbcTable extends AbstractQueryableTable
    implements TranslatableTable, ScannableTable, ModifiableTable {
  @SuppressWarnings("methodref.receiver.bound.invalid")
  // 利用 Guava Suppliers.memoize 实现的懒加载提供者
  private final Supplier<RelProtoDataType> protoRowTypeSupplier =
      Suppliers.memoize(this::supplyProto);
  // 所属的 JdbcSchema 对象。
  // 说明：通过它可以访问 DataSource（获取连接）和 SqlDialect（处理数据库方言）。
  public final JdbcSchema jdbcSchema;
  // 作用：物理数据库中的 Catalog 名称（可能为 null）。
  public final String jdbcCatalogName;
  // 作用：物理数据库中的 Schema 名称（可能为 null）。
  public final String jdbcSchemaName;
  // 作用：物理数据库中的表名或视图名。
  public final String jdbcTableName;
  // 作用：表的类型。
  public final Schema.TableType jdbcTableType;

  JdbcTable(JdbcSchema jdbcSchema, String jdbcCatalogName,
      String jdbcSchemaName, String jdbcTableName,
      Schema.TableType jdbcTableType) {
    super(Object[].class);
    this.jdbcSchema = requireNonNull(jdbcSchema, "jdbcSchema");
    this.jdbcCatalogName = jdbcCatalogName;
    this.jdbcSchemaName = jdbcSchemaName;
    this.jdbcTableName = requireNonNull(jdbcTableName, "jdbcTableName");
    this.jdbcTableType = requireNonNull(jdbcTableType, "jdbcTableType");
  }

  @Override public String toString() {
    return "JdbcTable {" + jdbcTableName + "}";
  }

  @Override public Schema.TableType getJdbcTableType() {
    return jdbcTableType;
  }
  // 允许用户直接从 JdbcTable 对象获取底层的 DataSource 或 SqlDialect。
  @Override public <C extends Object> @Nullable C unwrap(Class<C> aClass) {
    if (aClass.isInstance(jdbcSchema.getDataSource())) {
      return aClass.cast(jdbcSchema.getDataSource());
    } else if (aClass.isInstance(jdbcSchema.dialect)) {
      return aClass.cast(jdbcSchema.dialect);
    } else {
      return super.unwrap(aClass);
    }
  }
  // 获取该表的 Calcite 类型。
  // 内部通过 protoRowTypeSupplier 调用 supplyProto。
  @Override public RelDataType getRowType(RelDataTypeFactory typeFactory) {
    return protoRowTypeSupplier.get().apply(typeFactory);
  }
  // 核心私有方法。
  // 调用 jdbcSchema.getRelDataType，通过 JDBC 驱动查询数据库元数据（字段名、类型、精度等）。
  private RelProtoDataType supplyProto() {
    try {
      return jdbcSchema.getRelDataType(
          jdbcCatalogName,
          jdbcSchemaName,
          jdbcTableName);
    } catch (SQLException e) {
      throw new RuntimeException(
          "Exception while reading definition of table '" + jdbcTableName
              + "'", e);
    }
  }
  // 将 Calcite 类型转换为 Java 类型映射，用于后续将 JDBC ResultSet 转换为 Java 对象。
  private List<Pair<ColumnMetaData.Rep, Integer>> fieldClasses(
      final JavaTypeFactory typeFactory) {
    final RelDataType rowType = getRowType(typeFactory);
    return Util.transform(rowType.getFieldList(), f -> {
      final RelDataType type = f.getType();
      final Class clazz = (Class) typeFactory.getJavaClass(type);
      final ColumnMetaData.Rep rep =
          Util.first(ColumnMetaData.Rep.of(clazz),
              ColumnMetaData.Rep.OBJECT);
      return Pair.of(rep, type.getSqlTypeName().getJdbcOrdinal());
    });
  }
  // 生成该表的查询基准 SQL。
  // 生成一个简单的 SELECT * FROM table 语句
  // 关键点在于它使用 jdbcSchema.dialect 确保表名的引用（如反引号或双引号）符合底层数据库规范。
  SqlString generateSql() {
    final SqlNodeList selectList = SqlNodeList.SINGLETON_STAR;
    SqlSelect node =
        new SqlSelect(SqlParserPos.ZERO, SqlNodeList.EMPTY, selectList,
            tableName(), null, null, null, null, null, null, null, null, null);
    final SqlWriterConfig config = SqlPrettyWriter.config()
        .withAlwaysUseParentheses(true)
        .withDialect(jdbcSchema.dialect);
    final SqlPrettyWriter writer = new SqlPrettyWriter(config);
    node.unparse(writer, 0, 0);
    return writer.toSqlString();
  }

  /** Returns the table name, qualified with catalog and schema name if
   * applicable, as a parse tree node ({@link SqlIdentifier}). */
  public SqlIdentifier tableName() {
    final List<String> names = new ArrayList<>(3);
    if (jdbcSchema.catalog != null) {
      names.add(jdbcSchema.catalog);
    }
    if (jdbcSchema.schema != null) {
      names.add(jdbcSchema.schema);
    }
    names.add(jdbcTableName);
    return new SqlIdentifier(names, SqlParserPos.ZERO);
  }
  // 将该表转化为一个物理算子 JdbcTableScan。
  // 这是进入 Calcite 优化器的入口，标记该扫描操作由 JdbcConvention（JDBC 约定）负责。
  @Override public RelNode toRel(RelOptTable.ToRelContext context,
      RelOptTable relOptTable) {
    return new JdbcTableScan(context.getCluster(), context.getTableHints(), relOptTable, this,
        jdbcSchema.convention);
  }
  // 实现 QueryableTable 接口。
  // 返回一个 JdbcTableQueryable 对象，使得该表可以参与 linq4j 的流式查询。
  @Override public <T> Queryable<T> asQueryable(QueryProvider queryProvider,
      SchemaPlus schema, String tableName) {
    return new JdbcTableQueryable<>(queryProvider, schema, tableName);
  }
  // 直接扫描表。它生成 SQL 字符串，然后使用 ResultSetEnumerable 直接通过 JDBC 执行查询并返回结果。
  @Override public Enumerable<@Nullable Object[]> scan(DataContext root) {
    JavaTypeFactory typeFactory = root.getTypeFactory();
    final SqlString sql = generateSql();
    return ResultSetEnumerable.of(jdbcSchema.getDataSource(), sql.getSql(),
        JdbcUtils.rowBuilderFactory2(fieldClasses(typeFactory)));
  }
  // 返回 null。JDBC 表不是内存集合，因此不支持直接返回底层集合对象。
  @Override public @Nullable Collection getModifiableCollection() {
    return null;
  }
  // 作用：当 SQL 是 INSERT 或 UPDATE 时，该方法负责生成 LogicalTableModify 节点。
  @Override public TableModify toModificationRel(RelOptCluster cluster,
      RelOptTable table, CatalogReader catalogReader, RelNode input,
      Operation operation, @Nullable List<String> updateColumnList,
      @Nullable List<RexNode> sourceExpressionList, boolean flattened) {
    jdbcSchema.convention.register(cluster.getPlanner());

    return new LogicalTableModify(cluster, cluster.traitSetOf(Convention.NONE),
        table, catalogReader, input, operation, updateColumnList,
        sourceExpressionList, flattened);
  }

  /** Enumerable that returns the contents of a {@link JdbcTable} by connecting
   * to the JDBC data source.
   *
   * @param <T> element type */
  // 当执行查询时，它会获取 Java 类型工厂，生成 SQL，并利用 ResultSetEnumerable 创建一个 JDBC 枚举器。
  // 它负责将底层 java.sql.ResultSet 的每一行映射为 Calcite 需要的格式。
  private class JdbcTableQueryable<T> extends AbstractTableQueryable<T> {
    JdbcTableQueryable(QueryProvider queryProvider, SchemaPlus schema,
        String tableName) {
      super(queryProvider, schema, JdbcTable.this, tableName);
    }

    @Override public String toString() {
      return "JdbcTableQueryable {table: " + tableName + "}";
    }

    @Override public Enumerator<T> enumerator() {
      final JavaTypeFactory typeFactory =
          ((CalciteConnection) queryProvider).getTypeFactory();
      final SqlString sql = generateSql();
      final List<Pair<ColumnMetaData.Rep, Integer>> pairs =
          fieldClasses(typeFactory);
      @SuppressWarnings({"rawtypes", "unchecked"})
      final Enumerable<T> enumerable =
          (Enumerable) ResultSetEnumerable.of(jdbcSchema.getDataSource(),
              sql.getSql(), JdbcUtils.rowBuilderFactory2(pairs));
      return enumerable.enumerator();
    }
  }
}
