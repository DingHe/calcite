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
package org.apache.calcite.sql2rel;

import org.apache.calcite.avatica.util.Spaces;
import org.apache.calcite.config.NullCollation;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.linq4j.tree.TableExpressionFactory;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.ViewExpanders;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.prepare.RelOptTableImpl;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelDistributions;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Collect;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinInfo;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.hint.HintStrategyTable;
import org.apache.calcite.rel.hint.Hintable;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalCorrelate;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalIntersect;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalMatch;
import org.apache.calcite.rel.logical.LogicalMinus;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.logical.LogicalTableFunctionScan;
import org.apache.calcite.rel.logical.LogicalTableModify;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.logical.LogicalUnion;
import org.apache.calcite.rel.logical.LogicalValues;
import org.apache.calcite.rel.metadata.RelColumnMapping;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.rel2sql.SqlImplementor;
import org.apache.calcite.rel.stream.Delta;
import org.apache.calcite.rel.stream.LogicalDelta;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexFieldCollation;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLambdaRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexPatternFieldRef;
import org.apache.calcite.rex.RexRangeRef;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexSubQuery;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.rex.RexWindowBound;
import org.apache.calcite.rex.RexWindowBounds;
import org.apache.calcite.rex.RexWindowExclusion;
import org.apache.calcite.runtime.PairList;
import org.apache.calcite.schema.ColumnStrategy;
import org.apache.calcite.schema.ModifiableTable;
import org.apache.calcite.schema.ModifiableView;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.Wrapper;
import org.apache.calcite.sql.JoinConditionType;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlExplainFormat;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLambda;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlMatchRecognize;
import org.apache.calcite.sql.SqlMerge;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlNumericLiteral;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlPivot;
import org.apache.calcite.sql.SqlSampleSpec;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlSelectKeyword;
import org.apache.calcite.sql.SqlSetOperator;
import org.apache.calcite.sql.SqlSnapshot;
import org.apache.calcite.sql.SqlUnnestOperator;
import org.apache.calcite.sql.SqlUnpivot;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.SqlValuesOperator;
import org.apache.calcite.sql.SqlWindow;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.fun.SqlCase;
import org.apache.calcite.sql.fun.SqlInOperator;
import org.apache.calcite.sql.fun.SqlQuantifyOperator;
import org.apache.calcite.sql.fun.SqlRowOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.type.TableFunctionReturnTypeInference;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.sql.util.SqlVisitor;
import org.apache.calcite.sql.validate.AggregatingSelectScope;
import org.apache.calcite.sql.validate.CollectNamespace;
import org.apache.calcite.sql.validate.DelegatingScope;
import org.apache.calcite.sql.validate.ListScope;
import org.apache.calcite.sql.validate.MatchRecognizeScope;
import org.apache.calcite.sql.validate.ParameterScope;
import org.apache.calcite.sql.validate.SelectScope;
import org.apache.calcite.sql.validate.SqlLambdaScope;
import org.apache.calcite.sql.validate.SqlMonotonicity;
import org.apache.calcite.sql.validate.SqlNameMatcher;
import org.apache.calcite.sql.validate.SqlQualified;
import org.apache.calcite.sql.validate.SqlUserDefinedTableFunction;
import org.apache.calcite.sql.validate.SqlUserDefinedTableMacro;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorImpl;
import org.apache.calcite.sql.validate.SqlValidatorNamespace;
import org.apache.calcite.sql.validate.SqlValidatorScope;
import org.apache.calcite.sql.validate.SqlValidatorTable;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.sql.validate.SqlWithItemTableRef;
import org.apache.calcite.sql2rel.SqlToRelConverter.Blackboard;
import org.apache.calcite.sql2rel.SqlToRelConverter.SqlIdentifierFinder;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.NlsString;
import org.apache.calcite.util.NumberUtil;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;
import org.apache.calcite.util.trace.CalciteTrace;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;
import org.slf4j.Logger;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

import static org.apache.calcite.linq4j.Nullness.castNonNull;
import static org.apache.calcite.runtime.FlatLists.append;
import static org.apache.calcite.sql.SqlUtil.containsDefault;
import static org.apache.calcite.sql.SqlUtil.containsIn;
import static org.apache.calcite.sql.SqlUtil.stripAs;
import static org.apache.calcite.util.Static.RESOURCE;

import static java.util.Objects.requireNonNull;

/**
 * Converts a SQL parse tree (consisting of
 * {@link org.apache.calcite.sql.SqlNode} objects) into a relational algebra
 * expression (consisting of {@link org.apache.calcite.rel.RelNode} objects).
 *
 * <p>The public entry points are: {@link #convertQuery},
 * {@link #convertExpression(SqlNode)}.
 */
// 在 Calcite 中，一条 SQL 语句在经过 SqlParser 解析后会生成一棵由抽象语法树构成的 SqlNode 树（代表具体的 SQL 语法结构），再经过 SqlValidator 进行元数据绑定和语义校验。
// SqlToRelConverter 的核心使命就是：将经过校验的、面向语法的 SqlNode 树，彻底翻译转换成面向代数优化的、解耦的 RelNode（关系代数逻辑算子树）。
// 输入：SqlNode（代表：SELECT, JOIN, WHERE 等语法节点）。
// 输出：RelNode / RelRoot（代表：LogicalProject, LogicalJoin, LogicalFilter 等逻辑算子节点）。
// 在转换过程中，它不仅仅做简单的节点映射，还要处理极其复杂的底层逻辑，
// 包括：相关子查询的重写与解关联、字段裁剪瘦身、IN/EXISTS 集合算子向 Semi-Join（半连接）的展平转换、以及物化视图和 Lattice 结构的初步对接。
// 它是将“文本化的 SQL 语法”送入“CBO 动态规划优化器”之前必须通过的工业级翻译发动机。
// 核心主入口：转换查询 convertQuery
@SuppressWarnings("UnstableApiUsage")
@Value.Enclosing
public class SqlToRelConverter {
  //~ Static fields/initializers ---------------------------------------------

  /** Default configuration. */
  public static final Config CONFIG =
      ImmutableSqlToRelConverter.Config.builder()
          .withRelBuilderFactory(RelFactories.LOGICAL_BUILDER)
          .withRelBuilderConfigTransform(c -> c.withPushJoinCondition(true))
          .withHintStrategyTable(HintStrategyTable.EMPTY)
          .build();

  protected static final Logger SQL2REL_LOGGER =
      CalciteTrace.getSqlToRelTracer();

  /** Size of the smallest IN list that will be converted to a semijoin to a
   * static table. */
  public static final int DEFAULT_IN_SUB_QUERY_THRESHOLD = 20;

  @Deprecated // to be removed before 2.0
  public static final int DEFAULT_IN_SUBQUERY_THRESHOLD =
      DEFAULT_IN_SUB_QUERY_THRESHOLD;

  //~ Instance fields --------------------------------------------------------

  public final @Nullable SqlValidator validator;
  protected final RexBuilder rexBuilder;
  protected final Prepare.CatalogReader catalogReader;
  protected final RelOptCluster cluster;
  private SubQueryConverter subQueryConverter;
  protected final Map<RelNode, Integer> leaves = new HashMap<>();
  private final List<@Nullable SqlDynamicParam> dynamicParamSqlNodes = new ArrayList<>();
  private final SqlOperatorTable opTab;
  protected final RelDataTypeFactory typeFactory;
  private final SqlNodeToRexConverter exprConverter;
  private final HintStrategyTable hintStrategies;
  private int explainParamCount;
  public final SqlToRelConverter.Config config;
  private final RelBuilder relBuilder;

  /**
   * Fields used in name resolution for correlated sub-queries.
   */
  private final Map<CorrelationId, DeferredLookup> mapCorrelToDeferred =
      new HashMap<>();

  /**
   * Stack of names of datasets requested by the <code>
   * TABLE(SAMPLE(&lt;datasetName&gt;, &lt;query&gt;))</code> construct.
   */
  // 这个变量专门用于在解析特定的扩展采样语法 TABLE(SAMPLE(<datasetName>, <query>)) 时，
  // 跨方法、跨层级传递“数据集名称（datasetName）”这一上下文状态。
  // FROM TABLE(SAMPLE('my_mock_dataset', SELECT * FROM emp))
  // 外层算子首先识别出这是一个采样结构，并且它能拿到采样名字 'my_mock_dataset'。
  // 但是，真正需要知道这个采样名字的，其实是内层递归里负责解析 FROM emp 的叶子节点转换方法。
  // 或者是某些自定义的 CatalogReader，它们需要根据这个 datasetName 去决定是去读真实的物理表 emp，还是去读一个专门为测试准备的、名为 'my_mock_dataset' 的 Mock 数据集。
  // 由于转换方法是层层递归调用的，如果不想在每个方法（如 convertFrom, convertJoin, convertIdentifier 等）的入参中都冗余地加上一个 String datasetName 参数，
  // 最优雅的架构设计就是引入一个双端队列（Deque）作为全局/实例级的状态栈。
  private final Deque<String> datasetStack = new ArrayDeque<>();

  /** Stack that contains the SqlInsert operator that is currently being
   * processed. It is used used for resolving DEFAULT expressions. */
  private final Deque<SqlCall> callStack = new ArrayDeque<>();

  /**
   * Mapping of non-correlated sub-queries that have been converted to their
   * equivalent constants. Used to avoid re-evaluating the sub-query if it's
   * already been evaluated.
   */
  private final Map<SqlNode, RexNode> mapConvertedNonCorrSubqs =
      new HashMap<>();

  public final RelOptTable.ViewExpander viewExpander;

  //~ Constructors -----------------------------------------------------------
  /**
   * Creates a converter.
   *
   * @param viewExpander    Preparing statement
   * @param validator       Validator
   * @param catalogReader   Schema
   * @param planner         Planner
   * @param rexBuilder      Rex builder
   * @param convertletTable Expression converter
   */
  @Deprecated // to be removed before 2.0
  public SqlToRelConverter(
      RelOptTable.ViewExpander viewExpander,
      SqlValidator validator,
      Prepare.CatalogReader catalogReader,
      RelOptPlanner planner,
      RexBuilder rexBuilder,
      SqlRexConvertletTable convertletTable) {
    this(viewExpander, validator, catalogReader,
        RelOptCluster.create(planner, rexBuilder), convertletTable, SqlToRelConverter.config());
  }

  @Deprecated // to be removed before 2.0
  public SqlToRelConverter(
      RelOptTable.ViewExpander viewExpander,
      SqlValidator validator,
      Prepare.CatalogReader catalogReader,
      RelOptCluster cluster,
      SqlRexConvertletTable convertletTable) {
    this(viewExpander, validator, catalogReader, cluster, convertletTable,
        SqlToRelConverter.config());
  }

  /* Creates a converter. */
  public SqlToRelConverter(
      RelOptTable.ViewExpander viewExpander,
      @Nullable SqlValidator validator,
      Prepare.CatalogReader catalogReader,
      RelOptCluster cluster,
      SqlRexConvertletTable convertletTable,
      Config config) {
    this.viewExpander = viewExpander;
    this.opTab =
        (validator
            == null) ? SqlStdOperatorTable.instance()
            : validator.getOperatorTable();
    this.validator = validator;
    this.catalogReader = catalogReader;
    this.subQueryConverter = new NoOpSubQueryConverter();
    this.rexBuilder = cluster.getRexBuilder();
    this.typeFactory = rexBuilder.getTypeFactory();
    this.exprConverter = new SqlNodeToRexConverterImpl(convertletTable);
    this.explainParamCount = 0;
    this.config = requireNonNull(config, "config");
    this.relBuilder =
            config.getRelBuilderFactory().create(cluster,
             validator != null ? validator.getCatalogReader().unwrap(RelOptSchema.class) : null)
        .transform(config.getRelBuilderConfigTransform());
    this.hintStrategies = config.getHintStrategyTable();

    cluster.setHintStrategies(this.hintStrategies);
    this.cluster = requireNonNull(cluster, "cluster");
  }

  //~ Methods ----------------------------------------------------------------

  private SqlValidator validator() {
    return requireNonNull(validator, "validator");
  }

  private <T extends SqlValidatorNamespace> T getNamespace(SqlNode node) {
    //noinspection unchecked
    return (T) requireNonNull(
        getNamespaceOrNull(node),
        () -> "Namespace is not found for " + node);
  }

  @SuppressWarnings("unchecked")
  private <T extends SqlValidatorNamespace> @Nullable T getNamespaceOrNull(SqlNode node) {
    return (@Nullable T) validator().getNamespace(node);
  }

  /** Returns the RelOptCluster in use. */
  public RelOptCluster getCluster() {
    return cluster;
  }

  /**
   * Returns the row-expression builder.
   */
  public RexBuilder getRexBuilder() {
    return rexBuilder;
  }

  /**
   * Returns the number of dynamic parameters encountered during translation;
   * this must only be called after {@link #convertQuery}.
   *
   * @return number of dynamic parameters
   */
  public int getDynamicParamCount() {
    return dynamicParamSqlNodes.size();
  }

  /**
   * Returns the type inferred for a dynamic parameter.
   *
   * @param index 0-based index of dynamic parameter
   * @return inferred type, never null
   */
  public RelDataType getDynamicParamType(int index) {
    SqlNode sqlNode = dynamicParamSqlNodes.get(index);
    if (sqlNode == null) {
      throw Util.needToImplement("dynamic param type inference");
    }
    return validator().getValidatedNodeType(sqlNode);
  }

  /**
   * Returns the current count of the number of dynamic parameters in an
   * EXPLAIN PLAN statement.
   *
   * @param increment if true, increment the count
   * @return the current count before the optional increment
   */
  public int getDynamicParamCountInExplain(boolean increment) {
    int retVal = explainParamCount;
    if (increment) {
      ++explainParamCount;
    }
    return retVal;
  }

  /** Returns the mapping of non-correlated sub-queries that have been converted
   * to the constants that they evaluate to. */
  public Map<SqlNode, RexNode> getMapConvertedNonCorrSubqs() {
    return mapConvertedNonCorrSubqs;
  }

  /**
   * Adds to the current map of non-correlated converted sub-queries the
   * elements from another map that contains non-correlated sub-queries that
   * have been converted by another SqlToRelConverter.
   *
   * @param alreadyConvertedNonCorrSubqs the other map
   */
  public void addConvertedNonCorrSubqs(
      Map<SqlNode, RexNode> alreadyConvertedNonCorrSubqs) {
    mapConvertedNonCorrSubqs.putAll(alreadyConvertedNonCorrSubqs);
  }

  /**
   * Sets a new SubQueryConverter. To have any effect, this must be called
   * before any convert method.
   *
   * @param converter new SubQueryConverter
   */
  public void setSubQueryConverter(SubQueryConverter converter) {
    subQueryConverter = converter;
  }

  /**
   * Sets the number of dynamic parameters in the current EXPLAIN PLAN
   * statement.
   *
   * @param explainParamCount number of dynamic parameters in the statement
   */
  public void setDynamicParamCountInExplain(int explainParamCount) {
    assert config.isExplain();
    this.explainParamCount = explainParamCount;
  }

  private void checkConvertedType(SqlNode query, RelNode result) {
    if (query.isA(SqlKind.DML)) {
      return;
    }
    // Verify that conversion from SQL to relational algebra did
    // not perturb any type information.  (We can't do this if the
    // SQL statement is something like an INSERT which has no
    // validator type information associated with its result,
    // hence the namespace check above.)
    final List<RelDataTypeField> validatedFields =
        validator().getValidatedNodeType(query).getFieldList();
    final RelDataType validatedRowType =
        validator().getTypeFactory().createStructType(
            Pair.right(validatedFields),
            SqlValidatorUtil.uniquify(Pair.left(validatedFields),
                catalogReader.nameMatcher().isCaseSensitive()));

    final List<RelDataTypeField> convertedFields =
        result.getRowType().getFieldList().subList(0, validatedFields.size());
    final RelDataType convertedRowType =
        validator().getTypeFactory().createStructType(convertedFields);

    if (!RelOptUtil.equal("validated row type", validatedRowType,
        "converted row type", convertedRowType, Litmus.IGNORE)) {
      throw new AssertionError("Conversion to relational algebra failed to "
          + "preserve datatypes:\n"
          + "validated type:\n"
          + validatedRowType.getFullTypeString()
          + "\nconverted type:\n"
          + convertedRowType.getFullTypeString()
          + "\nrel:\n"
          + RelOptUtil.toString(result));
    }
  }

  public RelNode flattenTypes(
      RelNode rootRel,
      boolean restructure) {
    RelStructuredTypeFlattener typeFlattener =
        new RelStructuredTypeFlattener(relBuilder,
            rexBuilder, createToRelContext(ImmutableList.of()), restructure);
    return typeFlattener.rewrite(rootRel);
  }

  /**
   * If sub-query is correlated and decorrelation is enabled, performs
   * decorrelation.
   *
   * @param query   Query
   * @param rootRel Root relational expression
   * @return New root relational expression after decorrelation
   */
  public RelNode decorrelate(SqlNode query, RelNode rootRel) {
    if (!config.isDecorrelationEnabled()) {
      return rootRel;
    }
    final RelNode result = decorrelateQuery(rootRel);
    if (result != rootRel) {
      checkConvertedType(query, result);
    }
    return result;
  }

  /**
   * Walks over a tree of relational expressions, replacing each
   * {@link RelNode} with a 'slimmed down' relational expression that projects
   * only the fields required by its consumer.
   *
   * <p>This may make things easier for the optimizer, by removing crud that
   * would expand the search space, but is difficult for the optimizer itself
   * to do it, because optimizer rules must preserve the number and type of
   * fields. Hence, this transform that operates on the entire tree, similar
   * to the {@link RelStructuredTypeFlattener type-flattening transform}.
   *
   * <p>Currently this functionality is disabled in farrago/luciddb; the
   * default implementation of this method does nothing.
   *
   * @param ordered Whether the relational expression must produce results in
   * a particular order (typically because it has an ORDER BY at top level)
   * @param rootRel Relational expression that is at the root of the tree
   * @return Trimmed relational expression
   */
  public RelNode trimUnusedFields(boolean ordered, RelNode rootRel) {
    // Trim fields that are not used by their consumer.
    if (config.isTrimUnusedFields()) {
      final RelFieldTrimmer trimmer = newFieldTrimmer();
      final List<RelCollation> collations =
          rootRel.getTraitSet().getTraits(RelCollationTraitDef.INSTANCE);
      rootRel = trimmer.trim(rootRel);
      if (!ordered
          && collations != null
          && !collations.isEmpty()
          && !collations.equals(ImmutableList.of(RelCollations.EMPTY))) {
        final RelTraitSet traitSet = rootRel.getTraitSet()
            .replace(RelCollationTraitDef.INSTANCE, collations);
        rootRel = rootRel.copy(traitSet, rootRel.getInputs());
      }
      if (SQL2REL_LOGGER.isDebugEnabled()) {
        SQL2REL_LOGGER.debug(
            RelOptUtil.dumpPlan("Plan after trimming unused fields", rootRel,
                SqlExplainFormat.TEXT, SqlExplainLevel.EXPPLAN_ATTRIBUTES));
      }
    }
    return rootRel;
  }

  /**
   * Creates a RelFieldTrimmer.
   *
   * @return Field trimmer
   */
  protected RelFieldTrimmer newFieldTrimmer() {
    return new RelFieldTrimmer(validator, relBuilder);
  }

  /**
   * Converts an unvalidated query's parse tree into a relational expression.
   *
   * @param query           Query to convert
   * @param needsValidation Whether to validate the query before converting;
   *                        <code>false</code> if the query has already been
   *                        validated.
   * @param top             Whether the query is top-level, say if its result
   *                        will become a JDBC result set; <code>false</code> if
   *                        the query will be part of a view.
   */
  // 主要职责是将一棵 SQL 语法树（SqlNode）转换为一棵关系代数表达式树（RelNode），并最终将其包装RelRoot 返回。
  public RelRoot convertQuery(
      // 输入的 SQL 解析树（Parse Tree / AST）。它可以是未经验证的原始语法树，也可以是已经通过 SqlValidator 验证过的语法树。
      SqlNode query,
      // 是否需要对输入的 query 进行语义验证（Validate）
      // 如果传入 true，方法内部会先调用验证器检查表名、列名、类型等是否正确；如果传入 false，则表明调用方已经在外部完成了验证工作。
      final boolean needsValidation,
      // 当前查询是否为最顶层（Top-level）查询
      // 用于判断该查询的结果是否会直接作为最终的输出（例如 JDBC 的结果集）。如果是视图（View）内部的子查询或嵌入式查询，则该值为 false。某些流处理算子（如 Delta）只会在 top=true 时触发。
      final boolean top) {
    // 如果 needsValidation 为真，则调用 Calcite 的 SqlValidator 对原始语法树进行语义检查（如权限、元数据匹配、隐式类型转换等）。
    if (needsValidation) {
      query = validator().validate(query);
    }
    // 调用内部的递归方法 convertQueryRecursive，将 SqlNode（如 SqlSelect, SqlJoin）逐层翻译为对应的 RelNode（如 LogicalProject, LogicalJoin）
    // .rel 表示提取出转换后生成的 RelNode 根节点，赋值给 result。
    RelNode result = convertQueryRecursive(query, top, null).rel;
    // 如果是最外层查询（top == true），且该查询是一个流式查询（例如使用了 STREAM 关键字或操作流数据）。
    // 会在当前的 result 节点之上包裹一层 LogicalDelta 算子。Delta 算子在 Calcite 中用于捕获关系表达式随时间变化的数据增量（Delta），是流处理的核心算子。
    if (top) {
      if (isStream(query)) {
        result = new LogicalDelta(cluster, result.getTraitSet(), result);
      }
    }
    RelCollation collation = RelCollations.EMPTY;
    // 首先判断，如果不是 DML 语句（即不是 INSERT/UPDATE/DELETE 等不需要排序的语句）。
    if (!query.isA(SqlKind.DML)) {
      // 接着判断 isOrdered(query)，即 SQL 语句中是否包含 ORDER BY 子句。
      if (isOrdered(query)) {
        // 如果包含排序，则通过 requiredCollation(result) 从当前的 result 关系表达式中提取出具体的排序字段和方向，将其赋值给 collation，用于后续放入 RelRoot。
        collation = requiredCollation(result);
      }
    }
    // 安全检查。确保转换出来的 RelNode 树的行类型（Row Type）与前面通过验证器得到的 SqlNode 的行类型在语义上是兼容和一致的
    checkConvertedType(query, result);

    if (SQL2REL_LOGGER.isDebugEnabled()) {
      SQL2REL_LOGGER.debug(
          RelOptUtil.dumpPlan("Plan after converting SqlNode to RelNode",
              result, SqlExplainFormat.TEXT,
              SqlExplainLevel.EXPPLAN_ATTRIBUTES));
    }
    // 从验证器中直接获取该 SQL 节点经校验后的标准输出行类型
    final RelDataType validatedRowType = validator().getValidatedNodeType(query);
    List<RelHint> hints = new ArrayList<>();
    // 如果当前查询是 SELECT 语句，并且用户在 SQL 中写了 Hint（如 /*+ BROADCAST(t1) */），则通过 SqlUtil.getRelHint 将这些 SQL 层的 Hint 转换为关系代数层的 RelHint 对象列表。
    if (query.getKind() == SqlKind.SELECT) {
      final SqlSelect select = (SqlSelect) query;
      if (select.hasHints()) {
        hints = SqlUtil.getRelHint(hintStrategies, select.getHints());
      }
    }
    // 如果用户在配置（config）中开启了 JSON 类型操作符的启用开关。
    // 利用访问者模式（Visitor），让 result 树接受 NestedJsonFunctionRelRewriter 的访问，对树中涉及嵌套 JSON 处理的函数进行特定的转换或包装。
    if (config.isAddJsonTypeOperatorEnabled()) {
      result = result.accept(new NestedJsonFunctionRelRewriter());
    }

    // propagate the hints.
    // 将刚才收集到的 Hint 顺着 RelNode 树向下传播给合适的子节点，确保底层算子在优化时能感知到这些提示。
    result = RelOptUtil.propagateRelHints(result, false);
    // 使用标准静态工厂方法将 result（关系代数根）、validatedRowType（验证类型）以及 query.getKind()（SQL类型）组装成一个 RelRoot 对象，
    // 并通过 Wither 模式将前面计算出的 collation（排序）和 hints（提示）绑定上去，最终返回给调用者。
    return RelRoot.of(result, validatedRowType, query.getKind())
        .withCollation(collation)
        .withHints(hints);
  }

  private static boolean isStream(SqlNode query) {
    return query instanceof SqlSelect
        && ((SqlSelect) query).isKeywordPresent(SqlSelectKeyword.STREAM);
  }

  public static boolean isOrdered(SqlNode query) {
    switch (query.getKind()) {
    case SELECT:
      SqlNodeList orderList = ((SqlSelect) query).getOrderList();
      return orderList != null
          && orderList.size() > 0;
    case WITH:
      return isOrdered(((SqlWith) query).body);
    case ORDER_BY:
      return ((SqlOrderBy) query).orderList.size() > 0;
    default:
      return false;
    }
  }

  private static RelCollation requiredCollation(RelNode r) {
    if (r instanceof Sort) {
      return ((Sort) r).collation;
    }
    if (r instanceof Project) {
      return requiredCollation(((Project) r).getInput());
    }
    if (r instanceof Delta) {
      return requiredCollation(((Delta) r).getInput());
    }
    throw new AssertionError();
  }

  /**
   * Converts a SELECT statement's parse tree into a relational expression.
   */
  // 将一个标准 SELECT 查询语法树转换为关系代数表达式（RelNode）的核心入口函数
  public RelNode convertSelect(
      // 代表一个已经通过语义验证的 SELECT 语法树节点。
      // 包含了 SQL SELECT 语句的所有核心组件，如 selectList（查询列）、from（数据源）、where（过滤条件）、groupBy（分组）、having（分组后过滤）等。
      SqlSelect select,
      // 标记当前 SELECT 语句是否为整个查询的最外层（顶级）查询。
      boolean top) {
    // 从验证器（Validator）中获取当前 SELECT 语句在解析 WHERE 子句时所使用的作用域（Scope）。
    final SqlValidatorScope selectScope = validator().getWhereScope(select);
    // 构建一个名为 Blackboard（小黑板） 的内部上下文对象。
    // Blackboard 是 SqlToRelConverter 中一个极其重要的内部类。它就像一块临时的黑板，用来记录和追踪转换过程中的中间状态。
    final Blackboard bb = createBlackboard(selectScope, null, top);
    // 调用真正负责执行转换的内部私有方法 convertSelectImpl
    convertSelectImpl(bb, select);
    return castNonNull(bb.root);
  }

  /**
   * Factory method for creating translation workspace.
   */
  protected Blackboard createBlackboard(SqlValidatorScope scope,
      @Nullable Map<String, RexNode> nameToNodeMap, boolean top) {
    return new Blackboard(scope, nameToNodeMap, top);
  }

  /**
   * Implementation of {@link #convertSelect(SqlSelect, boolean)};
   * derived class may override.
   */
  // convertSelectImpl 是 SqlToRelConverter 中最核心、最经典的方法之一。
  // 严格按照标准 SQL 的逻辑执行顺序（Logical Query Processing Order），自底向上、层层嵌套地将一棵 SqlSelect 语法树节点，像搭积木一样组装成由 RelNode 构成的关系代数逻辑算子树。
  protected void convertSelectImpl(
      // 贯穿整个转换生命周期的环境状态容器。它内部维护了当前 Scope 作用域（如当前 Select 块能看到哪些表、哪些列）、当前的代数树根节点（bb.root）、标量表达式到别名的映射等。
      // 所有的子转换方法都会不断修改或读取这个 bb 里面的状态。
      final Blackboard bb,
      // 目标语法树节点。代表当前正在被翻译的、经过语义校验后的标准 SELECT 语法块对象。它包含了 FROM、WHERE、GROUP BY、HAVING、SELECT LIST、DISTINCT、ORDER BY、LIMIT、HINTS 等全部组件。
      SqlSelect select) {
    // 第 1 步：解析 FROM 子句。
    // SQL 执行的第一步。它去翻译 FROM 后面的表、视图、多表连接（JOIN）或是子查询。执行完毕后，底层会生成最初的扫描算子（如 LogicalTableScan 或 LogicalJoin），并将其暂存为当前黑板的根节点（更新 bb.root）。
    convertFrom(
        bb,
        select.getFrom());

    // We would like to remove ORDER BY clause from an expanded view, except if
    // it is top-level or affects semantics.
    //
    // Top-level example. Given the view definition
    //   CREATE VIEW v AS SELECT * FROM t ORDER BY x
    // we would retain the view's ORDER BY in
    //   SELECT * FROM v
    // or
    //   SELECT * FROM v WHERE y = 5
    // but remove the view's ORDER BY in
    //   SELECT * FROM v ORDER BY z
    // and
    //   SELECT deptno, COUNT(*) FROM v GROUP BY deptno
    // because the ORDER BY and GROUP BY mean that the view is not 'top level' in
    // the query.
    //
    // Semantics example. Given the view definition
    //   CREATE VIEW v2 AS SELECT * FROM t ORDER BY x LIMIT 10
    // we would never remove the ORDER BY, because "ORDER BY ... LIMIT" is about
    // semantics. It is not a 'pure order'.
    // 第 2 步：冗余排序裁剪优化（针对视图或子查询）。
    // 如果当前 FROM 展开的是一个视图或子查询，且这个子查询里带有了 ORDER BY（即 bb.root 此时是一个 Sort 算子，且是 isPureOrder 纯排序，没有带 LIMIT）。
    if (RelOptUtil.isPureOrder(castNonNull(bb.root))
        && config.isRemoveSortInSubQuery()) {
      // Remove the Sort if the view is at the top level. Also remove the Sort
      // if there are other nodes, which will cause the view to be in the
      // sub-query.
      // 如果当前查询不是最外层查询（!bb.top），
      // 或者上层有聚合、DISTINCT、或者上层自己又指定了 ORDER BY、FETCH/OFFSET。因为在这些情况下，内层子查询的纯排序对最终结果的顺序毫无贡献，纯属浪费性能。
      if (!bb.top
          || validator().isAggregate(select)
          || select.isDistinct()
          || select.hasOrderBy()
          || select.getFetch() != null
          || select.getOffset() != null) {
        // 调用 bb.setRoot(..., true) 将根节点替换为 bb.root.getInput(0)，直接把内层的 Sort 算子从算子树里剥离、丢弃。
        bb.setRoot(castNonNull(bb.root).getInput(0), true);
      }
    }
    // 第 3 步：解析 WHERE 子句。
    // 把 WHERE 条件表达式转换为关系代数表达式 RexNode。然后，在当前 bb.root 的算子树最外层，包裹上一层 LogicalFilter 算子，并将过滤条件挂载上去。
    convertWhere(
        bb,
        select.getWhere());
    // 第 4 步：预收集 ORDER BY 表达式。
    // 为什么在处理 SELECT 投影之前要先收集 ORDER BY？因为在 SQL 标准中，ORDER BY 允许引用 SELECT 列表中没有显式输出的列。
    final List<SqlNode> orderExprList = new ArrayList<>();
    final List<RelFieldCollation> collationList = new ArrayList<>();
    // gatherOrderExprs 会扫描 ORDER BY 列表，把这些排序列记录到 orderExprList（语法节点列表）中，并将它们的升降序、Null 排序特征转化为物理排序格（RelFieldCollation）并存入 collationList。
    gatherOrderExprs(
        bb,
        select,
        select.getOrderList(),
        orderExprList,
        collationList);
    // 作用：第 5 步：生成标准物理排序特征（Collation Trait）。
    final RelCollation collation =
        cluster.traitSet().canonize(RelCollations.of(collationList));
    // 第 6 步：核心投影/聚合分支路由。
    if (validator().isAggregate(select)) {
      // 如果是聚合查询：调用 convertAgg。该方法内部会处理 GROUP BY 字段，并将普通的过滤节点重构转换为 LogicalAggregate 算子，同时把 HAVING 子句转换为紧随其后的 LogicalFilter
      convertAgg(
          bb,
          select,
          orderExprList);
    } else {
      // 如果不是聚合查询：调用 convertSelectList。将 SELECT 处的列和表达式（以及刚才搜集到的 orderExprList 里的隐藏排序列）转化为 RexNode，并在算子树顶层套上一个 LogicalProject 算子。
      convertSelectList(
          bb,
          select,
          orderExprList);
    }
    // 解析 QUALIFY 子句。
    // QUALIFY 是针对窗口函数（Window Functions）结果进行过滤的高级语法（类似于 WHERE 针对普通列，HAVING 针对聚合列）。该方法会在这里对窗口过滤进行捕获和算子转换。
    convertQualify(bb, select.getQualify());
    // 作用：第 8 步：去重处理（SELECT DISTINCT）。
    // 如果用户指定了 DISTINCT，则调用 distinctify 方法。该方法会在当前的算子树最外层，强行追加一个去重专用的 LogicalAggregate 算子（以所有 SELECT 列作为 Group Key 进行分组），实现全局去重语义。
    if (select.isDistinct()) {
      distinctify(bb, true);
    }
    // 第 9 步：解析排序与分页（ORDER BY / LIMIT / OFFSET）。
    // 将前面第 5 步生成的 collation（排序规则），以及 LIMIT（在 Calcite 中称为 Fetch）、OFFSET 表达式组装起来。在整棵算子树的最外层套上一个 LogicalSort 算子。到这一步为止，整棵标准树的核心拓扑就完全建好了。
    convertOrder(
        select, bb, collation, orderExprList, select.getOffset(),
        select.getFetch());
    // 第 10 步：挂载 SQL Hint（提示词）并最终确立根节点。
    // 如果带有 Hint（如 /*+ BROADCAST(t1) */）：首先通过工具类将语法层的 Hint 解析为关系代数层的 RelHint 列表。接着，
    // 利用一个匿名内部类 RelShuttleImpl（基于访问者模式的算子树遍历器）自顶向下扫描整棵树。当找到第一个支持接收 Hint 的算子（实现 Hintable 接口的算子，通常就是最外层的 Project 或 Sort）时，
    // 将 attached 设为 true，并调用 attachHints(hints) 把提示词缝合进该算子中。随后将全新的树封为最终根节点。
    if (select.hasHints()) {
      final List<RelHint> hints = SqlUtil.getRelHint(hintStrategies, select.getHints());
      // Attach the hints to the first Hintable node we found from the root node.
      bb.setRoot(bb.root()
          .accept(
              new RelShuttleImpl() {
                boolean attached = false;
                @Override public RelNode visitChild(RelNode parent, int i, RelNode child) {
                  if (parent instanceof Hintable && !attached) {
                    attached = true;
                    return ((Hintable) parent).attachHints(hints);
                  } else {
                    return super.visitChild(parent, i, child);
                  }
                }
              }), true);
    } else {
      bb.setRoot(bb.root(), true);
    }
  }

