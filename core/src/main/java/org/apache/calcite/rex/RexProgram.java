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

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.plan.RelOptPredicateList;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelInput;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.externalize.RelJsonWriter;
import org.apache.calcite.rel.externalize.RelWriterImpl;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Permutation;
import org.apache.calcite.util.mapping.MappingType;
import org.apache.calcite.util.mapping.Mappings;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import com.google.errorprone.annotations.CheckReturnValue;

import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

import static java.util.Objects.requireNonNull;

/**
 * A collection of expressions which read inputs, compute output expressions,
 * and optionally use a condition to filter rows.
 *
 * <p>Programs are immutable. It may help to use a {@link RexProgramBuilder},
 * which has the same relationship to {@link RexProgram} as {@link StringBuilder}
 * has to {@link String}.
 *
 * <p>A program can contain aggregate functions. If it does, the arguments to
 * each aggregate function must be an {@link RexInputRef}.
 *
 * @see RexProgramBuilder
 */
// RexProgram 是一个具有里程碑意义的核心数据结构。它专门服务于 Calc（计算节点）算子，是整个优化器走向单机高性能流水线（如利用 Linq4j 和 Janino 动态生成 Java 字节码）的核心底座。
// RexProgram 类的核心作用：打平一切的“公共表达式大本营”
// 传统的 SQL 表达式采用的是深度嵌套的树状拓扑（AST 树）。例如一个简单的投影：SELECT price * qty, (price * qty) * tax FROM orders。
// 传统树状问题：在这个表达式里，price * qty 被重复计算了两次。当树很深时，不仅内存占用极大，且公共子表达式消除（CSE, Common Subexpression Elimination） 的计算复杂度极高。
// RexProgram 彻底颠覆了这种拓扑。它是一个将行级过滤条件（Condition）与字段输出投影（Projects）完美揉合在一起的、不可变的线性计算指令集。
// 它将树状图彻底“打平（Flatten）” 为一个线性的数组，并在内部通过局部下标引用（RexLocalRef）来组织依赖。上述 SQL 在 RexProgram 内部的扁平化形态如下
// 终极核心效益：通过将大树解构成线性数组，RexProgram 使得所有的公共表达式天然共享同一个数组下标（完美消除公共子表达式），并且极易被翻译为循环展开的、无任何多余对象开销的物理微指令代码。
public class RexProgram {
  //~ Instance fields --------------------------------------------------------

  /**
   * First stage of expression evaluation. The expressions in this array can
   * refer to inputs (using input ordinal #0) or previous expressions in the
   * array (using input ordinal #1).
   */
  // 一阶段扁平化公共表达式大池子。
  // 存放当前节点计算要用到的所有细粒度标量算子（如 RexInputRef、RexLiteral、RexCall ）。
  // 它有极严格的拓扑顺序：排在前面的元素是原始输入，后面的元素只能引用前面的元素（利用 RexLocalRef），绝不允许循环依赖，也不允许前向引用（Forward Reference）。
  private final List<RexNode> exprs;

  /**
   * With {@link #condition}, the second stage of expression evaluation.
   */
  // 二阶段最终输出投影映射序列。
  // 长度与当前算子最终吐出的列数完全一致。里面存储的不是复杂的表达式树，而是纯粹的数组下标指针（RexLocalRef），直接勾连并锁定 exprs 池子中的某一项计算结果。
  private final List<RexLocalRef> projects;

  /**
   * The optional condition. If null, the calculator does not filter rows.
   */
  // 可选的行级布尔过滤条件指针。
  // 如果为 null 代表当前计算节点不进行任何 WHERE 过滤；如果不为 null，则它也是一个局部引用指针，锁定 exprs 数组里某一个最终算出来为 Boolean 类型的节点的下标。
  private final @Nullable RexLocalRef condition;
  // 上游输入行的元数据类型（Schema）。
  // 标记当前程序吞入的每一行包含哪些字段、字段名称及对应的数据类型。
  private final RelDataType inputRowType;
  // 当前程序最终吐出的输出行元数据类型（Schema）。
  // 标记通过 projects 映射输出后，最终交付给下游父算子的数据流列结构。
  private final RelDataType outputRowType;

