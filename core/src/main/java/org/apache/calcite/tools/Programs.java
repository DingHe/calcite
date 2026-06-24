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
package org.apache.calcite.tools;

import org.apache.calcite.adapter.enumerable.EnumerableRules;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteSystemProperty;
import org.apache.calcite.plan.RelOptCostImpl;
import org.apache.calcite.plan.RelOptLattice;
import org.apache.calcite.plan.RelOptMaterialization;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRules;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.hep.HepMatchOrder;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgram;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Calc;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.metadata.ChainedRelMetadataProvider;
import org.apache.calcite.rel.metadata.DefaultRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.rel.rules.JoinPushThroughJoinRule;
import org.apache.calcite.sql2rel.RelDecorrelator;
import org.apache.calcite.sql2rel.RelFieldTrimmer;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import java.util.Arrays;
import java.util.List;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

import static java.util.Objects.requireNonNull;

/**
 * Utilities for creating {@link Program}s.
 */
public class Programs {
  @Deprecated // to be removed before 2.0
  public static final ImmutableList<RelOptRule> CALC_RULES = RelOptRules.CALC_RULES;

  /** Program that converts filters and projects to {@link Calc}s. */
  public static final Program CALC_PROGRAM =
      calc(DefaultRelMetadataProvider.INSTANCE);

  /** Program that expands sub-queries. */
  public static final Program SUB_QUERY_PROGRAM =
      subQuery(DefaultRelMetadataProvider.INSTANCE);

  public static final ImmutableSet<RelOptRule> RULE_SET =
      ImmutableSet.of(
          EnumerableRules.ENUMERABLE_TABLE_SCAN_RULE,
          EnumerableRules.ENUMERABLE_JOIN_RULE,
          EnumerableRules.ENUMERABLE_MERGE_JOIN_RULE,
          EnumerableRules.ENUMERABLE_CORRELATE_RULE,
          EnumerableRules.ENUMERABLE_PROJECT_RULE,
          EnumerableRules.ENUMERABLE_FILTER_RULE,
          EnumerableRules.ENUMERABLE_AGGREGATE_RULE,
          EnumerableRules.ENUMERABLE_SORT_RULE,
          EnumerableRules.ENUMERABLE_LIMIT_RULE,
          EnumerableRules.ENUMERABLE_UNION_RULE,
          EnumerableRules.ENUMERABLE_MERGE_UNION_RULE,
          EnumerableRules.ENUMERABLE_INTERSECT_RULE,
          EnumerableRules.ENUMERABLE_MINUS_RULE,
          EnumerableRules.ENUMERABLE_TABLE_MODIFICATION_RULE,
          EnumerableRules.ENUMERABLE_VALUES_RULE,
          EnumerableRules.ENUMERABLE_WINDOW_RULE,
          EnumerableRules.ENUMERABLE_MATCH_RULE,
          CoreRules.PROJECT_TO_SEMI_JOIN,
          CoreRules.JOIN_ON_UNIQUE_TO_SEMI_JOIN,
          CoreRules.JOIN_TO_SEMI_JOIN,
          CoreRules.MATCH,
          CalciteSystemProperty.COMMUTE.value()
              ? CoreRules.JOIN_ASSOCIATE
              : CoreRules.PROJECT_MERGE,
          CoreRules.AGGREGATE_STAR_TABLE,
          CoreRules.AGGREGATE_PROJECT_STAR_TABLE,
          CoreRules.FILTER_SCAN,
          CoreRules.FILTER_PROJECT_TRANSPOSE,
          CoreRules.FILTER_INTO_JOIN,
          CoreRules.AGGREGATE_EXPAND_DISTINCT_AGGREGATES,
          CoreRules.AGGREGATE_REDUCE_FUNCTIONS,
          CoreRules.FILTER_AGGREGATE_TRANSPOSE,
          CoreRules.JOIN_COMMUTE,
          JoinPushThroughJoinRule.RIGHT,
          JoinPushThroughJoinRule.LEFT,
          CoreRules.SORT_PROJECT_TRANSPOSE);

  // private constructor for utility class
  private Programs() {}

  /** Creates a program that executes a rule set. */
  public static Program of(RuleSet ruleSet) {
    return new RuleSetProgram(ruleSet);
  }

  /** Creates a list of programs based on an array of rule sets. */
  public static List<Program> listOf(RuleSet... ruleSets) {
    return Util.transform(Arrays.asList(ruleSets), Programs::of);
  }

  /** Creates a list of programs based on a list of rule sets. */
  public static List<Program> listOf(List<RuleSet> ruleSets) {
    return Util.transform(ruleSets, Programs::of);
  }

