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
package org.apache.calcite.rel;

import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.runtime.ImmutablePairList;
import org.apache.calcite.runtime.PairList;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.mapping.Mappings;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Root of a tree of {@link RelNode}.
 *
 * <p>One important reason that RelRoot exists is to deal with queries like
 *  此类的出现主要是解决order by 字段不在select语句中的场景
 * <blockquote><code>SELECT name
 * FROM emp
 * ORDER BY empno DESC</code></blockquote>
 *
 * <p>Calcite knows that the result must be sorted, but cannot represent its
 * sort order as a collation, because {@code empno} is not a field in the
 * result.
 *
 * <p>Instead we represent this as
 *
 * <blockquote><code>RelRoot: {
 *   rel: Sort($1 DESC)
 *          Project(name, empno)
 *            TableScan(EMP)
 *   fields: [0]
 *   collation: [1 DESC]
 * }</code></blockquote>
 *
 * <p>Note that the {@code empno} field is present in the result, but the
 * {@code fields} mask tells the consumer to throw it away.
 *
 * <p>Another use case is queries like this:
 *
 * <blockquote><code>SELECT name AS n, name AS n2, empno AS n
 * FROM emp</code></blockquote>
 *
 * <p>The there are multiple uses of the {@code name} field. and there are
 * multiple columns aliased as {@code n}. You can represent this as
 *
 * <blockquote><code>RelRoot: {
 *   rel: Project(name, empno)
 *          TableScan(EMP)
 *   fields: [(0, "n"), (0, "n2"), (1, "n")]
 *   collation: []
 * }</code></blockquote>
 */
// RelNode（关系表达式节点）用于构建抽象语法树（AST）转换后的关系代数树。
// 然而，单纯的 RelNode 树有时无法完美表达 SQL 查询最终要求的输出结构。RelRoot 的出现就是为了作为 RelNode 树的“顶层包装”，它的主要作用包括：
// 解决“排序字段不在选择列表中”的问题： 例如 SELECT name FROM emp ORDER BY empno。在关系代数中，必须先经过 Project(name, empno) 才能让顶层的 Sort(empno) 看到 empno 字段。但用户最终只需要 name。RelRoot 通过内部的 fields 掩码（Mask）记录了最终只输出第 0 列（name），指导最终的消费端把 empno 丢弃。
// 处理别名与重复列：
// 例如 SELECT name AS n, name AS n2 FROM emp。底层 RelNode 只需要读取一次 name 字段，而 RelRoot 可以在顶层通过投影映射将其映射为两个不同的别名（n 和 n2），避免了底层节点做无谓的重复计算。
// 保留查询的元数据：
// 它记录了经过校验后的原始行类型（validatedRowType）、SQL 语义类型（SqlKind，如 SELECT, UPDATE）以及全局排序属性（collation）和提示（hints）。
public class RelRoot {
  // 核心关系代数节点。代表当前关系表达式树的根节点（例如一个 Sort 或者是 Project 节点）。所有的优化器优化操作主要针对这个树进行。
  public final RelNode rel;
  // 经校验后的原始行类型。由 SqlValidator 校验后得到的最初 SQL 查询应当返回的类型（包含字段名和类型），用于和最终生成的 rel 节点的类型做对比。
  public final RelDataType validatedRowType;
  // SQL 节点类型枚举。标记此查询的顶级操作类型，例如 SELECT, INSERT, UPDATE, DELETE 等，帮助后续处理判断是否为 DML 语句。
  public final SqlKind kind;
  // 字段映射关系（索引 -> 别名）。
  // 成对的列表，左值（Integer）是底层 rel 节点的字段索引，右值（String）是最终输出的列名/别名。它决定了对外暴露哪些列。
  public final ImmutablePairList<Integer, String> fields;
  // 整体排序性（Collation）。
  // 表示该查询要求的最终输出全局排序规则（包含排序列和正序/倒序抗性）。
  public final RelCollation collation;
  // 查询提示列表。
  // 存储作用在全局或顶层的 SQL Hints（如 /*+ MERGE */ 等），用于指导优化器。
  public final ImmutableList<RelHint> hints;

  /**
   * Creates a RelRoot.
   *
   * @param validatedRowType Original row type returned by query validator
   * @param kind Type of query (SELECT, UPDATE, ...)
   */

  public RelRoot(RelNode rel, RelDataType validatedRowType, SqlKind kind,
      Iterable<? extends Map.Entry<Integer, String>> fields,
      RelCollation collation, List<RelHint> hints) {
    this.rel = rel;
    this.validatedRowType = validatedRowType;
    this.kind = kind;
    this.fields = ImmutablePairList.copyOf(fields);
    this.collation = requireNonNull(collation, "collation");
    this.hints = ImmutableList.copyOf(hints);
  }

