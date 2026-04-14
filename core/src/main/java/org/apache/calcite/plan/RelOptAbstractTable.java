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
import org.apache.calcite.prepare.RelOptTableImpl;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelDistributions;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.ColumnStrategy;
import org.apache.calcite.util.ImmutableBitSet;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Partial implementation of {@link RelOptTable}.
 */
// 该类的核心作用是：为自定义的 RelOptTable 实现提供一套“默认行为”或“脚手架”。
// 在 Calcite 中，实现一个完整的 RelOptTable 接口需要处理大量的元数据方法（如主键、外键、统计信息、物理分布等）。如果你正在开发一个简单的数据源，或者是一个临时的测试表，手动实现每一个方法会非常繁琐。
// RelOptAbstractTable 通过提供一系列默认实现简化了这一过程：
// 它实现了基础的属性存储（名称、行类型、Schema）。
// 它为复杂的方法（如排序、分发、约束）提供了保守的默认值（例如：默认没有排序、默认是广播分布）。
// 它是典型的模板方法模式的应用，允许子类只重写感兴趣的部分。
public abstract class RelOptAbstractTable implements RelOptTable {
  //~ Instance fields --------------------------------------------------------
  // 存储该表所属的优化架构实例。
  // 用于标识该表在哪个 RelOptSchema 中被维护，方便优化器在执行路径查找时溯源。
  protected final RelOptSchema schema;
  // 存储表的行类型定义。
  // 包含了列名和字段类型。由于是 final，一旦在构造函数中确定，该抽象表的结构即固定。
  protected final RelDataType rowType;
  // 存储表的名称。
  // 通常是简单的表名字符串。
  protected final String name;

  //~ Constructors -----------------------------------------------------------

  protected RelOptAbstractTable(
      RelOptSchema schema,
      String name,
      RelDataType rowType) {
    this.schema = schema;
    this.name = name;
    this.rowType = rowType;
  }

  //~ Methods ----------------------------------------------------------------
  // 获取表的简单名称。
  public String getName() {
    return name;
  }
  // 获取表的全限定名。
  @Override public List<String> getQualifiedName() {
    return ImmutableList.of(name);
  }
  // 返回估计行数。
  // 默认返回 100。这是一个硬编码的占位符，子类若要支持基于代价的优化（CBO），必须重写此方法以提供真实统计。
  @Override public double getRowCount() {
    return 100;
  }
  // 返回构造时传入的行类型。
  @Override public RelDataType getRowType() {
    return rowType;
  }
  // 返回构造时传入的 Schema 对象。
  @Override public RelOptSchema getRelOptSchema() {
    return schema;
  }

  // Override to define collations.
  // 获取表的物理排序信息。
  // 默认返回一个空列表（Collections.emptyList()），表示数据在物理上是无序的。
  @Override public @Nullable List<RelCollation> getCollationList() {
    return Collections.emptyList();
  }
  // 获取数据的物理分布。
  // 默认返回 BROADCAST_DISTRIBUTED（广播分布）。这在分布式计算中意味着假设数据量小到可以发送到每个节点。
  @Override public @Nullable RelDistribution getDistribution() {
    return RelDistributions.BROADCAST_DISTRIBUTED;
  }

  @Override public <T extends Object> @Nullable T unwrap(Class<T> clazz) {
    return clazz.isInstance(this)
        ? clazz.cast(this)
        : null;
  }

  // Override to define keys
  // 判断给定列集合是否为唯一键。
  @Override public boolean isKey(ImmutableBitSet columns) {
    return false;
  }

  // Override to get unique keys
  // 获取所有的唯一键定义。
  @Override public @Nullable List<ImmutableBitSet> getKeys() {
    return Collections.emptyList();
  }

  // Override to define foreign keys
  // 获取外键约束。
  // 默认返回空列表。
  @Override public @Nullable List<RelReferentialConstraint> getReferentialConstraints() {
    return Collections.emptyList();
  }
  // 将该表转换为关系表达式节点。
  @Override public RelNode toRel(ToRelContext context) {
    return LogicalTableScan.create(context.getCluster(), this,
        context.getTableHints());
  }
  // 生成 Linq4j 访问代码。
  @Override public @Nullable Expression getExpression(Class clazz) {
    return null;
  }
  // 扩展列。
  @Override public RelOptTable extend(List<RelDataTypeField> extendedFields) {
    throw new UnsupportedOperationException();
  }
  // 获取列的填充策略。
  @Override public List<ColumnStrategy> getColumnStrategies() {
    return RelOptTableImpl.columnStrategies(this);
  }

}
