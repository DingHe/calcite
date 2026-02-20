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
 * 策略模式的策略接口，用于根据操作数推断返回值类型
 * <p>This interface is an example of the
 * {@link org.apache.calcite.util.Glossary#STRATEGY_PATTERN strategy pattern}.
 * This makes
 * sense because many operators have similar, straightforward strategies, such
 * as to take the type of the first operand.
 *
 * @see ReturnTypes
 */
@FunctionalInterface
public interface SqlReturnTypeInference {
  //~ Methods ----------------------------------------------------------------

  /**
   * Infers the return type of a call to an {@link SqlOperator}.
   *
   * @param opBinding description of operator binding
   * @return inferred type; may be null
   */
  @Nullable RelDataType inferReturnType(
      SqlOperatorBinding opBinding);

  /** Returns a return-type inference that applies this rule then a
   * transform. 根据inferReturnType的推断结果，再应用SqlTypeTransform*/
  default SqlReturnTypeInference andThen(SqlTypeTransform transform) {
    return ReturnTypes.cascade(this, transform);
  }

  /** Returns a return-type inference that applies this rule then another
   * rule, until one of them returns a not-null result. 如果inferReturnType推断的结果为null，则应用transform*/
  default SqlReturnTypeInference orElse(SqlReturnTypeInference transform) {
    return ReturnTypes.chain(this, transform);
  }
}