  /** Creates a program from a list of rules. */
  public static Program ofRules(RelOptRule... rules) {
    return of(RuleSets.ofList(rules));
  }

  /** Creates a program from a list of rules. */
  public static Program ofRules(Iterable<? extends RelOptRule> rules) {
    return of(RuleSets.ofList(rules));
  }

  /** Creates a program that executes a sequence of programs. */
  public static Program sequence(Program... programs) {
    return new SequenceProgram(ImmutableList.copyOf(programs));
  }

  /** Creates a program that executes a list of rules in a HEP planner. */
  public static Program hep(Iterable<? extends RelOptRule> rules,
      boolean noDag, RelMetadataProvider metadataProvider) {
    final HepProgramBuilder builder = HepProgram.builder();
    for (RelOptRule rule : rules) {
      builder.addRuleInstance(rule);
    }
    return of(builder.build(), noDag, metadataProvider);
  }

  /** Creates a program that executes a {@link HepProgram}. */
  @SuppressWarnings("deprecation")
  public static Program of(final HepProgram hepProgram, final boolean noDag,
      final RelMetadataProvider metadataProvider) {
    requireNonNull(metadataProvider, "metadataProvider");
    return (planner, rel, requiredOutputTraits, materializations, lattices) -> {
      final HepPlanner hepPlanner =
          new HepPlanner(hepProgram, null, noDag, null, RelOptCostImpl.FACTORY);

      List<RelMetadataProvider> list = Lists.newArrayList(metadataProvider);
      hepPlanner.registerMetadataProviders(list);
      for (RelOptMaterialization materialization : materializations) {
        hepPlanner.addMaterialization(materialization);
      }
      for (RelOptLattice lattice : lattices) {
        hepPlanner.addLattice(lattice);
      }
      RelMetadataProvider plannerChain =
          ChainedRelMetadataProvider.of(list);
      rel.getCluster().setMetadataProvider(plannerChain);

      hepPlanner.setRoot(rel);
      return hepPlanner.findBestExp();
    };
  }

  /** Creates a program that invokes heuristic join-order optimization
   * (via {@link org.apache.calcite.rel.rules.JoinToMultiJoinRule},
   * {@link org.apache.calcite.rel.rules.MultiJoin} and
   * {@link org.apache.calcite.rel.rules.LoptOptimizeJoinRule})
   * if there are 6 or more joins (7 or more relations). */
  public static Program heuristicJoinOrder(
      final Iterable<? extends RelOptRule> rules,
      final boolean bushy, final int minJoinCount) {
    return (planner, rel, requiredOutputTraits, materializations, lattices) -> {
      final int joinCount = RelOptUtil.countJoins(rel);
      final Program program;
      if (joinCount < minJoinCount) {
        program = ofRules(rules);
      } else {
        // Create a program that gathers together joins as a MultiJoin.
        final HepProgram hep = new HepProgramBuilder()
            .addRuleInstance(CoreRules.FILTER_INTO_JOIN)
            .addMatchOrder(HepMatchOrder.BOTTOM_UP)
            .addRuleInstance(CoreRules.JOIN_TO_MULTI_JOIN)
            .build();
        final Program program1 =
            of(hep, false, DefaultRelMetadataProvider.INSTANCE);

        // Create a program that contains a rule to expand a MultiJoin
        // into heuristically ordered joins.
        // We use the rule set passed in, but remove JoinCommuteRule and
        // JoinPushThroughJoinRule, because they cause exhaustive search.
        final List<RelOptRule> list = Lists.newArrayList(rules);
        list.removeAll(
            ImmutableList.of(
                CoreRules.JOIN_COMMUTE,
                CoreRules.JOIN_ASSOCIATE,
                JoinPushThroughJoinRule.LEFT,
                JoinPushThroughJoinRule.RIGHT));
        list.add(bushy
            ? CoreRules.MULTI_JOIN_OPTIMIZE_BUSHY
            : CoreRules.MULTI_JOIN_OPTIMIZE);
        final Program program2 = ofRules(list);

        program = sequence(program1, program2);
      }
      return program.run(
          planner, rel, requiredOutputTraits, materializations, lattices);
    };
  }

  public static Program calc(RelMetadataProvider metadataProvider) {
    return hep(RelOptRules.CALC_RULES, true, metadataProvider);
  }

  @Deprecated // to be removed before 2.0
  public static Program subquery(RelMetadataProvider metadataProvider) {
    return subQuery(metadataProvider);
  }

