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
package org.apache.calcite.rel;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelDigest;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.hint.Hintable;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.Metadata;
import org.apache.calcite.rel.metadata.MetadataFactory;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.runtime.PairList;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableSet;

import org.apiguardian.api.API;
import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

/**
 * Base class for every relational expression ({@link RelNode}).
 */
// 在 Apache Calcite 中，SQL 语句首先会被解析并转换为一棵由 RelNode 组成的关系算子树（关系表达式树）。
// AbstractRelNode 实现了 RelNode 接口，它的主要作用是：
// 提供通用基础属性的维护：为每个算子节点提供全局唯一的 id（用于调试）、rowType（数据行类型/Schema）、traitSet（物理特征，如排序规则、分布式规则等）以及 cluster（优化器上下文环境）。
// 骨架实现与默认行为：定义了节点在优化器（Planner）中注册、复制、遍历、打印和比较的默认逻辑。这样具体的子类（如具体的 Join 或 Project）只需要关注自身特有的业务逻辑，而不需要重复编写通波段代码。
// 管理节点的摘要（Digest）：通过内部机制，计算并缓存当前节点的唯一字符串摘要，优化器利用这个摘要来快速判断两个逻辑算子树节点是否等价，从而避免重复计算。
public abstract class AbstractRelNode implements RelNode {
  //~ Static fields/initializers ---------------------------------------------

  /** Generator for {@link #id} values. */
  // 全局原子计数器。用于为整个 JVM 进程中创建的每一个 RelNode 实例分配一个递增的、唯一的 ID。
  private static final AtomicInteger NEXT_ID = new AtomicInteger(0);

  //~ Instance fields --------------------------------------------------------

  /**
   * Cached type of this relational expression.
   */
  // 缓存当前关系表达式输出的行类型（Schema）。
  // 它定义了当前算子输出的数据包含哪些字段、字段名以及各自的数据类型。使用延迟加载，初次调用 getRowType() 时才通过 deriveRowType() 推导。
  protected @MonotonicNonNull RelDataType rowType;

  /**
   * The digest that uniquely identifies the node.
   */
  // 当前节点的唯一身份摘要对象（包装了结构、特征和参数）。Calcite 的优化器依赖它来识别完全相同的算子，以进行去重和缓存。
  @API(since = "1.24", status = API.Status.INTERNAL)
  protected RelDigest digest;
  // 当前节点所属的集群（环境上下文）。它持有类型工厂（TypeFactory）、 planner（优化器）、元数据查询引擎等全局共享资源。
  private final RelOptCluster cluster;

  /** Unique id of this object, for debugging. */
  // 当前节点的唯一整数 ID，主要在可视化打印执行计划、日志调试（rel#123）时使用。
  protected final int id;

  /** RelTraitSet that describes the traits of this RelNode. */
  // 特征集。包含该节点的物理特征，比如它属于什么调用约定（Convention，如逻辑层、JDBC层、Spark层）、排序方式（Collation）、分布方式（Distribution）等。
  protected RelTraitSet traitSet;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates an <code>AbstractRelNode</code>.
   */
  protected AbstractRelNode(RelOptCluster cluster, RelTraitSet traitSet) {
    super();
    assert cluster != null;
    this.cluster = cluster;
    this.traitSet = traitSet;
    this.id = NEXT_ID.getAndIncrement();
    this.digest = new InnerRelDigest();
  }

  //~ Methods ----------------------------------------------------------------

