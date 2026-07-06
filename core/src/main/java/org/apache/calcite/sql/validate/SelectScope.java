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
// SelectScope 是 Apache Calcite SQL 校验器体系中用于表示 SELECT 子句的"名字解析作用域"（name-resolution scope） 的类，继承自 ListScope（而 ListScope 又是维护"子命名空间列表"的作用域基类）
// SelectScope 定义了在一个 SELECT 子句中，哪些对象（表、列等）是"可见"的。这些可见对象包括：
// 该 SELECT 语句自己的 FROM 子句中出现的所有表/子查询/JOIN 结果。
// 从父作用域（外层查询）继承而来的对象（对应相关子查询、嵌套查询场景）。
// 同时扮演两种角色：
// 作为 SqlValidatorScope（作用域）：用于校验 SELECT 语句内部的表达式引用。例如在 SELECT name FROM (SELECT * FROM emp WHERE gender = 'F') 中，解析内层 WHERE gender = 'F' 里的 gender 时，就是把内层的 SelectScope 当作"作用域"来查找 gender 属于哪个表。
// 作为 SqlValidatorNamespace（命名空间，通过继承 ListScope→...→最终关联 SelectNamespace）：当这个 SELECT 语句本身被作为一个"数据源"（比如作为子查询嵌套在外层 FROM 中）时，外层查询在解析 name 时，会把整个内层 SELECT 当作一个命名空间来获取其行类型（结果集的列）。
public class SelectScope extends ListScope {
  //~ Instance fields --------------------------------------------------------
  // 指向该作用域对应的 SQL 解析树节点（即具体的 SELECT 语句）。
  private final SqlSelect select;
  //  存放当前 SELECT 语句中，通过 WINDOW 子句定义的窗口名称列表。例如 SQL 中写：
  //  SELECT SUM(sal) OVER w
  //  FROM emp
  //  WINDOW w AS (PARTITION BY deptno ORDER BY sal)
  //  这里 w 这个窗口名称就会被记录到 windowNames 中，用于后续校验窗口名是否重复定义、是否可以被正确引用等。
  protected final List<String> windowNames = new ArrayList<>();
  // 存储展开后的 SELECT 列表。所谓"展开"，是指将 SELECT *（星号通配符）替换为具体的列名列表，
  // 比如把 SELECT * FROM emp 中的 * 展开成 SELECT empno, ename, sal, ... FROM emp
  private @Nullable List<SqlNode> expandedSelectList = null;

  /**
   * List of column names which sort this scope. Empty if this scope is not
   * sorted. Null if has not been computed yet.
   */
  // 存储对该作用域进行排序的列。如果该作用域没有排序或者是初次计算，则可能为空。
  private @MonotonicNonNull SqlNodeList orderList;

  /** Scope to use to resolve windows. */
  // 用于解析窗口的父作用域。
  // 通常窗口的定义可以继承自更外层的定义。
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
  // 根据给定的窗口名称 name，在当前 SELECT 的 WINDOW 子句中查找对应的窗口定义（SqlWindow），如果找不到则委托父作用域（windowParent）继续查找。
  // name（String）：待查找的窗口名称，比如 SQL 中 OVER w 里的 w。
  @Override public @Nullable SqlWindow lookupWindow(String name) {
    @SuppressWarnings({"unchecked", "rawtypes"})
    // 调用 select.getWindowList() 获取该 SELECT 语句的 WINDOW 子句对应的节点列表（原始返回类型可能是更通用的 SqlNodeList 或类似类型），
    // 通过双重强制转换将其视为 List<SqlWindow> 类型，因为 WINDOW 子句中的每一项本质上都是 SqlWindow 类型的节点。
    final List<SqlWindow> windowList =
        (List<SqlWindow>) (List) select.getWindowList();
    for (SqlWindow window : windowList) {
      final SqlIdentifier declId =
          requireNonNull(window.getDeclName(),
              () -> "declName of window " + window);
      assert declId.isSimple();
      // 取出标识符的第一部分名称（对于简单标识符，names 列表只有一个元素），与传入的 name 参数进行比较，如果相等，说明找到了匹配的窗口定义，直接返回该 SqlWindow 对象。
      if (declId.names.get(0).equals(name)) {
        return window;
      }
    }

    // if not in the select scope, then check window scope
    // 如果遍历完当前 SELECT 语句自身的所有窗口定义都没有找到匹配项，则委托给 windowParent（构造时传入的窗口父作用域）继续向上查找，实现窗口定义的"层级查找"机制（类似于变量作用域链的查找方式）。
    return windowParent.lookupWindow(name);
  }
  // 判断给定的表达式 expr 在当前作用域下的单调性（SqlMonotonicity）。这是判断"某列/表达式的值是否随结果集行的顺序呈现递增、递减等规律"的核心逻辑。
  //
  @Override public SqlMonotonicity getMonotonicity(SqlNode expr) {
    SqlMonotonicity monotonicity = expr.getMonotonicity(this);
    // 如果第一步已经能得出明确的（非"不确定/非单调"）结果，直接返回，无需继续判断。
    if (monotonicity != SqlMonotonicity.NOT_MONOTONIC) {
      return monotonicity;
    }

    // TODO: compare fully qualified names
    // 逻辑的思路是：如果当前查询结果本身是按某个表达式排序的，那么该表达式在结果集中天然具有单调性（比如 ORDER BY sal 意味着结果按 sal 递增排列，那么 sal 这一列在遍历结果时就是单调递增的）。
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
  // 获取（并在必要时懒加载计算）当前作用域的"排序列表"——即决定当前结果集顺序的表达式列表，供 getMonotonicity 方法及其他需要判断结果集有序性的地方使用。
  @Override public SqlNodeList getOrderList() {
    if (orderList == null) {
      // Compute on demand first call.
      orderList = new SqlNodeList(SqlParserPos.ZERO);
      // 检查当前作用域的子命名空间（即 FROM 子句中的表/子查询列表，children 是继承自父类 ListScope 的字段）数量是否恰好为 1。
      // 关键的业务逻辑：只有当 FROM 子句中只有单一数据源（比如 SELECT ... FROM (SELECT ... ORDER BY ...) AS t，即从一个已经排序好的子查询中查询）时，
      // 才能直接"继承"这个子查询的排序特性；如果 FROM 子句涉及多表 JOIN，则整体结果的顺序变得不确定，无法简单继承某个单一来源的排序信息。
      if (children.size() == 1) {
        // 取出这唯一的子命名空间对象（children 中的元素通常是某种"命名空间+别名"的封装，这里通过 .namespace 取出实际的命名空间对象）。
        final SqlValidatorNamespace child = children.get(0).namespace;
        // 调用子命名空间的 getMonotonicExprs() 方法，获取该子查询/子命名空间中已知的"单调表达式列表"（每一项是一个 <表达式, 单调性> 的键值对，通常对应该子查询自己的 ORDER BY 列表）。
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
  // 检查指定的窗口名称 winName 是否已经存在（无论是在当前作用域，还是在任何一层父作用域中），用于防止窗口名称重复定义（SQL 标准通常要求同一查询范围内窗口名不能重复）。
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
