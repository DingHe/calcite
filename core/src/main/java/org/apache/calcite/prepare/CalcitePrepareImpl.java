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

import org.apache.calcite.DataContexts;
import org.apache.calcite.adapter.enumerable.EnumerableCalc;
import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableInterpretable;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRules;
import org.apache.calcite.adapter.enumerable.RexToLixTranslator;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.AvaticaParameter;
import org.apache.calcite.avatica.ColumnMetaData;
import org.apache.calcite.avatica.Meta;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteSystemProperty;
import org.apache.calcite.interpreter.BindableConvention;
import org.apache.calcite.interpreter.Interpreters;
import org.apache.calcite.jdbc.CalcitePrepare;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.CalciteSchema.LatticeEntry;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.linq4j.function.Function1;
import org.apache.calcite.linq4j.tree.BinaryExpression;
import org.apache.calcite.linq4j.tree.BlockStatement;
import org.apache.calcite.linq4j.tree.Blocks;
import org.apache.calcite.linq4j.tree.ConstantExpression;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.MemberExpression;
import org.apache.calcite.linq4j.tree.MethodCallExpression;
import org.apache.calcite.linq4j.tree.NewExpression;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.linq4j.tree.PseudoField;
import org.apache.calcite.materialize.MaterializationService;
import org.apache.calcite.plan.Contexts;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCostFactory;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexExecutorImpl;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.runtime.Bindable;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.runtime.Typed;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.Table;
import org.apache.calcite.server.CalciteServerStatement;
import org.apache.calcite.server.DdlExecutor;
import org.apache.calcite.sql.SqlBinaryOperator;
import org.apache.calcite.sql.SqlExplainFormat;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserImplFactory;
import org.apache.calcite.sql.parser.impl.SqlParserImpl;
import org.apache.calcite.sql.type.ExtraSqlTypes;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlOperatorTables;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.SqlRexConvertletTable;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.apache.calcite.linq4j.Nullness.castNonNull;
import static org.apache.calcite.util.Static.RESOURCE;

import static java.util.Objects.requireNonNull;

/**
 * Shit just got real.
 *
 * <p>This class is public so that projects that create their own JDBC driver
 * and server can fine-tune preferences. However, this class and its methods are
 * subject to change without notice.
 */
// CalcitePrepareImpl 是 Apache Calcite 框架中最为核心的、承上启下的物理协调官与编译流水线总指挥部。
// 它实现了 CalcitePrepare 接口，负责将外界输入的原始 SQL 文本（或 Linq4j 表达式、未优化的 RelNode），
// 通过完整的编译、语义校验、关系代数转换、CBO 物理优化、最终动态生成运行期 Java 字节码，并打包成标准 JDBC 驱动（Avatica）可以直接消费的结果。
// SQL 编译生命周期的物理总调度：它将 SqlParser（解析器）、SqlValidator（校验器）、SqlToRelConverter（代数转换器）、VolcanoPlanner（优化器）以及 RexToLixTranslator（代码生成器）等分散的组件，串联成一条串行的、线程安全的编译装配流水线。
// 连接 Calcite 核心与 JDBC 驱动（Avatica）的桥梁：外界给它 SQL 字符串，它最终吐出 CalciteSignature。这个签名内含可以直接驱动游标拉取数据的可执行句柄 Bindable，使得上游 JDBC 框架能够无缝以标准数据库的形式消费 Calcite 联邦查询的结果。
// 视图（View）与 DDL 的安全审计枢纽：它不仅处理普通的 SELECT，还负责剖析视图底层映射的物理表及列约束（判断其是否可更新），并在遇到 CREATE/DROP 等 DDL 语句时，将其路由给具体的 DdlExecutor 立即物理落地。
public class CalcitePrepareImpl implements CalcitePrepare {
  // 控制是否启用基于 Linq4j 的 Enumerable 执行后端（单机内存迭代器模式）。其值读取自全局系统属性。
  @Deprecated // to be removed before 2.0
  public static final boolean ENABLE_ENUMERABLE =
      CalciteSystemProperty.ENABLE_ENUMERABLE.value();
  // 控制是否激活流式 SQL（Streaming SQL）支持。其值读取自全局系统属性。
  @Deprecated // to be removed before 2.0
  public static final boolean ENABLE_STREAM =
      CalciteSystemProperty.ENABLE_STREAM.value();
  // 持有 Calcite 默认将逻辑算子转为 Enumerable 物理算子的核心转换规则集。
  @Deprecated // to be removed before 2.0
  public static final List<RelOptRule> ENUMERABLE_RULES =
      EnumerableRules.ENUMERABLE_RULES;
  /** Whether the bindable convention should be the root convention of any
   * plan. If not, enumerable convention is the default. */
  // 核心物理转换路由指针。若通过 Hook.ENABLE_BINDABLE 激活（默认为 false），Calcite 的物理计划根节点将采用 BindableConvention（解释执行器模式）；
  // 否则采用默认的 EnumerableConvention（动态代码生成字节码模式）。
  public final boolean enableBindable = Hook.ENABLE_BINDABLE.get(false);
  // 简单查询白名单常量集。包含 "SELECT 1", "values 1" 等。
  // 用于在编译入口触发极速快道拦截，直接绕过高昂的 Parser/Planner 编译开销，瞬间返回静态签名。
  private static final Set<String> SIMPLE_SQLS =
      ImmutableSet.of(
          "SELECT 1",
          "select 1",
          "SELECT 1 FROM DUAL",
          "select 1 from dual",
          "values 1",
          "VALUES 1");

  public CalcitePrepareImpl() {
  }
  // 只解析和校验，不转代数树。
  // 将 SQL 字符串转换为抽象语法树（AST），并通过元数据进行语义校验，最终封装为 ParseResult 返回。常用于纯语法/语义检查工具。
  @Override public ParseResult parse(
      Context context, String sql) {
    return parse_(context, sql, false, false, false);
  }

  @Override public ConvertResult convert(Context context, String sql) {
    return (ConvertResult) parse_(context, sql, true, false, false);
  }

  @Override public AnalyzeViewResult analyzeView(Context context, String sql, boolean fail) {
    return (AnalyzeViewResult) parse_(context, sql, true, true, fail);
  }

