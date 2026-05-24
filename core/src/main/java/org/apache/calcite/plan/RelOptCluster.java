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
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.hint.HintStrategyTable;
import org.apache.calcite.rel.metadata.DefaultRelMetadataProvider;
import org.apache.calcite.rel.metadata.JaninoRelMetadataProvider;
import org.apache.calcite.rel.metadata.MetadataFactory;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.metadata.RelMetadataQueryBase;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;

import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

/**
 * An environment for related relational expressions during the
 * optimization of a query.
 */
// RelOptCluster 意为 “关系表达式优化集群”。但在日常研发中，你可以直接把它理解为 “单次 SQL 查询优化周期的全局上下文大容器（Context）”。
// 在 Calcite 的多阶段设计里，一棵关系代数树的生命周期极其漫长：要经历从逻辑表达式、基于成本的火山模型改写（Volcano Optimization）、再到目标引擎的物理落地。
// 如果让每个算子独立维护底层工厂或优化策略，会导致数据格式混乱、内存碎片化、甚至在相关子查询（Correlated Subquery）中发生变量错位。
// RelOptCluster 完美解决了这个痛点，它的三大核心价值如下：
// 全局资产共享纽带：它作为一个强状态持有者，统一看管了类型工厂、表达式构建器、特征管理器、提示词策略等高频底层组件。所有属于当前 SQL 周期的 RelNode 算子，都能通过同一个 cluster 对象复用相同的工具指针。
// 动态元数据代价查询的“主调度室”：它是优化过程中最重要的代价评估大脑。通过在集群中绑定、缓存和使失效（Invalidate）元数据查询提供者（RelMetadataQuery），优化器得以为每一次形变算子快速打分（CBO 估算行数、CPU/IO 成本）
// 防撞车与变量冲突隔离带：它在内部通过原子锁机制，统一发放整个集群周期内绝对不重复的关联变量 ID（CorrelationId），确保复杂子查询嵌套在深度变换、拉平（Unnest）时，其血缘变量指针稳固无损。
public class RelOptCluster {
  //~ Instance fields --------------------------------------------------------
  // 专门负责在整个优化上下文内浇筑、创建、派生标准 SQL 或异构数仓的关系型强类型对象（如 BIGINT, VARCHAR(30)）。
  // 保证集群内部所有算子使用的类型定义具有唯一的物理引用（Pointer）。
  private final RelDataTypeFactory typeFactory;
  // 当前集群正在使用的优化器算法实例（如 VolcanoPlanner 或 HepPlanner）
  private final RelOptPlanner planner;
  // 一个线程安全的、自增的整型计数器。
  // 专门用来集中发放集群唯一的关联子查询 ID。
  private final AtomicInteger nextCorrel;
  // 用来记录当前的 CorrelationId 字符串到底绑定、指向哪一个底层的物理关系表达式节点（RelNode），以便进行子查询解耦改写。
  private final Map<String, RelNode> mapCorrelToRel;
  // 早已废弃的内部追溯属性。
  // 早期用来标记当前优化的初始表达式根节点，默认初始化为一个特殊的字面量字串 "?"。
  private RexNode originalExpression;
  // 行级行列表达式构建工厂
  // 负责在集群内创建所有非关系算子的“原子行表达式表达式”（如过滤条件、投影转换算子中的算术、比较、逻辑节点 RexNode）
  private final RexBuilder rexBuilder;
  // 底层提供行数估算、选择率估算、基数统计的算法插件供应商，默认绑定 Calcite 内置的标准机制 DefaultRelMetadataProvider.INSTANCE
  private RelMetadataProvider metadataProvider;
  @Deprecated // to be removed before 2.0
  // 已废弃）。
  // 早期通过元数据提供商动态生产代价对象的中间工厂。
  private MetadataFactory metadataFactory;
  // SQL 提示（Hint）传播策略路由表
  // 专门用来看管用户在 SQL 中强行注入的特种暗示控制机制（如 /*+ BROADCAST(t1) */）。它定义了这些 Hint 在后续算子形变、分裂、重组过程中该如何进行分裂和精准透传。
  private @Nullable HintStrategyTable hintStrategies;
  // 当前优化器支持的、所有物理/逻辑特征定义的纯空白底盘（如没有排序、没有任何分布式约定），用来作为后续物理衍生节点进行链式 .replace(trait) 特征赋予时的原始始祖。
  private final RelTraitSet emptyTraitSet;
  // 当前正在服务的元数据代价查询对象。因为元数据评估耗能极大，该字段充当了一级内存缓存。
  private @Nullable RelMetadataQuery mq;
  // 用来在当前的缓存 mq 失效（Invalidate）后，由优化器调用并重新浇筑出一个充满活力的全新的 RelMetadataQuery 干净实例。
  private Supplier<RelMetadataQuery> mqSupplier;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a cluster.
   */
  @Deprecated // to be removed before 2.0
  RelOptCluster(
      RelOptQuery query,
      RelOptPlanner planner,
      RelDataTypeFactory typeFactory,
      RexBuilder rexBuilder) {
    this(planner, typeFactory, rexBuilder, query.nextCorrel,
        query.mapCorrelToRel);
  }

