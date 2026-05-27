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

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.initialization.qual.NotOnlyInitialized;
import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Operand that determines whether a {@link RelOptRule}
 * can be applied to a particular expression.
 * <p>For example, the rule to pull a filter up from the left side of a join
 * takes operands: <code>Join(Filter, Any)</code>.
 *
 * <p>Note that <code>children</code> means different things if it is empty or
 * it is <code>null</code>: <code>Join(Filter <b>()</b>, Any)</code> means
 * that, to match the rule, <code>Filter</code> must have no operands.
 */
// RelOptRuleOperand（关系优化规则操作数）是实现 基于规则优化（Rule-Based Optimization） 的基石之一
// 在 Calcite 中，一个优化规则（RelOptRule）想要应用到当前的逻辑算子树（关系表达式树 RelNode）上，必须先完成模式匹配（Pattern Matching）。RelOptRuleOperand 就是用来定义这个匹配模式的树状骨架节点。
// 核心作用
// 定义匹配树的结构：优化规则的匹配条件通常不是单一的算术节点，而是一个局部树状结构。例如，规则“将 Filter 提到 Join 上层”需要匹配的模式是 Join(Filter, Any)。这个模式就是由三个嵌套的 RelOptRuleOperand 节点（Join 节点作为根，Filter 和 Any 作为其 children）组成的。
// 多维度强状态约束：它内部封装了类型（Class）、物理特征（Trait）以及自定义谓词（Predicate）三大卡点，只有当实际的关系算子（RelNode）完美通过这三道门槛时，才算匹配成功。
// 驱动优化器执行顺序：它内部携带的序号和解题顺序（solveOrder）能明确告知 Volcano 或 Hep 优化引擎在面对错综复杂的算子树时，“先去匹配哪一个分支，后去检查哪一个叶子”，极大地提高了树状拓扑匹配的效率。

public class RelOptRuleOperand {
  //~ Instance fields --------------------------------------------------------
  // 父操作数指针。指向当前操作数在操作数树中的上级节点。如果是模式的根节点（如 Join），则为 null。
  private @Nullable RelOptRuleOperand parent;
  // 所属优化规则。指向当前操作数注册并服务于哪一个具体的优化规则（RelOptRule）。
  private @NotOnlyInitialized RelOptRule rule;
  // 用户自定义匹配谓词。提供深度的动态业务过滤逻辑（例如：限制匹配的 Filter 算子其过滤条件必须包含等值判断）。
  private final Predicate<RelNode> predicate;

  // REVIEW jvs 29-Aug-2004: some of these are Volcano-specific and should be
  // factored out
  // 优化引擎解题/匹配顺序。Volcano 优化器专属，定义了多叉树操作数被评估匹配的先后优先级，通常较低的值会优先执行匹配尝试。
  public int @MonotonicNonNull [] solveOrder;
  // 在父算子中的孩子索引序号。标明当前操作数属于父节点的第几个子节点（从 0 开始）。
  public int ordinalInParent;
  // 在整个规则中的唯一平铺序号。规则在扁平化存储所有操作数时，用于唯一标识和快速索引当前操作数。
  public int ordinalInRule;
  // 期望匹配的物理特征（如排序、分布式分布等）。为 null 时代表不限制特征。
  public final @Nullable RelTrait trait;
  // 期望匹配的关系表达式类型占位符。例如 Project.class、Filter.class，用于反射和类型校验。
  private final Class<? extends RelNode> clazz;
  // 不可变的子操作数列表。
  // 代表当前匹配模式下层的子树结构。
  private final ImmutableList<RelOptRuleOperand> children;

