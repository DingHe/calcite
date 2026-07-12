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
package org.apache.calcite.plan.hep;

import org.apache.calcite.linq4j.function.Function2;
import org.apache.calcite.linq4j.function.Functions;
import org.apache.calcite.plan.AbstractRelOptPlanner;
import org.apache.calcite.plan.CommonRelSubExprRule;
import org.apache.calcite.plan.Context;
import org.apache.calcite.plan.RelDigest;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptCostFactory;
import org.apache.calcite.plan.RelOptCostImpl;
import org.apache.calcite.plan.RelOptMaterialization;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.plan.RelTrait;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.Converter;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.convert.TraitMatchingRule;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;
import org.apache.calcite.util.graph.BreadthFirstIterator;
import org.apache.calcite.util.graph.CycleDetector;
import org.apache.calcite.util.graph.DefaultDirectedGraph;
import org.apache.calcite.util.graph.DefaultEdge;
import org.apache.calcite.util.graph.DepthFirstIterator;
import org.apache.calcite.util.graph.DirectedGraph;
import org.apache.calcite.util.graph.Graphs;
import org.apache.calcite.util.graph.TopologicalOrderIterator;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

import static java.util.Objects.requireNonNull;

/**
 * HepPlanner is a heuristic implementation of the {@link RelOptPlanner}
 * interface.
 */
// HepPlanner 是一个基于启发式规则（Heuristic-Based）、确定性方向的 SQL 优化器（RBO）。
// HepPlanner 的核心逻辑是“按单向剧本顺序，局部重写代数树”。
// 图模型管理（DAG）：它将关系算子（RelNode）存放在一个有向无环图（DAG）中，用 HepRelVertex 作为包装节点，支持公共子表达式（CSE）的识别与复用。
// 确定性状态机：它不进行全局代价对比和多空间保留。当一条优化规则（RelOptRule）匹配到图中的某个节点并产生了新的等价节点时，HepPlanner 会直接进行原位热替换，原有的老节点如果失去所有父节点的引用，就会沦为垃圾（Garbage）。
// 分层迭代与收敛：它完全听从 HepProgram（优化剧本）的指令。通过控制规则的匹配方向（如 Top-Down、Bottom-Up）、匹配上限（Match Limit）以及分组循环，让整个图重写过程一直执行，直到达到定点（Fixpoint，即没有任何规则能再改变图结构）或者触发上限后退出。

public class HepPlanner extends AbstractRelOptPlanner {
  //~ Instance fields --------------------------------------------------------
  // 优化器的“核心剧本”。存储了通过 HepProgramBuilder 组装好的静态控制流指令集。
  private final HepProgram mainProgram;
  // 当前优化图（DAG）的根节点指针。随着顶层算子被重写，该指针会被动态调整。
  private @Nullable HepRelVertex root;
  // 记录用户最终对根节点所期望的物理特征集（如特定的排序 Collation 或分布式分片 Distribution）。
  private @Nullable RelTraitSet requestedRootTraits;

  /**
   * {@link RelDataType} is represented with its field types as {@code List<RelDataType>}.
   * This enables to treat as equal projects that differ in expression names only.
   */
  // 算子摘要到图顶点的映射表（去重账本）。
  // 用于在允许 DAG（noDag=false）时，通过算子的唯一 RelDigest 识别出结构完全相同的等价算子，从而实现公共子表达式消除。
  private final Map<RelDigest, HepRelVertex> mapDigestToVertex =
      new HashMap<>();
  // 累计转换计数器。只要有规则成功重写了算子并生成了新节点，该值就自增。
  private int nTransformations;
  // 记录上一次执行垃圾回收（GC）时，图中的顶点总数量。
  private int graphSizeLastGC;
  // 记录上一次执行垃圾回收（GC）时的 nTransformations 计数值。用于计算两次 GC 之间的转换差值。
  private int nTransformationsLastGC;
  // 流控开关。
  // 若为 true，则退化为普通树结构，禁止多父节点的 DAG 共享；若为 false（默认），则开启图优化模式。
  private final boolean noDag;

  /**
   * Query graph, with edges directed from parent to child. This is a
   * single-rooted DAG, possibly with additional roots corresponding to
   * discarded plan fragments which remain to be garbage-collected.
   */
  // 底层的核心有向无环图（DAG）。顶点是 HepRelVertex，边是由父节点指向子节点的物理引用边。
  private final DirectedGraph<HepRelVertex, DefaultEdge> graph =
      DefaultDirectedGraph.create();
  // 节点拷贝触发的钩子函数。当算子调用 copy 产生新副本时，回调此函数进行外部状态的同步或追踪。
  private final Function2<RelNode, RelNode, Void> onCopyHook;

  // 存储当前优化器注册的物化视图（Materialization）列表，供物化重写规则检索。
  private final List<RelOptMaterialization> materializations =
      new ArrayList<>();

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a new HepPlanner that allows DAG.
   *
   * @param program program controlling rule application
   */
  public HepPlanner(HepProgram program) {
    this(program, null, false, null, RelOptCostImpl.FACTORY);
  }

  /**
   * Creates a new HepPlanner that allows DAG.
   *
   * @param program program controlling rule application
   * @param context to carry while planning
   */
  public HepPlanner(HepProgram program, @Nullable Context context) {
    this(program, context, false, null, RelOptCostImpl.FACTORY);
  }

  /**
   * Creates a new HepPlanner with the option to keep the graph a
   * tree (noDag = true) or allow DAG (noDag = false).
   *
   * @param noDag      If false, create shared nodes if expressions are
   *                   identical
   * @param program    Program controlling rule application
   * @param onCopyHook Function to call when a node is copied
   */
  // 全参数主构造函数。完成所有核心属性的初始化绑定与防御性校验。
  public HepPlanner(
      HepProgram program,
      @Nullable Context context,
      boolean noDag,
      @Nullable Function2<RelNode, RelNode, Void> onCopyHook,
      RelOptCostFactory costFactory) {
    super(costFactory, context);
    this.mainProgram = requireNonNull(program, "program");
    this.onCopyHook = Util.first(onCopyHook, Functions.ignore2());
    this.noDag = noDag;
  }

  //~ Methods ----------------------------------------------------------------
  // 注入原始的未优化关系代数树。它会递归地将该代数树的所有节点转换为 HepRelVertex 并编织进 graph，最后将根顶点赋给 root。
  @Override public void setRoot(RelNode rel) {
    root = addRelToGraph(rel);
    dumpGraph();
  }

