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

import org.apache.calcite.linq4j.function.Functions;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.SqlOperandMetadata;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlOperandTypeInference;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorScope;
import org.apache.calcite.sql.validate.implicit.TypeCoercion;
import org.apache.calcite.util.Util;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import java.util.List;
import java.util.Objects;

import static org.apache.calcite.linq4j.Nullness.castNonNull;
import static org.apache.calcite.util.Static.RESOURCE;

/**
 * A <code>SqlFunction</code> is a type of operator which has conventional
 * function-call syntax.
 */
// SqlFunction 类是描述 SQL 函数的核心类。它继承自 SqlOperator，专门用于处理那些遵循“函数调用语法”（即 FUNCTION_NAME(args...)）的运算符
// SqlFunction 的主要作用是定义和管理 SQL 函数的元数据与行为。
// 与普通的 SqlOperator（如 +、-、AND 等）不同，SqlFunction 具有以下特点：
// 语法规范：强制要求 FUNCTION(arg1, arg2) 这种调用形式。
// 分类管理：通过 SqlFunctionCategory 区分内置函数、用户自定义函数（UDF）、系统函数等。
// 重载支持：在校验阶段，它能够根据参数类型和名称，从函数表中查找并匹配最合适的函数定义。
// 特性支持：支持聚合函数特有的修饰符（如 DISTINCT）。
public class SqlFunction extends SqlOperator {

  //~ Instance fields --------------------------------------------------------
  // 定义函数的分类。
  // 指示该函数属于哪一类。例如：NUMERIC（数值函数）、STRING（字符串函数）、USER_DEFINED_FUNCTION（用户自定义函数）或 USER_DEFINED_CONSTRUCTOR（类型构造函数）。这会影响函数在查找时的过滤逻辑。
  private final SqlFunctionCategory category;
  // 存储函数的全限定名称。
  // 对于内置函数，通常为 null（只使用简单的 String 名称
  // 对于用户定义函数或带有模式前缀的函数（如 schema.my_func），它存储对应的 SqlIdentifier 对象，用于后续的命名解析。
  private final @Nullable SqlIdentifier sqlIdentifier;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a new SqlFunction for a call to a built-in function.
   *
   * @param name                 Name of built-in function
   * @param kind                 kind of operator implemented by function
   * @param returnTypeInference  strategy to use for return type inference
   * @param operandTypeInference strategy to use for parameter type inference
   * @param operandTypeChecker   strategy to use for parameter type checking
   * @param category             categorization for function
   */
  // 用于创建内置函数。内部会将 sqlIdentifier 设为 null。
  public SqlFunction(
      String name,
      SqlKind kind,
      @Nullable SqlReturnTypeInference returnTypeInference,
      @Nullable SqlOperandTypeInference operandTypeInference,
      @Nullable SqlOperandTypeChecker operandTypeChecker,
      SqlFunctionCategory category) {
    // We leave sqlIdentifier as null to indicate
    // that this is a built-in.
    this(name, null, kind, returnTypeInference, operandTypeInference,
        operandTypeChecker, category);

    assert !((category == SqlFunctionCategory.USER_DEFINED_CONSTRUCTOR)
        && (returnTypeInference == null));
  }

  /**
   * Creates a placeholder SqlFunction for an invocation of a function with a
   * possibly qualified name. This name must be resolved into either a built-in
   * function or a user-defined function.
   *
   * @param sqlIdentifier        possibly qualified identifier for function
   * @param returnTypeInference  strategy to use for return type inference
   * @param operandTypeInference strategy to use for parameter type inference
   * @param operandTypeChecker   strategy to use for parameter type checking
   * @param paramTypes           array of parameter types
   * @param funcType             function category
   */
  // 用于创建可能带有命名空间的函数（如 UDF）。
  public SqlFunction(
      SqlIdentifier sqlIdentifier,
      @Nullable SqlReturnTypeInference returnTypeInference,
      @Nullable SqlOperandTypeInference operandTypeInference,
      @Nullable SqlOperandTypeChecker operandTypeChecker,
      @Nullable List<RelDataType> paramTypes,
      SqlFunctionCategory funcType) {
    this(Util.last(sqlIdentifier.names), sqlIdentifier, SqlKind.OTHER_FUNCTION,
        returnTypeInference, operandTypeInference, operandTypeChecker,
        paramTypes, funcType);
  }

  @Deprecated // to be removed before 2.0
  protected SqlFunction(
      String name,
      @Nullable SqlIdentifier sqlIdentifier,
      SqlKind kind,
      @Nullable SqlReturnTypeInference returnTypeInference,
      @Nullable SqlOperandTypeInference operandTypeInference,
      @Nullable SqlOperandTypeChecker operandTypeChecker,
      @Nullable List<RelDataType> paramTypes,
      SqlFunctionCategory category) {
    this(name, sqlIdentifier, kind, returnTypeInference, operandTypeInference,
        operandTypeChecker, category);
  }

