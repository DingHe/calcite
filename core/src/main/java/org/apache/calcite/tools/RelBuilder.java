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
package org.apache.calcite.tools;

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.linq4j.function.Experimental;
import org.apache.calcite.plan.Context;
import org.apache.calcite.plan.Contexts;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPredicateList;
import org.apache.calcite.plan.RelOptSamplingParameters;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.ViewExpanders;
import org.apache.calcite.prepare.RelOptTableImpl;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Intersect;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Match;
import org.apache.calcite.rel.core.Minus;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.core.RepeatUnion;
import org.apache.calcite.rel.core.Sample;
import org.apache.calcite.rel.core.Snapshot;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.Spool;
import org.apache.calcite.rel.core.TableFunctionScan;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.core.TableSpool;
import org.apache.calcite.rel.core.Uncollect;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rel.hint.Hintable;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.metadata.RelColumnMapping;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.rules.AggregateRemoveRule;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelDataTypeFieldImpl;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexCallBinding;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexExecutor;
import org.apache.calcite.rex.RexFieldCollation;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexSimplify;
import org.apache.calcite.rex.RexSubQuery;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.rex.RexWindowBound;
import org.apache.calcite.rex.RexWindowBounds;
import org.apache.calcite.rex.RexWindowExclusion;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.runtime.ImmutablePairList;
import org.apache.calcite.runtime.PairList;
import org.apache.calcite.schema.TransientTable;
import org.apache.calcite.schema.impl.ListTransientTable;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlStaticAggFunction;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.SqlWindow;
import org.apache.calcite.sql.fun.SqlCountAggFunction;
import org.apache.calcite.sql.fun.SqlInternalOperators;
import org.apache.calcite.sql.fun.SqlLikeOperator;
import org.apache.calcite.sql.fun.SqlQuantifyOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.TableFunctionReturnTypeInference;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.util.DateString;
import org.apache.calcite.util.Holder;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.ImmutableNullableList;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.NlsString;
import org.apache.calcite.util.Optionality;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;
import org.apache.calcite.util.mapping.Mapping;
import org.apache.calcite.util.mapping.Mappings;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMultiset;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import java.math.BigDecimal;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

import static org.apache.calcite.linq4j.Nullness.castNonNull;
import static org.apache.calcite.rel.rules.AggregateRemoveRule.canFlattenStatic;
import static org.apache.calcite.sql.SqlKind.UNION;
import static org.apache.calcite.util.Static.RESOURCE;
import static org.apache.calcite.util.Util.first;

import static java.util.Objects.requireNonNull;

/**
 * Builder for relational expressions.
 *
 * <p>{@code RelBuilder} does not make possible anything that you could not
 * also accomplish by calling the factory methods of the particular relational
 * expression. But it makes common tasks more straightforward and concise.
 *
 * <p>{@code RelBuilder} uses factories to create relational expressions.
 * By default, it uses the default factories, which create logical relational
 * expressions ({@link LogicalFilter},
 * {@link LogicalProject} and so forth).
 * But you could override those factories so that, say, {@code filter} creates
 * instead a {@code HiveFilter}.
 *
 * <p>It is not thread-safe.
 */
// org.apache.calcite.tools.RelBuilder 是一个出镜率极高、最核心的工具类之一。
// 它采用建造者模式（Builder Pattern）并结合了操作数栈（Stack）的设计思想，专门用于编排、构建和重构关系代数表达式树（RelNode）。
// 在 Calcite 中，直接调用各种物理/逻辑算子的构造函数（例如 new LogicalProject(...), new LogicalFilter(...)）来手工组装一棵算子树是非常痛苦且容易出错的。你需要自己处理复杂的类型推导（RelDataType）、全局宇宙上下文（RelOptCluster）传递以及繁琐的字段索引（RexInputRef）计算。
// RelBuilder 核心解决的就是算子树手工构建复杂、可读性差、容易出错的问题：
// 统一的脚手架接口：它提供了一套极度流畅的 流式 API（Fluent API）。例如，构建一个普通的查询，使用 RelBuilder 只需要一气呵成：
// builder.scan("EMP")
//       .filter(builder.equals(builder.field("DEPTNO"), builder.literal(10)))
//       .project(builder.field("ENAME"))
//       .build();
// 基于栈（Stack）的算子管理：它的绝大多数算子方法（如 filter, project, join）都属于隐式入栈/出栈操作。例如，当你调用 scan("A") 时，A 算子会被压入内部栈顶；紧接着调用 filter，它会自动从栈顶弹出 A，包裹上一层 Filter 后再压回栈顶；当调用 join 时，它会自动弹出栈顶的两棵树进行关联。
// 插件式物理算子替换（解耦核心）：RelBuilder 默认生产的是标准的逻辑算子（LogicalProject 等）。
// 但在不同的计算引擎（如 Hive、Flink、Spark）集成时，可以通过传入不同的工厂配置（RelFactories.Struct），
// 在完全不修改流式代码结构的前提下，直接让 builder.filter 生产出 HiveFilter 或 FlinkStreamFilter。
@Value.Enclosing
public class RelBuilder {
  // 当前关系代数树运行的全局集群/宇宙上下文环境。
  // 持有了优化器（Planner）实例、类型工厂（RelDataTypeFactory）、表达式建造器（RexBuilder）以及元数据查询系统。所有的算子和行表达式节点诞生时，都必须绑定到同一个 cluster。
  protected final RelOptCluster cluster;
  // 全局的关系型元数据大纲（Schema）。
  // 主要用于通过表名字符串（如 scan("EMP")）反向检索物理表的元数据。由于在纯内存计算或直接传递 RelNode 的轻量优化场景下可能不需要 catalog 检索，因此允许为 null。
  protected final @Nullable RelOptSchema relOptSchema;
  // 最核心的内部状态：操作数栈。
  // 这是一个双端队列（当作栈使用），用来存储当前阶段已经构建好的、处于悬挂状态的 RelNode 及其字段别名映射（封装在内部类 Frame 中）。流式调用算子方法时，就是在此栈上不断进行压栈（Push）和弹栈（Pop）的拓扑组装。
  private final Deque<Frame> stack = new ArrayDeque<>();
  // 行表达式（RexNode）简化器。
  // 每当你在 RelBuilder 中构建布尔过滤条件时（例如 AND(true, x = 1)），这个组件会利用底层的谓词推导（Predicates）和执行器，自动在编译期将其常数折叠或简化为 x = 1，从而提升整体树的初始质量。
  private RexSimplify simplifier;
  // RelBuilder 的运行时控制行为配置对象。
  private final Config config;
  // 视图展开器。
  private final RelOptTable.ViewExpander viewExpander;
  // 抽象算子工厂结构体（内含各种核心算子的 Factory）
  private RelFactories.Struct struct;

  protected RelBuilder(@Nullable Context context, RelOptCluster cluster,
      @Nullable RelOptSchema relOptSchema) {
    this.cluster = cluster;
    this.relOptSchema = relOptSchema;
    if (context == null) {
      context = Contexts.EMPTY_CONTEXT;
    }
    this.config = getConfig(context);
    this.viewExpander = getViewExpander(cluster, context);
    this.struct =
        requireNonNull(RelFactories.Struct.fromContext(context));
    final RexExecutor executor =
        context.maybeUnwrap(RexExecutor.class)
            .orElse(
                first(cluster.getPlanner().getExecutor(),
                    RexUtil.EXECUTOR));
    final RelOptPredicateList predicates = RelOptPredicateList.EMPTY;
    this.simplifier =
        new RexSimplify(cluster.getRexBuilder(), predicates, executor);
  }

  /**
   * Derives the view expander
   * {@link org.apache.calcite.plan.RelOptTable.ViewExpander}
   * to be used for this RelBuilder.
   *
   * <p>The ViewExpander instance is used for expanding views in the default
   * table scan factory {@code RelFactories.TableScanFactoryImpl}.
   * You can also define a new table scan factory in the {@code struct}
   * to override the whole table scan creation.
   *
   * <p>The default view expander does not support expanding views.
   */
  // 负责为当前正在创建的 RelBuilder 推导并绑定一个视图展开器（ViewExpander）。
  // 在大数据和数据库系统中，“视图（View）”本质上是一个存储在元数据中的虚拟 SQL 查询。
  // 当你在流式 API 中调用 builder.scan("MY_VIEW") 时，RelBuilder 内部不能直接去扫物理表，
  // 而是需要通过 ViewExpander 把这个视图的底层 SQL 捞出来，解析并展开成一棵子算子树塞进当前的位置。
  private static RelOptTable.ViewExpander getViewExpander(RelOptCluster cluster,
      Context context) {
    return context.maybeUnwrap(RelOptTable.ViewExpander.class)
        // 如果用户根本没有在 context 里配过视图展开器，则触发此保底分支。
        .orElseGet(() -> ViewExpanders.simpleContext(cluster)); //ViewExpanders.simpleContext里面并没有实现视图的展开逻辑
  }

  /** Derives the Config to be used for this RelBuilder.
   *
   * <p>Overrides {@link RelBuilder.Config#simplify} if
   * {@link Hook#REL_BUILDER_SIMPLIFY} is set.
   */
  // 负责推导、衍生出符合当前环境要求的最终运行时控制配置对象（Config）。它的核心价值在于提供了一种通过全局“钩子（Hook）”在运行时强制介入/扭转优化行为的后门机制。
  private static Config getConfig(Context context) {
    // 首先尝试从上下文 context 中解包获取用户设定的高级配置 Config。如果解包不出来，说明用户想要用开箱即用的体验，此时直接选用系统内置的默认大盘配置 Config.DEFAULT。
    final Config config =
        context.maybeUnwrap(Config.class).orElse(Config.DEFAULT);
    boolean simplify = Hook.REL_BUILDER_SIMPLIFY.get(config.simplify());
    return config.withSimplify(simplify);
  }

  /** Creates a RelBuilder. */
  // 暴露给整个 Calcite 社区最通用、最标准的静态公共工厂方法（主入口）
  public static RelBuilder create(FrameworkConfig config) {
    // 调用 Calcite 框架提供的准备工具。这是一个控制反转（IoC）的高阶函数。
    return Frameworks.withPrepare(config,
        (cluster, relOptSchema, rootSchema, statement) ->
            new RelBuilder(config.getContext(), cluster, relOptSchema));
  }

  /** Creates a copy of this RelBuilder, with the same state as this, applying
   * a transform to the config. */
  // 允许你基于当前 RelBuilder 的所有已有元数据和状态，快速克隆出一个“局部临时改变了构建规则”的全新 RelBuilder 副本。
  // 在编写高级优化规则（Optimizer Rules）或定制数据转换时，你经常会遇到一种“既要、又要”的尴尬两难境地：
  // 全局环境：你希望新创建的算子树依然在原来的 cluster 宇宙中，能看到原来的元数据大纲 relOptSchema。
  // 局部策略：但是，对于接下来要流式组装的这几步特定算子，你希望临时改变其构建策略。例如，接下来要构建一处特殊的逻辑，你希望临时关闭常数折叠（simplify = false），或者临时允许 Project 算子无限合并膨胀（bloat = 10000）。
  // 你必须手动把老 builder 的 cluster、relOptSchema、context 挨个抽取出来，自己解包配置、手动修改后再去重新 new RelBuilder(...)。这不仅繁琐，而且极易丢失老 builder 已经加载好的工厂结构体（struct）。
  // 用了函数式编程（UnaryOperator 单元算子），允许你在不破坏原有 RelBuilder 配置的前提下，一行代码派生出一个临时改变了规则的“分身”。
  // UnaryOperator<Config> transform 一个配置转换函数（接收一个 Config 对象，处理后必须返回一个新的 Config 对象）。
  public RelBuilder transform(UnaryOperator<Config> transform) {
    // ontexts.of(struct, ...)：
    //
    //逻辑：将当前的算子物理工厂结构体 this.struct（里面保存了当前正在使用的 FilterFactory、ProjectFactory 等）与刚刚生成的全新 Config 一起，重新揉碎、打包成一个全新的上下文大对象（Context）。
    final Context context =
        Contexts.of(struct, transform.apply(config));
    return new RelBuilder(context, cluster, relOptSchema);
  }

  /** Performs an action on this RelBuilder.
   *
   * <p>For example, consider the following code:
   *
   * <blockquote><pre>
   *   RelNode filterAndRename(RelBuilder relBuilder, RelNode rel,
   *       RexNode condition, List&lt;String&gt; fieldNames) {
   *     relBuilder.push(rel)
   *         .filter(condition);
   *     if (fieldNames != null) {
   *       relBuilder.rename(fieldNames);
   *     }
   *     return relBuilder
   *         .build();</pre>
   * </blockquote>
   *
   * <p>The pipeline is disrupted by the 'if'. The {@code let} method
   * allows you to perform the flow as a single pipeline:
   *
   * <blockquote><pre>
   *   RelNode filterAndRename(RelBuilder relBuilder, RelNode rel,
   *       RexNode condition, List&lt;String&gt; fieldNames) {
   *     return relBuilder.push(rel)
   *         .filter(condition)
   *         .let(r -&gt; fieldNames == null ? r : r.rename(fieldNames))
   *         .build();</pre>
   * </blockquote>
   *
   * <p>In pipelined cases such as this one, the lambda must return this
   * RelBuilder. But {@code let} return values of other types.
   */
  // 终结被 if 语句打断的流式管道
  // 在开发复杂的 SQL 转换器时，经常需要根据业务配置动态地叠加算子。传统的流式代码一旦遇到分支逻辑（例如：如果参数不为空，就加一层 rename 算子），就必须强行断开链式调用，声明变量，等 if 判定完后再继续链式调用。
  // let 方法通过引入高阶函数（函数作为参数），允许你将 if-else 这样的条件控制块直接“内联”融入到一气呵成的单条流式管道中。
  // let 方法非常大方，它不强制要求闭包必须返回 RelBuilder 本身，而是允许返回任意类型 R。
  // 如果 R 是 RelBuilder：你可以在 Lambda 内部继续写 .filter().project()，然后让 Lambda 把当前的 builder 吐出来，支持后续继续 .build()。
  // 结合官方注释的对比示例
  //传统的痛苦写法（被 if 打断）：
  // 必须断开管道，因为 if 语句没办法作为一个算子连在 filter() 后面
  //relBuilder.push(rel)
  //          .filter(condition); // 管道第一次断开
  //
  //if (fieldNames != null) {
  //  relBuilder.rename(fieldNames); // 管道第二次断开
  //}
  //
  //return relBuilder.build();
  // 使用 let 后的优雅写法（单条管道一气呵成）：
  // return relBuilder.push(rel)
  //    .filter(condition)
  //    // r 就是传入的 relBuilder 实例
  //    .let(r -> fieldNames == null ? r : r.rename(fieldNames))
  //    .build(); // 从头到尾不需要任何局部变量，一个分号直接到终点
  public <R> R let(Function<RelBuilder, R> consumer) {
    return consumer.apply(this);
  }

  /** Converts this RelBuilder to a string.
   * The string is the string representation of all of the RelNodes on the stack. */
  @Override public String toString() {
    return stack.stream()
        .map(frame -> RelOptUtil.toString(frame.rel))
        .collect(Collectors.joining(""));
  }

  /** Returns the type factory. */
  public RelDataTypeFactory getTypeFactory() {
    return cluster.getTypeFactory();
  }

  /** Returns new RelBuilder that adopts the convention provided.
   * RelNode will be created with such convention if corresponding factory is provided. */
  // RelBuilder 类中用于改变物理算子方言/物理执行引擎特征的一个极度核心、且带有副作用的公共方法：adoptConvention（采纳物理特征约束）。
  // RelBuilder 的灵魂在于它拥有一个抽象工厂结构体属性 this.struct。而这个方法正是通过动态改变这个结构体，从而改变接下来生成的算子树宿主命运的开关。
  // 在 Apache Calcite 中，Convention（特征约束/方言）代表了一棵算子树最终要在哪里运行。
  // 如果特征是 Convention.NONE，那么它就是一棵纯粹、抽象的逻辑算子树（如 LogicalFilter）。
  // 如果特征是 SparkModel.CONVENTION、FlinkStream.CONVENTION 或 JDBC.CONVENTION，那么它代表这棵树是要丢给 Spark、Flink 或者是特定关系型数据库（如 MySQL/Oracle）直接去执行的物理算子树。
  // 传统的优化器在将一棵“逻辑树”翻译成“特定引擎的物理树”时，需要编写极其庞大且复杂的优化规则（ConverterRule），在 Volcano 引擎里反复推导碰撞、计算代价、替换节点。这对于一些直观的、定制化的、目的性极强的翻译工作来说，性能损耗大且开发成本高。
  // adoptConvention 的解法
  //它允许你在建树的途中，直接给 RelBuilder“换脑”。
  // 调用该方法后，RelBuilder 内部的底层工厂指针会被瞬间整体打包替换。
  // 接下来你再调用流式 API（如 .filter().project()）时，生产出来的将不再是普通的 LogicalFilter，
  // 而是直接生产出对应引擎的物理算子（例如 JdbcFilter、SparkFilter 等）。
  public RelBuilder adoptConvention(Convention convention) {
    this.struct = convention.getRelFactories();
    return this;
  }

  /** Returns the builder for {@link RexNode} expressions. */
  public RexBuilder getRexBuilder() {
    return cluster.getRexBuilder();
  }

  /** Creates a {@link RelBuilderFactory}, a partially-created RelBuilder.
   * Just add a {@link RelOptCluster} and a {@link RelOptSchema} */
  // 用于高阶函数式编程的静态工厂方法。它们通过引入一个名为 RelBuilderFactory（工厂的工厂 / 模板工厂） 的高度抽象接口，
  // 彻底解决了在多线程或多会话（Session）场景下，如何“一次性配置物理算子插件，处处安全实例化”的架构难题。
  // 典型的函数式接口（Functional Interface），只接收运行期的两个核心环境参数，即可实例化一个 RelBuilder。
  // RelBuilder 是**非线程安全（Not thread-safe）**的，因为它内部死死绑定了一个带有状态的“操作数栈（stack）”。
  //这意味着在生产环境（例如一个并发接收 SQL 查询的 Spring Boot Gateway 或 Web 服务器）中，你绝对不能把它声明为一个全局单例（Singleton）供所有线程共享。每个查询请求进入系统，都必须拥有自己专属的、全新干净的 RelBuilder 实例。
  public static RelBuilderFactory proto(final Context context) {
    return (cluster, schema) -> new RelBuilder(context, cluster, schema);
  }

  /** Creates a {@link RelBuilderFactory} that uses a given set of factories. */
  public static RelBuilderFactory proto(Object... factories) {
    return proto(Contexts.of(factories));
  }

  public RelOptCluster getCluster() {
    return cluster;
  }

  public @Nullable RelOptSchema getRelOptSchema() {
    return relOptSchema;
  }

  public RelFactories.TableScanFactory getScanFactory() {
    return struct.scanFactory;
  }

  // Methods for manipulating the stack

  /** Adds a relational expression to be the input to the next relational
   * expression constructed.
   *
   * <p>This method is usual when you want to weave in relational expressions
   * that are not supported by the builder. If, while creating such expressions,
   * you need to use previously built expressions as inputs, call
   * {@link #build()} to pop those inputs. */
  // 将一个现成的算子节点“强行压入”操作数栈顶
  // 通常情况下，我们是通过流式 API（如 .scan(), .filter()）隐式地向栈里放算子的。但在以下两种高级场景中，你需要直接调用 push
  // 打破常规，编织外部算子：你在外部自己通过 new MyCustomRelNode(...) 手工写了一个 Calcite 原生 RelBuilder 压根不支持的自定义特种算子。此时，你可以通过 push(myCustomNode) 强行把它塞进 RelBuilder 的宇宙中，作为后续链式调用的输入。
  // 多分支缝合（如 Join/Union 准备）：当你需要做两表 Join 时，你必须先 push 左表算子树，再 push 右表算子树，让栈里同时积压两棵树，最后才能调用 .join()。
  public RelBuilder push(RelNode node) {
    stack.push(new Frame(node));
    return this;
  }

  /** Adds a rel node to the top of the stack while preserving the field names
   * and aliases. */
  // 在“完全保留字段元数据和别名”的前提下，偷换栈顶的物理算子
  private void replaceTop(RelNode node) {
    final Frame frame = stack.pop();
    stack.push(new Frame(node, frame.fields));
  }

  /** Pushes a collection of relational expressions. */
  // 批量将一组已经构建好的关系表达式（RelNode）节点压入 RelBuilder 的内部工作栈中。
  public RelBuilder pushAll(Iterable<? extends RelNode> nodes) {
    for (RelNode node : nodes) {
      push(node);
    }
    return this;
  }

  /** Returns the size of the stack. */
  // 返回当前 RelBuilder 内部工作栈的深度（元素个数）
  public int size() {
    return stack.size();
  }

  /** Returns the final relational expression.
   *
   * <p>Throws if the stack is empty.
   */
  // 构建并返回最终的关系表达式树。这是 RelBuilder 链式调用的终点方法。
  public RelNode build() {
    return stack.pop().rel;
  }

  /** Returns the relational expression at the top of the stack, but does not
   * remove it. */
  // 查看（不弹出）当前栈顶的关系表达式（最顶层的算子节点）
  public RelNode peek() {
    return castNonNull(peek_()).rel;
  }

  // 查看当前栈顶的内部包装对象 Frame（包含 RelNode 及其附加的元数据字段信息）。
  private @Nullable Frame peek_() {
    return stack.peek();
  }

  /** Returns the relational expression {@code n} positions from the top of the
   * stack, but does not remove it. */
  // 获取距离栈顶 从上往下数第 n 个位置 的关系表达式（n=0 代表栈顶本身，n=1 代表栈顶下面紧挨着的元素）
  public RelNode peek(int n) {
    return peek_(n).rel;
  }
  // 获取距离栈顶第 n 个位置的内部 Frame 对象。
  private Frame peek_(int n) {
    if (n == 0) {
      // more efficient than starting an iterator
      return Objects.requireNonNull(stack.peek(), "stack.peek");
    }
    return Iterables.get(stack, n);
  }

  /** Returns the relational expression {@code n} positions from the top of the
   * stack, but does not remove it. */
  // 根据多路输入总数和指定的输入序号，查看栈中对应的关系表达式。这在处理多路 Join、Union 或 Correlate 时极为重要。
  public RelNode peek(int inputCount, int inputOrdinal) {
    return peek_(inputCount, inputOrdinal).rel;
  }
  // 将“算子的多路输入视角”转换为“栈的从上往下倒数视角”。
  private Frame peek_(int inputCount, int inputOrdinal) {
    return peek_(inputCount - 1 - inputOrdinal);
  }

  /** Returns the number of fields in all inputs before (to the left of)
   * the given input.
   *
   * @param inputCount Number of inputs
   * @param inputOrdinal Input ordinal
   */
  // 计算在给定的复数个输入源中，指定序号 inputOrdinal 的输入源在其左侧所有输入源的字段总偏移量（Field Offset）。
  // 应用场景：
  // 常用于多路连接（Multi-Join）或集合操作。例如：
  // 栈里有三个输入源：A（2个字段）、B（3个字段）、C（4个字段）。
  // 如果我们要引用 B 树里的字段，由于 A 在 B 的左边，B 的字段索引不能从 0 开始，而必须加上 A 的字段总数（偏移量 = 2）。
  private int inputOffset(int inputCount, int inputOrdinal) {
    int offset = 0;
    for (int i = 0; i < inputOrdinal; i++) {
      offset += peek(inputCount, i).getRowType().getFieldCount();
    }
    return offset;
  }

  /** Evaluates an expression with a relational expression temporarily on the
   * stack. */
  // 在 RelBuilder 的内部工作栈上临时压入一个关系表达式节点 r，
  // 在这个临时上下文中执行一段特定的操作（由函数 fn 定义），并在操作结束后自动将该节点从栈中弹出，恢复原样。
  public <E> E with(RelNode r, Function<RelBuilder, E> fn) {
    try {
      push(r);
      return fn.apply(this);
    } finally {
      stack.pop();
    }
  }

  /** Performs an action with a temporary simplifier. */
  // 临时改变或增强表达式简化器（RexSimplify）的逻辑。
  // 在传入的函数 fn 执行期间使用新的简化器规则，执行完毕后自动恢复为原有的简化器。
  // simplifierTransform：一个双参数函数。它接收当前 RelBuilder 和原有的简化器（previousSimplifier），并返回一个加工/增强后的新简化器。
  // fn：在此临时简化器环境中需要执行的具体操作函数。
  public <E> E withSimplifier(
      BiFunction<RelBuilder, RexSimplify, RexSimplify> simplifierTransform,
      Function<RelBuilder, E> fn) {
    final RexSimplify previousSimplifier = this.simplifier;
    try {
      this.simplifier = simplifierTransform.apply(this, previousSimplifier);
      return fn.apply(this);
    } finally {
      this.simplifier = previousSimplifier;
    }
  }

  /** Performs an action using predicates of
   * the {@link #peek() current node} to simplify. */
  // 这是 withSimplifier 的一个典型高阶应用。
  // 它通过元数据查询（Metadata Query）获取当前栈顶节点已经具备的断言（谓词/过滤条件），并把这些断言作为已知的“先验知识”注入到临时简化器中。
  // 在这段作用域内构建的所有表达式，都会自动利用这些已知的过滤条件进行深度化简。
  public <E> E withPredicates(RelMetadataQuery mq,
      Function<RelBuilder, E> fn) {
    final RelOptPredicateList predicates = mq.getPulledUpPredicates(peek());
    return withSimplifier((r, s) -> s.withPredicates(predicates), fn);
  }

  // Methods that return scalar expressions

