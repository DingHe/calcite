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
package org.apache.calcite.adapter.jdbc;

import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.plan.Contexts;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.InvalidRelException;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Intersect;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Minus;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.rel2sql.SqlImplementor;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.schema.ModifiableTable;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Util;
import org.apache.calcite.util.trace.CalciteTrace;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;

import static java.util.Objects.requireNonNull;

/**
 * Rules and relational operators for
 * {@link JdbcConvention}
 * calling convention.
 */
public class JdbcRules {
  private JdbcRules() {
  }

  protected static final Logger LOGGER = CalciteTrace.getPlannerTracer();

  static final RelFactories.ProjectFactory PROJECT_FACTORY =
      (input, hints, projects, fieldNames, variablesSet) -> {
        checkArgument(variablesSet.isEmpty(),
            "JdbcProject does not allow variables");
        final RelOptCluster cluster = input.getCluster();
        final RelDataType rowType =
            RexUtil.createStructType(cluster.getTypeFactory(), projects,
                fieldNames, SqlValidatorUtil.F_SUGGESTER);
        return new JdbcProject(cluster, input.getTraitSet(), input, projects,
            rowType);
      };

  static final RelFactories.FilterFactory FILTER_FACTORY =
      (input, condition, variablesSet) -> {
        checkArgument(variablesSet.isEmpty(),
            "JdbcFilter does not allow variables");
        return new JdbcFilter(input.getCluster(),
            input.getTraitSet(), input, condition);
      };

  static final RelFactories.JoinFactory JOIN_FACTORY =
      (left, right, hints, condition, variablesSet, joinType, semiJoinDone) -> {
        final RelOptCluster cluster = left.getCluster();
        final RelTraitSet traitSet =
            cluster.traitSetOf(
                requireNonNull(left.getConvention(), "left.getConvention()"));
        try {
          return new JdbcJoin(cluster, traitSet, left, right, condition,
              variablesSet, joinType);
        } catch (InvalidRelException e) {
          throw new AssertionError(e);
        }
      };

  static final RelFactories.CorrelateFactory CORRELATE_FACTORY =
      (left, right, hints, correlationId, requiredColumns, joinType) -> {
        throw new UnsupportedOperationException("JdbcCorrelate");
      };

  public static final RelFactories.SortFactory SORT_FACTORY =
      (input, collation, offset, fetch) -> {
        throw new UnsupportedOperationException("JdbcSort");
      };

  public static final RelFactories.ExchangeFactory EXCHANGE_FACTORY =
      (input, distribution) -> {
        throw new UnsupportedOperationException("JdbcExchange");
      };

  public static final RelFactories.SortExchangeFactory SORT_EXCHANGE_FACTORY =
      (input, distribution, collation) -> {
        throw new UnsupportedOperationException("JdbcSortExchange");
      };

  public static final RelFactories.AggregateFactory AGGREGATE_FACTORY =
      (input, hints, groupSet, groupSets, aggCalls) -> {
        final RelOptCluster cluster = input.getCluster();
        final RelTraitSet traitSet =
            cluster.traitSetOf(
                requireNonNull(input.getConvention(), "input.getConvention()"));
        try {
          return new JdbcAggregate(cluster, traitSet, input, groupSet,
              groupSets, aggCalls);
        } catch (InvalidRelException e) {
          throw new AssertionError(e);
        }
      };

  public static final RelFactories.MatchFactory MATCH_FACTORY =
      (input, pattern, rowType, strictStart, strictEnd, patternDefinitions,
          measures, after, subsets, allRows, partitionKeys, orderKeys,
          interval) -> {
        throw new UnsupportedOperationException("JdbcMatch");
      };

  public static final RelFactories.SetOpFactory SET_OP_FACTORY =
      (kind, inputs, all) -> {
        RelNode input = inputs.get(0);
        RelOptCluster cluster = input.getCluster();
        final RelTraitSet traitSet =
            cluster.traitSetOf(
                requireNonNull(input.getConvention(), "input.getConvention()"));
        switch (kind) {
        case UNION:
          return new JdbcUnion(cluster, traitSet, inputs, all);
        case INTERSECT:
          return new JdbcIntersect(cluster, traitSet, inputs, all);
        case EXCEPT:
          return new JdbcMinus(cluster, traitSet, inputs, all);
        default:
          throw new AssertionError("unknown: " + kind);
        }
      };

  public static final RelFactories.ValuesFactory VALUES_FACTORY =
      (cluster, rowType, tuples) -> {
        throw new UnsupportedOperationException();
      };

  public static final RelFactories.TableScanFactory TABLE_SCAN_FACTORY =
      (toRelContext, table) -> {
        throw new UnsupportedOperationException();
      };

  public static final RelFactories.SnapshotFactory SNAPSHOT_FACTORY =
      (input, period) -> {
        throw new UnsupportedOperationException();
      };

