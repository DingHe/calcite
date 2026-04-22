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

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Pair;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import java.util.List;

/**
 * A namespace describes the relation returned by a section of a SQL query.
 *
 * <p>For example, in the query <code>SELECT emp.deptno, age FROM emp,
 * dept</code>, the FROM clause forms a namespace consisting of two tables EMP
 * and DEPT, and a row type consisting of the combined columns of those tables.
 *
 * <p>Other examples of namespaces include a table in the from list (the
 * namespace contains the constituent columns) and a sub-query (the namespace
 * contains the columns in the SELECT clause of the sub-query).
 *
 * <p>These various kinds of namespace are implemented by classes
 * {@link IdentifierNamespace} for table names, {@link SelectNamespace} for
 * SELECT queries, {@link SetopNamespace} for UNION, EXCEPT and INTERSECT, and
 * so forth. But if you are looking at a SELECT query and call
 * {@link SqlValidator#getNamespace(org.apache.calcite.sql.SqlNode)}, you may
 * not get a SelectNamespace. Why? Because the validator is allowed to wrap
 * namespaces in other objects which implement
 * {@link SqlValidatorNamespace}. Your SelectNamespace will be there somewhere,
 * but might be one or two levels deep.  Don't try to cast the namespace or use
 * <code>instanceof</code>; use {@link SqlValidatorNamespace#unwrap(Class)} and
 * {@link SqlValidatorNamespace#isWrapperFor(Class)} instead.
 *
 * @see SqlValidator
 * @see SqlValidatorScope
 *
 *SqlValidatorNamespace
 */
// 代表了 SQL 查询中某个部分所返回的关系（Relation）或结果集
// 是 SQL 校验阶段的“数据提供者”。
// 定义结果集结构：当你写 SELECT * FROM emp 时，emp 表就是一个 Namespace。它描述了该部分查询返回的列名、列类型以及行类型。
// 解耦 SQL 节点与类型：SqlNode 只是语法树节点，而 Namespace 将这些节点与具体的元数据（RelDataType）绑定在一起。
// 支持嵌套与包装：Namespace 可以嵌套。例如，一个 SELECT 查询是一个 Namespace，它内部引用的表又是另一个 Namespace。校验器经常会通过“装饰器模式”包装 Namespace 以增加功能。
// 校验的最小单元：校验器（SqlValidator）通过递归调用各个 Namespace 的 validate 方法来确保整个 SQL 语义正确。
// 示例 SELECT e.empno, d.name
//FROM emp AS e
//JOIN (SELECT * FROM dept WHERE region = 'US') AS d
//  ON e.deptno = d.deptno
// TableNamespace (表命名空间)  对于 FROM emp AS e 中的 emp： 对应节点：emp（标识符）。 作用：它从数据库元数据（Catalog）中读取 emp 表的结构。
// SelectNamespace (查询命名空间) 对于子查询 (SELECT * FROM dept WHERE region = 'US') AS d： 对应节点：中间的整个 SELECT 语句。作用：它并不直接对应一张表，而是一个计算结果集。校验器需要先校验这个子查询，推导出它返回哪些列。
// AliasNamespace / IdentifierNamespace (别名命名空间)  对于 AS e 和 AS d：用：当你使用 e.empno 时，校验器会先找到名为 e 的 Namespace。解析逻辑：e 这个 Namespace 会通过其 resolve() 方法指向真实的 emp 表 Namespace。

public interface SqlValidatorNamespace {
  //~ Methods ----------------------------------------------------------------

  /**
   * Returns the validator.
   *
   * @return validator
   */
  // 获取关联的 SQL 校验器实例。
  // Namespace 需要校验器提供的上下文信息（如类型工厂、错误处理逻辑）来完成自身的推导。
  SqlValidator getValidator();

  /**
   * Returns the underlying table, or null if there is none.
   */
  // 获取该 Namespace 对应的底层表（SqlValidatorTable）。
  // 如果该 Namespace 代表的是一个具体的表（如 IdentifierNamespace），则返回该表；如果是子查询或集合操作，则可能返回 null。
  @Nullable SqlValidatorTable getTable();

  /**
   * Returns the row type of this namespace, which comprises a list of names
   * and types of the output columns. If the scope's type has not yet been
   * derived, derives it.
   *
   * @return Row type of this namespace, never null, always a struct
   */
  // 获取该 Namespace 的行类型（包含列名和类型列表）
  // 核心方法。如果类型尚未推导（Derive），该方法会触发推导过程。它总是返回一个结构类型（Struct）。
  RelDataType getRowType();

  /**
   * Returns the type of this namespace.
   *
   * @return Row type converted to struct
   */
  // 获取 Namespace 的类型。
  // 通常与 getRowType() 相同，但它确保返回的是结构化后的类型。
  RelDataType getType();

  /**
   * Sets the type of this namespace.
   *
   * <p>Allows the type for the namespace to be explicitly set, but usually is
   * called during {@link #validate(RelDataType)}.
   *
   * <p>Implicitly also sets the row type. If the type is not a struct, then
   * the row type is the type wrapped as a struct with a single column,
   * otherwise the type and row type are the same.
   */
  // 显式设置该 Namespace 的类型。
  // 通常在校验过程中被调用。设置类型时，也会隐式更新对应的行类型。
  void setType(RelDataType type);

  /**
   * Returns the row type of this namespace, sans any system columns.
   *
   * @return Row type sans system columns
   */
  // 获取不包含系统列（如 Oracle 的 ROWID）的行类型。
  // 在执行 SELECT * 等操作时，通常只需要用户可见的列，而排除系统隐藏列。
  RelDataType getRowTypeSansSystemColumns();