  /**
   * Reference counts for each expression, computed on demand.
   */
  // 延迟计算的表达式引用计数计数器。
  // 数组长度与 exprs 一致。
  // 用来记录 exprs[i] 被后续表达式、projects 或 condition 累计引用的总次数。
  // 如果次数 > 1，则代表它是一个真正的公共子表达式，在代码生成时可以被安全地提炼成一个局部临时变量。
  private int @MonotonicNonNull[] refCounts;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a program.
   *
   * <p>The expressions must be valid: they must not contain common expressions,
   * forward references, or non-trivial aggregates.
   *
   * @param inputRowType  Input row type
   * @param exprs         Common expressions
   * @param projects      Projection expressions
   * @param condition     Condition expression. If null, calculator does not
   *                      filter rows
   * @param outputRowType Description of the row produced by the program
   */
  public RexProgram(
      RelDataType inputRowType,
      List<? extends RexNode> exprs,
      List<RexLocalRef> projects,
      @Nullable RexLocalRef condition,
      RelDataType outputRowType) {
    this.inputRowType = inputRowType;
    this.exprs = ImmutableList.copyOf(exprs);
    this.projects = ImmutableList.copyOf(projects);
    this.condition = condition;
    this.outputRowType = outputRowType;
    assert isValid(Litmus.THROW, null);
  }

  //~ Methods ----------------------------------------------------------------

  // REVIEW jvs 16-Oct-2006:  The description below is confusing.  I
  // think it means "none of the entries are null, there may be none,
  // and there is no further reduction into smaller common sub-expressions
  // possible"?

  /**
   * Returns the common sub-expressions of this program.
   *
   * <p>The list is never null but may be empty; each the expression in the
   * list is not null; and no further reduction into smaller common
   * sub-expressions is possible.
   */
  public List<RexNode> getExprList() {
    return exprs;
  }

  /**
   * Returns an array of references to the expressions which this program is
   * to project. Never null, may be empty.
   */
  public List<RexLocalRef> getProjectList() {
    return projects;
  }

  /**
   * Returns a list of project expressions and their field names.
   */
  public List<Pair<RexLocalRef, String>> getNamedProjects() {
    return new AbstractList<Pair<RexLocalRef, String>>() {
      @Override public int size() {
        return projects.size();
      }

      @Override public Pair<RexLocalRef, String> get(int index) {
        return Pair.of(
            projects.get(index),
            outputRowType.getFieldList().get(index).getName());
      }
    };
  }

  /**
   * Returns the field reference of this program's filter condition, or null
   * if there is no condition.
   */
  @Pure
  public @Nullable RexLocalRef getCondition() {
    return condition;
  }

  /**
   * Creates a program which calculates projections and filters rows based
   * upon a condition. Does not attempt to eliminate common sub-expressions.
   *
   * @param projectExprs  Project expressions
   * @param conditionExpr Condition on which to filter rows, or null if rows
   *                      are not to be filtered
   * @param outputRowType Output row type
   * @param rexBuilder    Builder of rex expressions
   * @return A program
   */
  public static RexProgram create(
      RelDataType inputRowType,
      List<? extends RexNode> projectExprs,
      @Nullable RexNode conditionExpr,
      RelDataType outputRowType,
      RexBuilder rexBuilder) {
    return create(inputRowType, projectExprs, conditionExpr,
        outputRowType.getFieldNames(), rexBuilder);
  }

