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
package org.apache.calcite.plan;

import org.apache.calcite.plan.volcano.RelSubset;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexExecutor;
import org.apache.calcite.util.CancelFlag;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;
import org.apache.calcite.util.trace.CalciteTrace;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;
import org.slf4j.Logger;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.apache.calcite.util.Static.RESOURCE;

/**
 * Abstract base for implementations of the {@link RelOptPlanner} interface.
 */
// 为 Calcite 中所有具体的优化器实现（比如基于 Volcano 模型的 VolcanoPlanner、基于启发式规则的 HepPlanner 等）提供一套通用的、可复用的基础设施，包括：
// 规则（RelOptRule）的注册、查找、移除与去重管理——所有优化器都需要维护一套规则集合，这部分逻辑是通用的，无需每个具体优化器各自实现。
// 监听器（RelOptListener）机制——用于在规则匹配、规则应用、关系表达式被选中/丢弃/发现等价等关键事件发生时，通知外部监听者（常用于调试、追踪、性能分析）。
// 成本工厂（RelOptCostFactory）、执行上下文（Context）、取消标志（CancelFlag）等通用基础设施的持有与管理。
// 关系表达式类型（RelNode 子类）与调用约定（Convention）的注册机制，用于让具体的规则能够正确匹配到对应类型的关系节点。
// 规则调用（fireRule）的统一入口，封装了规则触发前后的取消检查、过滤检查、监听器通知等公共逻辑，避免每个具体优化器各自重复实现这套"仪式性"代码。
// 规则尝试次数的调试统计功能（通过内部类 RuleAttemptsListener），用于在调试模式下分析各规则的调用频率和耗时。
// 对于一些并非所有优化器都支持的高级特性（比如物化视图 Materialization、Lattice/格），提供了默认的"空实现"（表示不支持），具体子类如果确实支持，可以选择重写这些方法。
public abstract class AbstractRelOptPlanner implements RelOptPlanner {
  //~ Static fields/initializers ---------------------------------------------

  /** Logger for rule attempts information. */
  private static final Logger RULE_ATTEMPTS_LOGGER = CalciteTrace.getRuleAttemptsTracer();

  //~ Instance fields --------------------------------------------------------

  /**
   * Maps rule description to rule, just to ensure that rules' descriptions
   * are unique.
   */
  // "规则描述字符串 → 规则对象"的映射表，核心目的不是为了通过描述查找规则（虽然确实也提供了这个能力），
  // 而是为了确保所有注册进来的规则，其"描述"（toString() 的结果）必须是唯一的，避免出现两个不同的规则实例却使用了相同的描述字符串这种容易引发混淆的情况。
  protected final Map<String, RelOptRule> mapDescToRule = new LinkedHashMap<>();

  // 保存"成本工厂"实例，用于创建代价（RelOptCost）对象——在基于代价的优化过程中，
  // 需要频繁地创建、比较不同关系表达式的执行代价（比如 I/O 开销、CPU 开销等），这个工厂类负责统一生产这些代价对象。
  protected final RelOptCostFactory costFactory;
  // 一种"广播式"监听器，内部可以同时管理多个真正的监听器，对外表现为单一的监听器接口，实际上会把事件转发给它内部持有的所有子监听器
  private @MonotonicNonNull MulticastRelOptListener listener;
  // 保存这个专门用于"规则尝试统计"的监听器实例，
  // 只有在调试日志级别开启时才会被真正创建（正如构造方法中所示）。
  private @MonotonicNonNull RuleAttemptsListener ruleAttemptsListener;

  // 保存一个用于"排除某些规则"的正则表达式模式。如果某个规则的描述字符串能匹配上这个正则，那么这个规则在触发时会被跳过（不会真正执行），
  // 这是一种常见的调试/测试手段——允许通过配置正则表达式，临时禁用某些特定的优化规则，观察禁用后的行为差异。
  private @Nullable Pattern ruleDescExclusionFilter;
  // 作为"取消标志"，用于支持优化过程的中途取消能力——比如当一个查询优化耗时过长，用户或系统希望能够主动中断这个优化过程时，可以把这个标志设为 true，
  // 优化器内部通过频繁调用 checkCancel() 方法检查这个标志，一旦发现被置位就抛出异常终止优化流程。
  protected final AtomicBoolean cancelFlag;

