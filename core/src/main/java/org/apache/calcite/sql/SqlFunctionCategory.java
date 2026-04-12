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

import org.apache.calcite.util.Util;

import java.util.Arrays;
import java.util.EnumSet;

import static org.apache.calcite.sql.SqlFunctionCategory.Property.FUNCTION;
import static org.apache.calcite.sql.SqlFunctionCategory.Property.SPECIFIC;
import static org.apache.calcite.sql.SqlFunctionCategory.Property.TABLE_FUNCTION;
import static org.apache.calcite.sql.SqlFunctionCategory.Property.USER_DEFINED;

/**
 * Enumeration of the categories of
 * SQL-invoked routines.
 */
// SqlFunctionCategory 是一个枚举类，它为 SQL 解析和验证过程中的“函数分类”定义了标准。
// 核心作用是：对 SQL 调用例程（Routine）进行语义分类，并定义这些类别的属性。
// 在 SQL 验证阶段，当验证器（Validator）在 SqlOperatorTable 中寻找函数时，它不仅通过名字找，还会通过 SqlFunctionCategory 过滤。
// 区分内置与自定义：帮助系统判断一个函数是数据库自带的（如 ABS）还是用户后来定义的（UDF）。
// 区分返回类型形态：区分是返回单行值的标量函数（Scalar Function）还是返回结果集的表函数（Table Function）。
// 优化查找效率：在进行函数重载解析（Overload Resolution）时，分类可以快速排除不相关的函数定义。
public enum SqlFunctionCategory {
  // 字符串处理内置函数（如 SUBSTR）
  STRING("STRING", "String function", FUNCTION),
  // 数值处理内置函数（如 ABS）
  NUMERIC("NUMERIC", "Numeric function", FUNCTION),
  // 日期时间内置函数（如 NOW）
  TIMEDATE("TIMEDATE", "Time and date function", FUNCTION),
  // 系统级内置函数（如 USER）
  SYSTEM("SYSTEM", "System function", FUNCTION),
  // 普通用户自定义标量函数 (UDF)
  USER_DEFINED_FUNCTION("UDF", "User-defined function", USER_DEFINED,
      FUNCTION),
  // 用户自定义存储过程 (UDP)
  USER_DEFINED_PROCEDURE("UDP", "User-defined procedure", USER_DEFINED),
  // 用户自定义构造函数 (UDC)
  USER_DEFINED_CONSTRUCTOR("UDC", "User-defined constructor", USER_DEFINED),
  // 带有特定名称的 UDF
  USER_DEFINED_SPECIFIC_FUNCTION("UDF_SPECIFIC",
      "User-defined function with SPECIFIC name", USER_DEFINED, SPECIFIC,
      FUNCTION),
  // 用户自定义表函数 (UDTF)
  USER_DEFINED_TABLE_FUNCTION("TABLE_UDF", "User-defined table function",
      USER_DEFINED, TABLE_FUNCTION),
  // 带有特定名称的 UDTF
  USER_DEFINED_TABLE_SPECIFIC_FUNCTION("TABLE_UDF_SPECIFIC",
      "User-defined table function with SPECIFIC name", USER_DEFINED,
      TABLE_FUNCTION, SPECIFIC),
  // 用于 MATCH_RECOGNIZE 子句的特殊表函数
  MATCH_RECOGNIZE("MATCH_RECOGNIZE", "MATCH_RECOGNIZE function", TABLE_FUNCTION);

  @SuppressWarnings("ImmutableEnumChecker")
  private final EnumSet<Property> properties;

  SqlFunctionCategory(String abbrev, String description,
      Property... properties) {
    Util.discard(abbrev);
    Util.discard(description);
    this.properties = EnumSet.copyOf(Arrays.asList(properties));
  }

  public boolean isUserDefined() {
    return properties.contains(USER_DEFINED);
  }

  public boolean isTableFunction() {
    return properties.contains(TABLE_FUNCTION);
  }

  public boolean isFunction() {
    return properties.contains(FUNCTION);
  }

  public boolean isSpecific() {
    return properties.contains(SPECIFIC);
  }

  public boolean isUserDefinedNotSpecificFunction() {
    return isUserDefined()
        && (isFunction() || isTableFunction())
        && !isSpecific();
  }

  /**
   * Property of a SqlFunctionCategory.
   */
  // 存储当前分类所具备的所有特征标签。
  enum Property {
    USER_DEFINED, TABLE_FUNCTION, SPECIFIC, FUNCTION
  }
}
