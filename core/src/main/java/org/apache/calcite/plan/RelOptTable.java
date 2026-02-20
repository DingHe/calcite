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

/** RelOptTable 接口是专门为 Calcite 的查询优化器设计的，包含了更详细的统计信息，是查询优化器进行逻辑优化和物理优化时的输入
 * Represents a relational dataset in a {@link RelOptSchema}. It has methods to
 * describe and implement itself.
 */
public interface RelOptTable extends Wrapper {
  //~ Methods ----------------------------------------------------------------

  /**
   * Obtains an identifier for this table. The identifier must be unique with
   * respect to the Connection producing this table.
   * 获取表的全限定名
   * @return qualified name
   */
  List<String> getQualifiedName();

  /** 估计表的行数
   * Returns an estimate of the number of rows in the table.
   */
  double getRowCount();

  /** 行的数据类型
   * Describes the type of rows returned by this table.
   */
  RelDataType getRowType();

  /** 表的schema
   * Returns the {@link RelOptSchema} this table belongs to.
   */
  @Nullable RelOptSchema getRelOptSchema();

  /**
   * Converts this table into a {@link RelNode relational expression}.
   * 把表转为关系节点
   * <p>The {@link org.apache.calcite.plan.RelOptPlanner planner} calls this
   * method to convert a table into an initial relational expression,
   * generally something abstract, such as a
   * {@link org.apache.calcite.rel.logical.LogicalTableScan},
   * then optimizes this expression by
   * applying {@link org.apache.calcite.plan.RelOptRule rules} to transform it
   * into more efficient access methods for this table.
   */
  RelNode toRel(ToRelContext context);

  /**
   * Returns a description of the physical ordering (or orderings) of the rows
   * returned from this table.
   * 表的物理排序
   * @see RelMetadataQuery#collations(RelNode)
   */
  @Nullable List<RelCollation> getCollationList();

  /**
   * Returns a description of the physical distribution of the rows
   * in this table.
   * 表的物理分布
   * @see RelMetadataQuery#distribution(RelNode)
   */
  @Nullable RelDistribution getDistribution();

  /**
   * Returns whether the given columns are a key or a superset of a unique key
   * of this table.
   * 判定某个列是否主键
   * @param columns Ordinals of key columns
   * @return Whether the given columns are a key or a superset of a key
   */
  boolean isKey(ImmutableBitSet columns);

  /** 获取主键列表
   * Returns a list of unique keys, empty list if no key exist,
   * the result should be consistent with {@code isKey}.
   */
  @Nullable List<ImmutableBitSet> getKeys();

  /** 表的引用约束
   * Returns the referential constraints existing for this table. These constraints
   * are represented over other tables using {@link RelReferentialConstraint} nodes.
   */
  @Nullable List<RelReferentialConstraint> getReferentialConstraints();

  /**
   * Generates code for this table.
   *
   * @param clazz The desired collection class; for example {@code Queryable}.
   *  生成表的lin4j代码
   * @return the code for the table, or null if code generation is not supported
   */
  @Nullable Expression getExpression(Class clazz);

  /** Returns a table with the given extra fields.
   * 返回加上拓展列的表
   * <p>The extended table includes the fields of this base table plus the
   * extended fields that do not have the same name as a field in the base
   * table.
   */
  RelOptTable extend(List<RelDataTypeField> extendedFields);
  //列是否可空，是否有默认值以及是否可以插入
  /** Returns a list describing how each column is populated. The list has the
   * same number of entries as there are fields, and is immutable. */
  List<ColumnStrategy> getColumnStrategies();

  /** Can expand a view into relational expressions. */
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
  //把表转为关系表达式的上下文
  /** Contains the context needed to convert a a table into a relational
   * expression. */
  interface ToRelContext extends ViewExpander {
    RelOptCluster getCluster();

    /**
     * Returns the table hints of the table to convert,
     * usually you can use the hints to pass along some dynamic params.
     *
     * @return the hints attached to the table, never null
     */
    List<RelHint> getTableHints();
  }
}
