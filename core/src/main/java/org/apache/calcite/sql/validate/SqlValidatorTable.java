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
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.Wrapper;
import org.apache.calcite.sql.SqlAccessType;
import org.apache.calcite.sql2rel.InitializerContext;

import java.util.List;

/**
 * Supplies a {@link SqlValidator} with the metadata for a table.
 * @see SqlValidatorCatalogReader
 */
// 在 Apache Calcite 中，SqlValidatorTable 是一个至关重要的接口。它处于 SQL 校验层（Validation） 和 元数据层（Catalog/Schema） 的交汇处。
// 它是 SqlValidator 观察数据库表的“透视镜”。
// 在 SQL 校验阶段，校验器需要知道：
// 某个表是否存在？
// 这个表有哪些列，类型是什么？（RowType）
// 校验器是否有权读写该表？
// 该表是普通的静态表，还是流（Stream）？
// SqlValidatorTable 并不直接存储数据，它是一个元数据桥梁。它封装了底层 Table 对象的属性，并以校验器易于理解的方式（例如使用 RelDataType 而非 Java Class）暴露出来。
// SqlValidatorTable 继承了 Wrapper 接口。这意味着它可以作为一个容器，通过 unwrap 方法获取底层的原始对象（如底层的 Table 实例或自定义的元数据对象）。
public interface SqlValidatorTable extends Wrapper {

  //~ Methods ----------------------------------------------------------------
  // 获取表的行类型。
  // 最重要的核心方法。
  // 它返回一个 RelDataType 对象，包含了表中所有列的名字和数据类型。校验器根据这个返回值来判断 SELECT a FROM table 中的 a 是否合法。
  RelDataType getRowType();
  // 获取表的限定名。
  // 返回一个字符串列表，代表表的完整路径。
  // 例如，对于 CATALOG.SCHEMA.TABLE_NAME，列表将包含这三个部分。校验器使用它来在错误消息中标识表，或在内部缓存中作为键。
  List<String> getQualifiedName();

  /**
   * Returns whether a given column is monotonic.
   */
  // 获取指定列的单调性（Monotonicity）。
  // 单调性指数据的排列趋势（递增、递减、常数或非单调）。
  // 应用场景：在流计算（Streaming）或物化视图改写中非常重要。例如，时间戳列通常是单调递增的，这可以帮助优化器进行特殊的排序消除或窗口触发操作。
  SqlMonotonicity getMonotonicity(String columnName);

  /**
   * Returns the access type of the table.
   */
  // 获取表的允许访问类型。
  // 返回 SqlAccessType。
  // 它定义了当前表是只读（READ_ONLY）、可更新（UPDATEABLE）还是完全不可访问。如果用户尝试对一个只读表执行 DELETE 或 INSERT 操作，校验器会通过此方法发现并报错。
  SqlAccessType getAllowedAccess();
  // 判断表是否支持某种模态。
  // SqlModality 分为 RELATION（传统关系表）和 STREAM（流）
  // 应用场景：如果你在 SQL 中使用了 SELECT STREAM * FROM t，校验器会调用此方法确认表 t 是否支持流模式。
  boolean supportsModality(SqlModality modality);

  /**
   * Returns whether the table is temporal.
   */
  // 判断是否为时态表（Temporal Table）。
  // 时态表是记录历史变更的表（通常关联系统时间或业务时间）。Calcite 支持时态表联接（Temporal Table Joins），校验器通过此方法识别该表是否具备时间维度特性。
  boolean isTemporal();

  /**
   * Returns whether the ordinal column has a default value.
   */
  // 判断指定列是否有默认值。
  // 用于在执行 INSERT 但未指定某列时，判断是否可以使用系统定义的默认值。现在这些逻辑更多地由 InitializerContext 统一处理。
  @Deprecated // to be removed before 2.0
  boolean columnHasDefaultValue(RelDataType rowType, int ordinal,
      InitializerContext initializerContext);

  /** Returns the table. */
  // 获取底层的 Table 对象。
  default Table table() {
    return unwrapOrThrow(Table.class);
  }
}
