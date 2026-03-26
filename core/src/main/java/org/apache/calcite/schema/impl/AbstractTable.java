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
package org.apache.calcite.schema.impl;

import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.Wrapper;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlNode;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Abstract base class for implementing {@link Table}.
 *
 * <p>Sub-classes should override {@link #isRolledUp} and
 * {@link Table#rolledUpColumnValidInsideAgg(String, SqlCall, SqlNode, CalciteConnectionConfig)}
 * if their table can potentially contain rolled up values. This information is
 * used by the validator to check for illegal uses of these columns.
 */
// AbstractTable 是一个至关重要的基类。它为开发者实现自定义表（Table）提供了一个“最小可行性”的骨架，减少了重复代码的编写。
// 默认实现提供者：Table 接口定义了许多复杂的方法。AbstractTable 为这些方法提供了最通用的默认行为（例如：默认没有统计信息、默认是普通实体表）。
// 能力桥接：它同时实现了 Table 和 Wrapper 接口。这意味着任何继承自 AbstractTable 的类都天然具备了 Calcite 的“能力发现”机制（即 unwrap 模式）。
public abstract class AbstractTable implements Table, Wrapper {
  protected AbstractTable() {
  }

  // Default implementation. Override if you have statistics.
  // 获取表的统计信息（如行数、分布情况等），用于优化器计算 Cost。
  @Override public Statistic getStatistic() {
    return Statistics.UNKNOWN;
  }
  // 默认实体表
  @Override public Schema.TableType getJdbcTableType() {
    return Schema.TableType.TABLE;
  }
  // 用于探测对象是否具备某种特定的能力。
  @Override public <C extends Object> @Nullable C unwrap(Class<C> aClass) {
    // 检查传入的 aClass 是否是当前对象（this）的实例或父类。
    if (aClass.isInstance(this)) {
      // 如果是，则直接强转并返回；否则返回 null。
      return aClass.cast(this);
    }
    return null;
  }
  // 判断指定的列是否是“上卷（Rolled up）”列。
  @Override public boolean isRolledUp(String column) {
    return false;
  }
  // 判断上卷列在聚合函数（如 SUM, COUNT）内部使用时是否合法。
  @Override public boolean rolledUpColumnValidInsideAgg(String column,
      SqlCall call, @Nullable SqlNode parent, @Nullable CalciteConnectionConfig config) {
    return true;
  }
}