  /**
   * Internal constructor.
   */
  protected SqlFunction(
      String name,
      @Nullable SqlIdentifier sqlIdentifier,
      SqlKind kind,
      @Nullable SqlReturnTypeInference returnTypeInference,
      @Nullable SqlOperandTypeInference operandTypeInference,
      @Nullable SqlOperandTypeChecker operandTypeChecker,
      SqlFunctionCategory category) {
    super(name, kind, 100, 100, returnTypeInference, operandTypeInference,
        operandTypeChecker);

    this.sqlIdentifier = sqlIdentifier;
    this.category = Objects.requireNonNull(category, "category");
  }

  //~ Methods ----------------------------------------------------------------
  // 始终返回 SqlSyntax.FUNCTION。这告诉解析器和反解析器该运算符使用 NAME(args) 的格式。
  @Override public SqlSyntax getSyntax() {
    return SqlSyntax.FUNCTION;
  }

  /**
   * Returns the fully-qualified name of function, or null for a built-in
   * function.
   */
  public @Nullable SqlIdentifier getSqlIdentifier() {
    return sqlIdentifier;
  }
  // 如果是内置函数且没有 SqlIdentifier，getNameAsId 会根据字符串名称生成一个新的标识符
  @Override public SqlIdentifier getNameAsId() {
    if (sqlIdentifier != null) {
      return sqlIdentifier;
    }
    return super.getNameAsId();
  }

  /** Use {@link SqlOperandMetadata#paramTypes(RelDataTypeFactory)} on the
   * result of {@link #getOperandTypeChecker()}. */
  @Deprecated // to be removed before 2.0
  public @Nullable List<RelDataType> getParamTypes() {
    return null;
  }

  /** Use {@link SqlOperandMetadata#paramNames()} on the result of
   * {@link #getOperandTypeChecker()}. */
  @Deprecated // to be removed before 2.0
  public List<String> getParamNames() {
    return Functions.generate(castNonNull(getParamTypes()).size(), i -> "arg" + i);
  }
  // 将函数调用转换回 SQL 字符串。它委托给 SqlSyntax 进行处理，确保输出格式为 NAME(args)。
  @Override public void unparse(
      SqlWriter writer,
      SqlCall call,
      int leftPrec,
      int rightPrec) {
    getSyntax().unparse(writer, this, call, leftPrec, rightPrec);
  }

  /**
   * Return function category.
   */
  // 返回该函数的 SqlFunctionCategory。
  public SqlFunctionCategory getFunctionType() {
    return this.category;
  }

  /**
   * Returns whether this function allows a <code>DISTINCT</code> or <code>
   * ALL</code> quantifier. The default is <code>false</code>; some aggregate
   * functions return <code>true</code>.
   */
  // 是否允许量词（DISTINCT 或 ALL）。默认返回 false。聚合函数（如 COUNT）通常会重写此方法返回 true。
  @Pure
  public boolean isQuantifierAllowed() {
    return false;
  }
  // 校验函数调用。除了基础校验，它还会检查用户是否非法使用了 DISTINCT。例如：ABS(DISTINCT x) 会在此阶段报错。
  @Override public void validateCall(
      SqlCall call,
      SqlValidator validator,
      SqlValidatorScope scope,
      SqlValidatorScope operandScope) {
    // This implementation looks for the quantifier keywords DISTINCT or
    // ALL as the first operand in the list.  If found then the literal is
    // not called to validate itself.  Further the function is checked to
    // make sure that a quantifier is valid for that particular function.
    //
    // If the first operand does not appear to be a quantifier then the
    // parent ValidateCall is invoked to do normal function validation.

    super.validateCall(call, validator, scope, operandScope);
    validateQuantifier(validator, call);
  }

  /**
   * Throws a validation error if a DISTINCT or ALL quantifier is present but
   * not allowed.
   */
  protected void validateQuantifier(SqlValidator validator, SqlCall call) {
    SqlLiteral functionQuantifier = call.getFunctionQuantifier();
    if ((null != functionQuantifier) && !isQuantifierAllowed()) {
      throw validator.newValidationError(functionQuantifier,
          RESOURCE.functionQuantifierNotAllowed(call.getOperator().getName()));
    }
  }

