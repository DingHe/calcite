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
package org.apache.calcite.plan.volcano;

import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptListener;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTrait;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.AbstractRelNode;
import org.apache.calcite.rel.PhysicalNode;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.externalize.RelWriterImpl;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;
import org.apache.calcite.util.trace.CalciteTrace;

import com.google.common.collect.Sets;

import org.apiguardian.api.API;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

import static java.util.Objects.requireNonNull;

/**
 * Subset of an equivalence class where all relational expressions have the
 * same physical properties.
 * 具有相同物理属性的相等集
 * <p>Physical properties are instances of the {@link RelTraitSet}, and consist
 * of traits such as calling convention and collation (sort-order).
 *
 * <p>For some traits, a relational expression can have more than one instance.
 * For example, R can be sorted on both [X] and [Y, Z]. In which case, R would
 * belong to the sub-sets for [X] and [Y, Z]; and also the leading edges [Y] and
 * [].
 *
 * @see RelNode
 * @see RelSet
 * @see RelTrait
 */
public class RelSubset extends AbstractRelNode {
  //~ Static fields/initializers ---------------------------------------------

  private static final Logger LOGGER = CalciteTrace.getPlannerTracer();
  private static final int DELIVERED = 1;
  private static final int REQUIRED = 2;

  //~ Instance fields --------------------------------------------------------

  /** Optimization task state. 优化状态分为优化中、已完成*/
  @Nullable OptimizeState taskState;

  /** Cost of best known plan (it may have improved since). */
  RelOptCost bestCost;

  /** The set this subset belongs to. 记录RelSubset所属的RelSet*/
  final RelSet set;

  /** Best known plan. 当前已知最优的关系节点*/
  @Nullable RelNode best;

  /** Timestamp for metadata validity. */
  long timestamp;

  /**
   * Physical property state of current subset. Values:
   * state 主要用于表示当前子集的物理属性状态（traitSet）
   * <ul>
   * <li>0: logical operators, NONE convention is neither DELIVERED nor REQUIRED  状态值 0：表示逻辑操作符（NONE 调用约定），该状态下没有交付（DELIVERED）或所需（REQUIRED）的物理属性
   * <li>1: traitSet DELIVERED from child operators or itself  状态值 1：表示物理属性（traitSet）已经从子操作符或自身交付（DELIVERED
   * <li>2: traitSet REQUIRED from parent operators  状态值 2：表示该子集的物理属性是由父操作符要求（REQUIRED）的
   * <li>3: both DELIVERED and REQUIRED 状态值 3：表示该子集同时满足了交付（DELIVERED）和所需（REQUIRED）的物理属性，意味着该子集的物理属性已经完全满足了查询的需求
   * </ul>
   */
  private int state = 0;

  /**
   * This subset should trigger rules when it becomes delivered.
   */
  boolean triggerRule = false;

  /**
   * When the subset state is REQUIRED, whether enable property enforcing
   * between this subset and other delivered subsets. When it is true,
   * no enforcer operators will be added even if the other subset can't
   * satisfy current subset's required traitSet.
   */
  private boolean enforceDisabled = false;

  /**
   * The upper bound of the last OptimizeGroup call.
   */
  RelOptCost upperBound;

  /**
   * A cache that recognize which RelNode has invoked the passThrough method
   * so as to avoid duplicate invocation.
   */
  @Nullable Set<RelNode> passThroughCache;

  //~ Constructors -----------------------------------------------------------