  /** Shared implementation for {@link #parse}, {@link #convert} and
   * {@link #analyzeView}. */
  // 负责将静态的 SQL 文本，通过“词法/语法解析 (Parse) -> 元数据绑定与语义校验 (Validate) -> 物理转换分流 (Convert)”的一体化管道，
  // 最终转化为具备代数优化能力的编译期成果。
  // Context context（编译上下文环境快照）：连接层托举的编译上下文环境。它是一个无状态的快照包，为当前的编译线程锁死了多版本元数据树（rootSchema）、类型系统（typeFactory）和连接级会话配置（config），确保编译阶段的绝对线程安全。
  // String sql（原始 SQL 文本）： 外界输入的、未经处理的纯字符串 SQL 语句（例如 SELECT id, name FROM users WHERE age > 18）。
  // boolean convert（关系代数转换控制旗帜）：硬核分流开关。如果是 true，代表流水线在校验完 SQL 后不能停，必须立刻把抽象语法树（SqlNode）转换为关系代数树（RelNode / 逻辑执行计划）；如果是 false，则说明上游只需要语法和语义层面的校验结果，到此即可满足。
  // boolean analyze（视图分析控制旗帜）：传给下层转换器的控制信号。主要用于在分析物化视图（Materialized View）或普通视图时，控制是否需要深度解耦并提取视图底层的表依赖、列映射等血缘元数据。
  // boolean fail（强力容错控制旗帜）：控制在转换或视图分析失败时，是采取硬核的“断电抛异常”（true），还是优雅的“静默容错并返回局部结果”（false）。
  private ParseResult parse_(Context context, String sql, boolean convert,
      boolean analyze, boolean fail) {
    final JavaTypeFactory typeFactory = context.getTypeFactory();
    // 校验器的“眼睛”。
    // 它把根元数据树、当前默认的工作空间路径（如 [hive_db, public]）、类型工厂和连接配置统一缝合起来。
    // 未来当校验器看到 SQL 里写着 FROM users 时，就会调度这个 catalogReader 去指定的路径下翻账本，查阅有没有一张叫 users 的表。
    CalciteCatalogReader catalogReader =
        new CalciteCatalogReader(
            context.getRootSchema(),
            context.getDefaultSchemaPath(),
            typeFactory,
            context.config());
    // 调用内部方法 createParser(sql)。
    // 它会读取 context.config() 里的词法规则（如使用的是 JavaCC 还是 Babel 编译器、标识符用双引号还是反引号、大小写是否敏感），
    // 在内存中动态孵化出一个完全对齐配置方言的 SqlParser。
    SqlParser parser = createParser(sql); //获取默认的解析器
    SqlNode sqlNode;
    try {
      //解析出SqlNode节点
      sqlNode = parser.parseStmt();
    } catch (SqlParseException e) {
      throw new RuntimeException("parse failed", e);
    }
    // 调用 createSqlValidator，向其注入上下文与刚刚建好的阅读器，当场孵化出大名鼎鼎的 SqlValidator（语义校验器）。
    // 这个校验器内部挂载了 Calcite 官方标配的标准算子表（SqlStdOperatorTable）以及强类型体系。
    final SqlValidator validator = createSqlValidator(context, catalogReader);
    // 普通的 parse 只能确保你句型正确，而 validate 负责确保你说话有意义：
    SqlNode sqlNode1 = validator.validate(sqlNode);
    if (convert) {   // 是否需要转为RelNode节点
      return convert_(
          context, sql, analyze, fail, catalogReader, validator, sqlNode1);
    }
    return new ParseResult(this, validator, sql, sqlNode1,
        validator.getValidatedNodeType(sqlNode1));
  }
  // 连接 “SQL 语法世界（SqlNode）” 与 “关系代数世界（RelNode）” 的核心引力场。
  // 它的使命非常纯粹：将一棵经过语义校验的静态语法树，融化并重构成一棵具备物理执行潜能的逻辑关系代数树（逻辑执行计划）。
  // Context context（编译上下文环境）：托举着当前的会话快照，提供 JavaTypeFactory 和 RootSchema。
  // String sql（原始 SQL 文本）：输入的纯字符串 SQL 语句，主要用于最终包装结果或报错上下文。
  // boolean analyze（视图分析控制旗帜）：分流开关。
  // 如果是 true，说明调用方不仅想要关系代数树，还想深度解耦并提取出该语句作为视图（View）时的血缘元数据（如依赖了哪些物理表、字段是怎么映射映射的）。
  // CalciteCatalogReader catalogReader：上游 parse_ 刚刚建好的数据字典阅读器，包含元数据表的索引目录。
  // SqlValidator validator：上游已经完成使命的语义校验器，内部挂载了整棵 SQL 树的所有节点类型推导账本。
  // SqlNode sqlNode1（洗白后的语法树）：最核心的加工原料。已经通过了上游类型大安检、补全了隐式转换和默认前缀的终极 SqlNode。
  private ParseResult convert_(Context context, String sql, boolean analyze,
      boolean fail, CalciteCatalogReader catalogReader, SqlValidator validator,
      SqlNode sqlNode1) {
    final JavaTypeFactory typeFactory = context.getTypeFactory();
    // 锁定最终的物理执行契约（ConventionTrait）目标。
    // 如果系统开启了 enableBindable，则目标锚定为 BindableConvention（动态解释执行，不需要频繁生成编译字节码）；
    // 否则，锚定为 Calcite 最经典的 EnumerableConvention（基于内存迭代器、且会高频触发 CodeGen 动态生成 Java 匿名类代码的黄金执行契约）。
    final Convention resultConvention =
        enableBindable ? BindableConvention.INSTANCE
            : EnumerableConvention.INSTANCE;
    // Use the Volcano because it can handle the traits.
    // 源码注释说得非常直白：因为 Volcano 能完美处理 Traits（物理特征）。
    // 在转换阶段，Calcite 就必须把优化器塞进环境里，为后续在关系代数树上挂载并传播 Convention（调用契约）、RelCollation（排序特征）打下物理物理底座。
    final VolcanoPlanner planner = new VolcanoPlanner();
    planner.addRelTraitDef(ConventionTraitDef.INSTANCE);

    // 这里特意开启了 withTrimUnusedFields(true)。
    // 这是一个极为重要的剪枝优化技术。如果你的 SQL 写着 SELECT id FROM (SELECT id, name, age FROM users)，外层只需要 id。
    // 开启这个配置后，转换器在编织关系代数树的瞬间，就会自动把子查询里没有被用到的 name 和 age 两列从投影（Project）里无情地裁剪掉，从源头上减少内存字段冗余。
    final SqlToRelConverter.Config config =
        SqlToRelConverter.config().withTrimUnusedFields(true);

    final CalcitePreparingStmt preparingStmt =
        new CalcitePreparingStmt(this, context, catalogReader, typeFactory,
            context.getRootSchema(), null,
            createCluster(planner, new RexBuilder(typeFactory)),
            resultConvention, createConvertletTable());
    final SqlToRelConverter converter =
        preparingStmt.getSqlToRelConverter(validator, catalogReader, config);
    //执行sqlNode到RelNode的转换
    final RelRoot root = converter.convertQuery(sqlNode1, false, true);
    if (analyze) {  //是否要分析视图
      return analyze_(validator, sql, sqlNode1, root, fail);
    }
    return new ConvertResult(this, validator, sql, sqlNode1,
        validator.getValidatedNodeType(sqlNode1), root);
  }
  // 核心使命是：深度拆解、分析一个视图背后的关系代数树（RelNode），并判定该视图是否是一个“可写/可更新视图”（Modifiable/Updatable View）。
  // 根据 SQL 标准，如果用户对一个虚拟视图执行 INSERT、UPDATE 或 DELETE，只有当该视图结构足够简单（例如只基于单张物理表，且没有复杂的聚合或投影重复字段）时，引擎才能把写操作逆向翻译并下推给底层的物理表。
  // analyze_ 就是用来进行这场逆向血缘安检的。
  // SqlValidator validator（语义校验器）：用于在核验失败时，利用其底层的错误上下文抛出规范的 SQL 语法/校验异常（ValidationError）。
  // String sql 与 SqlNode sqlNode：当前查询的原始 SQL 文本和与之对应的 AST 语法树节点，主要在出错时用来精确定位错误锚点。
  // RelRoot root（关系代数根包裹器）：最核心的解剖原料。里面持有着刚刚转换出来的关系代数逻辑计划树（root.rel）。
  // boolean fail（强力断电旗帜）：控制当发现该视图不可写时，是直接简单粗暴地抛出异常熔断编译（true），还是静默返回一个标记为 modifiable = false 的分析结果集（false）。
  private AnalyzeViewResult analyze_(SqlValidator validator, String sql,
      SqlNode sqlNode, RelRoot root, boolean fail) {
    // 从关系代数树中提取行表达式构建器 rexBuilder。
    // 同时，用局部变量 rel 作为指针，准备对执行计划展开由上至下的剥洋葱式扫描。
    final RexBuilder rexBuilder = root.rel.getCluster().getRexBuilder();
    RelNode rel = root.rel;
    final RelNode viewRel = rel;
    Project project;
    // 如果最外层算子是 Project（代表 SELECT a, b），则将其暂存到 project 变量中，并将 rel 指针下移指向其子节点（Input）；
    // 如果没有，说明是 SELECT * 这种默认全投影，project 置为空。
    if (rel instanceof Project) {
      project = (Project) rel;
      rel = project.getInput();
    } else {
      project = null;
    }
    // 继续向下剥离。如果发现了 Filter 算子（代表 WHERE 条件），则将其暂存至 filter 变量，并将指针再次下移指向子节点。
    Filter filter;
    if (rel instanceof Filter) {
      filter = (Filter) rel;
      rel = filter.getInput();
    } else {
      filter = null;
    }
    // 经过层层剥离，最底层必须是一个 TableScan（代表物理表）。如果是 Join 或 Aggregate（聚合算子），scan 就会沦为空。
    TableScan scan;
    if (rel instanceof TableScan) {
      scan = (TableScan) rel;
    } else {
      scan = null;
    }
    // 红线一：可更新视图必须只能基于单张物理表！
    // 如果 scan == null（说明视图包含了 JOIN、UNION 或 GROUP BY），这在关系代数逆向映射中是无法直接更新的。
    // 如果 fail 为 true，直接抛出 modifiableViewMustBeBasedOnSingleTable（可写视图必须基于单表）的致命错误；
    // 否则，返回一个 modifiable = false 的失败分析铁盒。
    if (scan == null) {
      if (fail) {
        throw validator.newValidationError(sqlNode,
            RESOURCE.modifiableViewMustBeBasedOnSingleTable());
      }
      return new AnalyzeViewResult(this, validator, sql, sqlNode,
          validator.getValidatedNodeType(sqlNode), root, null, null, null,
          null, false);
    }
    // 通过 scan 算子，顺藤摸瓜提取出底层物理表的 Catalog 字典节点（targetRelTable）、物理表行类型（targetRowType）、真正的物理表实体（table）以及它的全限定路径（tablePath，如 [catalog, db, t]）。
    final RelOptTable targetRelTable = scan.getTable();
    final RelDataType targetRowType = targetRelTable.getRowType();
    final Table table = targetRelTable.unwrapOrThrow(Table.class);
    final List<String> tablePath = targetRelTable.getQualifiedName();
    List<Integer> columnMapping;
    final Map<Integer, RexNode> projectMap = new HashMap<>();
    // 分析视图到物理表的列映射血缘（Column Mapping）
    if (project == null) {
      // 如果 project == null（即 SELECT *），视图和物理表的列是完全一一对应的，直接使用物理表索引范围初始化映射。
      columnMapping = ImmutableIntList.range(0, targetRowType.getFieldCount());
    } else {
      columnMapping = new ArrayList<>();
      for (Ord<RexNode> node : Ord.zip(project.getProjects())) {
        if (node.e instanceof RexInputRef) {
          RexInputRef rexInputRef = (RexInputRef) node.e;
          int index = rexInputRef.getIndex();
          // 物理机制：红线二：物理表的同一列在视图中绝不能被 SELECT 两次！
          if (projectMap.get(index) != null) {
            if (fail) {
              throw validator.newValidationError(sqlNode,
                  RESOURCE.moreThanOneMappedColumn(
                      targetRowType.getFieldList().get(index).getName(),
                      Util.last(tablePath)));
            }
            return new AnalyzeViewResult(this, validator, sql, sqlNode,
                validator.getValidatedNodeType(sqlNode), root, null, null, null,
                null, false);
          }
          projectMap.put(index, rexBuilder.makeInputRef(viewRel, node.i));
          columnMapping.add(index);
        } else {
          columnMapping.add(-1);
        }
      }
    }
    final RexNode constraint;
    // 如果视图自带 WHERE 条件，将其提取为 constraint；否则默认给个常数 true 表达式。
    if (filter != null) {
      constraint = filter.getCondition();
    } else {
      constraint = rexBuilder.makeLiteral(true);
    }
    final List<RexNode> filters = new ArrayList<>();
    // If we put a constraint in projectMap above, then filters will not be empty despite
    // being a modifiable view.
    final List<RexNode> filters2 = new ArrayList<>();
    boolean retry = false;
    // 核心方法 RelOptUtil.inferViewPredicates：它的核心黑魔法是，根据当前视图的投影映射，将 WHERE 条件逆向推导，看能否将其转化为对底层物理表某些列的等值约束表达式。推导出来的物理约束会被压入 filters。
    RelOptUtil.inferViewPredicates(projectMap, filters, constraint);
    if (fail && !filters.isEmpty()) {
      final Map<Integer, RexNode> projectMap2 = new HashMap<>();
      RelOptUtil.inferViewPredicates(projectMap2, filters2, constraint);
      if (!filters2.isEmpty()) {
        throw validator.newValidationError(sqlNode,
            RESOURCE.modifiableViewMustHaveOnlyEqualityPredicates());
      }
      retry = true;
    }

    // Check that all columns that are not projected have a constant value
    for (RelDataTypeField field : targetRowType.getFieldList()) {
      final int x = columnMapping.indexOf(field.getIndex());
      if (x >= 0) {
        assert Util.skip(columnMapping, x + 1).indexOf(field.getIndex()) < 0
            : "column projected more than once; should have checked above";
        continue; // target column is projected
      }
      if (projectMap.get(field.getIndex()) != null) {
        continue; // constant expression
      }
      if (field.getType().isNullable()) {
        continue; // don't need expression for nullable columns; NULL suffices
      }
      if (fail) {
        throw validator.newValidationError(sqlNode,
            RESOURCE.noValueSuppliedForViewColumn(field.getName(),
                Util.last(tablePath)));
      }
      return new AnalyzeViewResult(this, validator, sql, sqlNode,
          validator.getValidatedNodeType(sqlNode), root, null, null, null,
          null, false);
    }
    // 如果前面所有红线关卡奇迹般地全部通过，则计算出 modifiable = true。
    final boolean modifiable = filters.isEmpty() || retry && filters2.isEmpty();
    return new AnalyzeViewResult(this, validator, sql, sqlNode,
        validator.getValidatedNodeType(sqlNode), root, modifiable ? table : null,
        ImmutableList.copyOf(tablePath),
        constraint, ImmutableIntList.copyOf(columnMapping),
        modifiable);
  }
  // executeDdl 是处理 DDL（Data Definition Language，数据定义语言，如 CREATE TABLE、DROP VIEW 等） 的最高层级物理入口方法
  // 与常规的 SELECT 或 INSERT 查询不同，DDL 语句不需要经过复杂的 Volcano 成本模型优化，也不需要生成运行期的 Java 字节码（CodeGen）。它的核心使命非常纯粹：直接修改、更新当前连接在内存中的元数据树（Catalog/Schema）。
  // Context context（编译上下文环境）：托举着当前的会话快照。虽然不需要它提供优化器，但 DDL 执行器需要通过它拿到 getMutableRootSchema()（可变的根元数据树）。
  // SqlNode node（DDL 抽象语法树）：最核心的加工原料。这是一个已经由 SqlParser 解析出来的 DDL 语法树节点。

