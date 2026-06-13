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

import org.apache.calcite.linq4j.function.Experimental;
import org.apache.calcite.plan.Context;
import org.apache.calcite.plan.Contexts;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptSamplingParameters;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalCorrelate;
import org.apache.calcite.rel.logical.LogicalExchange;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalIntersect;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalMatch;
import org.apache.calcite.rel.logical.LogicalMinus;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalRepeatUnion;
import org.apache.calcite.rel.logical.LogicalSnapshot;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.logical.LogicalSortExchange;
import org.apache.calcite.rel.logical.LogicalTableFunctionScan;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.logical.LogicalTableSpool;
import org.apache.calcite.rel.logical.LogicalUnion;
import org.apache.calcite.rel.logical.LogicalValues;
import org.apache.calcite.rel.metadata.RelColumnMapping;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexCallBinding;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.SqlTableFunction;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;
import org.apache.calcite.util.ImmutableBitSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

import static java.util.Objects.requireNonNull;

/**
 * Contains factory interface and default implementation for creating various
 * rel nodes.
 */
// RelFactories 的核心作用是解耦关系表达式（RelNode）的构建逻辑，为 Calcite 内部（如优化器 Rule、RelBuilder）提供统一的、可定制的抽象工厂模式实现。
// 统一接口规范：它将各种 RelNode（如 Project, Filter, Join 等）的创建行为抽象为内部的 Factory 接口。
// 支持多生态/多 Convention：通过这些接口，优化器可以在不改变核心 Rule 代码的前提下，通过替换底层 Factory 来生成不同物理引擎或 Calling Convention（调用约定）的节点（例如，默认生成逻辑节点 LogicalProject，但可以定制生成 Spark 或 Flink 的物理节点）。
// 配合 RelBuilder 管道化构建：该类组装的高级工厂（如 LOGICAL_BUILDER）能够允许开发者以流畅的 API（Fluent API）模式去构建整棵关系代数语法树。
public class RelFactories {
  // 默认投影算子工厂，内部实例为 ProjectFactoryImpl，用于创建标准的 LogicalProject 节点。
  public static final ProjectFactory DEFAULT_PROJECT_FACTORY =
      new ProjectFactoryImpl();
  // 默认过滤算子工厂，内部实例为 FilterFactoryImpl，用于创建标准的 LogicalFilter 节点。
  public static final FilterFactory DEFAULT_FILTER_FACTORY =
      new FilterFactoryImpl();
  // 默认连接算子工厂，内部实例为 JoinFactoryImpl，用于创建标准的 LogicalJoin 节点。
  public static final JoinFactory DEFAULT_JOIN_FACTORY = new JoinFactoryImpl();
  // 认相关关联算子工厂，内部实例为 CorrelateFactoryImpl，用于创建标准的 LogicalCorrelate 节点（通常处理 Correlated Subquery）。
  public static final CorrelateFactory DEFAULT_CORRELATE_FACTORY =
      new CorrelateFactoryImpl();
  // 默认排序与切片（Limit/Offset）算子工厂，内部实例为 SortFactoryImpl，用于创建标准的 LogicalSort 节点。
  public static final SortFactory DEFAULT_SORT_FACTORY =
      new SortFactoryImpl();
  // 默认数据分布式交换算子（Exchange）工厂，内部实例为 ExchangeFactoryImpl，用于分布式计算中的数据重分区。
  public static final ExchangeFactory DEFAULT_EXCHANGE_FACTORY =
      new ExchangeFactoryImpl();
  // 默认排序交换算子工厂，内部实例为 SortExchangeFactoryImpl，在做数据交换的同时保证分区间或分区内数据的有序性。
  public static final SortExchangeFactory DEFAULT_SORT_EXCHANGE_FACTORY =
      new SortExchangeFactoryImpl();
  // 默认聚合/分组算子工厂，内部实例为 AggregateFactoryImpl，用于创建标准的 LogicalAggregate 节点。
  public static final AggregateFactory DEFAULT_AGGREGATE_FACTORY =
      new AggregateFactoryImpl();
  // 默认抽样算子工厂，内部实例为 SampleFactoryImpl，用于创建在大数据场景下做数据仿样/抽样的 Sample 节点。
  public static final SampleFactory DEFAULT_SAMPLE_FACTORY =
      new SampleFactoryImpl();
  // 默认复杂事件处理算子工厂，内部实例为 MatchFactoryImpl，用于实现类似 SQL MATCH_RECOGNIZE 的模式匹配。
  public static final MatchFactory DEFAULT_MATCH_FACTORY =
      new MatchFactoryImpl();
  // 默认集合操作算子工厂，内部实例为 SetOpFactoryImpl，根据 SQL 类型可以创建 Union、Minus 或 Intersect。
  public static final SetOpFactory DEFAULT_SET_OP_FACTORY =
      new SetOpFactoryImpl();
  // 默认内联常量元组算子工厂，内部实例为 ValuesFactoryImpl，常用于 SELECT 1 或 INSERT INTO ... VALUES ... 场景。
  public static final ValuesFactory DEFAULT_VALUES_FACTORY =
      new ValuesFactoryImpl();
  // 默认表扫描算子工厂，内部实例为 TableScanFactoryImpl，是整棵关系代数树的叶子节点，负责读取元数据表。
  public static final TableScanFactory DEFAULT_TABLE_SCAN_FACTORY =
      new TableScanFactoryImpl();
  // 默认表函数扫描算子工厂，内部实例为 TableFunctionScanFactoryImpl，用于处理表函数（Table-Valued Functions）。
  public static final TableFunctionScanFactory
      DEFAULT_TABLE_FUNCTION_SCAN_FACTORY = new TableFunctionScanFactoryImpl();
  // 默认快照算子工厂，内部实例为 SnapshotFactoryImpl，主要用于时态表（Temporal Tables）查询，拦截指定快照版本的数据。
  public static final SnapshotFactory DEFAULT_SNAPSHOT_FACTORY =
      new SnapshotFactoryImpl();
  // 默认暂存/假脱机算子工厂（实验性），内部实例为 SpoolFactoryImpl，用于物化或暂存中间计算结果（常在复杂递归或重复读中应用）。
  public static final SpoolFactory DEFAULT_SPOOL_FACTORY =
      new SpoolFactoryImpl();
  // 默认迭代连结算子工厂（实验性），内部实例为 RepeatUnionFactoryImpl，用于支持递归查询（如常用于计算图拓扑、SQL 中的 WITH RECURSIVE）。
  public static final RepeatUnionFactory DEFAULT_REPEAT_UNION_FACTORY =
      new RepeatUnionFactoryImpl();

