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
package org.apache.calcite.sql;

import org.apache.calcite.adapter.enumerable.EnumUtils;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeFactoryImpl;
import org.apache.calcite.runtime.CalciteException;
import org.apache.calcite.runtime.Resources;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlMonotonicity;
import org.apache.calcite.sql.validate.SqlValidatorException;
import org.apache.calcite.util.NlsString;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.AbstractList;
import java.util.List;

/**
 * <code>SqlOperatorBinding</code> represents the binding of an
 * {@link SqlOperator} to actual operands, along with any additional information
 * required to validate those operands if needed.
 */
// 它的核心作用是：解耦操作符（SqlOperator）与其具体调用场景（SqlCall 或 RexCall）。
// 当我们讨论一个操作符（如 SUM 或 +）时，我们不仅需要知道它的定义，还需要知道它被绑定到了哪些具体的参数上。
// 在 校验阶段（Validation）：它被绑定到 SqlNode（SQL 解析树节点）。
// 在 执行计划阶段（Optimization）：它被绑定到 RexNode（行表达式节点）。
// SqlOperatorBinding 提供了一套统一的接口，使得 SqlReturnTypeInference（类型推导器）和 SqlOperandTypeChecker（参数校验器）在不需要关心底层到底是 SqlNode 还是 RexNode 的情况下，
// 就能获取到参数的类型、个数、甚至是字面量的值。
public abstract class SqlOperatorBinding {
  //~ Instance fields --------------------------------------------------------
  // 用于在推导过程中创建新的类型（比如将两个类型合并后的最小公共类型）。
  protected final RelDataTypeFactory typeFactory;
  // 当前正在处理的 SqlOperator 对象（即操作符的定义）。
  private final SqlOperator sqlOperator;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a SqlOperatorBinding.
   *
   * @param typeFactory Type factory
   * @param sqlOperator Operator which is subject of this call
   */
  protected SqlOperatorBinding(
      RelDataTypeFactory typeFactory,
      SqlOperator sqlOperator) {
    this.typeFactory = typeFactory;
    this.sqlOperator = sqlOperator;
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * If the operator call occurs in an aggregate query, returns the number of
   * columns in the GROUP BY clause. For example, for "SELECT count(*) FROM emp
   * GROUP BY deptno, gender", returns 2.
   *
   * <p>Returns 0 if the query is implicitly "GROUP BY ()" because of an
   * aggregate expression. For example, "SELECT sum(sal) FROM emp".
   *
   * <p>Returns -1 if the query is not an aggregate query.
   */
  // 如果是在聚合查询中，返回 GROUP BY 子句中的列数。非聚合查询返回 -1。
  public int getGroupCount() {
    return -1;
  }

  /**
   * Returns whether the operator is an aggregate function with a filter.
   */
  // 返回该聚合函数是否带有 FILTER 子句。
  public boolean hasFilter() {
    return false;
  }

  /** Returns the bound operator. */
  public SqlOperator getOperator() {
    return sqlOperator;
  }

  /** Returns the factory for type creation. */
  public RelDataTypeFactory getTypeFactory() {
    return typeFactory;
  }

  /**
   * Gets the string value of a string literal operand.
   *
   * @param ordinal zero-based ordinal of operand of interest
   * @return string value
   */
  // 获取字符串字面量（已过时，建议使用 getOperandLiteralValue）。
  @Deprecated // to be removed before 2.0
  public @Nullable String getStringLiteralOperand(int ordinal) {
    throw new UnsupportedOperationException();
  }

  /**
   * Gets the integer value of a numeric literal operand.
   *
   * @param ordinal zero-based ordinal of operand of interest
   * @return integer value
   */
  // 获取整型字面量（已过时）。
  @Deprecated // to be removed before 2.0
  public int getIntLiteralOperand(int ordinal) {
    throw new UnsupportedOperationException();
  }

  /**
   * Gets the value of a literal operand.
   *
   * <p>Cases:
   * <ul>
   * <li>If the operand is not a literal, the value is null.
   *
   * <li>If the operand is a string literal,
   * the value will be of type {@link org.apache.calcite.util.NlsString}.
   *
   * <li>If the operand is a numeric literal,
   * the value will be of type {@link java.math.BigDecimal}.
   *
   * <li>If the operand is an interval qualifier,
   * the value will be of type {@link SqlIntervalQualifier}</li>
   *
   * <li>Otherwise the type is undefined, and the value may be null.
   * </ul>
   *
   * @param ordinal zero-based ordinal of operand of interest
   * @param clazz Desired valued type
   *
   * @return value of operand
   */
  // 获取第 ordinal 个操作数的字面量值，并尝试转换成指定的 Java 类型 clazz。如果不是字面量，返回 null。
  public <T extends Object> @Nullable T getOperandLiteralValue(int ordinal, Class<T> clazz) {
    throw new UnsupportedOperationException();
  }

  /**
   * Gets the value of a literal operand as a Calcite type.
   *
   * @param ordinal zero-based ordinal of operand of interest
   * @param type Desired valued type
   *
   * @return value of operand
   */
  // 获取字面量值并转换成 Calcite 定义的类型。
  public @Nullable Object getOperandLiteralValue(int ordinal, RelDataType type) {
    if (!(type instanceof RelDataTypeFactoryImpl.JavaType)) {
      return null;
    }
    final Class<?> clazz = ((RelDataTypeFactoryImpl.JavaType) type).getJavaClass();
    final Object o = getOperandLiteralValue(ordinal, Object.class);
    if (o == null) {
      return null;
    }
    if (clazz.isInstance(o)) {
      return clazz.cast(o);
    }
    final Object o2 = o instanceof NlsString ? ((NlsString) o).getValue() : o;
    return EnumUtils.evaluate(o2, clazz);
  }


  @Deprecated // to be removed before 2.0
  public @Nullable Comparable getOperandLiteralValue(int ordinal) {
    return getOperandLiteralValue(ordinal, Comparable.class);
  }

  /**
   * Determines whether a bound operand is NULL.
   *
   * <p>This is only relevant for SQL validation.
   *
   * @param ordinal   zero-based ordinal of operand of interest
   * @param allowCast whether to regard CAST(constant) as a constant
   * @return whether operand is null; false for everything except SQL
   * validation
   */
  // 判断指定的参数是否为 NULL 常量。
  public boolean isOperandNull(int ordinal, boolean allowCast) {
    throw new UnsupportedOperationException();
  }

  /**
   * Determines whether an operand is a literal.
   *
   * @param ordinal   zero-based ordinal of operand of interest
   * @param allowCast whether to regard CAST(literal) as a literal
   * @return whether operand is literal
   */
  // 判断第 ordinal 个参数是否为字面量常量。allowCast 参数决定是否视 CAST(constant) 为常量。
  public boolean isOperandLiteral(int ordinal, boolean allowCast) {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns whether an operand is a time frame.
   *
   * @param ordinal   zero-based ordinal of operand of interest
   * @return whether operand is a time frame
   */
  // 判断操作数是否代表一个时间框架（如 YEAR, MINUTE）。
  public boolean isOperandTimeFrame(int ordinal) {
    return getOperandCount() > 0
        && SqlTypeName.TIME_FRAME_TYPES.contains(
            getOperandType(ordinal).getSqlTypeName());
  }

  /** Returns the number of bound operands.
   * Includes pre-operands and regular operands. */
  // 返回当前调用中绑定的操作数总数。
  public abstract int getOperandCount();

  /** Returns the number of pre-operands.
   * Zero except for a few aggregate functions. */
  // 返回“预操作数”的数量。通常只有特定的聚合函数会用到，默认返回 0。
  public int getPreOperandCount() {
    return 0;
  }

  /**
   * Gets the type of a bound operand.
   *
   * @param ordinal zero-based ordinal of operand of interest
   * @return bound operand type
   */
  // 获取第 ordinal 个操作数的逻辑类型（RelDataType）。这是类型推导最常用的方法。
  public abstract RelDataType getOperandType(int ordinal);

  /**
   * Gets the monotonicity of a bound operand.
   *
   * @param ordinal zero-based ordinal of operand of interest
   * @return monotonicity of operand
   */
  // 获取参数的单调性（递增、递减或非单调）。这在处理流式 SQL 或排序优化时非常有用。
  public SqlMonotonicity getOperandMonotonicity(int ordinal) {
    return SqlMonotonicity.NOT_MONOTONIC;
  }


  /**
   * Returns the collation type.
   */
  // 获取排序规则类型。
  public RelDataType getCollationType() {
    throw new UnsupportedOperationException();
  }

  /**
   * Collects the types of the bound operands into a list.
   *
   * @return collected list
   */
  // 将所有操作数的类型收集到一个 List 中。
  public List<RelDataType> collectOperandTypes() {
    return new AbstractList<RelDataType>() {
      @Override public RelDataType get(int index) {
        return getOperandType(index);
      }

      @Override public int size() {
        return getOperandCount();
      }
    };
  }

  /**
   * Returns the rowtype of the <code>ordinal</code>th operand, which is a
   * cursor.
   *
   * <p>This is only implemented for {@link SqlCallBinding}.
   *
   * @param ordinal Ordinal of the operand
   * @return Rowtype of the query underlying the cursor
   */
  // 获取游标类型的参数行类型。
  public @Nullable RelDataType getCursorOperand(int ordinal) {
    throw new UnsupportedOperationException();
  }

  /**
   * Retrieves information about a column list parameter.
   *
   * @param ordinal    ordinal position of the column list parameter
   * @param paramName  name of the column list parameter
   * @param columnList returns a list of the column names that are referenced
   *                   in the column list parameter
   * @return the name of the parent cursor referenced by the column list
   * parameter if it is a column list parameter; otherwise, null is returned
   */
  // 获取列列表参数的信息，常用于表函数（Table Functions）。
  public @Nullable String getColumnListParamInfo(
      int ordinal,
      String paramName,
      List<String> columnList) {
    throw new UnsupportedOperationException();
  }

  /**
   * Wraps a validation error with context appropriate to this operator call.
   *
   * @param e Validation error, not null
   * @return Error wrapped, if possible, with positional information
   */
  public abstract CalciteException newError(
      Resources.ExInst<SqlValidatorException> e);
}