  /** Creates a literal (constant expression). */
  // 用来构建标量表达式（Scalar Expressions）的，也就是 SQL 中作用于单行、返回单个值的表达式（如常量、函数调用、算术运算等）。
  // 用来将一个 Java 原生对象（Object）安全、动态地转换为 Calcite 内部的行表达式常量——RexLiteral。
  // RexBuilder：Calcite 中专门用来构建行表达式（Row Expressions，即 RexNode 系列，如 RexLiteral, RexCall, RexInputRef）的工厂类
  // TypeFactory (RelDataTypeFactory)：类型工厂，用来创建或获取 Calcite 中的 SQL 数据类型系统（RelDataType）。
  public RexLiteral literal(@Nullable Object value) {
    final RexBuilder rexBuilder = cluster.getRexBuilder();
    // 如果 Java 对象为 null，首先通过类型工厂创建一个 SQL 层的 NULL 类型定义（SqlTypeName.NULL），然后让 rexBuilder 生成一个对应类型的 NullLiteral 标量。
    if (value == null) {
      final RelDataType type = getTypeFactory().createSqlType(SqlTypeName.NULL);
      return rexBuilder.makeNullLiteral(type);
    // 如果对象是 Boolean（true 或 false），直接调用 makeLiteral，生成 SQL 中的布尔常量（对应 SQL 的 BOOLEAN）。
    } else if (value instanceof Boolean) {
      return rexBuilder.makeLiteral((Boolean) value);
    } else if (value instanceof BigDecimal) {
      return rexBuilder.makeExactLiteral((BigDecimal) value);
    } else if (value instanceof Float || value instanceof Double) {
      return rexBuilder.makeApproxLiteral(
          BigDecimal.valueOf(((Number) value).doubleValue()));
    } else if (value instanceof Number) {
      return rexBuilder.makeExactLiteral(
          BigDecimal.valueOf(((Number) value).longValue()));
    } else if (value instanceof String) {
      return rexBuilder.makeLiteral((String) value);
    } else if (value instanceof Enum) {
      return rexBuilder.makeLiteral(value,
          getTypeFactory().createSqlType(SqlTypeName.SYMBOL));
    } else if (value instanceof DateString) {
      return rexBuilder.makeDateLiteral((DateString) value);
    } else {
      throw new IllegalArgumentException("cannot convert " + value
          + " (" + value.getClass() + ") to a constant");
    }
  }

  // 核心作用是在构建关联子查询（Correlated Subquery）时，动态生成一个关联变量（Correlation Variable，即 RexCorrelVariable）。
  // 通过这个变量，内层的子查询可以引用外层查询的字段（例如，实现类似于外表 EMP 的当前行传递到内表过滤条件中的功能）。
  // 在标准 SQL 中，相关子查询非常常见，例如：
  // SELECT * FROM EMP e
  //WHERE e.sal > (SELECT AVG(sal) FROM DEPT d WHERE d.deptno = e.deptno)
  //--                                                   ↑ 这里的 e.deptno 就是关联引用
  // 内部使用了 Java 8 的方法引用（Method Reference） v::set，将其转换为一个 Consumer<RexCorrelVariable>，然后直接转发调用给下面的核心方法（方法 2）。
  @Deprecated // to be removed before 2.0
  public RelBuilder variable(Holder<RexCorrelVariable> v) {
    return variable(v::set);
  }

  /** Creates a correlation variable for the current input, and writes it into
   * a Consumer.
   *
   * <p>Often the Consumer will write to a {@link Holder}, as follows:
   * <blockquote>{@code
   *   RelBuilder builder;
   *    builder.scan("EMP")
   *        .variable(v::set)
   *        .filter(builder.equals(builder.field(0), v.get()))
   * }</blockquote>
   */
  // 基于 Consumer 响应式回调的核心方法
  // 目前官方推荐的核心方法。它利用好莱坞原则（“不要给我们打电话，我们会给你打电话”），通过回调函数 Consumer 将系统新创建的关联变量“推送”给开发者定义好的变量接收器。
  // 创建的相关变量给consumer消费
  public RelBuilder variable(Consumer<RexCorrelVariable> consumer) {
    consumer.accept((RexCorrelVariable)
        getRexBuilder().makeCorrel(peek().getRowType(),
            cluster.createCorrel()));
    return this;
  }

  /** Creates a reference to a field by name.
   *
   * <p>Equivalent to {@code field(1, 0, fieldName)}.
   *
   * @param fieldName Field name
   */
  public RexInputRef field(String fieldName) {
    return field(1, 0, fieldName);
  }

  /** Creates a reference to a field of given input relational expression
   * by name.
   *
   * @param inputCount Number of inputs
   * @param inputOrdinal Input ordinal
   * @param fieldName Field name
   */
  // 核心作用是：通过“字段名称（String）”而非“数字索引（int）”，去寻找并创建对上游/父级关系算子（RelNode）中某个特定列的引用节点（RexInputRef）。
  // int inputCount 当前上下文中所期望的总输入算子数量（通常代表当前 RelBuilder 栈顶有多少个活动的 RelNode 供你查询）。场景：单表操作（如 Filter/Project）传 1；双表 Join 操作传 2。
  // int inputOrdinal 含义：目标字段所属的具体输入算子索引（从 0 开始）。场景：如果是 Join 操作，0 代表左表（Left Input），1 代表右表（Right Input）。
  // String fieldName 含义：你想要引用的列名字符串。例如 "name"、"salary" 等。
  // 返回值 RexInputRef 含义：返回精确定位到该列的绝对索引引用节点。注意，它不像前两个方法返回基类 RexNode，这里直接返回了子类类型 RexInputRef。
  public RexInputRef field(int inputCount, int inputOrdinal, String fieldName) {
    final Frame frame = peek_(inputCount, inputOrdinal);
    final List<String> fieldNames = Pair.left(frame.fields());
    int i = fieldNames.indexOf(fieldName);
    if (i >= 0) {
      return field(inputCount, inputOrdinal, i);
    } else {
      throw new IllegalArgumentException("field [" + fieldName
          + "] not found; input fields are: " + fieldNames);
    }
  }

  /** Creates a reference to an input field by ordinal.
   *
   * <p>Equivalent to {@code field(1, 0, ordinal)}.
   *
   * @param fieldOrdinal Field ordinal
   */
  public RexInputRef field(int fieldOrdinal) {
    return (RexInputRef) field(1, 0, fieldOrdinal, false);
  }

  /** Creates a reference to a field of a given input relational expression
   * by ordinal.
   *
   * @param inputCount Number of inputs
   * @param inputOrdinal Input ordinal
   * @param fieldOrdinal Field ordinal within input
   */
  // 在通过 RelBuilder 构建 SQL 关系表达式树时，根据指定的输入算子和字段索引，创建一个行表达式节点（RexNode，通常是 RexInputRef），
  // 用于引用下游/父级算子的某个特定字段，并根据需要自动处理字段别名（Alias）
  // 在 Calcite 内部，当你想在 Filter（Where 条件）或 Project（Select 字段）中引用某个表的列时，都需要通过这个方法来计算它在当前输入栈（Stack）中的绝对偏移量并生成引用。
  // int inputCount 含义：当前操作期望的总输入算子数量（即当前栈顶有多少个关联的 RelNode 参与计算）。场景：如果是普通的单表操作（如 Filter/Project），inputCount 通常为 1；如果是双表关联操作（如 Join），inputCount 通常为 2。
  // int inputOrdinal 含义：目标字段所属的输入算子的索引（从 0 开始计数）场景：在单表操作中只能是 0；在 Join 操作中，0 代表左表（Left Input），1 代表右表（Right Input）。
  // int fieldOrdinal 目标字段在它所属的那个输入算子中的列相对索引/位置（从 0 开始计数）举例：如果左表有 (id, name, age) 三列，你想引用 name，那么 fieldOrdinal 就是 1。
  public RexInputRef field(int inputCount, int inputOrdinal, int fieldOrdinal) {
    return (RexInputRef) field(inputCount, inputOrdinal, fieldOrdinal, false);
  }

  /** As {@link #field(int, int, int)}, but if {@code alias} is true, the method
   * may apply an alias to make sure that the field has the same name as in the
   * input frame. If no alias is applied the expression is definitely a
   * {@link RexInputRef}. */
  // 在通过 RelBuilder 构建 SQL 关系表达式树时，根据指定的输入算子和字段索引，创建一个行表达式节点（RexNode，通常是 RexInputRef），
  // 用于引用下游/父级算子的某个特定字段，并根据需要自动处理字段别名（Alias）
  // 在 Calcite 内部，当你想在 Filter（Where 条件）或 Project（Select 字段）中引用某个表的列时，都需要通过这个方法来计算它在当前输入栈（Stack）中的绝对偏移量并生成引用。
  // int inputCount 含义：当前操作期望的总输入算子数量（即当前栈顶有多少个关联的 RelNode 参与计算）。场景：如果是普通的单表操作（如 Filter/Project），inputCount 通常为 1；如果是双表关联操作（如 Join），inputCount 通常为 2。
  // int inputOrdinal 含义：目标字段所属的输入算子的索引（从 0 开始计数）场景：在单表操作中只能是 0；在 Join 操作中，0 代表左表（Left Input），1 代表右表（Right Input）。
  // int fieldOrdinal 目标字段在它所属的那个输入算子中的列相对索引/位置（从 0 开始计数）举例：如果左表有 (id, name, age) 三列，你想引用 name，那么 fieldOrdinal 就是 1。
  // boolean alias 是否强制应用字段别名。
  private RexNode field(int inputCount, int inputOrdinal, int fieldOrdinal,
      boolean alias) {
    final Frame frame = peek_(inputCount, inputOrdinal);
    final RelNode input = frame.rel;
    final RelDataType rowType = input.getRowType();
    // 严格检查传入的列相对索引 fieldOrdinal 是否越界。
    // 如果小于 0 或者大于当前算子的总列数，直接抛出 IllegalArgumentException 异常，并打印出当前算子所有可用的字段名，便于开发者调试。
    if (fieldOrdinal < 0 || fieldOrdinal > rowType.getFieldCount()) {
      throw new IllegalArgumentException("field ordinal [" + fieldOrdinal
          + "] out of range; input fields are: " + rowType.getFieldNames());
    }
    // 根据相对索引，获取该列的类型和名称信息（RelDataTypeField）。
    final RelDataTypeField field = rowType.getFieldList().get(fieldOrdinal);
    // 计算绝对偏移量（Offset）。
    // Calcite 的字段引用机制：在诸如 Join 的多表环境中，Calcite 会将左表和右表的字段“扁平化”合并成一个连续的字段序列。
    final int offset = inputOffset(inputCount, inputOrdinal);
    // 创建标准的字段引用节点
    final RexInputRef ref = cluster.getRexBuilder()
        .makeInputRef(field.getType(), offset + fieldOrdinal);
    final RelDataTypeField aliasField = frame.fields().get(fieldOrdinal);
    if (!alias || field.getName().equals(aliasField.getName())) {
      return ref;
    } else {
      return alias(ref, aliasField.getName());
    }
  }

  /** Creates a reference to a field of the current record which originated
   * in a relation with a given alias. */
  public RexNode field(String alias, String fieldName) {
    return field(1, alias, fieldName);
  }

  /** Creates a reference to a field which originated in a relation with the
   * given alias. Searches for the relation starting at the top of the
   * stack. */
  public RexNode field(int inputCount, String alias, String fieldName) {
    requireNonNull(alias, "alias");
    requireNonNull(fieldName, "fieldName");
    for (int inputOrdinal = 0; inputOrdinal < inputCount; ++inputOrdinal) {
      final Frame frame = peek_(inputOrdinal);
      final List<ImmutableSet<String>> aliasSets = frame.fields.leftList();
      final List<RelDataTypeField> fields = frame.fields.rightList();
      for (int i = 0, n = frame.fields.size(); i < n; i++) {
        // If alias and field name match, reference that field.
        if (aliasSets.get(i).contains(alias)
            && fields.get(i).getName().equals(fieldName)) {
          return field(inputCount, inputCount - 1 - inputOrdinal, i);
        }
      }
    }

    // Not found. Build a message.
    StringBuilder b =
        new StringBuilder().append("{alias=").append(alias)
            .append(",fieldName=").append(fieldName)
            .append("} field not found; fields are: ");
    for (int inputOrdinal = 0; inputOrdinal < inputCount; ++inputOrdinal) {
      final Frame frame = peek_(inputOrdinal);
      frame.fields.forEach((aliasSet, field) ->
          b.append(
              String.format(Locale.ROOT, "{aliases=%s,fieldName=%s}",
                  aliasSet, field.getName())));
    }
    throw new IllegalArgumentException(b.toString());
  }

  /** Returns a reference to a given field of a record-valued expression. */
  public RexNode field(RexNode e, String name) {
    return getRexBuilder().makeFieldAccess(e, name, false);
  }

  /** Returns references to the fields of the top input. */
  public ImmutableList<RexNode> fields() {
    return fields(1, 0);
  }

  /** Returns references to the fields of a given input. */
  public ImmutableList<RexNode> fields(int inputCount, int inputOrdinal) {
    final RelNode input = peek(inputCount, inputOrdinal);
    final RelDataType rowType = input.getRowType();
    final ImmutableList.Builder<RexNode> nodes = ImmutableList.builder();
    for (int fieldOrdinal : Util.range(rowType.getFieldCount())) {
      nodes.add(field(inputCount, inputOrdinal, fieldOrdinal));
    }
    return nodes.build();
  }

  /** Returns references to fields for a given collation. */
  public ImmutableList<RexNode> fields(RelCollation collation) {
    final ImmutableList.Builder<RexNode> nodes = ImmutableList.builder();
    for (RelFieldCollation fieldCollation : collation.getFieldCollations()) {
      RexNode node = field(fieldCollation.getFieldIndex());
      switch (fieldCollation.direction) {
      case DESCENDING:
        node = desc(node);
        break;
      default:
        break;
      }
      switch (fieldCollation.nullDirection) {
      case FIRST:
        node = nullsFirst(node);
        break;
      case LAST:
        node = nullsLast(node);
        break;
      default:
        break;
      }
      nodes.add(node);
    }
    return nodes.build();
  }

  /** Returns references to fields for a given list of input ordinals. */
  public ImmutableList<RexNode> fields(List<? extends Number> ordinals) {
    final ImmutableList.Builder<RexNode> nodes = ImmutableList.builder();
    for (Number ordinal : ordinals) {
      RexNode node = field(1, 0, ordinal.intValue(), false);
      nodes.add(node);
    }
    return nodes.build();
  }

  /** Returns references to fields for a given bit set of input ordinals. */
  public ImmutableList<RexNode> fields(ImmutableBitSet ordinals) {
    return fields(ordinals.asList());
  }

  /** Returns references to fields identified by name. */
  public ImmutableList<RexNode> fields(Iterable<String> fieldNames) {
    final ImmutableList.Builder<RexNode> builder = ImmutableList.builder();
    for (String fieldName : fieldNames) {
      builder.add(field(fieldName));
    }
    return builder.build();
  }

  /** Returns references to fields identified by a mapping. */
  public ImmutableList<RexNode> fields(Mappings.TargetMapping mapping) {
    return fields(Mappings.asListNonNull(mapping));
  }

  /** Creates an access to a field by name. */
  public RexNode dot(RexNode node, String fieldName) {
    final RexBuilder builder = cluster.getRexBuilder();
    return builder.makeFieldAccess(node, fieldName, true);
  }

  /** Creates an access to a field by ordinal. */
  public RexNode dot(RexNode node, int fieldOrdinal) {
    final RexBuilder builder = cluster.getRexBuilder();
    return builder.makeFieldAccess(node, fieldOrdinal);
  }

  /** Creates a call to a scalar operator. */
  public RexNode call(SqlOperator operator, RexNode... operands) {
    return call(operator, ImmutableList.copyOf(operands));
  }

  /** Creates a call to a scalar operator. */
  // 核心作用是：创建一个标量操作符（Scalar Operator）的调用节点（RexCall）。
  // 在 Calcite 树中，无论是算术运算（如 +, -）、比较运算（如 >, <），还是逻辑运算（如 AND, OR），乃至特定的 SQL 关键字（如 LIKE, BETWEEN），在行表达式层面都被统一表示为 RexCall。
  // 不仅负责通用函数的构建，还针对特殊的 SQL 操作符进行了等价语法重写或简化（例如将 NOT LIKE 转换为 NOT(LIKE)）。
  // SqlOperator operator：SQL 操作符元数据（例如 SqlStdOperatorTable.PLUS 代表加号，或者特定的内置/自定义函数对象）。
  // List<RexNode> operandList：该操作符对应的入参/操作数列表。例如加法需要两个操作数，BETWEEN 需要三个操作数。
  // 特殊操作符的拦截重写（Switch 块） 以及 通用的默认构建逻辑。
  private RexCall call(SqlOperator operator, List<RexNode> operandList) {
    switch (operator.getKind()) {
    case LIKE:
    case SIMILAR:
      final SqlLikeOperator likeOperator = (SqlLikeOperator) operator;
      // 检查该操作符是否是被否定的（即用户写的是 NOT LIKE 或 NOT SIMILAR TO）。
      if (likeOperator.isNegated()) {
        // 通过 likeOperator.not() 拿到正向的 LIKE 操作符（notLikeOperator），
        // 然后递归调用 call(notLikeOperator, operandList) 创建出标准的 LIKE 表达式，最后在外层套上一个 not(...) 节点
        final SqlOperator notLikeOperator = likeOperator.not();
        return (RexCall) not(call(notLikeOperator, operandList));
      }
      break;
    case BETWEEN:
      // 如果是 BETWEEN 算子（如 x BETWEEN a AND b），首先通过 assert 断言其操作数列表 operandList 必须严格等于 3 个（目标值、下界、上界）
      assert operandList.size() == 3;
      // 转发给专门处理区间判断的 between(...) 方法。
      return (RexCall) between(operandList.get(0), operandList.get(1),
          operandList.get(2));
    default:
      break;
    }
    final RexBuilder builder = cluster.getRexBuilder();
    // 动态类型推导（Type Inference）。
    final RelDataType type = builder.deriveReturnType(operator, operandList);
    // 正式物理组装并返回 RexCall 节点。
    return (RexCall) builder.makeCall(type, operator, operandList);
  }

  /** Creates a call to a scalar operator. */
  public RexNode call(SqlOperator operator,
      Iterable<? extends RexNode> operands) {
    return call(operator, ImmutableList.copyOf(operands));
  }

  /** Creates an IN predicate with a list of values.
   *
   * <p>For example,
   * <pre>{@code
   * b.scan("Emp")
   *     .filter(b.in(b.field("deptno"), b.literal(10), b.literal(20)))
   * }</pre>
   * is equivalent to SQL
   * <pre>{@code
   * SELECT *
   * FROM Emp
   * WHERE deptno IN (10, 20)
   * }</pre> */
  public RexNode in(RexNode arg, RexNode... ranges) {
    return in(arg, ImmutableList.copyOf(ranges));
  }

  /** Creates an IN predicate with a list of values.
   *
   * <p>For example,
   *
   * <pre>{@code
   * b.scan("Emps")
   *     .filter(
   *         b.in(b.field("deptno"),
   *             Arrays.asList(b.literal(10), b.literal(20))))
   * }</pre>
   *
   * <p>is equivalent to the SQL
   *
   * <pre>{@code
   * SELECT *
   * FROM Emps
   * WHERE deptno IN (10, 20)
   * }</pre> */
  public RexNode in(RexNode arg, Iterable<? extends RexNode> ranges) {
    return getRexBuilder().makeIn(arg, ImmutableList.copyOf(ranges));
  }

  /** Creates an IN predicate with a sub-query. */
  @Experimental
  public RexSubQuery in(RelNode rel, Iterable<? extends RexNode> nodes) {
    return RexSubQuery.in(rel, ImmutableList.copyOf(nodes));
  }

  /** Creates an IN predicate with a sub-query.
   *
   * <p>For example,
   *
   * <pre>{@code
   * b.scan("Emps")
   *     .filter(
   *         b.in(b.field("deptno"),
   *             b2 -> b2.scan("Depts")
   *                 .filter(
   *                     b2.eq(b2.field("location"), b2.literal("Boston")))
   *                 .project(b.field("deptno"))
   *                 .build()))
   * }</pre>
   *
   * <p>is equivalent to the SQL
   *
   * <pre>{@code
   * SELECT *
   * FROM Emps
   * WHERE deptno IN (SELECT deptno FROM Dept WHERE location = 'Boston')
   * }</pre> */
  @Experimental
  public RexNode in(RexNode arg, Function<RelBuilder, RelNode> f) {
    final RelNode rel = f.apply(this);
    return RexSubQuery.in(rel, ImmutableList.of(arg));
  }

  /** Creates a SOME (or ANY) predicate.
   *
   * <p>For example,
   *
   * <pre>{@code
   * b.scan("Emps")
   *     .filter(
   *         b.some(b.field("commission"),
   *             SqlStdOperatorTable.GREATER_THAN,
   *             b2 -> b2.scan("Emps")
   *                 .filter(
   *                     b2.eq(b2.field("job"), b2.literal("Manager")))
   *                 .project(b2.field("sal"))
   *                 .build()))
   * }</pre>
   *
   * <p>is equivalent to the SQL
   *
   * <pre>{@code
   * SELECT *
   * FROM Emps
   * WHERE commission > SOME (SELECT sal FROM Emps WHERE job = 'Manager')
   * }</pre>
   *
   * <p>or (since {@code SOME} and {@code ANY} are synonyms) the SQL
   *
   * <pre>{@code
   * SELECT *
   * FROM Emps
   * WHERE commission > ANY (SELECT sal FROM Emps WHERE job = 'Manager')
   * }</pre> */
  @Experimental
  public RexSubQuery some(RexNode node, SqlOperator op,
      Function<RelBuilder, RelNode> f) {
    return some_(node, op.kind, f);
  }

  private RexSubQuery some_(RexNode node, SqlKind kind,
      Function<RelBuilder, RelNode> f) {
    final RelNode rel = f.apply(this);
    final SqlQuantifyOperator quantifyOperator =
        SqlStdOperatorTable.some(kind);
    return RexSubQuery.some(rel, ImmutableList.of(node), quantifyOperator);
  }

  /** Creates an ALL predicate.
   *
   * <p>For example,
   *
   * <pre>{@code
   * b.scan("Emps")
   *     .filter(
   *         b.all(b.field("commission"),
   *             SqlStdOperatorTable.GREATER_THAN,
   *             b2 -> b2.scan("Emps")
   *                 .filter(
   *                     b2.eq(b2.field("job"), b2.literal("Manager")))
   *                 .project(b2.field("sal"))
   *                 .build()))
   * }</pre>
   *
   * <p>is equivalent to the SQL
   *
   * <pre>{@code
   * SELECT *
   * FROM Emps
   * WHERE commission > ALL (SELECT sal FROM Emps WHERE job = 'Manager')
   * }</pre>
   *
   * <p>Calcite translates {@code ALL} predicates to {@code NOT SOME}. The
   * following SQL is equivalent to the previous:
   *
   * <pre>{@code
   * SELECT *
   * FROM Emps
   * WHERE NOT (commission <= SOME (SELECT sal FROM Emps WHERE job = 'Manager'))
   * }</pre> */
  @Experimental
  public RexNode all(RexNode node, SqlOperator op,
      Function<RelBuilder, RelNode> f) {
    return not(some_(node, op.kind.negateNullSafe(), f));
  }

  /** Creates an EXISTS predicate.
   *
   * <p>For example,
   *
   * <pre>{@code
   * b.scan("Depts")
   *     .filter(
   *         b.exists(b2 ->
   *             b2.scan("Emps")
   *                 .filter(
   *                     b2.eq(b2.field("job"), b2.literal("Manager")))
   *                 .build()))
   * }</pre>
   *
   * <p>is equivalent to the SQL
   *
   * <pre>{@code
   * SELECT *
   * FROM Depts
   * WHERE EXISTS (SELECT 1 FROM Emps WHERE job = 'Manager')
   * }</pre> */
  @Experimental
  public RexSubQuery exists(Function<RelBuilder, RelNode> f) {
    final RelNode rel = f.apply(this);
    return RexSubQuery.exists(rel);
  }

  /** Creates a UNIQUE predicate.
   *
   * <p>For example,
   *
   * <pre>{@code
   * b.scan("Depts")
   *     .filter(
   *         b.exists(b2 ->
   *             b2.scan("Emps")
   *                 .filter(
   *                     b2.eq(b2.field("job"), b2.literal("Manager")))
   *                 .project(b2.field("deptno")
   *                 .build()))
   * }</pre>
   *
   * <p>is equivalent to the SQL
   *
   * <pre>{@code
   * SELECT *
   * FROM Depts
   * WHERE UNIQUE (SELECT deptno FROM Emps WHERE job = 'Manager')
   * }</pre> */
  @Experimental
  public RexSubQuery unique(Function<RelBuilder, RelNode> f) {
    final RelNode rel = f.apply(this);
    return RexSubQuery.unique(rel);
  }

  /** Creates a scalar sub-query.
   *
   * <p>For example,
   *
   * <pre>{@code
   * b.scan("Depts")
   *     .project(
   *         b.field("deptno")
   *         b.scalarQuery(b2 ->
   *             b2.scan("Emps")
   *                 .aggregate(
   *                     b2.eq(b2.field("job"), b2.literal("Manager")))
   *                 .build()))
   * }</pre>
   *
   * <p>is equivalent to the SQL
   *
   * <pre>{@code
   * SELECT deptno, (SELECT MAX(sal) FROM Emps)
   * FROM Depts
   * }</pre> */
  @Experimental
  public RexSubQuery scalarQuery(Function<RelBuilder, RelNode> f) {
    return RexSubQuery.scalar(f.apply(this));
  }

  /** Creates an ARRAY sub-query.
   *
   * <p>For example,
   *
   * <pre>{@code
   * b.scan("Depts")
   *     .project(
   *         b.field("deptno")
   *         b.arrayQuery(b2 ->
   *             b2.scan("Emps")
   *                 .build()))
   * }</pre>
   *
   * <p>is equivalent to the SQL
   *
   * <pre>{@code
   * SELECT deptno, ARRAY (SELECT * FROM Emps)
   * FROM Depts
   * }</pre> */
  @Experimental
  public RexSubQuery arrayQuery(Function<RelBuilder, RelNode> f) {
    return RexSubQuery.array(f.apply(this));
  }

  /** Creates a MULTISET sub-query.
   *
   * <p>For example,
   *
   * <pre>{@code
   * b.scan("Depts")
   *     .project(
   *         b.field("deptno")
   *         b.multisetQuery(b2 ->
   *             b2.scan("Emps")
   *                 .build()))
   * }</pre>
   *
   * <p>is equivalent to the SQL
   *
   * <pre>{@code
   * SELECT deptno, MULTISET (SELECT * FROM Emps)
   * FROM Depts
   * }</pre> */
  @Experimental
  public RexSubQuery multisetQuery(Function<RelBuilder, RelNode> f) {
    return RexSubQuery.multiset(f.apply(this));
  }