  public static final Struct DEFAULT_STRUCT =
      new Struct(DEFAULT_FILTER_FACTORY,
          DEFAULT_PROJECT_FACTORY,
          DEFAULT_AGGREGATE_FACTORY,
          DEFAULT_SORT_FACTORY,
          DEFAULT_EXCHANGE_FACTORY,
          DEFAULT_SORT_EXCHANGE_FACTORY,
          DEFAULT_SET_OP_FACTORY,
          DEFAULT_JOIN_FACTORY,
          DEFAULT_CORRELATE_FACTORY,
          DEFAULT_VALUES_FACTORY,
          DEFAULT_TABLE_SCAN_FACTORY,
          DEFAULT_TABLE_FUNCTION_SCAN_FACTORY,
          DEFAULT_SNAPSHOT_FACTORY,
          DEFAULT_SAMPLE_FACTORY,
          DEFAULT_MATCH_FACTORY,
          DEFAULT_SPOOL_FACTORY,
          DEFAULT_REPEAT_UNION_FACTORY);

  /** A {@link RelBuilderFactory} that creates a {@link RelBuilder} that will
   * create logical relational expressions for everything. */
  public static final RelBuilderFactory LOGICAL_BUILDER =
      RelBuilder.proto(Contexts.of(DEFAULT_STRUCT)); //返回RelBuilderFactory工厂

  private RelFactories() {
  }

