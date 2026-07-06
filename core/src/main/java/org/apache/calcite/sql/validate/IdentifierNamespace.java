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
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.util.Pair;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

import static org.apache.calcite.util.Static.RESOURCE;

import static java.util.Objects.requireNonNull;

/**
 * Namespace whose contents are defined by the type of an
 * {@link org.apache.calcite.sql.SqlIdentifier identifier}.
 */
// IdentifierNamespace 专门负责处理 FROM 子句中出现的"简单表引用"这种情况，比如：
// SELECT * FROM emp
// SELECT * FROM  catalog.schema.emp
// SELECT * FROM emp EXTEND (bonus DECIMAL(10,2))
// 这里的 emp、catalog.schema.emp 都是一个标识符（SqlIdentifier），IdentifierNamespace 的职责就是：把这个标识符解析（resolve）成一个真正的数据源（通常是一张表），并把这个数据源的行类型（RelDataType）暴露出来，供外层查询引用。
// 和 SelectNamespace（对应子查询）、JoinNamespace（对应 JOIN 结果）不同，IdentifierNamespace 对应的是最基础的、通过名字引用现有对象这种情况——它本身并不"计算"出一个新的结果集结构，而是需要通过名字解析（比如在 catalog/schema 中查找同名的表）去"找到"一个已经存在的命名空间，然后把这个被找到的命名空间的信息转发出来。这种"代理"或"转发"的设计模式贯穿了整个类。



public class IdentifierNamespace extends AbstractNamespace {
  //~ Instance fields --------------------------------------------------------
  // 保存该命名空间所对应的具体标识符节点，比如 emp 或 catalog.schema.emp
  // 所有的解析工作都是围绕"把这个标识符解析成什么"来展开的。
  private final SqlIdentifier id;
  // 保存父作用域，用于在解析标识符（比如把 emp 解析成具体的表）时，作为"从哪里开始查找"的上下文起点。
  // 正如后面 resolveImpl 方法中会看到的，实际的名字解析逻辑是委托给 parentScope.resolveTable(...) 完成的。
  private final SqlValidatorScope parentScope;
  // 保存 EXTEND 语法中定义的扩展列列表，比如 emp EXTEND (bonus DECIMAL(10,2)) 中的 (bonus DECIMAL(10,2)) 部分。如果该标识符没有使用 EXTEND 语法，则为 null。
  public final @Nullable SqlNodeList extendList;

  /**
   * The underlying namespace. Often a {@link TableNamespace}.
   * Set on validate.
   */
  // 标识符真正解析出来的底层命名空间，通常是一个 TableNamespace（代表一张真实的数据库表）
  private @MonotonicNonNull SqlValidatorNamespace resolvedNamespace;

  /**
   * List of monotonic expressions. Set on validate.
   */
  // 用于保存该命名空间中具有单调性的表达式列表（每一项是一个 <表达式, 单调性> 的键值对）。
  // 这个信息会在 validateImpl 方法执行过程中被计算并填充，
  // 之后供 getMonotonicExprs() 方法对外提供，外层查询可能会通过 getMonotonicExprs() 来"继承"内层数据源的排序/单调特性。
  private @Nullable List<Pair<SqlNode, SqlMonotonicity>> monotonicExprs;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates an IdentifierNamespace.
   *
   * @param validator     Validator
   * @param id            Identifier node (or "identifier EXTEND column-list")
   * @param extendList    Extension columns, or null
   * @param enclosingNode Enclosing node
   * @param parentScope   Parent scope which this namespace turns to in order to
   */
  IdentifierNamespace(SqlValidatorImpl validator, SqlIdentifier id,
      @Nullable SqlNodeList extendList, @Nullable SqlNode enclosingNode,
      SqlValidatorScope parentScope) {
    super(validator, enclosingNode);
    this.id = id;
    this.extendList = extendList;
    this.parentScope = requireNonNull(parentScope, "parentScope");
  }

  IdentifierNamespace(SqlValidatorImpl validator, SqlNode node,
      @Nullable SqlNode enclosingNode, SqlValidatorScope parentScope) {
    this(validator, split(node).left, split(node).right, enclosingNode,
        parentScope);
  }