  /** Creates a MAP sub-query.
   *
   * <p>For example,
   *
   * <pre>{@code
   * b.scan("Depts")
   *     .project(
   *         b.field("deptno")
   *         b.multisetQuery(b2 ->
   *             b2.scan("Emps")
   *                 .project(b2.field("empno"), b2.field("job"))
   *                 .build()))
   * }</pre>
   *
   * <p>is equivalent to the SQL
   *
   * <pre>{@code
   * SELECT deptno, MAP (SELECT empno, job FROM Emps)
   * FROM Depts
   * }</pre> */
  @Experimental
  public RexSubQuery mapQuery(Function<RelBuilder, RelNode> f) {
    return RexSubQuery.map(f.apply(this));
  }

  /** Creates an AND. */
  public RexNode and(RexNode... operands) {
    return and(ImmutableList.copyOf(operands));
  }

  /** Creates an AND.
   *
   * <p>Simplifies the expression a little:
   * {@code e AND TRUE} becomes {@code e};
   * {@code e AND e2 AND NOT e} becomes {@code e2}. */
  public RexNode and(Iterable<? extends RexNode> operands) {
    return RexUtil.composeConjunction(getRexBuilder(), operands);
  }

  /** Creates an OR. */
  public RexNode or(RexNode... operands) {
    return or(ImmutableList.copyOf(operands));
  }

  /** Creates an OR. */
  public RexNode or(Iterable<? extends RexNode> operands) {
    return RexUtil.composeDisjunction(cluster.getRexBuilder(), operands);
  }

  /** Creates a NOT. */
  public RexNode not(RexNode operand) {
    return call(SqlStdOperatorTable.NOT, operand);
  }

  /** Creates an {@code =}. */
  public RexNode equals(RexNode operand0, RexNode operand1) {
    return call(SqlStdOperatorTable.EQUALS, operand0, operand1);
  }

  /** Creates a {@code >}. */
  public RexNode greaterThan(RexNode operand0, RexNode operand1) {
    return call(SqlStdOperatorTable.GREATER_THAN, operand0, operand1);
  }

  /** Creates a {@code >=}. */
  public RexNode greaterThanOrEqual(RexNode operand0, RexNode operand1) {
    return call(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, operand0, operand1);
  }

  /** Creates a {@code <}. */
  public RexNode lessThan(RexNode operand0, RexNode operand1) {
    return call(SqlStdOperatorTable.LESS_THAN, operand0, operand1);
  }

  /** Creates a {@code <=}. */
  public RexNode lessThanOrEqual(RexNode operand0, RexNode operand1) {
    return call(SqlStdOperatorTable.LESS_THAN_OR_EQUAL, operand0, operand1);
  }

  /** Creates a {@code <>}. */
  public RexNode notEquals(RexNode operand0, RexNode operand1) {
    return call(SqlStdOperatorTable.NOT_EQUALS, operand0, operand1);
  }

  /** Creates an expression equivalent to "{@code o0 IS NOT DISTINCT FROM o1}".
   * It is also equivalent to
   * "{@code o0 = o1 OR (o0 IS NULL AND o1 IS NULL)}". */
  public RexNode isNotDistinctFrom(RexNode operand0, RexNode operand1) {
    return RelOptUtil.isDistinctFrom(getRexBuilder(), operand0, operand1, true);
  }

  /** Creates an expression equivalent to {@code o0 IS DISTINCT FROM o1}.
   * It is also equivalent to
   * "{@code NOT (o0 = o1 OR (o0 IS NULL AND o1 IS NULL))}. */
  public RexNode isDistinctFrom(RexNode operand0, RexNode operand1) {
    return RelOptUtil.isDistinctFrom(getRexBuilder(), operand0, operand1, false);
  }

  /** Creates a {@code BETWEEN}. */
  public RexNode between(RexNode arg, RexNode lower, RexNode upper) {
    return getRexBuilder().makeBetween(arg, lower, upper);
  }

  /** Creates ab {@code IS NULL}. */
  public RexNode isNull(RexNode operand) {
    return call(SqlStdOperatorTable.IS_NULL, operand);
  }

  /** Creates an {@code IS NOT NULL}. */
  public RexNode isNotNull(RexNode operand) {
    return call(SqlStdOperatorTable.IS_NOT_NULL, operand);
  }

  /** Creates an expression that casts an expression to a given type. */
  public RexNode cast(RexNode expr, SqlTypeName typeName) {
    final RelDataType type = cluster.getTypeFactory().createSqlType(typeName);
    return cluster.getRexBuilder().makeCast(type, expr);
  }

  /** Creates an expression that casts an expression to a type with a given name
   * and precision or length. */
  public RexNode cast(RexNode expr, SqlTypeName typeName, int precision) {
    final RelDataType type =
        cluster.getTypeFactory().createSqlType(typeName, precision);
    return cluster.getRexBuilder().makeCast(type, expr);
  }

  /** Creates an expression that casts an expression to a type with a given
   * name, precision and scale. */
  public RexNode cast(RexNode expr, SqlTypeName typeName, int precision,
      int scale) {
    final RelDataType type =
        cluster.getTypeFactory().createSqlType(typeName, precision, scale);
    return cluster.getRexBuilder().makeCast(type, expr);
  }

  /**
   * Returns an expression wrapped in an alias.
   *
   * <p>This method is idempotent: If the expression is already wrapped in the
   * correct alias, does nothing; if wrapped in an incorrect alias, removes
   * the incorrect alias and applies the correct alias.
   *
   * @see #project
   */
  // 核心作用是：将一个行表达式（RexNode）包装在一个指定名称的别名（Alias）中，其行为相当于 SQL 中的 expr AS alias。
  // RexNode expr：输入的行表达式（可以是字段引用 RexInputRef、函数调用 RexCall、或者已经是带有 AS 的表达式）。
  // String alias：期望为这个表达式指定的新的别名字符串。
  public RexNode alias(RexNode expr, String alias) {
    // 将字符串别名转化为字面量节点
    final RexNode aliasLiteral = literal(alias);
    switch (expr.getKind()) {
    // 获取当前表达式的语法类型（SqlKind）
    case AS:
      // 说明传入的 expr 已经是一个别名表达式了（即类似 x AS old_alias）。在 Calcite 中，AS 被视为一个函数调用（RexCall），它有两个子操作数（Operands）：
      // call.operands.get(0)：被起别名的原始表达式（x）。
      // call.operands.get(1)：当前的旧别名字面量（old_alias）。
      final RexCall call = (RexCall) expr;
      if (call.operands.get(1).equals(aliasLiteral)) {
        // current alias is correct
        return expr;
      }
      expr = call.operands.get(0);
      // strip current (incorrect) alias, and fall through
    default:
      return call(SqlStdOperatorTable.AS, expr, aliasLiteral);
    }
  }

  private RexNode aliasMaybe(RexNode node, @Nullable String name) {
    return name == null ? node : alias(node, name);
  }

  /** Converts a sort expression to descending. */
  public RexNode desc(RexNode node) {
    return call(SqlStdOperatorTable.DESC, node);
  }

  /** Converts a sort expression to nulls last. */
  public RexNode nullsLast(RexNode node) {
    return call(SqlStdOperatorTable.NULLS_LAST, node);
  }

  /** Converts a sort expression to nulls first. */
  public RexNode nullsFirst(RexNode node) {
    return call(SqlStdOperatorTable.NULLS_FIRST, node);
  }

  // Methods that create window bounds

  /** Creates an {@code UNBOUNDED PRECEDING} window bound,
   * for use in methods such as {@link OverCall#rowsFrom(RexWindowBound)}
   * and {@link OverCall#rangeBetween(RexWindowBound, RexWindowBound)}. */
  public RexWindowBound unboundedPreceding() {
    return RexWindowBounds.UNBOUNDED_PRECEDING;
  }

  /** Creates a {@code bound PRECEDING} window bound,
   * for use in methods such as {@link OverCall#rowsFrom(RexWindowBound)}
   * and {@link OverCall#rangeBetween(RexWindowBound, RexWindowBound)}. */
  public RexWindowBound preceding(RexNode bound) {
    return RexWindowBounds.preceding(bound);
  }

  /** Creates a {@code CURRENT ROW} window bound,
   * for use in methods such as {@link OverCall#rowsFrom(RexWindowBound)}
   * and {@link OverCall#rangeBetween(RexWindowBound, RexWindowBound)}. */
  public RexWindowBound currentRow() {
    return RexWindowBounds.CURRENT_ROW;
  }

  /** Creates a {@code bound FOLLOWING} window bound,
   * for use in methods such as {@link OverCall#rowsFrom(RexWindowBound)}
   * and {@link OverCall#rangeBetween(RexWindowBound, RexWindowBound)}. */
  public RexWindowBound following(RexNode bound) {
    return RexWindowBounds.following(bound);
  }

  /** Creates an {@code UNBOUNDED FOLLOWING} window bound,
   * for use in methods such as {@link OverCall#rowsFrom(RexWindowBound)}
   * and {@link OverCall#rangeBetween(RexWindowBound, RexWindowBound)}. */
  public RexWindowBound unboundedFollowing() {
    return RexWindowBounds.UNBOUNDED_FOLLOWING;
  }

  // Methods that create group keys and aggregate calls

  /** Creates an empty group key. */
  public GroupKey groupKey() {
    return groupKey(ImmutableList.of());
  }

  /** Creates a group key. */
  public GroupKey groupKey(RexNode... nodes) {
    return groupKey(ImmutableList.copyOf(nodes));
  }

  /** Creates a group key. */
  public GroupKey groupKey(Iterable<? extends RexNode> nodes) {
    return new GroupKeyImpl(ImmutableList.copyOf(nodes), null, null);
  }

  /** Creates a group key with grouping sets. */
  public GroupKey groupKey(Iterable<? extends RexNode> nodes,
      Iterable<? extends Iterable<? extends RexNode>> nodeLists) {
    return groupKey_(nodes, nodeLists);
  }

  // CHECKSTYLE: IGNORE 1
  /** @deprecated Now that indicator is deprecated, use
   * {@link #groupKey(Iterable, Iterable)}, which has the same behavior as
   * calling this method with {@code indicator = false}. */
  @Deprecated // to be removed before 2.0
  public GroupKey groupKey(Iterable<? extends RexNode> nodes, boolean indicator,
      Iterable<? extends Iterable<? extends RexNode>> nodeLists) {
    Aggregate.checkIndicator(indicator);
    return groupKey_(nodes, nodeLists);
  }

  private static GroupKey groupKey_(Iterable<? extends RexNode> nodes,
      Iterable<? extends Iterable<? extends RexNode>> nodeLists) {
    final ImmutableList.Builder<ImmutableList<RexNode>> builder =
        ImmutableList.builder();
    for (Iterable<? extends RexNode> nodeList : nodeLists) {
      builder.add(ImmutableList.copyOf(nodeList));
    }
    return new GroupKeyImpl(ImmutableList.copyOf(nodes), builder.build(), null);
  }

  /** Creates a group key of fields identified by ordinal. */
  public GroupKey groupKey(int... fieldOrdinals) {
    return groupKey(fields(ImmutableIntList.of(fieldOrdinals)));
  }

  /** Creates a group key of fields identified by name. */
  public GroupKey groupKey(String... fieldNames) {
    return groupKey(fields(ImmutableList.copyOf(fieldNames)));
  }

  /** Creates a group key, identified by field positions
   * in the underlying relational expression.
   *
   * <p>This method of creating a group key does not allow you to group on new
   * expressions, only column projections, but is efficient, especially when you
   * are coming from an existing {@link Aggregate}. */
  public GroupKey groupKey(ImmutableBitSet groupSet) {
    return groupKey_(groupSet, ImmutableList.of(groupSet));
  }

  /** Creates a group key with grouping sets, both identified by field positions
   * in the underlying relational expression.
   *
   * <p>This method of creating a group key does not allow you to group on new
   * expressions, only column projections, but is efficient, especially when you
   * are coming from an existing {@link Aggregate}.
   *
   * <p>It is possible for {@code groupSet} to be strict superset of all
   * {@code groupSets}. For example, in the pseudo SQL
   *
   * <pre>{@code
   * GROUP BY 0, 1, 2
   * GROUPING SETS ((0, 1), 0)
   * }</pre>
   *
   * <p>column 2 does not appear in either grouping set. This is not valid SQL.
   * We can approximate in actual SQL by adding an extra grouping set and
   * filtering out using {@code HAVING}, as follows:
   *
   * <pre>{@code
   * GROUP BY GROUPING SETS ((0, 1, 2), (0, 1), 0)
   * HAVING GROUPING_ID(0, 1, 2) <> 0
   * }</pre>
   */
  public GroupKey groupKey(ImmutableBitSet groupSet,
      Iterable<? extends ImmutableBitSet> groupSets) {
    return groupKey_(groupSet, ImmutableList.copyOf(groupSets));
  }

  // CHECKSTYLE: IGNORE 1
  /** @deprecated Use {@link #groupKey(ImmutableBitSet, Iterable)}. */
  @Deprecated // to be removed before 2.0
  public GroupKey groupKey(ImmutableBitSet groupSet, boolean indicator,
      @Nullable ImmutableList<ImmutableBitSet> groupSets) {
    Aggregate.checkIndicator(indicator);
    return groupKey_(groupSet, groupSets == null
        ? ImmutableList.of(groupSet) : ImmutableList.copyOf(groupSets));
  }

  private GroupKey groupKey_(ImmutableBitSet groupSet,
      ImmutableList<ImmutableBitSet> groupSets) {
    if (groupSet.length() > peek().getRowType().getFieldCount()) {
      throw new IllegalArgumentException("out of bounds: " + groupSet);
    }
    requireNonNull(groupSets, "groupSets");
    final ImmutableList<RexNode> nodes = fields(groupSet);
    return groupKey_(nodes, Util.transform(groupSets, this::fields));
  }

  @Deprecated // to be removed before 2.0
  public AggCall aggregateCall(SqlAggFunction aggFunction, boolean distinct,
      RexNode filter, @Nullable String alias, RexNode... operands) {
    return aggregateCall(aggFunction, distinct, false, false, filter, null,
        ImmutableList.of(), alias, ImmutableList.of(),
        ImmutableList.copyOf(operands));
  }

  @Deprecated // to be removed before 2.0
  public AggCall aggregateCall(SqlAggFunction aggFunction, boolean distinct,
      boolean approximate, RexNode filter, @Nullable String alias,
      RexNode... operands) {
    return aggregateCall(aggFunction, distinct, approximate, false, filter,
        null, ImmutableList.of(), alias, ImmutableList.of(),
        ImmutableList.copyOf(operands));
  }

  @Deprecated // to be removed before 2.0
  public AggCall aggregateCall(SqlAggFunction aggFunction, boolean distinct,
      RexNode filter, @Nullable String alias,
      Iterable<? extends RexNode> operands) {
    return aggregateCall(aggFunction, distinct, false, false, filter, null,
        ImmutableList.of(), alias, ImmutableList.of(),
        ImmutableList.copyOf(operands));
  }

  @Deprecated // to be removed before 2.0
  public AggCall aggregateCall(SqlAggFunction aggFunction, boolean distinct,
      boolean approximate, RexNode filter, @Nullable String alias,
      Iterable<? extends RexNode> operands) {
    return aggregateCall(aggFunction, distinct, approximate, false, filter,
        null, ImmutableList.of(), alias, ImmutableList.of(),
        ImmutableList.copyOf(operands));
  }

  /** Creates a call to an aggregate function.
   *
   * <p>To add other operands, apply
   * {@link AggCall#distinct()},
   * {@link AggCall#approximate(boolean)},
   * {@link AggCall#filter(RexNode...)},
   * {@link AggCall#sort},
   * {@link AggCall#as} to the result. */
  public AggCall aggregateCall(SqlAggFunction aggFunction,
      Iterable<? extends RexNode> operands) {
    return aggregateCall(aggFunction, false, false, false, null, null,
        ImmutableList.of(), null, ImmutableList.of(),
        ImmutableList.copyOf(operands));
  }

  /** Creates a call to an aggregate function.
   *
   * <p>To add other operands, apply
   * {@link AggCall#distinct()},
   * {@link AggCall#approximate(boolean)},
   * {@link AggCall#filter(RexNode...)},
   * {@link AggCall#sort},
   * {@link AggCall#as} to the result. */
  public AggCall aggregateCall(SqlAggFunction aggFunction,
      RexNode... operands) {
    return aggregateCall(aggFunction, false, false, false, null, null,
        ImmutableList.of(), null, ImmutableList.of(),
        ImmutableList.copyOf(operands));
  }

  /** Creates a call to an aggregate function as a copy of an
   * {@link AggregateCall}. */
  public AggCall aggregateCall(AggregateCall a) {
    return aggregateCall(a.getAggregation(), a.isDistinct(), a.isApproximate(),
        a.ignoreNulls(), a.filterArg < 0 ? null : field(a.filterArg),
        a.distinctKeys == null ? null : fields(a.distinctKeys),
        fields(a.collation), a.name, ImmutableList.copyOf(a.rexList),
        fields(a.getArgList()));
  }

  /** Creates a call to an aggregate function as a copy of an
   * {@link AggregateCall}, applying a mapping. */
  public AggCall aggregateCall(AggregateCall a, Mapping mapping) {
    return aggregateCall(a.getAggregation(), a.isDistinct(), a.isApproximate(),
        a.ignoreNulls(),
        a.filterArg < 0 ? null : field(Mappings.apply(mapping, a.filterArg)),
        a.distinctKeys == null ? null
            : fields(Mappings.apply(mapping, a.distinctKeys)),
        fields(RexUtil.apply(mapping, a.collation)), a.name,
        ImmutableList.copyOf(a.rexList),
        fields(Mappings.apply2(mapping, a.getArgList())));
  }

  /** Creates a call to an aggregate function with all applicable operands. */
  protected AggCall aggregateCall(SqlAggFunction aggFunction, boolean distinct,
      boolean approximate, boolean ignoreNulls, @Nullable RexNode filter,
      @Nullable ImmutableList<RexNode> distinctKeys,
      ImmutableList<RexNode> orderKeys, @Nullable String alias,
      ImmutableList<RexNode> preOperands, ImmutableList<RexNode> operands) {
    return new AggCallImpl(aggFunction, distinct, approximate, ignoreNulls,
        filter, alias, preOperands, operands, distinctKeys, orderKeys);
  }

  /** Creates a call to the {@code COUNT} aggregate function. */
  public AggCall count(RexNode... operands) {
    return count(false, null, operands);
  }

  /** Creates a call to the {@code COUNT} aggregate function. */
  public AggCall count(Iterable<? extends RexNode> operands) {
    return count(false, null, operands);
  }

  /** Creates a call to the {@code COUNT} aggregate function,
   * optionally distinct and with an alias. */
  public AggCall count(boolean distinct, @Nullable String alias,
      RexNode... operands) {
    return aggregateCall(SqlStdOperatorTable.COUNT, distinct, false, false, null,
        null, ImmutableList.of(), alias, ImmutableList.of(),
        ImmutableList.copyOf(operands));
  }

  /** Creates a call to the {@code COUNT} aggregate function,
   * optionally distinct and with an alias. */
  public AggCall count(boolean distinct, @Nullable String alias,
      Iterable<? extends RexNode> operands) {
    return aggregateCall(SqlStdOperatorTable.COUNT, distinct, false, false, null,
        null, ImmutableList.of(), alias, ImmutableList.of(),
        ImmutableList.copyOf(operands));
  }

  /** Creates a call to the {@code COUNT(*)} aggregate function. */
  public AggCall countStar(@Nullable String alias) {
    return count(false, alias);
  }

  /** Creates a call to the {@code SUM} aggregate function. */
  public AggCall sum(RexNode operand) {
    return sum(false, null, operand);
  }

  /** Creates a call to the {@code SUM} aggregate function,
   * optionally distinct and with an alias. */
  public AggCall sum(boolean distinct, @Nullable String alias,
      RexNode operand) {
    return aggregateCall(SqlStdOperatorTable.SUM, distinct, false, false, null,
        null, ImmutableList.of(), alias, ImmutableList.of(),
        ImmutableList.of(operand));
  }

  /** Creates a call to the {@code AVG} aggregate function. */
  public AggCall avg(RexNode operand) {
    return avg(false, null, operand);
  }

  /** Creates a call to the {@code AVG} aggregate function,
   * optionally distinct and with an alias. */
  public AggCall avg(boolean distinct, @Nullable String alias,
      RexNode operand) {
    return aggregateCall(SqlStdOperatorTable.AVG, distinct, false, false, null,
        null, ImmutableList.of(), alias, ImmutableList.of(),
        ImmutableList.of(operand));
  }

  /** Creates a call to the {@code MIN} aggregate function. */
  public AggCall min(RexNode operand) {
    return min(null, operand);
  }

  /** Creates a call to the {@code MIN} aggregate function,
   * optionally with an alias. */
  public AggCall min(@Nullable String alias, RexNode operand) {
    return aggregateCall(SqlStdOperatorTable.MIN, false, false, false, null,
        null, ImmutableList.of(), alias, ImmutableList.of(),
        ImmutableList.of(operand));
  }

  /** Creates a call to the {@code MAX} aggregate function,
   * optionally with an alias. */
  public AggCall max(RexNode operand) {
    return max(null, operand);
  }

  /** Creates a call to the {@code MAX} aggregate function. */
  public AggCall max(@Nullable String alias, RexNode operand) {
    return aggregateCall(SqlStdOperatorTable.MAX, false, false, false, null,
        null, ImmutableList.of(), alias, ImmutableList.of(),
        ImmutableList.of(operand));
  }

  /** Creates a call to the {@code LITERAL_AGG} aggregate function. */
  public AggCall literalAgg(@Nullable Object value) {
    return aggregateCall(SqlInternalOperators.LITERAL_AGG)
        .preOperands(literal(value));
  }

  // Methods for patterns

  /**
   * Creates a reference to a given field of the pattern.
   *
   * @param alpha the pattern name
   * @param type Type of field
   * @param i Ordinal of field
   * @return Reference to field of pattern
   */
  public RexNode patternField(String alpha, RelDataType type, int i) {
    return getRexBuilder().makePatternFieldRef(alpha, type, i);
  }

  /** Creates a call that concatenates patterns;
   * for use in {@link #match}. */
  public RexNode patternConcat(Iterable<? extends RexNode> nodes) {
    final ImmutableList<RexNode> list = ImmutableList.copyOf(nodes);
    if (list.size() > 2) {
      // Convert into binary calls
      return patternConcat(patternConcat(Util.skipLast(list)), Util.last(list));
    }
    final RelDataType t = getTypeFactory().createSqlType(SqlTypeName.NULL);
    return getRexBuilder().makeCall(t, SqlStdOperatorTable.PATTERN_CONCAT,
        list);
  }

  /** Creates a call that concatenates patterns;
   * for use in {@link #match}. */
  public RexNode patternConcat(RexNode... nodes) {
    return patternConcat(ImmutableList.copyOf(nodes));
  }

  /** Creates a call that creates alternate patterns;
   * for use in {@link #match}. */
  public RexNode patternAlter(Iterable<? extends RexNode> nodes) {
    final RelDataType t = getTypeFactory().createSqlType(SqlTypeName.NULL);
    return getRexBuilder().makeCall(t, SqlStdOperatorTable.PATTERN_ALTER,
        ImmutableList.copyOf(nodes));
  }

  /** Creates a call that creates alternate patterns;
   * for use in {@link #match}. */
  public RexNode patternAlter(RexNode... nodes) {
    return patternAlter(ImmutableList.copyOf(nodes));
  }

  /** Creates a call that creates quantify patterns;
   * for use in {@link #match}. */
  public RexNode patternQuantify(Iterable<? extends RexNode> nodes) {
    final RelDataType t = getTypeFactory().createSqlType(SqlTypeName.NULL);
    return getRexBuilder().makeCall(t, SqlStdOperatorTable.PATTERN_QUANTIFIER,
        ImmutableList.copyOf(nodes));
  }

  /** Creates a call that creates quantify patterns;
   * for use in {@link #match}. */
  public RexNode patternQuantify(RexNode... nodes) {
    return patternQuantify(ImmutableList.copyOf(nodes));
  }

  /** Creates a call that creates permute patterns;
   * for use in {@link #match}. */
  public RexNode patternPermute(Iterable<? extends RexNode> nodes) {
    final RelDataType t = getTypeFactory().createSqlType(SqlTypeName.NULL);
    return getRexBuilder().makeCall(t, SqlStdOperatorTable.PATTERN_PERMUTE,
        ImmutableList.copyOf(nodes));
  }

  /** Creates a call that creates permute patterns;
   * for use in {@link #match}. */
  public RexNode patternPermute(RexNode... nodes) {
    return patternPermute(ImmutableList.copyOf(nodes));
  }

  /** Creates a call that creates an exclude pattern;
   * for use in {@link #match}. */
  public RexNode patternExclude(RexNode node) {
    final RelDataType t = getTypeFactory().createSqlType(SqlTypeName.NULL);
    return getRexBuilder().makeCall(t, SqlStdOperatorTable.PATTERN_EXCLUDE,
        ImmutableList.of(node));
  }

  // Methods that create relational expressions

  /** Creates a {@link TableScan} of the table
   * with a given name.
   *
   * <p>Throws if the table does not exist.
   *
   * <p>Returns this builder.
   *
   * @param tableNames Name of table (can optionally be qualified)
   */
  public RelBuilder scan(Iterable<String> tableNames) {
    final List<String> names = ImmutableList.copyOf(tableNames);
    requireNonNull(relOptSchema, "relOptSchema");
    final RelOptTable relOptTable = relOptSchema.getTableForMember(names);
    if (relOptTable == null) {
      throw RESOURCE.tableNotFound(String.join(".", names)).ex();
    }
    final RelNode scan =
        struct.scanFactory.createScan(        //通过scan工厂创建关系节点
            ViewExpanders.toRelContext(viewExpander, cluster),
            relOptTable);
    push(scan);
    rename(relOptTable.getRowType().getFieldNames());

    // When the node is not a TableScan but from expansion,
    // we need to explicitly add the alias.
    if (!(scan instanceof TableScan)) {
      as(Util.last(ImmutableList.copyOf(tableNames)));
    }
    return this;
  }

  /** Creates a {@link TableScan} of the table
   * with a given name.
   *
   * <p>Throws if the table does not exist.
   *
   * <p>Returns this builder.
   *
   * @param tableNames Name of table (can optionally be qualified)
   */
  public RelBuilder scan(String... tableNames) {
    return scan(ImmutableList.copyOf(tableNames));
  }

  /** Creates a {@link Snapshot} of a given snapshot period.
   *
   * <p>Returns this builder.
   *
   * @param period Name of table (can optionally be qualified)
   */
  public RelBuilder snapshot(RexNode period) {
    final Frame frame = stack.pop();
    final RelNode snapshot =
        struct.snapshotFactory.createSnapshot(frame.rel, period);
    stack.push(new Frame(snapshot, frame.fields));
    return this;
  }