  /**
   * Creates a program which calculates projections and filters rows based
   * upon a condition. Does not attempt to eliminate common sub-expressions.
   *
   * @param projectExprs  Project expressions
   * @param conditionExpr Condition on which to filter rows, or null if rows
   *                      are not to be filtered
   * @param fieldNames    Names of projected fields
   * @param rexBuilder    Builder of rex expressions
   * @return A program
   */
  // 作为一个高层门面（Facade），负责接收外界传入的、传统的树状“投影表达式列表”和“过滤条件表达式”，
  // 然后借助 RexProgramBuilder 这个“大熔炉”，将它们打平（Flatten）、去重并焊接成一个标准化的线性 RexProgram 指令集。
  public static RexProgram create(
      // 上游输入算子吐给当前节点的行元数据类型（Schema）
      // 让内部的 RexProgramBuilder 明确知道初始输入流里一共有多少列、每一列叫什么名字、是什么数据类型，以便正确解析表达式中的 RexInputRef（类似于 $0, $1）。
      RelDataType inputRowType,
      // 最终要输出的投影（SELECT 后面）表达式树列表。
      List<? extends RexNode> projectExprs,
      // 可选的行过滤（WHERE 后面）条件表达式树。
      // 同样是一棵常规的布尔表达式树（例如 a > 10），如果 SQL 里没有写 WHERE 过滤，则传入 null。
      @Nullable RexNode conditionExpr,
      // 输出投影列的别名（Alias）列表。
      @Nullable List<? extends @Nullable String> fieldNames,
      // 表达式构建器工厂。
      RexBuilder rexBuilder) {
    // 如果外部调用方没有提供输出列名列表（fieldNames == null），则调用 Collections.nCopies 制造一个长度与投影列数完全相同、里面全是 null 的虚拟不可变列表，用作占位符。
    if (fieldNames == null) {
      fieldNames = Collections.nCopies(projectExprs.size(), null);
    } else {
      assert fieldNames.size() == projectExprs.size()
          : "fieldNames=" + fieldNames
          + ", exprs=" + projectExprs;
    }
    // RexProgramBuilder 的关系就像 StringBuilder 之于 String。
    // 因为 RexProgram 本身是完全不可变的（Immutable），所有复杂的去重、打平、排序拓扑逻辑必须在一个可变的容器里完成。
    final RexProgramBuilder programBuilder =
        new RexProgramBuilder(inputRowType, rexBuilder);
    // 依次遍历每一列投影表达式树。
    // 调用 programBuilder.addProject(...)。这个方法非常重型，当一棵复杂的表达式大树被丢进 addProject 时，Builder 会使用深度优先遍历（DFS）把这棵树彻底“撕碎”并“打平”：
    // 它会把树上的每一个细粒度叶子节点、中间计算节点依次剥离出来。
    // 检查这些剥离出来的项在内部大池子里是否已经存在（公共子表达式消除）。如果存在，直接复用其数组下标；如果不存在，将其追加进大池子尾部。
    for (int i = 0; i < projectExprs.size(); i++) {
      programBuilder.addProject(projectExprs.get(i), fieldNames.get(i));
    }
    // Builder 也会采用 DFS 将这棵布尔条件树彻底打平，消灭掉条件树里与前面投影列重复的计算。
    if (conditionExpr != null) {
      programBuilder.addCondition(conditionExpr);
    }
    return programBuilder.getProgram();
  }

  /**
   * Create a program from serialized output.
   * In this case, the input is mainly from the output json string of {@link RelJsonWriter}
   */
  // 典型的反序列化（Deserialization）工厂方法
  // 核心作用是：从一个已经序列化的媒介（通常是 SQL 优化器生成的 JSON 文本或分布式节点传输的执行计划字符串）中，重新读取并重构（还原）出一个完整的、内存中的不可变 RexProgram 对象。
  // RelInput input：代表一个关系表达式的输入读取器（元数据包装器）。
  public static RexProgram create(RelInput input) {
    final List<RexNode> exprs =
        requireNonNull(input.getExpressionList("exprs"), "exprs");
    final List<RexNode> projectRexNodes =
        requireNonNull(input.getExpressionList("projects"), "projects");
    final List<RexLocalRef> projects = new ArrayList<>(projectRexNodes.size());
    for (RexNode rexNode : projectRexNodes) {
      projects.add((RexLocalRef) rexNode);
    }
    final RelDataType inputType = input.getRowType("inputRowType");
    final RelDataType outputType = input.getRowType("outputRowType");
    final RexLocalRef condition = (RexLocalRef) input.getExpression("condition");
    return new RexProgram(inputType, exprs, projects, condition, outputType);
  }

  // description of this calc, chiefly intended for debugging
  @Override public String toString() {
    // Intended to produce similar output to explainCalc,
    // but without requiring a RelNode or RelOptPlanWriter.
    final RelWriterImpl pw =
        new RelWriterImpl(new PrintWriter(new StringWriter()));
    collectExplainTerms("", pw);
    return pw.simple();
  }

  /**
   * Writes an explanation of the expressions in this program to a plan
   * writer.
   *
   * @param pw Plan writer
   */
  // 主要职责是：根据传入的计划呈现器（RelWriter）的类型，采用不同的策略将当前 RexProgram 的内部核心组件（表达式池、投影、条件等）输出为可读的执行计划或可序列化的文本。
  public RelWriter explainCalc(RelWriter pw) {
    if (pw instanceof RelJsonWriter) {
      return pw
          .item("exprs", exprs)
          .item("projects", projects)
          .item("condition", condition)
          .item("inputRowType", inputRowType)
          .item("outputRowType", outputRowType);
    } else {
      return collectExplainTerms("", pw, pw.getDetailLevel());
    }
  }

  public RelWriter collectExplainTerms(
      String prefix,
      RelWriter pw) {
    return collectExplainTerms(
        prefix,
        pw,
        SqlExplainLevel.EXPPLAN_ATTRIBUTES);
  }

