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

import org.apache.calcite.config.NullCollation;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.TimeFrame;
import org.apache.calcite.rel.type.TimeFrameSet;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.runtime.CalciteException;
import org.apache.calcite.runtime.Resources;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlLambda;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlMatchRecognize;
import org.apache.calcite.sql.SqlMerge;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.SqlWindow;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.type.SqlTypeCoercionRule;
import org.apache.calcite.sql.type.SqlTypeMappingRule;
import org.apache.calcite.sql.validate.implicit.TypeCoercion;
import org.apache.calcite.sql.validate.implicit.TypeCoercionFactory;
import org.apache.calcite.sql.validate.implicit.TypeCoercions;

import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;
import org.immutables.value.Value;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Validates the parse tree of a SQL statement, and provides semantic
 * information about the parse tree.
 *
 * <p>To create an instance of the default validator implementation, call
 * {@link SqlValidatorUtil#newValidator}.
 *
 * <h2>Visitor pattern</h2>
 *
 * <p>The validator interface is an instance of the
 * {@link org.apache.calcite.util.Glossary#VISITOR_PATTERN visitor pattern}.
 * Implementations
 * of the {@link SqlNode#validate} method call the <code>validateXxx</code>
 * method appropriate to the kind of node:
 * <ul>
 * <li>{@link SqlLiteral#validate(SqlValidator, SqlValidatorScope)}
 *     calls
 *     {@link #validateLiteral(org.apache.calcite.sql.SqlLiteral)};
 * <li>{@link SqlCall#validate(SqlValidator, SqlValidatorScope)}
 *     calls
 *     {@link #validateCall(SqlCall, SqlValidatorScope)};
 * <li>and so forth.</ul>
 *
 * <p>The {@link SqlNode#validateExpr(SqlValidator, SqlValidatorScope)} method
 * is as {@link SqlNode#validate(SqlValidator, SqlValidatorScope)} but is called
 * when the node is known to be a scalar expression.
 *
 * <h2>Scopes and namespaces</h2>
 *
 * <p>In order to resolve names to objects, the validator builds a map of the
 * structure of the query. This map consists of two types of objects. A
 * {@link SqlValidatorScope} describes the tables and columns accessible at a
 * particular point in the query; and a {@link SqlValidatorNamespace} is a
 * description of a data source used in a query.
 *
 * <p>There are different kinds of namespace for different parts of the query.
 * for example {@link IdentifierNamespace} for table names,
 * {@link SelectNamespace} for SELECT queries,
 * {@link SetopNamespace} for UNION, EXCEPT
 * and INTERSECT. A validator is allowed to wrap namespaces in other objects
 * which implement {@link SqlValidatorNamespace}, so don't try to cast your
 * namespace or use <code>instanceof</code>; use
 * {@link SqlValidatorNamespace#unwrap(Class)} and
 * {@link SqlValidatorNamespace#isWrapperFor(Class)} instead.
 *
 * <p>The validator builds the map by making a quick scan over the query when
 * the root {@link SqlNode} is first provided. Thereafter, it supplies the
 * correct scope or namespace object when it calls validation methods.
 *
 * <p>The methods {@link #getSelectScope}, {@link #getFromScope},
 * {@link #getWhereScope}, {@link #getGroupScope}, {@link #getHavingScope},
 * {@link #getOrderScope} and {@link #getJoinScope} get the correct scope
 * to resolve
 * names in a particular clause of a SQL statement.
 */
// SqlValidator 的主要职责是执行 SQL 的语义校验（Semantic Validation）。仅仅语法正确（Parser 通过）是不够的，还需要确保 SQL 在逻辑上是有意义的。
// 语义校验阶段的核心接口。它位于 SQL 解析（Parser）之后、逻辑计划转换（SqlToRelConverter）之前。
// 其核心职责是：将“看起来像 SQL”的语法树（SqlNode）转变为“语义正确且含义明确”的校验树，并为树中的每个节点分配数据类型（RelDataType）。
// 名称解析（Name Resolution）：将 SQL 中的标识符（如 empno）映射到元数据中的具体对象（如 EMP 表的第 1 列）。
// 类型推导（Type Derivation）：确定每个表达式、函数调用、查询结果集的返回类型。
// 合法性检查（Validation）：检查 SQL 是否违反语义规则（如：聚合查询中引用了非聚合列、函数参数类型不匹配、表不存在等）。
// SQL 重写（Rewriting）：为了消除歧义或简化结构，校验器会重写部分语法树（如展开 SELECT * 为具体的列名列表）。
// 维护命名空间与作用域：管理查询中不同位置（如 WHERE、GROUP BY）可见的表和列。
@Value.Enclosing
public interface SqlValidator {
  //~ Methods ----------------------------------------------------------------

  /**
   * Returns the catalog reader used by this validator.
   *
   * @return catalog reader
   */
  // 获取元数据读取器，用于从数据库 Schema 中查找表、函数和字段。
  @Pure
  SqlValidatorCatalogReader getCatalogReader();

  /**
   * Returns the operator table used by this validator.
   *
   * @return operator table
   */
  // 获取运算符表，包含 SQL 注册的所有内置和自定义函数/运算符。
  @Pure
  SqlOperatorTable getOperatorTable();

  /**
   * Validates an expression tree. You can call this method multiple times,
   * but not reentrantly.
   *
   * @param topNode top of expression tree to be validated
   * @return validated tree (possibly rewritten)
   */
  // 最重要的入口。
  // 对整棵 SQL 语法树进行深度优先遍历校验，返回校验后（可能被重写）的树。
  SqlNode validate(SqlNode topNode);

  /**
   * Validates an expression tree. You can call this method multiple times,
   * but not reentrantly.
   *
   * @param topNode       top of expression tree to be validated
   * @param nameToTypeMap map of simple name to {@link RelDataType}; used to
   *                      resolve {@link SqlIdentifier} references
   * @return validated tree (possibly rewritten)
   */
  // 校验带有动态参数的表达式，允许传入名称到类型的映射。
  SqlNode validateParameterizedExpression(
      SqlNode topNode,
      Map<String, RelDataType> nameToTypeMap);

  /**
   * Checks that a query is valid.
   *
   * <p>Valid queries include:
   *
   * <ul>
   * <li><code>SELECT</code> statement,
   * <li>set operation (<code>UNION</code>, <code>INTERSECT</code>, <code>
   * EXCEPT</code>)
   * <li>identifier (e.g. representing use of a table in a FROM clause)
   * <li>query aliased with the <code>AS</code> operator
   * </ul>
   *
   * @param node  Query node
   * @param scope Scope in which the query occurs
   * @param targetRowType Desired row type, must not be null, may be the data
   *                      type 'unknown'.
   * @throws RuntimeException if the query is not valid
   */
  // 专门校验查询类节点（SELECT、UNION 等），检查结果集是否符合目标行类型。
  void validateQuery(SqlNode node, SqlValidatorScope scope,
      RelDataType targetRowType);

  /**
   * Returns the type assigned to a node by validation.
   *
   * @param node the node of interest
   * @return validated type, never null
   */
  // 获取已校验节点推导出的数据类型。若未推导出则抛出异常。
  RelDataType getValidatedNodeType(SqlNode node);

  /**
   * Returns the type assigned to a node by validation, or null if unknown.
   * This allows for queries against nodes such as aliases, which have no type
   * of their own. If you want to assert that the node of interest must have a
   * type, use {@link #getValidatedNodeType} instead.
   *
   * @param node the node of interest
   * @return validated type, or null if unknown or not applicable
   */
  // 尝试获取类型，若未知则返回 null（常用于别名解析）。
  @Nullable RelDataType getValidatedNodeTypeIfKnown(SqlNode node);

  /**
   * Returns the types of a call's operands.
   *
   * <p>Returns null if the call has not been validated, or if the operands'
   * types do not differ from their types as expressions.
   *
   * <p>This method is most useful when some of the operands are of type ANY,
   * or if they need to be coerced to be consistent with other operands, or
   * with the needs of the function.
   *
   * @param call Call
   * @return List of operands' types, or null if not known or 'obvious'
   */
  // 获取一个函数调用中各个操作数的校验后类型。
  @Nullable List<RelDataType> getValidatedOperandTypes(SqlCall call);

  /**
   * Resolves an identifier to a fully-qualified name.
   *
   * @param id    Identifier
   * @param scope Naming scope
   */
  // 校验标识符（表名、列名），将其与作用域绑定。
  void validateIdentifier(SqlIdentifier id, SqlValidatorScope scope);

  /**
   * Validates a literal.
   *
   * @param literal Literal
   */
  // 校验常量（数字、字符串）。
  void validateLiteral(SqlLiteral literal);

  /**
   * Validates a {@link SqlIntervalQualifier}.
   *
   * @param qualifier Interval qualifier
   */
  // 校验时间间隔限定符（如 YEAR TO MONTH）。
  void validateIntervalQualifier(SqlIntervalQualifier qualifier);

  /**
   * Validates an INSERT statement.
   *
   * @param insert INSERT statement
   */
  // 校验插入语句的源与目标匹配性。
  void validateInsert(SqlInsert insert);

  /**
   * Validates an UPDATE statement.
   *
   * @param update UPDATE statement
   */
  // 校验更新语句。
  void validateUpdate(SqlUpdate update);

  /**
   * Validates a DELETE statement.
   *
   * @param delete DELETE statement
   */
  // 校验删除语句。
  void validateDelete(SqlDelete delete);

  /**
   * Validates a MERGE statement.
   *
   * @param merge MERGE statement
   */
  // 校验 MERGE 语句。
  void validateMerge(SqlMerge merge);

  /**
   * Validates a data type expression.
   *
   * @param dataType Data type
   */
  // 校验 SQL 中的类型声明（如 CAST(x AS INT) 中的 INT）。
  void validateDataType(SqlDataTypeSpec dataType);

  /**
   * Validates a dynamic parameter.
   *
   * @param dynamicParam Dynamic parameter
   */
  // 校验动态参数（问号占位符）。
  void validateDynamicParam(SqlDynamicParam dynamicParam);

  /**
   * Validates the right-hand side of an OVER expression. It might be either
   * an {@link SqlIdentifier identifier} referencing a window, or an
   * {@link SqlWindow inline window specification}.
   *
   * @param windowOrId SqlNode that can be either SqlWindow with all the
   *                   components of a window spec or a SqlIdentifier with the
   *                   name of a window spec.
   * @param scope      Naming scope
   * @param call       the SqlNode if a function call if the window is attached
   *                   to one.
   */
  // 校验窗口函数（OVER 子句）。
  void validateWindow(
      SqlNode windowOrId,
      SqlValidatorScope scope,
      @Nullable SqlCall call);

  /**
   * Validates a MATCH_RECOGNIZE clause.
   *
   * @param pattern MATCH_RECOGNIZE clause
   */
  // 校验复杂事件处理（CEP）中的 MATCH_RECOGNIZE 子句。
  void validateMatchRecognize(SqlCall pattern);

  /**
   * Validates a lambda expression. lambda expression will be validated twice
   * during the validation process. The first time is validate lambda expression
   * namespace, the second time is when validating higher order function operands
   * type check.
   *
   * @param lambdaExpr Lambda expression
   */
  // 校验 Lambda 表达式（通常用于高级函数）
  void validateLambda(SqlLambda lambdaExpr);

  /**
   * Validates a call to an operator.
   *
   * @param call  Operator call
   * @param scope Naming scope
   */
  // 校验运算符或函数调用。
  void validateCall(
      SqlCall call,
      SqlValidatorScope scope);

  /**
   * Validates parameters for aggregate function.
   *
   * @param aggCall      Call to aggregate function
   * @param filter       Filter ({@code FILTER (WHERE)} clause), or null
   * @param distinctList Distinct specification ({@code WITHIN DISTINCT}
   *                     clause), or null
   * @param orderList    Ordering specification ({@code WITHIN GROUP} clause),
   *                     or null
   * @param scope        Syntactic scope
   */
  // 专门校验聚合函数的参数（如 DISTINCT、FILTER、ORDER BY 等）。
  void validateAggregateParams(SqlCall aggCall, @Nullable SqlNode filter,
      @Nullable SqlNodeList distinctList, @Nullable SqlNodeList orderList,
      SqlValidatorScope scope);

  /**
   * If an identifier is a legitimate call to a function that has no
   * arguments and requires no parentheses (for example "CURRENT_USER"),
   * returns a call to that function, otherwise returns null.
   */
  // 如果标识符是不带括号的函数（如 CURRENT_USER），则将其转为函数调用。
  @Nullable SqlCall makeNullaryCall(SqlIdentifier id);

  /**
   * Derives the type of a node in a given scope. If the type has already been
   * inferred, returns the previous type.
   *
   * @param scope   Syntactic scope
   * @param operand Parse tree node
   * @return Type of the SqlNode. Should never return <code>NULL</code>
   */
  // 根据给定的作用域推导表达式的类型。
  RelDataType deriveType(
      SqlValidatorScope scope,
      SqlNode operand);

  /**
   * Adds "line x, column y" context to a validator exception.
   *
   * <p>Note that the input exception is checked (it derives from
   * {@link Exception}) and the output exception is unchecked (it derives from
   * {@link RuntimeException}). This is intentional -- it should remind code
   * authors to provide context for their validation errors.
   *
   * @param node The place where the exception occurred, not null
   * @param e    The validation error
   * @return Exception containing positional information, never null
   */
  // 创建一个带有行列位置信息的校验错误异常。
  CalciteContextException newValidationError(
      SqlNode node,
      Resources.ExInst<SqlValidatorException> e);

  /**
   * Returns whether a SELECT statement is an aggregation. Criteria are: (1)
   * contains GROUP BY, or (2) contains HAVING, or (3) SELECT or ORDER BY
   * clause contains aggregate functions. (Windowed aggregate functions, such
   * as <code>SUM(x) OVER w</code>, don't count.)
   *
   * @param select SELECT statement
   * @return whether SELECT statement is an aggregation
   */
  // 判断一个 SELECT 语句是否包含聚合操作。
  boolean isAggregate(SqlSelect select);

  /**
   * Returns whether a select list expression is an aggregate function.
   *
   * @param selectNode Expression in SELECT clause
   * @return whether expression is an aggregate function
   */
  @Deprecated // to be removed before 2.0
  boolean isAggregate(SqlNode selectNode);

  /**
   * Converts a window specification or window name into a fully-resolved
   * window specification. For example, in <code>SELECT sum(x) OVER (PARTITION
   * BY x ORDER BY y), sum(y) OVER w1, sum(z) OVER (w ORDER BY y) FROM t
   * WINDOW w AS (PARTITION BY x)</code> all aggregations have the same
   * resolved window specification <code>(PARTITION BY x ORDER BY y)</code>.
   *
   * @param windowOrRef    Either the name of a window (a {@link SqlIdentifier})
   *                       or a window specification (a {@link SqlWindow}).
   * @param scope          Scope in which to resolve window names
   * @return A window
   * @throws RuntimeException Validation exception if window does not exist
   */
  // 将窗口引用（别名）解析为完整的窗口规格。
  SqlWindow resolveWindow(
      SqlNode windowOrRef,
      SqlValidatorScope scope);

  /**
   * Converts a window specification or window name into a fully-resolved
   * window specification.
   *
   * @deprecated Use {@link #resolveWindow(SqlNode, SqlValidatorScope)}, which
   * does not have the deprecated {@code populateBounds} parameter.
   *
   * @param populateBounds Whether to populate bounds. Doing so may alter the
   *                       definition of the window. It is recommended that
   *                       populate bounds when translating to physical algebra,
   *                       but not when validating.
   */
  @Deprecated // to be removed before 2.0
  default SqlWindow resolveWindow(
      SqlNode windowOrRef,
      SqlValidatorScope scope,
      boolean populateBounds) {
    return resolveWindow(windowOrRef, scope);
  };

  /**
   * Finds the namespace corresponding to a given node.
   *
   * <p>For example, in the query <code>SELECT * FROM (SELECT * FROM t), t1 AS
   * alias</code>, the both items in the FROM clause have a corresponding
   * namespace.
   *
   * @param node Parse tree node
   * @return namespace of node
   */
  // 获取节点对应的命名空间。
  @Nullable SqlValidatorNamespace getNamespace(SqlNode node);

  /**
   * Derives an alias for an expression. If no alias can be derived, returns
   * null if <code>ordinal</code> is less than zero, otherwise generates an
   * alias <code>EXPR$<i>ordinal</i></code>.
   *
   * @param node    Expression
   * @param ordinal Ordinal of expression
   * @return derived alias, or null if no alias can be derived and ordinal is
   * less than zero
   */
  // 为表达式推导别名（如果没有显式别名，则生成 EXPR$n）。
  @Nullable String deriveAlias(
      SqlNode node,
      int ordinal);

  /**
   * Returns a list of expressions, with every occurrence of "&#42;" or
   * "TABLE.&#42;" expanded.
   *
   * @param selectList        Select clause to be expanded
   * @param query             Query
   * @param includeSystemVars Whether to include system variables
   * @return expanded select clause
   */
  // 将 SELECT * 或 SELECT table.* 展开为具体的字段列表
  SqlNodeList expandStar(SqlNodeList selectList, SqlSelect query,
      boolean includeSystemVars);

  /**
   * Returns the scope that expressions in the WHERE and GROUP BY clause of
   * this query should use. This scope consists of the tables in the FROM
   * clause, and the enclosing scope.
   *
   * @param select Query
   * @return naming scope of WHERE clause
   */
  // 获取 WHERE 子句的作用域。
  SqlValidatorScope getWhereScope(SqlSelect select);

  /**
   * Returns the type factory used by this validator.
   *
   * @return type factory
   */
  // 获取类型工厂，用于创建和管理 SQL 运行时的各种数据类型。
  @Pure
  RelDataTypeFactory getTypeFactory();

  /**
   * Saves the type of a {@link SqlNode}, now that it has been validated.
   *
   * <p>This method is only for internal use. The validator should drive the
   * type-derivation process, and store nodes' types when they have been derived.
   *
   * @param node A SQL parse tree node, never null
   * @param type Its type; must not be null
   */
  // 手动为某个节点绑定校验后的类型。
  @API(status = API.Status.INTERNAL, since = "1.24")
  void setValidatedNodeType(SqlNode node, RelDataType type);

  /**
   * Removes a node from the set of validated nodes.
   *
   * @param node node to be removed
   */
  // 从缓存中移除某个节点的类型信息。
  void removeValidatedNodeType(SqlNode node);

  /**
   * Returns an object representing the "unknown" type.
   *
   * @return unknown type
   */
  // 返回表示“未知类型”的对象。
  RelDataType getUnknownType();

  /**
   * Returns the appropriate scope for validating a particular clause of a
   * SELECT statement.
   *
   * <p>Consider
   *
   * <blockquote><pre><code>SELECT *
   * FROM foo
   * WHERE EXISTS (
   *    SELECT deptno AS x
   *    FROM emp
   *       JOIN dept ON emp.deptno = dept.deptno
   *    WHERE emp.deptno = 5
   *    GROUP BY deptno
   *    ORDER BY x)</code></pre></blockquote>
   *
   * <p>What objects can be seen in each part of the sub-query?
   *
   * <ul>
   * <li>In FROM ({@link #getFromScope} , you can only see 'foo'.
   *
   * <li>In WHERE ({@link #getWhereScope}), GROUP BY ({@link #getGroupScope}),
   * SELECT ({@code getSelectScope}), and the ON clause of the JOIN
   * ({@link #getJoinScope}) you can see 'emp', 'dept', and 'foo'.
   *
   * <li>In ORDER BY ({@link #getOrderScope}), you can see the column alias 'x';
   * and tables 'emp', 'dept', and 'foo'.
   *
   * </ul>
   *
   * @param select SELECT statement
   * @return naming scope for SELECT statement
   */
  // 获取 SELECT 子句的作用域。
  SqlValidatorScope getSelectScope(SqlSelect select);

  /**
   * Returns the scope for resolving the SELECT, GROUP BY and HAVING clauses.
   * Always a {@link SelectScope}; if this is an aggregation query, the
   * {@link AggregatingScope} is stripped away.
   *
   * @param select SELECT statement
   * @return naming scope for SELECT statement, sans any aggregating scope
   */
  // 获取原始 SELECT 作用域（不含聚合逻辑）。
  @Nullable SelectScope getRawSelectScope(SqlSelect select);

  /**
   * Returns a scope containing the objects visible from the FROM clause of a
   * query.
   *
   * @param select SELECT statement
   * @return naming scope for FROM clause
   */
  // 获取 FROM 子句的作用域。
  SqlValidatorScope getFromScope(SqlSelect select);

  /**
   * Returns a scope containing the objects visible from the ON and USING
   * sections of a JOIN clause.
   *
   * @param node The item in the FROM clause which contains the ON or USING
   *             expression
   * @return naming scope for JOIN clause
   * @see #getFromScope
   */
  // 获取 JOIN 内部（ON/USING）的作用域。
  SqlValidatorScope getJoinScope(SqlNode node);

  /**
   * Returns a scope containing the objects visible from the GROUP BY clause
   * of a query.
   *
   * @param select SELECT statement
   * @return naming scope for GROUP BY clause
   */
  // 获取 GROUP BY 子句的作用域。
  SqlValidatorScope getGroupScope(SqlSelect select);

  /**
   * Returns a scope containing the objects visible from the HAVING clause of
   * a query.
   *
   * @param select SELECT statement
   * @return naming scope for HAVING clause
   */
  // 获取 HAVING 子句的作用域。
  SqlValidatorScope getHavingScope(SqlSelect select);

  /**
   * Returns the scope that expressions in the SELECT and HAVING clause of
   * this query should use. This scope consists of the FROM clause and the
   * enclosing scope. If the query is aggregating, only columns in the GROUP
   * BY clause may be used.
   *
   * @param select SELECT statement
   * @return naming scope for ORDER BY clause
   */
  // 获取 ORDER BY 子句的作用域。
  SqlValidatorScope getOrderScope(SqlSelect select);

  /**
   * Returns a scope match recognize clause.
   *
   * @param node Match recognize
   * @return naming scope for Match recognize clause
   */
  // 获取 MATCH_RECOGNIZE 的作用域。
  SqlValidatorScope getMatchRecognizeScope(SqlMatchRecognize node);

  /**
   * Returns the lambda expression scope.
   *
   * @param node Lambda expression
   * @return naming scope for lambda expression
   */
  // 获取 Lambda 表达式内部的作用域。
  SqlValidatorScope getLambdaScope(SqlLambda node);

  /**
   * Returns a scope that cannot see anything.
   */
  // 获取一个什么也看不见的空作用域。
  SqlValidatorScope getEmptyScope();

  /**
   * Declares a SELECT expression as a cursor.
   *
   * @param select select expression associated with the cursor
   * @param scope  scope of the parent query associated with the cursor
   */
  // 将 SELECT 声明为游标。
  void declareCursor(SqlSelect select, SqlValidatorScope scope);

  /**
   * Pushes a new instance of a function call on to a function call stack.
   */
  // 维护函数调用栈，用于递归校验。
  void pushFunctionCall();

  /**
   * Removes the topmost entry from the function call stack.
   */
  // 维护函数调用栈，用于递归校验。
  void popFunctionCall();

  /**
   * Retrieves the name of the parent cursor referenced by a column list
   * parameter.
   *
   * @param columnListParamName name of the column list parameter
   * @return name of the parent cursor
   */
  // 获取引用的父游标名称。
  @Nullable String getParentCursor(String columnListParamName);

  /**
   * Derives the type of a constructor.
   *
   * @param scope                 Scope
   * @param call                  Call
   * @param unresolvedConstructor TODO
   * @param resolvedConstructor   TODO
   * @param argTypes              Types of arguments
   * @return Resolved type of constructor
   */
  // 推导构造函数的类型。
  RelDataType deriveConstructorType(
      SqlValidatorScope scope,
      SqlCall call,
      SqlFunction unresolvedConstructor,
      @Nullable SqlFunction resolvedConstructor,
      List<RelDataType> argTypes);

  /**
   * Handles a call to a function which cannot be resolved. Returns an
   * appropriately descriptive error, which caller must throw.
   *
   * @param call               Call
   * @param unresolvedFunction Overloaded function which is the target of the
   *                           call
   * @param argTypes           Types of arguments
   * @param argNames           Names of arguments, or null if call by position
   */
  // 当找不到匹配的函数时，处理并返回描述性错误。
  CalciteException handleUnresolvedFunction(SqlCall call,
      SqlOperator unresolvedFunction, List<RelDataType> argTypes,
      @Nullable List<String> argNames);

  /**
   * Expands an expression in the ORDER BY clause into an expression with the
   * same semantics as expressions in the SELECT clause.
   *
   * <p>This is made necessary by a couple of dialect 'features':
   *
   * <ul>
   * <li><b>ordinal expressions</b>: In "SELECT x, y FROM t ORDER BY 2", the
   * expression "2" is shorthand for the 2nd item in the select clause, namely
   * "y".
   * <li><b>alias references</b>: In "SELECT x AS a, y FROM t ORDER BY a", the
   * expression "a" is shorthand for the item in the select clause whose alias
   * is "a"
   * </ul>
   *
   * @param select    Select statement which contains ORDER BY
   * @param orderExpr Expression in the ORDER BY clause.
   * @return Expression translated into SELECT clause semantics
   */
  // 将 ORDER BY 2 中的序号或别名展开为具体的表达式。
  SqlNode expandOrderExpr(SqlSelect select, SqlNode orderExpr);

  /**
   * Expands an expression.
   *
   * @param expr  Expression
   * @param scope Scope
   * @return Expanded expression
   */
  // 展开表达式。
  SqlNode expand(SqlNode expr, SqlValidatorScope scope);

  /** Resolves a literal.
   *
   * <p>Usually returns the literal unchanged, but if the literal is of type
   * {@link org.apache.calcite.sql.type.SqlTypeName#UNKNOWN} looks up its type
   * and converts to the appropriate literal subclass. */
  // 将未知类型的常量解析为具体的子类（如将字符串解析为 Date 常量）。
  SqlLiteral resolveLiteral(SqlLiteral literal);

  /**
   * Returns whether a field is a system field. Such fields may have
   * particular properties such as sortedness and nullability.
   *
   * <p>In the default implementation, always returns {@code false}.
   *
   * @param field Field
   * @return whether field is a system field
   */
  // 判断字段是否为系统内置字段。
  boolean isSystemField(RelDataTypeField field);

  /**
   * Returns a description of how each field in the row type maps to a
   * catalog, schema, table and column in the schema.
   *
   * <p>The returned list is never null, and has one element for each field
   * in the row type. Each element is a list of four elements (catalog,
   * schema, table, column), or may be null if the column is an expression.
   *
   * @param sqlQuery Query
   * @return Description of how each field in the row type maps to a schema
   * object
   */
  // 获取结果集中每一列的原始出处（表名、列名）。
  List<@Nullable List<String>> getFieldOrigins(SqlNode sqlQuery);

  /**
   * Returns a record type that contains the name and type of each parameter.
   * Returns a record type with no fields if there are no parameters.
   *
   * @param sqlQuery Query
   * @return Record type
   */
  // 获取查询中所有动态参数构成的行类型。
  RelDataType getParameterRowType(SqlNode sqlQuery);

  /**
   * Returns the scope of an OVER or VALUES node.
   *
   * @param node Node
   * @return Scope
   */
  // 获取窗口或 VALUES 节点的作用域。
  SqlValidatorScope getOverScope(SqlNode node);

  /**
   * Validates that a query is capable of producing a return of given modality
   * (relational or streaming).
   *
   * @param select Query
   * @param modality Modality (streaming or relational)
   * @param fail Whether to throw a user error if does not support required
   *             modality
   * @return whether query supports the given modality
   */
  // 校验查询是否支持流式（Streaming）或关系型（Relational）模式。
  boolean validateModality(SqlSelect select, SqlModality modality,
      boolean fail);
  // 校验 CTE（WITH 语句）。
  void validateWith(SqlWith with, SqlValidatorScope scope);
  // 校验 CTE 中的单个定义项。
  void validateWithItem(SqlWithItem withItem);
  // 校验序列值（如 NEXT VALUE FOR）。
  void validateSequenceValue(SqlValidatorScope scope, SqlIdentifier id);
  // 获取 WITH 定义项的作用域。
  SqlValidatorScope getWithScope(SqlNode withItem);

  /** Get the type coercion instance. */
  // 获取隐式类型转换（Coercion）的实例。
  TypeCoercion getTypeCoercion();

  /** Returns the type mapping rule. */
  // 获取类型映射规则（决定类型转换是否宽松）。
  default SqlTypeMappingRule getTypeMappingRule() {
    return config().conformance().allowLenientCoercion()
        ? SqlTypeCoercionRule.lenientInstance()
        : SqlTypeCoercionRule.instance();
  }

  /** Returns the config of the validator. */
  // 获取校验器的配置信息。
  Config config();

  /**
   * Returns this SqlValidator, with the same state, applying
   * a transform to the config.
   *
   * <p>This is mainly used for tests, otherwise constructs a {@link Config} directly
   * through the constructor.
   */
  // 变换校验器的配置并返回一个新的实例。
  @API(status = API.Status.INTERNAL, since = "1.23")
  SqlValidator transform(UnaryOperator<SqlValidator.Config> transform);

  /** Returns the set of allowed time frames. */
  // 返回该校验器允许的时间框架集合（用于时间区间处理）
  TimeFrameSet getTimeFrameSet();

  /** Validates a time frame.
   *
   * <p>A time frame is either a built-in time frame based on a time unit such
   * as {@link org.apache.calcite.avatica.util.TimeUnitRange#HOUR},
   * or is a custom time frame represented by a name in
   * {@link SqlIntervalQualifier#timeFrameName}. A custom time frame is
   * validated against {@link #getTimeFrameSet()}.
   *
   * <p>Returns a time frame, or throws.
   */
  // 校验自定义或内置的时间框架。
  TimeFrame validateTimeFrame(SqlIntervalQualifier intervalQualifier);

  //~ Inner Class ------------------------------------------------------------

  /**
   * Interface to define the configuration for a SqlValidator.
   * Provides methods to set each configuration option.
   */
  @Value.Immutable(singleton = false)
  interface Config {
    /** Default configuration. */
    SqlValidator.Config DEFAULT = ImmutableSqlValidator.Config.builder()
        .withTypeCoercionFactory(TypeCoercions::createTypeCoercion)
        .build();

    /**
     * Returns whether to enable rewrite of "macro-like" calls such as COALESCE.
     */
    // 是否允许将宏调用（如 COALESCE）重写。
    @Value.Default default boolean callRewrite() {
      return true;
    }

    /**
     * Sets whether to enable rewrite of "macro-like" calls such as COALESCE.
     */
    Config withCallRewrite(boolean rewrite);

    /** Returns how NULL values should be collated if an ORDER BY item does not
     * contain NULLS FIRST or NULLS LAST. */
    // 默认的 NULL 排序规则（高位或低位）。
    @Value.Default default NullCollation defaultNullCollation() {
      return NullCollation.HIGH;
    }

    /** Sets how NULL values should be collated if an ORDER BY item does not
     * contain NULLS FIRST or NULLS LAST. */
    Config withDefaultNullCollation(NullCollation nullCollation);

    /** Returns whether column reference expansion is enabled. */
    // 是否允许展开列引用。
    @Value.Default default boolean columnReferenceExpansion() {
      return true;
    }

    /**
     * Sets whether to enable expansion of column references. (Currently this does
     * not apply to the ORDER BY clause; may be fixed in the future.)
     */
    Config withColumnReferenceExpansion(boolean expand);

    /**
     * Returns whether to expand identifiers other than column
     * references.
     *
     * <p>REVIEW jvs 30-June-2006: subclasses may override shouldExpandIdentifiers
     * in a way that ignores this; we should probably get rid of the protected
     * method and always use this variable (or better, move preferences like
     * this to a separate "parameter" class).
     */
    // 是否允许展开标识符。
    @Value.Default default boolean identifierExpansion() {
      return false;
    }

    /**
     * Sets whether to enable expansion of identifiers other than column
     * references.
     */
    Config withIdentifierExpansion(boolean expand);

    /**
     * Returns whether this validator should be lenient upon encountering an
     * unknown function, default false.
     *
     * <p>If true, if a statement contains a call to a function that is not
     * present in the operator table, or if the call does not have the required
     * number or types of operands, the validator nevertheless regards the
     * statement as valid. The type of the function call will be
     * {@link #getUnknownType() UNKNOWN}.
     *
     * <p>If false (the default behavior), an unknown function call causes a
     * validation error to be thrown.
     */
    // 是否开启宽松模式（找不到函数时不报错，返回 UNKNOWN 类型）。
    @Value.Default default boolean lenientOperatorLookup() {
      return false;
    }

    /**
     * Sets whether this validator should be lenient upon encountering an unknown
     * function.
     *
     * @param lenient Whether to be lenient when encountering an unknown function
     */
    Config withLenientOperatorLookup(boolean lenient);

    /** Returns whether the validator allows measures to be used without
     * AGGREGATE function in a non-aggregate query. Default is true.
     */
    @Value.Default default boolean nakedMeasuresInNonAggregateQuery() {
      return true;
    }

    /** Sets whether the validator allows measures to be used without AGGREGATE
     * function in a non-aggregate query.
     */
    Config withNakedMeasuresInNonAggregateQuery(boolean value);

    /** Returns whether the validator allows measures to be used without
     * AGGREGATE function in an aggregate query. Default is true.
     */
    @Value.Default default boolean nakedMeasuresInAggregateQuery() {
      return true;
    }

    /** Sets whether the validator allows measures to be used without AGGREGATE
     * function in an aggregate query.
     */
    Config withNakedMeasuresInAggregateQuery(boolean value);

    /** Sets whether the validator allows measures to be used without the
     * AGGREGATE function inside or outside aggregate queries.
     * Deprecated: use the inside / outside variants instead.
     */
    @Deprecated // to be removed before 1.38
    default Config withNakedMeasures(boolean nakedMeasures) {
      return withNakedMeasuresInAggregateQuery(nakedMeasures)
              .withNakedMeasuresInNonAggregateQuery(nakedMeasures);
    }

    /** Returns whether the validator supports implicit type coercion. */
    @Value.Default default boolean typeCoercionEnabled() {
      return true;
    }

    /**
     * Sets whether to enable implicit type coercion for validation, default true.
     *
     * @see org.apache.calcite.sql.validate.implicit.TypeCoercionImpl TypeCoercionImpl
     */
    Config withTypeCoercionEnabled(boolean enabled);

    /** Returns the type coercion factory. */
    TypeCoercionFactory typeCoercionFactory();

    /**
     * Sets a factory to create type coercion instance that overrides the
     * default coercion rules defined in
     * {@link org.apache.calcite.sql.validate.implicit.TypeCoercionImpl}.
     *
     * @param factory Factory to create {@link TypeCoercion} instance
     */
    Config withTypeCoercionFactory(TypeCoercionFactory factory);

    /** Returns the type coercion rules for explicit type coercion. */
    @Nullable SqlTypeCoercionRule typeCoercionRules();

    /**
     * Sets the {@link SqlTypeCoercionRule} instance which defines the type conversion matrix
     * for the explicit type coercion.
     *
     * <p>The {@code rules} setting should be thread safe. In the default implementation,
     * it is set to a ThreadLocal variable.
     *
     * @param rules The {@link SqlTypeCoercionRule} instance,
     *              see its documentation for how to customize the rules
     */
    Config withTypeCoercionRules(@Nullable SqlTypeCoercionRule rules);

    /** Returns the dialect of SQL (SQL:2003, etc.) this validator recognizes.
     * Default is {@link SqlConformanceEnum#DEFAULT}. */
    @Value.Default default SqlConformance conformance() {
      return SqlConformanceEnum.DEFAULT;
    }

    /** Returns the SQL conformance.
     *
     * @deprecated Use {@link #conformance()} */
    @Deprecated // to be removed before 2.0
    default SqlConformance sqlConformance() {
      return conformance();
    }

    /** Sets the SQL conformance of the validator. */
    Config withConformance(SqlConformance conformance);

    /** Sets the SQL conformance of the validator.
     *
     * @deprecated Use {@link #conformance()} */
    @Deprecated // to be removed before 2.0
    default Config withSqlConformance(SqlConformance conformance) {
      return withConformance(conformance);
    }
  }
}