  /** A {@link RelBuilderFactory} that creates a {@link RelBuilder} that will
   * create JDBC relational expressions for everything. */
  public static final RelBuilderFactory JDBC_BUILDER =
      RelBuilder.proto(
          Contexts.of(PROJECT_FACTORY,
              FILTER_FACTORY,
              JOIN_FACTORY,
              SORT_FACTORY,
              EXCHANGE_FACTORY,
              SORT_EXCHANGE_FACTORY,
              AGGREGATE_FACTORY,
              MATCH_FACTORY,
              SET_OP_FACTORY,
              VALUES_FACTORY,
              TABLE_SCAN_FACTORY,
              SNAPSHOT_FACTORY));

  /** Creates a list of rules with the given JDBC convention instance. */
  public static List<RelOptRule> rules(JdbcConvention out) {
    final ImmutableList.Builder<RelOptRule> b = ImmutableList.builder();
    foreachRule(out, b::add);
    return b.build();
  }

  /** Creates a list of rules with the given JDBC convention instance
   * and builder factory. */
  public static List<RelOptRule> rules(JdbcConvention out,
      RelBuilderFactory relBuilderFactory) {
    final ImmutableList.Builder<RelOptRule> b = ImmutableList.builder();
    foreachRule(out, r ->
        b.add(r.config.withRelBuilderFactory(relBuilderFactory).toRule()));
    return b.build();
  }

  private static void foreachRule(JdbcConvention out,
      Consumer<RelRule<?>> consumer) {
    consumer.accept(JdbcToEnumerableConverterRule.create(out));
    consumer.accept(JdbcJoinRule.create(out));
    consumer.accept(JdbcProjectRule.create(out));
    consumer.accept(JdbcFilterRule.create(out));
    consumer.accept(JdbcAggregateRule.create(out));
    consumer.accept(JdbcSortRule.create(out));
    consumer.accept(JdbcUnionRule.create(out));
    consumer.accept(JdbcIntersectRule.create(out));
    consumer.accept(JdbcMinusRule.create(out));
    consumer.accept(JdbcTableModificationRule.create(out));
    consumer.accept(JdbcValuesRule.create(out));
  }

  /** Abstract base class for rule that converts to JDBC. */
  abstract static class JdbcConverterRule extends ConverterRule {
    protected JdbcConverterRule(Config config) {
      super(config);
    }
  }

  /** Rule that converts a join to JDBC. */
  public static class JdbcJoinRule extends JdbcConverterRule {
    /** Creates a JdbcJoinRule. */
    public static JdbcJoinRule create(JdbcConvention out) {
      return Config.INSTANCE
          .withConversion(Join.class, Convention.NONE, out, "JdbcJoinRule")
          .withRuleFactory(JdbcJoinRule::new)
          .toRule(JdbcJoinRule.class);
    }

    /** Called from the Config. */
    protected JdbcJoinRule(Config config) {
      super(config);
    }

    @Override public @Nullable RelNode convert(RelNode rel) {
      final Join join = (Join) rel;
      switch (join.getJoinType()) {
      case SEMI:
      case ANTI:  //不支持半连接和反连接
        // It's not possible to convert semi-joins or anti-joins. They have fewer columns
        // than regular joins.
        return null;
      default:
        return convert(join, true);
      }
    }

    /**
     * Converts a {@code Join} into a {@code JdbcJoin}.
     *
     * @param join Join operator to convert
     * @param convertInputTraits Whether to convert input to {@code join}'s
     *                            JDBC convention
     * @return A new JdbcJoin
     */
    public @Nullable RelNode convert(Join join, boolean convertInputTraits) {
      final List<RelNode> newInputs = new ArrayList<>();
      for (RelNode input : join.getInputs()) {
        if (convertInputTraits && input.getConvention() != getOutTrait()) {
          input =
              convert(input,
                  input.getTraitSet().replace(out));
        }
        newInputs.add(input);
      }
      if (convertInputTraits && !canJoinOnCondition(join.getCondition())) {
        return null;
      }
      try {
        return new JdbcJoin(
            join.getCluster(),
            join.getTraitSet().replace(out),
            newInputs.get(0),
            newInputs.get(1),
            join.getCondition(),
            join.getVariablesSet(),
            join.getJoinType());
      } catch (InvalidRelException e) {
        LOGGER.debug(e.toString());
        return null;
      }
    }