  /**
   * Can create a
   * {@link org.apache.calcite.rel.logical.LogicalProject} of the
   * appropriate type for this rule's calling convention.
   */
  // ProjectFactory 是一个抽象工厂接口，其核心作用是定义如何创建关系代数中的“投影（Project）”算子（即 SQL 中的 SELECT 字段映射与计算）
  // 在 Apache Calcite 的优化器（如 VolcanoPlanner 或 HepPlanner）执行优化规则（RelOptRule）时，经常需要创建新的节点来替换旧的节点。如果直接通过 new LogicalProject(...) 硬编码硬点，代码就失去了灵活性。
  //通过抽象出 ProjectFactory，优化器可以根据当前的调用约定（Calling Convention）动态地替换底层的工厂：
  // 在逻辑优化阶段，它可以映射为 ProjectFactoryImpl，生成标准的 LogicalProject。
  // 在物理对接阶段（如对接 Spark、Flink 或 具体的数据库引擎），可以注入对应的物理工厂，生成 SparkProject 或 FlinkHiveProject，而不需要修改上层的优化规则代码。
  public interface ProjectFactory {
    /**
     * Creates a project.
     *
     * @param input The input
     * @param hints The hints
     * @param childExprs The projection expressions
     * @param fieldNames The projection field names
     * @return a project
     * @deprecated Use {@link #createProject(RelNode, List, List, List, Set)} instead
     */
    // RelNode input 当前投影算子的下游/输入数据源算子（即数据从哪里来）
    // List<RelHint> hints  SQL 提示（Hints）列表。 如果在 SQL 中写了 SELECT /*+ MAX_EXECUTION_TIME(1000) */ a FROM t，这些控制执行策略的元数据信息会以 RelHint 的形式保存在这个 List 中，并透传给最终生成的 Project 节点。
    // List<? extends RexNode> childExprs 投影表达式列表。这是 Project 算子的灵魂，定义了每一列应该如何计算。
    // @Nullable List<? extends @Nullable String> fieldNames 投影输出的字段名称/别名列表。该参数被设计为可空（@Nullable）。
    @Deprecated // to be removed before 2.0
    default RelNode createProject(RelNode input, List<RelHint> hints,
        List<? extends RexNode> childExprs, @Nullable List<? extends @Nullable String> fieldNames) {
      return createProject(input, hints, childExprs, fieldNames, ImmutableSet.of());
    }

    /**
     * Creates a project.
     *
     * @param input The input
     * @param hints The hints
     * @param childExprs The projection expressions
     * @param fieldNames The projection field names
     * @param variablesSet Correlating variables that are set when reading a row
     *                     from the input, and which may be referenced from the
     *                     projection expressions
     * @return a project
     */
    // Set<CorrelationId> variablesSet  当前投影算子引发或向下传递的相关变量（Correlating Variables）集合
    // 这通常出现在相关子查询（如 WHERE t1.a > (SELECT AVG(t2.x) FROM t2 WHERE t2.y = t1.b)）的去关联（De-correlation）或重写中。
    // 当读取输入行时，这些变量会被赋值，以便在这个投影表达式内部，或者传递给下游表达式去引用外层查询的字段。
    RelNode createProject(RelNode input, List<RelHint> hints,
        List<? extends RexNode> childExprs, @Nullable List<? extends @Nullable String> fieldNames,
        Set<CorrelationId> variablesSet);
  }

  /**
   * Implementation of {@link ProjectFactory} that returns a vanilla
   * {@link org.apache.calcite.rel.logical.LogicalProject}.
   */
  // 直接调用LogicalProject的create方法
  private static class ProjectFactoryImpl implements ProjectFactory {
    @Override public RelNode createProject(RelNode input, List<RelHint> hints,
        List<? extends RexNode> childExprs, @Nullable List<? extends @Nullable String> fieldNames,
        Set<CorrelationId> variablesSet) {
      return LogicalProject.create(input, hints, childExprs, fieldNames, variablesSet);
    }
  }

  /**
   * Can create a {@link Sort} of the appropriate type
   * for this rule's calling convention.
   */
  public interface SortFactory {
    /** Creates a sort. */
    RelNode createSort(RelNode input, RelCollation collation, @Nullable RexNode offset,
        @Nullable RexNode fetch);

    @Deprecated // to be removed before 2.0
    default RelNode createSort(RelTraitSet traitSet, RelNode input,
        RelCollation collation, @Nullable RexNode offset, @Nullable RexNode fetch) {
      return createSort(input, collation, offset, fetch);
    }
  }