  /**
   * Collects the expressions in this program into a list of terms and values.
   *
   * @param prefix Prefix for term names, usually the empty string, but useful
   *               if a relational expression contains more than one program
   * @param pw     Plan writer
   */
  public RelWriter collectExplainTerms(
      String prefix,
      RelWriter pw,
      SqlExplainLevel level) {
    final List<RelDataTypeField> inFields = inputRowType.getFieldList();
    final List<RelDataTypeField> outFields = outputRowType.getFieldList();
    assert outFields.size() == projects.size()
        : "outFields.length=" + outFields.size()
        + ", projects.length=" + projects.size();
    pw.item(prefix + "expr#0"
            + ((inFields.size() > 1) ? (".." + (inFields.size() - 1)) : ""),
        "{inputs}");
    for (int i = inFields.size(); i < exprs.size(); i++) {
      pw.item(prefix + "expr#" + i, exprs.get(i));
    }

    // If a lot of the fields are simply projections of the underlying
    // expression, try to be a bit less verbose.
    int trivialCount = countTrivial(projects);

    switch (trivialCount) {
    case 0:
      break;
    case 1:
      trivialCount = 0;
      break;
    default:
      pw.item(prefix + "proj#0.." + (trivialCount - 1), "{exprs}");
      break;
    }

    final boolean withFieldNames = level != SqlExplainLevel.DIGEST_ATTRIBUTES;
    // Print the non-trivial fields with their names as they appear in the
    // output row type.
    for (int i = trivialCount; i < projects.size(); i++) {
      final String fieldName = withFieldNames ? prefix + outFields.get(i).getName() : prefix + i;
      pw.item(fieldName, projects.get(i));
    }
    if (condition != null) {
      pw.item(prefix + "$condition", condition);
    }
    return pw;
  }

  /**
   * Returns the number of expressions at the front of an array which are
   * simply projections of the same field.
   *
   * @param refs References
   */
  // 计算一个指针列表从最前端（Index = 0）开始，有多少个连续的表达式属于“平庸的（Trivial）”等值物理映射。
  // 什么是“平庸的映射（Trivial Projection）”？
  // 在编译器和数据库优化器中，如果一个投影操作（SELECT）只是原封不动地按顺序搬运上游输入列，没有发生任何实质性的列交换、列过滤或者数学计算，这个投影就被称为 Trivial（平庸/琐碎的）。
  // 情况 A（平庸）：上游输入是 (a, b, c)，下游 SELECT a, b。此时指针列表对应为 [$0, $1]。因为第 0 位置放 $0，第 1 位置放 $1，这属于完美的平庸映射。
  // 情况 B（非平庸）：上游输入是 (a, b, c)，下游 SELECT b, a。此时指针列表对应为 [$1, $0]。虽然没有发生计算，但因为列的顺序发生了解构和交换，这就不是平庸映射。
  private static int countTrivial(List<RexLocalRef> refs) {
    for (int i = 0; i < refs.size(); i++) {
      RexLocalRef ref = refs.get(i);
      if (ref.getIndex() != i) {
        return i;
      }
    }
    return refs.size();
  }

  /**
   * Returns the number of expressions in this program.
   */
  public int getExprCount() {
    return exprs.size()
        + projects.size()
        + ((condition == null) ? 0 : 1);
  }

  /**
   * Creates the identity program.
   */
  public static RexProgram createIdentity(RelDataType rowType) {
    return createIdentity(rowType, rowType);
  }

  /**
   * Creates a program that projects its input fields but with possibly
   * different names for the output fields.
   */
  public static RexProgram createIdentity(
      RelDataType rowType,
      RelDataType outputRowType) {
    if (rowType != outputRowType
        && !Pair.right(rowType.getFieldList()).equals(
            Pair.right(outputRowType.getFieldList()))) {
      throw new IllegalArgumentException(
          "field type mismatch: " + rowType + " vs. " + outputRowType);
    }
    final List<RelDataTypeField> fields = rowType.getFieldList();
    final List<RexLocalRef> projectRefs = new ArrayList<>();
    final List<RexInputRef> refs = new ArrayList<>();
    for (int i = 0; i < fields.size(); i++) {
      final RexInputRef ref = RexInputRef.of(i, fields);
      refs.add(ref);
      projectRefs.add(new RexLocalRef(i, ref.getType()));
    }
    return new RexProgram(rowType, refs, projectRefs, null, outputRowType);
  }

  /**
   * Returns the type of the input row to the program.
   *
   * @return input row type
   */
  public RelDataType getInputRowType() {
    return inputRowType;
  }