  @Override public void executeDdl(Context context, SqlNode node) {
    final CalciteConnectionConfig config = context.config();
    // Calcite 官方标配的解析器工厂是 SqlParserImpl.FACTORY（它只认得标准的 DML，如 SELECT，遇到 CREATE TABLE 就会报语法错误）。
    // 如果用户想要支持 DDL，就必须在连接字符串或配置中将 parserFactory 替换为扩展的解析器工厂（例如 org.apache.calcite.sql.parser.ddl.SqlDdlParserImpl#FACTORY）。
    final SqlParserImplFactory parserFactory =
        config.parserFactory(SqlParserImplFactory.class, SqlParserImpl.FACTORY);
    // Calcite 巧妙地把“谁负责解析 DDL”和“谁负责执行 DDL”通过同一个工厂（SqlParserImplFactory）进行了强绑定。
    final DdlExecutor ddlExecutor = parserFactory.getDdlExecutor();
    ddlExecutor.executeDdl(context, node);
  }

  /** Factory method for default SQL parser. */
  protected SqlParser createParser(String sql) {
    return createParser(sql, createParserConfig());
  }

  /** Factory method for SQL parser with a given configuration. */
  protected SqlParser createParser(String sql, SqlParser.Config parserConfig) {
    return SqlParser.create(sql, parserConfig);
  }

  @Deprecated // to be removed before 2.0
  protected SqlParser createParser(String sql,
      SqlParser.ConfigBuilder parserConfig) {
    return createParser(sql, parserConfig.build());
  }

  /** Factory method for SQL parser configuration. */
  protected SqlParser.Config parserConfig() {
    return SqlParser.config();
  }

  @Deprecated // to be removed before 2.0
  protected SqlParser.ConfigBuilder createParserConfig() {
    return SqlParser.configBuilder();
  }

  /** Factory method for default convertlet table. */
  // 使命虽然简单，但在整个 SQL 编译管道的“第二阶段”（即 SqlNode 向 RelNode 转换的过程）中，扮演着极其重要的表达式方言翻译官角色。
  protected SqlRexConvertletTable createConvertletTable() {
    // 标量表达式转换表契约
    // 它是一个注册表（Registry），里面密密麻麻地记录了“当把 SQL 语法树（SqlNode）里的某个函数或操作符转换成行级表达式（RexNode）时，应该采用什么样的特殊物理翻译策略”。
    return StandardConvertletTable.INSTANCE;
  }

  /** Factory method for cluster. */
  protected RelOptCluster createCluster(RelOptPlanner planner,
      RexBuilder rexBuilder) {
    return RelOptCluster.create(planner, rexBuilder);
  }

  /** Creates a collection of planner factories.
   *
   * <p>The collection must have at least one factory, and each factory must
   * create a planner. If the collection has more than one planner, Calcite will
   * try each planner in turn.
   *
   * <p>One of the things you can do with this mechanism is to try a simpler,
   * faster, planner with a smaller rule set first, then fall back to a more
   * complex planner for complex and costly queries.
   *
   * <p>The default implementation returns a factory that calls
   * {@link #createPlanner(org.apache.calcite.jdbc.CalcitePrepare.Context)}.
   */
  protected List<Function1<Context, RelOptPlanner>> createPlannerFactories() {
    return Collections.singletonList(
        context -> createPlanner(context, null, null));
  }

  /** Creates a query planner and initializes it with a default set of
   * rules. */
  protected RelOptPlanner createPlanner(CalcitePrepare.Context prepareContext) {
    return createPlanner(prepareContext, null, null);
  }

