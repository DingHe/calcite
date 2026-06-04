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
import org.apache.calcite.rel.type.RelDataTypeFactory;

import java.util.List;

/**
 * Extension to {@link SqlOperandTypeChecker} that also provides
 * names and types of particular operands.
 *
 * <p>It is intended for user-defined functions (UDFs), and therefore the number
 * of parameters is fixed.
 *
 * @see OperandTypes
 */
// 在标准的 SQL 校验中，父接口 SqlOperandTypeChecker 的主要职责是“当裁判”——它只负责检查用户在 SQL 中传入的参数是否合法（例如：参数个数对不对、类型是不是数字或字符串）。然而，它并不知道这些参数叫什么名字，也无法为开发人员或智能提示工具提供函数的“签名大纲”。
// SqlOperandMetadata 接口的核心作用就是为函数（尤其是用户自定义函数 UDF 或系统内置的高级表函数 TVF）注入丰富的“自省元数据”（Introspection Metadata）。它将“参数校验”与“参数元数据声明”完美结合：
// 提供参数显式命名（Named Parameters）：它能告诉优化器函数的第 $n$ 个参数叫什么名字（例如 PARAM_SIZE、PARAM_OFFSET）。这使得 SQL 能够支持具名参数调用（例如：MY_FUNC(DATA => table, SIZE => 10)），这在参数众多、包含大量可选参数的窗口函数中极为关键。
// 指导编译期提示与元数据导出：它能明确反馈每个参数的标准预期类型。这常用于给上层应用（如 SQL 编辑器、IDE 插件）提供精准的智能感知、自动补全、或者在通过 DESCRIBE FUNCTION 查看函数描述时导出标准的函数签名。
public interface SqlOperandMetadata extends SqlOperandTypeChecker {
  //~ Methods ----------------------------------------------------------------

  /** Returns the types of the parameters. */
  // 返回值：List<RelDataType>（一个按顺序排列的、代表各个参数预期数据类型的列表）。
  // 声明该函数每一个位置上参数的“官方标准类型”。
  List<RelDataType> paramTypes(RelDataTypeFactory typeFactory);

  /** Returns the names of the parameters. */
  // 声明该函数每一个位置上参数的“官方名字”。
  // 返回值顺序必须与 paramTypes 的返回顺序完全一一对应。
  // 具名绑定（Named Binding）的幕后功臣：在现代 SQL 语法中，如果用户不想按照顺序传参，而是通过类似 TIMECOL => DESCRIPTOR(ts) 这样的显式指定赋值时，Calcite 的解析绑定器就会调用 paramNames() 拿到这个官方名字大纲，
  // 在内部通过名字检索（如映射到索引 1）完成参数重排和正确对齐。如果没有实现这个方法，SQL 就只能死板地按照位置顺序（Positional）来传参。
  List<String> paramNames();
}
