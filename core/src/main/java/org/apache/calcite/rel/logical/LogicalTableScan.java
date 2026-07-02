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
package org.apache.calcite.rel.logical;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelInput;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.schema.Table;

import com.google.common.collect.ImmutableList;

import java.util.List;

/**
 * A <code>LogicalTableScan</code> reads all the rows from a
 * {@link RelOptTable}.
 *
 * <p>If the table is a <code>net.sf.saffron.ext.JdbcTable</code>, then this is
 * literally possible. But for other kinds of tables, there may be many ways to
 * read the data from the table. For some kinds of table, it may not even be
 * possible to read all of the rows unless some narrowing constraint is applied.
 *
 * <p>In the example of the <code>net.sf.saffron.ext.ReflectSchema</code>
 * schema,
 *
 * <blockquote>
 * <pre>select from fields</pre>
 * </blockquote>
 *
 * <p>cannot be implemented, but
 *
 * <blockquote>
 * <pre>select from fields as f
 * where f.getClass().getName().equals("java.lang.String")</pre>
 * </blockquote>
 * <p>can. It is the optimizer's responsibility to find these ways, by applying
 * transformation rules.
 */
// TableScan 是扫表算子的抽象基类。而 LogicalTableScan 则是它的纯逻辑形态实现，属于 Convention.NONE（无物理执行契约流派）
// 核心职责是：
// 关系代数树的起点：它是 SQL 语句（如 SELECT * FROM emp）刚被解析、校验（Validate）完成并转化为关系代数树（RelNode Tree）时，最先被实例化出来的叶子节点。
// 解除底层绑定：它仅代表“我们要扫描这张表”的逻辑意图，而不关心这张表到底存在于 MySQL、HBase、还是一个 CSV 文件中。
// 留给优化器转换：它就像一块未雕琢的璞玉。在随后的优化阶段，Calcite 的 VolcanoPlanner 或 HepPlanner 会利用规则（Rules），把它转换成具体的物理扫表算子（例如 JdbcTableScan 或 BindableTableScan）去真正读取数据。

public final class LogicalTableScan extends TableScan {
  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a LogicalTableScan.
   *
   * <p>Use {@link #create} unless you know what you're doing.
   */
  public LogicalTableScan(RelOptCluster cluster, RelTraitSet traitSet,
      List<RelHint> hints, RelOptTable table) {
    super(cluster, traitSet, hints, table);
  }

  @Deprecated // to be removed before 2.0
  public LogicalTableScan(RelOptCluster cluster, RelTraitSet traitSet,
      RelOptTable table) {
    this(cluster, traitSet, ImmutableList.of(), table);
  }

  @Deprecated // to be removed before 2.0
  public LogicalTableScan(RelOptCluster cluster, RelOptTable table) {
    this(cluster, cluster.traitSetOf(Convention.NONE), ImmutableList.of(), table);
  }

  /**
   * Creates a LogicalTableScan by parsing serialized output.
   */
  public LogicalTableScan(RelInput input) {
    super(input);
  }

  @Override public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    assert traitSet.containsIfApplicable(Convention.NONE);
    assert inputs.isEmpty();
    return this;
  }

  /** Creates a LogicalTableScan.
   *
   * @param cluster     Cluster
   * @param relOptTable Table
   * @param hints       The hints
   */
  // 不仅是创建逻辑扫表算子的标准入口，更是 Calcite 在算子树构建之初，进行底层物理特征（Traits）向上捕捉与传递的经典范例
  public static LogicalTableScan create(RelOptCluster cluster,
      final RelOptTable relOptTable, List<RelHint> hints) {
    // 利用了设计模式中的包装器模式（Wrapper Pattern）。这行代码就像撕开包装纸一样，把高层的封装剥离，
    // 拿到最底层、由具体数据源适配器（如 JDBC、Cassandra、File 等）实现的原始元数据对象 Table
    final Table table = relOptTable.unwrap(Table.class);
    final RelTraitSet traitSet =
        // 首先，由于这是逻辑算子（Logical），它的执行流派特质（Convention）毫无疑问被贴上 NONE 标签，代表它目前还不属于任何具体的物理引擎。
        cluster.traitSetOf(Convention.NONE)
            // 捕捉并向下继承底层表的天然排序特征（Collation）。
            // 如果这张表在底层确实拥有天然的排序（比如按 id 升序），这个方法会在逻辑扫表算子刚诞生的一瞬间，把这个物理排序特质精准地捕捉到，并强行注入到 LogicalTableScan 的 traitSet 中！
            .replaceIfs(RelCollationTraitDef.INSTANCE, () -> {
              if (table != null) {
                return table.getStatistic().getCollations();
              }
              return ImmutableList.of();
            });
    return new LogicalTableScan(cluster, traitSet, hints, relOptTable);
  }

  @Override public RelNode withHints(List<RelHint> hintList) {
    return new LogicalTableScan(getCluster(), traitSet, hintList, table);
  }
}