  @Override public RelDataType deriveType(
      SqlValidator validator,
      SqlValidatorScope scope,
      SqlCall call) {
    return deriveType(validator, scope, call, false);
  }
  // 核心逻辑
  // 执行以下步骤：
  // 参数准备: 构造参数名和参数类型列表。
  // 函数匹配 (Routine Lookup): 在 SqlOperatorTable 中查找与当前参数类型、名称最匹配的函数实例。
  // 类型隐式转换: 如果启用了 typeCoercionEnabled，当找不到精确匹配时，会尝试进行隐式转换（如将 INT 转为 DOUBLE）后再次查找。
  // 构造函数处理: 如果分类是 USER_DEFINED_CONSTRUCTOR，则调用专门的构造器类型推导。
  // 未知处理: 如果最终找不到匹配项，可能返回一个 SqlUnresolvedFunction 或抛出异常。
  // 绑定算子: 找到确切函数后，使用 setOperator 将其更新到当前的 SqlCall 中。
  private RelDataType deriveType(
      SqlValidator validator,
      SqlValidatorScope scope,
      SqlCall call,
      boolean convertRowArgToColumnList) {
    // Scope for operands. Usually the same as 'scope'.
    final SqlValidatorScope operandScope = scope.getOperandScope(call);

    // Indicate to the validator that we're validating a new function call
    validator.pushFunctionCall();

    final List<String> argNames = constructArgNameList(call);

    final List<SqlNode> args = constructOperandList(validator, call, argNames);

    final List<RelDataType> argTypes =
        constructArgTypeList(validator, scope,
            call, args, convertRowArgToColumnList);

    SqlFunction function =
        (SqlFunction) SqlUtil.lookupRoutine(validator.getOperatorTable(),
            validator.getTypeFactory(), getNameAsId(), argTypes, argNames,
            getFunctionType(), SqlSyntax.FUNCTION, getKind(),
            validator.getCatalogReader().nameMatcher(), false);

    // If the call already has an operator and its syntax is SPECIAL, it must
    // have been created intentionally by the parser.
    if (function == null
        && call.getOperator().getSyntax() == SqlSyntax.SPECIAL
        && call.getOperator() instanceof SqlFunction
        && validator.getOperatorTable().getOperatorList().contains(
            call.getOperator())) {
      function = (SqlFunction) call.getOperator();
    }

    try {
      // if we have a match on function name and parameter count, but
      // couldn't find a function with  a COLUMN_LIST type, retry, but
      // this time, don't convert the row argument to a COLUMN_LIST type;
      // if we did find a match, go back and re-validate the row operands
      // (corresponding to column references), now that we can set the
      // scope to that of the source cursor referenced by that ColumnList
      // type
      if (convertRowArgToColumnList && containsRowArg(args)) {
        if (function == null
            && SqlUtil.matchRoutinesByParameterCount(
                validator.getOperatorTable(), getNameAsId(), argTypes,
                getFunctionType(),
                validator.getCatalogReader().nameMatcher())) {
          // remove the already validated node types corresponding to
          // row arguments before re-validating
          for (SqlNode operand : args) {
            if (operand.getKind() == SqlKind.ROW) {
              validator.removeValidatedNodeType(operand);
            }
          }
          return deriveType(validator, scope, call, false);
        }
      }

      if (getFunctionType() == SqlFunctionCategory.USER_DEFINED_CONSTRUCTOR) {
        return validator.deriveConstructorType(scope, call, this, function,
            argTypes);
      }

      validCoercionType:
      if (function == null) {
        if (validator.config().typeCoercionEnabled()) {
          // try again if implicit type coercion is allowed.
          function = (SqlFunction)
              SqlUtil.lookupRoutine(validator.getOperatorTable(),
                  validator.getTypeFactory(),
                  getNameAsId(),
                  argTypes, argNames, getFunctionType(), SqlSyntax.FUNCTION,
                  getKind(), validator.getCatalogReader().nameMatcher(), true);
          // try to coerce the function arguments to the declared sql type name.
          // if we succeed, the arguments would be wrapped with CAST operator.
          if (function != null) {
            TypeCoercion typeCoercion = validator.getTypeCoercion();
            if (typeCoercion.userDefinedFunctionCoercion(scope, call, function)) {
              break validCoercionType;
            }
          }
        }

        // check if the identifier represents type
        final SqlFunction x = (SqlFunction) call.getOperator();
        final SqlIdentifier identifier =
            Util.first(x.getSqlIdentifier(),
                new SqlIdentifier(x.getName(), SqlParserPos.ZERO));
        RelDataType type = validator.getCatalogReader().getNamedType(identifier);
        if (type != null) {
          function = new SqlTypeConstructorFunction(identifier, type);
          break validCoercionType;
        }

        // if function doesn't exist within operator table and known function
        // handling is turned off then create a more permissive function
        if (function == null && validator.config().lenientOperatorLookup()) {
          function =
              new SqlUnresolvedFunction(identifier, null,
                  null, OperandTypes.VARIADIC, null, x.getFunctionType());
          break validCoercionType;
        }
        throw validator.handleUnresolvedFunction(call, this, argTypes,
            argNames);
      }

      // REVIEW jvs 25-Mar-2005:  This is, in a sense, expanding
      // identifiers, but we ignore shouldExpandIdentifiers()
      // because otherwise later validation code will
      // choke on the unresolved function.
      ((SqlBasicCall) call).setOperator(function);
      return function.validateOperands(
          validator,
          operandScope,
          call);
    } finally {
      validator.popFunctionCall();
    }
  }

  private static boolean containsRowArg(List<SqlNode> args) {
    for (SqlNode operand : args) {
      if (operand.getKind() == SqlKind.ROW) {
        return true;
      }
    }
    return false;
  }
}
