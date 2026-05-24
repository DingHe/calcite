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
package org.apache.calcite.rel.core;

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelInput;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.hint.Hintable;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexChecker;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Permutation;
import org.apache.calcite.util.Util;
import org.apache.calcite.util.mapping.MappingType;
import org.apache.calcite.util.mapping.Mappings;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Relational expression that computes a set of
 * 'select expressions' from its input relational expression.
 *
 * @see org.apache.calcite.rel.logical.LogicalProject
 */
// Project 是 Apache Calcite 项目中极其核心的关系代数基类。
// 它继承自 SingleRel，在 SQL 或逻辑执行计划中，它直接对应于标准关系代数中的“投影（Projection）”操作，即 SQL 语句中的 SELECT 列表。
// Project 算子主要用于处理一元数据流的“形状转化”。它的核心职责包括：
// 字段裁剪与挑选：从输入节点（Input）吐出的众多列中，只挑选出业务需要的某些列向下游传递。
// 表达式计算与衍生：在 SELECT 列表中执行标量计算、函数调用（例如 amount * price、UPPER(name) 等），从而派生出新的列。
// 列重命名（Alias）：为计算出的新列或原有列赋予新的别名（如 AS total_price）。
// 承载高级计算（如窗口函数）：当 SQL 包含 OVER 窗口函数时，Calcite 通常也会将其包装在 Project 的表达式中，并作为特征标记提供给优化器。

public abstract class Project extends SingleRel implements Hintable {
  //~ Instance fields --------------------------------------------------------

  // 当前投影算子所包含的核心投影表达式列表。
  // 每一个 RexNode（行级表达式）对应 SELECT 列表中的一项。
  // 它可以是一个简单的字段引用（RexInputRef），也可以是复杂的算术或函数调用表达式。使用 ImmutableList 确保表达式在算子构建后不可篡改。
  protected final ImmutableList<RexNode> exps;
  // 附加在当前投影算子上的 SQL 提示（Hints）列表。
  // 实现了 Hintable 接口。允许开发人员在 SQL 顶层传入特定路由或优化提示（例如 SELECT /*+ MAX_EXECUTION_TIME(1000) */ a FROM b），这些提示将伴随 Project 节点流入优化器。
  protected final ImmutableList<RelHint> hints;
  // 当前投影算子所捕获或设置的关联变量（Correlation ID）集合。
  // 在处理某些特定的嵌套关联子查询（Correlated Subquery）时，投影层可能会捕获部分上游变量并向下游的嵌套表达式（如窗口函数内部）透传。
  protected final ImmutableSet<CorrelationId> variablesSet;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a Project.
   *
   * @param cluster  Cluster that this relational expression belongs to
   * @param traits   Traits of this relational expression
   * @param hints    Hints of this relation expression
   * @param input    Input relational expression
   * @param projects List of expressions for the input columns
   * @param rowType  Output row type
   * @param variableSet Correlation variables set by this relational expression
   *                    to be used by nested expressions
   */
  @SuppressWarnings("method.invocation.invalid")
  protected Project(
      RelOptCluster cluster,
      RelTraitSet traits,
      List<RelHint> hints,
      RelNode input,
      List<? extends RexNode> projects,
      RelDataType rowType,
      Set<CorrelationId> variableSet) {
    super(cluster, traits, input);
    assert rowType != null;
    this.exps = ImmutableList.copyOf(projects);
    this.hints = ImmutableList.copyOf(hints);
    this.rowType = rowType; //返回的行类型
    this.variablesSet = ImmutableSet.copyOf(variableSet);
    assert isValid(Litmus.THROW, null);
  }

  @Deprecated // to be removed before 2.0
  protected Project(
      RelOptCluster cluster,
      RelTraitSet traits,
      List<RelHint> hints,
      RelNode input,
      List<? extends RexNode> projects,
      RelDataType rowType) {
    this(cluster, traits, hints, input, projects, rowType, ImmutableSet.of());
  }

  @Deprecated // to be removed before 2.0
  protected Project(RelOptCluster cluster, RelTraitSet traits,
      RelNode input, List<? extends RexNode> projects, RelDataType rowType) {
    this(cluster, traits, ImmutableList.of(), input, projects, rowType, ImmutableSet.of());
  }

