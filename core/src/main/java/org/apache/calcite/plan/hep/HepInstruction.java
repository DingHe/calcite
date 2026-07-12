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

import org.apache.calcite.plan.RelOptRule;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

import static java.util.Objects.requireNonNull;

/**
 * HepInstruction represents one instruction in a HepProgram. The actual
 * instruction set is defined here via inner classes; if these grow too big,
 * they should be moved out to top-level classes.
 */
// HepInstruction 是 Apache Calcite 中基于 Hep（Heuristic，启发式）优化模型的核心类之一
// 什么是 HepPlanner / HepProgram
// Calcite 提供了两种主流的优化器实现模型：
// 基于代价的优化（Volcano/Cascades 模型）：通过穷举/半穷举搜索并结合代价估算，找到全局最优（或接近最优）的执行计划。
// 基于启发式规则的优化（Hep 模型）：HepPlanner 按照一个预先定义好的、有明确执行顺序的"程序"（HepProgram），依次执行一系列"指令"，每条指令规定了"在这一步应该做什么"（比如应用某一类规则、应用某个具体规则、切换匹配顺序等），
// 这种模式更类似于传统编译器中"按照固定的 pass 顺序依次优化"的思路，而不是像 Volcano 那样进行代价驱动的搜索。
// HepProgram 就是由一系列 HepInstruction（指令）组成的"程序"，HepPlanner 会按顺序逐条执行这些指令。
// HepInstruction 本身是一个抽象基类，它定义了"指令"这个概念的统一契约，而具体每一种指令的类型（比如"执行某一类规则""执行某个具体规则""设置匹配顺序"等）都以内部类的形式定义在它内部。
// 设计模式：指令（Instruction）与状态（State）分离
// 这个类还体现了一个重要的设计模式：每种指令类型都自带一个对应的"状态"（State）内部类。HepInstruction 本身及其子类代表的是静态的、不可变的"指令定义"（相当于程序代码本身），
// 而每次真正执行这条指令时，需要一个运行时可变的"状态"对象（相当于程序运行时的栈帧/上下文），这个状态对象在指令执行完毕后就可以被丢弃。
// 这种"指令定义与运行时状态分离"的设计，使得同一条指令定义可以被多次重复执行（比如在循环中反复执行同一个程序），而不会因为状态残留而互相干扰。
abstract class HepInstruction {
  //~ Methods ----------------------------------------------------------------

  /** Creates runtime state for this instruction.
   *
   * <p>The state is mutable, knows how to execute the instruction, and is
   * discarded after this execution. See {@link HepState}.
   *
   * @param px Preparation context; the state should copy from the context
   * all information that it will need to execute
   *
   * @return Initialized state
   */
  // HepInstruction 抽象基类中唯一定义的抽象方法，
  // 是所有具体指令类型都必须实现的核心契约——根据给定的"准备上下文"（PrepareContext），为当前这条指令创建一个对应的、可变的运行时状态对象（HepState）
  abstract HepState prepare(PrepareContext px);

  //~ Inner Classes ----------------------------------------------------------

  /** Instruction that executes all rules of a given class. */
  // 让优化器去执行属于某一个特定 Java 类（或其子类）的所有规则
  static class RuleClass extends HepInstruction {
    // 目标规则的类类型。
    final Class<? extends RelOptRule> ruleClass;

    <R extends RelOptRule> RuleClass(Class<R> ruleClass) {
      this.ruleClass = requireNonNull(ruleClass, "ruleClass");
    }

    @Override State prepare(PrepareContext px) {
      return new State(px);
    }

    /** State for a {@link RuleClass} instruction. */
    class State extends HepState {
      /** Actual rule set instantiated during planning by filtering all the
       * planner's rules through {@link #ruleClass}. */
      @Nullable Set<RelOptRule> ruleSet;

      State(PrepareContext px) {
        super(px);
      }

      @Override void execute() {
        planner.executeRuleClass(RuleClass.this, this);
      }
    }
  }

  /** Instruction that executes all rules in a given collection. */
  // 规则集合执行指令
  // 让优化器去顺序执行一个预先封装好的规则列表。
  static class RuleCollection extends HepInstruction {
    /** Collection of rules to apply. */
    final List<RelOptRule> rules;