  // 记录当前优化器已经"见过"（已注册）的所有 RelNode 具体子类类型。
  // 这个集合的作用是配合规则匹配机制——很多优化规则的"操作数"（RelOptRuleOperand）会指定它们期望匹配的 RelNode 具体类型，优化器需要知道当前已经出现过哪些类型，才能正确地建立规则与关系节点之间的匹配关系（尤其是涉及到某个规则期望匹配某个抽象基类的所有具体子类型时）。
  private final Set<Class<? extends RelNode>> classes = new HashSet<>();

  // 记录当前优化器已经"见过"（已注册）的所有"调用约定"（Convention，
  // 代表关系表达式的物理实现方式/执行引擎类型，比如 Calcite 内置的枚举执行方式、或者对接到某个特定数据源的调用约定
  private final Set<Convention> conventions = new HashSet<>();  //调用约定

  /** External context. Never null. */
  // 保存"外部上下文"对象，这是 Calcite 中一种通用的、可扩展的"配置/依赖查找"机制（类似一个轻量级的依赖注入容器），
  // 优化器可以通过这个 context 来获取各种外部传入的配置信息或服务对象（
  protected final Context context;
  // 用于在优化过程中提前计算（常量折叠）某些可以静态求值的表达式（比如 1 + 2 这种在编译期就能确定结果的表达式，可以直接被优化器替换为常量 3，减少运行时的计算开销）
  private @Nullable RexExecutor executor;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates an AbstractRelOptPlanner.
   */
  protected AbstractRelOptPlanner(RelOptCostFactory costFactory,
      @Nullable Context context) {
    this.costFactory = Objects.requireNonNull(costFactory, "costFactory");
    if (context == null) {
      context = Contexts.empty();
    }
    this.context = context;

    this.cancelFlag =
        context.maybeUnwrap(CancelFlag.class)
            .map(flag -> flag.atomicBoolean)
            .orElseGet(AtomicBoolean::new);

    // Add abstract RelNode classes. No RelNodes will ever be registered with
    // these types, but some operands may use them.
    // `RelNode` 和 `RelSubset` 都是抽象类型（`RelNode` 是所有关系表达式的顶层接口/抽象类，
    // `RelSubset` 是 Volcano 优化框架中代表"一组等价关系表达式集合"的特殊类型），
    // 实际运行中不会有真正的关系节点直接以这两个类型本身注册进优化器（因为它们太抽象，实际注册的都是具体子类），
    // 但是**某些规则的操作数（operand）在定义匹配条件时，可能会直接以这两个抽象类型作为期望匹配的类型**（比如某条规则希望匹配"任意 RelNode"），
    // 所以需要预先把这两个类型加入 `classes` 集合，确保后续基于 `classes` 集合进行的子类型查找逻辑（比如 `subClasses` 方法）能够正常工作。
    classes.add(RelNode.class);
    classes.add(RelSubset.class);
    // 按需创建规则尝试统计监听器
    if (RULE_ATTEMPTS_LOGGER.isDebugEnabled()) {
      this.ruleAttemptsListener = new RuleAttemptsListener();
      addListener(this.ruleAttemptsListener);
    }
    // 无条件地添加一个 `RuleEventLogger` 监听器（负责把规则相关的事件记录到常规日志中，供开发调试参考）
    addListener(new RuleEventLogger());
  }

  //~ Methods ----------------------------------------------------------------

  @Override public void clear() {}

  @Override public Context getContext() {
    return context;
  }

  @Override public RelOptCostFactory getCostFactory() {
    return costFactory;
  }

  @SuppressWarnings("deprecation")
  @Override public void setCancelFlag(CancelFlag cancelFlag) {
    // ignored
  }