  //~ Methods ----------------------------------------------------------------
  // 接收一个可能是多种不同形态的 SqlNode，统一拆分成"真正的标识符部分"和"可能存在的扩展列列表部分"这样一对结果（用 Pair 封装）。
  // node（SqlNode）：待拆分的原始节点，可能是纯粹的标识符、TABLE_REF 调用，或者 EXTEND 调用。
  protected static Pair<SqlIdentifier, @Nullable SqlNodeList> split(SqlNode node) {
    switch (node.getKind()) {
    case EXTEND:
      // 形如 emp EXTEND (col1 type1, ...) 或 TABLE_REF(...) EXTEND (...)
      final SqlCall call = (SqlCall) node;
      final SqlNode operand0 = call.operand(0);
      final SqlIdentifier identifier = operand0.getKind() == SqlKind.TABLE_REF
          ? ((SqlCall) operand0).operand(0)
          : (SqlIdentifier) operand0;
      return Pair.of(identifier, call.operand(1));
    case TABLE_REF:
      final SqlCall tableRef = (SqlCall) node;
      //noinspection ConstantConditions
      return Pair.of(tableRef.operand(0), null);
    default:
      //noinspection ConstantConditions
      return Pair.of((SqlIdentifier) node, null);
    }
  }
  // 负责真正执行标识符的名字解析工作（比如把 emp 解析成 catalog 中真实存在的表）
  // id（SqlIdentifier）：待解析的标识符。
  private SqlValidatorNamespace resolveImpl(SqlIdentifier id) {
    final SqlNameMatcher nameMatcher = validator.catalogReader.nameMatcher();
    final SqlValidatorScope.ResolvedImpl resolved =
        new SqlValidatorScope.ResolvedImpl();
    final List<String> names = SqlIdentifier.toStar(id.names);
    try {
      parentScope.resolveTable(names, nameMatcher,
          SqlValidatorScope.Path.EMPTY, resolved);
    } catch (CyclicDefinitionException e) {
      if (e.depth == 1) {
        throw validator.newValidationError(id,
            RESOURCE.cyclicDefinition(id.toString(),
                SqlIdentifier.getString(e.path)));
      } else {
        throw new CyclicDefinitionException(e.depth - 1, e.path);
      }
    }
    SqlValidatorScope.Resolve previousResolve = null;
    if (resolved.count() == 1) {
      final SqlValidatorScope.Resolve resolve =
          previousResolve = resolved.only();
      if (resolve.remainingNames.isEmpty()) {
        return resolve.namespace;
      }
      // If we're not case sensitive, give an error.
      // If we're case sensitive, we'll shortly try again and give an error
      // then.
      if (!nameMatcher.isCaseSensitive()) {
        throw validator.newValidationError(id,
            RESOURCE.objectNotFoundWithin(resolve.remainingNames.get(0),
                SqlIdentifier.getString(resolve.path.stepNames())));
      }
    }

    // Failed to match.  If we're matching case-sensitively, try a more
    // lenient match. If we find something we can offer a helpful hint.
    if (nameMatcher.isCaseSensitive()) {
      final SqlNameMatcher liberalMatcher = SqlNameMatchers.liberal();
      resolved.clear();
      parentScope.resolveTable(names, liberalMatcher,
          SqlValidatorScope.Path.EMPTY, resolved);
      if (resolved.count() == 1) {
        final SqlValidatorScope.Resolve resolve = resolved.only();
        if (resolve.remainingNames.isEmpty()
            || previousResolve == null) {
          // We didn't match it case-sensitive, so they must have had the
          // right identifier, wrong case.
          //
          // If previousResolve is null, we matched nothing case-sensitive and
          // everything case-insensitive, so the mismatch must have been at
          // position 0.
          final int i =
              previousResolve == null ? 0
                  : previousResolve.path.stepCount();
          final int offset = resolve.path.stepCount()
              + resolve.remainingNames.size() - names.size();
          final List<String> prefix =
              resolve.path.stepNames().subList(0, offset + i);
          final String next = resolve.path.stepNames().get(i + offset);
          if (prefix.isEmpty()) {
            throw validator.newValidationError(id,
                RESOURCE.objectNotFoundDidYouMean(names.get(i), next));
          } else {
            throw validator.newValidationError(id,
                RESOURCE.objectNotFoundWithinDidYouMean(names.get(i),
                    SqlIdentifier.getString(prefix), next));
          }
        } else {
          throw validator.newValidationError(id,
              RESOURCE.objectNotFoundWithin(resolve.remainingNames.get(0),
                  SqlIdentifier.getString(resolve.path.stepNames())));
        }
      }
    }
    throw validator.newValidationError(id,
        RESOURCE.objectNotFound(id.getComponent(0).toString()));
  }