  /**
   * Creates a cluster.
   *
   * <p>For use only from {@link #create} and {@link RelOptQuery}.
   */
  RelOptCluster(RelOptPlanner planner, RelDataTypeFactory typeFactory,
      RexBuilder rexBuilder, AtomicInteger nextCorrel,
      Map<String, RelNode> mapCorrelToRel) {
    this.nextCorrel = nextCorrel;
    this.mapCorrelToRel = mapCorrelToRel;
    this.planner = Objects.requireNonNull(planner, "planner");
    this.typeFactory = Objects.requireNonNull(typeFactory, "typeFactory");
    this.rexBuilder = rexBuilder;
    this.originalExpression = rexBuilder.makeLiteral("?");

    // set up a default rel metadata provider,
    // giving the planner first crack at everything
    setMetadataProvider(DefaultRelMetadataProvider.INSTANCE);
    setMetadataQuerySupplier(RelMetadataQuery::instance);
    this.emptyTraitSet = planner.emptyTraitSet();
    assert emptyTraitSet.size() == planner.getRelTraitDefs().size();
  }

  /** Creates a cluster. */
  public static RelOptCluster create(RelOptPlanner planner,
      RexBuilder rexBuilder) {
    return new RelOptCluster(planner, rexBuilder.getTypeFactory(),
        rexBuilder, new AtomicInteger(0), new HashMap<>());
  }

  //~ Methods ----------------------------------------------------------------

  @Deprecated // to be removed before 2.0
  public RelOptQuery getQuery() {
    return new RelOptQuery(castNonNull(planner), nextCorrel, mapCorrelToRel);
  }

  @Deprecated // to be removed before 2.0
  public RexNode getOriginalExpression() {
    return originalExpression;
  }

  @Deprecated // to be removed before 2.0
  public void setOriginalExpression(RexNode originalExpression) {
    this.originalExpression = originalExpression;
  }

  public RelOptPlanner getPlanner() {
    return planner;
  }

  public RelDataTypeFactory getTypeFactory() {
    return typeFactory;
  }

  public RexBuilder getRexBuilder() {
    return rexBuilder;
  }

  public @Nullable RelMetadataProvider getMetadataProvider() {
    return metadataProvider;
  }

  /**
   * Overrides the default metadata provider for this cluster.
   *
   * @param metadataProvider custom provider
   */
  @EnsuresNonNull({"this.metadataProvider", "this.metadataFactory"})
  @SuppressWarnings("deprecation")
  public void setMetadataProvider(
      @UnknownInitialization RelOptCluster this,
      RelMetadataProvider metadataProvider) {
    this.metadataProvider = metadataProvider;
    this.metadataFactory =
        new org.apache.calcite.rel.metadata.MetadataFactoryImpl(metadataProvider);
    // Wrap the metadata provider as a JaninoRelMetadataProvider
    // and set it to the ThreadLocal,
    // JaninoRelMetadataProvider is required by the RelMetadataQuery.
    RelMetadataQueryBase.THREAD_PROVIDERS
        .set(JaninoRelMetadataProvider.of(metadataProvider));
  }