  /**
   * Having translated 'SELECT ... FROM ... [GROUP BY ...] [HAVING ...]', adds
   * a relational expression to make the results unique.
   *
   * <p>If the SELECT clause contains duplicate expressions, adds
   * {@link org.apache.calcite.rel.logical.LogicalProject}s so that we are
   * grouping on the minimal set of keys. The performance gain isn't huge, but
   * it is difficult to detect these duplicate expressions later.
   *
   * @param bb               Blackboard
   * @param checkForDupExprs Check for duplicate expressions
   */
  private void distinctify(
      Blackboard bb,
      boolean checkForDupExprs) {
    // Look for duplicate expressions in the project.
    // Say we have 'select x, y, x, z'.
    // Then dups will be {[2, 0]}
    // and oldToNew will be {[0, 0], [1, 1], [2, 0], [3, 2]}
    RelNode rel = bb.root;
    if (checkForDupExprs && (rel instanceof LogicalProject)) {
      LogicalProject project = (LogicalProject) rel;
      final List<RexNode> projectExprs = project.getProjects();
      final List<Integer> origins = new ArrayList<>();
      int dupCount = 0;
      for (int i = 0; i < projectExprs.size(); i++) {
        int x = projectExprs.indexOf(projectExprs.get(i));
        if (x >= 0 && x < i) {
          origins.add(x);
          ++dupCount;
        } else {
          origins.add(i);
        }
      }
      if (dupCount == 0) {
        distinctify(bb, false);
        return;
      }

      final Map<Integer, Integer> squished = new HashMap<>();
      final List<RelDataTypeField> fields = rel.getRowType().getFieldList();
      final PairList<RexNode, String> newProjects = PairList.of();
      for (int i = 0; i < fields.size(); i++) {
        if (origins.get(i) == i) {
          squished.put(i, newProjects.size());
          RexInputRef.add2(newProjects, i, fields);
        }
      }
      rel =
          LogicalProject.create(rel, ImmutableList.of(),
              newProjects.leftList(), newProjects.rightList(),
              project.getVariablesSet());
      bb.root = rel;
      distinctify(bb, false);
      rel = bb.root();

      // Create the expressions to reverse the mapping.
      // Project($0, $1, $0, $2).
      final PairList<RexNode, String> undoProjects = PairList.of();
      for (int i = 0; i < fields.size(); i++) {
        final int origin = origins.get(i);
        RelDataTypeField field = fields.get(i);
        undoProjects.add(
            new RexInputRef(castNonNull(squished.get(origin)),
                field.getType()),
            field.getName());
      }

      rel =
          LogicalProject.create(rel, ImmutableList.of(),
              undoProjects.leftList(), undoProjects.rightList(),
              ImmutableSet.of());
      bb.setRoot(
          rel,
          false);

      return;
    }

    assert rel != null : "rel must not be null, root = " + bb.root;
    // Usual case: all expressions in the SELECT clause are different.
    final ImmutableBitSet groupSet =
        ImmutableBitSet.range(rel.getRowType().getFieldCount());
    rel =
        createAggregate(bb, groupSet, ImmutableList.of(groupSet),
            ImmutableList.of());

    bb.setRoot(
        rel,
        false);
  }

  /**
   * Converts a query's ORDER BY clause, if any.
   *
   * <p>Ignores the ORDER BY clause if the query is not top-level and FETCH or
   * OFFSET are not present.
   *
   * @param select        Query
   * @param bb            Blackboard
   * @param collation     Collation list
   * @param orderExprList Method populates this list with orderBy expressions
   *                      not present in selectList
   * @param offset        Expression for number of rows to discard before
   *                      returning first row
   * @param fetch         Expression for number of rows to fetch
   */
  protected void convertOrder(
      SqlSelect select,
      Blackboard bb,
      RelCollation collation,
      List<SqlNode> orderExprList,
      @Nullable SqlNode offset,
      @Nullable SqlNode fetch) {
    if (removeSortInSubQuery(bb.top)
        || select.getOrderList() == null
        || select.getOrderList().isEmpty()) {
      assert removeSortInSubQuery(bb.top) || collation.getFieldCollations().isEmpty();
      if ((offset == null
            || (offset instanceof SqlLiteral
                && Objects.equals(((SqlLiteral) offset).bigDecimalValue(), BigDecimal.ZERO)))
          && fetch == null) {
        return;
      }
    }

    // Create a sorter using the previously constructed collations.
    bb.setRoot(
        LogicalSort.create(bb.root(), collation,
            offset == null ? null : convertExpression(offset),
            fetch == null ? null : convertExpression(fetch)),
        false);

    // If extra expressions were added to the project list for sorting,
    // add another project to remove them. But make the collation empty, because
    // we can't represent the real collation.
    //
    // If it is the top node, use the real collation, but don't trim fields.
    if (orderExprList.size() > 0 && !bb.top) {
      final List<RexNode> exprs = new ArrayList<>();
      final RelDataType rowType = bb.root().getRowType();
      final int fieldCount =
          rowType.getFieldCount() - orderExprList.size();
      for (int i = 0; i < fieldCount; i++) {
        exprs.add(rexBuilder.makeInputRef(bb.root(), i));
      }
      bb.setRoot(
          LogicalProject.create(bb.root(),
              ImmutableList.of(),
              exprs,
              rowType.getFieldNames().subList(0, fieldCount),
              ImmutableSet.of()),
          false);
    }
  }

  /**
   * Returns whether we should remove the sort for the subsequent query conversion.
   *
   * @param top Whether the rel to convert is the root of the query
   */
  private boolean removeSortInSubQuery(boolean top) {
    return config.isRemoveSortInSubQuery() && !top;
  }

  /**
   * Push down all the NOT logical operators into any IN/NOT IN operators.
   *
   * @param scope Scope where {@code sqlNode} occurs
   * @param sqlNode the root node from which to look for NOT operators
   * @return the transformed SqlNode representation with NOT pushed down.
   */
  // 核心职责是：在语法树阶段（SqlNode）应用德·摩根定律（De Morgan's laws）等逻辑等价变形，将 NOT 逻辑运算符尽可能地“下推”到 IN 或 NOT IN 运算符内部。
  // 为什么需要这个方法？因为形式如 NOT (x IN (1, 2)) 的语法，在后续转换为关系代数（如子查询变连接）时极其别扭且难以优化。
  // 若能提前转换为 x NOT IN (1, 2)，或者运用德·摩根定律消除大范围的 NOT，会让生成的 RelNode 结构更加清晰，更易被优化器识别。
  // SqlValidatorScope scope 义校验器的作用域。当在方法中动态创建出新的 SqlNode 表达式节点时，必须将其重新注册到该 scope 中，以保证系统能够正确识别这些新节点的输出类型和语义特征。
  // SqlNode sqlNode 输入的语法树节点。代表当前正在被检查或下推处理的 SQL 表达式根节点。
  private static SqlNode pushDownNotForIn(SqlValidatorScope scope,
      SqlNode sqlNode) {
    // 如果当前节点不是一个运算符调用（SqlCall，如普通的常数、列标识符），
    // 或者通过辅助方法 containsIn(sqlNode) 检查发现其子树中根本不包含任何 IN 或 NOT IN 语法，说明该分支不需要做任何优化，直接原样返回
    if (!(sqlNode instanceof SqlCall) || !containsIn(sqlNode)) {
      return sqlNode;
    }
    final SqlCall sqlCall = (SqlCall) sqlNode;
    switch (sqlCall.getKind()) {
    // 对 AND/OR 的各子项进行深度优先遍历递归。
    case AND:
    case OR:
      final List<SqlNode> operands = new ArrayList<>();
      for (SqlNode operand : sqlCall.getOperandList()) {
        operands.add(pushDownNotForIn(scope, operand));
      }
      final SqlCall newCall =
          sqlCall.getOperator().createCall(sqlCall.getParserPosition(),
              operands);
      // 通过 reg(scope, ...) 注册到校验作用域中返回。
      return reg(scope, newCall);
    // 第二层分发：核心，处理顶层的 NOT
    // 意味着当前遇到了类似 NOT (xxx) 的结构。代码取出 NOT 内部紧包裹的操作数 call，并针对 call 的类型（call.getKind()）展开嵌套的 switch-case 变形：
    case NOT:
      assert sqlCall.operand(0) instanceof SqlCall;
      final SqlCall call = sqlCall.operand(0);
      switch (sqlCall.operand(0).getKind()) {
      // 分支 A：NOT (CASE ... END) 变形
      case CASE:
        // 将 NOT 塞进 CASE WHEN 的结果分支（THEN）中
        // 等价变形原理：NOT (CASE WHEN a THEN b ELSE c END) 相当于 CASE WHEN a THEN NOT(b) ELSE NOT(c) END。
        // 遍历原本 CASE 的每一个 THEN 分支，动态创建一个 NOT(thenOperand) 节点，并将其递归送入 pushDownNotForIn 继续深度下推。
        final SqlCase caseNode = (SqlCase) call;
        final SqlNodeList thenOperands = new SqlNodeList(SqlParserPos.ZERO);

        for (SqlNode thenOperand : caseNode.getThenOperands()) {
          final SqlCall not =
              SqlStdOperatorTable.NOT.createCall(SqlParserPos.ZERO,
                  thenOperand);
          thenOperands.add(pushDownNotForIn(scope, reg(scope, not)));
        }
        // 下推 NOT 到 ELSE 分支
        SqlNode elseOperand =
            requireNonNull(caseNode.getElseOperand(),
                "getElseOperand for " + caseNode);
        if (!SqlUtil.isNull(elseOperand)) {
          // "not(unknown)" is "unknown", so no need to simplify
          final SqlCall not =
              SqlStdOperatorTable.NOT.createCall(SqlParserPos.ZERO,
                  elseOperand);
          elseOperand = pushDownNotForIn(scope, reg(scope, not));
        }
        // 重新将改造后的条件、THEN 列表、ELSE 项缝合成一个全新的 CASE 表达式节点并返回。
        return reg(scope,
            SqlStdOperatorTable.CASE.createCall(SqlParserPos.ZERO,
                caseNode.getValueOperand(),
                caseNode.getWhenOperands(),
                thenOperands,
                elseOperand));
      // 分支 B & C：德·摩根定律转换（NOT(AND) 与 NOT(OR)）
      case AND:
        // 应用德·摩根定律：NOT (A AND B) -> (NOT A) OR (NOT B)。
        // 面对 NOT(AND)，代码将原本 AND 里的每一个操作数都套上一个 NOT 运算符，然后再把这群新子项丢给 pushDownNotForIn 递归向下推。最终将这群节点用一个大 OR 运算符焊接起来。
        final List<SqlNode> orOperands = new ArrayList<>();
        for (SqlNode operand : call.getOperandList()) {
          orOperands.add(
              pushDownNotForIn(scope,
                  reg(scope,
                      SqlStdOperatorTable.NOT.createCall(SqlParserPos.ZERO,
                          operand))));
        }
        return reg(scope,
            SqlStdOperatorTable.OR.createCall(SqlParserPos.ZERO,
                orOperands));

      case OR:
        // 同上理，面对 NOT(OR)，将内部各节点套上 NOT 递归下推，最后用 AND 运算符将其连接。
        final List<SqlNode> andOperands = new ArrayList<>();
        for (SqlNode operand : call.getOperandList()) {
          andOperands.add(
              pushDownNotForIn(scope,
                  reg(scope,
                      SqlStdOperatorTable.NOT.createCall(SqlParserPos.ZERO,
                          operand))));
        }
        return reg(scope,
            SqlStdOperatorTable.AND.createCall(SqlParserPos.ZERO,
                andOperands));
      // 分支 D：负负得正（双重否定消除）
      case NOT:
        assert call.operandCount() == 1;
        return pushDownNotForIn(scope, call.operand(0));
      // 分支 E & F：最终目的地（下推至 IN / NOT IN）
      case NOT_IN:
        // 将 NOT (x NOT IN (...)) 直接转换为 x IN (...)。
        return reg(scope,
           SqlStdOperatorTable.IN.createCall(SqlParserPos.ZERO,
               call.getOperandList()));

      case IN:
        // 将 NOT (x IN (...)) 直接转换为 x NOT IN (...)。
        return reg(scope,
            SqlStdOperatorTable.NOT_IN.createCall(SqlParserPos.ZERO,
                call.getOperandList()));
      default:
        break;
      }
      break;
    default:
      break;
    }
    return sqlNode;
  }

  /** Registers with the validator a {@link SqlNode} that has been created
   * during the Sql-to-Rel process. */
  private static SqlNode reg(SqlValidatorScope scope, SqlNode e) {
    scope.getValidator().deriveType(scope, e);
    return e;
  }

  /**
   * Converts a WHERE clause.
   *
   * @param bb    Blackboard
   * @param where WHERE clause, may be null
   */
  // 解析、改写 SQL 中的 WHERE 子句（SqlNode），将其转换为行表达式（RexNode），并在小黑板（Blackboard）当前的根节点上方叠加一层过滤算子（LogicalFilter）。
  private void convertWhere(
      // 小黑板对象。
      // 维护了当前查询块转换的上下文状态（例如当前的 root 算子、当前所处的名空间作用域 scope 等），是整个转换过程的工作台。
      final Blackboard bb,
      // 代表 SQL WHERE 子句的抽象语法树节点。允许为 null（即 SQL 中没有 WHERE 条件）。
      final @Nullable SqlNode where) {
    // 边界情况：无 WHERE 条件
    if (where == null) {
      return;
    }
    // 下推 NOT 操作符以优化 IN 子查询。
    // 例如，当 SQL 中出现类似 NOT (x IN (SELECT ...)) 这样的表达式时，该方法会尝试将 NOT 操作符下推，将其改写或打标记为 NOT_IN 节点。
    SqlNode newWhere = pushDownNotForIn(bb.scope, where);
    // 拉平并替换 WHERE 条件中嵌套的子查询（Sub-queries）。
    // 如果 WHERE 条件里包含 IN、EXISTS 或标量子查询（例如 WHERE age > (SELECT avg(age) FROM ...)），此方法会率先介入。
    // 它会递归地将这些子查询转换成关系算子（如 Join），并注册（register）到小黑板的 root 上。
    replaceSubQueries(bb, newWhere, RelOptUtil.Logic.UNKNOWN_AS_FALSE);
    // 调用小黑板的表达式转换总入口（convertExpression），正式将 AST 树节点 SqlNode 翻译转换为行表达式 RexNode
    final RexNode convertedWhere = bb.convertExpression(newWhere);
    // 剥离无意义的类型强转（CAST）
    // 在校验阶段，为了保证表达式两侧类型完全一致，Calcite 有时会隐式补充诸如 CAST(expr AS Nullable(BOOLEAN)) 的节点。
    // 这一步通过 removeNullabilityCast 把包装在最外层、仅仅用来改变“可空性（Nullability）”的 CAST 剥除，露出纯粹的过滤条件表达式（如直接拿到里面的 RexCall），避免生成冗余的代码。
    final RexNode convertedWhere2 =
        RexUtil.removeNullabilityCast(typeFactory, convertedWhere);

    // only allocate filter if the condition is not TRUE
    // 过滤条件恒真（Always True）优化
    if (convertedWhere2.isAlwaysTrue()) {
      return;
    }
    // 利用 Calcite 默认的过滤算子工厂 DEFAULT_FILTER_FACTORY，创建一个逻辑过滤算子 filter（通常是 LogicalFilter）。
    final RelFactories.FilterFactory filterFactory =
        RelFactories.DEFAULT_FILTER_FACTORY;

    final RelNode filter =
        filterFactory.createFilter(bb.root(), convertedWhere2, ImmutableSet.of());
    // 处理关联子查询引发的关联变量（Correlation Variable）收集与重构。
    // getCorrelationUse(bb, filter) 会深入扫描刚刚生成的 filter 算子，
    // 检查其中是否引用了外层查询块传进来的关联变量（例如相关子查询中引用了父查询表的列：WHERE t2.id = t1.id 中的 t1.id）。
    // 如果发现了关联变量的使用（p != null），说明当前 Filter 依赖某些外界传入的变量。
    // 代码会断言确认该节点是一个 Filter，并调用 LogicalFilter.create 方法重新创建一个带有相关性变量 ID 签名（ImmutableSet.of(p.id)）的新 LogicalFilter 算子 r。
    // 这样可以确保后续的优化器（如 RelDecorrelator）能够精确识别并解开这个关联关系。
    // 如果没有关联变量（p == null），则保持原来的 filter 算子不变。
    final RelNode r;
    final CorrelationUse p = getCorrelationUse(bb, filter);
    if (p != null) {
      assert p.r instanceof Filter;
      Filter f = (Filter) p.r;
      r =
          LogicalFilter.create(f.getInput(), f.getCondition(),
              ImmutableSet.of(p.id));
    } else {
      r = filter;
    }
    // 调用我们之前解读过的 setRoot 最底层方法，把新鲜出炉、已经绑定好过滤条件的关系算子 r 强制塞回小黑板作为最新的根节点 root。
    bb.setRoot(r, false);
  }

  private void replaceSubQueries(
      final Blackboard bb,
      final SqlNode expr,
      RelOptUtil.Logic logic) {
    replaceSubQueries(bb, expr, logic, null);
  }
  // 先找出当前 SQL 表达式中所有的子查询，然后将这些子查询替换（转换）为等价的关系代数（RelNode）结构。
  private void replaceSubQueries(
      final Blackboard bb,
      final SqlNode expr,
      RelOptUtil.Logic logic,
      final SqlImplementor.@Nullable Clause clause) {
    findSubQueries(bb, expr, logic, false, clause);
    for (SubQuery node : bb.subQueryList) {
      substituteSubQuery(bb, node);
    }
  }
  // 实现子查询去关联化（Decorrelation）和关系代数拉升（Pull-up/Expansion）的核心方法。
  // 根据子查询的不同类型（如 IN, EXISTS, SCALAR_QUERY 等），将黑板（Blackboard）中收集到的 SqlNode 语法节点转换为等价的关系代数节点（RelNode），
  // 通常将它们转换为与主查询的 Join（如 Semi-Join, Left-Join）或 Correlate 结构，并将生成的关系表达式引用赋值给 subQuery.expr。
  private void substituteSubQuery(Blackboard bb,
      // 一个封装了待转换子查询的数据对象。
      // subQuery.node：保存了原本的 SQL 语法树节点（SqlNode）
      // subQuery.logic：保存了该子查询所处的布尔逻辑上下文策略（如 UNKNOWN_AS_FALSE）。
      // subQuery.expr：输出参数。转换成功后，该字段会被赋予对应的行表达式（RexNode，通常是 Join 之后指向右表关联字段的 RexInputRef），以此来取代原 SQL 中子查询所在的位置。
      SubQuery subQuery) {
    final RexNode expr = subQuery.expr;
    // 如果 subQuery.expr 已经有值，说明该子查询在之前的递归或依赖处理中已经被转换过了，直接返回，避免重复转换。
    if (expr != null) {
      // Already done.
      return;
    }

    final SqlBasicCall call;
    final RelNode rel;
    final SqlNode query;
    final RelOptUtil.Exists converted;
    switch (subQuery.node.getKind()) {
    case CURSOR:
      convertCursor(bb, subQuery);
      return;

    case ARRAY_QUERY_CONSTRUCTOR:
    case MAP_QUERY_CONSTRUCTOR:
    case MULTISET_QUERY_CONSTRUCTOR:
      if (!config.isExpand()) {// 如果配置关闭了子查询展开，则直接返回不予处理
        return;
      }
      // fall through
    case MULTISET_VALUE_CONSTRUCTOR:
      // 将集合构造器转换为关系代数节点
      rel = convertMultisets(ImmutableList.of(subQuery.node), bb);
      // 将生成的 rel 以 INNER JOIN 形式注册到黑板，并将产生的 RexNode 赋给 subQuery.expr
      subQuery.expr = bb.register(rel, JoinRelType.INNER);
      return;
    // 集合成员资格分支（IN, NOT IN, SOME, ALL）— 核心难点
    case IN:
    case NOT_IN:
    case SOME:
    case ALL:
      // 转换为普通调用对象（如 a IN (SELECT ...)）
      call = (SqlBasicCall) subQuery.node;
      query = call.operand(1);
      // 如果未开启展开，且右边不是固定值列表（而是一条 SELECT 语句），则不在此处展开
      if (!config.isExpand() && !(query instanceof SqlNodeList)) {
        return;
      }
      // 获取左操作数（如 emp.deptno）
      final SqlNode leftKeyNode = call.operand(0);

      final List<SqlNode> leftSqlKeys;
      switch (leftKeyNode.getKind()) {
      // 处理左侧的 key。如果左侧是多列（ROW 类型，如 (a, b) IN (...)），进行拆解
      case ROW:
        leftSqlKeys = new ArrayList<>();
        for (SqlNode sqlExpr : ((SqlBasicCall) leftKeyNode).getOperandList()) {
          leftSqlKeys.add(sqlExpr);
        }
        break;
      default:
        // 单列情况
        leftSqlKeys = ImmutableList.of(leftKeyNode);
      }
      // 固定值列表优化（如 IN (1, 2, 3)）
      if (query instanceof SqlNodeList) {
        SqlNodeList valueList = (SqlNodeList) query;
        // When the list size under the threshold or the list references columns, we convert to OR.
        // 如果值列表的大小小于设定的阈值，或者列表中引用了列（非纯常量）
        if (valueList.size() < config.getInSubQueryThreshold()
            || valueList.accept(new SqlIdentifierFinder())) {
          // 优化：直接将 IN 列表重写为一连串的 OR 表达式（如 a=1 OR a=2 OR a=3）
          subQuery.expr =
              convertInToOr(
                  bb,
                  leftSqlKeys,
                  valueList,
                  (SqlInOperator) call.getOperator());
          return;
        }

        // Otherwise, let convertExists translate
        // values list into an inline table for the
        // reference to Q below.
        // 如果超出了阈值，则不转为 OR，代码将继续向下，把这个值列表转化为一个内联表（Inline Table/Values）
      }
      // 转换为关系代数计划
      // // 将左侧的 SqlNode 键值对转换成关系表达式中的 RexNode
      final List<RexNode> leftKeys = leftSqlKeys.stream()
          .map(bb::convertExpression)
          .collect(toImmutableList());

      // Project out the search columns from the left side

      // Q1:
      // "select from emp where emp.deptno in (select col1 from T)"
      //
      // is converted to
      //
      // "select from
      //   emp inner join (select distinct col1 from T)) q
      //   on emp.deptno = q.col1
      //
      // Q2:
      // "select from emp where emp.deptno not in (Q)"
      //
      // is converted to
      //
      // "select from
      //   emp left outer join (select distinct col1, TRUE from T) q
      //   on emp.deptno = q.col1
      //   where emp.deptno <> null
      //         and q.indicator <> TRUE"
      //
      // Note: Sub-query can be used as SqlUpdate#condition like below:
      //
      //   UPDATE emp
      //   SET empno = 1 WHERE emp.empno IN (
      //     SELECT emp.empno FROM emp WHERE emp.empno = 2)
      //
      // In such case, when converting SqlUpdate#condition, bb.root is null
      // and it makes no sense to do the sub-query substitution.
      // 边界防御：如果当前黑板没有根节点（例如在非法的 UPDATE 条件中），无法做 Join 替换，直接返回
      if (bb.root == null) {
        return;
      }
      // 获取左侧 Key 的行类型
      final RelDataType targetRowType =
          SqlTypeUtil.promoteToRowType(typeFactory,
              validator().getValidatedNodeType(leftKeyNode), null);
      // 标记是否为 NOT IN
      final boolean notIn = call.getOperator().kind == SqlKind.NOT_IN;
      // 核心转换核心：调用 convertExists 将右侧的子查询 Q 包装、转换为一个含有 Exists/Join 语义的逻辑计划
      converted =
          convertExists(query, RelOptUtil.SubQueryType.IN, subQuery.logic,
              notIn, targetRowType);
      if (converted.indicator) {
        // Generate
        //    emp CROSS JOIN (SELECT COUNT(*) AS c,
        //                       COUNT(deptno) AS ck FROM dept)
        final RelDataType longType =
            typeFactory.createSqlType(SqlTypeName.BIGINT);
        final RelNode seek = converted.r.getInput(0); // fragile
        final int keyCount = leftKeys.size();
        final List<Integer> args = ImmutableIntList.range(0, keyCount);
        LogicalAggregate aggregate =
            LogicalAggregate.create(seek,
                ImmutableList.of(),
                ImmutableBitSet.of(),
                null,
                ImmutableList.of(
                    AggregateCall.create(SqlStdOperatorTable.COUNT, false,
                        false, false, ImmutableList.of(), ImmutableList.of(),
                        -1, null, RelCollations.EMPTY, longType, null),
                    AggregateCall.create(SqlStdOperatorTable.COUNT, false,
                        false, false, ImmutableList.of(), args,
                        -1, null, RelCollations.EMPTY, longType, null)));
        LogicalJoin join =
            LogicalJoin.create(bb.root(), aggregate, ImmutableList.of(),
                rexBuilder.makeLiteral(true), ImmutableSet.of(), JoinRelType.INNER);
        bb.setRoot(join, false);
      }
      final RexNode rex =
          bb.register(converted.r,
              converted.outerJoin ? JoinRelType.LEFT : JoinRelType.INNER,
              leftKeys);

      RelOptUtil.Logic logic = subQuery.logic;
      switch (logic) {
      case TRUE_FALSE_UNKNOWN:
      case UNKNOWN_AS_TRUE:
        if (!converted.indicator) {
          logic = RelOptUtil.Logic.TRUE_FALSE;
        }
        break;
      default:
        break;
      }
      subQuery.expr = translateIn(logic, bb.root, rex);
      if (notIn) {
        subQuery.expr =
            rexBuilder.makeCall(SqlStdOperatorTable.NOT, subQuery.expr);
      }
      return;

    case EXISTS:
      // "select from emp where exists (select a from T)"
      //
      // is converted to the following if the sub-query is correlated:
      //
      // "select from emp left outer join (select AGG_TRUE() as indicator
      // from T group by corr_var) q where q.indicator is true"
      //
      // If there is no correlation, the expression is replaced with a
      // boolean indicating whether the sub-query returned 0 or >= 1 row.
      if (!config.isExpand()) {
        return;
      }
      call = (SqlBasicCall) subQuery.node;
      query = call.operand(0);
      final SqlValidatorScope seekScope =
          (query instanceof SqlSelect)
              ? validator().getSelectScope((SqlSelect) query)
              : validator().getEmptyScope();
      final Blackboard seekBb = createBlackboard(seekScope, null, false);
      final RelNode seekRel = convertQueryOrInList(seekBb, query, null);
      requireNonNull(seekRel, () -> "seekRel is null for query " + query);
      // An EXIST sub-query whose inner child has at least 1 tuple
      // (e.g. an Aggregate with no grouping columns or non-empty Values
      // node) should be simplified to a Boolean constant expression.
      final RelMetadataQuery mq = seekRel.getCluster().getMetadataQuery();
      final Double minRowCount = mq.getMinRowCount(seekRel);
      if (minRowCount != null && minRowCount >= 1D) {
        subQuery.expr = rexBuilder.makeLiteral(true);
        return;
      }
      converted =
          RelOptUtil.createExistsPlan(seekRel,
              RelOptUtil.SubQueryType.EXISTS, subQuery.logic, true, relBuilder);
      assert !converted.indicator;
      if (convertNonCorrelatedSubQuery(subQuery, bb, converted.r, true)) {
        return;
      }
      subQuery.expr = bb.register(converted.r, JoinRelType.LEFT);
      return;
    case UNIQUE:
      return;
    case SCALAR_QUERY:
      // Convert the sub-query.  If it's non-correlated, convert it
      // to a constant expression.
      if (!config.isExpand()) {
        return;
      }
      call = (SqlBasicCall) subQuery.node;
      query = call.operand(0);
      converted =
          convertExists(query, RelOptUtil.SubQueryType.SCALAR,
              subQuery.logic, true, null);
      assert !converted.indicator;
      if (convertNonCorrelatedSubQuery(subQuery, bb, converted.r, false)) {
        return;
      }
      rel = convertToSingleValueSubq(query, converted.r);
      subQuery.expr = bb.register(rel, JoinRelType.LEFT);
      return;

    case SELECT:
      // This is used when converting multiset queries:
      //
      // select * from unnest(select multiset[deptno] from emps);
      //
      converted =
          convertExists(subQuery.node, RelOptUtil.SubQueryType.SCALAR,
              subQuery.logic, true, null);
      assert !converted.indicator;
      subQuery.expr = bb.register(converted.r, JoinRelType.LEFT);

      // This is used when converting window table functions:
      //
      // select * from table(tumble(table emps, descriptor(deptno), interval '3' DAY))
      //
      bb.cursors.add(converted.r);
      return;
    case SET_SEMANTICS_TABLE:
      if (!config.isExpand()) {
        return;
      }
      substituteSubQueryOfSetSemanticsInputTable(bb, subQuery);
      return;
    default:
      throw new AssertionError("unexpected kind of sub-query: "
          + subQuery.node);
    }
  }

  private void substituteSubQueryOfSetSemanticsInputTable(
      Blackboard bb,
      SubQuery subQuery) {
    SqlBasicCall call;
    SqlNode query;
    call = (SqlBasicCall) subQuery.node;
    query = call.operand(0);
    final SqlValidatorScope innerTableScope =
        (query instanceof SqlSelect)
            ? validator().getSelectScope((SqlSelect) query)
            : validator().getEmptyScope();
    final Blackboard setSemanticsTableBb =
        createBlackboard(innerTableScope, null, false);
    final RelNode inputOfSetSemanticsTable =
        convertQueryRecursive(query, false, null).project();
    requireNonNull(inputOfSetSemanticsTable,
        () -> "input RelNode is null for query " + query);
    SqlNodeList partitionList = call.operand(1);
    final ImmutableBitSet partitionKeys =
        buildPartitionKeys(setSemanticsTableBb, partitionList);
    // For set semantics table, distribution is singleton if does not specify
    // partition keys
    RelDistribution distribution = partitionKeys.isEmpty()
        ? RelDistributions.SINGLETON
        : RelDistributions.hash(partitionKeys.asList());
    // ORDER BY
    final SqlNodeList orderList = call.operand(2);
    final RelCollation orders = buildCollation(setSemanticsTableBb, orderList);
    relBuilder.push(inputOfSetSemanticsTable);
    if (orderList.isEmpty()) {
      relBuilder.exchange(distribution);
    } else {
      relBuilder.sortExchange(distribution, orders);
    }
    RelNode tableRel = relBuilder.build();
    subQuery.expr = bb.register(tableRel, JoinRelType.LEFT);
    // This is used when converting window table functions:
    //
    // select * from table(tumble(table emps, descriptor(deptno),
    // interval '3' DAY))
    //
    bb.cursors.add(tableRel);
  }

  private ImmutableBitSet buildPartitionKeys(Blackboard bb, SqlNodeList partitionList) {
    final ImmutableBitSet.Builder partitionKeys = ImmutableBitSet.builder();
    for (SqlNode partition : partitionList) {
      validator().deriveType(bb.scope, partition);
      RexNode e = bb.convertExpression(partition);
      partitionKeys.set(parseFieldIdx(e));
    }
    return partitionKeys.build();
  }

  /**
   * Note: The ORDER BY clause for input table parameter differs from the
   * ORDER BY clause in some other contexts in that only columns may be sorted
   * (not arbitrary expressions).
   *
   * @param bb              Scope within which to resolve identifiers
   * @param orderList       Order by clause, may be null
   * @return ordering of input table
   */
  private RelCollation buildCollation(Blackboard bb, SqlNodeList orderList) {
    final List<RelFieldCollation> orderKeys = new ArrayList<>();
    for (SqlNode orderItem : orderList) {
      orderKeys.add(
          convertOrderItem(
              bb,
              orderItem,
              RelFieldCollation.Direction.ASCENDING,
              RelFieldCollation.NullDirection.UNSPECIFIED));
    }
    return cluster.traitSet().canonize(RelCollations.of(orderKeys));
  }

  private RelFieldCollation convertOrderItem(
      Blackboard bb,
      SqlNode orderItem,
      RelFieldCollation.Direction direction,
      RelFieldCollation.NullDirection nullDirection) {
    switch (orderItem.getKind()) {
    case DESCENDING:
      return convertOrderItem(
          bb,
          ((SqlCall) orderItem).operand(0),
          RelFieldCollation.Direction.DESCENDING,
          nullDirection);
    case NULLS_FIRST:
      return convertOrderItem(
          bb,
          ((SqlCall) orderItem).operand(0),
          direction,
          RelFieldCollation.NullDirection.FIRST);
    case NULLS_LAST:
      return convertOrderItem(
          bb,
          ((SqlCall) orderItem).operand(0),
          direction,
          RelFieldCollation.NullDirection.LAST);
    default:
      break;
    }

    switch (nullDirection) {
    case UNSPECIFIED:
      nullDirection = validator().config().defaultNullCollation().last(desc(direction))
          ? RelFieldCollation.NullDirection.LAST
          : RelFieldCollation.NullDirection.FIRST;
      break;
    default:
      break;
    }

    RexNode e = bb.convertExpression(orderItem);
    return new RelFieldCollation(parseFieldIdx(e), direction, nullDirection);
  }

  private static int parseFieldIdx(RexNode e) {
    switch (e.getKind()) {
    case FIELD_ACCESS:
      final RexFieldAccess f = (RexFieldAccess) e;
      return f.getField().getIndex();
    case INPUT_REF:
      final RexInputRef ref = (RexInputRef) e;
      return ref.getIndex();
    default:
      throw new AssertionError();
    }
  }

