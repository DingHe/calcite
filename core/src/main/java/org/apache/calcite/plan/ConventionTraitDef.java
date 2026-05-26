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
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.graph.DefaultDirectedGraph;
import org.apache.calcite.util.graph.DefaultEdge;
import org.apache.calcite.util.graph.DirectedGraph;
import org.apache.calcite.util.graph.Graphs;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Definition of the convention trait.
 * A new set of conversion information is created for
 * each planner that registers at least one {@link ConverterRule} instance.
 *
 * <p>Conversion data is held in a {@link LoadingCache}
 * with weak keys so that the JVM's garbage
 * collector may reclaim the conversion data after the planner itself has been
 * garbage collected. The conversion information consists of a graph of
 * conversions (from one calling convention to another) and a map of graph arcs
 * to {@link ConverterRule}s.
 */
// ConventionTraitDef 是 Apache Calcite 优化器框架中用于管理“调用约定（Convention）”特质的核心定义类。
// Convention 代表一个物理执行流派（如：纯逻辑状态 Convention.NONE、内存单线程物理流 EnumerableConvention、分布式物理流 SparkConvention 等）。
// 而 ConventionTraitDef 的核心任务，就是在底层构建一张“调用约定转换的有向图（Directed Graph）”，并利用最短路径算法驱动不同物理引擎之间的计划转换。
// 核心作用
// 图结构驱动的特质转换：它在内部为每个 RelOptPlanner（优化器实例）专门维护了一个有向图。图的顶点（Vertex）是不同的 Convention，边（Edge/Arc）是转换规则 ConverterRule。
// 多阶段桥接转换（Multi-step Conversion）：如果用户想把一个 ConventionA 的算子转换为 ConventionC，但系统中并没有直接一步到位的 A -> C 的规则，ConventionTraitDef 会通过图算法（如广度优先/最短路径）自动推导并计算出一条桥接路径（例如 A -> B -> C），然后按顺序串联调用转换规则，生成一个复合的转换算子链路
// 基于弱引用的优化器级隔离与防内存泄漏：不同的优化器实例注册的规则可能不同。该类通过 Google Guava Cache 的弱键（Weak Keys）机制，让转换图的生命周期与 RelOptPlanner 实例强绑定，一旦优化器被 GC 回收，其对应的图数据也立即释放。
public class ConventionTraitDef extends RelTraitDef<Convention> {
  //~ Static fields/initializers ---------------------------------------------
  // 全局单例对象。
  // 调用约定维度在整个 JVM 进程中通常只需要这一个元数据中枢来调度。
  public static final ConventionTraitDef INSTANCE =
      new ConventionTraitDef();

  //~ Instance fields --------------------------------------------------------

  /**
   * Weak-key cache of RelOptPlanner to ConversionData. The idea is that when
   * the planner goes away, so does the cache entry.
   */
  // 核心转换数据缓存
  // 将 RelOptPlanner 映射到专属的 ConversionData（转换有向图和规则矩阵）。
  private final LoadingCache<RelOptPlanner, ConversionData> conversionCache =
      CacheBuilder.newBuilder().weakKeys()
          .build(CacheLoader.from(ConversionData::new));

  //~ Constructors -----------------------------------------------------------

  private ConventionTraitDef() {
    super();
  }

  //~ Methods ----------------------------------------------------------------

  // implement RelTraitDef
  @Override public Class<Convention> getTraitClass() {
    return Convention.class;
  }

  @Override public String getSimpleName() {
    return "convention";
  }