  @Override public @Nullable RelNode getRoot() {
    return root;
  }
  // 重置清空当前优化器内部注册的全部规则以及物化视图缓存，使 Planner 回归初始态。
  @Override public void clear() {
    super.clear();
    for (RelOptRule rule : getRules()) {
      removeRule(rule);
    }
    this.materializations.clear();
  }
  // 请求改变某个节点的物理特征。
  // 在 Hep 模式下，非根节点的物理特征转换不作强制要求，仅在当前节点为 root 时，将物理特征记录至 requestedRootTraits。
  @Override public RelNode changeTraits(RelNode rel, RelTraitSet toTraits) {
    // Ignore traits, except for the root, where we remember
    // what the final conversion should be.
    if ((rel == root) || (rel == requireNonNull(root, "root").getCurrentRel())) {
      requestedRootTraits = toTraits;
    }
    return rel;
  }
  // 触发启发式优化流程的总入口方法。
  @Override public RelNode findBestExp() {
    requireNonNull(root, "root");
    // 顺序执行整个剧本。
    executeProgram(mainProgram);

    // Get rid of everything except what's in the final plan.
    // 扫除历史重写残留的无用顶点。
    collectGarbage();
    dumpRuleAttemptsInfo();
    // 彻底褪去所有 HepRelVertex 包装壳，返回纯净、优质的最终 RelNode 树。
    return buildFinalPlan(requireNonNull(root, "root"));
  }

  /** Top-level entry point for a program. Initializes state and then invokes
   * the program. */
  // 整个启发式优化阶段的首席驱动引擎。
  private void executeProgram(HepProgram program) {
    final HepInstruction.PrepareContext px =
        HepInstruction.PrepareContext.create(this);
    final HepState state = program.prepare(px);
    // 真正进入循环重写图结构的执行流。
    state.execute();
  }
  // 批量驱动指令序列。它是子程序或主程序循环调用的核心。
  // 依次执行组内的每一条子指令状态，并检查转换累加数 delta；如果发现产生的垃圾节点数可能超过了上一次图的大小，
  // 则在指令间隙顺便触发增量垃圾回收 collectGarbage()，从而对整个长链路优化的内存高水位线进行削峰。
  void executeProgram(HepProgram instruction, HepProgram.State state) {
    state.init();
    state.instructionStates.forEach(instructionState -> {
      instructionState.execute();
      int delta = nTransformations - nTransformationsLastGC;
      if (delta > graphSizeLastGC) {
        // The number of transformations performed since the last
        // garbage collection is greater than the number of vertices in
        // the graph at that time.  That means there should be a
        // reasonable amount of garbage to collect now.  We do it this
        // way to amortize garbage collection cost over multiple
        // instructions, while keeping the highwater memory usage
        // proportional to the graph size.
        collectGarbage();
      }
    });
  }
  // 执行修改最大匹配次数的指令。将剧本中设定的 limit 刷入运行时状态的 matchLimit 属性中。
  void executeMatchLimit(HepInstruction.MatchLimit instruction,
      HepInstruction.MatchLimit.State state) {
    LOGGER.trace("Setting match limit to {}", instruction.limit);
    state.programState.matchLimit = instruction.limit;
  }
  // 执行修改遍历顺序的指令。改变后续规则在图中的检索方向（如修改为 BOTTOM_UP）。
  void executeMatchOrder(HepInstruction.MatchOrder instruction,
      HepInstruction.MatchOrder.State state) {
    LOGGER.trace("Setting match order to {}", instruction.order);
    state.programState.matchOrder = instruction.order;
  }
  // 执行单条规则指令。
  // 首先通过 skippingGroup() 检查是否可短路跳过，否则调用 applyRules 单兵作战。
  void executeRuleInstance(HepInstruction.RuleInstance instruction,
      HepInstruction.RuleInstance.State state) {
    if (state.programState.skippingGroup()) {
      return;
    }
    applyRules(state.programState, ImmutableList.of(instruction.rule), true);
  }
  // 专门负责真正执行 RuleLookup 这种指令的方法
  // 这条指令的特点是——并不直接持有规则对象本身，而是只持有一个"规则描述字符串"，需要在执行时动态地去优化器中根据这个描述字符串查找对应的具体规则对象，然后再应用它。
  void executeRuleLookup(HepInstruction.RuleLookup instruction,
      HepInstruction.RuleLookup.State state) {
    // 检查是否处于"跳过分组"状态
    if (state.programState.skippingGroup()) {
      return;
    }
    RelOptRule rule = state.rule;
    if (rule == null) {
      state.rule = rule = getRuleByDescription(instruction.ruleDescription);
      LOGGER.trace("Looking up rule with description {}, found {}",
          instruction.ruleDescription, rule);
    }
    if (rule != null) {
      applyRules(state.programState, ImmutableList.of(rule), true);
    }
  }
  // 负责真正执行 RuleClass 这种指令的方法
  // 这条指令的语义是——按照一个给定的 Java 类型（ruleClass），从优化器已注册的所有规则中筛选出所有属于该类型（或其子类型）的规则，把这一整批规则统一拿来应用。
  void executeRuleClass(HepInstruction.RuleClass instruction,
      HepInstruction.RuleClass.State state) {
    if (state.programState.skippingGroup()) {
      return;
    }
    LOGGER.trace("Applying rule class {}", instruction.ruleClass);
    Set<RelOptRule> ruleSet = state.ruleSet;
    if (ruleSet == null) {
      state.ruleSet = ruleSet = new LinkedHashSet<>();
      Class<?> ruleClass = instruction.ruleClass;
      for (RelOptRule rule : mapDescToRule.values()) { //判断HepPlanner中的规则是否属于RuleClass里面定义的类，属于才加入进来
        if (ruleClass.isInstance(rule)) {
          ruleSet.add(rule);
        }
      }
    }
    applyRules(state.programState, ruleSet, true);
  }

  void executeRuleCollection(HepInstruction.RuleCollection instruction,
      HepInstruction.RuleCollection.State state) {
    if (state.programState.skippingGroup()) {
      return;
    }
    applyRules(state.programState, instruction.rules, true);
  }
  // 负责真正执行 ConverterRules 这种指令的方法
  // 这条指令的语义是——从优化器已注册的所有规则中，筛选出所有"转换器规则"（ConverterRule），并且根据构造时指定的 guaranteed 标志，
  // 进一步筛选出"保证型"或"非保证型"的转换器规则，把这批规则（以及根据需要额外补充的辅助规则）统一拿来应用。
  void executeConverterRules(HepInstruction.ConverterRules instruction,
      HepInstruction.ConverterRules.State state) {
    checkArgument(state.programState.group == null);
    Set<RelOptRule> ruleSet = state.ruleSet;
    if (ruleSet == null) {
      state.ruleSet = ruleSet = new LinkedHashSet<>();
      for (RelOptRule rule : mapDescToRule.values()) {
        if (!(rule instanceof ConverterRule)) {
          continue;
        }
        ConverterRule converter = (ConverterRule) rule;
        if (converter.isGuaranteed() != instruction.guaranteed) {
          continue;
        }

        // Add the rule itself to work top-down
        ruleSet.add(converter);
        if (!instruction.guaranteed) {
          // Add a TraitMatchingRule to work bottom-up
          ruleSet.add(
              TraitMatchingRule.config(converter, RelFactories.LOGICAL_BUILDER)
                  .toRule());
        }
      }
    }
    applyRules(state.programState, ruleSet, instruction.guaranteed);
  }

