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
import org.apache.calcite.sql.SqlOperatorBinding;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import static com.google.common.base.Preconditions.checkArgument;

/** sql返回值类型推断链，根据rules的顺序，返回第一个推断返回值不为null的类型
 * Strategy to infer the type of an operator call from the type of the operands
 * by using a series of {@link SqlReturnTypeInference} rules in a given order.
 * If a rule fails to find a return type (by returning NULL), next rule is tried
 * until there are no more rules in which case NULL will be returned.
 */
public class SqlReturnTypeInferenceChain implements SqlReturnTypeInference {
  //~ Instance fields --------------------------------------------------------

  private final ImmutableList<SqlReturnTypeInference> rules;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a SqlReturnTypeInferenceChain from an array of rules.
   *
   * <p>Package-protected.
   * Use {@link org.apache.calcite.sql.type.ReturnTypes#chain}.
   */
  SqlReturnTypeInferenceChain(SqlReturnTypeInference... rules) {
    checkArgument(rules.length > 1);
    this.rules = ImmutableList.copyOf(rules);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public @Nullable RelDataType inferReturnType(SqlOperatorBinding opBinding) {
    for (SqlReturnTypeInference rule : rules) {
      RelDataType ret = rule.inferReturnType(opBinding);  //遍历规则，如果rule返回的返回值类型不为null，则返回
      if (ret != null) {
        return ret;
      }
    }
    return null;
  }
}
