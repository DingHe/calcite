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
import org.apache.calcite.plan.RelDigest;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptNode;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.metadata.Metadata;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.util.Litmus;

import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import java.util.List;
import java.util.Set;

/**
 * A <code>RelNode</code> is a relational expression.
 *
 * <p>Relational expressions process data, so their names are typically verbs:
 * Sort, Join, Project, Filter, Scan, Sample.
 *
 * <p>A relational expression is not a scalar expression; see
 * {@link org.apache.calcite.sql.SqlNode} and {@link RexNode}.
 *
 * <p>If this type of relational expression has some particular planner rules,
 * it should implement the <em>public static</em> method
 * {@link AbstractRelNode#register}.
 *
 * <p>When a relational expression comes to be implemented, the system allocates
 * a {@link org.apache.calcite.plan.RelImplementor} to manage the process. Every
 * implementable relational expression has a {@link RelTraitSet} describing its
 * physical attributes. The RelTraitSet always contains a {@link Convention}
 * describing how the expression passes data to its consuming
 * relational expression, but may contain other traits, including some applied
 * externally. Because traits can be applied externally, implementations of
 * RelNode should never assume the size or contents of their trait set (beyond
 * those traits configured by the RelNode itself).
 *
 * <p>For each calling-convention, there is a corresponding sub-interface of
 * RelNode. For example,
 * {@code org.apache.calcite.adapter.enumerable.EnumerableRel}
 * has operations to manage the conversion to a graph of
 * {@code org.apache.calcite.adapter.enumerable.EnumerableConvention}
 * calling-convention, and it interacts with a
 * {@code EnumerableRelImplementor}.
 * <p>A relational expression is only required to implement its
 * calling-convention's interface when it is actually implemented, that is,
 * converted into a plan/program. This means that relational expressions which
 * cannot be implemented, such as converters, are not required to implement
 * their convention's interface.
 *
 * <p>Every relational expression must derive from {@link AbstractRelNode}. (Why
 * have the <code>RelNode</code> interface, then? We need a root interface,
 * because an interface can only derive from an interface.)
 */
// RelNode 意为 “关系代数表达式（Relational Expression）”。
// 如果说 SqlNode 是 SQL 文本在前端的“语法树（AST）”，那么 RelNode 就是经由 SqlToRelConverter 翻译、脱胎换骨后的“逻辑与物理执行计划树”。
// 关系代数算子的正统化身：它代表一切关系代数的经典转换动作（通常是动词）：Sort（排序）、Join（联结）、Project（投影）、Filter（过滤）、TableScan（表扫描）。它承担了数据的加工、修剪和转化契约。
// 物理与逻辑改写的百变金刚：通过它的子接口和多态体系，它既能代表不绑死任何具体计算引擎的“逻辑算子（LogicalFilter）”，也能通过优化器的规则淬炼（copy 方法），蜕变为绑定具体底层的“物理算子（EnumerableFilter / SparkFilter）”。
// CBO 成本估算的实体标靶：它是计算代价（Cost-Based Optimization）的基本单元。它了解自己的结构特征（如选择率、输入节点），并把这些数据提供给 RelMetadataQuery，从而让优化器能精细计算出每一种执行树形态的物理开销。
public interface RelNode extends RelOptNode, Cloneable {
  //~ Methods ----------------------------------------------------------------

  /**
   * Return the CallingConvention trait from this RelNode's
   * {@link #getTraitSet() trait set}.
   *
   * @return this RelNode's CallingConvention
   */
  // 获取当前节点的调用约定（Calling Convention）特征
  // Convention 是关系表达式数据传递方式的“最高协议”。如果返回 Convention.NONE 或 Logical，代表它是一个纯逻辑节点，无法直接运行；
  // 如果返回 EnumerableConvention.INSTANCE，代表它将转化为内存迭代器代码；
  // 如果是 FlinkConventions.DATASTREAM，则意味着它会演化为分布式流节点。优化器通过它来确保整棵执行计划树的上下游物理协议是对齐的。
  @Pure
  @Nullable Convention getConvention();

  /**
   * Returns the name of the variable which is to be implicitly set at runtime
   * each time a row is returned from the first input of this relational
   * expression; or null if there is no variable.
   *
   * @return Name of correlating variable, or null
   */
  // 返回该关系表达式隐式绑定的关联变量名称（若无则返回 null
  // 当当前节点处于一个关联子查询（Correlated Subquery）的右翼，且需要依赖外层主查询每行吐出的变量数据时，这个方法会返回那个用于动态传递每行数据的变量名（如 $cor0）。
  @Nullable String getCorrelVariable();