  private RexNode translateIn(RelOptUtil.Logic logic, @Nullable RelNode root,
      final RexNode rex) {
    switch (logic) {
    case TRUE:
      return rexBuilder.makeLiteral(true);

    case TRUE_FALSE:
    case UNKNOWN_AS_FALSE:
      assert rex instanceof RexRangeRef;
      final int fieldCount = rex.getType().getFieldCount();
      RexNode rexNode = rexBuilder.makeFieldAccess(rex, fieldCount - 1);
      rexNode = rexBuilder.makeCall(SqlStdOperatorTable.IS_TRUE, rexNode);

      // Then append the IS NOT NULL(leftKeysForIn).
      //
      // RexRangeRef contains the following fields:
      //   leftKeysForIn,
      //   rightKeysForIn (the original sub-query select list),
      //   nullIndicator
      //
      // The first two lists contain the same number of fields.
      final int k = (fieldCount - 1) / 2;
      ImmutableList.Builder<RexNode> rexNodeBuilder = ImmutableList.builder();
      rexNodeBuilder.add(rexNode);
      for (int i = 0; i < k; i++) {
        rexNodeBuilder.add(
            rexBuilder.makeCall(
                SqlStdOperatorTable.IS_NOT_NULL,
                rexBuilder.makeFieldAccess(rex, i)));
      }
      rexNode =
          rexBuilder.makeCall(rexNode.getType(), SqlStdOperatorTable.AND,
              RexUtil.flatten(rexNodeBuilder.build(), SqlStdOperatorTable.AND));
      return rexNode;

    case TRUE_FALSE_UNKNOWN:
    case UNKNOWN_AS_TRUE:
      // select e.deptno,
      //   case
      //   when ct.c = 0 then false
      //   when dt.i is not null then true
      //   when e.deptno is null then null
      //   when ct.ck < ct.c then null
      //   else false
      //   end
      // from e
      // cross join (select count(*) as c, count(deptno) as ck from v) as ct
      // left join (select distinct deptno, true as i from v) as dt
      //   on e.deptno = dt.deptno
      final Join join = (Join) requireNonNull(root, "root");
      final Project left = (Project) join.getLeft();
      final RelNode leftLeft = ((Join) left.getInput()).getLeft();
      final int leftLeftCount = leftLeft.getRowType().getFieldCount();
      final RelDataType longType =
          typeFactory.createSqlType(SqlTypeName.BIGINT);
      final RexNode cRef = rexBuilder.makeInputRef(root, leftLeftCount);
      final RexNode ckRef = rexBuilder.makeInputRef(root, leftLeftCount + 1);
      final RexNode iRef =
          rexBuilder.makeInputRef(root, root.getRowType().getFieldCount() - 1);

      final RexLiteral zero =
          rexBuilder.makeExactLiteral(BigDecimal.ZERO, longType);
      final RexLiteral trueLiteral = rexBuilder.makeLiteral(true);
      final RexLiteral falseLiteral = rexBuilder.makeLiteral(false);
      final RexNode unknownLiteral =
          rexBuilder.makeNullLiteral(trueLiteral.getType());

      final ImmutableList.Builder<RexNode> args = ImmutableList.builder();
      args.add(rexBuilder.makeCall(SqlStdOperatorTable.EQUALS, cRef, zero),
          falseLiteral,
          rexBuilder.makeCall(SqlStdOperatorTable.IS_NOT_NULL, iRef),
          trueLiteral);
      final JoinInfo joinInfo = join.analyzeCondition();
      for (int leftKey : joinInfo.leftKeys) {
        final RexNode kRef = rexBuilder.makeInputRef(root, leftKey);
        args.add(rexBuilder.makeCall(SqlStdOperatorTable.IS_NULL, kRef),
            unknownLiteral);
      }
      args.add(rexBuilder.makeCall(SqlStdOperatorTable.LESS_THAN, ckRef, cRef),
          unknownLiteral,
          falseLiteral);

      return rexBuilder.makeCall(SqlStdOperatorTable.CASE, args.build());

    default:
      throw new AssertionError(logic);
    }
  }

  /**
   * Determines if a sub-query is non-correlated and if so, converts it to a
   * constant.
   *
   * @param subQuery  the call that references the sub-query
   * @param bb        blackboard used to convert the sub-query
   * @param converted RelNode tree corresponding to the sub-query
   * @param isExists  true if the sub-query is part of an EXISTS expression
   * @return Whether the sub-query can be converted to a constant
   */
  private boolean convertNonCorrelatedSubQuery(
      SubQuery subQuery,
      Blackboard bb,
      RelNode converted,
      boolean isExists) {
    SqlCall call = (SqlBasicCall) subQuery.node;
    if (subQueryConverter.canConvertSubQuery()
        && isSubQueryNonCorrelated(converted, bb)) {
      // First check if the sub-query has already been converted
      // because it's a nested sub-query.  If so, don't re-evaluate
      // it again.
      RexNode constExpr = mapConvertedNonCorrSubqs.get(call);
      if (constExpr == null) {
        constExpr =
            subQueryConverter.convertSubQuery(
                call,
                this,
                isExists,
                config.isExplain());
      }
      if (constExpr != null) {
        subQuery.expr = constExpr;
        mapConvertedNonCorrSubqs.put(call, constExpr);
        return true;
      }
    }
    return false;
  }

  /**
   * Converts the RelNode tree for a select statement to a select that
   * produces a single value.
   *
   * @param query the query
   * @param plan   the original RelNode tree corresponding to the statement
   * @return the converted RelNode tree
   */
  public RelNode convertToSingleValueSubq(
      SqlNode query,
      RelNode plan) {
    // Check whether query is guaranteed to produce a single value.
    if (query instanceof SqlSelect) {
      SqlSelect select = (SqlSelect) query;
      SqlNodeList selectList = select.getSelectList();
      SqlNodeList groupList = select.getGroup();

      if ((selectList.size() == 1)
          && ((groupList == null) || (groupList.size() == 0))) {
        SqlNode selectExpr = selectList.get(0);
        if (selectExpr instanceof SqlCall) {
          SqlCall selectExprCall = (SqlCall) selectExpr;
          if (Util.isSingleValue(selectExprCall)) {
            return plan;
          }
        }

        // If there is a limit with 0 or 1,
        // it is ensured to produce a single value
        SqlNode fetch = select.getFetch();
        if (fetch instanceof SqlNumericLiteral) {
          long value = ((SqlNumericLiteral) fetch).getValueAs(Long.class);
          if (value < 2) {
            return plan;
          }
        }
      }
    } else if (query instanceof SqlCall) {
      // If the query is (values ...),
      // it is necessary to look into the operands to determine
      // whether SingleValueAgg is necessary
      SqlCall exprCall = (SqlCall) query;
      if (exprCall.getOperator()
          instanceof SqlValuesOperator
              && Util.isSingleValue(exprCall)) {
        return plan;
      }
    }

    // If not, project SingleValueAgg
    return RelOptUtil.createSingleValueAggRel(
        cluster,
        plan);
  }

  /**
   * Converts "x IN (1, 2, ...)" to "x=1 OR x=2 OR ...".
   *
   * @param leftKeys   LHS
   * @param valuesList RHS
   * @param op         The operator (IN, NOT IN, &gt; SOME, ...)
   * @return converted expression
   */
  private @Nullable RexNode convertInToOr(
      final Blackboard bb,
      final List<SqlNode> leftKeys,
      SqlNodeList valuesList,
      SqlInOperator op) {
    final List<RexNode> comparisons = new ArrayList<>();
    for (SqlNode rightVals : valuesList) {
      final SqlOperator comparisonOp;
      if (op instanceof SqlQuantifyOperator) {
        comparisonOp =
            RelOptUtil.op(((SqlQuantifyOperator) op).comparisonKind,
                SqlStdOperatorTable.EQUALS);
      } else {
        comparisonOp = SqlStdOperatorTable.EQUALS;
      }
      RexNode rexComparison;
      if (leftKeys.size() == 1) {
        SqlCall sqlCall =
            comparisonOp.createCall(rightVals.getParserPosition(), leftKeys.get(0), rightVals);
        rexComparison = bb.convertExpression(sqlCall);
      } else {
        assert rightVals instanceof SqlCall;
        final SqlBasicCall call = (SqlBasicCall) rightVals;
        assert (call.getOperator() instanceof SqlRowOperator)
            && call.operandCount() == leftKeys.size();
        rexComparison =
            RexUtil.composeConjunction(
              rexBuilder, Util.transform(
                  Pair.zip(leftKeys, call.getOperandList()),
                  pair -> bb.convertExpression(
                      comparisonOp.createCall(
                        rightVals.getParserPosition(), pair.left, pair.right))));
      }
      comparisons.add(rexComparison);
    }

    switch (op.kind) {
    case ALL:
      return RexUtil.composeConjunction(rexBuilder, comparisons, true);
    case NOT_IN:
      return rexBuilder.makeCall(SqlStdOperatorTable.NOT,
          RexUtil.composeDisjunction(rexBuilder, comparisons));
    case IN:
    case SOME:
      return RexUtil.composeDisjunction(rexBuilder, comparisons, true);
    default:
      throw new AssertionError();
    }
  }

  /**
   * Gets the list size threshold under which {@link #convertInToOr} is used.
   * Lists of this size or greater will instead be converted to use a join
   * against an inline table
   * ({@link org.apache.calcite.rel.logical.LogicalValues}) rather than a
   * predicate. A threshold of 0 forces usage of an inline table in all cases; a
   * threshold of Integer.MAX_VALUE forces usage of OR in all cases
   *
   * @return threshold, default {@link #DEFAULT_IN_SUB_QUERY_THRESHOLD}
   */
  @Deprecated // to be removed before 2.0
  protected int getInSubqueryThreshold() {
    return config.getInSubQueryThreshold();
  }

  /**
   * Converts an EXISTS or IN predicate into a join. For EXISTS, the sub-query
   * produces an indicator variable, and the result is a relational expression
   * which outer joins that indicator to the original query. After performing
   * the outer join, the condition will be TRUE if the EXISTS condition holds,
   * NULL otherwise.
   *
   * @param seek           A query, for example 'select * from emp' or
   *                       'values (1,2,3)' or '('Foo', 34)'.
   * @param subQueryType   Whether sub-query is IN, EXISTS or scalar
   * @param logic Whether the answer needs to be in full 3-valued logic (TRUE,
   *     FALSE, UNKNOWN) will be required, or whether we can accept an
   *     approximation (say representing UNKNOWN as FALSE)
   * @param notIn Whether the operation is NOT IN
   * @return join expression
   */
  private RelOptUtil.Exists convertExists(
      SqlNode seek,
      RelOptUtil.SubQueryType subQueryType,
      RelOptUtil.Logic logic,
      boolean notIn,
      @Nullable RelDataType targetDataType) {
    final SqlValidatorScope seekScope =
        (seek instanceof SqlSelect)
            ? validator().getSelectScope((SqlSelect) seek)
            : validator().getEmptyScope();
    final Blackboard seekBb = createBlackboard(seekScope, null, false);
    RelNode seekRel = convertQueryOrInList(seekBb, seek, targetDataType);
    requireNonNull(seekRel, () -> "seekRel is null for query " + seek);

    return RelOptUtil.createExistsPlan(seekRel,
        subQueryType, logic, notIn, relBuilder);
  }

  private @Nullable RelNode convertQueryOrInList(
      Blackboard bb,
      SqlNode seek,
      @Nullable RelDataType targetRowType) {
    // NOTE: Once we start accepting single-row queries as row constructors,
    // there will be an ambiguity here for a case like X IN ((SELECT Y FROM
    // Z)).  The SQL standard resolves the ambiguity by saying that a lone
    // select should be interpreted as a table expression, not a row
    // expression.  The semantic difference is that a table expression can
    // return multiple rows.
    if (seek instanceof SqlNodeList) {
      return convertRowValues(
          bb,
          seek,
          (SqlNodeList) seek,
          false,
          targetRowType);
    } else {
      return convertQueryRecursive(seek, false, null).project();
    }
  }

  private @Nullable RelNode convertRowValues(
      Blackboard bb,
      SqlNode rowList,
      Collection<SqlNode> rows,
      boolean allowLiteralsOnly,
      @Nullable RelDataType targetRowType) {
    // NOTE jvs 30-Apr-2006: We combine all rows consisting entirely of
    // literals into a single LogicalValues; this gives the optimizer a smaller
    // input tree.  For everything else (computed expressions, row
    // sub-queries), we union each row in as a projection on top of a
    // LogicalOneRow.

    final ImmutableList.Builder<ImmutableList<RexLiteral>> tupleList =
        ImmutableList.builder();
    final RelDataType listType = validator().getValidatedNodeType(rowList);
    final RelDataType rowType;
    if (targetRowType != null) {
      rowType =
              SqlTypeUtil.keepSourceTypeAndTargetNullability(targetRowType, listType, typeFactory);
    } else {
      rowType = SqlTypeUtil.promoteToRowType(typeFactory, listType, null);
    }

    final List<RelNode> unionInputs = new ArrayList<>();
    for (SqlNode node : rows) {
      SqlBasicCall call;
      if (isRowConstructor(node)) {
        call = (SqlBasicCall) node;
        ImmutableList.Builder<RexLiteral> tuple = ImmutableList.builder();
        for (Ord<SqlNode> operand : Ord.zip(call.getOperandList())) {
          RexLiteral rexLiteral =
              convertLiteralInValuesList(
                  operand.e,
                  bb,
                  rowType,
                  operand.i);
          if ((rexLiteral == null) && allowLiteralsOnly) {
            return null;
          }
          if ((rexLiteral == null) || !config.isCreateValuesRel()) {
            // fallback to convertRowConstructor
            tuple = null;
            break;
          }
          tuple.add(rexLiteral);
        }
        if (tuple != null) {
          tupleList.add(tuple.build());
          continue;
        }
      } else {
        RexLiteral rexLiteral =
            convertLiteralInValuesList(
                node,
                bb,
                rowType,
                0);
        if ((rexLiteral != null) && config.isCreateValuesRel()) {
          tupleList.add(ImmutableList.of(rexLiteral));
          continue;
        } else {
          if ((rexLiteral == null) && allowLiteralsOnly) {
            return null;
          }
        }

        // convert "1" to "row(1)"
        call =
            (SqlBasicCall) SqlStdOperatorTable.ROW.createCall(
                SqlParserPos.ZERO,
                node);
      }
      unionInputs.add(convertRowConstructor(bb, call));
    }
    LogicalValues values =
        LogicalValues.create(cluster, rowType, tupleList.build());
    RelNode resultRel;
    if (unionInputs.isEmpty()) {
      resultRel = values;
    } else {
      if (!values.getTuples().isEmpty()) {
        unionInputs.add(values);
      }
      resultRel = LogicalUnion.create(unionInputs, true);
    }
    leaves.put(resultRel, resultRel.getRowType().getFieldCount());
    return resultRel;
  }

  private @Nullable RexLiteral convertLiteralInValuesList(
      @Nullable SqlNode sqlNode,
      Blackboard bb,
      RelDataType rowType,
      int iField) {
    if (!(sqlNode instanceof SqlLiteral)) {
      return null;
    }
    RelDataTypeField field = rowType.getFieldList().get(iField);
    RelDataType type = field.getType();
    if (type.isStruct()) {
      // null literals for weird stuff like UDT's need
      // special handling during type flattening, so
      // don't use LogicalValues for those
      return null;
    }
    return convertLiteral((SqlLiteral) sqlNode, bb, type);
  }

  private RexLiteral convertLiteral(SqlLiteral sqlLiteral,
      Blackboard bb, RelDataType type) {
    RexNode literalExpr = exprConverter.convertLiteral(bb, sqlLiteral);

    if (!(literalExpr instanceof RexLiteral)) {
      assert literalExpr.isA(SqlKind.CAST);
      RexNode child = ((RexCall) literalExpr).getOperands().get(0);
      assert RexLiteral.isNullLiteral(child);

      // NOTE jvs 22-Nov-2006:  we preserve type info
      // in LogicalValues digest, so it's OK to lose it here
      return (RexLiteral) child;
    }

    RexLiteral literal = (RexLiteral) literalExpr;

    Comparable value = literal.getValue();

    if (SqlTypeUtil.isExactNumeric(type) && SqlTypeUtil.hasScale(type)) {
      BigDecimal roundedValue =
          NumberUtil.rescaleBigDecimal(
              (BigDecimal) value,
              type.getScale());
      return rexBuilder.makeExactLiteral(
          roundedValue,
          type);
    }

    if ((value instanceof NlsString)
        && (type.getSqlTypeName() == SqlTypeName.CHAR)) {
      // pad fixed character type
      NlsString unpadded = (NlsString) value;
      return rexBuilder.makeCharLiteral(
          new NlsString(
              Spaces.padRight(unpadded.getValue(), type.getPrecision()),
              unpadded.getCharsetName(),
              unpadded.getCollation()));
    }
    return literal;
  }

  private static boolean isRowConstructor(SqlNode node) {
    if (!(node.getKind() == SqlKind.ROW)) {
      return false;
    }
    SqlCall call = (SqlCall) node;
    return call.getOperator().getName().equalsIgnoreCase("row");
  }

  /**
   * Builds a list of all <code>IN</code> or <code>EXISTS</code> operators
   * inside SQL parse tree. Does not traverse inside queries.
   *
   * @param bb                           blackboard
   * @param node                         the SQL parse tree
   * @param logic Whether the answer needs to be in full 3-valued logic (TRUE,
   *              FALSE, UNKNOWN) will be required, or whether we can accept
   *              an approximation (say representing UNKNOWN as FALSE)
   * @param registerOnlyScalarSubQueries if set to true and the parse tree
   *                                     corresponds to a variation of a select
   *                                     node, only register it if it's a scalar
   *                                     sub-query
   * @param clause A clause inside which sub-query is searched
   */
  // 主要职责是：深度优先遍历 SQL 语法树（SqlNode），找出所有嵌套的子查询（如 IN、EXISTS、标量子查询等），并将它们注册到黑板（Blackboard）中。
  // 最难、最精妙的地方在于，它在遍历的同时，会严格推导三值逻辑（TRUE, FALSE, UNKNOWN）在算子嵌套过程中的变化，
  // 以此决定后续将子查询消除（或转换为连接）时，是用半连接（Semi-Join）、反连接（Anti-Join）还是普通连接。
  // SqlNode node  当前正在扫描、遍历的语法树节点。
  // RelOptUtil.Logic logic 逻辑上下文类型（三值逻辑指示器）。它是一个枚举，表明当前节点所处的逻辑环境（例如是在 WHERE、SELECT、还是被 NOT 包裹）。它直接决定了子查询是否需要区分 UNKNOWN（Null 值带来的未知状态）。
  private void findSubQueries(
      Blackboard bb,
      SqlNode node,
      RelOptUtil.Logic logic,
      // 控制开关：是否仅注册标量子查询。如果为 true，遇到非标量子查询（如集合类型的子查询）时将不予注册。
      boolean registerOnlyScalarSubQueries,
      // 子查询所在的 SQL 子句标记（如 WHERE、HAVING、SELECT）。它用来辅助追踪子查询在物理 SQL 中所处的位置。
      SqlImplementor.@Nullable Clause clause) {
    final SqlKind kind = node.getKind();
    switch (kind) {
    //  1. 如果这些类型本身就是某种子查询（EXISTS, SELECT, 标量子查询, 或者是数组/集合构造器等）
    case EXISTS:
    case UNIQUE:
    case SELECT:
    case MULTISET_QUERY_CONSTRUCTOR:
    case MULTISET_VALUE_CONSTRUCTOR:
    case ARRAY_QUERY_CONSTRUCTOR:
    case MAP_QUERY_CONSTRUCTOR:
    case CURSOR:
    case SET_SEMANTICS_TABLE:
    case SCALAR_QUERY:
      // 如果当前不限制“只注册标量子查询”，或者当前节点本身就是一个合法的标量子查询（SCALAR_QUERY）
      if (!registerOnlyScalarSubQueries
          || (kind == SqlKind.SCALAR_QUERY)) {
        // 核心操作：直接将该子查询节点注册到黑板 bb 中，默认使用 TRUE_FALSE 逻辑
        bb.registerSubQuery(node, RelOptUtil.Logic.TRUE_FALSE, clause);
      }
      return;
    // IN 节点需要留到方法最后阶段做特殊处理，这里先跳过
    case IN:
      break;
    case NOT_IN:
    case NOT:
    //  2. 如果遇到取反操作（NOT 或 NOT IN），当前的逻辑策略（logic）需要发生反转
    // 例如 UNKNOWN_AS_FALSE 变成 UNKNOWN_AS_TRUE
      logic = logic.negate();
      break;
    default:
      break;
    }
    if (node instanceof SqlCall) {
      switch (kind) {
      // Do no change logic for AND, IN and NOT IN expressions;
      // but do change logic for OR, NOT and others;
      // EXISTS was handled already.
      // 对于 AND, IN, NOT IN，它们在传递逻辑时不需要强行重置为三值逻辑；
      // 但对于 OR, NOT 以及其他操作，由于可能打乱三值逻辑的假设，需要安全地退化为最严格的三值逻辑。
      case AND:
      case IN:
      case NOT_IN:
        break;
      default:
        // /其他所有调用（如 OR 或者是普通函数），强制将逻辑上下文退化为标准三值逻辑，即保留 UNKNOWN
        logic = RelOptUtil.Logic.TRUE_FALSE_UNKNOWN;
        break;
      }
      // 此阶段的目的是先于当前节点找出并注册子树里所有的标量子查询。
      // // 遍历当前 SqlCall（函数/操作符调用）的所有操作数（Operands）
      for (SqlNode operand : ((SqlCall) node).getOperandList()) {
        if (operand != null) {
          // In the case of an IN expression, locate scalar
          // sub-queries so we can convert them to constants
          findSubQueries(bb, operand, logic,
              // 注意第四个参数（registerOnlyScalarSubQueries）：
              // 如果当前节点是 IN, NOT_IN, SOME(ANY), ALL 或者是上一层传下来的要求，
              // 说明我们正在探测这些集合表达式的内部，此时将其设为 true，代表在操作数内部“只寻找标量子查询”
              // 比如：WHERE a IN (SELECT x FROM t WHERE y = (SELECT max(z) FROM m))
              // 这里要先找出里面的 (SELECT max(z) ...)，把它转成常量
              kind == SqlKind.IN || kind == SqlKind.NOT_IN
                  || kind == SqlKind.SOME || kind == SqlKind.ALL
                  || registerOnlyScalarSubQueries, clause);
        }
      }
      // 如果当前节点是一个节点列表（比如 Select 的字段列表，或者 Group By 的列表）
    } else if (node instanceof SqlNodeList) {
      for (SqlNode child : (SqlNodeList) node) {
        // 同样递归遍历列表中的每一个子节点
        findSubQueries(bb, child, logic,
            kind == SqlKind.IN || kind == SqlKind.NOT_IN
                || kind == SqlKind.SOME || kind == SqlKind.ALL
                || registerOnlyScalarSubQueries, clause);
      }
    }

    // Now that we've located any scalar sub-queries inside the IN
    // expression, register the IN expression itself.  We need to
    // register the scalar sub-queries first so they can be converted
    // before the IN expression is converted.
    // 处理并注册集合类子查询
    switch (kind) {
    // 代码注释说明：现在我们已经定位并处理完了 IN 表达式内部的所有标量子查询，
    // 接下来开始注册这个 IN 表达式本身。必须先注册内部的标量子查询，才能保证正确的转换顺序。
    case IN:
    case NOT_IN:
    case SOME:
    case ALL:
      switch (logic) {
      // 如果是严格的三值逻辑，尝试去校验器中获取该节点的类型
      case TRUE_FALSE_UNKNOWN:
        RelDataType type = validator().getValidatedNodeTypeIfKnown(node);
        if (type == null) {
          // 如果当前节点还没被成功校验（类型未知），则直接返回，暂时不处理
          // The node might not be validated if we still don't know type of the node.
          // Therefore return directly.
          return;
        } else {
          break;
        }
      case UNKNOWN_AS_FALSE:
        // 如果上下文允许将 UNKNOWN 视为 FALSE，Calcite 会将策略升级为 Logic.TRUE
        // 这意味着只有返回 TRUE 的行才有效，后续可以安全地将其转换为高效的 Semi-Join（半连接）
        logic = RelOptUtil.Logic.TRUE;
        break;
      default:
        break;
      }
      // 特殊条件检查：如果该节点是量化运算符（如类似 SOME/ANY 的特殊集合操作）
      // 且能够推导导出集合的类型
      if (node instanceof SqlBasicCall
          && ((SqlCall) node).getOperator() instanceof SqlQuantifyOperator
          && ((SqlQuantifyOperator) ((SqlCall) node).getOperator())
              .tryDeriveTypeForCollection(bb.getValidator(), bb.scope,
                  (SqlCall) node) != null) {
        // 分别递归处理该量化运算符的左操作数和右操作数
        findSubQueries(bb, ((SqlCall) node).operand(0), logic, registerOnlyScalarSubQueries,
            clause);
        findSubQueries(bb, ((SqlCall) node).operand(1), logic, registerOnlyScalarSubQueries,
            clause);
        break;
      }
      // 核心操作：正式将这个 IN / NOT IN / SOME / ALL 表达式节点作为子查询注册到黑板 bb 中
      // 后续优化器看到黑板上的这个记录，就会把它拉升重写为 Join 树。
      bb.registerSubQuery(node, logic, clause);
      break;
    default:
      break;
    }
  }

  /**
   * Converts an expression from {@link SqlNode} to {@link RexNode} format.
   *
   * @param node Expression to translate
   * @return Converted expression
   */
  public RexNode convertExpression(
      SqlNode node) {
    Map<String, RelDataType> nameToTypeMap = Collections.emptyMap();
    final ParameterScope scope =
        new ParameterScope((SqlValidatorImpl) validator(), nameToTypeMap);
    final Blackboard bb = createBlackboard(scope, null, false);
    replaceSubQueries(bb, node, RelOptUtil.Logic.TRUE_FALSE_UNKNOWN);
    return bb.convertExpression(node);
  }

  /**
   * Converts an expression from {@link SqlNode} to {@link RexNode} format,
   * mapping identifier references to predefined expressions.
   *
   * @param node          Expression to translate
   * @param nameToNodeMap map from String to {@link RexNode}; when an
   *                      {@link SqlIdentifier} is encountered, it is used as a
   *                      key and translated to the corresponding value from
   *                      this map
   * @return Converted expression
   */
  public RexNode convertExpression(
      SqlNode node,
      Map<String, RexNode> nameToNodeMap) {
    final Map<String, RelDataType> nameToTypeMap = new HashMap<>();
    for (Map.Entry<String, RexNode> entry : nameToNodeMap.entrySet()) {
      nameToTypeMap.put(entry.getKey(), entry.getValue().getType());
    }
    final ParameterScope scope =
        new ParameterScope((SqlValidatorImpl) validator(), nameToTypeMap);
    final Blackboard bb = createBlackboard(scope, nameToNodeMap, false);
    replaceSubQueries(bb, node, RelOptUtil.Logic.TRUE_FALSE_UNKNOWN);
    return bb.convertExpression(node);
  }

  /**
   * Converts a non-standard expression.
   *
   * <p>This method is an extension-point that derived classes can override. If
   * this method returns a null result, the normal expression translation
   * process will proceed. The default implementation always returns null.
   *
   * @param node Expression
   * @param bb   Blackboard
   * @return null to proceed with the usual expression translation process
   */
  protected @Nullable RexNode convertExtendedExpression(
      SqlNode node,
      Blackboard bb) {
    return null;
  }

  /**
   * Converts a lambda expression to a RexNode.
   *
   * @param bb   Blackboard
   * @param node Lambda expression
   * @return Relational expression
   */
  private RexNode convertLambda(Blackboard bb, SqlNode node) {
    final SqlLambda call = (SqlLambda) node;
    final SqlLambdaScope scope = (SqlLambdaScope) validator().getLambdaScope(call);

    final Map<String, RexNode> nameToNodeMap = new HashMap<>();
    final List<RexLambdaRef> parameters = new ArrayList<>(scope.getParameterTypes().size());
    final Map<String, RelDataType> parameterTypes = scope.getParameterTypes();

    int i = 0;
    for (SqlNode p : call.getParameters()) {
      final String name = p.toString();
      final RexLambdaRef parameter =
          new RexLambdaRef(i, name, requireNonNull(parameterTypes.get(name)));
      parameters.add(parameter);
      nameToNodeMap.put(name, parameter);
      i++;
    }

    final Blackboard lambdaBb = createBlackboard(scope, nameToNodeMap, false);
    lambdaBb.setRoot(castNonNull(bb.inputs));
    final RexNode expr = lambdaBb.convertExpression(call.getExpression());
    return rexBuilder.makeLambdaCall(expr, parameters);
  }

  private RexNode convertOver(Blackboard bb, SqlNode node) {
    SqlCall call = (SqlCall) node;
    SqlCall aggCall = call.operand(0);
    boolean ignoreNulls = false;
    switch (aggCall.getKind()) {
    case IGNORE_NULLS:
      ignoreNulls = true;
      // fall through
    case RESPECT_NULLS:
      aggCall = aggCall.operand(0);
      break;
    default:
      break;
    }

    SqlNode windowOrRef = call.operand(1);
    final SqlWindow window =
        validator().resolveWindow(windowOrRef, bb.scope);

    SqlNode sqlLowerBound = window.getLowerBound();
    SqlNode sqlUpperBound = window.getUpperBound();
    boolean rows = window.isRows();
    SqlNodeList orderList = window.getOrderList();

    if (!aggCall.getOperator().allowsFraming()) {
      // If the operator does not allow framing, bracketing is implicitly
      // everything up to the current row.
      sqlLowerBound = SqlWindow.createUnboundedPreceding(SqlParserPos.ZERO);
      sqlUpperBound = SqlWindow.createCurrentRow(SqlParserPos.ZERO);
      if (aggCall.getKind() == SqlKind.ROW_NUMBER) {
        // ROW_NUMBER() expects specific kind of framing.
        rows = true;
      }
    } else if (orderList.size() == 0) {
      // Without ORDER BY, there must be no bracketing.
      sqlLowerBound = SqlWindow.createUnboundedPreceding(SqlParserPos.ZERO);
      sqlUpperBound = SqlWindow.createUnboundedFollowing(SqlParserPos.ZERO);
    } else if (sqlLowerBound == null && sqlUpperBound == null) {
      sqlLowerBound = SqlWindow.createUnboundedPreceding(SqlParserPos.ZERO);
      sqlUpperBound = SqlWindow.createCurrentRow(SqlParserPos.ZERO);
    } else if (sqlUpperBound == null) {
      sqlUpperBound = SqlWindow.createCurrentRow(SqlParserPos.ZERO);
    } else if (sqlLowerBound == null) {
      sqlLowerBound = SqlWindow.createCurrentRow(SqlParserPos.ZERO);
    }
    final SqlNodeList partitionList = window.getPartitionList();
    final ImmutableList.Builder<RexNode> partitionKeys =
        ImmutableList.builder();
    for (SqlNode partition : partitionList) {
      validator().deriveType(bb.scope, partition);
      partitionKeys.add(bb.convertExpression(partition));
    }
    final RexNode lowerBound =
        bb.convertExpression(requireNonNull(sqlLowerBound, "sqlLowerBound"));
    final RexNode upperBound =
        bb.convertExpression(requireNonNull(sqlUpperBound, "sqlUpperBound"));
    if (orderList.size() == 0 && !rows) {
      // A logical range requires an ORDER BY clause. Use the implicit
      // ordering of this relation. There must be one, otherwise it would
      // have failed validation.
      orderList = bb.scope.getOrderList();
      if (orderList == null) {
        throw new AssertionError(
            "Relation should have sort key for implicit ORDER BY");
      }
    }
    final RexWindowExclusion exclude = RexWindowExclusion.create(window.getExclude());

    final ImmutableList.Builder<RexNode> orderKeys =
        ImmutableList.builder();
    for (SqlNode order : orderList) {
      orderKeys.add(
          bb.convertSortExpression(order,
              RelFieldCollation.Direction.ASCENDING,
              RelFieldCollation.NullDirection.UNSPECIFIED,
              bb::sortToRex));
    }

    try {
      checkArgument(bb.window == null,
          "already in window agg mode");
      bb.window = window;
      RexNode rexAgg = exprConverter.convertCall(bb, aggCall);
      rexAgg =
          rexBuilder.ensureType(
              validator().getValidatedNodeType(call), rexAgg, false);

      // Walk over the tree and apply 'over' to all agg functions. This is
      // necessary because the returned expression is not necessarily a call
      // to an agg function. For example, AVG(x) becomes SUM(x) / COUNT(x).

      final SqlLiteral q = aggCall.getFunctionQuantifier();
      final boolean isDistinct = q != null
          && q.getValue() == SqlSelectKeyword.DISTINCT;

      final RexShuttle visitor =
          new HistogramShuttle(partitionKeys.build(), orderKeys.build(), rows,
              RexWindowBounds.create(sqlLowerBound, lowerBound),
              RexWindowBounds.create(sqlUpperBound, upperBound),
              exclude,
              window.isAllowPartial(), isDistinct, ignoreNulls);
      return rexAgg.accept(visitor);
    } finally {
      bb.window = null;
    }
  }

  protected void convertFrom(
      Blackboard bb,
      @Nullable SqlNode from) {
    convertFrom(bb, from, Collections.emptyList());
  }