  @Override public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    // Note that empty set equals empty set, so relational expressions
    // with zero inputs do not generally need to implement their own copy
    // method.
    if (getInputs().equals(inputs)
        && traitSet == getTraitSet()) {
      return this;
    }
    throw new AssertionError("Relational expression should override copy. "
        + "Class=[" + getClass()
        + "]; traits=[" + getTraitSet()
        + "]; desired traits=[" + traitSet
        + "]");
  }
  // 断言集合中必须有且仅有一个元素，并将其返回。通常用于单输入算子（如 Filter, Project）获取其唯一的输入。
  protected static <T> T sole(List<T> collection) {
    assert collection.size() == 1;
    return collection.get(0);
  }

  @Override public final RelOptCluster getCluster() {
    return cluster;
  }

  // 从特征集 traitSet 中快速提取并返回当前节点的调用约定（Convention，如 EnumerableConvention、LogicalConvention）
  @Pure
  @Override public final @Nullable Convention getConvention(
      @UnknownInitialization AbstractRelNode this) {
    return traitSet == null ? null : traitSet.getTrait(ConventionTraitDef.INSTANCE);
  }

  @Override public RelTraitSet getTraitSet() {
    return traitSet;
  }
  //默认变量为空
  @Override public @Nullable String getCorrelVariable() {
    return null;
  }

  @Override public int getId() {
    return id;
  }
  // 获取当前节点第 i 个子节点（输入算子）。
  @Override public RelNode getInput(int i) {
    List<RelNode> inputs = getInputs();
    return inputs.get(i);
  }
  // 向优化器注册当前节点特有的优化规则。
  // 基类中默认使用 Util.discard(planner) 什么都不做，留给需要自定义注册规则的特殊算子重写
  @Override public void register(RelOptPlanner planner) {
    Util.discard(planner);
  }

  // It is not recommended to override this method, but sub-classes can do it at their own risk.
  // 获取当前关系算子的类型名称。
  // 默认截取当前类的全限定类名或内部类名最后一部分（如 org.apache.calcite.rel.logical.LogicalProject 会返回 LogicalProject）。
  @Override public String getRelTypeName() {
    String cn = getClass().getName();
    int i = cn.length();
    while (--i >= 0) {
      if (cn.charAt(i) == '$' || cn.charAt(i) == '.') {
        return cn.substring(i + 1);
      }
    }
    return cn;
  }

  @Override public boolean isValid(Litmus litmus, @Nullable Context context) {
    return litmus.succeed();
  }
  // 对外暴露的获取行类型（Schema）的方法。
  // 被 final 修饰。如果 rowType 为空，会调用 deriveRowType() 进行推导并缓存。
  @Override public final RelDataType getRowType() {
    if (rowType == null) {
      rowType = deriveRowType();
      assert rowType != null : this;
    }
    return rowType;
  }
  // 由具体实现类去重写的类型推导逻辑。
  // 基类中默认直接抛出异常。如果子类在构造时没有主动传入 rowType，就必须实现该方法来计算自己的 Schema。
  protected RelDataType deriveRowType() {
    // This method is only called if rowType is null, so you don't NEED to
    // implement it if rowType is always set.
    throw new UnsupportedOperationException();
  }
  // 希望输入的行类型，默认是行类型
  @Override public RelDataType getExpectedInputRowType(int ordinalInParent) {
    return getRowType();
  }

  // 获取当前节点的所有子节点。基类默认返回一个空列表（Collections.emptyList()），意味着叶子节点（如 TableScan）无需重写。
  @Override public List<RelNode> getInputs() {
    return Collections.emptyList();
  }
  // 估算当前算子输出的数据行数。基类默认返回 1.0。
  // 具体的子类通常会结合底层元数据进行更精确的估算（例如 Filter 会乘以过滤率）。
  @Override public double estimateRowCount(RelMetadataQuery mq) {
    return 1.0;
  }
  //默认为空
  @Override public Set<CorrelationId> getVariablesSet() {
    return ImmutableSet.of();
  }

  @Override public void collectVariablesUsed(Set<CorrelationId> variableSet) {
    // for default case, nothing to do
  }
  //指示当前节点是否是一个“强制执行（Enforcer）”节点。比如 Sort 算子是为了强制满足上层对排序的要求而存在的，它不改变数据本身。默认返回 false。
  @Override public boolean isEnforcer() {
    return false;
  }

  @Override public void collectVariablesSet(Set<CorrelationId> variableSet) {
  }
  // 让传入的访问者（RelVisitor）遍历当前节点的所有子节点。
  @Override public void childrenAccept(RelVisitor visitor) {
    List<RelNode> inputs = getInputs();
    for (int i = 0; i < inputs.size(); i++) {
      visitor.visit(inputs.get(i), i, this);
    }
  }
  // 接受一个关系表达式穿梭器（RelShuttle），用于在关系算子树上进行变换或查找。默认调用 shuttle.visit(this)。
  @Override public RelNode accept(RelShuttle shuttle) {
    // Call fall-back method. Specific logical types (such as LogicalProject
    // and LogicalJoin) have their own RelShuttle.visit methods.
    return shuttle.visit(this);
  }
  // 接受一个行表达式穿梭器（RexShuttle），用于遍历或修改算子内部包含的表达式（如 Project 的投影列表，Filter 的条件）。
  // 由于基类不持有任何 RexNode，默认直接返回 this。
  @Override public RelNode accept(RexShuttle shuttle) {
    return this;
  }
  // 计算当前算子自身的执行代价（CPU, I/O, 内存等）。
  // 默认认为代价与输出行数 rowCount 成正比。Cost 越小，越容易被优化器选中。
  @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
      RelMetadataQuery mq) {
    // by default, assume cost is proportional to number of rows
    double rowCount = mq.getRowCount(this);
    return planner.getCostFactory().makeCost(rowCount, rowCount, 0);
  }
  // 已废弃方法（将在 2.0 前移除）。用于通过元数据工厂查询该节点指定类型的元数据（如累积代价、谓词下推信息等）。
  @Deprecated // to be removed before 2.0
  @Override public final <@Nullable M extends @Nullable Metadata> M metadata(Class<M> metadataClass,
      RelMetadataQuery mq) {
    final MetadataFactory factory = cluster.getMetadataFactory();
    final M metadata = factory.query(this, mq, metadataClass);
    assert metadata != null
        : "no provider found (rel=" + this + ", m=" + metadataClass
        + "); a backstop provider is recommended";
    // Usually the metadata belongs to the rel that created it. RelSubset and
    // HepRelVertex are notable exceptions, so disable the assert. It's not
    // worth the performance hit to override this method for them.
    //   assert metadata.rel() == this : "someone else's metadata";
    return metadata;
  }

  @Override public void explain(RelWriter pw) {
    explainTerms(pw).done(this);
  }

  /**
   * Describes the inputs and attributes of this relational expression.
   * Each node should call {@code super.explainTerms}, then call the
   * {@link org.apache.calcite.rel.externalize.RelWriterImpl#input(String, RelNode)}
   * and
   * {@link RelWriter#item(String, Object)}
   * methods for each input and attribute.
   *
   * @param pw Plan writer
   * @return Plan writer for fluent-explain pattern
   */
  public RelWriter explainTerms(RelWriter pw) {
    return pw;
  }

  // 当一个算子准备加入优化器（火山模型/Hep模型）时，确保其所有子节点已被优化器接管，并根据需要对自己进行重构（Reconstruct）和摘要刷新。
  @Override public RelNode onRegister(RelOptPlanner planner) {
    // 获取当前节点在未注册前的所有子节点（oldInputs），并创建一个大小相同的空列表 inputs 用来存放注册后的子节点。
    List<RelNode> oldInputs = getInputs();
    List<RelNode> inputs = new ArrayList<>(oldInputs.size());
    for (final RelNode input : oldInputs) {
      // 如果子节点 input 之前没有被优化器认识，优化器会注册它；如果已经认识了，优化器会返回一个已经被优化器接管的、等价的特殊节点。
      // 在 VolcanoPlanner（火山模型优化器）中，这个返回的 e 通常是一个 RelSubset 或 RelSet 的代理对象。
      RelNode e = planner.ensureRegistered(input, null); //先注册子节点
      // 如果注册后返回的节点 e 和原节点 input 内存地址不同，那么它们的 rowType（Schema / 字段名和类型）必须完全一致
      assert e == input || RelOptUtil.equal("rowtype of rel before registration",
          input.getRowType(),
          "rowtype of rel after registration",
          e.getRowType(),
          Litmus.THROW);
      inputs.add(e);
    }
    RelNode r = this;
    // 检测子节点是否发生变化
    // 比较两个 List 内部的元素指针是否一一对应相同。
    if (!Util.equalShallow(oldInputs, inputs)) {
      //如果子节点在注册后变成了 RelSubset，这意味着原先直接指向具体算子（如 LogicalTableScan）的引用，现在需要指向优化器的等价集合。
      // 因为 RelNode 是不可变的，我们不能直接去 setInputs(...) 修改当前节点，而是必须调用 copy 方法，传入原有的特征集（getTraitSet()）和新注册的子节点 inputs，克隆出一个全新的当前算子节点 r。
      r = copy(getTraitSet(), inputs);
    }
    r.recomputeDigest();
    // 调用算子的自我校验逻辑，确保重构后的算子树状态依然合法。
    assert r.isValid(Litmus.THROW, null);
    return r;
  }

  @Override public void recomputeDigest() {
    digest.clear();
  }
  // 强行替换指定索引处的子节点。
  // 基类中默认直接抛出不支持异常 UnsupportedOperationException。可变节点或特定优化阶段的对象会重写它。
  @Override public void replaceInput(
      int ordinalInParent,
      RelNode p) {
    throw new UnsupportedOperationException("replaceInput called on " + this);
  }

  /** Description; consists of id plus digest. */
  @Override public String toString() {
    return "rel#" + id + ':' + getDigest();
  }

  @Deprecated // to be removed before 2.0
  @Override public final String getDescription() {
    return this.toString();
  }

  @Override public String getDigest() {
    return digest.toString();
  }

  @Override public final RelDigest getRelDigest() {
    return digest;
  }
  // 获取该算子关联的元数据表（RelOptTable）。
  // 对于一般的计算算子（如 Project/Filter）返回 null；只有类似 TableScan 这种直接读取物理表的算子才会重写并返回具体的表。
  @Override public @Nullable RelOptTable getTable() {
    return null;
  }

  /**
   * {@inheritDoc}
   *
   * <p>This method (and {@link #hashCode} is intentionally final. We do not want
   * sub-classes of {@link RelNode} to redefine identity. Various algorithms
   * (e.g. visitors, planner) can define the identity as meets their needs.
   */
  @Override public final boolean equals(@Nullable Object obj) {
    return super.equals(obj);
  }

  /**
   * {@inheritDoc}
   *
   * <p>This method (and {@link #equals} is intentionally final. We do not want
   * sub-classes of {@link RelNode} to redefine identity. Various algorithms
   * (e.g. visitors, planner) can define the identity as meets their needs.
   */
  @Override public final int hashCode() {
    return super.hashCode();
  }

  /**
   * Equality check for RelNode digest.
   *
   * <p>By default this method collects digest attributes from
   * {@link #explainTerms(RelWriter)}, then compares each attribute pair.
   * This should work well for most cases. If this method is a performance
   * bottleneck for your project, or the default behavior can't handle
   * your scenario properly, you can choose to override this method and
   * {@link #deepHashCode()}. See {@code LogicalJoin} as an example.
   *
   * @return Whether the 2 RelNodes are equivalent or have the same digest.
   * @see #deepHashCode()
   */
  @API(since = "1.25", status = API.Status.MAINTAINED)
  @Override public boolean deepEquals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || this.getClass() != obj.getClass()) {
      return false;
    }
    AbstractRelNode that = (AbstractRelNode) obj;
    boolean result = this.getTraitSet().equals(that.getTraitSet())
        && this.getRowType().equalsSansFieldNames(that.getRowType());
    if (!result) {
      return false;
    }
    PairList<String, @Nullable Object> items1 = this.getDigestItems();
    PairList<String, @Nullable Object> items2 = that.getDigestItems();
    if (items1.size() != items2.size()) {
      return false;
    }
    for (int i = 0; result && i < items1.size(); i++) {
      Map.Entry<String, @Nullable Object> attr1 = items1.get(i);
      Map.Entry<String, @Nullable Object> attr2 = items2.get(i);
      if (attr1.getValue() instanceof RelNode) {
        result = ((RelNode) attr1.getValue()).deepEquals(attr2.getValue());
      } else {
        result = attr1.equals(attr2);
      }
    }
    return result;
  }

  /**
   * Compute hash code for RelNode digest.
   *
   * @see RelNode#deepEquals(Object)
   */
  @API(since = "1.25", status = API.Status.MAINTAINED)
  @Override public int deepHashCode() {
    int result = 31 + getTraitSet().hashCode();
    PairList<String, @Nullable Object> items = this.getDigestItems();
    for (@Nullable Object value : items.rightList()) {
      final int h;
      if (value == null) {
        h = 0;
      } else if (value instanceof RelNode) {
        h = ((RelNode) value).deepHashCode();
      } else {
        h = value.hashCode();
      }
      result = result * 31 + h;
    }
    return result;
  }

  private PairList<String, @Nullable Object> getDigestItems() {
    RelDigestWriter rdw = new RelDigestWriter();
    explainTerms(rdw);
    if (this instanceof Hintable) {
      List<RelHint> hints = ((Hintable) this).getHints();
      rdw.itemIf("hints", hints, !hints.isEmpty());
    }
    return rdw.attrs;
  }

  /** Implementation of {@link RelDigest}. */
  private class InnerRelDigest implements RelDigest {
    /** Cached hash code. */
    private int hash = 0;

    @Override public RelNode getRel() {
      return AbstractRelNode.this;
    }

    @Override public void clear() {
      hash = 0;
    }

    @Override public boolean equals(final @Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      final InnerRelDigest relDigest = (InnerRelDigest) o;
      return deepEquals(relDigest.getRel());
    }

    @Override public int hashCode() {
      if (hash == 0) {
        hash = deepHashCode();
      }
      return hash;
    }

    @Override public String toString() {
      RelDigestWriter rdw = new RelDigestWriter();
      explain(rdw);
      return requireNonNull(rdw.digest, "digest");
    }
  }

  /**
   * A writer object used exclusively for computing the digest of a RelNode.
   *
   * <p>The writer is meant to be used only for computing a single digest and
   * then thrown away.  After calling {@link #done(RelNode)} the writer should
   * be used only to obtain the computed {@link #digest}. Any other action is
   * prohibited.
   */
  // 专用于计算 RelNode 摘要字符串的特殊 RelWriter 实现类。
  // 它不负责往控制台或日志打印，而是把算子的各种属性收集到内部的 attrs 集合中，最后拼装成标准格式的字符串。
  private static final class RelDigestWriter implements RelWriter {
    private final PairList<String, @Nullable Object> attrs = PairList.of();

    @Nullable String digest = null;

    @Override public void explain(final RelNode rel,
        final List<Pair<String, @Nullable Object>> valueList) {
      throw new IllegalStateException("Should not be called for computing digest");
    }

    @Override public SqlExplainLevel getDetailLevel() {
      return SqlExplainLevel.DIGEST_ATTRIBUTES;
    }

    @Override public RelWriter item(String term, @Nullable Object value) {
      if (value != null && value.getClass().isArray()) {
        // We can't call hashCode and equals on Array, so
        // convert it to String to keep the same behaviour.
        value = "" + value;
      }
      attrs.add(term, value);
      return this;
    }

    @Override public RelWriter done(RelNode node) {
      StringBuilder sb = new StringBuilder();
      sb.append(node.getRelTypeName());
      sb.append('.');
      sb.append(node.getTraitSet());
      sb.append('(');
      attrs.forEachIndexed((j, left, right) -> {
        if (j > 0) {
          sb.append(',');
        }
        sb.append(left);
        sb.append('=');
        if (right instanceof RelNode) {
          RelNode input = (RelNode) right;
          sb.append(input.getRelTypeName());
          sb.append('#');
          sb.append(input.getId());
        } else {
          sb.append(right);
        }
      });
      sb.append(')');
      digest = sb.toString();
      return this;
    }
  }
}