  /**
   * Validates this namespace.
   *
   * <p>If the scope has already been validated, does nothing.
   *
   * <p>Please call {@link SqlValidatorImpl#validateNamespace} rather than
   * calling this method directly.
   *
   * @param targetRowType Desired row type, must not be null, may be the data
   *                      type 'unknown'.
   */
  // 执行 Namespace 内部的语义校验。
  // 这是校验流程的触发点。它会检查列是否存在、类型是否匹配等。开发者应通过 SqlValidatorImpl#validateNamespace 调用，而不是直接调用此方法。
  void validate(RelDataType targetRowType);

  /**
   * Returns the parse tree node at the root of this namespace.
   *
   * @return parse tree node; null for {@link TableNamespace}
   */
  // 获取该 Namespace 对应的抽象语法树（AST）根节点。
  // 例如，对于 SelectNamespace，返回的是 SqlSelect 对象。对于直接的表 Namespace，可能返回 null。
  @Nullable SqlNode getNode();

  /**
   * Returns the parse tree node that at is at the root of this namespace and
   * includes all decorations. If there are no decorations, returns the same
   * as {@link #getNode()}.
   */
  // 获取包含该 Namespace 的顶层节点，包含所有装饰（Decorations）。
  // 与 getNode() 类似，但在处理复杂的嵌套结构或带有特殊语法包装的节点时，它能提供更完整的信息。标注了 @Pure 表示无副作用。
  @Pure
  @Nullable SqlNode getEnclosingNode();

  /**
   * Looks up a child namespace of a given name.
   *
   * <p>For example, in the query <code>select e.name from emps as e</code>,
   * <code>e</code> is an {@link IdentifierNamespace} which has a child <code>
   * name</code> which is a {@link FieldNamespace}.
   *
   * @param name Name of namespace
   * @return Namespace
   */
  // 根据名称查找子 Namespace。
  // 用于处理复合对象。例如在 emp.address.city 中，address 可能是 emp 这个 Namespace 的一个子 Namespace。
  @Nullable SqlValidatorNamespace lookupChild(String name);

  /**
   * Returns whether this namespace has a field of a given name.
   *
   * @param name Field name
   * @return Whether field exists
   */
  // 判断是否存在指定名称的字段。
  // 默认实现是通过调用 field(name) 并判断是否为非空。
  default boolean fieldExists(String name) {
    return field(name) != null;
  }

  /**
   * Returns a field of a given name, or null.
   *
   * @param name Field name
   * @return Field, or null
   */
  // 根据名称获取具体的字段元数据（RelDataTypeField）。
  @Nullable RelDataTypeField field(String name);

  /**
   * Returns a list of expressions which are monotonic in this namespace. For
   * example, if the namespace represents a relation ordered by a column
   * called "TIMESTAMP", then the list would contain a
   * {@link org.apache.calcite.sql.SqlIdentifier} called "TIMESTAMP".
   */
  // 返回该 Namespace 中具有单调性（递增或递减）的表达式列表。
  // 例如某列是时间戳且按顺序排列，这对于流式查询（Streaming SQL）的优化至关重要。
  List<Pair<SqlNode, SqlMonotonicity>> getMonotonicExprs();

  /**
   * Returns whether and how a given column is sorted.
   */
  // 获取指定列的单调性类型（递增、常数、非单调等）。
  SqlMonotonicity getMonotonicity(String columnName);
  // 将 Namespace 的所有列标记为可空（Nullable）。
  @Deprecated // to be removed before 2.0
  void makeNullable();

  /**
   * Returns this namespace, or a wrapped namespace, cast to a particular
   * class.
   *
   * @param clazz Desired type
   * @return This namespace cast to desired type
   * @throws ClassCastException if no such interface is available
   */
  // 将 Namespace 转换为指定的具体实现类。
  // 由于 Namespace 经常被包装（Wrapper），直接强转会报错。必须通过此方法“拆包”获取内部的真实实例。
  <T> T unwrap(Class<T> clazz);

  /**
   * Returns whether this namespace implements a given interface, or wraps a
   * class which does.
   *
   * @param clazz Interface
   * @return Whether namespace implements given interface
   */
  // 判断当前 Namespace 是否是指定类的包装器，或者其自身就是该类。
  boolean isWrapperFor(Class<?> clazz);

  /** If this namespace resolves to another namespace, returns that namespace,
   * following links to the end of the chain.
   *
   * <p>A {@code WITH}) clause defines table names that resolve to queries
   * (the body of the with-item). An {@link IdentifierNamespace} typically
   * resolves to a {@link TableNamespace}.
   *
   * <p>You must not call this method before {@link #validate(RelDataType)} has
   * completed. */
  // 解析 Namespace 链，返回最终指向的实际 Namespace。
  // 处理别名或 WITH 语句。例如 WITH t AS (SELECT...)，t 的 Namespace 会通过 resolve() 最终指向 SELECT 的 Namespace。注意：必须在校验完成后调用。
  SqlValidatorNamespace resolve();

  /** Returns whether this namespace is capable of giving results of the desired
   * modality. {@code true} means streaming, {@code false} means relational.
   *
   * @param modality Modality
   */
  // 判断该 Namespace 是否支持指定的模式（流式 streaming 或 关系式 relational）。
  boolean supportsModality(SqlModality modality);

  /** Returns the ordinals (in the row type) of the "must-filter" fields,
   * fields that that must be filtered in a query. */
  // 返回必须在查询中被过滤（出现在 WHERE 子句）的字段索引。
  // 用于某些强制合规性或分区表的限制场景。默认返回空集。
  default ImmutableBitSet getMustFilterFields() {
    return ImmutableBitSet.of();
  }
}