  RelSubset(
      RelOptCluster cluster,
      RelSet set,
      RelTraitSet traits) {
    super(cluster, traits);
    this.set = set;
    assert traits.allSimple();
    computeBestCost(cluster, cluster.getPlanner());//计算RelSubset的成本
    upperBound = bestCost;
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Computes the best {@link RelNode} in this subset.
   * 计算该子集的bestCost
   * <p>Only necessary when a subset is created in a set that has subsets that
   * subsume it. Rationale:
   *
   * <ol>
   * <li>If the are no subsuming subsets, the subset is initially empty.</li>
   * <li>After creation, {@code best} and {@code bestCost} are maintained
   *    incrementally by {@link VolcanoPlanner#propagateCostImprovements} and
   *    {@link RelSet#mergeWith(VolcanoPlanner, RelSet)}.</li>
   * </ol>
   */
  @EnsuresNonNull("bestCost")
  private void computeBestCost(
      @UnderInitialization RelSubset this,
      RelOptCluster cluster,
      RelOptPlanner planner) {
    bestCost = planner.getCostFactory().makeInfiniteCost(); //刚开始默认为最大
    final RelMetadataQuery mq = cluster.getMetadataQuery();
    @SuppressWarnings("method.invocation.invalid")
    Iterable<RelNode> rels = getRels();
    for (RelNode rel : rels) {
      final RelOptCost cost = planner.getCost(rel, mq);
      if (cost == null) {
        continue;
      }
      if (cost.isLt(bestCost)) {
        bestCost = cost;
        best = rel;
      }
    }
  }

  void setDelivered() {
    triggerRule = !isDelivered();
    state |= DELIVERED;
  }
  //REQUIRED表示父节点要求满足
  void setRequired() {
    triggerRule = false;
    state |= REQUIRED;
  }

  @API(since = "1.23", status = API.Status.EXPERIMENTAL)
  public boolean isDelivered() {
    return (state & DELIVERED) == DELIVERED;
  }

  @API(since = "1.23", status = API.Status.EXPERIMENTAL)
  public boolean isRequired() {
    return (state & REQUIRED) == REQUIRED;
  }

  void disableEnforcing() {
    assert isDelivered();
    enforceDisabled = true;
  }

  boolean isEnforceDisabled() {
    return enforceDisabled;
  }

  public @Nullable RelNode getBest() {
    return best;
  }

  public @Nullable RelNode getOriginal() {
    return set.rel;
  }

  @API(since = "1.27", status = API.Status.INTERNAL)
  public RelNode getBestOrOriginal() {
    RelNode result = getBest();
    if (result != null) {
      return result;
    }
    return requireNonNull(getOriginal(), "both best and original nodes are null");
  }

  @Override public RelNode stripped() {
    return getBestOrOriginal();
  }

  @Override public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    if (inputs.isEmpty()) {
      final RelTraitSet traitSet1 = traitSet.simplify();
      if (traitSet1.equals(this.traitSet)) {
        return this;
      }
      return set.getOrCreateSubset(getCluster(), traitSet1, isRequired());
    }
    throw new UnsupportedOperationException();
  }

  @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
      RelMetadataQuery mq) {
    return planner.getCostFactory().makeZeroCost();
  }

  @Override public double estimateRowCount(RelMetadataQuery mq) {
    if (best != null) {
      return mq.getRowCount(best);
    } else {
      return mq.getRowCount(castNonNull(set.rel));
    }
  }

  @Override public void explain(RelWriter pw) {
    // Not a typical implementation of "explain". We don't gather terms &
    // values to be printed later. We actually do the work.
    pw.item("subset", toString());
    final AbstractRelNode input =
        (@Nullable AbstractRelNode) Util.first(getBest(), getOriginal());
    if (input == null) {
      return;
    }
    input.explainTerms(pw);
    pw.done(input);
  }

  @Override public boolean deepEquals(@Nullable Object obj) {
    return this == obj;
  }

  @Override public int deepHashCode() {
    return this.hashCode();
  }

  @Override protected RelDataType deriveRowType() {
    return castNonNull(set.rel).getRowType();
  }

  /**
   * Returns the collection of RelNodes one of whose inputs is in this
   * subset.
   */
  Set<RelNode> getParents() {
    final Set<RelNode> list = new LinkedHashSet<>();
    for (RelNode parent : set.getParentRels()) {
      for (RelSubset rel : inputSubsets(parent)) {
        // see usage of this method in propagateCostImprovements0()
        if (rel == this) {
          list.add(parent);
          break;
        }
      }
    }
    return list;
  }

  /**
   * Returns the collection of distinct subsets that contain a RelNode one
   * of whose inputs is in this subset.
   */
  Set<RelSubset> getParentSubsets(VolcanoPlanner planner) {
    final Set<RelSubset> list = new LinkedHashSet<>();
    for (RelNode parent : set.getParentRels()) {
      for (RelSubset rel : inputSubsets(parent)) {
        if (rel.set == set && rel.getTraitSet().equals(traitSet)) {
          list.add(planner.getSubsetNonNull(parent));
          break;
        }
      }
    }
    return list;
  }

  private static List<RelSubset> inputSubsets(RelNode parent) {
    //noinspection unchecked
    return (List<RelSubset>) (List) parent.getInputs();
  }

  /**
   * Returns a list of relational expressions one of whose children is this
   * subset. The elements of the list are distinct.
   */
  public Collection<RelNode> getParentRels() {
    final Set<RelNode> list = new LinkedHashSet<>();
  parentLoop:
    for (RelNode parent : set.getParentRels()) {
      for (RelSubset rel : inputSubsets(parent)) {
        if (rel.set == set && traitSet.satisfies(rel.getTraitSet())) {
          list.add(parent);
          continue parentLoop;
        }
      }
    }
    return list;
  }

  RelSet getSet() {
    return set;
  }

  /** 把rel加入RelSubset所属的RelSet的rels里面
   * Adds expression <code>rel</code> to this subset.
   */
  void add(RelNode rel) {
    assert !(rel instanceof HepRelVertex);
    if (set.rels.contains(rel)) {
      return;
    }

    VolcanoPlanner planner = (VolcanoPlanner) rel.getCluster().getPlanner();
    if (planner.getListener() != null) {
      RelOptListener.RelEquivalenceEvent event =
          new RelOptListener.RelEquivalenceEvent(
              planner,
              rel,
              this,
              true);
      planner.getListener().relEquivalenceFound(event);
    }

    set.addInternal(rel);
    if (false) {
      Set<CorrelationId> variablesSet = RelOptUtil.getVariablesSet(rel);
      Set<CorrelationId> variablesStopped = rel.getVariablesSet();
      Set<CorrelationId> variablesPropagated =
          Util.minus(variablesSet, variablesStopped);
      assert set.variablesPropagated.containsAll(variablesPropagated);
      Set<CorrelationId> variablesUsed = RelOptUtil.getVariablesUsed(rel);
      assert set.variablesUsed.containsAll(variablesUsed);
    }
  }

  /**
   * Recursively builds a tree consisting of the cheapest plan at each node.
   */
  RelNode buildCheapestPlan(VolcanoPlanner planner) {
    CheapestPlanReplacer replacer = new CheapestPlanReplacer(planner);
    final RelNode cheapest = replacer.visit(this, -1, null);  //-1表示根节点无父节点

    if (planner.getListener() != null) {
      RelOptListener.RelChosenEvent event =
          new RelOptListener.RelChosenEvent(
              planner,
              null);
      planner.getListener().relChosen(event);
    }

    return cheapest;
  }

  @Override public void collectVariablesUsed(Set<CorrelationId> variableSet) {
    variableSet.addAll(set.variablesUsed);
  }

  @Override public void collectVariablesSet(Set<CorrelationId> variableSet) {
    variableSet.addAll(set.variablesPropagated);
  }

  /**
   * Returns the rel nodes in this rel subset.  All rels must have the same
   * traits and are logically equivalent.
   * 遍历RelSet中语意相等的RelSet，过滤满足当前子集特征的RelNode
   * @return all the rels in the subset
   */
  public Iterable<RelNode> getRels() {
    return () -> Linq4j.asEnumerable(set.rels)
        .where(v1 -> v1.getTraitSet().satisfies(traitSet))
        .iterator();
  }

  /**
   * As {@link #getRels()} but returns a list.
   */
  public List<RelNode> getRelList() {
    final List<RelNode> list = new ArrayList<>();
    for (RelNode rel : set.rels) {
      if (rel.getTraitSet().satisfies(traitSet)) {
        list.add(rel);
      }
    }
    return list;
  }

  /**
   * Returns whether this subset contains the specified relational expression.
   */
  public boolean contains(RelNode node) {
    return set.rels.contains(node) && node.getTraitSet().satisfies(traitSet);
  }

  /**
   * Returns stream of subsets whose traitset satisfies
   * current subset's traitset.
   */
  @API(since = "1.23", status = API.Status.EXPERIMENTAL)
  public Stream<RelSubset> getSubsetsSatisfyingThis() {
    return set.subsets.stream()
      .filter(s -> s.getTraitSet().satisfies(traitSet));
  }

  /**
   * Returns stream of subsets whose traitset is satisfied
   * by current subset's traitset.
   */
  @API(since = "1.23", status = API.Status.EXPERIMENTAL)
  public Stream<RelSubset> getSatisfyingSubsets() {
    return set.subsets.stream()
      .filter(s -> traitSet.satisfies(s.getTraitSet()));
  }

  /**
   * Returns the best cost if this subset is fully optimized
   * or null if the subset is not fully optimized.
   */
  @API(since = "1.24", status = API.Status.INTERNAL)
  public @Nullable RelOptCost getWinnerCost() {
    if (taskState == OptimizeState.COMPLETED && bestCost.isLe(upperBound)) {
      return bestCost;
    }
    // if bestCost != upperBound, it means optimize failed
    return null;
  }

  void startOptimize(RelOptCost ub) {
    assert getWinnerCost() == null : this + " is already optimized";  //如果不winnerCost不为null，则代表已经优化过
    if (upperBound.isLt(ub)) {
      upperBound = ub;
      if (bestCost.isLt(upperBound)) {
        upperBound = bestCost;  //如果有最优成本，则上限就是最优成本
      }
    }
    taskState = OptimizeState.OPTIMIZING;  //状态为优化中
  }
  //标志优化完成
  void setOptimized() {
    taskState = OptimizeState.COMPLETED;
  }

  boolean resetTaskState() {
    boolean optimized = taskState != null;
    taskState = null;
    upperBound = bestCost;
    return optimized;
  }

  @Nullable RelNode passThrough(RelNode rel) {
    if (!(rel instanceof PhysicalNode)) {
      return null;
    }
    if (passThroughCache == null) {
      passThroughCache = Sets.newIdentityHashSet();
      passThroughCache.add(rel);
    } else if (!passThroughCache.add(rel)) {
      return null;
    }
    return ((PhysicalNode) rel).passThrough(this.getTraitSet());
  }

  boolean isExplored() {
    return set.exploringState == RelSet.ExploringState.EXPLORED; //表示已经探索过
  }

  boolean explore() {
    if (set.exploringState != null) {
      return false;
    }
    set.exploringState = RelSet.ExploringState.EXPLORING;
    return true;
  }

  void setExplored() {
    set.exploringState = RelSet.ExploringState.EXPLORED;
  }

  //~ Inner Classes ----------------------------------------------------------

  /**
   * Identifies the leaf-most non-implementable nodes.
   */
  static class DeadEndFinder {
    final Set<RelSubset> deadEnds = new HashSet<>();
    // To save time,已经访问过的节点
    private final Set<RelNode> visitedNodes = new HashSet<>();
    // For cycle detection
    private final Set<RelNode> activeNodes = new HashSet<>();

    private boolean visit(RelNode p) {
      if (p instanceof RelSubset) {
        visitSubset((RelSubset) p);
        return false;
      }
      return visitRel(p);
    }

    private void visitSubset(RelSubset subset) {
      RelNode cheapest = subset.getBest();
      if (cheapest != null) { //能找到最优计划
        // Subset is implementable, and we are looking for bad ones, so stop here
        return;
      }

      boolean isEmpty = true;
      for (RelNode rel : subset.getRels()) { //遍历RelSet中语意相等的RelSet，过滤满足当前子集特征的RelNode
        if (rel instanceof AbstractConverter) {
          // Converters are not implementable
          continue;
        }
        if (!activeNodes.add(rel)) {
          continue;
        }
        boolean res = visit(rel);
        isEmpty &= res;
        activeNodes.remove(rel);
      }
      if (isEmpty) {
        deadEnds.add(subset);
      }
    }

    /**
     * Returns true when input {@code RelNode} is cyclic.
     */
    private boolean visitRel(RelNode p) {
      // If one of the inputs is in "active" set, that means the rel forms a cycle,
      // then we just ignore it. Cyclic rels are not implementable.
      for (RelNode oldInput : p.getInputs()) {
        if (activeNodes.contains(oldInput)) {
          return true;
        }
      }
      // The same subset can be used multiple times (e.g. union all with the same inputs),
      // so it is important to perform "contains" and "add" in different loops
      activeNodes.addAll(p.getInputs());
      for (RelNode oldInput : p.getInputs()) {
        if (!visitedNodes.add(oldInput)) {
          // We don't want to explore the same subset twice
          continue;
        }
        visit(oldInput);
      }
      activeNodes.removeAll(p.getInputs());
      return false;
    }
  }

  @Override public String getDigest() {
    return "RelSubset#" + set.id + '.' + getTraitSet();
  }

  /** 从根节点开始递归遍历，每个关系节点的子集取最优计划节点，没取到就会报错
   * Visitor which walks over a tree of {@link RelSet}s, replacing each node
   * with the cheapest implementation of the expression.
   */
  static class CheapestPlanReplacer {
    VolcanoPlanner planner;
    final Map<Integer, RelNode> visited = new HashMap<>(); //存储已经访问过的RelNode，防止重复计算或遍历

    CheapestPlanReplacer(VolcanoPlanner planner) {
      super();
      this.planner = planner;
    }

    private static String traitDiff(RelTraitSet original, RelTraitSet desired) {
      return Pair.zip(original, desired)
          .stream()
          .filter(p -> !p.left.satisfies(p.right)) //拆选出original 不满足 desired的特征集
          .map(p -> p.left.getTraitDef().getSimpleName() + ": " + p.left + " -> " + p.right)
          .collect(Collectors.joining(", ", "[", "]")); //只是转为文本输出？
    }

    public RelNode visit(
        RelNode p, //当前要访问的 RelNode 节点
        int ordinal, //当前节点在其父节点中的位置，-1表示无父节点
        @Nullable RelNode parent) {  //当前节点的父节点，可能为 null
      final int pId = p.getId();
      RelNode prevVisit = visited.get(pId);
      if (prevVisit != null) {  //如果已经访问过，则直接返回
        // return memoized result of previous visit if available
        return prevVisit;
      }

      if (p instanceof RelSubset) {
        RelSubset subset = (RelSubset) p;
        RelNode cheapest = subset.best;  //取最优的节点
        if (cheapest == null) {
          //没找到，
          //RelSubset 中有一个 best 属性，它保存了最优的执行计划。如果没有找到最优的计划，就会触发一段详细的错误处理代码，用于调试和输出为什么没有找到最优计划
          // Dump the planner's expression pool so we can figure
          // out why we reached impasse.
          StringWriter sw = new StringWriter();
          final PrintWriter pw = new PrintWriter(sw);

          pw.print("There are not enough rules to produce a node with desired properties");
          RelTraitSet desiredTraits = subset.getTraitSet();
          String sep = ": ";
          for (RelTrait trait : desiredTraits) {
            pw.print(sep);
            pw.print(trait.getTraitDef().getSimpleName());
            pw.print("=");
            pw.print(trait);
            sep = ", ";
          }
          pw.print(".");
          DeadEndFinder finder = new DeadEndFinder();
          finder.visit(subset);
          if (finder.deadEnds.isEmpty()) {
            pw.print(" All the inputs have relevant nodes, however the cost is still infinite.");
          } else {
            Map<String, Long> problemCounts =
                finder.deadEnds.stream()
                    .filter(deadSubset -> deadSubset.getOriginal() != null)
                    .map(x -> {
                      RelNode original = castNonNull(x.getOriginal());
                      return original.getClass().getSimpleName()
                          + traitDiff(original.getTraitSet(), x.getTraitSet());
                    })
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            // Sort problems from most often to less often ones
            String problems = problemCounts.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry<String, Long>::getValue).reversed())
                .map(e -> e.getKey() + (e.getValue() > 1 ? " (" + e.getValue() + " cases)" : ""))
                .collect(Collectors.joining(", "));
            pw.println();
            pw.print("Missing conversion");
            pw.print(finder.deadEnds.size() == 1 ? " is " : "s are ");
            pw.print(problems);
            pw.println();
            if (finder.deadEnds.size() == 1) {
              pw.print("There is 1 empty subset: ");
            }
            if (finder.deadEnds.size() > 1) {
              pw.println("There are " + finder.deadEnds.size() + " empty subsets:");
            }
            int i = 0;
            int rest = finder.deadEnds.size();
            for (RelSubset deadEnd : finder.deadEnds) {
              if (finder.deadEnds.size() > 1) {
                pw.print("Empty subset ");
                pw.print(i);
                pw.print(": ");
              }
              pw.print(deadEnd);
              pw.println(", the relevant part of the original plan is as follows");
              RelNode original = deadEnd.getOriginal();
              if (original != null) {
                original.explain(
                    new RelWriterImpl(pw, SqlExplainLevel.EXPPLAN_ATTRIBUTES, true));
              }
              i++;
              rest--;
              if (rest > 0) {
                pw.println();
              }
              if (i >= 10 && rest > 1) {
                pw.print("The rest ");
                pw.print(rest);
                pw.println(" leafs are omitted.");
                break;
              }
            }
          }
          pw.println();

          planner.dump(pw);
          pw.flush();
          final String dump = sw.toString();
          RuntimeException e =
              new RelOptPlanner.CannotPlanException(dump);
          LOGGER.trace("Caught exception in class={}, method=visit", getClass().getName(), e);
          throw e;
        }
        p = cheapest;
      }
      //-1表示无父节点，这里表示当前节点有父节点
      if (ordinal != -1) {
        if (planner.getListener() != null) {
          RelOptListener.RelChosenEvent event =
              new RelOptListener.RelChosenEvent(
                  planner,
                  p);
          planner.getListener().relChosen(event);
        }
      }
      //对于每个输入节点，递归调用 visit 方法来计算其最优执行计划。
      List<RelNode> oldInputs = p.getInputs();
      List<RelNode> inputs = new ArrayList<>();
      for (int i = 0; i < oldInputs.size(); i++) {
        RelNode oldInput = oldInputs.get(i);
        RelNode input = visit(oldInput, i, p);
        inputs.add(input);
      }
      if (!inputs.equals(oldInputs)) {  //不想等表示
        final RelNode pOld = p;
        p = p.copy(p.getTraitSet(), inputs);
        planner.provenanceMap.put(
            p, new VolcanoPlanner.DirectProvenance(pOld));
      }
      visited.put(pId, p); // memoize result for pId
      return p;
    }
  }

  /** State of optimizer. */
  enum OptimizeState {
    OPTIMIZING,
    COMPLETED
  }
}
