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

import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlWindow;

import org.checkerframework.checker.nullness.qual.Nullable;

import static org.apache.calcite.sql.JoinType.LEFT_ANTI_JOIN;
import static org.apache.calcite.sql.JoinType.LEFT_SEMI_JOIN;
import static org.apache.calcite.sql.SqlUtil.stripAs;

import static java.util.Objects.requireNonNull;

/**
 * The name-resolution context for expression inside a JOIN clause. The objects
 * visible are the joined table expressions, and those inherited from the parent
 * scope.
 *
 * <p>Consider "SELECT * FROM (A JOIN B ON {exp1}) JOIN C ON {exp2}". {exp1} is
 * resolved in the join scope for "A JOIN B", which contains A and B but not
 * C.
 */
// JoinScope 定义了 JOIN 子句内部（主要是 ON 连接条件）的表达式能够看到哪些对象。
// 这些可见对象包括：
// 参与本次 JOIN 的左右两个表达式（可能是简单表、也可能是嵌套的子 JOIN、子查询等）。
// 从父作用域继承而来的对象
// SELECT * FROM (A JOIN B ON {exp1}) JOIN C ON {exp2}
// 这是一个"左深"结构的多表 JOIN，整体结构是 (A JOIN B) JOIN C。这里：
// {exp1}（A JOIN B 之间的连接条件）应该在 "A JOIN B" 这一层的 JoinScope 中解析，这个作用域只能看到 A 和 B，看不到 C——因为从语法结构上看，C 是在更外层的 JOIN 中才被引入的，此时 A JOIN B 这个子结构还不知道 C 的存在。
// {exp2}（最外层 JOIN 的连接条件）则应该在 "(A JOIN B) JOIN C" 这一层的 JoinScope 中解析，这个作用域能同时看到 A JOIN B 的整体结果（也就间接能看到 A、B）以及 C。
// 说明了 JoinScope 的核心职责：为每一层 JOIN 精确划定"此时此刻能看到谁"的边界，避免内层的连接条件错误地引用了尚未在语法结构中出现的外层表。
public class JoinScope extends ListScope {
  //~ Instance fields --------------------------------------------------------
  // 表示"用于解析 USING 子句的作用域"。在 SQL 中，JOIN 可以使用 USING (col1, col2) 这种简化写法（等价于在两边同名列上做等值连接），这种情况下需要一个特定的作用域来正确解析 USING 子句里提到的列名。
  private final @Nullable SqlValidatorScope usingScope;
  // Calcite AST 中专门表示 JOIN 结构的节点类型（包含左表达式、右表达式、连接类型 JoinType、连接条件 condition 等信息）
  private final SqlJoin join;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a <code>JoinScope</code>.
   *
   * @param parent     Parent scope
   * @param usingScope Scope for resolving USING clause
   * @param join       Call to JOIN operator
   */
  JoinScope(
      SqlValidatorScope parent,
      @Nullable SqlValidatorScope usingScope,
      SqlJoin join) {
    super(parent);
    this.usingScope = usingScope;
    this.join = join;
  }

  //~ Methods ----------------------------------------------------------------

  @Override public SqlNode getNode() {
    return join;
  }

  // 重写父类 ListScope 的 addChild 方法，在完成"添加子命名空间"这一基本动作的基础上，额外处理两种特殊情况：LEFT SEMI/ANTI JOIN 右表的特殊屏蔽，以及多层嵌套 JOIN 中子节点向更外层作用域的递归传播。
  // ns（SqlValidatorNamespace）：待添加的子命名空间（比如 JOIN 左边或右边的表所对应的命名空间）
  // alias（String）：该子命名空间对应的别名。
  // nullable（boolean）：该子命名空间的行类型是否应该被强制标记为可空
  @Override public void addChild(SqlValidatorNamespace ns, String alias,
      boolean nullable) {
    super.addChild(ns, alias, nullable);

    // LEFT SEMI JOIN and LEFT ANTI JOIN can only come from Babel.
    // **背景知识**：`LEFT SEMI JOIN`（左半连接）和 `LEFT ANTI JOIN`（左反连接）是两种特殊的 JOIN 类型，
    // 它们的语义特点是——**最终结果只会输出左表的列，右表仅仅作为过滤条件参与运算（判断左表的行是否存在/不存在满足条件的右表匹配行），右表本身的列不会出现在结果中，也不应该在外部作用域中被继续引用**。
    // `stripAs(join.getRight()) == ns.getNode()`：`stripAs` 用于剥离节点外层可能包裹的 `AS` 别名装饰，取到最内层的真实节点。这里判断——当前正在添加的这个子命名空间 `ns`，其对应的节点是否恰好就是这个 JOIN 的**右表**。
    // 也就是说，右表命名空间虽然已经被 `super.addChild` 加入了当前 `JoinScope` 自己的子列表（用于在当前作用域内部的 `ON` 条件里可以正常引用它做过滤判断），
    // 但**不会被继续传播到更外层的作用域**，从而保证外层查询（比如最外层 SELECT）无法引用这个右表的列，这正确反映了 SEMI/ANTI JOIN "右表列不对外可见"的语义。
    if ((join.getJoinType() == LEFT_SEMI_JOIN
        || join.getJoinType() == LEFT_ANTI_JOIN)
        && stripAs(join.getRight()) == ns.getNode()) {
      // Ignore the right hand side.
      return;
    }
    // **为什么要判断 `usingScope != parent`**：如果两者相同，说明当前 JOIN 并不是嵌套在另一个 JOIN 内部（`usingScope` 和 `parent` 本来就是一回事，不存在"多一层需要传播"的情况），此时不需要额外的递归传播动作（避免重复添加）。
    // **触发条件成立时**：说明当前正处于"JOIN 嵌套 JOIN"的场景（正如 Javadoc 开头例子中的 `(A JOIN B) JOIN C` 结构），
    // 此时需要把这个子命名空间**递归地**也添加到 `usingScope`（即更外层的作用域，可能是外层的 `JoinScope` 或者是外层的 `SelectScope`）中去。
    if ((usingScope != null) && (usingScope != parent)) {
      // We're looking at a join within a join. Recursively add this
      // child to its parent scope too. Example:
      //
      //   select *
      //   from (a join b on expr1)
      //   join c on expr2
      //   where expr3
      //
      // 'a' is a child namespace of 'a join b' and also of
      // 'a join b join c'.
      usingScope.addChild(ns, alias, nullable);
    }
  }

  @Override public @Nullable SqlWindow lookupWindow(String name) {
    // Lookup window in enclosing select.
    if (usingScope != null) {
      return usingScope.lookupWindow(name);
    } else {
      return null;
    }
  }

  /**
   * Returns the scope which is used for resolving USING clause.
   */
  public @Nullable SqlValidatorScope getUsingScope() {
    return usingScope;
  }

  @Override public boolean isWithin(SqlValidatorScope scope2) {
    if (this == scope2) {
      return true;
    }
    // go from the JOIN to the enclosing SELECT
    return requireNonNull(usingScope, "usingScope").isWithin(scope2);
  }
}