  /** Creates a query planner and initializes it with a default set of
   * rules. */
  // createPlanner 是一个地位极其显赫的核心保护级工厂方法。
  // 如果说之前的 parse_ 和 convert_ 是在搭建舞台、准备道具，那么 createPlanner 就是在孵化整场大戏的灵魂——CBO（基于成本模型）的优化器引擎。
  // CalcitePrepare.Context prepareContext（准备上下文）： 编译期的全局配置上下文。优化器需要从中读取核心系统配置（如是否开启物化视图、是否激活自顶向下优化 topDownOpt），并从中获取大数据计算生态（如 Spark）的特殊处理器。
  // org.apache.calcite.plan.@Nullable Context externalContext（外部注入上下文）：注意这里的 Context 包名是 org.apache.calcite.plan，它是专为优化器设计的数据载体。允许外部宿主系统（如 Apache Flink 或 Phoenix）在初始化优化器时，注入其自定义的连接配置、会话变量或运行时全局参数。可以为 null。
  // @Nullable RelOptCostFactory costFactory（成本因子模型工厂）：CBO 的天平。用于定义怎么去算一个算子的 CPU、I/O 和内存开销（Cost）。如果传入自定义工厂，Volcano 就会按照你的规则去挑最优解；如果为 null，则使用 Calcite 官方标准的 Volcano 成本模型。
  protected RelOptPlanner createPlanner(
      final CalcitePrepare.Context prepareContext,
      org.apache.calcite.plan.@Nullable Context externalContext,
      @Nullable RelOptCostFactory costFactory) {
    // 如果调用方没有显式传入外部优化上下文（externalContext == null），
    // Calcite 会采用防御性编程，直接通过 Contexts.of(...) 将 prepareContext 里的会话级连接配置（CalciteConnectionConfig）包装起来，
    // 强行塞给 externalContext，确保后续优化器内部能读取到系统的基础配置。
    if (externalContext == null) {
      externalContext = Contexts.of(prepareContext.config());
    }
    // 正式孵化 VolcanoPlanner（火山优化器）
    final VolcanoPlanner planner =
        new VolcanoPlanner(costFactory, externalContext);
    // 挂载常量折叠与表达式求值执行器（RexExecutor）
    // 用来实现 RBO/CBO 中的“常量折叠（Constant Folding）”优化规则的。当优化规则在优化期间看到关系代数树里写着 WHERE age > 10 + 8 时，优化器不需要等到运行期，而是直接驱动这个内部的 RexExecutorImpl，在编译期当场把 10 + 8 算出来，将算子当场改写为 WHERE age > 18。
    planner.setExecutor(new RexExecutorImpl(DataContexts.EMPTY));
    // 向优化器注册调用契约特征定义。
    planner.addRelTraitDef(ConventionTraitDef.INSTANCE);
    // 检查 Calcite 内部系统级变量。如果开启了 ENABLE_COLLATION_TRAIT，则向优化器追加注册排序特征定义（RelCollationTraitDef）
    if (CalciteSystemProperty.ENABLE_COLLATION_TRAIT.value()) {
      planner.addRelTraitDef(RelCollationTraitDef.INSTANCE);
    }
    // 将用户在连接配置里指定的 topDownOpt 状态同步给优化器。
    // 传统的 Volcano 模型是自底向上（Bottom-Up）生成等价类并计算 Cost。
    // Calcite 在中后期引入了类似 Cascades 优化器的自顶向下（Top-Down）空间搜索与剪枝技术。这一行代码直接决定了优化器接下来采用哪种搜索寻路算法。
    planner.setTopDownOpt(prepareContext.config().topDownOpt());
    // 整段代码最繁重的物理灌注点。
    // 调用 RelOptUtil.registerDefaultRules 静态方法，向 planner 内部疯狂注册成百上千条 Calcite 官方标配的经典优化规则。
    RelOptUtil.registerDefaultRules(planner,
        prepareContext.config().materializationsEnabled(),
        enableBindable);

    final CalcitePrepare.SparkHandler spark = prepareContext.spark();
    // Calcite 原生对 Apache Spark 等外部计算引擎留出的扩展通道。如果当前的运行环境启用了 Spark 模式，会调用 spark.registerRules。
    if (spark.enabled()) {
      spark.registerRules(
          new SparkHandler.RuleSetBuilder() {
            @Override public void addRule(RelOptRule rule) {
              // TODO:
            }

            @Override public void removeRule(RelOptRule rule) {
              // TODO:
            }
          });
    }
    // Calcite 的阿喀琉斯之踵和终极后门。代码执行到这里，默认规则已经装配完毕，但在正式返回前，Calcite 触发了全局事件钩子 Hook.PLANNER.run(planner)。
    Hook.PLANNER.run(planner); // allow test to add or remove rules

    return planner;
  }

  @Override public <T> CalciteSignature<T> prepareQueryable(
      Context context,
      Queryable<T> queryable) {
    return prepare_(context, Query.of(queryable), queryable.getElementType(),
        -1);
  }

  @Override public <T> CalciteSignature<T> prepareSql(
      Context context,
      Query<T> query,
      Type elementType,
      long maxRowCount) {
    return prepare_(context, query, elementType, maxRowCount);
  }
  // prepare_ 方法是整个 SQL 编译、优化与代码生成流水线的总调度司令部（The Grand Coordinator）。
  // 当一条 DML 查询（如 SELECT）通过 JDBC 驱动或外部 API 砸进 Calcite 时，首先迎上来的就是这个方法。它负责评估 SQL 复杂性以进行快慢分流，
  // 并在复杂查询时循环调度不同的优化器工厂，尝试用最完美的策略将 SQL 编译为可直接执行的二进制/中间件签名（CalciteSignature）。
  // Context context（编译上下文环境）：托举着当前的会话快照，提供元数据树（rootSchema）、类型系统（typeFactory）和连接级会话配置（config）。
  // Query<T> query（查询包装对象）：里面死死包裹着外界输入的原始 SQL 字符串（query.sql），以及一些特定于查询的运行时泛型参数。
  // Type elementType（元素类型）：指定最终执行结果集里，每一行数据的物理表现形式。例如，如果你希望返回的每行数据是一个 Object[]（对象数组），或者是一个特定的 Java 实体类（Bean），甚至是一个 Linq 的 Record 对象，都由该类型背书。
  // long maxRowCount（最大行数限制）：
  <T> CalciteSignature<T> prepare_(
      Context context,
      Query<T> query,
      Type elementType,
      long maxRowCount) {
    // 极速分流硬核机制。
    if (SIMPLE_SQLS.contains(query.sql)) {
      return simplePrepare(context, castNonNull(query.sql));
    }
    // 从上下文提取唯一的类型工厂，并原地实例化一个 CalciteCatalogReader。这为接下来的语义校验和算子转换锁定了查阅元数据和数据字典的“天眼”
    final JavaTypeFactory typeFactory = context.getTypeFactory();
    CalciteCatalogReader catalogReader =
        new CalciteCatalogReader(
            context.getRootSchema(),
            context.getDefaultSchemaPath(),
            typeFactory,
            context.config());
    // 获得一个优化器工厂函数列表
    final List<Function1<Context, RelOptPlanner>> plannerFactories =
        createPlannerFactories();
    if (plannerFactories.isEmpty()) {
      throw new AssertionError("no planner factories");
    }
    RuntimeException exception = Util.FoundOne.NULL;
    // 开启一个 for 循环，准备依次取出每一个优化器工厂。
    // 这是 Calcite 为了保证查询一定有解而设置的“备胎容错体系”。
    for (Function1<Context, RelOptPlanner> plannerFactory : plannerFactories) {
      // 将当前的 context 灌入当前的工厂函数，正式生产出一个具体的优化器实例（planner）
      final RelOptPlanner planner = plannerFactory.apply(context);
      if (planner == null) {
        throw new AssertionError("factory returned null planner");
      }
      try {
        // 把上下文、元素类型、元数据阅读器以及当前正在挑大梁的这个优化器（planner）缝合在一起，构建出这一轮优化冲锋的准备语句大总管 preparingStmt。
        CalcitePreparingStmt preparingStmt =
            getPreparingStmt(context, elementType, catalogReader, planner);
        // 在 prepare2_ 内部，Calcite 会真正引爆我们之前死磕过的整个链路——调用 parse_、convert_ 变成关系代数，
        // 然后执行极其繁重的 CBO 规则搜索，最终将逻辑计划优化为最优物理计划，并交付给 CodeGen 生成 Java 字节码。
        return prepare2_(context, query, elementType, maxRowCount,
            catalogReader, preparingStmt);
      } catch (RelOptPlanner.CannotPlanException e) {
        exception = e;
      }
    }
    throw exception;
  }

  /** Returns CalcitePreparingStmt
   *
   * <p>Override this function to return a custom {@link CalcitePreparingStmt} and
   * {@link #createSqlValidator} to enable custom validation logic.
   */
  protected CalcitePreparingStmt getPreparingStmt(
      Context context,
      Type elementType,
      CalciteCatalogReader catalogReader,
      RelOptPlanner planner) {
    final JavaTypeFactory typeFactory = context.getTypeFactory();
    final EnumerableRel.Prefer prefer;
    if (elementType == Object[].class) {
      prefer = EnumerableRel.Prefer.ARRAY;
    } else {
      prefer = EnumerableRel.Prefer.CUSTOM;
    }
    final Convention resultConvention =
        enableBindable ? BindableConvention.INSTANCE
            : EnumerableConvention.INSTANCE;
    return new CalcitePreparingStmt(this, context, catalogReader, typeFactory,
            context.getRootSchema(), prefer, createCluster(planner, new RexBuilder(typeFactory)),
            resultConvention, createConvertletTable());
  }

