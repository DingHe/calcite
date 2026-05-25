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
package org.apache.calcite.adapter.jdbc;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.hint.RelHint;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Objects;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

import static java.util.Objects.requireNonNull;

/**
 * Relational expression representing a scan of a table in a JDBC data source.
 */
// 在 Calcite 的多数据源架构中，JdbcTableScan 属于 JdbcConvention（JDBC 物理执行流派） 的算子。它是整棵 JDBC 物理执行计划树的叶子节点（最底层的源头）
// 定义底层关系型数据库的数据源入口：它代表对远程外部关系型数据库（如 MySQL、Oracle、PostgreSQL、SQL Server 等）中某张物理表的全表扫描意图。
// 作为 SQL 逆向转译的基石：它自身不直接包含读取底层的 Java 代码，而是作为一个“账本标记”。当 Calcite 决定将计算下推给底层数据库执行时，它会配合上层的物理算子（如 JdbcFilter、JdbcProject），通过特定的实现机制，自底向上将整棵算子树逆向翻译（Decompile）回一条标准的底层 SQL 字符串。

public class JdbcTableScan extends TableScan implements JdbcRel {
  // 直击底层的、真正的 JDBC 物理表元数据实体。
  // table 属性是 Calcite 高层通用的包装，而 jdbcTable 则是解开包装后的、
  // 具体的 JDBC 实现类（org.apache.calcite.adapter.jdbc.JdbcTable）。通过它，可以拿到该表在远程数据库中的物理表名（tableName()）、所属的物理数据源（DataSource）以及对应的 SQL 方言（SqlDialect）等底层物理执行不可或缺的参数。
  public final JdbcTable jdbcTable;

  protected JdbcTableScan(
      RelOptCluster cluster,
      List<RelHint> hints,
      RelOptTable table,
      JdbcTable jdbcTable,
      JdbcConvention jdbcConvention) {
    super(cluster, cluster.traitSetOf(jdbcConvention), hints, table);
    this.jdbcTable = Objects.requireNonNull(jdbcTable, "jdbcTable");
  }

  @Override public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    assert inputs.isEmpty();
    return new JdbcTableScan(
        getCluster(), getHints(), table, jdbcTable, (JdbcConvention) castNonNull(getConvention()));
  }

  @Override public JdbcImplementor.Result implement(JdbcImplementor implementor) {
    return implementor.result(jdbcTable.tableName(),
        ImmutableList.of(JdbcImplementor.Clause.FROM), this, null);
  }

  @Override public RelNode withHints(List<RelHint> hintList) {
    Convention convention = requireNonNull(getConvention(), "getConvention()");
    return new JdbcTableScan(getCluster(), hintList, getTable(), jdbcTable,
        (JdbcConvention) convention);
  }
}
