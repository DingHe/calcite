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

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * RelTraitDef represents a class of {@link RelTrait}s. Implementations of
 * RelTraitDef may be singletons under the following conditions:
 * <ol>
 * <li>if the set of all possible associated RelTraits is finite and fixed (e.g.
 * all RelTraits for this RelTraitDef are known at compile time). For example,
 * the CallingConvention trait meets this requirement, because CallingConvention
 * is effectively an enumeration.</li>
 * <li>Either
 *
 * <ul>
 * <li> {@link #canConvert(RelOptPlanner, RelTrait, RelTrait)} and
 * {@link #convert(RelOptPlanner, RelNode, RelTrait, boolean)} do not require
 * planner-instance-specific information, <b>or</b></li>
 *
 * <li>the RelTraitDef manages separate sets of conversion data internally. See
 * {@link ConventionTraitDef} for an example of this.</li>
 * </ul>
 * </li>
 * </ol>
 *
 * <p>Otherwise, a new instance of RelTraitDef must be constructed and
 * registered with each new planner instantiated.
 *
 * @param <T> Trait that this trait definition is based upon
 */
// RelTraitDef（Relation Trait Definition）是 Apache Calcite 优化器框架（Volcano/Hep 规划器）中极具分量的特质维度定义基类
// 在 Calcite 的 CBO（基于代价的优化器）中，一个关系代数节点（RelNode）除了自身携带的算子语义（如 Project, Filter）外，
// 还必须绑定一组 “特质”（RelTrait）。特质代表了算子在特定物理执行环境下的状态或物理约束
// 而 RelTraitDef 的作用，就是定义和管理某一个独立特质维度的“元数据中枢”与“类型工厂”。
// 核心职责可以概括为三个核心维度：
// 定义特质的空间维度：Calcite 将特质划分为不同的、互不干扰的独立维度。
// ConventionTraitDef：管理 调用约定（Convention） 维度（如：它是逻辑层 Convention.NONE，还是物理层 EnumerableConvention、SparkConvention）
// RelCollationTraitDef：管理 排序列/排序规则（Collation） 维度（如：数据是否已经按 Id ASC 拍好了序）。
// RelDistributionTraitDef：管理 分布式物理分布（Distribution） 维度（如：数据是 Hash 分片、Broadcast 广播还是 Singleton 单点）。
// 全局唯一规范化（Canonization）：为了极速提升优化器在 Memo 空间进行等价哈希比对时的性能，RelTraitDef 充当了单例享元工坊，确保内存中相同的特质对象永远只保留一份，从而允许外界直接使用硬核的 ==（指针相等）替代高成本的 .equals() 判定。
// 驱动特征物理转换（Converter）：当优化器发现父节点要求的特质与子节点现有的特质不匹配时（例如：父节点要求物理流，子节点是逻辑流），优化器会直接调动对应维度的 RelTraitDef 去寻找或强行孵化出转换算子（Converter），从而拉通数据流。

public abstract class RelTraitDef<T extends RelTrait> {
  //~ Instance fields --------------------------------------------------------

  /**
   * Cache of traits.
   *
   * <p>Uses weak interner to allow GC.
   */
  // 当前特质维度的享元池（规范化缓存池）
  // 这意味着它充当了一个线程安全的内存池。
  // 当某个特质对象在计划树中没有被任何 RelNode 或优化器 Memo 引用时，JVM 的垃圾回收器（GC）可以自由地将其从该池中回收销毁，
  // 从而完美防止了复杂 SQL 长期多阶段优化时发生内存泄漏（OOM），同时又能保证活跃特质的单例重用。
  @SuppressWarnings("BetaApi")
  private final Interner<T> interner = Interners.newWeakInterner();

  //~ Constructors -----------------------------------------------------------

  protected RelTraitDef() {
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Whether a relational expression may possess more than one instance of
   * this trait simultaneously.
   * <p>A subset has only one instance of a trait.
   */
  // 判定一个关系表达式（RelNode）是否可以同时拥有该特征维度的多个实例。
  // 默认直接返回 false。在 Calcite 官方实现中，绝大部分物理维度都是唯一的（例如一个算子不可能同时既是 EnumerableConvention 又是 SparkConvention）。
  //但是该方法留下了多态扩展。当面对多维复合排序（如数据同时满足 a ASC, b DESC 以及 c ASC 两种排序序列断言）时，特定的子类可以重写此方法返回 true。
  public boolean multiple() {
    return false;
  }

  /** Returns the specific RelTrait type associated with this RelTraitDef. */
  // 抽象方法，
  // 返回与当前定义紧密绑定的具体 RelTrait 实现类的 Class 类型。
  // 应用场景：例如 ConventionTraitDef 会返回 Convention.class。框架借此在运行期开展安全的反射及类型实例判定。
  public abstract Class<T> getTraitClass();

  /** Returns a simple name for this RelTraitDef (for use in
   * {@link org.apache.calcite.rel.RelNode#explain}). */
  // 作用：抽象方法，返回该特质维度的一个简短可读名称。
  // 应用场景：专供 RelNode#explain（打印执行计划）时使用。
  // 例如排序维度会返回 "collation"，分布式维度会返回 "distribution"。
  // 用户在看 EXPLAIN 文本时看到的 [convention=ENUMERABLE, collation=[0 ASC]] 标签名就是由它决定的。
  public abstract String getSimpleName();

  /**
   * Takes an arbitrary RelTrait and returns the canonical representation of
   * that RelTrait. Canonized RelTrait objects may always be compared using
   * the equality operator (<code>==</code>).
   *
   * <p>If an equal RelTrait has already been canonized and is still in use,
   * it will be returned. Otherwise, the given RelTrait is made canonical and
   * returned.
   *
   * @param trait a possibly non-canonical RelTrait
   * @return a canonical RelTrait.
   */
  // 将一个任意创建的、可能非规范化的特质对象，强转并收敛为全局唯一的规范化单例（Canonical Representation）
  @SuppressWarnings("BetaApi")
  public final T canonize(T trait) {
    // 非复合特质（RelCompositeTrait）传入时，必须强制校验它是当前特质维度的合法实例。
    // 通过 interner.intern(trait)，如果池中已存在通过 .equals() 判定相等的特质，则直接抛弃新对象，返回池中的老对象；
    // 否则将新对象入池并返回。
    // 这确保了整个 Calcite 优化器内部，所有的特质比较都可以极速退化为 if (trait1 == trait2) 的 CPU 寄存器级指针比对。
    if (!(trait instanceof RelCompositeTrait)) {
      assert getTraitClass().isInstance(trait)
          : getClass().getName()
          + " cannot canonize a "
          + trait.getClass().getName();
    }
    return interner.intern(trait);
  }

  /**
   * Converts the given RelNode to the given RelTrait.
   * 把给定的关系节点按照给定的特征转换
   * @param planner                     the planner requesting the conversion
   * @param rel                         RelNode to convert
   * @param toTrait                     RelTrait to convert to
   * @param allowInfiniteCostConverters flag indicating whether infinite cost
   *                                    converters are allowed
   * @return a converted RelNode or null if conversion is not possible
   */
  // 负责将一个关系节点（rel）强行转换为具备目标特质（toTrait）的全新关系节点。
  // 物理内幕：这是优化器拉通物理特质的底层驱动核心。例如：
  // 当 ConventionTraitDef 收到请求，要把一个逻辑算子转换为 EnumerableConvention 时，它会去寻找对应的 ConverterRule，
  // 并孵化出一个 EnumerableConverter 物理算子包裹在原算子上方。
  public abstract @Nullable RelNode convert(
      RelOptPlanner planner,
      RelNode rel,
      T toTrait,
      boolean allowInfiniteCostConverters);

  /**
   * Tests whether the given RelTrait can be converted to another RelTrait.
   *
   * @param planner   the planner requesting the conversion test
   * @param fromTrait the RelTrait to convert from
   * @param toTrait   the RelTrait to convert to
   * @return true if fromTrait can be converted to toTrait
   */
  // 作用：抽象方法，提供转换路径的可行性超前探测。
  // 物理含义：用于快速回答优化器：“在当前规划器环境下，能否支持从 fromTrait 特质转换到 toTrait 特质？”（例如：从 EnumerableConvention 转换到 SparkConvention 是否可行）。
  // 如果返回 false，优化器将直接放弃尝试，不再调用上面的 convert 方法，从而大幅裁剪无效的搜索空间。
  public abstract boolean canConvert(
      RelOptPlanner planner,
      T fromTrait,
      T toTrait);

  /**
   * Provides notification of the registration of a particular
   * {@link ConverterRule} with a {@link RelOptPlanner}. The default
   * implementation does nothing.
   *
   * @param planner       the planner registering the rule
   * @param converterRule the registered converter rule
   */
  // 作用：当一个特质转换规则（ConverterRule）被注册到优化器（RelOptPlanner）时，触发的异步回调通知接口。
  public void registerConverterRule(
      RelOptPlanner planner,
      ConverterRule converterRule) {
  }

  /**
   * Provides notification that a particular {@link ConverterRule} has been
   * de-registered from a {@link RelOptPlanner}. The default implementation
   * does nothing.
   *
   * @param planner       the planner registering the rule
   * @param converterRule the registered converter rule
   */
  // 作用：当一个转换规则从优化器中被注销或废弃时，触发的异步回调通知接口。
  public void deregisterConverterRule(
      RelOptPlanner planner,
      ConverterRule converterRule) {
  }

  /**
   * Returns the default member of this trait.
   */
  // 作用：抽象方法，返回当前特征维度在缺省状态下的默认基本特质（Default Member）。
  // 物理含义：任何一个关系算子在刚被创建而没有指定任何物理约束时，都需要一个保底的特质。
  // 对于 ConventionTraitDef，默认特质是 Convention.NONE（纯逻辑状态）。
  // 对于 RelCollationTraitDef（排序维度），默认特质是 RelCollations.EMPTY（代表数据内部杂乱无章、完全无序）。
  public abstract T getDefault();
}