  /** Quickly prepares a simple SQL statement, circumventing the usual
   * preparation process. */
  private static <T> CalciteSignature<T> simplePrepare(Context context, String sql) {
    final JavaTypeFactory typeFactory = context.getTypeFactory();
    final RelDataType x =
        typeFactory.builder()
            .add(SqlUtil.deriveAliasFromOrdinal(0), SqlTypeName.INTEGER)
            .build();
    @SuppressWarnings("unchecked")
    final List<T> list = (List) ImmutableList.of(1);
    final List<String> origin = null;
    final List<@Nullable List<String>> origins =
        Collections.nCopies(x.getFieldCount(), origin);
    final List<ColumnMetaData> columns =
        getColumnMetaDataList(typeFactory, x, x, origins);
    final Meta.CursorFactory cursorFactory =
        Meta.CursorFactory.deduce(columns, null);
    return new CalciteSignature<>(
        sql,
        ImmutableList.of(),
        ImmutableMap.of(),
        x,
        columns,
        cursorFactory,
        context.getRootSchema(),
        ImmutableList.of(),
        -1, dataContext -> Linq4j.asEnumerable(list),
        Meta.StatementType.SELECT);
  }

  /**
   * Deduces the broad type of statement.
   * Currently returns SELECT for most statement types, but this may change.
   *
   * @param kind Kind of statement
   */
  private static Meta.StatementType getStatementType(SqlKind kind) {
    switch (kind) {
    case INSERT:
    case DELETE:
    case UPDATE:
    case MERGE:
      return Meta.StatementType.IS_DML;
    default:
      return Meta.StatementType.SELECT;
    }
  }

  /**
   * Deduces the broad type of statement for a prepare result.
   * Currently returns SELECT for most statement types, but this may change.
   *
   * @param preparedResult Prepare result
   */
  private static Meta.StatementType getStatementType(Prepare.PreparedResult preparedResult) {
    if (preparedResult.isDml()) {
      return Meta.StatementType.IS_DML;
    } else {
      return Meta.StatementType.SELECT;
    }
  }
  // prepare2_ 是真正的技术终点站。
  // 上游的 prepare_ 只是在轮询和选择优化器，而一旦进入 prepare2_，系统将拉起全套的物理编译链条：
  // 配置对齐的语法解析 -> DDL/DML/关系代数多路分流 -> CBO 核心物理优化 -> 参数化元数据提取 -> 动态生成可执行字节码（CodeGen） -> 封装交付 JDBC 执行签名（CalciteSignature）
  // Context context（连接上下文）：锁定了当前的连接配置（config()）和类型工厂（typeFactory）。
  // Query<T> query（多模态查询载体）：它是 Calcite 多源驱动的体现。里面可能装着纯 SQL 文本（query.sql），或者是一个 Linq 表达式对象（query.queryable），亦或是一棵已经由外部构建好的关系代数树（query.rel）。
  // Type elementType：最终执行结果集中每一行 Java 对象的物理物理形态（如 Object[] 或自定义 POJO 实体）。
  // long maxRowCount：最大行数限制，用于物理限流。
  // CalciteCatalogReader catalogReader：由上游建好的 Catalog 阅读器，负责元数据字典检索。
  // CalcitePreparingStmt preparingStmt：本次物理冲锋的总控制台。它肚子里死死拽着我们上一期死磕出来的 VolcanoPlanner（优化器）以及优化集群（RelOptCluster）。
  <T> CalciteSignature<T> prepare2_(
      Context context,
      Query<T> query,
      Type elementType,
      long maxRowCount,
      CalciteCatalogReader catalogReader,
      CalcitePreparingStmt preparingStmt) {
    final JavaTypeFactory typeFactory = context.getTypeFactory();

    final RelDataType x;
    final Prepare.PreparedResult preparedResult;
    final Meta.StatementType statementType;
    if (query.sql != null) {
      // 如果输入的是 sql 文本，Calcite 必须现场拉起解析管道。
      // 这里将 JDBC 连接字符串里带的所有词法/语法参数（大小写敏感度 caseSensitive、标识符引用符 quoting 等）全量同步给 SqlParser.Config，
      // 并注入可能存在的定制解析器工厂（parserFactory）。
      final CalciteConnectionConfig config = context.config();
      SqlParser.Config parserConfig = parserConfig()
          .withQuotedCasing(config.quotedCasing())
          .withUnquotedCasing(config.unquotedCasing())
          .withQuoting(config.quoting())
          .withConformance(config.conformance())
          .withCaseSensitive(config.caseSensitive());
      final SqlParserImplFactory parserFactory =
          config.parserFactory(SqlParserImplFactory.class, null);
      if (parserFactory != null) {
        parserConfig = parserConfig.withParserFactory(parserFactory);
      }
      // 驱动 JavaCC 状态机，把 SQL 文本暴力肢解并组装成抽象语法树 SqlNode，同时通过 sqlNode.getKind() 判定它是 SELECT、INSERT 还是其他语句形态。
      SqlParser parser = createParser(query.sql,  parserConfig);
      SqlNode sqlNode;
      try {
        sqlNode = parser.parseStmt();
        statementType = getStatementType(sqlNode.getKind());
      } catch (SqlParseException e) {
        throw new RuntimeException(
            "parse failed: " + e.getMessage(), e);
      }
      // 触发 PARSE_TREE 钩子，方便测试层或监控层拦截原始语法树。
      Hook.PARSE_TREE.run(new Object[] {query.sql, sqlNode});
      // 如果是 CREATE TABLE 等 DDL 语句，直接调用我们死磕过的 executeDdl 修改元数据大账本，然后直接原地 return 一个空签名，不向下推入关系代数层。
      if (sqlNode.getKind().belongsTo(SqlKind.DDL)) {
        executeDdl(context, sqlNode);

        return new CalciteSignature<>(query.sql,
            ImmutableList.of(),
            ImmutableMap.of(), null,
            ImmutableList.of(), Meta.CursorFactory.OBJECT,
            null, ImmutableList.of(), -1, null,
            Meta.StatementType.OTHER_DDL);
      }
      // 实例化语义校验器 validator。
      final SqlValidator validator = preparingStmt.createSqlValidator(catalogReader);

      // validator.validate(sqlNode)（语义与强类型大安检）。
      // SqlToRelConverter.convertQuery（将语法树物理转换成 RelNode 关系代数树）。
      // 引爆 VolcanoPlanner 进行 CBO 成本大优化，将逻辑计划变换为最优物理执行计划。
      // 引爆 CodeGen（代码生成器）：将物理执行计划编译、重构，动态生成能在 JVM 里跑的 Java 匿名类底层字节码。
      preparedResult =
          preparingStmt.prepareSql(sqlNode, Object.class, validator, true);
      switch (sqlNode.getKind()) {
      case INSERT:
      case DELETE:
      case UPDATE:
      case MERGE:
      case EXPLAIN:
        // 如果是 INSERT/UPDATE 等 DML 语句或 EXPLAIN 语句，它们执行完后吐给客户端的往往不是表数据，而是“受影响的行数（如 ROWCOUNT INT）”，
        // 因此调用 createDmlRowType 伪造一个 DML 的行类型。
        // FIXME: getValidatedNodeType is wrong for DML
        x = RelOptUtil.createDmlRowType(sqlNode.getKind(), typeFactory);
        break;
      default:
        // 如果是普通的 SELECT（default 路由），直接去校验器账本里抓取这句查询吐出来的真实字段列类型 x。
        x = validator.getValidatedNodeType(sqlNode);
      }
      // 多模态入口分流之二：处理 Linq 表达式（Queryable）
    } else if (query.queryable != null) {
      x = context.getTypeFactory().createType(elementType);
      preparedResult =
          preparingStmt.prepareQueryable(query.queryable, x);
      statementType = getStatementType(preparedResult);
    } else {
      // 如果输入不是 SQL，而是通过类似 Linq-to-DB 传进来的 queryable 表达式树。
      // 直接用类型工厂根据 elementType 划定行类型，然后调用 prepareQueryable 将其转换为关系代数、进行优化和编译。
      assert query.rel != null;
      x = query.rel.getRowType();
      preparedResult = preparingStmt.prepareRel(query.rel);
      statementType = getStatementType(preparedResult);
    }
    // 如果 SQL 写着 WHERE id = ?，这里的 parameterRowType 会精准抓取到每一个占位符 ? 的物理期望类型、精度（Precision）、刻度（Scale）
    // 以及在 Java 中对应的类名，将其包装进 AvaticaParameter 集合，以便上游 JDBC 驱动在执行 setInt(1, xxx) 时展开类型安全校验。
    final List<AvaticaParameter> parameters = new ArrayList<>();
    final RelDataType parameterRowType = preparedResult.getParameterRowType();
    for (RelDataTypeField field : parameterRowType.getFieldList()) {
      RelDataType type = field.getType();
      parameters.add(
          new AvaticaParameter(
              false,
              getPrecision(type),
              getScale(type),
              getTypeOrdinal(type),
              getTypeName(type),
              getClassName(type),
              field.getName()));
    }
    // 将刚才得到的行类型 x 规范化为结构体类型 jdbcType。
    // 同时通过 preparedResult.getFieldOrigins() 提取出结果集里的每一列最初来自于哪张物理表的哪个字段（数据血缘溯源）。
    // 最后通过 getColumnMetaDataList 组装出 JDBC 结果集（ResultSet）所需的全量列元数据。
    RelDataType jdbcType = makeStruct(typeFactory, x);
    final List<? extends @Nullable List<String>> originList = preparedResult.getFieldOrigins();
    final List<ColumnMetaData> columns =
        getColumnMetaDataList(typeFactory, x, jdbcType, originList);
    Class resultClazz = null;
    if (preparedResult instanceof Typed) {
      resultClazz = (Class) ((Typed) preparedResult).getElementType();
    }
    // 如果物理执行契约是 BindableConvention，游标底层统一格式化为 Meta.CursorFactory.ARRAY（数组游标）
    // 否则，调用 deduce 根据列元数据和生成的 Java 类进行智能推导（如推导为 Map 游标或特定 Object 游标）。
    final Meta.CursorFactory cursorFactory =
        preparingStmt.resultConvention == BindableConvention.INSTANCE
            ? Meta.CursorFactory.ARRAY
            : Meta.CursorFactory.deduce(columns, resultClazz);
    //noinspection unchecked
    // 这个 bindable 对象内部死死拽着刚刚由 Calcite 运行时利用 Janino 框架动态编译生成的内存类实例。
    // 它里面含有一个 bind 方法。一旦调用，就会拉起生成的编译代码，里面全是用 Java 编织的操作算子逻辑（如 while(hasNext) { ... }）。
    final Bindable<T> bindable = preparedResult.getBindable(cursorFactory);
    // new 一个 CalciteSignature（Calcite 物理签名），把原始 SQL、参数列表、列元数据、游标工厂、多版本元数据树根以及最重要的字节码执行体 bindable 全量焊死在这个大铁盒里，光荣地交付给 JDBC 驱动。
    return new CalciteSignature<>(
        query.sql,
        parameters,
        preparingStmt.internalParameters,
        jdbcType,
        columns,
        cursorFactory,
        context.getRootSchema(),
        preparedResult instanceof Prepare.PreparedResultImpl
            ? ((Prepare.PreparedResultImpl) preparedResult).collations
            : ImmutableList.of(),
        maxRowCount,
        bindable,
        statementType);
  }