  void executeCommonRelSubExprRules(
      HepInstruction.CommonRelSubExprRules instruction,
      HepInstruction.CommonRelSubExprRules.State state) {
    checkArgument(state.programState.group == null);
    Set<RelOptRule> ruleSet = state.ruleSet;
    if (ruleSet == null) {
      state.ruleSet = ruleSet = new LinkedHashSet<>();
      for (RelOptRule rule : mapDescToRule.values()) {
        if (!(rule instanceof CommonRelSubExprRule)) {
          continue;
        }
        ruleSet.add(rule);
      }
    }
    applyRules(state.programState, ruleSet, true);
  }

  void executeSubProgram(HepInstruction.SubProgram instruction,
      HepInstruction.SubProgram.State state) {
    LOGGER.trace("Entering subprogram");
    for (;;) {
      int nTransformationsBefore = nTransformations;
      state.programState.execute();
      if (nTransformations == nTransformationsBefore) {
        // Nothing happened this time around.
        break;
      }
    }
    LOGGER.trace("Leaving subprogram");
  }
  // 负责真正执行 BeginGroup 指令，其核心作用是——把程序状态标记为"进入分组模式"，
  // 从此刻起，直到对应的 EndGroup 指令被执行之前，其间遇到的所有具体规则指令（如 RuleClass、RuleLookup 等）都不会独立执行，而是会被收集起来
  void executeBeginGroup(HepInstruction.BeginGroup instruction,
      HepInstruction.BeginGroup.State state) {
    // 确认在开始一个新分组之前，程序状态中的 group 字段必须是 null（即当前尚未处于任何一个分组内部）
    checkArgument(state.programState.group == null);
    // 把程序共享状态（state.programState）中的 group 字段，
    // 设置为本次 BeginGroup.State 所持有的、与之配对的 endGroup（即对应的 EndGroup.State 对象）。
    state.programState.group = state.endGroup;
    LOGGER.trace("Entering group");
  }
  // 负责真正执行 EndGroup 指令，其核心作用是——结束当前的分组模式，并把在这个分组期间被陆续收集起来的所有规则，作为一个统一的整体，一次性交给 applyRules 进行反复匹配应用。
  void executeEndGroup(HepInstruction.EndGroup instruction,
      HepInstruction.EndGroup.State state) {
    // 确认程序共享状态中当前记录的 group，确实就是本次 EndGroup.State 自身
    checkArgument(state.programState.group == state);
    // 把程序共享状态中的 group 字段重新置回 null，正式退出分组模式
    state.programState.group = null;
    // 把这个 EndGroup.State 自身的 collecting 标志设置为 false，表示"收集阶段已经结束"。
    state.collecting = false;
    // 把整个分组期间陆续收集到的所有规则（state.ruleSet，此时已经包含了从对应的 BeginGroup 到这个 EndGroup 之间所有具体规则指令贡献进来的规则），
    // 作为一个统一的整体规则集合，一次性地交给 applyRules 去反复匹配、应用，直到达到不动点或匹配上限。
    applyRules(state.programState, state.ruleSet, true);
    LOGGER.trace("Leaving group");
  }
  // 实现 DEPTH_FIRST（深度优先）或 ARBITRARY（任意） 遍历战术的级联轰炸核心方法。
  private int depthFirstApply(
      // 当前运行时的状态账本，主要用来读取 matchLimit（最大允许匹配击发次数），防止规则无休止匹配导致死循环。
      HepProgram.State programState,
      // 当前层级步进的图节点迭代器
      Iterator<HepRelVertex> iter,
      // 次要一口气轰炸图节点的规则大军（规则集合）。
      Collection<RelOptRule> rules,
      // 物理转换开关。透传给底层的 applyRule 方法，用来决定是否强迫触发特征转换器。
      boolean forceConversions,
      // 全局匹配计数器。用来累计在整个优化生命周期中，规则被成功击合并改变图结构的累积总次数。
      int nMatches) {
    while (iter.hasNext()) {
      HepRelVertex vertex = iter.next();
      // 针对当前拿到的这个图顶点 vertex，开启 for 循环，驱使本次带上的所有优化规则（rules）挨个上去试探匹配。
      for (RelOptRule rule : rules) {
        HepRelVertex newVertex =
            applyRule(rule, vertex, forceConversions);
        // 空白过滤：判定结构是否发生实质改变
        if (newVertex == null || newVertex == vertex) {
          continue;
        }
        ++nMatches;
        if (nMatches >= programState.matchLimit) {
          return nMatches;
        }
        // To the extent possible, pick up where we left
        // off; have to create a new iterator because old
        // one was invalidated by transformation.
        // 核心魔术：级联深挖与递归回溯
        // 实现深度优先（DFS）优化链路的精髓所在。
        Iterator<HepRelVertex> depthIter =
            getGraphIterator(programState, newVertex);
        nMatches =
            depthFirstApply(programState, depthIter, rules, forceConversions,
                nMatches);
        break;
      }
    }
    return nMatches;
  }
  // 针对当前关系表达式图（graph）中的所有节点，反复尝试应用给定的一组规则（rules），直到达到某种"不动点"（fixed point，即没有任何规则能再匹配成功）或者达到匹配次数上限为止。
  // Hep 优化模型的核心工作方式——不同于基于代价的 Volcano 优化器那样进行代价驱动的搜索，HepPlanner 采取的是一种**"反复遍历图 + 贪心式应用第一个匹配到的规则 + 视情况决定是否需要重启遍历"**的启发式策略。

