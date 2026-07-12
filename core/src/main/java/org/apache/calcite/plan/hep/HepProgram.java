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

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.apache.calcite.linq4j.Nullness.castNonNull;
import static org.apache.calcite.linq4j.Nullness.castToInitialized;

/**
 * HepProgram specifies the order in which rules should be attempted by
 * {@link HepPlanner}. Use {@link HepProgramBuilder} to create a new
 * instance of HepProgram.
 *
 * <p>Note that the structure of a program is immutable, but the planner uses it
 * as read/write during planning, so a program can only be in use by a single
 * planner at a time.
 */
// HepProgram 继承自 HepInstruction（说明它本身也可以作为一个整体嵌套在别的程序中成为“子程序”）。
// 它的核心作用是显式地规定优化规则（RelOptRule）和控制流指令在 HepPlanner 中被尝试和触发的绝对顺序。
// HepProgram 内部的指令序列（instructions）在被 HepProgramBuilder 构建出来之后，就是完全不可变（Immutable）的。它是一个静态的结构蓝图。
// 当外部调用 prepare 时，它会作为“总指挥”，将自己内部包含的所有子指令列表传递给其内部状态类 HepProgram.State，由该状态类级联孵化出所有指令的运行时状态，并最终交付给 HepPlanner 按顺序逐行执行。
public class HepProgram extends HepInstruction {
  //~ Static fields/initializers ---------------------------------------------

  /**
   * Symbolic constant for matching until no more matches occur.
   */
  // 代表“匹配直到收敛/定点”的符号常量（其值为 Integer.MAX_VALUE）。
  public static final int MATCH_UNTIL_FIXPOINT = Integer.MAX_VALUE;

