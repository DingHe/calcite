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
package org.apache.calcite.plan;

import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.ColumnStrategy;
import org.apache.calcite.schema.Wrapper;
import org.apache.calcite.util.ImmutableBitSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Represents a relational dataset in a {@link RelOptSchema}. It has methods to
 * describe and implement itself.
 */
// 在 Apache Calcite 的架构中，RelOptTable 是一个处于核心地位的接口。
// 如果说 SqlValidatorTable 是校验阶段对表的抽象，那么 RelOptTable 就是**优化阶段（Optimization）**对表的抽象。
// 它是查询优化器（Planner/Optimizer）感知数据的“情报站”。
// RelOptTable 代表了 RelOptSchema（关系优化架构）中的一个数据集。它的主要任务是为优化器提供决策所需的代价信息和物理属性。
// 代价建模的依据：提供行数估计、统计信息，帮助优化器计算 Join 顺序或选择索引。
// 物理属性的描述：描述数据在磁盘或集群中的分布（Distribution）和排序（Collation）情况。
// 桥梁作用：负责将逻辑上的“表”定义，转化为优化器可以处理的“关系表达式节点”（RelNode）。
// RelOptTable 和 Table 之间的关系可以概括为：Table 是元数据层的定义，而 RelOptTable 是优化器层的包装。
// org.apache.calcite.schema.Table (元数据层)
// 最底层的表定义。描述了数据的物理存在方式（如表是什么类型、统计信息如何）。当你实现自定义数据源（如接入 ClickHouse 或自定义文件系统）时，你需要实现这个接口。它相对“静态”，主要负责与具体的外部数据存储对接。
// org.apache.calcite.plan.RelOptTable (优化器层)
// 优化器专用的表引用。它在 Table 的基础上，增加了大量为了生成执行计划而需要的“动态”信息和方法（如 toRel 方法用于将表转为关系节点）。属于优化阶段，是 RelOptPlanner 直接操作的对象。
// RelOptTable 通常是 Table 的一个逻辑包装器。在 Calcite 的默认实现中，当你通过 SqlValidator 校验完 SQL 并进入优化阶段时，Calcite 会将 Table 包装成 RelOptTable。
public interface RelOptTable extends Wrapper {
  //~ Methods ----------------------------------------------------------------

  /**
   * Obtains an identifier for this table. The identifier must be unique with
   * respect to the Connection producing this table.
   * @return qualified name
   */
  // 获取表的全限定路径名。
  // 返回一个列表，通常形如 [catalog, schema, table]。这是表在当前连接上下文中的唯一标识。
  List<String> getQualifiedName();

  /**
   * Returns an estimate of the number of rows in the table.
   */
  // 获取表定义的行类型。
  // 包含列名、数据类型、是否可为空等信息。这是优化过程中进行类型推导的基础。
  double getRowCount();

  /**
   * Describes the type of rows returned by this table.
   */
  // 获取表定义的行类型。
  // 包含列名、数据类型、是否可为空等信息。这是优化过程中进行类型推导的基础。
  RelDataType getRowType();

  /**
   * Returns the {@link RelOptSchema} this table belongs to.
   */
  // 获取该表所属的优化架构。
  // 返回表所在的 RelOptSchema 对象，用于在同一个架构内查找其他相关联的表或元数据。
  @Nullable RelOptSchema getRelOptSchema();

  /**
   * Converts this table into a {@link RelNode relational expression}.
   * <p>The {@link org.apache.calcite.plan.RelOptPlanner planner} calls this
   * method to convert a table into an initial relational expression,
   * generally something abstract, such as a
   * {@link org.apache.calcite.rel.logical.LogicalTableScan},
   * then optimizes this expression by
   * applying {@link org.apache.calcite.plan.RelOptRule rules} to transform it
   * into more efficient access methods for this table.
   */
  // 将表转换为关系表达式。
  // 核心方法。它通常将表包装成一个 LogicalTableScan。优化器通过此方法开始构建初始的关系表达式树。
  RelNode toRel(ToRelContext context);

  /**
   * Returns a description of the physical ordering (or orderings) of the rows
   * returned from this table.
   * @see RelMetadataQuery#collations(RelNode)
   */
  // 描述数据的物理排序。
  // 如果数据在底层存储中是按某列排序的（如聚簇索引），优化器可以利用这一点消除后续的 Sort 操作。
  @Nullable List<RelCollation> getCollationList();

