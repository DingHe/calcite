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
// 在 Calcite 中，Program（优化程序）代表了查询优化器执行流水线中的一个独立阶段。优化一个原始的逻辑代数树（AST）通常无法一蹴而就，必须像工业流水线一样分阶段实施：
//阶段一：子查询展开（Sub-query Expansion）。
//阶段二：子查询去关联（Decorrelation），打平嵌套循环。
//阶段三：死字段裁剪（Field Trimming），为算子树瘦身
//阶段四：基于代价（CBO）或规则（RBO）的物理算子转换与最优路径搜索。
// Programs 类的核心作用就是作为中央工厂和编排中心。
// 它不仅预置了一系列标准通用的优化程序组合（如标准流水线 standard()），还提供了极其丰富的静态工厂方法，允许开发者通过组合规则集（RuleSet）、启发式优化器（HepPlanner）、或者是将多个程序前后串联（SequenceProgram），像搭积木一样轻松定制出企业级的专属优化流水线。

public class Programs {
  // 内置的 Calc 转换规则列表。为了向后兼容保留，将在 2.0 版本移除，直接被 RelOptRules.CALC_RULES 接管。
  @Deprecated // to be removed before 2.0
  public static final ImmutableList<RelOptRule> CALC_RULES = RelOptRules.CALC_RULES;

  /** Program that converts filters and projects to {@link Calc}s. */
  // Calc 融合重构程序。这是一个预设的 Hep 程序，专门负责将关系代数树中的一系列连续的 Filter 和 Project 算子合并、
  // 坍塌为单一的高效 Calc（计算）算子，从而减少执行期树的深度和数据传递开销。
  public static final Program CALC_PROGRAM =
      calc(DefaultRelMetadataProvider.INSTANCE);

  /** Program that expands sub-queries. */
  // 子查询展开程序。
  // 专门在优化早期触发，负责捕捉 SQL 转换为 RelNode 后的子查询结构，并将其转换为更易于后续优化的相关变量或关联（Correlate）物理模型。
  public static final Program SUB_QUERY_PROGRAM =
      subQuery(DefaultRelMetadataProvider.INSTANCE);
  // Calcite 标准物理转换规则大合集。
  // 打包了 Calcite 官方最核心的一线优化规则。它主要包含两大阵营：
  // EnumerableRules 阵营：负责将逻辑算子（如 LogicalProject、LogicalJoin）彻底向物理层转化（转化为可以在 JVM 中直接运行的 EnumerableJoin、EnumerableFilter 等）。
  // CoreRules 逻辑重写阵营：包含了连接交换律（JOIN_COMMUTE）、过滤器下推（FILTER_INTO_JOIN、FILTER_PROJECT_TRANSPOSE）、聚合函数拆解化简等经典 RBO 优化规则。
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
  // 基于规则集构建程序
  // 将一组包装好的规则集合（RuleSet）直接实例化为一个单阶段的优化程序 RuleSetProgram。
  public static Program of(RuleSet ruleSet) {
    return new RuleSetProgram(ruleSet);
  }

  /** Creates a list of programs based on an array of rule sets. */
  // 批量规则集包装
  // 将传入的一个或多个 RuleSet 批量、依次转换为对应的 Program 列表，常用于复杂多阶段优化器的批量初始化。
  public static List<Program> listOf(RuleSet... ruleSets) {
    return Util.transform(Arrays.asList(ruleSets), Programs::of);
  }

  /** Creates a list of programs based on a list of rule sets. */
  public static List<Program> listOf(List<RuleSet> ruleSets) {
    return Util.transform(ruleSets, Programs::of);
  }

  /** Creates a program from a list of rules. */
  // 直接基于规则列表构建程序
  public static Program ofRules(RelOptRule... rules) {
    return of(RuleSets.ofList(rules));
  }

  /** Creates a program from a list of rules. */
  public static Program ofRules(Iterable<? extends RelOptRule> rules) {
    return of(RuleSets.ofList(rules));
  }

  /** Creates a program that executes a sequence of programs. */
  // 多阶段程序串联
  // 流水线编排核心。将多个独立的 Program 阶段按照传入的先后顺序首尾相接，融合成一个强大的、复合的 SequenceProgram 流水线。
  public static Program sequence(Program... programs) {
    return new SequenceProgram(ImmutableList.copyOf(programs));
  }

  /** Creates a program that executes a list of rules in a HEP planner. */
  // 根据传入的规则列表，自动为其装配好一个启发式图优化程序构建器（HepProgramBuilder），并将其转化为支持 Hep 运行模式的 Program。
  public static Program hep(Iterable<? extends RelOptRule> rules,
      boolean noDag, RelMetadataProvider metadataProvider) {
    final HepProgramBuilder builder = HepProgram.builder();
    for (RelOptRule rule : rules) {
      builder.addRuleInstance(rule);
    }
    return of(builder.build(), noDag, metadataProvider);
  }

