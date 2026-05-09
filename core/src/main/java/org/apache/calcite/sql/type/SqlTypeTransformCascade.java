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

import java.util.Objects;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Strategy to infer the type of an operator call from the type of the operands
 * by using one {@link SqlReturnTypeInference} rule and a combination of
 * {@link SqlTypeTransform}s.
 */
// SqlTypeTransformCascade 的本质是一个“流水线（Pipeline）”控制器。
// 先通过一个基础规则推导出初始类型，然后像接力赛一样，将这个结果通过一系列“类型转换器”进行加工，最终得到最终的返回类型。
// 级联机制：名字中的 "Cascade" 意为级联。它解决了单一推导规则过于死板的问题。例如，你可以定义一个规则推导基础类型是 INTEGER，然后级联一个转换器将其变为 NULLABLE（可空）。
public class SqlTypeTransformCascade implements SqlReturnTypeInference {
  //~ Instance fields --------------------------------------------------------
  // 这是类型推导的起点（种子规则）
  // 它的任务是根据当前的 SQL 绑定上下文（SqlOperatorBinding）计算出一个初始的 RelDataType。如果这个规则返回 null，整个级联过程就会提前终止。
  private final SqlReturnTypeInference rule;
  // 类型转换的工序列表
  // 不可变的插件列表，每个插件都实现了 SqlTypeTransform 接口。它们按顺序排列，上一个转换器的输出将作为下一个转换器的输入
  private final ImmutableList<SqlTypeTransform> transforms;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a SqlTypeTransformCascade from a rule and an array of one or more
   * transforms.
   */
  public SqlTypeTransformCascade(
      SqlReturnTypeInference rule,
      SqlTypeTransform... transforms) {
    checkArgument(transforms.length > 0);
    this.rule = Objects.requireNonNull(rule, "rule");
    this.transforms = ImmutableList.copyOf(transforms);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public @Nullable RelDataType inferReturnType(
      SqlOperatorBinding opBinding) {
    RelDataType ret = rule.inferReturnType(opBinding);  //根据rule推断返回值
    if (ret == null) {
      // inferReturnType may return null; transformType does not accept or
      // return null types
      return null;
    }
    for (SqlTypeTransform transform : transforms) {   //再根据SqlTypeTransform转换
      ret = transform.transformType(opBinding, ret);
    }
    return ret;
  }
}