  /**
   * Converts a FROM clause into a relational expression.
   *
   * @param bb   Scope within which to resolve identifiers
   * @param from FROM clause of a query. Examples include:
   *
   * <ul>
   * <li>a single table ("SALES.EMP"),
   * <li>an aliased table ("EMP AS E"),
   * <li>a list of tables ("EMP, DEPT"),
   * <li>an ANSI Join expression ("EMP JOIN DEPT ON EMP.DEPTNO = DEPT.DEPTNO"),
   * <li>a VALUES clause ("VALUES ('Fred', 20)"),
   * <li>a query ("(SELECT * FROM EMP WHERE GENDER = 'F')"),
   * <li>or any combination of the above.
   * </ul>
   *
   * @param fieldNames Field aliases, usually come from AS clause, or null
   */
  // 主要职责是解析 SQL 的 FROM 子句。由于 FROM 子句的形式极其多样（可以是一张普通表、一个别名块、一个子查询、一个复杂的 ANSI JOIN 树，或是 UNNEST 集合等），
  // 该方法通过一个庞大的 switch-case 语法树分类器，将不同的语法节点分发给对应的专用子转换方法
  protected void convertFrom(
      // 小黑板上下文。
      // 用于承载和记录当前 FROM 子句解析出来的算子树节点。
      // 各个 case 分支在解析完成后，都会将生成的 RelNode 节点通过 bb.setRoot(...) 挂载到这个黑板上，作为后续处理（如 WHERE 过滤）的底层数据源。
      Blackboard bb,
      // FROM 子句的语法树节点。代表当前需要解析的表达式实体。
      @Nullable SqlNode from,
      // 字段别名列表。
      // 通常来自于 AS 子句显式指定的列重命名（例如 FROM t AS my_table(col1, col2) 中的 col1, col2），用于在底层算子构建完成后强制对其字段名进行刷新重命名
      @Nullable List<String> fieldNames) {

    // 当 SQL 没有 FROM 子句时（例如：SELECT 1 + 1;），from 节点传入为 null。
    // 此时，Calcite 会在黑板上生成一个仅包含单行单列虚数据的虚拟节点（LogicalValues.createOneRow），以此驱动上层的表达式计算。
    if (from == null) {
      bb.setRoot(LogicalValues.createOneRow(cluster), false);
      return;
    }

    final SqlCall call;
    switch (from.getKind()) {
    // 处理 AS（表别名/列别名）语法。
    // 例如 FROM emp AS e(c1, c2)。第一个操作数 operand(0) 是真正的表或子查询 emp。如果操作数数量大于 2，说明后面还附带了列别名 (c1, c2)，
    // 通过 Util.skip 剥离前两个参数后转化为简易名称列表 fieldNameList。接着递归调用 convertFrom，把剥离出来的别名信息向下传递。
    case AS:
      call = (SqlCall) from;
      SqlNode firstOperand = call.operand(0);
      final List<String> fieldNameList = call.operandCount() > 2
          ? SqlIdentifier.simpleNames(Util.skip(call.getOperandList(), 2))
          : null;
      convertFrom(bb, firstOperand, fieldNameList);
      return;
    // 遇到 SQL 里的复杂分析子句（如复杂事件模式匹配 MATCH_RECOGNIZE、行转列 PIVOT、列转行 UNPIVOT）时，直接委托给对应的专用转换方法。
    case MATCH_RECOGNIZE:
      convertMatchRecognize(bb, (SqlMatchRecognize) from);
      return;

    case PIVOT:
      convertPivot(bb, (SqlPivot) from);
      return;

    case UNPIVOT:
      convertUnpivot(bb, (SqlUnpivot) from);
      return;
    // CTE（通用的表达式/字面量）分支
    // 处理 CTE（WITH 临时表定义）。穿透包装，将其内部真正的 query 或者是 body 主体部分提取出来，继续递归调用 convertFrom 转化为物理算子。
    case WITH_ITEM:
      convertFrom(bb, ((SqlWithItem) from).query);
      return;

    case WITH:
      convertFrom(bb, ((SqlWith) from).body);
      return;
    // 数据采样分支
    // 提取 TABLESAMPLE（样本抽样）属性。获取抽样规范（如伯努利抽样或系统抽样参数）。
    // FROM TABLE(SAMPLE('my_mock_dataset', SELECT * FROM emp))
    // 外层算子首先识别出这是一个采样结构，并且它能拿到采样名字 'my_mock_dataset'
    // 但是，真正需要知道这个采样名字的，其实是内层递归里负责解析 FROM emp 的叶子节点转换方法（即刚才分析过的 convertIdentifier）。
    // 或者是某些自定义的 CatalogReader，它们需要根据这个 datasetName 去决定是去读真实的物理表 emp，还是去读一个专门为测试准备的、名为 'my_mock_dataset' 的 Mock 数据集。
    case TABLESAMPLE:
      final List<SqlNode> operands = ((SqlCall) from).getOperandList();
      SqlSampleSpec sampleSpec =
          SqlLiteral.sampleValue(
              requireNonNull(operands.get(1), () -> "operand[1] of " + from));
      if (sampleSpec instanceof SqlSampleSpec.SqlSubstitutionSampleSpec) {
        String sampleName =
            ((SqlSampleSpec.SqlSubstitutionSampleSpec) sampleSpec)
                .getName();
        // 1. 将数据集名字 'my_mock_dataset' 压入栈顶
        datasetStack.push(sampleName);
        // 2. 递归去解析内层的查询（如 SELECT * FROM emp）
        convertFrom(bb, operands.get(0));
        // 3. 内层解析完毕后，及时将该名字弹栈，防止污染其他并列的查询块
        datasetStack.pop();
      } else if (sampleSpec instanceof SqlSampleSpec.SqlTableSampleSpec) {
        SqlSampleSpec.SqlTableSampleSpec tableSampleSpec =
            (SqlSampleSpec.SqlTableSampleSpec) sampleSpec;
        convertFrom(bb, operands.get(0));

        bb.setRoot(
            relBuilder.push(bb.root())
                .sample(tableSampleSpec.isBernoulli(),
                    tableSampleSpec.sampleRate,
                    tableSampleSpec.isRepeatable()
                        ? tableSampleSpec.getRepeatableSeed()
                        : null)
                .build(),
            true);
      } else {
        throw new AssertionError("unknown TABLESAMPLE type: " + sampleSpec);
      }
      return;
    // 转换最基础的数据库表扫描（TableScan）
    // 如果是带特定提示或包装的引用则是 TABLE_REF。
    // 这里统一调用核心方法 convertIdentifier。它会去元数据中查找这张表，并最终创建出最底层的 LogicalTableScan 逻辑算子节点。
    case TABLE_REF:
      call = (SqlCall) from;
      convertIdentifier(bb, call.operand(0), null, call.operand(1));
      return;

    // 当 FROM 后面跟着的就是一张纯表名（如 FROM emp）时，它的类型就是 IDENTIFIER。
    case IDENTIFIER:
      convertIdentifier(bb, (SqlIdentifier) from, null, null);
      return;
    // 转换 CTE 临时表的引用扫描。
    // 当 FROM 引用的是之前在 WITH 块里定义好的临时表名时，调用 convertTransientScan 生成一个临时的虚拟扫描节点，而不会去扫真实的物理磁盘数据库。
    case WITH_ITEM_TABLE_REF:
      SqlWithItemTableRef withItemTableRef = (SqlWithItemTableRef) from;
      convertTransientScan(bb, withItemTableRef.getWithItem());
      return;
    // 处理 EXTEND 动态列扩充语法。
    // 例如在流处理或特定非关系型数据库中，在查询期临时为某张表追加声明几个动态字段。
    // 提取表名 id 和扩充的列定义列表 extendedColumns，一并打包送给 convertIdentifier 进行宽表算子转换。
    case EXTEND:
      call = (SqlCall) from;
      final SqlNode operand0 = call.getOperandList().get(0);
      final SqlIdentifier id = operand0.getKind() == SqlKind.TABLE_REF
          ? ((SqlCall) operand0).operand(0)
          : (SqlIdentifier) operand0;
      SqlNodeList extendedColumns = (SqlNodeList) call.getOperandList().get(1);
      convertIdentifier(bb, id, extendedColumns, null);
      return;
    // 处理时态表快照（FOR SYSTEM_TIME AS OF）。
    // 用于基于时间戳的历史版本回溯查询，调用专用的时态表转换器。
    case SNAPSHOT:
      convertTemporalTable(bb, (SqlCall) from);
      return;
    // 处理各种多表连接（JOIN）。
    // 一旦发现 FROM 里包含 JOIN 结构，立即分发给 convertJoin 方法。
    // 该方法会根据 LEFT/RIGHT/INNER 以及 ON 条件，拼装出极其关键的 LogicalJoin 算子树。
    case JOIN:
      convertJoin(bb, (SqlJoin) from);
      return;
    // 子查询与集合分支
    // 处理嵌套子查询或集合操作。
    // 如果 FROM 后面括号里包着一个内层 SELECT 或是由 UNION/EXCEPT 连起来的子查询块，
    // 这里直接调用高阶的 convertQueryRecursive 将该子查询块彻底拉平转换成一棵独立的算子树 rel，然后将该树挂载为当前黑板的主体数据源。
    case SELECT:
    case INTERSECT:
    case EXCEPT:
    case UNION:
      final RelNode rel = convertQueryRecursive(from, false, null).project();
      bb.setRoot(rel, true);
      return;
    // 虚拟行与集合打平分支
    // 处理字面量结果集（VALUES 表达式）。
    // 例如 FROM (VALUES (1, 'a'), (2, 'b'))。调用 convertValuesImpl 生成常数数据集节点 LogicalValues。
    // 如果外层附带了重命名别名（fieldNames != null），则利用 relBuilder.rename 强行覆盖其默认列名。
    case VALUES:
      convertValuesImpl(bb, (SqlCall) from, null);
      if (fieldNames != null) {
        bb.setRoot(relBuilder.push(bb.root()).rename(fieldNames).build(), true);
      }
      return;
    // 处理 UNNEST（嵌套集合打平算子）。
    // 用于将一行内部的数组（Array）或映射（Map）数据行，纵向展开打平为多行独立的数据记录。
    case UNNEST:
      convertUnnest(bb, (SqlCall) from, fieldNames);
      return;
    // 处理表函数调用（TABLE(...) 语法）。
    // 剥离外层的 TABLE() 语法糖外壳，取出内部真正的自定义函数调用节点 call2，
    // 调用 convertCollectionTable 生成用于承载表函数的专用关系代数节点（如 LogicalTableFunctionScan）。
    case COLLECTION_TABLE:
      call = (SqlCall) from;

      // Dig out real call; TABLE() wrapper is just syntactic.
      assert call.getOperandList().size() == 1;
      final SqlCall call2 = call.operand(0);
      convertCollectionTable(bb, call2);
      return;

    default:
      throw new AssertionError("not a join operator " + from);
    }
  }

  private void convertUnnest(Blackboard bb, SqlCall call, @Nullable List<String> fieldNames) {
    final List<SqlNode> nodes = call.getOperandList();
    final SqlUnnestOperator operator = (SqlUnnestOperator) call.getOperator();
    for (SqlNode node : nodes) {
      replaceSubQueries(bb, node, RelOptUtil.Logic.TRUE_FALSE_UNKNOWN);
    }
    final List<RexNode> exprs = new ArrayList<>();
    Ord.forEach(nodes, (node, i) -> {
      final RexNode e = bb.convertExpression(node);
      final String alias = SqlValidatorUtil.alias(node, i);
      exprs.add(relBuilder.alias(e, alias));
    });
    RelNode child =
        (null != bb.root) ? bb.root : LogicalValues.createOneRow(cluster);
    RelNode uncollect;
    try {
      if (validator().config().conformance().allowAliasUnnestItems()) {
        uncollect = relBuilder
            .push(child)
            .project(exprs)
            .uncollect(requireNonNull(fieldNames, "fieldNames"), operator.withOrdinality)
            .build();
      } else {
        // REVIEW danny 2020-04-26: should we unify the normal field aliases and
        // the item aliases?
        uncollect = relBuilder
            .push(child)
            .project(exprs)
            .uncollect(Collections.emptyList(), operator.withOrdinality)
            .let(r -> fieldNames == null ? r : r.rename(fieldNames))
            .build();
      }
    } catch (Exception ex) {
      SqlParserPos pos = call.getParserPosition();
      throw RESOURCE.validatorContext(
          pos.getLineNum(), pos.getColumnNum(), pos.getEndLineNum(), pos.getEndColumnNum()).ex(ex);
    }
    bb.setRoot(uncollect, true);
  }

  protected void convertMatchRecognize(Blackboard bb,
      SqlMatchRecognize matchRecognize) {
    final SqlValidatorNamespace ns = getNamespace(matchRecognize);
    final SqlValidatorScope scope = validator().getMatchRecognizeScope(matchRecognize);

    final Blackboard matchBb = createBlackboard(scope, null, false);
    final RelDataType rowType = ns.getRowType();
    // convert inner query, could be a table name or a derived table
    SqlNode expr = matchRecognize.getTableRef();
    convertFrom(matchBb, expr);
    final RelNode input = matchBb.root();

    // PARTITION BY
    final SqlNodeList partitionList = matchRecognize.getPartitionList();
    final ImmutableBitSet partitionKeys = buildPartitionKeys(matchBb, partitionList);

    // ORDER BY
    // TODO combine with buildCollation method after support NULLS_FIRST/NULLS_LAST
    final SqlNodeList orderList = matchRecognize.getOrderList();
    final List<RelFieldCollation> orderKeys = new ArrayList<>();
    for (SqlNode order : orderList) {
      final RelFieldCollation.Direction direction;
      switch (order.getKind()) {
      case DESCENDING:
        direction = RelFieldCollation.Direction.DESCENDING;
        order = ((SqlCall) order).operand(0);
        break;
      case NULLS_FIRST:
      case NULLS_LAST:
        throw new AssertionError();
      default:
        direction = RelFieldCollation.Direction.ASCENDING;
        break;
      }
      final RelFieldCollation.NullDirection nullDirection =
          validator().config().defaultNullCollation().last(desc(direction))
              ? RelFieldCollation.NullDirection.LAST
              : RelFieldCollation.NullDirection.FIRST;
      RexNode e = matchBb.convertExpression(order);
      orderKeys.add(
          new RelFieldCollation(((RexInputRef) e).getIndex(), direction,
              nullDirection));
    }
    final RelCollation orders = cluster.traitSet().canonize(RelCollations.of(orderKeys));

    // convert pattern
    final Set<String> patternVarsSet = new HashSet<>();
    SqlNode pattern = matchRecognize.getPattern();
    final SqlBasicVisitor<@Nullable RexNode> patternVarVisitor =
        new SqlBasicVisitor<@Nullable RexNode>() {
          @Override public RexNode visit(SqlCall call) {
            List<SqlNode> operands = call.getOperandList();
            List<RexNode> newOperands = new ArrayList<>();
            for (SqlNode node : operands) {
              RexNode arg = requireNonNull(node.accept(this), node::toString);
              newOperands.add(arg);
            }
            return rexBuilder.makeCall(
              validator().getUnknownType(), call.getOperator(), newOperands);
          }

          @Override public RexNode visit(SqlIdentifier id) {
            assert id.isSimple();
            patternVarsSet.add(id.getSimple());
            return rexBuilder.makeLiteral(id.getSimple());
          }

          @Override public RexNode visit(SqlLiteral literal) {
            if (literal instanceof SqlNumericLiteral) {
              return rexBuilder.makeExactLiteral(BigDecimal.valueOf(literal.intValue(true)));
            } else {
              return rexBuilder.makeLiteral(literal.booleanValue());
            }
          }
        };
    final RexNode patternNode = pattern.accept(patternVarVisitor);
    assert patternNode != null : "pattern is not found in " + pattern;

    SqlLiteral interval = matchRecognize.getInterval();
    RexNode intervalNode = null;
    if (interval != null) {
      intervalNode = matchBb.convertLiteral(interval);
    }

    // convert subset
    final SqlNodeList subsets = matchRecognize.getSubsetList();
    final Map<String, TreeSet<String>> subsetMap = new HashMap<>();
    for (SqlNode node : subsets) {
      List<SqlNode> operands = ((SqlCall) node).getOperandList();
      SqlIdentifier left = (SqlIdentifier) operands.get(0);
      patternVarsSet.add(left.getSimple());
      final SqlNodeList rights = (SqlNodeList) operands.get(1);
      final TreeSet<String> list =
          new TreeSet<>(SqlIdentifier.simpleNames(rights));
      subsetMap.put(left.getSimple(), list);
    }

    SqlNode afterMatch = matchRecognize.getAfter();
    if (afterMatch == null) {
      afterMatch =
          SqlMatchRecognize.AfterOption.SKIP_TO_NEXT_ROW.symbol(SqlParserPos.ZERO);
    }

    final RexNode after;
    if (afterMatch instanceof SqlCall) {
      List<SqlNode> operands = ((SqlCall) afterMatch).getOperandList();
      SqlOperator operator = ((SqlCall) afterMatch).getOperator();
      assert operands.size() == 1;
      SqlIdentifier id = (SqlIdentifier) operands.get(0);
      assert patternVarsSet.contains(id.getSimple())
          : id.getSimple() + " not defined in pattern";
      RexNode rex = rexBuilder.makeLiteral(id.getSimple());
      after =
          rexBuilder.makeCall(validator().getUnknownType(), operator,
              ImmutableList.of(rex));
    } else {
      after = matchBb.convertExpression(afterMatch);
    }

    matchBb.setPatternVarRef(true);

    // convert measures
    final ImmutableMap.Builder<String, RexNode> measureNodes =
        ImmutableMap.builder();
    for (SqlNode measure : matchRecognize.getMeasureList()) {
      List<SqlNode> operands = ((SqlCall) measure).getOperandList();
      String alias = ((SqlIdentifier) operands.get(1)).getSimple();
      RexNode rex = matchBb.convertExpression(operands.get(0));
      measureNodes.put(alias, rex);
    }

    // convert definitions
    final ImmutableMap.Builder<String, RexNode> definitionNodes =
        ImmutableMap.builder();
    for (SqlNode def : matchRecognize.getPatternDefList()) {
      replaceSubQueries(matchBb, def, RelOptUtil.Logic.UNKNOWN_AS_FALSE);
      List<SqlNode> operands = ((SqlCall) def).getOperandList();
      String alias = ((SqlIdentifier) operands.get(1)).getSimple();
      RexNode rex = matchBb.convertExpression(operands.get(0));
      definitionNodes.put(alias, rex);
    }

    final SqlLiteral rowsPerMatch = matchRecognize.getRowsPerMatch();
    final boolean allRows = rowsPerMatch != null
        && rowsPerMatch.getValue() == SqlMatchRecognize.RowsPerMatchOption.ALL_ROWS;

    matchBb.setPatternVarRef(false);

    final RelFactories.MatchFactory factory =
        RelFactories.DEFAULT_MATCH_FACTORY;
    final RelNode rel =
        factory.createMatch(input, patternNode,
            rowType, matchRecognize.getStrictStart().booleanValue(),
            matchRecognize.getStrictEnd().booleanValue(),
            definitionNodes.build(), measureNodes.build(), after, subsetMap,
            allRows, partitionKeys, orders, intervalNode);
    bb.setRoot(rel, false);
  }

  protected void convertPivot(Blackboard bb, SqlPivot pivot) {
    final SqlValidatorScope scope = validator().getJoinScope(pivot);
    final Blackboard pivotBb = createBlackboard(scope, null, false);

    // Convert input
    convertFrom(pivotBb, pivot.query);
    final RelNode input = pivotBb.root();

    final RelDataType inputRowType = input.getRowType();
    relBuilder.push(input);

    // Gather fields.
    final AggConverter aggConverter = AggConverter.create(pivotBb);
    final Set<String> usedColumnNames = pivot.usedColumnNames();

    // 1. Gather group keys.
    inputRowType.getFieldList().stream()
        .filter(field -> !usedColumnNames.contains(field.getName()))
        .forEach(field ->
            aggConverter.addGroupExpr(
                new SqlIdentifier(field.getName(), SqlParserPos.ZERO)));

    // 2. Gather axes.
    pivot.axisList.forEach(aggConverter::addGroupExpr);

    // 3. Gather columns used as arguments to aggregate functions.
    pivotBb.agg = aggConverter;
    final List<@Nullable String> aggAliasList = new ArrayList<>();
    assert aggConverter.aggCalls.size() == 0;
    pivot.forEachAgg((alias, call) -> {
      call.accept(aggConverter);
      aggAliasList.add(alias);
      assert aggConverter.aggCalls.size() == aggAliasList.size();
    });
    pivotBb.agg = null;

    // Project the fields that we will need.
    relBuilder
        .project(aggConverter.convertedInputExprs.leftList(),
            aggConverter.convertedInputExprs.rightList());

    // Build expressions.

    // 1. Build group key
    final RelBuilder.GroupKey groupKey =
        relBuilder.groupKey(
            inputRowType.getFieldList().stream()
                .filter(field -> !usedColumnNames.contains(field.getName()))
                .map(field ->
                    aggConverter.addGroupExpr(
                        new SqlIdentifier(field.getName(), SqlParserPos.ZERO)))
                .collect(ImmutableBitSet.toImmutableBitSet()));

    // 2. Build axes, for example
    // FOR (axis1, axis2 ...) IN ...
    final List<RexNode> axes = new ArrayList<>();
    for (SqlNode axis : pivot.axisList) {
      axes.add(relBuilder.field(aggConverter.addGroupExpr(axis)));
    }

    // 3. Build aggregate expressions, for example
    // PIVOT (sum(a) AS alias1, min(b) AS alias2, ... FOR ... IN ...)
    final List<RelBuilder.AggCall> aggCalls = new ArrayList<>();
    Pair.forEach(aggAliasList, aggConverter.aggCalls, (alias, aggregateCall) ->
        aggCalls.add(relBuilder.aggregateCall(aggregateCall).as(alias)));

    // 4. Build values, for example
    // IN ((v11, v12, ...) AS label1, (v21, v22, ...) AS label2, ...)
    final ImmutableList.Builder<Pair<String, List<RexNode>>> valueList =
        ImmutableList.builder();
    pivot.forEachNameValues((alias, nodeList) ->
        valueList.add(
            Pair.of(alias,
                nodeList.stream().map(bb::convertExpression)
                    .collect(toImmutableList()))));

    final RelNode rel =
        relBuilder.pivot(groupKey, aggCalls, axes, valueList.build())
            .build();
    bb.setRoot(rel, true);
  }

  protected void convertUnpivot(Blackboard bb, SqlUnpivot unpivot) {
    final SqlValidatorScope scope = validator().getJoinScope(unpivot);
    final Blackboard unpivotBb = createBlackboard(scope, null, false);

    // Convert input
    convertFrom(unpivotBb, unpivot.query);
    final RelNode input = unpivotBb.root();
    relBuilder.push(input);

    final List<String> measureNames = unpivot.measureList.stream()
        .map(node -> ((SqlIdentifier) node).getSimple())
        .collect(toImmutableList());
    final List<String> axisNames =  unpivot.axisList.stream()
        .map(node -> ((SqlIdentifier) node).getSimple())
        .collect(toImmutableList());
    final ImmutableList.Builder<Pair<List<RexLiteral>, List<RexNode>>> axisMap =
        ImmutableList.builder();
    unpivot.forEachNameValues((nodeList, valueList) -> {
      if (valueList == null) {
        valueList =
            new SqlNodeList(
                Collections.nCopies(axisNames.size(),
                    SqlLiteral.createCharString(SqlUnpivot.aliasValue(nodeList),
                        SqlParserPos.ZERO)),
                SqlParserPos.ZERO);
      }
      final List<RexLiteral> literals = new ArrayList<>();
      Pair.forEach(valueList, unpivot.axisList, (value, axis) -> {
        final RelDataType type = validator().getValidatedNodeType(axis);
        literals.add(convertLiteral((SqlLiteral) value, bb, type));
      });
      final List<RexNode> nodes = nodeList.stream()
          .map(unpivotBb::convertExpression)
          .collect(toImmutableList());
      axisMap.add(Pair.of(literals, nodes));
    });
    relBuilder.unpivot(unpivot.includeNulls, measureNames, axisNames,
        axisMap.build());
    relBuilder.convert(getNamespace(unpivot).getRowType(), false);

    bb.setRoot(relBuilder.build(), true);
  }

  private void convertTransientScan(Blackboard bb, SqlWithItem withItem) {
    final SqlValidatorNamespace fromNamespace = getNamespace(withItem).resolve();
    bb.setRoot(
        relBuilder.transientScan(withItem.name.getSimple(),
            fromNamespace.getRowType()).build(),
        true);
  }

  // 负责将一个 SQL 标识符（通常是普通的表名或视图名）真正翻译为底层关系代数叶子节点（如 TableScan）。
  // 当 FROM 子句中出现了一个明确的表名（如 FROM emp），经过分类分发后就会进入此方法。
  // 它负责连接 Calcite 的语义校验层（SqlValidator）、元数据层（CatalogReader）以及物理算子构建层。
  private void convertIdentifier(
      // 小黑板上下文。用于暂存和管理当前转换阶段生成的逻辑算子树。该方法最终生成的表扫描节点会回填到 bb.setRoot(...) 中。
      Blackboard bb,
      // 目标表/视图的语法标识符。比如 SALES.EMP，它包含了该实体的多级名称路径。
      SqlIdentifier id,
      // 动态扩充列列表。对应 SQL 的 EXTEND 语法。如果非空，说明用户在查询期临时为这张表追加声明了几个动态字段。
      @Nullable SqlNodeList extendedColumns,
      // 表级 Hint（提示词）列表。
      // 例如特定数据库中针对单表指定的物理索引提示或并发提示。
      @Nullable SqlNodeList tableHints) {
    // 获取并解析标识符对应的元数据命名空间
    // 通过 getNamespace(id) 向校验器索取该标识符在校验期注册的元数据命名空间，并调用 .resolve() 将其平铺、解析为最终的实体命名空间（SqlValidatorNamespace）。
    final SqlValidatorNamespace fromNamespace = getNamespace(id).resolve();
    if (fromNamespace.getNode() != null) {
      // 如果 fromNamespace.getNode() != null，说明当前这个标识符不是一张物理表，而是一个虚拟视图（View）或公共表表达式（CTE）。
      // getNode() 取出来的将是这个视图背后的子查询 SqlNode。此时，代码不再创建物理表扫描，而是直接把子查询重新丢回 convertFrom 方法去递归展开，
      // 将其平铺进当前的算子树中。
      // 然后直接 return 结束当前流程。
      convertFrom(bb, fromNamespace.getNode());
      return;
    }
    // 准备抽样数据集上下文。
    // 检查之前的 TABLESAMPLE 环境栈（datasetStack）中是否积压了指定的数据集名称。
    final String datasetName =
        datasetStack.isEmpty() ? null : datasetStack.peek();
    final boolean[] usedDataset = {false};
    // 从元数据目录中提取核心表对象（RelOptTable）
    // 利用工具类，结合当前命名空间和元数据读取器（catalogReader），正式从底层的元数据仓储中把物理表实体（RelOptTable）搬运到内存中。
    // 紧接着用一条断言，确保表对象绝对存在。
    RelOptTable table =
        SqlValidatorUtil.getRelOptTable(fromNamespace, catalogReader,
            datasetName, usedDataset);
    assert table != null : "getRelOptTable returned null for " + fromNamespace;
    // 动态注入 EXTEND 扩充列。
    if (extendedColumns != null && extendedColumns.size() > 0) {
      // 如果传入的动态列不为空，先将底层表对象解包为 SqlValidatorTable。
      // 接着，把语法层的动态列声明转化为优化器底层的字段强类型（RelDataTypeField），
      // 最后调用 table.extend(extendedFields) 原地重构、生成一张带有这些新动态字段的拓扑新表对象。
      final SqlValidatorTable validatorTable =
          table.unwrapOrThrow(SqlValidatorTable.class);
      final List<RelDataTypeField> extendedFields =
          SqlValidatorUtil.getExtendedColumns(validator(), validatorTable,
              extendedColumns);
      table = table.extend(extendedFields);
    }
    // Review Danny 2020-01-13: hacky to construct a new table scan
    // in order to apply the hint strategies.
    // 预过滤并应用表级 Hint 策略。
    // 正如源码中 Danny 的注释所言，这里略显 Hack（投机）。
    // 为了能够让配置好的 Hint 策略表（hintStrategies）对传入的 tableHints 进行合法性过滤和合并，
    // 这里先通过 LogicalTableScan.create 虚构构建了一个临时的 TableScan 节点。将其作为上下文，计算出最终应该在该表上生效的 RelHint 集合。
    final List<RelHint> hints =
        hintStrategies.apply(SqlUtil.getRelHint(hintStrategies, tableHints),
            LogicalTableScan.create(cluster, table, ImmutableList.of()));

    // 孵化真正的逻辑表扫描算子（TableScan）并挂载到黑板。
    // 在绝大多数默认实现中，它会真正产出一个包装了物理元数据和 Hint 的 LogicalTableScan 节点（它是代数树上最基层的叶子节点）。
    // 随后调用 bb.setRoot 将其封为当前黑板的核心根节点。
    final RelNode tableRel = toRel(table, hints);
    bb.setRoot(tableRel, true);
    // 二级冗余排序安全裁剪。
    // 在少数极端元数据重载情况下，toRel 可能会直接吐出一个带有排序特征的子代数树。
    // 这里做一层防御性检测：如果生成的根节点包含了无上限限制的纯排序（isPureOrder），
    // 且当前环境触发了子查询排序消除策略（removeSortInSubQuery），则同样强制将其 getInput(0) 提堂，把无意义的排序算子当场裁剪剥离。
    if (RelOptUtil.isPureOrder(castNonNull(bb.root))
        && removeSortInSubQuery(bb.top)) {
      bb.setRoot(castNonNull(bb.root).getInput(0), true);
    }
    // 同步黑板的数据集状态。
    if (usedDataset[0]) {
      bb.setDataset(datasetName);
    }
  }

  protected void convertCollectionTable(
      Blackboard bb,
      SqlCall call) {
    final SqlOperator operator = call.getOperator();
    if (operator == SqlStdOperatorTable.TABLESAMPLE) {
      final String sampleName =
          SqlLiteral.unchain(call.operand(0)).getValueAs(String.class);
      datasetStack.push(sampleName);
      SqlCall cursorCall = call.operand(1);
      SqlNode query = cursorCall.operand(0);
      RelNode converted = convertQuery(query, false, false).rel;
      bb.setRoot(converted, false);
      datasetStack.pop();
      return;
    }
    replaceSubQueries(bb, call, RelOptUtil.Logic.TRUE_FALSE_UNKNOWN);

    // Expand table macro if possible. It's more efficient than
    // LogicalTableFunctionScan.
    final SqlCallBinding callBinding =
        new SqlCallBinding(bb.scope.getValidator(), bb.scope, call);
    if (operator instanceof SqlUserDefinedTableMacro) {
      final SqlUserDefinedTableMacro udf =
          (SqlUserDefinedTableMacro) operator;
      final TranslatableTable table = udf.getTable(callBinding);
      final RelDataType rowType = table.getRowType(typeFactory);
      CalciteSchema schema =
          Schemas.subSchema(catalogReader.getRootSchema(),
              udf.getNameAsId().skipLast(1).names);
      TableExpressionFactory expressionFunction =
          clazz -> Schemas.getTableExpression(
              Objects.requireNonNull(schema, "schema").plus(),
              Util.last(udf.getNameAsId().names), table, clazz);
      RelOptTable relOptTable =
          RelOptTableImpl.create(null, rowType,
              udf.getNameAsId().names, table, expressionFunction);
      RelNode converted = toRel(relOptTable, ImmutableList.of());
      bb.setRoot(converted, true);
      return;
    }

    Type elementType;
    if (operator instanceof SqlUserDefinedTableFunction) {
      SqlUserDefinedTableFunction udtf = (SqlUserDefinedTableFunction) operator;
      elementType = udtf.getElementType(callBinding);
    } else {
      elementType = null;
    }

    RexNode rexCall = bb.convertExpression(call);
    final List<RelNode> inputs = bb.retrieveCursors();
    Set<RelColumnMapping> columnMappings =
        getColumnMappings(operator);

    LogicalTableFunctionScan callRel =
        LogicalTableFunctionScan.create(
            cluster,
            inputs,
            rexCall,
            elementType,
            validator().getValidatedNodeType(call),
            columnMappings);

    bb.setRoot(callRel, true);
    afterTableFunction(bb, call, callRel);
  }

  protected void afterTableFunction(
      SqlToRelConverter.Blackboard bb,
      SqlCall call,
      LogicalTableFunctionScan callRel) {
  }

  private void convertTemporalTable(Blackboard bb, SqlCall call) {
    final SqlSnapshot snapshot = (SqlSnapshot) call;
    final RexNode period = bb.convertExpression(snapshot.getPeriod());

    // convert inner query, could be a table name or a derived table
    SqlNode expr = snapshot.getTableRef();
    convertFrom(bb, expr);

    final RelNode snapshotRel = relBuilder.push(bb.root()).snapshot(period).build();

    bb.setRoot(snapshotRel, false);
  }

  private static @Nullable Set<RelColumnMapping> getColumnMappings(SqlOperator op) {
    SqlReturnTypeInference rti = op.getReturnTypeInference();
    if (rti == null) {
      return null;
    }
    if (rti instanceof TableFunctionReturnTypeInference) {
      TableFunctionReturnTypeInference tfrti =
          (TableFunctionReturnTypeInference) rti;
      return tfrti.getColumnMappings();
    } else {
      return null;
    }
  }

  /** Shuttle that replace outer {@link RexInputRef} with
   * {@link RexFieldAccess}, and adjust {@code offset} to
   * each inner {@link RexInputRef} in the lateral join
   * condition. */
  private static class RexAccessShuttle extends RexShuttle {
    private final RexBuilder builder;
    private final RexCorrelVariable rexCorrel;
    private final BitSet varCols = new BitSet();

    RexAccessShuttle(RexBuilder builder, RexCorrelVariable rexCorrel) {
      this.builder = builder;
      this.rexCorrel = rexCorrel;
    }

    @Override public RexNode visitInputRef(RexInputRef input) {
      int i = input.getIndex() - rexCorrel.getType().getFieldCount();
      if (i < 0) {
        varCols.set(input.getIndex());
        return builder.makeFieldAccess(rexCorrel, input.getIndex());
      }
      return builder.makeInputRef(input.getType(), i);
    }
  }

  protected RelNode createJoin(
      Blackboard bb,
      RelNode leftRel,
      RelNode rightRel,
      RexNode joinCond,
      JoinRelType joinType) {
    assert joinCond != null;

    final CorrelationUse p = getCorrelationUse(bb, rightRel);
    if (p != null) {
      RelNode innerRel = p.r;
      ImmutableBitSet requiredCols = p.requiredColumns;

      if (!joinCond.isAlwaysTrue()) {
        final RelFactories.FilterFactory factory =
            RelFactories.DEFAULT_FILTER_FACTORY;
        final RexCorrelVariable rexCorrel =
            (RexCorrelVariable) rexBuilder.makeCorrel(
                leftRel.getRowType(), p.id);
        final RexAccessShuttle shuttle =
            new RexAccessShuttle(rexBuilder, rexCorrel);

        // Replace outer RexInputRef with RexFieldAccess,
        // and push lateral join predicate into inner child
        final RexNode newCond = joinCond.accept(shuttle);
        innerRel = factory.createFilter(p.r, newCond, ImmutableSet.of());
        requiredCols = ImmutableBitSet
            .fromBitSet(shuttle.varCols)
            .union(p.requiredColumns);
      }

      return LogicalCorrelate.create(leftRel, innerRel, ImmutableList.of(),
          p.id, requiredCols, joinType);
    }

    final RelNode node =
        relBuilder.push(leftRel)
            .push(rightRel)
            .join(joinType, joinCond)
            .build();

    // If join conditions are pushed down, update the leaves.
    if (node instanceof Project) {
      final Join newJoin = (Join) node.getInputs().get(0);
      if (leaves.containsKey(leftRel)) {
        leaves.put(newJoin.getLeft(), leaves.get(leftRel));
      }
      if (leaves.containsKey(rightRel)) {
        leaves.put(newJoin.getRight(), leaves.get(rightRel));
      }
    }
    return node;
  }

