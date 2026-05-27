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
package org.apache.calcite.rel.convert;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.RelTrait;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.tools.RelBuilderFactory;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

/**
 * Abstract base class for a rule which converts from one calling convention to
 * another without changing semantics.
 */
// ConverterRule 是 Apache Calcite 优化器框架（特别是基于成本的优化器 VolcanoPlanner）中最核心、使用频率最高的规则基类之一。
// 在分布式大数据引擎和多数据源联邦查询（如跨 JDBC、Spark、Flink 等）的实际开发中，它扮演着“物理特质/执行流派转换器”的角色。
// 核心作用
// 跨物理流派/调用约定转译（Calling Convention Conversion）：
// 在 Calcite 中，Convention（调用约定）代表了算子的物理底层驱动流派。
// 例如，LogicalProject（逻辑算子）不能直接执行，必须被转换为物理算子（如 JdbcProject 或 EnumerableProject）。
// ConverterRule 的核心使命就是将算子从一个物理流派转换到另一个物理流派，而完全不改变其原本的关系代数语义。
// 物理特征对齐（Trait Matching）：
// 除了转换 Convention，它还能转换其他维度的 RelTrait（如物理分布特质 RelDistribution、排序特质 RelCollation）。
// 例如：当一个物理 Join 算子要求其右孩子必须按指定列进行 Hash 分布，而右孩子目前是随机分布时，
// 优化器就会激活一个 ConverterRule，在右孩子上方强行织入一个 Exchange 转换算子，以实现物理特征对齐。
// 基于规则体系的“安全熔断机制”：
//通过继承上一章提到的 ConverterRelOptRuleOperand，该类本身天然免疫同维度物理转换连续叠加引发的 $\mathcal{O}(n^2)$ 级联状态爆炸（无限死循环），确保优化器内部 Memo 空间的健康。
@Value.Enclosing
public abstract class ConverterRule
    extends RelRule<ConverterRule.Config> {
  //~ Instance fields --------------------------------------------------------
  // 该转换规则期望拦截/输入的源头物理特质（例如：输入算子必须属于 Convention.NONE 逻辑特质，或者必须满足某种排序）。
  private final RelTrait inTrait;
  // 该转换规则处理完成后，期望吐出/输出的目标物理特质（例如：输出算子必须满足 EnumerableConvention.INSTANCE）。
  private final RelTrait outTrait;
  // 输出的目标调用约定的便捷快捷字段（Short-cut）。
  // 因为在 90% 以上的工业场景中，转换规则都是在处理 Convention 的对齐和转换。为了避免子类频繁编写臃肿的 (Convention) getOutTrait() 强制类型转换代码，
  // 该字段在构造时如果是 Convention 的实例，就会直接缓存下来供子类就地使用，否则安全解包存为 null。
  protected final Convention out; //输出调用特征

  //~ Constructors -----------------------------------------------------------

  /** Creates a <code>ConverterRule</code>. */
  protected ConverterRule(Config config) {
    super(config);
    this.inTrait = Objects.requireNonNull(config.inTrait());
    this.outTrait = Objects.requireNonNull(config.outTrait());
    //输入特征和输出特征的定义要一致
    // Source and target traits must have same type
    assert inTrait.getTraitDef() == outTrait.getTraitDef();

    // Most sub-classes are concerned with converting one convention to
    // another, and for them, the "out" field is a convenient short-cut.
    this.out =
        outTrait instanceof Convention ? (Convention) outTrait
            : castNonNull(null);
  }

  /**
   * Creates a <code>ConverterRule</code>.
   *
   * @param clazz       Type of relational expression to consider converting
   * @param in          Trait of relational expression to consider converting
   * @param out         Trait which is converted to
   * @param descriptionPrefix Description prefix of rule
   *
   * @deprecated Use {@link #ConverterRule(Config)}
   */
  @Deprecated // to be removed before 2.0
  protected ConverterRule(Class<? extends RelNode> clazz, RelTrait in,
      RelTrait out, String descriptionPrefix) {
    this(Config.INSTANCE
        .withConversion(clazz, in, out, descriptionPrefix));
  }

  @SuppressWarnings("Guava")
  @Deprecated // to be removed before 2.0
  protected <R extends RelNode> ConverterRule(Class<R> clazz,
      com.google.common.base.Predicate<? super R> predicate,
      RelTrait in, RelTrait out, String descriptionPrefix) {
    this(Config.INSTANCE
        .withConversion(clazz, (Predicate<? super R>) predicate::apply,
            in, out, descriptionPrefix));
  }

  /**
   * Creates a <code>ConverterRule</code> with a predicate.
   *
   * @param clazz       Type of relational expression to consider converting
   * @param predicate   Predicate on the relational expression
   * @param in          Trait of relational expression to consider converting
   * @param out         Trait which is converted to
   * @param relBuilderFactory Builder for relational expressions
   * @param descriptionPrefix Description prefix of rule
   *
   * @deprecated Use {@link #ConverterRule(Config)}
   */
  @Deprecated // to be removed before 2.0
  protected <R extends RelNode> ConverterRule(Class<R> clazz,
      Predicate<? super R> predicate, RelTrait in, RelTrait out,
      RelBuilderFactory relBuilderFactory, String descriptionPrefix) {
    this(ImmutableConverterRule.Config.builder()
        .withRelBuilderFactory(relBuilderFactory)
        .build()
        .withConversion(clazz, predicate, in, out, descriptionPrefix));
  }

  @SuppressWarnings("Guava")
  @Deprecated // to be removed before 2.0
  protected <R extends RelNode> ConverterRule(Class<R> clazz,
      com.google.common.base.Predicate<? super R> predicate, RelTrait in,
      RelTrait out, RelBuilderFactory relBuilderFactory, String description) {
    this(clazz, (Predicate<? super R>) predicate::apply, in, out,
        relBuilderFactory, description);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public Convention getOutConvention() {
    return (Convention) outTrait;
  }

  @Override public RelTrait getOutTrait() {
    return outTrait;
  }

  public RelTrait getInTrait() {
    return inTrait;
  }

  public RelTraitDef getTraitDef() {
    return inTrait.getTraitDef();
  }

  private static String createDescription(String descriptionPrefix,
      RelTrait in, RelTrait out) {
    return String.format(Locale.ROOT, "%s(in:%s,out:%s)",
        Objects.toString(descriptionPrefix, "ConverterRule"), in, out);
  }

  /** Converts a relational expression to the target trait(s) of this rule.
   *
   * <p>Returns null if conversion is not possible. */
  // 核心抽象方法，
  // 由业务子类必须实现的物理转译工厂。
  // 接收一个源算子 rel，子类必须在此编写代码，剥离出其逻辑核心，并返回重新包装、对齐了目标特证 outTrait 的全新物理 RelNode
  // （例如：接收一个 LogicalProject，通过 new EnumerableProject(...) 将其物理化返回）。
  // 如果因为某种物理局限或内部属性冲突导致当前节点无法进行特质转译，允许返回 null。
  public abstract @Nullable RelNode convert(RelNode rel);

  /**
   * Returns true if this rule can convert <em>any</em> relational expression
   * of the input convention.
   *
   * <p>The union-to-java converter, for example, is not guaranteed, because
   * it only works on unions.
   * @return {@code true} if this rule can convert <em>any</em> relational
   *   expression
   */
  // 表明当前转换规则是否是“100% 绝对完备的担保转换”。
  public boolean isGuaranteed() {
    return false;
  }
  // 核心执行总入口。
  // 当优化器在 Memo 中发现匹配的算子时，会回调此方法。
  @Override public void onMatch(RelOptRuleCall call) {
    // 提取出触发匹配的第 0 个算子节点 rel。
    RelNode rel = call.rel(0);
    // 执行二次防御性校验：检查该算子当前的 TraitSet 中是否切实包含了规则声明的 inTrait。
    if (rel.getTraitSet().contains(inTrait)) {
      // 如果包含，则直接启动子类实现的 convert(rel) 物理工厂。
      final RelNode converted = convert(rel);
      // 检查生成的 converted 新物理节点是否非空。如果成功吐出了新物理节点，
      // 则调用 call.transformTo(converted) 正式宣布将这个新节点织入 Memo 空间，参与下一轮的 CBO 动态代价估算。
      if (converted != null) {
        call.transformTo(converted);
      }
    }
  }

  //~ Inner Classes ----------------------------------------------------------

  /** Rule configuration. */
  @Value.Immutable(singleton = false)
  public interface Config extends RelRule.Config {
    Config INSTANCE = ImmutableConverterRule.Config.builder()
        .withInTrait(Convention.NONE)  //调用约定默认为NONE
        .withOutTrait(Convention.NONE)
        .withRuleFactory(new Function<Config, ConverterRule>() {
          @Override public ConverterRule apply(final Config config) {
            throw new UnsupportedOperationException("A rule factory must be provided");
          }
        }).build();

    RelTrait inTrait();

    /** Sets {@link #inTrait}. */
    Config withInTrait(RelTrait trait);

    RelTrait outTrait();

    /** Sets {@link #outTrait}. */
    Config withOutTrait(RelTrait trait);
    //输入Config，返回ConverterRule
    Function<Config, ConverterRule> ruleFactory();

    /** Sets {@link #outTrait}. */
    Config withRuleFactory(Function<Config, ConverterRule> factory);

    default <R extends RelNode> Config withConversion(Class<R> clazz,
        Predicate<? super R> predicate, RelTrait in, RelTrait out,
        String descriptionPrefix) {
      return withInTrait(in)
          .withOutTrait(out)
          .withOperandSupplier(b ->
              b.operand(clazz).predicate(predicate).convert(in))
          .withDescription(createDescription(descriptionPrefix, in, out))
          .as(Config.class);
    }

    default Config withConversion(Class<? extends RelNode> clazz, RelTrait in,
        RelTrait out, String descriptionPrefix) {
      return withConversion(clazz, r -> true, in, out, descriptionPrefix);
    }

    @Override default RelOptRule toRule() {
      return toRule(ConverterRule.class);
    }

    default <R extends ConverterRule> R toRule(Class<R> ruleClass) {
      return ruleClass.cast(ruleFactory().apply(this));
    }
  }

}
