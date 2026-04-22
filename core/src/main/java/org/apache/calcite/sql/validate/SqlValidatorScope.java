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
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.StructKind;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWindow;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Name-resolution scope. Represents any position in a parse tree than an
 * expression can be, or anything in the parse tree which has columns.
 *
 * <p>When validating an expression, say "foo"."bar", you first use the
 * {@link #resolve} method of the scope where the expression is defined to
 * locate "foo". If successful, this returns a
 * {@link SqlValidatorNamespace namespace} describing the type of the resulting
 * object.
 */
// SqlValidatorScope 是 Apache Calcite SQL 校验器的核心接口之一。
// 如果说 SqlValidatorNamespace 代表的是“数据源（表、结果集）”，那么 SqlValidatorScope 代表的就是**“解析环境（上下文）”**。
// 在解析和校验 SQL 表达式时，Calcite 需要知道某个标识符（如 empno 或 alias.name）到底指向哪里。SqlValidatorScope 的主要作用是：
// 名称解析（Name Resolution）：管理标识符的可见性。例如，在 WHERE 子句中能看到 FROM 子句定义的表，但在 FROM 子句中通常看不到 SELECT 子句定义的别名。
// 上下文管理：SQL 的不同部分有不同的规则（例如 GROUP BY 作用域、HAVING 作用域、窗口函数作用域等）。Scope 组织成了树状结构，当当前层级找不到标识符时，会向父级（Enclosing Scope）递归查找。
// 表达式校验：提供验证特定上下文中表达式合法性的能力。
// SELECT empno, (SELECT deptname FROM dept WHERE dept.deptno = emp.deptno)
//FROM emp
//WHERE salary > 1000
// Calcite 会为这个查询构建四个主要的作用域，它们从内到外排列：
// SelectScope (主查询) 最基础的作用域，对应 FROM emp。 可见性：它能看到表 emp 及其所有列。职责：当校验 WHERE salary > 1000 时，Calcite 会在 SelectScope 中查找 salary。由于 emp 表包含此列，解析成功。
// WhereScope 虽然 WHERE 逻辑上属于 SELECT 的一部分，但 Calcite 会为其创建一个专门的 Scope。 关系：它的父级是 SelectScope。 特性：在 WHERE 中，你不能引用 SELECT 子句中定义的别名（例如 SELECT salary AS s ... WHERE s > 10 会报错），这就是通过 Scope 的隔离实现的。
// 3. SubqueryScope (相关子查询) 对应内部的 (SELECT deptname FROM dept ...)。 可见性：它首先能看到 FROM dept 里的列。关键点（嵌套）：它的父级是外部的 SelectScope。解析 emp.deptno：当前 Scope 只有 dept，找不到 emp。于是去父级作用域（主查询的 SelectScope）找，找到了 emp。这就是“相关子查询”能访问外部变量的底层原理。
public interface SqlValidatorScope {
  //~ Methods ----------------------------------------------------------------

  /**
   * Returns the validator which created this scope.
   */
  // 返回创建此作用域的校验器实例。
  SqlValidator getValidator();

  /**
   * Returns the root node of this scope. Never null.
   */
  // 返回此作用域对应的解析树节点（如 SqlSelect 或 SqlJoin）。
  SqlNode getNode();

  /**
   * Looks up a node with a given name. Returns null if none is found.
   *
   * @param names       Name of node to find, maybe partially or fully qualified
   * @param nameMatcher Name matcher
   * @param deep        Whether to look more than one level deep
   * @param resolved    Callback wherein to write the match(es) we find
   */
  // 最核心的查找方法。根据给定的名称列表（可能是多级标识符，如 schema.table.column）寻找对应的 Namespace。
  // Resolved 回调：找到的结果会存入 Resolved 对象中。
  void resolve(List<String> names, SqlNameMatcher nameMatcher, boolean deep,
      Resolved resolved);

  // CHECKSTYLE: IGNORE 1
  /** @deprecated Use
   * {@link #findQualifyingTableNames(String, SqlNode, SqlNameMatcher)} */
  // 给定一个不带前缀的列名（如 name），找出在这个作用域内哪些表/别名包含了这个列。
  // 如果找到多个，则说明标识符有歧义。
  @Deprecated // to be removed before 2.0
  Pair<String, SqlValidatorNamespace> findQualifyingTableName(String columnName,
      SqlNode ctx);

  /**
   * Finds all table aliases which are implicitly qualifying an unqualified
   * column name.
   *
   * <p>This method is only implemented in scopes (such as
   * {@link org.apache.calcite.sql.validate.SelectScope}) which can be the
   * context for name-resolution. In scopes such as
   * {@link org.apache.calcite.sql.validate.IdentifierNamespace}, it throws
   * {@link UnsupportedOperationException}.
   *
   * @param columnName Column name
   * @param ctx        Validation context, to appear in any error thrown
   * @param nameMatcher Name matcher
   *
   * @return Map of applicable table alias and namespaces, never null, empty
   * if no aliases found
   */
  Map<String, ScopeChild> findQualifyingTableNames(String columnName,
      SqlNode ctx, SqlNameMatcher nameMatcher);

  /**
   * Collects the {@link SqlMoniker}s of all possible columns in this scope.
   *
   * @param result an array list of strings to add the result to
   */
  // 收集当前作用域内所有可见的列名。
  void findAllColumnNames(List<SqlMoniker> result);

  /**
   * Collects the {@link SqlMoniker}s of all table aliases (uses of tables in
   * query FROM clauses) available in this scope.
   *
   * @param result a list of monikers to add the result to
   */
  // 收集当前作用域内所有可用的表别名。
  void findAliases(Collection<SqlMoniker> result);

  /**
   * Converts an identifier into a fully-qualified identifier. For example,
   * the "empno" in "select empno from emp natural join dept" becomes
   * "emp.empno".
   *
   * @return A qualified identifier, never null
   */
  // 将一个简写的标识符转换为全限定形式（例如将 empno 补全为 emp.empno）。
  SqlQualified fullyQualify(SqlIdentifier identifier);

  /** Returns whether an expression is a reference to a measure column. */
  // 用于判断一个 SQL 节点（通常是一个列引用）是否指向一个 Measure（度量值）。
  // 在现代 SQL 标准（以及 Calcite 的扩展）中，Measure 是一种特殊的列，它不像普通列那样存储静态数据，而是包含了一个聚合表达式。
  default boolean isMeasureRef(SqlNode node) {
    // 只有当节点是一个标识符（SqlIdentifier，即列名或 table.column 这种形式）时，才可能是一个 Measure 引用。如果是字面量或普通的函数调用，直接返回 false。
    if (node instanceof SqlIdentifier) {
      // 将简写的名称（如 price）转换为全限定形式（如 orders.price），并关联到对应的命名空间（namespace）。
      final SqlQualified q = fullyQualify((SqlIdentifier) node);
      // 确保解析结果指向的是一个具体的列，而不是一个表或 Schema。
      if (q.suffix().size() == 1
          && q.namespace != null) {
        final @Nullable RelDataTypeField f =
            q.namespace.field(q.suffix().get(0));
        // 情况 A：来源是 SELECT 子句 (SelectNamespace)
        if (q.namespace instanceof SelectNamespace) {
          final SqlSelect select = ((SelectNamespace) q.namespace).getNode();
          return f != null
              && SqlValidatorUtil.isMeasure(select.getSelectList().get(f.getIndex()));
        }
        // 调用工具类检查该表达式是否被定义为 MEASURE。
        return f != null
            && f.getType().getSqlTypeName() == SqlTypeName.MEASURE;
      }
    }
    return false;
  }

  /**
   * Registers a relation in this scope.
   *
   * @param ns    Namespace representing the result-columns of the relation
   * @param alias Alias with which to reference the relation, must not be null
   * @param nullable Whether this is a null-generating side of a join
   */
  // 向当前作用域添加一个子关系（通常是 FROM 子句中的一个项）。
  void addChild(SqlValidatorNamespace ns, String alias, boolean nullable);

  /**
   * Finds a window with a given name. Returns null if not found.
   */
  // 根据名称查找定义的窗口（WINDOW 子句）。
  @Nullable SqlWindow lookupWindow(String name);

  /**
   * Returns whether an expression is monotonic in this scope. For example, if
   * the scope has previously been sorted by columns X, Y, then X is monotonic
   * in this scope, but Y is not.
   */
  // 判断一个表达式在该作用域下是否是单调的（用于流处理或排序优化）。
  SqlMonotonicity getMonotonicity(SqlNode expr);

  /**
   * Returns the expressions by which the rows in this scope are sorted. If
   * the rows are unsorted, returns null.
   */
  @Nullable SqlNodeList getOrderList();

  /**
   * Resolves a single identifier to a column, and returns the datatype of
   * that column.
   *
   * <p>If it cannot find the column, returns null. If the column is
   * ambiguous, throws an error with context <code>ctx</code>.
   *
   * @param name Name of column
   * @param ctx  Context for exception
   * @return Type of column, if found and unambiguous; null if not found
   */
  // 专门解析单个列名，返回其数据类型。
  @Nullable RelDataType resolveColumn(String name, SqlNode ctx);

  /**
   * Returns the scope within which operands to a call are to be validated.
   * Usually it is this scope, but when the call is to an aggregate function
   * and this is an aggregating scope, it will be a a different scope.
   *
   * @param call Call
   * @return Scope within which to validate arguments to call.
   */
  // 当校验函数调用（如 SUM(x)）时，返回校验其参数时应使用的作用域（聚合函数内部往往能看到原始数据列）。
  SqlValidatorScope getOperandScope(SqlCall call);

  /**
   * Performs any scope-specific validation of an expression. For example, an
   * aggregating scope requires that expressions are valid aggregations. The
   * expression has already been validated.
   */
  // 执行特定于作用域的校验。例如，在聚合作用域中，该方法会检查非聚合列是否出现在了 GROUP BY 中。
  void validateExpr(SqlNode expr);

  // CHECKSTYLE: IGNORE 1
  /** @deprecated Use
   * {@link #resolveTable(List, SqlNameMatcher, Path, Resolved)}. */
  @Deprecated // to be removed before 2.0
  @Nullable SqlValidatorNamespace getTableNamespace(List<String> names);

  /**
   * Looks up a table in this scope from its name. If found, calls
   * {@link Resolved#resolve(List, SqlNameMatcher, boolean, Resolved)}.
   * {@link TableNamespace} that wraps it. If the "table" is defined in a
   * {@code WITH} clause it may be a query, not a table after all.
   *
   * <p>The name matcher is not null, and one typically uses
   * {@link SqlValidatorCatalogReader#nameMatcher()}.
   *
   * @param names Name of table, may be qualified or fully-qualified
   * @param nameMatcher Name matcher
   * @param path List of names that we have traversed through so far
   */
  // 专门用于查找表。如果名称指向 WITH 子句定义的 CTE，也会在这里处理。
  void resolveTable(List<String> names, SqlNameMatcher nameMatcher, Path path,
      Resolved resolved);

  /** Converts the type of an expression to nullable, if the context
   * warrants it. */
  // 根据上下文（如外连接侧）决定是否将类型标记为可为空。
  RelDataType nullifyType(SqlNode node, RelDataType type);

  /** Returns whether this scope is enclosed within {@code scope2} in such
   * a way that it can see the contents of {@code scope2}. */
  // 检查当前作用域是否被包含在另一个作用域内（可见性检查）。
  default boolean isWithin(SqlValidatorScope scope2)  {
    return this == scope2;
  }

  /** Callback from {@link SqlValidatorScope#resolve}. */
  // Resolved 接口扮演着**“搜集器”和“结果回调”**的角色。
  // 当你在一个作用域内通过 resolve 方法查找某个名字（例如 emp.deptno）时，查找过程可能非常复杂（涉及嵌套 Scope、多级对象路径、别名匹配等）。
  // Resolved 接口通过回调机制，将匹配到的结果实时反馈给调用者。
  interface Resolved {
    // 接口的核心方法。每当校验器成功定位到一个匹配项时，就会调用此方法。
    void found(SqlValidatorNamespace namespace,// 表示匹配到的结果所对应的命名空间。通过这个参数，你可以获取解析目标的行类型（Row Type）。比如解析 emp，它返回的就是 TableNamespace，包含了该表的列信息
        boolean nullable, // 标记当前解析路径是否涉及“可为空”的上下文。
        SqlValidatorScope scope, // 指明该标识符最终是在哪一个具体的作用域中被找到的。
        Path path, // 记录从作用域根节点到目标对象的完整解析路径。
        List<String> remainingNames);// 返回尚未被解析的名字后缀。 如果搜索 a.b.c.d，解析器在当前层级找到了 a.b，那么 remainingNames 就是 [c, d]。这告诉调用者：解析尚未完全完成，需要在返回的 namespace 内部继续深入查找。
    // 返回目前已经找到的匹配项总数。
    int count();
  }

  /** A sequence of steps by which an identifier was resolved. Immutable. */
  abstract class Path {
    /** The empty path. */
    @SuppressWarnings("StaticInitializerReferencesSubClass")
    public static final EmptyPath EMPTY = new EmptyPath();

    /** Creates a path that consists of this path plus one additional step. */
    public Step plus(@Nullable RelDataType rowType, int i, String name, StructKind kind) {
      return new Step(this, rowType, i, name, kind);
    }

    /** Number of steps in this path. */
    public int stepCount() {
      return 0;
    }

    /** Returns the steps in this path. */
    public List<Step> steps() {
      ImmutableList.Builder<Step> paths = new ImmutableList.Builder<>();
      build(paths);
      return paths.build();
    }

    /** Returns a list ["step1", "step2"]. */
    List<String> stepNames() {
      return Util.transform(steps(), input -> input.name);
    }

    protected void build(ImmutableList.Builder<Step> paths) {
    }

    @Override public String toString() {
      return stepNames().toString();
    }
  }

  /** A path that has no steps. */
  class EmptyPath extends Path {
  }

  /** A step in resolving an identifier. */
  class Step extends Path {
    final Path parent;
    final @Nullable RelDataType rowType;
    public final int i;
    public final String name;
    final StructKind kind;

    Step(Path parent, @Nullable RelDataType rowType, int i, String name,
        StructKind kind) {
      this.parent = requireNonNull(parent, "parent");
      this.rowType = rowType; // may be null
      this.i = i;
      this.name = name;
      this.kind = requireNonNull(kind, "kind");
    }

    @Override public int stepCount() {
      return 1 + parent.stepCount();
    }

    @Override protected void build(ImmutableList.Builder<Step> paths) {
      parent.build(paths);
      paths.add(this);
    }
  }

  /** Default implementation of
   * {@link org.apache.calcite.sql.validate.SqlValidatorScope.Resolved}. */
  // 本质上是一个解析结果容器，用于在 SQL 标识符解析过程中收集、存储并规范化所有匹配到的信息。
  // 在 Calcite 校验器进行名称解析（Name Resolution）时，一个名字可能会在不同的作用域中找到匹配，或者在同一个作用域中因为重名而找到多个匹配。
  // ResolvedImpl 的核心作用是：
  // 结果存储：记录所有成功的解析动作（Resolve 对象）。
  // 作用域规范化：这是该类最特殊的地方。它在存储结果前，会将一些临时的、内部的 Scope（如 TableScope）转换为更通用的 SelectScope。这确保了后续校验逻辑（如判断列是否在 GROUP BY 中）能够拿到正确的上下文。
  // 歧义检测支持：通过维护结果列表，方便校验器判断是否存在多个匹配项（即 SQL 歧义错误）。
  class ResolvedImpl implements Resolved {
    // 存储所有解析出的 Resolve 实例。
    // 每个 Resolve 都代表一个完整的匹配路径。如果这个 List 的大小超过 1，通常意味着 SQL 存在列名歧义。
    final List<Resolve> resolves = new ArrayList<>();
    // 找到一个匹配项时会调用此方法。它是该类逻辑最复杂的地方，包含作用域的重定向逻辑。
    @Override public void found(SqlValidatorNamespace namespace,
        boolean nullable, SqlValidatorScope scope, Path path,
        List<String> remainingNames) {
      // 1. 处理 TableScope 转换
      if (scope instanceof TableScope) {
        // TableScope 是解析 FROM 子句时的临时状态。
        // 为了让后续代码（比如解析 SELECT 列表）能正确感知到这个列属于哪个 SELECT 语句，需要将其转化为 SelectScope。
        scope = scope.getValidator().getSelectScope((SqlSelect) scope.getNode());
      }
      // // 2. 处理聚合作用域回退
      if (scope instanceof AggregatingSelectScope) {
        // 如果标识符是在聚合作用域中找到的，它通常需要映射回其父级 SelectScope。
        // 这是因为聚合作用域（用于处理 GROUP BY 等）实际上是包裹在标准 SelectScope 之外的一层逻辑约束。
        scope = ((AggregatingSelectScope) scope).parent;
        assert scope instanceof SelectScope;
      }
      // 3. 封装并存入列表
      resolves.add(
          new Resolve(namespace, nullable, scope, path, remainingNames));
    }

    @Override public int count() {
      return resolves.size();
    }

    public Resolve only() {
      return Iterables.getOnlyElement(resolves);
    }

    /** Resets all state. */
    public void clear() {
      resolves.clear();
    }

  }

  /** A match found when looking up a name. */
  // 代表了标识符解析过程中的最终产物。当校验器说“我找到了这个名字”时，所有的证据和结果都封装在这个 Resolve 对象里。
  class Resolve {
    // 指向匹配到的目标命名空间。
    // 如果解析的是表名，它可能是 TableNamespace；如果解析的是列名，它指向该列所属的表的 Namespace。
    public final SqlValidatorNamespace namespace;
    // 标记此特定解析路径是否产生了“空值可能性”。
    // 关键的动态属性。即使表定义中列是 NOT NULL，如果它是通过 LEFT JOIN 的右侧被找到的，此处的 nullable 也会是 true。
    private final boolean nullable;
    // 记录找到该标识符的实际作用域。
    public final SqlValidatorScope scope;
    // 存储解析路径。
    public final Path path;
    /** Names not matched; empty if it was a full match. */
    // 存储未消耗完的名字部分。
    final List<String> remainingNames;

    Resolve(SqlValidatorNamespace namespace, boolean nullable,
        SqlValidatorScope scope, Path path, List<String> remainingNames) {
      this.namespace = requireNonNull(namespace, "namespace");
      this.nullable = nullable;
      this.scope = scope;
      assert !(scope instanceof TableScope);
      this.path = requireNonNull(path, "path");
      this.remainingNames = ImmutableList.copyOf(remainingNames);
    }

    /** The row type of the found namespace, nullable if the lookup has
     * looked into outer joins. */
    public RelDataType rowType() {
      return namespace.getValidator().getTypeFactory()
          .createTypeWithNullability(namespace.getRowType(), nullable);
    }
  }
}