    RuleCollection(Collection<RelOptRule> rules) {
      this.rules = ImmutableList.copyOf(rules);
    }

    @Override State prepare(PrepareContext px) {
      return new State(px);
    }

    /** State for a {@link RuleCollection} instruction. */
    class State extends HepState {
      State(PrepareContext px) {
        super(px);
      }

      @Override void execute() {
        planner.executeRuleCollection(RuleCollection.this, this);
      }
    }
  }

  /** Instruction that executes converter rules. */
  // 特征转换规则执行指令
  // 让优化器去执行物理特征转换规则（如将逻辑算子转化为特定的物理算子）
  static class ConverterRules extends HepInstruction {
    // 标记该转换是否是强制/保证必须成功的。
    final boolean guaranteed;

    ConverterRules(boolean guaranteed) {
      this.guaranteed = guaranteed;
    }

    @Override State prepare(PrepareContext px) {
      return new State(px);
    }

    /** State for a {@link ConverterRules} instruction. */
    class State extends HepState {
      /** Actual rule set instantiated during planning by filtering all the
       * planner's rules, looking for the desired converters. */
      // 运行时筛选出来的转换规则集合。
      @MonotonicNonNull Set<RelOptRule> ruleSet;

      State(PrepareContext px) {
        super(px);
      }

      @Override void execute() {
        planner.executeConverterRules(ConverterRules.this, this);
      }
    }
  }

  /** Instruction that finds common relational sub-expressions. */
  // 公共子表达式抽取指令
  // 用于在关系代数树中寻找并提取等价的公共子表达式（Common Sub-expressions），实现算子复用。
  static class CommonRelSubExprRules extends HepInstruction {
    @Override State prepare(PrepareContext px) {
      return new State(px);
    }

    /** State for a {@link CommonRelSubExprRules} instruction. */
    class State extends HepState {
      @Nullable Set<RelOptRule> ruleSet;

      State(PrepareContext px) {
        super(px);
      }

      @Override void execute() {
        planner.executeCommonRelSubExprRules(CommonRelSubExprRules.this, this);
      }
    }
  }

  /** Instruction that executes a given rule. */
  // 单条规则实例执行指令
  static class RuleInstance extends HepInstruction {
    /** Explicitly specified rule. */
    final RelOptRule rule;

    RuleInstance(RelOptRule rule) {
      this.rule = requireNonNull(rule, "rule");
    }

    @Override State prepare(PrepareContext px) {
      return new State(px);
    }

    /** State for a {@link RuleInstance} instruction. */
    class State extends HepState {
      State(PrepareContext px) {
        super(px);
      }

      @Override void execute() {
        planner.executeRuleInstance(RuleInstance.this, this);
      }
    }
  }

  /** Instruction that executes a rule that is looked up by description. */
  // 则描述查找执行指令
  // 根据规则的字符串描述（Description）动态去优化器中寻找对应的规则并执行。
  static class RuleLookup extends HepInstruction {
    /** Description to look for. */
    final String ruleDescription;

    RuleLookup(String ruleDescription) {
      this.ruleDescription = requireNonNull(ruleDescription, "ruleDescription");
    }

    @Override State prepare(PrepareContext px) {
      return new State(px);
    }

    /** State for a {@link RuleLookup} instruction. */
    class State extends HepState {
      /** Rule looked up by planner from description. */
      @Nullable RelOptRule rule;

      State(PrepareContext px) {
        super(px);
      }

      @Override void init() {
        // Look up anew each run.
        rule = null;
      }

      @Override void execute() {
        planner.executeRuleLookup(RuleLookup.this, this);
      }
    }
  }

  /** Instruction that sets match order. */
  // 改变匹配顺序指令
  // 控制优化器在遍历关系代数树时的图遍历顺序（如：从顶向下 TOP_DOWN 或底向上 BOTTOM_UP）。
  static class MatchOrder extends HepInstruction {
    final HepMatchOrder order;

    MatchOrder(HepMatchOrder order) {
      this.order = requireNonNull(order, "order");
    }

    @Override State prepare(PrepareContext px) {
      return new State(px);
    }

