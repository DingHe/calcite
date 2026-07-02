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
package org.apache.calcite.prepare;

import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.Meta;
import org.apache.calcite.jdbc.CalcitePrepare;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.CalciteSchema.LatticeEntry;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptLattice;
import org.apache.calcite.plan.RelOptMaterialization;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexExecutorImpl;
import org.apache.calcite.runtime.Bindable;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.runtime.Typed;
import org.apache.calcite.schema.ColumnStrategy;
import org.apache.calcite.schema.ExtensibleTable;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.Wrapper;
import org.apache.calcite.schema.impl.ModifiableViewTable;
import org.apache.calcite.schema.impl.StarTable;
import org.apache.calcite.sql.SqlExplain;
import org.apache.calcite.sql.SqlExplainFormat;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorCatalogReader;
import org.apache.calcite.sql.validate.SqlValidatorTable;
import org.apache.calcite.sql2rel.InitializerContext;
import org.apache.calcite.sql2rel.InitializerExpressionFactory;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.tools.Program;
import org.apache.calcite.tools.Programs;
import org.apache.calcite.util.Holder;
import org.apache.calcite.util.TryThreadLocal;
import org.apache.calcite.util.trace.CalciteTimingTracer;
import org.apache.calcite.util.trace.CalciteTrace;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.apache.calcite.linq4j.Nullness.castNonNull;
import static org.apache.calcite.sql2rel.SqlToRelConverter.DEFAULT_IN_SUB_QUERY_THRESHOLD;

import static java.util.Objects.requireNonNull;

/**
 * Abstract base for classes that implement
 * the process of preparing and executing SQL expressions.
 */
// 所有查询“准备（Preparing）”与物理落地执行类的核心抽象基类。
// 定义了将一个解析后的 SQL 语法树（SqlNode）逐步编译、重写、剪枝、转换为关系代数逻辑计划（RelNode），并在火山模型优化器（CBO）中进行最佳代价评估，最终转换为可执行结果集（PreparedResult）的标准骨架算法大纲（利用了设计模式中的模板方法模式）。
// 统一 SQL 准备（Prepare）流水线骨架：它承接了从语法校验后，到转换为逻辑计划、再到类型扁平化（Flattening）、去相关性（Decorrelating）、无效列剪枝（Field Trimming）和最终 CBO 实物优化的标准顺序。
// 抽象元数据统一视图（CatalogReader）：定义了贯穿整个 Calcite 解析、校验、优化三大阶段的统一元数据访问规范，消除各编译阶段对元数据格式理解不一致的问题。
// 物化视图（Materialization）与星型表（Lattice）重写重构的调度中枢：在优化阶段前，负责将注册的物化视图结构和多维网格算子注入到优化器中，从而让 CBO 能够在成本核算时自动发生查询重写。
// 对 Explain 计划解释的策略路由：集中接管 EXPLAIN PLAN FOR ... 语句，根据用户的 Depth 级别（类型、逻辑、物理），在特定的生命周期位置截断流水线并格式化 dump 输出。
// CalcitePrepare 是对外的“门面与执行接口”，而 Prepare 是内部的“流水线骨架与策略基类”。
// CalcitePrepare 对外契约 (Interface)。定义了 Calcite 引擎如何与 Avatica / JDBC 连接层进行交互的顶级标准。 面向调用者。主要面向 JDBC 驱动（如 CalciteConnection）和 Avatica 远程组件。
// Prepare 内部骨架 (Abstract Class)。实现了将 SQL 转换为可执行代码的核心编译流水线算法。 面向实现者。主要面向内核开发者，用来派生出特定后端（如 Java Enumerable、Bindable 或自定义执行引擎）的准备类。
// 在整个 SQL 编译执行的生命周期中，它们两个是上下游的协作关系。CalcitePrepare 处于最外层，它收到请求后，内部会委派具体的 Prepare 实现类去干脏活累活。
public abstract class Prepare {
  protected static final Logger LOGGER = CalciteTrace.getStatementTracer();
  // 包含当前执行引擎和连接环境的只读上下文胶囊（如当前的类型工厂 JavaTypeFactory、时区、配置参数等）。
  protected final CalcitePrepare.Context context;
  // 当前编译器持有的全能元数据访问目录读取器（融合了校验器、优化器和函数查找能力）。
  protected final CatalogReader catalogReader;
  /**
   * Convention via which results should be returned by execution.
   */
  // 终极目标物理公约（如 EnumerableConvention 或 BindableConvention）。用来指示优化器必须将算子树最终转换为这一种指定的物理执行模型。
  protected final Convention resultConvention;
  // 编译期耗时追踪监控器，用于精确度量 sql2rel、optimization 等各核心阶段的物理耗时。
  protected @Nullable CalciteTimingTracer timingTracer;
  // 字段血缘追踪追踪结果缓存。
  // 对应输出行中的每个字段，记录其最终来源于底层哪个 (Database, Schema, Table, Column) 四元组。
  protected @MonotonicNonNull List<@Nullable List<String>> fieldOrigins;
  // 参数化记录行类型。用来存放当前 SQL 中所有 ? 占位符所代表的动态参数对应的强类型定义信息。
  protected @MonotonicNonNull RelDataType parameterRowType;