  /**
   * Checks to see whether cancellation has been requested, and if so, throws
   * an exception.
   */
  public void checkCancel() {
    if (cancelFlag.get()) {
      throw RESOURCE.preparationAborted().ex();
    }
  }
  // 获取规则集合
  @Override public List<RelOptRule> getRules() {
    return ImmutableList.copyOf(mapDescToRule.values());
  }
  // 添加规则
  @Override public boolean addRule(RelOptRule rule) {
    // Check that there isn't a rule with the same description
    final String description = rule.toString();
    assert description != null;

    RelOptRule existingRule = mapDescToRule.put(description, rule);
    if (existingRule != null) {
      if (existingRule.equals(rule)) {
        return false;
      } else {
        // This rule has the same description as one previously
        // registered, yet it is not equal. You may need to fix the
        // rule's equals and hashCode methods.
        throw new AssertionError("Rule's description should be unique; "
            + "existing rule=" + existingRule + "; new rule=" + rule);
      }
    }
    return true;
  }
  // 删除规则
  @Override public boolean removeRule(RelOptRule rule) {
    String description = rule.toString();
    RelOptRule removed = mapDescToRule.remove(description);
    return removed != null;
  }

  /**
   * Returns the rule with a given description.

   * @param description Description
   * @return Rule with given description, or null if not found
   */
  // 根据描述获取规则
  protected @Nullable RelOptRule getRuleByDescription(String description) {
    return mapDescToRule.get(description);
  }

  // 设置规则的过滤正则表达式
  @Override public void setRuleDescExclusionFilter(@Nullable Pattern exclusionFilter) {
    ruleDescExclusionFilter = exclusionFilter;
  }

  /**
   * Determines whether a given rule is excluded by ruleDescExclusionFilter.
   * @param rule rule to test
   * @return true iff rule should be excluded
   */
  // 确定是否要过滤此规则
  public boolean isRuleExcluded(RelOptRule rule) {
    return ruleDescExclusionFilter != null
        && ruleDescExclusionFilter.matcher(rule.toString()).matches();
  }
  // 优先选择此优化器
  @Override public RelOptPlanner chooseDelegate() {
    return this;
  }
  // 用于向优化器注册一个"物化视图"（Materialization，一种预先计算并存储好结果的视图，优化器可以利用它来加速某些查询）
  @Override public void addMaterialization(RelOptMaterialization materialization) {
    // ignore - this planner does not support materializations
  }

  @Override public List<RelOptMaterialization> getMaterializations() {
    return ImmutableList.of();
  }
  // 用于注册"Lattice"（格/网格，Calcite 中用于支持 OLAP 多维分析场景的一种特殊结构，可以基于它自动推导出各种聚合级别的物化结果
  @Override public void addLattice(RelOptLattice lattice) {
    // ignore - this planner does not support lattices
  }

  @Override public @Nullable RelOptLattice getLattice(RelOptTable table) {
    // this planner does not support lattices
    return null;
  }
  // 实现接口方法，用于向优化器注册一个"关系模式"（Schema）信息。这里是空实现，什么都不做，具体子类可以按需重写。
  @Override public void registerSchema(RelOptSchema schema) {
  }

  @Deprecated // to be removed before 2.0
  @Override public long getRelMetadataTimestamp(RelNode rel) {
    return 0;
  }

  // 通知优化器某个关系表达式（及其相关的分支）已经不再需要被考虑，可以从搜索空间中移除
  @Override public void prune(RelNode rel) {
  }
  // 向优化器"登记"一个具体的关系节点类型（注意，是登记类型/类，而不是登记这一个具体的节点实例），
  // 确保后续规则匹配机制能够正确识别和处理这种类型的节点
  @Override public void registerClass(RelNode node) {
    final Class<? extends RelNode> clazz = node.getClass();
    if (classes.add(clazz)) {
      onNewClass(node); //把匹配此类及其子类的规则操作数加入classOperands，便于后面规则转换，不包含物理节点的TransformationRule规则
    }
    Convention convention = node.getConvention();
    if (convention != null && conventions.add(convention)) {
      // 让这个调用约定有机会向当前优化器注册一批与之相关的专用规则
      //把优化器注册到调用约定
      convention.register(this);
    }
  }