  /**
   * Gets column mappings of the operator.
   *
   * @param op operator instance
   * @return column mappings associated with this function
   */
  private static @Nullable Set<RelColumnMapping> getColumnMappings(SqlOperator op) {
    SqlReturnTypeInference inference = op.getReturnTypeInference();
    if (inference instanceof TableFunctionReturnTypeInference) {
      return ((TableFunctionReturnTypeInference) inference).getColumnMappings();
    } else {
      return null;
    }
  }

  /**
   * Creates a RexCall to the {@code CURSOR} function by ordinal.
   *
   * @param inputCount Number of inputs
   * @param ordinal The reference to the relational input
   * @return RexCall to CURSOR function
   */
  public RexNode cursor(int inputCount, int ordinal) {
    if (inputCount <= ordinal || ordinal < 0) {
      throw new IllegalArgumentException("bad input count or ordinal");
    }
    // Refer to the "ordinal"th input as if it were a field
    // (because that's how things are laid out inside a TableFunctionScan)
    final RelNode input = peek(inputCount, ordinal);
    return call(SqlStdOperatorTable.CURSOR,
        getRexBuilder().makeInputRef(input.getRowType(), ordinal));
  }

  /** Creates a {@link TableFunctionScan}. */
  public RelBuilder functionScan(SqlOperator operator,
      int inputCount, RexNode... operands) {
    return functionScan(operator, inputCount, ImmutableList.copyOf(operands));
  }

  /** Creates a {@link TableFunctionScan}. */
  public RelBuilder functionScan(SqlOperator operator,
      int inputCount, Iterable<? extends RexNode> operands) {
    if (inputCount < 0 || inputCount > stack.size()) {
      throw new IllegalArgumentException("bad input count");
    }

    // Gets inputs.
    final List<RelNode> inputs = new ArrayList<>();
    for (int i = 0; i < inputCount; i++) {
      inputs.add(0, build());
    }

    final RexCall call = call(operator, ImmutableList.copyOf(operands));
    final RelNode functionScan =
        struct.tableFunctionScanFactory.createTableFunctionScan(cluster,
            inputs, call, null, getColumnMappings(operator));
    push(functionScan);
    return this;
  }

  /** Creates a {@link Filter} of an array of
   * predicates.
   *
   * <p>The predicates are combined using AND,
   * and optimized in a similar way to the {@link #and} method.
   * If the result is TRUE no filter is created. */
  public RelBuilder filter(RexNode... predicates) {
    return filter(ImmutableSet.of(), ImmutableList.copyOf(predicates));
  }

  /** Creates a {@link Filter} of a list of
   * predicates.
   *
   * <p>The predicates are combined using AND,
   * and optimized in a similar way to the {@link #and} method.
   * If the result is TRUE no filter is created. */
  public RelBuilder filter(Iterable<? extends RexNode> predicates) {
    return filter(ImmutableSet.of(), predicates);
  }

  /** Creates a {@link Filter} of a list of correlation variables
   * and an array of predicates.
   *
   * <p>The predicates are combined using AND,
   * and optimized in a similar way to the {@link #and} method.
   * If the result is TRUE no filter is created. */
  public RelBuilder filter(Iterable<CorrelationId> variablesSet,
      RexNode... predicates) {
    return filter(variablesSet, ImmutableList.copyOf(predicates));
  }

  /**
   * Creates a {@link Filter} of a list of correlation variables
   * and a list of predicates.
   *
   * <p>The predicates are combined using AND,
   * and optimized in a similar way to the {@link #and} method.
   * If simplification is on and the result is TRUE, no filter is created. */
  public RelBuilder filter(Iterable<CorrelationId> variablesSet,
      Iterable<? extends RexNode> predicates) {
    final RexNode conjunctionPredicates;
    if (config.simplify()) {
      conjunctionPredicates = simplifier.simplifyFilterPredicates(predicates);
    } else {
      conjunctionPredicates =
          RexUtil.composeConjunction(simplifier.rexBuilder, predicates);
    }

    if (conjunctionPredicates == null || conjunctionPredicates.isAlwaysFalse()) {
      return empty();
    }
    if (conjunctionPredicates.isAlwaysTrue()) {
      return this;
    }

    final Frame frame = stack.pop();
    final RelNode filter =
        struct.filterFactory.createFilter(frame.rel,
            conjunctionPredicates, ImmutableSet.copyOf(variablesSet));
    stack.push(new Frame(filter, frame.fields));
    return this;
  }

  /** Creates a {@link Project} of the given
   * expressions. */
  public RelBuilder project(RexNode... nodes) {
    return project(ImmutableList.copyOf(nodes));
  }

  /** Creates a {@link Project} of the given list
   * of expressions.
   *
   * <p>Infers names as would {@link #project(Iterable, Iterable)} if all
   * suggested names were null.
   *
   * @param nodes Expressions
   */
  public RelBuilder project(Iterable<? extends RexNode> nodes) {
    return project(nodes, ImmutableList.of());
  }

  /** Creates a {@link Project} of the given list
   * of expressions and field names.
   *
   * @param nodes Expressions
   * @param fieldNames field names for expressions
   */
  public RelBuilder project(Iterable<? extends RexNode> nodes,
      Iterable<? extends @Nullable String> fieldNames) {
    return project(nodes, fieldNames, false);
  }

  /** Creates a {@link Project} of the given list
   * of expressions, using the given names.
   *
   * <p>Names are deduced as follows:
   * <ul>
   *   <li>If the length of {@code fieldNames} is greater than the index of
   *     the current entry in {@code nodes}, and the entry in
   *     {@code fieldNames} is not null, uses it; otherwise
   *   <li>If an expression projects an input field,
   *     or is a cast an input field,
   *     uses the input field name; otherwise
   *   <li>If an expression is a call to
   *     {@link SqlStdOperatorTable#AS}
   *     (see {@link #alias}), removes the call but uses the intended alias.
   * </ul>
   *
   * <p>After the field names have been inferred, makes the
   * field names unique by appending numeric suffixes.
   *
   * @param nodes Expressions
   * @param fieldNames Suggested field names
   * @param force create project even if it is identity
   */
  public RelBuilder project(Iterable<? extends RexNode> nodes,
      Iterable<? extends @Nullable String> fieldNames, boolean force) {
    return project(nodes, fieldNames, force, ImmutableSet.of());
  }

  /**
   * The same with {@link #project(Iterable, Iterable, boolean)}, with additional
   * variablesSet param.
   *
   * @param nodes Expressions
   * @param fieldNames Suggested field names
   * @param force create project even if it is identity
   * @param variablesSet Correlating variables that are set when reading a row
   *                     from the input, and which may be referenced from the
   *                     projection expressions
   */
  public RelBuilder project(Iterable<? extends RexNode> nodes,
      Iterable<? extends @Nullable String> fieldNames, boolean force,
      Iterable<CorrelationId> variablesSet) {
    return project_(nodes, fieldNames, ImmutableList.of(), force, variablesSet);
  }

  /** Creates a {@link Project} of all original fields, plus the given
   * expressions. */
  public RelBuilder projectPlus(RexNode... nodes) {
    return projectPlus(ImmutableList.copyOf(nodes));
  }

  /** Creates a {@link Project} of all original fields, plus the given list of
   * expressions. */
  public RelBuilder projectPlus(Iterable<? extends RexNode> nodes) {
    return project(Iterables.concat(fields(), nodes));
  }

  /** Creates a {@link Project} of all original fields, except the given
   * expressions.
   *
   * @throws IllegalArgumentException if the given expressions contain duplicates
   *    or there is an expression that does not match an existing field
   */
  public RelBuilder projectExcept(RexNode... expressions) {
    return projectExcept(ImmutableList.copyOf(expressions));
  }

  /** Creates a {@link Project} of all original fields, except the given list of
   * expressions.
   *
   * @throws IllegalArgumentException if the given expressions contain duplicates
   *    or there is an expression that does not match an existing field
   */
  public RelBuilder projectExcept(Iterable<RexNode> expressions) {
    List<RexNode> allExpressions = new ArrayList<>(fields());
    Set<RexNode> excludeExpressions = new HashSet<>();
    for (RexNode excludeExp : expressions) {
      if (!excludeExpressions.add(excludeExp)) {
        throw new IllegalArgumentException(
          "Input list contains duplicates. Expression " + excludeExp + " exists multiple times.");
      }
      if (!allExpressions.remove(excludeExp)) {
        throw new IllegalArgumentException("Expression " + excludeExp.toString() + " not found.");
      }
    }
    return this.project(allExpressions);
  }

  /** Creates a {@link Project} of the given list
   * of expressions, using the given names.
   *
   * <p>Names are deduced as follows:
   * <ul>
   *   <li>If the length of {@code fieldNames} is greater than the index of
   *     the current entry in {@code nodes}, and the entry in
   *     {@code fieldNames} is not null, uses it; otherwise
   *   <li>If an expression projects an input field,
   *     or is a cast an input field,
   *     uses the input field name; otherwise
   *   <li>If an expression is a call to
   *     {@link SqlStdOperatorTable#AS}
   *     (see {@link #alias}), removes the call but uses the intended alias.
   * </ul>
   *
   * <p>After the field names have been inferred, makes the
   * field names unique by appending numeric suffixes.
   *
   * @param nodes Expressions
   * @param fieldNames Suggested field names
   * @param hints Hints
   * @param force create project even if it is identity
   */
  // 负责实例化 Project 节点，还承担了投影消除、投影合并（Project Merge）、表达式化简（Simplify）、别名自动推导、以及列名唯一性去重（Unique Suffix）等极其关键的引擎级优化。
  //
  private RelBuilder project_(
      // 投影表达式列表（行级表达式）
      // 定义了最终 SELECT 出来的具体列计算逻辑，例如 [$0, +($1, 10)]（表示保留第一列，并将第二列加 10）。
      Iterable<? extends RexNode> nodes,
      // 调用方建议/期望的列别名列表。
      // 如果用户显式指定了别名，则尽可能使用；列表中允许包含 null，表示该列需要由 Calcite 引擎自动推导或生成默认列名。
      Iterable<? extends @Nullable String> fieldNames,
      // 将用户在 SQL 中书写的 Hint 提示（如 /*+ HINT_NAME */）绑定到新生成的 Project 算子上，向下游物理层传递。
      Iterable<RelHint> hints,
      // 若为 false，当检测到当前投影是“恒等投影”（Identity Project，即没有任何计算、没有改变任何列名和排序，只是原样透传数据）时，引擎会实施优化，直接跳过不创建该 Project；若为 true，则不论如何都必须生成一个物理物理节点。
      boolean force,
      // 用于相关子查询。若当前投影算子内部定义或透传了某些关联变量（如 $cor0），需要通过该参数显式注册。
      Iterable<CorrelationId> variablesSet) {
    // peek_() 从 RelBuilder 的核心算子栈（Stack）中弹出当前栈顶的 Frame，作为当前 Project 的输入孩子节点（Input）
    final Frame frame = requireNonNull(peek_(), "frame stack is empty");
    final RelDataType inputRowType = frame.rel.getRowType();
    final List<RexNode> nodeList = Lists.newArrayList(nodes);
    final Set<CorrelationId> variables = ImmutableSet.copyOf(variablesSet);

    // Perform a quick check for identity. We'll do a deeper check
    // later when we've derived column names.
    // 快速恒等投影检查（第一轮）
    if (!force && Iterables.isEmpty(fieldNames)
        && RexUtil.isIdentity(nodeList, inputRowType)) {
      return this;
    }
    // 列名容器对齐
    final List<@Nullable String> fieldNameList = Lists.newArrayList(fieldNames);
    while (fieldNameList.size() < nodeList.size()) {
      fieldNameList.add(null);
    }

    // Do not merge projection when top projection has correlation variables
    // 如果当前 Project 的下方紧挨着的也是一个 Project（即出现了 Project(Project(X)) 的“套娃”结构），
    // 在满足膨胀阈值约束且没有复杂的关联变量时，尝试将其合并为一个单层的 Project。
    bloat:
    if (frame.rel instanceof Project
        && config.bloat() >= 0
        && variables.isEmpty()) {
      final Project project = (Project) frame.rel;
      // Populate field names. If the upper expression is an input ref and does
      // not have a recommended name, use the name of the underlying field.
      for (int i = 0; i < fieldNameList.size(); i++) {
        if (fieldNameList.get(i) == null) {
          final RexNode node = nodeList.get(i);
          // 如果上层列没有起别名，且它只是简单引用了下层 Project 的某个字段（RexInputRef），则直接抓取并继承下层 Project 对应字段的名字。
          if (node instanceof RexInputRef) {
            final RexInputRef ref = (RexInputRef) node;
            fieldNameList.set(i,
                project.getRowType().getFieldNames().get(ref.getIndex()));
          }
        }
      }
      // 调用底层工具方法将上层的表达式和下层的表达式进行代数复合（Inline）。如果复合后导致表达式过于臃肿，超出了 config.bloat() 设定的阈值，则放弃合并。
      final List<RexNode> newNodes =
          RelOptUtil.pushPastProjectUnlessBloat(nodeList, project,
              config.bloat());
      if (newNodes == null) {
        // The merged expression is more complex than the input expressions.
        // Do not merge.
        break bloat;
      }

      // Carefully build a list of fields, so that table aliases from the input
      // can be seen for fields that are based on a RexInputRef.
      // 维护表别名（Table Alias）并递归
      // 合并成功后，需要小心地将原下层栈帧的表别名、表空间信息无缝传递给最底层的 Input。
      // 随后把合并出来的 newNodes、合并后的 Hint 集合连同底层 Project 自身的变量集，反手重新触发一次 project_ 方法，实现多层 Project 的连环合并
      final Frame frame1 = stack.pop();
      final PairList<ImmutableSet<String>, RelDataTypeField> fields =
          PairList.of();
      project.getInput().getRowType().getFieldList()
          .forEach(f -> fields.add(ImmutableSet.of(), f));
      for (Pair<RexNode, ImmutableSet<String>> pair
          : Pair.zip(project.getProjects(), frame1.fields.leftList())) {
        switch (pair.left.getKind()) {
        case INPUT_REF:
          final int i = ((RexInputRef) pair.left).getIndex();
          fields.set(i, pair.right, fields.rightList().get(i));
          break;
        default:
          break;
        }
      }
      stack.push(new Frame(project.getInput(), fields));
      final ImmutableSet.Builder<RelHint> mergedHints = ImmutableSet.builder();
      mergedHints.addAll(project.getHints());
      mergedHints.addAll(hints);
      // Keep bottom projection's variablesSet.
      // 核心：把融合后的 newNodes 递归再次调用 project_ 方法
      return project_(newNodes, fieldNameList, mergedHints.build(), force,
          ImmutableSet.copyOf(project.getVariablesSet()));
    }

    // Simplify expressions.
    // 表达式简化
    if (config.simplify()) {
      nodeList.replaceAll(e -> simplifier.simplifyPreservingType(e));
    }

    // Replace null names with generated aliases.
    // 默认别名推导（Infer Alias）
    // 对于那些用户没有提供别名（仍为 null）的列，调用 inferAlias 方法根据表达式的特征去智能推导。如果是字段引用就推导为原字段名，如果是函数调用（如 COUNT(*)）则生成系统内部的默认别名（如 EXPR$i）。
    for (int i = 0; i < fieldNameList.size(); i++) {
      if (fieldNameList.get(i) == null) {
        fieldNameList.set(i, inferAlias(nodeList, nodeList.get(i), i));
      }
    }
    // 最终列名唯一性去重（Unique Suffix 后缀处理）与字段生成
    // 引擎在此处通过 uniqueNameList 判定当前列名是否碰撞。如果发生了重名，则调用系统的 F_SUGGESTER 建议器自动为其追加数字后缀（例如将第二个 id 改为 id0、id1），
    // 确保所有最终输出列名绝不冲突。根据系统配置，该过程会自动适配大小写敏感度。
    final PairList<ImmutableSet<String>, RelDataTypeField> fields =
        PairList.of();
    final Set<String> uniqueNameList =
        getTypeFactory().getTypeSystem().isSchemaCaseSensitive()
        ? new HashSet<>()
        : new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    // calculate final names and build field list
    for (int i = 0; i < fieldNameList.size(); ++i) {
      final RexNode node = nodeList.get(i);
      String name = fieldNameList.get(i);
      String originalName = name;
      if (name == null || uniqueNameList.contains(name)) {
        int j = 0;
        if (name == null) {
          j = i;
        }
        do {
          name = SqlValidatorUtil.F_SUGGESTER.apply(originalName, j, j++);
        } while (uniqueNameList.contains(name));
        fieldNameList.set(i, name);
      }
      // 表别名与数据类型装配
      // 为每一列实例化物理列类型 RelDataTypeFieldImpl。如果是纯粹的字段透传（INPUT_REF），
      // 则将下层对应的表、空间等别名集合（leftList）无缝地复制绑定给新列，保证后序的 RexInputRef 依然能找得到其所属的表别名源头。
      RelDataTypeField fieldType =
          new RelDataTypeFieldImpl(name, i, node.getType());
      switch (node.getKind()) {
      case INPUT_REF:
        // preserve rel aliases for INPUT_REF fields
        final int index = ((RexInputRef) node).getIndex();
        fields.add(frame.fields.leftList().get(index), fieldType);
        break;
      default:
        fields.add(ImmutableSet.of(), fieldType);
        break;
      }
      uniqueNameList.add(name);
    }
    // 恒等投影消除（第二轮深层判定）
    if (!force && RexUtil.isIdentity(nodeList, inputRowType)) {
      //  列名和计算全一致，不创建物理节点
      if (fieldNameList.equals(inputRowType.getFieldNames())) {
        // Do not create an identity project if it does not rename any fields
        return this;
      } else {
        // create "virtual" row type for project only rename fields
        // 仅仅改了列名：不创建物理 Project，而是用改名后的 fields 替换原栈顶 Frame
        stack.pop();
        // Ignore the hints.
        stack.push(new Frame(frame.rel, fields));
      }
      return this;
    }

    // If the expressions are all literals, and the input is a Values with N
    // rows (or an Aggregate with 1 row), replace with a Values with same tuple
    // N times.
    // 极限特殊优化：向 Values 算子退化
    final int rowCount;
    // 如果当前投影里所有的表达式全是纯粹的常量字面量（RexLiteral，例如 SELECT 1, 'apple', true FROM t）。
    // 且此时孩子节点吐出的行数是固定、可预知的（fixedRowCount >= 0，比如孩子是一个 Values 算子或者只有一条输出的 Aggregate 算子）。
    // 此时，直接把整个子树连根拔起（build() 抛弃原输入），直接将当前算子重构、退化为一个纯静态的 Values 静态常量表算子，
    // 里面存放 rowCount 个完全一样的常数元组。这样彻底免去了未来扫描真实物理表的庞大开销。
    if (config.simplifyValues()
        && nodeList.stream().allMatch(e -> e instanceof RexLiteral)
        && (rowCount = fixedRowCount(frame)) >= 0) {
      RelNode unused = build();
      final RelDataTypeFactory.Builder typeBuilder = getTypeFactory().builder();
      Pair.forEach(fieldNameList, nodeList, (name, expr) ->
          typeBuilder.add(requireNonNull(name, "name"), expr.getType()));
      @SuppressWarnings({"unchecked", "rawtypes"})
      final List<RexLiteral> tuple = (List<RexLiteral>) (List) nodeList;
      return values(Collections.nCopies(rowCount, tuple),
          typeBuilder.build());
    }
    // 物理建树与入栈
    // 调用底层的物理工厂 projectFactory.createProject 创建真正的 Project 算子节点。
    // 将栈顶的老孩子节点弹出（stack.pop()）。
    // 将新生成的 project 节点连同加工好的别名元数据 fields 组装成一个全新的 Frame，强行压入 RelBuilder 的核心算子栈顶，并返回 this 流式管道，宣告本轮投影构建圆满结束。
    final RelNode project =
        struct.projectFactory.createProject(frame.rel,
            ImmutableList.copyOf(hints),
            ImmutableList.copyOf(nodeList),
            fieldNameList,
            variables);
    stack.pop();
    stack.push(new Frame(project, fields));
    return this;
  }

  /** If current frame will return a known, constant number of
   * rows, returns that number; otherwise returns -1. */
  private static int fixedRowCount(Frame frame) {
    if (frame.rel instanceof Values) {
      return ((Values) frame.rel).tuples.size();
    }
    if (frame.rel instanceof Aggregate) {
      final Aggregate aggregate = (Aggregate) frame.rel;
      if (aggregate.getGroupSet().isEmpty()
          && aggregate.getGroupType() == Aggregate.Group.SIMPLE) {
        return 1;
      }
    }
    return -1;
  }

  /** Creates a {@link Project} of the given
   * expressions and field names, and optionally optimizing.
   *
   * <p>If {@code fieldNames} is null, or if a particular entry in
   * {@code fieldNames} is null, derives field names from the input
   * expressions.
   *
   * <p>If {@code force} is false,
   * and the input is a {@code Project},
   * and the expressions  make the trivial projection ($0, $1, ...),
   * modifies the input.
   *
   * @param nodes       Expressions
   * @param fieldNames  Suggested field names, or null to generate
   * @param force       Whether to create a renaming Project if the
   *                    projections are trivial
   */
  public RelBuilder projectNamed(Iterable<? extends RexNode> nodes,
      @Nullable Iterable<? extends @Nullable String> fieldNames, boolean force) {
    return projectNamed(nodes, fieldNames, force, ImmutableSet.of());
  }

  /** Creates a {@link Project} of the given
   * expressions and field names, and optionally optimizing.
   *
   * <p>If {@code fieldNames} is null, or if a particular entry in
   * {@code fieldNames} is null, derives field names from the input
   * expressions.
   *
   * <p>If {@code force} is false,
   * and the input is a {@code Project},
   * and the expressions  make the trivial projection ($0, $1, ...),
   * modifies the input.
   *
   * @param nodes       Expressions
   * @param fieldNames  Suggested field names, or null to generate
   * @param force       Whether to create a renaming Project if the
   *                    projections are trivial
   * @param variablesSet Correlating variables that are set when reading a row
   *                     from the input, and which may be referenced from the
   *                     projection expressions
   */
  public RelBuilder projectNamed(Iterable<? extends RexNode> nodes,
      @Nullable Iterable<? extends @Nullable String> fieldNames, boolean force,
      Iterable<CorrelationId> variablesSet) {
    @SuppressWarnings({"unchecked", "rawtypes"})
    final List<? extends RexNode> nodeList =
        nodes instanceof List ? (List) nodes : ImmutableList.copyOf(nodes);
    final List<@Nullable String> fieldNameList =
        fieldNames == null ? null
          : fieldNames instanceof List ? (List<@Nullable String>) fieldNames
          : ImmutableNullableList.copyOf(fieldNames);
    final RelNode input = peek();
    final RelDataType rowType =
        RexUtil.createStructType(cluster.getTypeFactory(), nodeList,
            fieldNameList, SqlValidatorUtil.F_SUGGESTER);
    if (!force
        && RexUtil.isIdentity(nodeList, input.getRowType())) {
      if (input instanceof Project && fieldNames != null) {
        // Rename columns of child projection if desired field names are given.
        final Frame frame = stack.pop();
        final Project childProject = (Project) frame.rel;
        final Project newInput =
            childProject.copy(childProject.getTraitSet(),
                childProject.getInput(), childProject.getProjects(), rowType);
        stack.push(new Frame(newInput.attachHints(childProject.getHints()), frame.fields));
      }
      if (input instanceof Values && fieldNameList != null) {
        // Rename columns of child values if desired field names are given.
        final Frame frame = stack.pop();
        final Values values = (Values) frame.rel;
        final RelDataTypeFactory.Builder typeBuilder =
            getTypeFactory().builder();
        Pair.forEach(fieldNameList, rowType.getFieldList(), (name, field) ->
            typeBuilder.add(requireNonNull(name, "name"), field.getType()));
        final RelDataType newRowType = typeBuilder.build();
        final RelNode newValues =
            struct.valuesFactory.createValues(cluster, newRowType,
                values.tuples);
        stack.push(new Frame(newValues, frame.fields));
      }
    } else {
      project(nodeList, rowType.getFieldNames(), force, variablesSet);
    }
    return this;
  }

  /**
   * Creates an {@link Uncollect} with given item aliases.
   *
   * @param itemAliases   Operand item aliases, never null
   * @param withOrdinality If {@code withOrdinality}, the output contains an extra
   * {@code ORDINALITY} column
   */
  public RelBuilder uncollect(List<String> itemAliases, boolean withOrdinality) {
    Frame frame = stack.pop();
    stack.push(
        new Frame(
          new Uncollect(
            cluster,
            cluster.traitSetOf(Convention.NONE),
            frame.rel,
            withOrdinality,
            requireNonNull(itemAliases, "itemAliases"))));
    return this;
  }

  /** Ensures that the field names match those given.
   *
   * <p>If all fields have the same name, adds nothing;
   * if any fields do not have the same name, adds a {@link Project}.
   *
   * <p>Note that the names can be short-lived. Other {@code RelBuilder}
   * operations make no guarantees about the field names of the rows they
   * produce.
   * 用于确保符合给定的字段名称
   * @param fieldNames List of desired field names; may contain null values or
   * have fewer fields than the current row type
   */
  public RelBuilder rename(List<? extends @Nullable String> fieldNames) {
    final List<String> oldFieldNames = peek().getRowType().getFieldNames();
    checkArgument(fieldNames.size() <= oldFieldNames.size(),
        "More names than fields");
    final List<String> newFieldNames = new ArrayList<>(oldFieldNames);
    for (int i = 0; i < fieldNames.size(); i++) {
      final String s = fieldNames.get(i);
      if (s != null) {
        newFieldNames.set(i, s);
      }
    }
    if (oldFieldNames.equals(newFieldNames)) {
      return this;
    }
    if (peek() instanceof Values) {
      // Special treatment for VALUES. Re-build it rather than add a project.
      final Values v = (Values) build();
      final RelDataTypeFactory.Builder b = getTypeFactory().builder();
      for (Pair<String, RelDataTypeField> p
          : Pair.zip(newFieldNames, v.getRowType().getFieldList())) {
        b.add(p.left, p.right.getType());
      }
      return values(v.tuples, b.build());
    }

    return project(fields(), newFieldNames, true);
  }