  /**
   * Whether child operands can be matched in any order.
   */
  // 子节点匹配策略（数量与顺序约束）。
  // 枚举值，决定子节点的匹配行为。如：ANY（匹配任意子节点）、LEAF（必须是叶子算子）、UNORDERED（无序匹配）等。
  public final RelOptRuleOperandChildPolicy childPolicy;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates an operand.
   *
   * <p>The {@code childOperands} argument is often populated by calling one
   * of the following methods:
   * {@link RelOptRule#some},
   * {@link RelOptRule#none()},
   * {@link RelOptRule#any},
   * {@link RelOptRule#unordered},
   * See {@link org.apache.calcite.plan.RelOptRuleOperandChildren} for more
   * details.
   *
   * @param clazz    Class of relational expression to match (must not be null)
   * @param trait    Trait to match, or null to match any trait
   * @param predicate Predicate to apply to relational expression
   * @param children Child operands
   *
   * @deprecated Use
   * {@link RelOptRule#operand(Class, RelOptRuleOperandChildren)} or one of its
   * overloaded methods.
   */
  @Deprecated // to be removed before 2.0; see [CALCITE-1166]
  protected <R extends RelNode> RelOptRuleOperand(
      Class<R> clazz, //要匹配的关系节点
      RelTrait trait,  //要匹配的特征
      Predicate<? super R> predicate, //判断谓词
      RelOptRuleOperandChildren children) { //要匹配的子节点
    this(clazz, trait, predicate, children.policy, children.operands);
  }

  /** Private constructor.
   *
   * <p>Do not call from outside package, and do not create a sub-class.
   *
   * <p>The other constructor is deprecated; when it is removed, make fields
   * {@link #parent}, {@link #ordinalInParent} and {@link #solveOrder} final,
   * and add constructor parameters for them. See
   * <a href="https://issues.apache.org/jira/browse/CALCITE-1166">[CALCITE-1166]
   * Disallow sub-classes of RelOptRuleOperand</a>. */
  @SuppressWarnings({"initialization.fields.uninitialized",
      "initialization.invalid.field.write.initialized"})
  <R extends RelNode> RelOptRuleOperand(
      Class<R> clazz,
      @Nullable RelTrait trait,
      Predicate<? super R> predicate,
      RelOptRuleOperandChildPolicy childPolicy,
      ImmutableList<RelOptRuleOperand> children) {
    assert clazz != null;
    switch (childPolicy) {
    case ANY:
      break;
    case LEAF:
      assert children.size() == 0;  //叶子节点，则子节点数量为0
      break;
    case UNORDERED:
      assert children.size() == 1; //只有一个子节点，则没有顺序
      break;
    default:
      assert children.size() > 0;
    }
    this.childPolicy = childPolicy;
    this.clazz = Objects.requireNonNull(clazz, "clazz");
    this.trait = trait;
    //noinspection unchecked
    this.predicate = Objects.requireNonNull((Predicate) predicate);
    this.children = children;
    for (RelOptRuleOperand child : this.children) {
      assert child.parent == null : "cannot re-use operands";
      child.parent = this;
    }
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Returns the parent operand.
   * 返回父操作数
   * @return parent operand
   */
  public @Nullable RelOptRuleOperand getParent() {
    return parent;
  }

  /**
   * Sets the parent operand.
   * 设置父操作数
   * @param parent Parent operand
   */
  public void setParent(@Nullable RelOptRuleOperand parent) {
    this.parent = parent;
  }

  /**
   * Returns the rule this operand belongs to.
   * 返回要匹配的规则
   * @return containing rule
   */
  public RelOptRule getRule() {
    return rule;
  }

  /**
   * Sets the rule this operand belongs to.
   *
   * @param rule containing rule
   */
  @SuppressWarnings("initialization.invalid.field.write.initialized")
  public void setRule(@UnknownInitialization RelOptRule rule) {
    this.rule = rule;
  }

  @Override public int hashCode() {
    return Objects.hash(clazz, trait, children);
  }

  @Override public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RelOptRuleOperand)) {
      return false;
    }
    RelOptRuleOperand that = (RelOptRuleOperand) obj;

    return (this.clazz == that.clazz)
        && Objects.equals(this.trait, that.trait)
        && this.children.equals(that.children);
  }

  /**
   * <b>FOR DEBUG ONLY.</b>
   *
   * <p>To facilitate IDE shows the operand description in the debugger,
   * returns the root operand description, but highlight current
   * operand's matched class with '*' in the description.
   *
   * <p>e.g. The following are examples of rule operand description for
   * the operands that match with {@code LogicalFilter}.
   *
   * <ul>
   * <li>SemiJoinRule:project: Project(Join(*RelNode*, Aggregate))</li>
   * <li>ProjectFilterTransposeRule: LogicalProject(*LogicalFilter*)</li>
   * <li>FilterProjectTransposeRule: *Filter*(Project)</li>
   * <li>ReduceExpressionsRule(Filter): *LogicalFilter*</li>
   * <li>PruneEmptyJoin(right): Join(*RelNode*, Values)</li>
   * </ul>
   *
   * @see #describeIt(RelOptRuleOperand)
   */
  @Override public String toString() {
    RelOptRuleOperand root = this;
    while (root.parent != null) {
      root = root.parent;
    }
    StringBuilder s = root.describeIt(this);
    return s.toString();
  }

  /**
   * Returns this rule operand description, and highlight the operand's
   * class name with '*' if {@code that} operand equals current operand.
   *
   * @param that The rule operand that needs to be highlighted
   * @return The string builder that describes this rule operand
   * @see #toString()
   */
  private StringBuilder describeIt(RelOptRuleOperand that) {
    StringBuilder s = new StringBuilder();
    if (parent == null) {
      s.append(rule).append(": ");
    }
    if (this == that) {
      s.append('*');
    }
    s.append(clazz.getSimpleName());
    if (this == that) {
      s.append('*');
    }
    if (children != null && !children.isEmpty()) {
      s.append('(');
      boolean first = true;
      for (RelOptRuleOperand child : children) {
        if (!first) {
          s.append(", ");
        }
        s.append(child.describeIt(that));
        first = false;
      }
      s.append(')');
    }
    return s;
  }

  /** 返回该操作匹配的关系节点
   * Returns relational expression class matched by this operand.
   */
  public Class<? extends RelNode> getMatchedClass() {
    return clazz;
  }

  /**
   * Returns the child operands.
   * 返回子操作数
   * @return child operands
   */
  public List<RelOptRuleOperand> getChildOperands() {
    return children;
  }

  /**
   * Returns whether a relational expression matches this operand. It must be
   * of the right class and trait.
   */
  // 整个 Calcite 优化器进行 模式匹配（Pattern Matching） 的核心咽喉要道。
  // 当优化引擎（如 VolcanoPlanner 或 HepPlanner）在遍历复杂的逻辑算子树（RelNode 树）时，
  // 它需要知道当前的算子是否符合某个优化规则（RelOptRule）的胃口。这个方法通过 三道由浅入深的关卡，以极高的效率对算子进行层层过滤。
  // 当前类只负责“单点体检”，而“纵深树状匹配”的控制权被交给了上层的优化器引擎（Planner Call）。
  public boolean matches(RelNode rel) {
    // 第一道关卡：核心类型（Class）匹配
    if (!clazz.isInstance(rel)) {
      return false;
    }
    // 第二道关卡：物理特征（Trait）匹配
    if ((trait != null) && !rel.getTraitSet().contains(trait)) {
      return false;
    }
    // 第三道关卡：动态业务谓词（Predicate）匹配
    return predicate.test(rel);
  }
}
