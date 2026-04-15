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
package org.apache.calcite.rex;

import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlKind;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * Reference to the current row of a correlating relational expression.
 *
 * <p>Correlating variables are introduced when performing nested loop joins.
 * Each row is received from one side of the join, a correlating variable is
 * assigned a value, and the other side of the join is restarted.
 */
// RexCorrelVariable 代表一个相关变量（Correlating Variable）。
// 跨层引用：在 SQL 中，当子查询引用外部查询的列时，就会产生相关性。例如：
// SELECT ename FROM emp WHERE EXISTS (
//  SELECT 1 FROM dept WHERE dept.deptno = emp.deptno -- 这里的 emp 就是相关变量
//)
// 在 Calcite 的逻辑计划中，外部查询的当前行被抽象为一个变量，子查询通过 RexCorrelVariable 来“观察”外部查询当前正在处理的那一行数据。
// 嵌套循环连接（Nested Loop Join）的基石：相关变量通常与嵌套循环连接配合使用。外部循环每读取一行，都会将该行赋值给相关变量，然后重新启动内部循环（子查询），内部循环通过该变量获取外部行的值。
public class RexCorrelVariable extends RexVariable {
  // 存储相关变量的唯一标识符。
  public final CorrelationId id;

  //~ Constructors -----------------------------------------------------------

  RexCorrelVariable(
      CorrelationId id,
      RelDataType type) {
    super(id.getName(), type);
    this.id = Objects.requireNonNull(id, "id");
  }

  //~ Methods ----------------------------------------------------------------

  @Override public <R> R accept(RexVisitor<R> visitor) {
    return visitor.visitCorrelVariable(this);
  }

  @Override public <R, P> R accept(RexBiVisitor<R, P> visitor, P arg) {
    return visitor.visitCorrelVariable(this, arg);
  }

  @Override public SqlKind getKind() {
    return SqlKind.CORREL_VARIABLE;
  }

  @Override public boolean equals(@Nullable Object obj) {
    return this == obj
        || obj instanceof RexCorrelVariable
        && Objects.equals(digest, ((RexCorrelVariable) obj).digest)
        && type.equals(((RexCorrelVariable) obj).type)
        && id.equals(((RexCorrelVariable) obj).id);
  }

  @Override public int hashCode() {
    return Objects.hash(digest, type, id);
  }
}