  /** Called when a new class of {@link RelNode} is seen.*/
  // 当 registerClass 方法检测到某个类型是"第一次出现"时，会回调这个方法，
  // 这里的默认实现是调用 node.register(this)——反过来让这个具体的关系节点类型有机会向当前优化器注册一批与它自身类型相关的规则（
  // 这是一种双向注册的设计——Convention 可以向优化器注册规则，RelNode 的具体类型也可以向优化器注册规则，两者互补，共同构成完整的规则注册体系）。
  protected void onNewClass(RelNode node) {
    node.register(this);
  }

  @Override public RelTraitSet emptyTraitSet() {
    return RelTraitSet.createEmpty();
  }

  // 计算给定关系表达式的（累计）代价
  // mq（RelMetadataQuery）：元数据查询工具，Calcite 中用于统一获取各种关系表达式元数据信息（比如行数估算、代价估算等）的入口
  @Override public @Nullable RelOptCost getCost(RelNode rel, RelMetadataQuery mq) {
    return mq.getCumulativeCost(rel);
  }

  @Deprecated // to be removed before 2.0
  @Override public @Nullable RelOptCost getCost(RelNode rel) {
    final RelMetadataQuery mq = rel.getCluster().getMetadataQuery();
    return getCost(rel, mq);
  }
  // 向优化器添加一个新的监听器。
  @Override public void addListener(
      @UnknownInitialization AbstractRelOptPlanner this,
      RelOptListener newListener) {
    if (listener == null) {
      listener = new MulticastRelOptListener();
    }
    listener.addListener(newListener);
  }

  @Deprecated // to be removed before 2.0
  @Override public void registerMetadataProviders(List<RelMetadataProvider> list) {
  }

  @Override public boolean addRelTraitDef(RelTraitDef relTraitDef) {
    return false;
  }

  @Override public void clearRelTraitDefs() {}

  @Override public List<RelTraitDef> getRelTraitDefs() {
    return ImmutableList.of();
  }
  // 常量表达式预先计算
  @Override public void setExecutor(@Nullable RexExecutor executor) {
    this.executor = executor;
  }

  @Override public @Nullable RexExecutor getExecutor() {
    return executor;
  }

  @Override public void onCopy(RelNode rel, RelNode newRel) {
    // do nothing
  }

  protected void dumpRuleAttemptsInfo() {
    if (this.ruleAttemptsListener != null) {
      RULE_ATTEMPTS_LOGGER.debug("Rule Attempts Info for " + this.getClass().getSimpleName());
      RULE_ATTEMPTS_LOGGER.debug(this.ruleAttemptsListener.dump());
    }
  }

  /**
   * Fires a rule, taking care of tracing and listener notification.
   *
   * @param ruleCall description of rule call
   */
  // 触发一条规则，同时负责处理追踪和监听器通知这些辅助工作
  // ruleCall（RelOptRuleCall）：代表一次具体的"规则调用"——包含了匹配到的规则本身、匹配到的具体关系表达式（操作数绑定结果）等上下文信息。
  protected void fireRule(
      RelOptRuleCall ruleCall) {
    // 首先检查取消标志，如果已经被请求取消，直接抛异常终止。
    checkCancel();

    assert ruleCall.getRule().matches(ruleCall);
    if (isRuleExcluded(ruleCall.getRule())) {  //规则是否被过滤
      LOGGER.debug("call#{}: Rule [{}] not fired due to exclusion filter",
          ruleCall.id, ruleCall.getRule());
      return;
    }

    if (ruleCall.isRuleExcluded()) { //确定规则是否被root节点的hint排除
      LOGGER.debug("call#{}: Rule [{}] not fired due to exclusion hint",
          ruleCall.id, ruleCall.getRule());
      return;
    }

    if (listener != null) {
      RelOptListener.RuleAttemptedEvent event =
          new RelOptListener.RuleAttemptedEvent(
              this,
              ruleCall.rel(0),
              ruleCall,
              true);
      listener.ruleAttempted(event);
    }
     //真正在这里使用规则把RelNode转换
    // onMatch 方法（这是 `RelOptRule` 中定义的核心抽象方法，每个具体规则都需要实现这个方法来完成"匹配成功后具体要做什么转换"这一实际逻辑），
    // 传入 `ruleCall` 作为上下文，真正驱动这条规则对匹配到的关系表达式进行转换
    ruleCall.getRule().onMatch(ruleCall);

    if (listener != null) {
      RelOptListener.RuleAttemptedEvent event =
          new RelOptListener.RuleAttemptedEvent(
              this,
              ruleCall.rel(0),
              ruleCall,
              false);
      listener.ruleAttempted(event);
    }
  }

