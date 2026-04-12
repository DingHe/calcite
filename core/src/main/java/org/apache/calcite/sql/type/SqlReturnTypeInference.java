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

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorBinding;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Strategy interface to infer the type of an operator call from the type of the
 * operands.
 * <p>This interface is an example of the
 * {@link org.apache.calcite.util.Glossary#STRATEGY_PATTERN strategy pattern}.
 * This makes
 * sense because many operators have similar, straightforward strategies, such
 * as to take the type of the first operand.
 *
 * @see ReturnTypes
 */
// SqlReturnTypeInference 是一个策略接口（Strategy Interface），其核心作用是：根据 SQL 操作符被调用时的上下文（包括参数的类型、数值等），推导出该操作返回的结果类型。
// 在 SQL 中，同一个操作符的返回类型往往不是固定的，而是取决于输入：
// 算术运算：INT + INT 返回 INT，但 INT + DOUBLE 必须返回 DOUBLE。
// 函数调用：SUBSTR(string, ...) 返回字符串，而 COUNT(*) 必须返回 BIGINT。
// 复杂逻辑：COALESCE(a, b, c) 的返回类型必须是 a, b, c 三者兼容后的最小公共类型（Least Restrictive Type）。
@FunctionalInterface
public interface SqlReturnTypeInference {
  //~ Methods ----------------------------------------------------------------

  /**
   * Infers the return type of a call to an {@link SqlOperator}.
   *
   * @param opBinding description of operator binding
   * @return inferred type; may be null
   */
  // 推导逻辑的入口。
  // opBinding：这是一个“绑定对象”，它封装了调用时的所有有用信息。通过它，你可以拿到：
  // 操作数的类型列表（getType(i)）。
  // 操作数的个数（getOperandCount()）。
  // 如果是常量，甚至可以拿到操作数的具体数值（用于某些需要根据值推导类型的特殊函数）。
  @Nullable RelDataType inferReturnType(
      SqlOperatorBinding opBinding);

  /** Returns a return-type inference that applies this rule then a
   * transform. */
  // 这两个方法体现了 Calcite 对**组合模式（Composite Pattern）**的应用，允许开发者像搭积木一样组合多个复杂的类型规则
  // 链式转换。先应用当前的推导规则得到一个类型，然后将这个结果交给 SqlTypeTransform 进行进一步加工。
  // “先推导出第一参数的类型，然后把它变成可空的（Nullable）”。
  // “先推导出数值类型，然后强制转换为 VARCHAR”。
  default SqlReturnTypeInference andThen(SqlTypeTransform transform) {
    return ReturnTypes.cascade(this, transform);
  }

  /** Returns a return-type inference that applies this rule then another
   * rule, until one of them returns a not-null result.*/
  // 容错/备选链。先尝试应用当前的推导规则，如果返回 null（表示当前规则不适用），则尝试应用 transform（另一个推导规则）。
  default SqlReturnTypeInference orElse(SqlReturnTypeInference transform) {
    return ReturnTypes.chain(this, transform);
  }
}
