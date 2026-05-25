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
import org.apache.calcite.linq4j.function.Experimental;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelInput;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.hint.Hintable;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.runtime.CalciteException;
import org.apache.calcite.runtime.Resources;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidatorException;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.math.IntMath;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;

import static java.util.Objects.requireNonNull;

/**
 * Relational operator that eliminates
 * duplicates and computes totals.
 *
 * <p>It corresponds to the {@code GROUP BY} operator in a SQL query
 * statement, together with the aggregate functions in the {@code SELECT}
 * clause.
 * <p>Rules:
 *
 * <ul>
 * <li>{@link org.apache.calcite.rel.rules.AggregateProjectPullUpConstantsRule}
 * <li>{@link org.apache.calcite.rel.rules.AggregateExpandDistinctAggregatesRule}
 * <li>{@link org.apache.calcite.rel.rules.AggregateReduceFunctionsRule}.
 * </ul>
 */
// 在关系代数和 SQL 树中，Aggregate 对应的是 分组聚合算子，即标准 SQL 中的 GROUP BY 语句以及伴随的聚合函数（如 COUNT、SUM、AVG、MAX、MIN）。
// 核心职责是：
// 数据降维与去重：它接收单股输入流（input），通过指定的分组键（groupSet）将多行数据合并、去重为更少的行。如果没有任何分组键（即 GROUP BY ()），则整张表的数据将被压缩聚合为单行（Grand Total）。
// 多维分析（高级分组）：它不仅支持标准的分组，还天然支持数据仓库中的多维度分析算子——ROLLUP（上卷）、CUBE（多维立方体）和 GROUPING SETS（分组集）。它通过 groupSets 属性精妙地映射了这一多维流派。
// 聚合指标映射：它持有一组 AggregateCall 列表，每一个 AggregateCall 代表一个具体的聚合计算任务，定义了函数类型、参数位置、以及可选的过滤条件（FILTER (WHERE ...)）。


public abstract class Aggregate extends SingleRel implements Hintable {
  // 当前聚合算子持有的 SQL 提示（Hints）列表，如 /*+ AGG_STRATEGY(HASH) */。
  protected final ImmutableList<RelHint> hints;
  // 判断当前聚合算子是否为简单聚合的 Guava 谓词封装
  public static boolean isSimple(Aggregate aggregate) {
    return aggregate.getGroupType() == Group.SIMPLE;
  }

  @SuppressWarnings("Guava")
  @Deprecated // to be converted to Java Predicate before 2.0
  // 判断当前聚合算子是否为简单聚合的 Guava 谓词封装
  public static final com.google.common.base.Predicate<Aggregate> IS_SIMPLE =
      Aggregate::isSimple;

  @SuppressWarnings("Guava")
  // 历史遗留谓词，始终返回 true。
  @Deprecated // to be converted to Java Predicate before 2.0
  public static final com.google.common.base.Predicate<Aggregate> NO_INDICATOR =
      Aggregate::noIndicator;

  @SuppressWarnings("Guava")
  // 判断当前聚合算子是否不是全局全量汇总的谓词封装。
  @Deprecated // to be converted to Java Predicate before 2.0
  public static final com.google.common.base.Predicate<Aggregate>
      IS_NOT_GRAND_TOTAL = Aggregate::isNotGrandTotal;

  /** Used internally; will removed when {@link #indicator} is removed,
   * before 2.0. */
  @Experimental
  public static void checkIndicator(boolean indicator) {
    checkArgument(!indicator,
        "indicator is no longer supported; use GROUPING function instead");
  }

  //~ Instance fields --------------------------------------------------------