  public static Program subQuery(RelMetadataProvider metadataProvider) {
    final HepProgramBuilder builder = HepProgram.builder();
    builder.addRuleCollection(
        ImmutableList.of(CoreRules.FILTER_SUB_QUERY_TO_CORRELATE,
            CoreRules.PROJECT_SUB_QUERY_TO_CORRELATE,
            CoreRules.JOIN_SUB_QUERY_TO_CORRELATE));
    return of(builder.build(), true, metadataProvider);
  }

  @Deprecated
  public static Program getProgram() {
    return (planner, rel, requiredOutputTraits, materializations, lattices) ->
        castNonNull(null);
  }

  /** Returns the standard program used by Prepare. */
  public static Program standard() {
    return standard(DefaultRelMetadataProvider.INSTANCE, true);
  }

  /** Returns the standard program with user metadata provider. */
  public static Program standard(RelMetadataProvider metadataProvider) {
    return standard(metadataProvider, true);
  }

  /** Returns the standard program with user metadata provider and enableFieldTrimming config. */
  public static Program standard(RelMetadataProvider metadataProvider,
      boolean enableFieldTrimming) {
    final Program program1 =
        (planner, rel, requiredOutputTraits, materializations, lattices) -> {
          for (RelOptMaterialization materialization : materializations) {
            planner.addMaterialization(materialization);
          }
          for (RelOptLattice lattice : lattices) {
            planner.addLattice(lattice);
          }

          planner.setRoot(rel);
          final RelNode rootRel2 =
              rel.getTraitSet().equals(requiredOutputTraits)
                  ? rel
                  : planner.changeTraits(rel, requiredOutputTraits);
          assert rootRel2 != null;

          planner.setRoot(rootRel2);
          final RelOptPlanner planner2 = planner.chooseDelegate();
          final RelNode rootRel3 = planner2.findBestExp();
          assert rootRel3 != null : "could not implement exp";
          return rootRel3;
        };

    List<Program> programs =
        Lists.newArrayList(subQuery(metadataProvider),
        new DecorrelateProgram(),
        new TrimFieldsProgram(),
        program1,

        // Second planner pass to do physical "tweaks". This the first time
        // that EnumerableCalcRel is introduced.
        calc(metadataProvider));

    programs.removeIf(program -> !enableFieldTrimming && program instanceof TrimFieldsProgram);

    return new SequenceProgram(ImmutableList.copyOf(programs));
  }

  /** Program backed by a {@link RuleSet}. */
  static class RuleSetProgram implements Program {
    final RuleSet ruleSet;

    private RuleSetProgram(RuleSet ruleSet) {
      this.ruleSet = ruleSet;
    }

    @Override public RelNode run(RelOptPlanner planner, RelNode rel,
        RelTraitSet requiredOutputTraits,
        List<RelOptMaterialization> materializations,
        List<RelOptLattice> lattices) {
      planner.clear();
      for (RelOptRule rule : ruleSet) {
        planner.addRule(rule);
      }
      for (RelOptMaterialization materialization : materializations) {
        planner.addMaterialization(materialization);
      }
      for (RelOptLattice lattice : lattices) {
        planner.addLattice(lattice);
      }
      if (!rel.getTraitSet().equals(requiredOutputTraits)) {
        rel = planner.changeTraits(rel, requiredOutputTraits);
      }
      planner.setRoot(rel);
      return planner.findBestExp();

    }
  }

  /** Program that runs sub-programs, sending the output of the previous as
   * input to the next. */
  // 主要用于将多个独立的优化阶段（Sub-programs）串联起来，形成一条高效的流水线（Pipeline）。
  // SequenceProgram 的核心作用是阶段性优化流水线编排。
  // 在复杂的数据库查询优化器中，把一棵原始的 AST（抽象语法树）转换成最终的高性能物理执行计划，往往不可能通过单一的规则或者一步优化就一蹴而就。优化过程通常被切分成多个职责单一的微型阶段（Phase）。
  // 在实际的企业级大数据查询引擎中，一个由 SequenceProgram 串联起来的流水线通常长这样：
  // 阶段一（Program 1）：执行去关联（DecorrelationProgram），把复杂的子查询打平。
  // 阶段二（Program 2）：执行字段裁剪（FieldTrimmerProgram），把没用的列干掉，减轻网络 IO。
  // 阶段三（Program 3）：基于规则的优化（HepProgram），执行常规的谓词下推、常量折叠。
  // 阶段四（Program 4）：基于代价的并发火山模型优化（VolcanoProgram），选择最佳的物理算子（如 HashJoin 还是 MergeJoin）。
 // SequenceProgram 就像是流水线上的传输带。它并不直接参与具体的代数树重构或代价计算，它的唯一使命就是把前一个优化阶段输出的中间形态 RelNode 算子树，无缝地喂给下一个优化阶段作为输入，直到所有注册的 Program 全部跑完。
  private static class SequenceProgram implements Program {
    // 顺序存储所有需要依次执行的子优化程序（Sub-programs）列表。
    private final ImmutableList<Program> programs;

