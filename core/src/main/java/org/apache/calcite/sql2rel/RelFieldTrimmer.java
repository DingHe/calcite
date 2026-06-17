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
package org.apache.calcite.sql2rel;

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Calc;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Exchange;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.core.Sample;
import org.apache.calcite.rel.core.SetOp;
import org.apache.calcite.rel.core.Snapshot;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.SortExchange;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalTableFunctionScan;
import org.apache.calcite.rel.logical.LogicalTableModify;
import org.apache.calcite.rel.logical.LogicalValues;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelDataTypeImpl;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexPermuteInputsShuttle;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.rex.RexSubQuery;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.rex.RexVisitor;
import org.apache.calcite.sql.SqlExplainFormat;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.Bug;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.ReflectUtil;
import org.apache.calcite.util.ReflectiveVisitor;
import org.apache.calcite.util.Util;
import org.apache.calcite.util.mapping.IntPair;
import org.apache.calcite.util.mapping.Mapping;
import org.apache.calcite.util.mapping.MappingType;
import org.apache.calcite.util.mapping.Mappings;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Transformer that walks over a tree of relational expressions, replacing each
 * {@link RelNode} with a 'slimmed down' relational expression that projects
 * only the columns required by its consumer.
 *
 * <p>Uses multi-methods to fire the right rule for each type of relational
 * expression. This allows the transformer to be extended without having to
 * add a new method to RelNode, and without requiring a collection of rule
 * classes scattered to the four winds.
 *
 * <p>REVIEW: jhyde, 2009/7/28: Is sql2rel the correct package for this class?
 * Trimming fields is not an essential part of SQL-to-Rel translation, and
 * arguably belongs in the optimization phase. But this transformer does not
 * obey the usual pattern for planner rules; it is difficult to do so, because
 * each {@link RelNode} needs to return a different set of fields after
 * trimming.
 *
 * <p>TODO: Change 2nd arg of the {@link #trimFields} method from BitSet to
 * Mapping. Sometimes it helps the consumer if you return the columns in a
 * particular order. For instance, it may avoid a project at the top of the
 * tree just for reordering. Could ease the transition by writing methods that
 * convert BitSet to Mapping and vice versa.
 */
// RelFieldTrimmer（字段裁剪器）的核心作用是：在关系代数树（RelNode AST）中，自顶向下、递归地裁剪掉所有未被上层算子使用的冗余字段。
// 在 SQL 转化为关系代数节点时（SqlToRelConverter 阶段），通常会将表的所有列全部拉取出来。如果用户写的 SQL 是：
// SELECT name FROM users WHERE age > 18
// 虽然用户只需要 name 列，但底层可能把用户表的 id, name, age, create_time 等所有列全扫描出来了。RelFieldTrimmer 通过对整棵树进行深度的、自顶向下的分析，只保留那些真正参与 SELECT、WHERE（Filter 条件）、ORDER BY（Sort 键）或 JOIN（关联条件）计算的列。
// 多方法动态路由（Multi-method Dispatch）：由于它继承自 ReflectiveVisitor，它不采用死板的 if-else 判断节点类型，
// 而是利用 Calcite 的 ReflectUtil 通过反射动态将不同类型的算子路由到对应的重载方法 trimFields(SpecificRelNode, ...)。
// 联合变化（TrimResult）：裁剪字段会导致当前算子的输出 RowType 发生坍塌（列数变少）。因此，改变子节点的输出意味着父节点中所有对子节点的列索引引用（RexInputRef）都必须进行重置刷新。该类通过维护一个 Mapping（映射表）来完成新老索引的平滑校准。
public class RelFieldTrimmer implements ReflectiveVisitor {
  //~ Static fields/initializers ---------------------------------------------

  //~ Instance fields --------------------------------------------------------
  // 方法分发器（Dispatcher）。
  // 这是反射访问者的核心纽带。在构造函数中通过 ReflectUtil.createMethodDispatcher 初始化。
  // 它绑定了当前实例中的所有名字叫 "trimFields" 且参数契约相符的方法。运行时，调用它的 invoke 就能自动找到最精确匹配的具体算子重载方法。
  private final ReflectUtil.MethodDispatcher<TrimResult> trimFieldsDispatcher;
  // Calcite 通用的关系代数构建器。在裁剪字段后，需要生成新的、瘦身后的 Project、Filter 等节点。
  // 该类通过调用 relBuilder 来优雅、统一地创建这些新节点，并利用其自带的简化重写逻辑。
  private final RelBuilder relBuilder;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a RelFieldTrimmer.
   *
   * @param validator Validator
   */
  public RelFieldTrimmer(@Nullable SqlValidator validator, RelBuilder relBuilder) {
    Util.discard(validator); // may be useful one day
    this.relBuilder = relBuilder;
    @SuppressWarnings("argument.type.incompatible")
    // 利用反射工具组装出针对 trimFields(RelNode, ImmutableBitSet, Set) 的方法分发器，并将其缓存在 trimFieldsDispatcher 中，为后续的动态多态路由铺平道路。
    ReflectUtil.MethodDispatcher<TrimResult> dispatcher =
        ReflectUtil.createMethodDispatcher(
            TrimResult.class,
            this,
            "trimFields",
            RelNode.class,
            ImmutableBitSet.class,
            Set.class);
    this.trimFieldsDispatcher = dispatcher;
  }
  // 接收各大算子的独立 Factory（如 ProjectFactory, FilterFactory 等）。
  // 内部通过 RelBuilder.proto(...) 把这一大堆工厂糅合拼装成一个统一的 RelBuilder，然后将其转发给核心构造函数 1。该方法将在 Calcite 2.0 被彻底移除。
  @Deprecated // to be removed before 2.0
  public RelFieldTrimmer(@Nullable SqlValidator validator,
      RelOptCluster cluster,
      RelFactories.ProjectFactory projectFactory,
      RelFactories.FilterFactory filterFactory,
      RelFactories.JoinFactory joinFactory,
      RelFactories.SortFactory sortFactory,
      RelFactories.AggregateFactory aggregateFactory,
      RelFactories.SetOpFactory setOpFactory) {
    this(validator,
        RelBuilder.proto(projectFactory, filterFactory, joinFactory,
            sortFactory, aggregateFactory, setOpFactory)
        .create(cluster, null));
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Trims unused fields from a relational expression.
   *
   * <p>We presume that all fields of the relational expression are wanted by
   * its consumer, so only trim fields that are not used within the tree.
   *
   * @param root Root node of relational expression
   * @return Trimmed relational expression
   */
  // 当整个 SQL 转换完成后，外部调用者会把关系代数树的根节点传给这个方法，从而启动整棵树的自顶向下字段裁剪优化。
  // 入参 RelNode root： 关系代数表达式树的根节点（通常是整条查询的最外层算子，比如最上层的 LogicalProject 或 LogicalSort）。
  // 由于 root 是输出给最外层客户端（如 JDBC 结果集）的，Calcite 必须默认客户端需要根节点吐出来的所有列。因此，根节点本身的输出列是不能被裁剪的，裁剪只能发生在树的内部和下游
  // 回一棵物理重构后的、瘦身成功的全新关系代数树的根节点。在这棵新树中，所有内部不必要的传输列、未被引用的表字段都已被剔除。
  public RelNode trim(RelNode root) {
    // 获取当前根节点原始输出行类型（RowType）中的字段/列总数。
    final int fieldCount = root.getRowType().getFieldCount();
    // 根节点的数据是直接交付给最外层用户的，所以每一列都算作“被使用”。这个位图代表告诉接下来的裁剪器：“根节点的所有列我全都要，一列都不能少！”。这为接下来的自顶向下遍历确立了初始边界条件
    final ImmutableBitSet fieldsUsed = ImmutableBitSet.range(fieldCount);
    // 某些特殊的物理算子或关联查询在执行时，可能需要隐式地向下游索要一些额外的系统字段或行级元数据。
    // 在根节点启动时，不存在任何额外的隐式字段索要诉求，因此初始化传入一个标准的空集合（Collections.emptySet()）。
    final Set<RelDataTypeField> extraFields = Collections.emptySet();
    // 核心一步。
    // 将根节点、全选位图、空扩展集传入 dispatchTrimFields 方法。
    final TrimResult trimResult =
        dispatchTrimFields(root, fieldsUsed, extraFields);
    // 恒等映射 意味着：裁剪前是第 0 列，裁剪后还是第 0 列；裁剪前有 $N$ 列，裁剪后依然有 $N$ 列，没有发生任何位置偏移或列丢失。
    // 核心逻辑：因为在步骤 2 中，我们已经明确下达了“根节点所有列全都要”的死命令，所以如果 dispatchTrimFields 执行完后，发现根节点的列居然变少了（isIdentity() 为 false），说明内部裁剪逻辑出现了严重 Bug，误杀了最外层用户需要的列。
    // 此时程序直接果断抛出 IllegalArgumentException 异常，防止将错误的吐数结构交付给上层。
    if (!trimResult.right.isIdentity()) {
      throw new IllegalArgumentException();
    }
    if (SqlToRelConverter.SQL2REL_LOGGER.isDebugEnabled()) {
      SqlToRelConverter.SQL2REL_LOGGER.debug(
          RelOptUtil.dumpPlan("Plan after trimming unused fields",
              trimResult.left, SqlExplainFormat.TEXT,
              SqlExplainLevel.EXPPLAN_ATTRIBUTES));
    }
    return trimResult.left;
  }

  /**
   * Trims the fields of an input relational expression.
   *
   * @param rel        Relational expression
   * @param input      Input relational expression, whose fields to trim
   * @param fieldsUsed Bitmap of fields needed by the consumer
   * @return New relational expression and its field mapping
   */
  // RelFieldTrimmer 中实现自顶向下（Top-Down）属性传递的关键纽带
  // 主要职责是：在正式把裁剪请求发送给子节点（input）之前，对当前父节点向下层索要的列（fieldsUsed）进行最后的安全性盘点和兜底保护。
  // 它会强行把“排序列”和“被其他关联算子引用的变量列”强行锁死在位图中，确保这些关键列不会被误杀。
  protected TrimResult trimChild(
      // RelNode rel：当前正在处理的父算子节点。
      RelNode rel,
      // RelNode input：当前父算子的下游子算子节点（即本次即将被裁剪的目标节点）。
      RelNode input,
      // 父算子根据自身的标量表达式，初步计算出需要子节点提供哪些列的索引位图。
      final ImmutableBitSet fieldsUsed,
      // 外部/隐式要求的扩展字段集合。
      Set<RelDataTypeField> extraFields) {
    // 因为入参 fieldsUsed 是不可变的（ImmutableBitSet），所以通过 rebuild() 方法将其转换成一个可变的构建器（Builder），以便在接下来的防御检查中随时往里面追加（Set）不能被裁剪的列。
    final ImmutableBitSet.Builder fieldsUsedBuilder = fieldsUsed.rebuild();

    // Fields that define the collation cannot be discarded.
    // 通过 RelMetadataQuery 元数据查询引擎，去试探下游子节点 input 是否拥有某些强烈的物理排序属性（比如底层是一个 LogicalSort，或者是一个本身就按主键有序的索引扫描 IndexScan）。
    final RelMetadataQuery mq = rel.getCluster().getMetadataQuery();
    final ImmutableList<RelCollation> collations = mq.collations(input);
    if (collations != null) {
      for (RelCollation collation : collations) {
        // 通过 fieldCollation.getFieldIndex() 拿到这些排序列在子节点中的列索引，并无条件强行塞入 fieldsUsedBuilder。
        for (RelFieldCollation fieldCollation : collation.getFieldCollations()) {
          fieldsUsedBuilder.set(fieldCollation.getFieldIndex());
        }
      }
    }

    // Correlating variables are a means for other relational expressions to use
    // fields.
    // rel.getVariablesSet()代表当前父节点（比如一个 LogicalCorrelate 算子或带有 CorrelationId 的算子）自己是一个变量定义源，它向外暴露了某些变量让其他外部子查询来读取。
    for (final CorrelationId correlation : rel.getVariablesSet()) {
      rel.accept(
          new CorrelationReferenceFinder() {
            @Override protected RexNode handle(RexFieldAccess fieldAccess) {
              final RexCorrelVariable v =
                  (RexCorrelVariable) fieldAccess.getReferenceExpr();
              // 如果子查询有引用到父节点的列，则需要保留
              if (v.id.equals(correlation)) {
                fieldsUsedBuilder.set(fieldAccess.getField().getIndex());
              }
              return fieldAccess;
            }
          });
    }
    //递归
    return dispatchTrimFields(input, fieldsUsedBuilder.build(), extraFields);
  }

  /**
   * Trims a child relational expression, then adds back a dummy project to
   * restore the fields that were removed.
   *
   * <p>Sounds pointless? It causes unused fields to be removed
   * further down the tree (towards the leaves), but it ensure that the
   * consuming relational expression continues to see the same fields.
   *
   * @param rel        Relational expression
   * @param input      Input relational expression, whose fields to trim
   * @param fieldsUsed Bitmap of fields needed by the consumer
   * @return New relational expression and its field mapping
   */
  protected TrimResult trimChildRestore(
      RelNode rel,
      RelNode input,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    TrimResult trimResult = trimChild(rel, input, fieldsUsed, extraFields);
    if (trimResult.right.isIdentity()) {
      return trimResult;
    }
    final RelDataType rowType = input.getRowType();
    List<RelDataTypeField> fieldList = rowType.getFieldList();
    final List<RexNode> exprList = new ArrayList<>();
    final List<String> nameList = rowType.getFieldNames();
    RexBuilder rexBuilder = rel.getCluster().getRexBuilder();
    assert trimResult.right.getSourceCount() == fieldList.size();
    for (int i = 0; i < fieldList.size(); i++) {
      int source = trimResult.right.getTargetOpt(i);
      RelDataTypeField field = fieldList.get(i);
      exprList.add(
          source < 0
              ? rexBuilder.makeZeroLiteral(field.getType())
              : rexBuilder.makeInputRef(field.getType(), source));
    }
    relBuilder.push(trimResult.left)
        .project(exprList, nameList);
    return result(relBuilder.build(),
        Mappings.createIdentity(fieldList.size()), rel);
  }

  /**
   * Invokes {@link #trimFields}, or the appropriate method for the type
   * of the rel parameter, using multi-method dispatch.
   *
   * @param rel        Relational expression
   * @param fieldsUsed Bitmap of fields needed by the consumer
   * @return New relational expression and its field mapping
   */
  // 至关重要的核心路由与校验中心
  // 主要职责是通过反射将当前算子分发给具体的裁剪实现方法，并在分发返回后，对生成的新算子与映射关系进行极其严密的正确性断言（Assertion）校验。
  protected final TrimResult dispatchTrimFields(
      // 当前正在处理的、裁剪前的关系代数节点（如 LogicalProject、LogicalFilter 等）。
      RelNode rel,
      // 记录了当前算子的上层消费者（Parent）所需要保留的列索引集合。裁剪的核心依据就是看当前列是否在这个位图中。
      ImmutableBitSet fieldsUsed,
      // 外部/隐式要求的扩展字段集合。
      // 有些特定的查询关联或物理算子，需要向下游索要当前 RowType 里原本没有包含的隐式系统列或相关变量列。
      Set<RelDataTypeField> extraFields) {
    // 动态反射分发
    // 如果 rel 运行时实际类型是 LogicalProject，就会自动路由到 trimFields(Project, ...)；如果是 LogicalFilter，则路由到 trimFields(Filter, ...)。执行后返回包含新算子和映射关系的 TrimResult。
    final TrimResult trimResult =
        trimFieldsDispatcher.invoke(rel, fieldsUsed, extraFields);
    final RelNode newRel = trimResult.left;
    final Mapping mapping = trimResult.right;
    // 校验映射矩阵的源（Source）数量。
    // mapping.getSourceCount() 代表该映射期望的“输入端老列数”。
    // 它必须严格等于裁剪前算子 rel 的真实列数 fieldCount。如果对不上，说明裁剪算法在构建映射时连老表的底细都没搞清楚，直接抛出断言错误。
    final int fieldCount = rel.getRowType().getFieldCount();
    assert mapping.getSourceCount() == fieldCount
        : "source: " + mapping.getSourceCount() + " != " + fieldCount;
    // 校验映射矩阵的目标（Target）数量。
    // 核心等式：$\text{映射保留的列数} + \text{额外扩展列数 (extraFields)} \equiv \text{新算子实际输出的列数 (newFieldCount)}$。
    final int newFieldCount = newRel.getRowType().getFieldCount();
    assert mapping.getTargetCount() + extraFields.size() == newFieldCount
        || Bug.TODO_FIXED
        : "target: " + mapping.getTargetCount()
        + " + " + extraFields.size()
        + " != " + newFieldCount;
    if (Bug.TODO_FIXED) {
      assert newFieldCount > 0 : "rel has no fields after trim: " + rel;
    }
    // 如果在 trimFields 内部执行了一圈，发现由于列全部被使用了，导致生成的 newRel 和原先的 rel 完全长得一模一样（没有被瘦身），
    // 为了避免破坏代数树原有的对象引用和 Hint 提示，直接调用 result(rel, mapping)，将最原始的 rel 对象搭配映射矩阵打包返回。
    if (newRel.equals(rel)) {
      return result(rel, mapping);
    }
    return trimResult;
  }

  protected TrimResult result(RelNode rel, final Mapping mapping, RelNode oldRel) {
    return result(RelOptUtil.copyRelHints(oldRel, rel), mapping);
  }

  protected TrimResult result(RelNode r, final Mapping mapping) {
    final RexBuilder rexBuilder = relBuilder.getRexBuilder();
    for (final CorrelationId correlation : r.getVariablesSet()) {
      r =
          r.accept(new CorrelationReferenceFinder() {
            @Override protected RexNode handle(RexFieldAccess fieldAccess) {
              final RexCorrelVariable v =
                  (RexCorrelVariable) fieldAccess.getReferenceExpr();
              if (v.id.equals(correlation)
                  && v.getType().getFieldCount() == mapping.getSourceCount()) {
                final int old = fieldAccess.getField().getIndex();
                final int new_ = mapping.getTarget(old);
                final RelDataTypeFactory.Builder typeBuilder =
                    relBuilder.getTypeFactory().builder();
                for (int target : Util.range(mapping.getTargetCount())) {
                  typeBuilder.add(
                      v.getType().getFieldList().get(mapping.getSource(target)));
                }
                final RexNode newV =
                    rexBuilder.makeCorrel(typeBuilder.build(), v.id);
                if (old != new_) {
                  return rexBuilder.makeFieldAccess(newV, new_);
                }
              }
              return fieldAccess;
            }
          });
    }
    return new TrimResult(r, mapping);
  }

  /**
   * Visit method, per {@link org.apache.calcite.util.ReflectiveVisitor}.
   *
   * <p>This method is invoked reflectively, so there may not be any apparent
   * calls to it. The class (or derived classes) may contain overloads of
   * this method with more specific types for the {@code rel} parameter.
   *
   * <p>Returns a pair: the relational expression created, and the mapping
   * between the original fields and the fields of the newly created
   * relational expression.
   *
   * @param rel        Relational expression
   * @param fieldsUsed Fields needed by the consumer
   * @return relational expression and mapping
   */
  public TrimResult trimFields(
      RelNode rel,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    // We don't know how to trim this kind of relational expression
    Util.discard(fieldsUsed);
    if (rel.getInputs().isEmpty()) {
      return result(rel, Mappings.createIdentity(rel.getRowType().getFieldCount()));
    }

    // We don't know how to trim this RelNode, but we can try to trim inside its inputs
    List<RelNode> newInputs = new ArrayList<>(rel.getInputs().size());
    for (RelNode input : rel.getInputs()) {
      ImmutableBitSet inputFieldsUsed = ImmutableBitSet.range(input.getRowType().getFieldCount());
      TrimResult trimResult = dispatchTrimFields(input, inputFieldsUsed, extraFields);
      if (!trimResult.right.isIdentity()) {
        throw new IllegalArgumentException("Expected identity mapping after processing RelNode "
            + input + "; but got " + trimResult.right);
      }
      newInputs.add(trimResult.left);
    }
    RelNode newRel = rel.copy(rel.getTraitSet(), newInputs);
    return result(newRel, Mappings.createIdentity(newRel.getRowType().getFieldCount()), rel);
  }

  /**
   * Variant of {@link #trimFields(RelNode, ImmutableBitSet, Set)} for
   * {@link org.apache.calcite.rel.logical.LogicalCalc}.
   */
  public TrimResult trimFields(
      Calc calc,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    final RexProgram rexProgram = calc.getProgram();
    final List<RexNode> projs =
        Util.transform(rexProgram.getProjectList(), rexProgram::expandLocalRef);

    final RexNode conditionExpr =
        rexProgram.getCondition() == null ? null
            : rexProgram.expandLocalRef(rexProgram.getCondition());

    final RelDataType rowType = calc.getRowType();
    final int fieldCount = rowType.getFieldCount();
    final RelNode input = calc.getInput();

    final Set<RelDataTypeField> inputExtraFields =
        new HashSet<>(extraFields);
    RelOptUtil.InputFinder inputFinder =
        new RelOptUtil.InputFinder(inputExtraFields);
    for (Ord<RexNode> ord : Ord.zip(projs)) {
      if (fieldsUsed.get(ord.i)) {
        ord.e.accept(inputFinder);
      }
    }
    if (conditionExpr != null) {
      conditionExpr.accept(inputFinder);
    }
    ImmutableBitSet inputFieldsUsed = inputFinder.build();

    // Create input with trimmed columns.
    TrimResult trimResult =
        trimChild(calc, input, inputFieldsUsed, inputExtraFields);
    RelNode newInput = trimResult.left;
    final Mapping inputMapping = trimResult.right;

    // If the input is unchanged, and we need to project all columns,
    // there's nothing we can do.
    if (newInput == input
        && fieldsUsed.cardinality() == fieldCount) {
      return result(calc, Mappings.createIdentity(fieldCount));
    }

    // Some parts of the system can't handle rows with zero fields, so
    // pretend that one field is used.
    if (fieldsUsed.cardinality() == 0 && rexProgram.getCondition() == null) {
      return dummyProject(fieldCount, newInput);
    }

    // Build new project expressions, and populate the mapping.
    final List<RexNode> newProjects = new ArrayList<>();
    final RexVisitor<RexNode> shuttle =
        new RexPermuteInputsShuttle(
            inputMapping, newInput);
    final Mapping mapping =
        Mappings.create(
            MappingType.INVERSE_SURJECTION,
            fieldCount,
            fieldsUsed.cardinality());
    for (Ord<RexNode> ord : Ord.zip(projs)) {
      if (fieldsUsed.get(ord.i)) {
        mapping.set(ord.i, newProjects.size());
        RexNode newProjectExpr = ord.e.accept(shuttle);
        newProjects.add(newProjectExpr);
      }
    }

    final RelDataType newRowType =
        RelOptUtil.permute(calc.getCluster().getTypeFactory(), rowType,
            mapping);

    final RelNode newInputRelNode = relBuilder.push(newInput).build();
    RexNode newConditionExpr = null;
    if (conditionExpr != null) {
      newConditionExpr = conditionExpr.accept(shuttle);
    }
    final RexProgram newRexProgram =
        RexProgram.create(newInputRelNode.getRowType(), newProjects,
            newConditionExpr, newRowType.getFieldNames(),
            newInputRelNode.getCluster().getRexBuilder());
    final Calc newCalc =
        calc.copy(calc.getTraitSet(), newInputRelNode, newRexProgram);
    return result(newCalc, mapping, calc);
  }

  /**
   * Variant of {@link #trimFields(RelNode, ImmutableBitSet, Set)} for
   * {@link org.apache.calcite.rel.logical.LogicalProject}.
   */
  // 针对 Project（投影算子） 的具体裁剪实现方法
  // Project 算子的裁剪逻辑非常具有代表性：它不仅要根据上层需要删掉自己内部多余的投影表达式，更核心的任务是分析留下来的表达式里，究竟引用了底层输入（input）的哪些列，从而把裁剪指令继续向更下层（如 TableScan）传递。
  public TrimResult trimFields(
      // 当前正在处理的、裁剪前的原始 Project 算子节点（例如 SELECT a, b+c, d FROM table）。
      Project project,
      // 上层消费者（Parent）指明当前 Project 最终必须保留的列索引集合。
      ImmutableBitSet fieldsUsed,
      // 外部/隐式要求的扩展字段集合，会随着裁剪向底层继续透传。
      Set<RelDataTypeField> extraFields) {
    // 阶段 1：元数据准备与输入依赖分析
    // 获取当前 Project 节点的输出行类型、原始输出列数（fieldCount）以及它的下游输入算子（input）。
    final RelDataType rowType = project.getRowType();
    final int fieldCount = rowType.getFieldCount();
    final RelNode input = project.getInput();

    // Which fields are required from the input?
    // 找出下游哪些列被真正使用了。
    // 通过 Ord.zip 给投影表达式列表打上序号（ord.i 是列索引，ord.e 是对应的 RexNode 表达式）。
    // 如果当前列是被上层需要的（fieldsUsed.get(ord.i) 为 true），就让 InputFinder 访问者去遍历这个表达式。
    final Set<RelDataTypeField> inputExtraFields =
        new LinkedHashSet<>(extraFields);
    RelOptUtil.InputFinder inputFinder =
        new RelOptUtil.InputFinder(inputExtraFields);
    for (Ord<RexNode> ord : Ord.zip(project.getProjects())) {
      if (fieldsUsed.get(ord.i)) {
        ord.e.accept(inputFinder);
      }
    }

    // Collect all the SubQueries in the projection list.
    // 阶段 2：处理关联子查询（SubQuery）中的相关变量
    // 收集相关子查询所依赖的外层依赖列。
    // 如果 SELECT 列表中包含了类似于 (SELECT max(x) FROM t2 WHERE t2.id = project.y) 这样的相关子查询（Correlated SubQuery），
    // 子查询内部会通过 CorrelationId 隐式引用当前 Project 的列。
    // 代码会把这些隐藏的关联列（requiredColumns）找出来，确保它们不会被误杀。
    List<RexSubQuery> subQueries = RexUtil.SubQueryCollector.collect(project);
    // Get all the correlationIds present in the SubQueries
    // 捞出子查询依赖的所有相关变量 ID
    // 物理背景：比如 SQL 中有 SELECT emp.name, (SELECT dept.name FROM dept WHERE dept.id = emp.dept_id) FROM emp。
    // 子查询里的 emp.dept_id 会被转换为对一个相关变量（如 $cor0）的引用。这行代码执行完后，correlationIds 就会塞入 [$cor0]。
    Set<CorrelationId> correlationIds = RelOptUtil.getVariablesUsed(subQueries);
    ImmutableBitSet requiredColumns = ImmutableBitSet.of();
    if (correlationIds.size() > 0) {
      // 如果大于 0，说明当前 Project 里面确实嵌套了相关子查询（即子查询向外层“吸血”了），必须启动字段保护机制。
      // 如果等于 0，则直接跳过，说明是普通查询或完全独立的非相关子查询，不需要特殊保护。
      // 在 Calcite 标准的自顶向下/自底向上转换和去关联（Decorrelation）流水线中，为了保证关系的清晰度和重构的确定性，通常在同一个 Project 算子的标量表达式域内，同一时间只会为当前层次绑定或透传一个唯一的 CorrelationId。
      // 如果出现多个，说明逻辑树发生了严重的混乱，断言会直接在测试阶段报错。
      assert correlationIds.size() == 1;
      // Correlation columns are also needed by SubQueries, so add them to inputFieldsUsed.
      // 真正落实“哪些列被征用了”。
      // 把这些表达式里访问的列索引全部收集起来。例如，如果子查询条件是 WHERE dept.id = emp.dept_id，而 dept_id 是外层 emp 表的第 5 列。
      requiredColumns = RelOptUtil.correlationColumns(correlationIds.iterator().next(), project);
    }
    // 计算下游输入算子的总使用列集合。
    ImmutableBitSet finderFields = inputFinder.build();

    ImmutableBitSet inputFieldsUsed = ImmutableBitSet.builder()
        .addAll(requiredColumns)
        .addAll(finderFields)
        .build();

    // Create input with trimmed columns.
    // 递归裁剪子节点与边界状态拦截
    // 带着刚刚算出来的列诉求位图，调用 trimChild 递归去裁剪洗涤下游子节点。返回加工后的新子节点 newInput 以及新旧索引转换账本 inputMapping。
    TrimResult trimResult =
        trimChild(project, input, inputFieldsUsed, inputExtraFields);
    RelNode newInput = trimResult.left;
    final Mapping inputMapping = trimResult.right;

    // If the input is unchanged, and we need to project all columns,
    // there's nothing we can do.
    // 边界优化拦截 1（无变化）
    // 如果发现下游 input 裁剪完后根本没变（newInput == input），且上层把当前 Project 的列全部要了，说明从上到下没有任何一列可以被裁剪。直接返回原始的 project 对象搭配一个恒等映射（Identity Mapping）
    if (newInput == input
        && fieldsUsed.cardinality() == fieldCount) {
      return result(project, Mappings.createIdentity(fieldCount));
    }

    // Some parts of the system can't handle rows with zero fields, so
    // pretend that one field is used.
    // 边界优化拦截 2（零列防御）。
    // 如果上层居然一列都不要（比如只做 EXISTS 判断或 COUNT(*)），fieldsUsed.cardinality() == 0。为了防止生成没有列的空行导致下游崩溃，直接路由到 dummyProject 方法生成一个包含常量占位符列的傀儡 Project。
    if (fieldsUsed.cardinality() == 0) {
      return dummyProject(fieldCount, newInput, project);
    }

    // Build new project expressions, and populate the mapping.
    // 阶段 4：洗牌并重构 Project 表达式与映射
    final List<RexNode> newProjects = new ArrayList<>();
    // 核心工具 RexPermuteInputsShuttle：因为下游子节点刚刚被裁剪了，列变少了，原先表达式里的 RexInputRef 索引全错位了。
    // 这个 Shuttle 就是用来根据 inputMapping 账本，把表达式里旧的列索引洗牌刷新为正确的新列索引。
    final RexVisitor<RexNode> shuttle =
        new RexPermuteInputsShuttle(
            inputMapping, newInput);
    final Mapping mapping =
        Mappings.create(
            MappingType.INVERSE_SURJECTION,
            fieldCount,
            fieldsUsed.cardinality());
    // 剔除废弃列，刷新保留列。
    for (Ord<RexNode> ord : Ord.zip(project.getProjects())) {
      if (fieldsUsed.get(ord.i)) {
        mapping.set(ord.i, newProjects.size());
        RexNode newProjectExpr = ord.e.accept(shuttle);
        newProjects.add(newProjectExpr);
      }
    }
    // 阶段 5：生成新节点并返回
    // 根据刚刚沉淀下来的新账本 mapping，对原始的行类型 rowType 进行投影重组，计算出裁剪后该 Project 吐出的全新物理行类型 newRowType。
    final RelDataType newRowType =
        RelOptUtil.permute(project.getCluster().getTypeFactory(), rowType,
            mapping);
    // 将瘦身后的下游输入 newInput 压入 relBuilder 栈。
    relBuilder.push(newInput);
    relBuilder.project(newProjects, newRowType.getFieldNames());
    final RelNode newProject = relBuilder.build();
    // 调用 result(...) 方法将新节点、当前层映射账本打包成 TrimResult 交付给上层。
    return result(newProject, mapping, project);
  }

  /** Creates a project with a dummy column, to protect the parts of the system
   * that cannot handle a relational expression with no columns.
   *
   * @param fieldCount Number of fields in the original relational expression
   * @param input Trimmed input
   * @return Dummy project
   */
  protected TrimResult dummyProject(int fieldCount, RelNode input) {
    return dummyProject(fieldCount, input, null);
  }

  /** Creates a project with a dummy column, to protect the parts of the system
   * that cannot handle a relational expression with no columns.
   *
   * @param fieldCount Number of fields in the original relational expression
   * @param input Trimmed input
   * @param originalRelNode Source RelNode for hint propagation (or null if no propagation needed)
   * @return Dummy project
   */
  protected TrimResult dummyProject(int fieldCount, RelNode input,
      @Nullable RelNode originalRelNode) {
    final RelOptCluster cluster = input.getCluster();
    final Mapping mapping =
        Mappings.create(MappingType.INVERSE_SURJECTION, fieldCount, 1);
    if (input.getRowType().getFieldCount() == 1) {
      // Input already has one field (and may in fact be a dummy project we
      // created for the child). We can't do better.
      return result(input, mapping);
    }
    final RexLiteral expr =
        cluster.getRexBuilder().makeExactLiteral(BigDecimal.ZERO);
    relBuilder.push(input);
    relBuilder.project(ImmutableList.of(expr), ImmutableList.of("DUMMY"));
    RelNode newProject = relBuilder.build();
    if (originalRelNode != null) {
      newProject = RelOptUtil.propagateRelHints(originalRelNode, newProject);
    }
    return result(newProject, mapping);
  }

  /**
   * Variant of {@link #trimFields(RelNode, ImmutableBitSet, Set)} for
   * {@link org.apache.calcite.rel.logical.LogicalFilter}.
   */
  public TrimResult trimFields(
      Filter filter,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    final RelDataType rowType = filter.getRowType();
    final int fieldCount = rowType.getFieldCount();
    final RexNode conditionExpr = filter.getCondition();
    final RelNode input = filter.getInput();

    // We use the fields used by the consumer, plus any fields used in the
    // filter.
    final Set<RelDataTypeField> inputExtraFields =
        new LinkedHashSet<>(extraFields);
    RelOptUtil.InputFinder inputFinder =
        new RelOptUtil.InputFinder(inputExtraFields, fieldsUsed);
    conditionExpr.accept(inputFinder);
    final ImmutableBitSet inputFieldsUsed = inputFinder.build();

    // Create input with trimmed columns.
    TrimResult trimResult =
        trimChild(filter, input, inputFieldsUsed, inputExtraFields);
    RelNode newInput = trimResult.left;
    final Mapping inputMapping = trimResult.right;

    // If the input is unchanged, and we need to project all columns,
    // there's nothing we can do.
    if (newInput == input
        && fieldsUsed.cardinality() == fieldCount) {
      return result(filter, Mappings.createIdentity(fieldCount));
    }

    // Build new project expressions, and populate the mapping.
    final RexVisitor<RexNode> shuttle =
        new RexPermuteInputsShuttle(inputMapping, newInput);
    RexNode newConditionExpr =
        conditionExpr.accept(shuttle);

    // Build new filter with trimmed input and condition.
    relBuilder.push(newInput)
        .filter(filter.getVariablesSet(), newConditionExpr);

    // The result has the same mapping as the input gave us. Sometimes we
    // return fields that the consumer didn't ask for, because the filter
    // needs them for its condition.
    return result(relBuilder.build(), inputMapping, filter);
  }

  /**
   * Variant of {@link #trimFields(RelNode, ImmutableBitSet, Set)} for
   * {@link org.apache.calcite.rel.core.Sort}.
   */
  public TrimResult trimFields(
      Sort sort,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    final RelDataType rowType = sort.getRowType();
    final int fieldCount = rowType.getFieldCount();
    final RelCollation collation = sort.getCollation();
    final RelNode input = sort.getInput();

    // We use the fields used by the consumer, plus any fields used as sort
    // keys.
    final ImmutableBitSet.Builder inputFieldsUsed = fieldsUsed.rebuild();
    for (RelFieldCollation field : collation.getFieldCollations()) {
      inputFieldsUsed.set(field.getFieldIndex());
    }

    // Create input with trimmed columns.
    final Set<RelDataTypeField> inputExtraFields = Collections.emptySet();
    TrimResult trimResult =
        trimChild(sort, input, inputFieldsUsed.build(), inputExtraFields);
    RelNode newInput = trimResult.left;
    final Mapping inputMapping = trimResult.right;

    // If the input is unchanged, and we need to project all columns,
    // there's nothing we can do.
    if (newInput == input
        && inputMapping.isIdentity()
        && fieldsUsed.cardinality() == fieldCount) {
      return result(sort, Mappings.createIdentity(fieldCount));
    }

    relBuilder.push(newInput);
    final ImmutableList<RexNode> fields =
        relBuilder.fields(RexUtil.apply(inputMapping, collation));
    relBuilder.sortLimit(sort.offset, sort.fetch, fields);

    // The result has the same mapping as the input gave us. Sometimes we
    // return fields that the consumer didn't ask for, because the filter
    // needs them for its condition.
    return result(relBuilder.build(), inputMapping, sort);
  }

  public TrimResult trimFields(
      Exchange exchange,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    final RelDataType rowType = exchange.getRowType();
    final int fieldCount = rowType.getFieldCount();
    final RelDistribution distribution = exchange.getDistribution();
    final RelNode input = exchange.getInput();

    // We use the fields used by the consumer, plus any fields used as exchange
    // keys.
    final ImmutableBitSet.Builder inputFieldsUsed = fieldsUsed.rebuild();
    for (int keyIndex : distribution.getKeys()) {
      inputFieldsUsed.set(keyIndex);
    }

    // Create input with trimmed columns.
    final Set<RelDataTypeField> inputExtraFields = Collections.emptySet();
    final TrimResult trimResult =
        trimChild(exchange, input, inputFieldsUsed.build(), inputExtraFields);
    final RelNode newInput = trimResult.left;
    final Mapping inputMapping = trimResult.right;

    // If the input is unchanged, and we need to project all columns,
    // there's nothing we can do.
    if (newInput == input
        && inputMapping.isIdentity()
        && fieldsUsed.cardinality() == fieldCount) {
      return result(exchange, Mappings.createIdentity(fieldCount));
    }

    relBuilder.push(newInput);
    final RelDistribution newDistribution = distribution.apply(inputMapping);
    relBuilder.exchange(newDistribution);

    return result(relBuilder.build(), inputMapping, exchange);
  }

  public TrimResult trimFields(
      SortExchange sortExchange,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    final RelDataType rowType = sortExchange.getRowType();
    final int fieldCount = rowType.getFieldCount();
    final RelCollation collation = sortExchange.getCollation();
    final RelDistribution distribution = sortExchange.getDistribution();
    final RelNode input = sortExchange.getInput();

    // We use the fields used by the consumer, plus any fields used as sortExchange
    // keys.
    final ImmutableBitSet.Builder inputFieldsUsed = fieldsUsed.rebuild();
    for (RelFieldCollation field : collation.getFieldCollations()) {
      inputFieldsUsed.set(field.getFieldIndex());
    }
    for (int keyIndex : distribution.getKeys()) {
      inputFieldsUsed.set(keyIndex);
    }

    // Create input with trimmed columns.
    final Set<RelDataTypeField> inputExtraFields = Collections.emptySet();
    TrimResult trimResult =
        trimChild(sortExchange, input, inputFieldsUsed.build(), inputExtraFields);
    RelNode newInput = trimResult.left;
    final Mapping inputMapping = trimResult.right;

    // If the input is unchanged, and we need to project all columns,
    // there's nothing we can do.
    if (newInput == input
        && inputMapping.isIdentity()
        && fieldsUsed.cardinality() == fieldCount) {
      return result(sortExchange, Mappings.createIdentity(fieldCount));
    }

    relBuilder.push(newInput);
    RelCollation newCollation = RexUtil.apply(inputMapping, collation);
    RelDistribution newDistribution = distribution.apply(inputMapping);
    relBuilder.sortExchange(newDistribution, newCollation);

    return result(relBuilder.build(), inputMapping, sortExchange);
  }

  /**
   * Variant of {@link #trimFields(RelNode, ImmutableBitSet, Set)} for
   * {@link org.apache.calcite.rel.logical.LogicalJoin}.
   */
  public TrimResult trimFields(
      Join join,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    final int fieldCount = join.getSystemFieldList().size()
        + join.getLeft().getRowType().getFieldCount()
        + join.getRight().getRowType().getFieldCount();
    final RexNode conditionExpr = join.getCondition();
    final int systemFieldCount = join.getSystemFieldList().size();

    // Add in fields used in the condition.
    final Set<RelDataTypeField> combinedInputExtraFields =
        new LinkedHashSet<>(extraFields);
    RelOptUtil.InputFinder inputFinder =
        new RelOptUtil.InputFinder(combinedInputExtraFields, fieldsUsed);
    conditionExpr.accept(inputFinder);
    final ImmutableBitSet fieldsUsedPlus = inputFinder.build();

    // If no system fields are used, we can remove them.
    int systemFieldUsedCount = 0;
    for (int i = 0; i < systemFieldCount; ++i) {
      if (fieldsUsed.get(i)) {
        ++systemFieldUsedCount;
      }
    }
    final int newSystemFieldCount;
    if (systemFieldUsedCount == 0) {
      newSystemFieldCount = 0;
    } else {
      newSystemFieldCount = systemFieldCount;
    }

    int offset = systemFieldCount;
    int changeCount = 0;
    int newFieldCount = newSystemFieldCount;
    final List<RelNode> newInputs = new ArrayList<>(2);
    final List<Mapping> inputMappings = new ArrayList<>();
    final List<Integer> inputExtraFieldCounts = new ArrayList<>();
    for (RelNode input : join.getInputs()) {
      final RelDataType inputRowType = input.getRowType();
      final int inputFieldCount = inputRowType.getFieldCount();

      // Compute required mapping.
      ImmutableBitSet.Builder inputFieldsUsed = ImmutableBitSet.builder();
      for (int bit : fieldsUsedPlus) {
        if (bit >= offset && bit < offset + inputFieldCount) {
          inputFieldsUsed.set(bit - offset);
        }
      }

      // If there are system fields, we automatically use the
      // corresponding field in each input.
      inputFieldsUsed.set(0, newSystemFieldCount);

      // FIXME: We ought to collect extra fields for each input
      // individually. For now, we assume that just one input has
      // on-demand fields.
      Set<RelDataTypeField> inputExtraFields =
          RelDataTypeImpl.extra(inputRowType) == null
              ? Collections.emptySet()
              : combinedInputExtraFields;
      inputExtraFieldCounts.add(inputExtraFields.size());
      TrimResult trimResult =
          trimChild(join, input, inputFieldsUsed.build(), inputExtraFields);
      newInputs.add(trimResult.left);
      if (trimResult.left != input) {
        ++changeCount;
      }

      final Mapping inputMapping = trimResult.right;
      inputMappings.add(inputMapping);

      // Move offset to point to start of next input.
      offset += inputFieldCount;
      newFieldCount +=
          inputMapping.getTargetCount() + inputExtraFields.size();
    }

    Mapping mapping =
        Mappings.create(
            MappingType.INVERSE_SURJECTION,
            fieldCount,
            newFieldCount);
    for (int i = 0; i < newSystemFieldCount; ++i) {
      mapping.set(i, i);
    }
    offset = systemFieldCount;
    int newOffset = newSystemFieldCount;
    for (int i = 0; i < inputMappings.size(); i++) {
      Mapping inputMapping = inputMappings.get(i);
      for (IntPair pair : inputMapping) {
        mapping.set(pair.source + offset, pair.target + newOffset);
      }
      offset += inputMapping.getSourceCount();
      newOffset += inputMapping.getTargetCount()
          + inputExtraFieldCounts.get(i);
    }

    if (changeCount == 0
        && mapping.isIdentity()) {
      return result(join, Mappings.createIdentity(join.getRowType().getFieldCount()));
    }

    // Build new join.
    final RexVisitor<RexNode> shuttle =
        new RexPermuteInputsShuttle(
            mapping, newInputs.get(0), newInputs.get(1));
    RexNode newConditionExpr =
        conditionExpr.accept(shuttle);

    relBuilder.push(newInputs.get(0));
    relBuilder.push(newInputs.get(1));

    switch (join.getJoinType()) {
    case SEMI:
    case ANTI:
      // For SemiJoins and AntiJoins only map fields from the left-side
      if (join.getJoinType() == JoinRelType.SEMI) {
        relBuilder.semiJoin(newConditionExpr);
      } else {
        relBuilder.antiJoin(newConditionExpr);
      }
      Mapping inputMapping = inputMappings.get(0);
      mapping =
          Mappings.create(MappingType.INVERSE_SURJECTION,
              join.getRowType().getFieldCount(),
              newSystemFieldCount + inputMapping.getTargetCount());
      for (int i = 0; i < newSystemFieldCount; ++i) {
        mapping.set(i, i);
      }
      offset = systemFieldCount;
      newOffset = newSystemFieldCount;
      for (IntPair pair : inputMapping) {
        mapping.set(pair.source + offset, pair.target + newOffset);
      }
      break;
    default:
      relBuilder.join(join.getJoinType(), newConditionExpr);
    }
    return result(relBuilder.build(), mapping, join);
  }

  /**
   * Variant of {@link #trimFields(RelNode, ImmutableBitSet, Set)} for
   * {@link org.apache.calcite.rel.core.SetOp} (Only UNION ALL is supported).
   */
  public TrimResult trimFields(
      SetOp setOp,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    final RelDataType rowType = setOp.getRowType();
    final int fieldCount = rowType.getFieldCount();

    // Trim fields only for UNION ALL.
    //
    // UNION | INTERSECT | INTERSECT ALL | EXCEPT | EXCEPT ALL
    // all have comparison between branches.
    // They can not be trimmed because the comparison needs
    // complete fields.
    if (!(setOp.kind == SqlKind.UNION && setOp.all)) {
      return trimFields((RelNode) setOp, fieldsUsed, extraFields);
    }

    int changeCount = 0;

    // Fennel abhors an empty row type, so pretend that the parent rel
    // wants the last field. (The last field is the least likely to be a
    // system field.)
    if (fieldsUsed.isEmpty()) {
      fieldsUsed = ImmutableBitSet.of(rowType.getFieldCount() - 1);
    }

    // Compute the desired field mapping. Give the consumer the fields they
    // want, in the order that they appear in the bitset.
    final Mapping mapping = createMapping(fieldsUsed, fieldCount);

    // Create input with trimmed columns.
    for (RelNode input : setOp.getInputs()) {
      TrimResult trimResult =
          trimChild(setOp, input, fieldsUsed, extraFields);

      // We want "mapping", the input gave us "inputMapping", compute
      // "remaining" mapping.
      //    |                   |                |
      //    |---------------- mapping ---------->|
      //    |-- inputMapping -->|                |
      //    |                   |-- remaining -->|
      //
      // For instance, suppose we have columns [a, b, c, d],
      // the consumer asked for mapping = [b, d],
      // and the transformed input has columns inputMapping = [d, a, b].
      // remaining will permute [b, d] to [d, a, b].
      Mapping remaining = Mappings.divide(mapping, trimResult.right);

      // Create a projection; does nothing if remaining is identity.
      relBuilder.push(trimResult.left);
      relBuilder.permute(remaining);

      if (input != relBuilder.peek()) {
        ++changeCount;
      }
    }

    // If the input is unchanged, and we need to project all columns,
    // there's to do.
    if (changeCount == 0
        && mapping.isIdentity()) {
      for (@SuppressWarnings("unused") RelNode input : setOp.getInputs()) {
        relBuilder.build();
      }
      return result(setOp, mapping);
    }

    switch (setOp.kind) {
    case UNION:
      relBuilder.union(setOp.all, setOp.getInputs().size());
      break;
    case INTERSECT:
      relBuilder.intersect(setOp.all, setOp.getInputs().size());
      break;
    case EXCEPT:
      assert setOp.getInputs().size() == 2;
      relBuilder.minus(setOp.all);
      break;
    default:
      throw new AssertionError("unknown setOp " + setOp);
    }
    return result(relBuilder.build(), mapping, setOp);
  }

  /**
   * Variant of {@link #trimFields(RelNode, ImmutableBitSet, Set)} for
   * {@link org.apache.calcite.rel.logical.LogicalAggregate}.
   */
  public TrimResult trimFields(
      Aggregate aggregate,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    // Fields:
    //
    // | sys fields | group fields | indicator fields | agg functions |
    //
    // Two kinds of trimming:
    //
    // 1. If agg rel has system fields but none of these are used, create an
    // agg rel with no system fields.
    //
    // 2. If aggregate functions are not used, remove them.
    //
    // But group and indicator fields stay, even if they are not used.

    final RelDataType rowType = aggregate.getRowType();

    // Compute which input fields are used.
    // 1. group fields are always used
    final ImmutableBitSet.Builder inputFieldsUsed =
        aggregate.getGroupSet().rebuild();
    // 2. agg functions
    for (AggregateCall aggCall : aggregate.getAggCallList()) {
      inputFieldsUsed.addAll(aggCall.getArgList());
      if (aggCall.filterArg >= 0) {
        inputFieldsUsed.set(aggCall.filterArg);
      }
      if (aggCall.distinctKeys != null) {
        inputFieldsUsed.addAll(aggCall.distinctKeys);
      }
      inputFieldsUsed.addAll(RelCollations.ordinals(aggCall.collation));
    }

    // Create input with trimmed columns.
    final RelNode input = aggregate.getInput();
    final Set<RelDataTypeField> inputExtraFields = Collections.emptySet();
    final TrimResult trimResult =
        trimChild(aggregate, input, inputFieldsUsed.build(), inputExtraFields);
    final RelNode newInput = trimResult.left;
    final Mapping inputMapping = trimResult.right;
    // We have to return group keys and (if present) indicators.
    // So, pretend that the consumer asked for them.
    final int groupCount = aggregate.getGroupSet().cardinality();
    fieldsUsed =
        fieldsUsed.union(ImmutableBitSet.range(groupCount));

    // If the input is unchanged, and we need to project all columns,
    // there's nothing to do.
    if (input == newInput
        && fieldsUsed.equals(ImmutableBitSet.range(rowType.getFieldCount()))) {
      return result(aggregate,
          Mappings.createIdentity(rowType.getFieldCount()));
    }

    // Which agg calls are used by our consumer?
    int j = groupCount;
    int usedAggCallCount = 0;
    for (int i = 0; i < aggregate.getAggCallList().size(); i++) {
      if (fieldsUsed.get(j++)) {
        ++usedAggCallCount;
      }
    }

    // Offset due to the number of system fields having changed.
    Mapping mapping =
        Mappings.create(
            MappingType.INVERSE_SURJECTION,
            rowType.getFieldCount(),
            groupCount + usedAggCallCount);

    final ImmutableBitSet newGroupSet =
        Mappings.apply(inputMapping, aggregate.getGroupSet());

    final ImmutableList<ImmutableBitSet> newGroupSets =
        ImmutableList.copyOf(
            Util.transform(aggregate.getGroupSets(),
                input1 -> Mappings.apply(inputMapping, input1)));

    // Populate mapping of where to find the fields. System, group key and
    // indicator fields first.
    for (j = 0; j < groupCount; j++) {
      mapping.set(j, j);
    }

    // Now create new agg calls, and populate mapping for them.
    relBuilder.push(newInput);
    final List<RelBuilder.AggCall> newAggCallList = new ArrayList<>();
    j = groupCount;
    for (AggregateCall aggCall : aggregate.getAggCallList()) {
      if (fieldsUsed.get(j)) {
        mapping.set(j, groupCount + newAggCallList.size());
        newAggCallList.add(relBuilder.aggregateCall(aggCall, inputMapping));
      }
      ++j;
    }

    if (newAggCallList.isEmpty() && newGroupSet.isEmpty()) {
      // Add a dummy call if all the column fields have been trimmed
      mapping =
          Mappings.create(MappingType.INVERSE_SURJECTION,
              mapping.getSourceCount(), 1);
      newAggCallList.add(relBuilder.count(false, "DUMMY"));
    }

    final RelBuilder.GroupKey groupKey = relBuilder.groupKey(newGroupSet, newGroupSets);
    relBuilder.aggregate(groupKey, newAggCallList);

    final RelNode newAggregate = relBuilder.build();
    return result(newAggregate, mapping, aggregate);
  }

  /**
   * Variant of {@link #trimFields(RelNode, ImmutableBitSet, Set)} for
   * {@link org.apache.calcite.rel.logical.LogicalTableModify}.
   */
  public TrimResult trimFields(
      LogicalTableModify modifier,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    // Ignore what consumer wants. We always project all columns.
    Util.discard(fieldsUsed);

    final RelDataType rowType = modifier.getRowType();
    final int fieldCount = rowType.getFieldCount();
    RelNode input = modifier.getInput();

    // We want all fields from the child.
    final int inputFieldCount = input.getRowType().getFieldCount();
    final ImmutableBitSet inputFieldsUsed =
        ImmutableBitSet.range(inputFieldCount);

    // Create input with trimmed columns.
    final Set<RelDataTypeField> inputExtraFields = Collections.emptySet();
    TrimResult trimResult =
        trimChild(modifier, input, inputFieldsUsed, inputExtraFields);
    RelNode newInput = trimResult.left;
    final Mapping inputMapping = trimResult.right;
    if (!inputMapping.isIdentity()) {
      // We asked for all fields. Can't believe that the child decided
      // to permute them!
      throw new AssertionError(
          "Expected identity mapping, got " + inputMapping);
    }

    LogicalTableModify newModifier = modifier;
    if (newInput != input) {
      newModifier =
          modifier.copy(
              modifier.getTraitSet(),
              Collections.singletonList(newInput));
    }
    assert newModifier.getClass() == modifier.getClass();

    // Always project all fields.
    Mapping mapping = Mappings.createIdentity(fieldCount);
    return result(newModifier, mapping, modifier);
  }

  /**
   * Variant of {@link #trimFields(RelNode, ImmutableBitSet, Set)} for
   * {@link org.apache.calcite.rel.logical.LogicalTableFunctionScan}.
   */
  public TrimResult trimFields(
      LogicalTableFunctionScan tabFun,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    final RelDataType rowType = tabFun.getRowType();
    final int fieldCount = rowType.getFieldCount();
    final List<RelNode> newInputs = new ArrayList<>();

    for (RelNode input : tabFun.getInputs()) {
      final int inputFieldCount = input.getRowType().getFieldCount();
      ImmutableBitSet inputFieldsUsed = ImmutableBitSet.range(inputFieldCount);

      // Create input with trimmed columns.
      final Set<RelDataTypeField> inputExtraFields =
          Collections.emptySet();
      TrimResult trimResult =
          trimChildRestore(
              tabFun, input, inputFieldsUsed, inputExtraFields);
      assert trimResult.right.isIdentity();
      newInputs.add(trimResult.left);
    }

    LogicalTableFunctionScan newTabFun = tabFun;
    if (!tabFun.getInputs().equals(newInputs)) {
      newTabFun =
          tabFun.copy(tabFun.getTraitSet(), newInputs, tabFun.getCall(),
              tabFun.getElementType(), tabFun.getRowType(),
              tabFun.getColumnMappings());
    }
    assert newTabFun.getClass() == tabFun.getClass();

    // Always project all fields.
    Mapping mapping = Mappings.createIdentity(fieldCount);
    return result(newTabFun, mapping, tabFun);
  }

  /**
   * Variant of {@link #trimFields(RelNode, ImmutableBitSet, Set)} for
   * {@link org.apache.calcite.rel.logical.LogicalValues}.
   */
  public TrimResult trimFields(
      LogicalValues values,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    final RelDataType rowType = values.getRowType();
    final int fieldCount = rowType.getFieldCount();

    // If they are asking for no fields, we can't give them what they want,
    // because zero-column records are illegal. Give them the last field,
    // which is unlikely to be a system field.
    if (fieldsUsed.isEmpty()) {
      fieldsUsed = ImmutableBitSet.range(fieldCount - 1, fieldCount);
    }

    // If all fields are used, return unchanged.
    if (fieldsUsed.equals(ImmutableBitSet.range(fieldCount))) {
      Mapping mapping = Mappings.createIdentity(fieldCount);
      return result(values, mapping);
    }

    final ImmutableList.Builder<ImmutableList<RexLiteral>> newTuples =
        ImmutableList.builder();
    for (ImmutableList<RexLiteral> tuple : values.getTuples()) {
      ImmutableList.Builder<RexLiteral> newTuple = ImmutableList.builder();
      for (int field : fieldsUsed) {
        newTuple.add(tuple.get(field));
      }
      newTuples.add(newTuple.build());
    }

    final Mapping mapping = createMapping(fieldsUsed, fieldCount);
    final RelDataType newRowType =
        RelOptUtil.permute(values.getCluster().getTypeFactory(), rowType,
            mapping);
    final LogicalValues newValues =
        LogicalValues.create(values.getCluster(), newRowType,
            newTuples.build());
    return result(newValues, mapping, values);
  }

  /**
   * Variant of {@link #trimFields(RelNode, ImmutableBitSet, Set)} for
   * {@link org.apache.calcite.rel.core.Sample}.
   */
  public TrimResult trimFields(
      final Sample sample,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    final RelDataType rowType = sample.getRowType();
    final int fieldCount = rowType.getFieldCount();
    final RelNode input = sample.getInput();

    // Create input with trimmed columns.
    final Set<RelDataTypeField> inputExtraFields = Collections.emptySet();
    TrimResult trimResult =
        trimChild(sample, input, fieldsUsed, inputExtraFields);
    final RelNode newInput = trimResult.left;
    final Mapping inputMapping = trimResult.right;

    // If the input is unchanged, and we need to project all columns,
    // there's nothing we can do.
    if (newInput == input
        && fieldsUsed.cardinality() == fieldCount) {
      return result(sample, Mappings.createIdentity(fieldCount));
    }

    final RelNode newSample =
        sample.copy(sample.getTraitSet(), ImmutableList.of(newInput));
    return result(newSample, inputMapping, sample);
  }

  /**
   * Variant of {@link #trimFields(RelNode, ImmutableBitSet, Set)} for
   * {@link org.apache.calcite.rel.core.Snapshot}.
   */
  public TrimResult trimFields(
      final Snapshot snapshot,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    final RelDataType rowType = snapshot.getRowType();
    final int fieldCount = rowType.getFieldCount();
    final RelNode input = snapshot.getInput();

    // Create input with trimmed columns.
    final Set<RelDataTypeField> inputExtraFields = Collections.emptySet();
    TrimResult trimResult =
        trimChild(snapshot, input, fieldsUsed, inputExtraFields);
    final RelNode newInput = trimResult.left;
    final Mapping inputMapping = trimResult.right;

    // If the input is unchanged, and we need to project all columns,
    // there's nothing we can do.
    if (newInput == input
        && fieldsUsed.cardinality() == fieldCount) {
      return result(snapshot, Mappings.createIdentity(fieldCount));
    }

    final Snapshot newSnapshot =
        snapshot.copy(snapshot.getTraitSet(), newInput,
        snapshot.getPeriod());
    return result(newSnapshot, inputMapping, snapshot);
  }

  protected Mapping createMapping(ImmutableBitSet fieldsUsed, int fieldCount) {
    final Mapping mapping =
        Mappings.create(
            MappingType.INVERSE_SURJECTION,
            fieldCount,
            fieldsUsed.cardinality());
    int i = 0;
    for (int field : fieldsUsed) {
      mapping.set(field, i++);
    }
    return mapping;
  }

  /**
   * Variant of {@link #trimFields(RelNode, ImmutableBitSet, Set)} for
   * {@link org.apache.calcite.rel.logical.LogicalTableScan}.
   */
  public TrimResult trimFields(
      final TableScan tableAccessRel,
      ImmutableBitSet fieldsUsed,
      Set<RelDataTypeField> extraFields) {
    final int fieldCount = tableAccessRel.getRowType().getFieldCount();
    if (fieldsUsed.equals(ImmutableBitSet.range(fieldCount))
        && extraFields.isEmpty()) {
      // if there is nothing to project or if we are projecting everything
      // then no need to introduce another RelNode
      return trimFields(
          (RelNode) tableAccessRel, fieldsUsed, extraFields);
    }
    final RelNode newTableAccessRel =
        tableAccessRel.project(fieldsUsed, extraFields, relBuilder);

    // Some parts of the system can't handle rows with zero fields, so
    // pretend that one field is used.
    if (fieldsUsed.cardinality() == 0) {
      RelNode input = newTableAccessRel;
      if (input instanceof Project) {
        // The table has implemented the project in the obvious way - by
        // creating project with 0 fields. Strip it away, and create our own
        // project with one field.
        Project project = (Project) input;
        if (project.getRowType().getFieldCount() == 0) {
          input = project.getInput();
        }
      }
      return dummyProject(fieldCount, input);
    }

    final Mapping mapping = createMapping(fieldsUsed, fieldCount);
    return result(newTableAccessRel, mapping, tableAccessRel);
  }

  //~ Inner Classes ----------------------------------------------------------

  /**
   * Result of an attempt to trim columns from a relational expression.
   *
   * <p>The mapping describes where to find the columns wanted by the parent
   * of the current relational expression.
   *
   * <p>The mapping is a
   * {@link org.apache.calcite.util.mapping.Mappings.SourceMapping}, which means
   * that no column can be used more than once, and some columns are not used.
   * {@code columnsUsed.getSource(i)} returns the source of the i'th output
   * field.
   *
   * <p>For example, consider the mapping for a relational expression that
   * has 4 output columns but only two are being used. The mapping
   * {2 &rarr; 1, 3 &rarr; 0} would give the following behavior:
   *
   * <ul>
   * <li>columnsUsed.getSourceCount() returns 4
   * <li>columnsUsed.getTargetCount() returns 2
   * <li>columnsUsed.getSource(0) returns 3
   * <li>columnsUsed.getSource(1) returns 2
   * <li>columnsUsed.getSource(2) throws IndexOutOfBounds
   * <li>columnsUsed.getTargetOpt(3) returns 0
   * <li>columnsUsed.getTargetOpt(0) returns -1
   * </ul>
   */
  // 由于 RelFieldTrimmer 在裁剪字段时会自底向上重构整棵算子树，改变子节点的输出意味着父节点中所有对子节点的列索引引用（RexInputRef）都必须进行重置。
  // TrimResult 就是用来完美记录“瘦身后的新算子”以及“新老列索引之间的映射关系”的。
  // left (即 Pair.left)：保存裁剪优化后的新算子节点（RelNode）。
  // right (即 Pair.right)：保存列索引映射矩阵（Mapping）。
  protected static class TrimResult extends Pair<RelNode, Mapping> {
    /**
     * Creates a TrimResult.
     *
     * @param left  New relational expression
     * @param right Mapping of fields onto original fields
     */
    public TrimResult(RelNode left, Mapping right) {
      super(left, right);
      assert right.getTargetCount() == left.getRowType().getFieldCount()
          : "rowType: " + left.getRowType() + ", mapping: " + right;
    }
  }
}
