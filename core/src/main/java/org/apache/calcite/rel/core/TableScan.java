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
package org.apache.calcite.rel.core;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.AbstractRelNode;
import org.apache.calcite.rel.RelInput;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttle;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.hint.Hintable;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.ImmutableIntList;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Relational operator that returns the contents of a table.
 */
// 继承自 AbstractRelNode（关系代数表达式抽象类），并实现了 Hintable 接口（支持 SQL 提示）
// 在关系代数中，TableScan 对应的是 扫表操作（Scan/Read），也就是 SQL 语句中的 FROM my_table。
// 核心职责是：
// 叶子节点定位：它是任何一棵物理或逻辑执行计划树（RelNode Tree）的终点与数据源头（叶子节点）。它不依赖任何其他算子作为输入（没有子节点）。
// 连接元数据与计算层：它将外部真实的存储系统（如 MySQL, HDFS 中的 Parquet 文件, HBase 等）注册的元数据表（RelOptTable），引入到 Calcite 的计算层和优化器中。
// 提供数据基数（Cardinality）：它为上层的 Filter、Join 等算子提供最基础的原始行数和开销估计，是 CBO 代价引擎计算的核心基准。
public abstract class TableScan
    extends AbstractRelNode implements Hintable {
  //~ Instance fields --------------------------------------------------------

  /**
   * The table definition.
   */
  // 当前扫表算子所绑定的元数据表定义实体。
  // 是 Calcite 连通外部数据源的桥梁。通过这个对象，
  // 可以获取表的全局唯一限定名（如 [catalog, schema, table]）、数据表的 Schema 结构、列的信息，以及底层引擎提供的统计信息（如总行数、列的基数等）。
  protected final RelOptTable table;

  /**
   * The table hints.
   */
  // 当前扫表算子持有的 SQL 提示（Hints）列表
  // 物理含义：对应用户在 SQL 语句中针对该表显式指定的提示。例如 SELECT * FROM my_table /*+ INDEX(idx_user) */。
  // Calcite 在解析阶段会把这些提示封装为 RelHint 放入该不可变列表中，供底层特定的物理引擎（如 JDBC 适配器）在最终生成物理执行计划时参考。
  protected final ImmutableList<RelHint> hints;

  //~ Constructors -----------------------------------------------------------

  // 除了基本的非空校验和属性赋值外，它会主动通过 table 拿到对应的元数据 Schema 对象 RelOptSchema。
  // 如果存在，会自动将其注册到当前优化器（Planner）中。这确保了优化器在后续进行规则匹配和改写时，能够感知到该 Schema 下的其他表和元数据。
  protected TableScan(RelOptCluster cluster, RelTraitSet traitSet,
      List<RelHint> hints, RelOptTable table) {
    super(cluster, traitSet);
    this.table = Objects.requireNonNull(table, "table");
    RelOptSchema relOptSchema = table.getRelOptSchema(); //通过RelOptTable获取schema
    if (relOptSchema != null) {
      cluster.getPlanner().registerSchema(relOptSchema); //在优化器中注册schema
    }
    this.hints = ImmutableList.copyOf(hints);
  }

  @Deprecated // to be removed before 2.0
  protected TableScan(RelOptCluster cluster, RelTraitSet traitSet,
      RelOptTable table) {
    this(cluster, traitSet, ImmutableList.of(), table);
  }

  /**
   * Creates a TableScan by parsing serialized output.
   */
  // 用于从序列化媒介（如 JSON 格式的执行计划字符串）中重新解析并还原出扫表算子节点。内部通过 input.getTable("table") 动态反序列化出元数据表对象。
  protected TableScan(RelInput input) {
    this(input.getCluster(), input.getTraitSet(), ImmutableList.of(), input.getTable("table"));
  }

  //~ Methods ----------------------------------------------------------------
  // 估算当前扫表算子会输出多少行数据（行数基数评估）
  @Override public double estimateRowCount(RelMetadataQuery mq) {
    return table.getRowCount();
  }
  // 多态获取当前算子绑定的元数据表对象
  @Override public RelOptTable getTable() {
    return table;
  }
  // 计算当前扫表算子自身的执行代价（Rows, CPU, IO）
  // 代价模型认为，全表扫描的行数就是其最大的代价
  @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
      RelMetadataQuery mq) {
    double dRows = mq.getRowCount(this);
    double dCpu = dRows + 1; // ensure non-zero cost
    double dIo = 0;
    return planner.getCostFactory().makeCost(dRows, dCpu, dIo);
  }
  // 推导并确定当前算子输出的行类型（Schema）
  @Override public RelDataType deriveRowType() {
    return table.getRowType();
  }
  // 生成一个与目标表字段数完全对齐的、自增的恒等索引序列（Identity Projection）
  // 例如目标表有 3 列，则返回一个不可变的整数列表 [0, 1, 2]。通常用于快速表示“全列投影”的场景。
  /** Returns an identity projection for the given table. */
  public static ImmutableIntList identity(RelOptTable table) {
    return ImmutableIntList.identity(table.getRowType().getFieldCount());
  }

  /** Returns an identity projection. */
  public ImmutableIntList identity() {
    return identity(table);
  }

  @Override public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .item("table", table.getQualifiedName());
  }

  /**
   * Projects a subset of the fields of the table, and also asks for "extra"
   * fields that were not included in the table's official type.
   *
   * <p>The default implementation assumes that tables cannot do either of
   * these operations, therefore it adds a {@link Project} that projects
   * {@code NULL} values for the extra fields, using the
   * {@link RelBuilder#project(Iterable)} method.
   *
   * <p>Sub-classes, representing table types that have these capabilities,
   * should override.
   * @param fieldsUsed  Bitmap of the fields desired by the consumer
   * @param extraFields Extra fields, not advertised in the table's row-type,
   *                    wanted by the consumer
   * @param relBuilder Builder used to create a Project
   * @return Relational expression that projects the desired fields
   */
  // 当上层算子只需要这张表的“某几列”（列裁剪/Column Pruning），或者额外索要一些“非官方声明的衍生列”（Extra Fields）时，该方法负责将这些需求组装并转换成全新的关系代数子树。
  // 为什么要提供这个方法？
  // 在查询优化阶段，列裁剪是最有效的黄金法则之一。如果一张表有 100 列，但你的 SQL 只写了 SELECT name, age FROM emp，那么剩下的 98 列在后续的 Join、Filter 中完全是内存负担，应该尽早丢弃。
  // 兜底策略：既然底层没办法直接过滤列，那基类就主动在 TableScan 的头上硬套（Wrap）一个 Project 算子。由 TableScan 负责把数据一股脑全读出来，再由头顶的 Project 算子把不要的列扔掉，把额外的虚列补上 NULL。
  // 留给子类的生路：如果子类代表的是高级存储引擎（如 BindableTableScan、ParquetTableScan），它们能够直接在底层只读部分列，那么子类就会重写（Override）这个方法，实现真正的“下推列裁剪”，不再套顶层的 Project。
  public RelNode project(ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields,
      RelBuilder relBuilder) {
    // 关卡 1：直通车判定（无裁剪、无衍生）
    final int fieldCount = getRowType().getFieldCount();
    if (fieldsUsed.equals(ImmutableBitSet.range(fieldCount)) //如果返回全部字段，并且没有衍生字段，直接返回
        && extraFields.isEmpty()) {
      return this;
    }
    int fieldSize = fieldsUsed.size() + extraFields.size();
    final List<RexNode> exprList = new ArrayList<>(fieldSize); // 存放最终输出列的计算表达式
    final List<String> nameList = new ArrayList<>(fieldSize); // 存放最终输出列的名称
    final RexBuilder rexBuilder = getCluster().getRexBuilder(); // 表达式构建器
    final List<RelDataTypeField> fields = getRowType().getFieldList(); // 拿到表原本的官方列定义

    // Project the subset of fields.
    // 核心步骤 1：保留需要的子集列（列裁剪）
    for (int i : fieldsUsed) {
      RelDataTypeField field = fields.get(i);
      exprList.add(rexBuilder.makeInputRef(this, i)); // 核心：使用行引用指向 TableScan 自身的第 i 列
      nameList.add(field.getName()); // 保持原始列名不变
    }

    // Project nulls for the extra fields. (Maybe a sub-class table has
    // extra fields, but we don't.)
    // 核心步骤 2：为额外索要的列“垫空值”（虚列填充）
    for (RelDataTypeField extraField : extraFields) {
      exprList.add(rexBuilder.makeNullLiteral(extraField.getType())); //衍生字段默认为空
      nameList.add(extraField.getName());
    }

    return relBuilder.push(this).project(exprList, nameList).build();
  }

  @Override public RelNode accept(RelShuttle shuttle) {
    return shuttle.visit(this);
  }

  @Override public ImmutableList<RelHint> getHints() {
    return hints;
  }
}
