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
package org.apache.calcite.schema;

import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.rel.type.RelProtoDataType;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;
import java.util.Set;

/**
 * A namespace for tables and functions.
 * <p>A schema can also contain sub-schemas, to any level of nesting. Most
 * providers have a limited number of levels; for example, most JDBC databases
 * have either one level ("schemas") or two levels ("database" and
 * "catalog").
 *
 * <p>There may be multiple overloaded functions with the same name but
 * different numbers or types of parameters.
 * For this reason, {@link #getFunctions} returns a list of all
 * members with the same name. Calcite will call
 * {@link Schemas#resolve(org.apache.calcite.rel.type.RelDataTypeFactory, String, java.util.Collection, java.util.List)}
 * to choose the appropriate one.
 *
 * <p>The most common and important type of member is the one with no
 * arguments and a result type that is a collection of records. This is called a
 * <dfn>relation</dfn>. It is equivalent to a table in a relational
 * database.
 *
 * <p>For example, the query
 *
 * <blockquote>select * from sales.emps</blockquote>
 *
 * <p>is valid if "sales" is a registered
 * schema and "emps" is a member with zero parameters and a result type
 * of <code>Collection(Record(int: "empno", String: "name"))</code>.
 *
 * <p>A schema may be nested within another schema; see
 * {@link Schema#getSubSchema(String)}.
 */
// Schema 接口是核心支柱之一。
// 它定义了一个命名空间（Namespace），用于组织和查找数据库中的对象，如表、函数、类型以及嵌套的子模式
// Schema 接口的主要职责是充当元数据目录（Catalog/Directory）：
// 容器作用：它是一个层级化的容器，包含了 Table（表）、Function（函数）和 RelProtoDataType（类型）。
// 层级结构支持：支持子模式（Sub-schema）的嵌套，能够模拟 JDBC 中的 Catalog 和 Schema 两级结构，甚至无限深度的树状结构。
// SQL 解析支撑：当 Calcite 解析 SQL 语句（如 SELECT * FROM sales.emps）时，它会递归地通过 Schema 接口查找 sales 这个模式，再从该模式中查找 emps 表。
// 代码生成引用：它定义了如何通过 linq4j 表达式在生成的 Java 代码中引用该模式。

public interface Schema {
  /**
   * Returns a table with a given name, or null if not found.
   * @param name Table name
   * @return Table, or null
   */
  // 根据名称获取具体的 Table 对象。
  // 说明：这是最常用的方法。如果表不存在，返回 null。
  @Nullable Table getTable(String name);

  /**
   * Returns the names of the tables in this schema.
   * @return Names of the tables in this schema
   */
  // 作用：获取该模式下所有表的名称集合。
  // 说明：用于元数据查询或 SQL 自动补全。
  Set<String> getTableNames();

  /**
   * Returns a type with a given name, or null if not found.
   * @param name Table name
   * @return Table, or null
   */
  // 作用：根据名称获取自定义的数据类型原型（RelProtoDataType）。
  @Nullable RelProtoDataType getType(String name);

  /**
   * Returns the names of the types in this schema.
   *
   * @return Names of the tables in this schema
   */
  // 作用：获取该模式下所有类型的名称集合。
  Set<String> getTypeNames();

  /**
   * Returns a list of functions in this schema with the given name, or
   * an empty list if there is no such function.
   * @param name Name of function
   * @return List of functions with given name, or empty list
   */
  // 作用：根据名称获取函数列表。
  // 说明：由于 Java 和 SQL 支持函数重载（同名但参数不同），因此返回的是一个 Collection<Function>，由 Calcite 之后进行参数匹配。
  Collection<Function> getFunctions(String name);

  /**
   * Returns the names of the functions in this schema.
   * @return Names of the functions in this schema
   */
  // 作用：获取该模式下所有函数的名称集合。
  Set<String> getFunctionNames();

  /**
   * Returns a sub-schema with a given name, or null.
   * @param name Sub-schema name
   * @return Sub-schema with a given name, or null
   */
  // 作用：获取嵌套在当前模式下的子模式。
  // 说明：用于支持类似 catalog.schema.table 的多级路径。
  @Nullable Schema getSubSchema(String name);

  /**
   * Returns the names of this schema's child schemas.
   * @return Names of this schema's child schemas
   */
  // 作用：获取所有子模式的名称集合。
  Set<String> getSubSchemaNames();

  /**
   * Returns the expression by which this schema can be referenced in generated
   * code.
   * @param parentSchema Parent schema
   * @param name Name of this schema
   * @return Expression by which this schema can be referenced in generated code
   */
  // 作用：生成引用此模式的 linq4j 表达式。
  // 说明：在 Calcite 将查询计划转换为 Java 代码执行时，需要知道如何在运行期定位到这个 Schema 对象。
  Expression getExpression(@Nullable SchemaPlus parentSchema, String name);

  /** Returns whether the user is allowed to create new tables, functions
   * and sub-schemas in this schema, in addition to those returned automatically
   * by methods such as {@link #getTable(String)}.
   *
   * <p>Even if this method returns true, the maps are not modified. Calcite
   * stores the defined objects in a wrapper object.
   * @return Whether the user is allowed to create new tables, functions
   *   and sub-schemas in this schema
   */
  // 作用：返回该模式是否允许动态修改。
  // 说明：如果为 true，用户可以向其中添加新表或函数。
  boolean isMutable();