  /**
   * Implementation of {@link RelFactories.SortFactory} that
   * returns a vanilla {@link Sort}.
   */
  private static class SortFactoryImpl implements SortFactory {
    @Override public RelNode createSort(RelNode input, RelCollation collation,
        @Nullable RexNode offset, @Nullable RexNode fetch) {
      return LogicalSort.create(input, collation, offset, fetch);
    }
  }

  /**
   * Can create a {@link org.apache.calcite.rel.core.Exchange}
   * of the appropriate type for a rule's calling convention.
   */
  public interface ExchangeFactory {
    /** Creates an Exchange. */
    RelNode createExchange(RelNode input, RelDistribution distribution);
  }

  /**
   * Implementation of
   * {@link RelFactories.ExchangeFactory}
   * that returns a {@link Exchange}.
   */
  private static class ExchangeFactoryImpl implements ExchangeFactory {
    @Override public RelNode createExchange(
        RelNode input, RelDistribution distribution) {
      return LogicalExchange.create(input, distribution);
    }
  }

  /**
   * Can create a {@link SortExchange}
   * of the appropriate type for a rule's calling convention.
   */
  public interface SortExchangeFactory {
    /**
     * Creates a {@link SortExchange}.
     */
    RelNode createSortExchange(
        RelNode input,
        RelDistribution distribution,
        RelCollation collation);
  }

  /**
   * Implementation of
   * {@link RelFactories.SortExchangeFactory}
   * that returns a {@link SortExchange}.
   */
  private static class SortExchangeFactoryImpl implements SortExchangeFactory {
    @Override public RelNode createSortExchange(
        RelNode input,
        RelDistribution distribution,
        RelCollation collation) {
      return LogicalSortExchange.create(input, distribution, collation);
    }
  }

  /**
   * Can create a {@link SetOp} for a particular kind of
   * set operation (UNION, EXCEPT, INTERSECT) and of the appropriate type
   * for this rule's calling convention.
   */
  public interface SetOpFactory {
    /** Creates a set operation. */
    RelNode createSetOp(SqlKind kind, List<RelNode> inputs, boolean all);
  }

  /**
   * Implementation of {@link RelFactories.SetOpFactory} that
   * returns a vanilla {@link SetOp} for the particular kind of set
   * operation (UNION, EXCEPT, INTERSECT).
   */
  private static class SetOpFactoryImpl implements SetOpFactory {
    @Override public RelNode createSetOp(SqlKind kind, List<RelNode> inputs,
        boolean all) {
      switch (kind) {
      case UNION:
        return LogicalUnion.create(inputs, all);
      case EXCEPT:
        return LogicalMinus.create(inputs, all);
      case INTERSECT:
        return LogicalIntersect.create(inputs, all);
      default:
        throw new AssertionError("not a set op: " + kind);
      }
    }
  }

  /**
   * Can create a {@link LogicalAggregate} of the appropriate type
   * for this rule's calling convention.
   */
  public interface AggregateFactory {
    /** Creates an aggregate. */
    RelNode createAggregate(RelNode input, List<RelHint> hints, ImmutableBitSet groupSet,
        ImmutableList<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls);
  }

  /**
   * Implementation of {@link RelFactories.AggregateFactory}
   * that returns a vanilla {@link LogicalAggregate}.
   */
  private static class AggregateFactoryImpl implements AggregateFactory {
    @Override public RelNode createAggregate(RelNode input, List<RelHint> hints,
        ImmutableBitSet groupSet, ImmutableList<ImmutableBitSet> groupSets,
        List<AggregateCall> aggCalls) {
      return LogicalAggregate.create(input, hints, groupSet, groupSets, aggCalls);
    }
  }

  /**
   * Can create a {@link Filter} of the appropriate type
   * for this rule's calling convention.
   */
  public interface FilterFactory {
    /** Creates a filter.
     *
     * <p>Some implementations of {@code Filter} do not support correlation
     * variables, and for these, this method will throw if {@code variablesSet}
     * is not empty.
     *
     * @param input Input relational expression
     * @param condition Filter condition; only rows for which this condition
     *   evaluates to TRUE will be emitted
     * @param variablesSet Correlating variables that are set when reading
     *   a row from the input, and which may be referenced from inside the
     *   condition
     */
    RelNode createFilter(RelNode input, RexNode condition,
        Set<CorrelationId> variablesSet);