  private static SqlValidator createSqlValidator(Context context,
      CalciteCatalogReader catalogReader) {
    final SqlOperatorTable opTab0 =
        context.config().fun(SqlOperatorTable.class,
            SqlStdOperatorTable.instance());
    final List<SqlOperatorTable> list = new ArrayList<>();
    list.add(opTab0);
    list.add(catalogReader);
    final SqlOperatorTable opTab = SqlOperatorTables.chain(list);
    final JavaTypeFactory typeFactory = context.getTypeFactory();
    final CalciteConnectionConfig connectionConfig = context.config();
    final SqlValidator.Config config = SqlValidator.Config.DEFAULT
        .withLenientOperatorLookup(connectionConfig.lenientOperatorLookup())
        .withConformance(connectionConfig.conformance())
        .withDefaultNullCollation(connectionConfig.defaultNullCollation())
        .withIdentifierExpansion(true);
    return new CalciteSqlValidator(opTab, catalogReader, typeFactory,
        config);
  }

  private static List<ColumnMetaData> getColumnMetaDataList(
      JavaTypeFactory typeFactory, RelDataType x, RelDataType jdbcType,
      List<? extends @Nullable List<String>> originList) {
    final List<ColumnMetaData> columns = new ArrayList<>();
    for (Ord<RelDataTypeField> pair : Ord.zip(jdbcType.getFieldList())) {
      final RelDataTypeField field = pair.e;
      final RelDataType type = field.getType();
      final RelDataType fieldType =
          x.isStruct() ? x.getFieldList().get(pair.i).getType() : type;
      columns.add(
          metaData(typeFactory, columns.size(), field.getName(), type,
              fieldType, originList.get(pair.i)));
    }
    return columns;
  }

  private static ColumnMetaData metaData(JavaTypeFactory typeFactory, int ordinal,
      String fieldName, RelDataType type, @Nullable RelDataType fieldType,
      @Nullable List<String> origins) {
    final ColumnMetaData.AvaticaType avaticaType =
        avaticaType(typeFactory, type, fieldType);
    return new ColumnMetaData(
        ordinal,
        false,
        true,
        false,
        false,
        type.isNullable()
            ? DatabaseMetaData.columnNullable
            : DatabaseMetaData.columnNoNulls,
        true,
        type.getPrecision(),
        fieldName,
        origin(origins, 0),
        origin(origins, 2),
        getPrecision(type),
        getScale(type),
        origin(origins, 1),
        null,
        avaticaType,
        true,
        false,
        false,
        avaticaType.columnClassName());
  }

  private static ColumnMetaData.AvaticaType avaticaType(JavaTypeFactory typeFactory,
      RelDataType type, @Nullable RelDataType fieldType) {
    final String typeName = getTypeName(type);
    if (type.getComponentType() != null) {
      final ColumnMetaData.AvaticaType componentType =
          avaticaType(typeFactory, type.getComponentType(), null);
      final Type clazz = typeFactory.getJavaClass(type.getComponentType());
      final ColumnMetaData.Rep rep = ColumnMetaData.Rep.of(clazz);
      assert rep != null;
      return ColumnMetaData.array(componentType, typeName, rep);
    } else {
      int typeOrdinal = getTypeOrdinal(type);
      switch (typeOrdinal) {
      case Types.STRUCT:
        final List<ColumnMetaData> columns = new ArrayList<>(type.getFieldList().size());
        for (RelDataTypeField field : type.getFieldList()) {
          columns.add(
              metaData(typeFactory, field.getIndex(), field.getName(),
                  field.getType(), null, null));
        }
        return ColumnMetaData.struct(columns);
      case ExtraSqlTypes.GEOMETRY:
        typeOrdinal = Types.VARCHAR;
        // fall through
      default:
        final Type clazz =
            typeFactory.getJavaClass(Util.first(fieldType, type));
        final ColumnMetaData.Rep rep = ColumnMetaData.Rep.of(clazz);
        assert rep != null;
        return ColumnMetaData.scalar(typeOrdinal, typeName, rep);
      }
    }
  }

  private static @Nullable String origin(@Nullable List<String> origins,
      int offsetFromEnd) {
    return origins == null || offsetFromEnd >= origins.size()
        ? null
        : origins.get(origins.size() - 1 - offsetFromEnd);
  }

  private static int getTypeOrdinal(RelDataType type) {
    switch (type.getSqlTypeName()) {
    case MEASURE:
      // getMeasureElementType() for MEASURE types will never be null
      final RelDataType measureElementType =
          requireNonNull(type.getMeasureElementType(), "measureElementType");
      return measureElementType.getSqlTypeName().getJdbcOrdinal();
    default:
      return type.getSqlTypeName().getJdbcOrdinal();
    }
  }

  private static String getClassName(@SuppressWarnings("unused") RelDataType type) {
    return Object.class.getName(); // CALCITE-2613
  }

  private static int getScale(RelDataType type) {
    return type.getScale() == RelDataType.SCALE_NOT_SPECIFIED
        ? 0
        : type.getScale();
  }

  private static int getPrecision(RelDataType type) {
    return type.getPrecision() == RelDataType.PRECISION_NOT_SPECIFIED
        ? 0
        : type.getPrecision();
  }

  /** Returns the type name in string form. Does not include precision, scale
   * or whether nulls are allowed. Example: "DECIMAL" not "DECIMAL(7, 2)";
   * "INTEGER" not "JavaType(int)". */
  private static String getTypeName(RelDataType type) {
    final SqlTypeName sqlTypeName = type.getSqlTypeName();
    switch (sqlTypeName) {
    case ARRAY:
    case MULTISET:
    case MAP:
    case ROW:
    case MEASURE:
      return type.toString(); // e.g. "INTEGER ARRAY"
    case INTERVAL_YEAR_MONTH:
      return "INTERVAL_YEAR_TO_MONTH";
    case INTERVAL_DAY_HOUR:
      return "INTERVAL_DAY_TO_HOUR";
    case INTERVAL_DAY_MINUTE:
      return "INTERVAL_DAY_TO_MINUTE";
    case INTERVAL_DAY_SECOND:
      return "INTERVAL_DAY_TO_SECOND";
    case INTERVAL_HOUR_MINUTE:
      return "INTERVAL_HOUR_TO_MINUTE";
    case INTERVAL_HOUR_SECOND:
      return "INTERVAL_HOUR_TO_SECOND";
    case INTERVAL_MINUTE_SECOND:
      return "INTERVAL_MINUTE_TO_SECOND";
    default:
      return sqlTypeName.getName(); // e.g. "DECIMAL", "INTERVAL_YEAR_MONTH"
    }
  }

