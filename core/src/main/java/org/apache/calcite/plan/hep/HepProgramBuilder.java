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

import org.apache.calcite.plan.CommonRelSubExprRule;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * HepProgramBuilder creates instances of {@link HepProgram}.
 */
// HepProgramBuilder 采用了经典的建造者模式（Builder Pattern）。它的核心作用是提供一套流畅的链式 API（Fluent API），供开发人员自由组合优化规则和流控指令，从而组装出一个不可变的 HepProgram（优化剧本）。
// 由于 HepProgram 内部的指令流是完全不可变的，直接通过构造函数实例化会极其繁琐且容易出错（尤其是在处理分组、嵌套子程序时）。HepProgramBuilder 充当了“编排草稿纸”的角色，它允许用户像写脚本一样，通过 .addRuleInstance().addMatchOrder().build() 的方式，安全、直观地定制启发式优化的执行流水线。

public class HepProgramBuilder {
  //~ Instance fields --------------------------------------------------------
  // 指令流草稿箱。用于在构建过程中，顺序暂存用户通过各种 addXXX 方法添加进来的 HepInstruction（指令）对象。当最终调用 build() 时，该列表的内容会被打包深拷贝进不可变的 HepProgram 中。
  private final List<HepInstruction> instructions = new ArrayList<>();