  /** Infers the alias of an expression.
   *
   * <p>If the expression was created by {@link #alias}, replaces the expression
   * in the project list.
   */
  private @Nullable String inferAlias(List<RexNode> exprList, RexNode expr, int i) {
    switch (expr.getKind()) {
    case INPUT_REF:
      final RexInputRef ref = (RexInputRef) expr;
      return requireNonNull(stack.peek(), "empty frame stack")
          .fields.get(ref.getIndex()).getValue().getName();
    case CAST:
      return inferAlias(exprList, ((RexCall) expr).getOperands().get(0), -1);
    case AS:
      final RexCall call = (RexCall) expr;
      if (i >= 0) {
        exprList.set(i, call.getOperands().get(0));
      }
      NlsString value = (NlsString) ((RexLiteral) call.getOperands().get(1)).getValue();
      return castNonNull(value)
          .getValue();
    default:
      return null;
    }
  }

  /** Creates an {@link Aggregate} that makes the
   * relational expression distinct on all fields. */
  public RelBuilder distinct() {
    return aggregate_((GroupKeyImpl) groupKey(fields()), ImmutableList.of());
  }

  /** Creates an {@link Aggregate} with an array of
   * calls. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public RelBuilder aggregate(GroupKey groupKey, AggCall... aggCalls) {
    return aggregate_((GroupKeyImpl) groupKey,
        (ImmutableList) ImmutableList.copyOf(aggCalls));
  }

  /** Creates an {@link Aggregate} with an array of
   * {@link AggregateCall}s. */
  public RelBuilder aggregate(GroupKey groupKey,
      List<AggregateCall> aggregateCalls) {
    return aggregate_((GroupKeyImpl) groupKey,
        aggregateCalls.stream()
            .map(aggregateCall ->
                new AggCallImpl2(aggregateCall,
                    aggregateCall.getArgList().stream()
                        .map(this::field)
                        .collect(toImmutableList())))
            .collect(toImmutableList()));
  }

  /** Creates an {@link Aggregate} with multiple calls. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public RelBuilder aggregate(GroupKey groupKey,
      Iterable<? extends AggCall> aggCalls) {
    return aggregate_((GroupKeyImpl) groupKey,
        ImmutableList.<AggCallPlus>copyOf((Iterable) aggCalls));
  }

  /** Creates an {@link Aggregate} with multiple calls. */
  private RelBuilder aggregate_(GroupKeyImpl groupKey,
      final ImmutableList<AggCallPlus> aggCalls) {
    if (groupKey.nodes.isEmpty()
        && aggCalls.isEmpty()
        && config.pruneInputOfAggregate()) {
      // Query is "SELECT /* no fields */ FROM t GROUP BY ()", which always
      // returns one row with zero columns.
      if (config.preventEmptyFieldList()) {
        // Convert to "VALUES ROW(true)".
        return values(new String[] {"dummy"}, true);
      } else {
        // Convert to "VALUES ROW()".
        return values(ImmutableList.of(ImmutableList.of()),
            getTypeFactory().builder().build());
      }
    }
    final Registrar registrar =
        new Registrar(fields(), peek().getRowType().getFieldNames());
    final ImmutableBitSet groupSet =
        ImmutableBitSet.of(registrar.registerExpressions(groupKey.nodes));
    if (alreadyUnique(aggCalls, groupKey, groupSet, registrar.extraNodes)) {
      final List<RexNode> nodes = new ArrayList<>(fields(groupSet));
      aggCalls.forEach(c -> {
        final AggregateCall call = c.aggregateCall();
        final SqlStaticAggFunction staticFun =
            call.getAggregation().unwrapOrThrow(SqlStaticAggFunction.class);
        final RexNode node =
            staticFun.constant(getRexBuilder(), groupSet, ImmutableList.of(), call);
        nodes.add(aliasMaybe(requireNonNull(node, "node"), call.getName()));
      });
      return project(nodes);
    }

    ImmutableList<ImmutableBitSet> groupSets;
    if (groupKey.nodeLists != null) {
      final int sizeBefore = registrar.extraNodes.size();
      final List<ImmutableBitSet> groupSetList = new ArrayList<>();
      for (ImmutableList<RexNode> nodeList : groupKey.nodeLists) {
        final ImmutableBitSet groupSet2 =
            ImmutableBitSet.of(registrar.registerExpressions(nodeList));
        if (!groupSet.contains(groupSet2)) {
          throw new IllegalArgumentException("group set element " + nodeList
              + " must be a subset of group key");
        }
        groupSetList.add(groupSet2);
      }
      final ImmutableSortedMultiset<ImmutableBitSet> groupSetMultiset =
          ImmutableSortedMultiset.copyOf(ImmutableBitSet.COMPARATOR,
              groupSetList);
      if (aggCalls.stream().anyMatch(RelBuilder::isGroupId)
          || !ImmutableBitSet.ORDERING.isStrictlyOrdered(groupSetMultiset)) {
        return rewriteAggregateWithDuplicateGroupSets(groupSet, groupSetMultiset,
            aggCalls);
      }
      groupSets = ImmutableList.copyOf(groupSetMultiset.elementSet());
      if (registrar.extraNodes.size() > sizeBefore) {
        throw new IllegalArgumentException("group sets contained expressions "
            + "not in group key: "
            + Util.skip(registrar.extraNodes, sizeBefore));
      }
    } else {
      groupSets = ImmutableList.of(groupSet);
    }

    aggCalls.forEach(aggCall -> aggCall.register(registrar));
    project(registrar.extraNodes);
    rename(registrar.names);
    final Frame frame = stack.pop();
    RelNode r = frame.rel;
    final List<AggregateCall> aggregateCalls = new ArrayList<>();
    for (AggCallPlus aggCall : aggCalls) {
      AggregateCall aggregateCall = aggCall.aggregateCall(registrar, groupSet, r);
      if (groupSets.size() <= 1) {
        aggregateCall = removeRedundantAggregateDistinct(aggregateCall, groupSet, r);
      }
      aggregateCalls.add(aggregateCall);
    }

    assert ImmutableBitSet.ORDERING.isStrictlyOrdered(groupSets) : groupSets;
    for (ImmutableBitSet set : groupSets) {
      assert groupSet.contains(set);
    }

    return pruneAggregateInputFieldsAndDeduplicateAggCalls(r, groupSet, groupSets, aggregateCalls,
        frame.fields, registrar.extraNodes);
  }

  /**
   * Prunes unused fields on the input of the aggregate and removes duplicate aggregation calls.
   */
  private RelBuilder pruneAggregateInputFieldsAndDeduplicateAggCalls(
      RelNode r,
      final ImmutableBitSet groupSet,
      final ImmutableList<ImmutableBitSet> groupSets,
      final List<AggregateCall> aggregateCalls,
      PairList<ImmutableSet<String>, RelDataTypeField> inFields,
      final List<RexNode> extraNodes) {
    final ImmutableBitSet groupSetAfterPruning;
    final ImmutableList<ImmutableBitSet> groupSetsAfterPruning;
    if (config.pruneInputOfAggregate()
        && r instanceof Project) {
      final Set<Integer> fieldsUsed =
          RelOptUtil.getAllFields2(groupSet, aggregateCalls);
      // Some parts of the system can't handle rows with zero fields, so
      // pretend that one field is used.
      if (fieldsUsed.isEmpty()) {
        r = ((Project) r).getInput();
        groupSetAfterPruning = groupSet;
        groupSetsAfterPruning = groupSets;
      } else if (fieldsUsed.size() < r.getRowType().getFieldCount()) {
        // Some fields are computed but not used. Prune them.
        final Map<Integer, Integer> sourceFieldToTargetFieldMap = new HashMap<>();
        for (int source : fieldsUsed) {
          sourceFieldToTargetFieldMap.put(source, sourceFieldToTargetFieldMap.size());
        }

        groupSetAfterPruning = groupSet.permute(sourceFieldToTargetFieldMap);
        groupSetsAfterPruning =
            ImmutableBitSet.ORDERING.immutableSortedCopy(
                ImmutableBitSet.permute(groupSets, sourceFieldToTargetFieldMap));

        final Mappings.TargetMapping targetMapping =
            Mappings.target(sourceFieldToTargetFieldMap, r.getRowType().getFieldCount(),
                fieldsUsed.size());
        final List<AggregateCall> oldAggregateCalls =
            new ArrayList<>(aggregateCalls);
        aggregateCalls.clear();
        for (AggregateCall aggregateCall : oldAggregateCalls) {
          aggregateCalls.add(aggregateCall.transform(targetMapping));
        }
        final PairList<ImmutableSet<String>, RelDataTypeField> newInFields =
            PairList.of();
        newInFields.addAll(Mappings.permute(inFields, targetMapping.inverse()));
        inFields = newInFields;

        final Project project = (Project) r;
        final List<RexNode> newProjects = new ArrayList<>();
        final RelDataTypeFactory.Builder builder =
            cluster.getTypeFactory().builder();
        for (int i : fieldsUsed) {
          newProjects.add(project.getProjects().get(i));
          builder.add(project.getRowType().getFieldList().get(i));
        }

        // This currently does not apply mappings correctly to the RelCollation due to
        // https://issues.apache.org/jira/browse/CALCITE-6391
        r =
            project.copy(project.getTraitSet().apply(targetMapping), project.getInput(),
                newProjects, builder.build());
      } else {
        groupSetAfterPruning = groupSet;
        groupSetsAfterPruning = groupSets;
      }
    } else {
      groupSetAfterPruning = groupSet;
      groupSetsAfterPruning = groupSets;
    }

    if (!config.dedupAggregateCalls() || Util.isDistinct(aggregateCalls)) {
      return aggregate_(groupSetAfterPruning, groupSetsAfterPruning, r, aggregateCalls,
          extraNodes, inFields);
    }

    // There are duplicate aggregate calls. Rebuild the list to eliminate
    // duplicates, then add a Project.
    final Set<AggregateCall> callSet = new HashSet<>();
    final PairList<Integer, @Nullable String> projects = PairList.of();
    Util.range(groupSetAfterPruning.cardinality())
        .forEach(i -> projects.add(i, null));
    final List<AggregateCall> distinctAggregateCalls = new ArrayList<>();
    for (AggregateCall aggregateCall : aggregateCalls) {
      final int i;
      if (callSet.add(aggregateCall)) {
        i = distinctAggregateCalls.size();
        distinctAggregateCalls.add(aggregateCall);
      } else {
        i = distinctAggregateCalls.indexOf(aggregateCall);
        assert i >= 0;
      }
      projects.add(groupSetAfterPruning.cardinality() + i, aggregateCall.name);
    }
    aggregate_(groupSetAfterPruning, groupSetsAfterPruning, r, distinctAggregateCalls,
        extraNodes, inFields);
    return project(projects.transform((i, name) -> aliasMaybe(field(i), name)));
  }

  /**
   * Removed redundant distinct if an input is already unique.
   */
  private AggregateCall removeRedundantAggregateDistinct(
      AggregateCall aggregateCall,
      ImmutableBitSet groupSet,
      RelNode relNode) {
    if (aggregateCall.isDistinct() && config.removeRedundantDistinct()) {
      final RelMetadataQuery mq = relNode.getCluster().getMetadataQuery();
      final List<Integer> argList = aggregateCall.getArgList();
      final ImmutableBitSet distinctArg = ImmutableBitSet.builder()
          .addAll(argList)
          .build();
      final ImmutableBitSet columns = groupSet.union(distinctArg);
      final Boolean alreadyUnique =
          mq.areColumnsUnique(relNode, columns);
      if (alreadyUnique != null && alreadyUnique) {
        // columns have been distinct or columns are primary keys
        return aggregateCall.withDistinct(false);
      }
    }
    return aggregateCall;
  }

  /** Returns whether an input is already unique, and therefore a Project
   * can be created instead of an Aggregate.
   *
   * <p>{@link AggregateRemoveRule} does something similar, but also handles
   * {@link org.apache.calcite.sql.SqlSingletonAggFunction} calls. */
  private boolean alreadyUnique(List<AggCallPlus> aggCallList,
      GroupKeyImpl groupKey, ImmutableBitSet groupSet,
      List<RexNode> extraNodes) {
    final RelMetadataQuery mq = peek().getCluster().getMetadataQuery();
    if (aggCallList.isEmpty() && groupSet.isEmpty()) {
      final Double minRowCount = mq.getMinRowCount(peek());
      if (minRowCount == null || minRowCount < 1d) {
        // We can't remove "GROUP BY ()" if there's a chance the rel could be
        // empty.
        return false;
      }
    }

    // If there are aggregate functions, we must be able to flatten them
    if (!aggCallList.stream()
        .allMatch(c -> canFlattenStatic(c.aggregateCall()))) {
      return false;
    }

    if (extraNodes.size() == fields().size()) {
      final Boolean unique = mq.areColumnsUnique(peek(), groupSet);
      if (unique != null && unique
          && !config.aggregateUnique()
          && groupKey.isSimple()) {
        // Rel is already unique.
        return true;
      }
    }

    // If there is at most one row, rel is already unique.
    final Double maxRowCount = mq.getMaxRowCount(peek());
    return maxRowCount != null && maxRowCount <= 1D
        && !config.aggregateUnique()
        && groupKey.isSimple();
  }

  /** Finishes the implementation of {@link #aggregate} by creating an
   * {@link Aggregate} and pushing it onto the stack. */
  private RelBuilder aggregate_(ImmutableBitSet groupSet,
      ImmutableList<ImmutableBitSet> groupSets, RelNode input,
      List<AggregateCall> aggregateCalls, List<RexNode> extraNodes,
      PairList<ImmutableSet<String>, RelDataTypeField> inFields) {
    final RelNode aggregate =
        struct.aggregateFactory.createAggregate(input,
            ImmutableList.of(), groupSet, groupSets, aggregateCalls);

    // build field list
    final PairList<ImmutableSet<String>, RelDataTypeField> fields =
        PairList.of();
    final List<RelDataTypeField> aggregateFields =
        aggregate.getRowType().getFieldList();
    int i = 0;
    // first, group fields
    for (Integer groupField : groupSet.asList()) {
      RexNode node = extraNodes.get(groupField);
      final SqlKind kind = node.getKind();
      switch (kind) {
      case INPUT_REF:
        fields.add(inFields.get(((RexInputRef) node).getIndex()));
        break;
      default:
        String name = aggregateFields.get(i).getName();
        RelDataTypeField fieldType =
            new RelDataTypeFieldImpl(name, i, node.getType());
        fields.add(ImmutableSet.of(), fieldType);
        break;
      }
      i++;
    }
    // second, aggregate fields. retain `i' as field index
    for (int j = 0; j < aggregateCalls.size(); ++j) {
      final AggregateCall call = aggregateCalls.get(j);
      final RelDataTypeField fieldType =
          new RelDataTypeFieldImpl(aggregateFields.get(i + j).getName(), i + j,
              call.getType());
      fields.add(ImmutableSet.of(), fieldType);
    }
    stack.push(new Frame(aggregate, fields));
    return this;
  }

  /**
   * The {@code GROUP_ID()} function is used to distinguish duplicate groups.
   * However, as Aggregate normalizes group sets to canonical form (i.e.,
   * flatten, sorting, redundancy removal), this information is lost in RelNode.
   * Therefore, it is impossible to implement the function in runtime.
   *
   * <p>To fill this gap, an aggregation query that contains duplicate group
   * sets is rewritten into a Union of Aggregate operators whose group sets are
   * distinct. The number of inputs to the Union is equal to the maximum number
   * of duplicates. In the {@code N}th input to the Union, calls to the
   * {@code GROUP_ID} aggregate function are replaced by the integer literal
   * {@code N}.
   *
   * <p>This method also handles the case where group sets are distinct but
   * there is a call to {@code GROUP_ID}. That call is replaced by the integer
   * literal {@code 0}.
   *
   * <p>Also see the discussion in
   * <a href="https://issues.apache.org/jira/browse/CALCITE-1824">[CALCITE-1824]
   * GROUP_ID returns wrong result</a> and
   * <a href="https://issues.apache.org/jira/browse/CALCITE-4748">[CALCITE-4748]
   * If there are duplicate GROUPING SETS, Calcite should return duplicate
   * rows</a>.
   */
  private RelBuilder rewriteAggregateWithDuplicateGroupSets(
      ImmutableBitSet groupSet,
      ImmutableSortedMultiset<ImmutableBitSet> groupSets,
      List<AggCallPlus> aggregateCalls) {
    final List<String> fieldNamesIfNoRewrite =
        Aggregate.deriveRowType(getTypeFactory(), peek().getRowType(), false,
            groupSet, groupSets.asList(),
            aggregateCalls.stream().map(AggCallPlus::aggregateCall)
                .collect(toImmutableList())).getFieldNames();

    // If n duplicates exist for a particular grouping, the {@code GROUP_ID()}
    // function produces values in the range 0 to n-1. For each value,
    // we need to figure out the corresponding group sets.
    //
    // For example, "... GROUPING SETS (a, a, b, c, c, c, c)"
    // (i) The max value of the GROUP_ID() function returns is 3
    // (ii) GROUPING SETS (a, b, c) produces value 0,
    //      GROUPING SETS (a, c) produces value 1,
    //      GROUPING SETS (c) produces value 2
    //      GROUPING SETS (c) produces value 3
    final Map<Integer, Set<ImmutableBitSet>> groupIdToGroupSets = new HashMap<>();
    int maxGroupId = 0;
    for (Multiset.Entry<ImmutableBitSet> entry : groupSets.entrySet()) {
      int groupId = entry.getCount() - 1;
      if (groupId > maxGroupId) {
        maxGroupId = groupId;
      }
      for (int i = 0; i <= groupId; i++) {
        groupIdToGroupSets.computeIfAbsent(i,
            k -> Sets.newTreeSet(ImmutableBitSet.COMPARATOR))
            .add(entry.getElement());
      }
    }

    // AggregateCall list without GROUP_ID function
    final List<AggCall> aggregateCallsWithoutGroupId =
        new ArrayList<>(aggregateCalls);
    aggregateCallsWithoutGroupId.removeIf(RelBuilder::isGroupId);

    // For each group id value, we first construct an Aggregate without
    // GROUP_ID() function call, and then create a Project node on top of it.
    // The Project adds literal value for group id in right position.
    final Frame frame = stack.pop();
    for (int groupId = 0; groupId <= maxGroupId; groupId++) {
      // Create the Aggregate node without GROUP_ID() call
      stack.push(frame);
      aggregate(groupKey(groupSet, castNonNull(groupIdToGroupSets.get(groupId))),
          aggregateCallsWithoutGroupId);

      final List<RexNode> selectList = new ArrayList<>();
      final int groupExprLength = groupSet.cardinality();
      // Project fields in group by expressions
      for (int i = 0; i < groupExprLength; i++) {
        selectList.add(field(i));
      }
      // Project fields in aggregate calls
      int groupIdCount = 0;
      for (int i = 0; i < aggregateCalls.size(); i++) {
        if (isGroupId(aggregateCalls.get(i))) {
          selectList.add(
              getRexBuilder().makeExactLiteral(BigDecimal.valueOf(groupId),
                  getTypeFactory().createSqlType(SqlTypeName.BIGINT)));
          groupIdCount++;
        } else {
          selectList.add(field(groupExprLength + i - groupIdCount));
        }
      }
      project(selectList, fieldNamesIfNoRewrite);
    }

    return union(true, maxGroupId + 1);
  }

  private static boolean isGroupId(AggCall c) {
    return ((AggCallPlus) c).op().kind == SqlKind.GROUP_ID;
  }

  private RelBuilder setOp(boolean all, SqlKind kind, int n) {
    List<RelNode> inputs = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      inputs.add(0, build());
    }
    switch (kind) {
    case UNION:
    case INTERSECT:
    case EXCEPT:
      if (n < 1) {
        throw new IllegalArgumentException(
            "bad INTERSECT/UNION/EXCEPT input count");
      }
      break;
    default:
      throw new AssertionError("bad setOp " + kind);
    }

    if (n == 1) {
      return push(inputs.get(0));
    }

    if (config.simplifyValues()
        && kind == UNION
        && inputs.stream().allMatch(r -> r instanceof Values)) {
      List<RelDataType> inputTypes = Util.transform(inputs, RelNode::getRowType);
      RelDataType rowType = getTypeFactory()
          .leastRestrictive(inputTypes);
      requireNonNull(rowType, () -> "leastRestrictive(" + inputTypes + ")");
      final List<List<RexLiteral>> tuples = new ArrayList<>();
      for (RelNode input : inputs) {
        tuples.addAll(((Values) input).tuples);
      }
      final List<List<RexLiteral>> tuples2 =
          all ? tuples : Util.distinctList(tuples);
      return values(tuples2, rowType);
    }