  @Override public Convention getDefault() {
    return Convention.NONE;
  }
  // 当优化器（RelOptPlanner，例如 VolcanoPlanner）在初始化或运行期间注册一个新的特质转换规则（ConverterRule）时，会自动调用此方法。
  // 核心任务是：将该规则所声明的“输入调用约定（In Convention）”与“输出调用约定（Out Convention）”提取出来，
  // 在内存中动态织入一张有向图（Directed Graph）中。
  // 依靠这张图，Calcite 以后就能像地图导航一样，自动计算出任意两个物理执行引擎（Convention）之间的多阶段转换路径。
  @Override public void registerConverterRule(
      // 当前正在执行规则注册行为的优化器实例（通常一个 Query 对应一个 Planner）。
      RelOptPlanner planner,
      // 被注册的转换规则（例如 EnumerableProjectRule，其职责是将逻辑 Project 转换为内存单线程物理形态的 EnumerableProject）
      ConverterRule converterRule) {
    // 检查该规则是否是“强确定性/有完备保证”的转换规则。
    // Guaranteed (true)：代表该规则声称“只要你给我任何一个符合 inTrait 的算子，我百分之百能无条件把它转换为 outTrait 的物理形态”。这种绝对可靠的规则，才有资格作为“边（Edge）”被写入路由有向图，用来计算多阶段路径。
    if (converterRule.isGuaranteed()) {
      // 去内部的 Guava Cache 缓存中拿取当前 planner 实例专属的 ConversionData 工作空间。
      ConversionData conversionData = getConversionData(planner);
      // 提取顶点：输入与输出调用约定
      final Convention inConvention =
          (Convention) converterRule.getInTrait();
      final Convention outConvention =
          (Convention) converterRule.getOutTrait();
      // 动态构建有向图（拓扑织入）
      // 将输入调用约定作为一个顶点（Vertex）扔进有向图。如果顶点已存在，图结构会自动忽略。
      conversionData.conversionGraph.addVertex(inConvention);
      // 将输出调用约定作为另一个顶点扔进有向图。
      conversionData.conversionGraph.addVertex(outConvention);
      // 在两点之间，强行拉出一条从 in 指向 out 的有向边（Edge）。
      conversionData.conversionGraph.addEdge(inConvention, outConvention);
      // 将这一对有向边作为 Key（Pair.of(in, out)），把当前这个 converterRule 实例作为 Value，存入一个多值映射容器（Multimap）中
      conversionData.mapArcToConverterRule.put(
          Pair.of(inConvention, outConvention), converterRule);
    }
  }
  // 当规则从优化器中注销、撤销时，触发的回调通知
  @Override public void deregisterConverterRule(
      RelOptPlanner planner,
      ConverterRule converterRule) {
    if (converterRule.isGuaranteed()) {
      ConversionData conversionData = getConversionData(planner);

      final Convention inConvention =
          (Convention) converterRule.getInTrait();
      final Convention outConvention =
          (Convention) converterRule.getOutTrait();

      final boolean removed =
          conversionData.conversionGraph.removeEdge(
              inConvention, outConvention);
      assert removed;
      conversionData.mapArcToConverterRule.remove(
          Pair.of(inConvention, outConvention), converterRule);
    }
  }

  // implement RelTraitDef
  // 职责是驱动并执行不同物理流派（Convention）之间的转换。
  // 当优化器发现一棵算子树中，父节点要求的执行环境与子节点现有的执行环境不一致时，就会调度此方法，
  // 顺着我们在前面注册规则时织好的“调用约定有向图”，把一个逻辑或物理算子逐步包装改写成目标调用约定的算子。
  @Override public @Nullable RelNode convert(
      RelOptPlanner planner,
      RelNode rel,
      Convention toConvention,
      boolean allowInfiniteCostConverters) {
    // 获取当前关系表达式集群的元数据查询引擎（RelMetadataQuery）。
    // 它在后面被用来高频计算中间算子的物理代价（Cost）
    final RelMetadataQuery mq = rel.getCluster().getMetadataQuery();
    // 提取当前优化器实例专属的有向图数据结构（ConversionData）。
    final ConversionData conversionData = getConversionData(planner);
    // 获取当前算子现有的调用约定（即路径的起点）
    final Convention fromConvention =
        requireNonNull(rel.getConvention(),
            () -> "convention is null for rel " + rel);
    // 计算出从当前状态 fromConvention 到目标状态 toConvention 的所有潜在可达路径集合
    List<List<Convention>> conversionPaths =
        conversionData.getPaths(fromConvention, toConvention);

  loop:
    // 遍历每一条潜在路径
    for (List<Convention> conversionPath : conversionPaths) {
      assert conversionPath.get(0) == fromConvention;
      assert conversionPath.get(conversionPath.size() - 1)
          == toConvention;
      RelNode converted = rel;
      Convention previous = null;
      // 遍历单条路径中的每一个顶点（arc）
      for (Convention arc : conversionPath) {
        // 利用最开始拿到的 RelMetadataQuery，实时计算当前这个正在转换的算子 converted 的物理代价（Cpu、Memory、I/O 等组成的复合 Cost
        RelOptCost cost = planner.getCost(converted, mq);
        // 如果算子的代价值为空或者为无穷大（isInfinite()），且外部传参声明了“不允许无限代价转换器”（!allowInfiniteCostConverters），
        // 说明当前转换状态已经导致计划陷入死胡同或物理上完全不可行。
        if ((cost == null || cost.isInfinite())
            && !allowInfiniteCostConverters) {
          continue loop;
        }
        if (previous != null) {
          // 执行单步特质转换
          converted =
              changeConvention(
                  converted, previous, arc,
                  conversionData.mapArcToConverterRule);
          // 在前一步注册规则（registerConverterRule）时，我们强调过只有 isGuaranteed()（有百分百成功保证）的规则才有资格入图
          if (converted == null) {
            throw new AssertionError("Converter from " + previous + " to " + arc
                + " guaranteed that it could convert any relexp");
          }
        }
        previous = arc;
      }
      return converted;
    }

    return null;
  }