  /**
   * Returns whether this program contains windowed aggregate functions.
   *
   * @return whether this program contains windowed aggregate functions
   */
  public boolean containsAggs() {
    return RexOver.containsOver(this);
  }

  /**
   * Returns the type of the output row from this program.
   *
   * @return output row type
   */
  public RelDataType getOutputRowType() {
    return outputRowType;
  }

  /**
   * Checks that this program is valid.
   *
   * <p>If <code>fail</code> is true, executes <code>assert false</code>, so
   * will throw an {@link AssertionError} if assertions are enabled. If <code>
   * fail</code> is false, merely returns whether the program is valid.
   *
   * @param litmus What to do if an error is detected
   * @param context Context of enclosing {@link RelNode}, for validity checking,
   *                or null if not known
   * @return Whether the program is valid
   */
  public boolean isValid(
      @UnknownInitialization RexProgram this,
      Litmus litmus, RelNode.@Nullable Context context) {
    if (inputRowType == null) {
      return litmus.fail(null);
    }
    if (exprs == null) {
      return litmus.fail(null);
    }
    if (projects == null) {
      return litmus.fail(null);
    }
    if (outputRowType == null) {
      return litmus.fail(null);
    }

    // If the input row type is a struct (contains fields) then the leading
    // expressions must be references to those fields. But we don't require
    // this if the input row type is, say, a java class.
    if (inputRowType.isStruct()) {
      if (!RexUtil.containIdentity(exprs, inputRowType, litmus)) {
        return litmus.fail(null);
      }

      // None of the other fields should be inputRefs.
      for (int i = inputRowType.getFieldCount(); i < exprs.size(); i++) {
        RexNode expr = exprs.get(i);
        if (expr instanceof RexInputRef) {
          return litmus.fail(null);
        }
      }
    }
    // todo: enable
    // CHECKSTYLE: IGNORE 1
    if (false && RexUtil.containNoCommonExprs(exprs, litmus)) {
      return litmus.fail(null);
    }
    if (!RexUtil.containNoForwardRefs(exprs, inputRowType, litmus)) {
      return litmus.fail(null);
    }
    if (!RexUtil.containNoNonTrivialAggs(exprs, litmus)) {
      return litmus.fail(null);
    }
    final Checker checker =
        new Checker(inputRowType, RexUtil.types(exprs), null, litmus);
    if (condition != null) {
      if (!SqlTypeUtil.inBooleanFamily(condition.getType())) {
        return litmus.fail("condition must be boolean");
      }
      condition.accept(checker);
      if (checker.failCount > 0) {
        return litmus.fail(null);
      }
    }
    for (RexLocalRef project : projects) {
      project.accept(checker);
      if (checker.failCount > 0) {
        return litmus.fail(null);
      }
    }
    for (RexNode expr : exprs) {
      expr.accept(checker);
      if (checker.failCount > 0) {
        return litmus.fail(null);
      }
    }
    return litmus.succeed();
  }

  /**
   * Returns whether an expression always evaluates to null.
   *
   * <p>Like {@link RexUtil#isNull(RexNode)}, null literals are null, and
   * casts of null literals are null. But this method also regards references
   * to null expressions as null.
   *
   * @param expr Expression
   * @return Whether expression always evaluates to null
   */
  public boolean isNull(RexNode expr) {
    switch (expr.getKind()) {
    case LITERAL:
      return ((RexLiteral) expr).getValue2() == null;
    case LOCAL_REF:
      RexLocalRef inputRef = (RexLocalRef) expr;
      return isNull(exprs.get(inputRef.index));
    case CAST:
      return isNull(((RexCall) expr).operands.get(0));
    default:
      return false;
    }
  }

  /**
   * Fully expands a RexLocalRef back into a pure RexNode tree containing no
   * RexLocalRefs (reversing the effect of common subexpression elimination).
   * For example, <code>program.expandLocalRef(program.getCondition())</code>
   * will return the expansion of a program's condition.
   *
   * @param ref a RexLocalRef from this program
   * @return expanded form
   */
  public RexNode expandLocalRef(RexLocalRef ref) {
    return ref.accept(new ExpansionShuttle(exprs));
  }

  /** Expands a list of expressions that may contain {@link RexLocalRef}s. */
  public List<RexNode> expandList(List<? extends RexNode> nodes) {
    return new ExpansionShuttle(exprs).visitList(nodes);
  }