  @Override public RelDataType validateImpl(RelDataType targetRowType) {
    resolvedNamespace = resolveImpl(id);
    validator.validateNamespace(resolvedNamespace, targetRowType);

    if (validator.config().identifierExpansion()) {
      SqlValidatorTable table = resolvedNamespace.getTable();
      if (table != null) {
        // TODO:  expand qualifiers for column references also
        List<String> qualifiedNames = table.getQualifiedName();
        // Assign positions to the components of the fully-qualified
        // identifier, as best we can. We assume that qualification
        // adds names to the front, e.g. FOO.BAR becomes BAZ.FOO.BAR.
        // Test offset in case catalog supports fewer qualifiers than catalog
        // reader.
        ImmutableList.Builder<SqlParserPos> positions =
            ImmutableList.builder();
        int offset = qualifiedNames.size() - id.names.size();
        for (int i = 0; i < qualifiedNames.size(); i++) {
          positions.add(offset >= 0 && i >= offset
              ? id.getComponentParserPosition(i - offset)
              : id.getParserPosition());
        }
        id.setNames(qualifiedNames, positions.build());
      }
    }

    this.mustFilterFields = resolvedNamespace.getMustFilterFields();
    RelDataType rowType = resolvedNamespace.getRowType();

    if (extendList != null) {
      if (!(resolvedNamespace instanceof TableNamespace)) {
        throw new RuntimeException("cannot convert");
      }
      resolvedNamespace =
          ((TableNamespace) resolvedNamespace).extend(extendList);
      rowType = resolvedNamespace.getRowType();
    }

    // Build a list of monotonic expressions.
    final ImmutableList.Builder<Pair<SqlNode, SqlMonotonicity>> builder =
        ImmutableList.builder();
    List<RelDataTypeField> fields = rowType.getFieldList();
    for (RelDataTypeField field : fields) {
      final String fieldName = field.getName();
      final SqlMonotonicity monotonicity =
          resolvedNamespace.getMonotonicity(fieldName);
      if (monotonicity != null && monotonicity != SqlMonotonicity.NOT_MONOTONIC) {
        builder.add(
            Pair.of(new SqlIdentifier(fieldName, SqlParserPos.ZERO),
                monotonicity));
      }
    }
    monotonicExprs = builder.build();

    // Validation successful.
    return rowType;
  }

  public SqlIdentifier getId() {
    return id;
  }

  @Override public @Nullable SqlNode getNode() {
    return id;
  }

  @Override public SqlValidatorNamespace resolve() {
    assert resolvedNamespace != null : "must call validate first";
    return resolvedNamespace.resolve();
  }

  @Override public @Nullable SqlValidatorTable getTable() {
    return resolvedNamespace == null ? null : resolve().getTable();
  }

  @Override public List<Pair<SqlNode, SqlMonotonicity>> getMonotonicExprs() {
    List<Pair<SqlNode, SqlMonotonicity>> monotonicExprs = this.monotonicExprs;
    return monotonicExprs == null ? ImmutableList.of() : monotonicExprs;
  }

  @Override public SqlMonotonicity getMonotonicity(String columnName) {
    final SqlValidatorTable table = getTable();
    if (table == null) {
      return SqlMonotonicity.NOT_MONOTONIC;
    }
    return table.getMonotonicity(columnName);
  }

  @Override public boolean supportsModality(SqlModality modality) {
    final SqlValidatorTable table = getTable();
    if (table == null) {
      return modality == SqlModality.RELATION;
    }
    return table.supportsModality(modality);
  }
}