    return push(struct.setOpFactory.createSetOp(kind, inputs, all));
  }

  /** Creates a {@link Union} of the two most recent
   * relational expressions on the stack.
   *
   * @param all Whether to create UNION ALL
   */
  public RelBuilder union(boolean all) {
    return union(all, 2);
  }

  /** Creates a {@link Union} of the {@code n}
   * most recent relational expressions on the stack.
   *
   * @param all Whether to create UNION ALL
   * @param n Number of inputs to the UNION operator
   */
  public RelBuilder union(boolean all, int n) {
    return setOp(all, UNION, n);
  }

  /** Creates an {@link Intersect} of the two most
   * recent relational expressions on the stack.
   *
   * @param all Whether to create INTERSECT ALL
   */
  public RelBuilder intersect(boolean all) {
    return intersect(all, 2);
  }

  /** Creates an {@link Intersect} of the {@code n}
   * most recent relational expressions on the stack.
   *
   * @param all Whether to create INTERSECT ALL
   * @param n Number of inputs to the INTERSECT operator
   */
  public RelBuilder intersect(boolean all, int n) {
    return setOp(all, SqlKind.INTERSECT, n);
  }

  /** Creates a {@link Minus} of the two most recent
   * relational expressions on the stack.
   *
   * @param all Whether to create EXCEPT ALL
   */
  public RelBuilder minus(boolean all) {
    return minus(all, 2);
  }

  /** Creates a {@link Minus} of the {@code n}
   * most recent relational expressions on the stack.
   *
   * @param all Whether to create EXCEPT ALL
   */
  public RelBuilder minus(boolean all, int n) {
    return setOp(all, SqlKind.EXCEPT, n);
  }

  /**
   * Creates a {@link TableScan} on a {@link TransientTable} with the given name, using as type
   * the top of the stack's type.
   *
   * @param tableName table name
   */
  @Experimental
  public RelBuilder transientScan(String tableName) {
    return this.transientScan(tableName, this.peek().getRowType());
  }

  /**
   * Creates a {@link TableScan} on a {@link TransientTable} with the given name and type.
   *
   * @param tableName table name
   * @param rowType row type of the table
   */
  @Experimental
  public RelBuilder transientScan(String tableName, RelDataType rowType) {
    TransientTable transientTable = new ListTransientTable(tableName, rowType);
    requireNonNull(relOptSchema, "relOptSchema");
    RelOptTable relOptTable =
        RelOptTableImpl.create(relOptSchema, rowType, transientTable,
            ImmutableList.of(tableName));
    RelNode scan =
        struct.scanFactory.createScan(
            ViewExpanders.toRelContext(viewExpander, cluster),
            relOptTable);
    push(scan);
    rename(rowType.getFieldNames());
    return this;
  }

  /**
   * Creates a {@link TableSpool} for the most recent relational expression.
   *
   * @param readType Spool's read type (as described in {@link Spool.Type})
   * @param writeType Spool's write type (as described in {@link Spool.Type})
   * @param table Table to write into
   */
  private RelBuilder tableSpool(Spool.Type readType, Spool.Type writeType,
      RelOptTable table) {
    RelNode spool =
        struct.spoolFactory.createTableSpool(peek(), readType, writeType,
            table);
    replaceTop(spool);
    return this;
  }

  /**
   * Creates a {@link RepeatUnion} associated to a {@link TransientTable} without a maximum number
   * of iterations, i.e. repeatUnion(tableName, all, -1).
   *
   * @param tableName name of the {@link TransientTable} associated to the {@link RepeatUnion}
   * @param all whether duplicates will be considered or not
   */
  @Experimental
  public RelBuilder repeatUnion(String tableName, boolean all) {
    return repeatUnion(tableName, all, -1);
  }

  /**
   * Creates a {@link RepeatUnion} associated to a {@link TransientTable} of the
   * two most recent relational expressions on the stack.
   *
   * <p>Warning: if these relational expressions are not
   * correctly defined, this operation might lead to an infinite loop.
   *
   * <p>The generated {@link RepeatUnion} operates as follows:
   *
   * <ul>
   * <li>Evaluate its left term once, propagating the results into the
   *     {@link TransientTable};
   * <li>Evaluate its right term (which may contain a {@link TableScan} on the
   *     {@link TransientTable}) over and over until it produces no more results
   *     (or until an optional maximum number of iterations is reached). On each
   *     iteration, the results are propagated into the {@link TransientTable},
   *     overwriting the results from the previous one.
   * </ul>
   *
   * @param tableName Name of the {@link TransientTable} associated to the
   *     {@link RepeatUnion}
   * @param all Whether duplicates are considered
   * @param iterationLimit Maximum number of iterations; negative value means no limit
   */
  @Experimental
  public RelBuilder repeatUnion(String tableName, boolean all, int iterationLimit) {
    RelOptTable table = null;
    for (int i = 0; i < stack.size(); i++) { // search scan(tableName) in the stack
      table = RelOptUtil.findTable(peek(i), tableName);
      if (table != null) { // found
        break;
      }
    }
    if (table == null) {
      throw RESOURCE.tableNotFound(tableName).ex();
    }

    RelNode iterative = tableSpool(Spool.Type.LAZY, Spool.Type.LAZY, table).build();
    RelNode seed = tableSpool(Spool.Type.LAZY, Spool.Type.LAZY, table).build();
    RelNode repeatUnion =
        struct.repeatUnionFactory.createRepeatUnion(seed, iterative, all,
            iterationLimit, table);
    return push(repeatUnion);
  }


  /** Creates a {@link Join} with an array of conditions. */
  public RelBuilder join(JoinRelType joinType, RexNode condition0,
      RexNode... conditions) {
    return join(joinType, Lists.asList(condition0, conditions));
  }

  /** Creates a {@link Join} with multiple
   * conditions. */
  public RelBuilder join(JoinRelType joinType,
      Iterable<? extends RexNode> conditions) {
    return join(joinType, and(conditions),
        ImmutableSet.of());
  }

  /** Creates a {@link Join} with one condition. */
  public RelBuilder join(JoinRelType joinType, RexNode condition) {
    return join(joinType, condition, ImmutableSet.of());
  }

  /** Creates a {@link Join} with correlating variables. */
  public RelBuilder join(JoinRelType joinType, RexNode condition,
      Set<CorrelationId> variablesSet) {
    Frame right = stack.pop();
    final Frame left = stack.pop();
    final RelNode join;
    final boolean correlate = checkIfCorrelated(variablesSet, joinType, left.rel, right.rel);
    RexNode postCondition = literal(true);
    if (config.simplify()) {
      // Normalize expanded versions IS NOT DISTINCT FROM so that simplifier does not
      // transform the expression to something unrecognizable
      if (condition instanceof RexCall) {
        condition =
            RelOptUtil.collapseExpandedIsNotDistinctFromExpr((RexCall) condition,
                getRexBuilder());
      }
      condition = simplifier.simplifyUnknownAsFalse(condition);
    }
    if (correlate) {
      final CorrelationId id = Iterables.getOnlyElement(variablesSet);
      // Correlate does not have an ON clause.
      switch (joinType) {
      case LEFT:
      case SEMI:
      case ANTI:
        // For a LEFT/SEMI/ANTI, predicate must be evaluated first.
        stack.push(right);
        filter(condition.accept(new Shifter(left.rel, id, right.rel)));
        right = stack.pop();
        break;
      case INNER:
        // For INNER, we can defer.
        postCondition = condition;
        break;
      default:
        throw new IllegalArgumentException("Correlated " + joinType + " join is not supported");
      }
      final ImmutableBitSet requiredColumns = RelOptUtil.correlationColumns(id, right.rel);
      join =
          struct.correlateFactory.createCorrelate(left.rel, right.rel, ImmutableList.of(), id,
              requiredColumns, joinType);
    } else {
      RelNode join0 =
          struct.joinFactory.createJoin(left.rel, right.rel,
              ImmutableList.of(), condition, variablesSet, joinType, false);

      if (join0 instanceof Join && config.pushJoinCondition()) {
        join = RelOptUtil.pushDownJoinConditions((Join) join0, this);
      } else {
        join = join0;
      }
    }
    final PairList<ImmutableSet<String>, RelDataTypeField> fields =
        PairList.of();
    fields.addAll(left.fields);
    fields.addAll(right.fields);
    stack.push(new Frame(join, fields));
    filter(postCondition);
    return this;
  }

  /** Creates a {@link Correlate}
   * with a {@link CorrelationId} and an array of fields that are used by correlation. */
  public RelBuilder correlate(JoinRelType joinType,
      CorrelationId correlationId, RexNode... requiredFields) {
    return correlate(joinType, correlationId, ImmutableList.copyOf(requiredFields));
  }

  /** Creates a {@link Correlate}
   * with a {@link CorrelationId} and a list of fields that are used by correlation. */
  public RelBuilder correlate(JoinRelType joinType,
      CorrelationId correlationId, Iterable<? extends RexNode> requiredFields) {
    Frame right = stack.pop();

    final Registrar registrar =
        new Registrar(fields(), peek().getRowType().getFieldNames());

    List<Integer> requiredOrdinals =
        registrar.registerExpressions(ImmutableList.copyOf(requiredFields));

    project(registrar.extraNodes);
    rename(registrar.names);
    Frame left = stack.pop();

    final RelNode correlate =
        struct.correlateFactory.createCorrelate(left.rel, right.rel, ImmutableList.of(),
            correlationId, ImmutableBitSet.of(requiredOrdinals), joinType);

    final PairList<ImmutableSet<String>, RelDataTypeField> fields =
        PairList.of();
    fields.addAll(left.fields);
    fields.addAll(right.fields);
    stack.push(new Frame(correlate, fields));

    return this;
  }

  /** Creates a {@link Join} using USING syntax.
   *
   * <p>For each of the field names, both left and right inputs must have a
   * field of that name. Constructs a join condition that the left and right
   * fields are equal.
   *
   * @param joinType Join type
   * @param fieldNames Field names
   */
  public RelBuilder join(JoinRelType joinType, String... fieldNames) {
    final List<RexNode> conditions = new ArrayList<>();
    for (String fieldName : fieldNames) {
      conditions.add(
          equals(field(2, 0, fieldName),
              field(2, 1, fieldName)));
    }
    return join(joinType, conditions);
  }

  /** Creates a {@link Join} with {@link JoinRelType#SEMI}.
   *
   * <p>A semi-join is a form of join that combines two relational expressions
   * according to some condition, and outputs only rows from the left input for
   * which at least one row from the right input matches. It only outputs
   * columns from the left input, and ignores duplicates on the right.
   *
   * <p>For example, {@code EMP semi-join DEPT} finds all {@code EMP} records
   * that do not have a corresponding {@code DEPT} record, similar to the
   * following SQL:
   *
   * <blockquote><pre>
   * SELECT * FROM EMP
   * WHERE EXISTS (SELECT 1 FROM DEPT
   *     WHERE DEPT.DEPTNO = EMP.DEPTNO)</pre>
   * </blockquote>
   */
  public RelBuilder semiJoin(Iterable<? extends RexNode> conditions) {
    final Frame right = stack.pop();
    final RelNode semiJoin =
        struct.joinFactory.createJoin(peek(),
            right.rel,
            ImmutableList.of(),
            and(conditions),
            ImmutableSet.of(),
            JoinRelType.SEMI,
            false);
    replaceTop(semiJoin);
    return this;
  }

  /** Creates a {@link Join} with {@link JoinRelType#SEMI}.
   *
   * @see #semiJoin(Iterable) */
  public RelBuilder semiJoin(RexNode... conditions) {
    return semiJoin(ImmutableList.copyOf(conditions));
  }

  /** Creates an anti-join.
   *
   * <p>An anti-join is a form of join that combines two relational expressions
   * according to some condition, but outputs only rows from the left input
   * for which no rows from the right input match.
   *
   * <p>For example, {@code EMP anti-join DEPT} finds all {@code EMP} records
   * that do not have a corresponding {@code DEPT} record, similar to the
   * following SQL:
   *
   * <blockquote><pre>
   * SELECT * FROM EMP
   * WHERE NOT EXISTS (SELECT 1 FROM DEPT
   *     WHERE DEPT.DEPTNO = EMP.DEPTNO)</pre>
   * </blockquote>
   */
  public RelBuilder antiJoin(Iterable<? extends RexNode> conditions) {
    final Frame right = stack.pop();
    final RelNode antiJoin =
        struct.joinFactory.createJoin(peek(),
            right.rel,
            ImmutableList.of(),
            and(conditions),
            ImmutableSet.of(),
            JoinRelType.ANTI,
            false);
    replaceTop(antiJoin);
    return this;
  }

  /** Creates an anti-join.
   *
   * @see #antiJoin(Iterable) */
  public RelBuilder antiJoin(RexNode... conditions) {
    return antiJoin(ImmutableList.copyOf(conditions));
  }

  /** Assigns a table alias to the top entry on the stack. */
  public RelBuilder as(final String alias) {
    final Frame pair = stack.pop();
    final PairList<ImmutableSet<String>, RelDataTypeField> newFields =
        PairList.of();
    pair.fields.forEach((aliases, field) -> {
      final ImmutableSet<String> aliasList =
          aliases.contains(alias)
              ? aliases
              : ImmutableSet.<String>builder().addAll(aliases).add(alias)
                  .build();
      newFields.add(aliasList, field);
    });
    stack.push(new Frame(pair.rel, newFields));
    return this;
  }

  /** Creates a {@link Values}.
   *
   * <p>The {@code values} array must have the same number of entries as
   * {@code fieldNames}, or an integer multiple if you wish to create multiple
   * rows.
   *
   * <p>The {@code fieldNames} array must not be null or empty, but may contain
   * null values.
   *
   * <p>If there are zero rows, or if all values of any column are
   * null, this method cannot deduce the type of columns. For these cases,
   * call {@link #values(Iterable, RelDataType)}.
   *
   * @param fieldNames Field names
   * @param values Values
   */
  public RelBuilder values(@Nullable String[] fieldNames, @Nullable Object... values) {
    requireNonNull(fieldNames, "fieldNames");
    if (fieldNames.length == 0
        || values.length % fieldNames.length != 0
        || values.length < fieldNames.length) {
      throw new IllegalArgumentException(
          "Value count must be a positive multiple of field count");
    }
    final int rowCount = values.length / fieldNames.length;
    for (Ord<@Nullable String> fieldName : Ord.zip(fieldNames)) {
      if (allNull(values, fieldName.i, fieldNames.length)) {
        throw new IllegalArgumentException("All values of field '" + fieldName.e
            + "' (field index " + fieldName.i + ")"
            + " are null; cannot deduce type");
      }
    }
    final ImmutableList<ImmutableList<RexLiteral>> tupleList =
        tupleList(fieldNames.length, values);
    assert tupleList.size() == rowCount;
    final List<String> fieldNameList =
        Util.transformIndexed(Arrays.asList(fieldNames), (name, i) ->
            name != null ? name : SqlUtil.deriveAliasFromOrdinal(i));
    return values(tupleList, fieldNameList);
  }

  private RelBuilder values(List<? extends List<RexLiteral>> tupleList,
      List<String> fieldNames) {
    final RelDataTypeFactory typeFactory = cluster.getTypeFactory();
    final RelDataTypeFactory.Builder builder = typeFactory.builder();
    Ord.forEach(fieldNames, (fieldName, i) -> {
      final RelDataType type =
          typeFactory.leastRestrictive(new AbstractList<RelDataType>() {
            @Override public RelDataType get(int index) {
              return tupleList.get(index).get(i).getType();
            }

            @Override public int size() {
              return tupleList.size();
            }
          });
      assert type != null
          : "can't infer type for field " + i + ", " + fieldName;
      builder.add(fieldName, type);
    });
    final RelDataType rowType = builder.build();
    return values(tupleList, rowType);
  }

  private ImmutableList<ImmutableList<RexLiteral>> tupleList(int columnCount,
      @Nullable Object[] values) {
    final ImmutableList.Builder<ImmutableList<RexLiteral>> listBuilder =
        ImmutableList.builder();
    final List<RexLiteral> valueList = new ArrayList<>();
    for (int i = 0; i < values.length; i++) {
      Object value = values[i];
      valueList.add(literal(value));
      if ((i + 1) % columnCount == 0) {
        listBuilder.add(ImmutableList.copyOf(valueList));
        valueList.clear();
      }
    }
    return listBuilder.build();
  }

  /** Returns whether all values for a given column are null. */
  private static boolean allNull(@Nullable Object[] values, int column, int columnCount) {
    for (int i = column; i < values.length; i += columnCount) {
      if (values[i] != null) {
        return false;
      }
    }
    return true;
  }

  /** Creates a relational expression that reads from an input and throws
   * all of the rows away.
   *
   * <p>Note that this method always pops one relational expression from the
   * stack. {@code values}, in contrast, does not pop any relational
   * expressions, and always produces a leaf.
   *
   * <p>The default implementation creates a {@link Values} with the same
   * specified row type and aliases as the input, and ignores the input entirely.
   * But schema-on-query systems such as Drill might override this method to
   * create a relation expression that retains the input, just to read its
   * schema.
   */
  public RelBuilder empty() {
    final Frame frame = stack.pop();
    final RelNode values =
        struct.valuesFactory.createValues(cluster, frame.rel.getRowType(),
            ImmutableList.of());
    stack.push(new Frame(values, frame.fields));
    return this;
  }

  /** Creates a {@link Values} with a specified row type.
   *
   * <p>This method can handle cases that {@link #values(String[], Object...)}
   * cannot, such as all values of a column being null, or there being zero
   * rows.
   *
   * @param rowType Row type
   * @param columnValues Values
   */
  public RelBuilder values(RelDataType rowType, Object... columnValues) {
    final ImmutableList<ImmutableList<RexLiteral>> tupleList =
        tupleList(rowType.getFieldCount(), columnValues);
    RelNode values =
        struct.valuesFactory.createValues(cluster, rowType,
            ImmutableList.copyOf(tupleList));
    push(values);
    return this;
  }

  /** Creates a {@link Values} with a specified row type.
   *
   * <p>This method can handle cases that {@link #values(String[], Object...)}
   * cannot, such as all values of a column being null, or there being zero
   * rows.
   *
   * @param tupleList Tuple list
   * @param rowType Row type
   */
  public RelBuilder values(Iterable<? extends List<RexLiteral>> tupleList,
      RelDataType rowType) {
    RelNode values =
        struct.valuesFactory.createValues(cluster, rowType,
            copy(tupleList));
    push(values);
    return this;
  }

  /** Creates a {@link Values} with a specified row type and
   * zero rows.
   *
   * @param rowType Row type
   */
  public RelBuilder values(RelDataType rowType) {
    return values(ImmutableList.<ImmutableList<RexLiteral>>of(), rowType);
  }

  /** Converts an iterable of lists into an immutable list of immutable lists
   * with the same contents. Returns the same object if possible. */
  private static <E> ImmutableList<ImmutableList<E>> copy(
      Iterable<? extends List<E>> tupleList) {
    final ImmutableList.Builder<ImmutableList<E>> builder =
        ImmutableList.builder();
    int changeCount = 0;
    for (List<E> literals : tupleList) {
      final ImmutableList<E> literals2 =
          ImmutableList.copyOf(literals);
      builder.add(literals2);
      if (literals != literals2) {
        ++changeCount;
      }
    }
    if (changeCount == 0 && tupleList instanceof ImmutableList) {
      // don't make a copy if we don't have to
      //noinspection unchecked
      return (ImmutableList<ImmutableList<E>>) tupleList;
    }
    return builder.build();
  }

  /** Creates a limit and/or offset without a sort.
   *
   * @param offset Number of rows to skip; non-positive means don't skip any
   * @param fetch Maximum number of rows to fetch; negative means no limit
   */
  public RelBuilder limit(int offset, int fetch) {
    return sortLimit(offset, fetch, ImmutableList.of());
  }

  /** Creates an Exchange by distribution. */
  public RelBuilder exchange(RelDistribution distribution) {
    RelNode exchange =
        struct.exchangeFactory.createExchange(peek(), distribution);
    replaceTop(exchange);
    return this;
  }

  /** Creates a SortExchange by distribution and collation. */
  public RelBuilder sortExchange(RelDistribution distribution,
      RelCollation collation) {
    RelNode exchange =
        struct.sortExchangeFactory.createSortExchange(peek(), distribution,
            collation);
    replaceTop(exchange);
    return this;
  }

  /** Creates a {@link Sort} by field ordinals.
   *
   * <p>Negative fields mean descending: -1 means field(0) descending,
   * -2 means field(1) descending, etc.
   */
  public RelBuilder sort(int... fields) {
    final ImmutableList.Builder<RexNode> builder = ImmutableList.builder();
    for (int field : fields) {
      builder.add(field < 0 ? desc(field(-field - 1)) : field(field));
    }
    return sortLimit(-1, -1, builder.build());
  }

  /** Creates a {@link Sort} by expressions. */
  public RelBuilder sort(RexNode... nodes) {
    return sortLimit(-1, -1, ImmutableList.copyOf(nodes));
  }

  /** Creates a {@link Sort} by expressions. */
  public RelBuilder sort(Iterable<? extends RexNode> nodes) {
    return sortLimit(-1, -1, nodes);
  }

  /** Creates a {@link Sort} by expressions, with limit and offset. */
  public RelBuilder sortLimit(int offset, int fetch, RexNode... nodes) {
    return sortLimit(offset, fetch, ImmutableList.copyOf(nodes));
  }

  /** Creates a {@link Sort} by specifying collations.
   */
  public RelBuilder sort(RelCollation collation) {
    final RelNode sort =
        struct.sortFactory.createSort(peek(), collation, null, null);
    replaceTop(sort);
    return this;
  }

  /** Creates a {@link Sort} by a list of expressions, with limit and offset.
   *
   * @param offset Number of rows to skip; non-positive means don't skip any
   * @param fetch Maximum number of rows to fetch; negative means no limit
   * @param nodes Sort expressions
   */
  public RelBuilder sortLimit(int offset, int fetch,
      Iterable<? extends RexNode> nodes) {
    final @Nullable RexNode offsetNode = offset <= 0 ? null : literal(offset);
    final @Nullable RexNode fetchNode = fetch < 0 ? null : literal(fetch);
    return sortLimit(offsetNode, fetchNode, nodes);
  }

  /** Creates a {@link Sort} by a list of expressions, with limitNode and offsetNode.
   *
   * @param offsetNode RexLiteral means number of rows to skip is deterministic,
   *                   RexDynamicParam means number of rows to skip is dynamic.
   * @param fetchNode  RexLiteral means maximum number of rows to fetch is deterministic,
   *                   RexDynamicParam mean maximum number is dynamic.
   * @param nodes      Sort expressions
   */
  public RelBuilder sortLimit(@Nullable RexNode offsetNode, @Nullable RexNode fetchNode,
      Iterable<? extends RexNode> nodes) {
    if (offsetNode != null) {
      if (!(offsetNode instanceof RexLiteral || offsetNode instanceof RexDynamicParam)) {
        throw new IllegalArgumentException("OFFSET node must be RexLiteral or RexDynamicParam");
      }
    }
    if (fetchNode != null) {
      if (!(fetchNode instanceof RexLiteral || fetchNode instanceof RexDynamicParam)) {
        throw new IllegalArgumentException("FETCH node must be RexLiteral or RexDynamicParam");
      }
    }

    final Registrar registrar = new Registrar(fields(), ImmutableList.of());
    final List<RelFieldCollation> fieldCollations =
        registrar.registerFieldCollations(nodes);
    final int fetch = fetchNode instanceof RexLiteral
        ? RexLiteral.intValue(fetchNode) : -1;
    if (offsetNode == null && fetch == 0 && config.simplifyLimit()) {
      return empty();
    }
    if (offsetNode == null && fetchNode == null && fieldCollations.isEmpty()) {
      return this; // sort is trivial
    }

    if (fieldCollations.isEmpty()) {
      assert registrar.addedFieldCount() == 0;
      RelNode top = peek();
      if (top instanceof Sort) {
        final Sort sort2 = (Sort) top;
        if ((offsetNode == null || sort2.offset == null)
            && (fetchNode == null || sort2.fetch == null)) {
          // We're not trying to replace something that's already set - an
          // offset in a sort that already has an offset, or a fetch in a sort
          // that already has a fetch - and so we can merge them.
          replaceTop(sort2.getInput());
          final RelNode sort =
              struct.sortFactory.createSort(peek(), sort2.collation,
                  first(offsetNode, sort2.offset),
                  first(fetchNode, sort2.fetch));
          replaceTop(sort);
          return this;
        }
      }
      if (top instanceof Project) {
        final Project project = (Project) top;
        if (project.getInput() instanceof Sort) {
          final Sort sort2 = (Sort) project.getInput();
          if (sort2.offset == null && sort2.fetch == null) {
            final RelNode sort =
                struct.sortFactory.createSort(sort2.getInput(),
                    sort2.collation, offsetNode, fetchNode);
            replaceTop(
                struct.projectFactory.createProject(sort,
                    project.getHints(),
                    project.getProjects(),
                    Pair.right(project.getNamedProjects()),
                    project.getVariablesSet()));
            return this;
          }
        }
      }
    }
    if (registrar.addedFieldCount() > 0) {
      project(registrar.extraNodes);
    }
    final RelNode sort =
        struct.sortFactory.createSort(peek(),
            RelCollations.of(fieldCollations), offsetNode, fetchNode);
    replaceTop(sort);
    if (registrar.addedFieldCount() > 0) {
      project(registrar.originalExtraNodes);
    }
    return this;
  }

  private static RelFieldCollation collation(RexNode node,
      RelFieldCollation.Direction direction,
      RelFieldCollation.@Nullable NullDirection nullDirection,
      List<RexNode> extraNodes) {
    switch (node.getKind()) {
    case INPUT_REF:
      return new RelFieldCollation(((RexInputRef) node).getIndex(), direction,
          first(nullDirection, direction.defaultNullDirection()));
    case DESCENDING:
      return collation(((RexCall) node).getOperands().get(0),
          RelFieldCollation.Direction.DESCENDING,
          nullDirection, extraNodes);
    case NULLS_FIRST:
      return collation(((RexCall) node).getOperands().get(0), direction,
          RelFieldCollation.NullDirection.FIRST, extraNodes);
    case NULLS_LAST:
      return collation(((RexCall) node).getOperands().get(0), direction,
          RelFieldCollation.NullDirection.LAST, extraNodes);
    default:
      final int fieldIndex = extraNodes.size();
      extraNodes.add(node);
      return new RelFieldCollation(fieldIndex, direction,
          first(nullDirection, direction.defaultNullDirection()));
    }
  }

  private static RexFieldCollation rexCollation(RexNode node,
      RelFieldCollation.Direction direction,
      RelFieldCollation.@Nullable NullDirection nullDirection) {
    switch (node.getKind()) {
    case DESCENDING:
      return rexCollation(((RexCall) node).operands.get(0),
          RelFieldCollation.Direction.DESCENDING, nullDirection);
    case NULLS_LAST:
      return rexCollation(((RexCall) node).operands.get(0),
          direction, RelFieldCollation.NullDirection.LAST);
    case NULLS_FIRST:
      return rexCollation(((RexCall) node).operands.get(0),
          direction, RelFieldCollation.NullDirection.FIRST);
    default:
      final Set<SqlKind> flags = EnumSet.noneOf(SqlKind.class);
      if (direction == RelFieldCollation.Direction.DESCENDING) {
        flags.add(SqlKind.DESCENDING);
      }
      if (nullDirection == RelFieldCollation.NullDirection.FIRST) {
        flags.add(SqlKind.NULLS_FIRST);
      }
      if (nullDirection == RelFieldCollation.NullDirection.LAST) {
        flags.add(SqlKind.NULLS_LAST);
      }
      return new RexFieldCollation(node, flags);
    }
  }

  /**
   * Creates a projection that converts the current relational expression's
   * output to a desired row type.
   *
   * <p>The desired row type and the row type to be converted must have the
   * same number of fields.
   *
   * @param castRowType row type after cast
   * @param rename      if true, use field names from castRowType; if false,
   *                    preserve field names from rel
   */
  public RelBuilder convert(RelDataType castRowType, boolean rename) {
    final RelNode r = build();
    final RelNode r2 =
        RelOptUtil.createCastRel(r, castRowType, rename,
            struct.projectFactory);
    push(r2);
    return this;
  }

  public RelBuilder permute(Mapping mapping) {
    assert mapping.getMappingType().isSingleSource();
    assert mapping.getMappingType().isMandatorySource();
    if (mapping.isIdentity()) {
      return this;
    }
    final List<RexNode> exprList = new ArrayList<>();
    for (int i = 0; i < mapping.getTargetCount(); i++) {
      exprList.add(field(mapping.getSource(i)));
    }
    return project(exprList);
  }

  /** Creates a {@link Sample}. (Repeatable if seed is not null.) */
  public RelBuilder sample(boolean bernoulli, BigDecimal sampleRate,
      @Nullable Integer repeatableSeed) {
    boolean repeatable;
    int seed;
    if (repeatableSeed != null) {
      repeatable = true;
      seed = repeatableSeed;
    } else {
      repeatable = false;
      seed = 0;
    }
    return sample(bernoulli, sampleRate, repeatable, seed);
  }

  /** Creates a {@link Sample}. */
  private RelBuilder sample(boolean bernoulli, BigDecimal sampleRate,
      boolean repeatable, int repeatableSeed) {
    if (sampleRate.compareTo(BigDecimal.ZERO) == 0) {
      // The sample rate is 0%; the query should return empty.
      return empty();
    } else if (sampleRate.compareTo(BigDecimal.ONE) == 0) {
      // The table sample rate is 100%; the query should return the contents
      // of the underlying table.
      return this;
    } else {
      final Frame frame = stack.pop();
      final RelNode r = frame.rel;
      final RelOptSamplingParameters param =
          new RelOptSamplingParameters(bernoulli, sampleRate, repeatable,
              repeatableSeed);
      return push(struct.sampleFactory.createSample(r, param));
    }
  }

  /** Creates a {@link Match}. */
  public RelBuilder match(RexNode pattern, boolean strictStart,
      boolean strictEnd, Map<String, RexNode> patternDefinitions,
      Iterable<? extends RexNode> measureList, RexNode after,
      Map<String, ? extends SortedSet<String>> subsets, boolean allRows,
      Iterable<? extends RexNode> partitionKeys,
      Iterable<? extends RexNode> orderKeys, RexNode interval) {
    final Registrar registrar =
        new Registrar(fields(), peek().getRowType().getFieldNames());
    final List<RelFieldCollation> fieldCollations =
        registrar.registerFieldCollations(orderKeys);

    final ImmutableBitSet partitionBitSet =
        ImmutableBitSet.of(registrar.registerExpressions(partitionKeys));

    final RelDataTypeFactory.Builder typeBuilder = cluster.getTypeFactory().builder();
    for (RexNode partitionKey : partitionKeys) {
      typeBuilder.add(partitionKey.toString(), partitionKey.getType());
    }
    if (allRows) {
      for (RexNode orderKey : orderKeys) {
        if (!typeBuilder.nameExists(orderKey.toString())) {
          typeBuilder.add(orderKey.toString(), orderKey.getType());
        }
      }

      final RelDataType inputRowType = peek().getRowType();
      for (RelDataTypeField fs : inputRowType.getFieldList()) {
        if (!typeBuilder.nameExists(fs.getName())) {
          typeBuilder.add(fs);
        }
      }
    }

    final ImmutableMap.Builder<String, RexNode> measures = ImmutableMap.builder();
    for (RexNode measure : measureList) {
      List<RexNode> operands = ((RexCall) measure).getOperands();
      String alias = operands.get(1).toString();
      typeBuilder.add(alias, operands.get(0).getType());
      measures.put(alias, operands.get(0));
    }

    final RelNode match =
        struct.matchFactory.createMatch(peek(), pattern,
            typeBuilder.build(), strictStart, strictEnd, patternDefinitions,
            measures.build(), after, subsets, allRows,
            partitionBitSet, RelCollations.of(fieldCollations), interval);
    stack.push(new Frame(match));
    return this;
  }

  /** Creates a Pivot.
   *
   * <p>To achieve the same effect as the SQL
   *
   * <blockquote><pre>{@code
   * SELECT *
   * FROM (SELECT mgr, deptno, job, sal FROM emp)
   * PIVOT (SUM(sal) AS ss, COUNT(*) AS c
   *     FOR (job, deptno)
   *     IN (('CLERK', 10) AS c10, ('MANAGER', 20) AS m20))
   * }</pre></blockquote>
   *
   * <p>use the builder as follows:
   *
   * <blockquote><pre>{@code
   * RelBuilder b;
   * b.scan("EMP");
   * final RelBuilder.GroupKey groupKey = b.groupKey("MGR");
   * final List<RelBuilder.AggCall> aggCalls =
   *     Arrays.asList(b.sum(b.field("SAL")).as("SS"),
   *         b.count().as("C"));
   * final List<RexNode> axes =
   *     Arrays.asList(b.field("JOB"),
   *         b.field("DEPTNO"));
   * final ImmutableMap.Builder<String, List<RexNode>> valueMap =
   *     ImmutableMap.builder();
   * valueMap.put("C10",
   *     Arrays.asList(b.literal("CLERK"), b.literal(10)));
   * valueMap.put("M20",
   *     Arrays.asList(b.literal("MANAGER"), b.literal(20)));
   * b.pivot(groupKey, aggCalls, axes, valueMap.build().entrySet());
   * }</pre></blockquote>
   *
   * <p>Note that the SQL uses a sub-query to project away columns (e.g.
   * {@code HIREDATE}) that it does not reference, so that they do not appear in
   * the {@code GROUP BY}. You do not need to do that in this API, because the
   * {@code groupKey} parameter specifies the keys.
   *
   * <p>Pivot is implemented by desugaring. The above example becomes the
   * following:
   *
   * <blockquote><pre>{@code
   * SELECT mgr,
   *     SUM(sal) FILTER (WHERE job = 'CLERK' AND deptno = 10) AS c10_ss,
   *     COUNT(*) FILTER (WHERE job = 'CLERK' AND deptno = 10) AS c10_c,
   *     SUM(sal) FILTER (WHERE job = 'MANAGER' AND deptno = 20) AS m20_ss,
   *      COUNT(*) FILTER (WHERE job = 'MANAGER' AND deptno = 20) AS m20_c
   * FROM emp
   * GROUP BY mgr
   * }</pre></blockquote>
   *
   * @param groupKey Key columns
   * @param aggCalls Aggregate expressions to compute for each value
   * @param axes Columns to pivot
   * @param values Values to pivot, and the alias for each column group
   *
   * @return this RelBuilder
   */
  public RelBuilder pivot(GroupKey groupKey,
      Iterable<? extends AggCall> aggCalls,
      Iterable<? extends RexNode> axes,
      Iterable<? extends Map.Entry<String,
          ? extends Iterable<? extends RexNode>>> values) {
    final List<RexNode> axisList = ImmutableList.copyOf(axes);
    final List<AggCall> multipliedAggCalls = new ArrayList<>();
    Pair.forEach(values, (alias, expressions) -> {
      final List<RexNode> expressionList = ImmutableList.copyOf(expressions);
      if (expressionList.size() != axisList.size()) {
        throw new IllegalArgumentException("value count must match axis count ["
            + expressionList + "], [" + axisList + "]");
      }
      aggCalls.forEach(aggCall -> {
        final String alias2 = alias + "_" + ((AggCallPlus) aggCall).alias();
        final List<RexNode> filters = new ArrayList<>();
        Pair.forEach(axisList, expressionList, (axis, expression) ->
            filters.add(equals(axis, expression)));
        multipliedAggCalls.add(aggCall.filter(and(filters)).as(alias2));
      });
    });
    return aggregate(groupKey, multipliedAggCalls);
  }

  /**
   * Creates an Unpivot.
   *
   * <p>To achieve the same effect as the SQL
   *
   * <blockquote><pre>{@code
   * SELECT *
   * FROM (SELECT deptno, job, sal, comm FROM emp)
   *   UNPIVOT INCLUDE NULLS (remuneration
   *     FOR remuneration_type IN (comm AS 'commission',
   *                               sal AS 'salary'))
   * }</pre></blockquote>
   *
   * <p>use the builder as follows:
   *
   * <blockquote><pre>{@code
   * RelBuilder b;
   * b.scan("EMP");
   * final List<String> measureNames = Arrays.asList("REMUNERATION");
   * final List<String> axisNames = Arrays.asList("REMUNERATION_TYPE");
   * final Map<List<RexLiteral>, List<RexNode>> axisMap =
   *     ImmutableMap.<List<RexLiteral>, List<RexNode>>builder()
   *         .put(Arrays.asList(b.literal("commission")),
   *             Arrays.asList(b.field("COMM")))
   *         .put(Arrays.asList(b.literal("salary")),
   *             Arrays.asList(b.field("SAL")))
   *         .build();
   * b.unpivot(false, measureNames, axisNames, axisMap);
   * }</pre></blockquote>
   *
   * <p>The query generates two columns: {@code remuneration_type} (an axis
   * column) and {@code remuneration} (a measure column). Axis columns contain
   * values to indicate the source of the row (in this case, {@code 'salary'}
   * if the row came from the {@code sal} column, and {@code 'commission'}
   * if the row came from the {@code comm} column).
   *
   * @param includeNulls Whether to include NULL values in the output
   * @param measureNames Names of columns to be generated to hold pivoted
   *                    measures
   * @param axisNames Names of columns to be generated to hold qualifying values
   * @param axisMap Mapping from the columns that hold measures to the values
   *           that the axis columns will hold in the generated rows
   * @return This RelBuilder
   */
  public RelBuilder unpivot(boolean includeNulls,
      Iterable<String> measureNames, Iterable<String> axisNames,
      Iterable<? extends Map.Entry<? extends List<? extends RexLiteral>,
          ? extends List<? extends RexNode>>> axisMap) {
    // Make immutable copies of all arguments.
    final List<String> measureNameList = ImmutableList.copyOf(measureNames);
    final List<String> axisNameList = ImmutableList.copyOf(axisNames);
    final PairList<List<RexLiteral>, List<RexNode>> map = PairList.of();
    Pair.forEach(axisMap, (valueList, inputMeasureList) ->
        map.add(ImmutableList.copyOf(valueList),
            ImmutableList.copyOf(inputMeasureList)));

    // Check that counts match.
    map.forEach((valueList, inputMeasureList) -> {
      if (inputMeasureList.size() != measureNameList.size()) {
        throw new IllegalArgumentException("Number of measures ("
            + inputMeasureList.size() + ") must match number of measure names ("
            + measureNameList.size() + ")");
      }
      if (valueList.size() != axisNameList.size()) {
        throw new IllegalArgumentException("Number of axis values ("
            + valueList.size() + ") match match number of axis names ("
            + axisNameList.size() + ")");
      }
    });

    final RelDataType leftRowType = peek().getRowType();
    final BitSet usedFields = new BitSet();
    map.forEach((aliases, nodes) ->
        nodes.forEach(node -> {
          if (node instanceof RexInputRef) {
            usedFields.set(((RexInputRef) node).getIndex());
          }
        }));

    // Create "VALUES (('commission'), ('salary')) AS t (remuneration_type)"
    values(ImmutableList.copyOf(map.leftList()), axisNameList);

    join(JoinRelType.INNER);

    final ImmutableBitSet unusedFields =
        ImmutableBitSet.range(leftRowType.getFieldCount())
            .except(ImmutableBitSet.fromBitSet(usedFields));
    final List<RexNode> projects = new ArrayList<>(fields(unusedFields));
    Ord.forEach(axisNameList, (dimensionName, d) ->
        projects.add(
            alias(field(leftRowType.getFieldCount() + d),
                dimensionName)));

    final List<RexNode> conditions = new ArrayList<>();
    Ord.forEach(measureNameList, (measureName, m) -> {
      final List<RexNode> caseOperands = new ArrayList<>();
      map.forEach((literals, nodes) -> {
        Ord.forEach(literals, (literal, d) ->
            conditions.add(
                equals(field(leftRowType.getFieldCount() + d), literal)));
        caseOperands.add(and(conditions));
        conditions.clear();
        caseOperands.add(nodes.get(m));
      });
      caseOperands.add(literal(null));
      projects.add(
          alias(call(SqlStdOperatorTable.CASE, caseOperands),
              measureName));
    });
    project(projects);

    if (!includeNulls) {
      // Add 'WHERE m1 IS NOT NULL OR m2 IS NOT NULL'
      final BitSet notNullFields = new BitSet();
      Ord.forEach(measureNameList, (measureName, m) -> {
        final int f = unusedFields.cardinality() + axisNameList.size() + m;
        conditions.add(isNotNull(field(f)));
        notNullFields.set(f);
      });
      filter(or(conditions));
      if (measureNameList.size() == 1) {
        // If there is one field, EXCLUDE NULLS will have converted it to NOT
        // NULL.
        final RelDataTypeFactory.Builder builder = getTypeFactory().builder();
        peek().getRowType().getFieldList().forEach(field -> {
          final RelDataType type = field.getType();
          builder.add(field.getName(),
              notNullFields.get(field.getIndex())
                  ? getTypeFactory().createTypeWithNullability(type, false)
                  : type);
        });
        convert(builder.build(), false);
      }
      conditions.clear();
    }

    return this;
  }

  /**
   * Attaches an array of hints to the stack top relational expression.
   *
   * <p>The redundant hints would be eliminated.
   *
   * @param hints Hints
   *
   * @throws AssertionError if the top relational expression does not implement
   * {@link org.apache.calcite.rel.hint.Hintable}
   */
  public RelBuilder hints(RelHint... hints) {
    return hints(ImmutableList.copyOf(hints));
  }

  /**
   * Attaches multiple hints to the stack top relational expression.
   *
   * <p>The redundant hints would be eliminated.
   *
   * @param hints Hints
   *
   * @throws AssertionError if the top relational expression does not implement
   * {@link org.apache.calcite.rel.hint.Hintable}
   */
  public RelBuilder hints(Iterable<RelHint> hints) {
    requireNonNull(hints, "hints");
    final List<RelHint> relHintList =
        hints instanceof List ? (List<RelHint>) hints
            : Lists.newArrayList(hints);
    if (relHintList.isEmpty()) {
      return this;
    }
    final Frame frame = peek_();
    assert frame != null : "There is no relational expression to attach the hints";
    assert frame.rel instanceof Hintable : "The top relational expression is not a Hintable";
    Hintable hintable = (Hintable) frame.rel;
    replaceTop(hintable.attachHints(relHintList));
    return this;
  }

  /** Clears the stack.
   *
   * <p>The builder's state is now the same as when it was created. */
  public void clear() {
    stack.clear();
  }

  /** Information necessary to create a call to an aggregate function.
   *
   * @see RelBuilder#aggregateCall */
  public interface AggCall {
    /** Returns a copy of this AggCall that applies a filter before aggregating
     * values. */
    AggCall filter(@Nullable RexNode condition);

    /** Returns a copy of this AggCall that sorts its input values by
     * {@code orderKeys} before aggregating, as in SQL's {@code WITHIN GROUP}
     * clause. */
    AggCall sort(Iterable<RexNode> orderKeys);

    /** Returns a copy of this AggCall that sorts its input values by
     * {@code orderKeys} before aggregating, as in SQL's {@code WITHIN GROUP}
     * clause. */
    default AggCall sort(RexNode... orderKeys) {
      return sort(ImmutableList.copyOf(orderKeys));
    }

    /** Returns a copy of this AggCall with the given pre-operands.
     *
     * <p>Pre-operands apply at the start of aggregation and are constant for
     * the whole query. They do not reference input columns and are typically
     * {@link RexLiteral}. An example is
     * {@link org.apache.calcite.sql.fun.SqlInternalOperators#LITERAL_AGG};
     * most aggregate functions do not take pre-operands. */
    AggCall preOperands(Iterable<? extends RexNode> preOperands);

    default AggCall preOperands(RexNode... preOperands) {
      return preOperands(ImmutableList.copyOf(preOperands));
    }

    /** Returns a copy of this AggCall that makes its input values unique by
     * {@code distinctKeys} before aggregating, as in SQL's
     * {@code WITHIN DISTINCT} clause. */
    AggCall unique(@Nullable Iterable<RexNode> distinctKeys);

    /** Returns a copy of this AggCall that makes its input values unique by
     * {@code distinctKeys} before aggregating, as in SQL's
     * {@code WITHIN DISTINCT} clause. */
    default AggCall unique(RexNode... distinctKeys) {
      return unique(ImmutableList.copyOf(distinctKeys));
    }

    /** Returns a copy of this AggCall that may return approximate results
     * if {@code approximate} is true. */
    AggCall approximate(boolean approximate);

    /** Returns a copy of this AggCall that ignores nulls. */
    AggCall ignoreNulls(boolean ignoreNulls);

    /** Returns a copy of this AggCall with a given alias. */
    AggCall as(@Nullable String alias);

    /** Returns a copy of this AggCall that is optionally distinct. */
    AggCall distinct(boolean distinct);

    /** Returns a copy of this AggCall that is distinct. */
    default AggCall distinct() {
      return distinct(true);
    }

    /** Converts this aggregate call to a windowed aggregate call. */
    OverCall over();
  }

  /** Internal methods shared by all implementations of {@link AggCall}. */
  private interface AggCallPlus extends AggCall {
    /** Returns the aggregate function. */
    SqlAggFunction op();

    /** Returns the alias. */
    @Nullable String alias();

    /** Returns an {@link AggregateCall} that is approximately equivalent
     * to this {@code AggCall} and is good for certain things, such as deriving
     * field names. */
    AggregateCall aggregateCall();

    /** Converts this {@code AggCall} to a good {@link AggregateCall}. */
    AggregateCall aggregateCall(Registrar registrar, ImmutableBitSet groupSet,
        RelNode r);

    /** Registers expressions in operands and filters. */
    void register(Registrar registrar);
  }

  /** Information necessary to create the GROUP BY clause of an Aggregate.
   *
   * @see RelBuilder#groupKey */
  public interface GroupKey {
    /** Assigns an alias to this group key.
     *
     * <p>Used to assign field names in the {@code group} operation. */
    GroupKey alias(@Nullable String alias);

    /** Returns the number of columns in the group key. */
    int groupKeyCount();
  }

  /** Implementation of {@link RelBuilder.GroupKey}. */
  static class GroupKeyImpl implements GroupKey {
    final ImmutableList<RexNode> nodes;
    final @Nullable ImmutableList<ImmutableList<RexNode>> nodeLists;
    final @Nullable String alias;

    GroupKeyImpl(ImmutableList<RexNode> nodes,
        @Nullable ImmutableList<ImmutableList<RexNode>> nodeLists,
        @Nullable String alias) {
      this.nodes = requireNonNull(nodes, "nodes");
      this.nodeLists = nodeLists;
      this.alias = alias;
    }

    @Override public String toString() {
      return alias == null ? nodes.toString() : nodes + " as " + alias;
    }

    @Override public int groupKeyCount() {
      return nodes.size();
    }

    @Override public GroupKey alias(@Nullable String alias) {
      return Objects.equals(this.alias, alias)
          ? this
          : new GroupKeyImpl(nodes, nodeLists, alias);
    }

    boolean isSimple() {
      return nodeLists == null || nodeLists.size() == 1;
    }
  }

  /**
   * Checks for {@link CorrelationId}, then validates the id is not used on left,
   * and finally checks if id is actually used on right.
   *
   * @return true if a correlate id is present and used
   *
   * @throws IllegalArgumentException if the {@link CorrelationId} is used by left side or if the a
   *   {@link CorrelationId} is present and the {@link JoinRelType} is FULL or RIGHT.
   */
  private boolean checkIfCorrelated(Set<CorrelationId> variablesSet,
      JoinRelType joinType, RelNode leftNode, RelNode rightRel) {
    if (variablesSet.size() != 1) {
      return false;
    }
    if (!config.convertCorrelateToJoin()) {
      return true;
    }
    CorrelationId id = Iterables.getOnlyElement(variablesSet);
    if (!RelOptUtil.notContainsCorrelation(leftNode, id, Litmus.IGNORE)) {
      throw new IllegalArgumentException("variable " + id
          + " must not be used by left input to correlation");
    }
    switch (joinType) {
    case RIGHT:
    case FULL:
      throw new IllegalArgumentException("Correlated " + joinType + " join is not supported");
    default:
      return !RelOptUtil.correlationColumns(
          Iterables.getOnlyElement(variablesSet),
          rightRel).isEmpty();
    }
  }


  /** Implementation of {@link AggCall}. */
  private class AggCallImpl implements AggCallPlus {
    private final SqlAggFunction aggFunction; //聚合函数
    private final boolean distinct; //是否去重
    private final boolean approximate; //是否近似计算
    private final boolean ignoreNulls; //是否忽略空值
    private final @Nullable RexNode filter; //过滤条件
    private final @Nullable String alias;
    private final ImmutableList<RexNode> preOperands; // may be empty
    private final ImmutableList<RexNode> operands; // may be empty
    private final @Nullable ImmutableList<RexNode> distinctKeys; // may be empty or null
    private final ImmutableList<RexNode> orderKeys; // may be empty

    AggCallImpl(SqlAggFunction aggFunction, boolean distinct,
        boolean approximate, boolean ignoreNulls, @Nullable RexNode filter,
        @Nullable String alias, ImmutableList<RexNode> preOperands,
        ImmutableList<RexNode> operands,
        @Nullable ImmutableList<RexNode> distinctKeys,
        ImmutableList<RexNode> orderKeys) {
      this.aggFunction = requireNonNull(aggFunction, "aggFunction");
      // If the aggregate function ignores DISTINCT,
      // make the DISTINCT flag FALSE.
      this.distinct = distinct
          && aggFunction.getDistinctOptionality() != Optionality.IGNORED; //count聚合函数的distinct选择性不是忽略
      this.approximate = approximate;
      this.ignoreNulls = ignoreNulls;
      this.alias = alias;
      this.preOperands = requireNonNull(preOperands, "preOperands");
      this.operands = requireNonNull(operands, "operands");
      this.distinctKeys = distinctKeys;
      this.orderKeys = requireNonNull(orderKeys, "orderKeys");
      if (filter != null) {
        if (filter.getType().getSqlTypeName() != SqlTypeName.BOOLEAN) {
          throw RESOURCE.filterMustBeBoolean().ex();
        }
        if (filter.getType().isNullable()) {
          filter = call(SqlStdOperatorTable.IS_TRUE, filter);
        }
      }
      this.filter = filter;
    }

    @Override public String toString() {
      final StringBuilder b = new StringBuilder();
      b.append(aggFunction.getName())
          .append('(');
      if (distinct) {
        b.append("DISTINCT ");
      }
      if (preOperands.size() > 0) {
        b.append(preOperands.get(0));
        for (int i = 1; i < preOperands.size(); i++) {
          b.append(", ");
          b.append(preOperands.get(i));
        }
        b.append(operands.size() > 0 ? "; " : ";");
      }
      if (operands.size() > 0) {
        b.append(operands.get(0));
        for (int i = 1; i < operands.size(); i++) {
          b.append(", ");
          b.append(operands.get(i));
        }
      }
      b.append(')');
      if (filter != null) {
        b.append(" FILTER (WHERE ").append(filter).append(')');
      }
      if (distinctKeys != null) {
        b.append(" WITHIN DISTINCT (").append(distinctKeys).append(')');
      }
      return b.toString();
    }

    @Override public SqlAggFunction op() {
      return aggFunction;
    }

    @Override public @Nullable String alias() {
      return alias;
    }

    @Override public AggregateCall aggregateCall() {
      // Use dummy values for collation and type. This method only promises to
      // return a call that is "approximately equivalent ... and is good for
      // deriving field names", so dummy values are good enough.
      final RelCollation collation = RelCollations.EMPTY;
      final RelDataType type =
          getTypeFactory().createSqlType(SqlTypeName.BOOLEAN);
      return AggregateCall.create(aggFunction, distinct, approximate,
          ignoreNulls, preOperands, ImmutableList.of(), -1,
          null, collation, type, alias);
    }

    @Override public AggregateCall aggregateCall(Registrar registrar,
        ImmutableBitSet groupSet, RelNode r) {
      List<Integer> args =
          registrar.registerExpressions(this.operands);
      final int filterArg =
          this.filter == null ? -1
              : registrar.registerExpression(this.filter);
      if (this.distinct && !this.aggFunction.isQuantifierAllowed()) {
        throw new IllegalArgumentException("DISTINCT not allowed");
      }
      if (this.filter != null && !this.aggFunction.allowsFilter()) {
        throw new IllegalArgumentException("FILTER not allowed");
      }
      final @Nullable ImmutableBitSet distinctKeys =
          this.distinctKeys == null
              ? null
              : ImmutableBitSet.of(
                  registrar.registerExpressions(this.distinctKeys));
      final RelCollation collation =
          RelCollations.of(this.orderKeys
              .stream()
              .map(orderKey ->
                  collation(orderKey, RelFieldCollation.Direction.ASCENDING,
                      null, Collections.emptyList()))
              .collect(Collectors.toList()));
      if (aggFunction instanceof SqlCountAggFunction && !distinct) {
        args = args.stream()
            .filter(r::fieldIsNullable)
            .collect(toImmutableList());
      }

      return AggregateCall.create(aggFunction, distinct, approximate,
          ignoreNulls, preOperands, args, filterArg, distinctKeys,
          collation, groupSet.cardinality(), r, null, alias);
    }

    @Override public void register(Registrar registrar) {
      registrar.registerExpressions(operands);
      if (filter != null) {
        registrar.registerExpression(filter);
      }
      if (distinctKeys != null) {
        registrar.registerExpressions(distinctKeys);
      }
      registrar.registerExpressions(orderKeys);
    }

    @Override public AggCall preOperands(
        Iterable<? extends RexNode> preOperands) {
      final ImmutableList<RexNode> preOperandList =
          ImmutableList.copyOf(preOperands);
      return preOperandList.equals(this.preOperands)
          ? this
          : new AggCallImpl(aggFunction, distinct, approximate, ignoreNulls,
              filter, alias, preOperandList, operands, distinctKeys, orderKeys);
    }

    @Override public OverCall over() {
      return new OverCallImpl(aggFunction, distinct, operands, ignoreNulls,
          alias);
    }

    @Override public AggCall sort(Iterable<RexNode> orderKeys) {
      final ImmutableList<RexNode> orderKeyList =
          ImmutableList.copyOf(orderKeys);
      return orderKeyList.equals(this.orderKeys)
          ? this
          : new AggCallImpl(aggFunction, distinct, approximate, ignoreNulls,
              filter, alias, preOperands, operands, distinctKeys, orderKeyList);
    }

    @Override public AggCall sort(RexNode... orderKeys) {
      return sort(ImmutableList.copyOf(orderKeys));
    }

    @Override public AggCall unique(@Nullable Iterable<RexNode> distinctKeys) {
      final @Nullable ImmutableList<RexNode> distinctKeyList =
          distinctKeys == null ? null : ImmutableList.copyOf(distinctKeys);
      return Objects.equals(distinctKeyList, this.distinctKeys)
          ? this
          : new AggCallImpl(aggFunction, distinct, approximate, ignoreNulls,
              filter, alias, preOperands, operands, distinctKeyList, orderKeys);
    }

    @Override public AggCall approximate(boolean approximate) {
      return approximate == this.approximate
          ? this
          : new AggCallImpl(aggFunction, distinct, approximate, ignoreNulls,
              filter, alias, preOperands, operands, distinctKeys, orderKeys);
    }

    @Override public AggCall filter(@Nullable RexNode condition) {
      return Objects.equals(condition, this.filter)
          ? this
          : new AggCallImpl(aggFunction, distinct, approximate, ignoreNulls,
              condition, alias, preOperands, operands, distinctKeys, orderKeys);
    }

    @Override public AggCall as(@Nullable String alias) {
      return Objects.equals(alias, this.alias)
          ? this
          : new AggCallImpl(aggFunction, distinct, approximate, ignoreNulls,
              filter, alias, preOperands, operands, distinctKeys, orderKeys);
    }

    @Override public AggCall distinct(boolean distinct) {
      return distinct == this.distinct
          ? this
          : new AggCallImpl(aggFunction, distinct, approximate, ignoreNulls,
              filter, alias, preOperands, operands, distinctKeys, orderKeys);
    }

    @Override public AggCall ignoreNulls(boolean ignoreNulls) {
      return ignoreNulls == this.ignoreNulls
          ? this
          : new AggCallImpl(aggFunction, distinct, approximate, ignoreNulls,
              filter, alias, preOperands, operands, distinctKeys, orderKeys);
    }
  }

  /** Implementation of {@link AggCall} that wraps an
   * {@link AggregateCall}. */
  private class AggCallImpl2 implements AggCallPlus {
    private final AggregateCall aggregateCall;
    private final ImmutableList<RexNode> operands;

    AggCallImpl2(AggregateCall aggregateCall, ImmutableList<RexNode> operands) {
      this.aggregateCall = requireNonNull(aggregateCall, "aggregateCall");
      this.operands = requireNonNull(operands, "operands");
    }

    @Override public OverCall over() {
      return new OverCallImpl(aggregateCall.getAggregation(),
          aggregateCall.isDistinct(), operands, aggregateCall.ignoreNulls(),
          aggregateCall.name);
    }

    @Override public String toString() {
      return aggregateCall.toString();
    }

    @Override public SqlAggFunction op() {
      return aggregateCall.getAggregation();
    }

    @Override public @Nullable String alias() {
      return aggregateCall.name;
    }

    @Override public AggregateCall aggregateCall() {
      return aggregateCall;
    }

    @Override public AggregateCall aggregateCall(Registrar registrar,
        ImmutableBitSet groupSet, RelNode r) {
      return aggregateCall;
    }

    @Override public void register(Registrar registrar) {
      // nothing to do
    }

    @Override public AggCall preOperands(Iterable<? extends RexNode> preOperands) {
      throw new UnsupportedOperationException();
    }

    @Override public AggCall sort(Iterable<RexNode> orderKeys) {
      throw new UnsupportedOperationException();
    }

    @Override public AggCall sort(RexNode... orderKeys) {
      throw new UnsupportedOperationException();
    }

    @Override public AggCall unique(@Nullable Iterable<RexNode> distinctKeys) {
      throw new UnsupportedOperationException();
    }

    @Override public AggCall approximate(boolean approximate) {
      throw new UnsupportedOperationException();
    }

    @Override public AggCall filter(@Nullable RexNode condition) {
      throw new UnsupportedOperationException();
    }

    @Override public AggCall as(@Nullable String alias) {
      throw new UnsupportedOperationException();
    }

    @Override public AggCall distinct(boolean distinct) {
      throw new UnsupportedOperationException();
    }

    @Override public AggCall ignoreNulls(boolean ignoreNulls) {
      throw new UnsupportedOperationException();
    }
  }

  /** Call to a windowed aggregate function.
   *
   * <p>To create an {@code OverCall}, start with an {@link AggCall} (created
   * by a method such as {@link #aggregateCall}, {@link #sum} or {@link #count})
   * and call its {@link AggCall#over()} method. For example,
   *
   * <pre>{@code
   *      b.scan("EMP")
   *         .project(b.field("DEPTNO"),
   *            b.aggregateCall(SqlStdOperatorTable.ROW_NUMBER)
   *               .over()
   *               .partitionBy()
   *               .orderBy(b.field("EMPNO"))
   *               .rowsUnbounded()
   *               .allowPartial(true)
   *               .nullWhenCountZero(false)
   *               .as("x"))
   * }</pre>
   *
   * <p>Unlike an aggregate call, a windowed aggregate call is an expression
   * that you can use in a {@link Project} or {@link Filter}. So, to finish,
   * call {@link OverCall#toRex()} to convert the {@code OverCall} to a
   * {@link RexNode}; the {@link OverCall#as} method (used in the above example)
   * does the same but also assigns an column alias.
   */
  public interface OverCall {
    /** Performs an action on this OverCall. */
    default <R> R let(Function<OverCall, R> consumer) {
      return consumer.apply(this);
    }

    /** Sets the PARTITION BY clause to an array of expressions. */
    OverCall partitionBy(RexNode... expressions);

    /** Sets the PARTITION BY clause to a list of expressions. */
    OverCall partitionBy(Iterable<? extends RexNode> expressions);

    /** Sets the ORDER BY BY clause to an array of expressions.
     *
     * <p>Use {@link #desc(RexNode)}, {@link #nullsFirst(RexNode)},
     * {@link #nullsLast(RexNode)} to control the sort order. */
    OverCall orderBy(RexNode... expressions);

    /** Sets the ORDER BY BY clause to a list of expressions.
     *
     * <p>Use {@link #desc(RexNode)}, {@link #nullsFirst(RexNode)},
     * {@link #nullsLast(RexNode)} to control the sort order. */
    OverCall orderBy(Iterable<? extends RexNode> expressions);

    /** Sets an unbounded ROWS window,
     * equivalent to SQL {@code ROWS BETWEEN UNBOUNDED PRECEDING AND
     * UNBOUNDED FOLLOWING}. */
    default OverCall rowsUnbounded() {
      return rowsBetween(RexWindowBounds.UNBOUNDED_PRECEDING,
          RexWindowBounds.UNBOUNDED_FOLLOWING);
    }

    /** Sets a ROWS window with a lower bound,
     * equivalent to SQL {@code ROWS BETWEEN lower AND CURRENT ROW}. */
    default OverCall rowsFrom(RexWindowBound lower) {
      return rowsBetween(lower, RexWindowBounds.UNBOUNDED_FOLLOWING);
    }

    /** Sets a ROWS window with an upper bound,
     * equivalent to SQL {@code ROWS BETWEEN CURRENT ROW AND upper}. */
    default OverCall rowsTo(RexWindowBound upper) {
      return rowsBetween(RexWindowBounds.UNBOUNDED_PRECEDING, upper);
    }

    /** Sets a RANGE window with lower and upper bounds,
     * equivalent to SQL {@code ROWS BETWEEN lower ROW AND upper}. */
    OverCall rowsBetween(RexWindowBound lower, RexWindowBound upper);

    /** Sets an unbounded RANGE window,
     * equivalent to SQL {@code RANGE BETWEEN UNBOUNDED PRECEDING AND
     * UNBOUNDED FOLLOWING}. */
    default OverCall rangeUnbounded() {
      return rangeBetween(RexWindowBounds.UNBOUNDED_PRECEDING,
          RexWindowBounds.UNBOUNDED_FOLLOWING);
    }

    /** Sets a RANGE window with a lower bound,
     * equivalent to SQL {@code RANGE BETWEEN lower AND CURRENT ROW}. */
    default OverCall rangeFrom(RexWindowBound lower) {
      return rangeBetween(lower, RexWindowBounds.CURRENT_ROW);
    }

    /** Sets a RANGE window with an upper bound,
     * equivalent to SQL {@code RANGE BETWEEN CURRENT ROW AND upper}. */
    default OverCall rangeTo(RexWindowBound upper) {
      return rangeBetween(RexWindowBounds.UNBOUNDED_PRECEDING, upper);
    }

    /** Sets the frame to EXCLUDE rows; default to EXCLUDE_NO_OTHER. */
    OverCall exclude(RexWindowExclusion exclude);

    /** Sets a RANGE window with lower and upper bounds,
     * equivalent to SQL {@code RANGE BETWEEN lower ROW AND upper}. */
    OverCall rangeBetween(RexWindowBound lower, RexWindowBound upper);

    /** Sets whether to allow partial width windows; default true. */
    OverCall allowPartial(boolean allowPartial);

    /** Sets whether the aggregate function should evaluate to null if no rows
     * are in the window; default false. */
    OverCall nullWhenCountZero(boolean nullWhenCountZero);

    /** Sets the alias of this expression, and converts it to a {@link RexNode};
     * default is the alias that was set via {@link AggCall#as(String)}. */
    RexNode as(String alias);

    /** Converts this expression to a {@link RexNode}. */
    RexNode toRex();
  }

  /** Implementation of {@link OverCall}. */
  private class OverCallImpl implements OverCall {
    private final ImmutableList<RexNode> operands;
    private final boolean ignoreNulls;
    private final @Nullable String alias;
    private final boolean nullWhenCountZero;
    private final boolean allowPartial;
    private final boolean rows;
    private final RexWindowBound lowerBound;
    private final RexWindowBound upperBound;
    private final RexWindowExclusion exclude;
    private final ImmutableList<RexNode> partitionKeys;
    private final ImmutableList<RexFieldCollation> sortKeys;
    private final SqlAggFunction op;
    private final boolean distinct;

    private OverCallImpl(SqlAggFunction op, boolean distinct,
        ImmutableList<RexNode> operands, boolean ignoreNulls,
        @Nullable String alias, ImmutableList<RexNode> partitionKeys,
        ImmutableList<RexFieldCollation> sortKeys, boolean rows,
        RexWindowBound lowerBound, RexWindowBound upperBound,
        boolean nullWhenCountZero, boolean allowPartial, RexWindowExclusion exclude) {
      this.op = op;
      this.distinct = distinct;
      this.operands = operands;
      this.ignoreNulls = ignoreNulls;
      this.alias = alias;
      this.partitionKeys = partitionKeys;
      this.sortKeys = sortKeys;
      this.nullWhenCountZero = nullWhenCountZero;
      this.allowPartial = allowPartial;
      this.rows = rows;
      this.lowerBound = lowerBound;
      this.upperBound = upperBound;
      this.exclude = exclude;
    }

    /** Creates an OverCallImpl with default settings. */
    OverCallImpl(SqlAggFunction op, boolean distinct,
        ImmutableList<RexNode> operands, boolean ignoreNulls,
        @Nullable String alias) {
      this(op, distinct, operands, ignoreNulls, alias, ImmutableList.of(),
          ImmutableList.of(), true, RexWindowBounds.UNBOUNDED_PRECEDING,
          RexWindowBounds.UNBOUNDED_FOLLOWING, false, true, RexWindowExclusion.EXCLUDE_NO_OTHER);
    }

    @Override public OverCall partitionBy(
        Iterable<? extends RexNode> expressions) {
      return partitionBy_(ImmutableList.copyOf(expressions));
    }

    @Override public OverCall partitionBy(RexNode... expressions) {
      return partitionBy_(ImmutableList.copyOf(expressions));
    }

    private OverCall partitionBy_(ImmutableList<RexNode> partitionKeys) {
      return new OverCallImpl(op, distinct, operands, ignoreNulls, alias,
          partitionKeys, sortKeys, rows, lowerBound, upperBound,
          nullWhenCountZero, allowPartial, exclude);
    }

    private OverCall orderBy_(ImmutableList<RexFieldCollation> sortKeys) {
      return new OverCallImpl(op, distinct, operands, ignoreNulls, alias,
          partitionKeys, sortKeys, rows, lowerBound, upperBound,
          nullWhenCountZero, allowPartial, exclude);
    }

    @Override public OverCall orderBy(Iterable<? extends RexNode> sortKeys) {
      ImmutableList.Builder<RexFieldCollation> fieldCollations =
          ImmutableList.builder();
      sortKeys.forEach(sortKey ->
          fieldCollations.add(
              rexCollation(sortKey, RelFieldCollation.Direction.ASCENDING,
                  RelFieldCollation.NullDirection.UNSPECIFIED)));
      return orderBy_(fieldCollations.build());
    }

    @Override public OverCall orderBy(RexNode... sortKeys) {
      return orderBy(Arrays.asList(sortKeys));
    }

    @Override public OverCall rowsBetween(RexWindowBound lowerBound,
        RexWindowBound upperBound) {
      return new OverCallImpl(op, distinct, operands, ignoreNulls, alias,
          partitionKeys, sortKeys, true, lowerBound, upperBound,
          nullWhenCountZero, allowPartial, exclude);
    }

    @Override public OverCall rangeBetween(RexWindowBound lowerBound,
        RexWindowBound upperBound) {
      return new OverCallImpl(op, distinct, operands, ignoreNulls, alias,
          partitionKeys, sortKeys, false, lowerBound, upperBound,
          nullWhenCountZero, allowPartial, exclude);
    }

    @Override public OverCall exclude(RexWindowExclusion exclude) {
      return new OverCallImpl(op, distinct, operands, ignoreNulls, alias,
          partitionKeys, sortKeys, rows, lowerBound, upperBound,
          nullWhenCountZero, allowPartial, exclude);
    }

    @Override public OverCall allowPartial(boolean allowPartial) {
      return new OverCallImpl(op, distinct, operands, ignoreNulls, alias,
          partitionKeys, sortKeys, rows, lowerBound, upperBound,
          nullWhenCountZero, allowPartial, exclude);
    }

    @Override public OverCall nullWhenCountZero(boolean nullWhenCountZero) {
      return new OverCallImpl(op, distinct, operands, ignoreNulls, alias,
          partitionKeys, sortKeys, rows, lowerBound, upperBound,
          nullWhenCountZero, allowPartial, exclude);
    }

    @Override public RexNode as(String alias) {
      return new OverCallImpl(op, distinct, operands, ignoreNulls, alias,
          partitionKeys, sortKeys, rows, lowerBound, upperBound,
          nullWhenCountZero, allowPartial, exclude).toRex();
    }

    @Override public RexNode toRex() {
      final RexCallBinding bind =
          new RexCallBinding(getTypeFactory(), op, operands,
              ImmutableList.of()) {
            @Override public int getGroupCount() {
              return SqlWindow.isAlwaysNonEmpty(lowerBound, upperBound) ? 1 : 0;
            }
          };
      final RelDataType type = op.inferReturnType(bind);
      final RexNode over = getRexBuilder()
          .makeOver(type, op, operands, partitionKeys, sortKeys,
              lowerBound, upperBound, exclude, rows, allowPartial, nullWhenCountZero,
              distinct, ignoreNulls);
      return aliasMaybe(over, alias);
    }
  }

  /** Collects the extra expressions needed for {@link #aggregate}.
   * 额外的表达式来自聚合key，并且可以当作聚合调用的参数
   * <p>The extra expressions come from the group key and as arguments to
   * aggregate calls, and later there will be a {@link #project} or a
   * {@link #rename(List)} if necessary. */
  private static class Registrar {
    final List<RexNode> originalExtraNodes;
    final List<RexNode> extraNodes;
    final List<@Nullable String> names;

    Registrar(Iterable<RexNode> fields, List<String> fieldNames) {
      originalExtraNodes = ImmutableList.copyOf(fields);
      extraNodes = new ArrayList<>(originalExtraNodes);
      names = new ArrayList<>(fieldNames);
    }

    int registerExpression(RexNode node) {
      switch (node.getKind()) {
      case AS:
        final List<RexNode> operands = ((RexCall) node).operands;
        final int i = registerExpression(operands.get(0));
        names.set(i, RexLiteral.stringValue(operands.get(1)));
        return i;
      case DESCENDING:
      case NULLS_FIRST:
      case NULLS_LAST:
        return registerExpression(((RexCall) node).operands.get(0));
      default:
        final int i2 = extraNodes.indexOf(node);
        if (i2 >= 0) {
          return i2;
        }
        extraNodes.add(node); //如果是衍生表达式，则加入
        names.add(null);   //衍生表达式，默认字段名字为空
        return extraNodes.size() - 1;
      }
    }

    List<Integer> registerExpressions(Iterable<? extends RexNode> nodes) {
      final List<Integer> builder = new ArrayList<>();
      for (RexNode node : nodes) {
        builder.add(registerExpression(node));
      }
      return builder;
    }

    List<RelFieldCollation> registerFieldCollations(
        Iterable<? extends RexNode> orderKeys) {
      final List<RelFieldCollation> fieldCollations = new ArrayList<>();
      for (RexNode orderKey : orderKeys) {
        final RelFieldCollation collation =
            collation(orderKey, RelFieldCollation.Direction.ASCENDING, null,
                extraNodes);
        if (!RelCollations.ordinals(fieldCollations)
            .contains(collation.getFieldIndex())) {
          fieldCollations.add(collation);
        }
      }
      return ImmutableList.copyOf(fieldCollations);
    }

    /** Returns the number of fields added. */
    int addedFieldCount() {
      return extraNodes.size() - originalExtraNodes.size();
    }
  }

  /** Builder stack frame.
   *
   * <p>Describes a previously created relational expression and
   * information about how table aliases map into its row type. */
  // 操作数栈帧
  // RelBuilder 是基于一个操作数栈（Stack）来管理算子的。而这个栈里存储的元素，并不是孤零零的 RelNode，而是被包裹在这个 Frame 对象里。
  // 在 SQL 或关系代数中，有两个致命的痛点，如果只靠原生的 RelNode 极难优雅地解决：
  // 表别名（Table Alias）与作用域的追踪：比如 SQL 中写了 SELECT * FROM emp AS e JOIN dept AS d。后续当你写 WHERE e.id = d.id 时，优化器必须知道 e.id 到底指向哪张表的哪一列。但原生的 RelNode 在做完 Join 后，底层表别名 e 和 d 的语义往往就丢失了，只剩下一堆扁平的字段。
  // 字段位置的动态映射：由于两个表 Join 之后，右表的字段索引（Index）会整体发生偏移，调用方很难实时记住每个字段当前的精确下标。
  // Frame 就是为此而生的：它是 RelBuilder 内部的“元数据保护壳”。它不仅持有了底层的关系代数节点（rel），还死死地捆绑维护了一张“表别名集合 -> 字段元数据”的实时映射表（fields）。
  // 有了 Frame，不管算子树怎么叠加、怎么 Join、字段下标怎么变，RelBuilder 都能根据别名（如 "e"）秒级帮用户定位到正确的列。
  private static class Frame {
    // 当前栈帧所包裹的真实关系代数表达式节点（算子树）
    // 核心的物理/逻辑数据载体，例如一个 LogicalTableScan、LogicalProject 或一棵复杂的 Join 树。
    final RelNode rel;
    // 最核心的元数据设计：别名集合到字段域的不可变映射双列集合。
    // RelDataTypeField：字段的真实元数据（包含字段名、字段类型、字段在当前行类型中的绝对索引 index）。
    // ImmutableSet<String>：绑定在这个字段上的所有合法别名（Aliases）。一个字段为什么会有多个别名？例如：emp 表起了别名 e，那么它的 id 列就同时拥有了无别名、emp.id 和 e.id 的访问资格。
    final ImmutablePairList<ImmutableSet<String>, RelDataTypeField> fields;

    private Frame(RelNode rel,
        PairList<ImmutableSet<String>, RelDataTypeField> fields) {
      this.rel = rel;
      this.fields = fields.immutable();
    }

    private Frame(RelNode rel) {
      String tableAlias = deriveAlias(rel);
      final PairList<ImmutableSet<String>, RelDataTypeField> fields =
          PairList.of();
      final ImmutableSet<String> aliases =
          tableAlias == null
              ? ImmutableSet.of()
              : ImmutableSet.of(tableAlias);
      for (RelDataTypeField field : rel.getRowType().getFieldList()) {
        fields.add(aliases, field);
      }
      this.rel = rel;
      this.fields = fields.immutable();
    }

    @Override public String toString() {
      return rel + ": " + fields;
    }

    private static @Nullable String deriveAlias(RelNode rel) {
      if (rel instanceof TableScan) {
        TableScan scan = (TableScan) rel;
        final List<String> names = scan.getTable().getQualifiedName();
        if (!names.isEmpty()) {
          return Util.last(names);
        }
      }
      return null;
    }

    List<RelDataTypeField> fields() {
      return fields.rightList();
    }
  }

  /** Shuttle that shifts a predicate's inputs to the left, replacing early
   * ones with references to a
   * {@link RexCorrelVariable}. */
  private class Shifter extends RexShuttle {
    private final RelNode left;
    private final CorrelationId id;
    private final RelNode right;

    Shifter(RelNode left, CorrelationId id, RelNode right) {
      this.left = left;
      this.id = id;
      this.right = right;
    }

    @Override public RexNode visitInputRef(RexInputRef inputRef) {
      final RelDataType leftRowType = left.getRowType();
      final RexBuilder rexBuilder = getRexBuilder();
      final int leftCount = leftRowType.getFieldCount();
      if (inputRef.getIndex() < leftCount) {
        final RexNode v = rexBuilder.makeCorrel(leftRowType, id);
        return rexBuilder.makeFieldAccess(v, inputRef.getIndex());
      } else {
        return rexBuilder.makeInputRef(right, inputRef.getIndex() - leftCount);
      }
    }
  }

  /** Configuration of RelBuilder.
   *
   * <p>It is immutable, and all fields are public.
   *
   * <p>Start with the {@link #DEFAULT} instance,
   * and call {@code withXxx} methods to set its properties. */
  @Value.Immutable
  public interface Config {
    /** Default configuration. */
    Config DEFAULT = ImmutableRelBuilder.Config.of();

    /** Controls whether  to merge two {@link Project} operators when inlining
     * expressions causes complexity to increase.
     *
     * <p>Usually merging projects is beneficial, but occasionally the
     * result is more complex than the original projects. Consider:
     *
     * <pre>
     * P: Project(a+b+c AS x, d+e+f AS y, g+h+i AS z)  # complexity 15
     * Q: Project(x*y*z AS p, x-y-z AS q)              # complexity 10
     * R: Project((a+b+c)*(d+e+f)*(g+h+i) AS s,
     *            (a+b+c)-(d+e+f)-(g+h+i) AS t)        # complexity 34
     * </pre>
     *
     * <p>The complexity of an expression is the number of nodes (leaves and
     * operators). For example, {@code a+b+c} has complexity 5 (3 field
     * references and 2 calls):
     *
     * <pre>
     *       +
     *      /  \
     *     +    c
     *    / \
     *   a   b
     * </pre>
     *
     * <p>A negative value never allows merges.
     *
     * <p>A zero or positive value, {@code bloat}, allows a merge if complexity
     * of the result is less than or equal to the sum of the complexity of the
     * originals plus {@code bloat}.
     *
     * <p>The default value, 100, allows a moderate increase in complexity but
     * prevents cases where complexity would run away into the millions and run
     * out of memory. Moderate complexity is OK; the implementation, say via
     * {@link org.apache.calcite.adapter.enumerable.EnumerableCalc}, will often
     * gather common sub-expressions and compute them only once.
     */
    // 表达式膨胀率阈值
    // 控制当 RelBuilder 连续构建两个相邻的 Project（投影/控制列字段）时，是否允许将它们强行合并（Merge/Inline）成一个 Project。
    @Value.Default default int bloat() {
      return 100;
    }

    /** Sets {@link #bloat}. */
    Config withBloat(int bloat);

    /** Whether {@link RelBuilder#aggregate} should eliminate duplicate
     * aggregate calls; default true. */
    // 聚合函数调用去重
    // 控制在构建 Aggregate（分组聚合）算子时，是否自动合并重复的聚合函数
    // 示例：用户发送了 SELECT COUNT(sal), SUM(sal), COUNT(sal) FROM emp GROUP BY deptno。当该配置为 true 时，RelBuilder 在推导这一层时，会自动发现存在两个一模一样的 COUNT(sal)，最终在算子树底层只保留一个 COUNT(sal) 物理计算项，顶层再通过 Project 映射出两列，避免重复计算。
    @Value.Default default boolean dedupAggregateCalls() {
      return true;
    }

    /** Sets {@link #dedupAggregateCalls}. */
    Config withDedupAggregateCalls(boolean dedupAggregateCalls);

    /** Whether {@link RelBuilder#aggregate} should prune unused
     * input columns; default true. */
    // 聚合输入列裁剪
    // 控制是否自动剔除传递给聚合算子的“无用底层列”。
    // 示例：底层表有 (deptno, sal, name, age) 四列。上层代码调用 builder.aggregate 算子时指定 GROUP BY deptno 且度量只有 SUM(sal)。此时 name 和 age 完全是累赘。如果为 true，RelBuilder 会自动在 Aggregate 下方贴一层 Project(deptno, sal)，把没用的列在输入阶段就拦腰斩断（Prune）。
    @Value.Default default boolean pruneInputOfAggregate() {
      return true;
    }

    /** Sets {@link #pruneInputOfAggregate}. */
    Config withPruneInputOfAggregate(boolean pruneInputOfAggregate);

    /** Whether to ensure that relational operators always have at least one
     * column. */
    // 阻止空字段列表
    // 在关系代数中，理论上是允许存在“0 个字段/0 列”的算子的（例如 SELECT 1 FROM table，如果不需要返回任何原始表字段，底层可以为空）。但是很多底层的执行引擎（物理算子）对“0列”的物理数据结构会直接抛错。
    // 该配置设为 true 可以强制确保任何衍生算子至少保留一列（哪怕是个无意义的 dummy 虚拟列），用来保障物理引擎兼容性。
    @Value.Default default boolean preventEmptyFieldList() {
      return true;
    }

    /** Sets {@link #preventEmptyFieldList()}. */
    Config withPreventEmptyFieldList(boolean preventEmptyFieldList);

    /** Whether to push down join conditions; default false (but
     * {@link SqlToRelConverter#config()} by default sets this to true). */
    // 下推 Join 条件
    // 默认值：false（但 SqlToRelConverter 的配置默认会把它覆盖成 true）
    // 控制当你在 RelBuilder 中构建 Join 时，如果把过滤条件以参数传给了 join() 方法，Builder 是否自动把这层 Condition 尝试解耦并下推到对应的左/右子树中，变成底层的 Filter 算子。
    @Value.Default default boolean pushJoinCondition() {
      return false;
    }

    /** Sets {@link #pushJoinCondition()}. */
    Config withPushJoinCondition(boolean pushJoinCondition);
    // 启用表达式简化
    /** Whether to simplify expressions; default true. */
    @Value.Default default boolean simplify() {
      return true;
    }

    /** Sets {@link #simplify}. */
    Config withSimplify(boolean simplify);

    /** Whether to simplify LIMIT 0 to an empty relation; default true. */
    // 简化 LIMIT 0 行为
    // 当你在流式 API 中调用 builder.limit(0) 时，如果此项为 true，Builder 会认为这一整棵树反正一条数据也吐不出来，它会直接把整棵底层树彻底废弃，当场替换为一个轻量级的 Values（空行集合）节点，直接斩断所有底层物理表的扫描 I/O。
    @Value.Default default boolean simplifyLimit() {
      return true;
    }

    /** Sets {@link #simplifyLimit()}. */
    Config withSimplifyLimit(boolean simplifyLimit);

    /** Whether to simplify {@code Union(Values, Values)} or
     * {@code Union(Project(Values))} to {@code Values}; default true. */
    // 静态值合并简化
    // 如果上层算子在做类似 UNION 的操作，且参与 Union 的子节点全都是一堆静态内存常量数据（Values 算子），或者被简单 Project 包裹的 Values，如果为 true，RelBuilder 会在内存里直接把这些多层 Values 节点融合成单张大 Values 常量表。
    @Value.Default default boolean simplifyValues() {
      return true;
    }

    /** Sets {@link #simplifyValues()}. */
    Config withSimplifyValues(boolean simplifyValues);

    /** Whether to create an Aggregate even if we know that the input is
     * already unique; default false. */
    // 唯一性输入下的聚合保留
    // 果 RelBuilder 通过元数据分析，得知下层传入的数据流在 GROUP BY 的那几个 Key 上本来就是全局唯一的（Unique）（比如基于主键分组），那么这一层 GROUP BY 其实就是个摆设（因为每组都只有一条数据，不需要真正做聚合分桶）。
    @Value.Default default boolean aggregateUnique() {
      return false;
    }

    /** Sets {@link #aggregateUnique()}. */
    Config withAggregateUnique(boolean aggregateUnique);

    /** Whether to convert Correlate to Join if correlation variable is unused. */
    // 关联查询转传统 Join
    // 当构建 Correlate 算子（通常由 SQL 里的 LATERAL、带有相关子查询的 EXISTS 触发）时，如果系统检测到子查询内部其实压根没有使用外部传进来的关联变量（Correlation variable），说明它本质上就是一个独立的普通查询。如果此项为 true，Builder 会直接把这个昂贵、难以分布式并行化的 Correlate 算子退化并重构为高效率的普通 Join 算子。
    @Value.Default default boolean convertCorrelateToJoin() {
      return true;
    }

    /** Sets {@link #convertCorrelateToJoin()}. */
    Config withConvertCorrelateToJoin(boolean convertCorrelateToJoin);

    /** Whether to remove the distinct that in aggregate if we know that the input is
     * already unique; default false. */
    // 移除冗余的 Distinct 标记
    // 在 Aggregate 算子里处理带去重的度量（如 SUM(DISTINCT sal)）时，如果系统通过唯一性约束已知输入的 sal 列本来就没有重复值，是否直接把 DISTINCT 标记删掉，退化成普通的 SUM(sal)。
    @Value.Default
    default boolean removeRedundantDistinct() {
      return false;
    }

    /**
     * Sets {@link #removeRedundantDistinct()}.
     */
    Config withRemoveRedundantDistinct(boolean removeRedundantDistinct);
  }

}
