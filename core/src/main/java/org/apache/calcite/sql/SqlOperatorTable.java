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

import org.apache.calcite.sql.validate.SqlNameMatcher;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * SqlOperatorTable defines a directory interface for enumerating and looking up
 * SQL operators and functions.
 */
// 如果把 SqlOperator 比作工具箱里的“工具”，那么 SqlOperatorTable 就是那个存储、分类并提供检索功能的“工具架”。
// SqlOperatorTable 是一个目录接口（Directory Interface），其核心作用是：为 Calcite 的验证器（Validator）提供操作符和函数的查找服务。
// 在 SQL 解析完成后，验证器只知道一个函数的名字（例如 MY_FUNC）。但这个名字对应哪个具体的 SqlOperator 实例？它是一个内置函数还是用户自定义函数（UDF）？它支持哪些重载？这些问题都需要通过询问 SqlOperatorTable 来获得答案。

public interface SqlOperatorTable {
  //~ Methods ----------------------------------------------------------------

  /**
   * Retrieves a list of operators with a given name and syntax. For example,
   * by passing SqlSyntax.Function, the returned list is narrowed to only
   * matching SqlFunction objects.
   *
   * @param opName   name of operator
   * @param category function category to look up, or null for any matching
   *                 operator
   * @param syntax   syntax type of operator
   * @param operatorList mutable list to which to append matches
   * @param nameMatcher Name matcher
   */
  // 根据给定的名称、类别和语法类型，搜索所有匹配的操作符，并将它们添加到结果列表中。
  void lookupOperatorOverloads(
      // 操作符的名称。例如 ABS 或 COUNT。
      SqlIdentifier opName,
      // 函数的分类（如 NUMERIC, STRING, USER_DEFINED_FUNCTION 等）。如果传入 null，则表示不限定类别，查找所有同名操作符。
      @Nullable SqlFunctionCategory category,
      // 语法类型。例如 FUNCTION（函数式）、BINARY（二元运算符 +）、POSTFIX（后缀运算符）等。这有助于区分同名但语法不同的操作符。
      SqlSyntax syntax,
      // 这是一个输出参数（Mutable List）。查找到的所有匹配项都会被 add 到这个 List 中。
      // 设计为传入 List 而不是直接返回，是为了方便在多个 OperatorTable 之间级联查找（例如先找用户定义的，再找系统内置的）。
      List<SqlOperator> operatorList,
      // 名称匹配策略。它决定了查找是否大小写敏感，或者是否支持某种特定的匹配逻辑。
      SqlNameMatcher nameMatcher);

  /**
   * Retrieves a list of all functions and operators in this table. Used for
   * automated testing. Depending on the table type, may or may not be mutable.
   * @return list of SqlOperator objects
   */
  // 获取全部列表
  // 返回该表中注册的所有操作符和函数的完整列表。
  // 自动化测试：验证表中是否包含了预期的所有操作符。
  // SQL 自动补全/提示：在 IDE 或 BI 工具中列出所有可用的函数。
  List<SqlOperator> getOperatorList();
}