  private @Nullable CorrelationUse getCorrelationUse(Blackboard bb, final RelNode r0) {
    final Set<CorrelationId> correlatedVariables =
        RelOptUtil.getVariablesUsed(r0);
    if (correlatedVariables.isEmpty()) {
      return null;
    }
    final ImmutableBitSet.Builder requiredColumns = ImmutableBitSet.builder();
    final List<CorrelationId> correlNames = new ArrayList<>();

    // All correlations must refer the same namespace since correlation
    // produces exactly one correlation source.
    // The same source might be referenced by different variables since
    // DeferredLookups are not de-duplicated at create time.
    SqlValidatorNamespace prevNs = null;

    for (CorrelationId correlName : correlatedVariables) {
      DeferredLookup lookup =
          requireNonNull(mapCorrelToDeferred.get(correlName),
              () -> "correlation variable is not found: " + correlName);
      RexFieldAccess fieldAccess = lookup.getFieldAccess(correlName);
      String originalFieldName = fieldAccess.getField().getName();

      final SqlNameMatcher nameMatcher =
          bb.getValidator().getCatalogReader().nameMatcher();
      final SqlValidatorScope.ResolvedImpl resolved =
          new SqlValidatorScope.ResolvedImpl();
      lookup.bb.scope.resolve(ImmutableList.of(lookup.originalRelName),
          nameMatcher, false, resolved);
      assert resolved.count() == 1;
      final SqlValidatorScope.Resolve resolve = resolved.only();
      final SqlValidatorNamespace foundNs = resolve.namespace;
      final RelDataType rowType = resolve.rowType();
      final int childNamespaceIndex = resolve.path.steps().get(0).i;
      final SqlValidatorScope ancestorScope = resolve.scope;
      boolean correlInCurrentScope = bb.scope.isWithin(ancestorScope);

      if (!correlInCurrentScope) {
        continue;
      }

      if (prevNs == null) {
        prevNs = foundNs;
      } else {
        assert prevNs == foundNs : "All correlation variables should resolve"
            + " to the same namespace."
            + " Prev ns=" + prevNs
            + ", new ns=" + foundNs;
      }

      int namespaceOffset = 0;
      if (childNamespaceIndex > 0) {
        // If not the first child, need to figure out the width
        // of output types from all the preceding namespaces
        assert ancestorScope instanceof ListScope;
        List<SqlValidatorNamespace> children =
            ((ListScope) ancestorScope).getChildren();

        for (int i = 0; i < childNamespaceIndex; i++) {
          SqlValidatorNamespace child = children.get(i);
          namespaceOffset +=
              child.getRowType().getFieldCount();
        }
      }

      RexFieldAccess topLevelFieldAccess = fieldAccess;
      while (topLevelFieldAccess.getReferenceExpr() instanceof RexFieldAccess) {
        topLevelFieldAccess = (RexFieldAccess) topLevelFieldAccess.getReferenceExpr();
      }
      final RelDataTypeField field = rowType.getFieldList()
          .get(topLevelFieldAccess.getField().getIndex() - namespaceOffset);
      int pos = namespaceOffset + field.getIndex();

      assert field.getType()
          == topLevelFieldAccess.getField().getType();

      assert pos != -1;

      // bb.root is an aggregate and only projects group by
      // keys.
      Map<Integer, Integer> exprProjection =
          bb.mapRootRelToFieldProjection.get(bb.root);
      if (exprProjection != null) {
        // sub-query can reference group by keys projected from
        // the root of the outer relation.
        Integer projection = exprProjection.get(pos);
        if (projection != null) {
          pos = projection;
        } else {
          // correl not grouped
          throw new AssertionError("Identifier '" + lookup.originalRelName
              + "." + originalFieldName + "' is not a group expr");
        }
      }

      requiredColumns.set(pos);
      correlNames.add(correlName);
    }

    if (correlNames.isEmpty()) {
      // None of the correlating variables originated in this scope.
      return null;
    }

    RelNode r = r0;
    if (correlNames.size() > 1) {
      // The same table was referenced more than once.
      // So we deduplicate.
      r =
          DeduplicateCorrelateVariables.go(rexBuilder, correlNames.get(0),
              Util.skip(correlNames), r0);
      // Add new node to leaves.
      leaves.put(r, r.getRowType().getFieldCount());
    }
    return new CorrelationUse(correlNames.get(0), requiredColumns.build(), r);
  }

  /**
   * Determines whether a sub-query is non-correlated. Note that a
   * non-correlated sub-query can contain correlated references, provided those
   * references do not reference select statements that are parents of the
   * sub-query.
   *
   * @param subq the sub-query
   * @param bb   blackboard used while converting the sub-query, i.e., the
   *             blackboard of the parent query of this sub-query
   * @return true if the sub-query is non-correlated
   */
  private boolean isSubQueryNonCorrelated(RelNode subq, Blackboard bb) {
    Set<CorrelationId> correlatedVariables = RelOptUtil.getVariablesUsed(subq);
    for (CorrelationId correlName : correlatedVariables) {
      DeferredLookup lookup =
          requireNonNull(mapCorrelToDeferred.get(correlName),
              () -> "correlation variable is not found: " + correlName);
      String originalRelName = lookup.originalRelName;

      final SqlNameMatcher nameMatcher =
          lookup.bb.scope.getValidator().getCatalogReader().nameMatcher();
      final SqlValidatorScope.ResolvedImpl resolved =
          new SqlValidatorScope.ResolvedImpl();
      lookup.bb.scope.resolve(ImmutableList.of(originalRelName), nameMatcher,
          false, resolved);

      SqlValidatorScope ancestorScope = resolved.only().scope;

      // If the correlated reference is in a scope that's "above" the
      // sub-query, then this is a correlated sub-query.
      SqlValidatorScope parentScope = bb.scope;
      do {
        if (ancestorScope == parentScope) {
          return false;
        }
        if (parentScope instanceof DelegatingScope) {
          parentScope = ((DelegatingScope) parentScope).getParent();
        } else {
          break;
        }
      } while (parentScope != null);
    }
    return true;
  }

  /**
   * Returns a list of fields to be prefixed to each relational expression.
   *
   * @return List of system fields
   */
  protected List<RelDataTypeField> getSystemFields() {
    return Collections.emptyList();
  }

  private void convertJoin(Blackboard bb, SqlJoin join) {
    SqlValidator validator = validator();
    final SqlValidatorScope scope = validator.getJoinScope(join);
    final Blackboard fromBlackboard = createBlackboard(scope, null, false);

    SqlNode left = join.getLeft();
    SqlNode right = join.getRight();
    final SqlValidatorScope leftScope = validator.getJoinScope(left);
    final Blackboard leftBlackboard =
        createBlackboard(leftScope, null, false);
    final SqlValidatorScope rightScope = validator.getJoinScope(right);
    final Blackboard rightBlackboard =
        createBlackboard(rightScope, null, false);
    convertFrom(leftBlackboard, left);
    final RelNode leftRel = requireNonNull(leftBlackboard.root, "leftBlackboard.root");
    convertFrom(rightBlackboard, right);
    final RelNode tempRightRel = requireNonNull(rightBlackboard.root, "rightBlackboard.root");

    final JoinConditionType conditionType = join.getConditionType();
    final RexNode condition;
    final RelNode rightRel;
    if (join.isNatural()) {
      condition =
          convertNaturalCondition(getNamespace(left), getNamespace(right));
      rightRel = tempRightRel;
    } else {
      switch (conditionType) {
      case NONE:
        condition = rexBuilder.makeLiteral(true);
        rightRel = tempRightRel;
        break;
      case USING:
        condition =
            convertUsingCondition(join, getNamespace(left),
                getNamespace(right));
        rightRel = tempRightRel;
        break;
      case ON:
        Pair<RexNode, RelNode> conditionAndRightNode =
            convertOnCondition(fromBlackboard, join, leftRel, tempRightRel);
        condition = conditionAndRightNode.left;
        rightRel = conditionAndRightNode.right;
        break;
      default:
        throw Util.unexpected(conditionType);
      }
    }
    final RelNode joinRel =
        createJoin(fromBlackboard, leftRel, rightRel, condition,
            convertJoinType(join.getJoinType()));
    relBuilder.push(joinRel);
    relBuilder.project(relBuilder.fields());
    bb.setRoot(relBuilder.build(), false);
  }

  private RexNode convertNaturalCondition(
      SqlValidatorNamespace leftNamespace,
      SqlValidatorNamespace rightNamespace) {
    final List<String> columnList =
        SqlValidatorUtil.deriveNaturalJoinColumnList(
            catalogReader.nameMatcher(),
            leftNamespace.getRowType(),
            rightNamespace.getRowType());
    return convertUsing(leftNamespace, rightNamespace, columnList);
  }

  private RexNode convertUsingCondition(
      SqlJoin join,
      SqlValidatorNamespace leftNamespace,
      SqlValidatorNamespace rightNamespace) {
    final SqlNodeList list =
        requireNonNull((SqlNodeList) join.getCondition(),
            () -> "getCondition for join " + join);
    return convertUsing(leftNamespace, rightNamespace,
        ImmutableList.copyOf(SqlIdentifier.simpleNames(list)));
  }

  /**
   * This currently does not expand correlated full outer joins correctly.  Replaying on the right
   * side to correctly support left joins multiplicities.
   *
   * <blockquote><pre>
   *   SELECT *
   *   FROM t1
   *   LEFT JOIN t2 ON
   *    EXIST(SELECT t3.c3 WHERE t1.c1 = t3.c1 AND t2.c2 = t3.c2)
   *    AND NOT (t2.t2 = 2)
   * </pre></blockquote>
   *
   * <p>Given the de-correlated query produces:
   *
   * <blockquote><pre>
   *  t1.c1 | t2.c2
   *  ------+------
   *    1   |  1
   *    1   |  2
   * </pre></blockquote>
   *
   * <p>If correlated query was replayed on the left side, then an extra rows would be emitted for
   * every {code t1.c1 = 1}, where it failed to join to right side due to {code NOT(t2.t2 = 2)}.
   * However, if the query is joined on the right, side multiplicity is maintained.
   */
  private Pair<RexNode, RelNode> convertOnCondition(
      Blackboard bb,
      SqlJoin join,
      RelNode leftRel,
      RelNode rightRel) {
    SqlNode condition =
        requireNonNull(join.getCondition(),
            () -> "getCondition for join " + join);

    bb.setRoot(ImmutableList.of(leftRel, rightRel));
    replaceSubQueries(bb, condition, RelOptUtil.Logic.UNKNOWN_AS_FALSE);
    final RelNode newRightRel =
        bb.root == null || bb.registered.size() == 0
            ? rightRel
            : bb.reRegister(rightRel);
    bb.setRoot(ImmutableList.of(leftRel, newRightRel));
    RexNode conditionExp =  bb.convertExpression(condition);
    if (conditionExp instanceof RexInputRef && newRightRel != rightRel) {
      int leftFieldCount = leftRel.getRowType().getFieldCount();
      List<RelDataTypeField> rightFieldList = newRightRel.getRowType().getFieldList();
      int rightFieldCount = newRightRel.getRowType().getFieldCount();
      conditionExp =
          rexBuilder.makeInputRef(
              rightFieldList.get(rightFieldCount - 1).getType(),
              leftFieldCount + rightFieldCount - 1);
    }
    return Pair.of(conditionExp, newRightRel);
  }

  /**
   * Returns an expression for matching columns of a USING clause or inferred
   * from NATURAL JOIN. "a JOIN b USING (x, y)" becomes "a.x = b.x AND a.y =
   * b.y". Returns null if the column list is empty.
   *
   * @param leftNamespace Namespace of left input to join
   * @param rightNamespace Namespace of right input to join
   * @param nameList List of column names to join on
   * @return Expression to match columns from name list, or true if name list
   * is empty
   */
  private RexNode convertUsing(SqlValidatorNamespace leftNamespace,
      SqlValidatorNamespace rightNamespace,
      List<String> nameList) {
    final SqlNameMatcher nameMatcher = catalogReader.nameMatcher();
    final List<RexNode> list = new ArrayList<>();
    for (String name : nameList) {
      List<RexNode> operands = new ArrayList<>();
      int offset = 0;
      for (SqlValidatorNamespace n : ImmutableList.of(leftNamespace,
          rightNamespace)) {
        final RelDataType rowType = n.getRowType();
        final RelDataTypeField field = nameMatcher.field(rowType, name);
        assert field != null : "field " + name + " is not found in " + rowType
            + " with " + nameMatcher;
        operands.add(
            rexBuilder.makeInputRef(field.getType(),
                offset + field.getIndex()));
        offset += rowType.getFieldList().size();
      }
      list.add(rexBuilder.makeCall(SqlStdOperatorTable.EQUALS, operands));
    }
    return RexUtil.composeConjunction(rexBuilder, list);
  }

  private static JoinRelType convertJoinType(JoinType joinType) {
    switch (joinType) {
    case COMMA:
    case INNER:
    case CROSS:
      return JoinRelType.INNER;
    case FULL:
      return JoinRelType.FULL;
    case LEFT:
      return JoinRelType.LEFT;
    case RIGHT:
      return JoinRelType.RIGHT;
    default:
      throw Util.unexpected(joinType);
    }
  }

  /**
   * Converts the SELECT, GROUP BY and HAVING clauses of an aggregate query.
   *
   * <p>This method extracts SELECT, GROUP BY and HAVING clauses, and creates
   * an {@link AggConverter}, then delegates to {@link #createAggImpl}.
   * Derived class may override this method to change any of those clauses or
   * specify a different {@link AggConverter}.
   *
   * @param bb            Scope within which to resolve identifiers
   * @param select        Query
   * @param orderExprList Additional expressions needed to implement ORDER BY
   */
  protected void convertAgg(Blackboard bb, SqlSelect select,
      List<SqlNode> orderExprList) {
    requireNonNull(bb.root, "bb.root");
    SqlNodeList groupList = select.getGroup();
    SqlNodeList selectList = select.getSelectList();
    SqlNode having = select.getHaving();

    final AggConverter aggConverter =
        AggConverter.create(bb,
            (AggregatingSelectScope) validator().getSelectScope(select));
    createAggImpl(bb, aggConverter, selectList, groupList, having,
        orderExprList);
  }

  protected final void createAggImpl(
      Blackboard bb,
      final AggConverter aggConverter,
      SqlNodeList selectList,
      @Nullable SqlNodeList groupList,
      @Nullable SqlNode having,
      List<SqlNode> orderExprList) {
    // Find aggregate functions in SELECT and HAVING clause
    final AggregateFinder aggregateFinder = new AggregateFinder();
    selectList.accept(aggregateFinder);
    if (having != null) {
      having.accept(aggregateFinder);
    }

    // first replace the sub-queries inside the aggregates
    // because they will provide input rows to the aggregates.
    replaceSubQueries(bb, aggregateFinder.list,
        RelOptUtil.Logic.TRUE_FALSE_UNKNOWN);

    // also replace sub-queries inside filters in the aggregates
    replaceSubQueries(bb, aggregateFinder.filterList,
        RelOptUtil.Logic.TRUE_FALSE_UNKNOWN);

    // also replace sub-queries inside ordering spec in the aggregates
    replaceSubQueries(bb, aggregateFinder.orderList,
        RelOptUtil.Logic.TRUE_FALSE_UNKNOWN);
    // If group-by clause is missing, pretend that it has zero elements.
    if (groupList == null) {
      groupList = SqlNodeList.EMPTY;
    }

    replaceSubQueries(bb, groupList, RelOptUtil.Logic.TRUE_FALSE_UNKNOWN,
        SqlImplementor.Clause.GROUP_BY);

    // register the group exprs

    // build a map to remember the projections from the top scope to the
    // output of the current root.
    //
    // Calcite allows expressions, not just column references in
    // group by list. This is not SQL 2003 compliant, but hey.

    final AggregatingSelectScope.Resolved r = aggConverter.getResolved();
    for (SqlNode e : r.groupExprList) {
      aggConverter.addGroupExpr(e);
    }

    final RexNode havingExpr;
    final PairList<RexNode, String> projects = PairList.of();

    try {
      checkArgument(bb.agg == null, "already in agg mode");
      bb.agg = aggConverter;

      // convert the select and having expressions, so that the
      // agg converter knows which aggregations are required

      selectList.accept(aggConverter);
      // Assert we don't have dangling items left in the stack
      assert !aggConverter.inOver;
      for (SqlNode expr : orderExprList) {
        expr.accept(aggConverter);
        assert !aggConverter.inOver;
      }
      if (having != null) {
        having.accept(aggConverter);
        assert !aggConverter.inOver;
      }

      // compute inputs to the aggregator
      final PairList<RexNode, @Nullable String> preExprs;
      if (aggConverter.convertedInputExprs.isEmpty()) {
        // Special case for COUNT(*), where we can end up with no inputs
        // at all.  The rest of the system doesn't like 0-tuples, so we
        // select a dummy constant here.
        final RexNode zero = rexBuilder.makeExactLiteral(BigDecimal.ZERO);
        preExprs = PairList.of(zero, null);
      } else {
        preExprs = aggConverter.convertedInputExprs;
      }

      final RelNode inputRel = bb.root();

      // Project the expressions required by agg and having.
      bb.setRoot(
          relBuilder.push(inputRel)
              .projectNamed(preExprs.leftList(), preExprs.rightList(), false)
              .build(),
          false);
      bb.mapRootRelToFieldProjection.put(bb.root(), r.groupExprProjection);

      // REVIEW jvs 31-Oct-2007:  doesn't the declaration of
      // monotonicity here assume sort-based aggregation at
      // the physical level?

      // Tell bb which of group columns are sorted.
      bb.columnMonotonicities.clear();
      for (SqlNode groupItem : groupList) {
        bb.columnMonotonicities.add(
            bb.scope.getMonotonicity(groupItem));
      }

      // Add the aggregator
      bb.setRoot(
          createAggregate(bb, r.groupSet, r.groupSets.asList(),
              aggConverter.aggCalls), false);
      bb.mapRootRelToFieldProjection.put(bb.root(), r.groupExprProjection);

      // Replace sub-queries in having here and modify having to use
      // the replaced expressions
      if (having != null) {
        SqlNode newHaving = pushDownNotForIn(bb.scope, having);
        replaceSubQueries(bb, newHaving, RelOptUtil.Logic.UNKNOWN_AS_FALSE);
        havingExpr = bb.convertExpression(newHaving);
      } else {
        havingExpr = relBuilder.literal(true);
      }

      // Now convert the other sub-queries in the select list.
      // This needs to be done separately from the sub-query inside
      // any aggregate in the select list, and after the aggregate rel
      // is allocated.
      replaceSubQueries(bb, selectList, RelOptUtil.Logic.TRUE_FALSE_UNKNOWN,
          SqlImplementor.Clause.SELECT);

      // Now sub-queries in the entire select list have been converted.
      // Convert the select expressions to get the final list to be
      // projected.
      int k = 0;

      // For select expressions, use the field names previously assigned
      // by the validator. If we derive afresh, we might generate names
      // like "EXPR$2" that don't match the names generated by the
      // validator. This is especially the case when there are system
      // fields; system fields appear in the relnode's rowtype but do not
      // (yet) appear in the validator type.
      final SelectScope selectScope =
          SqlValidatorUtil.getEnclosingSelectScope(bb.scope);
      assert selectScope != null;
      final SqlValidatorNamespace selectNamespace = getNamespaceOrNull(selectScope.getNode());
      assert selectNamespace != null : "selectNamespace must not be null for " + selectScope;
      final List<String> names =
          selectNamespace.getRowType().getFieldNames();
      int sysFieldCount = selectList.size() - names.size();
      for (SqlNode expr : selectList) {
        projects.add(bb.convertExpression(expr),
            k < sysFieldCount
                ? SqlValidatorUtil.alias(expr, k++)
                : names.get(k++ - sysFieldCount));
      }

      for (SqlNode expr : orderExprList) {
        projects.add(bb.convertExpression(expr),
            SqlValidatorUtil.alias(expr, k++));
      }
    } finally {
      bb.agg = null;
    }

    // implement HAVING (we have already checked that it is non-trivial)
    relBuilder.push(bb.root());
    // Set the correlation variables used in this sub-query to the filter node,
    // same logic is being used for the filter generated in where clause.
    Set<CorrelationId> variableSet = new HashSet<>();
    RexSubQuery subQ = RexUtil.SubQueryFinder.find(havingExpr);
    if (subQ != null) {
      CorrelationUse p = getCorrelationUse(bb, subQ.rel);
      if (p != null) {
        variableSet.add(p.id);
      }
    }
    relBuilder.filter(variableSet, havingExpr);

    // implement the SELECT list
    relBuilder.project(projects.leftList(), projects.rightList())
        .rename(projects.rightList());
    bb.setRoot(relBuilder.build(), false);

    // Tell bb which of group columns are sorted.
    bb.columnMonotonicities.clear();
    for (SqlNode selectItem : selectList) {
      bb.columnMonotonicities.add(
          bb.scope.getMonotonicity(selectItem));
    }
  }

  /**
   * Creates an Aggregate.
   *
   * <p>In case the aggregate rel changes the order in which it projects
   * fields, the <code>groupExprProjection</code> parameter is provided, and
   * the implementation of this method may modify it.
   *
   * <p>The <code>sortedCount</code> parameter is the number of expressions
   * known to be monotonic. These expressions must be on the leading edge of
   * the grouping keys. The default implementation of this method ignores this
   * parameter.
   *
   * @param bb       Blackboard
   * @param groupSet Bit set of ordinals of grouping columns
   * @param groupSets Grouping sets
   * @param aggCalls Array of calls to aggregate functions
   * @return LogicalAggregate
   */
  protected RelNode createAggregate(Blackboard bb, ImmutableBitSet groupSet,
      ImmutableList<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls) {
    relBuilder.push(bb.root());
    final RelBuilder.GroupKey groupKey =
        relBuilder.groupKey(groupSet, groupSets);
    return relBuilder.aggregate(groupKey, aggCalls)
        .build();
  }

  public RexDynamicParam convertDynamicParam(
      final SqlDynamicParam dynamicParam) {
    // REVIEW jvs 8-Jan-2005:  dynamic params may be encountered out of
    // order.  Should probably cross-check with the count from the parser
    // at the end and make sure they all got filled in.  Why doesn't List
    // have a resize() method?!?  Make this a utility.
    while (dynamicParam.getIndex() >= dynamicParamSqlNodes.size()) {
      dynamicParamSqlNodes.add(null);
    }

    dynamicParamSqlNodes.set(
        dynamicParam.getIndex(),
        dynamicParam);
    return rexBuilder.makeDynamicParam(
        getDynamicParamType(dynamicParam.getIndex()),
        dynamicParam.getIndex());
  }

  /**
   * Creates a list of collations required to implement the ORDER BY clause,
   * if there is one. Populates <code>extraOrderExprs</code> with any sort
   * expressions which are not in the select clause.
   *
   * @param bb              Scope within which to resolve identifiers
   * @param select          Select clause. Never null, because we invent a
   *                        dummy SELECT if ORDER BY is applied to a set
   *                        operation (UNION etc.)
   * @param orderList       Order by clause, may be null
   * @param extraOrderExprs Sort expressions which are not in the select
   *                        clause (output)
   * @param collationList   List of collations (output)
   */
  protected void gatherOrderExprs(
      Blackboard bb,
      SqlSelect select,
      @Nullable SqlNodeList orderList,
      List<SqlNode> extraOrderExprs,
      List<RelFieldCollation> collationList) {
    // TODO:  add validation rules to SqlValidator also
    assert bb.root != null : "precondition: child != null";
    assert select != null;
    if (orderList == null) {
      return;
    }

    if (removeSortInSubQuery(bb.top)) {
      SqlNode offset = select.getOffset();
      if ((offset == null
              || (offset instanceof SqlLiteral
                  && Objects.equals(((SqlLiteral) offset).bigDecimalValue(), BigDecimal.ZERO)))
          && select.getFetch() == null) {
        return;
      }
    }

    for (SqlNode orderItem : orderList) {
      collationList.add(
          convertOrderItem(select, orderItem, extraOrderExprs,
              RelFieldCollation.Direction.ASCENDING,
              RelFieldCollation.NullDirection.UNSPECIFIED));
    }
  }

  protected RelFieldCollation convertOrderItem(
      SqlSelect select,
      SqlNode orderItem, List<SqlNode> extraExprs,
      RelFieldCollation.Direction direction,
      RelFieldCollation.NullDirection nullDirection) {
    assert select != null;
    // Handle DESC keyword, e.g. 'select a, b from t order by a desc'.
    switch (orderItem.getKind()) {
    case DESCENDING:
      return convertOrderItem(
          select,
          ((SqlCall) orderItem).operand(0),
          extraExprs,
          RelFieldCollation.Direction.DESCENDING,
          nullDirection);
    case NULLS_FIRST:
      return convertOrderItem(
          select,
          ((SqlCall) orderItem).operand(0),
          extraExprs,
          direction,
          RelFieldCollation.NullDirection.FIRST);
    case NULLS_LAST:
      return convertOrderItem(
          select,
          ((SqlCall) orderItem).operand(0),
          extraExprs,
          direction,
          RelFieldCollation.NullDirection.LAST);
    default:
      break;
    }

    SqlNode converted = validator().expandOrderExpr(select, orderItem);

    switch (nullDirection) {
    case UNSPECIFIED:
      nullDirection = validator().config().defaultNullCollation().last(desc(direction))
          ? RelFieldCollation.NullDirection.LAST
          : RelFieldCollation.NullDirection.FIRST;
      break;
    default:
      break;
    }

    // Scan the select list and order exprs for an identical expression.
    final SelectScope selectScope =
        requireNonNull(validator().getRawSelectScope(select),
            () -> "getRawSelectScope is not found for " + select);
    int ordinal = -1;
    List<SqlNode> expandedSelectList = selectScope.getExpandedSelectList();
    for (SqlNode selectItem : requireNonNull(expandedSelectList, "expandedSelectList")) {
      ++ordinal;
      if (converted.equalsDeep(stripAs(selectItem), Litmus.IGNORE)) {
        return new RelFieldCollation(ordinal, direction, nullDirection);
      }
    }

    for (SqlNode extraExpr : extraExprs) {
      ++ordinal;
      if (converted.equalsDeep(extraExpr, Litmus.IGNORE)) {
        return new RelFieldCollation(ordinal, direction, nullDirection);
      }
    }

    // TODO:  handle collation sequence
    // TODO: flag expressions as non-standard

    extraExprs.add(converted);
    return new RelFieldCollation(ordinal + 1, direction, nullDirection);
  }

  private static boolean desc(RelFieldCollation.Direction direction) {
    switch (direction) {
    case DESCENDING:
    case STRICTLY_DESCENDING:
      return true;
    default:
      return false;
    }
  }

  @Deprecated // to be removed before 2.0
  protected boolean enableDecorrelation() {
    // disable sub-query decorrelation when needed.
    // e.g. if outer joins are not supported.
    return config.isDecorrelationEnabled();
  }

  protected RelNode decorrelateQuery(RelNode rootRel) {
    return RelDecorrelator.decorrelateQuery(rootRel, relBuilder);
  }

  /**
   * Returns whether to trim unused fields as part of the conversion process.
   *
   * @return Whether to trim unused fields
   */
  @Deprecated // to be removed before 2.0
  public boolean isTrimUnusedFields() {
    return config.isTrimUnusedFields();
  }

  /**
   * Recursively converts a query to a relational expression.
   *
   * @param query         Query
   * @param top           Whether this query is the top-level query of the
   *                      statement
   * @param targetRowType Target row type, or null
   * @return Relational expression
   */
  // 将 AST（抽象语法树 SqlNode）转换为关系代数树（RelNode）的核心路由分发方法。
  // 通过一种典型的“工厂/策略模式”，根据不同类型的 SQL 节点，调用对应的专用转换函数进行递归处理。
  protected RelRoot convertQueryRecursive(
      // 当前需要被转换的 SQL 节点。
      // 随着递归的深入，它不仅可以是顶层的整个查询，也可以是子查询、WITH 表达式中的片段或集合操作（如 UNION）的一侧分支。
      SqlNode query,
      // 标记当前处理的 query 是否为最顶层（顶级）的 SQL 语句。
      boolean top,
      // 期望的预期目标行类型（允许为 null）。
      @Nullable RelDataType targetRowType) {
    final SqlKind kind = query.getKind();
    switch (kind) {
    // 处理 SELECT 查询
    case SELECT:
      return RelRoot.of(convertSelect((SqlSelect) query, top), kind);
    // 处理 INSERT 语句
    case INSERT:
      return RelRoot.of(convertInsert((SqlInsert) query), kind);
    // 处理 DELETE 语句
    case DELETE:
      return RelRoot.of(convertDelete((SqlDelete) query), kind);
    // 处理 UPDATE 语句
    case UPDATE:
      return RelRoot.of(convertUpdate((SqlUpdate) query), kind);
    // 处理 MERGE 语句
    case MERGE:
      return RelRoot.of(convertMerge((SqlMerge) query), kind);
    // 处理集合操作 (UNION, INTERSECT, EXCEPT)
    // 这三种操作都属于集合操作，它们在 AST 中都是 SqlCall。统一路由到 convertSetOp 方法，在内部会分别转换为 LogicalUnion、LogicalIntersect 或 LogicalMinus 算子。
    case UNION:
    case INTERSECT:
    case EXCEPT:
      return RelRoot.of(convertSetOp((SqlCall) query), kind);
    // 处理 CTE (WITH 表达式)
    case WITH:
      return convertWith((SqlWith) query, top);
    // 处理 VALUES 表达式
    case VALUES:
      return RelRoot.of(convertValues((SqlCall) query, targetRowType), kind);
    default:
      throw new AssertionError("not a query: " + query);
    }
  }

  private RelNode createUnion(SqlCall call,
      RelNode left,
      RelNode right) {
    SqlValidatorNamespace nameSpace = this.validator().getNamespace(call);
    boolean all = all(call);
    if (nameSpace != null) {
      SqlNode enclosingNode = nameSpace.getEnclosingNode();
      if (enclosingNode != null) {
        String name = "";
        if (enclosingNode.getKind() == SqlKind.WITH_ITEM) {
          name = ((SqlWithItem) enclosingNode).name.getSimple();
        }
        if (RelOptUtil.findTable(right, name) != null) {
          return this.relBuilder.
              push(left).
              push(right).
              repeatUnion(name, all).
              build();
        }
      }
    }
    return LogicalUnion.create(ImmutableList.of(left, right), all);
  }

  /**
   * Converts a set operation (UNION, INTERSECT, MINUS) into relational
   * expressions.
   *
   * @param call Call to set operator
   * @return Relational expression
   */
  protected RelNode convertSetOp(SqlCall call) {
    final RelNode left =
        convertQueryRecursive(call.operand(0), false, null).project();
    final RelNode right =
        convertQueryRecursive(call.operand(1), false, null).project();
    switch (call.getKind()) {
    case UNION:
      return createUnion(call, left, right);

    case INTERSECT:
      return LogicalIntersect.create(ImmutableList.of(left, right), all(call));

    case EXCEPT:
      return LogicalMinus.create(ImmutableList.of(left, right), all(call));

    default:
      throw Util.unexpected(call.getKind());
    }
  }

  private static boolean all(SqlCall call) {
    return ((SqlSetOperator) call.getOperator()).isAll();
  }

  protected RelNode convertInsert(SqlInsert call) {
    RelOptTable targetTable = getTargetTable(call);

    callStack.push(call);

    final RelDataType targetRowType =
        validator().getValidatedNodeType(call);
    assert targetRowType != null;
    RelNode sourceRel =
        convertQueryRecursive(call.getSource(), true, targetRowType).project();
    RelNode massagedRel = convertColumnList(call, sourceRel);

    callStack.pop();

    return createModify(targetTable, massagedRel);
  }

  /** Creates a relational expression to modify a table or modifiable view. */
  private RelNode createModify(RelOptTable targetTable, RelNode source) {
    final ModifiableTable modifiableTable =
        targetTable.unwrap(ModifiableTable.class);
    if (modifiableTable != null
        && modifiableTable == targetTable.unwrap(Table.class)) {
      return modifiableTable.toModificationRel(cluster, targetTable,
          catalogReader, source, LogicalTableModify.Operation.INSERT, null,
          null, false);
    }
    final ModifiableView modifiableView =
        targetTable.unwrap(ModifiableView.class);
    if (modifiableView != null) {
      final Table delegateTable = modifiableView.getTable();
      final RelDataType delegateRowType = delegateTable.getRowType(typeFactory);
      final RelOptTable delegateRelOptTable =
          RelOptTableImpl.create(null, delegateRowType, delegateTable,
              modifiableView.getTablePath());
      final RelNode newSource =
          createSource(targetTable, source, modifiableView, delegateRowType);
      return createModify(delegateRelOptTable, newSource);
    }
    return LogicalTableModify.create(targetTable, catalogReader, source,
        LogicalTableModify.Operation.INSERT, null, null, false);
  }

  /** Wraps a relational expression in the projects and filters implied by
   * a {@link ModifiableView}.
   *
   * <p>The input relational expression is suitable for inserting into the view,
   * and the returned relational expression is suitable for inserting into its
   * delegate table.
   *
   * <p>In principle, the delegate table of a view might be another modifiable
   * view, and if so, the process can be repeated. */
  private RelNode createSource(RelOptTable targetTable, RelNode source,
      ModifiableView modifiableView, RelDataType delegateRowType) {
    final ImmutableIntList mapping = modifiableView.getColumnMapping();
    assert mapping.size() == targetTable.getRowType().getFieldCount();

    // For columns represented in the mapping, the expression is just a field
    // reference.
    final Map<Integer, RexNode> projectMap = new HashMap<>();
    final List<RexNode> filters = new ArrayList<>();
    for (int i = 0; i < mapping.size(); i++) {
      int target = mapping.get(i);
      if (target >= 0) {
        projectMap.put(target, RexInputRef.of(i, source.getRowType()));
      }
    }

    // For columns that are not in the mapping, and have a constraint of the
    // form "column = value", the expression is the literal "value".
    //
    // If a column has multiple constraints, the extra ones will become a
    // filter.
    final RexNode constraint =
        modifiableView.getConstraint(rexBuilder, delegateRowType);
    RelOptUtil.inferViewPredicates(projectMap, filters, constraint);
    final PairList<RexNode, String> projects = PairList.of();
    for (RelDataTypeField field : delegateRowType.getFieldList()) {
      RexNode node = projectMap.get(field.getIndex());
      if (node == null) {
        node = rexBuilder.makeNullLiteral(field.getType());
      }
      projects.add(rexBuilder.ensureType(field.getType(), node, false),
          field.getName());
    }

    return relBuilder.push(source)
        .projectNamed(projects.leftList(), projects.rightList(), false)
        .filter(filters)
        .build();
  }

  private RelOptTable.ToRelContext createToRelContext(List<RelHint> hints) {
    return ViewExpanders.toRelContext(viewExpander, cluster, hints);
  }


