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
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

import static java.util.Objects.requireNonNull;

/**
 * Abstract implementation of {@link SqlValidatorNamespace}.
 */
// 各种具体的命名空间（如表、子查询、集合操作等）提供了默认的行为实现和状态管理逻辑
// 核心作用是管理命名空间的生命周期和元数据状态
// 状态机控制：通过 status 字段记录校验进度（未校验、校验中、已校验），有效防止 SQL 语法树中的循环引用（例如递归视图）。
// 类型缓存：它缓存了推导出的 rowType（行类型）和 type（原始类型），确保校验过程的高效，避免重复计算。
// 模板方法模式：它实现了通用的 validate 方法，而将具体的校验逻辑留给子类通过 validateImpl 去实现。
// 结构化适配：它提供工具方法确保返回的类型符合 SQL 关系的“结构化（Struct）”要求。
abstract class AbstractNamespace implements SqlValidatorNamespace {
  //~ Instance fields --------------------------------------------------------
  // 引用所属的 SqlValidatorImpl 实例。
  // Namespace 需要通过它来访问类型工厂（TypeFactory）、目录读取器（CatalogReader）以及触发其他节点的校验。
  protected final SqlValidatorImpl validator;

  /**
   * Whether this scope is currently being validated. Used to check for
   * cycles.
   */
  // 记录当前的校验状态（UNVALIDATED, IN_PROGRESS, VALID）
  // 在 validate 方法中切换，如果遇到 IN_PROGRESS 则抛出死循环异常。
  private SqlValidatorImpl.Status status =
      SqlValidatorImpl.Status.UNVALIDATED;

  /**
   * Type of the output row, which comprises the name and type of each output
   * column. Set on validate.
   */
  // 缓存该 Namespace 产出的行类型。
  // 必须是结构化类型（即包含多个字段名和类型的记录）。
  protected @Nullable RelDataType rowType;

  /** As {@link #rowType}, but not necessarily a struct. */
  // 缓存该 Namespace 的原始类型。
  // 区别：对于标量子查询或单列集合，type 可能是 INTEGER，而 rowType 则是包装后的 RECORD(EXPR$0: INTEGER)。
  protected @Nullable RelDataType type;

  /** Ordinals of fields that must be filtered. Initially the empty set, but
   * should typically be re-assigned on validate. */
  // 记录必须被过滤的字段索引。通常用于合规性检查（如强制要求在 WHERE 中包含某些分区键）。
  protected ImmutableBitSet mustFilterFields = ImmutableBitSet.of();
  // 记录包含此命名空间的 SQL 节点（语法树中的父节点或装饰节点）。
  protected final @Nullable SqlNode enclosingNode;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates an AbstractNamespace.
   *
   * @param validator     Validator
   * @param enclosingNode Enclosing node
   */
  AbstractNamespace(
      SqlValidatorImpl validator,
      @Nullable SqlNode enclosingNode) {
    this.validator = validator;
    this.enclosingNode = enclosingNode;
  }

  //~ Methods ----------------------------------------------------------------

  @Override public SqlValidator getValidator() {
    return validator;
  }
  // 最关键的方法之一。
  // 它实现了一个带有状态保护的模板模式，确保每个命名空间（Namespace）只被校验一次，并能检测出 SQL 语义中的循环引用（例如递归定义的视图）。
  @Override public final void validate(RelDataType targetRowType) {
    switch (status) {
    case UNVALIDATED:
      // 状态机切换
      try {
        status = SqlValidatorImpl.Status.IN_PROGRESS;
        checkArgument(rowType == null,
            "Namespace.rowType must be null before validate has been called");
        // 执行核心校验（模板方法）
        RelDataType type = validateImpl(targetRowType);
        requireNonNull(type, "validateImpl() returned null");
        // 将推导出的 RelDataType 存储在 Namespace 对象中。
        setType(type);
      } finally {
        // 正常结束：标记为 VALID，后续如果再次调用 validate，将直接跳过。
        status = SqlValidatorImpl.Status.VALID;
      }
      break;
    case IN_PROGRESS:
      throw new AssertionError("Cycle detected during type-checking");
    case VALID:
      break;
    default:
      throw Util.unexpected(status);
    }
  }

