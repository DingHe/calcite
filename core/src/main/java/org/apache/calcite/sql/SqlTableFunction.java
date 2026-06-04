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

import org.apache.calcite.sql.type.SqlReturnTypeInference;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A function that returns a table.
 */
// 专门用来定义 SQL 表函数（Table Function，也常被称为 UDF / TVF - Table-Valued Function） 的元数据行为。
// 在标准 SQL 中，普通的标量函数（如 ABS(x) 或 LENGTH(str)）通常是“输入一行，返回一个值”。而 表函数（Table Function） 是一种特殊的函数，它允许你传入一堆参数，然后像查询一张物理表一样返回一个完整的“结果集/关系表”（多行多列）。
// 例如在 SQL 语句中：
// SELECT * FROM TABLE(MY_TABLE_FUNCTION(10, 'marketing'))
// SqlTableFunction 接口正是为了定义这类函数的底层框架行为。它的核心作用有两点：
// 行类型动态推导（Row Type Inference）：
// 表函数返回的是一张虚拟表，优化器在编译期必须知道这张表长什么样（包含哪些列，每列是什么数据类型）。因为这个接口定义的方法能返回类型推导策略，使 Calcite 的 SqlValidator（校验器）在遇到这类函数时，可以根据传入的参数值（字面量）或默认值，动态推导并确立返回表的 Schema 大纲。
// 多态表函数参数支持（Polymorphic Table Functions - PTF）：
// 先进的 SQL 引擎通常允许表函数的入参本身就是“另外一张表”（比如一些窗口滚动函数或跨表数据清洗函数）。该接口能够声明函数的第 $n$ 个参数是否为一张“表参数”，以及该表参数具备什么样的物理特性（如分区、排序等）。
public interface SqlTableFunction {
  /**
   * Returns the record type of the table yielded by this function when
   * applied to given arguments. Only literal arguments are passed,
   * non-literal are replaced with default values (null, 0, false, etc).
   *
   * @return strategy to infer the row type of a call to this function
   */
  // 获取该表函数的行结构推导机。
  SqlReturnTypeInference getRowTypeInference();

  /**
   * Returns the table parameter characteristics for <code>ordinal</code>th
   * parameter to this table function.
   *
   * <p>Returns <code>null</code> if the <code>ordinal</code>th argument is
   * not table parameter or the <code>ordinal</code> is smaller than 0 or
   * the <code>ordinal</code> is greater than or equals to the number of
   * parameters.
   */
  // 入参 int ordinal：代表函数参数的索引位置（从 0 开始计数）。例如传入 2 代表关注函数的第 3 个参数。
  // 返回值：@Nullable TableCharacteristic（表参数特征描述符）。如果返回 null，说明该位置只是一个常规的标量参数（如普通的 INT 或 VARCHAR），而不是表。
  default @Nullable TableCharacteristic tableCharacteristic(int ordinal) {
    return null;
  }
}
