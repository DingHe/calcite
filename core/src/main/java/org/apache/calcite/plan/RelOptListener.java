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

import org.apache.calcite.rel.RelNode;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.EventListener;
import java.util.EventObject;

/**
 * RelOptListener defines an interface for listening to events which occur
 * during the optimization process.
 */
// 在优化器（如 VolcanoPlanner 或 HepPlanner）对关系代数树（RelNode）进行空间搜索、规则匹配、等价类划分、修剪垃圾节点以及最终选择最优路径的整个执行生命周期中，提供全套的事件回调钩子。
// 调试与可视化：捕获优化器底层的每一步规则交替（Rule Fire），用来打印日志或生成图形化的 Memo 结构树，方便开发人员诊断为什么某条 SQL 没有走索引或物化视图。
// 性能监控：统计各个优化规则尝试（Attempt）和产出（Produce）的时间消耗，找出导致规划超时的“慢规则”。
// 指标埋点：追踪最终执行计划中挑选了哪些物理算子。
public interface RelOptListener extends EventListener {
  //~ Methods ----------------------------------------------------------------

  /**
   * Notifies this listener that a relational expression has been registered
   * with a particular equivalence class after an equivalence has been either
   * detected or asserted. Equivalence classes may be either logical (all
   * expressions which yield the same result set) or physical (all expressions
   * which yield the same result set with a particular calling convention).
   *
   * @param event details about the event
   */
  // 当优化器发现或断言了一个新的关系表达式（RelNode）与某个已有的等价类（Equivalence Class / Set）等价时触发。
  // 等价类可以是逻辑等价（返回相同的数据集），也可以是物理等价（返回相同的数据但带有特定的物理特征，如 Convention）
  void relEquivalenceFound(RelEquivalenceEvent event);

  /**
   * Notifies this listener that an optimizer rule is being applied to a
   * particular relational expression. This rule is called twice; once before
   * the rule is invoked, and once after. Note that the rel attribute of the
   * event is always the old expression.
   *
   * @param event details about the event
   */
  // 当优化器尝试调用/触发某条优化规则（RelOptRule）去匹配某个节点时触发。
  // 在一条规则的生命周期里会被调用两次：一次在规则被真正调用执行之前（Before），一次在执行完毕之后（After）。可通过事件内的属性区分。
  void ruleAttempted(RuleAttemptedEvent event);

  /**
   * Notifies this listener that an optimizer rule has been successfully
   * applied to a particular relational expression, resulting in a new
   * equivalent expression (relEquivalenceFound will also be called unless the
   * new expression is identical to an existing one). This rule is called
   * twice; once before registration of the new rel, and once after. Note that
   * the rel attribute of the event is always the new expression; to get the
   * old expression, use event.getRuleCall().rels[0].
   *
   * @param event details about the event
   */
  // 当优化规则成功应用并产生了一个全新的等价关系表达式时触发。
  // 也就是说，规则没有空手而归。该方法也会被调用两次：一次在新节点向优化器注册（Register）之前，一次在注册完成之后。
  void ruleProductionSucceeded(RuleProductionEvent event);

  /**
   * Notifies this listener that a relational expression is no longer of
   * interest to the planner.
   *
   * @param event details about the event
   */
  // 当一个关系表达式节点被优化器“废弃”或“修剪”（Prune）时触发。说明该节点已经不再被优化器关注，将来不会在其上触发任何规则，以此减少搜索空间。
  void relDiscarded(RelDiscardedEvent event);

  /**
   * Notifies this listener that a relational expression has been chosen as
   * part of the final implementation of the query plan. After the plan is
   * complete, this is called one more time with null for the rel.
   *
   * @param event details about the event
   */
  // 当优化器在最终阶段决定挑选（Choose）该关系节点作为最终物理执行计划的一部分时触发。
  // 在整个执行计划全部敲定后，该方法还会被额外调用最后一次，此时事件内的 rel 属性为 null，标志着构建完全结束。
  void relChosen(RelChosenEvent event);

  //~ Inner Classes ----------------------------------------------------------

  /**
   * Event class for abstract event dealing with a relational expression. The
   * source of an event is typically the RelOptPlanner which initiated it.
   */
  // 涉及关系表达式节点的抽象事件基类。
  abstract class RelEvent extends EventObject {
    // 触发或关联该事件的核心关系表达式节点。
    private final @Nullable RelNode rel;

    protected RelEvent(Object eventSource, @Nullable RelNode rel) {
      super(eventSource);
      this.rel = rel;
    }

    public @Nullable RelNode getRel() {
      return rel;
    }
  }

  /** Event indicating that a relational expression has been chosen. */
  // 当某个节点被选为最终计划的一部分时所对应的事件实体。
  class RelChosenEvent extends RelEvent {
    public RelChosenEvent(Object eventSource, @Nullable RelNode rel) {
      super(eventSource, rel);
    }
  }

  /** Event indicating that a relational expression has been found to
   * be equivalent to an equivalence class. */
  // 当节点找到等价类时所对应的事件实体。
  class RelEquivalenceEvent extends RelEvent {
    // 该节点加入的等价类对象标识（在 Volcano 中通常是 RelSet）。
    private final Object equivalenceClass;
    // 标记这是否是一个物理层面的等价类。
    private final boolean isPhysical;

    public RelEquivalenceEvent(
        Object eventSource,
        RelNode rel,
        Object equivalenceClass,
        boolean isPhysical) {
      super(eventSource, rel);
      this.equivalenceClass = equivalenceClass;
      this.isPhysical = isPhysical;
    }

    public Object getEquivalenceClass() {
      return equivalenceClass;
    }

    public boolean isPhysical() {
      return isPhysical;
    }
  }

  /** Event indicating that a relational expression has been discarded. */
  // 某个节点被优化器裁剪、废弃时所对应的事件实体。
  class RelDiscardedEvent extends RelEvent {
    public RelDiscardedEvent(Object eventSource, RelNode rel) {
      super(eventSource, rel);
    }
  }

  /** Event indicating that a planner rule has fired. */
  // 所有与优化器规则（Rule）触发相关事件的抽象基类。
  abstract class RuleEvent extends RelEvent {
    // 包含了当前规则调用的上下文详细元数据（例如被匹配到的算子节点数组 rels 等）。
    private final RelOptRuleCall ruleCall;

    protected RuleEvent(
        Object eventSource,
        RelNode rel,
        RelOptRuleCall ruleCall) {
      super(eventSource, rel);
      this.ruleCall = ruleCall;
    }

    public RelOptRuleCall getRuleCall() {
      return ruleCall;
    }
  }

  /** Event indicating that a planner rule has been attempted. */
  class RuleAttemptedEvent extends RuleEvent {
    private final boolean before;

    public RuleAttemptedEvent(
        Object eventSource,
        RelNode rel,
        RelOptRuleCall ruleCall,
        boolean before) {
      super(eventSource, rel, ruleCall);
      this.before = before;
    }

    public boolean isBefore() {
      return before;
    }
  }

  /** Event indicating that a planner rule has produced a result. */
  class RuleProductionEvent extends RuleAttemptedEvent {
    public RuleProductionEvent(
        Object eventSource,
        RelNode rel,
        RelOptRuleCall ruleCall,
        boolean before) {
      super(eventSource, rel, ruleCall, before);
    }
  }
}