  private void applyRules(HepProgram.State programState,
      // 本次要尝试应用的一组规则集合
      Collection<RelOptRule> rules,
      // 用于控制在尝试应用规则（applyRule）时，是否需要"强制转换"（比如强制进行调用约定/物理特征的转换尝试，即使规则本身没有严格要求）
      boolean forceConversions) {
    // 第一段：处理"正在分组收集"的特殊情况
    // 从程序状态中取出当前"分组结束状态"对象
    final HepInstruction.EndGroup.State group = programState.group;
    // 判断当前是否正处于一个分组的收集阶段
    if (group != null) {
      // 确认这个分组当前确实处在"收集"状态（collecting 为 true
      checkArgument(group.collecting);
      Set<RelOptRule> ruleSet = requireNonNull(group.ruleSet, "group.ruleSet");
      // 核心逻辑——把这次传入的 rules 全部添加进分组的收集集合中，而不是真正立即去应用这些规则。
      ruleSet.addAll(rules);
      return;
    }

    LOGGER.trace("Applying rule set {}", rules);
    // 背景解释：HepPlanner 在遍历整个关系表达式图、逐个节点尝试应用规则时，一旦某个规则成功地对某个节点进行了转换（产生了一个新的关系表达式，替换了原来的节点），那么图的结构本身就发生了变化。
    // 此时，对于要求严格遍历顺序的匹配策略（比如自顶向下，必须先处理完父节点再处理子节点），一旦图结构发生变化，之前基于旧图结构建立的遍历顺序就可能不再准确，
    // 必须从根节点（root）重新开始一次完整的遍历，才能保证遍历顺序的正确性。
    // 而对于 ARBITRARY（无所谓顺序）和 DEPTH_FIRST（深度优先，通常允许从发生变化的局部位置继续，而不强制要求全局顺序的严格性）这两种相对宽松的策略，
    // 则不需要每次转换后都从头开始，可以"就地"从刚刚发生变化的位置继续遍历，效率更高。
    final boolean fullRestartAfterTransformation =
        programState.matchOrder != HepMatchOrder.ARBITRARY
            && programState.matchOrder != HepMatchOrder.DEPTH_FIRST;
    // 记录到目前为止总共成功应用了多少次规则（跨越可能的多轮外层循环累计计数）
    int nMatches = 0;
    // 用于控制外层的 do-while 循环——当这个变量为 true 时表示"已经达到不动点"
    boolean fixedPoint;
    do {
      // 根据 programState 中设置的匹配顺序，构建出对应遍历策略的迭代器
      Iterator<HepRelVertex> iter =
          getGraphIterator(programState, requireNonNull(root, "root"));
      fixedPoint = true;
      while (iter.hasNext()) {
        // 从当前迭代器中取出下一个待处理的图顶点
        HepRelVertex vertex = iter.next();
        for (RelOptRule rule : rules) {
          // 对这一个顶点，依次尝试规则集合中的每一条规则（注意这是内层的规则遍历，而外层是节点遍历——即"对每个节点，依次试每条规则"）。
          HepRelVertex newVertex =
              applyRule(rule, vertex, forceConversions);
          if (newVertex == null || newVertex == vertex) {
            continue;
          }
          // 如果代码执行到这里，说明规则确实成功地对这个顶点产生了一次真正的转换
          ++nMatches;
          if (nMatches >= programState.matchLimit) {
            return;
          }
          // 重新从根节点（`root`）获取一个全新的迭代器
          if (fullRestartAfterTransformation) {
            iter = getGraphIterator(programState, requireNonNull(root, "root"));
          } else {
            // To the extent possible, pick up where we left
            // off; have to create a new iterator because old
            // one was invalidated by transformation.
            // 尽可能地从我们刚才停下的地方继续；必须创建一个新的迭代器，因为旧的迭代器已经因为这次转换而失效了
            iter = getGraphIterator(programState, newVertex);
            if (programState.matchOrder == HepMatchOrder.DEPTH_FIRST) {
              // 以深度优先的方式**在刚才转换产生的新子树范围内**，继续尝试应用规则
              nMatches =
                  depthFirstApply(programState, iter, rules, forceConversions, nMatches);
              if (nMatches >= programState.matchLimit) {
                return;
              }
            }
            // Remember to go around again since we're
            // skipping some stuff.
            fixedPoint = false;
          }
          break;
        }
      }
    } while (!fixedPoint);
  }
  // 根据当前剧本规定的遍历顺序（MatchOrder），为规则匹配引擎生成一套精准的图节点遍历迭代器（Iterator）
  private Iterator<HepRelVertex> getGraphIterator(
      // 当前正在运行的 HepProgram 的动态运行时状态账本
      HepProgram.State programState,
      // 图遍历的起点/扫描锚点。
      HepRelVertex start) {
    // Make sure there's no garbage, because topological sort
    // doesn't start from a specific root, and rules can't
    // deal with firing on garbage.

    // FIXME jvs 25-Sept-2006:  I had to move this earlier because
    // of FRG-215, which is still under investigation.  Once we
    // figure that one out, move down to location below for
    // better optimizer performance.
    // 强制垃圾回收
    // 在生成任何迭代器之前，直接调用 collectGarbage() 扫除图中已经因为被替换而断开引用的“僵尸节点”。
    collectGarbage();

    switch (requireNonNull(programState.matchOrder, "programState.matchOrder")) {
    //模式 A：任意（ARBITRARY）与深度优先（DEPTH_FIRST）
    case ARBITRARY:
    case DEPTH_FIRST:
      // 以传入的 start 节点作为源头，拉起一个深度优先（DFS）遍历器
      return DepthFirstIterator.of(graph, start).iterator();
    // 模式 B：自顶向下拓扑序（TOP_DOWN）
    case TOP_DOWN:
      assert start == root;
      // see above
/*
        collectGarbage();
*/    // 生成一个标准拓扑排序迭代器
      return TopologicalOrderIterator.of(graph).iterator();
    // 模式 C：自底向上拓扑序（BOTTOM_UP / 默认）
    case BOTTOM_UP:
    default:
      assert start == root;

      // see above
/*
        collectGarbage();
*/

      // TODO jvs 4-Apr-2006:  enhance TopologicalOrderIterator
      // to support reverse walk.
      final List<HepRelVertex> list = new ArrayList<>();
      for (HepRelVertex vertex : TopologicalOrderIterator.of(graph)) {
        list.add(vertex);
      }
      Collections.reverse(list);
      return list.iterator();
    }
  }