  @Deprecated // unused field, to be removed before 2.0
  // 历史版本中用于标识是否生成专门的指示列，现已被完全弃用，统一使用 GROUPING 函数代替。
  public final boolean indicator = false;
  // 聚合函数调用列表。
  // 记录了需要计算的所有指标（例如 SUM(sales), COUNT(DISTINCT user_id)）。每一个 AggregateCall 都定义了其对应的物理数据和输出类型。
  protected final List<AggregateCall> aggCalls;
  // 所有参与过分组的输入列索引的并集。
  // 物理含义：使用位图（BitSet）来高效率记录哪些列是分组键。例如位图为 {0, 2}，代表输入端的第 0 列和第 2 列是分组字段。
  protected final ImmutableBitSet groupSet;
  // 分组集的物理列表（包含多维组合）。
  // 物理含义：用于支持 GROUPING SETS。如果只是简单的 GROUP BY x, y，该列表中将只包含一个元素，即 groupSet 本身。如果是 ROLLUP(x, y)，该列表中会包含三个位图：{x, y}, {x}, {}。
  public final ImmutableList<ImmutableBitSet> groupSets;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates an Aggregate.
   *
   * <p>All members of {@code groupSets} must be sub-sets of {@code groupSet}.
   * For a simple {@code GROUP BY}, {@code groupSets} is a singleton list
   * containing {@code groupSet}.
   *
   * <p>It is allowed for {@code groupSet} to contain bits that are not in any
   * of the {@code groupSets}, even this does not correspond to valid SQL. See
   * discussion in
   * {@link org.apache.calcite.tools.RelBuilder#groupKey(ImmutableBitSet, Iterable)}.
   *
   * <p>If {@code GROUP BY} is not specified,
   * or equivalently if {@code GROUP BY ()} is specified,
   * {@code groupSet} will be the empty set,
   * and {@code groupSets} will have one element, that empty set.
   *
   * <p>If {@code CUBE}, {@code ROLLUP} or {@code GROUPING SETS} are
   * specified, {@code groupSets} will have additional elements,
   * but they must each be a subset of {@code groupSet},
   * and they must be sorted by inclusion:
   * {@code (0, 1, 2), (1), (0, 2), (0), ()}.
   *
   * @param cluster  Cluster
   * @param traitSet Trait set
   * @param hints    Hints of this relational expression
   * @param input    Input relational expression
   * @param groupSet Bit set of grouping fields
   * @param groupSets List of all grouping sets; null for just {@code groupSet}
   * @param aggCalls Collection of calls to aggregate functions
   */
  @SuppressWarnings("method.invocation.invalid")
  protected Aggregate(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      List<RelHint> hints,
      RelNode input,
      ImmutableBitSet groupSet,
      @Nullable List<ImmutableBitSet> groupSets,
      List<AggregateCall> aggCalls) {
    super(cluster, traitSet, input);
    this.hints = ImmutableList.copyOf(hints);
    this.aggCalls = ImmutableList.copyOf(aggCalls);
    this.groupSet = requireNonNull(groupSet, "groupSet");
    // 如果 groupSets 为 null，则自动将其降级初始化为仅包含唯一 groupSet 的单例列表（代表简单 GROUP BY）。
    if (groupSets == null) {
      this.groupSets = ImmutableList.of(groupSet);
    } else {
      this.groupSets = ImmutableList.copyOf(groupSets);
      // 断言校验 groupSets 内的每一个子分组集必须已被强力递减排序（isStrictlyOrdered）
      assert ImmutableBitSet.ORDERING.isStrictlyOrdered(groupSets) : groupSets;
      // 断言校验 groupSet 必须包含所有子分组集（即子集合法性校验：groupSet.contains(set)）
      for (ImmutableBitSet set : groupSets) {
        assert groupSet.contains(set);
      }
    }
    //
    assert groupSet.length() <= input.getRowType().getFieldCount();
    // 逐一遍历 aggCalls，通过 typeMatchesInferred 核准每一个聚合函数声明的输出类型与类型工厂推导出来的真实类型是否完全一致。
    for (AggregateCall aggCall : aggCalls) {
      assert typeMatchesInferred(aggCall, Litmus.THROW);
      checkArgument(aggCall.filterArg < 0
          || isPredicate(input, aggCall.filterArg),
          "filter must be BOOLEAN NOT NULL");
    }
  }

  @Deprecated // to be removed before 2.0
  protected Aggregate(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode input,
      ImmutableBitSet groupSet,
      List<ImmutableBitSet> groupSets,
      List<AggregateCall> aggCalls) {
    this(cluster, traitSet, new ArrayList<>(), input, groupSet, groupSets, aggCalls);
  }

  @Deprecated // to be removed before 2.0
  protected Aggregate(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode child,
      boolean indicator,
      ImmutableBitSet groupSet,
      List<ImmutableBitSet> groupSets,
      List<AggregateCall> aggCalls) {
    this(cluster, traits, ImmutableList.of(), child, groupSet, groupSets, aggCalls);
    checkIndicator(indicator);
  }

  public static boolean isNotGrandTotal(Aggregate aggregate) {
    return aggregate.getGroupCount() > 0;
  }

  @Deprecated // to be removed before 2.0
  public static boolean noIndicator(Aggregate aggregate) {
    return true;
  }

  private static boolean isPredicate(RelNode input, int index) {
    final RelDataType type =
        input.getRowType().getFieldList().get(index).getType();
    return type.getSqlTypeName() == SqlTypeName.BOOLEAN
        && !type.isNullable();
  }

  /**
   * Creates an Aggregate by parsing serialized output.
   */
  protected Aggregate(RelInput input) {
    this(input.getCluster(), input.getTraitSet(), new ArrayList<>(),
        input.getInput(), input.getBitSet("group"),
        input.getBitSetList("groups"), input.getAggregateCalls("aggs"));
  }

  //~ Methods ----------------------------------------------------------------

  @Override public final RelNode copy(RelTraitSet traitSet,
      List<RelNode> inputs) {
    return copy(traitSet, sole(inputs), groupSet, groupSets, aggCalls);
  }

  /** Creates a copy of this aggregate.
   *
   * @param traitSet Traits
   * @param input Input
   * @param groupSet Bit set of grouping fields
   * @param groupSets List of all grouping sets; null for just {@code groupSet}
   * @param aggCalls Collection of calls to aggregate functions
   * @return New {@code Aggregate} if any parameter differs from the value of
   *   this {@code Aggregate}, or just {@code this} if all the parameters are
   *   the same
   *
   * @see #copy(org.apache.calcite.plan.RelTraitSet, java.util.List)
   */
  public abstract Aggregate copy(RelTraitSet traitSet, RelNode input,
      ImmutableBitSet groupSet,
      @Nullable List<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls);

  @Deprecated // to be removed before 2.0
  public Aggregate copy(RelTraitSet traitSet, RelNode input,
      boolean indicator, ImmutableBitSet groupSet,
      List<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls) {
    checkIndicator(indicator);
    return copy(traitSet, input, groupSet, groupSets, aggCalls);
  }

  /**
   * Returns a list of calls to aggregate functions.
   *
   * @return list of calls to aggregate functions
   */
  public List<AggregateCall> getAggCallList() {
    return aggCalls;
  }

  /**
   * Returns a list of calls to aggregate functions together with their output
   * field names.
   *
   * @return list of calls to aggregate functions and their output field names
   */
  public List<Pair<AggregateCall, String>> getNamedAggCalls() {
    final int offset = getGroupCount();
    return Pair.zip(aggCalls, Util.skip(getRowType().getFieldNames(), offset));
  }

  /**
   * Returns the number of grouping fields.
   * These grouping fields are the leading fields in both the input and output
   * records.
   *
   * <p>NOTE: The {@link #getGroupSet()} data structure allows for the
   * grouping fields to not be on the leading edge. New code should, if
   * possible, assume that grouping fields are in arbitrary positions in the
   * input relational expression.
   *
   * @return number of grouping fields
   */
  public int getGroupCount() {
    return groupSet.cardinality();
  }

  /**
   * Returns the number of indicator fields.
   *
   * <p>Always zero.
   *
   * @return number of indicator fields, always zero
   */
  @Deprecated // to be removed before 2.0
  public int getIndicatorCount() {
    return 0;
  }

  /**
   * Returns a bit set of the grouping fields.
   *
   * @return bit set of ordinals of grouping fields
   */
  public ImmutableBitSet getGroupSet() {
    return groupSet;
  }

  /**
   * Returns the list of grouping sets computed by this Aggregate.
   *
   * @return List of all grouping sets
   */
  public ImmutableList<ImmutableBitSet> getGroupSets() {
    return groupSets;
  }

  @Override public RelWriter explainTerms(RelWriter pw) {
    // We skip the "groups" element if it is a singleton of "group".
    super.explainTerms(pw)
        .item("group", groupSet)
        .itemIf("groups", groupSets, getGroupType() != Group.SIMPLE)
        .itemIf("aggs", aggCalls, pw.nest());
    if (!pw.nest()) {
      for (Ord<AggregateCall> ord : Ord.zip(aggCalls)) {
        pw.item(Util.first(ord.e.name, "agg#" + ord.i), ord.e);
      }
    }
    return pw;
  }

  @Override public double estimateRowCount(RelMetadataQuery mq) {
    // Assume that each sort column has 50% of the value count.
    // Therefore one sort column has .5 * rowCount,
    // 2 sort columns give .75 * rowCount.
    // Zero sort columns yields 1 row (or 0 if the input is empty).
    final int groupCount = groupSet.cardinality();
    if (groupCount == 0) {
      return 1;
    } else {
      double rowCount = super.estimateRowCount(mq);
      rowCount *= 1.0 - Math.pow(.5, groupCount);
      return rowCount;
    }
  }

  @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
      RelMetadataQuery mq) {
    // REVIEW jvs 24-Aug-2008:  This is bogus, but no more bogus
    // than what's currently in Join.
    double rowCount = mq.getRowCount(this);
    // Aggregates with more aggregate functions cost a bit more
    float multiplier = 1f + (float) aggCalls.size() * 0.125f;
    for (AggregateCall aggCall : aggCalls) {
      if (aggCall.getAggregation().getName().equals("SUM")) {
        // Pretend that SUM costs a little bit more than $SUM0,
        // to make things deterministic.
        multiplier += 0.0125f;
      }
    }
    return planner.getCostFactory().makeCost(rowCount * multiplier, 0, 0);
  }