  /**
   * Returns a {@link MetadataFactory}.
   *
   * @deprecated Use {@link #getMetadataQuery()}.
   */
  @Deprecated // to be removed before 2.0
  public MetadataFactory getMetadataFactory() {
    return metadataFactory;
  }

  /**
   * Sets up the customized {@link RelMetadataQuery} instance supplier that to
   * use during rule planning.
   *
   * <p>Note that the {@code mqSupplier} should return
   * a fresh new {@link RelMetadataQuery} instance because the instance would be
   * cached in this cluster, and we may invalidate and re-generate it
   * for each {@link RelOptRuleCall} cycle.
   */
  @EnsuresNonNull("this.mqSupplier")
  public void setMetadataQuerySupplier(
      @UnknownInitialization RelOptCluster this,
      Supplier<RelMetadataQuery> mqSupplier) {
    this.mqSupplier = mqSupplier;
  }

  /**
   * Returns the current RelMetadataQuery.
   *
   * <p>This method might be changed or moved in future.
   * If you have a {@link RelOptRuleCall} available,
   * for example if you are in a {@link RelOptRule#onMatch(RelOptRuleCall)}
   * method, then use {@link RelOptRuleCall#getMetadataQuery()} instead. */
  public RelMetadataQuery getMetadataQuery() {
    if (mq == null) {
      mq = castNonNull(mqSupplier).get();
    }
    return mq;
  }

  /**
   * Returns the supplier of RelMetadataQuery.
   */
  public Supplier<RelMetadataQuery> getMetadataQuerySupplier() {
    return this.mqSupplier;
  }

  /**
   * Should be called whenever the current {@link RelMetadataQuery} becomes
   * invalid. Typically invoked from {@link RelOptRuleCall#transformTo}.
   */
  public void invalidateMetadataQuery() {
    mq = null;
  }

  /**
   * Sets up the hint propagation strategies to be used during rule planning.
   *
   * <p>Use <code>RelOptNode.getCluster().getHintStrategies()</code> to fetch
   * the hint strategies.
   *
   * <p>Note that this method is only for internal use; the cluster {@code hintStrategies}
   * would be always set up with the instance configured by
   * {@link org.apache.calcite.sql2rel.SqlToRelConverter.Config}.
   *
   * @param hintStrategies The specified hint strategies to override the default one(empty)
   */
  public void setHintStrategies(HintStrategyTable hintStrategies) {
    Objects.requireNonNull(hintStrategies, "hintStrategies");
    this.hintStrategies = hintStrategies;
  }

  /**
   * Returns the hint strategies of this cluster. It is immutable during the whole planning phrase.
   */
  public HintStrategyTable getHintStrategies() {
    if (this.hintStrategies == null) {
      this.hintStrategies = HintStrategyTable.EMPTY;
    }
    return this.hintStrategies;
  }

  /**
   * Constructs a new id for a correlating variable. It is unique within the
   * whole query.
   */
  public CorrelationId createCorrel() {
    return new CorrelationId(nextCorrel.getAndIncrement());
  }

  /** Returns the default trait set for this cluster. */
  public RelTraitSet traitSet() {
    return emptyTraitSet;
  }

  // CHECKSTYLE: IGNORE 2
  /** @deprecated For {@code traitSetOf(t1, t2)},
   * use {@link #traitSet}().replace(t1).replace(t2). */
  @Deprecated // to be removed before 2.0
  public RelTraitSet traitSetOf(RelTrait... traits) {
    RelTraitSet traitSet = emptyTraitSet;
    for (RelTrait trait : traits) {
      traitSet = traitSet.replace(trait);
    }
    return traitSet;
  }

  public RelTraitSet traitSetOf(RelTrait trait) {
    return emptyTraitSet.replace(trait);
  }
}