  /** Creates a program that executes a {@link HepProgram}. */
  // Hep 运行时适配器。通过 Lambda 表达式直接实现并返回一个 Program 接口对象。
  // 当该程序运行时，它会在内部临时孵化出一个专门的 HepPlanner 实例，并将传入的物化视图（materializations）、Lattice 预计算和元数据提供者（MetadataProvider）注册进去。
  // 随后在 hepPlanner 内部对代数树执行完全遍历替换，产出重写后的 RelNode。
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
  // 启发式大连接顺序优化
  // 多表连接（Join Reorder）防爆炸保护程序。
  // 当系统进行多表 JOIN 优化时，如果表的数量过多，基于动态规划的 VolcanoPlanner 搜索空间会呈指数级爆炸（$O(2^N)$）。
  //本方法会统计当前的 Join 数量，一旦达到或超过阈值 minJoinCount（默认推荐 6 个 Join），
  // 它会强行切断 Volcano 搜索，启动一条特殊的两阶段 Hep 管道：先通过 JOIN_TO_MULTI_JOIN 规则把所有的 Join 合并坍塌为一个宏观的 MultiJoin 算子，然后利用贪心算法或 Bushy（茂密树）启发式规则算法，
  // 在极短时间内计算出一个效果不错的连接顺序，从而保护系统不出现 OOM。
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
  // 将全局的 CALC_RULES 规则集与传入的元数据结合，包装生成专门用于做 Calc 融合优化的 Hep 程序。
  public static Program calc(RelMetadataProvider metadataProvider) {
    return hep(RelOptRules.CALC_RULES, true, metadataProvider);
  }
  // 构建一个专属的 Hep 阶段程序，利用 XXX_SUB_QUERY_TO_CORRELATE 规则族，
  // 把 Filter、Project、Join 里的标量/谓词子查询升级包装为含有相关引用的 Correlate 节点。
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
  // 获取标准优化流水线
  // 定义 Calcite 的“教科书级”标准优化五阶段流水线。
  // 主要分为三部分：定义主 CBO 优化阶段（program1）、编排五阶段流水线（programs 列表）、以及根据参数微调并打包返回。
  public static Program standard(
      // 元数据提供者。用于在优化过程中为各种规则（Rules）和优化器（Planner）提供节点树的元数据支持（如：预估行数 rowCount、唯一键 uniqueKeys、计算代价 cost 等）。
      RelMetadataProvider metadataProvider,
      // 字段裁剪（Field Trimming）开关。若为 true，流水线中会包含裁剪冗余列的步骤；若为 false，则跳过该步骤（这通常是为了规避某些特定复杂场景下字段索引错位的 Bug）。
      boolean enableFieldTrimming) {
    // 第一部分：定义基于代价（CBO）的主优化程序
    // 使用 Lambda 表达式直接实现了一个 Program 接口。它并不立即执行，而是作为流水线的第四个阶段挂载。
    final Program program1 =
        (planner, rel, requiredOutputTraits, materializations, lattices) -> {
          // 加载物化视图。将当前环境中所有可用的物化视图（Materialization）注册到优化器中，供优化器尝试进行物化视图重写代替。
          for (RelOptMaterialization materialization : materializations) {
            planner.addMaterialization(materialization);
          }
          // 加载 Lattice 结构
          for (RelOptLattice lattice : lattices) {
            planner.addLattice(lattice);
          }

          planner.setRoot(rel);
          // 目标物理特征（Traits）转换。检查当前代数树的特征（如逻辑 Convention）是否与最终要求的特征（如 EnumerableConvention 物理特征）一致。
          // 如果不一致，则调用 changeTraits 在树的最外层套一个转换虚节点（AbstractConverter），强制优化器在后续搜索中向该目标转换。
          final RelNode rootRel2 =
              rel.getTraitSet().equals(requiredOutputTraits)
                  ? rel
                  : planner.changeTraits(rel, requiredOutputTraits);
          assert rootRel2 != null;

          planner.setRoot(rootRel2);
          // 选择代理优化器。
          // 允许当前的 planner 返回一个更适合当前任务的实际执行代理
          final RelOptPlanner planner2 = planner.chooseDelegate();
          final RelNode rootRel3 = planner2.findBestExp();
          assert rootRel3 != null : "could not implement exp";
          return rootRel3;
        };
    // 第二部分：编排完整的标准优化流水线
    // 按部就班地创建五阶段顺序优化流水线
    List<Program> programs =
        Lists.newArrayList(
            // 子查询捕捉阶段。
            // 利用 HepPlanner 将 SQL 文本初步转成的 Filter/Project 内的子查询抽离，升级为 Correlate（相关性循环）算子模型
            subQuery(metadataProvider),
        // 去关联化阶段。捕捉 Correlate 拓扑，利用数学等价重写将复杂的嵌套循环打平，转化为普通的 Left Join 或 Inner Join。
        new DecorrelateProgram(),
        // 死字段裁剪阶段。全局扫描最终 SELECT 需要的列，自顶向下裁剪掉那些底层读了但上层完全不用的冗余列，为代数树瘦身。
        new TrimFieldsProgram(),
        // 核心 CBO 转换阶段。将前面的逻辑算子树进行物理算子大转换（如 LogicalJoin 转换为 EnumerableHashJoin），选出最优物理计划。
        program1,

        // Second planner pass to do physical "tweaks". This the first time
        // that EnumerableCalcRel is introduced.
        // 物理层微调融合阶段。在物理计划敲定后，最后做一次 Hep 优化，把连续的物理 Filter 和 Project 融合成单一的 EnumerableCalc，减少运行时的方法调用和数据拷贝开销。
        calc(metadataProvider));
    // 条件过滤。
    // 如果外界调用时传入的 enableFieldTrimming 为 false，则利用 removeIf 把第三阶段的 TrimFieldsProgram 从流水线列表中安全地移除。
    programs.removeIf(program -> !enableFieldTrimming && program instanceof TrimFieldsProgram);

    return new SequenceProgram(ImmutableList.copyOf(programs));
  }