  /**
   * Returns the <code>i</code><sup>th</sup> input relational expression.
   * @param i Ordinal of input
   * @return <code>i</code><sup>th</sup> input
   */
  // 高效抓取当前节点的第 i 个直接输入（子算子节点）
  // 索引从 0 开始。例如对于 Join 算子，getInput(0) 获取的是左表（Left Input），getInput(1) 获取的是右表（Right Input）。
  RelNode getInput(int i);

  /**
   * Returns the type of the rows returned by this relational expression.
   */
  // 覆盖父接口，返回当前节点输出行的强类型结构（Row Type）
  // 声明当前算子吐出的数据合同。它严格约束了当前节点产出的行有多少列、各列叫什么名字、以及属于何种 SQL 强类型。
  @Override RelDataType getRowType();

  /**
   * Returns the type of the rows expected for an input. Defaults to
   * {@link #getRowType}.
   * @param ordinalInParent input's 0-based ordinal with respect to this
   *                        parent rel
   * @return expected row type
   */
  // 获取当前算子对它的第 ordinalInParent 个子节点所期望的输入行类型。
  // 默认实现等同于子节点的 getRowType()。但在特定场景下（如隐式类型转换、或强行要求隐式对齐的特殊算子），父算子可以通过这个方法宣称：“虽然你吐给我的数据是这个类型，但我希望你最好是另一个类型”，从而触发类型安全校验。
  RelDataType getExpectedInputRowType(int ordinalInParent);

  /**
   * Returns an array of this relational expression's inputs. If there are no
   * inputs, returns an empty list, not {@code null}.
   * @return Array of this relational expression's inputs
   */
  // 获取当前算子的全量输入节点列表。
  // 覆盖了父接口的返回类型，将其收敛、强转为更具体的 List。如果是叶子算子（如 TableScan），则返回一个绝对不为 null 的空列表。
  @Override List<RelNode> getInputs();

  /**
   * Returns an estimate of the number of rows this relational expression will
   * return.
   *
   * <p>NOTE jvs 29-Mar-2006: Don't call this method directly. Instead, use
   * {@link RelMetadataQuery#getRowCount}, which gives plugins a chance to
   * override the rel's default ideas about row count.
   * @param mq Metadata query
   * @return Estimate of the number of rows this relational expression will
   *   return
   */
  // 根据当前节点的过滤或转换系数，估算出该节点即将吐出的结果行数（Row Count）。
  // 研发人员绝不允许在外部直接调用该方法！ 必须通过 mq.getRowCount(rel) 间接盘问。
  // 因为直接调用只会触发算子内部死板的默认公式，而通过 RelMetadataQuery 能够调用到各种外部高级代价插件、动态采样、历史血缘统计等算法来覆盖（Override）默认行数。
  double estimateRowCount(RelMetadataQuery mq);

  /**
   * Returns the variables that are set in this relational
   * expression but also used and therefore not available to parents of this
   * relational expression.
   *
   * @return Names of variables which are set in this relational
   *   expression
   */
  // 返回当前关系表达式就地产生（Set/Produce）并向下游透传的关联变量 ID 集合。
  // 多发生在外层的 Filter 或 Join 算子中。它告诉系统：“我在此处拦截并捕获了当前行，并创建了这些 CorrelationId 分发给我的子嵌套节点使用。这些变量在我的上层（Parent）是不可见、不可复用的。”
  Set<CorrelationId> getVariablesSet();

  /**
   * Collects variables known to be used by this expression or its
   * descendants. By default, no such information is available and must be
   * derived by analyzing sub-expressions, but some optimizer implementations
   * may insert special expressions which remember such information.
   *
   * @param variableSet receives variables used
   */
  // 深度收集当前节点以及它所有子孙后代节点共同消费（Used）的关联变量集合。
  // 副作用收集方法。优化器在做“子查询拉平（Subquery Unnesting / Decorrelation）”时，需要精准调用此方法摸清底细：到底底层哪一个暗藏的 Filter 偷偷引用了外层的变量，
  // 从而决定如何将其重组为 Correlate 物理算子。
  void collectVariablesUsed(Set<CorrelationId> variableSet);

  /**
   * Collects variables set by this expression.
   * TODO: is this required?
   *
   * @param variableSet receives variables known to be set by
   */
  // 深度收集当前节点所定义/产生的关联变量集合，将它们填入传入的 variableSet 容器中。
  void collectVariablesSet(Set<CorrelationId> variableSet);