  /**
   * Takes care of tracing and listener notification when a rule's
   * transformation is applied.
   *
   * @param ruleCall description of rule call
   * @param newRel   result of transformation
   * @param before   true before registration of new rel; false after
   */
  // 当某条规则的转换结果被应用时，负责处理追踪和监听器通知
  // 当某条规则成功产生了一个新的转换结果（newRel，即规则应用后生成的新关系表达式）时，通知监听器这一事件。
  protected void notifyTransformation(
      // 本次规则调用的上下文。
      RelOptRuleCall ruleCall,
      // 规则转换产生的新关系表达式结果。
      RelNode newRel,
      // true 表示"这个新关系表达式在被正式注册到优化器内部结构之前"，
      // false 表示"注册之后"（这与优化器内部的具体实现相关，比如 VolcanoPlanner 可能需要把新产生的关系表达式加入自己的等价类结构中，
      // 这个注册动作本身可能耗费一定处理，所以分为"注册前/后"两个时机分别通知，方便外部监听者精确了解处理进度）
      boolean before) {
    if (listener != null) {
      RelOptListener.RuleProductionEvent event =
          new RelOptListener.RuleProductionEvent(
              this,
              newRel,
              ruleCall,
              before);
      listener.ruleProductionSucceeded(event);
    }
  }

  /**
   * Takes care of tracing and listener notification when a rel is chosen as
   * part of the final plan.
   *
   * @param rel chosen rel
   */
  // 当某个关系表达式被选中作为最终执行计划的一部分时，负责处理追踪和监听器通知。
  protected void notifyChosen(RelNode rel) {
    LOGGER.debug("For final plan, using {}", rel);

    if (listener != null) {
      RelOptListener.RelChosenEvent event =
          new RelOptListener.RelChosenEvent(
              this,
              rel);
      listener.relChosen(event);
    }
  }

  /**
   * Takes care of tracing and listener notification when a rel equivalence is
   * detected.
   *
   * @param rel chosen rel
   */
  // 当检测到某个关系表达式与某个等价类存在等价关系时，负责处理追踪和监听器通知。
  // 当优化器发现某个关系表达式 rel 属于某个"等价类"（equivalenceClass，代表一组在逻辑上等价、可以互相替换的关系表达式集合，这是基于代价的优化器（如 Volcano）中的核心概念）时，通知监听器。
  protected void notifyEquivalence(
      RelNode rel,
      Object equivalenceClass,
      boolean physical) {
    if (listener != null) {
      RelOptListener.RelEquivalenceEvent event =
          new RelOptListener.RelEquivalenceEvent(
              this,
              rel,
              equivalenceClass,
              physical);
      listener.relEquivalenceFound(event);
    }
  }

  /**
   * Takes care of tracing and listener notification when a rel is discarded.
   *
   * @param rel Discarded rel
   */
  // 当某个关系表达式被丢弃时，负责处理追踪和监听器通知。
  protected void notifyDiscard(RelNode rel) {
    if (listener != null) {
      RelOptListener.RelDiscardedEvent event =
          new RelOptListener.RelDiscardedEvent(
              this,
              rel);
      listener.relDiscarded(event);
    }
  }