    @Deprecated // to be removed before 2.0
    default RelNode createFilter(RelNode input, RexNode condition) {
      return createFilter(input, condition, ImmutableSet.of());
    }
  }

  /**
   * Implementation of {@link RelFactories.FilterFactory} that
   * returns a vanilla {@link LogicalFilter}.
   */
  private static class FilterFactoryImpl implements FilterFactory {
    @Override public RelNode createFilter(RelNode input, RexNode condition,
        Set<CorrelationId> variablesSet) {
      return LogicalFilter.create(input, condition,
          ImmutableSet.copyOf(variablesSet));
    }
  }

  /**
   * Can create a join of the appropriate type for a rule's calling convention.
   *
   * <p>The result is typically a {@link Join}.
   */
  public interface JoinFactory {
    /**
     * Creates a join.
     *
     * @param left             Left input
     * @param right            Right input
     * @param hints            Hints
     * @param condition        Join condition
     * @param variablesSet     Set of variables that are set by the
     *                         LHS and used by the RHS and are not available to
     *                         nodes above this LogicalJoin in the tree
     * @param joinType         Join type
     * @param semiJoinDone     Whether this join has been translated to a
     *                         semi-join
     */
    RelNode createJoin(RelNode left, RelNode right, List<RelHint> hints,
        RexNode condition, Set<CorrelationId> variablesSet, JoinRelType joinType,
        boolean semiJoinDone);
  }

  /**
   * Implementation of {@link JoinFactory} that returns a vanilla
   * {@link org.apache.calcite.rel.logical.LogicalJoin}.
   */
  private static class JoinFactoryImpl implements JoinFactory {
    @Override public RelNode createJoin(RelNode left, RelNode right, List<RelHint> hints,
        RexNode condition, Set<CorrelationId> variablesSet,
        JoinRelType joinType, boolean semiJoinDone) {
      return LogicalJoin.create(left, right, hints, condition, variablesSet, joinType,
          semiJoinDone, ImmutableList.of());
    }
  }

  /**
   * Can create a correlate of the appropriate type for a rule's calling
   * convention.
   *
   * <p>The result is typically a {@link Correlate}.
   */
  public interface CorrelateFactory {

    /**
     * Creates a correlate.
     *
     * @param left             Left input
     * @param right            Right input
     * @param hints            Hints
     * @param correlationId    Variable name for the row of left input
     * @param requiredColumns  Required columns
     * @param joinType         Join type
     */
    RelNode createCorrelate(RelNode left, RelNode right, List<RelHint> hints,
        CorrelationId correlationId, ImmutableBitSet requiredColumns,
        JoinRelType joinType);
  }

  /**
   * Implementation of {@link CorrelateFactory} that returns a vanilla
   * {@link org.apache.calcite.rel.logical.LogicalCorrelate}.
   */
  private static class CorrelateFactoryImpl implements CorrelateFactory {

    @Override public RelNode createCorrelate(RelNode left, RelNode right, List<RelHint> hints,
        CorrelationId correlationId, ImmutableBitSet requiredColumns, JoinRelType joinType) {
      return LogicalCorrelate.create(left, right, hints, correlationId,
          requiredColumns, joinType);
    }
  }

  /**
   * Can create a semi-join of the appropriate type for a rule's calling
   * convention.
   *
   * @deprecated Use {@link JoinFactory} instead.
   */
  @Deprecated // to be removed before 2.0
  public interface SemiJoinFactory {
    /**
     * Creates a semi-join.
     *
     * @param left             Left input
     * @param right            Right input
     * @param condition        Join condition
     */
    RelNode createSemiJoin(RelNode left, RelNode right, RexNode condition);
  }

  /**
   * Can create a {@link Values} of the appropriate type for a rule's calling
   * convention.
   */
  public interface ValuesFactory {
    /**
     * Creates a Values.
     */
    RelNode createValues(RelOptCluster cluster, RelDataType rowType,
        List<ImmutableList<RexLiteral>> tuples);
  }

