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

/** Able to execute an instruction or program, and contains all mutable state
 * for that instruction.
 *
 * <p>The goal is that programs are re-entrant - they can be used by more than
 * one thread at a time. We achieve this by making instructions and programs
 * immutable. All mutable state is held in the state objects.
 *
 * <p>State objects are allocated, just before the program is executed, by
 * calling {@link HepInstruction#prepare(HepInstruction.PrepareContext)} on the
 * program and recursively on all of its instructions. */
// HepState 是 Hep 指令运行时可变状态（Mutable State）的抽象基类
// 在多线程环境下，Calcite 提出了一个核心设计目标：希望优化程序（HepProgram）和指令（HepInstruction）是“可重入（Re-entrant）”的——即同一个优化脚本可以同时被多个线程并发使用。
// HepProgram 和 HepInstruction 被设计为完全不可变（Immutable）的静态蓝图。
// 所有在程序执行过程中产生的可变状态、临时变量、运行期上下文，被全部剥离出来，封装在 HepState 的子类中。
// 当优化器准备执行程序时，会通过 prepare 递归地为所有指令分配专属的 HepState 实例。这样，即使多个线程跑同一个优化脚本，它们也只是各自持有独立的 HepState，从而保证了线程安全。
abstract class HepState {
  // 当前正在驱动该指令执行的 HepPlanner 优化器实例的引用。
  // 指令在执行（execute()）时，不能凭空进行，它必须调用优化器底层的引擎算法。
  final HepPlanner planner;
  // 当前指令所属的上层主程序（HepProgram）的运行时状态引用
  // 为当前的指令状态提供一个全局的父级作用域（Parent Scope）。
  // 这样，局部指令就可以通过 programState 随时去获取或修改全局的流控信息、总匹配次数上限，或者判断是否需要提前终止整个程序。
  final HepProgram.State programState;

  HepState(HepInstruction.PrepareContext px) {
    this.planner = px.planner;
    this.programState = px.programState;
  }

  /** Executes the instruction. */
  // 核心执行体，触发当前指令的实际运算。
  // 抽象方法，留给 HepInstruction 的各个子类状态去实现。例如，如果是单条规则指令的状态（RuleInstance.State），
  // 它的 execute() 实现就是去触发这一条规则；如果是组结束指令的状态（EndGroup.State），它的 execute() 就是去循环调度组内的所有规则
  abstract void execute();

  /** Re-initializes the state. (The state was initialized when it was created
   * via {@link HepInstruction#prepare}.) */
  // 重新初始化（Re-initialize）当前的状态。
  // 虽然状态在通过 prepare 被创建时已经进行了初次初始化，但有些指令（例如循环执行的子程序或规则组）在运行过程中可能会被反复调用。为了防止上一次运行残留的脏数据（如旧的规则缓存、收集标记）污染下一次运行，优化器会在每轮循环开始前调用 init()。
  void init() {
  }
}