    /**
     * Returns whether a condition is supported by {@link JdbcJoin}.
     *
     * <p>Corresponds to the capabilities of
     * {@link SqlImplementor#convertConditionToSqlNode}.
     *
     * @param node Condition
     * @return Whether condition is supported
     */
    private static boolean canJoinOnCondition(RexNode node) {
      final List<RexNode> operands;
      switch (node.getKind()) {
      case DYNAMIC_PARAM:
      case INPUT_REF:
      case LITERAL:
        // literal on a join condition would be TRUE or FALSE
        return true;
      case AND:
      case OR:
      case IS_NULL:
      case IS_NOT_NULL:
      case IS_TRUE:
      case IS_NOT_TRUE:
      case IS_FALSE:
      case IS_NOT_FALSE:
      case EQUALS:
      case NOT_EQUALS:
      case GREATER_THAN:
      case GREATER_THAN_OR_EQUAL:
      case LESS_THAN:
      case LESS_THAN_OR_EQUAL:
      case IS_NOT_DISTINCT_FROM:
      case CAST:
        operands = ((RexCall) node).getOperands();
        for (RexNode operand : operands) {
          if (!canJoinOnCondition(operand)) {
            return false;
          }
        }
        return true;
      default:
        return false;
      }
    }

    @Override public boolean matches(RelOptRuleCall call) {
      Join join = call.rel(0);
      JoinRelType joinType = join.getJoinType();
      return ((JdbcConvention) getOutConvention()).dialect.supportsJoinType(joinType);
    }
  }

  /** Join operator implemented in JDBC convention. */
  public static class JdbcJoin extends Join implements JdbcRel {
    /** Creates a JdbcJoin. */
    public JdbcJoin(RelOptCluster cluster, RelTraitSet traitSet,
        RelNode left, RelNode right, RexNode condition,
        Set<CorrelationId> variablesSet, JoinRelType joinType)
        throws InvalidRelException {
      super(cluster, traitSet, ImmutableList.of(), left, right, condition, variablesSet, joinType);
    }

    @Deprecated // to be removed before 2.0
    protected JdbcJoin(
        RelOptCluster cluster,
        RelTraitSet traitSet,
        RelNode left,
        RelNode right,
        RexNode condition,
        JoinRelType joinType,
        Set<String> variablesStopped)
        throws InvalidRelException {
      this(cluster, traitSet, left, right, condition,
          CorrelationId.setOf(variablesStopped), joinType);
    }

    @Override public JdbcJoin copy(RelTraitSet traitSet, RexNode condition,
        RelNode left, RelNode right, JoinRelType joinType,
        boolean semiJoinDone) {
      try {
        return new JdbcJoin(getCluster(), traitSet, left, right,
            condition, variablesSet, joinType);
      } catch (InvalidRelException e) {
        // Semantic error not possible. Must be a bug. Convert to
        // internal error.
        throw new AssertionError(e);
      }
    }

    @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
        RelMetadataQuery mq) {
      // We always "build" the
      double rowCount = mq.getRowCount(this);

      return planner.getCostFactory().makeCost(rowCount, 0, 0);
    }

    @Override public double estimateRowCount(RelMetadataQuery mq) {
      final double leftRowCount = mq.getRowCount(left);
      final double rightRowCount = mq.getRowCount(right);
      return Math.max(leftRowCount, rightRowCount);
    }

