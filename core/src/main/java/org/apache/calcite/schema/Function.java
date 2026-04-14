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
package org.apache.calcite.schema;

import java.util.List;

/**
 * Named expression that accepts parameters and returns a result.
 *
 * <p>The application may occur at compile time (for a macro) or at run time
 * (for a regular function). The result may be a relation, and so might any of
 * the parameters.
 *
 * <p>Functions are registered in a {@link Schema}, and may be queried by name
 * ({@link Schema#getFunctions(String)}) then overloads resolved based on
 * parameter types.
 *
 * @see TableMacro
 * @see ScalarFunction
 */
// Function 接口是所有用户自定义逻辑（UDF、UDTF、聚合函数等）的基石。它代表了一个可以接受参数并返回结果的命名表达式。
// Function 接口定义了 Calcite 中“函数”这一概念的最通用形态。
// 元数据载体：它描述了一个函数“长什么样”（即它的签名/参数列表）。
// 统一抽象：在 SQL 中，无论是返回单值的标量函数（Scalar Function）、返回表的表函数（Table Function），还是在编译时展开的宏（Macro），在底层元数据层都统一表示为 Function 的子类。
// Function 是元数据层的定义（存在于库里），而 SqlOperator 是 SQL 语法层的表达（存在于 SQL 语句里）。
// Function 代表后端存储或计算引擎中的物理函数，范围仅限函数（Scalar, Table, Aggregate）
// qlOperator代表 SQL 语法树（SqlNode）中的一个节点，范围包含函数、算子（+, AND）、语法结构（CASE）
public interface Function {
  /**
   * Returns the parameters of this function.
   *
   * @return Parameters; never null
   */
  // 获取该函数定义的所有参数信息。
  // FunctionParameter 接口包含了参数的名称、序号（Ordinal）、数据类型（RelDataType）以及是否允许为 Null 等元数据。
  List<FunctionParameter> getParameters();
}
