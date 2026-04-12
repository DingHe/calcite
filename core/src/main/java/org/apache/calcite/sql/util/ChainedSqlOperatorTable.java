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
package org.apache.calcite.sql.util;

import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.validate.SqlNameMatcher;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * ChainedSqlOperatorTable implements the {@link SqlOperatorTable} interface by
 * chaining together any number of underlying operator table instances.
 *
 * <p>To create, call {@link SqlOperatorTables#chain}.
 */
// 在 Apache Calcite 项目中，ChainedSqlOperatorTable 是 SqlOperatorTable 接口的一个非常实用且强大的实现类。它采用了 组合模式（Composite Pattern），将多个算子表组合在一起工作。
// 该类的核心作用是：将多个独立的算子表“串联”起来，形成一个统一的逻辑表。
// 在复杂的 SQL 引擎中，我们往往需要同时支持多种来源的函数，例如：
// 标准函数（SqlStdOperatorTable）
// 方言特定函数（如 OracleSqlOperatorTable）
// 用户自定义函数（UDF）
public class ChainedSqlOperatorTable implements SqlOperatorTable {
  //~ Instance fields --------------------------------------------------------

  protected final List<SqlOperatorTable> tableList;

  //~ Constructors -----------------------------------------------------------

  @Deprecated // to be removed before 2.0
  public ChainedSqlOperatorTable(List<SqlOperatorTable> tableList) {
    this(ImmutableList.copyOf(tableList));
  }

  /** Internal constructor; call {@link SqlOperatorTables#chain}. */
  protected ChainedSqlOperatorTable(ImmutableList<SqlOperatorTable> tableList) {
    this.tableList = ImmutableList.copyOf(tableList);
  }

  //~ Methods ----------------------------------------------------------------

  @Deprecated // to be removed before 2.0
  public void add(SqlOperatorTable table) {
    if (!tableList.contains(table)) {
      tableList.add(table);
    }
  }

  @Override public void lookupOperatorOverloads(SqlIdentifier opName,
      @Nullable SqlFunctionCategory category, SqlSyntax syntax,
      List<SqlOperator> operatorList, SqlNameMatcher nameMatcher) {
    for (SqlOperatorTable table : tableList) {
      table.lookupOperatorOverloads(opName, category, syntax, operatorList,
          nameMatcher);
    }
  }

  @Override public List<SqlOperator> getOperatorList() {
    List<SqlOperator> list = new ArrayList<>();
    for (SqlOperatorTable table : tableList) {
      list.addAll(table.getOperatorList());
    }
    return list;
  }
}
