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

import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.sql.SqlDialect;

/**
 * Calling convention for relational operations that occur in a JDBC
 * database.
 *
 * <p>The convention is a slight misnomer. The operations occur in whatever
 * data-flow architecture the database uses internally. Nevertheless, the result
 * pops out in JDBC.
 *
 * <p>This is the only convention, thus far, that is not a singleton. Each
 * instance contains a JDBC schema (and therefore a data source). If Calcite is
 * working with two different databases, it would even make sense to convert
 * from "JDBC#A" convention to "JDBC#B", even though we don't do it currently.
 * (That would involve asking database B to open a database link to database
 * A.)
 * <p>As a result, converter rules from and to this convention need to be
 * instantiated, at the start of planning, for each JDBC database in play.
 */
// JdbcConvention 是 JDBC 适配器（JDBC Adapter）的核心。它代表了一种特殊的物理特征：该算子及其子树将在远程关系型数据库中执行。
// JdbcConvention 的本质是下推（Push-down）。
// 标识存储边界： 它告诉优化器，标记为该约定的算子不是由 Calcite 引擎在内存中执行的，而是会被转换成特定数据库的 SQL 语句（如 MySQL, Oracle, PostgreSQL），发送到远程数据库执行。
// 非单例设计： 与 EnumerableConvention 不同，JdbcConvention 不是单例。每一个不同的数据源（DataSource）都会对应一个 JdbcConvention 实例。这允许 Calcite 同时处理多个不同的数据库（例如：从 Oracle 读取数据并与 MySQL 数据进行 Join）。
// 规则注入： 它是相关优化规则的载体。当 JdbcConvention 被注册到优化器时，它会带入一系列能够将逻辑算子（如 LogicalFilter）转换为 JDBC 物理算子（如 JdbcFilter）的规则。
public class JdbcConvention extends Convention.Impl {
  /** Cost of a JDBC node versus implementing an equivalent node in a "typical"
   * calling convention. */
  // 其值小于 1.0（相比之下 Enumerable 是 1.0）。
  // 这是一种贪心策略：它告诉优化器，在远程数据库中执行过滤、聚合等操作通常比把原始数据拉取到 Calcite 内存中执行更便宜（因为减少了网络传输且利用了索引）。优化器会因此倾向于尽可能多地将算子下推到 JDBC 约定中。
  public static final double COST_MULTIPLIER = 0.8d;
  // SQL 方言定义。
  // 存储了目标数据库的特性（例如：Top 还是 Limit，引号如何转义等）。在最后生成 SQL 字符串时，会根据这个属性来适配不同的数据库语法。
  public final SqlDialect dialect;
  // Java 表达式引用。
  // 在生成的代码中，该属性指向该 JDBC Schema 的表达式。这主要用于在代码生成阶段，让 Calcite 知道如何获取对应的 DataSource 或连接对象。
  public final Expression expression;

  public JdbcConvention(SqlDialect dialect, Expression expression,
      String name) {
    super("JDBC." + name, JdbcRel.class);
    this.dialect = dialect;
    this.expression = expression;
  }

  public static JdbcConvention of(SqlDialect dialect, Expression expression,
      String name) {
    return new JdbcConvention(dialect, expression, name);
  }
  // 核心钩子方法，注册优化规则。
  // 当优化器发现使用了这个 JDBC 数据源时，会调用此方法：
  // 注入 JDBC 专用规则： 调用 JdbcRules.rules(this)。这些规则负责将 LogicalProject 变成 JdbcProject，将 LogicalJoin 变成 JdbcJoin 等。因为这些规则需要引用当前的 JdbcConvention 实例，所以必须动态生成。
  @Override public void register(RelOptPlanner planner) {
    for (RelOptRule rule : JdbcRules.rules(this)) {
      planner.addRule(rule);
    }
    planner.addRule(CoreRules.FILTER_SET_OP_TRANSPOSE);
    planner.addRule(CoreRules.PROJECT_REMOVE);
  }
}