  /** Splits this program into a list of project expressions and a list of
   * filter expressions.
   *
   * <p>Neither list is null.
   * The filters are evaluated first. */
  public Pair<ImmutableList<RexNode>, ImmutableList<RexNode>> split() {
    final List<RexNode> filters = new ArrayList<>();
    if (condition != null) {
      RelOptUtil.decomposeConjunction(expandLocalRef(condition), filters);
    }
    final ImmutableList.Builder<RexNode> projects = ImmutableList.builder();
    for (RexLocalRef project : this.projects) {
      projects.add(expandLocalRef(project));
    }
    return Pair.of(projects.build(), ImmutableList.copyOf(filters));
  }

  /**
   * Given a list of collations which hold for the input to this program,
   * returns a list of collations which hold for its output. The result is
   * mutable and sorted.
   */
  public List<RelCollation> getCollations(List<RelCollation> inputCollations) {
    final List<RelCollation> outputCollations = new ArrayList<>();
    deduceCollations(
        outputCollations,
        inputRowType.getFieldCount(), projects,
        inputCollations);
    return outputCollations;
  }

  /**
   * Given a list of expressions and a description of which are ordered,
   * populates a list of collations, sorted in natural order.
   */
  public static void deduceCollations(
      List<RelCollation> outputCollations,
      final int sourceCount,
      List<RexLocalRef> refs,
      List<RelCollation> inputCollations) {
    int[] targets = new int[sourceCount];
    Arrays.fill(targets, -1);
    for (int i = 0; i < refs.size(); i++) {
      final RexLocalRef ref = refs.get(i);
      final int source = ref.getIndex();
      if ((source < sourceCount) && (targets[source] == -1)) {
        targets[source] = i;
      }
    }
  loop:
    for (RelCollation collation : inputCollations) {
      final List<RelFieldCollation> fieldCollations = new ArrayList<>(0);
      for (RelFieldCollation fieldCollation : collation.getFieldCollations()) {
        final int source = fieldCollation.getFieldIndex();
        final int target = targets[source];
        if (target < 0) {
          continue loop;
        }
        fieldCollations.add(fieldCollation.withFieldIndex(target));
      }

      // Success -- all of the source fields of this key are mapped
      // to the output.
      outputCollations.add(RelCollations.of(fieldCollations));
    }
    outputCollations.sort(Ordering.natural());
  }

  /**
   * Returns whether the fields on the leading edge of the project list are
   * the input fields.
   *
   * @param fail Whether to throw an assert failure if does not project
   *             identity
   */
  public boolean projectsIdentity(final boolean fail) {
    final int fieldCount = inputRowType.getFieldCount();
    if (projects.size() < fieldCount) {
      assert !fail
          : "program '" + toString()
          + "' does not project identity for input row type '"
          + inputRowType + "'";
      return false;
    }
    for (int i = 0; i < fieldCount; i++) {
      RexLocalRef project = projects.get(i);
      if (project.index != i) {
        assert !fail
            : "program " + toString()
            + "' does not project identity for input row type '"
            + inputRowType + "', field #" + i;
        return false;
      }
    }
    return true;
  }

