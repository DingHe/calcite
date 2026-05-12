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

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlOperandTypeInference;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeName;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Placeholder for an unresolved function.
 * <p>Created by the parser, then it is rewritten to proper SqlFunction by
 * the validator to a function defined in a Calcite schema.
 */
// 核心作用是作为 “临时占位符”。
// 在 SQL 解析阶段（Parser），当遇到一个函数调用（如 MY_CUSTOM_FUNC(1)）时，解析器只知道这是一个函数名，但并不知道这个函数是否真实存在，也不知道它是内置函数、用户自定义函数（UDF）还是某种存储过程。
// 产生时机：由解析器（Parser）创建。
// 消亡时机：在校验阶段（Validator）被重写。校验器会去 SqlOperatorTable 中查找真实的函数定义，一旦找到，就会将该节点替换为具体的 SqlFunction 子类。
// 容错设计：它确保了即使在不确定函数定义的情况下，抽象语法树（AST）也能正常构建，而不会在解析阶段就因为找不到函数而崩溃。
public class SqlUnresolvedFunction extends SqlFunction {
  /**
   * Creates a placeholder SqlUnresolvedFunction for an invocation of a function
   * with a possibly qualified name. This name must be resolved into either
   * a builtin function or a user-defined function.
   *
   * @param sqlIdentifier        possibly qualified identifier for function
   * @param returnTypeInference  strategy to use for return type inference
   * @param operandTypeInference strategy to use for parameter type inference
   * @param operandTypeChecker   strategy to use for parameter type checking
   * @param paramTypes           array of parameter types
   * @param funcType             function category
   */
  // 初始化占位符。
  public SqlUnresolvedFunction(
      SqlIdentifier sqlIdentifier,
      @Nullable SqlReturnTypeInference returnTypeInference,
      @Nullable SqlOperandTypeInference operandTypeInference,
      @Nullable SqlOperandTypeChecker operandTypeChecker,
      @Nullable List<RelDataType> paramTypes,
      SqlFunctionCategory funcType) {
    super(sqlIdentifier, returnTypeInference, operandTypeInference,
        operandTypeChecker, paramTypes, funcType);
  }

  /**
   * {@inheritDoc}T
   *
   * <p>The operator class for this function isn't resolved to the
   * correct class. This happens in the case of user defined
   * functions. Return the return type to be 'ANY', so we don't
   * fail.
   */
  // 推断返回类型。
  // 为什么要返回 ANY？
  // 因为这个函数还没被解析，系统不知道它到底返回什么（是数字？字符串？还是表？）。
  // 为了让校验器的后续流程（比如处理嵌套表达式）能够继续进行而不立即报错，这里慷慨地返回 ANY。这是一种延迟报错的策略，真正的错误（如果函数不存在）会在校验器的 lookup 失败时抛出。
  // 关键重写。在函数身份未明时，统一声称返回 ANY 类型，确保校验流程不中断。
  @Override public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
    final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
    return typeFactory.createTypeWithNullability(
        typeFactory.createSqlType(SqlTypeName.ANY), true);
  }
}
