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
package org.apache.calcite.sql.fun;

import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.util.ReflectiveSqlOperatorTable;

import com.google.common.base.Suppliers;

import java.util.function.Supplier;

/**
 * Operator table that contains only Oracle-specific functions and operators.
 *
 * @deprecated Use
 * {@link SqlLibraryOperatorTableFactory#getOperatorTable(SqlLibrary...)}
 * instead, passing {@link SqlLibrary#ORACLE} as argument.
 */
// 专门用于支持 Oracle 方言特定函数 的算子表类
// 该类的主要作用是：为 Calcite 提供 Oracle 数据库特有的 SQL 函数支持。
// 虽然 Calcite 默认支持标准 SQL（通过 SqlStdOperatorTable），但现实中很多业务逻辑依赖于 Oracle 的特定函数（如 DECODE, NVL 等）。
// 这个类通过继承 ReflectiveSqlOperatorTable，将这些特定的算子集中在一个表中，方便校验器（Validator）在处理 Oracle 方言的 SQL 时进行查找。
@Deprecated // to be removed before 2.0
public class OracleSqlOperatorTable extends ReflectiveSqlOperatorTable {
  //~ Static fields/initializers ---------------------------------------------

  /**
   * The table of Oracle-specific operators.
   */
  private static final Supplier<OracleSqlOperatorTable> INSTANCE =
      Suppliers.memoize(() ->
          (OracleSqlOperatorTable) new OracleSqlOperatorTable().init());

  @Deprecated // to be removed before 2.0
  public static final SqlFunction DECODE = SqlLibraryOperators.DECODE;

  @Deprecated // to be removed before 2.0
  public static final SqlFunction NVL = SqlLibraryOperators.NVL;

  @Deprecated // to be removed before 2.0
  public static final SqlFunction LTRIM = SqlLibraryOperators.LTRIM;

  @Deprecated // to be removed before 2.0
  public static final SqlFunction RTRIM = SqlLibraryOperators.RTRIM;

  @Deprecated // to be removed before 2.0
  public static final SqlFunction SUBSTR = SqlLibraryOperators.SUBSTR_ORACLE;

  @Deprecated // to be removed before 2.0
  public static final SqlFunction GREATEST = SqlLibraryOperators.GREATEST;

  @Deprecated // to be removed before 2.0
  public static final SqlFunction LEAST = SqlLibraryOperators.LEAST;

  @Deprecated // to be removed before 2.0
  public static final SqlFunction TRANSLATE3 = SqlLibraryOperators.TRANSLATE3;

  /**
   * Returns the Oracle operator table, creating it if necessary.
   */
  public static OracleSqlOperatorTable instance() {
    return INSTANCE.get();
  }
}