  /**
   * Interacts with the {@link RelVisitor} in a
   * {@link org.apache.calcite.util.Glossary#VISITOR_PATTERN visitor pattern} to
   * traverse the tree of relational expressions.
   *
   * @param visitor Visitor that will traverse the tree of relational
   *                expressions
   */
  // 经典的访问者模式（Visitor Pattern）实现，让传入的 RelVisitor 遍历当前节点的所有直接子节点（Children）。
  void childrenAccept(RelVisitor visitor);

  /**
   * Returns the cost of this plan (not including children). The base
   * implementation throws an error; derived classes should override.
   *
   * <p>NOTE jvs 29-Mar-2006: Don't call this method directly. Instead, use
   * {@link RelMetadataQuery#getNonCumulativeCost}, which gives plugins a
   * chance to override the rel's default ideas about cost.
   * @param planner Planner for cost calculation
   * @param mq Metadata query
   * @return Cost of this plan (not including children)
   */
  // 计算当前节点自身的、不包含任何子节点在内的物理开销（Self Cost）。
  // 它只评估自己这个动作消耗的 CPU、IO 内存（例如 HashJoin 算子自身构建哈希表所需的额外内存与 CPU 比对代价）。
  // 同样的，严禁外部直接调用，必须由优化器通过 mq.getNonCumulativeCost(rel) 来路由计算，
  // 最终 VolcanoPlanner 会自底向上累加这些 SelfCost，得出整棵树的全局总累加成本（Cumulative Cost），以此挑选最优执行计划。
  @Nullable RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq);

  /**
   * Returns a metadata interface.
   *
   * @deprecated Use {@link RelMetadataQuery} via {@link #getCluster()}.
   *
   * @param <M> Type of metadata being requested
   * @param metadataClass Metadata interface
   * @param mq Metadata query
   *
   * @return Metadata object that supplies the desired metadata (never null,
   *     although if the information is not present the metadata object may
   *     return null from all methods)
   */
  // 获取绑定的特定元数据接口实例。
  // （已废弃）。2.0 之前删除，统一要求通过 getCluster().getMetadataQuery() 进行高阶演进查询。
  @Deprecated // to be removed before 2.0
  <@Nullable M extends @Nullable Metadata> M metadata(Class<M> metadataClass, RelMetadataQuery mq);

  /**
   * Describes the inputs and attributes of this relational expression.
   * Each node should call {@code super.explain}, then call the
   * {@link org.apache.calcite.rel.externalize.RelWriterImpl#input(String, RelNode)}
   * and
   * {@link RelWriter#item(String, Object)}
   * methods for each input and attribute.
   *
   * @param pw Plan writer
   */
  // 向计划打印器（RelWriter）描述、倾倒当前算子的特有属性与属性键值对。
  // 当你在命令行执行 EXPLAIN PLAN FOR ... 时，每个算子都会被调用此方法。子类实现时必须先调用 super.explain(pw) 打印父类通用属性，然后再灌入自己的独特词条（例如 Filter 会灌入 condition=[...]，Project 会灌入 exprs=[...]）。
  void explain(RelWriter pw);

  /**
   * Returns a relational expression string of this {@code RelNode}.
   * The string returned is the same as
   * {@link RelOptUtil#toString(org.apache.calcite.rel.RelNode)}.
   *
   * <p>This method is intended mainly for use while debugging in an IDE,
   * as a convenient shorthand for {@link RelOptUtil#toString}.
   * We recommend that classes implementing this interface
   * do not override this method.
   *
   * @return Relational expression string of this {@code RelNode}
   */
  // 快捷将当前算子及其子树转化为标准可读的文本执行计划字串。
  default String explain() {
    return RelOptUtil.toString(this);
  }

  /**
   * Receives notification that this expression is about to be registered. The
   * implementation of this method must at least register all child
   * expressions.
   * @param planner Planner that plans this relational node
   * @return Relational expression that should be used by the planner
   */
  // 当当前算子即将被正式登记、注册进优化器的等价图矩阵（RelSet / VolcanoPlanner）时，触发的异步通知回调方法。
  // 底层实现必须首先确保其所有的 getInputs()（子节点）都已经先行完成了注册。它允许算子在最后一刻对自身进行微调，并返回最终真正要塞入优化器大网的那个 RelNode。
  RelNode onRegister(RelOptPlanner planner);

  /**
   * Returns a digest string of this {@code RelNode}.
   *
   * <p>Each call creates a new digest string,
   * so don't forget to cache the result if necessary.
   *
   * @return Digest string of this {@code RelNode}
   *
   * @see #getRelDigest()
   */
  // 覆盖父接口，直接调用 getRelDigest().toString() 获取当前节点的唯一摘要字符串（去掉 id 后的语义特征串）。
  @Override default String getDigest() {
    return getRelDigest().toString();
  }

  /**
   * Returns a digest of this {@code RelNode}.
   *
   * <p>INTERNAL USE ONLY. For use by the planner.
   *
   * @return Digest of this {@code RelNode}
   * @see #getDigest()
   */
  // 获取当前节点的 RelDigest 对象（比单纯的 String 更加结构化）
  @API(since = "1.24", status = API.Status.INTERNAL)
  RelDigest getRelDigest();

  /**
   * Recomputes the digest.
   *
   * <p>INTERNAL USE ONLY. For use by the planner.
   *
   * @see #getDigest()
   */
  @API(since = "1.24", status = API.Status.INTERNAL)
  void recomputeDigest();

  /**
   * Deep equality check for RelNode digest.
   *
   * <p>By default this method collects digest attributes from
   * explain terms, then compares each attribute pair.
   *
   * @return Whether the 2 RelNodes are equivalent or have the same digest.
   * @see #deepHashCode()
   */
  @EnsuresNonNullIf(expression = "#1", result = true)
  boolean deepEquals(@Nullable Object obj);

  /**
   * Compute deep hash code for RelNode digest.
   *
   * @see #deepEquals(Object)
   */
  int deepHashCode();

  /**
   * Replaces the <code>ordinalInParent</code><sup>th</sup> input. You must
   * override this method if you override {@link #getInputs}.
   * @param ordinalInParent Position of the child input, 0 is the first
   * @param p New node that should be put at position {@code ordinalInParent}
   */
  // 关系树形变原位替换法。
  // 强行将当前节点的第 ordinalInParent 个子节点，直接拔掉并替换为一个崭新的算子节点 p。
  void replaceInput(
      int ordinalInParent,
      RelNode p);

  /**
   * If this relational expression represents an access to a table, returns
   * that table, otherwise returns null.
   * @return If this relational expression represents an access to a table,
   *   returns that table, otherwise returns null
   */
  // 如果当前算子代表对底层物理物理表的直接访问，则返回对应的元数据表对象（RelOptTable）；如果是中间计算过程（如一个 Project），则返回 null。
  // 优化器的某些元数据规则（如物化视图改写 MaterializedViewSubstitutionRule）需要顺着树一直往下摸，通过这个方法快速拿到源头表，来判定两棵树是否源自相同的物理数据源。
  @Nullable RelOptTable getTable();

  /**
   * Returns the name of this relational expression's class, sans package
   * name, for use in explain. For example, for a <code>
   * org.apache.calcite.rel.ArrayRel.ArrayReader</code>, this method returns
   * "ArrayReader".
   * @return Name of this relational expression's class, sans package name,
   *   for use in explain
   */
  // 返回当前算子的去包名类名简称，专门用于计划打印。例如 org.apache.calcite.rel.logical.LogicalFilter 调用此方法后会优雅地返回 "LogicalFilter"。
  String getRelTypeName();

  /**
   * Returns whether this relational expression is valid.
   *
   * <p>If assertions are enabled, this method is typically called with <code>
   * litmus</code> = <code>THROW</code>, as follows:
   *
   * <blockquote>
   * <pre>assert rel.isValid(Litmus.THROW)</pre>
   * </blockquote>
   *
   * <p>This signals that the method can throw an {@link AssertionError} if it
   * is not valid.
   *
   * @param litmus What to do if invalid
   * @param context Context for validity checking
   * @return Whether relational expression is valid
   * @throws AssertionError if this relational expression is invalid and
   *                        litmus is THROW
   */
  // 算子合法性自我诊断契约方法。
  // 例如，一个 Project 算子会自我核对：“我所引用的子字段索引，在我的 Input 输入类型里到底存不存在？有没有越界？”如果校验失败且 litmus 是 THROW，会直接抛出 AssertionError 保护现场。
  boolean isValid(Litmus litmus, @Nullable Context context);

  /**
   * Creates a copy of this relational expression, perhaps changing traits and
   * inputs.
   *
   * <p>Sub-classes with other important attributes are encouraged to create
   * variants of this method with more parameters.
   *
   * @param traitSet Trait set
   * @param inputs   Inputs
   * @return Copy of this relational expression, substituting traits and
   * inputs
   */
  // 整个 Calcite CBO 乃至所有优化规则的核心生命线——多态克隆演化方法。
  // 是实现自定义算子时最需要精心书写的方法！ 在 CBO 演化中，算子本身是不可变的（Immutable）。
  // 当一条规则想要对算子树改写（例如改变它的物理特征集 traitSet、或者把原先的子节点替换为新优化的 inputs），它绝不能原位修改，而必须调用 oldRel.copy(newTraits, newInputs)。
  // 该方法会在底层调用构造函数，以极高的效率克隆孵化出一个基因突变后的全新 RelNode 交付给优化器。
  RelNode copy(
      RelTraitSet traitSet,
      List<RelNode> inputs);

  /**
   * Registers any special rules specific to this kind of relational
   * expression.
   * 注册该类相关的规则
   * <p>The planner calls this method this first time that it sees a
   * relational expression of this class. The derived class should call
   * {@link org.apache.calcite.plan.RelOptPlanner#addRule} for each rule, and
   * then call {@code super.register}.
   *
   * @param planner Planner to be used to register additional relational
   *                expressions
   */
  // 注册该算子类目所独有的、专属于它的自定义优化规则（Planner Rules）。
  void register(RelOptPlanner planner);

  /**
   * Indicates whether it is an enforcer operator, e.g. PhysicalSort,
   * PhysicalHashDistribute, etc. As an enforcer, the operator must be
   * created only when required traitSet is not satisfied by its input.
   * @return Whether it is an enforcer operator
   */
  // 标记当前算子是否是一个“强制器（Enforcer）操作符”（默认返回 false）。
  // Volcano / Cascades 优化模型的高级精髓。像 PhysicalSort（物理排序）或 Exchange（分布式数据分发）就是典型的 Enforcer。
  // 当父节点要求“我需要输入数据必须按 ID 有序”，但底层的子节点无法自发满足这个特征（Trait）时，优化器就会探查全天下所有的 Enforcer，发现 PhysicalSort 匹配，从而自动将一尊 PhysicalSort “强制器”强行插入两者之间来达成特征契约。
  default boolean isEnforcer() {
    return false;
  }

  /**
   * Accepts a visit from a shuttle.
   * @param shuttle Shuttle
   * @return A copy of this node incorporating changes made by the shuttle to
   * this node's children
   */
  // 接受一个关系表达式梭子（RelShuttle）的访问。
  // 用于深层遍历或成批改写整棵算子树的拓扑结构（例如：把树上所有的 LogicalFilter 批量换成某种自定义过滤算子）。
  // Shuttle 在访问完子节点后，如果发现子节点变了，会调用上面的 copy 方法返回一棵崭新的复制树。
  RelNode accept(RelShuttle shuttle);

  /**
   * Accepts a visit from a shuttle. If the shuttle updates expression, then
   * a copy of the relation should be created. This new relation might have
   * a different row-type.
   *
   * @param shuttle Shuttle
   * @return A copy of this node incorporating changes made by the shuttle to
   * this node's children
   */
  // 接受一个行级表达式梭子（RexShuttle）的访问。
  // 用于成批改写当前算子内部深埋的行级过滤/投影条件（例如：扫描当前算子内部所有的 RexNode，把里面所有引用物理字段 $1 的指针全部偏置加 1，或者把所有的 NOW() 函数原地替换为当前时间戳常量）。
  RelNode accept(RexShuttle shuttle);

  /** Returns whether a field is nullable. */
  // 快捷判定当前算子吐出的第 i 个字段，在业务语义上是否允许为 null（Nullable）。
  default boolean fieldIsNullable(int i) {
    return getRowType().getFieldList().get(i).getType().isNullable();
  }

  /** Returns this node without any wrapper added by the planner. */
  // 剥离当前节点身上包裹的所有优化器外壳（Wrapper）。默认返回自身。在高级的 Volcano 计划检索中，用于穿透特定的等价代理层拿到最纯净的物理算子本质。
  default RelNode stripped() {
    return this;
  }

  /** Context of a relational expression, for purposes of checking validity. */
  // 在调用上面的 isValid(litmus, context) 进行合法性审查时，该接口负责向算子递交当前整棵计划树上已经合法挂载的所有 CorrelationId（关联变量）大名单。
  // 算子据此可以判定自己身上内部引用的那些关联变量是不是“野指针”或“越界盗用”，是 Calcite 编译期边界安全诊断的重要微型组件。
  interface Context {
    Set<CorrelationId> correlationIds();
  }
}