  @Override protected RelDataType deriveRowType() {
    return deriveRowType(getCluster().getTypeFactory(), getInput().getRowType(),
        false, groupSet, groupSets, aggCalls);
  }

  /**
   * Computes the row type of an {@code Aggregate} before it exists.
   *
   * @param typeFactory Type factory
   * @param inputRowType Input row type
   * @param indicator Deprecated, always false
   * @param groupSet Bit set of grouping fields
   * @param groupSets List of all grouping sets; null for just {@code groupSet}
   * @param aggCalls Collection of calls to aggregate functions
   * @return Row type of the aggregate
   */
  public static RelDataType deriveRowType(RelDataTypeFactory typeFactory,
      final RelDataType inputRowType, boolean indicator,
      ImmutableBitSet groupSet, @Nullable List<ImmutableBitSet> groupSets,
      final List<AggregateCall> aggCalls) {
    final List<Integer> groupList = groupSet.asList();
    assert groupList.size() == groupSet.cardinality();
    final RelDataTypeFactory.Builder builder = typeFactory.builder();
    final List<RelDataTypeField> fieldList = inputRowType.getFieldList();
    final Set<String> containedNames = new HashSet<>();
    for (int groupKey : groupList) {
      final RelDataTypeField field = fieldList.get(groupKey);
      containedNames.add(field.getName());
      builder.add(field);
      if (groupSets != null && !ImmutableBitSet.allContain(groupSets, groupKey)) {
        builder.nullable(true);
      }
    }
    checkIndicator(indicator);
    for (Ord<AggregateCall> aggCall : Ord.zip(aggCalls)) {
      final String base;
      if (aggCall.e.name != null) {
        base = aggCall.e.name;
      } else {
        base = "$f" + (groupList.size() + aggCall.i);
      }
      String name = base;
      int i = 0;
      while (containedNames.contains(name)) {
        name = base + "_" + i++;
      }
      containedNames.add(name);
      builder.add(name, aggCall.e.type);
    }
    return builder.build();
  }

