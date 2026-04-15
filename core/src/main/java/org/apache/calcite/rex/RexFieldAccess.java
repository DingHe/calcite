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

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.SqlKind;

import org.checkerframework.checker.nullness.qual.Nullable;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Access to a field of a row-expression.
 *
 * <p>You might expect to use a <code>RexFieldAccess</code> to access columns of
 * relational tables, for example, the expression <code>emp.empno</code> in the
 * query
 *
 * <blockquote>
 * <pre>SELECT emp.empno FROM emp</pre>
 * </blockquote>
 *
 * <p>but there is a specialized expression {@link RexInputRef} for this
 * purpose. So in practice, <code>RexFieldAccess</code> is usually used to
 * access fields of correlating variables, for example the expression
 * <code>emp.deptno</code> in
 * <blockquote>
 * <pre>SELECT ename
 * FROM dept
 * WHERE EXISTS (
 *     SELECT NULL
 *     FROM emp
 *     WHERE emp.deptno = dept.deptno
 *     AND gender = 'F')</pre>
 * </blockquote>
 */
// RexFieldAccess 的主要作用是访问一个行表达式（Row Expression）中的特定字段。
// 虽然在普通的 SQL 查询（如 SELECT col FROM table）中，Calcite 通常使用 RexInputRef 来引用列，但 RexFieldAccess 在以下场景中不可或缺：
// 相关子查询（Correlated Subqueries）：当子查询引用外部查询的变量（Correlating Variables）时。例如 WHERE emp.deptno = dept.deptno，这里的 dept.deptno 往往通过 RexFieldAccess 访问。
// 嵌套数据结构（Structured Types）：访问复合类型（如对象、结构体或行类型）中的某一个成员。例如访问一个名为 Address 字段中的 City 属性。
public class RexFieldAccess extends RexNode {
  //~ Instance fields --------------------------------------------------------
  // 代表被访问的基础表达式。
  // 这是“点”操作符左侧的部分。它本身可以是一个变量引用（RexCorrelVariable）、另一个字段访问（嵌套访问）或者其他任何返回行/对象类型的表达式。
  private final RexNode expr;
  // 代表被访问的具体字段。
  private final RelDataTypeField field; //哪个字段

  //~ Constructors -----------------------------------------------------------

  RexFieldAccess(
      RexNode expr,
      RelDataTypeField field) {
    checkValid(expr, field);
    this.expr = expr;
    this.field = field;
    this.digest = expr + "." + field.getName();
  }

  //~ Methods ----------------------------------------------------------------

  private static void checkValid(RexNode expr, RelDataTypeField field) {
    RelDataType exprType = expr.getType();
    int fieldIdx = field.getIndex();
    checkArgument(fieldIdx >= 0 && fieldIdx < exprType.getFieldList().size()
            && exprType.getFieldList().get(fieldIdx).equals(field),
        "Field %s does not exist for expression %s", field, expr);
  }

  public RelDataTypeField getField() {
    return field;
  }

  @Override public RelDataType getType() {
    return field.getType();
  }

  @Override public SqlKind getKind() {
    return SqlKind.FIELD_ACCESS;
  }

  @Override public <R> R accept(RexVisitor<R> visitor) {
    return visitor.visitFieldAccess(this);
  }

  @Override public <R, P> R accept(RexBiVisitor<R, P> visitor, P arg) {
    return visitor.visitFieldAccess(this, arg);
  }

  /**
   * Returns the expression whose field is being accessed.
   */
  public RexNode getReferenceExpr() {
    return expr;
  }

  @Override public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    RexFieldAccess that = (RexFieldAccess) o;

    return field.equals(that.field) && expr.equals(that.expr);
  }

  @Override public int hashCode() {
    int result = expr.hashCode();
    result = 31 * result + field.hashCode();
    return result;
  }
}