  // applyRule 方法是优化规则击发（Fire）的轴心调度室。
  // 核心作用是：针对指定的图顶点（HepRelVertex），尝试去匹配并触发单条优化规则（RelOptRule），如果匹配成功并产生了更好的等价新算子，则通过重织网络完成图的热替换。
  private @Nullable HepRelVertex applyRule(
      // 当前尝试匹配的目标优化规则（例如 FilterJoinRule 或某个物理转换的 ConverterRule）。
      RelOptRule rule,
      // 当前正在扫描的 DAG 图顶点。规则将以该顶点包裹的算子作为根节点，向下做模式匹配。
      HepRelVertex vertex,
      // 是否强制执行转换。
      // 主要在处理 ConverterRule 时作为控制流开关，用来决定是否无条件触发物理特征（Trait）的强制转换。
      boolean forceConversions) {
    // 检查传入的 vertex 顶点是否还存活在当前的 graph 拓扑中。
    // 因为在级联优化或循环轰炸中，前一个规则的击发可能会通过收缩图（contractVertices）让某些老节点沦为断开引用的“孤岛垃圾”。如果该节点已经被淘汰，直接短路返回 null，不再为其虚耗算力。
    if (!graph.vertexSet().contains(vertex)) {
      return null;
    }
    // 用于记录转换器规则要求的输出特征
    RelTrait parentTrait = null;
    // 用于存储当前顶点的父算子实体
    List<RelNode> parents = null;
    // 分支 A：特征转换规则（ConverterRule）
    // 防止转换规则陷入无限生成自我、自我套娃的黑洞（“to infinity and beyond”）
    if (rule instanceof ConverterRule) {
      // Guaranteed converter rules require special casing to make sure
      // they only fire where actually needed, otherwise they tend to
      // fire to infinity and beyond.
      ConverterRule converterRule = (ConverterRule) rule;
      if (converterRule.isGuaranteed() || !forceConversions) {
        // 审查这个转换是不是上层父节点或根节点真正想要的特征
        if (!doesConverterApply(converterRule, vertex)) {
          return null;
        }
        // 审查通过，则捕获其期望的输出物理特征 parentTrait。
        parentTrait = converterRule.getOutTrait();
      }
    // 分支 B：公共子表达式提取规则（CommonRelSubExprRule）
    } else if (rule instanceof CommonRelSubExprRule) {
      // Only fire CommonRelSubExprRules if the vertex is a common
      // subexpression.
      // 获取引用当前顶点的父顶点数量
      List<HepRelVertex> parentVertices = getVertexParents(vertex);
      // 如果 parentVertices.size() < 2，意味着当前节点在图中只有孤独的一个父节点，根本不构成“公共子表达式”（没有被共享），直接返回 null。
      if (parentVertices.size() < 2) {
        return null;
      }
      parents = new ArrayList<>();
      // 如果有多于两个父节点引用它，说明抓到了共享分支，则把所有父算子解壳剥离出来放入 parents 列表中，喂给规则。
      for (HepRelVertex pVertex : parentVertices) {
        parents.add(pVertex.getCurrentRel());
      }
    }

    final List<RelNode> bindings = new ArrayList<>();
    final Map<RelNode, List<RelNode>> nodeChildren = new HashMap<>();
    // 验证当前的算子树形状是否符合规则定义的“猎物骨架”（例如 Filter 紧挨着 Join）。
    // 传入规则要求的 Operand（操作数树）和当前顶点解壳后的真实算子。该方法会自顶向下做递归匹配。
    // 匹配成功：它会将沿途捕获并契合的一串算子绑定到 bindings 列表中，并将父子层次树绑定在 nodeChildren 字典中，完成运行时现场的静态快照。
    boolean match =
        matchOperands(
            rule.getOperand(),
            vertex.getCurrentRel(),
            bindings,
            nodeChildren);

    if (!match) {
      return null;
    }
    // 组装上下文与规则自定义条件审查
    // 实例化具体的规则触发上下文（HepRuleCall），并给规则一次“反悔”的机会。
    HepRuleCall call =
        new HepRuleCall(
            this,
            rule.getOperand(),
            bindings.toArray(new RelNode[0]),
            nodeChildren,
            parents);

    // Allow the rule to apply its own side-conditions.

    if (!rule.matches(call)) {
      return null;
    }
    // 正式击发规则！
    fireRule(call);
    // 规则执行完后，检查 call.getResults() 里有没有产出新代数算子。
    if (!call.getResults().isEmpty()) {
      return applyTransformationResults(
          vertex,
          call,
          parentTrait);
    }

    return null;
  }
  // 物理特征转换（如将逻辑算子转换成特定的物理算子，或转换数据分布、排序规则等）是由 ConverterRule 驱动的。
  // 而物理转换规则最怕的就是无限套娃（例如：不停地在节点上叠加转换器：A -> Converter -> Converter -> Converter）
  // doesConverterApply 方法就是为了解决这个问题而设计的特征按需按靶发射审查器。它的核心作用是：逆向检查当前顶点的父节点（或全局根节点），
  // 判断它们是否真正需要该转换规则所产出的物理特征（outTrait）。
  // 只有上层真的需要，当前转换规则才允许击发。
  private boolean doesConverterApply(
      // 当前正在试探击发的物理特征转换规则。
      ConverterRule converterRule,
      // 当前正在扫描的图顶点（代数子树的根）。规则尝试在此节点上拉起一个转换器。
      HepRelVertex vertex) {
    // 从当前的转换规则中，获取它一旦击发后能够产出的目标物理特征（outTrait）
    // 例如，某个规则可以把算子转换成 Enumerable 物理约定，那它的 outTrait 就是 EnumerableConvention.INSTANCE。
    RelTrait outTrait = converterRule.getOutTrait();
    // 逆向搜寻当前算子的所有父节点
    // 在底层有向无环图 graph 中顺着反向引用，一次性捞出所有直接指向当前 vertex 的父顶点列表（parents）。
    List<HepRelVertex> parents = Graphs.predecessorListOf(graph, vertex);
    for (HepRelVertex parent : parents) {
      RelNode parentRel = parent.getCurrentRel();
      // 核心防线 A：无情拒绝转换器连环套娃（Converter Chain）
      // 如果发现当前顶点的父算子本身居然就已经是一个 Converter（转换器）了，直接 continue 跳过它。这是因为 HepPlanner 明确不支持物理转换器的无脑串联/链式叠加
      if (parentRel instanceof Converter) {
        // We don't support converter chains.
        continue;
      }
      // 核心防线 B：精准的供需契合判定
      // 检查这个父算子自身的物理特征集（TraitSet）里，是否正好包含了当前规则准备产出的 outTrait。如果包含，说明“上层父节点正嗷嗷待哺，苦苦寻找具备这种物理特征的子算子”。此时供需双方完美契合，方法直接返回 true，恩准当前的转换规则在此击发。
      if (parentRel.getTraitSet().contains(outTrait)) {
        // This parent wants the traits produced by the converter.
        return true;
      }
    }
    // 边界网开一面：全局根节点的特征救赎
    return (vertex == root)
        && (requestedRootTraits != null)
        && requestedRootTraits.contains(outTrait);
  }