  /**
   * Tries to convert a relational expression to the target convention of an
   * arc.
   */
  // 真正动手改写算子的“物理执行者”。
  // 当 convert 方法确定了某一步转换的起点（source）和终点（target）后，就会调度该方法来寻找并应用对应的转换规则。
  // 核心任务是：在特定的调用约定对（Source -> Target）之间，寻找并依次尝试所有可用的 ConverterRule（转换规则）
  // ，直到其中某个规则成功将输入的 RelNode 转换为目标调用约定形态。
  private static @Nullable RelNode changeConvention(
      RelNode rel, // 当前等待转换的原始关系代数节点
      // 代表本次单步转换的源物理流派与目标物理流派（有向图中的一条特定弧/边）。
      Convention source,
      Convention target,
      // 之前在 registerConverterRule 中织好的、以 Pair<Convention, Convention> 作为 Key 的边到转换规则的多值映射矩阵。
      final Multimap<Pair<Convention, Convention>, ConverterRule>
          mapArcToConverterRule) {
    assert source == rel.getConvention();

    // Try to apply each converter rule for this arc's source/target calling
    // conventions.
    final Pair<Convention, Convention> key = Pair.of(source, target);
    // 在 $\mathcal{O}(1)$ 的时间复杂度内抓取出所有绑定在这条转换路径上的 ConverterRule 列表并开始遍历。
    for (ConverterRule rule : mapArcToConverterRule.get(key)) {
      assert rule.getInTrait() == source;
      assert rule.getOutTrait() == target;
      // 真正干活的阶段
      // 调度当前 ConverterRule 的 convert(rel) 方法
      RelNode converted = rule.convert(rel);
      if (converted != null) {
        return converted;
      }
    }
    return null;
  }

  // 优化器在进行搜索空间裁剪（Search Space Pruning）时高频调用的超前探测接口。
  // 在 CBO（基于代价的优化）探索执行计划的过程中，优化器需要频繁询问：“我能不能把一个处于 fromConvention 的算子转换为 toConvention？”
  // 如果回答是 false，优化器就会果断放弃这条无意义的搜索路径，从而极大提升拓展效率。
  // 以极快的速度判定（返回 true 或 false），在当前的优化器规则配置下，是否能够通过某种直接或间接的手段，将数据流从起点调用约定转换到目标调用约定。
  @Override public boolean canConvert(
      RelOptPlanner planner,
      Convention fromConvention, // 转换的源调用约定（起点）
      Convention toConvention) { // 转换的目标调用约定（终点）
    ConversionData conversionData = getConversionData(planner);
    // 直接询问起点调用约定（fromConvention）对象本身：“你自己在代码实现里，有没有硬编码支持直接转换到 toConvention 的特殊能力？
    return fromConvention.canConvertConvention(toConvention)
        // 有向图拓扑连通性探测。 如果轨道一不满足，则求助于我们之前编织好的规则有向图。
        || conversionData.getShortestDistance(fromConvention, toConvention) != -1;
  }

  private ConversionData getConversionData(RelOptPlanner planner) {
    return conversionCache.getUnchecked(planner);
  }

  //~ Inner Classes ----------------------------------------------------------

  /** Workspace for converting from one convention to another. */
  // Apache Calcite 调用约定（Convention）维度的核心数据蓄水池与图算法工作空间。
  // 每个优化器实例（RelOptPlanner）在 ConventionTraitDef 的缓存中都独占一个 ConversionData 实例。
  // 优化器之所以能具备“导航”能力、自动推导复杂跨引擎转换路径，全仰仗这个内部类在底层提供的图结构支撑。
  private static final class ConversionData {
    // 动态有向图结构
    // 顶点（Vertex）：Convention 对象（如 NONE, ENUMERABLE, SPARK）
    // 有向边（Edge）：DefaultEdge，代表这两个调用约定之间存在至少一条物理转换通道。
    final DirectedGraph<Convention, DefaultEdge> conversionGraph =
        DefaultDirectedGraph.create();

    /** 一对convention，可能有多条转换规则
     * For a given source/target convention, there may be several possible
     * conversion rules. Maps {@link DefaultEdge} to a
     * collection of {@link ConverterRule} objects.
     */
    // 边到规则列表的倒排索引（多值映射矩阵）
    // 由于有向图的边（DefaultEdge）本身不承载具体的执行代码，我们需要一个容器来记录“这条边到底对应哪个具体的转换规则”。
    // 采用 Google Guava 的 HashMultimap（一对多关系）。因为在同一条转换边上（例如逻辑流转 ENUMERABLE 物理流），会有很多个不同的规则（Project规则、Filter规则、Join规则等）并存。
    final Multimap<Pair<Convention, Convention>, ConverterRule> mapArcToConverterRule =
        HashMultimap.create();

    private Graphs.@MonotonicNonNull FrozenGraph<Convention, DefaultEdge> pathMap;

    public List<List<Convention>> getPaths(
        Convention fromConvention,
        Convention toConvention) {
      return getPathMap().getPaths(fromConvention, toConvention);
    }

    private Graphs.FrozenGraph<Convention, DefaultEdge> getPathMap() {
      if (pathMap == null) {
        pathMap = Graphs.makeImmutable(conversionGraph);
      }
      return pathMap;
    }

    public int getShortestDistance(
        Convention fromConvention,
        Convention toConvention) {
      return getPathMap().getShortestDistance(fromConvention, toConvention);
    }
  }
}
