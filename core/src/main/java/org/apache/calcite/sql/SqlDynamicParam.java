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
package org.apache.calcite.sql;

import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.util.SqlVisitor;
import org.apache.calcite.sql.validate.SqlMonotonicity;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorScope;
import org.apache.calcite.util.Litmus;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A <code>SqlDynamicParam</code> represents a dynamic parameter marker in an
 * SQL statement. The textual order in which dynamic parameters appear within an
 * SQL statement is the only property which distinguishes them, so this 0-based
 * index is recorded as soon as the parameter is encountered.
 */
// SqlDynamicParam 是一个非常精简但关键的类。它代表了 SQL 语句中的 动态参数占位符（通常在 SQL 文本中显示为问号 ?）
// SqlDynamicParam 的主要作用是 在抽象语法树（AST）中表示 JDBC 风格的参数占位符。
// 当你在执行预编译 SQL（PreparedStatement）时，例如：
// SELECT * FROM emp WHERE empno = ?
// 其中的 ? 在 Calcite 的解析阶段就会被转化为一个 SqlDynamicParam 对象。
// 唯一标识性：动态参数之间唯一的区别就是它们在 SQL 语句中出现的顺序索引（从 0 开始）。
public class SqlDynamicParam extends SqlNode {
  //~ Instance fields --------------------------------------------------------
  // 存储该参数在 SQL 文本中出现的基于 0 的索引位置。
  private final int index;

  //~ Constructors -----------------------------------------------------------

  public SqlDynamicParam(
      int index,
      SqlParserPos pos) {
    super(pos);
    this.index = index;
  }

  //~ Methods ----------------------------------------------------------------

  @Override public SqlNode clone(SqlParserPos pos) {
    return new SqlDynamicParam(index, pos);
  }

  @Override public SqlKind getKind() {
    return SqlKind.DYNAMIC_PARAM;
  }

  public int getIndex() {
    return index;
  }

  @Override public void unparse(
      SqlWriter writer,
      int leftPrec,
      int rightPrec) {
    writer.dynamicParam(index);
  }

  @Override public void validate(SqlValidator validator, SqlValidatorScope scope) {
    validator.validateDynamicParam(this);
  }

  @Override public SqlMonotonicity getMonotonicity(SqlValidatorScope scope) {
    return SqlMonotonicity.CONSTANT;
  }

  @Override public <R> R accept(SqlVisitor<R> visitor) {
    return visitor.visit(this);
  }

  @Override public boolean equalsDeep(@Nullable SqlNode node, Litmus litmus) {
    if (!(node instanceof SqlDynamicParam)) {
      return litmus.fail("{} != {}", this, node);
    }
    SqlDynamicParam that = (SqlDynamicParam) node;
    if (this.index != that.index) {
      return litmus.fail("{} != {}", this, node);
    }
    return litmus.succeed();
  }
}
