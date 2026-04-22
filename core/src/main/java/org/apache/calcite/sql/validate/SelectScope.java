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

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWindow;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.Pair;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * The name-resolution scope of a SELECT clause. The objects visible are those
 * in the FROM clause, and objects inherited from the parent scope.
 *
 * <p>This object is both a {@link SqlValidatorScope} and a
 * {@link SqlValidatorNamespace}. In the query
 *
 * <blockquote>
 * <pre>SELECT name FROM (
 *     SELECT *
 *     FROM emp
 *     WHERE gender = 'F')</pre></blockquote>
 *
 * <p>we need to use the {@link SelectScope} as a
 * {@link SqlValidatorNamespace} when resolving 'name', and
 * as a {@link SqlValidatorScope} when resolving 'gender'.
 *
 * <h2>Scopes</h2>
 *
 * <p>In the query
 *
 * <blockquote>
 * <pre>
 * SELECT expr1
 * FROM t1,
 *     t2,
 *     (SELECT expr2 FROM t3) AS q3
 * WHERE c1 IN (SELECT expr3 FROM t4)
 * ORDER BY expr4</pre>
 * </blockquote>
 *
 * <p>The scopes available at various points of the query are as follows:
 *
 * <ul>
 * <li>expr1 can see t1, t2, q3</li>
 * <li>expr2 can see t3</li>
 * <li>expr3 can see t4, t1, t2</li>
 * <li>expr4 can see t1, t2, q3, plus (depending upon the dialect) any aliases
 * defined in the SELECT clause</li>
 * </ul>
 *
 * <h2>Namespaces</h2>
 *
 * <p>In the above query, there are 4 namespaces:
 *
 * <ul>
 * <li>t1</li>
 * <li>t2</li>
 * <li>(SELECT expr2 FROM t3) AS q3</li>
 * <li>(SELECT expr3 FROM t4)</li>
 * </ul>
 *
 * @see SelectNamespace
 */
// 专门负责处理 SELECT 查询语句的作用域。
// 在 SQL 中，同一个标识符（如字段名）在 SELECT、WHERE、GROUP BY 中的含义和可见性是不同的。SelectScope 的职责就是定义哪些对象对当前 SELECT 子句可见。
// 可见性控制：在 SELECT 子句中，你可以看到 FROM 子句中定义的所有表（Namespaces）以及来自父查询（Parent Scope）的对象。
// 作为 SqlValidatorScope：用于校验内部表达式。例如在 SELECT gender ... 中，它负责通过 FROM 子句找到 gender 属于哪张表。
// 作为 SqlValidatorNamespace：对于外部查询，当前的 SELECT 块本身就是一个数据源。例如子查询 SELECT * FROM (SELECT id FROM emp)，外层查询将这个 SelectScope 视为一个结果集。
public class SelectScope extends ListScope {
  //~ Instance fields --------------------------------------------------------
  // 指向该作用域对应的 SQL 解析树节点（即具体的 SELECT 语句）。
  private final SqlSelect select;
  // 存储当前作用域中定义的窗口名称（来自 WINDOW 子句）。
  protected final List<String> windowNames = new ArrayList<>();
  // 存储“展开”后的 Select 列表。例如将 SELECT * 替换为具体的 SELECT col1, col2...。
  private @Nullable List<SqlNode> expandedSelectList = null;

  /**
   * List of column names which sort this scope. Empty if this scope is not
   * sorted. Null if has not been computed yet.
   */
  // 存储对该作用域进行排序的列。如果该作用域没有排序或者是初次计算，则可能为空。
  private @MonotonicNonNull SqlNodeList orderList;

  /** Scope to use to resolve windows. */
  // 用于解析窗口的父作用域。通常窗口的定义可以继承自更外层的定义。
  private final SqlValidatorScope windowParent;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a scope corresponding to a SELECT clause.
   *
   * @param parent    Parent scope
   * @param windowParent Scope for window parent
   * @param select    Select clause
   */
  SelectScope(SqlValidatorScope parent, SqlValidatorScope windowParent,
      SqlSelect select) {
    super(parent);
    this.select = requireNonNull(select, "select");
    this.windowParent = requireNonNull(windowParent, "windowParent");
  }

  //~ Methods ----------------------------------------------------------------

  public @Nullable SqlValidatorTable getTable() {
    return null;
  }

  @Override public SqlSelect getNode() {
    return select;
  }

  @Override public @Nullable SqlWindow lookupWindow(String name) {
    @SuppressWarnings({"unchecked", "rawtypes"})
    final List<SqlWindow> windowList =
        (List<SqlWindow>) (List) select.getWindowList();
    for (SqlWindow window : windowList) {
      final SqlIdentifier declId =
          requireNonNull(window.getDeclName(),
              () -> "declName of window " + window);
      assert declId.isSimple();
      if (declId.names.get(0).equals(name)) {
        return window;
      }
    }

    // if not in the select scope, then check window scope
    return windowParent.lookupWindow(name);
  }

  @Override public SqlMonotonicity getMonotonicity(SqlNode expr) {
    SqlMonotonicity monotonicity = expr.getMonotonicity(this);
    if (monotonicity != SqlMonotonicity.NOT_MONOTONIC) {
      return monotonicity;
    }

    // TODO: compare fully qualified names
    final SqlNodeList orderList = getOrderList();
    if (orderList.size() > 0) {
      SqlNode order0 = orderList.get(0);
      monotonicity = SqlMonotonicity.INCREASING;
      if ((order0 instanceof SqlCall)
          && (((SqlCall) order0).getOperator()
          == SqlStdOperatorTable.DESC)) {
        monotonicity = monotonicity.reverse();
        order0 = ((SqlCall) order0).operand(0);
      }
      if (expr.equalsDeep(order0, Litmus.IGNORE)) {
        return monotonicity;
      }
    }

    return SqlMonotonicity.NOT_MONOTONIC;
  }

  @Override public SqlNodeList getOrderList() {
    if (orderList == null) {
      // Compute on demand first call.
      orderList = new SqlNodeList(SqlParserPos.ZERO);
      if (children.size() == 1) {
        final SqlValidatorNamespace child = children.get(0).namespace;
        final List<Pair<SqlNode, SqlMonotonicity>> monotonicExprs =
            child.getMonotonicExprs();
        if (monotonicExprs.size() > 0) {
          orderList.add(monotonicExprs.get(0).left);
        }
      }
    }
    return orderList;
  }

  public void addWindowName(String winName) {
    windowNames.add(winName);
  }

  public boolean existingWindowName(String winName) {
    for (String windowName : windowNames) {
      if (windowName.equalsIgnoreCase(winName)) {
        return true;
      }
    }

    // if the name wasn't found then check the parent(s)
    SqlValidatorScope walker = parent;
    while (!(walker instanceof EmptyScope)) {
      if (walker instanceof SelectScope) {
        final SelectScope parentScope = (SelectScope) walker;
        return parentScope.existingWindowName(winName);
      }
      walker = ((DelegatingScope) walker).parent;
    }

    return false;
  }

  public @Nullable List<SqlNode> getExpandedSelectList() {
    return expandedSelectList;
  }

  public void setExpandedSelectList(@Nullable List<SqlNode> selectList) {
    expandedSelectList = selectList;
  }
}