  /**
   * Retrieves the parent vertices of a vertex.  If a vertex appears multiple
   * times as an input into a parent, then that counts as multiple parents,
   * one per input reference.
   *
   * @param vertex the vertex
   * @return the list of parents for the vertex
   */
  private List<HepRelVertex> getVertexParents(HepRelVertex vertex) {
    final List<HepRelVertex> parents = new ArrayList<>();
    final List<HepRelVertex> parentVertices =
        Graphs.predecessorListOf(graph, vertex);

    for (HepRelVertex pVertex : parentVertices) {
      RelNode parent = pVertex.getCurrentRel();
      for (int i = 0; i < parent.getInputs().size(); i++) {
        HepRelVertex child = (HepRelVertex) parent.getInputs().get(i);
        if (child == vertex) {
          parents.add(pVertex);
        }
      }
    }
    return parents;
  }
  // 职责是模式匹配（Pattern Matching）：它像拿着一张“规则设计蓝图”去对照“真实算子树”的骨架，只有当真实算子的类型和层次结构完全契合规则的预期时，才能将算子成功捕获（Bind）
  private static boolean matchOperands(
      // 优化规则定义的匹配操作数（蓝图节点）。
      // 它定义了预期的算子类型（如 LogicalFilter.class）以及它的子节点匹配策略（childPolicy）。
      RelOptRuleOperand operand,
      // 当前正在接受检验的真实关系代数算子
      RelNode rel,
      // 结果收集口袋（顺序绑定列表）。
      // 如果整棵树匹配成功，所有通过检验的真实算子都会按照匹配顺序被塞进这个列表中，供后续规则 onMatch(call) 直接提取使用（如 call.rel(0)、call.rel(1)）
      List<RelNode> bindings,
      // 父子拓扑记录本。
      // 用于以键值对形式临时存放“当前算子”对应的“解壳后的真实子算子列表”，帮助优化器在外部还原完整的局部树结构。
      Map<RelNode, List<RelNode>> nodeChildren) {
    // 调用 operand 自身的校验方法，检查当前 rel 的类类型（Class）或特征是否符合操作数的要求。
    if (!operand.matches(rel)) {
      return false;
    }
    // 确保当前算子的所有子节点（inputs）都已经被封装成了图顶点 HepRelVertex。
    for (RelNode input : rel.getInputs()) {
      if (!(input instanceof HepRelVertex)) {
        // The graph could be partially optimized for materialized view. In that
        // case, the input would be a RelNode and shouldn't be matched again here.
        return false;
      }
    }
    // 既然当前节点过审，就立刻把它作为战利品塞入 bindings 结果集中
    bindings.add(rel);
    @SuppressWarnings("unchecked")
    List<HepRelVertex> childRels = (List) rel.getInputs();
    switch (operand.childPolicy) {
    // 策略 A：ANY 模式（万能匹配）
    // 如果当前规则操作数的策略是 ANY，意味着“只要当前算子类型对了就行，它下面的孩子是谁、长什么样、有多少个，我完全不在乎”。
    case ANY:
      return true;
    // 策略 B：UNORDERED 模式（无序乱序匹配）
    // 要求蓝图中的每一个子操作数（childOperand），在真实的孩子列表（childRels）中至少能碰撞匹配到一个，不需要对齐位置。
    case UNORDERED:
      // For each operand, at least one child must match. If
      // matchAnyChildren, usually there's just one operand.
      for (RelOptRuleOperand childOperand : operand.getChildOperands()) {
        boolean match = false;
        for (HepRelVertex childRel : childRels) {
          match =
              matchOperands(
                  childOperand,
                  childRel.getCurrentRel(),
                  bindings,
                  nodeChildren);
          if (match) {
            break;
          }
        }
        if (!match) {
          return false;
        }
      }
      final List<RelNode> children = new ArrayList<>(childRels.size());
      for (HepRelVertex childRel : childRels) {
        children.add(childRel.getCurrentRel());
      }
      nodeChildren.put(rel, children);
      return true;
    // 策略 C：DEFAULT / 有序对齐模式（默认严格匹配）
    // 最常用的严格匹配。 规则要求现实算子的子节点不仅数量要够，而且必须像拉链一样位置一一严格对应（例如：左孩子必须是 Filter，右孩子必须是 Project）。
    default:
      int n = operand.getChildOperands().size();
      if (childRels.size() < n) {
        return false;
      }
      for (Pair<HepRelVertex, RelOptRuleOperand> pair
          : Pair.zip(childRels, operand.getChildOperands())) {
        boolean match =
            matchOperands(
                pair.right,
                pair.left.getCurrentRel(),
                bindings,
                nodeChildren);
        if (!match) {
          return false;
        }
      }
      return true;
    }
  }
  // 当一条规则（RelOptRule）真正被触发并成功产生了一个或多个"转换结果"（RelNode）之后，
  // 从这些候选结果中挑选出最合适的一个，并把这个选中的结果真正"合并"（contract）到当前的关系表达式图（graph）中，替换掉原来的顶点，最终返回代表这个新结果的图顶点。
  // 连接"规则匹配/转换逻辑"与"关系表达式图结构维护"之间的关键桥梁——规则本身只负责产生若干候选的新关系表达式，而这个方法负责决定"用哪一个""如何正确地更新图结构"这两件事。
  private HepRelVertex applyTransformationResults(
      // 本次规则转换所针对的原始顶点——即规则匹配之前，图中原本对应这个位置的那个节点，转换完成后，这个顶点将被新的结果所替代（或"收缩合并"）。
      HepRelVertex vertex,
      // 代表这一次具体的规则调用上下文，包含了规则本身、匹配到的操作数、以及规则执行后产生的转换结果列表等信息
      HepRuleCall call,
      // 一个可选的"父节点特征"约束。当这次转换是由某种转换器规则触发的时候，
      // 这个参数用于指定——只有那些确实需要（要求）这个特定物理特征的父节点，才应该被"重新指向"这个新生成的转换结果
      @Nullable RelTrait parentTrait) {
    // TODO jvs 5-Apr-2006:  Take the one that gives the best
    // global cost rather than the best local cost.  That requires
    // "tentative" graph edits.
    // 目前的选择策略是"挑选局部代价最优的那一个"（Best Local Cost），而理想情况下应该考虑"哪个选择能带来全局最优代价"（Best Global Cost），
    // 但要实现后者需要支持"试探性的图编辑"（tentative graph edits，即可以先尝试性地做修改，如果发现不是最优的还能撤销/回滚），
    // 这在当前的 Hep 模型实现中还没有被支持——这也从侧面说明了 Hep 优化器（相比 Volcano）在"局部贪心 vs. 全局最优"这一权衡上的固有局限。
    // 规则确实至少产生了一个候选结果
    assert !call.getResults().isEmpty();

    RelNode bestRel = null;
    // 特殊情况优化——如果这次规则调用只产生了唯一一个候选结果，那么根本不需要进行任何代价比较（因为没有其他选项可比），直接把这个唯一结果作为 bestRel。
    if (call.getResults().size() == 1) {
      // No costing required; skip it to minimize the chance of hitting
      // rels without cost information.
      bestRel = call.getResults().get(0);
    } else {
    // 如果确实有多个候选结果，则需要真正地逐一计算代价并进行比较
      RelOptCost bestCost = null;
      final RelMetadataQuery mq = call.getMetadataQuery();
      for (RelNode rel : call.getResults()) {
        RelOptCost thisCost = getCost(rel, mq);
        if (LOGGER.isTraceEnabled()) {
          // Keep in the isTraceEnabled for the getRowCount method call
          LOGGER.trace("considering {} with cumulative cost={} and rowcount={}",
              rel, thisCost, mq.getRowCount(rel));
        }
        if (thisCost == null) {
          continue;
        }
        if (bestRel == null || thisCost.isLt(castNonNull(bestCost))) {
          bestRel = rel;
          bestCost = thisCost;
        }
      }
    }

    ++nTransformations;
    notifyTransformation(
        call,
        requireNonNull(bestRel, "bestRel"),
        true);

    // Before we add the result, make a copy of the list of vertex's
    // parents.  We'll need this later during contraction so that
    // we only update the existing parents, not the new parents
    // (otherwise loops can result).  Also take care of filtering
    // out parents by traits in case we're dealing with a converter rule.
    // 第三段：收集并过滤当前顶点的父节点
    final List<HepRelVertex> allParents =
        Graphs.predecessorListOf(graph, vertex);
    final List<HepRelVertex> parents = new ArrayList<>();
    for (HepRelVertex parent : allParents) {
      // 说明这确实是一次转换器规则相关的调用
      if (parentTrait != null) {
        RelNode parentRel = parent.getCurrentRel();
        if (parentRel instanceof Converter) {
          // 排除转换器类型的父节点
          // 如果这个父节点本身就是一个 `Converter`（转换器节点，即已经是某种物理特征转换的结果），则直接跳过，不把它纳入后续处理的 `parents` 列表中
          // We don't support automatically chaining conversions.
          // Treating a converter as a candidate parent here
          // can cause the "iParentMatch" check below to
          // throw away a new converter needed in
          // the multi-parent DAG case.
          continue;
        }
        // 排除不需要该特征的父节点
        if (!parentRel.getTraitSet().contains(parentTrait)) {
          // This parent does not want the converted result.
          continue;
        }
      }
      parents.add(parent);
    }
    // 第四段：把最佳结果加入图中，并处理"结果恰好与某个父节点相同"的特殊情况
    HepRelVertex newVertex = addRelToGraph(bestRel);

    // There's a chance that newVertex is the same as one
    // of the parents due to common subexpression recognition
    // (e.g. the LogicalProject added by JoinCommuteRule).  In that
    // case, treat the transformation as a nop to avoid
    // creating a loop.
    // 在结构上恰好与图中某个已经存在的节点完全等价，可能会直接复用那个已有的顶点，而不是无脑地创建一个全新的顶点。
    int iParentMatch = parents.indexOf(newVertex);
    if (iParentMatch != -1) {
      newVertex = parents.get(iParentMatch);
    } else {
      // 真正执行图结构的"收缩"（contraction）操作——即把原来的 vertex 替换为新的 newVertex，
      // 并相应地更新之前收集/过滤好的那些父节点（parents），让它们改为指向这个新的顶点，从而完成整个转换在图结构层面的真正落地。
      contractVertices(newVertex, vertex, parents);
    }

    if (getListener() != null) {
      // Assume listener doesn't want to see garbage.
      collectGarbage();
    }

    notifyTransformation(
        call,
        bestRel,
        false);
    // 可能是把当前图结构的详细信息输出到调试日志中（具体实现应该会检查日志级别，只有在需要时才真正执行输出），便于开发者追踪每一步转换后图结构的变化情况。
    dumpGraph();

    return newVertex;
  }
  // 在 Volcano 模型中，register 方法承担着核心职责——把一个新产生的关系表达式正式纳入优化器的等价类管理体系，
  // 同时通过识别"这个表达式是否与某个已存在的等价表达式相同"，来避免规则反复触发同样的转换从而陷入无限循环。
  // 而 HepPlanner 完全不依赖这套机制（它自己通过 HepRelVertex 图结构和 applyRules 方法中的"不动点"迭代逻辑来实现类似的收敛效果），
  // 所以这里的实现选择了最简单的方式——什么都不做，直接原样返回输入
  @Override public RelNode register(
      RelNode rel,
      @Nullable RelNode equivRel) {
    // Ignore; this call is mostly to tell Volcano how to avoid
    // infinite loops.
    return rel;
  }