  /**
   * Returns whether this program projects precisely its input fields. It may
   * or may not apply a condition.
   */
  public boolean projectsOnlyIdentity() {
    if (projects.size() != inputRowType.getFieldCount()) {
      return false;
    }
    for (int i = 0; i < projects.size(); i++) {
      RexLocalRef project = projects.get(i);
      if (project.index != i) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns whether this program returns its input exactly.
   *
   * <p>This is a stronger condition than {@link #projectsIdentity(boolean)}.
   */
  public boolean isTrivial() {
    return getCondition() == null && projectsOnlyIdentity();
  }

  /**
   * Gets reference counts for each expression in the program, where the
   * references are detected from later expressions in the same program, as
   * well as the project list and condition. Expressions with references
   * counts greater than 1 are true common sub-expressions.
   *
   * @return array of reference counts; the ith element in the returned array
   * is the number of references to getExprList()[i]
   */
  public int[] getReferenceCounts() {
    if (refCounts != null) {
      return refCounts;
    }
    refCounts = new int[exprs.size()];
    ReferenceCounter refCounter = new ReferenceCounter(refCounts);
    RexUtil.apply(refCounter, exprs, null);
    if (condition != null) {
      refCounter.visitLocalRef(condition);
    }
    for (RexLocalRef project : projects) {
      refCounter.visitLocalRef(project);
    }
    return refCounts;
  }

  /**
   * Returns whether an expression is constant.
   */
  public boolean isConstant(RexNode ref) {
    return ref.accept(new ConstantFinder());
  }

  public @Nullable RexNode gatherExpr(RexNode expr) {
    return expr.accept(new Marshaller());
  }

  /**
   * Returns the input field that an output field is populated from, or -1 if
   * it is populated from an expression.
   */
  public int getSourceField(int outputOrdinal) {
    assert (outputOrdinal >= 0) && (outputOrdinal < this.projects.size());
    RexLocalRef project = projects.get(outputOrdinal);
    int index = project.index;
    while (true) {
      RexNode expr = exprs.get(index);
      if (expr instanceof RexCall
          && ((RexCall) expr).getOperator()
          == SqlStdOperatorTable.IN_FENNEL) {
        // drill through identity function
        expr = ((RexCall) expr).getOperands().get(0);
      }
      if (expr instanceof RexLocalRef) {
        index = ((RexLocalRef) expr).index;
      } else if (expr instanceof RexInputRef) {
        return ((RexInputRef) expr).index;
      } else {
        return -1;
      }
    }
  }

  /**
   * Returns whether this program is a permutation of its inputs.
   */
  public boolean isPermutation() {
    if (projects.size() != inputRowType.getFieldList().size()) {
      return false;
    }
    for (int i = 0; i < projects.size(); ++i) {
      if (getSourceField(i) < 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns a permutation, if this program is a permutation, otherwise null.
   */
  @CheckReturnValue
  public @Nullable Permutation getPermutation() {
    Permutation permutation = new Permutation(projects.size());
    if (projects.size() != inputRowType.getFieldList().size()) {
      return null;
    }
    for (int i = 0; i < projects.size(); ++i) {
      int sourceField = getSourceField(i);
      if (sourceField < 0) {
        return null;
      }
      permutation.set(i, sourceField);
    }
    return permutation;
  }

  /**
   * Returns the set of correlation variables used (read) by this program.
   *
   * @return set of correlation variable names
   */
  public Set<String> getCorrelVariableNames() {
    final Set<String> paramIdSet = new HashSet<>();
    RexUtil.apply(
        new RexVisitorImpl<Void>(true) {
          @Override public Void visitCorrelVariable(
              RexCorrelVariable correlVariable) {
            paramIdSet.add(correlVariable.getName());
            return null;
          }
        },
        exprs,
        null);
    return paramIdSet;
  }

  /**
   * Returns whether this program is in canonical form.
   *
   * @param litmus     What to do if an error is detected (program is not in
   *                   canonical form)
   * @param rexBuilder Rex builder
   * @return whether in canonical form
   */
  public boolean isNormalized(Litmus litmus, RexBuilder rexBuilder) {
    final RexProgram normalizedProgram = normalize(rexBuilder, null);
    String normalized = normalizedProgram.toString();
    String string = toString();
    if (!normalized.equals(string)) {
      final String message = "Program is not normalized:\n"
          + "program:    {}\n"
          + "normalized: {}\n";
      return litmus.fail(message, string, normalized);
    }
    return litmus.succeed();
  }

  /**
   * Creates a simplified/normalized copy of this program.
   *
   * @param rexBuilder Rex builder
   * @param simplify Simplifier to simplify (in addition to normalizing),
   *     or null to not simplify
   * @return Normalized program
   */
  public RexProgram normalize(RexBuilder rexBuilder, @Nullable RexSimplify simplify) {
    // Normalize program by creating program builder from the program, then
    // converting to a program. getProgram does not need to normalize
    // because the builder was normalized on creation.
    assert isValid(Litmus.THROW, null);
    final RexProgramBuilder builder =
        RexProgramBuilder.create(rexBuilder, inputRowType, exprs, projects,
            condition, outputRowType, true, simplify);
    return builder.getProgram(false);
  }

  @Deprecated // to be removed before 2.0
  public RexProgram normalize(RexBuilder rexBuilder, boolean simplify) {
    final RelOptPredicateList predicates = RelOptPredicateList.EMPTY;
    return normalize(rexBuilder, simplify
        ? new RexSimplify(rexBuilder, predicates, RexUtil.EXECUTOR)
        : null);
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
   * @return Mapping of a set of project expressions, never null
   */
  public Mappings.TargetMapping getPartialMapping(int inputFieldCount) {
    Mappings.TargetMapping mapping =
        Mappings.create(MappingType.INVERSE_FUNCTION,
            inputFieldCount, projects.size());
    for (Ord<RexLocalRef> exp : Ord.zip(projects)) {
      RexNode rexNode = expandLocalRef(exp.e);
      if (rexNode instanceof RexInputRef) {
        mapping.set(((RexInputRef) rexNode).getIndex(), exp.i);
      }
    }
    return mapping;
  }

  //~ Inner Classes ----------------------------------------------------------

  /**
   * Visitor which walks over a program and checks validity.
   */
  static class Checker extends RexChecker {
    private final List<RelDataType> internalExprTypeList;

    /**
     * Creates a Checker.
     *
     * @param inputRowType         Types of the input fields
     * @param internalExprTypeList Types of the internal expressions
     * @param context              Context of the enclosing {@link RelNode},
     *                             or null
     * @param litmus               Whether to fail
     */
    Checker(RelDataType inputRowType,
        List<RelDataType> internalExprTypeList, RelNode.@Nullable Context context,
        Litmus litmus) {
      super(inputRowType, context, litmus);
      this.internalExprTypeList = internalExprTypeList;
    }

    /** Overrides {@link RexChecker} method, because {@link RexLocalRef} is
     * is illegal in most rex expressions, but legal in a program. */
    @Override public Boolean visitLocalRef(RexLocalRef localRef) {
      final int index = localRef.getIndex();
      if ((index < 0) || (index >= internalExprTypeList.size())) {
        ++failCount;
        return litmus.fail(null);
      }
      if (!RelOptUtil.eq(
          "type1",
          localRef.getType(),
          "type2",
          internalExprTypeList.get(index), litmus)) {
        ++failCount;
        return litmus.fail(null);
      }
      return litmus.succeed();
    }
  }

  /**
   * A RexShuttle used in the implementation of
   * {@link RexProgram#expandLocalRef}.
   */
  static class ExpansionShuttle extends RexShuttle {
    private final List<RexNode> exprs;

    ExpansionShuttle(List<RexNode> exprs) {
      this.exprs = exprs;
    }

    @Override public RexNode visitLocalRef(RexLocalRef localRef) {
      RexNode tree = exprs.get(localRef.getIndex());
      return tree.accept(this);
    }
  }

  /**
   * Walks over an expression and determines whether it is constant.
   */
  private class ConstantFinder extends RexUtil.ConstantFinder {
    @Override public Boolean visitLocalRef(RexLocalRef localRef) {
      final RexNode expr = exprs.get(localRef.index);
      return expr.accept(this);
    }

    @Override public Boolean visitOver(RexOver over) {
      return false;
    }

    @Override public Boolean visitCorrelVariable(RexCorrelVariable correlVariable) {
      // Correlating variables are constant WITHIN A RESTART, so that's
      // good enough.
      return true;
    }
  }

  /**
   * Given an expression in a program, creates a clone of the expression with
   * sub-expressions (represented by {@link RexLocalRef}s) fully expanded.
   */
  private class Marshaller extends RexVisitorImpl<@Nullable RexNode> {
    Marshaller() {
      super(false);
    }

    @Override public RexNode visitInputRef(RexInputRef inputRef) {
      return inputRef;
    }

    @Override public @Nullable RexNode visitLocalRef(RexLocalRef localRef) {
      final RexNode expr = exprs.get(localRef.index);
      return expr.accept(this);
    }

    @Override public RexNode visitLiteral(RexLiteral literal) {
      return literal;
    }

    @Override public RexNode visitCall(RexCall call) {
      final List<RexNode> newOperands = new ArrayList<>();
      for (RexNode operand : call.getOperands()) {
        newOperands.add(castNonNull(operand.accept(this)));
      }
      return call.clone(call.getType(), newOperands);
    }

    @Override public RexNode visitOver(RexOver over) {
      return visitCall(over);
    }

    @Override public RexNode visitCorrelVariable(RexCorrelVariable correlVariable) {
      return correlVariable;
    }

    @Override public RexNode visitDynamicParam(RexDynamicParam dynamicParam) {
      return dynamicParam;
    }

    @Override public RexNode visitRangeRef(RexRangeRef rangeRef) {
      return rangeRef;
    }

    @Override public RexNode visitFieldAccess(RexFieldAccess fieldAccess) {
      final RexNode referenceExpr =
          fieldAccess.getReferenceExpr().accept(this);
      return new RexFieldAccess(
          requireNonNull(referenceExpr, "referenceExpr must not be null"),
          fieldAccess.getField());
    }
  }

  /**
   * Visitor which marks which expressions are used.
   */
  private static class ReferenceCounter extends RexVisitorImpl<Void> {
    private final int[] refCounts;

    ReferenceCounter(int[] refCounts) {
      super(true);
      this.refCounts = refCounts;
    }

    @Override public Void visitLocalRef(RexLocalRef localRef) {
      final int index = localRef.getIndex();
      refCounts[index]++;
      return null;
    }
  }
}