  /**
   * Validates this scope and returns the type of the records it returns.
   * External users should call {@link #validate}, which uses the
   * {@link #status} field to protect against cycles.
   *
   * @param targetRowType Desired row type, must not be null, may be the data
   *                      type 'unknown'.
   * @return record data type, never null
   */
  // 子类必须实现的钩子方法。
  protected abstract RelDataType validateImpl(RelDataType targetRowType);
  // 获取行类型。如果尚未校验，会主动触发 validator 对自身进行校验。
  @Override public RelDataType getRowType() {
    if (rowType == null) {
      validator.validateNamespace(this, validator.unknownType);
      requireNonNull(rowType, "validate must set rowType");
    }
    return rowType;
  }

  @Override public RelDataType getRowTypeSansSystemColumns() {
    return getRowType();
  }

  @Override public RelDataType getType() {
    Util.discard(getRowType());
    return requireNonNull(type, "type");
  }

  @Override public void setType(RelDataType type) {
    this.type = type;
    this.rowType = convertToStruct(type);
  }

  @Override public @Nullable SqlNode getEnclosingNode() {
    return enclosingNode;
  }

  @Override public @Nullable SqlValidatorTable getTable() {
    return null;
  }
  // 在当前行类型中查找子字段，并返回其对应的字段 Namespace。
  @Override public @Nullable SqlValidatorNamespace lookupChild(String name) {
    return validator.lookupFieldNamespace(
        getRowType(),
        name);
  }
  // 利用校验器的名称匹配器（NameMatcher）在行类型中查找指定的字段定义。
  @Override public @Nullable RelDataTypeField field(String name) {
    final RelDataType rowType = getRowType();
    return validator.catalogReader.nameMatcher().field(rowType, name);
  }
  // 默认返回空列表，表示没有单调递增/递减的表达式。
  @Override public List<Pair<SqlNode, SqlMonotonicity>> getMonotonicExprs() {
    return ImmutableList.of();
  }
  // 返回必须过滤的字段集合。
  @Override public ImmutableBitSet getMustFilterFields() {
    return requireNonNull(mustFilterFields,
        "mustFilterFields (maybe validation is not complete?)");
  }
  // 默认返回 NOT_MONOTONIC。
  @Override public SqlMonotonicity getMonotonicity(String columnName) {
    return SqlMonotonicity.NOT_MONOTONIC;
  }

  @SuppressWarnings("deprecation")
  @Override public void makeNullable() {
  }
  // 将名称进行转换，默认原样返回。
  public String translate(String name) {
    return name;
  }
  // 返回自身。对于别名 Namespace，它会返回其指向的实际 Namespace。
  @Override public SqlValidatorNamespace resolve() {
    return this;
  }
  // 默认支持所有模式（流或关系）
  @Override public boolean supportsModality(SqlModality modality) {
    return true;
  }

  @Override public <T> T unwrap(Class<T> clazz) {
    return clazz.cast(this);
  }

  @Override public boolean isWrapperFor(Class<?> clazz) {
    return clazz.isInstance(this);
  }

  protected RelDataType convertToStruct(RelDataType type) {
    // "MULTISET [<expr>, ...]" needs to be wrapped in a record if
    // <expr> has a scalar type.
    // For example, "MULTISET [8, 9]" has type
    // "RECORD(INTEGER EXPR$0 NOT NULL) NOT NULL MULTISET NOT NULL".
    final RelDataType componentType = type.getComponentType();
    if (componentType == null || componentType.isStruct()) {
      return type;
    }
    final RelDataTypeFactory typeFactory = validator.getTypeFactory();
    final RelDataType structType = toStruct(componentType, getNode());
    final RelDataType collectionType;
    switch (type.getSqlTypeName()) {
    case ARRAY:
      collectionType = typeFactory.createArrayType(structType, -1);
      break;
    case MULTISET:
      collectionType = typeFactory.createMultisetType(structType, -1);
      break;
    default:
      throw new AssertionError(type);
    }
    return typeFactory.createTypeWithNullability(collectionType,
        type.isNullable());
  }

  /** Converts a type to a struct if it is not already. */
  protected RelDataType toStruct(RelDataType type, @Nullable SqlNode unnest) {
    if (type.isStruct()) {
      return type;
    }
    return validator.getTypeFactory().builder()
        .add(SqlValidatorUtil.alias(requireNonNull(unnest, "unnest"), 0), type)
        .build();
  }
}