  @Override public void onCopy(RelNode rel, RelNode newRel) {
    onCopyHook.apply(rel, newRel);
  }
  // 在 Volcano 模型中，ensureRegistered 通常用于——"确保这个关系表达式已经被注册进优化器（如果还没有注册，就顺带完成注册），并返回代表它（或者它所属等价类）的规范化表示"。
  // 这个方法经常在某些规则的实现内部被调用，用于确保规则产生的中间关系表达式能够被正确纳入优化器的管理体系。
  @Override public RelNode ensureRegistered(RelNode rel, @Nullable RelNode equivRel) {
    return rel;
  }

  @Override public boolean isRegistered(RelNode rel) {
    return true;
  }

  // 构建和维护有向无环图（DAG）最核心的私有递归方法。
  // 无论是最初通过 setRoot 注入整棵关系代数树，还是在优化规则击发后将产生的新算子（bestRel）缝合回图中，都必须通过这个方法进行“图节点化”处理。
  private HepRelVertex addRelToGraph(
      RelNode rel) {
    // Check if a transformation already produced a reference
    // to an existing vertex.
    // 阶段 1：边界防线 —— 检查是否已是图顶点
    // 如果传入的 rel 已经属于 graph.vertexSet()（这意味着它本身其实就是一个已经被包装好的 HepRelVertex），说明它在之前的图变换中已经被处理过了，无需重复操作，直接强转并返回。
    if (graph.vertexSet().contains(rel)) {
      return (HepRelVertex) rel;
    }

    // Recursively add children, replacing this rel's inputs
    // with corresponding child vertices.
    // 阶段 2：DFS 自底向上递归 —— 处理子节点并重织输入
    final List<RelNode> inputs = rel.getInputs();
    final List<RelNode> newInputs = new ArrayList<>();
    // 优先对当前算子的所有子节点（inputs）递归调用 addRelToGraph。这保证了子节点一定比父节点先被加入图并被包装成 HepRelVertex。
    for (RelNode input1 : inputs) {
      HepRelVertex childVertex = addRelToGraph(input1);
      newInputs.add(childVertex);
    }
    // inputs 指针将全部指向包装好的 HepRelVertex 顶点。随后触发 onCopy 钩子函数。
    if (!Util.equalShallow(inputs, newInputs)) {
      RelNode oldRel = rel;
      rel = rel.copy(rel.getTraitSet(), newInputs);
      onCopy(oldRel, rel);
    }
    // Compute digest first time we add to DAG,
    // otherwise can't get equivVertex for common sub-expression
    // 阶段 3：重算摘要 —— 为去重做准备
    rel.recomputeDigest();

    // try to find equivalent rel only if DAG is allowed
    // 阶段 4：公共子表达式消除（CSE）—— DAG 核心去重
    if (!noDag) {
      // Now, check if an equivalent vertex already exists in graph.
      // 碰撞成功：如果发现字典里已经存在一个一模一样的摘要，说明当前正在处理的这棵子树，在图的其他地方已经完全一模一样地出现过一次了（公共子表达式）
      // 直接复用：此时，优化器会直接丢弃当前新孵化的 rel，转而返回已经存在的、老顶点的引用 equivVertex。这直接让当前的父节点也指向这个老顶点，在图结构上形成了一个多引用分支，完美实现了代数层面的算子共享（去重）。
      HepRelVertex equivVertex = mapDigestToVertex.get(rel.getRelDigest());
      if (equivVertex != null) {
        // Use existing vertex.
        return equivVertex;
      }
    }

    // No equivalence:  create a new vertex to represent this rel.
    // 阶段 5：图拓扑织网 —— 真正创建并拉起边引用
    // 真正实例化一个 HepRelVertex 顶点，将 rel 锁在壳内。
    HepRelVertex newVertex = new HepRelVertex(rel);
    // 将该顶点塞入 graph 的全局顶点集（Vertex Set）。
    graph.addVertex(newVertex);
    updateVertex(newVertex, rel);
    // 遍历当前算子的 inputs，在底层 graph 中建立一条条从当前父顶点指向子顶点的有向边（Edge），完成图拓扑网络的编织。
    for (RelNode input : rel.getInputs()) {
      graph.addEdge(newVertex, (HepRelVertex) input);
    }

    nTransformations++;
    return newVertex;
  }