  /**
   * Implementation of {@link ValuesFactory} that returns a
   * {@link LogicalValues}.
   */
  private static class ValuesFactoryImpl implements ValuesFactory {
    @Override public RelNode createValues(RelOptCluster cluster, RelDataType rowType,
        List<ImmutableList<RexLiteral>> tuples) {
      return LogicalValues.create(cluster, rowType,
          ImmutableList.copyOf(tuples));
    }
  }

  /**
   * Can create a {@link TableScan} of the appropriate type for a rule's calling
   * convention.
   */
  public interface TableScanFactory {
    /**
     * Creates a {@link TableScan}.
     */
    RelNode createScan(RelOptTable.ToRelContext toRelContext, RelOptTable table);
  }

  /**
   * Implementation of {@link TableScanFactory} that returns a
   * {@link LogicalTableScan}.
   */
  private static class TableScanFactoryImpl implements TableScanFactory {
    @Override public RelNode createScan(RelOptTable.ToRelContext toRelContext, RelOptTable table) {
      return table.toRel(toRelContext);
    }
  }

  /**
   * Can create a {@link TableFunctionScan}
   * of the appropriate type for a rule's calling convention.
   */
  public interface TableFunctionScanFactory {
    /** Creates a {@link TableFunctionScan}. */
    RelNode createTableFunctionScan(RelOptCluster cluster,
        List<RelNode> inputs, RexCall call, @Nullable Type elementType,
        @Nullable Set<RelColumnMapping> columnMappings);
  }

  /**
   * Implementation of
   * {@link TableFunctionScanFactory}
   * that returns a {@link TableFunctionScan}.
   */
  private static class TableFunctionScanFactoryImpl
      implements TableFunctionScanFactory {
    @Override public RelNode createTableFunctionScan(RelOptCluster cluster,
        List<RelNode> inputs, RexCall call, @Nullable Type elementType,
        @Nullable Set<RelColumnMapping> columnMappings) {
      final RelDataType rowType;
      // To deduce the return type:
      // 1. if the operator implements SqlTableFunction,
      // use the SqlTableFunction's return type inference;
      // 2. else use the call's type, e.g. the operator may has
      // its custom way for return type inference.
      if (call.getOperator() instanceof SqlTableFunction) {
        final SqlOperatorBinding callBinding =
            new RexCallBinding(cluster.getTypeFactory(), call.getOperator(),
                call.operands, ImmutableList.of());
        final SqlTableFunction operator = (SqlTableFunction) call.getOperator();
        final SqlReturnTypeInference rowTypeInference =
            operator.getRowTypeInference();
        rowType = rowTypeInference.inferReturnType(callBinding);
      } else {
        rowType = call.getType();
      }

      return LogicalTableFunctionScan.create(cluster, inputs, call,
          elementType, requireNonNull(rowType, "rowType"), columnMappings);
    }
  }

  /**
   * Can create a {@link Snapshot} of
   * the appropriate type for a rule's calling convention.
   */
  public interface SnapshotFactory {
    /**
     * Creates a {@link Snapshot}.
     */
    RelNode createSnapshot(RelNode input, RexNode period);
  }

  /**
   * Implementation of {@link RelFactories.SnapshotFactory} that
   * returns a vanilla {@link LogicalSnapshot}.
   */
  public static class SnapshotFactoryImpl implements SnapshotFactory {
    @Override public RelNode createSnapshot(RelNode input, RexNode period) {
      return LogicalSnapshot.create(input, period);
    }
  }

  /**
   * Can create a {@link Match} of
   * the appropriate type for a rule's calling convention.
   */
  public interface MatchFactory {
    /** Creates a {@link Match}. */
    RelNode createMatch(RelNode input, RexNode pattern,
        RelDataType rowType, boolean strictStart, boolean strictEnd,
        Map<String, RexNode> patternDefinitions, Map<String, RexNode> measures,
        RexNode after, Map<String, ? extends SortedSet<String>> subsets,
        boolean allRows, ImmutableBitSet partitionKeys, RelCollation orderKeys,
        @Nullable RexNode interval);
  }