  // 将优化器层面的物理表元数据对象（RelOptTable）正式实例化转换为一个关系代数节点（RelNode，通常是 TableScan 算子），
  // 并专门处理表中可能包含的“虚拟生成列（Virtual Generated Columns）”或默认值扩展。
  public RelNode toRel(
      // 待转换的优化器表元数据对象。它封装了该物理表的 Schema、列类型、全限定名以及将其转换为关系算子的底层实现。
      final RelOptTable table,
      // 从 SQL 中解析出来的 SQL 提示（Hints） 列表（例如 /*+ BROADCAST(t1) */ 或特定的索引提示）。这些提示会在建表扫描时被向下传递，指导算子生成。
      final List<RelHint> hints) {
    // 调用 RelOptTable 自身的 toRel 接口，把元数据表真正具象化为一个底层的扫描算子 scan（通常是 LogicalTableScan 或绑定了特定存储引擎的 Scan 算子）
    final RelNode scan = table.toRel(createToRelContext(hints));
    // 尝试从 table 对象中解包获取 InitializerExpressionFactory（初始化表达式工厂）。
    final InitializerExpressionFactory ief =
        table.maybeUnwrap(InitializerExpressionFactory.class)
            .orElse(NullInitializerExpressionFactory.INSTANCE);
    // 检测是否存在虚拟生成列（Virtual Fields）
    // 遍历这张表的所有字段，通过工厂检查是否存在任何一列的生成策略为 ColumnStrategy.VIRTUAL。
    // 什么是虚拟列？：类似于 MySQL 或 Oracle 中的 GENERATED ALWAYS AS (c1 + c2) VIRTUAL。
    // 这类列不在底层存储中实际存在，必须在每次读取该表时，在引擎上方通过表达式动态计算出来。
    boolean hasVirtualFields = table.getRowType()
        .getFieldList().stream()
        .anyMatch(f -> ief.generationStrategy(table, f.getIndex()) == ColumnStrategy.VIRTUAL);
    // 核心分支：当表中包含虚拟列时（执行 Project 补齐与展开）
    if (hasVirtualFields) {
      final RexNode sourceRef = rexBuilder.makeRangeReference(scan);
      // 为虚拟列的计算环境构建一个专用的小黑板（Blackboard）
      final Blackboard bb =
          createInsertBlackboard(table, sourceRef,
              table.getRowType().getFieldNames());
      // 初始化一个空的行表达式列表 list，准备构建上方 Project 算子的输出投影列。
      // 然后开始循环处理表中的每一个字段 f，获取其列策略 strategy。
      final List<RexNode> list = new ArrayList<>();
      for (RelDataTypeField f : table.getRowType().getFieldList()) {
        final ColumnStrategy strategy =
            ief.generationStrategy(table, f.getIndex());
        // 如果当前列是虚拟列，调用工厂的 newColumnDefaultValue 方法。
        // 它会读取建表时指定的虚拟列算式（例如 c1 + c2），并借助刚才创建的小黑板 bb，将其转化为优化器可识别的行表达式（RexCall 等），追加到列表中
        switch (strategy) {
        case VIRTUAL:
          list.add(ief.newColumnDefaultValue(table, f.getIndex(), bb));
          break;
        // 如果是普通物理列，不需要计算，直接调用 rexBuilder.makeInputRef 创建一个指向下方 scan 对应物理列的指针引用（RexInputRef）。
        default:
          list.add(
              rexBuilder.makeInputRef(scan,
                  RelOptTableImpl.realOrdinal(table, f.getIndex())));
        }
      }
      // 利用关系表达式建造器 relBuilder，将底层的 scan 算子压栈，然后在其上方叠加刚刚拼装好、包含了虚拟列计算公式的 project(list) 投影算子，
      // 最后 build() 实例化出来这棵包含 Project -> TableScan 的局部新树。
      relBuilder.push(scan);
      relBuilder.project(list);
      final RelNode project = relBuilder.build();
      // 检查初始化工厂是否注册了后置拦截挂钩。如果存在（postConversionHook != null），则把当前组装好的 project 算子和上下文交给钩子做最后的加工修饰并返回；
      // 否则，直接返回组装好虚拟列表达式的 project 算子。
      BiFunction<InitializerContext, RelNode, RelNode> postConversionHook =
          ief.postExpressionConversionHook();
      if (postConversionHook != null) {
        return postConversionHook.apply(bb, project);
      } else {
        return project;
      }
    }

    return scan;
  }

  protected RelOptTable getTargetTable(SqlNode call) {
    final SqlValidatorNamespace targetNs = getNamespace(call);
    SqlValidatorNamespace namespace;
    if (targetNs.isWrapperFor(SqlValidatorImpl.DmlNamespace.class)) {
      namespace = targetNs.unwrap(SqlValidatorImpl.DmlNamespace.class);
    } else {
      namespace = targetNs.resolve();
    }
    RelOptTable table = SqlValidatorUtil.getRelOptTable(namespace, catalogReader, null, null);
    return requireNonNull(table, "no table found for " + call);
  }

  /**
   * Creates a source for an INSERT statement.
   *
   * <p>If the column list is not specified, source expressions match target
   * columns in order.
   *
   * <p>If the column list is specified, Source expressions are mapped to
   * target columns by name via targetColumnList, and may not cover the entire
   * target table. So, we'll make up a full row, using a combination of
   * default values and the source expressions provided.
   *
   * @param call      Insert expression
   * @param source Source relational expression
   * @return Converted INSERT statement
   */
  protected RelNode convertColumnList(final SqlInsert call, RelNode source) {
    RelDataType sourceRowType = source.getRowType();
    final RexNode sourceRef =
        rexBuilder.makeRangeReference(sourceRowType, 0, false);
    final List<String> targetColumnNames = new ArrayList<>();
    final List<RexNode> columnExprs = new ArrayList<>();
    collectInsertTargets(call, sourceRef, targetColumnNames, columnExprs);

    final RelOptTable targetTable = getTargetTable(call);
    final RelDataType targetRowType = RelOptTableImpl.realRowType(targetTable);
    final List<RelDataTypeField> targetFields = targetRowType.getFieldList();
    final List<@Nullable RexNode> sourceExps =
        new ArrayList<>(
            Collections.nCopies(targetFields.size(), null));
    final List<@Nullable String> fieldNames =
        new ArrayList<>(
            Collections.nCopies(targetFields.size(), null));

    final InitializerExpressionFactory initializerFactory =
        getInitializerFactory(getNamespace(call).getTable());

    // Walk the name list and place the associated value in the
    // expression list according to the ordinal value returned from
    // the table construct, leaving nulls in the list for columns
    // that are not referenced.
    final SqlNameMatcher nameMatcher = catalogReader.nameMatcher();
    for (Pair<String, RexNode> p : Pair.zip(targetColumnNames, columnExprs)) {
      RelDataTypeField field = nameMatcher.field(targetRowType, p.left);
      assert field != null : "column " + p.left + " not found";
      sourceExps.set(field.getIndex(), p.right);
    }

    // Lazily create a blackboard that contains all non-generated columns.
    final Supplier<Blackboard> bb = () ->
        createInsertBlackboard(targetTable, sourceRef, targetColumnNames);

    // Walk the expression list and get default values for any columns
    // that were not supplied in the statement. Get field names too.
    for (int i = 0; i < targetFields.size(); ++i) {
      final RelDataTypeField field = targetFields.get(i);
      final String fieldName = field.getName();
      fieldNames.set(i, fieldName);
      RexNode sourceExpression = sourceExps.get(i);
      if (sourceExpression == null
          || sourceExpression.getKind() == SqlKind.DEFAULT) {
        sourceExpression =
            initializerFactory.newColumnDefaultValue(targetTable, i, bb.get());
        // bare nulls are dangerous in the wrong hands
        sourceExpression =
            castNullLiteralIfNeeded(sourceExpression, field.getType());

        sourceExps.set(i, sourceExpression);
      }
    }

    // sourceExps should not contain nulls (see the loop above)
    @SuppressWarnings("assignment.type.incompatible")
    List<RexNode> nonNullExprs = sourceExps;

    return relBuilder.push(source)
        .projectNamed(nonNullExprs, fieldNames, false)
        .build();
  }

  /** Creates a blackboard for translating the expressions of generated columns
   * in an INSERT statement. */
  private Blackboard createInsertBlackboard(RelOptTable targetTable,
      RexNode sourceRef, List<String> targetColumnNames) {
    final Map<String, RexNode> nameToNodeMap = new HashMap<>();
    int j = 0;

    // Assign expressions for non-generated columns.
    final List<ColumnStrategy> strategies = targetTable.getColumnStrategies();
    final List<String> targetFields = targetTable.getRowType().getFieldNames();
    for (String targetColumnName : targetColumnNames) {
      final int i = targetFields.indexOf(targetColumnName);
      switch (strategies.get(i)) {
      case STORED:
      case VIRTUAL:
        break;
      default:
        nameToNodeMap.put(targetColumnName,
            rexBuilder.makeFieldAccess(sourceRef, j++));
      }
    }
    return createBlackboard(validator().getEmptyScope(), nameToNodeMap, false);
  }

  private static InitializerExpressionFactory getInitializerFactory(
      @Nullable SqlValidatorTable validatorTable) {
    // We might unwrap a null instead of a InitializerExpressionFactory.
    final Table table = unwrap(validatorTable, Table.class);
    if (table != null) {
      InitializerExpressionFactory f =
          unwrap(table, InitializerExpressionFactory.class);
      if (f != null) {
        return f;
      }
    }
    return NullInitializerExpressionFactory.INSTANCE;
  }

  private static <T extends Object> @Nullable T unwrap(@Nullable Object o, Class<T> clazz) {
    if (o instanceof Wrapper) {
      return ((Wrapper) o).unwrap(clazz);
    }
    return null;
  }

  private RexNode castNullLiteralIfNeeded(RexNode node, RelDataType type) {
    if (!RexLiteral.isNullLiteral(node)) {
      return node;
    }
    return rexBuilder.makeCast(type, node);
  }

  /**
   * Given an INSERT statement, collects the list of names to be populated and
   * the expressions to put in them.
   *
   * @param call              Insert statement
   * @param sourceRef         Expression representing a row from the source
   *                          relational expression
   * @param targetColumnNames List of target column names, to be populated
   * @param columnExprs       List of expressions, to be populated
   */
  protected void collectInsertTargets(
      SqlInsert call,
      final RexNode sourceRef,
      final List<String> targetColumnNames,
      List<RexNode> columnExprs) {
    final RelOptTable targetTable = getTargetTable(call);
    final RelDataType tableRowType = targetTable.getRowType();
    SqlNodeList targetColumnList = call.getTargetColumnList();
    if (targetColumnList == null) {
      if (validator().config().conformance().isInsertSubsetColumnsAllowed()) {
        final RelDataType targetRowType =
            typeFactory.createStructType(
                tableRowType.getFieldList()
                    .subList(0, sourceRef.getType().getFieldCount()));
        targetColumnNames.addAll(targetRowType.getFieldNames());
      } else {
        targetColumnNames.addAll(tableRowType.getFieldNames());
      }
    } else {
      for (int i = 0; i < targetColumnList.size(); i++) {
        SqlIdentifier id = (SqlIdentifier) targetColumnList.get(i);
        RelDataTypeField field =
            SqlValidatorUtil.getTargetField(
                tableRowType, typeFactory, id, catalogReader, targetTable);
        assert field != null : "column " + id.toString() + " not found";
        targetColumnNames.add(field.getName());
      }
    }

    final Blackboard bb =
        createInsertBlackboard(targetTable, sourceRef, targetColumnNames);

    // Next, assign expressions for generated columns.
    final List<ColumnStrategy> strategies = targetTable.getColumnStrategies();
    for (String columnName : targetColumnNames) {
      final int i = tableRowType.getFieldNames().indexOf(columnName);
      final RexNode expr;
      switch (strategies.get(i)) {
      case STORED:
        final InitializerExpressionFactory f =
            targetTable.maybeUnwrap(InitializerExpressionFactory.class)
                .orElse(NullInitializerExpressionFactory.INSTANCE);
        expr = f.newColumnDefaultValue(targetTable, i, bb);
        break;
      case VIRTUAL:
        expr = null;
        break;
      default:
        expr = requireNonNull(bb.nameToNodeMap, "nameToNodeMap")
            .get(columnName);
      }
      // expr is nullable, however, all the nulls will be removed in the loop below
      columnExprs.add(castNonNull(expr));
    }

    // Remove virtual columns from the list.
    for (int i = 0; i < targetColumnNames.size(); i++) {
      if (columnExprs.get(i) == null) {
        columnExprs.remove(i);
        targetColumnNames.remove(i);
        --i;
      }
    }
  }

  private RelNode convertDelete(SqlDelete call) {
    RelOptTable targetTable = getTargetTable(call);
    final SqlSelect sourceSelect =
        requireNonNull(call.getSourceSelect(),
            () -> "sourceSelect for " + call);
    RelNode sourceRel = convertSelect(sourceSelect, false);
    return LogicalTableModify.create(targetTable, catalogReader, sourceRel,
        LogicalTableModify.Operation.DELETE, null, null, false);
  }

  private RelNode convertUpdate(SqlUpdate call) {
    final SqlSelect sourceSelect =
        requireNonNull(call.getSourceSelect(),
            () -> "sourceSelect for " + call);
    final SqlValidatorScope scope = validator().getWhereScope(sourceSelect);
    Blackboard bb = createBlackboard(scope, null, false);

    replaceSubQueries(bb, call, RelOptUtil.Logic.TRUE_FALSE_UNKNOWN);

    RelOptTable targetTable = getTargetTable(call);

    // convert update column list from SqlIdentifier to String
    final List<String> targetColumnNameList = new ArrayList<>();
    final RelDataType targetRowType = targetTable.getRowType();
    for (SqlNode node : call.getTargetColumnList()) {
      SqlIdentifier id = (SqlIdentifier) node;
      RelDataTypeField field =
          SqlValidatorUtil.getTargetField(
              targetRowType, typeFactory, id, catalogReader, targetTable);
      assert field != null : "column " + id + " not found";
      targetColumnNameList.add(field.getName());
    }

    RelNode sourceRel = convertSelect(sourceSelect, false);

    bb.setRoot(sourceRel, false);
    ImmutableList.Builder<RexNode> rexNodeSourceExpressionListBuilder =
        ImmutableList.builder();
    for (SqlNode n : call.getSourceExpressionList()) {
      RexNode rn = bb.convertExpression(n);
      rexNodeSourceExpressionListBuilder.add(rn);
    }

    return LogicalTableModify.create(targetTable, catalogReader, sourceRel,
        LogicalTableModify.Operation.UPDATE, targetColumnNameList,
        rexNodeSourceExpressionListBuilder.build(), false);
  }

  private RelNode convertMerge(SqlMerge call) {
    RelOptTable targetTable = getTargetTable(call);

    // convert update column list from SqlIdentifier to String
    final List<String> targetColumnNameList = new ArrayList<>();
    final RelDataType targetRowType = targetTable.getRowType();
    SqlUpdate updateCall = call.getUpdateCall();
    if (updateCall != null) {
      for (SqlNode targetColumn : updateCall.getTargetColumnList()) {
        SqlIdentifier id = (SqlIdentifier) targetColumn;
        RelDataTypeField field =
            SqlValidatorUtil.getTargetField(
                targetRowType, typeFactory, id, catalogReader, targetTable);
        assert field != null : "column " + id.toString() + " not found";
        targetColumnNameList.add(field.getName());
      }
    }

    // replace the projection of the source select with a
    // projection that contains the following:
    // 1) the expressions corresponding to the new insert row (if there is
    //    an insert)
    // 2) all columns from the target table (if there is an update)
    // 3) the set expressions in the update call (if there is an update)

    // first, convert the merge's source select to construct the columns
    // from the target table and the set expressions in the update call
    final SqlSelect sourceSelect =
        requireNonNull(call.getSourceSelect(),
            () -> "sourceSelect for " + call);
    RelNode mergeSourceRel = convertSelect(sourceSelect, false);

    // then, convert the insert statement so we can get the insert
    // values expressions
    SqlInsert insertCall = call.getInsertCall();
    int nLevel1Exprs = 0;
    List<RexNode> level1InsertExprs = null;
    List<RexNode> level2InsertExprs = null;
    if (insertCall != null) {
      RelNode insertRel = convertInsert(insertCall);

      // if there are 2 level of projections in the insert source, combine
      // them into a single project; level1 refers to the topmost project;
      // the level1 projection contains references to the level2
      // expressions, except in the case where no target expression was
      // provided, in which case, the expression is the default value for
      // the column; or if the expressions directly map to the source
      // table
      level1InsertExprs =
          ((LogicalProject) insertRel.getInput(0)).getProjects();
      if (insertRel.getInput(0).getInput(0) instanceof LogicalProject) {
        level2InsertExprs =
            ((LogicalProject) insertRel.getInput(0).getInput(0))
                .getProjects();
      }
      nLevel1Exprs = level1InsertExprs.size();
    }

    LogicalJoin join = (LogicalJoin) mergeSourceRel.getInput(0);
    int nSourceFields = join.getLeft().getRowType().getFieldCount();
    final List<RexNode> projects = new ArrayList<>();
    for (int level1Idx = 0; level1Idx < nLevel1Exprs; level1Idx++) {
      requireNonNull(level1InsertExprs, "level1InsertExprs");
      if ((level2InsertExprs != null)
          && (level1InsertExprs.get(level1Idx) instanceof RexInputRef)) {
        int level2Idx =
            ((RexInputRef) level1InsertExprs.get(level1Idx)).getIndex();
        projects.add(level2InsertExprs.get(level2Idx));
      } else {
        projects.add(level1InsertExprs.get(level1Idx));
      }
    }
    if (updateCall != null) {
      final LogicalProject project = (LogicalProject) mergeSourceRel;
      projects.addAll(
          Util.skip(project.getProjects(), nSourceFields));
    }

    relBuilder.push(join)
        .project(projects);

    return LogicalTableModify.create(targetTable, catalogReader,
        relBuilder.build(), LogicalTableModify.Operation.MERGE,
        targetColumnNameList, null, false);
  }

  /**
   * Converts an identifier into an expression in a given scope. For example,
   * the "empno" in "select empno from emp join dept" becomes "emp.empno".
   */
  private RexNode convertIdentifier(
      Blackboard bb,
      SqlIdentifier identifier) {
    // first check for reserved identifiers like CURRENT_USER
    final SqlCall call = bb.getValidator().makeNullaryCall(identifier);
    if (call != null) {
      return bb.convertExpression(call);
    }

    String pv = null;
    if (bb.isPatternVarRef && identifier.names.size() > 1) {
      pv = identifier.names.get(0);
    }

    final SqlQualified qualified = bb.scope.fullyQualify(identifier);
    final Pair<RexNode, @Nullable BiFunction<RexNode, String, RexNode>> e0 =
        bb.lookupExp(qualified);
    RexNode e = e0.left;
    for (String name : qualified.suffix()) {
      if (e == e0.left && e0.right != null) {
        e = e0.right.apply(e, name);
      } else {
        final boolean caseSensitive = true; // name already fully-qualified
        if (identifier.isStar() && bb.scope instanceof MatchRecognizeScope) {
          e = rexBuilder.makeFieldAccess(e, 0);
        } else {
          e = rexBuilder.makeFieldAccess(e, name, caseSensitive);
        }
      }
    }
    if (e instanceof RexInputRef) {
      // adjust the type to account for nulls introduced by outer joins
      e = adjustInputRef(bb, (RexInputRef) e);
      if (pv != null) {
        e = RexPatternFieldRef.of(pv, (RexInputRef) e);
      }
    }

    if (e0.left instanceof RexCorrelVariable) {
      assert e instanceof RexFieldAccess;
      final RexNode prev =
          bb.mapCorrelateToRex.put(((RexCorrelVariable) e0.left).id,
              (RexFieldAccess) e);
      assert prev == null;
    }
    return e;
  }

  /**
   * Adjusts the type of a reference to an input field to account for nulls
   * introduced by outer joins; and adjusts the offset to match the physical
   * implementation.
   *
   * @param bb       Blackboard
   * @param inputRef Input ref
   * @return Adjusted input ref
   */
  protected RexNode adjustInputRef(
      Blackboard bb,
      RexInputRef inputRef) {
    RelDataTypeField field = bb.getRootField(inputRef);
    if (field != null) {
      if (!SqlTypeUtil.equalSansNullability(typeFactory,
          field.getType(), inputRef.getType())) {
        return inputRef;
      }
      return rexBuilder.makeInputRef(
          field.getType(),
          inputRef.getIndex());
    }
    return inputRef;
  }

  /**
   * Converts a row constructor into a relational expression.
   *
   * @param bb             Blackboard
   * @param rowConstructor Row constructor expression
   * @return Relational expression which returns a single row.
   */
  private RelNode convertRowConstructor(
      Blackboard bb,
      SqlCall rowConstructor) {
    checkArgument(isRowConstructor(rowConstructor));
    final List<SqlNode> operands = rowConstructor.getOperandList();
    return convertMultisets(operands, bb);
  }

  private RelNode convertCursor(Blackboard bb, SubQuery subQuery) {
    final SqlCall cursorCall = (SqlCall) subQuery.node;
    assert cursorCall.operandCount() == 1;
    SqlNode query = cursorCall.operand(0);
    RelNode converted = convertQuery(query, false, false).rel;
    int iCursor = bb.cursors.size();
    bb.cursors.add(converted);
    subQuery.expr =
        new RexInputRef(
            iCursor,
            converted.getRowType());
    return converted;
  }

  private RelNode convertMultisets(final List<SqlNode> operands,
      Blackboard bb) {
    // NOTE: Wael 2/04/05: this implementation is not the most efficient in
    // terms of planning since it generates XOs that can be reduced.
    final List<Object> joinList = new ArrayList<>();
    List<SqlNode> lastList = new ArrayList<>();
    for (int i = 0; i < operands.size(); i++) {
      SqlNode operand = operands.get(i);
      if (!(operand instanceof SqlCall)) {
        lastList.add(operand);
        continue;
      }

      final SqlCall call = (SqlCall) operand;
      final RelNode input;
      switch (call.getKind()) {
      case MULTISET_VALUE_CONSTRUCTOR:
      case ARRAY_VALUE_CONSTRUCTOR:
        final SqlNodeList list =
            new SqlNodeList(call.getOperandList(), call.getParserPosition());
        CollectNamespace nss = getNamespaceOrNull(call);
        Blackboard usedBb;
        if (null != nss) {
          usedBb = createBlackboard(nss.getScope(), null, false);
        } else {
          usedBb =
              createBlackboard(new ListScope(bb.scope) {
                @Override public SqlNode getNode() {
                  return call;
                }
              }, null, false);
        }
        RelDataType multisetType = validator().getValidatedNodeType(call);
        validator().setValidatedNodeType(list,
            requireNonNull(multisetType.getComponentType(),
                () -> "componentType for multisetType " + multisetType));
        input = convertQueryOrInList(usedBb, list, null);
        break;
      case MULTISET_QUERY_CONSTRUCTOR:
      case ARRAY_QUERY_CONSTRUCTOR:
      case MAP_QUERY_CONSTRUCTOR:
        final RelRoot root = convertQuery(call.operand(0), false, true);
        input = root.rel;
        break;
      default:
        lastList.add(operand);
        continue;
      }

      if (lastList.size() > 0) {
        joinList.add(lastList);
      }
      lastList = new ArrayList<>();
      relBuilder.push(
          Collect.create(requireNonNull(input, "input"),
              call.getKind(), SqlValidatorUtil.alias(call, i)));
      joinList.add(relBuilder.build());
    }

    if (joinList.size() == 0) {
      joinList.add(lastList);
    }

    for (int i = 0; i < joinList.size(); i++) {
      Object o = joinList.get(i);
      if (o instanceof List) {
        @SuppressWarnings("unchecked")
        List<SqlNode> projectList = (List<SqlNode>) o;
        final List<RexNode> selectList = new ArrayList<>();
        final List<String> fieldNameList = new ArrayList<>();
        for (int j = 0; j < projectList.size(); j++) {
          SqlNode operand = projectList.get(j);
          selectList.add(bb.convertExpression(operand));

          // REVIEW angel 5-June-2005: Use deriveAliasFromOrdinal
          // instead of deriveAlias to match field names from
          // SqlRowOperator. Otherwise, get error   Type
          // 'RecordType(INTEGER EMPNO)' has no field 'EXPR$0' when
          // doing   select * from unnest(     select multiset[empno]
          // from sales.emps);

          fieldNameList.add(SqlUtil.deriveAliasFromOrdinal(j));
        }

        relBuilder.push(LogicalValues.createOneRow(cluster))
            .projectNamed(selectList, fieldNameList, true);

        joinList.set(i, relBuilder.build());
      }
    }

    RelNode ret = (RelNode) joinList.get(0);
    for (int i = 1; i < joinList.size(); i++) {
      RelNode relNode = (RelNode) joinList.get(i);
      ret =
          RelFactories.DEFAULT_JOIN_FACTORY.createJoin(
              ret,
              relNode,
              ImmutableList.of(),
              rexBuilder.makeLiteral(true),
              ImmutableSet.of(),
              JoinRelType.INNER,
              false);
    }
    return ret;
  }

  private void convertSelectList(
      Blackboard bb,
      SqlSelect select,
      List<SqlNode> orderList) {
    SqlNodeList selectList = select.getSelectList();
    selectList = validator().expandStar(selectList, select, false);

    replaceSubQueries(bb, selectList, RelOptUtil.Logic.TRUE_FALSE_UNKNOWN);
    replaceSubQueries(bb, new SqlNodeList(orderList, SqlParserPos.ZERO),
        RelOptUtil.Logic.TRUE_FALSE_UNKNOWN);

    List<String> fieldNames = new ArrayList<>();
    final List<RexNode> exprs = new ArrayList<>();
    final Collection<String> aliases = new TreeSet<>();

    // Project any system fields. (Must be done before regular select items,
    // because offsets may be affected.)
    final List<SqlMonotonicity> columnMonotonicityList = new ArrayList<>();
    extraSelectItems(
        bb,
        select,
        exprs,
        fieldNames,
        aliases,
        columnMonotonicityList);

    // Project select clause.
    int i = -1;
    for (SqlNode expr : selectList) {
      ++i;
      exprs.add(bb.convertExpression(expr));
      fieldNames.add(deriveAlias(expr, aliases, i));
    }

    // Project extra fields for sorting.
    for (SqlNode expr : orderList) {
      ++i;
      SqlNode expr2 = validator().expandOrderExpr(select, expr);
      exprs.add(bb.convertExpression(expr2));
      fieldNames.add(deriveAlias(expr, aliases, i));
    }

    fieldNames =
        SqlValidatorUtil.uniquify(fieldNames,
            catalogReader.nameMatcher().isCaseSensitive());

    relBuilder.push(bb.root())
        .projectNamed(exprs, fieldNames, true);

    RelNode project = relBuilder.build();

    final RelNode r;
    final CorrelationUse p = getCorrelationUse(bb, project);
    if (p != null) {
      assert p.r instanceof Project;
      // correlation variables have been normalized in p.r, we should use expressions
      // in p.r instead of the original exprs
      Project project1 = (Project) p.r;
      r = relBuilder.push(bb.root())
          .projectNamed(project1.getProjects(), fieldNames, true, ImmutableSet.of(p.id))
          .build();
    } else {
      r = project;
    }

    bb.setRoot(r, false);

    assert bb.columnMonotonicities.isEmpty();
    bb.columnMonotonicities.addAll(columnMonotonicityList);
    for (SqlNode selectItem : selectList) {
      bb.columnMonotonicities.add(
          selectItem.getMonotonicity(bb.scope));
    }
  }

  /**
   * Adds extra select items. The default implementation adds nothing; derived
   * classes may add columns to exprList, nameList, aliasList and
   * columnMonotonicityList.
   *
   * @param bb                     Blackboard
   * @param select                 Select statement being translated
   * @param exprList               List of expressions in select clause
   * @param nameList               List of names, one per column
   * @param aliasList              Collection of aliases that have been used
   *                               already
   * @param columnMonotonicityList List of monotonicity, one per column
   */
  protected void extraSelectItems(
      Blackboard bb,
      SqlSelect select,
      List<RexNode> exprList,
      List<String> nameList,
      Collection<String> aliasList,
      List<SqlMonotonicity> columnMonotonicityList) {
  }

  private static String deriveAlias(
      final SqlNode node,
      Collection<String> aliases,
      final int ordinal) {
    checkArgument(ordinal >= 0);
    String alias = SqlValidatorUtil.alias(node, ordinal);
    if (aliases.contains(alias)) {
      final String aliasBase = alias;
      for (int j = 0;; j++) {
        alias = aliasBase + j;
        if (!aliases.contains(alias)) {
          break;
        }
      }
    }
    aliases.add(alias);
    return alias;
  }

  private void convertQualify(Blackboard bb, @Nullable SqlNode qualify) {
    if (qualify == null) {
      return;
    }

    final LogicalProject projectionFromSelect =
        requireNonNull((LogicalProject) bb.root, "root");

    // Convert qualify SqlNode to a RexNode
    replaceSubQueries(bb, qualify, RelOptUtil.Logic.UNKNOWN_AS_FALSE);
    final RelNode originalRoot = requireNonNull(bb.root, "root");
    RexNode qualifyRexNode;
    try {
      // Set the root to the input of the project,
      // since QUALIFY might have an expression in the OVER clause
      // that references a column not in the SELECT.
      bb.setRoot(projectionFromSelect.getInput(), false);
      qualifyRexNode = bb.convertExpression(qualify);
    } finally {
      bb.setRoot(originalRoot, false);
    }

    // Check to see if the qualify expression has a referenced expression and
    // do some referencing accordingly
    final RexNode qualifyWithReferencesRexNode =
        qualifyRexNode.accept(
            new DuplicateEliminator(projectionFromSelect.getProjects()));

    // Create a Project with the QUALIFY expression
    if (qualifyWithReferencesRexNode.equals(qualifyRexNode)) {
      // The QUALIFY expression does not depend on any references like so:
      //
      //  SELECT A, B
      //  FROM tbl
      //  QUALIFY WINDOW(C) = 1
      //
      // Meaning we should generate a plan like:
      //  Project(A, B, WINDOW(C) = 1 as QualifyExpression)
      //    TableScan(tbl)
      //
      relBuilder.push(projectionFromSelect.getInput())
          .project(
              append(projectionFromSelect.getProjects(), qualifyRexNode),
              append(projectionFromSelect.getRowType().getFieldNames(),
                  "QualifyExpression"));
    } else {
      // The QUALIFY expression depended on a reference meaning
      // we need to introduce an extra project like so:
      //
      //  SELECT A, B, WINDOW(C) as window_val
      //  FROM tbl
      //  QUALIFY window_val = 1
      //
      // Meaning we should generate a plan like:
      //
      //  Project($0, $1, $2, =($2, 1) as QualifyExpression)
      //    Project(A, B, WINDOW(C) as window_val)
      //      TableScan(tbl)
      //
      // This is a very specific application of Common Subexpression Elimination
      // (CSE), since the window value pops up twice.
      relBuilder.push(requireNonNull(bb.root, "root"))
          .project(
              append(relBuilder.fields(), qualifyWithReferencesRexNode),
              append(relBuilder.peek().getRowType().getFieldNames(),
                  "QualifyExpression"));
    }

    // Filter on that extra column
    relBuilder.filter(Util.last(relBuilder.fields()));

    // Remove that extra column from the projection
    relBuilder.project(
        Util.first(relBuilder.fields(),
            projectionFromSelect.getProjects().size()));

    // Update the root
    bb.setRoot(relBuilder.build(), false);
  }

  /** Eliminates a common sub-expression by looking for a {@link RexNode}
   * in the expressions of a {@link Project}; if found, returns a refIndex
   * instead of the raw node. */
  private static final class DuplicateEliminator extends RexShuttle {
    private final List<RexNode> projects;

    DuplicateEliminator(List<RexNode> projects) {
      this.projects = projects;
    }

    @Override public RexNode visitCall(RexCall call) {
      final int i = projects.indexOf(call);
      if (i >= 0) {
        return new RexInputRef(i, projects.get(i).getType());
      }
      return super.visitCall(call);
    }

    @Override public RexNode visitOver(RexOver over) {
      final int i = projects.indexOf(over);
      if (i >= 0) {
        return new RexInputRef(i, projects.get(i).getType());
      }
      return over;
    }
  }

  /**
   * Converts a WITH sub-query into a relational expression.
   */
  public RelRoot convertWith(SqlWith with, boolean top) {
    return convertQuery(with.body, false, top);
  }

  /**
   * Converts a SELECT statement's parse tree into a relational expression.
   */
  public RelNode convertValues(
      SqlCall values,
      @Nullable RelDataType targetRowType) {
    final SqlValidatorScope scope = validator().getOverScope(values);
    assert scope != null;
    final Blackboard bb = createBlackboard(scope, null, false);
    convertValuesImpl(bb, values, targetRowType);
    return bb.root();
  }

  /**
   * Converts a VALUES clause into a relational expression.
   *
   * <p>Example:
   * <blockquote><pre>{@code
   * INSERT INTO T(x, y)
   * VALUES (1, 2), (2, DEFAULT)
   * }</pre></blockquote>
   *
   * @param bb            Blackboard
   * @param values        Call to SQL VALUES operator
   * @param targetRowType Target row type
   */
  private void convertValuesImpl(
      Blackboard bb,
      SqlCall values,
      @Nullable RelDataType targetRowType) {
    // Attempt direct conversion to LogicalValues; if that fails, deal with
    // fancy stuff like sub-queries below.
    RelNode valuesRel =
        convertRowValues(
            bb,
            values,
            values.getOperandList(),
            true,
            targetRowType);
    if (valuesRel != null) {
      bb.setRoot(valuesRel, true);
      return;
    }

    final SqlCall insertOp = callStack.peek();
    // resolve presence of default params
    boolean processDefaults = (insertOp != null) && containsDefault(values);

    if (targetRowType == null) {
      RelDataType listType = validator().getValidatedNodeType(values);
      targetRowType = SqlTypeUtil.promoteToRowType(typeFactory, listType, null);
    }

    assert insertOp instanceof SqlInsert || !processDefaults
        : "operation: " + insertOp;

    final InitializerExpressionFactory initializerFactory;
    final RelOptTable targetTable;
    final int[] mapping;
    if (processDefaults) {
      requireNonNull(insertOp, "insertOp");
      requireNonNull(targetRowType, "targetRowType");
      targetTable = getTargetTable(insertOp);
      initializerFactory =
          getInitializerFactory(getNamespace(insertOp).getTable());
      mapping = processColumnsMapping(targetRowType, targetTable);
    } else {
      initializerFactory = null;
      targetTable = null;
      mapping = null;
    }

    for (SqlNode rowConstructor : values.getOperandList()) {
      SqlCall newRowConst = (SqlCall) rowConstructor;
      Blackboard tmpBb = createBlackboard(bb.scope, null, false);
      replaceSubQueries(tmpBb, rowConstructor,
          RelOptUtil.Logic.TRUE_FALSE_UNKNOWN);
      final PairList<RexNode, String> exps = PairList.of();
      Ord.forEach(newRowConst.getOperandList(), (operand, i) -> {
        RexNode def;
        if (processDefaults
            && operand.getKind() == SqlKind.DEFAULT
            && requireNonNull(mapping, "mapping")[i] != -1) {
          // obtain expression for appropriate default
          requireNonNull(initializerFactory, "initializerFactory");
          requireNonNull(targetTable, "targetTable");
          def =
              initializerFactory.newColumnDefaultValue(targetTable,
                  mapping[i], bb);
        } else {
          def = tmpBb.convertExpression(operand);
        }
        exps.add(def, SqlValidatorUtil.alias(operand, i));
      });

      RelNode in =
          (null == tmpBb.root)
              ? LogicalValues.createOneRow(cluster)
              : tmpBb.root;
      relBuilder.push(in)
          .project(exps.leftList(), exps.rightList());
    }

    bb.setRoot(
        relBuilder.union(true, values.getOperandList().size())
            .build(),
        true);
  }

