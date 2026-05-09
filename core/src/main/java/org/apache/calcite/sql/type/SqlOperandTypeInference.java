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
import org.apache.calcite.sql.SqlCallBinding;

/**
 * Strategy to infer unknown types of the operands of an operator call.
 *
 * @see InferTypes
 */
// 定义了 “操作数类型推导策略”
// 主要作用是在 SQL 校验阶段，推断那些“未知”的操作数类型。最典型的场景是处理 动态参数（Dynamic Parameters，即 ? 占位符）。
// 当你在 SQL 中写 SELECT * FROM emp WHERE empno = ? 时，数据库并不知道 ? 是什么类型。此时，Calcite 会利用这个接口，根据上下文（例如 empno 是 INTEGER）推断出 ? 也应该是 INTEGER。
public interface SqlOperandTypeInference {
  //~ Methods ----------------------------------------------------------------

  /**
   * Infers any unknown operand types.
   *
   * @param callBinding  description of the call being analyzed
   * @param returnType   the type known or inferred for the result of the call
   * @param operandTypes receives the inferred types for all operands
   */
  void inferOperandTypes(
      SqlCallBinding callBinding, // 提供函数或运算符调用的详细上下文。仅包含操作数本身，还包含了作用域（Scope）、验证器（Validator）等。你可以通过它访问 SQL 节点的语法树信息
      RelDataType returnType, // 该表达式已被推断出的返回类型。有时操作数的类型取决于返回值的类型。例如，在某些转换函数中，如果我们知道结果必须是 VARCHAR，我们可以反向推导出输入参数也应该是某种字符类型。
      RelDataType[] operandTypes); // 这是一个输出参数（结果容器），也是该方法最核心的操作对象。
}