  // temporary. for testing.
  // 线程级本地测试开发后门勾子。强制控制在编译期是否触发针对无用列进行裁剪（trimUnusedFields）的算法。
  public static final TryThreadLocal<Boolean> THREAD_TRIM =
      TryThreadLocal.of(false);

  /** Temporary, until
   * <a href="https://issues.apache.org/jira/browse/CALCITE-1045">[CALCITE-1045]
   * Decorrelate sub-queries in Project and Join</a> is fixed.
   *
   * <p>The default is false, meaning do not expand queries during sql-to-rel,
   * but a few tests override and set it to true. After CALCITE-1045
   * is fixed, remove those overrides and use false everywhere. */
  // 线程级本地过渡期勾子（CALCITE-1045 专用）。
  // 控制是否在 sql-to-rel 阶段直接展开子查询（Sub-query expand）。
  public static final TryThreadLocal<Boolean> THREAD_EXPAND =
      TryThreadLocal.of(false);

  // temporary. for testing.
  // 线程级子查询重写阈值勾子。控制当 IN (...) 子查询中的元素超过多少个时，系统自动将普通列表项重写改写为物理上的底层 HashJoin。
  public static final TryThreadLocal<@Nullable Integer> THREAD_INSUBQUERY_THRESHOLD =
      TryThreadLocal.of(DEFAULT_IN_SUB_QUERY_THRESHOLD);

  protected Prepare(CalcitePrepare.Context context, CatalogReader catalogReader,
      Convention resultConvention) {
    this.context = requireNonNull(context, "context");
    this.catalogReader = catalogReader;
    this.resultConvention = resultConvention;
  }
  // 抽象策略勾子。
  // 由子类负责实现。
  // 当检测到当前准备的语句是 EXPLAIN 时，该方法负责将其结果（包括类型、参数、代数树）格式化包装为专用的 PreparedExplain 实体。
  protected abstract PreparedResult createPreparedExplanation(
      @Nullable RelDataType resultType,
      RelDataType parameterRowType,
      @Nullable RelRoot root,
      SqlExplainFormat format,
      SqlExplainLevel detailLevel);