  @Deprecated // to be removed before 2.0
  protected Project(RelOptCluster cluster, RelTraitSet traitSet, RelNode input,
      List<? extends RexNode> projects, RelDataType rowType, int flags) {
    this(cluster, traitSet, ImmutableList.of(), input, projects, rowType, ImmutableSet.of());
    Util.discard(flags);
  }

  /**
   * Creates a Project by parsing serialized output.
   */
  protected Project(RelInput input) {
    this(input.getCluster(),
        input.getTraitSet(),
        ImmutableList.of(),
        input.getInput(),
        requireNonNull(input.getExpressionList("exprs"), "exprs"),
        input.getRowType("exprs", "fields"),
        ImmutableSet.copyOf(
            Util.transform(
                Optional.ofNullable(input.getIntegerList("variablesSet"))
                    .orElse(ImmutableList.of()),
                id -> new CorrelationId(id))));
  }

  //~ Methods ----------------------------------------------------------------

  @Override public final RelNode copy(RelTraitSet traitSet,
      List<RelNode> inputs) {
    return copy(traitSet, sole(inputs), exps, getRowType());
  }

  /**
   * Copies a project.
   *
   * @param traitSet Traits
   * @param input Input
   * @param projects Project expressions
   * @param rowType Output row type
   * @return New {@code Project} if any parameter differs from the value of this
   *   {@code Project}, or just {@code this} if all the parameters are
   *   the same
   *
   * @see #copy(RelTraitSet, List)
   */
  public abstract Project copy(RelTraitSet traitSet, RelNode input,
      List<RexNode> projects, RelDataType rowType);

  @Deprecated // to be removed before 2.0
  public Project copy(RelTraitSet traitSet, RelNode input,
      List<RexNode> projects, RelDataType rowType, int flags) {
    Util.discard(flags);
    return copy(traitSet, input, projects, rowType);
  }

  @Deprecated // to be removed before 2.0
  public boolean isBoxed() {
    return true;
  }

  @Override public RelNode accept(RexShuttle shuttle) {
    List<RexNode> exps = shuttle.apply(this.exps);
    if (this.exps == exps) {
      return this;
    }
    final RelDataType rowType =
        RexUtil.createStructType(
            getInput().getCluster().getTypeFactory(),
            exps,
            getRowType().getFieldNames(),
            null);
    return copy(traitSet, getInput(), exps, rowType);
  }

  /**
   * Returns the project expressions.
   *
   * @return Project expressions
   */
  public List<RexNode> getProjects() {
    return exps;
  } //返回project的表达式

  /**
   * Returns a list of (expression, name) pairs. Convenient for various
   * transformations.
   *
   * @return List of (expression, name) pairs
   */
  public final List<Pair<RexNode, String>> getNamedProjects() {
    return Pair.zip(getProjects(), getRowType().getFieldNames());
  }

  /** Returns a list of project expressions, each of which is wrapped in a
   * call to {@code AS} if its field name differs from the default.
   *
   * <p>This method has a similar effect to {@link #getNamedProjects()},
   * but the single list is easier to manage.
   *
   * @see org.apache.calcite.tools.RelBuilder#alias(RexNode, String)
   */
  // TODO: move to RelBuilder?
  // TODO: replace calls to getNamedProjects
  public final List<RexNode> getAliasedProjects(RelBuilder b) {
    final ImmutableList.Builder<RexNode> builder = ImmutableList.builder();
    Pair.forEach(exps, getRowType().getFieldList(), (e, f) -> {
      builder.add(b.alias(e, f.getName()));
    });
    return builder.build();
  }

  @Override public ImmutableList<RelHint> getHints() {
    return hints;
  }

  @Deprecated // to be removed before 2.0
  public int getFlags() {
    return 1;
  }

  /** Returns whether this Project contains any windowed-aggregate functions. */
  public final boolean containsOver() {
    return RexOver.containsOver(getProjects(), null);
  }