  /**
   * Implementation of {@link MatchFactory}
   * that returns a {@link LogicalMatch}.
   */
  private static class MatchFactoryImpl implements MatchFactory {
    @Override public RelNode createMatch(RelNode input, RexNode pattern,
        RelDataType rowType, boolean strictStart, boolean strictEnd,
        Map<String, RexNode> patternDefinitions, Map<String, RexNode> measures,
        RexNode after, Map<String, ? extends SortedSet<String>> subsets,
        boolean allRows, ImmutableBitSet partitionKeys, RelCollation orderKeys,
        @Nullable RexNode interval) {
      return LogicalMatch.create(input, rowType, pattern, strictStart,
          strictEnd, patternDefinitions, measures, after, subsets, allRows,
          partitionKeys, orderKeys, interval);
    }
  }

  /**
   * Can create a {@link Sample} of
   * the appropriate type for a rule's calling convention.
   */
  public interface SampleFactory {
    /** Creates a {@link Sample}. */
    RelNode createSample(RelNode input, RelOptSamplingParameters parameter);
  }

  /**
   * Implementation of {@link SampleFactory}
   * that returns a {@link Sample}.
   */
  private static class SampleFactoryImpl implements SampleFactory {
    @Override public RelNode createSample(RelNode input,
        RelOptSamplingParameters parameter) {
      return new Sample(input.getCluster(), input, parameter);
    }
  }

  /**
   * Can create a {@link Spool} of
   * the appropriate type for a rule's calling convention.
   */
  @Experimental
  public interface SpoolFactory {
    /** Creates a {@link TableSpool}. */
    RelNode createTableSpool(RelNode input, Spool.Type readType,
        Spool.Type writeType, RelOptTable table);
  }

  /**
   * Implementation of {@link SpoolFactory}
   * that returns Logical Spools.
   */
  private static class SpoolFactoryImpl implements SpoolFactory {
    @Override public RelNode createTableSpool(RelNode input, Spool.Type readType,
        Spool.Type writeType, RelOptTable table) {
      return LogicalTableSpool.create(input, readType, writeType, table);
    }
  }

  /**
   * Can create a {@link RepeatUnion} of
   * the appropriate type for a rule's calling convention.
   */
  @Experimental
  public interface RepeatUnionFactory {
    /** Creates a {@link RepeatUnion}. */
    RelNode createRepeatUnion(RelNode seed, RelNode iterative, boolean all,
        int iterationLimit, RelOptTable table);
  }

  /**
   * Implementation of {@link RepeatUnion}
   * that returns a {@link LogicalRepeatUnion}.
   */
  private static class RepeatUnionFactoryImpl implements RepeatUnionFactory {
    @Override public RelNode createRepeatUnion(RelNode seed, RelNode iterative,
        boolean all, int iterationLimit, RelOptTable table) {
      return LogicalRepeatUnion.create(seed, iterative, all, iterationLimit, table);
    }
  }

  /** Immutable record that contains an instance of each factory. */
  public static class Struct {
    public final FilterFactory filterFactory;
    public final ProjectFactory projectFactory;
    public final AggregateFactory aggregateFactory;
    public final SortFactory sortFactory;
    public final ExchangeFactory exchangeFactory;
    public final SortExchangeFactory sortExchangeFactory;
    public final SetOpFactory setOpFactory;
    public final JoinFactory joinFactory;
    public final CorrelateFactory correlateFactory;
    public final ValuesFactory valuesFactory;
    public final TableScanFactory scanFactory;
    public final TableFunctionScanFactory tableFunctionScanFactory;
    public final SnapshotFactory snapshotFactory;
    public final MatchFactory matchFactory;
    public final SampleFactory sampleFactory;
    public final SpoolFactory spoolFactory;
    public final RepeatUnionFactory repeatUnionFactory;