    /** State for a {@link MatchOrder} instruction. */
    class State extends HepState {
      State(PrepareContext px) {
        super(px);
      }

      @Override void execute() {
        planner.executeMatchOrder(MatchOrder.this, this);
      }
    }
  }

  /** Instruction that sets match limit. */
  // 匹配次数限制指令
  static class MatchLimit extends HepInstruction {
    final int limit;

    MatchLimit(int limit) {
      this.limit = limit;
    }

    @Override State prepare(PrepareContext px) {
      return new State(px);
    }

    /** State for a {@link MatchLimit} instruction. */
    class State extends HepState {
      State(PrepareContext px) {
        super(px);
      }

      @Override void execute() {
        planner.executeMatchLimit(MatchLimit.this, this);
      }
    }
  }

  /** Instruction that executes a sub-program. */
  // 子程序嵌套指令
  // 允许在一个 Hep 程序内部嵌套、并调用执行另一个完整的 HepProgram 子程序。
  static class SubProgram extends HepInstruction {
    final HepProgram subProgram;

    SubProgram(HepProgram subProgram) {
      this.subProgram = requireNonNull(subProgram, "subProgram");
    }

    @Override HepProgram.State prepare(PrepareContext px) {
      return subProgram.prepare(px);
    }

    /** State for a {@link SubProgram} instruction. */
    class State extends HepState {
      final HepProgram.State subProgramState;

      State(PrepareContext px) {
        super(px);
        subProgramState = subProgram.prepare(px);
      }

      @Override void init() {
        subProgramState.init();
      }

      @Override void execute() {
        planner.executeSubProgram(SubProgram.this, this);
      }
    }
  }

  /** Instruction that begins a group. */
  // BeginGroup 及其内部类 State 扮演着规则分组（Group）“入场券与导航员”的角色。
  // 核心作用是在 Hep 程序（HepProgram）的指令流中标记一个规则组（Group）的起点，并建立与该组终点（EndGroup）的运行时关联通道。
  // 在启发式优化中，分组内的规则需要被打包合并处理。因此，当优化器推进到规则组的起点时：
  // 确立边界：BeginGroup 告诉优化器状态机：“注意，从现在开始，接下来的所有规则都属于同一个逻辑组，不要单独执行它们，先收集起来。”
  // 握手联动：它通过持有并绑定对应的 EndGroup.State，让整个优化流程知晓当前组的终点以及对应的规则容器在哪里。
  static class BeginGroup extends HepInstruction {
    // 持有该分组对应的静态终点指令（EndGroup）的引用。
    final EndGroup endGroup;
    // 接收与之配对的 endGroup 实例，并通过 requireNonNull 进行强校验，确保任何一个分组起点都必须有明确的终点，不允许出现“孤儿起点”。
    BeginGroup(EndGroup endGroup) {
      this.endGroup = requireNonNull(endGroup, "endGroup");
    }

    @Override State prepare(PrepareContext px) {
      return new State(px);
    }

    /** State for a {@link BeginGroup} instruction. */
    // 负责在运行期间将控制流引导进正确的分组逻辑中：
    class State extends HepState {
      // 持有当前分组对应的运行时终点状态对象（EndGroup.State）的引用
      final HepInstruction.EndGroup.State endGroup;

      State(PrepareContext px) {
        super(px);
        this.endGroup = requireNonNull(px.endGroupState, "endGroupState");
      }

      @Override void execute() {
        planner.executeBeginGroup(BeginGroup.this, this);
      }
    }
  }

  /** Placeholder instruction that marks the beginning of a group under
   * construction. */
  static class Placeholder extends HepInstruction {
    @Override HepState prepare(PrepareContext px) {
      throw new UnsupportedOperationException();
    }
  }

