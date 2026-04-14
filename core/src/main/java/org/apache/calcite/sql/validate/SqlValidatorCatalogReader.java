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

import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.Wrapper;
import org.apache.calcite.sql.SqlIdentifier;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Supplies catalog information for {@link SqlValidator}.
 *
 * <p>This interface only provides a thin API to the underlying repository, and
 * this is intentional. By only presenting the repository information of
 * interest to the validator, we reduce the dependency on exact mechanism to
 * implement the repository. It is also possible to construct mock
 * implementations of this interface for testing purposes.
 */
// SqlValidatorCatalogReader 是连接 SQL 校验器（SqlValidator） 与 元数据仓库（Catalog/Schema） 的核心桥梁。
// SqlValidatorCatalogReader 的主要作用是为 SQL 校验过程提供元数据查询服务。
// 当校验器（SqlValidator）处理一条 SQL 语句（如 SELECT * FROM emp）时，它需要知道：
// 表 emp 是否存在？
// emp 表有哪些列？
// 用户自定义的类型（UDT）是否存在？
// 当前的名称匹配规则（是否大小写敏感）是什么？
public interface SqlValidatorCatalogReader extends Wrapper {
  //~ Methods ----------------------------------------------------------------

  /**
   * Finds a table or schema with the given name, possibly qualified.
   *
   * <p>Uses the case-sensitivity policy of the catalog reader.
   *
   * <p>If not found, returns null. If you want a more descriptive error
   * message or to override the case-sensitivity of the match, use
   * {@link SqlValidatorScope#resolveTable}.
   *
   * @param names Name of table, may be qualified or fully-qualified
   *
   * @return Table with the given name, or null
   */
  // 根据给定的限定名（如 ["catalog", "schema", "table"]）查找表或架构
  // 根据 CatalogReader 内部定义的大小写敏感策略进行查找。如果找不到，返回 null。它是校验器确认表存在与否的首选方法。
  @Nullable SqlValidatorTable getTable(List<String> names);

  /**
   * Finds a user-defined type with the given name, possibly qualified.
   *
   * <p>NOTE jvs 12-Feb-2005: the reason this method is defined here instead
   * of on RelDataTypeFactory is that it has to take into account
   * context-dependent information such as SQL schema path, whereas a type
   * factory is context-independent.
   *
   * @param typeName Name of type
   * @return named type, or null if not found
   */
  // 根据给定的标识符查找用户定义类型（User-defined type）。
  // 之所以定义在这里而非类型工厂（Type Factory），是因为类型的解析往往依赖于当前的 Schema 路径上下文（Context-dependent），而类型工厂通常是上下文无关的。
  @Nullable RelDataType getNamedType(SqlIdentifier typeName);

  /**
   * Given fully qualified schema name, returns schema object names as
   * specified. They can be schema, table, function, view.
   * When names array is empty, the contents of root schema should be returned.
   *
   * @param names the array contains fully qualified schema name or empty
   *              list for root schema
   * @return the list of all object (schema, table, function,
   *         view) names under the above criteria
   */
  // 获取指定 Schema 下所有对象的名称（包括子 Schema、表、函数、视图）。
  // 如果传入的 names 为空，则返回根架构（Root Schema）下的内容。这通常用于 SQL 自动补全或元数据浏览。
  List<SqlMoniker> getAllSchemaObjectNames(List<String> names);

  /**
   * Returns the paths of all schemas to look in for tables.
   *
   * @return paths of current schema and root schema
   */
  // 返回查找表时需要搜索的所有 Schema 路径
  // 例如，如果当前在 SALES 架构下，路径可能包含 ["SALES"] 和 ["ROOT"]，这决定了名称解析的优先级。
  List<List<String>> getSchemaPaths();

  // CHECKSTYLE: IGNORE 1
  /** @deprecated Use
   * {@link #nameMatcher()}.{@link SqlNameMatcher#field(RelDataType, String)} */
  // 根据别名查找字段。现应使用 nameMatcher().field(...)。
  @Deprecated // to be removed before 2.0
  @Nullable RelDataTypeField field(RelDataType rowType, String alias);

  /** Returns an implementation of
   * {@link org.apache.calcite.sql.validate.SqlNameMatcher}
   * that matches the case-sensitivity policy. */
  // 返回一个 SqlNameMatcher 实例。
  // 这是目前最推荐的用于处理名称匹配的方法。它封装了大小写敏感性逻辑，决定了 EMP 是否能匹配上 emp。
  SqlNameMatcher nameMatcher();

  // CHECKSTYLE: IGNORE 1
  /** @deprecated Use
   * {@link #nameMatcher()}.{@link SqlNameMatcher#matches(String, String)} */
  // 判断两个字符串是否匹配。现应使用 nameMatcher().matches(...)。
  @Deprecated // to be removed before 2.0
  boolean matches(String string, String name);
  // 基于原始行类型和指定的列名列表，创建一个投影（Projection）后的新类型。
  // 常用于处理 SELECT a, c FROM table(a, b, c) 这种只选择部分列的情况。
  RelDataType createTypeFromProjection(RelDataType type,
      List<String> columnNameList);

  // CHECKSTYLE: IGNORE 1
  /** @deprecated Use
   * {@link #nameMatcher()}.{@link SqlNameMatcher#isCaseSensitive()} */
  // 判断是否大小写敏感。现应使用 nameMatcher().isCaseSensitive()。
  @Deprecated // to be removed before 2.0
  boolean isCaseSensitive();

  /** Returns the root namespace for name resolution. */
  // 返回元数据的根命名空间（CalciteSchema）。
  // 它是整个元数据树的起点。
  CalciteSchema getRootSchema();

  /** Returns Config settings. */
  // 获取连接配置设置（CalciteConnectionConfig）。
  // 包含了诸如大小写策略、符合标准的 SQL 兼容性设置等参数。
  CalciteConnectionConfig getConfig();
}
