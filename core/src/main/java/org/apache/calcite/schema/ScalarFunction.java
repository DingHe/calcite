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

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;

/**
 * Function that returns a scalar result.
 */
// ScalarFunction 代表的是 “一行输入，一个结果” 的函数。
// 它是 SQL 中最典型的函数类型，例如 ABS(x)、UPPER(str)、1 + 1 等。这类函数的特点是：
// 位置限制：通常出现在 SELECT 列表、WHERE 子句或 ORDER BY 子句中。
// 返回值：对于每一行数据，它总是返回一个标量值（即单个数据项，如一个整数、一个字符串或一个布尔值）。
public interface ScalarFunction extends Function {
  /**
   * Returns the return type of this function, constructed using the given
   * type factory.
   *
   * @param typeFactory Type factory
   */
  // 获取该标量函数的返回结果类型。
  RelDataType getReturnType(RelDataTypeFactory typeFactory);
}
