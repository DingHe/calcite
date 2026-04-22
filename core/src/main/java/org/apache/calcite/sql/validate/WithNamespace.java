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
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.util.Util;

import org.checkerframework.checker.nullness.qual.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * Namespace for <code>WITH</code> clause.
 */
// 专门用于处理 WITH 子句（也称为公共表表达式，CTE - Common Table Expressions） 的命名空间类。
// WITH 允许你定义一个或多个临时结果集，并在主查询中引用它们。WithNamespace 的核心职责就是协调这些临时定义与主查询体（Body）之间的关系。
// WithNamespace 代表了整个 WITH ... SELECT ... 语句的命名空间。它的作用主要体现在以下两个方面：
// 顺序校验：负责遍历并校验 WITH 列表中的每一个 SqlWithItem（即每一个定义的临时表）。
// 作用域传递：确保在校验主查询体（Body）时，能够识别并访问到前面定义的临时表。它将 WITH 列表定义的可见性“传递”给主查询。
// 结果透传：WITH 语句最终产生的数据行类型（Row Type）实际上就是它内部主查询体产生的结果类型。WithNamespace 负责将这个类型提取并作为整个表达式的类型。
public class WithNamespace extends AbstractNamespace {
  //~ Instance fields --------------------------------------------------------

  private final SqlWith with;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a TableConstructorNamespace.
   *
   * @param validator     Validator
   * @param with          WITH clause
   * @param enclosingNode Enclosing node
   */
  WithNamespace(SqlValidatorImpl validator,
      SqlWith with,
      SqlNode enclosingNode) {
    super(validator, enclosingNode);
    this.with = with;
  }

  //~ Methods ----------------------------------------------------------------

  @Override protected RelDataType validateImpl(RelDataType targetRowType) {
    // 1. 遍历并校验所有的 WITH 定义项 (如 AS (...) 里的查询)
    for (SqlNode withItem : with.withList) {
      validator.validateWithItem((SqlWithItem) withItem);
    }
    // 2. 获取最后一个 WITH 项的作用域
    // 因为前面的项对后面可见，最后一个项的作用域包含了所有已定义的 CTE 名称
    final SqlValidatorScope scope2 =
        validator.getWithScope(Util.last(with.withList));
    // 3. 获取主查询体 (Body) 的命名空间
    final SqlValidatorNamespace bodyNamespace =
        requireNonNull(validator.getNamespace(with.body), "namespace");
    // 4. 在刚才获取的作用域 scope2 下校验主查询体
    validator.validateQuery(with.body, scope2, targetRowType);
    // 5. 获取主查询体校验后推导出的行类型
    final RelDataType rowType = validator.getValidatedNodeType(with.body);
    // 6. 将主查询体的类型设置为整个 WITH 表达式的类型
    validator.setValidatedNodeType(with, rowType);
    // 7. 继承主查询体的敏感字段过滤属性 (mustFilterFields)
    mustFilterFields = bodyNamespace.getMustFilterFields();
    return rowType;
  }

  @Override public @Nullable SqlNode getNode() {
    return with;
  }
}