  private void contractVertices(
      HepRelVertex preservedVertex,
      HepRelVertex discardedVertex,
      List<HepRelVertex> parents) {
    if (preservedVertex == discardedVertex) {
      // Nop.
      return;
    }

    RelNode rel = preservedVertex.getCurrentRel();
    updateVertex(preservedVertex, rel);

    // Update specified parents of discardedVertex.
    for (HepRelVertex parent : parents) {
      RelNode parentRel = parent.getCurrentRel();
      List<RelNode> inputs = parentRel.getInputs();
      for (int i = 0; i < inputs.size(); ++i) {
        RelNode child = inputs.get(i);
        if (child != discardedVertex) {
          continue;
        }
        parentRel.replaceInput(i, preservedVertex);
      }
      clearCache(parent);
      graph.removeEdge(parent, discardedVertex);
      graph.addEdge(parent, preservedVertex);
      updateVertex(parent, parentRel);
    }

    // NOTE:  we don't actually do graph.removeVertex(discardedVertex),
    // because it might still be reachable from preservedVertex.
    // Leave that job for garbage collection.

    if (discardedVertex == root) {
      root = preservedVertex;
    }
  }

  /**
   * Clears metadata cache for the RelNode and its ancestors.
   *
   * @param vertex relnode
   */
  private void clearCache(HepRelVertex vertex) {
    RelMdUtil.clearCache(vertex.getCurrentRel());
    if (!RelMdUtil.clearCache(vertex)) {
      return;
    }
    Queue<DefaultEdge> queue =
        new ArrayDeque<>(graph.getInwardEdges(vertex));
    while (!queue.isEmpty()) {
      DefaultEdge edge = queue.remove();
      HepRelVertex source = (HepRelVertex) edge.source;
      RelMdUtil.clearCache(source.getCurrentRel());
      if (RelMdUtil.clearCache(source)) {
        queue.addAll(graph.getInwardEdges(source));
      }
    }
  }

  private void updateVertex(HepRelVertex vertex, RelNode rel) {
    if (rel != vertex.getCurrentRel()) {
      // REVIEW jvs 5-Apr-2006:  We'll do this again later
      // during garbage collection.  Or we could get rid
      // of mark/sweep garbage collection and do it precisely
      // at this point by walking down to all rels which are only
      // reachable from here.
      notifyDiscard(vertex.getCurrentRel());
    }
    RelDigest oldKey = vertex.getCurrentRel().getRelDigest();
    if (mapDigestToVertex.get(oldKey) == vertex) {
      mapDigestToVertex.remove(oldKey);
    }
    // When a transformation happened in one rule apply, support
    // vertex2 replace vertex1, but the current relNode of
    // vertex1 and vertex2 is same,
    // then the digest is also same. but we can't remove vertex2,
    // otherwise the digest will be removed wrongly in the mapDigestToVertex
    //  when collectGC
    // so it must update the digest that map to vertex
    mapDigestToVertex.put(rel.getRelDigest(), vertex);
    if (rel != vertex.getCurrentRel()) {
      vertex.replaceRel(rel);
    }
    notifyEquivalence(
        rel,
        vertex,
        false);
  }

  private RelNode buildFinalPlan(HepRelVertex vertex) {
    RelNode rel = vertex.getCurrentRel();

    notifyChosen(rel);

    // Recursively process children, replacing this rel's inputs
    // with corresponding child rels.
    List<RelNode> inputs = rel.getInputs();
    boolean changed = false;
    for (int i = 0; i < inputs.size(); ++i) {
      RelNode child = inputs.get(i);
      if (!(child instanceof HepRelVertex)) {
        // Already replaced.
        continue;
      }
      child = buildFinalPlan((HepRelVertex) child);
      rel.replaceInput(i, child);
      changed = true;
    }
    if (changed) {
      RelMdUtil.clearCache(rel);
      rel.recomputeDigest();
    }

    if (rel instanceof HepRelVertex) {
      throw new AssertionError("post-condition failed: " + rel);
    }
    return rel;
  }

  private void collectGarbage() {
    if (nTransformations == nTransformationsLastGC) {
      // No modifications have taken place since the last gc,
      // so there can't be any garbage.
      return;
    }
    nTransformationsLastGC = nTransformations;

    LOGGER.trace("collecting garbage");

    // Yer basic mark-and-sweep.
    final Set<HepRelVertex> rootSet = new HashSet<>();
    HepRelVertex root = requireNonNull(this.root, "this.root");
    if (graph.vertexSet().contains(root)) {
      BreadthFirstIterator.reachable(rootSet, graph, root);
    }

    if (rootSet.size() == graph.vertexSet().size()) {
      // Everything is reachable:  no garbage to collect.
      return;
    }
    final Set<HepRelVertex> sweepSet = new HashSet<>();
    for (HepRelVertex vertex : graph.vertexSet()) {
      if (!rootSet.contains(vertex)) {
        sweepSet.add(vertex);
        RelNode rel = vertex.getCurrentRel();
        notifyDiscard(rel);
      }
    }
    assert !sweepSet.isEmpty();
    graph.removeAllVertices(sweepSet);
    graphSizeLastGC = graph.vertexSet().size();

    // Clean up digest map too.
    Iterator<Map.Entry<RelDigest, HepRelVertex>> digestIter =
        mapDigestToVertex.entrySet().iterator();
    while (digestIter.hasNext()) {
      HepRelVertex vertex = digestIter.next().getValue();
      if (sweepSet.contains(vertex)) {
        digestIter.remove();
      }
    }
  }

  private void assertNoCycles() {
    // Verify that the graph is acyclic.
    final CycleDetector<HepRelVertex, DefaultEdge> cycleDetector =
        new CycleDetector<>(graph);
    Set<HepRelVertex> cyclicVertices = cycleDetector.findCycles();
    if (cyclicVertices.isEmpty()) {
      return;
    }

    throw new AssertionError("Query graph cycle detected in HepPlanner: "
        + cyclicVertices);
  }

  private void dumpGraph() {
    if (!LOGGER.isTraceEnabled()) {
      return;
    }

    assertNoCycles();

    HepRelVertex root = this.root;
    if (root == null) {
      LOGGER.trace("dumpGraph: root is null");
      return;
    }
    final RelMetadataQuery mq = root.getCluster().getMetadataQuery();
    final StringBuilder sb = new StringBuilder();
    sb.append("\nBreadth-first from root:  {\n");
    for (HepRelVertex vertex : BreadthFirstIterator.of(graph, root)) {
      sb.append("    ")
          .append(vertex)
          .append(" = ");
      RelNode rel = vertex.getCurrentRel();
      sb.append(rel)
          .append(", rowcount=")
          .append(mq.getRowCount(rel))
          .append(", cumulative cost=")
          .append(getCost(rel, mq))
          .append('\n');
    }
    sb.append("}");
    LOGGER.trace(sb.toString());
  }

  @Deprecated // to be removed before 2.0
  @Override public void registerMetadataProviders(List<RelMetadataProvider> list) {
    list.add(0, new HepRelMetadataProvider());
  }

  @Deprecated // to be removed before 2.0
  @Override public long getRelMetadataTimestamp(RelNode rel) {
    // TODO jvs 20-Apr-2006: This is overly conservative.  Better would be
    // to keep a timestamp per HepRelVertex, and update only affected
    // vertices and all ancestors on each transformation.
    return nTransformations;
  }

  @Override public ImmutableList<RelOptMaterialization> getMaterializations() {
    return ImmutableList.copyOf(materializations);
  }

  @Override public void addMaterialization(RelOptMaterialization materialization) {
    materializations.add(materialization);
  }
}