    SequenceProgram(ImmutableList<Program> programs) {
      this.programs = programs;
    }

    @Override public RelNode run(RelOptPlanner planner, RelNode rel,
        RelTraitSet requiredOutputTraits,
        List<RelOptMaterialization> materializations,
        List<RelOptLattice> lattices) {
      for (Program program : programs) {
        rel =
            program.run(planner, rel, requiredOutputTraits, materializations,
                lattices);
      }
      return rel;
    }
  }

  /** Program that de-correlates a query.
   *
   * <p>To work around
   * <a href="https://issues.apache.org/jira/browse/CALCITE-842">[CALCITE-842]
   * Decorrelator gets field offsets confused if fields have been trimmed</a>,
   * disable field-trimming in {@link SqlToRelConverter}, and run
   * {@link TrimFieldsProgram} after this program. */
  // DecorrelateProgram 是 Apache Calcite 中负责子查询去关联化（Decorrelation，也称解关联）的阶段性程序。
  // 它同样实现了 Program 接口，是构建企业级查询优化器流水线时必不可少的一环。
  // DecorrelateProgram 的核心使命是：将“相关子查询”转换为高效的、可并行的“等值连接（Join）”或“窗口函数”结构。
  // 为什么要“去关联化”？
  // 在未优化的代数树中，相关子查询（如前面的 WHERE emp.dept_id = dept.id）在逻辑上表现为 嵌套循环（Nested Loop/Correlate 算子）。
  // 这意味着外层表有一万条数据，内层的子查询可能就要硬生生重复执行一万次（即 $O(N \times M)$ 的笛卡尔积级延迟），在大数据分布式引擎下简直是性能灾难。
  // 去关联化程序会利用数学代数等价变换，把这种嵌套循环结构打平（Flatten）。
  // 官方推荐的正确姿势：在初始化 SqlToRelConverter 时先禁用其自带的早期字段裁剪。
  // 在优化流水线中，必须保证 DecorrelateProgram（去关联）先执行，等它成功把子查询打平、关联列确定后，
  // 再紧随其后运行 TrimFieldsProgram 进行瘦身。这完美解释了为什么我们在研究 RelFieldTrimmer 的同时，必须深刻理解 DecorrelateProgram。
  private static class DecorrelateProgram implements Program {
    @Override public RelNode run(RelOptPlanner planner, RelNode rel,
        RelTraitSet requiredOutputTraits,
        List<RelOptMaterialization> materializations,
        List<RelOptLattice> lattices) {
      final CalciteConnectionConfig config =
          planner.getContext().maybeUnwrap(CalciteConnectionConfig.class)
              .orElse(CalciteConnectionConfig.DEFAULT);
      // 判断是否开启了“强制去关联”优化。
      if (config.forceDecorrelate()) {
        final RelBuilder relBuilder =
            RelFactories.LOGICAL_BUILDER.create(rel.getCluster(), null);
        return RelDecorrelator.decorrelateQuery(rel, relBuilder);
      }
      return rel;
    }
  }

  /** Program that trims fields. */
  // 专门负责在优化阶段对关系代数树（RelNode）进行全局的“死字段/冗余列”裁剪（Field Trimming），以消除无用的 I/O 和计算开销。
  // 在复杂的 SQL 查询中，尤其是经过多表 JOIN 或者嵌套了多层子查询（如大宽表视图）后，顶层的 SELECT 可能最终只需要其中极少量的几个字段。
  // 核心价值：通过尽早裁剪掉不使用的字段，能极大地减轻后续优化阶段（如基于代价的 Volcano 优化器）的空间搜索负担，并在执行期大幅节省内存带宽和 CPU 消耗。
  private static class TrimFieldsProgram implements Program {
    @Override public RelNode run(RelOptPlanner planner, RelNode rel,
        RelTraitSet requiredOutputTraits,
        List<RelOptMaterialization> materializations,
        List<RelOptLattice> lattices) {
      final RelBuilder relBuilder =
          RelFactories.LOGICAL_BUILDER.create(rel.getCluster(), null);
      return new RelFieldTrimmer(null, relBuilder).trim(rel);
    }
  }
}