  /** Creates a simple RelRoot. */
  public static RelRoot of(RelNode rel, SqlKind kind) {
    return of(rel, rel.getRowType(), kind);
  }

  /** Creates a simple RelRoot. */
  public static RelRoot of(RelNode rel, RelDataType rowType, SqlKind kind) {
    final PairList<Integer, String> fields = PairList.of();
    Pair.forEach(ImmutableIntList.identity(rowType.getFieldCount()),
        rowType.getFieldNames(), fields::add);
    return new RelRoot(rel, rowType, kind, fields, RelCollations.EMPTY,
        ImmutableList.of());
  }

  @Override public String toString() {
    return "Root {kind: " + kind
        + ", rel: " + rel
        + ", rowType: " + validatedRowType
        + ", fields: " + fields
        + ", collation: " + collation + "}";
  }

  /** Creates a copy of this RelRoot, assigning a {@link RelNode}. */
  public RelRoot withRel(RelNode rel) {
    if (rel == this.rel) {
      return this;
    }
    return new RelRoot(rel, validatedRowType, kind, fields, collation, hints);
  }

  /** Creates a copy, assigning a new kind. */
  public RelRoot withKind(SqlKind kind) {
    if (kind == this.kind) {
      return this;
    }
    return new RelRoot(rel, validatedRowType, kind, fields, collation, hints);
  }

  public RelRoot withCollation(RelCollation collation) {
    return new RelRoot(rel, validatedRowType, kind, fields, collation, hints);
  }

  /** Creates a copy, assigning the query hints. */
  public RelRoot withHints(List<RelHint> hints) {
    return new RelRoot(rel, validatedRowType, kind, fields, collation, hints);
  }

  /** Returns the root relational expression, creating a {@link LogicalProject}
   * if necessary to remove fields that are not needed. */
  public RelNode project() {
    return project(false);
  }

  /** Returns the root relational expression as a {@link LogicalProject}.
   *
   * @param force Create a Project even if all fields are used */
  // 根据 fields 映射关系，决定是否在 rel 之上强行追加一层 LogicalProject。
  // 如果 isRefTrivial() 为 true（即当前节点的字段输出顺序及数量已经完美契合预期的字段），且不需要强制生成 (force=false)，或者它本身就是 DML/已经是一个 LogicalProject，则直接返回底层的 rel。
  // 否则，它会利用 RexBuilder 根据 fields 中的索引创建一系列输入引用（RexInputRef），并在最外层套一个 LogicalProject 返回。以此来裁剪掉不需要的字段（如前文提到的 empno）。
  public RelNode project(boolean force) {
    if (isRefTrivial()
        && (SqlKind.DML.contains(kind)
            || !force
            || rel instanceof LogicalProject)) {
      return rel;
    }
    final List<RexNode> projects = new ArrayList<>(fields.size());
    final RexBuilder rexBuilder = rel.getCluster().getRexBuilder();
    fields.forEach((i, name) -> projects.add(rexBuilder.makeInputRef(rel, i)));
    return LogicalProject.create(rel, hints, projects, fields.rightList(),
        ImmutableSet.of());
  }
  // "Trivial" 在这里意为“平凡的/微不足道的”，即底层结构是否已经和顶层要求完全一致，不需要做额外转换。
  // 检查列名是否完全一致。
  // 对比 fields 中的别名列表与底层 rel 节点的字段名列表是否完全相同。
  public boolean isNameTrivial() {
    final RelDataType inputRowType = rel.getRowType();
    return fields.rightList().equals(inputRowType.getFieldNames());
  }
  // 检查列的引用和索引映射是否是一对一的恒等映射（Identity Mapping）。
  public boolean isRefTrivial() {
    if (SqlKind.DML.contains(kind)) {
      // DML statements return a single count column.
      // The validated type is of the SELECT.
      // Still, we regard the mapping as trivial.
      return true;
    }
    final RelDataType inputRowType = rel.getRowType();
    return Mappings.isIdentity(fields.leftList(), inputRowType.getFieldCount());
  }
  // 检查排序特性是否完全一致。
  public boolean isCollationTrivial() {
    final List<RelCollation> collations = rel.getTraitSet()
        .getTraits(RelCollationTraitDef.INSTANCE);
    return collations != null
        && collations.size() == 1
        && collations.get(0).equals(collation);
  }
}