  /**
   * Returns a description of the physical distribution of the rows
   * in this table.
   * @see RelMetadataQuery#distribution(RelNode)
   */
  // 描述数据在分布式系统中的物理分布方式。
  // 例如是哈希分布（Hash）、广播（Broadcast）还是随机分布（Random），这决定了分布式 Join 时的 Shuffle 代价。
  @Nullable RelDistribution getDistribution();

  /**
   * Returns whether the given columns are a key or a superset of a unique key
   * of this table.
   * @param columns Ordinals of key columns
   * @return Whether the given columns are a key or a superset of a key
   */
  // 判定指定的列集合是否为唯一键（Key）。
  // 优化器可以利用此信息进行聚合消除或 Join 优化。
  boolean isKey(ImmutableBitSet columns);

  /**
   * Returns a list of unique keys, empty list if no key exist,
   * the result should be consistent with {@code isKey}.
   */
  // 获取该表定义的所有唯一键。
  // 返回所有候选键的集合列表，用于全局优化决策。
  @Nullable List<ImmutableBitSet> getKeys();

  /**
   * Returns the referential constraints existing for this table. These constraints
   * are represented over other tables using {@link RelReferentialConstraint} nodes.
   */
  // 获取外键等引用约束。
  // 描述表与表之间的参照完整性，可用于某些高级转换规则（如消除不必要的 Join）。
  @Nullable List<RelReferentialConstraint> getReferentialConstraints();

  /**
   * Generates code for this table.
   *
   * @param clazz The desired collection class; for example {@code Queryable}.
   * @return the code for the table, or null if code generation is not supported
   */
  // 生成用于 Linq4j 执行层访问该表的 Java 代码表达式。
  // 如果 Calcite 被配置为生成代码运行，此方法返回如何获取数据集的表达式（如 TableQueryable）。
  @Nullable Expression getExpression(Class clazz);

  /** Returns a table with the given extra fields.
   * <p>The extended table includes the fields of this base table plus the
   * extended fields that do not have the same name as a field in the base
   * table.
   */
  // 扩展表的列定义。
  // 返回一个新的表对象，该对象包含原始列加上指定的额外列（常用于 HBase 或 Phoenix 等支持动态列的引擎）
  RelOptTable extend(List<RelDataTypeField> extendedFields);
  // 获取每一列的生成策略。
  // 描述每一列是如何生成的（如：是否是虚拟列、是否有默认值、是否必须插入）。
  /** Returns a list describing how each column is populated. The list has the
   * same number of entries as there are fields, and is immutable. */
  List<ColumnStrategy> getColumnStrategies();

  /** Can expand a view into relational expressions. */
  // 为了支持视图（View）的展开和转换，RelOptTable 定义了两个关键的内部接口：
  // 将 SQL 视图字符串展开为关系表达式树。
  // 应用：当 toRel 发现当前表实际上是一个视图时，会调用此接口将视图的定义 SQL 解析并转换为 RelNode。
  interface ViewExpander {
    /**
     * Returns a relational expression that is to be substituted for an access
     * to a SQL view.
     *
     * @param rowType Row type of the view  视图行数据的类型
     * @param queryString Body of the view  视图的代码
     * @param schemaPath Path of a schema wherein to find referenced tables
     * @param viewPath Path of the view, ending with its name; may be null
     * @return Relational expression
     */
    RelRoot expandView(RelDataType rowType, String queryString,
        List<String> schemaPath, @Nullable List<String> viewPath);
  }
  // 把表转为关系表达式的上下文
  /** Contains the context needed to convert a a table into a relational
   * expression. */
  interface ToRelContext extends ViewExpander {
    // 获取当前的优化集群（RelOptCluster），其中包含环境配置。
    RelOptCluster getCluster();

    /**
     * Returns the table hints of the table to convert,
     * usually you can use the hints to pass along some dynamic params.
     *
     * @return the hints attached to the table, never null
     */
    // 获取 SQL 中针对该表的 Hint（暗示），如 /*+ INDEX(t1 idx_a) */，用于精细化控制优化行为。
    List<RelHint> getTableHints();
  }
}
