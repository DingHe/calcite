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
package org.apache.calcite.sql.validate;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.type.SqlTypeUtil;

import static java.util.Objects.requireNonNull;

/**
 * Namespace offered by a sub-query.
 *
 * @see SelectScope
 * @see SetopNamespace
 */
// 为 SQL 中的 SELECT 子查询（SqlSelect 节点）提供一个"命名空间"（Namespace），用于在 SQL 校验（validation）过程中管理该 SELECT 语句所对应的行类型（RelDataType）、校验逻辑、模态（modality）支持情况以及列的单调性（monotonicity）信息。
// 在 Calcite 的 SQL 校验框架中，"Namespace"是一个抽象概念，代表 SQL 查询中任何能产生一个"行类型"（即一组具名、具类型的列）的结构，例如：
// 一个 SELECT 语句
// 一个表引用
// 一个 JOIN 结果
// 一个集合操作（UNION/INTERSECT/EXCEPT）结果
// 一个子查询等
// SelectNamespace 专门对应 SqlSelect（SELECT 语句本身） 这种情况。它在类注释中也说明了这一点：
// "Namespace offered by a sub-query."（由子查询提供的命名空间）
// 即：当一个 SqlSelect 节点作为子查询或者顶层查询出现时，Calcite 会为其创建一个 SelectNamespace 实例，用来：
// 触发对该 SELECT 语句的完整校验（类型推导、语义检查等）。
// 记录并返回校验后得到的行类型（rowType）。
// 判断该 SELECT 是否支持某种"模态"（比如是流式 STREAM 还是关系型 RELATION）。
// 提供某一列的单调性信息（用于优化，比如判断某列是否递增/递减，从而支持某些排序消除等优化）。
public class SelectNamespace extends AbstractNamespace {
  //~ Instance fields --------------------------------------------------------
  // Calcite SQL 抽象语法树（AST）中表示 SELECT 语句的节点类型。
  // 保存该命名空间所对应的具体 SELECT 语句节点。这是该命名空间的核心数据——所有的方法（校验、获取节点、判断模态、获取单调性）都是围绕这个 select 字段展开操作的。
  private final SqlSelect select;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a SelectNamespace.
   *
   * @param validator     Validate
   * @param select        Select node
   * @param enclosingNode Enclosing node
   */
  public SelectNamespace(
      SqlValidatorImpl validator,
      SqlSelect select,
      SqlNode enclosingNode) {
    super(validator, enclosingNode);
    this.select = select;
  }

  //~ Methods ----------------------------------------------------------------

  @Override public SqlSelect getNode() {
    return select;
  }
  // 命名空间校验逻辑的核心入口。
  // 当框架需要对该命名空间进行"实际校验"时会调用此方法（通常由父类的 validate() 方法在带有缓存/去重机制的情况下间接调用，保证同一个命名空间不会被重复校验）。
  @Override public RelDataType validateImpl(RelDataType targetRowType) {
    validator.validateSelect(select, targetRowType);
    return requireNonNull(rowType, "rowType");
  }
  // 判断该 SELECT 语句是否支持指定的"模态"（SqlModality）
  // Calcite 支持流式 SQL（Streaming SQL），SqlModality 通常有两种取值：RELATION（普通的关系型/静态数据表）和 STREAM（流式数据）。
  // 这个方法用于校验：例如当某个 SELECT 语句被要求作为流（STREAM）使用时，需要检查其 FROM 子句中的表是否都是流表，或者其结构是否符合流式查询的语义要求。
  @Override public boolean supportsModality(SqlModality modality) {
    return validator.validateModality(select, modality, false);
  }
  // 获取该 SELECT 语句中指定列名对应的**单调性（Monotonicity）**信息。单调性用于描述某一列的数据在查询结果中是否呈现递增、递减、常量或不确定的趋势
  // （例如 SqlMonotonicity 可能的取值包括 INCREASING、DECREASING、CONSTANT、NOT_MONOTONIC 等）。
  // 这一信息在查询优化中非常有用，比如当某列已知单调递增时，某些排序（ORDER BY）、窗口聚合等操作可以被简化或消除。
  @Override public SqlMonotonicity getMonotonicity(String columnName) {
    final RelDataType rowType = this.getRowTypeSansSystemColumns();
    final int field = SqlTypeUtil.findField(rowType, columnName);
    SelectScope selectScope =
        requireNonNull(validator.getRawSelectScope(select),
            () -> "rawSelectScope for " + select);
    final SqlNode selectItem =
        requireNonNull(selectScope.getExpandedSelectList(),
            () -> "expandedSelectList for selectScope of " + select).get(field);
    return validator.getSelectScope(select).getMonotonicity(selectItem);
  }

}