  protected void populateMaterializations(Context context,
      RelOptCluster cluster, Prepare.Materialization materialization) {
    // REVIEW: initialize queryRel and tableRel inside MaterializationService,
    // not here?
    try {
      final CalciteSchema schema = materialization.materializedTable.schema;
      CalciteCatalogReader catalogReader =
          new CalciteCatalogReader(
              schema.root(),
              materialization.viewSchemaPath,
              context.getTypeFactory(),
              context.config());
      final CalciteMaterializer materializer =
          new CalciteMaterializer(this, context, catalogReader, schema,
              cluster, createConvertletTable());
      materializer.populate(materialization);
    } catch (Exception e) {
      throw new RuntimeException("While populating materialization "
          + materialization.materializedTable.path(), e);
    }
  }

  private static RelDataType makeStruct(
      RelDataTypeFactory typeFactory,
      RelDataType type) {
    if (type.isStruct()) {
      return type;
    }
    return typeFactory.builder().add("$0", type).build();
  }

  @Deprecated // to be removed before 2.0
  public <R> R perform(CalciteServerStatement statement,
      Frameworks.PrepareAction<R> action) {
    return perform(statement, action.getConfig(), action);
  }

  /** Executes a prepare action. */
  public <R> R perform(CalciteServerStatement statement,
      FrameworkConfig config, Frameworks.BasePrepareAction<R> action) {
    final CalcitePrepare.Context prepareContext =
        statement.createPrepareContext();  //准备prepare语句的上下文
    final JavaTypeFactory typeFactory = prepareContext.getTypeFactory();
    SchemaPlus defaultSchema = config.getDefaultSchema();
    final CalciteSchema schema =
        defaultSchema != null
            ? CalciteSchema.from(defaultSchema)
            : prepareContext.getRootSchema();
    CalciteCatalogReader catalogReader =
        new CalciteCatalogReader(schema.root(),
            schema.path(null),
            typeFactory,
            prepareContext.config());
    final RexBuilder rexBuilder = new RexBuilder(typeFactory);
    final RelOptPlanner planner =
        createPlanner(prepareContext,
            config.getContext(),
            config.getCostFactory());
    final RelOptCluster cluster = createCluster(planner, rexBuilder);
    return action.apply(cluster, catalogReader,
        prepareContext.getRootSchema().plus(), statement);
  }

  /** Holds state for the process of preparing a SQL statement.
   *
   * <p>Overload this class and {@link #createSqlValidator} to provide desired
   * SqlValidator and custom validation logic.
   */
  public static class CalcitePreparingStmt extends Prepare
      implements RelOptTable.ViewExpander {
    protected final RelOptPlanner planner;
    protected final RexBuilder rexBuilder;
    protected final CalcitePrepareImpl prepare;
    protected final CalciteSchema schema;
    protected final RelDataTypeFactory typeFactory;
    protected final SqlRexConvertletTable convertletTable;
    private final EnumerableRel.@Nullable Prefer prefer;
    private final RelOptCluster cluster;
    private final Map<String, Object> internalParameters =
        new LinkedHashMap<>();
    @SuppressWarnings("unused")
    private int expansionDepth;
    private @Nullable SqlValidator sqlValidator;

    /** Constructor.
     *
     * <p>Overload this constructor and {@link #createSqlValidator} to provide
     * desired SqlValidator and custom validation logic.
     */
    public CalcitePreparingStmt(CalcitePrepareImpl prepare,
        Context context,
        CatalogReader catalogReader,
        RelDataTypeFactory typeFactory,
        CalciteSchema schema,
        EnumerableRel.@Nullable Prefer prefer,
        RelOptCluster cluster,
        Convention resultConvention,
        SqlRexConvertletTable convertletTable) {
      super(context, catalogReader, resultConvention);
      this.prepare = prepare;
      this.schema = schema;
      this.prefer = prefer;
      this.cluster = cluster;
      this.planner = cluster.getPlanner();
      this.rexBuilder = cluster.getRexBuilder();
      this.typeFactory = typeFactory;
      this.convertletTable = convertletTable;
    }

    @Override protected void init(Class runtimeContextClass) {
    }

    public PreparedResult prepareQueryable(
        final Queryable queryable,
        RelDataType resultType) {
      return prepare_(() -> {
        final RelOptCluster cluster =
            prepare.createCluster(planner, rexBuilder);
        return new LixToRelTranslator(cluster, CalcitePreparingStmt.this)
            .translate(queryable);
      }, resultType);
    }

    public PreparedResult prepareRel(final RelNode rel) {
      return prepare_(() -> rel, rel.getRowType());
    }

    private PreparedResult prepare_(Supplier<RelNode> fn,
        RelDataType resultType) {
      Class runtimeContextClass = Object.class;
      init(runtimeContextClass);

      final RelNode rel = fn.get();
      final RelDataType rowType = rel.getRowType();
      final List<Pair<Integer, String>> fields =
          Pair.zip(ImmutableIntList.identity(rowType.getFieldCount()),
              rowType.getFieldNames());
      final RelCollation collation =
          rel instanceof Sort
              ? ((Sort) rel).collation
              : RelCollations.EMPTY;
      RelRoot root =
          new RelRoot(rel, resultType, SqlKind.SELECT, fields, collation,
              ImmutableList.of());

      if (timingTracer != null) {
        timingTracer.traceTime("end sql2rel");
      }

      final RelDataType jdbcType =
          makeStruct(rexBuilder.getTypeFactory(), resultType);
      fieldOrigins = Collections.nCopies(jdbcType.getFieldCount(), null);
      parameterRowType = rexBuilder.getTypeFactory().builder().build();

      // Structured type flattening, view expansion, and plugging in
      // physical storage.
      root = root.withRel(flattenTypes(root.rel, true));

      // Trim unused fields.
      root = trimUnusedFields(root);

      final List<Materialization> materializations = ImmutableList.of();
      final List<CalciteSchema.LatticeEntry> lattices = ImmutableList.of();
      root = optimize(root, materializations, lattices);

      if (timingTracer != null) {
        timingTracer.traceTime("end optimization");
      }

      return implement(root);
    }

    @Override protected SqlToRelConverter getSqlToRelConverter(
        SqlValidator validator,
        CatalogReader catalogReader,
        SqlToRelConverter.Config config) {
      return new SqlToRelConverter(this, validator, catalogReader, cluster,
          convertletTable, config);
    }

    @Override public RelNode flattenTypes(
        RelNode rootRel,
        boolean restructure) {
      final SparkHandler spark = context.spark();
      if (spark.enabled()) {
        return spark.flattenTypes(planner, rootRel, restructure);
      }
      return rootRel;
    }

    @Override protected RelNode decorrelate(SqlToRelConverter sqlToRelConverter,
        SqlNode query, RelNode rootRel) {
      return sqlToRelConverter.decorrelate(query, rootRel);
    }

    @Override public RelRoot expandView(RelDataType rowType, String queryString,
        List<String> schemaPath, @Nullable List<String> viewPath) {
      expansionDepth++;

      SqlParser parser = prepare.createParser(queryString);
      SqlNode sqlNode;
      try {
        sqlNode = parser.parseQuery();
      } catch (SqlParseException e) {
        throw new RuntimeException("parse failed", e);
      }
      // View may have different schema path than current connection.
      final CatalogReader catalogReader =
          this.catalogReader.withSchemaPath(schemaPath);
      SqlValidator validator = createSqlValidator(catalogReader);
      final SqlToRelConverter.Config config =
          SqlToRelConverter.config().withTrimUnusedFields(true);
      SqlToRelConverter sqlToRelConverter =
          getSqlToRelConverter(validator, catalogReader, config);
      RelRoot root =
          sqlToRelConverter.convertQuery(sqlNode, true, true);

      --expansionDepth;
      return root;
    }

    protected SqlValidator createSqlValidator(CatalogReader catalogReader) {
      return CalcitePrepareImpl.createSqlValidator(context,
          (CalciteCatalogReader) catalogReader);
    }

    @Override protected SqlValidator getSqlValidator() {
      if (sqlValidator == null) {
        sqlValidator = createSqlValidator(catalogReader);
      }
      return sqlValidator;
    }

    @Override protected PreparedResult createPreparedExplanation(
        @Nullable RelDataType resultType,
        RelDataType parameterRowType,
        @Nullable RelRoot root,
        SqlExplainFormat format,
        SqlExplainLevel detailLevel) {
      return new CalcitePreparedExplain(resultType, parameterRowType, root,
          format, detailLevel);
    }

    @Override protected PreparedResult implement(RelRoot root) {
      Hook.PLAN_BEFORE_IMPLEMENTATION.run(root);
      RelDataType resultType = root.rel.getRowType();
      boolean isDml = root.kind.belongsTo(SqlKind.DML);
      final Bindable bindable;
      if (resultConvention == BindableConvention.INSTANCE) {
        bindable = Interpreters.bindable(root.rel);
      } else {
        EnumerableRel enumerable = (EnumerableRel) root.rel;
        if (!root.isRefTrivial()) {
          final List<RexNode> projects = new ArrayList<>();
          final RexBuilder rexBuilder = enumerable.getCluster().getRexBuilder();
          for (int field : Pair.left(root.fields)) {
            projects.add(rexBuilder.makeInputRef(enumerable, field));
          }
          RexProgram program =
              RexProgram.create(enumerable.getRowType(), projects, null,
                  root.validatedRowType, rexBuilder);
          enumerable = EnumerableCalc.create(enumerable, program);
        }

        try {
          CatalogReader.THREAD_LOCAL.set(catalogReader);
          final SqlConformance conformance = context.config().conformance();
          internalParameters.put("_conformance", conformance);
          bindable =
              EnumerableInterpretable.toBindable(internalParameters,
                  context.spark(), enumerable,
                  requireNonNull(prefer, "EnumerableRel.Prefer prefer"));
        } finally {
          CatalogReader.THREAD_LOCAL.remove();
        }
      }

      if (timingTracer != null) {
        timingTracer.traceTime("end codegen");
      }

      if (timingTracer != null) {
        timingTracer.traceTime("end compilation");
      }

      return new PreparedResultImpl(
          resultType,
          requireNonNull(parameterRowType, "parameterRowType"),
          requireNonNull(fieldOrigins, "fieldOrigins"),
          root.collation.getFieldCollations().isEmpty()
              ? ImmutableList.of()
              : ImmutableList.of(root.collation),
          root.rel,
          mapTableModOp(isDml, root.kind),
          isDml) {
        @Override public String getCode() {
          throw new UnsupportedOperationException();
        }

        @Override public Bindable getBindable(Meta.CursorFactory cursorFactory) {
          return bindable;
        }

        @Override public Type getElementType() {
          return ((Typed) bindable).getElementType();
        }
      };
    }

    @Override protected List<Materialization> getMaterializations() {
      final List<Prepare.Materialization> materializations =
          context.config().materializationsEnabled()
              ? MaterializationService.instance().query(schema)
              : ImmutableList.of();
      for (Prepare.Materialization materialization : materializations) {
        prepare.populateMaterializations(context, cluster, materialization);
      }
      return materializations;
    }

    @Override protected List<LatticeEntry> getLattices() {
      return Schemas.getLatticeEntries(schema);
    }
  }