  /** Program backed by a {@link RuleSet}. */
  // 用于封装和执行一组特定优化规则（RuleSet）
  // 查询优化往往不是一步到位的，而是分为多个阶段（例如：逻辑等价改写、物化视图改写、物理算子转换等）。
  // 每一个阶段都可以被抽象为一个 Program。
  // 而 RuleSetProgram 的核心职责就是：作为一个中间媒介，负责将一组预先定义好的规则集合（RuleSet）、
  // 物化视图以及期望的输出特征（Traits）注册到指定的优化器（Planner）中，并驱动优化器运行以产出最佳的执行计划。
  static class RuleSetProgram implements Program {
    // 规则集合。它是一个不可变的规则容器，里面持有当前优化阶段需要应用的所有优化规则（RelOptRule）。
    // 例如，它可以包含一组用于将逻辑算子转换为 Spark 算子的物理转换规则，或者一组专用于谓词下推的逻辑重写规则。
    final RuleSet ruleSet;

    private RuleSetProgram(RuleSet ruleSet) {
      this.ruleSet = ruleSet;
    }

    @Override public RelNode run(RelOptPlanner planner,
        // 当前输入的、等待本阶段优化的关系代数树（AST）根节点。
        RelNode rel,
        // 本阶段结束时，期望输出的关系代数树必须满足的特征集（Traits），例如特定的物理 convention（如 EnumerableConvention）、特定的排序（Collation）或数据分布（Distribution）。
        RelTraitSet requiredOutputTraits,
        List<RelOptMaterialization> materializations,
        List<RelOptLattice> lattices) {
      // 清理优化器状态。
      // 在开始这一阶段的优化前，擦除该优化器之前注册过的所有规则和状态，确保当前阶段的优化是一个干净、隔离、不受历史阶段干扰的环境
      planner.clear();
      // 加载当前阶段的规则集
      for (RelOptRule rule : ruleSet) {
        planner.addRule(rule);
      }
      // 注入物化视图元数据。将环境中所有的物化视图注册进优化器。优化器在后续优化中会尝试利用这些物化视图来改写输入的 rel 树（以空间换时间）。
      for (RelOptMaterialization materialization : materializations) {
        planner.addMaterialization(materialization);
      }
      // 注入 Lattice（星型模型）元数据。将可用的多维聚合预计算结构注册进优化器，用于潜在的聚合改写加速。
      for (RelOptLattice lattice : lattices) {
        planner.addLattice(lattice);
      }
      // 检查当前输入的 rel 的特征（如逻辑 Convention）是否已经满足了要求的输出特征 requiredOutputTraits（如 Enumerable 物理 Convention）。如果不满足，调用 planner.changeTraits 在树的根节点外层包裹一个“特征转换虚节点（AbstractConverter）”，
      // 指示优化器在随后的物理寻找过程中引入对应的转换算子（如等价于将逻辑节点强制要求转化为物理物理节点）。
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