  /** Processes names matching between incoming row and appropriate dataset
   * definition. Returns incoming fields positions in relation to dataset
   * representation. */
  private static int[] processColumnsMapping(RelDataType targetRowType,
      RelOptTable targetTable) {
    List<ColumnStrategy> strategies = targetTable.getColumnStrategies();
    List<String> targetFields = targetRowType.getFieldNames();
    List<RelDataTypeField> fields = targetTable.getRowType().getFieldList();

    final int[] mapping = new int[targetFields.size()];
    Arrays.fill(mapping, -1);
    Ord.forEach(fields, (field, i) -> {
      if (strategies.get(i) == ColumnStrategy.DEFAULT) {
        int pos = targetFields.indexOf(field.getName());
        if (pos != -1) {
          mapping[pos] = i;
        }
      }
    });

    if (mapping.length != targetFields.size()) {
      throw new AssertionError("Columns partially mapped; src=" + targetFields
          + ", dest=" + Util.transform(fields, RelDataTypeField::getName));
    }

    return mapping;
  }

  //~ Inner Classes ----------------------------------------------------------

  /**
   * A Tuple to remember all calls to Blackboard.register
   */
  private static class RegisterArgs {
    final RelNode rel;
    final JoinRelType joinType;
    final @Nullable List<RexNode> leftKeys;

    RegisterArgs(RelNode rel, JoinRelType joinType, @Nullable List<RexNode> leftKeys) {
      this.rel = rel;
      this.joinType = joinType;
      this.leftKeys = leftKeys;
    }
  }

  /** Function that can convert a sort specification (expression, direction
   * and null direction) to a target format.
   *
   * @param <R> Target format, such as {@link RexFieldCollation} or
   * {@link RexNode}
   */
  @FunctionalInterface
  interface SortExpressionConverter<R> {
    R convert(SqlNode node, RelFieldCollation.Direction direction,
        RelFieldCollation.NullDirection nullDirection);
  }

  /**
   * Workspace for translating an individual SELECT statement (or sub-SELECT).
   */
  // Blackboard（小黑板）的定位是 单一 SELECT 语句（或子查询）在转换为关系代数时的“临时工作台/状态机”。
  // 在将一个复杂的 SELECT 语法树（SqlNode）翻译为关系算子（RelNode）以及行表达式（RexNode）的过程中，Calcite 需要在一个统一的地方记录当前的转换进度和环境上下文。Blackboard 主要承担以下职责：
  // 持有当前关系表达式树的根节点（root）：随着 FROM、WHERE、SELECT 等子句的逐个解析，小黑板上的 root 节点会被不断地叠加、重构。
  // 管理作用域（scope）与名称解析：在遇到 SqlIdentifier（如列名 emp.deptno）时，负责在当前 SELECT 语句的可见范围内找到对应的物理列索引。
  // 追踪子查询与聚合状态：临时存放 IN、EXISTS 子查询列表，以及在处理 GROUP BY 时跟踪聚合算子和分组表达式。
  protected class Blackboard implements SqlRexContext, SqlVisitor<RexNode>,
      InitializerContext {
    /**
     * Collection of {@link RelNode} objects which correspond to a SELECT
     * statement.
     */
    // 当前 SELECT 语句的命名空间作用域。用于在转换标量表达式时解析表名和列名。
    public final SqlValidatorScope scope;
    // 名字到行表达式的映射表。主要用于在特定场景下（如表达式展开）将某个参数名称直接替换为指定的 RexNode。
    private final @Nullable Map<String, RexNode> nameToNodeMap;
    // 当前正在构建的关系代数表达式树的根节点。随着转换进行，它会从最开始的 LogicalTableScan 逐步演变为包裹了 Filter、Project 后的复杂树。
    public @Nullable RelNode root;
    // 当前作用域下的输入源集合。通常是当前 FROM 子句中各个表或连接产生的关系算子列表。
    private @Nullable List<RelNode> inputs;
    // 存放关联变量 ID（CorrelationId）到行表达式字段访问（RexFieldAccess）的映射，用于处理关联子查询（Correlated Subquery）。
    private final Map<CorrelationId, RexFieldAccess> mapCorrelateToRex =
        new HashMap<>();
    // 记录通过 register 方法注册进来的关系表达式及其参数。在某些子查询重写时需要利用此列表进行重新注册。
    private List<RegisterArgs> registered = new ArrayList<>();
    // 标记当前是否正在引用 MATCH_RECOGNIZE（复杂事件处理）中的模式变量。
    private boolean isPatternVarRef = false;
    // 存放当前查询中涉及的游标（Cursor）表达式对应的关系节点。
    final List<RelNode> cursors = new ArrayList<>();

    /**
     * List of <code>IN</code> and <code>EXISTS</code> nodes inside this
     * <code>SELECT</code> statement (but not inside sub-queries).
     */
    // 收集当前 SELECT 语句内部（不包括更深层子查询）出现的所有 IN 和 EXISTS 子查询表达式。
    private final List<SubQuery> subQueryList = new ArrayList<>();

    /**
     * Workspace for building aggregates.
     */
    // 聚合转换器。
    // 当查询进入聚合模式（有 GROUP BY 或聚合函数）时，该对象不为空，专门用于追踪和映射分组列及聚合函数调用。
    @Nullable AggConverter agg;

    /**
     * When converting window aggregate, we need to know if the window is
     * guaranteed to be non-empty.
     */
    // 窗口函数对象。转换窗口聚合时使用，用来判断当前窗口是否保证非空。
    @Nullable SqlWindow window;

    /**
     * Project the groupby expressions out of the root of this sub-select.
     * Sub-queries can reference group by expressions projected from the
     * "right" to the sub-query.
     */
    // 维护关系算子根节点到字段投影的映射，专门用于解决子查询引用右侧 GROUP BY 表达式时的字段偏移问题。
    private final Map<RelNode, Map<Integer, Integer>> mapRootRelToFieldProjection =
        new HashMap<>();
    // 记录当前各个输出列的单调性（如递增、递减、常数等），用于流式查询或特定优化。
    private final List<SqlMonotonicity> columnMonotonicities =
        new ArrayList<>();
    // 存储当前关联算子中附带的系统字段列表。
    private final List<RelDataTypeField> systemFieldList = new ArrayList<>();
    // 标记该小黑板对应的 SELECT 语句是否是整个 SQL 的最外层查询。
    final boolean top;
    // 初始化表达式工厂，默认使用不提供默认值的空工厂实现。
    private final InitializerExpressionFactory initializerExpressionFactory =
        new NullInitializerExpressionFactory();

    /**
     * Creates a Blackboard.
     *
     * @param scope         Name-resolution scope for expressions validated
     *                      within this query. Can be null if this Blackboard is
     *                      for a leaf node, say
     * @param nameToNodeMap Map which translates the expression to map a
     *                      given parameter into, if translating expressions;
     *                      null otherwise
     * @param top           Whether this is the root of the query
     */
    protected Blackboard(@Nullable SqlValidatorScope scope,
        @Nullable Map<String, RexNode> nameToNodeMap, boolean top) {
      this.scope = requireNonNull(scope, "scope");
      this.nameToNodeMap = nameToNodeMap;
      this.top = top;
    }

    public RelNode root() {
      return requireNonNull(root, "root");
    }

    @Deprecated // to be removed before 2.0
    public SqlValidatorScope scope() {
      return scope;
    }

    public void setPatternVarRef(boolean isVarRef) {
      this.isPatternVarRef = isVarRef;
    }

    public RexNode register(
        RelNode rel,
        JoinRelType joinType) {
      return register(rel, joinType, null);
    }

    /**
     * Registers a relational expression.
     *
     * @param rel               Relational expression
     * @param joinType          Join type
     * @param leftKeys LHS of IN clause, or null for expressions
     *                          other than IN
     * @return Expression with which to refer to the row (or partial row)
     * coming from this relational expression's side of the join
     */
    // 主要用于在当前逻辑树的工作台中，将一个新生成的逻辑算子（rel）合并、注册到当前已有的关系代数树（root）上，并返回能够准确引用新算子字段输出的表达式（RexRangeRef）。
    // 返回值 RexNode（实际为 RexRangeRef）：返回一个范围引用表达式，用于告诉外界：“刚才新加进来的那个 rel 算子，现在它的字段在新合并出来的大树的哪个偏移量（Offset）开始，可以用它直接访问新加的那部分列”。
    public RexNode register(
        // 当前待注册（新入场）的关系表达式节点（例如 FROM 子句中新遇到的一张表，或者一条被展开的 IN / EXISTS 子查询子树）。
        RelNode rel,
        // 新入场的 rel 应该以何种连接方式与当前的 root 进行合并（如 INNER, LEFT, FULL 等）。
        JoinRelType joinType,
        // 主要用于处理 IN 子查询被展开为半连接或外连接（Semi-Join / Left-Join） 时的左侧关联键（Left-Hand Side Keys）。
        // 如果是普通无条件连接（如平铺非关联的 Cross Join）或转换第一张表，则该参数为 null。
        @Nullable List<RexNode> leftKeys) {
      requireNonNull(joinType, "joinType");
      // 将本次注册的参数打包存储到 registered 列表中。
      // 这就像是一个“备忘录”，如果后续发生子查询重写或者需要把这棵树强行重构时，可以通过读取这个备忘录实现重新注册（reRegister）。
      registered.add(new RegisterArgs(rel, joinType, leftKeys));
      // 边界情况：当 Blackboard 的当前树（root）还是空的时候
      if (root == null) {
        assert leftKeys == null : "leftKeys must be null";
        // 直接将这个新入场的 rel 算子指定为小黑板当前查询的根节点 root。
        setRoot(rel, false);
        // 返回一个 RexRangeRef。因为当前树只有这一个算子，所以它的字段在大树中的起始偏移量（offset）是 0。
        return rexBuilder.makeRangeReference(
            root().getRowType(),
            0,
            false);
      }
      // 连接条件与左侧键值对齐处理（核心逻辑 1）
      // 如果 root 已经有值了，说明现在要把新来的 rel 通过 joinType 连接到已有的 root 上。
      final RexNode joinCond;
      // 记录已有旧 root 在执行这次连接前的列总数。
      final int origLeftInputCount = root.getRowType().getFieldCount();
      // 如果携带了左侧关联键（常发生于 IN 子查询展开改写为连接时），说明两边需要建立基于键的等值连接（Equi-Join）。
      if (leftKeys != null) {
        List<RexNode> newLeftInputExprs = new ArrayList<>();
        for (int i = 0; i < origLeftInputCount; i++) {
          // 做投影重构（Project Alignment）。
          // 先将原 root 的所有列引用（RexInputRef）依次捞出来放入 newLeftInputExprs 投影列表中，保证旧字段数据不丢失。
          newLeftInputExprs.add(rexBuilder.makeInputRef(root(), i));
        }

        final List<Integer> leftJoinKeys = new ArrayList<>();
        // 检查 IN 左边的关联表达式 leftKey 是否在当前的投影列表里。
        for (RexNode leftKey : leftKeys) {
          int index = newLeftInputExprs.indexOf(leftKey);
          // 如果当前投影里找不到（index < 0），或者由于是 LEFT JOIN 需要严格隔离字段，就把该 leftKey 作为一个全新生成的计算列追加到 newLeftInputExprs 的末尾。
          if (index < 0 || joinType == JoinRelType.LEFT) {
            index = newLeftInputExprs.size();
            newLeftInputExprs.add(leftKey);
          }
          // 最终记录下左侧用于连接的键在最新投影中的数字索引（index），存入 leftJoinKeys。
          leftJoinKeys.add(index);
        }
        // 用 RelBuilder 在原 root 上方追加一层 LogicalProject。
        // 这个新的左侧输入树 newLeftInput 现在不仅包含了原来的旧字段，还在尾部对齐、补齐了用于做等值连接的 leftKeys。
        RelNode newLeftInput =
            relBuilder.push(root())
                .project(newLeftInputExprs)
                .build();

        // maintain the group by mapping in the new LogicalProject
        // 如果在聚合或特定的 GROUP BY 环境下，将原 root 的字段映射元数据信息平移到新生成的 newLeftInput 节点上，防止后续上下文丢失。
        Map<Integer, Integer> currentProjection = mapRootRelToFieldProjection.get(root());
        if (currentProjection != null) {
          mapRootRelToFieldProjection.put(
              newLeftInput,
              currentProjection);
        }

        // if the original root rel is a leaf rel, the new root should be a leaf.
        // otherwise the field offset will be wrong.
        setRoot(newLeftInput, leaves.remove(root()) != null);

        // right fields appear after the LHS fields.
        // 由于连接后右侧输入 rel 的字段会被拼在新 root 字段的后面，通过计算偏移量算出来右侧表对应的等值连接键的绝对索引位置。
        final int rightOffset = root().getRowType().getFieldCount()
            - newLeftInput.getRowType().getFieldCount();
        final List<Integer> rightKeys =
            Util.range(rightOffset, rightOffset + leftKeys.size());
        // 生成等值连接的条件表达式（例如 newLeftInput.field_X = rel.field_Y）。
        joinCond =
            RelOptUtil.createEquiJoinCondition(newLeftInput, leftJoinKeys,
                rel, rightKeys, rexBuilder);
      } else {
        // 如果没有提供 leftKeys，说明是一个普通交叉连接（Cross Join / ON TRUE）。
        joinCond = rexBuilder.makeLiteral(true);
      }
      // 记录此时最新左侧大树（root）的总列数。新来的 rel 拼上去之后，其字段的起始索引就是 leftFieldCount。
      int leftFieldCount = root().getRowType().getFieldCount();
      // 调用外层核心方法，在当前的 root() 和传入的 rel 之间真正创建出一个 LogicalJoin 算子，并将上面推导出的 joinCond（连接条件）和 joinType 灌进去。
      final RelNode join =
          createJoin(
              this,
              root(),
              rel,
              joinCond,
              joinType);

      setRoot(join, false);
      // 计算并返回字段映射范围引用 (返回值处理)
      if (leftKeys != null
          && joinType == JoinRelType.LEFT) {
        final int leftKeyCount = leftKeys.size();
        int rightFieldLength = rel.getRowType().getFieldCount();
        assert leftKeyCount == rightFieldLength - 1;

        final int rexRangeRefLength = leftKeyCount + rightFieldLength;
        final RelDataTypeFactory.Builder builder = typeFactory.builder();
        for (int i = 0; i < rexRangeRefLength; i++) {
          builder.add(join.getRowType().getFieldList()
              .get(origLeftInputCount + i));
        }

        return rexBuilder.makeRangeReference(builder.build(),
            origLeftInputCount,
            false);
      } else {
        return rexBuilder.makeRangeReference(
            rel.getRowType(),
            leftFieldCount,
            joinType.generatesNullsOnRight());
      }
    }

    /**
     * Re-register the {@code registered} with given root node and
     * return the new root node.
     *
     * @param root The given root, never leaf
     *
     * @return new root after the registration
     */
    // 核心应用场景：
    // 通常在子查询展开（Sub-query Unnesting / Decorrelation）或标量子查询（Scalar Subquery）转换阶段被调用。
    // 当 Calcite 发现一个原本作为标量的子查询可能会返回多行数据、从而违反 SQL 规范时，需要通过引入 SINGLE_VALUE 聚合函数来强行保证数据的标量特性，并将之前所有的注册逻辑在一个新的 root 算子基础之上“重放”一遍。
    // RelNode root：传入的新根节点（通常是由主查询转换出来的基础关系算子树）。该方法会以此节点作为最新的左侧输入基底，重新在其上方拼接之前登记过的其他算子。
    // 返回值 RelNode：返回重新装配完成后的最终大树的根节点。该节点会被作为当前小黑板的最新 root。
    public RelNode reRegister(RelNode root) {
      // 将小黑板当前的根节点强制重置为传入的新 root 节点。
      setRoot(root, false);
      // 把之前通过 register 方法记录下来的所有连接/子查询注册历史（registered 备忘录）备份到 registerCopy 中。
      // 然后把小黑板上的 registered 列表初始化为空，准备接收接下来的“重新注册”。
      List<RegisterArgs> registerCopy = registered;
      registered = new ArrayList<>();
      for (RegisterArgs reg : registerCopy) {
        // 将当前循环处理的这个待合并算子 relNode 压入 Calcite 的关系表达式建造器 relBuilder 的栈顶，准备对其进行加工。
        RelNode relNode = reg.rel;
        relBuilder.push(relNode);
        // 利用元数据查询（Metadata Query）框架，检查当前算子输出的行数是否天生唯一。
        final RelMetadataQuery mq = relBuilder.getCluster().getMetadataQuery();
        final Boolean unique =
            mq.areColumnsUnique(relBuilder.peek(), ImmutableBitSet.of());
        // 如果无法确定结果集唯一，或者明确知道结果集可能返回多行（!unique），则触发内部的防御保护。
        if (unique == null || !unique) {
          // 强行套上一层 SINGLE_VALUE 聚合算子
          // SqlStdOperatorTable.SINGLE_VALUE 是 Calcite 内置的特殊聚合函数。它的行为是：如果输入只有 1 行，则返回该行数据；如果输入为空，返回 NULL；如果输入超过 1 行，在运行时直接抛出异常。
          relBuilder.aggregate(relBuilder.groupKey(),
              relBuilder.aggregateCall(SqlStdOperatorTable.SINGLE_VALUE,
                  relBuilder.field(0)));
        }
        register(relBuilder.build(), reg.joinType, reg.leftKeys);
      }
      return requireNonNull(this.root, "root");
    }

    /**
     * Sets a new root relational expression, as the translation process
     * backs its way further up the tree.
     *
     * @param root New root relational expression
     * @param leaf Whether the relational expression is a leaf, that is,
     *             derived from an atomic relational expression such as a table
     *             name in the from clause, or the projection on top of a
     *             select-sub-query. In particular, relational expressions
     *             derived from JOIN operators are not leaves, but set
     *             expressions are.
     */
    public void setRoot(RelNode root, boolean leaf) {
      setRoot(
          Collections.singletonList(root), root, root instanceof LogicalJoin);
      if (leaf) {
        leaves.put(root, root.getRowType().getFieldCount());
      }
      this.columnMonotonicities.clear();
    }

    private void setRoot(
        // 当前查询块（Query Block）所依赖的输入源（关系表达式算子）列表。
        // 通常代表当前 FROM 子句中各个表或子查询转换后的算子集合。
        // 当我们在表达式中通过别名引用某张表（例如 SELECT emp.empno FROM emp）时，小黑板需要通过这个 inputs 列表来做反向追溯，确定该表在当前环境中的物理位置。
        List<RelNode> inputs,
        // 当前整个小黑板逻辑树的最新根节点算子。
        // 随着转换的深入（如串联 Filter、Project），这个 root 会不断指向最新的、包裹了更多层级的外层算子。允许为 null（例如在刚初始化、还没有构建出完整逻辑树的中间状态）。
        @Nullable RelNode root,
        // 指示当前新构建的算子树中是否包含系统级字段（System Fields，如某些数据库中隐式的行号 ROWID 或隐藏的事务元数据列）。
        boolean hasSystemFields) {
      this.inputs = inputs;
      this.root = root;
      this.systemFieldList.clear();
      if (hasSystemFields) {
        this.systemFieldList.addAll(getSystemFields());
      }
    }

    /**
     * Notifies this Blackboard that the root just set using
     * {@link #setRoot(RelNode, boolean)} was derived using dataset
     * substitution.
     *
     * <p>The default implementation is not interested in such
     * notifications, and does nothing.
     *
     * @param datasetName Dataset name
     */
    public void setDataset(@Nullable String datasetName) {
    }

    void setRoot(List<RelNode> inputs) {
      setRoot(inputs, null, false);
    }

    /**
     * Returns an expression with which to reference a from-list item;
     * throws if not found.
     *
     * @param qualified The alias of the FROM item
     * @return a {@link RexFieldAccess} or {@link RexRangeRef}, never null
     */
    Pair<RexNode, @Nullable BiFunction<RexNode, String, RexNode>> lookupExp(
        SqlQualified qualified) {
      if (nameToNodeMap != null && qualified.prefixLength == 1) {
        RexNode node = nameToNodeMap.get(qualified.identifier.names.get(0));
        if (node == null) {
          throw new AssertionError("Unknown identifier '" + qualified.identifier
              + "' encountered while expanding expression");
        }
        return Pair.of(node, null);
      }
      final SqlNameMatcher nameMatcher =
          scope.getValidator().getCatalogReader().nameMatcher();
      final SqlValidatorScope.ResolvedImpl resolved =
          new SqlValidatorScope.ResolvedImpl();
      scope.resolve(qualified.prefix(), nameMatcher, false, resolved);
      if (resolved.count() != 1) {
        throw new AssertionError("no unique expression found for " + qualified
            + "; count is " + resolved.count());
      }
      final SqlValidatorScope.Resolve resolve = resolved.only();
      final RelDataType rowType = resolve.rowType();

      // Found in current query's from list.  Find which from item.
      // We assume that the order of the from clause items has been
      // preserved.
      final SqlValidatorScope ancestorScope = resolve.scope;
      boolean isParent = ancestorScope != scope;
      if ((inputs != null) && !isParent) {
        final LookupContext rels =
            new LookupContext(this, inputs, systemFieldList.size());
        final RexNode node = lookup(resolve.path.steps().get(0).i, rels);
        return Pair.of(node, (e, fieldName) -> {
          final RelDataTypeField field =
              requireNonNull(rowType.getField(fieldName, true, false),
                  () -> "field " + fieldName);
          return rexBuilder.makeFieldAccess(e, field.getIndex());
        });
      } else {
        // We're referencing a relational expression which has not been
        // converted yet. This occurs when from items are correlated,
        // e.g. "select from emp as emp join emp.getDepts() as dept".
        // Create a temporary expression.
        DeferredLookup lookup =
            new DeferredLookup(this, qualified.identifier.names.get(0));
        final CorrelationId correlId = cluster.createCorrel();
        mapCorrelToDeferred.put(correlId, lookup);
        if (resolve.path.steps().get(0).i < 0) {
          return Pair.of(rexBuilder.makeCorrel(rowType, correlId), null);
        } else {
          final RelDataTypeFactory.Builder builder = typeFactory.builder();
          final ListScope ancestorScope1 = (ListScope)
              requireNonNull(resolve.scope, "resolve.scope");
          final ImmutableMap.Builder<String, Integer> fields =
              ImmutableMap.builder();
          int i = 0;
          int offset = 0;
          for (SqlValidatorNamespace c : ancestorScope1.getChildren()) {
            if (ancestorScope1.isChildNullable(i)) {
              for (final RelDataTypeField f : c.getRowType().getFieldList()) {
                builder.add(f.getName(), typeFactory.createTypeWithNullability(f.getType(), true));
              }
            } else {
              builder.addAll(c.getRowType().getFieldList());
            }
            if (i == resolve.path.steps().get(0).i) {
              for (RelDataTypeField field : c.getRowType().getFieldList()) {
                fields.put(field.getName(), field.getIndex() + offset);
              }
            }
            ++i;
            offset += c.getRowType().getFieldCount();
          }
          final RexNode c =
              rexBuilder.makeCorrel(builder.uniquify().build(), correlId);
          final ImmutableMap<String, Integer> fieldMap = fields.build();
          return Pair.of(c, (e, fieldName) -> {
            final int j = requireNonNull(fieldMap.get(fieldName), "field " + fieldName);
            return rexBuilder.makeFieldAccess(e, j);
          });
        }
      }
    }

    /**
     * Creates an expression with which to reference the expression whose
     * offset in its from-list is {@code offset}.
     */
    RexNode lookup(
        int offset,
        LookupContext lookupContext) {
      Map.Entry<RelNode, Integer> pair = lookupContext.findRel(offset);
      return rexBuilder.makeRangeReference(
          pair.getKey().getRowType(),
          pair.getValue(),
          false);
    }

    @Nullable RelDataTypeField getRootField(RexInputRef inputRef) {
      List<RelNode> inputs = this.inputs;
      if (inputs == null) {
        return null;
      }
      int fieldOffset = inputRef.getIndex();
      for (RelNode input : inputs) {
        RelDataType rowType = input.getRowType();
        if (fieldOffset < rowType.getFieldCount()) {
          return rowType.getFieldList().get(fieldOffset);
        }
        fieldOffset -= rowType.getFieldCount();
      }
      return null;
    }

    public void flatten(
        List<RelNode> rels,
        int systemFieldCount,
        int[] start,
        BiConsumer<RelNode, Integer> relOffsetList) {
      for (RelNode rel : rels) {
        if (leaves.containsKey(rel)) {
          relOffsetList.accept(rel, start[0]);
          start[0] += leaves.get(rel);
        } else if (rel instanceof LogicalMatch) {
          relOffsetList.accept(rel, start[0]);
          start[0] += rel.getRowType().getFieldCount();
        } else {
          if (rel instanceof LogicalJoin
              || rel instanceof LogicalAggregate) {
            start[0] += systemFieldCount;
          }
          flatten(rel.getInputs(), systemFieldCount, start, relOffsetList);
        }
      }
    }

    void registerSubQuery(SqlNode node, RelOptUtil.Logic logic,
        SqlImplementor.@Nullable Clause clause) {
      if (getSubQuery(node, clause) == null) {
        subQueryList.add(new SubQuery(node, logic, clause));
      }
    }

    @Nullable SubQuery getSubQuery(SqlNode expr, SqlImplementor.@Nullable Clause exprClause) {
      for (SubQuery subQuery : subQueryList) {
        // Compare the reference to make sure the matched node has
        // exact scope where it belongs.
        if (expr == subQuery.node) {
          return subQuery;
        }

        // Reference comparing does not work in case when select list has column which refers
        // to the column inside `GROUP BY` clause.
        // For example: SELECT deptno IN (1,2) FROM emp.deptno GROUP BY deptno IN (1,2);
        if (exprClause == SqlImplementor.Clause.SELECT
            && subQuery.clause == SqlImplementor.Clause.GROUP_BY
            && expr.equalsDeep(subQuery.node, Litmus.IGNORE)) {
          return subQuery;
        }
      }
      return null;
    }

    ImmutableList<RelNode> retrieveCursors() {
      try {
        return ImmutableList.copyOf(cursors);
      } finally {
        cursors.clear();
      }
    }
    // SQL 抽象语法树节点（SqlNode，代表表达式/子查询等）向行表达式节点（RexNode，包含算子字段引用、函数调用、子查询节点等）转换的核心总入口。
    // SqlNode expr：输入的 SQL 语法树表达式节点（例如普通的列名 id、常量 10、复杂的函数调用 A + B，或者嵌套的标量子查询 (SELECT max(x) FROM t)）。
    // 返回值 RexNode：转换后对应的行表达式。它已经绑定了关系代数树中的物理位置、底层列索引或标准函数操作符。
    @Override public RexNode convertExpression(SqlNode expr) {
      // If we're in aggregation mode and this is an expression in the
      // GROUP BY clause, return a reference to the field.
      // 阶段 1：聚合（Aggregation）环境下的分组列/聚合函数匹配
      AggConverter agg = this.agg;
      // 判断当前转换上下文是否处于 GROUP BY / 聚合阶段（如正在转换 SELECT 列表或 HAVING 子句）。
      if (agg != null) {
        // 利用 Validator 对当前表达式进行别名或隐式转换的展开，统一格式（确保能与 GROUP BY 里的原始表达式对齐）
        final SqlNode expandedGroupExpr = validator().expand(expr, scope);
        // 在 GROUP BY 的字段列表里查找该表达式。如果能找到（返回索引 ref >= 0），说明该表达式正是分组键之一。
        final int ref = agg.lookupGroupExpr(expandedGroupExpr);
        // 既然是分组键，就不必在当前层重新计算整个表达式，直接返回对底层 Aggregate 算子输出的分组列的引用（RexInputRef）。
        if (ref >= 0) {
          return rexBuilder.makeInputRef(root(), ref);
        }
        // 如果是函数调用（SqlCall），去已收集的聚合函数（如 SUM, COUNT）中匹配。如果命中，直接返回该聚合函数在结果集中的引用。
        if (expr instanceof SqlCall) {
          final RexNode rex = agg.lookupAggregates((SqlCall) expr);
          if (rex != null) {
            return rex;
          }
        }
      }

      // Allow the derived class chance to override the standard
      // behavior for special kinds of expressions.
      // 阶段 2：留给子类的扩展接口（Hook）
      RexNode rex = convertExtendedExpression(expr, this);
      if (rex != null) {
        return rex;
      }

      // Sub-queries and OVER expressions are not like ordinary
      // expressions.
      // 阶段 3：当配置为“不展开（expand = false）”时，直接构建 RexSubQuery
      // 当系统配置中明确禁止在这一步将子查询展开为 Join 算子时（保留原汁原味的子查询形式），会进入以下逻辑，将其转化为特殊的 RexSubQuery 表达式节点：
      final SqlKind kind = expr.getKind();
      final SubQuery subQuery;
      if (!config.isExpand()) {
        final SqlCall call;
        final SqlNode query;
        final RelRoot root;
        switch (kind) {
        case IN:
        case NOT_IN:
        case SOME:
        case ALL:
          call = (SqlCall) expr;
          // 获取右侧的子查询树
          query = call.operand(1);
          if (!(query instanceof SqlNodeList)) { // 排除 IN (1, 2, 3) 这种普通的列表常量，只处理嵌套查询
            // 递归将子查询的 SqlNode 转换为 RelNode 逻辑树
            root = convertQueryRecursive(query, false, null);
            // 获取左侧的表达式（如 id IN (...) 中的 id）
            final SqlNode operand = call.operand(0);
            List<SqlNode> nodes;
            switch (operand.getKind()) {
            // 处理多列 IN 语法，如 (a, b) IN (SELECT x, y FROM ...)
            case ROW:
              nodes = ((SqlCall) operand).getOperandList();
              break;
            default:
              nodes = ImmutableList.of(operand);
            }
            final ImmutableList.Builder<RexNode> builder =
                ImmutableList.builder();
            // 递归转换左侧的每一列表达式
            for (SqlNode node : nodes) {
              builder.add(convertExpression(node));
            }
            final ImmutableList<RexNode> list = builder.build();
            RelNode rel = root.rel;
            // Fix the correlation namespaces and de-duplicate the correlation variables.
            // 修正关联子查询的 Namespace 并去重关联变量
            CorrelationUse correlationUse = getCorrelationUse(this, root.rel);
            if (correlationUse != null) {
              rel = correlationUse.r;
            }
            // 根据不同的 SQL 类型，打包成对应的 RexSubQuery 表达式
            switch (kind) {
            case IN:
              return RexSubQuery.in(rel, list);
            case NOT_IN: // NOT_IN 转为 NOT (RexSubQuery.in)
              return rexBuilder.makeCall(SqlStdOperatorTable.NOT,
                  RexSubQuery.in(rel, list));
            case SOME:
              return RexSubQuery.some(rel, list,
                  (SqlQuantifyOperator) call.getOperator());
            case ALL:
              return rexBuilder.makeCall(SqlStdOperatorTable.NOT,
                  RexSubQuery.some(rel, list,
                      negate((SqlQuantifyOperator) call.getOperator())));
            default:
              throw new AssertionError(kind);
            }
          }
          break;

        case EXISTS:
          call = (SqlCall) expr;
          query = Iterables.getOnlyElement(call.getOperandList());
          root = convertQueryRecursive(query, false, null);
          RelNode rel = root.rel;
          // Fix the correlation namespaces and de-duplicate the correlation variables.
          CorrelationUse correlationUse = getCorrelationUse(this, root.rel);
          if (correlationUse != null) {
            rel = correlationUse.r;
          }
          // 裁剪无意义的外层算子：如果 EXISTS 内部带有没有 limit/offset 的 Project 或 Sort，直接脱壳取其 Input
          while (rel instanceof Project
              || rel instanceof Sort
              && ((Sort) rel).fetch == null
              && ((Sort) rel).offset == null) {
            rel = ((SingleRel) rel).getInput();
          }
          // 返回 EXISTS 类型的 RexSubQuery
          return RexSubQuery.exists(rel);
        // 类似地，将 UNIQUE 子查询转换为 RexSubQuery.unique
        case UNIQUE:
          call = (SqlCall) expr;
          query = Iterables.getOnlyElement(call.getOperandList());
          root = convertQueryRecursive(query, false, null);
          return RexSubQuery.unique(root.rel);
        // 标量子查询，形如 (SELECT price FROM t LIMIT 1)
        case SCALAR_QUERY:
          call = (SqlCall) expr;
          query = Iterables.getOnlyElement(call.getOperandList());
          root = convertQueryRecursive(query, false, null);
          rel = root.rel;
          // Fix the correlation namespaces and de-duplicate the correlation variables.
          correlationUse = getCorrelationUse(this, root.rel);
          if (correlationUse != null) {
            rel = correlationUse.r;
          }
          // 返回标量形式的 RexSubQuery
          return RexSubQuery.scalar(rel);
        // 处理复杂的数据集合构造器查询（ARRAY, MAP, MULTISET）
        case ARRAY_QUERY_CONSTRUCTOR:
          call = (SqlCall) expr;
          query = Iterables.getOnlyElement(call.getOperandList());
          // let top=true to make the query be top-level query,
          // then ORDER BY will be reserved.
          // top=true 保证作为顶层查询转换，从而保留其内部的 ORDER BY 语义
          root = convertQueryRecursive(query, true, null);
          return RexSubQuery.array(root.rel);

        case MAP_QUERY_CONSTRUCTOR:
          call = (SqlCall) expr;
          query = Iterables.getOnlyElement(call.getOperandList());
          root = convertQueryRecursive(query, false, null);
          return RexSubQuery.map(root.rel);

        case MULTISET_QUERY_CONSTRUCTOR:
          call = (SqlCall) expr;
          query = Iterables.getOnlyElement(call.getOperandList());
          root = convertQueryRecursive(query, false, null);
          return RexSubQuery.multiset(root.rel);

        default:
          break;
        }
      }
      // 阶段 4：当配置为“要展开（expand = true）”时，利用先前拉平的 Join 数据构建指针
      switch (kind) {
      case SOME:
      case ALL:
      case UNIQUE:
        if (config.isExpand()) {
          // 抛出异常：表明某些复杂的修饰词子查询在当前展开模式下还未被支持
          throw new RuntimeException(kind
              + " is only supported if expand = false");
        }
        // fall through
      case CURSOR:
      case IN:
      case NOT_IN:
        // 获取此前已经注册并预转换好的子查询上下文数据
        subQuery = getSubQuery(expr, null);
        if (subQuery == null && (kind == SqlKind.SOME || kind == SqlKind.ALL)) {
          break;
        }
        assert subQuery != null;
        rex = requireNonNull(subQuery.expr);
        // 执行 CAST 校验：确保转换出来的 rex 类型与 SQL 语法层校验出来的验证类型严格对齐
        return StandardConvertletTable.castToValidatedType(expr, rex,
            validator(), rexBuilder, false);

      case SELECT:
      case EXISTS:
      case SCALAR_QUERY:
      case ARRAY_QUERY_CONSTRUCTOR:
      case MAP_QUERY_CONSTRUCTOR:
      case MULTISET_QUERY_CONSTRUCTOR:
        subQuery = getSubQuery(expr, null);
        assert subQuery != null;
        rex = subQuery.expr;
        assert rex != null : "rex != null";
        // 如果发现这个标量子查询已经被提前优化或转换成了一个常量（Literal），直接将其作为结果返回
        if (((kind == SqlKind.SCALAR_QUERY)
            || (kind == SqlKind.EXISTS))
            && isConvertedSubq(rex)) {
          // scalar sub-query or EXISTS has been converted to a
          // constant
          return rex;
        }

        // The indicator column is the last field of the sub-query.
        // 【核心机制：指示列】
        // 子查询被转化为右侧连接后，通过在右表最末尾增加一列指示列（Indicator column），
        // 用来标志子查询结果是否存在，或映射其输出结果。
        RexNode fieldAccess =
            rexBuilder.makeFieldAccess(
                rex,
                rex.getType().getFieldCount() - 1);// 获取该子查询关联块下的最后一列

        // The indicator column will be nullable if it comes from
        // the null-generating side of the join. For EXISTS, add an
        // "IS TRUE" check so that the result is "BOOLEAN NOT NULL".
        // 对于 EXISTS 而言，如果对应的连接侧是 Null-generating（如左外连接产生 null），
        // 那么它的指示列就会是 Nullable。此处通过套一层 IS NOT NULL，将其强转为 BOOLEAN NOT NULL
        if (fieldAccess.getType().isNullable()
            && kind == SqlKind.EXISTS) {
          fieldAccess =
              rexBuilder.makeCall(
                  SqlStdOperatorTable.IS_NOT_NULL,
                  fieldAccess);
        }
        return fieldAccess;
      // 路由处理：单独处理窗口函数（OVER 表达式）
      case OVER:
        return convertOver(this, expr);
      // 路由处理：单独处理 Lambda 表达式
      case LAMBDA:
        return convertLambda(this, expr);

      default:
        // fall through
      }

      // Apply standard conversions.
      //阶段 5：兜底处理（标准标量与算子转换）
      // 如果上述所有特异场景（聚合、子查询、窗口函数）全部没有命中，说明它只是一个普通的 SQL 标量表达式（如 1 + 1，user.age）。
      // 通过调用 expr.accept(this)，小黑板会触发访问者模式（Visitor Pattern），将请求分发给对应的 SqlVisitor 实现（实际上最终由 StandardConvertletTable 完成），
      // 将其组装为标准的 RexCall 或 RexInputRef 标量表达式并返回。
      rex = expr.accept(this);
      return requireNonNull(rex, "rex");
    }

    /**
     * Converts an item in an ORDER BY clause inside a window (OVER) clause,
     * extracting DESC, NULLS LAST and NULLS FIRST flags first.
     */
    @Deprecated // to be removed before 2.0
    public RexFieldCollation convertSortExpression(SqlNode expr,
        RelFieldCollation.Direction direction,
        RelFieldCollation.NullDirection nullDirection) {
      return convertSortExpression(expr, direction, nullDirection,
          this::sortToRexFieldCollation);
    }

    /** Handles an item in an ORDER BY clause, passing using a converter
     * function to produce the final result. */
    <R> R convertSortExpression(SqlNode expr,
        RelFieldCollation.Direction direction,
        RelFieldCollation.NullDirection nullDirection,
        SortExpressionConverter<R> converter) {
      switch (expr.getKind()) {
      case DESCENDING:
        return convertSortExpression(((SqlCall) expr).operand(0),
            RelFieldCollation.Direction.DESCENDING, nullDirection, converter);
      case NULLS_LAST:
        return convertSortExpression(((SqlCall) expr).operand(0),
            direction, RelFieldCollation.NullDirection.LAST, converter);
      case NULLS_FIRST:
        return convertSortExpression(((SqlCall) expr).operand(0),
            direction, RelFieldCollation.NullDirection.FIRST, converter);
      default:
        return converter.convert(expr, direction, nullDirection);
      }
    }

    // Only used by deprecated method "convertSortExpression", and will be
    // removed with that method.
    private RexFieldCollation sortToRexFieldCollation(SqlNode expr,
        RelFieldCollation.Direction direction,
        RelFieldCollation.NullDirection nullDirection) {
      final Set<SqlKind> flags = EnumSet.noneOf(SqlKind.class);
      if (direction == RelFieldCollation.Direction.DESCENDING) {
        flags.add(SqlKind.DESCENDING);
      }
      switch (nullDirection) {
      case UNSPECIFIED:
        final RelFieldCollation.NullDirection nullDefaultDirection =
            validator().config().defaultNullCollation().last(desc(direction))
                ? RelFieldCollation.NullDirection.LAST
                : RelFieldCollation.NullDirection.FIRST;
        if (nullDefaultDirection != direction.defaultNullDirection()) {
          SqlKind nullDirectionSqlKind =
              validator().config().defaultNullCollation().last(desc(direction))
                  ? SqlKind.NULLS_LAST
                  : SqlKind.NULLS_FIRST;
          flags.add(nullDirectionSqlKind);
        }
        break;
      case FIRST:
        flags.add(SqlKind.NULLS_FIRST);
        break;
      case LAST:
        flags.add(SqlKind.NULLS_LAST);
        break;
      default:
        break;
      }
      return new RexFieldCollation(convertExpression(expr), flags);
    }

    private RexNode sortToRex(SqlNode expr,
        RelFieldCollation.Direction direction,
        RelFieldCollation.NullDirection nullDirection) {
      RexNode node = convertExpression(expr);
      final boolean desc = direction == RelFieldCollation.Direction.DESCENDING;
      if (desc) {
        node = relBuilder.desc(node);
      }
      if (nullDirection == RelFieldCollation.NullDirection.UNSPECIFIED) {
        final NullCollation nullCollation =
            validator().config().defaultNullCollation();
        final boolean nullsLast = nullCollation.last(desc);
        final boolean nullsFirst = !nullsLast;
        if (!NullCollation.HIGH.isDefaultOrder(nullsFirst, desc)) {
          nullDirection = nullsLast
              ? RelFieldCollation.NullDirection.LAST
              : RelFieldCollation.NullDirection.FIRST;
        }
      }
      if (nullDirection == RelFieldCollation.NullDirection.FIRST) {
        node = relBuilder.nullsFirst(node);
      }
      if (nullDirection == RelFieldCollation.NullDirection.LAST) {
        node = relBuilder.nullsLast(node);
      }
      return node;
    }

    /**
     * Determines whether a RexNode corresponds to a sub-query that's been
     * converted to a constant.
     *
     * @param rex the expression to be examined
     * @return true if the expression is a dynamic parameter, a literal, or
     * a literal that is being cast
     */
    private boolean isConvertedSubq(RexNode rex) {
      if ((rex instanceof RexLiteral)
          || (rex instanceof RexDynamicParam)) {
        return true;
      }
      if (rex instanceof RexCall) {
        RexCall call = (RexCall) rex;
        if (call.getOperator() == SqlStdOperatorTable.CAST) {
          RexNode operand = call.getOperands().get(0);
          if (operand instanceof RexLiteral) {
            return true;
          }
        }
      }
      return false;
    }

    @Override public int getGroupCount() {
      if (agg != null) {
        return agg.groupExprs.size();
      }
      if (window != null) {
        return window.isAlwaysNonEmpty() ? 1 : 0;
      }
      return -1;
    }

    @Override public RexBuilder getRexBuilder() {
      return rexBuilder;
    }

    @Override public SqlNode validateExpression(RelDataType rowType, SqlNode expr) {
      return SqlValidatorUtil.validateExprWithRowType(
          catalogReader.nameMatcher().isCaseSensitive(), opTab,
          typeFactory, rowType, expr).left;
    }

    @Override public RexRangeRef getSubQueryExpr(SqlCall call) {
      final SubQuery subQuery = getSubQuery(call, null);
      assert subQuery != null;
      return (RexRangeRef) requireNonNull(subQuery.expr, () -> "subQuery.expr for " + call);
    }

    @Override public RelDataTypeFactory getTypeFactory() {
      return typeFactory;
    }

    @Override public InitializerExpressionFactory getInitializerExpressionFactory() {
      return initializerExpressionFactory;
    }

    @Override public SqlValidator getValidator() {
      return validator();
    }

    @Override public RexNode convertLiteral(SqlLiteral literal) {
      return exprConverter.convertLiteral(this, literal);
    }

    public RexNode convertInterval(SqlIntervalQualifier intervalQualifier) {
      return exprConverter.convertInterval(this, intervalQualifier);
    }

    @Override public RexNode visit(SqlLiteral literal) {
      return exprConverter.convertLiteral(this, literal);
    }

    @Override public RexNode visit(SqlCall call) {
      if (agg != null) {
        final SqlOperator op = call.getOperator();
        if (window == null
            && (op.isAggregator()
            || op.getKind() == SqlKind.FILTER
            || op.getKind() == SqlKind.WITHIN_DISTINCT
            || op.getKind() == SqlKind.WITHIN_GROUP)) {
          return requireNonNull(agg.lookupAggregates(call),
              () -> "agg.lookupAggregates for call " + call);
        }
      }
      return exprConverter.convertCall(this,
          new SqlCallBinding(validator(), scope, call).permutedCall());
    }

    @Override public RexNode visit(SqlNodeList nodeList) {
      throw new UnsupportedOperationException();
    }

    @Override public RexNode visit(SqlIdentifier id) {
      return convertIdentifier(this, id);
    }

    @Override public RexNode visit(SqlDataTypeSpec type) {
      throw new UnsupportedOperationException();
    }

    @Override public RexNode visit(SqlDynamicParam param) {
      return convertDynamicParam(param);
    }

    @Override public RexNode visit(SqlIntervalQualifier intervalQualifier) {
      return convertInterval(intervalQualifier);
    }

    public List<SqlMonotonicity> getColumnMonotonicities() {
      return columnMonotonicities;
    }

  }

