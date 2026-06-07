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

import org.apache.calcite.sql.type.OperandHandlers;
import org.apache.calcite.sql.type.SqlOperandHandler;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlOperandTypeInference;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.validate.SqlMonotonicity;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorScope;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Concrete implementation of {@link SqlFunction}.
 *
 * <p>The class is final, and instances are immutable.
 *
 * <p>Instances are created only by {@link SqlBasicFunction#create} and are
 * "modified" by "wither" methods such as {@link #withName} to create a new
 * instance with one property changed. Since the class is final, you can modify
 * behavior only by providing strategy objects, not by overriding methods in a
 * subclass.
 */
// SqlBasicFunction 是一个设计得非常纯粹且优雅的标准 SQL 函数具体实现类。它继承自 SqlFunction，是元数据层面上各种内置函数和自定义函数（如 ABS、CONCAT、SUBSTRING 等）的物理载体。
// 在很多传统的 SQL 解析器中，每增加一个 SQL 函数（比如加一个 SUBSTR 函数），开发人员就必须去写一个对应的子类（如 class SqlSubstrFunction extends SqlFunction）。这样会导致子类数量疯狂爆炸，架构难以维护。
// SqlBasicFunction 彻底终结了这种乱象。它被声明为 final，意味着不允许任何人继承它。它把函数的所有行为特征（如怎么校验参数、怎么推导返回值类型）抽象成了独立的策略对象（Strategy Objects）。注册新函数时，只需要用不同的策略对象去组合、实例化 SqlBasicFunction 即可。
// 这个类所有的成员属性都被锁死为 final，其实例在内存中是绝对不可变的。
// 当 Calcite 的优化器或校验器想要微调某个函数的属性时（例如改个名字或换个类型检查器），它不能直接修改当前对象，而是通过调用该类特有的 withXXX（Wither）方法，在内存中瞬间克隆并返回一个带有新特性的全新实例。这保证了在多线程并发解析和优化 SQL 时的绝对线程安全。
public class SqlBasicFunction extends SqlFunction {
  // 该函数的 SQL 语法形态特征（如 FUNCTION 对应 f(x)，BINARY 对应 a + b）。
  // 指导 SQL 转换器和还原器（SqlDialect）如何把语法树节点重新正确的吐成对应的标准的 SQL 文本字符串。
  private final SqlSyntax syntax;
  // 该函数是否为确定性函数（幂等函数）。
  // 如果为 true（如 ABS(x)），代表只要输入相同，返回值永远相同。优化器据此可以启动常量折叠（Constant Folding），在编译期直接把 ABS(-5) 算成 5。如果为 false（如 RAND()、NOW()），优化器则绝对不敢在编译期乱动它，必须留到运行期实时计算。
  private final boolean deterministic;
  // 操作数（入参）的特殊处理器。
  // 负责干预入参的物理呈现。例如有些函数在校验前需要特殊的重写（Rewrite）改写逻辑，或者有些特殊参数需要做就地变换，全部通过这个引脚委派出去执行。
  private final SqlOperandHandler operandHandler;
  // 用于精细化控制校验阶段的特殊路由分支。
  private final int callValidator;
  // 函数的单调性（Monotonicity）推导策略
  // 负责计算当输入列是单调递增时，函数的输出是不是也是单调的
  // 例如，如果 ts 列是单调递增的，那么 FLOOR(ts) 也是单调递增的。这对于流处理（Streaming SQL）中判断 Watermark 是否可以安全推进，或者对有序流进行 Group By 优化时至关重要。
  private final Function<SqlOperatorBinding, SqlMonotonicity> monotonicityInference;
  // 该函数是否为动态函数（如 CURRENT_TIMESTAMP）。
  private final boolean dynamic;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a new SqlFunction for a call to a built-in function.
   *
   * @param name Name of built-in function
   * @param kind Kind of operator implemented by function
   * @param syntax Syntax
   * @param deterministic Whether the function is deterministic
   * @param returnTypeInference Strategy to use for return type inference
   * @param operandTypeInference Strategy to use for parameter type inference
   * @param operandHandler Strategy to use for handling operands
   * @param operandTypeChecker Strategy to use for parameter type checking
   * @param callValidator Strategy to validate calls
   * @param category Categorization for function
   * @param monotonicityInference Strategy to infer monotonicity of a call
   */
  // 使命非常纯粹：将高层传入的 12 个核心函数行为特征，一部分“向上弹射”到基类 SqlFunction 锁死，
  // 另一部分留存在本地转化为 final 属性，在内存中瞬间凝固成一个绝对不可变的函数元数据原子。
  protected SqlBasicFunction(String name, SqlKind kind, SqlSyntax syntax,
      boolean deterministic, SqlReturnTypeInference returnTypeInference,
      @Nullable SqlOperandTypeInference operandTypeInference,
      SqlOperandHandler operandHandler,
      SqlOperandTypeChecker operandTypeChecker,
      Integer callValidator,
      SqlFunctionCategory category,
      Function<SqlOperatorBinding, SqlMonotonicity> monotonicityInference,
      boolean dynamic) {
    super(name, kind,
        requireNonNull(returnTypeInference, "returnTypeInference"),
        operandTypeInference,
        // 入参类型推导器。允许传入 @Nullable。
        requireNonNull(operandTypeChecker, "operandTypeChecker"), category);
    this.syntax = requireNonNull(syntax, "syntax");
    this.deterministic = deterministic;
    this.operandHandler = requireNonNull(operandHandler, "operandHandler");
    this.callValidator = requireNonNull(callValidator, "callValidator");
    this.monotonicityInference =
        requireNonNull(monotonicityInference, "monotonicityInference");
    this.dynamic = dynamic;
  }

  /**
   * Creates a {@code SqlBasicFunction}.
   *
   * @param name function name
   * @param kind function kind
   * @param returnTypeInference Strategy to use for return type inference
   * @param operandTypeChecker Strategy to use for parameter type checking
   * @return a {@code SqlBasicFunction}
   */
  public static SqlBasicFunction create(String name, SqlKind kind,
      SqlReturnTypeInference returnTypeInference,
      SqlOperandTypeChecker operandTypeChecker) {
    return new SqlBasicFunction(name, kind,
        SqlSyntax.FUNCTION, true, returnTypeInference, null,
        OperandHandlers.DEFAULT, operandTypeChecker, 0,
        SqlFunctionCategory.SYSTEM, call -> SqlMonotonicity.NOT_MONOTONIC, false);
  }

  /** Creates a {@code SqlBasicFunction} whose name is the same as its kind
   * and whose category {@link SqlFunctionCategory#SYSTEM}. */
  public static SqlBasicFunction create(SqlKind kind,
      SqlReturnTypeInference returnTypeInference,
      SqlOperandTypeChecker operandTypeChecker) {
    return new SqlBasicFunction(kind.name(), kind,
        SqlSyntax.FUNCTION, true, returnTypeInference, null,
        OperandHandlers.DEFAULT, operandTypeChecker, 0,
        SqlFunctionCategory.SYSTEM, call -> SqlMonotonicity.NOT_MONOTONIC, false);
  }

  /** Creates a {@code SqlBasicFunction}
   * with kind {@link SqlKind#OTHER_FUNCTION}
   * and category {@link SqlFunctionCategory#NUMERIC}. */
  public static SqlBasicFunction create(String name,
      SqlReturnTypeInference returnTypeInference,
      SqlOperandTypeChecker operandTypeChecker) {
    return new SqlBasicFunction(name, SqlKind.OTHER_FUNCTION,
        SqlSyntax.FUNCTION, true, returnTypeInference, null,
        OperandHandlers.DEFAULT, operandTypeChecker, 0,
        SqlFunctionCategory.NUMERIC, call -> SqlMonotonicity.NOT_MONOTONIC, false);
  }

  /** Creates a {@code SqlBasicFunction}
   * with kind {@link SqlKind#OTHER_FUNCTION}. */
  public static SqlBasicFunction create(String name,
      SqlReturnTypeInference returnTypeInference,
      SqlOperandTypeChecker operandTypeChecker, SqlFunctionCategory category) {
    return new SqlBasicFunction(name, SqlKind.OTHER_FUNCTION,
        SqlSyntax.FUNCTION, true, returnTypeInference, null,
        OperandHandlers.DEFAULT, operandTypeChecker, 0,
        category, call -> SqlMonotonicity.NOT_MONOTONIC, false);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public SqlReturnTypeInference getReturnTypeInference() {
    return requireNonNull(super.getReturnTypeInference(), "returnTypeInference");
  }

  @Override public SqlOperandTypeChecker getOperandTypeChecker() {
    return requireNonNull(super.getOperandTypeChecker(), "operandTypeChecker");
  }

  @Override public SqlSyntax getSyntax() {
    return syntax;
  }

  @Override public boolean isDeterministic() {
    return deterministic;
  }

  @Override public SqlMonotonicity getMonotonicity(SqlOperatorBinding call) {
    return monotonicityInference.apply(call);
  }

  @Override public SqlNode rewriteCall(SqlValidator validator, SqlCall call) {
    return operandHandler.rewriteCall(validator, call);
  }

  @Override public void validateCall(SqlCall call, SqlValidator validator,
      SqlValidatorScope scope, SqlValidatorScope operandScope) {
    super.validateCall(call, validator, scope, operandScope);
  }

  @Override public boolean isDynamicFunction() {
    return dynamic;
  }

  /** Returns a copy of this function with a given name. */
  public SqlBasicFunction withName(String name) {
    return new SqlBasicFunction(name, kind, syntax, deterministic,
        getReturnTypeInference(), getOperandTypeInference(), operandHandler,
        getOperandTypeChecker(), callValidator,
        getFunctionType(), monotonicityInference, dynamic);
  }

  /** Returns a copy of this function with a given kind. */
  public SqlBasicFunction withKind(SqlKind kind) {
    return new SqlBasicFunction(getName(), kind, syntax, deterministic,
        getReturnTypeInference(), getOperandTypeInference(), operandHandler,
        getOperandTypeChecker(), callValidator,
        getFunctionType(), monotonicityInference, dynamic);
  }

  /** Returns a copy of this function with a given category. */
  public SqlBasicFunction withFunctionType(SqlFunctionCategory category) {
    return new SqlBasicFunction(getName(), kind, syntax, deterministic,
        getReturnTypeInference(), getOperandTypeInference(), operandHandler,
        getOperandTypeChecker(), callValidator, category, monotonicityInference, dynamic);
  }

  /** Returns a copy of this function with a given syntax. */
  public SqlBasicFunction withSyntax(SqlSyntax syntax) {
    return new SqlBasicFunction(getName(), kind, syntax, deterministic,
        getReturnTypeInference(), getOperandTypeInference(), operandHandler,
        getOperandTypeChecker(), callValidator,
        getFunctionType(), monotonicityInference, dynamic);
  }

  /** Returns a copy of this function with a given strategy for inferring
   * returned type. */
  public SqlBasicFunction withReturnTypeInference(
      SqlReturnTypeInference returnTypeInference) {
    return new SqlBasicFunction(getName(), kind, syntax, deterministic,
        returnTypeInference, getOperandTypeInference(), operandHandler,
        getOperandTypeChecker(), callValidator,
        getFunctionType(), monotonicityInference, dynamic);
  }

  /** Returns a copy of this function with a given strategy for inferring
   * the types of its operands. */
  public SqlBasicFunction withOperandTypeInference(
      SqlOperandTypeInference operandTypeInference) {
    return new SqlBasicFunction(getName(), kind, syntax, deterministic,
        getReturnTypeInference(), operandTypeInference, operandHandler,
        getOperandTypeChecker(), callValidator,
        getFunctionType(), monotonicityInference, dynamic);
  }

  /** Returns a copy of this function with a given strategy for handling
   * operands. */
  public SqlBasicFunction withOperandHandler(SqlOperandHandler operandHandler) {
    return new SqlBasicFunction(getName(), kind, syntax, deterministic,
        getReturnTypeInference(), getOperandTypeInference(), operandHandler,
        getOperandTypeChecker(), callValidator,
        getFunctionType(), monotonicityInference, dynamic);
  }
  /** Returns a copy of this function with a given determinism. */
  public SqlBasicFunction withDeterministic(boolean deterministic) {
    return new SqlBasicFunction(getName(), kind, syntax, deterministic,
        getReturnTypeInference(), getOperandTypeInference(), operandHandler,
        getOperandTypeChecker(), callValidator,
        getFunctionType(), monotonicityInference, dynamic);
  }

  /** Returns a copy of this function with a given strategy for inferring
   * whether a call is monotonic. */
  public SqlBasicFunction withMonotonicityInference(
      Function<SqlOperatorBinding, SqlMonotonicity> monotonicityInference) {
    return new SqlBasicFunction(getName(), kind, syntax, deterministic,
        getReturnTypeInference(), getOperandTypeInference(), operandHandler,
        getOperandTypeChecker(), callValidator,
        getFunctionType(), monotonicityInference, dynamic);
  }

  public SqlBasicFunction withValidation(int callValidator) {
    return new SqlBasicFunction(getName(), kind, syntax, deterministic,
        getReturnTypeInference(), getOperandTypeInference(), operandHandler,
        getOperandTypeChecker(), callValidator,
        getFunctionType(), monotonicityInference, dynamic);
  }

  public SqlBasicFunction withDynamic(boolean dynamic) {
    return new SqlBasicFunction(getName(), kind, syntax, deterministic,
        getReturnTypeInference(), getOperandTypeInference(), operandHandler,
        getOperandTypeChecker(), callValidator,
        getFunctionType(), monotonicityInference, dynamic);
  }

  public SqlBasicFunction withOperandTypeChecker(SqlOperandTypeChecker operandTypeChecker) {
    return new SqlBasicFunction(getName(), kind, syntax, deterministic,
        getReturnTypeInference(), getOperandTypeInference(), operandHandler,
        operandTypeChecker, callValidator,
        getFunctionType(), monotonicityInference, dynamic);
  }
}