  //~ Instance fields --------------------------------------------------------
  // 存储当前程序所包含的所有顺序指令流。
  final ImmutableList<HepInstruction> instructions;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a new empty HepProgram. The program has an initial match order of
   * {@link org.apache.calcite.plan.hep.HepMatchOrder#DEPTH_FIRST}, and an initial
   * match limit of {@link #MATCH_UNTIL_FIXPOINT}.
   */
  HepProgram(List<HepInstruction> instructions) {
    this.instructions = ImmutableList.copyOf(instructions);
  }
  // 对外暴露的唯一构建入口。
  public static HepProgramBuilder builder() {
    return new HepProgramBuilder();
  }

  //~ Methods ----------------------------------------------------------------

  @Override State prepare(PrepareContext px) {
    return new State(px, instructions);
  }

  /** State for a {@link HepProgram} instruction. */
  // 核心作用是封装一个完整 HepProgram（优化程序）在执行期间的所有动态控制参数和子指令状态。
  // 由于 HepProgram 是不可变的规则脚本蓝图，当它被交付给优化器运行前，必须通过这个 State 类将其转化为一个可运行的实例。
  // 关键任务包括：
  // 级联初始化：在构造时，负责遍历主程序中定义的所有静态指令（List<HepInstruction>），挨个调用它们的 prepare 方法，从而孵化出全套的子指令状态列表（instructionStates）。
  // 图流控管理：实时记录当前程序运行的遍历顺序（matchOrder）、剩余匹配次数上限（matchLimit）以及当前是否处于某个规则分组的流控中（group）。
  // 破除环形依赖（核心亮点）：利用延迟回调机制，精妙地解决了 BeginGroup 和 EndGroup 在初始化时互相强引用的时序矛盾。
  class State extends HepState {
    // 按顺序存放了当前程序中所有子指令在运行期的状态对象（HepState）。当程序运行时，优化器会循环遍历这个列表来依次触发 execute()。
    final ImmutableList<HepState> instructionStates;
    // 当前的匹配次数限制。在运行期间，某些指令（如 MatchLimit 指令）会动态修改这个值。如果减到 0，程序将提前终止。默认是 MATCH_UNTIL_FIXPOINT（即持续匹配直到收敛/定点，不再有规则能匹配成功为止）。
    int matchLimit = MATCH_UNTIL_FIXPOINT;
    // 当前图遍历的匹配顺序。
    // 有些指令（如 MatchOrder 指令）会在中途修改它，告诉优化器接下来应该改用深度优先（DEPTH_FIRST）、广度优先（BREADTH_FIRST）还是自顶向下等顺序去扫描关系代数树。
    HepMatchOrder matchOrder = HepMatchOrder.DEPTH_FIRST;
    // 指向当前正在生效的“规则组终点状态”的指针。
    // 如果为 null，说明当前处于普通指令流执行阶段；如果不为 null，说明优化器当前正在联合调度一个组（Group）内的所有规则。
    HepInstruction.EndGroup.@Nullable State group;
    // 构造函数是该类的灵魂，
    // 负责把静态的 instructions 列表翻译成动态的 states 列表。由于 SQL 优化允许将规则“分组合并执行”，代码中引入了高明的两阶段延迟回调技术：
    // 为什么需要延迟回调？
    // 当遇到 BeginGroup 指令时，它的状态初始化强依赖对应的 EndGroup 的状态。
    // 但是，由于列表是顺序遍历的，此时 EndGroup 还没被扫描到，它的状态根本不存在。
    State(PrepareContext px, List<HepInstruction> instructions) {
      super(px);
      // 环境升级：首先通过 px.withProgramState(this) 把当前程序状态自己融入上下文 px2 中，向下传递。
      final PrepareContext px2 = px.withProgramState(castToInitialized(this));
      final List<HepState> states = new ArrayList<>();
      final Map<HepInstruction, Consumer<HepState>> actions = new HashMap<>();
      for (HepInstruction instruction : instructions) {
        final HepState state;
        if (instruction instanceof BeginGroup) {
          // The state of a BeginGroup instruction needs the state of the
          // corresponding EndGroup instruction, which we haven't seen yet.
          // Temporarily put a placeholder State into the list, and add an
          // action to replace that State. The action will be invoked when we
          // reach the EndGroup.
          // 遇到 BeginGroup 时：
          final int i = states.size();
          // 创建一个未执行的悬挂动作（Lambda 表达式 Consumer），以 BeginGroup.endGroup 作为 Key 存入 actions 映射表中。
          // 这个 Lambda 的意思是：“未来当有人把 EndGroup 的状态交给我时，我会回过头来把刚才占坑的位置，真正替换为 BeginGroup 准备好的状态”。
          actions.put(((BeginGroup) instruction).endGroup, state2 ->
              states.set(i,
                  instruction.prepare(
                      px2.withEndGroupState((EndGroup.State) state2))));
          // 在 states 列表中先丢入一个 null（通过 castNonNull(null)）占坑。
          state = castNonNull(null);
        } else {
          // 遇到 EndGroup 时：
          // 首先它属于普通分支，正常通过 prepare 生成了自己的 EndGroup.State。
          // 紧接着，代码检查 if (actions.containsKey(instruction))，发现刚才 BeginGroup 留下的契约挂钩。
          // 立即执行 actions.get(instruction).accept(state)，把刚出炉的 EndGroup.State 喂给刚才的 Lambda。
          // Lambda 触发，顺藤摸瓜找到当初占坑的索引 i，完美将 BeginGroup 的状态补齐并回填到 states 树中。
          state = instruction.prepare(px2);
          if (actions.containsKey(instruction)) {
            actions.get(instruction).accept(state);
          }
        }
        states.add(state);
      }
      this.instructionStates = ImmutableList.copyOf(states);
    }

    @Override void init() {
      matchLimit = MATCH_UNTIL_FIXPOINT;
      matchOrder = HepMatchOrder.DEPTH_FIRST;
      group = null;
    }
    // 执行触发方法
    // 当外界要求运行这个程序时，它将控制权交回给关联的 HepPlanner 优化器，并把当前的配置和程序蓝图（HepProgram.this）连同自身（this）一起打包投递给优化器内核执行
    @Override void execute() {
      planner.executeProgram(HepProgram.this, this);
    }
    // 状态流控判断方法
    // 制优化器在遍历指令流时，是否应该跳过（Skip）当前的某些编排动作。
    boolean skippingGroup() {
      if (group != null) {
        // Skip if we've already collected the ruleset.
        // 如果正在处理分组，它会去查看终点状态的 group.collecting 标记。
        // 如果在第一轮扫描中，规则组已经把该组内的所有规则全部收集捕获完毕了（collecting 变为了 false），那么在随后的迭代中，这个组内部嵌套的小指令定义就不需要再被重复扫描了，直接跳过它们，返回 true
        return !group.collecting;
      } else {
        // 如果当前没有在处理分组（group == null），绝不跳过，返回 false。
        // Not grouping.
        return false;
      }
    }
  }
}