  /** An {@code EXPLAIN} statement, prepared and ready to execute. */
  private static class CalcitePreparedExplain extends Prepare.PreparedExplain {
    CalcitePreparedExplain(
        @Nullable RelDataType resultType,
        RelDataType parameterRowType,
        @Nullable RelRoot root,
        SqlExplainFormat format,
        SqlExplainLevel detailLevel) {
      super(resultType, parameterRowType, root, format, detailLevel);
    }

    @Override public Bindable getBindable(final Meta.CursorFactory cursorFactory) {
      final String explanation = getCode();
      return dataContext -> {
        switch (cursorFactory.style) {
        case ARRAY:
          return Linq4j.singletonEnumerable(new String[] {explanation});
        case OBJECT:
        default:
          return Linq4j.singletonEnumerable(explanation);
        }
      };
    }
  }

  /** Translator from Java AST to {@link RexNode}. */
  interface ScalarTranslator {
    RexNode toRex(BlockStatement statement);
    List<RexNode> toRexList(BlockStatement statement);
    RexNode toRex(Expression expression);
    ScalarTranslator bind(List<ParameterExpression> parameterList,
        List<RexNode> values);
  }

  /** Basic translator. */
  static class EmptyScalarTranslator implements ScalarTranslator {
    private final RexBuilder rexBuilder;

    EmptyScalarTranslator(RexBuilder rexBuilder) {
      this.rexBuilder = rexBuilder;
    }

    public static ScalarTranslator empty(RexBuilder builder) {
      return new EmptyScalarTranslator(builder);
    }

    @Override public List<RexNode> toRexList(BlockStatement statement) {
      final List<Expression> simpleList = simpleList(statement);
      final List<RexNode> list = new ArrayList<>();
      for (Expression expression1 : simpleList) {
        list.add(toRex(expression1));
      }
      return list;
    }

    @Override public RexNode toRex(BlockStatement statement) {
      return toRex(Blocks.simple(statement));
    }

    private static List<Expression> simpleList(BlockStatement statement) {
      Expression simple = Blocks.simple(statement);
      if (simple instanceof NewExpression) {
        NewExpression newExpression = (NewExpression) simple;
        return newExpression.arguments;
      } else {
        return Collections.singletonList(simple);
      }
    }

    @Override public RexNode toRex(Expression expression) {
      switch (expression.getNodeType()) {
      case MemberAccess:
        // Case-sensitive name match because name was previously resolved.
        MemberExpression memberExpression = (MemberExpression) expression;
        PseudoField field = memberExpression.field;
        Expression targetExpression =
            requireNonNull(memberExpression.expression,
                () -> "static field access is not implemented yet."
                    + " field.name=" + field.getName()
                    + ", field.declaringClass=" + field.getDeclaringClass());
        return rexBuilder.makeFieldAccess(
            toRex(targetExpression),
            field.getName(),
            true);
      case GreaterThan:
        return binary(expression, SqlStdOperatorTable.GREATER_THAN);
      case LessThan:
        return binary(expression, SqlStdOperatorTable.LESS_THAN);
      case Parameter:
        return parameter((ParameterExpression) expression);
      case Call:
        MethodCallExpression call = (MethodCallExpression) expression;
        SqlOperator operator =
            RexToLixTranslator.JAVA_TO_SQL_METHOD_MAP.get(call.method);
        if (operator != null) {
          return rexBuilder.makeCall(
              type(call),
              operator,
              toRex(
                  Expressions.<Expression>list()
                      .appendIfNotNull(call.targetExpression)
                      .appendAll(call.expressions)));
        }
        throw new RuntimeException(
            "Could translate call to method " + call.method);
      case Constant:
        final ConstantExpression constant =
            (ConstantExpression) expression;
        Object value = constant.value;
        if (value instanceof Number) {
          Number number = (Number) value;
          if (value instanceof Double || value instanceof Float) {
            return rexBuilder.makeApproxLiteral(
                BigDecimal.valueOf(number.doubleValue()));
          } else if (value instanceof BigDecimal) {
            return rexBuilder.makeExactLiteral((BigDecimal) value);
          } else {
            return rexBuilder.makeExactLiteral(
                BigDecimal.valueOf(number.longValue()));
          }
        } else if (value instanceof Boolean) {
          return rexBuilder.makeLiteral((Boolean) value);
        } else {
          return rexBuilder.makeLiteral(constant.toString());
        }
      default:
        throw new UnsupportedOperationException(
            "unknown expression type " + expression.getNodeType() + " "
            + expression);
      }
    }

    private RexNode binary(Expression expression, SqlBinaryOperator op) {
      BinaryExpression call = (BinaryExpression) expression;
      return rexBuilder.makeCall(type(call), op,
          toRex(ImmutableList.of(call.expression0, call.expression1)));
    }

    private List<RexNode> toRex(List<Expression> expressions) {
      final List<RexNode> list = new ArrayList<>();
      for (Expression expression : expressions) {
        list.add(toRex(expression));
      }
      return list;
    }

    protected RelDataType type(Expression expression) {
      final Type type = expression.getType();
      return ((JavaTypeFactory) rexBuilder.getTypeFactory()).createType(type);
    }

    @Override public ScalarTranslator bind(
        List<ParameterExpression> parameterList, List<RexNode> values) {
      return new LambdaScalarTranslator(
          rexBuilder, parameterList, values);
    }

    public RexNode parameter(ParameterExpression param) {
      throw new RuntimeException("unknown parameter " + param);
    }
  }

  /** Translator that looks for parameters. */
  private static class LambdaScalarTranslator extends EmptyScalarTranslator {
    private final List<ParameterExpression> parameterList;
    private final List<RexNode> values;

    LambdaScalarTranslator(
        RexBuilder rexBuilder,
        List<ParameterExpression> parameterList,
        List<RexNode> values) {
      super(rexBuilder);
      this.parameterList = parameterList;
      this.values = values;
    }

    @Override public RexNode parameter(ParameterExpression param) {
      int i = parameterList.indexOf(param);
      if (i >= 0) {
        return values.get(i);
      }
      throw new RuntimeException("unknown parameter " + param);
    }
  }
}