  @Override public boolean isValid(Litmus litmus, @Nullable Context context) {
    return super.isValid(litmus, context)
        && litmus.check(Util.isDistinct(getRowType().getFieldNames()),
            "distinct field names: {}", getRowType());
  }

  /**
   * Returns whether the inferred type of an {@link AggregateCall} matches the
   * type it was given when it was created.
   *
   * @param aggCall Aggregate call
   * @param litmus What to do if an error is detected (types do not match)
   * @return Whether the inferred and declared types match
   */
  private boolean typeMatchesInferred(
      final AggregateCall aggCall,
      final Litmus litmus) {
    SqlAggFunction aggFunction = aggCall.getAggregation();
    AggCallBinding callBinding = aggCall.createBinding(this);
    RelDataType type = aggFunction.inferReturnType(callBinding);
    RelDataType expectedType = aggCall.type;
    return RelOptUtil.eq("aggCall type",
        expectedType,
        "inferred type",
        type,
        litmus);
  }

  /**
   * Returns whether any of the aggregates are DISTINCT.
   *
   * @return Whether any of the aggregates are DISTINCT
   */
  public boolean containsDistinctCall() {
    for (AggregateCall call : aggCalls) {
      if (call.isDistinct()) {
        return true;
      }
    }
    return false;
  }

