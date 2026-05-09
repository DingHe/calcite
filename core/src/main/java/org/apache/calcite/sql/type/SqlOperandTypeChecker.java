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
package org.apache.calcite.sql.type;

import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlOperandCountRange;
import org.apache.calcite.sql.SqlOperator;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.function.BiFunction;

/**
 * Strategy interface to check for allowed operand types of an operator call.
 *
 * <p>This interface is an example of the
 * {@link org.apache.calcite.util.Glossary#STRATEGY_PATTERN strategy pattern}.
 *
 * @see OperandTypes
 */
// 定义了 “操作数类型校验策略”。
// 主要职责是判断用户在 SQL 中对某个运算符（Operator）或函数（Function）的调用是否“合规”。例如，当你尝试执行 SUM('abc') 时，正是这个接口的实现类跳出来报错：“SUM 函数不接受字符类型”。
// 职责范围：不仅校验类型（Type），还校验操作数的个数（Count）、顺序（Order）以及是否可选（Optional）。
// 设计模式：它是典型的策略模式。不同的函数拥有不同的校验器实现。
public interface SqlOperandTypeChecker {
  //~ Methods ----------------------------------------------------------------

  /**
   * Checks the types of all operands to an operator call.
   *
   * @param callBinding    description of the call to be checked
   * @param throwOnFailure whether to throw an exception if check fails
   *                       (otherwise returns false in that case)
   * @return whether check succeeded
   */
  // 执行实际的校验逻辑。
  boolean checkOperandTypes(
      SqlCallBinding callBinding, // 包含调用的上下文（操作数的类型、名称等）
      boolean throwOnFailure);

  /** Returns the range of operand counts allowed in a call. */
  // 定义该运算符允许的操作数个数范围。
  // 返回一个范围对象（如：1 到 3 个参数，或者固定 2 个参数）。
  SqlOperandCountRange getOperandCountRange();

  /**
   * Returns a string describing the allowed formal signatures of a call, e.g.
   * "SUBSTR(VARCHAR, INTEGER, INTEGER)".
   *
   * @param op     the operator being checked
   * @param opName name to use for the operator in case of aliasing
   * @return generated string
   */
  // 当校验失败时，用于生成错误提示信息中的“期望签名”。
  // 示例：如果调用 MOD(1, 'a') 失败，它会返回 "MOD(NUMERIC, NUMERIC)"，提示用户正确的用法。
  String getAllowedSignatures(SqlOperator op, String opName);

  /** Returns the strategy for making the arguments have consistency types. */
  // 返回该校验器的一致性策略。
  // NONE：不强制一致，各走各的。
  // COMPARE：使用比较语义进行隐式转换（如将字符串转为数字）。
  // LEAST_RESTRICTIVE：尝试将所有操作数提升为它们之间“约束最少”的公共类型。
  default Consistency getConsistency() {
    return Consistency.NONE;
  }

  /** Returns a copy of this checker with the given signature generator. */
  // 创建一个副本，并为其指定自定义的签名生成器。
  default CompositeOperandTypeChecker withGenerator(
      BiFunction<SqlOperator, String, String> signatureGenerator) {
    // We should support for all subclasses but don't yet.
    throw new UnsupportedOperationException("withGenerator");
  }

  /** Returns whether the {@code i}th operand is optional. */
  // 判断第 i 个参数（从 0 开始）是否是可选的。
  // 场景：例如 SUBSTR(str, start, [length]) 中的 length 就是可选的。
  default boolean isOptional(int i) {
    return false;
  }

  /** Returns whether the list of parameters is fixed-length. In standard SQL,
   * user-defined functions are fixed-length.
   *
   * <p>If true, the validator should expand calls, supplying a {@code DEFAULT}
   * value for each parameter for which an argument is not supplied. */
  // 标识参数列表是否为固定长度。
  // 机制：如果是 true，校验器在遇到缺失的参数时，会自动尝试用 DEFAULT 关键字填充。
  default boolean isFixedParameters() {
    return false;
  }

  /** Converts this type checker to a type inference; returns null if not
   * possible. */
  // 将当前校验器转换为推断器。
  default @Nullable SqlOperandTypeInference typeInference() {
    return null;
  }

  /** Composes this with another checker using AND. */
  // 逻辑组合。
  default SqlOperandTypeChecker and(SqlOperandTypeChecker checker) {
    return OperandTypes.and(this, checker);
  }

  /** Composes this with another checker using OR. */
  default SqlOperandTypeChecker or(SqlOperandTypeChecker checker) {
    return OperandTypes.or(this, checker);
  }

  /** Strategy used to make arguments consistent. */
  enum Consistency {
    /** Do not try to make arguments consistent. */
    NONE,
    /** Make arguments of consistent type using comparison semantics.
     * Character values are implicitly converted to numeric, date-time, interval
     * or boolean. */
    COMPARE,
    /** Convert all arguments to the least restrictive type. */
    LEAST_RESTRICTIVE
  }
}