    @Override public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      return implementor.implement(this);
    }
  }

  /** Calc operator implemented in JDBC convention.
   *
   * @see org.apache.calcite.rel.core.Calc
   * */
  @Deprecated // to be removed before 2.0
  public static class JdbcCalc extends SingleRel implements JdbcRel {
    private final RexProgram program;

    public JdbcCalc(RelOptCluster cluster,
        RelTraitSet traitSet,
        RelNode input,
        RexProgram program) {
      super(cluster, traitSet, input);
      assert getConvention() instanceof JdbcConvention;
      this.program = program;
      this.rowType = program.getOutputRowType();
    }

    @Deprecated // to be removed before 2.0
    public JdbcCalc(RelOptCluster cluster, RelTraitSet traitSet, RelNode input,
        RexProgram program, int flags) {
      this(cluster, traitSet, input, program);
      Util.discard(flags);
    }

    @Override public RelWriter explainTerms(RelWriter pw) {
      return program.explainCalc(super.explainTerms(pw));
    }

    @Override public double estimateRowCount(RelMetadataQuery mq) {
      return RelMdUtil.estimateFilteredRows(getInput(), program, mq);
    }

    @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
        RelMetadataQuery mq) {
      double dRows = mq.getRowCount(this);
      double dCpu = mq.getRowCount(getInput())
          * program.getExprCount();
      double dIo = 0;
      return planner.getCostFactory().makeCost(dRows, dCpu, dIo);
    }

    @Override public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
      return new JdbcCalc(getCluster(), traitSet, sole(inputs), program);
    }

    @Override public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      return implementor.implement(this);
    }
  }

  /**
   * Rule to convert a {@link org.apache.calcite.rel.core.Project} to
   * an {@link org.apache.calcite.adapter.jdbc.JdbcRules.JdbcProject}.
   */
  public static class JdbcProjectRule extends JdbcConverterRule {
    /** Creates a JdbcProjectRule. */
    public static JdbcProjectRule create(JdbcConvention out) {
      return Config.INSTANCE
          .withConversion(Project.class, project ->
                  (out.dialect.supportsWindowFunctions()
                      || !project.containsOver())
                      && !userDefinedFunctionInProject(project),
              Convention.NONE, out, "JdbcProjectRule")
          .withRuleFactory(JdbcProjectRule::new)
          .toRule(JdbcProjectRule.class);
    }

    /** Called from the Config. */
    protected JdbcProjectRule(Config config) {
      super(config);
    }

    private static boolean userDefinedFunctionInProject(Project project) {
      CheckingUserDefinedFunctionVisitor visitor = new CheckingUserDefinedFunctionVisitor();
      for (RexNode node : project.getProjects()) {
        node.accept(visitor);
        if (visitor.containsUserDefinedFunction()) {
          return true;
        }
      }
      return false;
    }

    @Override public boolean matches(RelOptRuleCall call) {
      Project project = call.rel(0);
      return project.getVariablesSet().isEmpty();
    }

    @Override public @Nullable RelNode convert(RelNode rel) {
      final Project project = (Project) rel;

      return new JdbcProject(
          rel.getCluster(),
          rel.getTraitSet().replace(out),
          convert(
              project.getInput(),
              project.getInput().getTraitSet().replace(out)),
          project.getProjects(),
          project.getRowType());
    }
  }

  /** Implementation of {@link org.apache.calcite.rel.core.Project} in
   * {@link JdbcConvention jdbc calling convention}. */
  // JdbcAdapter（JDBC 适配器）模块中的一个核心物理算子。它以静态内部类的形式存在，继承自抽象基类 Project 并实现了 JdbcRel 接口
  // 下推（Push-down）计算：代表一个准备在底层关系型数据库（如 MySQL, Oracle, PostgreSQL 等）中直接执行的 SELECT 投影与计算操作。
  // 异构 SQL 转译：它不直接在 Calcite 内存中通过 Java 代码算数据，而是作为一个“账本标记”。当整个算子树下推完成后，它会通过特定的实现机制，
  // 将自身以及底层的算子节点联合逆向翻译（Decompile）回一条标准的 SQL 字符串，发送给底层的目标数据库去执行。
  public static class JdbcProject
      extends Project
      implements JdbcRel {
    public JdbcProject(
        RelOptCluster cluster,
        RelTraitSet traitSet,
        RelNode input, // JDBC 数据流中的上游算子（例如一个 JdbcFilter 或 JdbcTableScan）。
        List<? extends RexNode> projects, // 需要在目标数据库中执行的 SQL 表达式列表（如原装列引用或底层数据库支持的函数）。
        RelDataType rowType) {
      super(cluster, traitSet, ImmutableList.of(), input, projects, rowType, ImmutableSet.of());
      // 包含一句硬核断言：assert getConvention() instanceof JdbcConvention;。这确保了传入的 traitSet 物理特质中必须包含 JDBC 协议流派，防止逻辑特征错配。
      assert getConvention() instanceof JdbcConvention;
    }

    @Deprecated // to be removed before 2.0
    public JdbcProject(RelOptCluster cluster, RelTraitSet traitSet,
        RelNode input, List<RexNode> projects, RelDataType rowType, int flags) {
      this(cluster, traitSet, input, projects, rowType);
      Util.discard(flags);
    }

    @Override public JdbcProject copy(RelTraitSet traitSet, RelNode input,
        List<RexNode> projects, RelDataType rowType) {
      return new JdbcProject(getCluster(), traitSet, input, projects, rowType);
    }
    // 精确调整算子自身在 CBO 代价模型中的物理分数。
    @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
        RelMetadataQuery mq) {
      RelOptCost cost = super.computeSelfCost(planner, mq);
      if (cost == null) {
        return null;
      }
      // 如果基础代价不为空，它会做一件非常关键的事：将该代价乘以一个特定的系数 JdbcConvention.COST_MULTIPLIER（通常这个值被设定得很小，例如 0.03）
      return cost.multiplyBy(JdbcConvention.COST_MULTIPLIER);
    }
    // 物理计划向标准 SQL 的逆向转译。
    // 当 Calcite 决定采用物理 JDBC 计划准备执行时，它会启动 JdbcImplementor（一个专门负责将关系代数树倒退回 SQL 字符串的重写器）自底向上遍历这棵物理树。
    // 当遍历到 JdbcProject 时，该方法被触发。implementor.implement(this) 内部会执行以下动作
    // 先通知底层的子节点（例如 JdbcTableScan）先生成它们的那部分 SQL（例如生成了 FROM my_table）。
    // 然后 JdbcProject 接过接力棒，将自身内部的 projects 表达式列表（RexNode）逐个翻译成对应数据库的方言字符串（例如将 RexCall(UPPER, $1) 翻译成目标数据库认识的 UPPER(user_name)）。
    // 最终它把这些翻译好的片段拼接并注册进 Result 对象的 SELECT 子句区域中。
    @Override public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      return implementor.implement(this);
    }
  }

  /**
   * Rule to convert a {@link org.apache.calcite.rel.core.Filter} to
   * an {@link org.apache.calcite.adapter.jdbc.JdbcRules.JdbcFilter}.
   */
  public static class JdbcFilterRule extends JdbcConverterRule {
    /** Creates a JdbcFilterRule. */
    public static JdbcFilterRule create(JdbcConvention out) {
      return Config.INSTANCE
          .withConversion(Filter.class, r -> !userDefinedFunctionInFilter(r),
              Convention.NONE, out, "JdbcFilterRule")
          .withRuleFactory(JdbcFilterRule::new)
          .toRule(JdbcFilterRule.class);
    }

    /** Called from the Config. */
    protected JdbcFilterRule(Config config) {
      super(config);
    }

    private static boolean userDefinedFunctionInFilter(Filter filter) {
      CheckingUserDefinedFunctionVisitor visitor = new CheckingUserDefinedFunctionVisitor();
      filter.getCondition().accept(visitor);
      return visitor.containsUserDefinedFunction();
    }

    @Override public @Nullable RelNode convert(RelNode rel) {
      final Filter filter = (Filter) rel;

      return new JdbcFilter(
          rel.getCluster(),
          rel.getTraitSet().replace(out),
          convert(filter.getInput(),
              filter.getInput().getTraitSet().replace(out)),
          filter.getCondition());
    }
  }

  /** Implementation of {@link org.apache.calcite.rel.core.Filter} in
   * {@link JdbcConvention jdbc calling convention}. */
  public static class JdbcFilter extends Filter implements JdbcRel {
    public JdbcFilter(
        RelOptCluster cluster,
        RelTraitSet traitSet,
        RelNode input,
        RexNode condition) {
      super(cluster, traitSet, input, condition);
      assert getConvention() instanceof JdbcConvention;
    }

    @Override public JdbcFilter copy(RelTraitSet traitSet, RelNode input,
        RexNode condition) {
      return new JdbcFilter(getCluster(), traitSet, input, condition);
    }

    @Override public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      return implementor.implement(this);
    }
  }

  /**
   * Rule to convert a {@link org.apache.calcite.rel.core.Aggregate}
   * to a {@link org.apache.calcite.adapter.jdbc.JdbcRules.JdbcAggregate}.
   */
  public static class JdbcAggregateRule extends JdbcConverterRule {
    /** Creates a JdbcAggregateRule. */
    public static JdbcAggregateRule create(JdbcConvention out) {
      return Config.INSTANCE
          .withConversion(Aggregate.class, Convention.NONE, out,
              "JdbcAggregateRule")
          .withRuleFactory(JdbcAggregateRule::new)
          .toRule(JdbcAggregateRule.class);
    }

    /** Called from the Config. */
    protected JdbcAggregateRule(Config config) {
      super(config);
    }

    @Override public @Nullable RelNode convert(RelNode rel) {
      final Aggregate agg = (Aggregate) rel;
      if (agg.getGroupSets().size() != 1) {
        // GROUPING SETS not supported; see
        // [CALCITE-734] Push GROUPING SETS to underlying SQL via JDBC adapter
        return null;
      }
      final RelTraitSet traitSet =
          agg.getTraitSet().replace(out);
      try {
        return new JdbcAggregate(rel.getCluster(), traitSet,
            convert(agg.getInput(), out), agg.getGroupSet(),
            agg.getGroupSets(), agg.getAggCallList());
      } catch (InvalidRelException e) {
        LOGGER.debug(e.toString());
        return null;
      }
    }
  }

  /** Returns whether this JDBC data source can implement a given aggregate
   * function. */
  private static boolean canImplement(AggregateCall aggregateCall,
      SqlDialect sqlDialect) {
    return sqlDialect.supportsAggregateFunction(
        aggregateCall.getAggregation().getKind())
        && aggregateCall.distinctKeys == null;
  }

  /** Aggregate operator implemented in JDBC convention. */
  public static class JdbcAggregate extends Aggregate implements JdbcRel {
    public JdbcAggregate(
        RelOptCluster cluster,
        RelTraitSet traitSet,
        RelNode input,
        ImmutableBitSet groupSet,
        @Nullable List<ImmutableBitSet> groupSets,
        List<AggregateCall> aggCalls)
        throws InvalidRelException {
      super(cluster, traitSet, ImmutableList.of(), input, groupSet, groupSets, aggCalls);
      assert getConvention() instanceof JdbcConvention;
      assert this.groupSets.size() == 1 : "Grouping sets not supported";
      final SqlDialect dialect = ((JdbcConvention) getConvention()).dialect;
      for (AggregateCall aggCall : aggCalls) {
        if (!canImplement(aggCall, dialect)) {
          throw new InvalidRelException("cannot implement aggregate function "
              + aggCall);
        }
        if (aggCall.hasFilter() && !dialect.supportsAggregateFunctionFilter()) {
          throw new InvalidRelException("dialect does not support aggregate "
              + "functions FILTER clauses");
        }
      }
    }

    @Deprecated // to be removed before 2.0
    public JdbcAggregate(RelOptCluster cluster, RelTraitSet traitSet,
        RelNode input, boolean indicator, ImmutableBitSet groupSet,
        List<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls)
        throws InvalidRelException {
      this(cluster, traitSet, input, groupSet, groupSets, aggCalls);
      checkIndicator(indicator);
    }

    @Override public JdbcAggregate copy(RelTraitSet traitSet, RelNode input,
        ImmutableBitSet groupSet,
        @Nullable List<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls) {
      try {
        return new JdbcAggregate(getCluster(), traitSet, input,
            groupSet, groupSets, aggCalls);
      } catch (InvalidRelException e) {
        // Semantic error not possible. Must be a bug. Convert to
        // internal error.
        throw new AssertionError(e);
      }
    }

    @Override public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      return implementor.implement(this);
    }
  }

  /**
   * Rule to convert a {@link org.apache.calcite.rel.core.Sort} to an
   * {@link org.apache.calcite.adapter.jdbc.JdbcRules.JdbcSort}.
   */
  public static class JdbcSortRule extends JdbcConverterRule {
    /** Creates a JdbcSortRule. */
    public static JdbcSortRule create(JdbcConvention out) {
      return Config.INSTANCE
          .withConversion(Sort.class, Convention.NONE, out, "JdbcSortRule")
          .withRuleFactory(JdbcSortRule::new)
          .toRule(JdbcSortRule.class);
    }

    /** Called from the Config. */
    protected JdbcSortRule(Config config) {
      super(config);
    }

    @Override public @Nullable RelNode convert(RelNode rel) {
      return convert((Sort) rel, true);
    }

    /**
     * Converts a {@code Sort} into a {@code JdbcSort}.
     *
     * @param sort Sort operator to convert
     * @param convertInputTraits Whether to convert input to {@code sort}'s
     *                            JDBC convention
     * @return A new JdbcSort
     */
    public RelNode convert(Sort sort, boolean convertInputTraits) {
      final RelTraitSet traitSet = sort.getTraitSet().replace(out);

      final RelNode input;
      if (convertInputTraits) {
        final RelTraitSet inputTraitSet = sort.getInput().getTraitSet().replace(out);
        input = convert(sort.getInput(), inputTraitSet);
      } else {
        input = sort.getInput();
      }

      return new JdbcSort(sort.getCluster(), traitSet,
          input, sort.getCollation(), sort.offset, sort.fetch);
    }
  }

  /** Sort operator implemented in JDBC convention. */
  public static class JdbcSort
      extends Sort
      implements JdbcRel {
    public JdbcSort(
        RelOptCluster cluster,
        RelTraitSet traitSet,
        RelNode input,
        RelCollation collation,
        @Nullable RexNode offset,
        @Nullable RexNode fetch) {
      super(cluster, traitSet, input, collation, offset, fetch);
      assert getConvention() instanceof JdbcConvention;
      assert getConvention() == input.getConvention();
    }

    @Override public JdbcSort copy(RelTraitSet traitSet, RelNode newInput,
        RelCollation newCollation, @Nullable RexNode offset, @Nullable RexNode fetch) {
      return new JdbcSort(getCluster(), traitSet, newInput, newCollation,
          offset, fetch);
    }

    @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
        RelMetadataQuery mq) {
      RelOptCost cost = super.computeSelfCost(planner, mq);
      if (cost == null) {
        return null;
      }
      return cost.multiplyBy(0.9);
    }

    @Override public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      return implementor.implement(this);
    }
  }

  /**
   * Rule to convert an {@link org.apache.calcite.rel.core.Union} to a
   * {@link org.apache.calcite.adapter.jdbc.JdbcRules.JdbcUnion}.
   */
  public static class JdbcUnionRule extends JdbcConverterRule {
    /** Creates a JdbcUnionRule. */
    public static JdbcUnionRule create(JdbcConvention out) {
      return Config.INSTANCE
          .withConversion(Union.class, Convention.NONE, out, "JdbcUnionRule")
          .withRuleFactory(JdbcUnionRule::new)
          .toRule(JdbcUnionRule.class);
    }

    /** Called from the Config. */
    protected JdbcUnionRule(Config config) {
      super(config);
    }

    @Override public @Nullable RelNode convert(RelNode rel) {
      final Union union = (Union) rel;
      final RelTraitSet traitSet =
          union.getTraitSet().replace(out);
      return new JdbcUnion(rel.getCluster(), traitSet,
          convertList(union.getInputs(), out), union.all);
    }
  }

  /** Union operator implemented in JDBC convention. */
  public static class JdbcUnion extends Union implements JdbcRel {
    public JdbcUnion(
        RelOptCluster cluster,
        RelTraitSet traitSet,
        List<RelNode> inputs,
        boolean all) {
      super(cluster, traitSet, inputs, all);
    }

    @Override public JdbcUnion copy(
        RelTraitSet traitSet, List<RelNode> inputs, boolean all) {
      return new JdbcUnion(getCluster(), traitSet, inputs, all);
    }

    @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
        RelMetadataQuery mq) {
      RelOptCost cost = super.computeSelfCost(planner, mq);
      if (cost == null) {
        return null;
      }
      return cost.multiplyBy(.1);
    }

    @Override public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      return implementor.implement(this);
    }
  }

  /**
   * Rule to convert a {@link org.apache.calcite.rel.core.Intersect}
   * to a {@link org.apache.calcite.adapter.jdbc.JdbcRules.JdbcIntersect}.
   */
  public static class JdbcIntersectRule extends JdbcConverterRule {
    /** Creates a JdbcIntersectRule. */
    public static JdbcIntersectRule create(JdbcConvention out) {
      return Config.INSTANCE
          .withConversion(Intersect.class, Convention.NONE, out,
              "JdbcIntersectRule")
          .withRuleFactory(JdbcIntersectRule::new)
          .toRule(JdbcIntersectRule.class);
    }

    /** Called from the Config. */
    protected JdbcIntersectRule(Config config) {
      super(config);
    }

    @Override public @Nullable RelNode convert(RelNode rel) {
      final Intersect intersect = (Intersect) rel;
      if (intersect.all) {
        return null; // INTERSECT ALL not implemented
      }
      final RelTraitSet traitSet =
          intersect.getTraitSet().replace(out);
      return new JdbcIntersect(rel.getCluster(), traitSet,
          convertList(intersect.getInputs(), out), false);
    }
  }

  /** Intersect operator implemented in JDBC convention. */
  public static class JdbcIntersect
      extends Intersect
      implements JdbcRel {
    public JdbcIntersect(
        RelOptCluster cluster,
        RelTraitSet traitSet,
        List<RelNode> inputs,
        boolean all) {
      super(cluster, traitSet, inputs, all);
      assert !all;
    }

    @Override public JdbcIntersect copy(
        RelTraitSet traitSet, List<RelNode> inputs, boolean all) {
      return new JdbcIntersect(getCluster(), traitSet, inputs, all);
    }

    @Override public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      return implementor.implement(this);
    }
  }

  /**
   * Rule to convert a {@link org.apache.calcite.rel.core.Minus} to a
   * {@link org.apache.calcite.adapter.jdbc.JdbcRules.JdbcMinus}.
   */
  public static class JdbcMinusRule extends JdbcConverterRule {
    /** Creates a JdbcMinusRule. */
    public static JdbcMinusRule create(JdbcConvention out) {
      return Config.INSTANCE
          .withConversion(Minus.class, Convention.NONE, out, "JdbcMinusRule")
          .withRuleFactory(JdbcMinusRule::new)
          .toRule(JdbcMinusRule.class);
    }

    /** Called from the Config. */
    protected JdbcMinusRule(Config config) {
      super(config);
    }

    @Override public @Nullable RelNode convert(RelNode rel) {
      final Minus minus = (Minus) rel;
      if (minus.all) {
        return null; // EXCEPT ALL not implemented
      }
      final RelTraitSet traitSet =
          rel.getTraitSet().replace(out);
      return new JdbcMinus(rel.getCluster(), traitSet,
          convertList(minus.getInputs(), out), false);
    }
  }

  /** Minus operator implemented in JDBC convention. */
  public static class JdbcMinus extends Minus implements JdbcRel {
    public JdbcMinus(RelOptCluster cluster, RelTraitSet traitSet,
        List<RelNode> inputs, boolean all) {
      super(cluster, traitSet, inputs, all);
      assert !all;
    }

    @Override public JdbcMinus copy(RelTraitSet traitSet, List<RelNode> inputs,
        boolean all) {
      return new JdbcMinus(getCluster(), traitSet, inputs, all);
    }

    @Override public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      return implementor.implement(this);
    }
  }

  /** Rule that converts a table-modification to JDBC. */
  public static class JdbcTableModificationRule extends JdbcConverterRule {
    /** Creates a JdbcToEnumerableConverterRule. */
    public static JdbcTableModificationRule create(JdbcConvention out) {
      return Config.INSTANCE
          .withConversion(TableModify.class, Convention.NONE, out,
              "JdbcTableModificationRule")
          .withRuleFactory(JdbcTableModificationRule::new)
          .toRule(JdbcTableModificationRule.class);
    }

    /** Called from the Config. */
    protected JdbcTableModificationRule(Config config) {
      super(config);
    }

    @Override public @Nullable RelNode convert(RelNode rel) {
      final TableModify modify =
          (TableModify) rel;
      final ModifiableTable modifiableTable =
          modify.getTable().unwrap(ModifiableTable.class);
      if (modifiableTable == null) {
        return null;
      }
      final RelTraitSet traitSet =
          modify.getTraitSet().replace(out);
      return new JdbcTableModify(
          modify.getCluster(), traitSet,
          modify.getTable(),
          modify.getCatalogReader(),
          convert(modify.getInput(), traitSet),
          modify.getOperation(),
          modify.getUpdateColumnList(),
          modify.getSourceExpressionList(),
          modify.isFlattened());
    }
  }

  /** Table-modification operator implemented in JDBC convention. */
  public static class JdbcTableModify extends TableModify implements JdbcRel {
    public JdbcTableModify(RelOptCluster cluster,
        RelTraitSet traitSet,
        RelOptTable table,
        Prepare.CatalogReader catalogReader,
        RelNode input,
        Operation operation,
        @Nullable List<String> updateColumnList,
        @Nullable List<RexNode> sourceExpressionList,
        boolean flattened) {
      super(cluster, traitSet, table, catalogReader, input, operation,
          updateColumnList, sourceExpressionList, flattened);
      assert input.getConvention() instanceof JdbcConvention;
      assert getConvention() instanceof JdbcConvention;
      final ModifiableTable modifiableTable =
          table.unwrap(ModifiableTable.class);
      if (modifiableTable == null) {
        throw new AssertionError(); // TODO: user error in validator
      }
      Expression expression = table.getExpression(Queryable.class);
      if (expression == null) {
        throw new AssertionError(); // TODO: user error in validator
      }
    }

    @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
        RelMetadataQuery mq) {
      RelOptCost cost = super.computeSelfCost(planner, mq);
      if (cost == null) {
        return null;
      }
      return cost.multiplyBy(.1);
    }

    @Override public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
      return new JdbcTableModify(
          getCluster(), traitSet, getTable(), getCatalogReader(),
          sole(inputs), getOperation(), getUpdateColumnList(),
          getSourceExpressionList(), isFlattened());
    }

    @Override public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      return implementor.implement(this);
    }
  }

  /** Rule that converts a values operator to JDBC. */
  public static class JdbcValuesRule extends JdbcConverterRule {
    /** Creates a JdbcValuesRule. */
    public static JdbcValuesRule create(JdbcConvention out) {
      return Config.INSTANCE
          .withConversion(Values.class, Convention.NONE, out, "JdbcValuesRule")
          .withRuleFactory(JdbcValuesRule::new)
          .toRule(JdbcValuesRule.class);
    }

    /** Called from the Config. */
    protected JdbcValuesRule(Config config) {
      super(config);
    }

    @Override public @Nullable RelNode convert(RelNode rel) {
      Values values = (Values) rel;
      return new JdbcValues(values.getCluster(), values.getRowType(),
          values.getTuples(), values.getTraitSet().replace(out));
    }
  }

  /** Values operator implemented in JDBC convention. */
  public static class JdbcValues extends Values implements JdbcRel {
    JdbcValues(RelOptCluster cluster, RelDataType rowType,
        ImmutableList<ImmutableList<RexLiteral>> tuples, RelTraitSet traitSet) {
      super(cluster, rowType, tuples, traitSet);
    }

    @Override public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
      assert inputs.isEmpty();
      return new JdbcValues(getCluster(), getRowType(), tuples, traitSet);
    }

    @Override public JdbcImplementor.Result implement(JdbcImplementor implementor) {
      return implementor.implement(this);
    }
  }

  /** Visitor that checks whether part of a projection is a user-defined
   * function (UDF). */
  private static class CheckingUserDefinedFunctionVisitor
      extends RexVisitorImpl<Void> {

    private boolean containsUsedDefinedFunction = false;

    CheckingUserDefinedFunctionVisitor() {
      super(true);
    }

    public boolean containsUserDefinedFunction() {
      return containsUsedDefinedFunction;
    }

    @Override public Void visitCall(RexCall call) {
      SqlOperator operator = call.getOperator();
      if (operator instanceof SqlFunction
          && ((SqlFunction) operator).getFunctionType().isUserDefined()) {
        containsUsedDefinedFunction |= true;
      }
      return super.visitCall(call);
    }

  }

}