  @Override public ImmutableList<RelHint> getHints() {
    return hints;
  }

  /**
   * Returns the type of roll-up.
   *
   * @return Type of roll-up
   */
  public Group getGroupType() {
    return Group.induce(groupSet, groupSets);
  }

  /** Describes the kind of roll-up. */
  public enum Group {
    SIMPLE, //简单分组通常指的是直接根据一个列或多个列进行分组
    ROLLUP, //针对多个列的分组，ROLLUP会依次进行更少列的聚合，直到最后汇总出空集合
    CUBE,  //CUBE会计算所有列的所有组合的聚合结果，包括列的每种可能子集
    OTHER; //表示其它不属于上述分类的分组方式

    public static Group induce(ImmutableBitSet groupSet,
        List<ImmutableBitSet> groupSets) {
      if (!ImmutableBitSet.ORDERING.isStrictlyOrdered(groupSets)) {
        throw new IllegalArgumentException("must be sorted: " + groupSets);
      }
      if (groupSets.size() == 1 && groupSets.get(0).equals(groupSet)) {
        return SIMPLE;
      }
      if (groupSets.size() == IntMath.pow(2, groupSet.cardinality())) {
        return CUBE;
      }
      if (isRollup(groupSet, groupSets)) {
        return ROLLUP;
      }
      return OTHER;
    }

    /** Returns whether a list of sets is a rollup.
     *
     * <p>For example, if {@code groupSet} is <code>{2, 4, 5}</code>, then
     * <code>[{2, 4, 5], {2, 5}, {5}, {}]</code> is a rollup. The first item is
     * equal to {@code groupSet}, and each subsequent item is a subset with one
     * fewer bit than the previous.
     *
     * @see #getRollup(List) */
    public static boolean isRollup(ImmutableBitSet groupSet,
        List<ImmutableBitSet> groupSets) {
      if (groupSets.size() != groupSet.cardinality() + 1) {
        return false;
      }
      ImmutableBitSet g = null;
      for (ImmutableBitSet bitSet : groupSets) {
        if (g == null) {
          // First item must equal groupSet
          if (!bitSet.equals(groupSet)) {
            return false;
          }
        } else {
          // Each subsequent items must be a subset with one fewer bit than the
          // previous item
          if (!g.contains(bitSet)
              || g.cardinality() - bitSet.cardinality() != 1) {
            return false;
          }
        }
        g = bitSet;
      }
      assert g != null : "groupSet must not be empty";
      assert g.isEmpty();
      return true;
    }