  @Override public boolean isValid(Litmus litmus, @Nullable Context context) {
    // 首先调用父类（SingleRel / AbstractRelNode）的校验逻辑。
    if (!super.isValid(litmus, context)) {
      return litmus.fail(null);
    }
    // 比对当前算子内部的表达式列表 exps 的推导类型，是否与当前算子宣称的输出行类型 getRowType() 严格兼容。
    if (!RexUtil.compatibleTypes(exps, getRowType(), litmus)) {
      return litmus.fail("incompatible types");
    }
    // 实例化一个表达式检查器 RexChecker，并传入子算子的输出行类型（getInput().getRowType()）作为上下文基准，
    // 然后使用访问者模式（accept）遍历每一个 SELECT 表达式。
    // 校验目的：确保表达式内部的所有引用都是合法的。
    // 例：上游子算子（比如一张表）总共只有 3 列（索引 0, 1, 2）。
    // 如果在 Project 算子中出现了一个 RexInputRef(index=5)，意味着你在 SELECT 阶段去访问一个根本不存在的第 6 列。
    // RexChecker 扫描到这里时就会将错误计数器加 1，从而触发该关卡的失败。
    RexChecker checker =
        new RexChecker(
            getInput().getRowType(), context, litmus);
    for (RexNode exp : exps) {
      exp.accept(checker);
      if (checker.getFailureCount() > 0) {
        return litmus.fail("{} failures in expression {}",
            checker.getFailureCount(), exp);
      }
    }
    // 别名唯一性校验
    // 调用 Util.isDistinct 校验当前 Project 抛给上层的外部 Schema 中，所有的列名（Field Names）是否是唯一的。
    if (!Util.isDistinct(getRowType().getFieldNames())) {
      return litmus.fail("field names not distinct: {}", rowType);
    }
    //CHECKSTYLE: IGNORE 1
    // 代码末尾有一段被 if (false && ...) 永远关闭的“僵尸校验”：
    if (false && !Util.isDistinct(Util.transform(exps, RexNode::toString))) {
      // Projecting the same expression twice is usually a bad idea,
      // because it may create expressions downstream which are equivalent
      // but which look different. We can't ban duplicate projects,
      // because we need to allow
      //
      //  SELECT a, b FROM c UNION SELECT x, x FROM z
      return litmus.fail("duplicate expressions: {}", exps);
    }
    return litmus.succeed();
  }
  // 用来计算自身执行代价的实现
  // Calcite 的代价模型主要从三个维度来衡量：行数（Rows）、CPU 开销 和 I/O 开销。下面我们来详细拆解这段代码的计算逻辑和设计哲学。
  @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
      RelMetadataQuery mq) {
    // 通过元数据查询引擎 RelMetadataQuery，获取当前投影算子的输入节点（子节点）的估算行数。
    double dRows = mq.getRowCount(getInput());
    // CPU 的代价被定义为：输入行数 × 投影表达式的数量（exps.size()）
    // 虽然 Calcite 在这里做了一个简化处理（把简单的列引用和复杂的函数计算都等同视之，每个表达式计为 1 个单位的 CPU 开销），
    // 但这已经能非常有效地让优化器感知到：SELECT 的列和计算表达式越多，这个算子就越“重”。
    double dCpu = dRows * exps.size();
    // 将 I/O 代价直接设为 0。
    double dIo = 0;
    // 通过优化器持有的代价工厂（CostFactory），将计算出的 dRows（行数开销）、dCpu（CPU开销）、dIo（I/O开销）组装成一个标准的 RelOptCost 对象并返回。
    return planner.getCostFactory().makeCost(dRows, dCpu, dIo);
  }

  /**
   * Returns the number of expressions at the front of an array which are
   * simply projections of the same field.
   *
   * @param refs References
   * @return the index of the first non-trivial expression, or list.size otherwise
   */
  // 计算在当前的 SELECT 表达式列表中，从最左边（前缀）开始，有多少个列属于“毫无加工、原样透传”的平凡投影（Trivial Projection）。
  // 什么是“平凡投影（Trivial Projection）”？
  // 在关系代数中，如果一个投影表达式仅仅是按照上游输入表原有的列顺序和索引，原封不动地把列拿过来，这就叫平凡投影。
  private static int countTrivial(List<RexNode> refs) {
    for (int i = 0; i < refs.size(); i++) {
      RexNode ref = refs.get(i);
      if (!(ref instanceof RexInputRef)
          || ((RexInputRef) ref).getIndex() != i) {
        // // 一旦遇到不满足条件的，立刻返回当前索引值
        return i;
      }
    }
    // 如果全列表都满足，返回列表总长度
    return refs.size();
  }

  @Override public Set<CorrelationId> getVariablesSet() {
    return variablesSet;
  }

  // 核心职责是：向计划打印器（RelWriter）登记当前投影算子的特有属性（如表达式、别名、关联变量）
  // 这段代码之所以写得相对复杂，是因为它必须同时满足两种截然不同的应用场景：
  // 优化器内部的摘要生成（DIGEST 模式）：核心是极简化、去重，让优化器能高效比对算子。
  // 面向用户的执行计划输出（EXPLAIN PLAN 模式）：核心是可读性、直观性，让人类开发者能一眼看懂 SQL 是如何被解析的。
  @Override public RelWriter explainTerms(RelWriter pw) {
    super.explainTerms(pw);
    pw.itemIf("variablesSet", variablesSet, !variablesSet.isEmpty());
    // Skip writing field names so the optimizer can reuse the projects that differ in
    // field names only
    if (pw.getDetailLevel() == SqlExplainLevel.DIGEST_ATTRIBUTES) {
      final int firstNonTrivial = countTrivial(exps);
      if (firstNonTrivial == 1) {
        pw.item("inputs", "0");
      } else if (firstNonTrivial != 0) {
        pw.item("inputs", "0.." + (firstNonTrivial - 1));
      }
      if (firstNonTrivial != exps.size()) {
        pw.item("exprs", exps.subList(firstNonTrivial, exps.size()));
      }
      return pw;
    }

    if (pw.nest()) {
      pw.item("fields", getRowType().getFieldNames());
      pw.item("exprs", exps);
    } else {
      for (Ord<RelDataTypeField> field : Ord.zip(getRowType().getFieldList())) {
        String fieldName = field.e.getName();
        if (fieldName == null) {
          fieldName = "field#" + field.i;
        }
        pw.item(fieldName, exps.get(field.i));
      }
    }

    return pw;
  }

  @API(since = "1.24", status = API.Status.INTERNAL)
  @EnsuresNonNullIf(expression = "#1", result = true)
  protected boolean deepEquals0(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    Project o = (Project) obj;
    return traitSet.equals(o.traitSet)
        && input.deepEquals(o.input)
        && exps.equals(o.exps)
        && hints.equals(o.hints)
        && getRowType().equalsSansFieldNames(o.getRowType());
  }

  @API(since = "1.24", status = API.Status.INTERNAL)
  protected int deepHashCode0() {
    return Objects.hash(traitSet, input.deepHashCode(), exps, hints);
  }

  /**
   * Returns a mapping, or null if this projection is not a mapping.
   *
   * @return Mapping, or null if this projection is not a mapping
   */
  // 探查当前的 SELECT 列表是否仅仅是把上游输入表的某些列直接拿过来（不带任何加减乘除或函数加工），如果是，则建立并返回一个“输入列到输出列”的单向映射矩阵（TargetMapping）。
  //
  public Mappings.@Nullable TargetMapping getMapping() {
    return getMapping(getInput().getRowType().getFieldCount(), exps);
  }

  /**
   * Returns a mapping of a set of project expressions.
   *
   * <p>The mapping is an inverse surjection.
   * Every target has a source field, but no
   * source has more than one target.
   * Thus you can safely call
   * {@link org.apache.calcite.util.mapping.Mappings.TargetMapping#getSourceOpt(int)}.
   *
   * @param inputFieldCount Number of input fields
   * @param projects Project expressions
   * @return Mapping of a set of project expressions, or null if projection is
   * not a mapping
   */
  // getMapping() 在 Calcite 的规则优化阶段（比如谓词下推、列裁剪）是绝对的明星方法。
  // 职责是用严格的算法去校验并构建一个“反单射（Inverse Surjection）”映射矩阵。
  // 该方法建立的映射是一种 Inverse Surjection（反单射，也有地方称为满射的逆）。在 Calcite 的多对多映射体系中，它代表以下硬性数学约束：
  // 每个目标（Target）都必须有一个明确的源（Source）：这意味着 SELECT 列表里的每一项都必须是原装列，不能有计算，不能有常量。
  // 没有任何一个源（Source）可以拥有超过一个的目标（Target）：这意味着原表的某一列，在 SELECT 列表里绝对不能重复出现。
  public static Mappings.@Nullable TargetMapping getMapping(int inputFieldCount,
      List<? extends RexNode> projects) {
    // 如果 SELECT 列表的表达式数量（projects.size()）比上游输入表的总列数（inputFieldCount）还要多，直接返回 null。
    if (inputFieldCount < projects.size()) {
      return null; // surjection is not possible
    }
    Mappings.TargetMapping mapping =
        Mappings.create(MappingType.INVERSE_SURJECTION,
            inputFieldCount, projects.size());
    for (Ord<RexNode> exp : Ord.<RexNode>zip(projects)) {
      // 必须全部是纯列引用
      if (!(exp.e instanceof RexInputRef)) {
        return null;
      }

      int source = ((RexInputRef) exp.e).getIndex();
      // 第二道防线：唯一性碰撞检测
      if (mapping.getTargetOpt(source) != -1) {
        return null;
      }
      mapping.set(source, exp.i);
    }
    return mapping;
  }

  /**
   * Returns a partial mapping of a set of project expressions.
   *
   * <p>The mapping is an inverse function.
   * Every target has a source field, but
   * a source might have 0, 1 or more targets.
   * Project expressions that do not consist of
   * a mapping are ignored.
   *
   * @param inputFieldCount Number of input fields
   * @param projects Project expressions
   * @return Mapping of a set of project expressions, never null
   */
  public static Mappings.TargetMapping getPartialMapping(int inputFieldCount,
      List<? extends RexNode> projects) {
    Mappings.TargetMapping mapping =
        Mappings.create(MappingType.INVERSE_FUNCTION,
            inputFieldCount, projects.size());
    for (Ord<RexNode> exp : Ord.<RexNode>zip(projects)) {
      if (exp.e instanceof RexInputRef) {
        mapping.set(((RexInputRef) exp.e).getIndex(), exp.i);
      }
    }
    return mapping;
  }

  /**
   * Returns a permutation, if this projection is merely a permutation of its
   * input fields; otherwise null.
   *
   * @return Permutation, if this projection is merely a permutation of its
   *   input fields; otherwise null
   */
  public @Nullable Permutation getPermutation() {
    return getPermutation(getInput().getRowType().getFieldCount(), exps);
  }

  /**
   * Returns a permutation, if this projection is merely a permutation of its
   * input fields; otherwise null.
   */
  // 核心作用是：判定当前的投影操作是否“仅仅是对输入列进行了位置重排（Permutation/置换）”。
  // 如果判定成功，它会返回一个记录了位置置换关系的 Permutation 对象；只要有任何一列被修改、裁剪或重复，它就会果断返回 null。
  // 在 SQL 中，有时我们写 SELECT 列表，既没有减少列的个数，也没有对列做任何加减乘除计算，而仅仅是打乱了列的先后顺序。这种操作在数学上被称为置换。
  public static @Nullable Permutation getPermutation(int inputFieldCount,
      List<? extends RexNode> projects) {
    final int fieldCount = projects.size();
    // 关卡 1：数量绝对对齐检查
    if (fieldCount != inputFieldCount) {
      return null;
    }
    final Permutation permutation = new Permutation(fieldCount);
    final Set<Integer> alreadyProjected = new HashSet<>(fieldCount);
    for (int i = 0; i < fieldCount; ++i) {
      final RexNode exp = projects.get(i);
      // 关卡 2：必须是纯列引用
      if (exp instanceof RexInputRef) {
        final int index = ((RexInputRef) exp).getIndex();
        // 关卡 3：去重检查（防止同一列被投影多次）
        if (!alreadyProjected.add(index)) {
          return null;
        }
        // 关卡 4：记录置换映射
        permutation.set(i, index);
      } else {
        return null;
      }
    }
    return permutation;
  }

  /**
   * Checks whether this is a functional mapping.
   * Every output is a source field, but
   * a source field may appear as zero, one, or more output fields.
   */
  public boolean isMapping() {
    for (RexNode exp : exps) {
      if (!(exp instanceof RexInputRef)) {
        return false;
      }
    }
    return true;
  }

  //~ Inner Classes ----------------------------------------------------------

  /** No longer used. */
  @Deprecated // to be removed before 2.0
  public static class Flags {
    public static final int ANON_FIELDS = 2;
    public static final int BOXED = 1;
    public static final int NONE = 0;
  }
}