  @Pure
  public @Nullable RelOptListener getListener() {
    return listener;
  }

  /** Returns sub-classes of relational expression. */
  // 从内部维护的 classes 集合中，筛选出所有属于给定类型 clazz 的子类（包括 clazz 自身，视具体判断逻辑而定）。
  // 这个方法通常被规则匹配机制使用——某条规则的操作数可能指定要匹配"某个抽象类型及其所有具体子类"，
  // 这时就需要通过这个方法找出当前优化器已知的、真正属于这个类型体系下的所有具体子类。
  public Iterable<Class<? extends RelNode>> subClasses(
      final Class<? extends RelNode> clazz) {
    return Util.filter(classes, c -> {
      // RelSubset must be exact type, not subclass
      if (c == RelSubset.class) {  //RelSubset必须精确匹配
        return c == clazz;
      }
      return clazz.isAssignableFrom(c);  //c是class的子类，clazz是c的父类
    });
  }

  /** Listener for counting the attempts of each rule. Only enabled under DEBUG level.*/
  // 实现了 RelOptListener 接口，专门用于在调试模式下统计每条规则被调用的次数以及累计耗费的时间，最终可以通过 dump() 方法生成一份格式化的统计报告。
  private static class RuleAttemptsListener implements RelOptListener {
    private long beforeTimestamp;
    private Map<String, Pair<Long, Long>> ruleAttempts;

    RuleAttemptsListener() {
      ruleAttempts = new HashMap<>();
    }

    @Override public void relEquivalenceFound(RelEquivalenceEvent event) {
    }

    @Override public void ruleAttempted(RuleAttemptedEvent event) {
      if (event.isBefore()) {
        this.beforeTimestamp = System.nanoTime();
      } else {
        long elapsed = (System.nanoTime() - this.beforeTimestamp) / 1000;
        String rule = event.getRuleCall().getRule().toString();
        if (ruleAttempts.containsKey(rule)) {
          Pair<Long, Long> p = ruleAttempts.get(rule);
          ruleAttempts.put(rule, Pair.of(p.left + 1, p.right + elapsed));
        } else {
          ruleAttempts.put(rule, Pair.of(1L,  elapsed));
        }
      }
    }

    @Override public void ruleProductionSucceeded(RuleProductionEvent event) {
    }

    @Override public void relDiscarded(RelDiscardedEvent event) {
    }

    @Override public void relChosen(RelChosenEvent event) {
    }

    public String dump() {
      // Sort rules by number of attempts descending, then by rule elapsed time descending,
      // then by rule name ascending.
      List<Map.Entry<String, Pair<Long, Long>>> list =
          new ArrayList<>(this.ruleAttempts.entrySet());
      Collections.sort(list,
          (left, right) -> {
            int res = right.getValue().left.compareTo(left.getValue().left);
            if (res == 0) {
              res = right.getValue().right.compareTo(left.getValue().right);
            }
            if (res == 0) {
              res = left.getKey().compareTo(right.getKey());
            }
            return res;
          });

      // Print out rule attempts and time
      StringBuilder sb = new StringBuilder();
      sb.append(String
          .format(Locale.ROOT, "%n%-60s%20s%20s%n", "Rules", "Attempts", "Time (us)"));
      NumberFormat usFormat = NumberFormat.getNumberInstance(Locale.US);
      long totalAttempts = 0;
      long totalTime = 0;
      for (Map.Entry<String, Pair<Long, Long>> entry : list) {
        sb.append(
            String.format(Locale.ROOT, "%-60s%20s%20s%n",
                entry.getKey(),
                usFormat.format(entry.getValue().left),
                usFormat.format(entry.getValue().right)));
        totalAttempts += entry.getValue().left;
        totalTime += entry.getValue().right;
      }
      sb.append(
          String.format(Locale.ROOT, "%-60s%20s%20s%n",
              "* Total",
              usFormat.format(totalAttempts),
              usFormat.format(totalTime)));

      return sb.toString();
    }
  }
}