  private static SqlQuantifyOperator negate(SqlQuantifyOperator operator) {
    assert operator.kind == SqlKind.ALL;
    return SqlStdOperatorTable.some(operator.comparisonKind.negateNullSafe());
  }

  /** Deferred lookup. */
  private static class DeferredLookup {
    final Blackboard bb;
    final String originalRelName;

    DeferredLookup(Blackboard bb, String originalRelName) {
      this.bb = bb;
      this.originalRelName = originalRelName;
    }

    RexFieldAccess getFieldAccess(CorrelationId name) {
      return requireNonNull(bb.mapCorrelateToRex.get(name),
          () -> "Correlation " + name + " is not found");
    }
  }

  /**
   * A default implementation of SubQueryConverter that does no conversion.
   */
  private static class NoOpSubQueryConverter implements SubQueryConverter {
    @Override public boolean canConvertSubQuery() {
      return false;
    }

    @Override public RexNode convertSubQuery(
        SqlCall subQuery,
        SqlToRelConverter parentConverter,
        boolean isExists,
        boolean isExplain) {
      throw new IllegalArgumentException();
    }
  }

  /**
   * Context to find a relational expression to a field offset.
   */
  private static class LookupContext {
    private final PairList<RelNode, Integer> relOffsetList = PairList.of();

    /**
     * Creates a LookupContext with multiple input relational expressions.
     *
     * @param bb               Context for translating this sub-query
     * @param rels             Relational expressions
     * @param systemFieldCount Number of system fields
     */
    LookupContext(Blackboard bb, List<RelNode> rels, int systemFieldCount) {
      bb.flatten(rels, systemFieldCount, new int[]{0}, relOffsetList::add);
    }

    /**
     * Returns the relational expression with a given offset, and the
     * ordinal in the combined row of its first field.
     *
     * <p>For example, in {@code Emp JOIN Dept}, findRel(1) returns the
     * relational expression for {@code Dept} and offset 6 (because
     * {@code Emp} has 6 fields, therefore the first field of {@code Dept}
     * is field 6.
     *
     * @param offset Offset of relational expression in FROM clause
     * @return Relational expression and the ordinal of its first field
     */
    Map.Entry<RelNode, Integer> findRel(int offset) {
      return relOffsetList.get(offset);
    }
  }

  /**
   * Shuttle which walks over a tree of {@link RexNode}s and applies 'over' to
   * all agg functions.
   *
   * <p>This is necessary because the returned expression is not necessarily a
   * call to an agg function. For example,
   *
   * <blockquote><code>AVG(x)</code></blockquote>
   *
   * <p>becomes
   *
   * <blockquote><code>SUM(x) / COUNT(x)</code></blockquote>
   *
   * <p>Any aggregate functions are converted to calls to the internal <code>
   * $Histogram</code> aggregation function and accessors such as <code>
   * $HistogramMin</code>; for example,
   *
   * <blockquote><code>MIN(x), MAX(x)</code></blockquote>
   *
   * <p>are converted to
   *
   * <blockquote><code>$HistogramMin($Histogram(x)),
   * $HistogramMax($Histogram(x))</code></blockquote>
   *
   * <p>Common sub-expression elimination will ensure that only one histogram is
   * computed.
   */
  private class HistogramShuttle extends RexShuttle {
    /**
     * Whether to convert calls to MIN(x) to HISTOGRAM_MIN(HISTOGRAM(x)).
     * Histograms allow rolling computation, but require more space.
     */
    static final boolean ENABLE_HISTOGRAM_AGG = false;

    private final ImmutableList<RexNode> partitionKeys;
    private final ImmutableList<RexNode> orderKeys;
    private final RexWindowBound lowerBound;
    private final RexWindowBound upperBound;
    private final RexWindowExclusion exclude;
    private final boolean rows;
    private final boolean allowPartial;
    private final boolean distinct;
    private final boolean ignoreNulls;

    HistogramShuttle(ImmutableList<RexNode> partitionKeys,
        ImmutableList<RexNode> orderKeys, boolean rows,
        RexWindowBound lowerBound, RexWindowBound upperBound, RexWindowExclusion exclude,
        boolean allowPartial, boolean distinct, boolean ignoreNulls) {
      this.partitionKeys = partitionKeys;
      this.orderKeys = orderKeys;
      this.lowerBound = lowerBound;
      this.upperBound = upperBound;
      this.exclude = exclude;
      this.rows = rows;
      this.allowPartial = allowPartial;
      this.distinct = distinct;
      this.ignoreNulls = ignoreNulls;
    }

    @Override public RexNode visitCall(RexCall call) {
      final SqlOperator op = call.getOperator();
      if (!(op instanceof SqlAggFunction)) {
        return super.visitCall(call);
      }
      final SqlAggFunction aggOp = (SqlAggFunction) op;
      final RelDataType type = call.getType();
      List<RexNode> exprs = call.getOperands();

      SqlFunction histogramOp = !ENABLE_HISTOGRAM_AGG
          ? null
          : getHistogramOp(aggOp);

      if (histogramOp != null) {
        final RelDataType histogramType = computeHistogramType(type);

        // For DECIMAL, since it's already represented as a bigint we
        // want to do a reinterpretCast instead of a cast to avoid
        // losing any precision.
        boolean reinterpretCast =
            type.getSqlTypeName() == SqlTypeName.DECIMAL;

        // Replace original expression with CAST of not one
        // of the supported types
        if (histogramType != type) {
          exprs = new ArrayList<>(exprs);
          exprs.set(
              0,
              reinterpretCast
              ? rexBuilder.makeReinterpretCast(histogramType, exprs.get(0),
                  rexBuilder.makeLiteral(false))
              : rexBuilder.makeCast(histogramType, exprs.get(0)));
        }

        RexNode over =
            relBuilder.aggregateCall(SqlStdOperatorTable.HISTOGRAM_AGG, exprs)
                .distinct(distinct)
                .ignoreNulls(ignoreNulls)
                .over()
                .partitionBy(partitionKeys)
                .orderBy(orderKeys)
                .let(c ->
                    rows ? c.rowsBetween(lowerBound, upperBound)
                        : c.rangeBetween(lowerBound, upperBound))
                .exclude(exclude)
                .allowPartial(allowPartial)
                .toRex();

        RexNode histogramCall =
            rexBuilder.makeCall(
                histogramType,
                histogramOp,
                ImmutableList.of(over));

        // If needed, post Cast result back to original
        // type.
        if (histogramType != type) {
          if (reinterpretCast) {
            histogramCall =
                rexBuilder.makeReinterpretCast(
                    type,
                    histogramCall,
                    rexBuilder.makeLiteral(false));
          } else {
            histogramCall =
                rexBuilder.makeCast(type, histogramCall);
          }
        }

        return histogramCall;
      } else {
        boolean needSum0 = aggOp == SqlStdOperatorTable.SUM
            && type.isNullable();
        SqlAggFunction aggOpToUse =
            needSum0 ? SqlStdOperatorTable.SUM0
                : aggOp;
        return relBuilder.aggregateCall(aggOpToUse, exprs)
            .distinct(distinct)
            .ignoreNulls(ignoreNulls)
            .over()
            .partitionBy(partitionKeys)
            .orderBy(orderKeys)
            .let(c ->
                rows ? c.rowsBetween(lowerBound, upperBound)
                    : c.rangeBetween(lowerBound, upperBound))
            .exclude(exclude)
            .allowPartial(allowPartial)
            .nullWhenCountZero(needSum0)
            .toRex();
      }
    }

    /**
     * Returns the histogram operator corresponding to a given aggregate
     * function.
     *
     * <p>For example, <code>getHistogramOp
     *({@link SqlStdOperatorTable#MIN}}</code> returns
     * {@link SqlStdOperatorTable#HISTOGRAM_MIN}.
     *
     * @param aggFunction An aggregate function
     * @return Its histogram function, or null
     */
    @Nullable SqlFunction getHistogramOp(SqlAggFunction aggFunction) {
      if (aggFunction == SqlStdOperatorTable.MIN) {
        return SqlStdOperatorTable.HISTOGRAM_MIN;
      } else if (aggFunction == SqlStdOperatorTable.MAX) {
        return SqlStdOperatorTable.HISTOGRAM_MAX;
      } else if (aggFunction == SqlStdOperatorTable.FIRST_VALUE) {
        return SqlStdOperatorTable.HISTOGRAM_FIRST_VALUE;
      } else if (aggFunction == SqlStdOperatorTable.LAST_VALUE) {
        return SqlStdOperatorTable.HISTOGRAM_LAST_VALUE;
      } else {
        return null;
      }
    }

    /**
     * Returns the type for a histogram function. It is either the actual
     * type or an an approximation to it.
     */
    private RelDataType computeHistogramType(RelDataType type) {
      if (SqlTypeUtil.isExactNumeric(type)
          && type.getSqlTypeName() != SqlTypeName.BIGINT) {
        return typeFactory.createSqlType(SqlTypeName.BIGINT);
      } else if (SqlTypeUtil.isApproximateNumeric(type)
          && type.getSqlTypeName() != SqlTypeName.DOUBLE) {
        return typeFactory.createSqlType(SqlTypeName.DOUBLE);
      } else {
        return type;
      }
    }
  }

  /** A sub-query, whether it needs to be translated using 2- or 3-valued
   * logic. */
  private static class SubQuery {
    final SqlNode node;
    final RelOptUtil.Logic logic;
    @Nullable RexNode expr;
    final SqlImplementor.@Nullable Clause clause;

    private SubQuery(SqlNode node, RelOptUtil.Logic logic,
        SqlImplementor.@Nullable Clause clause) {
      this.node = node;
      this.logic = logic;
      this.clause = clause;
    }
  }

  /**
   * Visitor that looks for an SqlIdentifier inside a tree of
   * {@link SqlNode} objects and return {@link Boolean#TRUE} when it finds
   * one.
   */
  public static class SqlIdentifierFinder implements SqlVisitor<Boolean> {

    @Override public Boolean visit(SqlCall sqlCall) {
      return sqlCall.getOperandList().stream().anyMatch(sqlNode -> sqlNode.accept(this));
    }

    @Override public Boolean visit(SqlNodeList nodeList) {
      return nodeList.stream().anyMatch(sqlNode -> sqlNode.accept(this));
    }

    @Override public Boolean visit(SqlIdentifier identifier) {
      return true;
    }

    @Override public Boolean visit(SqlLiteral literal) {
      return false;
    }

    @Override public Boolean visit(SqlDataTypeSpec type) {
      return false;
    }

    @Override public Boolean visit(SqlDynamicParam param) {
      return false;
    }

    @Override public Boolean visit(SqlIntervalQualifier intervalQualifier) {
      return false;
    }

  }

  /**
   * Visitor that collects all aggregate functions in a {@link SqlNode} tree.
   */
  private static class AggregateFinder extends SqlBasicVisitor<Void> {
    final SqlNodeList list = new SqlNodeList(SqlParserPos.ZERO);
    final SqlNodeList filterList = new SqlNodeList(SqlParserPos.ZERO);
    final SqlNodeList distinctList = new SqlNodeList(SqlParserPos.ZERO);
    final SqlNodeList orderList = new SqlNodeList(SqlParserPos.ZERO);

    @Override public Void visit(SqlCall call) {
      // ignore window aggregates and ranking functions (associated with OVER operator)
      if (call.getOperator().getKind() == SqlKind.OVER) {
        return null;
      }

      if (call.getOperator().getKind() == SqlKind.FILTER) {
        // the WHERE in a FILTER must be tracked too so we can call replaceSubQueries on it.
        // see https://issues.apache.org/jira/browse/CALCITE-1910
        final SqlNode aggCall = call.getOperandList().get(0);
        final SqlNode whereCall = call.getOperandList().get(1);
        list.add(aggCall);
        filterList.add(whereCall);
        return null;
      }

      if (call.getOperator().getKind() == SqlKind.WITHIN_DISTINCT) {
        final SqlNode aggCall = call.getOperandList().get(0);
        final SqlNodeList distinctList =
            (SqlNodeList) call.getOperandList().get(1);
        list.add(aggCall);
        distinctList.getList().forEach(this.distinctList::add);
        return null;
      }

      if (call.getOperator().getKind() == SqlKind.WITHIN_GROUP) {
        final SqlNode aggCall = call.getOperandList().get(0);
        final SqlNodeList orderList = (SqlNodeList) call.getOperandList().get(1);
        list.add(aggCall);
        this.orderList.addAll(orderList);
        return null;
      }

      if (call.getOperator().isAggregator()) {
        list.add(call);
        return null;
      }

      // Don't traverse into sub-queries, even if they contain aggregate
      // functions.
      if (call instanceof SqlSelect) {
        return null;
      }

      return call.getOperator().acceptCall(this, call);
    }
  }

  /** Use of a row as a correlating variable by a given relational
   * expression. */
  private static class CorrelationUse {
    private final CorrelationId id;
    private final ImmutableBitSet requiredColumns;
    /** The relational expression that uses the variable. */
    private final RelNode r;

    CorrelationUse(CorrelationId id, ImmutableBitSet requiredColumns,
        RelNode r) {
      this.id = id;
      this.requiredColumns = requiredColumns;
      this.r = r;
    }
  }

  /** Returns a default {@link Config}. */
  public static Config config() {
    return CONFIG;
  }

  /**
   * Interface to define the configuration for a SqlToRelConverter.
   * Provides methods to set each configuration option.
   *
   * @see SqlToRelConverter#CONFIG
   */
  @Value.Immutable(singleton = false)
  public interface Config {
    /** Returns the {@code decorrelationEnabled} option. Controls whether to
     * disable sub-query decorrelation when needed. e.g. if outer joins are not
     * supported. */
    @Value.Default default boolean isDecorrelationEnabled() {
      return true;
    }

    /** Sets {@link #isDecorrelationEnabled()}. */
    Config withDecorrelationEnabled(boolean decorrelationEnabled);

    /** Returns the {@code trimUnusedFields} option. Controls whether to trim
     * unused fields as part of the conversion process. */
    @Value.Default default boolean isTrimUnusedFields() {
      return false;
    }

    /** Sets {@link #isTrimUnusedFields()}. */
    Config withTrimUnusedFields(boolean trimUnusedFields);

    /** Returns the {@code createValuesRel} option. Controls whether instances
     * of {@link org.apache.calcite.rel.logical.LogicalValues} are generated.
     * These may not be supported by all physical implementations. */
    @Value.Default default boolean isCreateValuesRel() {
      return true;
    }

    /** Sets {@link #isCreateValuesRel()}. */
    Config withCreateValuesRel(boolean createValuesRel);

    /** Returns the {@code explain} option. Describes whether the current
     * statement is part of an EXPLAIN PLAN statement. */
    @Value.Default default boolean isExplain() {
      return false;
    }

    /** Sets {@link #isExplain()}. */
    Config withExplain(boolean explain);

    /** Returns the {@code expand} option. Controls whether to expand
     * sub-queries. If false (the default), each sub-query becomes a
     * {@link org.apache.calcite.rex.RexSubQuery}.
     *
     * <p>Setting {@code expand} to true is deprecated. Expansion still works,
     * but there will be less development effort in that area. */
    @Value.Default default boolean isExpand() {
      return false;
    }

    /** Sets {@link #isExpand()}.
     *
     * <p>Expansion is deprecated. We recommend that you do not call this
     * method, and use the default value of {@link #isExpand()}, false. */
    Config withExpand(boolean expand);

    /** Returns the {@code inSubQueryThreshold} option,
     * default {@link #DEFAULT_IN_SUB_QUERY_THRESHOLD}. Controls the list size
     * threshold under which {@link #convertInToOr} is used. Lists of this size
     * or greater will instead be converted to use a join against an inline
     * table ({@link org.apache.calcite.rel.logical.LogicalValues}) rather than
     * a predicate. A threshold of 0 forces usage of an inline table in all
     * cases; a threshold of {@link Integer#MAX_VALUE} forces usage of OR in all
     * cases. */
    @Value.Default default int getInSubQueryThreshold() {
      return DEFAULT_IN_SUB_QUERY_THRESHOLD;
    }

    /** Sets {@link #getInSubQueryThreshold()}. */
    Config withInSubQueryThreshold(int threshold);

    /** Returns whether to remove Sort operator for a sub-query
     * if the Sort has no offset and fetch limit attributes.
     * Because the remove does not change the semantics,
     * in many cases this is a promotion.
     * Default is true. */
    @Value.Default default boolean isRemoveSortInSubQuery() {
      return true;
    }

    /** Sets {@link #isRemoveSortInSubQuery()}. */
    Config withRemoveSortInSubQuery(boolean removeSortInSubQuery);

    /** Returns the factory to create {@link RelBuilder}, never null. Default is
     * {@link RelFactories#LOGICAL_BUILDER}. */
    RelBuilderFactory getRelBuilderFactory();

    /** Sets {@link #getRelBuilderFactory()}. */
    Config withRelBuilderFactory(RelBuilderFactory factory);

    /** Returns a function that takes a {@link RelBuilder.Config} and returns
     * another. Default is the identity function. */
    UnaryOperator<RelBuilder.Config> getRelBuilderConfigTransform();

    /** Sets {@link #getRelBuilderConfigTransform()}.
     *
     * @see #addRelBuilderConfigTransform */
    Config withRelBuilderConfigTransform(
        UnaryOperator<RelBuilder.Config> transform);

    /** Adds a transform to {@link #getRelBuilderConfigTransform()}. */
    default Config addRelBuilderConfigTransform(
        UnaryOperator<RelBuilder.Config> transform) {
      return withRelBuilderConfigTransform(
          getRelBuilderConfigTransform().andThen(transform)::apply);
    }

    /** Returns the hint strategies used to decide how the hints are propagated to
     * the relational expressions. Default is
     * {@link HintStrategyTable#EMPTY}. */
    HintStrategyTable getHintStrategyTable();

    /** Sets {@link #getHintStrategyTable()}. */
    Config withHintStrategyTable(HintStrategyTable hintStrategyTable);

    /**
     * Whether add {@link SqlStdOperatorTable#JSON_TYPE_OPERATOR} for between json functions.
     */
    @Value.Default default boolean isAddJsonTypeOperatorEnabled() {
      return true;
    }

    /** Sets {@link #isAddJsonTypeOperatorEnabled()}. */
    Config withAddJsonTypeOperatorEnabled(boolean addJsonTypeOperatorEnabled);
  }

  /**
   * Used to find nested json functions, and add {@link SqlStdOperatorTable#JSON_TYPE_OPERATOR}
   * to nested json output.
   */
  private class NestedJsonFunctionRelRewriter extends RelShuttleImpl {

    @Override public RelNode visit(LogicalProject project) {
      final Set<Integer> jsonInputFields = findJsonInputs(project.getInput());
      final Set<Integer> requiredJsonFieldsFromParent = stack.size() > 0
          ? requiredJsonOutputFromParent(stack.getLast()) : Collections.emptySet();

      final List<RexNode> originalProjections = project.getProjects();
      final ImmutableList.Builder<RexNode> newProjections = ImmutableList.builder();
      JsonFunctionRexRewriter rexRewriter = new JsonFunctionRexRewriter(jsonInputFields);
      for (int i = 0; i < originalProjections.size(); ++i) {
        if (requiredJsonFieldsFromParent.contains(i)) {
          newProjections.add(rexRewriter.forceChildJsonType(originalProjections.get(i)));
        } else {
          newProjections.add(originalProjections.get(i).accept(rexRewriter));
        }
      }

      RelNode newInput = project.getInput().accept(this);
      return LogicalProject.create(
          newInput,
          project.getHints(),
          newProjections.build(),
          project.getRowType().getFieldNames(),
          project.getVariablesSet());
    }

    private Set<Integer> requiredJsonOutputFromParent(RelNode relNode) {
      if (!(relNode instanceof Aggregate)) {
        return Collections.emptySet();
      }
      final Aggregate aggregate = (Aggregate) relNode;
      final List<AggregateCall> aggregateCalls = aggregate.getAggCallList();
      final ImmutableSet.Builder<Integer> result = ImmutableSet.builder();
      for (final AggregateCall call : aggregateCalls) {
        if (call.getAggregation() == SqlStdOperatorTable.JSON_OBJECTAGG) {
          result.add(call.getArgList().get(1));
        } else if (call.getAggregation() == SqlStdOperatorTable.JSON_ARRAYAGG) {
          result.add(call.getArgList().get(0));
        }
      }
      return result.build();
    }

    private Set<Integer> findJsonInputs(RelNode relNode) {
      if (!(relNode instanceof Aggregate)) {
        return Collections.emptySet();
      }
      final Aggregate aggregate = (Aggregate) relNode;
      final List<AggregateCall> aggregateCalls = aggregate.getAggCallList();
      final ImmutableSet.Builder<Integer> result = ImmutableSet.builder();
      for (int i = 0; i < aggregateCalls.size(); ++i) {
        final AggregateCall call = aggregateCalls.get(i);
        if (call.getAggregation() == SqlStdOperatorTable.JSON_OBJECTAGG
            || call.getAggregation() == SqlStdOperatorTable.JSON_ARRAYAGG) {
          result.add(aggregate.getGroupCount() + i);
        }
      }
      return result.build();
    }
  }

  /**
   * Used to rewrite json functions which is nested.
   */
  private class JsonFunctionRexRewriter extends RexShuttle {

    private final Set<Integer> jsonInputFields;

    JsonFunctionRexRewriter(Set<Integer> jsonInputFields) {
      this.jsonInputFields = jsonInputFields;
    }

    @Override public RexNode visitCall(RexCall call) {
      if (call.getOperator() == SqlStdOperatorTable.JSON_OBJECT) {
        final ImmutableList.Builder<RexNode> builder = ImmutableList.builder();
        for (int i = 0; i < call.operands.size(); ++i) {
          if ((i & 1) == 0 && i != 0) {
            builder.add(forceChildJsonType(call.operands.get(i)));
          } else {
            builder.add(call.operands.get(i));
          }
        }
        return rexBuilder.makeCall(SqlStdOperatorTable.JSON_OBJECT, builder.build());
      }
      if (call.getOperator() == SqlStdOperatorTable.JSON_ARRAY) {
        final ImmutableList.Builder<RexNode> builder = ImmutableList.builder();
        builder.add(call.operands.get(0));
        for (int i = 1; i < call.operands.size(); ++i) {
          builder.add(forceChildJsonType(call.operands.get(i)));
        }
        return rexBuilder.makeCall(SqlStdOperatorTable.JSON_ARRAY, builder.build());
      }
      return super.visitCall(call);
    }

    private RexNode forceChildJsonType(RexNode rexNode) {
      final RexNode childResult = rexNode.accept(this);
      if (isJsonResult(rexNode)) {
        return rexBuilder.makeCall(SqlStdOperatorTable.JSON_TYPE_OPERATOR, childResult);
      }
      return childResult;
    }

    private boolean isJsonResult(RexNode rexNode) {
      if (rexNode instanceof RexCall) {
        final RexCall call = (RexCall) rexNode;
        final SqlOperator operator = call.getOperator();
        return operator == SqlStdOperatorTable.JSON_OBJECT
            || operator == SqlStdOperatorTable.JSON_ARRAY
            || operator == SqlStdOperatorTable.JSON_VALUE;
      } else if (rexNode instanceof RexInputRef) {
        final RexInputRef inputRef = (RexInputRef) rexNode;
        return jsonInputFields.contains(inputRef.getIndex());
      }
      return false;
    }
  }

}