  /**
   * Optimizes a query plan.
   *
   * @param root Root of relational expression tree
   * @param materializations Tables known to be populated with a given query
   * @param lattices Lattices
   * @return an equivalent optimized relational expression
   */
  // 实现基于代价的优化（CBO, Cost-Based Optimization）的底层核心调度器。
  // 它的主要职责是：初始化优化器、注入物化视图和星型网格元数据、设定目标物理属性，最后驱动优化大纲（Program）将逻辑代数树转换并打磨为最优的物理代数树。
  // RelRoot root 待优化的关系代数树的根节点包装对象。不仅包含核心的算子树本身（root.rel），还记录了当前 SQL 要求的输出列名映射、全局物理排序规则（Collation）以及当前算子的代数大类（SqlKind）。它是优化的起点。
  // final List<Materialization> materializations 当前连接中注册的、且判定为合法的物化视图（Materialization）列表。如果优化器发现当前查询中的部分子树与某个物化视图的定义查询完全匹配或可推导，它会自动将原表扫描替换为对物化视图物理表的扫描，从而大幅加速查询。
  // final List<CalciteSchema.LatticeEntry> lattices 当前 Schema 中配置的多维网格（Lattice，即星型/雪花型模型）实体列表。用于 OLAP 多维分析场景。它向优化器提供维表与事实表之间的预聚合、联接图谱关系，使得优化器能够自动执行高阶的聚合重写。
  protected RelRoot optimize(RelRoot root,
      final List<Materialization> materializations,
      final List<CalciteSchema.LatticeEntry> lattices) {

    final RelOptPlanner planner = root.rel.getCluster().getPlanner();
    // 绑定运行时表达式执行器
    // 赋予优化器在调优时计算常量（Constant Folding）的能力。当优化器推演规则时，如果遇到类似 WHERE age > 10 + 8 的表达式，优化器可以利用这个执行器提前将其算成 WHERE age > 18
    final DataContext dataContext = context.getDataContext();
    planner.setExecutor(new RexExecutorImpl(dataContext));

    final List<RelOptMaterialization> materializationList =
        new ArrayList<>(materializations.size());
    // 循环是一个中间数据适配包装的过程。
    // 因为入参 Materialization 是准备（Prepare）阶段的抽象描述，而优化器只能读懂优化层专用的 RelOptMaterialization。
    for (Materialization materialization : materializations) {
      List<String> qualifiedTableName = materialization.materializedTable.path();
      materializationList.add(
          new RelOptMaterialization(
              castNonNull(materialization.tableRel),
              castNonNull(materialization.queryRel),
              materialization.starRelOptTable,
              qualifiedTableName));
    }
    // 转化并注入多维星型网格元数据
    // 同样是将 OLAP 层的星型模型适配升级为优化器专用的对象
    final List<RelOptLattice> latticeList = new ArrayList<>(lattices.size());
    for (CalciteSchema.LatticeEntry lattice : lattices) {
      final CalciteSchema.TableEntry starTable = lattice.getStarTable();
      final JavaTypeFactory typeFactory = context.getTypeFactory();
      final RelOptTableImpl starRelOptTable =
          RelOptTableImpl.create(catalogReader,
              starTable.getTable().getRowType(typeFactory), starTable, null);
      latticeList.add(
          new RelOptLattice(lattice.getLattice(), starRelOptTable));
    }
    // 计算整个关系树在优化结束后最终必须强制对齐展现的物理状态集（desiredTraits）
    // 包含了最终交付给后端的物理协议（如 EnumerableConvention 物理公约）以及原始 SQL 要求的全局排序（Collation）特征。
    // 它像是一个“终点标杆”，限制了优化器无论怎么改变中间算子的形态，顶层输出必须满足这些物理要求（若不满足，优化器会自动在最顶层插入 Sort 或 Exchange 算子来强行纠正）
    final RelTraitSet desiredTraits = getDesiredRootTraitSet(root);
    // 这是整段代码里开销最大、最核心的部分。 * getProgram() 获取了当前系统配置的优化流程大纲（通常包含一组组的优化规则集，如剪枝规则、Join 重排规则、物理转换规则）
    final Program program = getProgram();
    // 优化器会把原始逻辑树 root.rel 放入等价集合空间中，倾泻元数据持有的所有规则，并疯狂进行成百上千次的结构演变。
    // 同时结合先前注入的 materializationList（物化视图）和 latticeList（网格），计算出每一种组合的 CPU、I/O 代价，最终脱胎换骨，筛选并吐出一棵综合成本最低的物理代数算子树 rootRel4
    final RelNode rootRel4 =
        program.run(planner, root.rel, desiredTraits, materializationList,
            latticeList);
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Plan after physical tweaks:\n{}",
          RelOptUtil.toString(rootRel4, SqlExplainLevel.ALL_ATTRIBUTES));
    }

    return root.withRel(rootRel4);
  }
  // 准备执行 SQL 查询（即编译、优化阶段）时，用于获取当前所使用的优化程序流水线（Program）
  // 在通常情况下返回 Calcite 的标准黄金优化流水线，同时提供一个强大的钩子（Hook）机制，允许测试用例或外部组件在运行时动态覆盖（Override）默认的优化行为。
  protected Program getProgram() {
    // Allow a test to override the default program.
    final Holder<@Nullable Program> holder = Holder.empty();
    Hook.PROGRAM.run(holder);
    @Nullable Program holderValue = holder.get();
    if (holderValue != null) {
      return holderValue;
    }
    // 生产线保底，返回标杆级标准流水线 —— 这是绝大多数生产环境和常规运行时的最终归宿。
    return Programs.standard();
  }

  protected RelTraitSet getDesiredRootTraitSet(RelRoot root) {
    // Make sure non-CallingConvention traits, if any, are preserved
    return root.rel.getTraitSet()
        .replace(resultConvention)
        .replace(root.collation)
        .simplify();
  }

  /**
   * Implements a physical query plan.
   *
   * @param root Root of the relational expression tree
   * @return an executable plan
   */
  protected abstract PreparedResult implement(RelRoot root);

  public PreparedResult prepareSql(
      SqlNode sqlQuery,
      Class runtimeContextClass,
      SqlValidator validator,
      boolean needsValidation) {
    return prepareSql(
        sqlQuery,
        sqlQuery,
        runtimeContextClass,
        validator,
        needsValidation);
  }

  public PreparedResult prepareSql(
      SqlNode sqlQuery,
      SqlNode sqlNodeOriginal,
      Class runtimeContextClass,
      SqlValidator validator,
      boolean needsValidation) {
    init(runtimeContextClass);

    final SqlToRelConverter.Config config =
        SqlToRelConverter.config()
            .withTrimUnusedFields(true)
            .withExpand(THREAD_EXPAND.get())
            .withInSubQueryThreshold(castNonNull(THREAD_INSUBQUERY_THRESHOLD.get()))
            .withExplain(sqlQuery.getKind() == SqlKind.EXPLAIN);
    final Holder<SqlToRelConverter.Config> configHolder = Holder.of(config);
    Hook.SQL2REL_CONVERTER_CONFIG_BUILDER.run(configHolder);
    final SqlToRelConverter sqlToRelConverter =
        getSqlToRelConverter(validator, catalogReader, configHolder.get());

    SqlExplain sqlExplain = null;
    if (sqlQuery.getKind() == SqlKind.EXPLAIN) {
      // dig out the underlying SQL statement
      sqlExplain = (SqlExplain) sqlQuery;
      sqlQuery = sqlExplain.getExplicandum();
      sqlToRelConverter.setDynamicParamCountInExplain(
          sqlExplain.getDynamicParamCount());
    }

    RelRoot root =
        sqlToRelConverter.convertQuery(sqlQuery, needsValidation, true);
    Hook.CONVERTED.run(root.rel);

    if (timingTracer != null) {
      timingTracer.traceTime("end sql2rel");
    }

    final RelDataType resultType = validator.getValidatedNodeType(sqlQuery);
    fieldOrigins = validator.getFieldOrigins(sqlQuery);
    assert fieldOrigins.size() == resultType.getFieldCount();

    parameterRowType = validator.getParameterRowType(sqlQuery);

    // Display logical plans before view expansion, plugging in physical
    // storage and decorrelation
    if (sqlExplain != null) {
      SqlExplain.Depth explainDepth = sqlExplain.getDepth();
      SqlExplainFormat format = sqlExplain.getFormat();
      SqlExplainLevel detailLevel = sqlExplain.getDetailLevel();
      switch (explainDepth) {
      case TYPE:
        return createPreparedExplanation(resultType, parameterRowType, null,
            format, detailLevel);
      case LOGICAL:
        return createPreparedExplanation(null, parameterRowType, root, format,
            detailLevel);
      default:
      }
    }

    // Structured type flattening, view expansion, and plugging in physical
    // storage.
    root = root.withRel(flattenTypes(root.rel, true));

    if (this.context.config().forceDecorrelate()) {
      // Sub-query decorrelation.
      root = root.withRel(decorrelate(sqlToRelConverter, sqlQuery, root.rel));
    }

    if (configHolder.get().isTrimUnusedFields()) {
      // Trim unused fields.
      root = trimUnusedFields(root);

      Hook.TRIMMED.run(root.rel);
    }

    // Display physical plan after decorrelation.
    if (sqlExplain != null) {
      switch (sqlExplain.getDepth()) {
      case PHYSICAL:
      default:
        root = optimize(root, getMaterializations(), getLattices());
        return createPreparedExplanation(null, parameterRowType, root,
            sqlExplain.getFormat(), sqlExplain.getDetailLevel());
      }
    }

    root = optimize(root, getMaterializations(), getLattices());

    if (timingTracer != null) {
      timingTracer.traceTime("end optimization");
    }

    // For transformation from DML -> DML, use result of rewrite
    // (e.g. UPDATE -> MERGE).  For anything else (e.g. CALL -> SELECT),
    // use original kind.
    if (!root.kind.belongsTo(SqlKind.DML)) {
      root = root.withKind(sqlNodeOriginal.getKind());
    }
    return implement(root);
  }

  protected TableModify.@Nullable Operation mapTableModOp(
      boolean isDml, SqlKind sqlKind) {
    if (!isDml) {
      return null;
    }
    switch (sqlKind) {
    case INSERT:
      return TableModify.Operation.INSERT;
    case DELETE:
      return TableModify.Operation.DELETE;
    case MERGE:
      return TableModify.Operation.MERGE;
    case UPDATE:
      return TableModify.Operation.UPDATE;
    default:
      return null;
    }
  }

  /**
   * Protected method to allow subclasses to override construction of
   * SqlToRelConverter.
   */
  protected abstract SqlToRelConverter getSqlToRelConverter(
      SqlValidator validator,
      CatalogReader catalogReader,
      SqlToRelConverter.Config config);

  public abstract RelNode flattenTypes(
      RelNode rootRel,
      boolean restructure);

  protected abstract RelNode decorrelate(SqlToRelConverter sqlToRelConverter,
      SqlNode query, RelNode rootRel);

  protected abstract List<Materialization> getMaterializations();

  protected abstract List<LatticeEntry> getLattices();

  /**
   * Walks over a tree of relational expressions, replacing each
   * {@link org.apache.calcite.rel.RelNode} with a 'slimmed down' relational
   * expression that projects
   * only the columns required by its consumer.
   *
   * @param root Root of relational expression tree
   * @return Trimmed relational expression
   */
  protected RelRoot trimUnusedFields(RelRoot root) {
    final SqlToRelConverter.Config config = SqlToRelConverter.config()
        .withTrimUnusedFields(shouldTrim(root.rel))
        .withExpand(THREAD_EXPAND.get())
        .withInSubQueryThreshold(castNonNull(THREAD_INSUBQUERY_THRESHOLD.get()));
    final SqlToRelConverter converter =
        getSqlToRelConverter(getSqlValidator(), catalogReader, config);
    final boolean ordered = !root.collation.getFieldCollations().isEmpty();
    final boolean dml = SqlKind.DML.contains(root.kind);
    return root.withRel(converter.trimUnusedFields(dml || ordered, root.rel));
  }

  private static boolean shouldTrim(RelNode rootRel) {
    // For now, don't trim if there are more than 3 joins. The projects
    // near the leaves created by trim migrate past joins and seem to
    // prevent join-reordering.
    return castNonNull(THREAD_TRIM.get()) || RelOptUtil.countJoins(rootRel) < 2;
  }

  protected abstract void init(Class runtimeContextClass);

  protected abstract SqlValidator getSqlValidator();

  /** Interface by which validator and planner can read table metadata. */
  // CatalogReader 的核心作用是统一元数据访问入口。
  // 在 SQL 处理的不同阶段，系统对元数据的需求各不相同：
  // 校验阶段 (Validation)：需要 SqlValidatorCatalogReader 来检查表名、列名和类型。
  // 优化阶段 (Planning)：需要 RelOptSchema 来获取表的统计信息和优化规则。
  // 函数解析 (Function Resolution)：需要 SqlOperatorTable 来查找 SQL 算子和函数。
  // CatalogReader 通过多重继承，将这三者组合在一起。它充当了一个全能型“管理员”，确保校验器（Validator）和优化器（Planner）看到的元数据是完全一致的。

  public interface CatalogReader
      extends RelOptSchema, SqlValidatorCatalogReader, SqlOperatorTable {
    // 重写自 RelOptSchema。
    // 根据路径名获取优化器专用的表对象。
    // PreparingTable（它是 RelOptTable 的子接口）。
    // 优化阶段的核心方法。它将 SQL 层的表名转化为包含统计信息（Cost）、物理属性（Distribution）和算子转换逻辑的“准备表”对象。
    @Override @Nullable PreparingTable getTableForMember(List<String> names);

    /** Returns a catalog reader the same as this one but with a possibly
     * different schema path. */
    // 创建一个基于当前配置但拥有不同搜索路径的新 CatalogReader 实例。
    // 参数：schemaPath 新的 Schema 搜索路径。
    CatalogReader withSchemaPath(List<String> schemaPath);
    // 重写自 SqlValidatorCatalogReader。
    // 作用：根据路径名获取校验器专用的表对象。
    @Override @Nullable PreparingTable getTable(List<String> names);
    // 线程本地变量，用于存储当前线程绑定的 CatalogReader 实例。
    ThreadLocal<@Nullable CatalogReader> THREAD_LOCAL = new ThreadLocal<>();
  }

  // PreparingTable是为了校验和优化而定义的表
  /** Definition of a table, for the purposes of the validator and planner. */
  public interface PreparingTable
      extends RelOptTable, SqlValidatorTable {
  }

  /** Abstract implementation of {@link PreparingTable} with an implementation
   * for {@link #columnHasDefaultValue}. */
  // 实现了 PreparingTable 接口。这个类的主要目的是为 SQL 准备（Preparing）阶段的表对象提供基础实现。
  // 中间适配层：它是连接 RelOptTable（优化器使用的表）和具体存储元数据（如 Table）的适配器。
  // 公共行为封装：它实现了 PreparingTable 中一些通用的逻辑，比如如何判断列的默认值、如何处理表的扩展（Extension）等，避免子类重复编写代码。
  // 可扩展性支持：它支持动态扩展列（Extended Columns），这在处理类似 HBase 或 Phoenix 等支持动态 Schema 的系统时非常有用。
  public abstract static class AbstractPreparingTable
      implements PreparingTable {
    @SuppressWarnings("deprecation")
    // 判断表中指定的某一列是否具有默认值。
    @Override public boolean columnHasDefaultValue(RelDataType rowType, int ordinal,
        InitializerContext initializerContext) {
      // This method is no longer used
      // 首先尝试将当前对象通过 unwrap(Table.class) 转为底层的 Table 对象。
      final Table table = this.unwrap(Table.class);
      if (table instanceof Wrapper) {
        // 如果 Table 实现了 Wrapper 接口且包含 InitializerExpressionFactory，则调用该工厂的 newColumnDefaultValue 方法。如果返回的类型不是 NULL，则认为有默认值。
        final InitializerExpressionFactory initializerExpressionFactory =
            ((Wrapper) table).unwrap(InitializerExpressionFactory.class); //通过InitializerExpressionFactory接口，创建默认值
        if (initializerExpressionFactory != null) {
          return initializerExpressionFactory
              .newColumnDefaultValue(this, ordinal, initializerContext)
              .getType().getSqlTypeName() != SqlTypeName.NULL;
        }
      }
      // 如果请求的列索引（ordinal）超出了当前行类型的范围，默认返回 true。
      if (ordinal >= rowType.getFieldList().size()) {
        return true;
      }
      // 可空性兜底：如果上述都不满足，则检查该列是否允许为 null。如果不允许为 null（Not Null），则隐式地认为它可能需要某种初始化处理。
      return !rowType.getFieldList().get(ordinal).getType().isNullable();
    }
    // 实现表的动态列扩展。允许在查询运行时为表增加原本元数据中不存在的列。
    @Override public final RelOptTable extend(List<RelDataTypeField> extendedFields) {
      final Table table = unwrap(Table.class);

      // Get the set of extended columns that do not have the same name as a column
      // in the base table.
      final List<RelDataTypeField> baseColumns = getRowType().getFieldList();
      // 将输入的扩展列与原表列对比，剔除名称重复的列。
      final List<RelDataTypeField> dedupedFields =
          RelOptUtil.deduplicateColumns(baseColumns, extendedFields);
      final List<RelDataTypeField> dedupedExtendedFields =
          dedupedFields.subList(baseColumns.size(), dedupedFields.size());

      if (table instanceof ExtensibleTable) {
        // 如果底层表是 ExtensibleTable：调用其 extend 方法生成新的 Table 对象，然后调用抽象方法 extend(Table) 封装回 RelOptTable。
        final Table extendedTable =
                ((ExtensibleTable) table).extend(dedupedExtendedFields);
        return extend(extendedTable);
      } else if (table instanceof ModifiableViewTable) {
        // 如果底层表是 ModifiableViewTable：针对可修改的视图进行扩展处理，利用 TypeFactory 重构类型。
        final ModifiableViewTable modifiableViewTable =
                (ModifiableViewTable) table;
        final ModifiableViewTable extendedView =
            modifiableViewTable.extend(dedupedExtendedFields,
                requireNonNull(
                    getRelOptSchema(),
                    () -> "relOptSchema for table " + getQualifiedName()).getTypeFactory());
        return extend(extendedView);
      }
      throw new RuntimeException("Cannot extend " + table);
    }

    /** Implementation-specific code to instantiate a new {@link RelOptTable}
     * based on a {@link Table} that has been extended. */
    // 抽象钩子方法。
    // 当 extend 流程生成了一个新的、带有扩展列的 Table 对象后，需要将其重新封装成优化器能识别的 RelOptTable。
    // 由于 AbstractPreparingTable 不知道具体的实现类（如 RelOptTableImpl）是如何构造的，因此交给子类去实现具体的实例化过程。
    protected abstract RelOptTable extend(Table extendedTable);
    // 获取表中每一列的列策略（Column Strategy）。
    @Override public List<ColumnStrategy> getColumnStrategies() {
      return RelOptTableImpl.columnStrategies(AbstractPreparingTable.this);
    }
  }

  /**
   * PreparedExplanation is a PreparedResult for an EXPLAIN PLAN statement.
   * It's always good to have an explanation prepared.
   */
  public abstract static class PreparedExplain
      implements PreparedResult {
    private final @Nullable RelDataType rowType;
    private final RelDataType parameterRowType;
    private final @Nullable RelRoot root;
    private final SqlExplainFormat format;
    private final SqlExplainLevel detailLevel;

    protected PreparedExplain(
        @Nullable RelDataType rowType,
        RelDataType parameterRowType,
        @Nullable RelRoot root,
        SqlExplainFormat format,
        SqlExplainLevel detailLevel) {
      this.rowType = rowType;
      this.parameterRowType = parameterRowType;
      this.root = root;
      this.format = format;
      this.detailLevel = detailLevel;
    }

    @Override public String getCode() {
      if (root == null) {
        return rowType == null ? "rowType is null" : RelOptUtil.dumpType(rowType);
      } else {
        return RelOptUtil.dumpPlan("", root.rel, format, detailLevel);
      }
    }

    @Override public RelDataType getParameterRowType() {
      return parameterRowType;
    }

    @Override public boolean isDml() {
      return false;
    }

    @Override public TableModify.@Nullable Operation getTableModOp() {
      return null;
    }

    @Override public List<@Nullable List<String>> getFieldOrigins() {
      return Collections.singletonList(
          Collections.nCopies(4, null));
    }
  }

  /**
   * Result of a call to {@link Prepare#prepareSql}.
   */
  public interface PreparedResult {
    /**
     * Returns the code generated by preparation.
     */
    String getCode();

    /**
     * Returns whether this result is for a DML statement, in which case the
     * result set is one row with one column containing the number of rows
     * affected.
     */
    boolean isDml();

    /**
     * Returns the table modification operation corresponding to this
     * statement if it is a table modification statement; otherwise null.
     */
    TableModify.@Nullable Operation getTableModOp();

    /**
     * Returns a list describing, for each result field, the origin of the
     * field as a 4-element list of (database, schema, table, column).
     */
    List<? extends @Nullable List<String>> getFieldOrigins();

    /**
     * Returns a record type whose fields are the parameters of this statement.
     */
    RelDataType getParameterRowType();

    /**
     * Executes the prepared result.
     *
     * @param cursorFactory How to map values into a cursor
     * @return producer of rows resulting from execution
     */
    Bindable getBindable(Meta.CursorFactory cursorFactory);
  }

  /**
   * Abstract implementation of {@link PreparedResult}.
   */
  public abstract static class PreparedResultImpl
      implements PreparedResult, Typed {
    protected final RelNode rootRel;
    protected final RelDataType parameterRowType;
    protected final RelDataType rowType;
    protected final boolean isDml;
    protected final TableModify.@Nullable Operation tableModOp;
    protected final List<? extends @Nullable List<String>> fieldOrigins;
    protected final List<RelCollation> collations;

    protected PreparedResultImpl(
        RelDataType rowType,
        RelDataType parameterRowType,
        List<? extends @Nullable List<String>> fieldOrigins,
        List<RelCollation> collations,
        RelNode rootRel,
        TableModify.@Nullable Operation tableModOp,
        boolean isDml) {
      this.rowType = requireNonNull(rowType, "rowType");
      this.parameterRowType = requireNonNull(parameterRowType, "parameterRowType");
      this.fieldOrigins = requireNonNull(fieldOrigins, "fieldOrigins");
      this.collations = ImmutableList.copyOf(collations);
      this.rootRel = requireNonNull(rootRel, "rootRel");
      this.tableModOp = tableModOp;
      this.isDml = isDml;
    }

    @Override public boolean isDml() {
      return isDml;
    }

    @Override public TableModify.@Nullable Operation getTableModOp() {
      return tableModOp;
    }

    @Override public List<? extends @Nullable List<String>> getFieldOrigins() {
      return fieldOrigins;
    }

    @Override public RelDataType getParameterRowType() {
      return parameterRowType;
    }

    /**
     * Returns the physical row type of this prepared statement. May not be
     * identical to the row type returned by the validator; for example, the
     * field names may have been made unique.
     */
    public RelDataType getPhysicalRowType() {
      return rowType;
    }

    @Override public abstract Type getElementType();

    public RelNode getRootRel() {
      return rootRel;
    }
  }

  /** Describes that a given SQL query is materialized by a given table.
   * The materialization is currently valid, and can be used in the planning
   * process. */
  // 用于实现物化视图（Materialized View）改写机制的核心描述类。
  // 在基于代价的优化器（CBO）中，如果系统提前将某个复杂的 SQL 查询结果计算并持久化成了一张物理表（即物化视图），当用户提交一个新查询时，Calcite 的优化器会尝试通过元数据匹配，
  // 将新查询或其中的子查询改写为直接读取这张物化表，从而避免昂贵的重复计算。
  // Materialization 类就是用来绑定“原始查询 SQL”与“物化存储表”之间映射关系的纽带。
  // 核心作用是描述并维护一个当前有效的物化视图元数据，供优化器（Planner）在查询规划过程中进行等价改写。
  // 同时持有了两种形态的信息：
  // 文本与逻辑路径形态：原始的查询 SQL 文本以及执行该 SQL 时的 Schema 路径上下文。
  // 关系表达式（RelNode）形态：经过 Calcite 转换后的逻辑执行计划树。优化器正是通过比对“查询的 RelNode 树”与“物化视图的 RelNode 树”的结构等价性，来决定是否能用 tableRel（物理表扫描）去替换 queryRel（原始复杂查询）。
  public static class Materialization {
    /** The table that holds the materialized data. */
    // 指向实际存放物化后数据的物理表（或外部存储表）在 Calcite Schema 中的元数据条目。
    // 这是改写后的“目的地”。一旦优化器决定使用这个物化视图，最终生成的物理执行计划就会去扫描这张表。
    final CalciteSchema.TableEntry materializedTable;
    /** The query that derives the data. */
    // 派生出上述物化数据的原始 SQL 查询语句文本
    // 即物化视图的定义语句（例如 SELECT col1, SUM(col2) FROM t GROUP BY col1）。它代表了物化表里数据的业务含义。
    final String sql;
    /** The schema path for the query. */
    // 执行上述 sql 语句时所处的 Schema 路径上下文（名称列表）。
    // 因为 SQL 内部可能使用了相对表名（如 FROM emps 而非 FROM hr.emps），该路径用于指导 Calcite 重新解析、验证该 SQL 时应该去哪个命名空间下寻找对应的表。
    final List<String> viewSchemaPath;
    /** Relational expression for the table. Usually a
     * {@link org.apache.calcite.rel.logical.LogicalTableScan}. */
    // 代表“读取该物化表”的关系表达式节点，通常是一个 LogicalTableScan。
    // 当改写成功时，优化器会用这个 tableRel 节点去替换掉原查询中等价的复杂子树。它是可空的，通常在优化器介入并开始为该物化视图构建等价关系时被赋值。
    @Nullable RelNode tableRel;
    /** Relational expression for the query to populate the table. */
    // 代表“生成该物化数据的原始查询”的关系表达式（AST/RelNode 树）。
    // 优化器在做改写算法时，无法直接比对字符串形式的 sql，必须将 sql 转换成由 RelNode 组成的逻辑算子树。优化器会拿用户的查询树与这个 queryRel 树进行子树匹配。
    @Nullable RelNode queryRel;
    /** Star table identified. */
    // 标识该物化视图是否与特定的星型模型表（Star Table）相关联。
    // Calcite 支持特殊的 StarTable 优化（主要用于传统 OLAP 多维分析中的星型/雪花模型）。如果该物化视图是基于星型模型构建的物化度量或预聚合，该属性会记录对应的优化器表元数据对象，以便进行更高级的星型改写优化。
    private @Nullable RelOptTable starRelOptTable;

    public Materialization(CalciteSchema.TableEntry materializedTable,
        String sql, List<String> viewSchemaPath) {
      assert materializedTable != null;
      assert sql != null;
      this.materializedTable = materializedTable;
      this.sql = sql;
      this.viewSchemaPath = viewSchemaPath;
    }

    public void materialize(RelNode queryRel,
        RelOptTable starRelOptTable) {
      this.queryRel = queryRel;
      this.starRelOptTable = starRelOptTable;
      assert starRelOptTable.maybeUnwrap(StarTable.class).isPresent();
    }
  }
}