  /** Returns the snapshot of this schema as of the specified time. The
   * contents of the schema snapshot should not change over time.
   *
   * @param version The current schema version
   *
   * @return the schema snapshot.
   */
  // 作用：获取该模式在特定版本/时间点的快照。
  // 说明：确保在长事务或复杂的查询计划生成过程中，元数据保持一致性，不被外部修改干扰。
  Schema snapshot(SchemaVersion version);

  /** Table type. */
  // 枚举定义了 Calcite 识别的各种表类型，以便在生成的元数据（如 DatabaseMetaData）中正确归类：
  enum TableType {
    /** A regular table.
     *
     * <p>Used by DB2, MySQL, PostgreSQL and others. */
    TABLE,

    /** A relation whose contents are calculated by evaluating a SQL
     * expression.
     *
     * <p>Used by DB2, PostgreSQL and others. */
    VIEW,

    /** Foreign table.
     *
     * <p>Used by PostgreSQL. */
    FOREIGN_TABLE,

    /** Materialized view.
     *
     * <p>Used by PostgreSQL. */
    MATERIALIZED_VIEW,

    /** Index table.
     *
     * <p>Used by Apache Phoenix, PostgreSQL. */
    INDEX,

    /** Join table.
     *
     * <p>Used by Apache Phoenix. */
    JOIN,

    /** Sequence table.
     *
     * <p>Used by Apache Phoenix, Oracle, PostgreSQL and others.
     * In Phoenix, must have a single BIGINT column called "$seq". */
    SEQUENCE,

    /** A structure, similar to a view, that is the basis for auto-generated
     * materializations. It is either a single table or a collection of tables
     * that are joined via many-to-one relationships from a central hub table.
     * It is not available for queries, but is just used as an intermediate
     * structure during query planning. */
    STAR,

    /** Stream. */
    STREAM,

    /** Type.
     *
     * <p>Used by PostgreSQL. */
    TYPE,

    /** A table maintained by the system. Data dictionary tables, such as the
     * "TABLES" and "COLUMNS" table in the "metamodel" schema, examples of
     * system tables.
     *
     * <p>Specified by the JDBC standard and used by DB2, MySQL, Oracle,
     * PostgreSQL and others. */
    SYSTEM_TABLE,

    /** System view.
     *
     * <p>Used by PostgreSQL, MySQL. */
    SYSTEM_VIEW,

    /** System index.
     *
     * <p>Used by PostgreSQL. */
    SYSTEM_INDEX,

    /** System TOAST index.
     *
     * <p>Used by PostgreSQL. */
    SYSTEM_TOAST_INDEX,

    /** System TOAST table.
     *
     * <p>Used by PostgreSQL. */
    SYSTEM_TOAST_TABLE,

    /** Temporary index.
     *
     * <p>Used by PostgreSQL. */
    TEMPORARY_INDEX,

    /** Temporary sequence.
     *
     * <p>Used by PostgreSQL. */
    TEMPORARY_SEQUENCE,

    /** Temporary table.
     *
     * <p>Used by PostgreSQL. */
    TEMPORARY_TABLE,

    /** Temporary view.
     *
     * <p>Used by PostgreSQL. */
    TEMPORARY_VIEW,

    /** A table that is only visible to one connection.
     *
     * <p>Specified by the JDBC standard and used by PostgreSQL, MySQL. */
    LOCAL_TEMPORARY,

    /** A synonym.
     *
     * <p>Used by DB2, Oracle. */
    SYNONYM,

    /** An alias.
     *
     * <p>Specified by the JDBC standard. */
    ALIAS,

    /** A global temporary table.
     *
     * <p>Specified by the JDBC standard. */
    GLOBAL_TEMPORARY,

    /** An accel-only table.
     *
     * <p>Used by DB2.
     */
    ACCEL_ONLY_TABLE,

    /** An auxiliary table.
     *
     * <p>Used by DB2.
     */
    AUXILIARY_TABLE,

    /** A global temporary table.
     *
     * <p>Used by DB2.
     */
    GLOBAL_TEMPORARY_TABLE,

    /** A hierarchy table.
     *
     * <p>Used by DB2.
     */
    HIERARCHY_TABLE,

    /** An inoperative view.
     *
     * <p>Used by DB2.
     */
    INOPERATIVE_VIEW,

    /** A materialized query table.
     *
     * <p>Used by DB2.
     */
    MATERIALIZED_QUERY_TABLE,

    /** A nickname.
     *
     * <p>Used by DB2.
     */
    NICKNAME,

    /** A typed table.
     *
     * <p>Used by DB2.
     */
    TYPED_TABLE,

    /** A typed view.
     *
     * <p>Used by DB2.
     */
    TYPED_VIEW,

    /**
     * A temporal table.
     *
     * <p>Used by MS SQL, Oracle and others
     */
    TEMPORAL_TABLE,

    /** Table type not known to Calcite.
     *
     * <p>If you get one of these, please fix the problem by adding an enum
     * value. */
    OTHER;

    /** The name used in JDBC. For example "SYSTEM TABLE" rather than
     * "SYSTEM_TABLE". */
    public final String jdbcName;

    TableType() {
      this.jdbcName = name().replace('_', ' ');
    }
  }
}