  /** Instruction that ends a group. */
  // 规则分组（Group）允许将多个不同的优化规则组合在一起，作为一个逻辑整体联合调度、统一迭代。EndGroup 及其内部类 State 正是用于定义和管理这种分组终点的核心组件。
  // 核心作用是在 Hep 程序（HepProgram）的指令流中标记一个规则组（Group）的终点，并联合其内部状态类统一收拢组内所有的优化规则。
  // Hep 优化器中，规则分组的生命周期通常如下：
  // 收集阶段（collecting = true）：优化器从 BeginGroup 开始顺序向下扫描，
  // 期间遇到的所有规则（如 RuleInstance 等指令）都不会单独执行，而是作为原材料被源源不断地添加、填充到 EndGroup.State 的规则集合（ruleSet）中。
  // 结算与联合调度阶段：当优化器到达 EndGroup 时，说明当前组内的规则已经全部收集完毕。此时，EndGroup 会关闭收集开关（collecting = false），
  // 并把最终聚合好的 ruleSet 交付给 HepPlanner，由优化器对这个规则集进行整体的循环迭代，直到其达到定点（收敛）。
  static class EndGroup extends HepInstruction {
    // 重写了基类 HepInstruction 的抽象方法。
    // 当 Hep 程序启动运行、自顶向下初始化整个指令流时，会调用此方法。它负责 new 出一个可变的、线程隔离的运行时状态对象 EndGroup.State 并返回。
    @Override State prepare(PrepareContext px) {
      return new State(px);
    }

    /** State for a {@link EndGroup} instruction. */
    // 保留了分组在运行期间的核心动态账本和规则容器：
    class State extends HepState {
      /** Actual rule set instantiated during planning by collecting grouped
       * rules. */
      // 组内规则的实体容器
      // 当优化器处于该分组的扫描范围内时，所有被夹在 BeginGroup 和 EndGroup 之间的子指令所携带的 RelOptRule（优化规则），都会被依次 add 到这个集合里。它是最终被联合调度的规则总和。
      final Set<RelOptRule> ruleSet = new HashSet<>();
      // 状态机开关（流控标记）。
      // 初始状态或重置后为 true，代表当前正处于“规则收集”阶段。此时优化器在组内遇到规则不会触发执行，只做收集。
      // 当优化器第一次执行到 EndGroup 节点并开始对规则集进行联合调度时，或者收集完毕后，该值通常会被置为 false。
      boolean collecting = true;

      State(PrepareContext px) {
        super(px);
      }

      @Override void execute() {
        planner.executeEndGroup(EndGroup.this, this);
      }

      @Override void init() {
        collecting = true;
      }
    }
  }

  /** All the information that might be necessary to initialize {@link HepState}
   * for a particular instruction. */
  // PrepareContext（准备上下文）的主要作用是解决指令（HepInstruction）在孵化其运行时状态（HepState）时的“信息不对称”问题。
  // PrepareContext 就像一个随行百宝箱，里面安全地封装了当前正在工作的优化器实例、主程序状态以及分组状态。每到一个指令节点，指令就可以从这个百宝箱里掏出自己初始化所需的数据。
  static class PrepareContext {
    // 当前正在调度并执行该程序的 HepPlanner 优化器实例
    // 运行时状态（HepState）必须拿到 planner 的引用，因为后面状态在调用 execute() 时，需要反向调用 planner.executeRuleClass(...) 等方法把控制权交回给优化器。
    final HepPlanner planner;
    // 当前上层主程序（HepProgram）的运行时状态。
    // 子指令可能会嵌套（如 SubProgram 指令），子指令在初始化时可能需要感知或关联父层级程序的运行状态（例如获取当前全局的匹配次数、流控标记等）。
    final HepProgram.State programState;
    // 当前最近一个配对的组终点（EndGroup）的运行时状态。
    // 专门服务于 BeginGroup 指令。当 BeginGroup 状态初始化时，它必须知道它的终点 EndGroup 在哪、长什么样，以便它们俩在运行时能形成闭环、协同收集并执行组内规则。
    final EndGroup.State endGroupState;

    private PrepareContext(HepPlanner planner,
        HepProgram.State programState, EndGroup.State endGroupState) {
      this.planner = planner;
      this.programState = programState;
      this.endGroupState = endGroupState;
    }

    static PrepareContext create(HepPlanner planner) {
      return new PrepareContext(planner, castNonNull(null), castNonNull(null));
    }

    PrepareContext withProgramState(HepProgram.State programState) {
      return new PrepareContext(planner, programState, endGroupState);
    }

    PrepareContext withEndGroupState(EndGroup.State endGroupState) {
      return new PrepareContext(planner, programState, endGroupState);
    }
  }
}