    private Struct(FilterFactory filterFactory,
        ProjectFactory projectFactory,
        AggregateFactory aggregateFactory,
        SortFactory sortFactory,
        ExchangeFactory exchangeFactory,
        SortExchangeFactory sortExchangeFactory,
        SetOpFactory setOpFactory,
        JoinFactory joinFactory,
        CorrelateFactory correlateFactory,
        ValuesFactory valuesFactory,
        TableScanFactory scanFactory,
        TableFunctionScanFactory tableFunctionScanFactory,
        SnapshotFactory snapshotFactory,
        SampleFactory sampleFactory,
        MatchFactory matchFactory,
        SpoolFactory spoolFactory,
        RepeatUnionFactory repeatUnionFactory) {
      this.filterFactory = requireNonNull(filterFactory, "filterFactory");
      this.projectFactory = requireNonNull(projectFactory, "projectFactory");
      this.aggregateFactory = requireNonNull(aggregateFactory, "aggregateFactory");
      this.sortFactory = requireNonNull(sortFactory, "sortFactory");
      this.exchangeFactory = requireNonNull(exchangeFactory, "exchangeFactory");
      this.sortExchangeFactory = requireNonNull(sortExchangeFactory, "sortExchangeFactory");
      this.setOpFactory = requireNonNull(setOpFactory, "setOpFactory");
      this.joinFactory = requireNonNull(joinFactory, "joinFactory");
      this.correlateFactory = requireNonNull(correlateFactory, "correlateFactory");
      this.valuesFactory = requireNonNull(valuesFactory, "valuesFactory");
      this.scanFactory = requireNonNull(scanFactory, "scanFactory");
      this.tableFunctionScanFactory =
          requireNonNull(tableFunctionScanFactory, "tableFunctionScanFactory");
      this.snapshotFactory = requireNonNull(snapshotFactory, "snapshotFactory");
      this.sampleFactory = requireNonNull(sampleFactory, "sampleFactory");
      this.matchFactory = requireNonNull(matchFactory, "matchFactory");
      this.spoolFactory = requireNonNull(spoolFactory, "spoolFactory");
      this.repeatUnionFactory = requireNonNull(repeatUnionFactory, "repeatUnionFactory");
    }

    public static Struct fromContext(Context context) {
      Struct struct = context.unwrap(Struct.class);
      if (struct != null) {
        return struct;
      }
      return new Struct(
          context.maybeUnwrap(FilterFactory.class)
              .orElse(DEFAULT_FILTER_FACTORY),
          context.maybeUnwrap(ProjectFactory.class)
              .orElse(DEFAULT_PROJECT_FACTORY),
          context.maybeUnwrap(AggregateFactory.class)
              .orElse(DEFAULT_AGGREGATE_FACTORY),
          context.maybeUnwrap(SortFactory.class)
              .orElse(DEFAULT_SORT_FACTORY),
          context.maybeUnwrap(ExchangeFactory.class)
              .orElse(DEFAULT_EXCHANGE_FACTORY),
          context.maybeUnwrap(SortExchangeFactory.class)
              .orElse(DEFAULT_SORT_EXCHANGE_FACTORY),
          context.maybeUnwrap(SetOpFactory.class)
              .orElse(DEFAULT_SET_OP_FACTORY),
          context.maybeUnwrap(JoinFactory.class)
              .orElse(DEFAULT_JOIN_FACTORY),
          context.maybeUnwrap(CorrelateFactory.class)
              .orElse(DEFAULT_CORRELATE_FACTORY),
          context.maybeUnwrap(ValuesFactory.class)
              .orElse(DEFAULT_VALUES_FACTORY),
          context.maybeUnwrap(TableScanFactory.class)
              .orElse(DEFAULT_TABLE_SCAN_FACTORY),
          context.maybeUnwrap(TableFunctionScanFactory.class)
              .orElse(DEFAULT_TABLE_FUNCTION_SCAN_FACTORY),
          context.maybeUnwrap(SnapshotFactory.class)
              .orElse(DEFAULT_SNAPSHOT_FACTORY),
          context.maybeUnwrap(SampleFactory.class)
              .orElse(DEFAULT_SAMPLE_FACTORY),
          context.maybeUnwrap(MatchFactory.class)
              .orElse(DEFAULT_MATCH_FACTORY),
          context.maybeUnwrap(SpoolFactory.class)
              .orElse(DEFAULT_SPOOL_FACTORY),
          context.maybeUnwrap(RepeatUnionFactory.class)
              .orElse(DEFAULT_REPEAT_UNION_FACTORY));
    }
  }
}