    /** Returns the ordered list of bits in a rollup.
     *
     * <p>For example, given a {@code groupSets} value
     * <code>[{2, 4, 5], {2, 5}, {5}, {}]</code>, returns the list
     * {@code [5, 2, 4]}, which are the succession of bits
     * added to each of the sets starting with the empty set.
     *
     * @see #isRollup(ImmutableBitSet, List) */
    public static List<Integer> getRollup(List<ImmutableBitSet> groupSets) {
      final List<Integer> rollUpBits = new ArrayList<>(groupSets.size() - 1);
      ImmutableBitSet g = null;
      for (ImmutableBitSet bitSet : groupSets) {
        if (g == null) {
          // First item must equal groupSet
        } else {
          // Each subsequent items must be a subset with one fewer bit than the
          // previous item
          ImmutableBitSet diff = g.except(bitSet);
          assert diff.cardinality() == 1;
          rollUpBits.add(diff.nth(0));
        }
        g = bitSet;
      }
      Collections.reverse(rollUpBits);
      return ImmutableList.copyOf(rollUpBits);
    }
  }

  //~ Inner Classes ----------------------------------------------------------

  /**
   * Implementation of the {@link SqlOperatorBinding} interface for an
   * {@link AggregateCall aggregate call} applied to a set of operands in the
   * context of a {@link org.apache.calcite.rel.logical.LogicalAggregate}.
   */
  public static class AggCallBinding extends SqlOperatorBinding {
    private final List<RelDataType> preOperands;
    private final List<RelDataType> operands;
    private final int groupCount;
    private final boolean filter;

    /**
     * Creates an AggCallBinding.
     *
     * @param typeFactory  Type factory
     * @param aggFunction  Aggregate function
     * @param preOperands  Data types of pre-operands
     * @param operands     Data types of operands
     * @param groupCount   Number of columns in the GROUP BY clause
     * @param filter       Whether the aggregate function has a FILTER clause
     */
    public AggCallBinding(RelDataTypeFactory typeFactory,
        SqlAggFunction aggFunction, List<RelDataType> preOperands,
        List<RelDataType> operands, int groupCount,
        boolean filter) {
      super(typeFactory, aggFunction);
      this.preOperands = requireNonNull(preOperands, "preOperands");
      this.operands =
          requireNonNull(operands,
              "operands of aggregate call should not be null");
      this.groupCount = groupCount;
      this.filter = filter;
      checkArgument(groupCount >= 0,
          "number of group by columns should be greater than zero in "
              + "aggregate call. Got %s", groupCount);
    }

    @Deprecated // to be removed before 2.0
    public AggCallBinding(RelDataTypeFactory typeFactory,
        SqlAggFunction aggFunction, List<RelDataType> operands, int groupCount,
        boolean filter) {
      this(typeFactory, aggFunction, ImmutableList.of(), operands, groupCount,
          filter);
    }

    @Override public int getGroupCount() {
      return groupCount;
    }

    @Override public boolean hasFilter() {
      return filter;
    }

    @Override public int getPreOperandCount() {
      return preOperands.size();
    }

    @Override public int getOperandCount() {
      return preOperands.size() + operands.size();
    }

    @Override public RelDataType getOperandType(int ordinal) {
      return ordinal < preOperands.size()
          ? preOperands.get(ordinal)
          : operands.get(ordinal - preOperands.size());
    }

    @Override public CalciteException newError(
        Resources.ExInst<SqlValidatorException> e) {
      return SqlUtil.newContextException(SqlParserPos.ZERO, e);
    }
  }

  /** Used for PERCENTILE_DISC return type inference. */
  public static class PercentileDiscAggCallBinding extends AggCallBinding {
    private final RelDataType collationType;

    PercentileDiscAggCallBinding(RelDataTypeFactory typeFactory, SqlAggFunction aggFunction,
        List<RelDataType> operands, RelDataType collationType, int groupCount,
        boolean filter) {
      super(typeFactory, aggFunction, operands, groupCount, filter);
      assert aggFunction.isPercentile();
      this.collationType = collationType;
    }

    @Override public RelDataType getCollationType() {
      return collationType;
    }
  }
}
