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
 * Operand决定规则是否能应用到特定的表达式
 * <p>For example, the rule to pull a filter up from the left side of a join
 * takes operands: <code>Join(Filter, Any)</code>.
 *
 * <p>Note that <code>children</code> means different things if it is empty or
 * it is <code>null</code>: <code>Join(Filter <b>()</b>, Any)</code> means
 * that, to match the rule, <code>Filter</code> must have no operands.
 */
public class RelOptRuleOperand {
  //~ Instance fields --------------------------------------------------------

  private @Nullable RelOptRuleOperand parent; //父节点
  private @NotOnlyInitialized RelOptRule rule; //规则
  private final Predicate<RelNode> predicate;

  // REVIEW jvs 29-Aug-2004: some of these are Volcano-specific and should be
  // factored out
  public int @MonotonicNonNull [] solveOrder; //它通常用于定义在应用优化规则时，操作数的优先级或处理顺序。较低的 solveOrder 值意味着这个操作数在优化过程中会被优先处理
  public int ordinalInParent;  //指明当前操作数在父节点的子节点中的位置
  public int ordinalInRule; ///每个规则可能包含多个操作数，ordinalInRule 用于唯一标识当前操作数在规则中的位置
  public final @Nullable RelTrait trait; //特征
  private final Class<? extends RelNode> clazz; //要匹配的关系节点
  private final ImmutableList<RelOptRuleOperand> children; //子操作数

  /**
   * Whether child operands can be matched in any order.
   */
  public final RelOptRuleOperandChildPolicy childPolicy; //决定子节点的数量

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

  /** 返回关系表达式是否匹配该操作数
   * Returns whether a relational expression matches this operand. It must be
   * of the right class and trait.主要判断rule的操作数是否匹配
   */
  public boolean matches(RelNode rel) {
    if (!clazz.isInstance(rel)) {  //类型一致
      return false;
    }
    if ((trait != null) && !rel.getTraitSet().contains(trait)) { //包含特征
      return false;
    }
    return predicate.test(rel); //谓词测试
  }
}