  /** If a group is under construction, ordinal of the first instruction in the
   * group; otherwise -1. */
  // 规则分组构建状态机指针。
  // 如果其值为 -1，说明当前处于常规指令编排状态。如果其值 >= 0，说明用户刚刚调用了 addGroupBegin() 开启了一个规则组，且该值记录了该组在 instructions 列表中的起始索引（位置）。它用于辅助校验和结算分组。
  private int group = -1;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a new HepProgramBuilder with an initially empty program. The
   * program under construction has an initial match order of
   * {@link HepMatchOrder#DEPTH_FIRST}, and an initial match limit of
   * {@link HepProgram#MATCH_UNTIL_FIXPOINT}.
   */
  public HepProgramBuilder() {
  }

  //~ Methods ----------------------------------------------------------------
  // 清理建造者状态。
  private void clear() {
    instructions.clear();
    group = -1;
  }

  /**
   * Adds an instruction to attempt to match any rules of a given class. The
   * order in which the rules within a class will be attempted is arbitrary,
   * so if more control is needed, use addRuleInstance instead.
   *
   * <p>Note that when this method is used, it is also necessary to add the
   * actual rule objects of interest to the planner via
   * {@link RelOptPlanner#addRule}. If the planner does not have any
   * rules of the given class, this instruction is a nop.
   *
   * <p>TODO: support classification via rule annotations.
   *
   * @param ruleClass class of rules to fire, e.g. ConverterRule.class
   */
  // 向程序中添加一条“按类匹配规则”的指令。
  // 传入一个继承自 RelOptRule 的类类型（如 ConverterRule.class）。运行时优化器会过滤系统内所有该类型的规则并打包执行。
  public <R extends RelOptRule> HepProgramBuilder addRuleClass(
      Class<R> ruleClass) {
    return addInstruction(new HepInstruction.RuleClass(ruleClass));
  }

  /**
   * Adds an instruction to attempt to match any rules in a given collection.
   * The order in which the rules within a collection will be attempted is
   * arbitrary, so if more control is needed, use addRuleInstance instead. The
   * collection can be "live" in the sense that not all rule instances need to
   * have been added to it at the time this method is called. The collection
   * contents are reevaluated for each execution of the program.
   *
   * <p>Note that when this method is used, it is NOT necessary to add the
   * rules to the planner via {@link RelOptPlanner#addRule}; the instances
   * supplied here will be used. However, adding the rules to the planner
   * redundantly is good form since other planners may require it.
   *
   * @param rules collection of rules to fire
   */
  // 向程序中添加一条“按集合匹配规则”的指令。
  // 传入一个现成的规则集合，优化器会顺序尝试该集合内的所有规则。
  public HepProgramBuilder addRuleCollection(Collection<RelOptRule> rules) {
    return addInstruction(new HepInstruction.RuleCollection(rules));
  }

  /**
   * Adds an instruction to attempt to match a specific rule object.
   *
   * <p>Note that when this method is used, it is NOT necessary to add the
   * rule to the planner via {@link RelOptPlanner#addRule}; the instance
   * supplied here will be used. However, adding the rule to the planner
   * redundantly is good form since other planners may require it.
   *
   * @param rule rule to fire
   */
  // 向程序中添加一条“精准触发单条规则”的指令。
  // 最常用的方法，精确指定运行某一条具体的优化规则实例。
  public HepProgramBuilder addRuleInstance(RelOptRule rule) {
    return addInstruction(new HepInstruction.RuleInstance(rule));
  }

  /**
   * Adds an instruction to attempt to match a specific rule identified by its
   * unique description.
   *
   * <p>Note that when this method is used, it is necessary to also add the
   * rule object of interest to the planner via {@link RelOptPlanner#addRule}.
   * This allows for some decoupling between optimizers and plugins: the
   * optimizer only knows about rule descriptions, while the plugins supply
   * the actual instances. If the planner does not have a rule matching the
   * description, this instruction is a nop.
   *
   * @param ruleDescription description of rule to fire
   */
  // 向程序中添加一条“根据描述文本查找并触发规则”的指令。
  public HepProgramBuilder addRuleByDescription(String ruleDescription) {
    return addInstruction(
        new HepInstruction.RuleLookup(ruleDescription));
  }

  /**
   * Adds an instruction to begin a group of rules. All subsequent rules added
   * (until the next endRuleGroup) will be collected into the group rather
   * than firing individually. After addGroupBegin has been called, only
   * addRuleXXX methods may be called until the next addGroupEnd.
   */
  // 开启一个规则分组（Group）。
  // 首先通过 checkArgument(group < 0) 强校验，防止分组嵌套（Hep 不允许组内再套组）。然后，将当前 instructions.size() 的位置赋给 group 变量，并在该位置放入一个 Placeholder（占位符指令）。
  public HepProgramBuilder addGroupBegin() {
    checkArgument(group < 0);
    group = instructions.size();
    return addInstruction(new HepInstruction.Placeholder());
  }

  /**
   * Adds an instruction to end a group of rules, firing the group
   * collectively. The order in which the rules within a group will be
   * attempted is arbitrary. Match order and limit applies to the group as a
   * whole.
   */
  // 结束当前的规则分组，并进行结算闭环
  // 首先强校验 group >= 0（必须先开组才能关组）。随后 new 出真正的终点指令 EndGroup。
  // 接着，找到当初开组时占坑的索引位置 group，将那里的 Placeholder 替换为真正的、绑定了终点的 BeginGroup(endGroup) 指令。最后把 group 拨回 -1，并在末尾追加 endGroup。
  public HepProgramBuilder addGroupEnd() {
    checkArgument(group >= 0);
    final HepInstruction.EndGroup endGroup = new HepInstruction.EndGroup();
    instructions.set(group, new HepInstruction.BeginGroup(endGroup));
    group = -1;
    return addInstruction(endGroup);
  }

  /**
   * Adds an instruction to attempt to match instances of
   * {@link org.apache.calcite.rel.convert.ConverterRule},
   * but only where a conversion is actually required.
   *
   * @param guaranteed if true, use only guaranteed converters; if false, use
   *                   only non-guaranteed converters
   */
  // 添加一条“执行物理特征转换（ConverterRule）”的指令。
  // guaranteed 为 true 时只使用强保证成功的转换器；为 false 时则使用非强保证的转换器。带有 group < 0 校验，不允许在分组内调用。
  public HepProgramBuilder addConverters(boolean guaranteed) {
    checkArgument(group < 0);
    return addInstruction(new HepInstruction.ConverterRules(guaranteed));
  }

  /**
   * Adds an instruction to attempt to match instances of
   * {@link CommonRelSubExprRule}, but only in cases where vertices have more
   * than one parent.
   */
  // 添加一条“寻找并提取公共子表达式”的指令。
  public HepProgramBuilder addCommonRelSubExprInstruction() {
    checkArgument(group < 0);
    return addInstruction(new HepInstruction.CommonRelSubExprRules());
  }

  /**
   * Adds an instruction to change the order of pattern matching for
   * subsequent instructions. The new order will take effect for the rest of
   * the program (not counting subprograms) or until another match order
   * instruction is encountered.
   *
   * @param order new match direction to set
   */
  // 添加一条“修改图遍历匹配顺序”的指令。
  // 传入新的遍历拓扑方向（如自顶向下、深度优先）。该指令被触发后，后续的所有指令都会遵循这一新顺序，直到再次被修改。
  public HepProgramBuilder addMatchOrder(HepMatchOrder order) {
    checkArgument(group < 0);
    return addInstruction(new HepInstruction.MatchOrder(order));
  }

  /**
   * Adds an instruction to limit the number of pattern matches for subsequent
   * instructions. The limit will take effect for the rest of the program (not
   * counting subprograms) or until another limit instruction is encountered.
   *
   * @param limit limit to set; use {@link HepProgram#MATCH_UNTIL_FIXPOINT} to
   *              remove limit
   */
  // 添加一条“修改匹配次数上限”的指令。
  // 传入一个整数限制后续指令的最大匹配重写次数，防止规则交替死循环。
  public HepProgramBuilder addMatchLimit(int limit) {
    checkArgument(group < 0);
    return addInstruction(new HepInstruction.MatchLimit(limit));
  }

  /**
   * Adds an instruction to execute a subprogram. Note that this is different
   * from adding the instructions from the subprogram individually. When added
   * as a subprogram, the sequence will execute repeatedly until a fixpoint is
   * reached, whereas when the instructions are added individually, the
   * sequence will only execute once (with a separate fixpoint for each
   * instruction).
   *
   * <p>The subprogram has its own state for match order and limit
   * (initialized to the defaults every time the subprogram is executed) and
   * any changes it makes to those settings do not affect the parent program.
   *
   * @param program subProgram to execute
   */
  // 嵌套一个完整的子程序（Subprogram）。
  public HepProgramBuilder addSubprogram(HepProgram program) {
    checkArgument(group < 0);
    return addInstruction(new HepInstruction.SubProgram(program));
  }
  // 统一的指令入队入口。
  // 将传入的指令对象追加到 instructions 列表中，并返回 this（当前 Builder 实例）。这是实现链式调用的底层核心。
  private HepProgramBuilder addInstruction(HepInstruction instruction) {
    instructions.add(instruction);
    return this;
  }

  /**
   * Returns the constructed program, clearing the state of this program
   * builder as a side-effect.
   *
   * @return immutable program
   */
  public HepProgram build() {
    checkArgument(group < 0);
    HepProgram program = new HepProgram(instructions);
    clear();
    return program;
  }
}
