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

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;

import java.util.Objects;

/**
 * The name-resolution scope of a LATERAL TABLE clause.
 *
 * <p>The objects visible are those in the parameters found on the left side of
 * the LATERAL TABLE clause, and objects inherited from the parent scope.
 */
// TableScope 是一个专门用于处理 LATERAL TABLE（横向派生表）子句的名称解析作用域。
// TableScope 的核心作用是实现 LATERAL 关键字定义的特殊可见性规则。
// 在标准 SQL 中，FROM 子句中的两个表通常是不能互相引用的。但当你使用 LATERAL 关键字时，右侧的表（或表函数）可以引用左侧已经出现的表的列。
// 可见性范围：TableScope 使得它所关联的 SQL 节点能够看到：
// 处于该 LATERAL 子句左侧的所有表定义的列。
// 从父级作用域（Parent Scope）继承过来的对象。
// 继承关系：它继承自 ListScope，这意味着它本质上是一个可以存放多个名称解析条目的列表空间。
class TableScope extends ListScope {
  //~ Instance fields --------------------------------------------------------
  // 存储该作用域所关联的 SQL 语法树节点。
  // 在 TableScope 的上下文中，这个 node 通常代表 LATERAL TABLE(...) 子句内部的查询部分（通常是一个 SqlSelect 节点）。通过持有这个节点，作用域可以回溯到对应的语法树位置。
  private final SqlNode node;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a scope corresponding to a LATERAL TABLE clause.
   *
   * @param parent  Parent scope
   */
  TableScope(SqlValidatorScope parent, SqlNode node) {
    super(Objects.requireNonNull(parent, "parent"));
    this.node = Objects.requireNonNull(node, "node");
  }

  //~ Methods ----------------------------------------------------------------

  @Override public SqlNode getNode() {
    return node;
  }
  // 判断当前作用域是否位于另一个作用域 scope2 之内（或者是其本身）。
  @Override public boolean isWithin(SqlValidatorScope scope2) {
    if (this == scope2) {
      return true;
    }
    // 委派解析：由于 TableScope 包装了一个 node（通常是 SqlSelect），它会通过验证器（Validator）获取该 node 真正的 SelectScope。
    SqlValidatorScope s = getValidator().getSelectScope((SqlSelect) node);
    return s.isWithin(scope2);
  }
}
