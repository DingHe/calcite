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
package org.apache.calcite.sql.validate;

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.linq4j.function.Functions;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.type.DynamicRecordType;
import org.apache.calcite.rel.type.RelCrossType;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rel.type.RelRecordType;
import org.apache.calcite.rel.type.TimeFrame;
import org.apache.calcite.rel.type.TimeFrameSet;
import org.apache.calcite.rel.type.TimeFrames;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexPatternFieldRef;
import org.apache.calcite.rex.RexVisitor;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.runtime.CalciteException;
import org.apache.calcite.runtime.Feature;
import org.apache.calcite.runtime.PairList;
import org.apache.calcite.runtime.Resources;
import org.apache.calcite.schema.ColumnStrategy;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.ModifiableViewTable;
import org.apache.calcite.sql.JoinConditionType;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlAccessEnum;
import org.apache.calcite.sql.SqlAccessType;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlAsOperator;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlExplain;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlIntervalLiteral;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLambda;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlMatchRecognize;
import org.apache.calcite.sql.SqlMerge;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlPivot;
import org.apache.calcite.sql.SqlSampleSpec;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlSelectKeyword;
import org.apache.calcite.sql.SqlSnapshot;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.SqlTableFunction;
import org.apache.calcite.sql.SqlUnknownLiteral;
import org.apache.calcite.sql.SqlUnpivot;
import org.apache.calcite.sql.SqlUnresolvedFunction;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.SqlWindow;
import org.apache.calcite.sql.SqlWindowTableFunction;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.TableCharacteristic;
import org.apache.calcite.sql.fun.SqlCase;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.AssignableOperandTypeChecker;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlOperandTypeInference;
import org.apache.calcite.sql.type.SqlTypeCoercionRule;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.util.IdPair;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.sql.util.SqlShuttle;
import org.apache.calcite.sql.util.SqlVisitor;
import org.apache.calcite.sql.validate.implicit.TypeCoercion;
import org.apache.calcite.util.BitString;
import org.apache.calcite.util.Bug;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.ImmutableNullableList;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.Optionality;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Static;
import org.apache.calcite.util.Util;
import org.apache.calcite.util.trace.CalciteTrace;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.KeyFor;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;
import org.checkerframework.dataflow.qual.Pure;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import static org.apache.calcite.linq4j.Nullness.castNonNull;
import static org.apache.calcite.linq4j.Ord.forEach;
import static org.apache.calcite.sql.SqlUtil.stripAs;
import static org.apache.calcite.sql.type.NonNullableAccessors.getCharset;
import static org.apache.calcite.sql.type.NonNullableAccessors.getCollation;
import static org.apache.calcite.sql.validate.SqlNonNullableAccessors.getCondition;
import static org.apache.calcite.sql.validate.SqlNonNullableAccessors.getTable;
import static org.apache.calcite.util.Static.RESOURCE;
import static org.apache.calcite.util.Util.first;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

/**
 * Default implementation of {@link SqlValidator}.
 */
// 该类的作用可以概括为以下四个核心环节：
// 标识符解析（Identifier Resolution）：
// 将 SQL 文本中的名字（如 EMP 表、sal 列）与数据库元数据（Catalog）中的真实对象对应起来。它会处理别名、隐式表引用以及复杂的嵌套字段。
// 类型推导（Type Derivation）：
// 通过递归遍历，计算每个表达式的最终数据类型。例如，1 + 1.5 会推导出 DOUBLE，COUNT(*) 会推导出 BIGINT。
// 语义规则检查（Semantic Constraint Checking）：
// 执行 SQL 标准和方言规定的各种限制检查。例如：
// GROUP BY 查询中，SELECT 的列是否合法。
// 函数参数的个数和类型是否匹配。
// 子查询的嵌套是否合法。
// 语法树重写（AST Rewriting）：
// 为了方便后续转换，它会对树进行物理变形。例如：
// 将 SELECT * 展开为具体的列名列表。
// 将 UPDATE 语句重写为 MERGE 语句（在某些配置下）。
// 处理隐式的类型转换（Type Coercion）。

public class SqlValidatorImpl implements SqlValidatorWithHints {
  //~ Static fields/initializers ---------------------------------------------

  public static final Logger TRACER = CalciteTrace.PARSER_LOGGER;

  /**
   * Alias generated for the source table when rewriting UPDATE to MERGE.
   */
  public static final String UPDATE_SRC_ALIAS = "SYS$SRC";

  /**
   * Alias generated for the target table when rewriting UPDATE to MERGE if no
   * alias was specified by the user.
   */
  public static final String UPDATE_TGT_ALIAS = "SYS$TGT";

  /**
   * Alias prefix generated for source columns when rewriting UPDATE to MERGE.
   */
  public static final String UPDATE_ANON_PREFIX = "SYS$ANON";

  //~ Instance fields --------------------------------------------------------
  // 运算符表，存储了所有的函数（如 SUM, CONCAT）和操作符（如 +, -）
  private final SqlOperatorTable opTab;
  // 目录读取器，是连接元数据的桥梁，用于查找表和 Schema 信息。
  final SqlValidatorCatalogReader catalogReader;

  /**
   * Maps {@link SqlParserPos} strings to the {@link SqlIdentifier} identifier
   * objects at these positions.
   */
  // 记录标识符在源代码中的位置，用于报错时精准定位行号
  protected final Map<String, IdInfo> idPositions = new HashMap<>();

  /**
   * Maps {@link SqlNode query node} objects to the {@link SqlValidatorScope}
   * scope created from them.
   */
  // 映射 SqlNode 到 SqlValidatorScope。
  // Scope 决定了在 SQL 的某个位置（如 WHERE 子句内部）哪些名字是可见的。
  protected final IdentityHashMap<SqlNode, SqlValidatorScope> scopes =
      new IdentityHashMap<>();

  /**
   * Maps a {@link SqlSelect} and a clause to the scope used by that clause.
   */
  // 细化管理 SELECT 各个子句（FROM, WHERE, GROUP BY 等）对应的特定作用域。
  private final Map<IdPair<SqlSelect, Clause>, SqlValidatorScope>
      clauseScopes = new HashMap<>();

  /**
   * The name-resolution scope of a LATERAL TABLE clause.
   */
  private @Nullable TableScope tableScope = null;

  /**
   * Maps a {@link SqlNode node} to the
   * {@link SqlValidatorNamespace namespace} which describes what columns they
   * contain.
   */
  // 映射 SqlNode 到 SqlValidatorNamespace。
  // Namespace 描述了一个数据源（如表、子查询）包含哪些列。
  protected final IdentityHashMap<SqlNode, SqlValidatorNamespace> namespaces =
      new IdentityHashMap<>();

  /**
   * Set of select expressions used as cursor definitions. In standard SQL,
   * only the top-level SELECT is a cursor; Calcite extends this with
   * cursors as inputs to table functions.
   */
  // 记录哪些 SELECT 被声明为游标（Cursor）
  private final Set<SqlNode> cursorSet = Sets.newIdentityHashSet();

  /**
   * Stack of objects that maintain information about function calls. A stack
   * is needed to handle nested function calls. The function call currently
   * being validated is at the top of the stack.
   */
  // 函数调用栈，处理嵌套函数调用时的参数验证。
  protected final Deque<FunctionParamInfo> functionCallStack =
      new ArrayDeque<>();

  private int nextGeneratedId;
  // 类型工厂，用于创建逻辑数据类型（RelDataType）。
  protected final RelDataTypeFactory typeFactory;
  // 预定义的常用数据类型单例。
  protected final RelDataType unknownType;
  private final RelDataType booleanType;

  protected final TimeFrameSet timeFrameSet;

  /**
   * Map of derived RelDataType for each node. This is an IdentityHashMap
   * since in some cases (such as null literals) we need to discriminate by
   * instance.
   */
  // 这是一个 IdentityHashMap，存储了每个 SqlNode 及其校验后的 RelDataType。
  // 这是校验结果的主要产出。
  private final IdentityHashMap<SqlNode, RelDataType> nodeToTypeMap =
      new IdentityHashMap<>();

  /** Provides the data for {@link #getValidatedOperandTypes(SqlCall)}. */
  // 函数调用的参数数据类型
  public final IdentityHashMap<SqlCall, List<RelDataType>> callToOperandTypesMap =
      new IdentityHashMap<>();

  // 一系列探测器，用于在语法树中快速查找是否存在聚合函数、窗口函数或分组标识。
  private final AggFinder aggFinder;
  private final AggFinder aggOrOverFinder;
  private final AggFinder aggOrOverOrGroupFinder;
  private final AggFinder groupFinder;
  private final AggFinder overFinder;
  // 校验器的配置信息（如是否允许隐式转换、标识符扩展规则等）。
  private Config config;
  // 记录重写前的原始表达式，主要用于内部追溯。
  private final Map<SqlNode, SqlNode> originalExprs = new HashMap<>();

  private @Nullable SqlNode top;

  // TODO jvs 11-Dec-2008:  make this local to performUnconditionalRewrites
  // if it's OK to expand the signature of that method.
  private boolean validatingSqlMerge;

  private boolean inWindow;                        // Allow nested aggregates

  private final SqlValidatorImpl.ValidationErrorFunction validationErrorFunction =
      new SqlValidatorImpl.ValidationErrorFunction();

  // TypeCoercion instance used for implicit type coercion.
  // 隐式类型转换处理器，负责在类型不匹配时（如 int 与 varchar 比较）自动插入转换逻辑。
  private final TypeCoercion typeCoercion;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a validator.
   *
   * @param opTab         Operator table
   * @param catalogReader Catalog reader
   * @param typeFactory   Type factory
   * @param config        Config
   */
  protected SqlValidatorImpl(
      SqlOperatorTable opTab,
      SqlValidatorCatalogReader catalogReader,
      RelDataTypeFactory typeFactory,
      Config config) {
    this.opTab = requireNonNull(opTab, "opTab");
    this.catalogReader = requireNonNull(catalogReader, "catalogReader");
    this.typeFactory = requireNonNull(typeFactory, "typeFactory");
    final RelDataTypeSystem typeSystem = typeFactory.getTypeSystem();
    // 从 typeSystem 中导出了 timeFrameSet，用于处理 SQL 中的时间单位和时间窗口。
    this.timeFrameSet =
        requireNonNull(typeSystem.deriveTimeFrameSet(TimeFrames.CORE),
            "timeFrameSet");
    // 决定了校验器的具体行为（如是否允许隐式转换）
    this.config = requireNonNull(config, "config");

    // It is assumed that unknown type is nullable by default
    // 被初始化为 Nullable（可为空）的未知类型。当 SQL 遇到 NULL 字面量或者无法立即推断类型的表达式时，会先标记为此类型。
    unknownType = typeFactory.createTypeWithNullability(typeFactory.createUnknownType(), true);
    // 标准的 SQL 布尔类型，常用于 WHERE 子句和逻辑判断。
    booleanType = typeFactory.createSqlType(SqlTypeName.BOOLEAN);

    final SqlNameMatcher nameMatcher = catalogReader.nameMatcher();
    // 校验器创建了多个不同配置的 AggFinder 实例。AggFinder 是一个访问器（Visitor），用于在 SQL 语法树中查找特定类型的函数。
    // 只找普通的聚合函数（如 SUM, AVG）。
    aggFinder = new AggFinder(opTab, false, true, false, null, nameMatcher);
    // 查找聚合函数或窗口函数（OVER）。
    aggOrOverFinder =
        new AggFinder(opTab, true, true, false, null, nameMatcher);
    // 专门查找窗口函数。
    overFinder =
        new AggFinder(opTab, true, false, false, aggOrOverFinder, nameMatcher);
    // 查找分组标识。
    groupFinder = new AggFinder(opTab, false, false, true, null, nameMatcher);
    // 全能探测器，查找以上所有内容。
    aggOrOverOrGroupFinder =
        new AggFinder(opTab, true, true, true, null, nameMatcher);
    @SuppressWarnings("argument.type.incompatible")
    // 隐式类型转换 (Type Coercion) 的策略配置
    TypeCoercion typeCoercion = config.typeCoercionFactory().create(typeFactory, this);
    this.typeCoercion = typeCoercion;

    if (config.conformance().allowLenientCoercion()) {
      final SqlTypeCoercionRule rules =
          requireNonNull(
              config.typeCoercionRules() != null
                  ? config.typeCoercionRules()
                  : SqlTypeCoercionRule.THREAD_PROVIDERS.get(),
              "rules");

      final ImmutableSet<SqlTypeName> arrayMapping =
          ImmutableSet.<SqlTypeName>builder()
              .addAll(rules.getTypeMapping()
                  .getOrDefault(SqlTypeName.ARRAY, ImmutableSet.of()))
              .add(SqlTypeName.VARCHAR)
              .add(SqlTypeName.CHAR)
              .build();

      Map<SqlTypeName, ImmutableSet<SqlTypeName>> mapping =
          new HashMap<>(rules.getTypeMapping());
      mapping.replace(SqlTypeName.ARRAY, arrayMapping);
      SqlTypeCoercionRule rules2 = SqlTypeCoercionRule.instance(mapping);

      SqlTypeCoercionRule.THREAD_PROVIDERS.set(rules2);
    } else if (config.typeCoercionRules() != null) {
      SqlTypeCoercionRule.THREAD_PROVIDERS.set(config.typeCoercionRules());
    }
  }

  //~ Methods ----------------------------------------------------------------

  public SqlConformance getConformance() {
    return config.conformance();
  }

  @Pure
  @Override public SqlValidatorCatalogReader getCatalogReader() {
    return catalogReader;
  }

  @Pure
  @Override public SqlOperatorTable getOperatorTable() {
    return opTab;
  }

  @Pure
  @Override public RelDataTypeFactory getTypeFactory() {
    return typeFactory;
  }

  @Override public RelDataType getUnknownType() {
    return unknownType;
  }

  @Override public TimeFrameSet getTimeFrameSet() {
    return timeFrameSet;
  }
  // 核心任务是将 SQL 查询中的星号（*）或限定星号（如 table.*）展开为具体的列名列表。
  // 作用：重写了接口方法。输入是一个原始的 SELECT 列表（可能包含 *），当前的 SELECT 语法树节点，以及一个是否包含系统变量的布尔标志。
  // 返回值：返回一个展开后的 SqlNodeList，其中所有的星号都已被替换为实际的列标识符（SqlIdentifier）。
  @Override public SqlNodeList expandStar(SqlNodeList selectList, // 原始的select字段列表
      SqlSelect select, // 当前select语法树节点
      boolean includeSystemVars) {
    final List<SqlNode> list = new ArrayList<>();
    final PairList<String, RelDataType> types = PairList.of();
    // 遍历用户在 SQL 中写的每一个 SELECT 项。例如 SELECT a, *, b FROM t，这里会依次处理 a、* 和 b。
    for (SqlNode selectItem : selectList) {
      // 尝试从验证器的缓存中获取当前项（selectItem）的类型。如果这个项之前已经被部分验证过，我们可以直接拿到它的类型。
      final RelDataType originalType = getValidatedNodeTypeIfKnown(selectItem);
      // 展开单个项
      // expandSelectItem 是一个递归或分发方法，它会判断 selectItem 是不是星号：
      // 如果是普通列：直接将其加入到 list 中。
      // 如果是星号 (*)：根据 select 的作用域（Scope），查找到对应的表，获取该表的所有列名，并逐个生成 SqlIdentifier 放入 list。
      expandSelectItem(selectItem, select, first(originalType, unknownType),
          list, catalogReader.nameMatcher().createSet(), types,
          includeSystemVars);
    }
    // getRawSelectScopeNonNull(select)：获取该 SELECT 语句对应的验证作用域（SelectScope）。作用域记录了哪些表和列在这个查询中是可见的。
    // setExpandedSelectList(list)：将展开后的列表缓存到作用域中。这是一个重要的副作用，确保后续的验证步骤（如 ORDER BY 或 HAVING 子句的验证）可以使用展开后的列。
    getRawSelectScopeNonNull(select).setExpandedSelectList(list);
    return new SqlNodeList(list, SqlParserPos.ZERO);
  }

  @Override public void declareCursor(SqlSelect select,
      SqlValidatorScope parentScope) {
    cursorSet.add(select);

    // add the cursor to a map that maps the cursor to its select based on
    // the position of the cursor relative to other cursors in that call
    FunctionParamInfo funcParamInfo =
        requireNonNull(functionCallStack.peek(), "functionCall");
    Map<Integer, SqlSelect> cursorMap = funcParamInfo.cursorPosToSelectMap;
    final int cursorCount = cursorMap.size();
    cursorMap.put(cursorCount, select);

    // create a namespace associated with the result of the select
    // that is the argument to the cursor constructor; register it
    // with a scope corresponding to the cursor
    SelectScope cursorScope =
        new SelectScope(parentScope, getEmptyScope(), select);
    clauseScopes.put(IdPair.of(select, Clause.CURSOR), cursorScope);
    final SelectNamespace selectNs = createSelectNamespace(select, select);
    final String alias = SqlValidatorUtil.alias(select, nextGeneratedId++);
    registerNamespace(cursorScope, alias, selectNs, false);
  }

  @Override public void pushFunctionCall() {
    FunctionParamInfo funcInfo = new FunctionParamInfo();
    functionCallStack.push(funcInfo);
  }

  @Override public void popFunctionCall() {
    functionCallStack.pop();
  }

  @Override public @Nullable String getParentCursor(String columnListParamName) {
    FunctionParamInfo funcParamInfo =
        requireNonNull(functionCallStack.peek(), "functionCall");
    Map<String, String> parentCursorMap =
        funcParamInfo.columnListParamToParentCursorMap;
    return parentCursorMap.get(columnListParamName);
  }

  /**
   * If <code>selectItem</code> is "*" or "TABLE.*", expands it and returns
   * true; otherwise writes the unexpanded item.
   *
   * @param selectItem        Select-list item
   * @param select            Containing select clause
   * @param selectItems       List that expanded items are written to
   * @param aliases           Set of aliases
   * @param fields            List of field names and types, in alias order
   * @param includeSystemVars If true include system vars in lists
   * @return Whether the node was expanded
   */
  // Apache Calcite 验证器中处理 SELECT 列表中单个项的核心逻辑。
  // 它不仅负责展开星号（*），还负责对普通表达式进行全限定化、类型推断和别名处理。
  private boolean expandSelectItem(final SqlNode selectItem, // 当前正在处理的原始 SQL 节点，它可以是星号（*）、限定星号（T.*）、普通的列名（col）、或者是复杂的表达式（CASE WHEN ...）。它是该方法的处理对象。
      SqlSelect select, // 当前项所属的整个 SELECT 查询语句。用于获取该查询的各种作用域（Scope），比如 WhereScope 或 SelectScope。它是定位当前项语法位置的基础。
      RelDataType targetType,// 期望的目标类型。在某些场景下（如 INSERT INTO ... SELECT ...），我们已经知道目标表对应的列类型。这个参数用于辅助推断 selectItem 中那些类型不明的节点（如 NULL 或动态参数 ?）。
      List<SqlNode> selectItems, // [输出参数] 展开后的节点列表。如果是星号，展开后的多列会加入其中；如果是普通项，改写后的节点会加入其中。最终这个列表会构成验证后的 SELECT 列表。
      Set<String> aliases, // [状态记录] 已存在的别名集合。用于记录当前 SELECT 列表中已经使用了哪些别名。在展开过程中，它帮助系统生成唯一的、不冲突的列名。
      PairList<String, RelDataType> fields, // [输出参数] 最终列名与类型的配对列表。每处理完一个项，该项的最终别名和推导出的数据类型（RelDataType）都会存入此列表。它直接定义了该查询结果集的“行结构（Row Type）”。
      boolean includeSystemVars) { // 决定在展开星号时，是否包含数据库系统定义的隐藏变量或元数据列。通常情况下为 false。
    // 获取当前 SELECT 语句的 WhereScope。
    final SelectScope scope = (SelectScope) getWhereScope(select);
    // 检查 selectItem 是否为 * 或 table.*。如果是，该方法会将展开后的所有列直接添加到 selectItems、aliases 和 fields 中，并返回 true 表示处理完毕。
    if (expandStar(selectItems, aliases, fields, includeSystemVars, scope,
        selectItem)) {
      return true;
    }

    // Expand the select item: fully-qualify columns, and convert
    // parentheses-free functions such as LOCALTIME into explicit function
    // calls.
    // 对表达式进行“预处理”。例如，将 col 转换为 table.col（全限定化），或将不带括号的函数（如 CURRENT_DATE）转换为标准的函数调用形式。
    SqlNode expanded = expandSelectExpr(selectItem, scope, select);
    // 根据原始的 selectItem 推导一个别名。如果用户没写 AS，Calcite 会根据列名或序号生成一个。
    final String alias =
        SqlValidatorUtil.alias(selectItem, aliases.size());

    // If expansion has altered the natural alias, supply an explicit 'AS'.
    // 检查展开后的 expanded 是否与原始 selectItem 不同。
    final SqlValidatorScope selectScope = getSelectScope(select);
    // 如果不同（发生了改写），且改写后自动生成的别名与原始别名不一致，则强制创建一个 AS 调用。
    if (expanded != selectItem) {
      String newAlias =
          SqlValidatorUtil.alias(expanded, aliases.size());
      if (!Objects.equals(newAlias, alias)) {
        expanded =
            SqlStdOperatorTable.AS.createCall(
                selectItem.getParserPosition(),
                expanded,
                new SqlIdentifier(alias, SqlParserPos.ZERO));
        deriveTypeImpl(selectScope, expanded);
      }
    }
    // 更新结果列表
    // 将处理完毕的语法节点和对应的别名存入结果容器中。
    selectItems.add(expanded);
    aliases.add(alias);
    // 未知类型推断
    // 如果 expanded 中包含某些类型待定的节点（如动态参数 ? 或某些 NULL 常量），根据 targetType 进行类型推断。
    inferUnknownTypes(targetType, selectScope, expanded);
    // 类型推导
    // 计算该表达式的结果类型。
    RelDataType type = deriveType(selectScope, expanded);
    // Re-derive SELECT ITEM's data type that may be nullable in
    // AggregatingSelectScope when it appears in advanced grouping elements such
    // as CUBE, ROLLUP, GROUPING SETS. For example, in
    //   SELECT CASE WHEN c = 1 THEN '1' ELSE '23' END AS x
    //   FROM t
    //   GROUP BY CUBE(x)
    // the 'x' should be nullable even if x's literal values are not null.
    // 在 GROUP BY ROLLUP(x) 中，即使 x 列在表中定义为 NOT NULL，在聚合结果的某些行（汇总行）中 x 也会呈现为 NULL。
    // 如果当前处于聚合作用域，调用 nullifyType 根据分组情况修正类型的可空性（Nullability）。
    if (selectScope instanceof AggregatingSelectScope) {
      type = requireNonNull(selectScope.nullifyType(stripAs(expanded), type));
    }
    // 缓存验证结果
    setValidatedNodeType(expanded, type);
    fields.add(alias, type);
    return false;
  }
  // Apache Calcite 处理 JOIN ... USING 语义的最终落地逻辑。
  // 它的任务是：如果一个标识符是 USING 子句中的公共列，就将其改写为 COALESCE(left.col, right.col)，以确保在外连接（Outer Join）等场景下结果的正确性。
  private static SqlNode expandExprFromJoin(SqlJoin join, // 当前正在处理的连接节点。
      SqlIdentifier identifier,  // 待检查和展开的标识符（即用户在 SELECT 中写的简单列名）。
      SelectScope scope) { // 当前查询的作用域，用于获取元数据和表的信息。
    // 如果当前的 JOIN 不是使用 USING 关键字连接的（例如是 ON 条件），则不符合公共列合并的逻辑，直接返回原标识符。
    if (join.getConditionType() != JoinConditionType.USING) {
      return identifier;
    }
    // 获取当前作用域中字段与其别名的映射关系，用于后续判断是否需要显式添加 AS 子句。
    final Map<String, String> fieldAliases = getFieldAliases(scope);
    // 获取 USING(c1, c2) 中的列名列表。如果用户 SELECT 的列名 identifier 恰好是其中之一，说明找到了“公共列”。
    for (String name
        : SqlIdentifier.simpleNames((SqlNodeList) getCondition(join))) {
      if (identifier.getSimple().equals(name)) {
        final List<SqlNode> qualifiedNode = new ArrayList<>();
        // 遍历作用域中的所有子表（child）
        for (ScopeChild child : requireNonNull(scope, "scope").children) {
          if (child.namespace.getRowType().getFieldNames().contains(name)) {
            // 如果该表包含这个公共列名，则构造一个带表名前缀的标识符（如 T1.col）
            final SqlIdentifier exp =
                new SqlIdentifier(
                    ImmutableList.of(child.name, name),
                    identifier.getParserPosition());
            qualifiedNode.add(exp);
          }
        }
        // 对于一个二元连接，公共列必然且只能来自左右两个数据源，所以大小应为 2。同时检查该列是否已有别名。
        assert qualifiedNode.size() == 2;

        // If there is an alias for the column, no need to wrap the coalesce with an AS operator
        boolean haveAlias = fieldAliases.containsKey(name);
        // 核心改写步骤。将 col 改写为 COALESCE(left.col, right.col)
        final SqlCall coalesceCall =
            SqlStdOperatorTable.COALESCE.createCall(SqlParserPos.ZERO, qualifiedNode.get(0),
            qualifiedNode.get(1));

        if (haveAlias) {
          return coalesceCall;
        } else {
          // 如果用户没有提供别名，Calcite 会自动加上 AS name，以保持结果列名与用户原始输入一致
          return SqlStdOperatorTable.AS.createCall(SqlParserPos.ZERO, coalesceCall,
              new SqlIdentifier(name, SqlParserPos.ZERO));
        }
      }
    }

    // Only need to try to expand the expr from the left input of join
    // since it is always left-deep join.
    // SQL 解析通常将多表连接构造成左深树结构。
    // 如果当前 join 的左节点还是一个 SqlJoin，则递归向上查找，直到在某个 JOIN 层级找到匹配的 USING 条件。
    final SqlNode node = join.getLeft();
    if (node instanceof SqlJoin) {
      return expandExprFromJoin((SqlJoin) node, identifier, scope);
    } else {
      return identifier;
    }
  }

  private static Map<String, String> getFieldAliases(final SelectScope scope) {
    final ImmutableMap.Builder<String, String> fieldAliases = new ImmutableMap.Builder<>();

    for (SqlNode selectItem : scope.getNode().getSelectList()) {
      if (selectItem instanceof SqlCall) {
        final SqlCall call = (SqlCall) selectItem;
        if (!(call.getOperator() instanceof SqlAsOperator)
            || !(call.operand(0) instanceof SqlIdentifier)) {
          continue;
        }

        final SqlIdentifier fieldIdentifier = call.operand(0);
        fieldAliases.put(fieldIdentifier.getSimple(),
            ((SqlIdentifier) call.operand(1)).getSimple());
      }
    }

    return fieldAliases.build();
  }

  /** Returns the set of field names in the join condition specified by USING
   * or implicitly by NATURAL, de-duplicated and in order. */
  public @Nullable List<String> usingNames(SqlJoin join) {
    switch (join.getConditionType()) {
    case USING:
      SqlNodeList condition = (SqlNodeList) getCondition(join);
      List<String> simpleNames = SqlIdentifier.simpleNames(condition);
      return catalogReader.nameMatcher().distinctCopy(simpleNames);

    case NONE:
      if (join.isNatural()) {
        return deriveNaturalJoinColumnList(join);
      }
      return null;

    default:
      return null;
    }
  }

  private List<String> deriveNaturalJoinColumnList(SqlJoin join) {
    return SqlValidatorUtil.deriveNaturalJoinColumnList(
        catalogReader.nameMatcher(),
        getNamespaceOrThrow(join.getLeft()).getRowType(),
        getNamespaceOrThrow(join.getRight()).getRowType());
  }
  // Calcite 处理 JOIN ... USING 或 NATURAL JOIN 语义的关键方法。它的核心作用是：识别并展开那些在连接子句中被标记为“公共列”的选择项。
  // 在 SQL 标准中，如果你使用了 USING(col)，那么 SELECT 列表中的 col 不再属于某张特定的表，而是代表连接双方的共同结果。
  private static SqlNode expandCommonColumn(SqlSelect sqlSelect,// 当前正在处理的完整 SELECT 语法树节点，用于从中获取 FROM 子句信息。
      SqlNode selectItem,  // 正在校验/展开的 SELECT 列表项（例如 SELECT a, b 中的 a）。
      SelectScope scope, // 当前 SELECT 语句的作用域，保存了数据源的元数据。
      SqlValidatorImpl validator) { // 验证器实例，用于获取配置信息（如 SQL 兼容性 conformance）或执行特定的错误校验。
    if (!(selectItem instanceof SqlIdentifier)) {
      return selectItem;
    }
    // 只有 FROM 子句是 SqlJoin 类型（即存在显式的 JOIN 语法）时，才可能存在 USING 或 NATURAL JOIN 产生的公共列。如果是单表查询，直接返回。
    final SqlNode from = sqlSelect.getFrom();
    if (!(from instanceof SqlJoin)) {
      return selectItem;
    }
    // isSimple(): 判断是否为简单名称（如 deptno）。如果带了前缀（如 emp.deptno），则不是简单名称。
    final SqlIdentifier identifier = (SqlIdentifier) selectItem;
    // 合规性检查：根据 SQL 标准，USING 中的列不建议带表名前缀。
    // 如果用户写了 emp.deptno 且配置不允许限定公共列，则调用 validateQualifiedCommonColumn 进行合法性校验。
    if (!identifier.isSimple()) {
      if (!validator.config().conformance().allowQualifyingCommonColumn()) {
        validateQualifiedCommonColumn((SqlJoin) from, identifier, scope, validator);
      }
      return selectItem;
    }
    // 调用 expandExprFromJoin。该方法会深入查找 SqlJoin 节点的连接条件（USING 或 NATURAL）
    return expandExprFromJoin((SqlJoin) from, identifier, scope);
  }

  private static void validateQualifiedCommonColumn(SqlJoin join,
      SqlIdentifier identifier, SelectScope scope, SqlValidatorImpl validator) {
    List<String> names = validator.usingNames(join);
    if (names == null) {
      // Not USING or NATURAL.
      return;
    }

    // First we should make sure that the first component is the table name.
    // Then check whether the qualified identifier contains common column.
    for (ScopeChild child : scope.children) {
      if (Objects.equals(child.name, identifier.getComponent(0).toString())) {
        if (names.contains(identifier.getComponent(1).toString())) {
          throw validator.newValidationError(identifier,
              RESOURCE.disallowsQualifyingCommonColumn(identifier.toString()));
        }
      }
    }

    // Only need to try to validate the expr from the left input of join
    // since it is always left-deep join.
    final SqlNode node = join.getLeft();
    if (node instanceof SqlJoin) {
      validateQualifiedCommonColumn((SqlJoin) node, identifier, scope, validator);
    }
  }
  // 处理 SELECT * 或 SELECT table.* 的核心逻辑。它将抽象的星号通配符转换为具体的列名列表。
  private boolean expandStar(List<SqlNode> selectItems, // [输出参数] 存放展开后的 SQL 节点（通常是 SqlIdentifier）。
      Set<String> aliases, // [输出参数] 存放展开后每一列对应的别名。
      PairList<String, RelDataType> fields, // [输出参数] 存放最终列名与对应的物理数据类型（RelDataType）的键值对。
      boolean includeSystemVars,
      SelectScope scope, // 当前 SELECT 语句的作用域，包含 FROM 子句中引入的所有表和列信息。
      SqlNode node) { // 待检查的节点（即当前的 SELECT 项）。
    // 只有当节点是标识符（SqlIdentifier）且其属性为“星号”（isStar() 返回 true）时才继续，否则说明这不是一个 * 操作，直接退出。
    if (!(node instanceof SqlIdentifier)) {
      return false;
    }
    final SqlIdentifier identifier = (SqlIdentifier) node;
    if (!identifier.isStar()) {
      return false;
    }
    final int originalSize = selectItems.size();
    final SqlParserPos startPosition = identifier.getParserPosition();
    switch (identifier.names.size()) {
    case 1:
      // 情况 A：处理单个星号 SELECT * (case 1)
      SqlNode from = scope.getNode().getFrom();
      // 没有 FROM 子句却用 *，报错
      if (from == null) {
        throw newValidationError(identifier, RESOURCE.selectStarRequiresFrom());
      }

      boolean hasDynamicStruct = false;
      // 遍历 FROM 子句中的每一个数据源（Child）：
      // 循环遍历 FROM 里的每个表。如果是动态表，不展开具体列，而是插入一个特殊的“动态星号”标识；如果是普通表，遍历其元数据中的所有字段，生成 表名.列名 的标识符并加入列表。
      for (ScopeChild child : scope.children) {
        final int before = fields.size();
        if (child.namespace.getRowType().isDynamicStruct()) {
          // 处理动态表（如 HBase/MongoDB，列是不固定的）
          hasDynamicStruct = true;
          // don't expand star if the underneath table is dynamic.
          // Treat this star as a special field in validation/conversion and
          // wait until execution time to expand this star.
          final SqlNode exp =
              new SqlIdentifier(
                  ImmutableList.of(child.name,
                      DynamicRecordType.DYNAMIC_STAR_PREFIX),
                  startPosition);
          addToSelectList(
               selectItems,
               aliases,
               fields,
               exp,
               scope,
               includeSystemVars);
        } else {
          // 处理普通结构化表
          // 从当前的作用域子项（child）中获取对应的 SqlNode。
          // child 代表 FROM 子句中的一个数据源（如一张表或一个子查询）
          final SqlNode from2 = SqlNonNullableAccessors.getNode(child);
          // 找到该节点对应的 Namespace（命名空间）
          final SqlValidatorNamespace fromNs = getNamespaceOrThrow(from2, scope);
          final RelDataType rowType = fromNs.getRowType();
          // 遍历列字段
          // 开始遍历 rowType 中的每一个字段（RelDataTypeField），并获取其原始列名。
          for (RelDataTypeField field : rowType.getFieldList()) {
            String columnName = field.getName();

            // TODO: do real implicit collation here
            // 构造全限定标识符
            final SqlIdentifier exp =
                new SqlIdentifier(
                    ImmutableList.of(child.name, columnName),
                    startPosition);
            // Don't add expanded rolled up columns
            // 排除上卷列（OLAP 场景）
            // 调用 isRolledUpColumn 判断该列是否是一个“上卷列”。
            // 在某些 OLAP 引擎（如 Druid 或 Kylin）中，某些列是经过预聚合的（Rolled Up）。在某些特定的查询上下文中，直接对这些列进行 SELECT * 展开可能会导致语义错误或性能问题。
            if (!isRolledUpColumn(exp, scope)) {
              // 执行最终的字段添加
              addOrExpandField(
                      selectItems,
                      aliases,
                      fields,
                      includeSystemVars,
                      scope,
                      exp,
                      field);
            }
          }
        }
        // 外连接（Outer Join）导致的结果集可空性变化。
        // 当 SELECT * 展开时，如果某个表是通过 LEFT JOIN 或 RIGHT JOIN 引入的，那么该表原本定义为 NOT NULL 的列，在结果集中也必须变成 NULLABLE。
        // child 代表 FROM 子句中的一个表。child.nullable 为 true 通常意味着这个表处于 LEFT JOIN 的右侧、RIGHT JOIN 的左侧 或 FULL JOIN 的任一侧。
        if (child.nullable) {
          // before 是在处理该表之前 fields 列表的大小。
          // 从 fields 列表中取出第 i 个字段的元数据（包括列名和数据类型 RelDataType）。
          for (int i = before; i < fields.size(); i++) {
            final Map.Entry<String, RelDataType> entry = fields.get(i);
            final RelDataType type = entry.getValue();
            if (!type.isNullable()) {
              // 强制转换类型为“可空（Nullable）”
              fields.set(i,
                  entry.getKey(),
                  typeFactory.createTypeWithNullability(type, true));
            }
          }
        }
      }
      // If NATURAL JOIN or USING is present, move key fields to the front of
      // the list, per standard SQL. Disabled if there are dynamic fields.
      // 处理的是 SQL 标准中关于 NATURAL JOIN 或 USING 子句的一个特殊显示规则。
      // 在这些特定的连接方式下，作为连接条件的列（Join Keys）在 SELECT * 展开时，必须排在结果集的最前面，且同名列只能出现一次。
      // !hasDynamicStruct：如果当前查询涉及动态表（如 NoSQL 数据源），通常不执行重排，因为动态表的列在运行前是不确定的。
      if (!hasDynamicStruct || Bug.CALCITE_2400_FIXED) {
        // If some fields before star identifier,
        // we should move offset.
        // calculatePermuteOffset(selectItems)：计算在当前星号之前，已经有多少个显式的选择项。
        // originalSize：方法开始时记录的 selectItems 长度。
        // 作用：确定从哪个位置开始应用重排规则。如果 SQL 是 SELECT col1, * FROM ...，偏移量能保证 col1 的位置不动，只对 * 展开的部分进行重排。
        int offset = Math.min(calculatePermuteOffset(selectItems), originalSize);
        // new Permute(from, offset)：创建一个重排器。它会分析 FROM 子句中的连接关系。
        // 普通等值连接：
        //SELECT * FROM t1 JOIN t2 ON t1.id = t2.id
        //
        //结果集顺序：t1.id, t1.name, t2.id, t2.age（包含两个 id 列）。
        //
        //USING 连接：
        //SELECT * FROM t1 JOIN t2 USING (id)
        //
        //结果集顺序：id, t1.name, t2.age。
        //
        //规则：连接键 id 必须被提到最前面，且合并为一列。
        new Permute(from, offset).permute(selectItems, fields);
      }
      return true;

    default:
      // 情况 B：处理带前缀的星号 SELECT table.* (default)
      // 去掉末尾的星号。例如，如果原始输入是 schema.table.*，则 prefixId 变为 schema.table。
      final SqlIdentifier prefixId = identifier.skipLast(1);
      final SqlValidatorScope.ResolvedImpl resolved =
          new SqlValidatorScope.ResolvedImpl();
      final SqlNameMatcher nameMatcher =
          scope.validator.catalogReader.nameMatcher();
      // 在当前的作用域中查找这个前缀到底指向什么。它可能指向一个表别名、一个实际的表名，甚至是一个结构化的列（如 struct 类型）。
      scope.resolve(prefixId.names, nameMatcher, true, resolved);
      // 如果 resolve 结果为空，说明你在 SELECT 中引用的前缀（如 s.*）在 FROM 子句或上下文中根本不存在，直接抛出“未知标识符”的验证错误。
      if (resolved.count() == 0) {
        // e.g. "select s.t.* from e"
        // or "select r.* from e"
        throw newValidationError(prefixId,
            RESOURCE.unknownIdentifier(prefixId.toString()));
      }
      // 从解析结果中提取该前缀对应的行类型（RelDataType）。这个类型描述了该对象包含哪些列。
      final RelDataType rowType = resolved.only().rowType();
      // 情况一：处理动态结构（Dynamic Struct）
      if (rowType.isDynamicStruct()) {
        // don't expand star if the underneath table is dynamic.
        // 如果前缀指向的是一个动态表（例如 HBase 或 Schema-less 数据源）。
        // 不进行具体的列展开。相反，它会构造一个带有“动态星号前缀”的特殊标识符。这相当于告诉执行引擎：“这里有一个星号，但目前我不知道具体有哪些列，请在运行时（Runtime）再根据实际数据决定”。
        addToSelectList(
            selectItems,
            aliases,
            fields,
            prefixId.plus(DynamicRecordType.DYNAMIC_STAR_PREFIX, startPosition),
            scope,
            includeSystemVars);
      } else if (rowType.isStruct()) {
        // 情况二：处理标准结构（Record/Struct Type）
        for (RelDataTypeField field : rowType.getFieldList()) {
          String columnName = field.getName();

          // TODO: do real implicit collation here
          addOrExpandField(
              selectItems,
              aliases,
              fields,
              includeSystemVars,
              scope,
              prefixId.plus(columnName, startPosition),
              field);
        }
      } else {
        // 情况三：非法类型
        throw newValidationError(prefixId, RESOURCE.starRequiresRecordType());
      }
      return true;
    }
  }

  private static int calculatePermuteOffset(List<SqlNode> selectItems) {
    for (int i = 0; i < selectItems.size(); i++) {
      SqlNode selectItem = selectItems.get(i);
      SqlNode col = stripAs(selectItem);
      if (col.getKind() == SqlKind.IDENTIFIER
          && selectItem.getKind() != SqlKind.AS) {
        return i;
      }
    }
    return 0;
  }

  private SqlNode maybeCast(SqlNode node, RelDataType currentType,
      RelDataType desiredType) {
    return SqlTypeUtil.equalSansNullability(typeFactory, currentType, desiredType)
        ? node
        : SqlStdOperatorTable.CAST.createCall(SqlParserPos.ZERO,
            node, SqlTypeUtil.convertTypeToSpec(desiredType));
  }
  // Apache Calcite 在展开星号（*）过程中的一个决策点。
  // 它的核心作用是：判断一个字段是作为普通列直接添加，还是作为一个“嵌套结构”需要进一步递归展开。
  private boolean addOrExpandField(List<SqlNode> selectItems, // [输出参数] 最终生成的 SELECT 节点列表
      Set<String> aliases, // [状态记录] 已使用的别名集合，用于防重。
      PairList<String, RelDataType> fields, // [输出参数] 存储最终列名及其数据类型的配对列表。
      boolean includeSystemVars,
      SelectScope scope, // 当前查询的作用域，提供类型推导所需的上下文。
      SqlIdentifier id, // 当前字段的 SQL 标识符（通常是 表名.列名）。
      RelDataTypeField field) { // 当前字段在元数据中的定义，包含字段名、索引和数据类型（RelDataType）。
    switch (field.getType().getStructKind()) {
    // 获取该字段数据类型的“结构种类（StructKind）”
    // PEEK 语义：这通常用于处理某些特殊的嵌套结构（如 Map 或 Record 类型），
    // 这些类型在 SQL 语义中被视为“透明”的，即当你查询包含该结构的表时，可能希望自动展开其中的子列。
    case PEEK_FIELDS:
    case PEEK_FIELDS_DEFAULT:
      // 将当前的标识符加上星号。例如，如果 id 是 address，则变成 address.*。
      final SqlNode starExp = id.plusStar();
      expandStar(
          selectItems,
          aliases,
          fields,
          includeSystemVars,
          scope,
          starExp);
      return true;
    // 默认处理：作为普通字段添加
    default:
      addToSelectList(
          selectItems,
          aliases,
          fields,
          id,
          scope,
          includeSystemVars);
    }

    return false;
  }

  @Override public SqlNode validate(SqlNode topNode) {
    SqlValidatorScope scope = new EmptyScope(this);
    scope = new CatalogScope(scope, ImmutableList.of("CATALOG"));
    final SqlNode topNode2 = validateScopedExpression(topNode, scope);
    final RelDataType type = getValidatedNodeType(topNode2);
    Util.discard(type);
    return topNode2;
  }

  @Override public List<SqlMoniker> lookupHints(SqlNode topNode, SqlParserPos pos) {
    SqlValidatorScope scope = new EmptyScope(this);
    SqlNode outermostNode = performUnconditionalRewrites(topNode, false);
    cursorSet.add(outermostNode);
    if (outermostNode.isA(SqlKind.TOP_LEVEL)) {
      registerQuery(
          scope,
          null,
          outermostNode,
          outermostNode,
          null,
          false);
    }
    final SqlValidatorNamespace ns = getNamespace(outermostNode);
    if (ns == null) {
      throw new AssertionError("Not a query: " + outermostNode);
    }
    Collection<SqlMoniker> hintList = Sets.newTreeSet(SqlMoniker.COMPARATOR);
    lookupSelectHints(ns, pos, hintList);
    return ImmutableList.copyOf(hintList);
  }

  @Override public @Nullable SqlMoniker lookupQualifiedName(SqlNode topNode, SqlParserPos pos) {
    final String posString = pos.toString();
    IdInfo info = idPositions.get(posString);
    if (info != null) {
      final SqlQualified qualified = info.scope.fullyQualify(info.id);
      return new SqlIdentifierMoniker(qualified.identifier);
    } else {
      return null;
    }
  }

  /**
   * Looks up completion hints for a syntactically correct select SQL that has
   * been parsed into an expression tree.
   *
   * @param select   the Select node of the parsed expression tree
   * @param pos      indicates the position in the sql statement we want to get
   *                 completion hints for
   * @param hintList list of {@link SqlMoniker} (sql identifiers) that can
   *                 fill in at the indicated position
   */
  void lookupSelectHints(
      SqlSelect select,
      SqlParserPos pos,
      Collection<SqlMoniker> hintList) {
    IdInfo info = idPositions.get(pos.toString());
    if (info == null) {
      SqlNode fromNode = select.getFrom();
      final SqlValidatorScope fromScope = getFromScope(select);
      lookupFromHints(fromNode, fromScope, pos, hintList);
    } else {
      lookupNameCompletionHints(info.scope, info.id.names,
          info.id.getParserPosition(), hintList);
    }
  }

  private void lookupSelectHints(
      SqlValidatorNamespace ns,
      SqlParserPos pos,
      Collection<SqlMoniker> hintList) {
    final SqlNode node = ns.getNode();
    if (node instanceof SqlSelect) {
      lookupSelectHints((SqlSelect) node, pos, hintList);
    }
  }

  private void lookupFromHints(
      @Nullable SqlNode node,
      SqlValidatorScope scope,
      SqlParserPos pos,
      Collection<SqlMoniker> hintList) {
    if (node == null) {
      // This can happen in cases like "select * _suggest_", so from clause is absent
      return;
    }
    final SqlValidatorNamespace ns = getNamespaceOrThrow(node);
    if (ns.isWrapperFor(IdentifierNamespace.class)) {
      IdentifierNamespace idNs = ns.unwrap(IdentifierNamespace.class);
      final SqlIdentifier id = idNs.getId();
      for (int i = 0; i < id.names.size(); i++) {
        if (pos.toString().equals(
            id.getComponent(i).getParserPosition().toString())) {
          final List<SqlMoniker> objNames = new ArrayList<>();
          SqlValidatorUtil.getSchemaObjectMonikers(
              getCatalogReader(),
              id.names.subList(0, i + 1),
              objNames);
          for (SqlMoniker objName : objNames) {
            if (objName.getType() != SqlMonikerType.FUNCTION) {
              hintList.add(objName);
            }
          }
          return;
        }
      }
    }
    switch (node.getKind()) {
    case JOIN:
      lookupJoinHints((SqlJoin) node, scope, pos, hintList);
      break;
    default:
      lookupSelectHints(ns, pos, hintList);
      break;
    }
  }

  private void lookupJoinHints(
      SqlJoin join,
      SqlValidatorScope scope,
      SqlParserPos pos,
      Collection<SqlMoniker> hintList) {
    SqlNode left = join.getLeft();
    SqlNode right = join.getRight();
    SqlNode condition = join.getCondition();
    lookupFromHints(left, scope, pos, hintList);
    if (hintList.size() > 0) {
      return;
    }
    lookupFromHints(right, scope, pos, hintList);
    if (hintList.size() > 0) {
      return;
    }
    final JoinConditionType conditionType = join.getConditionType();
    switch (conditionType) {
    case ON:
      requireNonNull(condition, () -> "join.getCondition() for " + join)
          .findValidOptions(this,
              getScopeOrThrow(join),
              pos, hintList);
      return;
    default:

      // No suggestions.
      // Not supporting hints for other types such as 'Using' yet.
    }
  }

  /**
   * Populates a list of all the valid alternatives for an identifier.
   *
   * @param scope    Validation scope
   * @param names    Components of the identifier
   * @param pos      position
   * @param hintList a list of valid options
   */
  public final void lookupNameCompletionHints(
      SqlValidatorScope scope,
      List<String> names,
      SqlParserPos pos,
      Collection<SqlMoniker> hintList) {
    // Remove the last part of name - it is a dummy
    List<String> subNames = Util.skipLast(names);

    if (subNames.size() > 0) {
      // If there's a prefix, resolve it to a namespace.
      SqlValidatorNamespace ns = null;
      for (String name : subNames) {
        if (ns == null) {
          final SqlValidatorScope.ResolvedImpl resolved =
              new SqlValidatorScope.ResolvedImpl();
          final SqlNameMatcher nameMatcher = catalogReader.nameMatcher();
          scope.resolve(ImmutableList.of(name), nameMatcher, false, resolved);
          if (resolved.count() == 1) {
            ns = resolved.only().namespace;
          }
        } else {
          ns = ns.lookupChild(name);
        }
        if (ns == null) {
          break;
        }
      }
      if (ns != null) {
        RelDataType rowType = ns.getRowType();
        if (rowType.isStruct()) {
          for (RelDataTypeField field : rowType.getFieldList()) {
            hintList.add(
                new SqlMonikerImpl(
                    field.getName(),
                    SqlMonikerType.COLUMN));
          }
        }
      }

      // builtin function names are valid completion hints when the
      // identifier has only 1 name part
      findAllValidFunctionNames(names, this, hintList, pos);
    } else {
      // No prefix; use the children of the current scope (that is,
      // the aliases in the FROM clause)
      scope.findAliases(hintList);

      // If there's only one alias, add all child columns
      SelectScope selectScope =
          SqlValidatorUtil.getEnclosingSelectScope(scope);
      if ((selectScope != null)
          && (selectScope.getChildren().size() == 1)) {
        RelDataType rowType =
            selectScope.getChildren().get(0).getRowType();
        for (RelDataTypeField field : rowType.getFieldList()) {
          hintList.add(
              new SqlMonikerImpl(
                  field.getName(),
                  SqlMonikerType.COLUMN));
        }
      }
    }

    findAllValidUdfNames(names, this, hintList);
  }

  private static void findAllValidUdfNames(
      List<String> names,
      SqlValidator validator,
      Collection<SqlMoniker> result) {
    final List<SqlMoniker> objNames = new ArrayList<>();
    SqlValidatorUtil.getSchemaObjectMonikers(
        validator.getCatalogReader(),
        names,
        objNames);
    for (SqlMoniker objName : objNames) {
      if (objName.getType() == SqlMonikerType.FUNCTION) {
        result.add(objName);
      }
    }
  }

  private static void findAllValidFunctionNames(
      List<String> names,
      SqlValidator validator,
      Collection<SqlMoniker> result,
      SqlParserPos pos) {
    // a function name can only be 1 part
    if (names.size() > 1) {
      return;
    }
    for (SqlOperator op : validator.getOperatorTable().getOperatorList()) {
      SqlIdentifier curOpId =
          new SqlIdentifier(
              op.getName(),
              pos);

      final SqlCall call = validator.makeNullaryCall(curOpId);
      if (call != null) {
        result.add(
            new SqlMonikerImpl(
                op.getName(),
                SqlMonikerType.FUNCTION));
      } else {
        if ((op.getSyntax() == SqlSyntax.FUNCTION)
            || (op.getSyntax() == SqlSyntax.PREFIX)) {
          if (op.getOperandTypeChecker() != null) {
            String sig = op.getAllowedSignatures();
            sig = sig.replace("'", "");
            result.add(
                new SqlMonikerImpl(
                    sig,
                    SqlMonikerType.FUNCTION));
            continue;
          }
          result.add(
              new SqlMonikerImpl(
                  op.getName(),
                  SqlMonikerType.FUNCTION));
        }
      }
    }
  }

  @Override public SqlNode validateParameterizedExpression(
      SqlNode topNode,
      final Map<String, RelDataType> nameToTypeMap) {
    SqlValidatorScope scope = new ParameterScope(this, nameToTypeMap);
    return validateScopedExpression(topNode, scope);
  }

  private SqlNode validateScopedExpression(
      SqlNode topNode,
      SqlValidatorScope scope) {
    SqlNode outermostNode = performUnconditionalRewrites(topNode, false);
    cursorSet.add(outermostNode);
    top = outermostNode;
    TRACER.trace("After unconditional rewrite: {}", outermostNode);
    if (outermostNode.isA(SqlKind.TOP_LEVEL)) {
      registerQuery(scope, null, outermostNode, outermostNode, null, false);
    }
    outermostNode.validate(this, scope);
    if (!outermostNode.isA(SqlKind.TOP_LEVEL)) {
      // force type derivation so that we can provide it to the
      // caller later without needing the scope
      deriveType(scope, outermostNode);
    }
    TRACER.trace("After validation: {}", outermostNode);
    return outermostNode;
  }
  // 通用的查询验证入口，负责确保一个 SQL 节点（如 SELECT、UNION、TABLESAMPLE 等）在语义上是合法且可访问的。
  // 触发命名空间验证：通过 SqlValidatorNamespace 启动深层验证（如解析子查询或加载表元数据）。
  // 处理采样逻辑 (TABLESAMPLE)：验证采样率是否在合法范围（0% 到 100%）。
  // 权限与访问控制：检查用户是否有权对该资源执行 SELECT 操作。
  // 快照与流处理验证：处理时间版本查询（SNAPSHOT）以及流式查询的模态验证。
  @Override public void validateQuery(SqlNode node, // 当前需要验证的 SQL 节点。
      SqlValidatorScope scope,
      RelDataType targetRowType) { // 期望的行类型。用于类型推导，如果外部已知结果结构，会传递给此参数。
    final SqlValidatorNamespace ns = getNamespaceOrThrow(node, scope);
    // 处理 TABLESAMPLE 子句
    if (node.getKind() == SqlKind.TABLESAMPLE) {
      List<SqlNode> operands = ((SqlCall) node).getOperandList();
      // 提取采样规范（例如采样比例）
      SqlSampleSpec sampleSpec = SqlLiteral.sampleValue(operands.get(1));
      if (sampleSpec instanceof SqlSampleSpec.SqlTableSampleSpec) {
        // The sampling percentage must be between 0 (0%) and 1 (100%).
        BigDecimal samplePercentage =
            ((SqlSampleSpec.SqlTableSampleSpec) sampleSpec).sampleRate;
        // Check the samplePercentage whether is between 0 and 1
        // 核心校验：采样率必须在 [0, 1] 之间
        if (samplePercentage.compareTo(BigDecimal.ZERO) < 0
            || samplePercentage.compareTo(BigDecimal.ONE) > 0) {
          throw SqlUtil.newContextException(node.getParserPosition(),
              RESOURCE.invalidSampleSize());
        }
        // 校验是否支持标准采样特性 (T613)
        validateFeature(RESOURCE.sQLFeature_T613(), node.getParserPosition());
      } else if (sampleSpec
          instanceof SqlSampleSpec.SqlSubstitutionSampleSpec) {
        // 校验是否支持扩展的替换采样特性
        validateFeature(RESOURCE.sQLFeatureExt_T613_Substitution(),
            node.getParserPosition());
      }
    }
    // 调用 ns.validate(targetRowType)，进而触发具体节点的深度验证（例如，如果是 SELECT 节点，会在这里触发 validateSelect）
    validateNamespace(ns, targetRowType);
    switch (node.getKind()) {
    case EXTEND:
      // Until we have a dedicated namespace for EXTEND
      deriveType(requireNonNull(scope, "scope"), node);
      break;
    default:
      break;
    }
    // 如果是最外层（Top）查询，检查其模态（Modality）。主要用于验证流式 SQL（Streaming SQL），确保查询是纯流式或纯批处理，不会出现非法的混合。
    if (node == top) {
      validateModality(node);
    }
    // 检查当前节点对应的表是否允许 SELECT 操作。
    validateAccess(
        node,
        ns.getTable(),
        SqlAccessEnum.SELECT);
    // 验证时间旅行查询（FOR SYSTEM_TIME AS OF ...），确保快照表达式在当前作用域内是合法的。
    validateSnapshot(node, scope, ns);
  }

  /**
   * Validates a namespace.
   *
   * @param namespace Namespace
   * @param targetRowType Desired row type, must not be null, may be the data
   *                      type 'unknown'.
   */
  // 作用是触发特定命名空间（Namespace）的内部验证逻辑，并确保验证结果符合全局约束（如安全过滤策略）
  // SqlValidatorNamespace 负责维护一个 SQL 节点及其对应的类型信息，而 validateNamespace 则是确保这些信息被正确填充和校验的桥梁
  protected void validateNamespace(final SqlValidatorNamespace namespace,// 可能代表一张表、一个子查询、一个 JOIN 结果或一个 VALUES 子句。该对象持有该节点的 RelDataType（行类型）
      RelDataType targetRowType) { // 期望的行类型
    namespace.validate(targetRowType);
    SqlNode node = namespace.getNode();
    if (node != null) {
      setValidatedNodeType(node, namespace.getType());

      if (node == top) {
        // A top-level namespace must not return any must-filter fields.
        // A non-top-level namespace (e.g. a subquery) may return must-filter
        // fields; these are neutralized if the consuming query filters on them.
        final ImmutableBitSet mustFilterFields =
            namespace.getMustFilterFields();
        if (!mustFilterFields.isEmpty()) {
          // Set of field names, sorted alphabetically for determinism.
          Set<String> fieldNameSet =
              StreamSupport.stream(mustFilterFields.spliterator(), false)
                  .map(namespace.getRowType().getFieldNames()::get)
                  .collect(Collectors.toCollection(TreeSet::new));
          throw newValidationError(node,
              RESOURCE.mustFilterFieldsMissing(fieldNameSet.toString()));
        }
      }
    }
  }

  @Override public SqlValidatorScope getEmptyScope() {
    return new EmptyScope(this);
  }
  // 用于从缓存中获取特定 SQL 子句对应的作用域（Scope）。
  // 同一个 SELECT 语句在处理不同子句（如 FROM、WHERE、GROUP BY）时，其可见的变量和解析规则是不一样的，这些规则被封装在不同的 SqlValidatorScope 对象中。
  private SqlValidatorScope getScope(SqlSelect select, Clause clause) {
    return requireNonNull(
        clauseScopes.get(IdPair.of(select, clause)),
        () -> "no " + clause + " scope for " + select);
  }

  public SqlValidatorScope getCursorScope(SqlSelect select) {
    return getScope(select, Clause.CURSOR);
  }
  // 获取指定 SELECT 语句中 WHERE 子句对应的验证作用域。
  @Override public SqlValidatorScope getWhereScope(SqlSelect select) {
    return getScope(select, Clause.WHERE);
  }

  @Override public SqlValidatorScope getSelectScope(SqlSelect select) {
    return getScope(select, Clause.SELECT);
  }

  @Override public @Nullable SelectScope getRawSelectScope(SqlSelect select) {
    SqlValidatorScope scope = clauseScopes.get(IdPair.of(select, Clause.SELECT));
    if (scope instanceof AggregatingSelectScope) {
      scope = ((AggregatingSelectScope) scope).getParent();
    }
    return (SelectScope) scope;
  }

  private SelectScope getRawSelectScopeNonNull(SqlSelect select) {
    return requireNonNull(getRawSelectScope(select),
        () -> "getRawSelectScope for " + select);
  }

  @Override public SqlValidatorScope getHavingScope(SqlSelect select) {
    // Yes, it's the same as getSelectScope
    return getScope(select, Clause.SELECT);
  }

  @Override public SqlValidatorScope getGroupScope(SqlSelect select) {
    // Yes, it's the same as getWhereScope
    return getScope(select, Clause.WHERE);
  }

  @Override public SqlValidatorScope getFromScope(SqlSelect select) {
    return requireNonNull(scopes.get(select),
        () -> "no scope for " + select);
  }

  @Override public SqlValidatorScope getOrderScope(SqlSelect select) {
    return getScope(select, Clause.ORDER);
  }

  @Override public SqlValidatorScope getMatchRecognizeScope(SqlMatchRecognize node) {
    return getScopeOrThrow(node);
  }

  @Override public SqlValidatorScope getLambdaScope(SqlLambda node) {
    return getScopeOrThrow(node);
  }

  @Override public SqlValidatorScope getJoinScope(SqlNode node) {
    return requireNonNull(scopes.get(stripAs(node)),
        () -> "scope for " + node);
  }

  @Override public SqlValidatorScope getOverScope(SqlNode node) {
    return getScopeOrThrow(node);
  }

  @Override public SqlValidatorScope getWithScope(SqlNode withItem) {
    assert withItem.getKind() == SqlKind.WITH_ITEM;
    return getScopeOrThrow(withItem);
  }

  private SqlValidatorScope getScopeOrThrow(SqlNode node) {
    return requireNonNull(scopes.get(node), () -> "scope for " + node);
  }
  // 根据给定的 SQL 节点（SqlNode）和当前的作用域（Scope），找到该节点对应的“命名空间”（Namespace）
  // SqlValidatorNamespace 代表了一个能够产生行数据的集合（比如一张表、一个子查询或一个函数结果）
  private @Nullable SqlValidatorNamespace getNamespace(SqlNode node, // 需要查找命名空间的语法树节点。它通常是一个表名（SqlIdentifier）、一个带别名的表达式（AS）、或者是一个表引用（TABLE_REF）
      SqlValidatorScope scope) { // 作用域决定了在该节点位置哪些表、列或别名是“可见”的
    if (node instanceof SqlIdentifier && scope instanceof DelegatingScope) {
      // 如果是一个简单的标识符（如表名 EMP）且作用域是“委托作用域”
      // 试从当前 scope 的父级作用域中去查找。这通常用于处理嵌套查询中，内部节点引用外部定义的表或别名的情况
      final SqlIdentifier id = (SqlIdentifier) node;
      final DelegatingScope idScope =
          (DelegatingScope) ((DelegatingScope) scope).getParent();
      return getNamespace(id, idScope);
    } else if (node instanceof SqlCall) {
      // Handle extended identifiers.
      final SqlCall call = (SqlCall) node;
      switch (call.getOperator().getKind()) {
      // 剥离 TABLE_REF 包装。在某些方言中，表引用会被包装，这里递归获取其内部真实的表标识符。
      case TABLE_REF:
        return getNamespace(call.operand(0), scope);
      // 动态列扩展
      case EXTEND:
        // *   **作用**：处理带有动态列定义的表（如 `SELECT * FROM T (c1 INT)`）。
        // *   **逻辑**：它会取第一个操作数（即原始表名），忽略掉后面的列定义，因为 Namespace 是绑定在原始表名上的。
        final SqlNode operand0 = call.getOperandList().get(0);
        final SqlIdentifier identifier = operand0.getKind() == SqlKind.TABLE_REF
            ? ((SqlCall) operand0).operand(0)
            : (SqlIdentifier) operand0;
        final DelegatingScope idScope = (DelegatingScope) scope;
        return getNamespace(identifier, idScope);
      case AS:
        final SqlNode nested = call.getOperandList().get(0);
        switch (nested.getKind()) {
        case TABLE_REF:
        case EXTEND:
          return getNamespace(nested, scope);
        default:
          break;
        }
        break;
      default:
        break;
      }
    }
    return getNamespace(node);
  }
  // 在指定的作用域（Scope）内查找并解析标识符
  // 当 SQL 中出现一个表名或别名时，它可能指向当前 SELECT 的 FROM 子句，也可能指向外部查询的表（关联子查询）。该方法通过调用 scope.resolve 逻辑，在层级化的作用域链中定位该名字对应的命名空间。
  private @Nullable SqlValidatorNamespace getNamespace(SqlIdentifier id, // 要解析的 SQL 标识符（如表名 EMP 或别名 E）
      @Nullable DelegatingScope scope) {
    if (id.isSimple()) {// 返回 true 表示该标识符只有一个部分（例如 EMP），而不是多级限定名（例如 CATALOG.SCHEMA.EMP）
      final SqlNameMatcher nameMatcher = catalogReader.nameMatcher();
      // 一个结果收集器，用于存储解析过程中找到的所有匹配项
      final SqlValidatorScope.ResolvedImpl resolved =
          new SqlValidatorScope.ResolvedImpl();
      // 执行作用域解析（核心逻辑）
      requireNonNull(scope, () -> "scope needed to lookup " + id)
          .resolve(id.names, nameMatcher, false, resolved);
      if (resolved.count() == 1) {
        return resolved.only().namespace;
      }
    }
    return getNamespace(id);
  }
  // 查找或推导给定 SQL 节点（SqlNode）关联的命名空间（SqlValidatorNamespace）。
  // 在 Calcite 中，Namespace 代表一个可以产生行的集合（如表、子查询、联接结果等），它保存了该集合的列名和类型信息。
  @Override public @Nullable SqlValidatorNamespace getNamespace(SqlNode node) {
    switch (node.getKind()) {
    case AS:
    // 先尝试从 namespaces 缓存 Map 中直接获取。
    // 如果找到了（说明该别名重新定义了结构），直接返回。
    // 如果没有找到（说明只是简单的表别名 AS t），则进入 "fall through"（击穿），跳转到下面的逻辑处理其内部的操作数。
      // AS has a namespace if it has a column list 'AS t (c1, c2, ...)'
      // 当遇到 SELECT * FROM table AS t(c1, c2) 这种显式定义了列列表的别名时，验证器会在注册阶段为这个 AS 节点本身创建一个专有的 Namespace。
      final SqlValidatorNamespace ns = namespaces.get(node);
      if (ns != null) {
        return ns;
      }
      // fall through
    // 这些 SqlKind 代表的节点在语义上只是对“数据源”的一个包装或修饰，它们本身并不改变数据源的列结构。
    case TABLE_REF: // 对表的引用。
    case SNAPSHOT: // 时态表快照。
    case OVER:
    case COLLECTION_TABLE: // 表函数调用。
    case ORDER_BY: // 排序操作（排序不改变行的字段）。
    case TABLESAMPLE:
      // 这些节点被视为 SqlCall，程序会递归调用 getNamespace 处理它们的第一个操作数（operand 0）。例如，对于 ORDER_BY (SELECT ...)，它会返回内部 SELECT 的 Namespace。
      return getNamespace(((SqlCall) node).operand(0));
    default:
      return namespaces.get(node);
    }
  }

  /**
   * Namespace for the given node.
   *
   * @param node node to compute the namespace for
   * @return namespace for the given node, never null
   * @see #getNamespace(SqlNode)
   */
  // 尝试获取一个 SQL 节点关联的“命名空间（Namespace）”，如果找不到则立即抛出异常
  // 在 Calcite 验证阶段，Namespace 代表一个能够产生行（Rows）的实体。
  // 一个物理表（TableNamespace）
  // 一个 SELECT 子句（SelectNamespace）
  // 一个 JOIN 表达式（JoinNamespace）
  // 一个 VALUES 子句（IdentifierNamespace 或其子类）
  // 每一个 SqlNode（如果它代表一个数据源），在验证时都会被注册并分配到一个 Namespace 中，这个 Namespace 负责维护该节点的元数据，最重要的是它的行类型（RelDataType）
  @API(since = "1.27", status = API.Status.INTERNAL)
  SqlValidatorNamespace getNamespaceOrThrow(SqlNode node) {
    return requireNonNull(
        getNamespace(node),
        () -> "namespace for " + node);
  }

  /**
   * Namespace for the given node.
   *
   * @param node node to compute the namespace for
   * @param scope namespace scope
   * @return namespace for the given node, never null
   * @see #getNamespace(SqlNode)
   */
  @API(since = "1.27", status = API.Status.INTERNAL)
  SqlValidatorNamespace getNamespaceOrThrow(SqlNode node,
      SqlValidatorScope scope) {
    return requireNonNull(
        getNamespace(node, scope),
        () -> "namespace for " + node + ", scope " + scope);
  }

  /**
   * Namespace for the given node.
   *
   * @param id identifier to resolve
   * @param scope namespace scope
   * @return namespace for the given node, never null
   * @see #getNamespace(SqlIdentifier, DelegatingScope)
   */
  @API(since = "1.26", status = API.Status.INTERNAL)
  SqlValidatorNamespace getNamespaceOrThrow(SqlIdentifier id,
      @Nullable DelegatingScope scope) {
    return requireNonNull(
        getNamespace(id, scope),
        () -> "namespace for " + id + ", scope " + scope);
  }

  private void handleOffsetFetch(@Nullable SqlNode offset, @Nullable SqlNode fetch) {
    if (offset instanceof SqlDynamicParam) {
      setValidatedNodeType(offset,
          typeFactory.createSqlType(SqlTypeName.INTEGER));
    }
    if (fetch instanceof SqlDynamicParam) {
      setValidatedNodeType(fetch,
          typeFactory.createSqlType(SqlTypeName.INTEGER));
    }
  }

  /**
   * Performs expression rewrites which are always used unconditionally. These
   * rewrites massage the expression tree into a standard form so that the
   * rest of the validation logic can be simpler.
   *
   * <p>Returns null if and only if the original expression is null.
   *
   * @param node      expression to be rewritten
   * @param underFrom whether node appears directly under a FROM clause
   * @return rewritten expression, or null if the original expression is null
   */
  protected @PolyNull SqlNode performUnconditionalRewrites(
      @PolyNull SqlNode node,
      boolean underFrom) {
    if (node == null) {
      return null;
    }

    // first transform operands and invoke generic call rewrite
    if (node instanceof SqlCall) {
      if (node instanceof SqlMerge) {
        validatingSqlMerge = true;
      }
      SqlCall call = (SqlCall) node;
      final SqlKind kind = call.getKind();
      final List<SqlNode> operands = call.getOperandList();
      for (int i = 0; i < operands.size(); i++) {
        SqlNode operand = operands.get(i);
        boolean childUnderFrom;
        if (kind == SqlKind.SELECT) {
          childUnderFrom = i == SqlSelect.FROM_OPERAND;
        } else if (kind == SqlKind.AS && (i == 0)) {
          // for an aliased expression, it is under FROM if
          // the AS expression is under FROM
          childUnderFrom = underFrom;
        } else {
          childUnderFrom = false;
        }
        SqlNode newOperand =
            performUnconditionalRewrites(operand, childUnderFrom);
        if (newOperand != null && newOperand != operand) {
          call.setOperand(i, newOperand);
        }
      }

      if (call.getOperator() instanceof SqlUnresolvedFunction) {
        assert call instanceof SqlBasicCall;
        final SqlUnresolvedFunction function =
            (SqlUnresolvedFunction) call.getOperator();
        // This function hasn't been resolved yet.  Perform
        // a half-hearted resolution now in case it's a
        // builtin function requiring special casing.  If it's
        // not, we'll handle it later during overload resolution.
        final List<SqlOperator> overloads = new ArrayList<>();
        opTab.lookupOperatorOverloads(function.getNameAsId(),
            function.getFunctionType(), SqlSyntax.FUNCTION, overloads,
            catalogReader.nameMatcher());
        if (overloads.size() == 1) {
          ((SqlBasicCall) call).setOperator(overloads.get(0));
        }
      }
      if (config.callRewrite()) {
        node = call.getOperator().rewriteCall(this, call);
      }
    } else if (node instanceof SqlNodeList) {
      final SqlNodeList list = (SqlNodeList) node;
      for (int i = 0; i < list.size(); i++) {
        SqlNode operand = list.get(i);
        SqlNode newOperand =
            performUnconditionalRewrites(
                operand,
                false);
        if (newOperand != null) {
          list.set(i, newOperand);
        }
      }
    }

    // now transform node itself
    final SqlKind kind = node.getKind();
    switch (kind) {
    case VALUES:
      // Do not rewrite VALUES clauses.
      // At some point we used to rewrite VALUES(...) clauses
      // to (SELECT * FROM VALUES(...)) but this was problematic
      // in various cases such as FROM (VALUES(...)) [ AS alias ]
      // where the rewrite was invoked over and over making the
      // expression grow indefinitely.
      return node;
    case ORDER_BY: {
      SqlOrderBy orderBy = (SqlOrderBy) node;
      handleOffsetFetch(orderBy.offset, orderBy.fetch);
      if (orderBy.query instanceof SqlSelect) {
        SqlSelect select = (SqlSelect) orderBy.query;

        // Don't clobber existing ORDER BY.  It may be needed for
        // an order-sensitive function like RANK.
        if (select.getOrderList() == null) {
          // push ORDER BY into existing select
          select.setOrderBy(orderBy.orderList);
          select.setOffset(orderBy.offset);
          select.setFetch(orderBy.fetch);
          return select;
        }
      }
      if (orderBy.query instanceof SqlWith
          && ((SqlWith) orderBy.query).body instanceof SqlSelect) {
        SqlWith with = (SqlWith) orderBy.query;
        SqlSelect select = (SqlSelect) with.body;

        // Don't clobber existing ORDER BY.  It may be needed for
        // an order-sensitive function like RANK.
        if (select.getOrderList() == null) {
          // push ORDER BY into existing select
          select.setOrderBy(orderBy.orderList);
          select.setOffset(orderBy.offset);
          select.setFetch(orderBy.fetch);
          return with;
        }
      }
      final SqlNodeList selectList = new SqlNodeList(SqlParserPos.ZERO);
      selectList.add(SqlIdentifier.star(SqlParserPos.ZERO));
      final SqlNodeList orderList;
      SqlSelect innerSelect = getInnerSelect(node);
      if (innerSelect != null && isAggregate(innerSelect)) {
        orderList = SqlNode.clone(orderBy.orderList);
        // We assume that ORDER BY item does not have ASC etc.
        // We assume that ORDER BY item is present in SELECT list.
        for (int i = 0; i < orderList.size(); i++) {
          SqlNode sqlNode = orderList.get(i);
          SqlNodeList selectList2 = SqlNonNullableAccessors.getSelectList(innerSelect);
          for (Ord<SqlNode> sel : Ord.zip(selectList2)) {
            if (stripAs(sel.e).equalsDeep(sqlNode, Litmus.IGNORE)) {
              orderList.set(i,
                  SqlLiteral.createExactNumeric(Integer.toString(sel.i + 1),
                      SqlParserPos.ZERO));
            }
          }
        }
      } else {
        orderList = orderBy.orderList;
      }
      return new SqlSelect(SqlParserPos.ZERO, null, selectList, orderBy.query,
          null, null, null, null, null, orderList, orderBy.offset,
          orderBy.fetch, null);
    }

    case EXPLICIT_TABLE: {
      // (TABLE t) is equivalent to (SELECT * FROM t)
      SqlCall call = (SqlCall) node;
      final SqlNodeList selectList = new SqlNodeList(SqlParserPos.ZERO);
      selectList.add(SqlIdentifier.star(SqlParserPos.ZERO));
      return new SqlSelect(SqlParserPos.ZERO, null, selectList, call.operand(0),
          null, null, null, null, null, null, null, null, null);
    }

    case DELETE: {
      SqlDelete call = (SqlDelete) node;
      SqlSelect select = createSourceSelectForDelete(call);
      call.setSourceSelect(select);
      break;
    }

    case UPDATE: {
      SqlUpdate call = (SqlUpdate) node;
      SqlSelect select = createSourceSelectForUpdate(call);
      call.setSourceSelect(select);

      // See if we're supposed to rewrite UPDATE to MERGE
      // (unless this is the UPDATE clause of a MERGE,
      // in which case leave it alone).
      if (!validatingSqlMerge) {
        SqlNode selfJoinSrcExpr =
            getSelfJoinExprForUpdate(
                call.getTargetTable(),
                UPDATE_SRC_ALIAS);
        if (selfJoinSrcExpr != null) {
          node = rewriteUpdateToMerge(call, selfJoinSrcExpr);
        }
      }
      break;
    }

    case MERGE: {
      SqlMerge call = (SqlMerge) node;
      rewriteMerge(call);
      break;
    }
    default:
      break;
    }
    return node;
  }

  private static @Nullable SqlSelect getInnerSelect(SqlNode node) {
    for (;;) {
      if (node instanceof SqlSelect) {
        return (SqlSelect) node;
      } else if (node instanceof SqlOrderBy) {
        node = ((SqlOrderBy) node).query;
      } else if (node instanceof SqlWith) {
        node = ((SqlWith) node).body;
      } else {
        return null;
      }
    }
  }

  private static void rewriteMerge(SqlMerge call) {
    SqlNodeList selectList;
    SqlUpdate updateStmt = call.getUpdateCall();
    if (updateStmt != null) {
      // if we have an update statement, just clone the select list
      // from the update statement's source since it's the same as
      // what we want for the select list of the merge source -- '*'
      // followed by the update set expressions
      SqlSelect sourceSelect = SqlNonNullableAccessors.getSourceSelect(updateStmt);
      selectList = SqlNode.clone(SqlNonNullableAccessors.getSelectList(sourceSelect));
    } else {
      // otherwise, just use select *
      selectList = new SqlNodeList(SqlParserPos.ZERO);
      selectList.add(SqlIdentifier.star(SqlParserPos.ZERO));
    }
    SqlNode targetTable = call.getTargetTable();
    if (call.getAlias() != null) {
      targetTable =
          SqlValidatorUtil.addAlias(
              targetTable,
              call.getAlias().getSimple());
    }

    // Provided there is an insert substatement, the source select for
    // the merge is a left outer join between the source in the USING
    // clause and the target table; otherwise, the join is just an
    // inner join.  Need to clone the source table reference in order
    // for validation to work
    SqlNode sourceTableRef = call.getSourceTableRef();
    SqlInsert insertCall = call.getInsertCall();
    JoinType joinType = (insertCall == null) ? JoinType.INNER : JoinType.LEFT;
    final SqlNode leftJoinTerm = SqlNode.clone(sourceTableRef);
    SqlNode outerJoin =
        new SqlJoin(SqlParserPos.ZERO,
            leftJoinTerm,
            SqlLiteral.createBoolean(false, SqlParserPos.ZERO),
            joinType.symbol(SqlParserPos.ZERO),
            targetTable,
            JoinConditionType.ON.symbol(SqlParserPos.ZERO),
            call.getCondition());
    SqlSelect select =
        new SqlSelect(SqlParserPos.ZERO, null, selectList, outerJoin, null,
            null, null, null, null, null, null, null, null);
    call.setSourceSelect(select);

    // Source for the insert call is a select of the source table
    // reference with the select list being the value expressions;
    // note that the values clause has already been converted to a
    // select on the values row constructor; so we need to extract
    // that via the from clause on the select
    if (insertCall != null) {
      SqlCall valuesCall = (SqlCall) insertCall.getSource();
      SqlCall rowCall = valuesCall.operand(0);
      selectList =
          new SqlNodeList(
              rowCall.getOperandList(),
              SqlParserPos.ZERO);
      final SqlNode insertSource = SqlNode.clone(sourceTableRef);
      select =
          new SqlSelect(SqlParserPos.ZERO, null, selectList, insertSource, null,
              null, null, null, null, null, null, null, null);
      insertCall.setSource(select);
    }
  }

  private SqlNode rewriteUpdateToMerge(
      SqlUpdate updateCall,
      SqlNode selfJoinSrcExpr) {
    // Make sure target has an alias.
    SqlIdentifier updateAlias = updateCall.getAlias();
    if (updateAlias == null) {
      updateAlias = new SqlIdentifier(UPDATE_TGT_ALIAS, SqlParserPos.ZERO);
      updateCall.setAlias(updateAlias);
    }
    SqlNode selfJoinTgtExpr =
        getSelfJoinExprForUpdate(
            updateCall.getTargetTable(),
            updateAlias.getSimple());
    assert selfJoinTgtExpr != null;

    // Create join condition between source and target exprs,
    // creating a conjunction with the user-level WHERE
    // clause if one was supplied
    SqlNode condition = updateCall.getCondition();
    SqlNode selfJoinCond =
        SqlStdOperatorTable.EQUALS.createCall(
            SqlParserPos.ZERO,
            selfJoinSrcExpr,
            selfJoinTgtExpr);
    if (condition == null) {
      condition = selfJoinCond;
    } else {
      condition =
          SqlStdOperatorTable.AND.createCall(
              SqlParserPos.ZERO,
              selfJoinCond,
              condition);
    }
    SqlNode target =
        updateCall.getTargetTable().clone(SqlParserPos.ZERO);

    // For the source, we need to anonymize the fields, so
    // that for a statement like UPDATE T SET I = I + 1,
    // there's no ambiguity for the "I" in "I + 1";
    // this is OK because the source and target have
    // identical values due to the self-join.
    // Note that we anonymize the source rather than the
    // target because downstream, the optimizer rules
    // don't want to see any projection on top of the target.
    IdentifierNamespace ns =
        new IdentifierNamespace(this, target, null,
            castNonNull(null));
    RelDataType rowType = ns.getRowType();
    SqlNode source = updateCall.getTargetTable().clone(SqlParserPos.ZERO);
    final SqlNodeList selectList = new SqlNodeList(SqlParserPos.ZERO);
    int i = 1;
    for (RelDataTypeField field : rowType.getFieldList()) {
      SqlIdentifier col =
          new SqlIdentifier(
              field.getName(),
              SqlParserPos.ZERO);
      selectList.add(
          SqlValidatorUtil.addAlias(col, UPDATE_ANON_PREFIX + i));
      ++i;
    }
    source =
        new SqlSelect(SqlParserPos.ZERO, null, selectList, source, null, null,
            null, null, null, null, null, null, null);
    source = SqlValidatorUtil.addAlias(source, UPDATE_SRC_ALIAS);
    SqlMerge mergeCall =
        new SqlMerge(updateCall.getParserPosition(), target, condition, source,
            updateCall, null, null, updateCall.getAlias());
    rewriteMerge(mergeCall);
    return mergeCall;
  }

  /**
   * Allows a subclass to provide information about how to convert an UPDATE
   * into a MERGE via self-join. If this method returns null, then no such
   * conversion takes place. Otherwise, this method should return a suitable
   * unique identifier expression for the given table.
   *
   * @param table identifier for table being updated
   * @param alias alias to use for qualifying columns in expression, or null
   *              for unqualified references; if this is equal to
   *              {@value #UPDATE_SRC_ALIAS}, then column references have been
   *              anonymized to "SYS$ANONx", where x is the 1-based column
   *              number.
   * @return expression for unique identifier, or null to prevent conversion
   */
  protected @Nullable SqlNode getSelfJoinExprForUpdate(
      SqlNode table,
      String alias) {
    return null;
  }

  /**
   * Creates the SELECT statement that putatively feeds rows into an UPDATE
   * statement to be updated.
   *
   * @param call Call to the UPDATE operator
   * @return select statement
   */
  protected SqlSelect createSourceSelectForUpdate(SqlUpdate call) {
    final SqlNodeList selectList = new SqlNodeList(SqlParserPos.ZERO);
    selectList.add(SqlIdentifier.star(SqlParserPos.ZERO));
    int ordinal = 0;
    for (SqlNode exp : call.getSourceExpressionList()) {
      // Force unique aliases to avoid a duplicate for Y with
      // SET X=Y
      String alias = SqlUtil.deriveAliasFromOrdinal(ordinal);
      selectList.add(SqlValidatorUtil.addAlias(exp, alias));
      ++ordinal;
    }
    SqlNode sourceTable = call.getTargetTable();
    SqlIdentifier alias = call.getAlias();
    if (alias != null) {
      sourceTable =
          SqlValidatorUtil.addAlias(
              sourceTable,
              alias.getSimple());
    }
    return new SqlSelect(SqlParserPos.ZERO, null, selectList, sourceTable,
        call.getCondition(), null, null, null, null, null, null, null, null);
  }

  /**
   * Creates the SELECT statement that putatively feeds rows into a DELETE
   * statement to be deleted.
   *
   * @param call Call to the DELETE operator
   * @return select statement
   */
  protected SqlSelect createSourceSelectForDelete(SqlDelete call) {
    final SqlNodeList selectList = new SqlNodeList(SqlParserPos.ZERO);
    selectList.add(SqlIdentifier.star(SqlParserPos.ZERO));
    SqlNode sourceTable = call.getTargetTable();
    SqlIdentifier alias = call.getAlias();
    if (alias != null) {
      sourceTable =
          SqlValidatorUtil.addAlias(
              sourceTable,
              alias.getSimple());
    }
    return new SqlSelect(SqlParserPos.ZERO, null, selectList, sourceTable,
        call.getCondition(), null, null, null, null, null, null, null, null);
  }

  /**
   * Returns null if there is no common type. E.g. if the rows have a
   * different number of columns.
   */
  @Nullable RelDataType getTableConstructorRowType(
      SqlCall values,
      SqlValidatorScope scope) {
    final List<SqlNode> rows = values.getOperandList();
    assert rows.size() >= 1;
    final List<RelDataType> rowTypes = new ArrayList<>();
    for (final SqlNode row : rows) {
      assert row.getKind() == SqlKind.ROW;
      SqlCall rowConstructor = (SqlCall) row;

      // REVIEW jvs 10-Sept-2003: Once we support single-row queries as
      // rows, need to infer aliases from there.
      final List<String> aliasList = new ArrayList<>();
      final List<RelDataType> typeList = new ArrayList<>();
      for (Ord<SqlNode> column : Ord.zip(rowConstructor.getOperandList())) {
        final String alias = SqlValidatorUtil.alias(column.e, column.i);
        aliasList.add(alias);
        final RelDataType type = deriveType(scope, column.e);
        typeList.add(type);
      }
      rowTypes.add(typeFactory.createStructType(typeList, aliasList));
    }
    if (rows.size() == 1) {
      // TODO jvs 10-Oct-2005:  get rid of this workaround once
      // leastRestrictive can handle all cases
      return rowTypes.get(0);
    }
    return typeFactory.leastRestrictive(rowTypes);
  }

  @Override public RelDataType getValidatedNodeType(SqlNode node) {
    RelDataType type = getValidatedNodeTypeIfKnown(node);
    if (type == null) {
      if (node.getKind() == SqlKind.IDENTIFIER) {
        throw newValidationError(node, RESOURCE.unknownIdentifier(node.toString()));
      }
      throw Util.needToImplement(node);
    } else {
      return type;
    }
  }

  @Override public @Nullable RelDataType getValidatedNodeTypeIfKnown(SqlNode node) {
    // 首先尝试从 nodeToTypeMap 这个成员变量中查询。
    final RelDataType type = nodeToTypeMap.get(node);
    if (type != null) {
      return type;
    }
    // 如果直接映射中没有，尝试通过 getNamespace(node) 获取该节点关联的“命名空间”。
    // 在 Calcite 中，关系表达式（如子查询、FROM 子句中的表）会被包装成 SqlValidatorNamespace。
    // Namespace 负责管理该集合的行类型（Row Type）。如果节点代表的是一个集合（如 SELECT 子句本身），这里能拿到它的结构信息。
    final SqlValidatorNamespace ns = getNamespace(node);
    if (ns != null) {
      return ns.getType();
    }
    // 检查 originalExprs 映射。这个 Map 记录了节点在经过改写（Rewrite）或展开（Expand）之前的“前身”。
    final SqlNode original = originalExprs.get(node);
    if (original != null && original != node) {
      // 递归查找
      return getValidatedNodeTypeIfKnown(original);
    }
    // 如果前面的缓存都失效，且当前节点是一个标识符（SqlIdentifier）
    // 调用 CatalogReader（目录读取器）去元数据中查找是否存在同名的自定义数据类型。例如，在 CAST(col AS MyType) 中，MyType 是一个标识符，验证器会去数据库元数据（Schema）中查找是否有名为 MyType 的类型定义。
    if (node instanceof SqlIdentifier) {
      return getCatalogReader().getNamedType((SqlIdentifier) node);
    }
    return null;
  }

  @Override public @Nullable List<RelDataType> getValidatedOperandTypes(SqlCall call) {
    return callToOperandTypesMap.get(call);
  }

  /**
   * Saves the type of a {@link SqlNode}, now that it has been validated.
   *
   * <p>Unlike the base class method, this method is not deprecated.
   * It is available from within Calcite, but is not part of the public API.
   *
   * @param node A SQL parse tree node, never null
   * @param type Its type; must not be null
   */
  @Override public final void setValidatedNodeType(SqlNode node, RelDataType type) {
    requireNonNull(type, "type");
    requireNonNull(node, "node");
    if (type.equals(unknownType)) {
      // don't set anything until we know what it is, and don't overwrite
      // a known type with the unknown type
      return;
    }
    nodeToTypeMap.put(node, type);
  }

  @Override public void removeValidatedNodeType(SqlNode node) {
    nodeToTypeMap.remove(node);
  }

  @Override public @Nullable SqlCall makeNullaryCall(SqlIdentifier id) {
    if (id.names.size() == 1 && !id.isComponentQuoted(0)) {
      final List<SqlOperator> list = new ArrayList<>();
      opTab.lookupOperatorOverloads(id, null, SqlSyntax.FUNCTION, list,
          catalogReader.nameMatcher());
      for (SqlOperator operator : list) {
        if (operator.getSyntax() == SqlSyntax.FUNCTION_ID) {
          // Even though this looks like an identifier, it is a
          // actually a call to a function. Construct a fake
          // call to this function, so we can use the regular
          // operator validation.
          return new SqlBasicCall(operator, ImmutableList.of(),
              id.getParserPosition(), null).withExpanded(true);
        }
      }
    }
    return null;
  }

  @Override public RelDataType deriveType(
      SqlValidatorScope scope,
      SqlNode expr) {
    requireNonNull(scope, "scope");
    requireNonNull(expr, "expr");

    // if we already know the type, no need to re-derive
    RelDataType type = nodeToTypeMap.get(expr);
    if (type != null) {
      return type;
    }
    final SqlValidatorNamespace ns = getNamespace(expr);
    if (ns != null) {
      return ns.getType();
    }
    type = deriveTypeImpl(scope, expr);
    requireNonNull(type, "SqlValidator.deriveTypeInternal returned null");
    setValidatedNodeType(expr, type);
    return type;
  }

  /**
   * Derives the type of a node, never null.
   */
  RelDataType deriveTypeImpl(
      SqlValidatorScope scope,
      SqlNode operand) {
    DeriveTypeVisitor v = new DeriveTypeVisitor(scope);
    final RelDataType type = operand.accept(v);
    return requireNonNull(scope.nullifyType(operand, type));
  }

  @Override public RelDataType deriveConstructorType(
      SqlValidatorScope scope,
      SqlCall call,
      SqlFunction unresolvedConstructor,
      @Nullable SqlFunction resolvedConstructor,
      List<RelDataType> argTypes) {
    SqlIdentifier sqlIdentifier = unresolvedConstructor.getSqlIdentifier();
    assert sqlIdentifier != null;
    RelDataType type = catalogReader.getNamedType(sqlIdentifier);
    if (type == null) {
      // TODO jvs 12-Feb-2005:  proper type name formatting
      throw newValidationError(sqlIdentifier,
          RESOURCE.unknownDatatypeName(sqlIdentifier.toString()));
    }

    if (resolvedConstructor == null) {
      if (call.operandCount() > 0) {
        // This is not a default constructor invocation, and
        // no user-defined constructor could be found
        throw handleUnresolvedFunction(call, unresolvedConstructor, argTypes,
            null);
      }
    } else {
      SqlCall testCall =
          resolvedConstructor.createCall(
              call.getParserPosition(),
              call.getOperandList());
      RelDataType returnType =
          resolvedConstructor.validateOperands(
              this,
              scope,
              testCall);
      assert type == returnType;
    }

    if (config.identifierExpansion()) {
      if (resolvedConstructor != null) {
        ((SqlBasicCall) call).setOperator(resolvedConstructor);
      } else {
        // fake a fully-qualified call to the default constructor
        ((SqlBasicCall) call).setOperator(
            new SqlFunction(
                requireNonNull(type.getSqlIdentifier(), () -> "sqlIdentifier of " + type),
                ReturnTypes.explicit(type),
                null,
                null,
                null,
                SqlFunctionCategory.USER_DEFINED_CONSTRUCTOR));
      }
    }
    return type;
  }

  @Override public CalciteException handleUnresolvedFunction(SqlCall call,
      SqlOperator unresolvedFunction, List<RelDataType> argTypes,
      @Nullable List<String> argNames) {
    // For builtins, we can give a better error message
    final List<SqlOperator> overloads = new ArrayList<>();
    opTab.lookupOperatorOverloads(unresolvedFunction.getNameAsId(), null,
        SqlSyntax.FUNCTION, overloads, catalogReader.nameMatcher());
    if (overloads.size() == 1) {
      SqlFunction fun = (SqlFunction) overloads.get(0);
      if ((fun.getSqlIdentifier() == null)
          && (fun.getSyntax() != SqlSyntax.FUNCTION_ID)) {
        final int expectedArgCount =
            fun.getOperandCountRange().getMin();
        throw newValidationError(call,
            RESOURCE.invalidArgCount(call.getOperator().getName(),
                expectedArgCount));
      }
    }

    final String signature;
    if (unresolvedFunction instanceof SqlFunction) {
      final SqlOperandTypeChecker typeChecking =
          new AssignableOperandTypeChecker(argTypes, argNames);
      signature =
          typeChecking.getAllowedSignatures(unresolvedFunction,
              unresolvedFunction.getName());
    } else {
      signature = unresolvedFunction.getName();
    }
    throw newValidationError(call,
        RESOURCE.validatorUnknownFunction(signature));
  }
  // 核心作用是自顶向下（Top-Down）地推导表达式的类型。
  // 在 SQL 中，有些节点本身没有明确类型（例如动态参数 ? 或 NULL 字面量）。该方法利用上下文信息（即父节点期望的类型）来填充这些“未知”类型。
  protected void inferUnknownTypes(
      RelDataType inferredType, // 根据上下文推导出的期望类型，（父节点传下来的类型）示例：在 INSERT INTO t(int_col) VALUES(?) 中，父节点会传给 ? 一个 INTEGER 类型。
      SqlValidatorScope scope, // 作用：当前的名称解析作用域。用于查找标识符、解析函数等。
      SqlNode node) { // 作用：当前正在处理的 SQL 语法树节点。
    requireNonNull(inferredType, "inferredType");
    requireNonNull(scope, "scope");
    requireNonNull(node, "node");
    // 确保参数非空。同时检查当前节点是否关联了特定的 Scope（例如 SqlSelect 节点会有自己的作用域），如果有则更新 scope。
    final SqlValidatorScope newScope = scopes.get(node);
    if (newScope != null) {
      scope = newScope;
    }
    // 如果遇到 ? 或 NULL，它们本身没有类型。
    // 代码会使用 inferredType（父节点期望的类型）作为其类型，并强制设置为 NULLABLE（允许为空）。如果推导出的类型是字符型，还会补全字符集和校对规则。
    boolean isNullLiteral = SqlUtil.isNullLiteral(node, false);
    if ((node instanceof SqlDynamicParam) || isNullLiteral) {
      if (inferredType.equals(unknownType)) {
        if (isNullLiteral) {
          if (config.typeCoercionEnabled()) {
            // derive type of null literal
            deriveType(scope, node); // 尝试自动强转
            return;
          } else {
            throw newValidationError(node, RESOURCE.nullIllegal());
          }
        } else {
          throw newValidationError(node, RESOURCE.dynamicParamIllegal());
        }
      }

      // REVIEW:  should dynamic parameter types always be nullable?
      // 核心逻辑：应用推导出的类型
      RelDataType newInferredType =
          typeFactory.createTypeWithNullability(inferredType, true);
      if (SqlTypeUtil.inCharFamily(inferredType)) {
        newInferredType =
            typeFactory.createTypeWithCharsetAndCollation(
                newInferredType,
                getCharset(inferredType),
                getCollation(inferredType));
      }
      // 将推导出的类型存入验证器
      setValidatedNodeType(node, newInferredType);
    } else if (node instanceof SqlNodeList) {
      SqlNodeList nodeList = (SqlNodeList) node;
      if (inferredType.isStruct()) {
        if (inferredType.getFieldCount() != nodeList.size()) {
          // this can happen when we're validating an INSERT
          // where the source and target degrees are different;
          // bust out, and the error will be detected higher up
          return;
        }
      }
      int i = 0;
      for (SqlNode child : nodeList) {
        RelDataType type;
        if (inferredType.isStruct()) {
          type = inferredType.getFieldList().get(i).getType();
          ++i;
        } else {
          type = inferredType;
        }
        inferUnknownTypes(type, scope, child);
      }
    } else if (node instanceof SqlCase) {
      final SqlCase caseCall = (SqlCase) node;

      final RelDataType whenType =
          caseCall.getValueOperand() == null ? booleanType : unknownType;
      for (SqlNode sqlNode : caseCall.getWhenOperands()) {
        inferUnknownTypes(whenType, scope, sqlNode);
      }
      RelDataType returnType = deriveType(scope, node);
      for (SqlNode sqlNode : caseCall.getThenOperands()) {
        inferUnknownTypes(returnType, scope, sqlNode);
      }

      SqlNode elseOperand =
          requireNonNull(caseCall.getElseOperand(),
              () -> "elseOperand for " + caseCall);
      if (!SqlUtil.isNullLiteral(elseOperand, false)) {
        inferUnknownTypes(
            returnType,
            scope,
            elseOperand);
      } else {
        setValidatedNodeType(elseOperand, returnType);
      }
    } else if (node.getKind()  == SqlKind.AS) {
      // For AS operator, only infer the operand not the alias
      inferUnknownTypes(inferredType, scope, ((SqlCall) node).operand(0));
    } else if (node instanceof SqlCall) {
      final SqlCall call = (SqlCall) node;
      final SqlOperandTypeInference operandTypeInference =
          call.getOperator().getOperandTypeInference();
      final SqlCallBinding callBinding = new SqlCallBinding(this, scope, call);
      final List<SqlNode> operands = callBinding.operands();
      final RelDataType[] operandTypes = new RelDataType[operands.size()];
      Arrays.fill(operandTypes, unknownType);
      // TODO:  eventually should assert(operandTypeInference != null)
      // instead; for now just eat it
      if (operandTypeInference != null) {
        operandTypeInference.inferOperandTypes(
            callBinding,
            inferredType,
            operandTypes);
      }
      for (int i = 0; i < operands.size(); ++i) {
        final SqlNode operand = operands.get(i);
        if (operand != null) {
          inferUnknownTypes(operandTypes[i], scope, operand);
        }
      }
    }
  }

  /**
   * Adds an expression to a select list, ensuring that its alias does not
   * clash with any existing expressions on the list.
   */
  // 核心任务是将一个表达式安全地加入到 SELECT 列表中，并处理别名冲突，确保最终生成的每一列都有一个唯一的名称。
  protected void addToSelectList(
      List<SqlNode> list, // [输出参数] 最终的 SELECT 项列表。经过别名处理后的 SqlNode 会被添加到这个集合中。
      Set<String> aliases, // [状态记录] 已存在的别名集合。用于检查当前生成的别名是否与同一 SELECT 列表中的其他列重名。
      List<Map.Entry<String, RelDataType>> fieldList, // [输出参数] 字段元数据列表。记录每一列的最终名称及其推导出的数据类型。
      SqlNode exp, // 待添加的原始表达式节点（例如一个列引用或加法运算）
      SelectScope scope, // 当前 SELECT 语句的作用域，用于在添加时推导表达式的类型。
      final boolean includeSystemVars) {
    // 如果表达式是 col AS my_name，则获取 my_name；如果是普通的 col，则获取 col；如果是复杂表达式且没写 AS，则可能返回 null 或生成的默认名。
    final @Nullable String alias = SqlValidatorUtil.alias(exp);
    // 将 alias 与已有的 aliases 集合进行比对
    // 如果发现冲突（例如 SELECT a, a），它会根据 EXPR_SUGGESTER 策略生成一个唯一的名称（例如将第二个 a 变为 a0 或 EXPR$1），确保 SQL 结果集的每一列名都是唯一的。
    String uniqueAlias =
        SqlValidatorUtil.uniquify(
            alias, aliases, SqlValidatorUtil.EXPR_SUGGESTER);
    // 检查生成的唯一别名是否与原始别名不同
    if (!Objects.equals(alias, uniqueAlias)) {
      exp = SqlValidatorUtil.addAlias(exp, uniqueAlias);
    }
    // 将“唯一列名”和“推导出的类型”存入元数据列表，这构成了查询结果集的 Schema
    ((PairList<String, RelDataType>) fieldList)
        .add(uniqueAlias, deriveType(scope, exp));
    list.add(exp);
  }

  @Override public @Nullable String deriveAlias(
      SqlNode node,
      int ordinal) {
    return ordinal < 0 ? SqlValidatorUtil.alias(node)
        : SqlValidatorUtil.alias(node, ordinal);
  }

  protected boolean shouldAllowIntermediateOrderBy() {
    return true;
  }

  private void registerMatchRecognize(
      SqlValidatorScope parentScope,
      SqlValidatorScope usingScope,
      SqlMatchRecognize call,
      SqlNode enclosingNode,
      @Nullable String alias,
      boolean forceNullable) {

    final MatchRecognizeNamespace matchRecognizeNamespace =
        createMatchRecognizeNameSpace(call, enclosingNode);
    registerNamespace(usingScope, alias, matchRecognizeNamespace, forceNullable);

    final MatchRecognizeScope matchRecognizeScope =
        new MatchRecognizeScope(parentScope, call);
    scopes.put(call, matchRecognizeScope);

    // parse input query
    SqlNode expr = call.getTableRef();
    SqlNode newExpr =
        registerFrom(usingScope, matchRecognizeScope, true, expr,
            expr, null, null, forceNullable, false);
    if (expr != newExpr) {
      call.setOperand(0, newExpr);
    }
  }

  protected MatchRecognizeNamespace createMatchRecognizeNameSpace(
      SqlMatchRecognize call,
      SqlNode enclosingNode) {
    return new MatchRecognizeNamespace(this, call, enclosingNode);
  }

  private void registerPivot(
      SqlValidatorScope parentScope,
      SqlValidatorScope usingScope,
      SqlPivot pivot,
      SqlNode enclosingNode,
      @Nullable String alias,
      boolean forceNullable) {
    final PivotNamespace namespace =
        createPivotNameSpace(pivot, enclosingNode);
    registerNamespace(usingScope, alias, namespace, forceNullable);

    final SqlValidatorScope scope =
        new PivotScope(parentScope, pivot);
    scopes.put(pivot, scope);

    // parse input query
    SqlNode expr = pivot.query;
    SqlNode newExpr =
        registerFrom(parentScope, scope, true, expr,
            expr, null, null, forceNullable, false);
    if (expr != newExpr) {
      pivot.setOperand(0, newExpr);
    }
  }

  protected PivotNamespace createPivotNameSpace(SqlPivot call,
      SqlNode enclosingNode) {
    return new PivotNamespace(this, call, enclosingNode);
  }

  private void registerUnpivot(
      SqlValidatorScope parentScope,
      SqlValidatorScope usingScope,
      SqlUnpivot call,
      SqlNode enclosingNode,
      @Nullable String alias,
      boolean forceNullable) {
    final UnpivotNamespace namespace =
        createUnpivotNameSpace(call, enclosingNode);
    registerNamespace(usingScope, alias, namespace, forceNullable);

    final SqlValidatorScope scope =
        new UnpivotScope(parentScope, call);
    scopes.put(call, scope);

    // parse input query
    SqlNode expr = call.query;
    SqlNode newExpr =
        registerFrom(parentScope, scope, true, expr,
            expr, null, null, forceNullable, false);
    if (expr != newExpr) {
      call.setOperand(0, newExpr);
    }
  }

  protected UnpivotNamespace createUnpivotNameSpace(SqlUnpivot call,
      SqlNode enclosingNode) {
    return new UnpivotNamespace(this, call, enclosingNode);
  }

  /**
   * Registers a new namespace, and adds it as a child of its parent scope.
   * Derived class can override this method to tinker with namespaces as they
   * are created.
   *
   * @param usingScope    Parent scope (which will want to look for things in
   *                      this namespace)
   * @param alias         Alias by which parent will refer to this namespace
   * @param ns            Namespace
   * @param forceNullable Whether to force the type of namespace to be nullable
   */
  // 不仅把一个新创建的命名空间（SqlValidatorNamespace）登记到全局的缓存 Map 中，
  // 同时还要把它作为子节点（Child）绑定到其上层的父级作用域（usingScope）中，并赋予它一个别名（alias）。
  // 这样，当上层子句在解析列名时，就能顺着 Scope 找到这个 Namespace。
  protected void registerNamespace(
      // 当前 Namespace 所属的父级/上层作用域（即哪一个 Scope 想要在后续的名字解析中看到并使用这个 Namespace）。
      // 如果传入不为 null，会把当前 Namespace 挂载到该 Scope 的子节点列表里。例如在解析 FROM emp AS e 时，usingScope 就是当前的 SelectScope。
      @Nullable SqlValidatorScope usingScope,
      // 父级作用域用来引用、称呼这个 Namespace 的别名（Alias）。
      // 例如 FROM emp AS e 中的 "e"。如果在 SQL 中没有显式写别名，Calcite 通常也会根据表名生成一个默认别名（如 "EMP"）。
      @Nullable String alias,
      // 当前正在被注册的命名空间对象本身（代表了某个数据源、物理表或子查询结果集）。
      SqlValidatorNamespace ns,
      // 是否强行将该 Namespace 的行类型（Row Type）标记为可空（Nullable）。
      // 主要用于处理 LEFT JOIN / RIGHT JOIN / FULL JOIN 等外连接场景。当一张表作为 LEFT JOIN 的右表引入时，虽然它原本的字段可能是 NOT NULL 的，但由于外连接的特性，未能匹配时会产生 NULL，此时该参数传入 true，以确保类型推导的正确性。
      boolean forceNullable) {
    // 1. 尝试从全局 namespaces 缓存 Map 中获取该节点对应的 Namespace
    SqlValidatorNamespace namespace =
        namespaces.get(requireNonNull(ns.getNode(), () -> "ns.getNode() for " + ns));
    // 2. 如果全局缓存中还没有注册过这个节点
    if (namespace == null) {
      // 将当前传入的 ns 放入全局缓存 Map 中，实现全局共享和幂等
      namespaces.put(requireNonNull(ns.getNode()), ns);
      namespace = ns;
    }
    // 3. 如果指定了父级作用域（usingScope 不为空）
    if (usingScope != null) {
      // 这是一个断言：既然指定了要将 Namespace 注册进某个 Scope，那么它必须有别名（alias），否则上层无法引用它
      assert alias != null : "Registering namespace " + ns + ", into scope " + usingScope
          + ", so alias must not be null";
      // 核心操作：调用 Scope 的 addChild 方法，将该 Namespace 正式绑定为 Scope 的子节点
      // 把“数据源”（Namespace）和“名字查找上下文”（Scope）串联起来的关键。
      // 举个例子，当把 emp（别名 e）通过 addChild 挂载到 SelectScope 之后，后续在 WHERE 子句或 SELECT 列表中写 e.empno 时，SelectScope 就能顺着内部的 child 映射表识别出 e 代表的就是这个 emp 命名空间，进而解析出 empno 的字段和类型。
      usingScope.addChild(namespace, alias, forceNullable);
    }
  }

  /**
   * Registers scopes and namespaces implied a relational expression in the
   * FROM clause.
   *
   * <p>{@code parentScope0} and {@code usingScope} are often the same. They
   * differ when the namespace are not visible within the parent. (Example
   * needed.)
   *
   * <p>Likewise, {@code enclosingNode} and {@code node} are often the same.
   * {@code enclosingNode} is the topmost node within the FROM clause, from
   * which any decorations like an alias (<code>AS alias</code>) or a table
   * sample clause are stripped away to get {@code node}. Both are recorded in
   * the namespace.
   *
   * @param parentScope0  Parent scope that this scope turns to in order to
   *                      resolve objects
   * @param usingScope    Scope whose child list this scope should add itself to
   * @param register      Whether to register this scope as a child of
   *                      {@code usingScope}
   * @param node          Node which namespace is based on
   * @param enclosingNode Outermost node for namespace, including decorations
   *                      such as alias and sample clause
   * @param alias         Alias
   * @param extendList    Definitions of extended columns
   * @param forceNullable Whether to force the type of namespace to be
   *                      nullable because it is in an outer join
   * @param lateral       Whether LATERAL is specified, so that items to the
   *                      left of this in the JOIN tree are visible in the
   *                      scope
   * @return registered node, usually the same as {@code node}
   */
  // 为 FROM 子句中出现的各种关系表达式（表引用、子查询、JOIN、别名、LATERAL、TABLESAMPLE 等各种形式）注册对应的作用域（Scope）和命名空间（Namespace）。
  // 这个方法需要处理 FROM 子句中几乎所有可能出现的语法形式，例如：
  // 简单表引用：FROM emp
  // 带别名的表引用：FROM emp AS e
  // 子查询：FROM (SELECT ...)
  // JOIN：FROM a JOIN b ON ...
  // LATERAL：FROM a, LATERAL (SELECT ... FROM b WHERE b.x = a.x)
  // TABLESAMPLE：FROM emp TABLESAMPLE BERNOULLI(50)
  // 表函数：FROM TABLE(f(x))
  // MATCH_RECOGNIZE、PIVOT、UNPIVOT 等高级语法
  private SqlNode registerFrom(
      // 父作用域，即该 FROM 项在解析内部标识符时应该"回退"求助的作用域。
      // 之所以命名带有 0 后缀，是因为方法内部会根据 lateral 参数的值，可能计算出一个新的 parentScope（区别于原始传入的 parentScope0），所以用 0 后缀标记这是"最初传入的、未经处理的"父作用域。
      SqlValidatorScope parentScope0,
      // 当前命名空间应该把自己"加入"到哪个作用域的子节点列表中
      SqlValidatorScope usingScope,
      // 是否需要将当前正在处理的这个作用域/命名空间，注册为 usingScope 的子节点。有些场景下（比如内部递归调用处理某个中间层级的表达式时）暂时不希望立即注册，而是交给外层的调用来统一处理注册逻辑。
      boolean register,
      // 当前命名空间所基于的具体节点，即真正要处理的核心内容节点（比如剥离了别名装饰后的裸节点）。
      final SqlNode node,
      // 命名空间的"最外层节点"，包含像别名（AS alias）、TABLESAMPLE 子句等装饰性内容。
      // Javadoc 说明：enclosingNode 和 node 通常相同，只有当存在这些装饰时才会不同——此时 enclosingNode 是最外层（带装饰）的节点，而 node 是去除装饰后的内层节点，两者都会被记录到命名空间中。
      SqlNode enclosingNode,
      // 该 FROM 项的别名。可以为 null，表示调用时尚未确定别名，需要方法内部逻辑自动生成（比如给一个匿名子查询生成类似 EXPR$1 这样的默认别名）。
      @Nullable String alias,
      // 扩展列的定义列表，对应 SQL 中 EXTEND 语法（例如 FROM emp EXTEND (bonus DECIMAL(10,2))，允许在查询时临时给表追加额外的列定义）。可以为 null，表示没有扩展列。
      @Nullable SqlNodeList extendList,
      // 是否强制将该命名空间的行类型标记为"可为空"（nullable）。
      // 典型应用场景是外连接（OUTER JOIN）：比如 LEFT JOIN 的右表，在没有匹配行时其所有列都会呈现为 NULL，因此需要强制将其类型标记为可空，即使原始列定义是 NOT NULL。
      boolean forceNullable,
      // 是否指定了 LATERAL 关键字。
      // 如果为 true，意味着该 FROM 项左侧的（在 JOIN 树中排在它之前的）表项在此处也是可见的（即支持相关子查询这种"横向"引用能力，这是 LATERAL 关键字的核心语义）。
      final boolean lateral) {
    final SqlKind kind = node.getKind();

    SqlNode expr;
    SqlNode newExpr;

    // Add an alias if necessary.
    SqlNode newNode = node;
    // 只有当调用方没有显式传入别名时，才需要方法内部自动生成别名。
    // 这里通过内层的 switch (kind) 对不同种类的节点采取不同的默认别名生成策略：
    if (alias == null) {
      switch (kind) {
      case IDENTIFIER:
      case OVER:
        alias = SqlValidatorUtil.alias(node);
        if (alias == null) {
          alias = SqlValidatorUtil.alias(node, nextGeneratedId++);
        }
        if (config.identifierExpansion()) {
          newNode = SqlValidatorUtil.addAlias(node, alias);
        }
        break;

      case SELECT:
      case UNION:
      case INTERSECT:
      case EXCEPT:
      case VALUES:
      case UNNEST:
      case OTHER_FUNCTION:
      case COLLECTION_TABLE:
      case PIVOT:
      case UNPIVOT:
      case MATCH_RECOGNIZE:
      case WITH:
        // give this anonymous construct a name since later
        // query processing stages rely on it
        alias = SqlValidatorUtil.alias(node, nextGeneratedId++);
        if (config.identifierExpansion()) {
          // Since we're expanding identifiers, we should make the
          // aliases explicit too, otherwise the expanded query
          // will not be consistent if we convert back to SQL, e.g.
          // "select EXPR$1.EXPR$2 from values (1)".
          newNode = SqlValidatorUtil.addAlias(node, alias);
        }
        break;
      default:
        break;
      }
    }

    final SqlValidatorScope parentScope;
    // 处理 LATERAL 相关的父作用域计算
    if (lateral) {
      // 如果指定了 LATERAL，意味着该表达式需要能够看到 JOIN 树中排在它左侧的其他表项，因此需要构造一个特殊的作用域来实现这种"横向可见性"：
      SqlValidatorScope s = usingScope;
      // 不断"剥离"，取其内部的 usingScope，直到找到一个不是 JoinScope 的作用域为止（这是为了穿透多层嵌套的 JOIN 作用域，找到最"根本"的那个非 JOIN 作用域，
      // 因为 LATERAL 需要的是能囊括左侧所有表的那个层面的作用域信息）。
      while (s instanceof JoinScope) {
        s = ((JoinScope) s).getUsingScope();
      }
      // 如果找到的 s 不为 null，取其对应的节点 s.getNode()；否则退化使用当前的 node 本身。
      final SqlNode node2 = s != null ? s.getNode() : node;
      // 基于最初的 parentScope0 和上一步确定的节点，创建一个新的 TableScope（专门用于表引用的作用域类型）
      final TableScope tableScope = new TableScope(parentScope0, node2);
      // 如果 usingScope 是 ListScope（维护子命名空间列表的作用域类型），则遍历它已有的所有子项（children），
      // 逐一添加到新创建的 tableScope 中——这一步的目的是把 JOIN 树中左侧已经注册过的表都"复制"进这个新的 tableScope，
      // 使得 LATERAL 表达式内部能够引用这些左侧的表
      if (usingScope instanceof ListScope) {
        for (ScopeChild child : ((ListScope) usingScope).children) {
          tableScope.addChild(child.namespace, child.name, child.nullable);
        }
      }
      // 最终将计算好的 tableScope 赋值给 parentScope，作为本次调用后续使用的父作用域。
      parentScope = tableScope;
    } else {
      // 如果没有指定 LATERAL，直接使用原始传入的 parentScope0，不做任何特殊处理。
      parentScope = parentScope0;
    }

    SqlCall call;
    SqlNode operand;
    SqlNode newOperand;

    switch (kind) {
    // 带别名的表达式，如 t AS alias 或 t AS alias(col1, col2)
    case AS:
      call = (SqlCall) node;
      if (alias == null) {
        alias = String.valueOf(call.operand(1));
      }
      expr = call.operand(0);
      // 如果 AS 调用的操作数超过 2 个，说明存在列名列表，即 AS alias(col1, col2, ...) 这种形式（不仅重命名了表，还重命名了列），此时需要引入专门的命名空间来做列名的映射转换。
      // 如果被起别名的表达式本身是 VALUES、UNNEST 或 COLLECTION_TABLE（表函数）这几种特殊类型，也同样需要引入别名命名空间（这些类型的处理有特殊性，用于列名转换）。
      final boolean needAliasNamespace = call.operandCount() > 2
          || expr.getKind() == SqlKind.VALUES || expr.getKind() == SqlKind.UNNEST
          || expr.getKind() == SqlKind.COLLECTION_TABLE;
      newExpr =
          registerFrom(
              parentScope,
              usingScope,
              // !needAliasNamespace——如果需要额外的别名命名空间，那么这一层递归调用暂时不注册（false），
              // 因为注册工作会在后面由专门创建的 AliasNamespace 来完成；反之如果不需要额外命名空间，则直接注册（true）
              !needAliasNamespace,
              expr,
              enclosingNode,
              alias,
              extendList,
              forceNullable,
              lateral);
      if (newExpr != expr) {
        call.setOperand(0, newExpr);
      }

      // If alias has a column list, introduce a namespace to translate
      // column names. We skipped registering it just now.
      // 如果确实需要别名命名空间（存在列名列表或特殊类型），创建一个 AliasNamespace（专门处理列名映射的命名空间类型），
      // 并调用 registerNamespace 将其注册到 usingScope 下，使用之前确定的 alias。
      if (needAliasNamespace) {
        registerNamespace(
            usingScope,
            alias,
            new AliasNamespace(this, call, enclosingNode),
            forceNullable);
      }
      return node;

    case MATCH_RECOGNIZE:
      registerMatchRecognize(parentScope, usingScope,
          (SqlMatchRecognize) node, enclosingNode, alias, forceNullable);
      return node;

    case PIVOT:
      registerPivot(parentScope, usingScope, (SqlPivot) node, enclosingNode,
          alias, forceNullable);
      return node;

    case UNPIVOT:
      registerUnpivot(parentScope, usingScope, (SqlUnpivot) node, enclosingNode,
          alias, forceNullable);
      return node;

    case TABLESAMPLE:
      call = (SqlCall) node;
      expr = call.operand(0);
      newExpr =
          registerFrom(
              parentScope,
              usingScope,
              true,
              expr,
              enclosingNode,
              alias,
              extendList,
              forceNullable,
              lateral);
      if (newExpr != expr) {
        call.setOperand(0, newExpr);
      }
      return node;

    case JOIN:
      final SqlJoin join = (SqlJoin) node;
      // 为该 JOIN 创建一个专门的 JoinScope（用于表示 JOIN 两侧表的联合可见性作用域）。
      final JoinScope joinScope =
          new JoinScope(parentScope, usingScope, join);
      scopes.put(join, joinScope);
      final SqlNode left = join.getLeft();
      final SqlNode right = join.getRight();
      boolean forceLeftNullable = forceNullable;
      boolean forceRightNullable = forceNullable;
      // 根据 JOIN 类型确定是否需要强制可空：
      switch (join.getJoinType()) {
      case LEFT:
        forceRightNullable = true;
        break;
      case RIGHT:
        forceLeftNullable = true;
        break;
      case FULL:
        forceLeftNullable = true;
        forceRightNullable = true;
        break;
      default:
        break;
      }
      // 以 joinScope 作为 usingScope（意味着左侧表会被注册为该 JOIN 作用域的子项），别名和扩展列传 null（因为 JOIN 的左右子项本身可能是任意 FROM 表达式，其内部会自行处理别名等细节）。
      // 如果返回的新节点与原节点不同，调用 join.setLeft(newLeft) 更新。
      final SqlNode newLeft =
          registerFrom(
              parentScope,
              joinScope,
              true,
              left,
              left,
              null,
              null,
              forceLeftNullable,
              lateral);
      if (newLeft != left) {
        join.setLeft(newLeft);
      }
      final SqlNode newRight =
          registerFrom(
              parentScope,
              joinScope,
              true,
              right,
              right,
              null,
              null,
              forceRightNullable,
              lateral);
      if (newRight != right) {
        join.setRight(newRight);
      }
      scopes.putIfAbsent(stripAs(join.getRight()), parentScope);
      scopes.putIfAbsent(stripAs(join.getLeft()), parentScope);
      // 对 JOIN 的连接条件（ON 子句，比如 a.id = b.id）调用之前详细讲解过的 registerSubQueries 方法，
      // 检查条件表达式中是否包含子查询（比如 ON a.id = (SELECT MAX(id) FROM b) 这种少见但合法的写法），并注册它们，
      // 使用 joinScope 作为父作用域（因为条件表达式中可以同时引用左右两个表）。
      registerSubQueries(joinScope, join.getCondition());
      final JoinNamespace joinNamespace = new JoinNamespace(this, join);
      // 为整个 JOIN 创建一个 JoinNamespace（代表 JOIN 结果作为一个整体的命名空间），
      // 并注册（这里传入的父作用域和别名都是 null，因为 JOIN 结果通常不需要单独的别名，它是通过左右子项各自的可见性来体现的）。
      registerNamespace(null, null, joinNamespace, forceNullable);
      return join;

    // 简单表标识符引用
    case IDENTIFIER:
      final SqlIdentifier id = (SqlIdentifier) node;
      // 创建一个 IdentifierNamespace（代表一个具名表引用的命名空间），传入校验器自身、标识符、扩展列定义、外层节点、父作用域。
      final IdentifierNamespace newNs =
          new IdentifierNamespace(
              this, id, extendList, enclosingNode,
              parentScope);
      // 注册这个新命名空间。这里通过三元表达式判断——只有当外部传入的 register 为 true 时，才真正把它注册到 usingScope 下；
      // 否则传 null（意味着这次调用不希望立即把该命名空间挂到某个可见的作用域下，可能后续会由外层逻辑另行处理）。
      registerNamespace(register ? usingScope : null, alias, newNs,
          forceNullable);
      if (tableScope == null) {
        tableScope = new TableScope(parentScope, node);
      }
      tableScope.addChild(newNs, requireNonNull(alias, "alias"), forceNullable);
      if (extendList != null && extendList.size() != 0) {
        return enclosingNode;
      }
      return newNode;

    case LATERAL:
      return registerFrom(
          parentScope,
          usingScope,
          register,
          ((SqlCall) node).operand(0),
          enclosingNode,
          alias,
          extendList,
          forceNullable,
          true);

    case COLLECTION_TABLE:
      call = (SqlCall) node;
      operand = call.operand(0);
      newOperand =
          registerFrom(
              parentScope,
              usingScope,
              register,
              operand,
              enclosingNode,
              alias,
              extendList,
              forceNullable, lateral);
      if (newOperand != operand) {
        call.setOperand(0, newOperand);
      }
      // If the operator is SqlWindowTableFunction, restricts the scope as
      // its first operand's (the table) scope.
      if (operand instanceof SqlBasicCall) {
        final SqlBasicCall call1 = (SqlBasicCall) operand;
        final SqlOperator op = call1.getOperator();
        if (op instanceof SqlWindowTableFunction
            && call1.operand(0).getKind() == SqlKind.SELECT) {
          scopes.put(node, getSelectScope(call1.operand(0)));
          return newNode;
        }
      }
      // Put the usingScope which can be a JoinScope
      // or a SelectScope, in order to see the left items
      // of the JOIN tree.
      scopes.put(node, usingScope);
      return newNode;

    case UNNEST:
      if (!lateral) {
        return registerFrom(parentScope, usingScope, register, node,
            enclosingNode, alias, extendList, forceNullable, true);
      }
    // fall through
    case SELECT:
    case UNION:
    case INTERSECT:
    case EXCEPT:
    case VALUES:
    case WITH:
    case OTHER_FUNCTION:
      if (alias == null) {
        alias = SqlValidatorUtil.alias(node, nextGeneratedId++);
      }
      registerQuery(
          parentScope,
          register ? usingScope : null,
          node,
          enclosingNode,
          alias,
          forceNullable);
      return newNode;

    case OVER:
      if (!shouldAllowOverRelation()) {
        throw Util.unexpected(kind);
      }
      call = (SqlCall) node;
      final OverScope overScope = new OverScope(usingScope, call);
      scopes.put(call, overScope);
      operand = call.operand(0);
      newOperand =
          registerFrom(
              parentScope,
              overScope,
              true,
              operand,
              enclosingNode,
              alias,
              extendList,
              forceNullable,
              lateral);
      if (newOperand != operand) {
        call.setOperand(0, newOperand);
      }

      for (ScopeChild child : overScope.children) {
        registerNamespace(register ? usingScope : null, child.name,
            child.namespace, forceNullable);
      }

      return newNode;

    case TABLE_REF:
      call = (SqlCall) node;
      registerFrom(parentScope,
          usingScope,
          register,
          call.operand(0),
          enclosingNode,
          alias,
          extendList,
          forceNullable,
          lateral);
      if (extendList != null && extendList.size() != 0) {
        return enclosingNode;
      }
      return newNode;

    case EXTEND:
      final SqlCall extend = (SqlCall) node;
      return registerFrom(parentScope,
          usingScope,
          true,
          extend.getOperandList().get(0),
          extend,
          alias,
          (SqlNodeList) extend.getOperandList().get(1),
          forceNullable,
          lateral);

    case SNAPSHOT:
      call = (SqlCall) node;
      operand = call.operand(0);
      newOperand =
          registerFrom(parentScope,
              usingScope,
              register,
              operand,
              enclosingNode,
              alias,
              extendList,
              forceNullable,
              lateral);
      if (newOperand != operand) {
        call.setOperand(0, newOperand);
      }
      // Put the usingScope which can be a JoinScope
      // or a SelectScope, in order to see the left items
      // of the JOIN tree.
      scopes.put(node, usingScope);
      return newNode;

    default:
      throw Util.unexpected(kind);
    }
  }

  protected boolean shouldAllowOverRelation() {
    return false;
  }

  /**
   * Creates a namespace for a <code>SELECT</code> node. Derived class may
   * override this factory method.
   *
   * @param select        Select node
   * @param enclosingNode Enclosing node
   * @return Select namespace
   */
  // 为一个 SELECT 节点创建对应的 SelectNamespace 实例。
  // SqlNode enclosingNode 是所有 SQL 语法树节点的公共父类型（比标准 SqlSelect 更泛化）
  // 表示"包裹"这个 SELECT 语句的外层节点。在实际的 SQL 语法树中，一个 SELECT 语句常常不是孤立存在的，而是被其他结构包裹，例如：
  // 被括号包裹的子查询：(SELECT ...)
  // 带别名的子查询：(SELECT ...) AS t
  // 作为 WITH ... AS (SELECT ...) 中的一部分
  // 作为 UNION、INSERT 等语句的一部分
  protected SelectNamespace createSelectNamespace(
      SqlSelect select,
      SqlNode enclosingNode) {
    return new SelectNamespace(this, select, enclosingNode);
  }

  /**
   * Creates a namespace for a set operation (<code>UNION</code>, <code>
   * INTERSECT</code>, or <code>EXCEPT</code>). Derived class may override
   * this factory method.
   *
   * @param call          Call to set operation
   * @param enclosingNode Enclosing node
   * @return Set operation namespace
   */
  protected SetopNamespace createSetopNamespace(
      SqlCall call,
      SqlNode enclosingNode) {
    return new SetopNamespace(this, call, enclosingNode);
  }

  /**
   * Registers a query in a parent scope.
   *
   * @param parentScope Parent scope which this scope turns to in order to
   *                    resolve objects
   * @param usingScope  Scope whose child list this scope should add itself to
   * @param node        Query node
   * @param alias       Name of this query within its parent. Must be specified
   *                    if usingScope != null
   */
  protected void registerQuery(
      SqlValidatorScope parentScope,
      @Nullable SqlValidatorScope usingScope,
      SqlNode node,
      SqlNode enclosingNode,
      @Nullable String alias,
      boolean forceNullable) {
    checkArgument(usingScope == null || alias != null);
    registerQuery(
        parentScope,
        usingScope,
        node,
        enclosingNode,
        alias,
        forceNullable,
        true);
  }

  /**
   * Registers a query in a parent scope.
   *
   * @param parentScope Parent scope which this scope turns to in order to
   *                    resolve objects
   * @param usingScope  Scope whose child list this scope should add itself to
   * @param node        Query node
   * @param alias       Name of this query within its parent. Must be specified
   *                    if usingScope != null
   * @param checkUpdate if true, validate that the update feature is supported
   *                    if validating the update statement
   */
  // 根据传入的查询树节点（SqlNode）类型，为其创建并注册专属的命名空间（SqlValidatorNamespace），同时划分和串联起各类 SQL 子句的作用域（SqlValidatorScope），并深度遍历其内部的所有子查询、操作数进行级联注册。
  // 是构建 SQL 校验期“可见性上下文网络”和“元数据缓存”的总调度中心。
  private void registerQuery(
      // 当前查询节点的父级/上级查找作用域。
      // 当该查询试图去解析、寻找某个标识符（表、列）时，如果自身作用域找不到，就会求助于这个 parentScope。这对于关联子查询向上寻找外层表的列至关重要。
      SqlValidatorScope parentScope,
      // 当前查询节点应该被挂载到的目标作用域。
      // 若不为空，当前节点注册好的 Namespace 会作为 child 塞进 usingScope 中。例如，FROM (SELECT * FROM t) AS a，
      // 这个子查询在注册完 Namespace 后会塞进外层的 SelectScope（即这里的 usingScope）中，使别名 "a" 变得可见。
      @Nullable SqlValidatorScope usingScope,
      // 当前正在被注册的 SQL 语法树节点（如 SqlSelect, SqlUpdate, SqlMerge 等）。
      SqlNode node,
      // 包裹/包含当前节点的上层父节点。用于某些特定命名空间在计算行类型时提供上下文引用。
      SqlNode enclosingNode,
      // 该查询在 usingScope 中对应的别名（如派生表的别名）。
      @Nullable String alias,
      // 是否强行将该查询产生的命名空间的行类型标记为可空（通常用于外连接场景）。
      boolean forceNullable,
      // 如果正在校验 UPDATE 语句，是否去校验当前方言/配置支持更新特性。
      boolean checkUpdate) {
    requireNonNull(node, "node");
    requireNonNull(enclosingNode, "enclosingNode");
    checkArgument(usingScope == null || alias != null);

    SqlCall call;
    List<SqlNode> operands;
    switch (node.getKind()) {
    // 1. 核心分支：SqlKind.SELECT（普通的 SELECT 查询语句）
    case SELECT:
      final SqlSelect select = (SqlSelect) node;
      // 1.1 为当前 SELECT 创建专属的 SelectNamespace
      final SelectNamespace selectNs =
          createSelectNamespace(select, enclosingNode);
      // 1.2 将该 Namespace 登记到全局，并挂载到 usingScope 中（如果 usingScope 不为空）
      registerNamespace(usingScope, alias, selectNs, forceNullable);
      // 1.3 确定窗口函数的父作用域，并基于 parentScope 实例创建一个全新的 SelectScope
      // 为什么窗口函数需要一个单独的父作用域？
      // 在 SQL 规范中，窗口函数（如 ROW_NUMBER() OVER (...) 或 SUM(sal) OVER (PARTITION BY ...)）通常出现在两个地方：
      // SELECT 投影列表（Select List）
      // ORDER BY 子句
      // 但是，窗口函数有一个至关重要的语义限制：窗口函数不能出现在 FROM 和 WHERE 子句中。
      // 因为执行顺序上，WHERE 过滤发生在窗口计算之前。为了校验“窗口函数里的表达式是否合法”，Calcite 必须知道：在这个窗口被解析时，它到底能“看见”外层的哪些变量？
      // 普通情况（无嵌套）：窗口函数只能看到 FROM 子句引入的列。
      // 嵌套情况（子查询）：如果当前 SELECT 嵌套在另一个 FROM 子句的派生表中，窗口函数不仅能看到当前 SELECT 的 FROM 列，还能看到外层主查询（Parent）里的列（即关联子查询的变量）。
      // 因此，windowParentScope 就是用来精准定义“在这个 SELECT 块中，窗口函数向外查找变量时的起点边界”。
      final SqlValidatorScope windowParentScope =
          first(usingScope, parentScope);
      SelectScope selectScope =
          new SelectScope(parentScope, windowParentScope, select);
      scopes.put(select, selectScope); // 将映射关系存入 scopes 缓存

      // Start by registering the WHERE clause
      // 1.4 开始注册 WHERE 子句的作用域，并递归注册 WHERE 子句里可能藏着的子查询
      clauseScopes.put(IdPair.of(select, Clause.WHERE), selectScope);
      registerOperandSubQueries(
          selectScope,
          select,
          SqlSelect.WHERE_OPERAND);

      // Register subqueries in the QUALIFY clause
      // 1.5 注册 QUALIFY 子句（用于过滤窗口函数结果）中嵌套的子查询
      // 在校验阶段，QUALIFY 子句和 WHERE 子句在名字解析上所拥有的“视野”是完全一样的：
      // 它们都需要看到 FROM 子句引入的所有基表字段。
      // 它们都能引用外层查询的关联变量。
      // 虽然在书写顺序上 QUALIFY 靠后，但在 SQL 的标准逻辑执行流水线中，它的位置非常靠前。其标准的执行顺序为：
      //FROM（定位数据源）
      //WHERE（初步过滤原始行）
      //GROUP BY（分组）
      //HAVING（聚合后过滤）
      //WINDOW（执行窗口函数计算）
      //QUALIFY（对窗口函数结果进行行过滤）
      //SELECT（投影）
      //DISTINCT（去重）
      //ORDER BY / LIMIT（排序展现）
      registerOperandSubQueries(
          selectScope,
          select,
          SqlSelect.QUALIFY_OPERAND);

      // Register FROM with the inherited scope 'parentScope', not
      // 'selectScope', otherwise tables in the FROM clause would be
      // able to see each other.
      // 1.6 注册 FROM 子句
      // 特别注意注释：传入的是继承来的 'parentScope' 而非刚刚创建的 'selectScope'。
      // 理由：防止 FROM 子句里的多张表在未定义连接时能够直接互相看到（遵循 SQL 语义隔离）
      final SqlNode from = select.getFrom();
      if (from != null) {
        final SqlNode newFrom =
            registerFrom(
                parentScope,
                selectScope,
                true,
                from,
                from,
                null,
                null,
                false,
                false);
        if (newFrom != from) {
          select.setFrom(newFrom); // 如果 FROM 节点在注册时被重写或包装，将其回填
        }
      }

      // If this is an aggregate query, the SELECT list and HAVING
      // clause use a different scope, where you can only reference
      // columns which are in the GROUP BY clause.
      // 1.7 划分聚合（Aggregate）作用域
      SqlValidatorScope aggScope = selectScope;
      if (isAggregate(select)) {
        // 如果是聚合查询（使用了 SUM/COUNT 或有 GROUP BY），SELECT 列表和 HAVING 能够看到的列会受到严格限制
        // （只能看到分组列或聚合函数），因此需要包裹一层特殊的 AggregatingSelectScope
        aggScope =
            new AggregatingSelectScope(selectScope, select, false);
        clauseScopes.put(IdPair.of(select, Clause.SELECT), aggScope);
      } else {
        clauseScopes.put(IdPair.of(select, Clause.SELECT), selectScope);
      }
      // 1.8 处理 GROUP BY 子句
      if (select.getGroup() != null) {
        GroupByScope groupByScope =
            new GroupByScope(selectScope, select.getGroup(), select);
        clauseScopes.put(IdPair.of(select, Clause.GROUP_BY), groupByScope);
        // 注册 GROUP BY 内部的子查询
        registerSubQueries(groupByScope, select.getGroup());
      }
      // 1.9 使用上面推导出的聚合作用域（aggScope）去注册 HAVING 和 SELECT 字段列表中的子查询
      registerOperandSubQueries(
          aggScope,
          select,
          SqlSelect.HAVING_OPERAND);
      registerSubQueries(aggScope, SqlNonNullableAccessors.getSelectList(select));
      // 1.10 处理 ORDER BY 子句
      final SqlNodeList orderList = select.getOrderList();
      if (orderList != null) {
        // If the query is 'SELECT DISTINCT', restrict the columns
        // available to the ORDER BY clause.
        // 如果是 SELECT DISTINCT，ORDER BY 允许访问的列会被进一步收窄，创建严格的 aggScope
        if (select.isDistinct()) {
          aggScope =
              new AggregatingSelectScope(selectScope, select, true);
        }
        OrderByScope orderScope =
            new OrderByScope(aggScope, orderList, select);
        clauseScopes.put(IdPair.of(select, Clause.ORDER), orderScope);
        registerSubQueries(orderScope, orderList);// 注册 ORDER BY 里的子查询
        // 语义合法性检查：如果不是聚合查询，那么 ORDER BY 里面绝对不允许出现聚合函数（如 ORDER BY SUM(a)）
        if (!isAggregate(select)) {
          // Since this is not an aggregate query,
          // there cannot be any aggregates in the ORDER BY clause.
          SqlNode agg = aggFinder.findAgg(orderList);
          if (agg != null) {
            throw newValidationError(agg, RESOURCE.aggregateIllegalInOrderBy());
          }
        }
      }
      break;

    case INTERSECT:
      validateFeature(RESOURCE.sQLFeature_F302(), node.getParserPosition());
      registerSetop(
          parentScope,
          usingScope,
          node,
          node,
          alias,
          forceNullable);
      break;

    case EXCEPT:
      validateFeature(RESOURCE.sQLFeature_E071_03(), node.getParserPosition());
      registerSetop(
          parentScope,
          usingScope,
          node,
          node,
          alias,
          forceNullable);
      break;

    case UNION:
      registerSetop(
          parentScope,
          usingScope,
          node,
          enclosingNode,
          alias,
          forceNullable);
      break;

    case LAMBDA:
      call = (SqlCall) node;
      SqlLambdaScope lambdaScope =
          new SqlLambdaScope(parentScope, (SqlLambda) call);
      scopes.put(call, lambdaScope);
      final LambdaNamespace lambdaNamespace =
          new LambdaNamespace(this, (SqlLambda) call, node);
      registerNamespace(
          usingScope,
          alias,
          lambdaNamespace,
          forceNullable);
      operands = call.getOperandList();
      for (int i = 0; i < operands.size(); i++) {
        registerOperandSubQueries(parentScope, call, i);
      }
      break;

    case WITH:
      registerWith(parentScope, usingScope, (SqlWith) node, enclosingNode,
          alias, forceNullable, checkUpdate);
      break;

    case VALUES:
      call = (SqlCall) node;
      scopes.put(call, parentScope);
      final TableConstructorNamespace tableConstructorNamespace =
          new TableConstructorNamespace(
              this,
              call,
              parentScope,
              enclosingNode);
      registerNamespace(
          usingScope,
          alias,
          tableConstructorNamespace,
          forceNullable);
      operands = call.getOperandList();
      for (int i = 0; i < operands.size(); ++i) {
        assert operands.get(i).getKind() == SqlKind.ROW;

        // FIXME jvs 9-Feb-2005:  Correlation should
        // be illegal in these sub-queries.  Same goes for
        // any non-lateral SELECT in the FROM list.
        registerOperandSubQueries(parentScope, call, i);
      }
      break;

    case INSERT:
      SqlInsert insertCall = (SqlInsert) node;
      InsertNamespace insertNs =
          new InsertNamespace(
              this,
              insertCall,
              enclosingNode,
              parentScope);
      registerNamespace(usingScope, null, insertNs, forceNullable);
      registerQuery(
          parentScope,
          usingScope,
          insertCall.getSource(),
          enclosingNode,
          null,
          false);
      break;

    case DELETE:
      SqlDelete deleteCall = (SqlDelete) node;
      DeleteNamespace deleteNs =
          new DeleteNamespace(
              this,
              deleteCall,
              enclosingNode,
              parentScope);
      registerNamespace(usingScope, null, deleteNs, forceNullable);
      registerQuery(
          parentScope,
          usingScope,
          SqlNonNullableAccessors.getSourceSelect(deleteCall),
          enclosingNode,
          null,
          false);
      break;

    case UPDATE:
      if (checkUpdate) {
        validateFeature(RESOURCE.sQLFeature_E101_03(),
            node.getParserPosition());
      }
      SqlUpdate updateCall = (SqlUpdate) node;
      UpdateNamespace updateNs =
          new UpdateNamespace(
              this,
              updateCall,
              enclosingNode,
              parentScope);
      registerNamespace(usingScope, null, updateNs, forceNullable);
      registerQuery(
          parentScope,
          usingScope,
          SqlNonNullableAccessors.getSourceSelect(updateCall),
          enclosingNode,
          null,
          false);
      break;

    case MERGE:
      validateFeature(RESOURCE.sQLFeature_F312(), node.getParserPosition());
      SqlMerge mergeCall = (SqlMerge) node;
      MergeNamespace mergeNs =
          new MergeNamespace(
              this,
              mergeCall,
              enclosingNode,
              parentScope);
      registerNamespace(usingScope, null, mergeNs, forceNullable);
      registerQuery(
          parentScope,
          usingScope,
          SqlNonNullableAccessors.getSourceSelect(mergeCall),
          enclosingNode,
          null,
          false);

      // update call can reference either the source table reference
      // or the target table, so set its parent scope to the merge's
      // source select; when validating the update, skip the feature
      // validation check
      SqlUpdate mergeUpdateCall = mergeCall.getUpdateCall();
      if (mergeUpdateCall != null) {
        registerQuery(
            getScope(SqlNonNullableAccessors.getSourceSelect(mergeCall), Clause.WHERE),
            null,
            mergeUpdateCall,
            enclosingNode,
            null,
            false,
            false);
      }
      SqlInsert mergeInsertCall = mergeCall.getInsertCall();
      if (mergeInsertCall != null) {
        registerQuery(
            parentScope,
            null,
            mergeInsertCall,
            enclosingNode,
            null,
            false);
      }
      break;

    case UNNEST:
      call = (SqlCall) node;
      final UnnestNamespace unnestNs =
          new UnnestNamespace(this, call, parentScope, enclosingNode);
      registerNamespace(
          usingScope,
          alias,
          unnestNs,
          forceNullable);
      registerOperandSubQueries(parentScope, call, 0);
      scopes.put(node, parentScope);
      break;
    case OTHER_FUNCTION:
      call = (SqlCall) node;
      ProcedureNamespace procNs =
          new ProcedureNamespace(
              this,
              parentScope,
              call,
              enclosingNode);
      registerNamespace(
          usingScope,
          alias,
          procNs,
          forceNullable);
      registerSubQueries(parentScope, call);
      break;

    case MULTISET_QUERY_CONSTRUCTOR:
    case MULTISET_VALUE_CONSTRUCTOR:
      validateFeature(RESOURCE.sQLFeature_S271(), node.getParserPosition());
      call = (SqlCall) node;
      CollectScope cs = new CollectScope(parentScope, usingScope, call);
      final CollectNamespace tableConstructorNs =
          new CollectNamespace(call, cs, enclosingNode);
      final String alias2 = SqlValidatorUtil.alias(node, nextGeneratedId++);
      registerNamespace(
          usingScope,
          alias2,
          tableConstructorNs,
          forceNullable);
      operands = call.getOperandList();
      for (int i = 0; i < operands.size(); i++) {
        registerOperandSubQueries(parentScope, call, i);
      }
      break;

    default:
      throw Util.unexpected(node.getKind());
    }
  }

  private void registerSetop(
      SqlValidatorScope parentScope,
      @Nullable SqlValidatorScope usingScope,
      SqlNode node,
      SqlNode enclosingNode,
      @Nullable String alias,
      boolean forceNullable) {
    SqlCall call = (SqlCall) node;
    final SetopNamespace setopNamespace =
        createSetopNamespace(call, enclosingNode);
    registerNamespace(usingScope, alias, setopNamespace, forceNullable);

    // A setop is in the same scope as its parent.
    scopes.put(call, parentScope);
    @NonNull SqlValidatorScope recursiveScope = parentScope;
    if (enclosingNode.getKind() == SqlKind.WITH_ITEM) {
      if (node.getKind() != SqlKind.UNION) {
        throw newValidationError(node, RESOURCE.recursiveWithMustHaveUnionSetOp());
      } else if (call.getOperandList().size() > 2) {
        throw newValidationError(node, RESOURCE.recursiveWithMustHaveTwoChildUnionSetOp());
      }
      final WithScope scope = (WithScope) scopes.get(enclosingNode);
      // recursive scope is only set for the recursive queries.
      recursiveScope = scope != null && scope.recursiveScope != null
          ? Objects.requireNonNull(scope.recursiveScope) : parentScope;
    }
    for (int i = 0; i < call.getOperandList().size(); i++) {
      SqlNode operand = call.getOperandList().get(i);
      @NonNull SqlValidatorScope scope = i == 0 ? parentScope : recursiveScope;
      registerQuery(
          scope,
          null,
          operand,
          operand,
          null,
          false);
    }
  }

  private void registerWith(
      SqlValidatorScope parentScope,
      @Nullable SqlValidatorScope usingScope,
      SqlWith with,
      SqlNode enclosingNode,
      @Nullable String alias,
      boolean forceNullable,
      boolean checkUpdate) {
    final WithNamespace withNamespace =
        new WithNamespace(this, with, enclosingNode);
    registerNamespace(usingScope, alias, withNamespace, forceNullable);
    scopes.put(with, parentScope);

    SqlValidatorScope scope = parentScope;
    for (SqlNode withItem_ : with.withList) {
      final SqlWithItem withItem = (SqlWithItem) withItem_;

      final boolean isRecursiveWith = withItem.recursive.booleanValue();
      final SqlValidatorScope withScope =
          new WithScope(scope, withItem,
              isRecursiveWith ? new WithRecursiveScope(scope, withItem) : null);
      scopes.put(withItem, withScope);

      registerQuery(scope, null, withItem.query,
          withItem.recursive.booleanValue() ? withItem : with, withItem.name.getSimple(),
          forceNullable);
      registerNamespace(null, alias,
          new WithItemNamespace(this, withItem, enclosingNode),
          false);
      scope = withScope;
    }
    registerQuery(scope, null, with.body, enclosingNode, alias, forceNullable,
        checkUpdate);
  }

  @Override public boolean isAggregate(SqlSelect select) {
    if (getAggregate(select) != null) {
      return true;
    }
    // Also when nested window aggregates are present
    for (SqlCall call : overFinder.findAll(SqlNonNullableAccessors.getSelectList(select))) {
      assert call.getKind() == SqlKind.OVER;
      if (isNestedAggregateWindow(call.operand(0))) {
        return true;
      }
      if (isOverAggregateWindow(call.operand(1))) {
        return true;
      }
    }
    return false;
  }

  protected boolean isNestedAggregateWindow(SqlNode node) {
    AggFinder nestedAggFinder =
        new AggFinder(opTab, false, false, false, aggFinder,
            catalogReader.nameMatcher());
    return nestedAggFinder.findAgg(node) != null;
  }

  protected boolean isOverAggregateWindow(SqlNode node) {
    return aggFinder.findAgg(node) != null;
  }

  /** Returns the parse tree node (GROUP BY, HAVING, or an aggregate function
   * call) that causes {@code select} to be an aggregate query, or null if it
   * is not an aggregate query.
   *
   * <p>The node is useful context for error messages,
   * but you cannot assume that the node is the only aggregate function. */
  protected @Nullable SqlNode getAggregate(SqlSelect select) {
    SqlNode node = select.getGroup();
    if (node != null) {
      return node;
    }
    node = select.getHaving();
    if (node != null) {
      return node;
    }
    return getAgg(select);
  }

  /** If there is at least one call to an aggregate function, returns the
   * first. */
  private @Nullable SqlNode getAgg(SqlSelect select) {
    final SelectScope selectScope = getRawSelectScope(select);
    if (selectScope != null) {
      final List<SqlNode> selectList = selectScope.getExpandedSelectList();
      if (selectList != null) {
        return aggFinder.findAgg(selectList);
      }
    }
    return aggFinder.findAgg(SqlNonNullableAccessors.getSelectList(select));
  }

  @Deprecated
  @Override public boolean isAggregate(SqlNode selectNode) {
    return aggFinder.findAgg(selectNode) != null;
  }

  private void validateNodeFeature(SqlNode node) {
    switch (node.getKind()) {
    case MULTISET_VALUE_CONSTRUCTOR:
      validateFeature(RESOURCE.sQLFeature_S271(), node.getParserPosition());
      break;
    default:
      break;
    }
  }

  // 递归地遍历一个 SQL 表达式节点（node）的内部结构，找出其中所有的子查询（sub-query），并为每一个发现的子查询调用 registerQuery 完成"注册"（建立命名空间和作用域）。
  private void registerSubQueries(
      // 表示当前正在处理的这部分 SQL 语法树所处的父作用域上下文。
      // 如果在遍历过程中发现了子查询，需要用这个 parentScope 作为其"父作用域"来注册该子查询（使子查询能够正确解析其内部标识符，包括引用外层表的相关子查询场景）。
      SqlValidatorScope parentScope,
      // 表示待处理的当前 SQL 节点，即需要检查其内部是否包含子查询的那个表达式/节点
      @Nullable SqlNode node) {
    // 如果传入的节点为 null（对应参数上 @Nullable 的标注），直接结束方法，不做任何处理
    if (node == null) {
      return;
    }
    // 节点本身就是一个"查询"或特殊构造器
    if (node.getKind().belongsTo(SqlKind.QUERY)
        || node.getKind() == SqlKind.LAMBDA
        || node.getKind() == SqlKind.MULTISET_QUERY_CONSTRUCTOR
        || node.getKind() == SqlKind.MULTISET_VALUE_CONSTRUCTOR) {
      registerQuery(parentScope, null, node, node, null, false);
    // 节点是一个函数/操作符调用
    } else if (node instanceof SqlCall) {
      validateNodeFeature(node);
      SqlCall call = (SqlCall) node;
      for (int i = 0; i < call.operandCount(); i++) {
        registerOperandSubQueries(parentScope, call, i);
      }
    // 节点是一个列表（SqlNodeList）
    } else if (node instanceof SqlNodeList) {
      SqlNodeList list = (SqlNodeList) node;
      for (int i = 0, count = list.size(); i < count; i++) {
        SqlNode listNode = list.get(i);
        if (listNode.getKind().belongsTo(SqlKind.QUERY)) {
          listNode =
              SqlStdOperatorTable.SCALAR_QUERY.createCall(
                  listNode.getParserPosition(),
                  listNode);
          list.set(i, listNode);
        }
        registerSubQueries(parentScope, listNode);
      }
    } else {
      // atomic node -- can be ignored
    }
  }

  /**
   * Registers any sub-queries inside a given call operand, and converts the
   * operand to a scalar sub-query if the operator requires it.
   *
   * @param parentScope    Parent scope
   * @param call           Call
   * @param operandOrdinal Ordinal of operand within call
   * @see SqlOperator#argumentMustBeScalar(int)
   */
  // 检查一个函数调用（SqlCall）中指定位置的操作数（operand），如果该操作数本身是一个子查询，则对其进行"子查询注册"处理；
  // 同时，如果该操作符要求该位置的参数必须是标量值（scalar），而当前操作数是一个查询（返回可能是多行多列的结果集），则需要将其包装为"标量子查询"（Scalar Sub-query）。
  // 子查询注册（Register Sub-query）：Calcite 校验器在校验整个 SQL 语句的过程中，需要为语句中出现的每一个子查询（无论出现在 FROM、WHERE、SELECT 列表还是函数参数中）建立对应的命名空间（Namespace）和作用域（Scope），
  // 以便后续能够正确解析该子查询内部的标识符、推导其行类型等。这个"建立命名空间/作用域"的过程就称为"注册"（register）。
  // 标量子查询（Scalar Sub-query）：普通子查询返回的是一个结果集（可能多行多列），但在某些上下文中（比如 WHERE sal > (SELECT AVG(sal) FROM emp)），
  // 子查询的结果必须是单行单列的标量值才能参与比较运算。这种"要求返回标量值的子查询"在 Calcite AST 中会被专门包装成一个 SCALAR_QUERY 类型的调用节点，用以标记"这是一个应当被当作标量值使用的子查询"，供后续代码生成、类型检查等阶段区别对待。
  private void registerOperandSubQueries(
      // 表示当前上下文的父作用域。当该操作数内部（如果是子查询）需要被注册时，需要知道它所处的作用域上下文是什么，以便正确建立作用域链（比如子查询内部可以引用哪些外层的表，即相关子查询的场景）。这个参数会被继续传递给内部调用的 registerSubQueries 方法。
      SqlValidatorScope parentScope,
      SqlCall call,
      // 示要处理的操作数在 call 的操作数列表中的序号（下标）
      int operandOrdinal) {
    SqlNode operand = call.operand(operandOrdinal);
    if (operand == null) {
      return;
    }
    // 核心判断与转换逻辑
    // 判断当前操作数节点的"种类"（SqlKind，是 Calcite 中标识 AST 节点类型的枚举，比如 SELECT、UNION、LITERAL、IDENTIFIER、PLUS 等）是否属于 SqlKind.QUERY 这一大类。
    // SqlKind.QUERY 是一个"分类集合"（在 SqlKind 中定义的一组相关种类的集合，通常包含 SELECT、UNION、INTERSECT、EXCEPT、VALUES、WITH 等所有"可以产生结果集的查询类型"）。
    // 获取当前调用 call 所使用的操作符（SqlOperator，比如 >、=、+ 等），调用其 argumentMustBeScalar(operandOrdinal) 方法，判断该操作符在指定的操作数位置（operandOrdinal）上，是否要求传入的参数必须是标量值。
    if (operand.getKind().belongsTo(SqlKind.QUERY)
        && call.getOperator().argumentMustBeScalar(operandOrdinal)) {
      operand =
          SqlStdOperatorTable.SCALAR_QUERY.createCall(
              operand.getParserPosition(),
              operand);
      call.setOperand(operandOrdinal, operand);
    }
    // 无论前面的 if 判断是否触发了标量子查询包装转换，最终都会调用 registerSubQueries 方法，对（可能已经被替换过的）operand 进行"子查询注册"处理。
    registerSubQueries(parentScope, operand);
  }

  @Override public void validateIdentifier(SqlIdentifier id, SqlValidatorScope scope) {
    final SqlQualified fqId = scope.fullyQualify(id);
    if (this.config.columnReferenceExpansion()) {
      // NOTE jvs 9-Apr-2007: this doesn't cover ORDER BY, which has its
      // own ideas about qualification.
      id.assignNamesFrom(fqId.identifier);
    } else {
      Util.discard(fqId);
    }
  }

  @Override public void validateLiteral(SqlLiteral literal) {
    switch (literal.getTypeName()) {
    case DECIMAL:
      // Decimal and long have the same precision (as 64-bit integers), so
      // the unscaled value of a decimal must fit into a long.

      // REVIEW jvs 4-Aug-2004:  This should probably be calling over to
      // the available calculator implementations to see what they
      // support.  For now use ESP instead.
      //
      // jhyde 2006/12/21: I think the limits should be baked into the
      // type system, not dependent on the calculator implementation.
      BigDecimal bd = literal.getValueAs(BigDecimal.class);
      BigInteger unscaled = bd.unscaledValue();
      long longValue = unscaled.longValue();
      if (!BigInteger.valueOf(longValue).equals(unscaled)) {
        // overflow
        throw newValidationError(literal,
            RESOURCE.numberLiteralOutOfRange(bd.toString()));
      }
      break;

    case DOUBLE:
    case FLOAT:
    case REAL:
      validateLiteralAsDouble(literal);
      break;

    case BINARY:
      final BitString bitString = literal.getValueAs(BitString.class);
      if ((bitString.getBitCount() % 8) != 0) {
        throw newValidationError(literal, RESOURCE.binaryLiteralOdd());
      }
      break;

    case DATE:
    case TIME:
    case TIMESTAMP:
      Calendar calendar = literal.getValueAs(Calendar.class);
      final int year = calendar.get(Calendar.YEAR);
      final int era = calendar.get(Calendar.ERA);
      if (year < 1 || era == GregorianCalendar.BC || year > 9999) {
        throw newValidationError(literal,
            RESOURCE.dateLiteralOutOfRange(literal.toString()));
      }
      break;

    case INTERVAL_YEAR:
    case INTERVAL_YEAR_MONTH:
    case INTERVAL_MONTH:
    case INTERVAL_DAY:
    case INTERVAL_DAY_HOUR:
    case INTERVAL_DAY_MINUTE:
    case INTERVAL_DAY_SECOND:
    case INTERVAL_HOUR:
    case INTERVAL_HOUR_MINUTE:
    case INTERVAL_HOUR_SECOND:
    case INTERVAL_MINUTE:
    case INTERVAL_MINUTE_SECOND:
    case INTERVAL_SECOND:
      if (literal instanceof SqlIntervalLiteral) {
        SqlIntervalLiteral.IntervalValue interval =
            literal.getValueAs(SqlIntervalLiteral.IntervalValue.class);
        SqlIntervalQualifier intervalQualifier =
            interval.getIntervalQualifier();

        // ensure qualifier is good before attempting to validate literal
        validateIntervalQualifier(intervalQualifier);
        String intervalStr = interval.getIntervalLiteral();
        // throws CalciteContextException if string is invalid
        int[] values =
            intervalQualifier.evaluateIntervalLiteral(intervalStr,
                literal.getParserPosition(), typeFactory.getTypeSystem());
        Util.discard(values);
      }
      break;
    default:
      // default is to do nothing
    }
  }

  private void validateLiteralAsDouble(SqlLiteral literal) {
    BigDecimal bd = literal.getValueAs(BigDecimal.class);
    double d = bd.doubleValue();
    if (Double.isInfinite(d) || Double.isNaN(d)) {
      // overflow
      throw newValidationError(literal,
          RESOURCE.numberLiteralOutOfRange(Util.toScientificNotation(bd)));
    }

    // REVIEW jvs 4-Aug-2004:  what about underflow?
  }

  @Override public void validateIntervalQualifier(SqlIntervalQualifier qualifier) {
    assert qualifier != null;
    boolean startPrecisionOutOfRange = false;
    boolean fractionalSecondPrecisionOutOfRange = false;
    final RelDataTypeSystem typeSystem = typeFactory.getTypeSystem();

    final int startPrecision = qualifier.getStartPrecision(typeSystem);
    final int fracPrecision =
        qualifier.getFractionalSecondPrecision(typeSystem);
    final int maxPrecision = typeSystem.getMaxPrecision(qualifier.typeName());
    final int minPrecision = qualifier.typeName().getMinPrecision();
    final int minScale = qualifier.typeName().getMinScale();
    final int maxScale = typeSystem.getMaxScale(qualifier.typeName());
    if (startPrecision < minPrecision || startPrecision > maxPrecision) {
      startPrecisionOutOfRange = true;
    } else {
      if (fracPrecision < minScale || fracPrecision > maxScale) {
        fractionalSecondPrecisionOutOfRange = true;
      }
    }

    if (startPrecisionOutOfRange) {
      throw newValidationError(qualifier,
          RESOURCE.intervalStartPrecisionOutOfRange(startPrecision,
              "INTERVAL " + qualifier));
    } else if (fractionalSecondPrecisionOutOfRange) {
      throw newValidationError(qualifier,
          RESOURCE.intervalFractionalSecondPrecisionOutOfRange(
              fracPrecision,
              "INTERVAL " + qualifier));
    }
  }

  @Override public TimeFrame validateTimeFrame(SqlIntervalQualifier qualifier) {
    if (qualifier.timeFrameName == null) {
      final TimeFrame timeFrame = timeFrameSet.get(qualifier.getUnit());
      return requireNonNull(timeFrame,
          () -> "time frame for " + qualifier.getUnit());
    }
    final @Nullable TimeFrame timeFrame =
        timeFrameSet.getOpt(qualifier.timeFrameName);
    if (timeFrame != null) {
      return timeFrame;
    }
    throw newValidationError(qualifier,
        RESOURCE.invalidTimeFrame(qualifier.timeFrameName));
  }

  /**
   * Validates the FROM clause of a query, or (recursively) a child node of
   * the FROM clause: AS, OVER, JOIN, VALUES, or sub-query.
   *
   * @param node          Node in FROM clause, typically a table or derived
   *                      table
   * @param targetRowType Desired row type of this expression, or
   *                      {@link #unknownType} if not fussy. Must not be null.
   * @param scope         Scope
   */
  // validateFrom 的主要作用是递归验证 FROM 子句及其子节点。
  // 在 SQL 解析树（AST）中，FROM 后面可能跟随各种复杂的结构。该方法的作用就像一个“交通调度员”，它根据节点的类型（SqlKind），将验证任务分发给专门的处理函数（如处理联接的、处理子查询的等）。
  // 最终目标是：
  // 确保 FROM 子句中引用的表、视图、函数等是存在的。
  // 为这些节点确立命名空间（Namespace），即弄清楚这些数据源到底能提供哪些列（RowType）。
  // 确保嵌套结构（如子查询）的内部语义也是正确的。
  protected void validateFrom(
      SqlNode node, // 当前正在验证的语法树节点。它代表 FROM 子句中的一部分，比如一个简单的表名、一个 JOIN 表达式、或者一个 VALUES 表达式。
      RelDataType targetRowType, // 期望的行类型。在某些上下文中（如 INSERT INTO table SELECT...），我们已经知道目标表的类型，这个参数会将这种“期望”向下传递，帮助推导动态参数，如果没有特定要求，通常传入 unknownType。
      SqlValidatorScope scope) { // 验证作用域。提供了一个查找标识符的上下文，包含当前环境下可见的表名、列名等信息
    requireNonNull(scope, "scope");
    requireNonNull(targetRowType, "targetRowType");
    switch (node.getKind()) {
    case AS:
    case TABLE_REF:
      // 处理别名（如 FROM emp AS e）或表引用
      // 它剥离外层的 AS 包装，取其第一个操作数（即真正的表名或子查询），然后递归调用自身进行验证。
      validateFrom(
          ((SqlCall) node).operand(0),
          targetRowType,
          scope);
      break;
    case VALUES:
      // **作用**：验证内联行集（如 `FROM (VALUES (1, 'a'), (2, 'b'))`）。
      // **逻辑**：跳转到专门的 `validateValues` 方法，检查行与行之间列数是否对齐，类型是否匹配。
      validateValues((SqlCall) node, targetRowType, scope);
      break;
    case JOIN:
      // **作用**：验证表联接（如 `LEFT JOIN`、`INNER JOIN`）。
      // **逻辑**：调用 `validateJoin`。这会进一步触发左侧数据源和右侧数据源的验证，并校验 `ON` 或 `USING` 条件。
      validateJoin((SqlJoin) node, scope);
      break;
    case OVER:
      // **作用**：处理窗口函数相关的语法节点。
      validateOver((SqlCall) node, scope);
      break;
    case UNNEST:
      // **作用**：验证将集合（Array/Multiset）展开为表的操作。
      validateUnnest((SqlCall) node, scope, targetRowType);
      break;
    case COLLECTION_TABLE:
      // **作用**：验证表函数（Table Functions）。
      validateTableFunction((SqlCall) node, scope, targetRowType);
      break;
    default:
      // **作用**：这是最常见的路径，处理普通的表名标识符（`SqlIdentifier`）或者括号里的子查询。
      // **逻辑**：进入通用的查询验证流程。
      validateQuery(node, scope, targetRowType);
      break;
    }

    // Validate the namespace representation of the node, just in case the
    // validation did not occur implicitly.
    // 这是最后一道防线。无论前面的 switch 命中了哪个分支，最后都会显式调用该 Namespace 的 validate 方法。
    // 如果是一个表，它会去数据库元数据里查找表结构。
    // 如果是一个子查询，它会触发子查询内部的完整验证。
    getNamespaceOrThrow(node, scope).validate(targetRowType);
  }
  // 专门用于处理 表函数（Table Functions） 的方法
  // 在 SQL 中，表函数通常出现在 FROM 子句中，并由 TABLE() 关键字包裹，例如 SELECT * FROM TABLE(MY_FUNC(arg1, arg2))。
  // 主要职责是验证表函数的调用是否符合语义规范，特别是针对 多态表函数（PTF, Polymorphic Table Functions） 的参数特性进行检查。
  // 参数语义校验：区分并验证“行语义（Row Semantics）”和“集合语义（Set Semantics）”的参数。
  // 约束检查：确保一个表函数最多只能有一个行语义的输入表，并防止不合法的分区（PARTITION BY）或排序（ORDER BY）出现在错误的参数位置。
  // 语法展开：剥离 TABLE() 包装器，对内部真实的函数调用进行验证。
  protected void validateTableFunction(SqlCall node, // 代表：外层的 TABLE(...) 调用。
      SqlValidatorScope scope,
      RelDataType targetRowType) {
    // Dig out real call; TABLE() wrapper is just syntactic.
    // TABLE() 只是语法上的包装，我们需要挖出内部真实的 Call
    SqlCall call = node.operand(0);
    if (call.getOperator() instanceof SqlTableFunction) {
      // 检查该操作符是否实现了 SqlTableFunction 接口。如果是，则开启针对表函数参数特性的循环检查
      SqlTableFunction tableFunction = (SqlTableFunction) call.getOperator();
      boolean visitedRowSemanticsTable = false;
      for (int idx = 0; idx < call.operandCount(); idx++) {
        TableCharacteristic tableCharacteristic = tableFunction.tableCharacteristic(idx);
        // // 如果输入表具有集合语义（SET），跳过进一步校验（因为它允许 PARTITION BY 等）
        if (tableCharacteristic != null) {
          // Skip validate if current input table has set semantics
          if (tableCharacteristic.semantics == TableCharacteristic.Semantics.SET) {
            continue;
          }
          // A table function at most has one input table with row semantics
          // SQL 标准规定：一个表函数最多只能有一个具有行语义（ROW）的输入表
          if (visitedRowSemanticsTable) {
            throw newValidationError(
                call,
                RESOURCE.multipleRowSemanticsTables(call.getOperator().getName()));
          }
          visitedRowSemanticsTable = true;
        }
        // If table function defines the parameter is not table parameter, or is an input table
        // parameter with row semantics, then it should not be with PARTITION BY OR ORDER BY.
        SqlNode currentNode = call.operand(idx);
        if (currentNode instanceof SqlCall) {
          SqlOperator op = ((SqlCall) currentNode).getOperator();
          // 处理命名参数赋值，如 FUNC(param => table_name)
          if (op == SqlStdOperatorTable.ARGUMENT_ASSIGNMENT) {
            // Dig out the underlying operand
            SqlNode realNode = ((SqlBasicCall) currentNode).operand(0);
            if (realNode instanceof SqlCall) {
              currentNode = realNode;
              op = ((SqlCall) realNode).getOperator();
            }
          }
          // 如果该位置不应该是集合语义表，但用户写了 SET_SEMANTICS_TABLE（即带了 PARTITION BY）
          if (op == SqlStdOperatorTable.SET_SEMANTICS_TABLE) {
            throwInvalidRowSemanticsTable(call, idx, (SqlCall) currentNode);
          }
        }
      }
    }
    // 在完成表函数特有的参数语义检查后，调用通用的 validateQuery 完成类型推导和整体结构验证。
    validateQuery(node, scope, targetRowType);
  }

  private void throwInvalidRowSemanticsTable(SqlCall call, int idx, SqlCall table) {
    SqlNodeList partitionList = table.operand(1);
    if (!partitionList.isEmpty()) {
      throw newValidationError(call,
          RESOURCE.invalidPartitionKeys(
              idx, call.getOperator().getName()));
    }
    SqlNodeList orderList = table.operand(2);
    if (!orderList.isEmpty()) {
      throw newValidationError(call,
          RESOURCE.invalidOrderBy(
              idx, call.getOperator().getName()));
    }
  }

  protected void validateOver(SqlCall call, SqlValidatorScope scope) {
    throw new AssertionError("OVER unexpected in this context");
  }
  // 处理 集合展开（Unnesting） 语义的方法。
  // 在 SQL 中，UNNEST 通常用于将一个集合类型（如 ARRAY 或 MULTISET）转换成多行数据，以便在 FROM 子句中像表一样进行查询。
  // 主要作用是预处理并验证 UNNEST 函数调用的内容。
  // 执行两个关键步骤：
  // 标识符展开：将 UNNEST(attr) 中的列名 attr 进行全限定化处理（例如转为 alias.attr），确保后续逻辑能正确找到数据源。
  // 委托验证：将处理后的节点转交给通用的查询验证逻辑（validateQuery），以推导其生成的行类型（Row Type）。
  protected void validateUnnest(SqlCall call, // 代表 UNNEST 的函数调用节点。通常形如 UNNEST(collection_expression)，其中操作数（Operand）是需要展开的集合表达式。
      SqlValidatorScope scope,
      RelDataType targetRowType) {
    for (int i = 0; i < call.operandCount(); i++) {
      // 1. 获取第 i 个操作数并执行展开
      SqlNode expandedItem = expand(call.operand(i), scope);
      // 2. 将展开后的新节点（带全限定名）存回 call 对象中
      call.setOperand(i, expandedItem);
    }
    // UNNEST 本质上产生了一个虚拟表。调用 validateQuery 会触发对 UNNEST 内部表达式类型的最终推导。
    validateQuery(call, scope, targetRowType);
  }

  private void checkRollUpInUsing(SqlIdentifier identifier,
      SqlNode leftOrRight, SqlValidatorScope scope) {
    SqlValidatorNamespace namespace = getNamespace(leftOrRight, scope);
    if (namespace != null) {
      SqlValidatorTable sqlValidatorTable = namespace.getTable();
      if (sqlValidatorTable != null) {
        Table table = sqlValidatorTable.table();
        String column = Util.last(identifier.names);

        if (table.isRolledUp(column)) {
          throw newValidationError(identifier,
              RESOURCE.rolledUpNotAllowed(column, "USING"));
        }
      }
    }
  }
  // validateJoin 的主要职责是确保 SQL 中的 JOIN 语句在语义上是正确的。它处理以下核心逻辑：
  // 递归验证数据源：验证 JOIN 左侧和右侧的表或子查询。
  // 验证联接条件：处理 ON 表达式、USING 子句以及 NATURAL 连接的合法性。
  // 约束检查：确保不同类型的 JOIN（如 CROSS vs INNER）是否正确地携带或省略了联接条件。
  // 公共列验证：针对 USING 和 NATURAL 连接，确保两边确实存在同名的列，且类型兼容。
  // SqlJoin join: 代表当前正在验证的 JOIN 节点，包含了左节点、右节点、联接类型（Inner, Left 等）以及联接条件。
  // SqlValidatorScope scope: 当前查询的外部作用域。
  protected void validateJoin(SqlJoin join, SqlValidatorScope scope) {
    // 初始化与数据源验证
    final SqlNode left = join.getLeft();
    final SqlNode right = join.getRight();
    final boolean natural = join.isNatural();
    final JoinType joinType = join.getJoinType();
    final JoinConditionType conditionType = join.getConditionType();
    // 获取专门为该 JOIN 节点创建的作用域，该作用域合并了左右两表的命名空间
    // 关键在于 joinScope，它允许在验证联接条件（如 ON t1.id = t2.id）时同时看到左右两张表的字段。
    final SqlValidatorScope joinScope = getScopeOrThrow(join); // getJoinScope?
    // 递归验证左表和右表
    validateFrom(left, unknownType, joinScope);
    validateFrom(right, unknownType, joinScope);

    // Validate condition.
    switch (conditionType) {
    case NONE:
      checkArgument(join.getCondition() == null);
      break;
    case ON:
      // // 展开表达式（处理标识符全限定化）
      final SqlNode condition = expand(getCondition(join), joinScope);
      // 更新 AST 节点
      join.setOperand(5, condition);
      // 验证布尔表达式合法性
      validateWhereOrOn(joinScope, condition, "ON");
      // 检查 OLAP 上卷限制
      checkRollUp(null, join, condition, joinScope, "ON");
      break;
    case USING:
      @SuppressWarnings({"rawtypes", "unchecked"}) List<SqlIdentifier> list =
          (List) getCondition(join);

      // Parser ensures that using clause is not empty.
      checkArgument(!list.isEmpty(), "Empty USING clause");
      // 验证 USING(id) 中的 id 在左右两边都存在且唯一
      for (SqlIdentifier id : list) {
        validateCommonJoinColumn(id, left, right, scope, natural);
      }
      break;
    default:
      throw Util.unexpected(conditionType);
    }

    // Validate NATURAL.
    // NATURAL JOIN 专项验证
    if (natural) {
      // NATURAL JOIN 不允许显式写 ON 或 USING
      if (join.getCondition() != null) {
        throw newValidationError(getCondition(join),
            RESOURCE.naturalDisallowsOnOrUsing());
      }

      // Join on fields that occur on each side.
      // Check compatibility of the chosen columns.
      // 自动推导两表中同名的列作为联接键
      // NATURAL 实际上是隐式的 USING。代码会找到左右两表的交集列名，并对每一列执行 validateCommonJoinColumn 验证。
      for (String name : deriveNaturalJoinColumnList(join)) {
        final SqlIdentifier id =
            new SqlIdentifier(name, join.isNaturalNode().getParserPosition());
        validateCommonJoinColumn(id, left, right, scope, natural);
      }
    }

    // Which join types require/allow a ON/USING condition, or allow
    // a NATURAL keyword?
    // 最后根据 JOIN 的物理类型检查语法约束：
    switch (joinType) {
    case LEFT_ANTI_JOIN:
    case LEFT_SEMI_JOIN:
    // Case SEMI, ANTI (半联接/反联接):
    // 检查当前的 SQL 兼容性配置（conformance）是否允许使用这类扩展的 Join 类型。
      if (!this.config.conformance().isLiberal()) {
        throw newValidationError(join.getJoinTypeNode(),
            RESOURCE.dialectDoesNotSupportFeature(joinType.name()));
      }
      // fall through
    case INNER:
    case LEFT:
    case RIGHT:
    case FULL:
      // 标准联接必须有 ON/USING 或 NATURAL 关键字
      if ((join.getCondition() == null) && !natural) {
        throw newValidationError(join, RESOURCE.joinRequiresCondition());
      }
      break;
    case COMMA:
    case CROSS:
      // 笛卡尔积（CROSS JOIN）不允许有联接条件或 NATURAL 关键字
      if (join.getCondition() != null) {
        throw newValidationError(join.getConditionTypeNode(),
            RESOURCE.crossJoinDisallowsCondition());
      }
      if (natural) {
        throw newValidationError(join.getConditionTypeNode(),
            RESOURCE.crossJoinDisallowsCondition());
      }
      break;
    default:
      throw Util.unexpected(joinType);
    }
  }

  /**
   * Throws an error if there is an aggregate or windowed aggregate in the
   * given clause.
   *
   * @param aggFinder Finder for the particular kind(s) of aggregate function
   * @param node      Parse tree
   * @param clause    Name of clause: "WHERE", "GROUP BY", "ON"
   */
  private void validateNoAggs(AggFinder aggFinder, SqlNode node,
      String clause) {
    final SqlCall agg = aggFinder.findAgg(node);
    if (agg == null) {
      return;
    }
    final SqlOperator op = agg.getOperator();
    if (op == SqlStdOperatorTable.OVER) {
      throw newValidationError(agg,
          RESOURCE.windowedAggregateIllegalInClause(clause));
    } else if (op.isGroup() || op.isGroupAuxiliary()) {
      throw newValidationError(agg,
          RESOURCE.groupFunctionMustAppearInGroupByClause(op.getName()));
    } else {
      throw newValidationError(agg,
          RESOURCE.aggregateIllegalInClause(clause));
    }
  }

  /** Validates a column in a USING clause, or an inferred join key in a NATURAL join. */
  private void validateCommonJoinColumn(SqlIdentifier id, SqlNode left,
      SqlNode right, SqlValidatorScope scope, boolean natural) {
    if (id.names.size() != 1) {
      throw newValidationError(id, RESOURCE.columnNotFound(id.toString()));
    }

    final RelDataType leftColType = natural
        ? checkAndDeriveDataType(id, left)
        : validateCommonInputJoinColumn(id, left, scope, natural);
    final RelDataType rightColType = validateCommonInputJoinColumn(id, right, scope, natural);
    if (!SqlTypeUtil.isComparable(leftColType, rightColType)) {
      throw newValidationError(id,
          RESOURCE.naturalOrUsingColumnNotCompatible(id.getSimple(),
              leftColType.toString(), rightColType.toString()));
    }
  }

  private RelDataType checkAndDeriveDataType(SqlIdentifier id, SqlNode node) {
    checkArgument(id.names.size() == 1);
    String name = id.names.get(0);
    SqlNameMatcher nameMatcher = getCatalogReader().nameMatcher();
    RelDataType rowType = getNamespaceOrThrow(node).getRowType();
    final RelDataTypeField field =
        requireNonNull(nameMatcher.field(rowType, name),
            () -> "unable to find left field " + name + " in " + rowType);
    return field.getType();
  }

  /** Validates a column in a USING clause, or an inferred join key in a
   * NATURAL join, in the left or right input to the join. */
  private RelDataType validateCommonInputJoinColumn(SqlIdentifier id,
      SqlNode leftOrRight, SqlValidatorScope scope, boolean natural) {
    checkArgument(id.names.size() == 1);
    final String name = id.names.get(0);
    final SqlValidatorNamespace namespace = getNamespaceOrThrow(leftOrRight);
    final RelDataType rowType = namespace.getRowType();
    final SqlNameMatcher nameMatcher = catalogReader.nameMatcher();
    final RelDataTypeField field = nameMatcher.field(rowType, name);
    if (field == null) {
      throw newValidationError(id, RESOURCE.columnNotFound(name));
    }
    Collection<RelDataType> rowTypes;
    if (!natural && rowType instanceof RelCrossType) {
      final RelCrossType crossType = (RelCrossType) rowType;
      rowTypes = new ArrayList<>(crossType.getTypes());
    } else {
      rowTypes = Collections.singleton(rowType);
    }
    for (RelDataType rowType0 : rowTypes) {
      if (nameMatcher.frequency(rowType0.getFieldNames(), name) > 1) {
        throw newValidationError(id, RESOURCE.columnInUsingNotUnique(name));
      }
    }
    checkRollUpInUsing(id, leftOrRight, scope);
    return field.getType();
  }

  /**
   * Validates a SELECT statement.
   *
   * @param select        Select statement
   * @param targetRowType Desired row type, must not be null, may be the data
   *                      type 'unknown'.
   */
  // 负责协调一个 SELECT 语句中各个子句（FROM, WHERE, GROUP BY, SELECT 等）的验证顺序，并确保它们之间的逻辑一致性。
  protected void validateSelect(
      SqlSelect select, // 代表当前正在验证的 SELECT 语法树节点。它包含了 SELECT 列表、FROM、WHERE、GROUP BY、HAVING 等所有子句的信息。
      RelDataType targetRowType) { // 目标行类型（期望的返回类型）
    requireNonNull(targetRowType, "targetRowType");

    // Namespace is either a select namespace or a wrapper around one.
    // 获取该 SELECT 语句对应的命名空间
    final SelectNamespace ns =
        getNamespaceOrThrow(select).unwrap(SelectNamespace.class);

    // Its rowtype is null, meaning it hasn't been validated yet.
    // This is important, because we need to take the targetRowType into
    // account.
    // 确保此时 rowType 为空，即尚未验证过
    // 获取当前 SELECT 语句的 Namespace。验证器的目标之一就是计算出这个 Namespace 的最终 rowType。
    assert ns.rowType == null;
    // 如果使用了 DISTINCT，检查当前配置是否支持该 SQL 特性。
    SqlNode distinctNode = select.getModifierNode(SqlSelectKeyword.DISTINCT);
    if (distinctNode != null) {
      validateFeature(RESOURCE.sQLFeature_E051_01(),
          distinctNode
              .getParserPosition());
    }
    // 核心目的是处理一种特殊的类型推导场景：当 INSERT ... VALUES 被改写为 SELECT * 时，如何将目标表的类型信息向下传递。
    // 从 SqlSelect 节点中提取 SELECT 子句后面的项列表（即投影列
    // 例子：对于 SELECT a, b，它获取 [a, b]；对于 SELECT *，它获取 [*]
    final SqlNodeList selectItems = SqlNonNullableAccessors.getSelectList(select);
    RelDataType fromType = unknownType;
    if (selectItems.size() == 1) {
      // 只有当 SELECT 列表里只有一个项时，才进入后续逻辑。
      final SqlNode selectItem = selectItems.get(0);
      if (selectItem instanceof SqlIdentifier) {
        SqlIdentifier id = (SqlIdentifier) selectItem;
        // 判断是否为 *。
        // id.names.size() 判断是否为非限定的。如果是 emp.*，其 names 大小为 2；如果是纯 *，其大小为 1。
        // 在 Calcite 内部，当你执行 INSERT INTO t(c1, c2) VALUES(?, ?) 时，系统在底层会将其展开/重写为类似 SELECT * FROM (VALUES(?, ?)) 的形式。
        // 为什么要这样做？
        // 在这种场景下，VALUES 里的动态参数 ? 本身是没有类型的。为了知道第一个 ? 应该是 INT 还是 VARCHAR，验证器必须参考 INSERT 目标表 t 的结构（即 targetRowType）。
        if (id.isStar() && (id.names.size() == 1)) {
          // Special case: for INSERT ... VALUES(?,?), the SQL
          // standard says we're supposed to propagate the target
          // types down.  So iff the select list is an unqualified
          // star (as it will be after an INSERT ... VALUES has been
          // expanded), then propagate.
          fromType = targetRowType;
        }
      }
    }

    // Make sure that items in FROM clause have distinct aliases.
    // 核心片段，主要负责 FROM 子句的合法性检查，分为两个阶段：别名唯一性校验 和 数据源结构验证。
    // 获取与该 SELECT 的 FROM 子句关联的作用域（Scope）
    final SelectScope fromScope = (SelectScope) getFromScope(select);
    // 提取该作用域下所有子节点（表、联接表、子查询）的别名或原始表名。
    List<@Nullable String> names = fromScope.getChildNames();
    // 如果当前的元数据读取器（CatalogReader）配置为不区分大小写，则将所有名字统一转换为大写。
    if (!catalogReader.nameMatcher().isCaseSensitive()) {
      //noinspection RedundantTypeArguments
      names = names.stream()
          .<@Nullable String>map(s -> s == null ? null : s.toUpperCase(Locale.ROOT))
          .collect(Collectors.toList());
    }
    // 返回列表中第一个重复元素的索引。如果没有重复，则返回 -1。
    final int duplicateAliasOrdinal = Util.firstDuplicate(names);
    if (duplicateAliasOrdinal >= 0) {
      final ScopeChild child =
          fromScope.children.get(duplicateAliasOrdinal);
      throw newValidationError(
          requireNonNull(
              child.namespace.getEnclosingNode(),
              () -> "enclosingNode of namespace of " + child.name),
          RESOURCE.fromAliasDuplicate(child.name));
    }
    // 检查 FROM 是否缺失
    final SqlNode from = select.getFrom();
    if (from == null) {
      // 根据 SQL 标准配置判断 FROM 是否必填
      // 例如：在 Oracle 中 SELECT 1 是非法的（必须加 FROM DUAL），但在 MySQL 或 PostgreSQL 中是允许的。如果配置要求必填而用户没写，则报错。
      if (this.config.conformance().isFromRequired()) {
        throw newValidationError(select, RESOURCE.selectMissingFrom());
      }
    } else {
      // 会深入到 FROM 子句内部。如果 FROM 是一个 SqlJoin，它会验证左右两表及连接条件；如果是一个子查询，它会开启新一轮的 SELECT 验证。
      // 参数传递：它将 fromType（可能是从 INSERT 目标表传下来的类型）传递下去，以便子查询或 VALUES 子句进行类型推导。
      validateFrom(from, fromType, fromScope);
    }

    validateWhereClause(select);
    validateGroupClause(select);
    validateHavingClause(select);
    validateWindowClause(select);
    validateQualifyClause(select);
    handleOffsetFetch(select.getOffset(), select.getFetch());

    // Validate the SELECT clause late, because a select item might
    // depend on the GROUP BY list, or the window function might reference
    // window name in the WINDOW clause etc.
    final RelDataType rowType =
        validateSelectList(selectItems, select, targetRowType);
    ns.setType(rowType);

    // Deduce which columns must be filtered.
    ns.mustFilterFields = ImmutableBitSet.of();
    if (from != null) {
      final Set<SqlQualified> qualifieds = new LinkedHashSet<>();
      for (ScopeChild child : fromScope.children) {
        final List<String> fieldNames =
            child.namespace.getRowType().getFieldNames();
        child.namespace.getMustFilterFields()
            .forEachInt(i ->
                qualifieds.add(
                    SqlQualified.create(fromScope, 1, child.namespace,
                        new SqlIdentifier(
                            ImmutableList.of(child.name, fieldNames.get(i)),
                            SqlParserPos.ZERO))));
      }
      if (!qualifieds.isEmpty()) {
        if (select.getWhere() != null) {
          forEachQualified(select.getWhere(), getWhereScope(select),
              qualifieds::remove);
        }
        if (select.getHaving() != null) {
          forEachQualified(select.getHaving(), getHavingScope(select),
              qualifieds::remove);
        }

        // Each of the must-filter fields identified must be returned as a
        // SELECT item, which is then flagged as must-filter.
        final BitSet mustFilterFields = new BitSet();
        final List<SqlNode> expandedSelectItems =
            requireNonNull(fromScope.getExpandedSelectList(),
                "expandedSelectList");
        forEach(expandedSelectItems, (selectItem, i) -> {
          selectItem = stripAs(selectItem);
          if (selectItem instanceof SqlIdentifier) {
            SqlQualified qualified =
                fromScope.fullyQualify((SqlIdentifier) selectItem);
            if (qualifieds.remove(qualified)) {
              // SELECT item #i referenced a must-filter column that was not
              // filtered in the WHERE or HAVING. It becomes a must-filter
              // column for our consumer.
              mustFilterFields.set(i);
            }
          }
        });

        // If there are must-filter fields that are not in the SELECT clause,
        // this is an error.
        if (!qualifieds.isEmpty()) {
          throw newValidationError(select,
              RESOURCE.mustFilterFieldsMissing(
                  qualifieds.stream()
                      .map(q -> q.suffix().get(0))
                      .collect(Collectors.toCollection(TreeSet::new))
                      .toString()));
        }
        ns.mustFilterFields = ImmutableBitSet.fromBitSet(mustFilterFields);
      }
    }

    // Validate ORDER BY after we have set ns.rowType because in some
    // dialects you can refer to columns of the select list, e.g.
    // "SELECT empno AS x FROM emp ORDER BY x"
    validateOrderList(select);

    if (shouldCheckForRollUp(from)) {
      checkRollUpInSelectList(select);
      checkRollUp(null, select, select.getWhere(), getWhereScope(select));
      checkRollUp(null, select, select.getHaving(), getHavingScope(select));
      checkRollUpInWindowDecl(select);
      checkRollUpInGroupBy(select);
      checkRollUpInOrderBy(select);
    }
  }

  /** For each identifier in an expression, resolves it to a qualified name
   * and calls the provided action. */
  private static void forEachQualified(SqlNode node, SqlValidatorScope scope,
      Consumer<SqlQualified> consumer) {
    node.accept(new SqlBasicVisitor<Void>() {
      @Override public Void visit(SqlIdentifier id) {
        final SqlQualified qualified = scope.fullyQualify(id);
        consumer.accept(qualified);
        return null;
      }
    });
  }

  private void checkRollUpInSelectList(SqlSelect select) {
    SqlValidatorScope scope = getSelectScope(select);
    for (SqlNode item : SqlNonNullableAccessors.getSelectList(select)) {
      checkRollUp(null, select, item, scope);
    }
  }

  private void checkRollUpInGroupBy(SqlSelect select) {
    SqlNodeList group = select.getGroup();
    if (group != null) {
      for (SqlNode node : group) {
        checkRollUp(null, select, node, getGroupScope(select), "GROUP BY");
      }
    }
  }

  private void checkRollUpInOrderBy(SqlSelect select) {
    SqlNodeList orderList = select.getOrderList();
    if (orderList != null) {
      for (SqlNode node : orderList) {
        checkRollUp(null, select, node, getOrderScope(select), "ORDER BY");
      }
    }
  }

  private void checkRollUpInWindow(@Nullable SqlWindow window, SqlValidatorScope scope) {
    if (window != null) {
      for (SqlNode node : window.getPartitionList()) {
        checkRollUp(null, window, node, scope, "PARTITION BY");
      }

      for (SqlNode node : window.getOrderList()) {
        checkRollUp(null, window, node, scope, "ORDER BY");
      }
    }
  }

  private void checkRollUpInWindowDecl(SqlSelect select) {
    for (SqlNode decl : select.getWindowList()) {
      checkRollUpInWindow((SqlWindow) decl, getSelectScope(select));
    }
  }

  /**
   * If the {@code node} is a DOT call, returns its first operand. Recurse, if
   * the first operand is another DOT call.
   *
   * <p>In other words, it converts {@code a DOT b DOT c} to {@code a}.
   *
   * @param node The node to strip DOT
   * @return the DOT's first operand
   */
  private static SqlNode stripDot(SqlNode node) {
    SqlNode res = node;
    while (res.getKind() == SqlKind.DOT) {
      res = requireNonNull(((SqlCall) res).operand(0), "operand");
    }
    return res;
  }

  private void checkRollUp(@Nullable SqlNode grandParent, @Nullable SqlNode parent,
      @Nullable SqlNode current, SqlValidatorScope scope, @Nullable String contextClause) {
    current = stripAs(current);
    if (current instanceof SqlCall && !(current instanceof SqlSelect)) {
      // Validate OVER separately
      checkRollUpInWindow(getWindowInOver(current), scope);
      current = stripOver(current);

      SqlNode stripDot = stripDot(current);
      if (stripDot != current) {
        // we stripped the field access. Recurse to this method, the DOT's operand
        // can be another SqlCall, or an SqlIdentifier.
        checkRollUp(grandParent, parent, stripDot, scope, contextClause);
      } else if (stripDot.getKind() == SqlKind.CONVERT
          || stripDot.getKind() == SqlKind.TRANSLATE) {
        // only need to check operand[0] for CONVERT or TRANSLATE
        SqlNode child = ((SqlCall) stripDot).getOperandList().get(0);
        checkRollUp(parent, current, child, scope, contextClause);
      } else if (stripDot.getKind() == SqlKind.LAMBDA) {
        // do not need to check lambda
      } else {
        List<? extends @Nullable SqlNode> children =
            ((SqlCall) stripDot).getOperandList();
        for (SqlNode child : children) {
          checkRollUp(parent, current, child, scope, contextClause);
        }
      }
    } else if (current instanceof SqlIdentifier) {
      SqlIdentifier id = (SqlIdentifier) current;
      if (!id.isStar() && isRolledUpColumn(id, scope)) {
        if (!isAggregation(requireNonNull(parent, "parent").getKind())
            || !isRolledUpColumnAllowedInAgg(id, scope, (SqlCall) parent, grandParent)) {
          String context = contextClause != null ? contextClause : parent.getKind().toString();
          throw newValidationError(id,
              RESOURCE.rolledUpNotAllowed(SqlValidatorUtil.alias(id, 0),
                  context));
        }
      }
    }
  }

  private void checkRollUp(@Nullable SqlNode grandParent, SqlNode parent,
      @Nullable SqlNode current, SqlValidatorScope scope) {
    checkRollUp(grandParent, parent, current, scope, null);
  }

  private static @Nullable SqlWindow getWindowInOver(SqlNode over) {
    if (over.getKind() == SqlKind.OVER) {
      SqlNode window = ((SqlCall) over).getOperandList().get(1);
      if (window instanceof SqlWindow) {
        return (SqlWindow) window;
      }
      // SqlIdentifier, gets validated elsewhere
      return null;
    }
    return null;
  }

  private static SqlNode stripOver(SqlNode node) {
    switch (node.getKind()) {
    case OVER:
      return ((SqlCall) node).getOperandList().get(0);
    default:
      return node;
    }
  }

  private @Nullable Pair<String, String> findTableColumnPair(SqlIdentifier identifier,
      SqlValidatorScope scope) {
    final SqlCall call = makeNullaryCall(identifier);
    if (call != null) {
      return null;
    }
    SqlQualified qualified = scope.fullyQualify(identifier);
    List<String> names = qualified.identifier.names;

    if (names.size() < 2) {
      return null;
    }

    return new Pair<>(names.get(names.size() - 2), Util.last(names));
  }

  // Returns true iff the given column is valid inside the given aggCall.
  private boolean isRolledUpColumnAllowedInAgg(SqlIdentifier identifier, SqlValidatorScope scope,
      SqlCall aggCall, @Nullable SqlNode parent) {
    Pair<String, String> pair = findTableColumnPair(identifier, scope);

    if (pair == null) {
      return true;
    }

    String columnName = pair.right;

    Table table = resolveTable(identifier, scope);
    if (table != null) {
      return table.rolledUpColumnValidInsideAgg(columnName, aggCall, parent,
          catalogReader.getConfig());
    }
    return true;
  }

  private static @Nullable Table resolveTable(SqlIdentifier identifier, SqlValidatorScope scope) {
    SqlQualified fullyQualified = scope.fullyQualify(identifier);
    assert fullyQualified.namespace != null : "namespace must not be null in " + fullyQualified;
    SqlValidatorTable sqlValidatorTable =
        fullyQualified.namespace.getTable();
    if (sqlValidatorTable != null) {
      return sqlValidatorTable.table();
    }
    return null;
  }


  // Returns true iff the given column is actually rolled up.
  private boolean isRolledUpColumn(SqlIdentifier identifier, SqlValidatorScope scope) {
    Pair<String, String> pair = findTableColumnPair(identifier, scope);

    if (pair == null) {
      return false;
    }

    String columnName = pair.right;

    Table table = resolveTable(identifier, scope);
    if (table != null) {
      return table.isRolledUp(columnName);
    }
    return false;
  }

  private static boolean shouldCheckForRollUp(@Nullable SqlNode from) {
    if (from != null) {
      SqlKind kind = stripAs(from).getKind();
      return kind != SqlKind.VALUES && kind != SqlKind.SELECT;
    }
    return false;
  }

  /** Validates that a query can deliver the modality it promises. Only called
   * on the top-most SELECT or set operator in the tree. */
  private void validateModality(SqlNode query) {
    final SqlModality modality = deduceModality(query);
    if (query instanceof SqlSelect) {
      final SqlSelect select = (SqlSelect) query;
      validateModality(select, modality, true);
    } else if (query.getKind() == SqlKind.VALUES) {
      switch (modality) {
      case STREAM:
        throw newValidationError(query, Static.RESOURCE.cannotStreamValues());
      default:
        break;
      }
    } else {
      assert query.isA(SqlKind.SET_QUERY);
      final SqlCall call = (SqlCall) query;
      for (SqlNode operand : call.getOperandList()) {
        if (deduceModality(operand) != modality) {
          throw newValidationError(operand,
              Static.RESOURCE.streamSetOpInconsistentInputs());
        }
        validateModality(operand);
      }
    }
  }

  /** Return the intended modality of a SELECT or set-op. */
  private static SqlModality deduceModality(SqlNode query) {
    if (query instanceof SqlSelect) {
      SqlSelect select = (SqlSelect) query;
      return select.getModifierNode(SqlSelectKeyword.STREAM) != null
          ? SqlModality.STREAM
          : SqlModality.RELATION;
    } else if (query.getKind() == SqlKind.VALUES) {
      return SqlModality.RELATION;
    } else {
      assert query.isA(SqlKind.SET_QUERY);
      final SqlCall call = (SqlCall) query;
      return deduceModality(call.getOperandList().get(0));
    }
  }

  @Override public boolean validateModality(SqlSelect select, SqlModality modality,
      boolean fail) {
    final SelectScope scope = getRawSelectScopeNonNull(select);

    switch (modality) {
    case STREAM:
      if (scope.children.size() == 1) {
        for (ScopeChild child : scope.children) {
          if (!child.namespace.supportsModality(modality)) {
            if (fail) {
              SqlNode node = SqlNonNullableAccessors.getNode(child);
              throw newValidationError(node,
                  Static.RESOURCE.cannotConvertToStream(child.name));
            } else {
              return false;
            }
          }
        }
      } else {
        int supportsModalityCount = 0;
        for (ScopeChild child : scope.children) {
          if (child.namespace.supportsModality(modality)) {
            ++supportsModalityCount;
          }
        }

        if (supportsModalityCount == 0) {
          if (fail) {
            String inputs = String.join(", ", scope.getChildNames());
            throw newValidationError(select,
                Static.RESOURCE.cannotStreamResultsForNonStreamingInputs(inputs));
          } else {
            return false;
          }
        }
      }
      break;
    default:
      for (ScopeChild child : scope.children) {
        if (!child.namespace.supportsModality(modality)) {
          if (fail) {
            SqlNode node = SqlNonNullableAccessors.getNode(child);
            throw newValidationError(node,
                Static.RESOURCE.cannotConvertToRelation(child.name));
          } else {
            return false;
          }
        }
      }
    }

    // Make sure that aggregation is possible.
    final SqlNode aggregateNode = getAggregate(select);
    if (aggregateNode != null) {
      switch (modality) {
      case STREAM:
        SqlNodeList groupList = select.getGroup();
        if (groupList == null
            || !SqlValidatorUtil.containsMonotonic(scope, groupList)) {
          if (fail) {
            throw newValidationError(aggregateNode,
                Static.RESOURCE.streamMustGroupByMonotonic());
          } else {
            return false;
          }
        }
        break;
      default:
        break;
      }
    }

    // Make sure that ORDER BY is possible.
    final SqlNodeList orderList  = select.getOrderList();
    if (orderList != null && orderList.size() > 0) {
      switch (modality) {
      case STREAM:
        if (!hasSortedPrefix(scope, orderList)) {
          if (fail) {
            throw newValidationError(orderList.get(0),
                Static.RESOURCE.streamMustOrderByMonotonic());
          } else {
            return false;
          }
        }
        break;
      default:
        break;
      }
    }
    return true;
  }

  /** Returns whether the prefix is sorted. */
  private static boolean hasSortedPrefix(SelectScope scope, SqlNodeList orderList) {
    return isSortCompatible(scope, orderList.get(0), false);
  }

  private static boolean isSortCompatible(SelectScope scope, SqlNode node,
      boolean descending) {
    switch (node.getKind()) {
    case DESCENDING:
      return isSortCompatible(scope, ((SqlCall) node).getOperandList().get(0),
          true);
    default:
      break;
    }
    final SqlMonotonicity monotonicity = scope.getMonotonicity(node);
    switch (monotonicity) {
    case INCREASING:
    case STRICTLY_INCREASING:
      return !descending;
    case DECREASING:
    case STRICTLY_DECREASING:
      return descending;
    default:
      return false;
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  protected void validateWindowClause(SqlSelect select) {
    final SqlNodeList windowList = select.getWindowList();
    if (windowList.isEmpty()) {
      return;
    }

    final SelectScope windowScope = (SelectScope) getFromScope(select);

    // 1. ensure window names are simple
    // 2. ensure they are unique within this scope
    for (SqlWindow window : (List<SqlWindow>) (List) windowList) {
      SqlIdentifier declName =
          requireNonNull(window.getDeclName(),
              () -> "window.getDeclName() for " + window);
      if (!declName.isSimple()) {
        throw newValidationError(declName, RESOURCE.windowNameMustBeSimple());
      }

      if (windowScope.existingWindowName(declName.toString())) {
        throw newValidationError(declName, RESOURCE.duplicateWindowName());
      } else {
        windowScope.addWindowName(declName.toString());
      }
    }

    // 7.10 rule 2
    // Check for pairs of windows which are equivalent.
    for (int i = 0; i < windowList.size(); i++) {
      SqlNode window1 = windowList.get(i);
      for (int j = i + 1; j < windowList.size(); j++) {
        SqlNode window2 = windowList.get(j);
        if (window1.equalsDeep(window2, Litmus.IGNORE)) {
          throw newValidationError(window2, RESOURCE.dupWindowSpec());
        }
      }
    }

    for (SqlWindow window : (List<SqlWindow>) (List) windowList) {
      final SqlNodeList expandedOrderList =
          (SqlNodeList) expand(window.getOrderList(), windowScope);
      window.setOrderList(expandedOrderList);
      expandedOrderList.validate(this, windowScope);

      final SqlNodeList expandedPartitionList =
          (SqlNodeList) expand(window.getPartitionList(), windowScope);
      window.setPartitionList(expandedPartitionList);
      expandedPartitionList.validate(this, windowScope);
    }

    // Hand off to validate window spec components
    windowList.validate(this, windowScope);
  }

  protected void validateQualifyClause(SqlSelect select) {
    SqlNode qualifyNode = select.getQualify();
    if (qualifyNode == null) {
      return;
    }

    SqlValidatorScope qualifyScope = getSelectScope(select);

    qualifyNode = extendedExpand(qualifyNode, qualifyScope, select, Clause.QUALIFY);
    select.setQualify(qualifyNode);

    inferUnknownTypes(
        booleanType,
        qualifyScope,
        qualifyNode);

    qualifyNode.validate(this, qualifyScope);

    final RelDataType type = deriveType(qualifyScope, qualifyNode);
    if (!SqlTypeUtil.inBooleanFamily(type)) {
      throw newValidationError(qualifyNode, RESOURCE.condMustBeBoolean("QUALIFY"));
    }

    boolean qualifyContainsWindowFunction = overFinder.findAgg(qualifyNode) != null;
    if (!qualifyContainsWindowFunction) {
      throw newValidationError(qualifyNode,
          RESOURCE.qualifyExpressionMustContainWindowFunction(qualifyNode.toString()));
    }
  }

  @Override public void validateWith(SqlWith with, SqlValidatorScope scope) {
    final SqlValidatorNamespace namespace = getNamespaceOrThrow(with);
    validateNamespace(namespace, unknownType);
  }

  @Override public void validateWithItem(SqlWithItem withItem) {
    SqlNodeList columnList = withItem.columnList;
    if (columnList != null) {
      final RelDataType rowType = getValidatedNodeType(withItem.query);
      final int fieldCount = rowType.getFieldCount();
      if (columnList.size() != fieldCount) {
        throw newValidationError(columnList,
            RESOURCE.columnCountMismatch());
      }
      SqlValidatorUtil.checkIdentifierListForDuplicates(
          columnList, validationErrorFunction);
    } else {
      // Luckily, field names have not been make unique yet.
      final List<String> fieldNames =
          getValidatedNodeType(withItem.query).getFieldNames();
      final int i = Util.firstDuplicate(fieldNames);
      if (i >= 0) {
        throw newValidationError(withItem.query,
            RESOURCE.duplicateColumnAndNoColumnList(fieldNames.get(i)));
      }
    }
  }

  @Override public void validateSequenceValue(SqlValidatorScope scope, SqlIdentifier id) {
    // Resolve identifier as a table.
    final SqlValidatorScope.ResolvedImpl resolved =
        new SqlValidatorScope.ResolvedImpl();
    scope.resolveTable(id.names, catalogReader.nameMatcher(),
        SqlValidatorScope.Path.EMPTY, resolved);
    if (resolved.count() != 1) {
      throw newValidationError(id, RESOURCE.tableNameNotFound(id.toString()));
    }
    // We've found a table. But is it a sequence?
    final SqlValidatorNamespace ns = resolved.only().namespace;
    if (ns instanceof TableNamespace) {
      final Table table = getTable(ns).table();
      switch (table.getJdbcTableType()) {
      case SEQUENCE:
      case TEMPORARY_SEQUENCE:
        return;
      default:
        break;
      }
    }
    throw newValidationError(id, RESOURCE.notASequence(id.toString()));
  }

  @Override public TypeCoercion getTypeCoercion() {
    assert config.typeCoercionEnabled();
    return this.typeCoercion;
  }

  @Override public Config config() {
    return this.config;
  }

  @Override public SqlValidator transform(UnaryOperator<Config> transform) {
    this.config = transform.apply(this.config);
    return this;
  }

  /**
   * Validates the ORDER BY clause of a SELECT statement.
   *
   * @param select Select statement
   */
  protected void validateOrderList(SqlSelect select) {
    // ORDER BY is validated in a scope where aliases in the SELECT clause
    // are visible. For example, "SELECT empno AS x FROM emp ORDER BY x"
    // is valid.
    SqlNodeList orderList = select.getOrderList();
    if (orderList == null) {
      return;
    }
    if (!shouldAllowIntermediateOrderBy()) {
      if (!cursorSet.contains(select)) {
        throw newValidationError(select, RESOURCE.invalidOrderByPos());
      }
    }
    final SqlValidatorScope orderScope = getOrderScope(select);
    requireNonNull(orderScope, "orderScope");

    List<SqlNode> expandList = new ArrayList<>();
    for (SqlNode orderItem : orderList) {
      SqlNode expandedOrderItem = expand(orderItem, orderScope);
      expandList.add(expandedOrderItem);
    }

    SqlNodeList expandedOrderList =
        new SqlNodeList(expandList, orderList.getParserPosition());
    select.setOrderBy(expandedOrderList);

    for (SqlNode orderItem : expandedOrderList) {
      validateOrderItem(select, orderItem);
    }
  }

  /**
   * Validates an item in the GROUP BY clause of a SELECT statement.
   *
   * @param select Select statement
   * @param groupByItem GROUP BY clause item
   */
  private void validateGroupByItem(SqlSelect select, SqlNode groupByItem) {
    final SqlValidatorScope groupByScope = getGroupScope(select);
    validateGroupByExpr(groupByItem, groupByScope);
    groupByScope.validateExpr(groupByItem);
  }

  private void validateGroupByExpr(SqlNode groupByItem,
      SqlValidatorScope groupByScope) {
    switch (groupByItem.getKind()) {
    case GROUP_BY_DISTINCT:
      SqlCall call = (SqlCall) groupByItem;
      for (SqlNode operand : call.getOperandList()) {
        validateGroupByExpr(operand, groupByScope);
      }
      break;
    case GROUPING_SETS:
    case ROLLUP:
    case CUBE:
      call = (SqlCall) groupByItem;
      for (SqlNode operand : call.getOperandList()) {
        validateExpr(operand, groupByScope);
      }
      break;
    default:
      validateExpr(groupByItem, groupByScope);
    }
  }

  /**
   * Validates an item in the ORDER BY clause of a SELECT statement.
   *
   * @param select Select statement
   * @param orderItem ORDER BY clause item
   */
  private void validateOrderItem(SqlSelect select, SqlNode orderItem) {
    switch (orderItem.getKind()) {
    case DESCENDING:
      validateFeature(RESOURCE.sQLConformance_OrderByDesc(),
          orderItem.getParserPosition());
      validateOrderItem(select,
          ((SqlCall) orderItem).operand(0));
      return;
    default:
      break;
    }

    final SqlValidatorScope orderScope = getOrderScope(select);
    validateExpr(orderItem, orderScope);
  }

  @Override public SqlNode expandOrderExpr(SqlSelect select, SqlNode orderExpr) {
    final SqlNode newSqlNode =
        new OrderExpressionExpander(select, orderExpr).go();
    if (newSqlNode != orderExpr) {
      final SqlValidatorScope scope = getOrderScope(select);
      inferUnknownTypes(unknownType, scope, newSqlNode);
      final RelDataType type = deriveType(scope, newSqlNode);
      setValidatedNodeType(newSqlNode, type);
    }
    return newSqlNode;
  }

  /**
   * Validates the GROUP BY clause of a SELECT statement. This method is
   * called even if no GROUP BY clause is present.
   */
  protected void validateGroupClause(SqlSelect select) {
    SqlNodeList groupList = select.getGroup();
    if (groupList == null) {
      return;
    }
    final String clause = "GROUP BY";
    validateNoAggs(aggOrOverFinder, groupList, clause);
    final SqlValidatorScope groupScope = getGroupScope(select);

    // expand the expression in group list.
    List<SqlNode> expandedList = new ArrayList<>();
    for (SqlNode groupItem : groupList) {
      SqlNode expandedItem =
          extendedExpand(groupItem, groupScope, select, Clause.GROUP_BY);
      expandedList.add(expandedItem);
    }
    groupList = new SqlNodeList(expandedList, groupList.getParserPosition());
    select.setGroupBy(groupList);
    inferUnknownTypes(unknownType, groupScope, groupList);
    for (SqlNode groupItem : expandedList) {
      validateGroupByItem(select, groupItem);
    }

    // Nodes in the GROUP BY clause are expressions except if they are calls
    // to the GROUPING SETS, ROLLUP or CUBE operators; this operators are not
    // expressions, because they do not have a type.
    for (SqlNode node : groupList) {
      switch (node.getKind()) {
      case GROUP_BY_DISTINCT:
      case GROUPING_SETS:
      case ROLLUP:
      case CUBE:
        node.validate(this, groupScope);
        break;
      default:
        node.validateExpr(this, groupScope);
      }
    }

    // Derive the type of each GROUP BY item. We don't need the type, but
    // it resolves functions, and that is necessary for deducing
    // monotonicity.
    final SqlValidatorScope selectScope = getSelectScope(select);
    AggregatingSelectScope aggregatingScope = null;
    if (selectScope instanceof AggregatingSelectScope) {
      aggregatingScope = (AggregatingSelectScope) selectScope;
    }
    for (SqlNode groupItem : groupList) {
      if (groupItem instanceof SqlNodeList
          && ((SqlNodeList) groupItem).size() == 0) {
        continue;
      }
      validateGroupItem(groupScope, aggregatingScope, groupItem);
    }

    SqlNode agg = aggFinder.findAgg(groupList);
    if (agg != null) {
      throw newValidationError(agg, RESOURCE.aggregateIllegalInClause(clause));
    }
  }

  private void validateGroupItem(SqlValidatorScope groupScope,
      @Nullable AggregatingSelectScope aggregatingScope,
      SqlNode groupItem) {
    switch (groupItem.getKind()) {
    case GROUP_BY_DISTINCT:
      for (SqlNode sqlNode : ((SqlCall) groupItem).getOperandList()) {
        validateGroupItem(groupScope, aggregatingScope, sqlNode);
      }
      break;
    case GROUPING_SETS:
    case ROLLUP:
    case CUBE:
      validateGroupingSets(groupScope, aggregatingScope, (SqlCall) groupItem);
      break;
    default:
      if (groupItem instanceof SqlNodeList) {
        break;
      }
      final RelDataType type = deriveType(groupScope, groupItem);
      setValidatedNodeType(groupItem, type);
    }
  }

  private void validateGroupingSets(SqlValidatorScope groupScope,
      @Nullable AggregatingSelectScope aggregatingScope, SqlCall groupItem) {
    for (SqlNode node : groupItem.getOperandList()) {
      validateGroupItem(groupScope, aggregatingScope, node);
    }
  }

  protected void validateWhereClause(SqlSelect select) {
    // validate WHERE clause
    final SqlNode where = select.getWhere();
    if (where == null) {
      return;
    }
    final SqlValidatorScope whereScope = getWhereScope(select);
    final SqlNode expandedWhere = expand(where, whereScope);
    select.setWhere(expandedWhere);
    validateWhereOrOn(whereScope, expandedWhere, "WHERE");
  }

  protected void validateWhereOrOn(
      SqlValidatorScope scope,
      SqlNode condition,
      String clause) {
    validateNoAggs(aggOrOverOrGroupFinder, condition, clause);
    inferUnknownTypes(
        booleanType,
        scope,
        condition);
    condition.validate(this, scope);

    final RelDataType type = deriveType(scope, condition);
    if (!isReturnBooleanType(type)) {
      throw newValidationError(condition, RESOURCE.condMustBeBoolean(clause));
    }
  }

  private static boolean isReturnBooleanType(RelDataType relDataType) {
    if (relDataType instanceof RelRecordType) {
      RelRecordType recordType = (RelRecordType) relDataType;
      checkState(recordType.getFieldList().size() == 1,
          "sub-query as condition must return only one column");
      RelDataTypeField recordField = recordType.getFieldList().get(0);
      return SqlTypeUtil.inBooleanFamily(recordField.getType());
    }
    return SqlTypeUtil.inBooleanFamily(relDataType);
  }

  protected void validateHavingClause(SqlSelect select) {
    // HAVING is validated in the scope after groups have been created.
    // For example, in "SELECT empno FROM emp WHERE empno = 10 GROUP BY
    // deptno HAVING empno = 10", the reference to 'empno' in the HAVING
    // clause is illegal.
    SqlNode having = select.getHaving();
    if (having == null) {
      return;
    }
    final AggregatingScope havingScope =
        (AggregatingScope) getSelectScope(select);
    if (config.conformance().isHavingAlias()) {
      SqlNode newExpr = extendedExpand(having, havingScope, select, Clause.HAVING);
      if (having != newExpr) {
        having = newExpr;
        select.setHaving(newExpr);
      }
    }
    havingScope.checkAggregateExpr(having, true);
    inferUnknownTypes(booleanType, havingScope, having);
    having.validate(this, havingScope);
    final RelDataType type = deriveType(havingScope, having);
    if (!SqlTypeUtil.inBooleanFamily(type)) {
      throw newValidationError(having, RESOURCE.havingMustBeBoolean());
    }
  }

  protected RelDataType validateSelectList(final SqlNodeList selectItems,
      SqlSelect select, RelDataType targetRowType) {
    // First pass, ensure that aliases are unique. "*" and "TABLE.*" items
    // are ignored.

    // Validate SELECT list. Expand terms of the form "*" or "TABLE.*".
    final SqlValidatorScope selectScope = getSelectScope(select);
    final List<SqlNode> expandedSelectItems = new ArrayList<>();
    final Set<String> aliases = new HashSet<>();
    final PairList<String, RelDataType> fieldList = PairList.of();

    for (SqlNode selectItem : selectItems) {
      if (selectItem instanceof SqlSelect) {
        handleScalarSubQuery(select, (SqlSelect) selectItem,
            expandedSelectItems, aliases, fieldList);
      } else {
        // Use the field list size to record the field index
        // because the select item may be a STAR(*), which could have been expanded.
        final int fieldIdx = fieldList.size();
        final RelDataType fieldType =
            targetRowType.isStruct()
                && targetRowType.getFieldCount() > fieldIdx
                ? targetRowType.getFieldList().get(fieldIdx).getType()
                : unknownType;
        expandSelectItem(selectItem, select, fieldType, expandedSelectItems,
            aliases, fieldList, false);
      }
    }

    // Create the new select list with expanded items.  Pass through
    // the original parser position so that any overall failures can
    // still reference the original input text.
    SqlNodeList newSelectList =
        new SqlNodeList(expandedSelectItems, selectItems.getParserPosition());
    if (config.identifierExpansion()) {
      select.setSelectList(newSelectList);
    }
    getRawSelectScopeNonNull(select).setExpandedSelectList(expandedSelectItems);

    // TODO: when SELECT appears as a value sub-query, should be using
    // something other than unknownType for targetRowType
    inferUnknownTypes(targetRowType, selectScope, newSelectList);

    for (SqlNode selectItem : expandedSelectItems) {
      validateNoAggs(groupFinder, selectItem, "SELECT");
      validateExpr(selectItem, selectScope);
    }

    return typeFactory.createStructType(fieldList);
  }

  /**
   * Validates an expression.
   *
   * @param expr  Expression
   * @param scope Scope in which expression occurs
   */
  private void validateExpr(SqlNode expr, SqlValidatorScope scope) {
    if (expr instanceof SqlCall) {
      final SqlOperator op = ((SqlCall) expr).getOperator();
      if (op.isAggregator() && op.requiresOver()) {
        throw newValidationError(expr,
            RESOURCE.absentOverClause());
      }
      if (op instanceof SqlTableFunction) {
        throw RESOURCE.cannotCallTableFunctionHere(op.getName()).ex();
      }
    }

    // Unless 'naked measures' are enabled, a non-aggregate query cannot
    // reference measure columns. (An aggregate query can use them as
    // argument to the AGGREGATE function.)
    if (!config.nakedMeasuresInNonAggregateQuery()
        && !(scope instanceof AggregatingScope)
        && scope.isMeasureRef(expr)) {
      throw newValidationError(expr,
          RESOURCE.measureMustBeInAggregateQuery());
    }

    // Call on the expression to validate itself.
    expr.validateExpr(this, scope);

    // Perform any validation specific to the scope. For example, an
    // aggregating scope requires that expressions are valid aggregations.
    scope.validateExpr(expr);
  }

  /**
   * Processes SubQuery found in Select list. Checks that is actually Scalar
   * sub-query and makes proper entries in each of the 3 lists used to create
   * the final rowType entry.
   *
   * @param parentSelect        base SqlSelect item
   * @param selectItem          child SqlSelect from select list
   * @param expandedSelectItems Select items after processing
   * @param aliasList           built from user or system values
   * @param fieldList           Built up entries for each select list entry
   */
  private void handleScalarSubQuery(SqlSelect parentSelect,
      SqlSelect selectItem, List<SqlNode> expandedSelectItems,
      Set<String> aliasList, PairList<String, RelDataType> fieldList) {
    // A scalar sub-query only has one output column.
    if (1 != SqlNonNullableAccessors.getSelectList(selectItem).size()) {
      throw newValidationError(selectItem,
          RESOURCE.onlyScalarSubQueryAllowed());
    }

    // No expansion in this routine just append to list.
    expandedSelectItems.add(selectItem);

    // Get or generate alias and add to list.
    final String alias =
        SqlValidatorUtil.alias(selectItem, aliasList.size());
    aliasList.add(alias);

    final SelectScope scope = (SelectScope) getWhereScope(parentSelect);
    final RelDataType type = deriveType(scope, selectItem);
    setValidatedNodeType(selectItem, type);

    // We do not want to pass on the RelRecordType returned
    // by the sub-query.  Just the type of the single expression
    // in the sub-query select list.
    assert type instanceof RelRecordType;
    RelRecordType rec = (RelRecordType) type;

    RelDataType nodeType = rec.getFieldList().get(0).getType();
    nodeType = typeFactory.createTypeWithNullability(nodeType, true);
    fieldList.add(alias, nodeType);
  }

  /**
   * Derives a row-type for INSERT and UPDATE operations.
   *
   * @param table            Target table for INSERT/UPDATE
   * @param targetColumnList List of target columns, or null if not specified
   * @param append           Whether to append fields to those in <code>
   *                         baseRowType</code>
   * @return Rowtype
   */
  protected RelDataType createTargetRowType(
      SqlValidatorTable table,
      @Nullable SqlNodeList targetColumnList,
      boolean append) {
    RelDataType baseRowType = table.getRowType();
    if (targetColumnList == null) {
      return baseRowType;
    }
    List<RelDataTypeField> targetFields = baseRowType.getFieldList();
    final PairList<String, RelDataType> fields = PairList.of();
    if (append) {
      for (RelDataTypeField targetField : targetFields) {
        fields.add(SqlUtil.deriveAliasFromOrdinal(fields.size()),
            targetField.getType());
      }
    }
    final Set<Integer> assignedFields = new HashSet<>();
    final RelOptTable relOptTable = table instanceof RelOptTable
        ? ((RelOptTable) table) : null;
    for (SqlNode node : targetColumnList) {
      SqlIdentifier id = (SqlIdentifier) node;
      RelDataTypeField targetField =
          SqlValidatorUtil.getTargetField(
              baseRowType, typeFactory, id, catalogReader, relOptTable);
      if (targetField == null) {
        throw newValidationError(id,
            RESOURCE.unknownTargetColumn(id.toString()));
      }
      if (!assignedFields.add(targetField.getIndex())) {
        throw newValidationError(id,
            RESOURCE.duplicateTargetColumn(targetField.getName()));
      }
      fields.add(targetField);
    }
    return typeFactory.createStructType(fields);
  }

  @Override public void validateInsert(SqlInsert insert) {
    final SqlValidatorNamespace targetNamespace = getNamespaceOrThrow(insert);
    validateNamespace(targetNamespace, unknownType);
    final RelOptTable relOptTable =
        SqlValidatorUtil.getRelOptTable(targetNamespace,
            catalogReader.unwrap(Prepare.CatalogReader.class), null, null);
    final SqlValidatorTable table = relOptTable == null
        ? getTable(targetNamespace)
        : relOptTable.unwrapOrThrow(SqlValidatorTable.class);

    // INSERT has an optional column name list.  If present then
    // reduce the rowtype to the columns specified.  If not present
    // then the entire target rowtype is used.
    final RelDataType targetRowType =
        createTargetRowType(
            table,
            insert.getTargetColumnList(),
            false);

    final SqlNode source = insert.getSource();
    if (source instanceof SqlSelect) {
      final SqlSelect sqlSelect = (SqlSelect) source;
      validateSelect(sqlSelect, targetRowType);
    } else {
      final SqlValidatorScope scope = scopes.get(source);
      requireNonNull(scope, "scope");
      validateQuery(source, scope, targetRowType);
    }

    // REVIEW jvs 4-Dec-2008: In FRG-365, this namespace row type is
    // discarding the type inferred by inferUnknownTypes (which was invoked
    // from validateSelect above).  It would be better if that information
    // were used here so that we never saw any untyped nulls during
    // checkTypeAssignment.
    final RelDataType sourceRowType = getNamespaceOrThrow(source).getRowType();
    final RelDataType logicalTargetRowType =
        getLogicalTargetRowType(targetRowType, insert);
    setValidatedNodeType(insert, logicalTargetRowType);
    final RelDataType logicalSourceRowType =
        getLogicalSourceRowType(sourceRowType, insert);

    final List<ColumnStrategy> strategies =
        table.unwrapOrThrow(RelOptTable.class).getColumnStrategies();

    final RelDataType realTargetRowType =
        typeFactory.createStructType(
            logicalTargetRowType.getFieldList()
                .stream()
                .filter(f -> strategies.get(f.getIndex()).canInsertInto())
                .collect(Collectors.toList()));

    final RelDataType targetRowTypeToValidate =
        logicalSourceRowType.getFieldCount() == logicalTargetRowType.getFieldCount()
        ? logicalTargetRowType
        : realTargetRowType;

    checkFieldCount(insert.getTargetTable(), table, strategies,
        targetRowTypeToValidate, realTargetRowType,
        source, logicalSourceRowType, logicalTargetRowType);

    checkTypeAssignment(scopes.get(source),
        table,
        logicalSourceRowType,
        targetRowTypeToValidate,
        insert);

    checkConstraint(table, source, logicalTargetRowType);

    validateAccess(insert.getTargetTable(), table, SqlAccessEnum.INSERT);

    // Refresh the insert row type to keep sync with source.
    setValidatedNodeType(insert, targetRowTypeToValidate);
  }

  /**
   * Validates insert values against the constraint of a modifiable view.
   *
   * @param validatorTable Table that may wrap a ModifiableViewTable
   * @param source        The values being inserted
   * @param targetRowType The target type for the view
   */
  private void checkConstraint(
      SqlValidatorTable validatorTable,
      SqlNode source,
      RelDataType targetRowType) {
    final ModifiableViewTable modifiableViewTable =
        validatorTable.unwrap(ModifiableViewTable.class);
    if (modifiableViewTable != null && source instanceof SqlCall) {
      final Table table = modifiableViewTable.getTable();
      final RelDataType tableRowType = table.getRowType(typeFactory);
      final List<RelDataTypeField> tableFields = tableRowType.getFieldList();

      // Get the mapping from column indexes of the underlying table
      // to the target columns and view constraints.
      final Map<Integer, RelDataTypeField> tableIndexToTargetField =
          SqlValidatorUtil.getIndexToFieldMap(tableFields, targetRowType);
      final Map<Integer, RexNode> projectMap =
          RelOptUtil.getColumnConstraints(modifiableViewTable, targetRowType, typeFactory);

      // Determine columns (indexed to the underlying table) that need
      // to be validated against the view constraint.
      @SuppressWarnings("RedundantCast")
      final ImmutableBitSet targetColumns =
          ImmutableBitSet.of((Iterable<Integer>) tableIndexToTargetField.keySet());
      @SuppressWarnings("RedundantCast")
      final ImmutableBitSet constrainedColumns =
          ImmutableBitSet.of((Iterable<Integer>) projectMap.keySet());
      @SuppressWarnings("assignment.type.incompatible")
      List<@KeyFor({"tableIndexToTargetField", "projectMap"}) Integer> constrainedTargetColumns =
          targetColumns.intersect(constrainedColumns).asList();

      // Validate insert values against the view constraint.
      final List<SqlNode> values = ((SqlCall) source).getOperandList();
      for (final int colIndex : constrainedTargetColumns) {
        final String colName = tableFields.get(colIndex).getName();
        final RelDataTypeField targetField = tableIndexToTargetField.get(colIndex);
        for (SqlNode row : values) {
          final SqlCall call = (SqlCall) row;
          final SqlNode sourceValue = call.operand(targetField.getIndex());
          final ValidationError validationError =
              new ValidationError(sourceValue,
                  RESOURCE.viewConstraintNotSatisfied(colName,
                      Util.last(validatorTable.getQualifiedName())));
          RelOptUtil.validateValueAgainstConstraint(sourceValue,
              projectMap.get(colIndex), validationError);
        }
      }
    }
  }

  /**
   * Validates updates against the constraint of a modifiable view.
   *
   * @param validatorTable A {@link SqlValidatorTable} that may wrap a
   *                       ModifiableViewTable
   * @param update         The UPDATE parse tree node
   * @param targetRowType  The target type
   */
  private void checkConstraint(
      SqlValidatorTable validatorTable,
      SqlUpdate update,
      RelDataType targetRowType) {
    final ModifiableViewTable modifiableViewTable =
        validatorTable.unwrap(ModifiableViewTable.class);
    if (modifiableViewTable != null) {
      final Table table = modifiableViewTable.getTable();
      final RelDataType tableRowType = table.getRowType(typeFactory);

      final Map<Integer, RexNode> projectMap =
          RelOptUtil.getColumnConstraints(modifiableViewTable, targetRowType,
              typeFactory);
      final Map<String, Integer> nameToIndex =
          SqlValidatorUtil.mapNameToIndex(tableRowType.getFieldList());

      // Validate update values against the view constraint.
      final List<String> targetNames =
          SqlIdentifier.simpleNames(update.getTargetColumnList());
      final List<SqlNode> sources = update.getSourceExpressionList();
      Pair.forEach(targetNames, sources, (columnName, expr) -> {
        final Integer columnIndex = nameToIndex.get(columnName);
        if (projectMap.containsKey(columnIndex)) {
          final RexNode columnConstraint = projectMap.get(columnIndex);
          final ValidationError validationError =
              new ValidationError(expr,
                  RESOURCE.viewConstraintNotSatisfied(columnName,
                      Util.last(validatorTable.getQualifiedName())));
          RelOptUtil.validateValueAgainstConstraint(expr,
              columnConstraint, validationError);
        }
      });
    }
  }

  /**
   * Check the field count of sql insert source and target node row type.
   *
   * @param node                    target table sql identifier
   * @param table                   target table
   * @param strategies              column strategies of target table
   * @param targetRowTypeToValidate row type to validate mainly for column strategies
   * @param realTargetRowType       target table row type exclusive virtual columns
   * @param source                  source node
   * @param logicalSourceRowType    source node row type
   * @param logicalTargetRowType    logical target row type, contains only target columns if
   *                                they are specified or if the sql dialect allows subset insert,
   *                                make a subset of fields(start from the left first field) whose
   *                                length is equals with the source row type fields number
   */
  private void checkFieldCount(SqlNode node, SqlValidatorTable table,
      List<ColumnStrategy> strategies, RelDataType targetRowTypeToValidate,
      RelDataType realTargetRowType, SqlNode source,
      RelDataType logicalSourceRowType, RelDataType logicalTargetRowType) {
    final int sourceFieldCount = logicalSourceRowType.getFieldCount();
    final int targetFieldCount = logicalTargetRowType.getFieldCount();
    final int targetRealFieldCount = realTargetRowType.getFieldCount();
    if (sourceFieldCount != targetFieldCount
        && sourceFieldCount != targetRealFieldCount) {
      // Allows the source row fields count to be equal with either
      // the logical or the real(excludes columns that can not insert into)
      // target row fields count.
      throw newValidationError(node,
          RESOURCE.unmatchInsertColumn(targetFieldCount, sourceFieldCount));
    }
    // Ensure that non-nullable fields are targeted.
    for (final RelDataTypeField field : table.getRowType().getFieldList()) {
      final RelDataTypeField targetField =
          targetRowTypeToValidate.getField(field.getName(), true, false);
      switch (strategies.get(field.getIndex())) {
      case NOT_NULLABLE:
        assert !field.getType().isNullable();
        if (targetField == null) {
          throw newValidationError(node,
              RESOURCE.columnNotNullable(field.getName()));
        }
        break;
      case NULLABLE:
        assert field.getType().isNullable();
        break;
      case VIRTUAL:
      case STORED:
        if (targetField != null
            && !isValuesWithDefault(source, targetField.getIndex())) {
          throw newValidationError(node,
              RESOURCE.insertIntoAlwaysGenerated(field.getName()));
        }
        break;
      default:
        break;
      }
    }
  }

  /** Returns whether a query uses {@code DEFAULT} to populate a given
   * column. */
  private static boolean isValuesWithDefault(SqlNode source, int column) {
    switch (source.getKind()) {
    case VALUES:
      for (SqlNode operand : ((SqlCall) source).getOperandList()) {
        if (!isRowWithDefault(operand, column)) {
          return false;
        }
      }
      return true;
    default:
      break;
    }
    return false;
  }

  private static boolean isRowWithDefault(SqlNode operand, int column) {
    switch (operand.getKind()) {
    case ROW:
      final SqlCall row = (SqlCall) operand;
      return row.getOperandList().size() >= column
          && row.getOperandList().get(column).getKind() == SqlKind.DEFAULT;
    default:
      break;
    }
    return false;
  }

  protected RelDataType getLogicalTargetRowType(
      RelDataType targetRowType,
      SqlInsert insert) {
    if (insert.getTargetColumnList() == null
        && this.config.conformance().isInsertSubsetColumnsAllowed()) {
      // Target an implicit subset of columns.
      final SqlNode source = insert.getSource();
      final RelDataType sourceRowType = getNamespaceOrThrow(source).getRowType();
      final RelDataType logicalSourceRowType =
          getLogicalSourceRowType(sourceRowType, insert);
      final RelDataType implicitTargetRowType =
          typeFactory.createStructType(
              targetRowType.getFieldList()
                  .subList(0, logicalSourceRowType.getFieldCount()));
      final SqlValidatorNamespace targetNamespace = getNamespaceOrThrow(insert);
      validateNamespace(targetNamespace, implicitTargetRowType);
      return implicitTargetRowType;
    } else {
      // Either the set of columns are explicitly targeted, or target the full
      // set of columns.
      return targetRowType;
    }
  }

  protected RelDataType getLogicalSourceRowType(
      RelDataType sourceRowType,
      SqlInsert insert) {
    return sourceRowType;
  }

  /**
   * Checks the type assignment of an INSERT or UPDATE query.
   *
   * <p>Skip the virtual columns(can not insert into) type assignment
   * check if the source fields count equals with
   * the real target table fields count, see how #checkFieldCount was used.
   *
   * @param sourceScope   Scope of query source which is used to infer node type
   * @param table         Target table
   * @param sourceRowType Source row type
   * @param targetRowType Target row type, it should either contain all the virtual columns
   *                      (can not insert into) or exclude all the virtual columns
   * @param query The query
   */
  protected void checkTypeAssignment(
      @Nullable SqlValidatorScope sourceScope,
      SqlValidatorTable table,
      RelDataType sourceRowType,
      RelDataType targetRowType,
      final SqlNode query) {
    // NOTE jvs 23-Feb-2006: subclasses may allow for extra targets
    // representing system-maintained columns, so stop after all sources
    // matched
    boolean isUpdateModifiableViewTable = false;
    if (query instanceof SqlUpdate) {
      final SqlNodeList targetColumnList =
          requireNonNull(((SqlUpdate) query).getTargetColumnList());
      final int targetColumnCount = targetColumnList.size();
      targetRowType =
          SqlTypeUtil.extractLastNFields(typeFactory, targetRowType,
              targetColumnCount);
      sourceRowType =
          SqlTypeUtil.extractLastNFields(typeFactory, sourceRowType,
              targetColumnCount);
      isUpdateModifiableViewTable =
          table.unwrap(ModifiableViewTable.class) != null;
    }
    if (SqlTypeUtil.equalAsStructSansNullability(typeFactory,
        sourceRowType, targetRowType, null)) {
      // Returns early if source and target row type equals sans nullability.
      return;
    }
    if (config.typeCoercionEnabled() && !isUpdateModifiableViewTable) {
      // Try type coercion first if implicit type coercion is allowed.
      boolean coerced =
          typeCoercion.querySourceCoercion(sourceScope, sourceRowType,
              targetRowType, query);
      if (coerced) {
        return;
      }
    }

    // Fall back to default behavior: compare the type families.
    List<RelDataTypeField> sourceFields = sourceRowType.getFieldList();
    List<RelDataTypeField> targetFields = targetRowType.getFieldList();
    final int sourceCount = sourceFields.size();
    for (int i = 0; i < sourceCount; ++i) {
      RelDataType sourceType = sourceFields.get(i).getType();
      RelDataType targetType = targetFields.get(i).getType();
      if (!SqlTypeUtil.canAssignFrom(targetType, sourceType)) {
        SqlNode node = getNthExpr(query, i, sourceCount);
        if (node instanceof SqlDynamicParam) {
          continue;
        }
        String targetTypeString;
        String sourceTypeString;
        if (SqlTypeUtil.areCharacterSetsMismatched(
            sourceType,
            targetType)) {
          sourceTypeString = sourceType.getFullTypeString();
          targetTypeString = targetType.getFullTypeString();
        } else {
          sourceTypeString = sourceType.toString();
          targetTypeString = targetType.toString();
        }
        throw newValidationError(node,
            RESOURCE.typeNotAssignable(
                targetFields.get(i).getName(), targetTypeString,
                sourceFields.get(i).getName(), sourceTypeString));
      }
    }
  }

  /**
   * Locates the n'th expression in an INSERT or UPDATE query.
   *
   * @param query       Query
   * @param ordinal     Ordinal of expression
   * @param sourceCount Number of expressions
   * @return Ordinal'th expression, never null
   */
  private static SqlNode getNthExpr(SqlNode query, int ordinal, int sourceCount) {
    if (query instanceof SqlInsert) {
      SqlInsert insert = (SqlInsert) query;
      if (insert.getTargetColumnList() != null) {
        return insert.getTargetColumnList().get(ordinal);
      } else {
        return getNthExpr(
            insert.getSource(),
            ordinal,
            sourceCount);
      }
    } else if (query instanceof SqlUpdate) {
      SqlUpdate update = (SqlUpdate) query;
      if (update.getSourceExpressionList() != null) {
        return update.getSourceExpressionList().get(ordinal);
      } else {
        return getNthExpr(SqlNonNullableAccessors.getSourceSelect(update),
            ordinal, sourceCount);
      }
    } else if (query instanceof SqlSelect) {
      SqlSelect select = (SqlSelect) query;
      SqlNodeList selectList = SqlNonNullableAccessors.getSelectList(select);
      if (selectList.size() == sourceCount) {
        return selectList.get(ordinal);
      } else {
        return query; // give up
      }
    } else {
      return query; // give up
    }
  }

  @Override public void validateDelete(SqlDelete call) {
    final SqlSelect sqlSelect = SqlNonNullableAccessors.getSourceSelect(call);
    validateSelect(sqlSelect, unknownType);

    final SqlValidatorNamespace targetNamespace = getNamespaceOrThrow(call);
    validateNamespace(targetNamespace, unknownType);
    final SqlValidatorTable table = targetNamespace.getTable();

    validateAccess(call.getTargetTable(), table, SqlAccessEnum.DELETE);
  }

  @Override public void validateUpdate(SqlUpdate call) {
    final SqlValidatorNamespace targetNamespace = getNamespaceOrThrow(call);
    validateNamespace(targetNamespace, unknownType);
    final RelOptTable relOptTable =
        SqlValidatorUtil.getRelOptTable(targetNamespace,
            castNonNull(catalogReader.unwrap(Prepare.CatalogReader.class)),
            null, null);
    final SqlValidatorTable table = relOptTable == null
        ? getTable(targetNamespace)
        : relOptTable.unwrapOrThrow(SqlValidatorTable.class);

    final RelDataType targetRowType =
        createTargetRowType(table, call.getTargetColumnList(), true);

    final SqlSelect select = SqlNonNullableAccessors.getSourceSelect(call);
    validateSelect(select, targetRowType);

    final RelDataType sourceRowType = getValidatedNodeType(select);
    checkTypeAssignment(scopes.get(select), table, sourceRowType, targetRowType,
        call);

    checkConstraint(table, call, targetRowType);

    validateAccess(call.getTargetTable(), table, SqlAccessEnum.UPDATE);
  }

  @Override public void validateMerge(SqlMerge call) {
    SqlSelect sqlSelect = SqlNonNullableAccessors.getSourceSelect(call);
    // REVIEW zfong 5/25/06 - Does an actual type have to be passed into
    // validateSelect()?

    // REVIEW jvs 6-June-2006:  In general, passing unknownType like
    // this means we won't be able to correctly infer the types
    // for dynamic parameter markers (SET x = ?).  But
    // maybe validateUpdate and validateInsert below will do
    // the job?

    // REVIEW ksecretan 15-July-2011: They didn't get a chance to
    // since validateSelect() would bail.
    // Let's use the update/insert targetRowType when available.
    IdentifierNamespace targetNamespace =
        (IdentifierNamespace) getNamespaceOrThrow(call.getTargetTable());
    validateNamespace(targetNamespace, unknownType);

    SqlValidatorTable table = targetNamespace.getTable();
    validateAccess(call.getTargetTable(), table, SqlAccessEnum.UPDATE);

    RelDataType targetRowType = unknownType;

    SqlUpdate updateCall = call.getUpdateCall();
    if (updateCall != null) {
      requireNonNull(table, () -> "ns.getTable() for " + targetNamespace);
      targetRowType =
          createTargetRowType(table, updateCall.getTargetColumnList(), true);
    }
    SqlInsert insertCall = call.getInsertCall();
    if (insertCall != null) {
      requireNonNull(table, () -> "ns.getTable() for " + targetNamespace);
      targetRowType =
          createTargetRowType(table, insertCall.getTargetColumnList(), false);
    }

    validateSelect(sqlSelect, targetRowType);

    SqlUpdate updateCallAfterValidate = call.getUpdateCall();
    if (updateCallAfterValidate != null) {
      validateUpdate(updateCallAfterValidate);
    }
    SqlInsert insertCallAfterValidate = call.getInsertCall();
    if (insertCallAfterValidate != null) {
      validateInsert(insertCallAfterValidate);
      // Throw if select list contains NULL literal and target is NOT NULL
      if (insertCallAfterValidate.getSource() instanceof SqlSelect) {
        final SqlSelect sourceSelect = (SqlSelect) insertCallAfterValidate.getSource();
        final SqlNodeList sourceSelectList = sourceSelect.getSelectList();
        for (int i = 0; i < sourceSelectList.size(); i++) {
          final RelDataTypeField targetField = targetRowType.getFieldList().get(i);
          final SqlNode selectItem = sourceSelect.getSelectList().get(i);
          if (!targetField.getType().isNullable() && SqlUtil.isNullLiteral(selectItem, true)) {
            throw newValidationError(selectItem,
                RESOURCE.columnNotNullable(targetField.getName()));
          }
        }
      }

    }
  }

  /**
   * Validates access to a table.
   *
   * @param table          Table
   * @param requiredAccess Access requested on table
   */
  private void validateAccess(
      SqlNode node,
      @Nullable SqlValidatorTable table,
      SqlAccessEnum requiredAccess) {
    if (table != null) {
      SqlAccessType access = table.getAllowedAccess();
      if (!access.allowsAccess(requiredAccess)) {
        throw newValidationError(node,
            RESOURCE.accessNotAllowed(requiredAccess.name(),
                table.getQualifiedName().toString()));
      }
    }
  }

  /**
   * Validates snapshot to a table.
   *
   * @param node  The node to validate
   * @param scope Validator scope to derive type
   * @param ns    The namespace to lookup table
   */
  private void validateSnapshot(
      SqlNode node,
      @Nullable SqlValidatorScope scope,
      SqlValidatorNamespace ns) {
    if (node.getKind() == SqlKind.SNAPSHOT) {
      SqlSnapshot snapshot = (SqlSnapshot) node;
      SqlNode period = snapshot.getPeriod();
      RelDataType dataType = deriveType(requireNonNull(scope, "scope"), period);
      if (!SqlTypeUtil.isTimestamp(dataType)) {
        throw newValidationError(period,
            Static.RESOURCE.illegalExpressionForTemporal(dataType.getSqlTypeName().getName()));
      }
      SqlValidatorTable table = getTable(ns);
      if (!table.isTemporal()) {
        List<String> qualifiedName = table.getQualifiedName();
        String tableName = qualifiedName.get(qualifiedName.size() - 1);
        throw newValidationError(snapshot.getTableRef(),
            Static.RESOURCE.notTemporalTable(tableName));
      }
    }
  }

  /**
   * Validates a VALUES clause.
   *
   * @param node          Values clause
   * @param targetRowType Row type which expression must conform to
   * @param scope         Scope within which clause occurs
   */
  // 专门用于验证 VALUES 子句 的核心方法。它确保内联数据（Inline Data）在结构上是对齐的，且数据类型在列维度上是兼容的。
  // 主要作用是执行以下三类检查：
  // 结构一致性：确保 VALUES 中的每一行（ROW）具有相同的列数。
  // 类型推导与匹配：如果存在目标类型（如 INSERT 目标表），则将类型下推到具体的字面量或参数上。
  // 类型兼容性：在没有明确目标类型时，确保同一列下的不同行数据能够转换到一个公共的父类型（Least Restrictive Type）。
  protected void validateValues(
      SqlCall node, // 代表整个 VALUES 表达式。它的操作数通常是一组 SqlKind.ROW 的调用。
      RelDataType targetRowType, // 期望类型。在 INSERT INTO t(a, b) VALUES (1, 'x') 中，它代表列 (a, b) 的结构。
      final SqlValidatorScope scope) { // 验证作用域。用于解析 VALUES 中可能出现的表达式（如函数调用或标量子查询）
    assert node.getKind() == SqlKind.VALUES;
    // 获取所有行
    final List<SqlNode> operands = node.getOperandList();
    // 遍历所有操作数，目前 Calcite 要求 VALUES 内部必须是 ROW 构造器（即 VALUES (row1), (row2) 形式）。
    for (SqlNode operand : operands) {
      if (!(operand.getKind() == SqlKind.ROW)) {
        throw Util.needToImplement(
            "Values function where operands are scalars");
      }

      SqlCall rowConstructor = (SqlCall) operand;
      // 处理 INSERT 子集列的情况
      if (this.config.conformance().isInsertSubsetColumnsAllowed()
          && targetRowType.isStruct()
          && rowConstructor.operandCount() < targetRowType.getFieldCount()) {
        // 逻辑：如果配置允许插入部分列，且 VALUES 提供的列数少于目标类型，则截断 targetRowType 以匹配当前的列数。如果不匹配且不允许子集，则跳过或交由上层处理。
        targetRowType =
            typeFactory.createStructType(
                targetRowType.getFieldList()
                    .subList(0, rowConstructor.operandCount()));
      } else if (targetRowType.isStruct()
          && rowConstructor.operandCount() != targetRowType.getFieldCount()) {
        return;
      }
      // 如果 VALUES 包含动态参数 ? 或 NULL 字面量，这一行代码将根据 targetRowType 的定义，赋予这些未知节点具体的类型。
      inferUnknownTypes(
          targetRowType,
          scope,
          rowConstructor);
      // 如果目标列定义为 NOT NULL，但用户在 VALUES 中显式写了 NULL 字面量，直接抛出验证错误。
      if (targetRowType.isStruct()) {
        for (Pair<SqlNode, RelDataTypeField> pair
            : Pair.zip(rowConstructor.getOperandList(),
                targetRowType.getFieldList())) {
          if (!pair.right.getType().isNullable()
              && SqlUtil.isNullLiteral(pair.left, false)) {
            throw newValidationError(node,
                RESOURCE.columnNotNullable(pair.right.getName()));
          }
        }
      }
    }
    // 触发每一行内部表达式的递归验证。
    for (SqlNode operand : operands) {
      operand.validate(this, scope);
    }

    // validate that all row types have the same number of columns
    //  and that expressions in each column are compatible.
    // A values expression is turned into something that looks like
    // ROW(type00, type01,...), ROW(type11,...),...
    // 多行间的兼容性检查（核心逻辑）
    final int rowCount = operands.size();
    if (rowCount >= 2) {
      SqlCall firstRow = (SqlCall) operands.get(0);
      final int columnCount = firstRow.operandCount();

      // 1. check that all rows have the same cols length
      // 1. 检查所有行的列数是否一致
      for (SqlNode operand : operands) {
        SqlCall thisRow = (SqlCall) operand;
        if (columnCount != thisRow.operandCount()) {
          throw newValidationError(node,
              RESOURCE.incompatibleValueType(
                  SqlStdOperatorTable.VALUES.getName()));
        }
      }

      // 2. check if types at i:th position in each row are compatible
      // 2. 检查每一列在所有行中的类型是否兼容
      for (int col = 0; col < columnCount; col++) {
        final int c = col;
        // 例子：如果第一行第一列是 1 (INTEGER)，第二行第一列是 2.5 (DECIMAL)，则该列最终会被推导为 DECIMAL。如果类型无法统一（如 INT 和 DATE），则报错
        final RelDataType type =
            typeFactory.leastRestrictive(
                new AbstractList<RelDataType>() {
                  @Override public RelDataType get(int row) {
                    SqlCall thisRow = (SqlCall) operands.get(row);
                    return deriveType(scope, thisRow.operand(c));
                  }

                  @Override public int size() {
                    return rowCount;
                  }
                });

        if (null == type) {
          throw newValidationError(node,
              RESOURCE.incompatibleValueType(
                  SqlStdOperatorTable.VALUES.getName()));
        }
      }
    }
  }

  @Override public void validateDataType(SqlDataTypeSpec dataType) {
  }

  @Override public void validateDynamicParam(SqlDynamicParam dynamicParam) {
  }

  /**
   * Throws a validator exception with access to the validator context.
   * The exception is determined when an instance is created.
   */
  private class ValidationError implements Supplier<CalciteContextException> {
    private final SqlNode sqlNode;
    private final Resources.ExInst<SqlValidatorException> validatorException;

    ValidationError(SqlNode sqlNode,
        Resources.ExInst<SqlValidatorException> validatorException) {
      this.sqlNode = sqlNode;
      this.validatorException = validatorException;
    }

    @Override public CalciteContextException get() {
      return newValidationError(sqlNode, validatorException);
    }
  }

  /**
   * Throws a validator exception with access to the validator context.
   * The exception is determined when the function is applied.
   */
  class ValidationErrorFunction
      implements BiFunction<SqlNode, Resources.ExInst<SqlValidatorException>,
            CalciteContextException> {
    @Override public CalciteContextException apply(
        SqlNode v0, Resources.ExInst<SqlValidatorException> v1) {
      return newValidationError(v0, v1);
    }
  }

  public ValidationErrorFunction getValidationErrorFunction() {
    return validationErrorFunction;
  }

  @Override public CalciteContextException newValidationError(SqlNode node,
      Resources.ExInst<SqlValidatorException> e) {
    assert node != null;
    final SqlParserPos pos = node.getParserPosition();
    return SqlUtil.newContextException(pos, e);
  }

  protected SqlWindow getWindowByName(
      SqlIdentifier id,
      SqlValidatorScope scope) {
    SqlWindow window = null;
    if (id.isSimple()) {
      final String name = id.getSimple();
      window = scope.lookupWindow(name);
    }
    if (window == null) {
      throw newValidationError(id, RESOURCE.windowNotFound(id.toString()));
    }
    return window;
  }

  @Override public SqlWindow resolveWindow(
      SqlNode windowOrRef,
      SqlValidatorScope scope) {
    SqlWindow window;
    if (windowOrRef instanceof SqlIdentifier) {
      window = getWindowByName((SqlIdentifier) windowOrRef, scope);
    } else {
      window = (SqlWindow) windowOrRef;
    }
    while (true) {
      final SqlIdentifier refId = window.getRefName();
      if (refId == null) {
        break;
      }
      final String refName = refId.getSimple();
      SqlWindow refWindow = scope.lookupWindow(refName);
      if (refWindow == null) {
        throw newValidationError(refId, RESOURCE.windowNotFound(refName));
      }
      window = window.overlay(refWindow, this);
    }

    return window;
  }

  public SqlNode getOriginal(SqlNode expr) {
    SqlNode original = originalExprs.get(expr);
    if (original == null) {
      original = expr;
    }
    return original;
  }

  public void setOriginal(SqlNode expr, SqlNode original) {
    // Don't overwrite the original original.
    originalExprs.putIfAbsent(expr, original);
  }

  @Nullable SqlValidatorNamespace lookupFieldNamespace(RelDataType rowType, String name) {
    final SqlNameMatcher nameMatcher = catalogReader.nameMatcher();
    final RelDataTypeField field = nameMatcher.field(rowType, name);
    if (field == null) {
      return null;
    }
    return new FieldNamespace(this, field.getType());
  }

  @Override public void validateWindow(
      SqlNode windowOrId,
      SqlValidatorScope scope,
      @Nullable SqlCall call) {
    // Enable nested aggregates with window aggregates (OVER operator)
    inWindow = true;

    final SqlWindow targetWindow;
    switch (windowOrId.getKind()) {
    case IDENTIFIER:
      // Just verify the window exists in this query.  It will validate
      // when the definition is processed
      targetWindow = getWindowByName((SqlIdentifier) windowOrId, scope);
      break;
    case WINDOW:
      targetWindow = (SqlWindow) windowOrId;
      break;
    default:
      throw Util.unexpected(windowOrId.getKind());
    }

    requireNonNull(call, () -> "call is null when validating windowOrId " + windowOrId);
    assert targetWindow.getWindowCall() == null;
    targetWindow.setWindowCall(call);
    targetWindow.validate(this, scope);
    targetWindow.setWindowCall(null);
    call.validate(this, scope);

    validateAggregateParams(call, null, null, null, scope);

    // Disable nested aggregates post validation
    inWindow = false;
  }

  @Override public void validateLambda(SqlLambda lambdaExpr) {
    final SqlLambdaScope scope = (SqlLambdaScope) scopes.get(lambdaExpr);
    requireNonNull(scope, "scope");
    final LambdaNamespace ns =
        getNamespaceOrThrow(lambdaExpr).unwrap(LambdaNamespace.class);

    deriveType(scope, lambdaExpr.getExpression());
    RelDataType type = deriveTypeImpl(scope, lambdaExpr);
    setValidatedNodeType(lambdaExpr, type);
    ns.setType(type);
  }

  @Override public void validateMatchRecognize(SqlCall call) {
    final SqlMatchRecognize matchRecognize = (SqlMatchRecognize) call;
    final MatchRecognizeScope scope =
        (MatchRecognizeScope) getMatchRecognizeScope(matchRecognize);

    final MatchRecognizeNamespace ns =
        getNamespaceOrThrow(call).unwrap(MatchRecognizeNamespace.class);
    assert ns.rowType == null;

    // rows per match
    final SqlLiteral rowsPerMatch = matchRecognize.getRowsPerMatch();
    final boolean allRows = rowsPerMatch != null
        && rowsPerMatch.getValue()
        == SqlMatchRecognize.RowsPerMatchOption.ALL_ROWS;

    final RelDataTypeFactory.Builder typeBuilder = typeFactory.builder();

    // parse PARTITION BY column
    SqlNodeList partitionBy = matchRecognize.getPartitionList();
    if (partitionBy != null) {
      for (SqlNode node : partitionBy) {
        SqlIdentifier identifier = (SqlIdentifier) node;
        identifier.validate(this, scope);
        RelDataType type = deriveType(scope, identifier);
        String name = identifier.names.get(1);
        typeBuilder.add(name, type);
      }
    }

    // parse ORDER BY column
    SqlNodeList orderBy = matchRecognize.getOrderList();
    if (orderBy != null) {
      for (SqlNode node : orderBy) {
        node.validate(this, scope);
        SqlIdentifier identifier;
        if (node instanceof SqlBasicCall) {
          identifier = ((SqlBasicCall) node).operand(0);
        } else {
          identifier =
              requireNonNull((SqlIdentifier) node,
                  () -> "order by field is null. All fields: " + orderBy);
        }

        if (allRows) {
          RelDataType type = deriveType(scope, identifier);
          String name = identifier.names.get(1);
          if (!typeBuilder.nameExists(name)) {
            typeBuilder.add(name, type);
          }
        }
      }
    }

    if (allRows) {
      final SqlValidatorNamespace sqlNs =
          getNamespaceOrThrow(matchRecognize.getTableRef());
      final RelDataType inputDataType = sqlNs.getRowType();
      for (RelDataTypeField fs : inputDataType.getFieldList()) {
        if (!typeBuilder.nameExists(fs.getName())) {
          typeBuilder.add(fs);
        }
      }
    }

    // retrieve pattern variables used in pattern and subset
    SqlNode pattern = matchRecognize.getPattern();
    PatternVarVisitor visitor = new PatternVarVisitor(scope);
    pattern.accept(visitor);

    SqlLiteral interval = matchRecognize.getInterval();
    if (interval != null) {
      interval.validate(this, scope);
      if (((SqlIntervalLiteral) interval).signum() < 0) {
        String intervalValue = interval.toValue();
        throw newValidationError(interval,
          RESOURCE.intervalMustBeNonNegative(
              intervalValue != null ? intervalValue : interval.toString()));
      }
      if (orderBy == null || orderBy.size() == 0) {
        throw newValidationError(interval,
          RESOURCE.cannotUseWithinWithoutOrderBy());
      }

      SqlNode firstOrderByColumn = orderBy.get(0);
      SqlIdentifier identifier;
      if (firstOrderByColumn instanceof SqlBasicCall) {
        identifier = ((SqlBasicCall) firstOrderByColumn).operand(0);
      } else {
        identifier = (SqlIdentifier) requireNonNull(firstOrderByColumn, "firstOrderByColumn");
      }
      RelDataType firstOrderByColumnType = deriveType(scope, identifier);
      if (!SqlTypeUtil.isTimestamp(firstOrderByColumnType)) {
        throw newValidationError(interval,
          RESOURCE.firstColumnOfOrderByMustBeTimestamp());
      }

      SqlNode expand = expand(interval, scope);
      RelDataType type = deriveType(scope, expand);
      setValidatedNodeType(interval, type);
    }

    validateDefinitions(matchRecognize, scope);

    SqlNodeList subsets = matchRecognize.getSubsetList();
    if (subsets != null && subsets.size() > 0) {
      for (SqlNode node : subsets) {
        List<SqlNode> operands = ((SqlCall) node).getOperandList();
        String leftString = ((SqlIdentifier) operands.get(0)).getSimple();
        if (scope.getPatternVars().contains(leftString)) {
          throw newValidationError(operands.get(0),
              RESOURCE.patternVarAlreadyDefined(leftString));
        }
        scope.addPatternVar(leftString);
        for (SqlNode right : (SqlNodeList) operands.get(1)) {
          SqlIdentifier id = (SqlIdentifier) right;
          if (!scope.getPatternVars().contains(id.getSimple())) {
            throw newValidationError(id,
                RESOURCE.unknownPattern(id.getSimple()));
          }
          scope.addPatternVar(id.getSimple());
        }
      }
    }

    // validate AFTER ... SKIP TO
    final SqlNode skipTo = matchRecognize.getAfter();
    if (skipTo instanceof SqlCall) {
      final SqlCall skipToCall = (SqlCall) skipTo;
      final SqlIdentifier id = skipToCall.operand(0);
      if (!scope.getPatternVars().contains(id.getSimple())) {
        throw newValidationError(id,
            RESOURCE.unknownPattern(id.getSimple()));
      }
    }

    PairList<String, RelDataType> measureColumns =
        validateMeasure(matchRecognize, scope, allRows);
    measureColumns.forEach((name, type) -> {
      if (!typeBuilder.nameExists(name)) {
        typeBuilder.add(name, type);
      }
    });

    final RelDataType rowType;
    if (matchRecognize.getMeasureList().isEmpty()) {
      rowType = getNamespaceOrThrow(matchRecognize.getTableRef()).getRowType();
    } else {
      rowType = typeBuilder.build();
    }
    ns.setType(rowType);
  }

  private PairList<String, RelDataType> validateMeasure(SqlMatchRecognize mr,
      MatchRecognizeScope scope, boolean allRows) {
    final List<String> aliases = new ArrayList<>();
    final List<SqlNode> sqlNodes = new ArrayList<>();
    final SqlNodeList measures = mr.getMeasureList();
    final PairList<String, RelDataType> fields = PairList.of();

    for (SqlNode measure : measures) {
      assert measure instanceof SqlCall;
      final String alias = SqlValidatorUtil.alias(measure, aliases.size());
      aliases.add(alias);

      SqlNode expand = expand(measure, scope);
      expand = navigationInMeasure(expand, allRows);
      setOriginal(expand, measure);

      inferUnknownTypes(unknownType, scope, expand);
      final RelDataType type = deriveType(scope, expand);
      setValidatedNodeType(measure, type);

      fields.add(alias, type);
      sqlNodes.add(
          SqlStdOperatorTable.AS.createCall(SqlParserPos.ZERO, expand,
              new SqlIdentifier(alias, SqlParserPos.ZERO)));
    }

    SqlNodeList list = new SqlNodeList(sqlNodes, measures.getParserPosition());
    inferUnknownTypes(unknownType, scope, list);

    for (SqlNode node : list) {
      validateExpr(node, scope);
    }

    mr.setOperand(SqlMatchRecognize.OPERAND_MEASURES, list);

    return fields;
  }

  private SqlNode navigationInMeasure(SqlNode node, boolean allRows) {
    final Set<String> prefix = node.accept(new PatternValidator(true));
    Util.discard(prefix);
    final List<SqlNode> ops = ((SqlCall) node).getOperandList();

    final SqlOperator defaultOp =
        allRows ? SqlStdOperatorTable.RUNNING : SqlStdOperatorTable.FINAL;
    final SqlNode op0 = ops.get(0);
    if (!isRunningOrFinal(op0.getKind())
        || !allRows && op0.getKind() == SqlKind.RUNNING) {
      SqlNode newNode = defaultOp.createCall(SqlParserPos.ZERO, op0);
      node = SqlStdOperatorTable.AS.createCall(SqlParserPos.ZERO, newNode, ops.get(1));
    }

    node = new NavigationExpander().go(node);
    return node;
  }

  private void validateDefinitions(SqlMatchRecognize mr,
      MatchRecognizeScope scope) {
    final Set<String> aliases = catalogReader.nameMatcher().createSet();
    for (SqlNode item : mr.getPatternDefList()) {
      final String alias = alias(item);
      if (!aliases.add(alias)) {
        throw newValidationError(item,
            Static.RESOURCE.patternVarAlreadyDefined(alias));
      }
      scope.addPatternVar(alias);
    }

    final List<SqlNode> sqlNodes = new ArrayList<>();
    for (SqlNode item : mr.getPatternDefList()) {
      final String alias = alias(item);
      SqlNode expand = expand(item, scope);
      expand = navigationInDefine(expand, alias);
      setOriginal(expand, item);

      inferUnknownTypes(booleanType, scope, expand);
      expand.validate(this, scope);

      // Some extra work need required here.
      // In PREV, NEXT, FINAL and LAST, only one pattern variable is allowed.
      sqlNodes.add(
          SqlStdOperatorTable.AS.createCall(SqlParserPos.ZERO, expand,
              new SqlIdentifier(alias, SqlParserPos.ZERO)));

      final RelDataType type = deriveType(scope, expand);
      if (!SqlTypeUtil.inBooleanFamily(type)) {
        throw newValidationError(expand, RESOURCE.condMustBeBoolean("DEFINE"));
      }
      setValidatedNodeType(item, type);
    }

    SqlNodeList list =
        new SqlNodeList(sqlNodes, mr.getPatternDefList().getParserPosition());
    inferUnknownTypes(unknownType, scope, list);
    for (SqlNode node : list) {
      validateExpr(node, scope);
    }
    mr.setOperand(SqlMatchRecognize.OPERAND_PATTERN_DEFINES, list);
  }

  /** Returns the alias of a "expr AS alias" expression. */
  private static String alias(SqlNode item) {
    assert item instanceof SqlCall;
    assert item.getKind() == SqlKind.AS;
    final SqlIdentifier identifier = ((SqlCall) item).operand(1);
    return identifier.getSimple();
  }

  public void validatePivot(SqlPivot pivot) {
    final PivotScope scope = (PivotScope) getJoinScope(pivot);

    final PivotNamespace ns =
        getNamespaceOrThrow(pivot).unwrap(PivotNamespace.class);
    assert ns.rowType == null;

    // Given
    //   query PIVOT (agg1 AS a, agg2 AS b, ...
    //   FOR (axis1, ..., axisN)
    //   IN ((v11, ..., v1N) AS label1,
    //       (v21, ..., v2N) AS label2, ...))
    // the type is
    //   k1, ... kN, a_label1, b_label1, ..., a_label2, b_label2, ...
    // where k1, ... kN are columns that are not referenced as an argument to
    // an aggregate or as an axis.

    // Aggregates, e.g. "PIVOT (sum(x) AS sum_x, count(*) AS c)"
    final PairList<@Nullable String, RelDataType> aggNames = PairList.of();
    pivot.forEachAgg((alias, call) -> {
      call.validate(this, scope);
      final RelDataType type = deriveType(scope, call);
      aggNames.add(alias, type);
      if (!(call instanceof SqlCall)
          || !(((SqlCall) call).getOperator() instanceof SqlAggFunction)) {
        throw newValidationError(call, RESOURCE.pivotAggMalformed());
      }
    });

    // Axes, e.g. "FOR (JOB, DEPTNO)"
    final List<RelDataType> axisTypes = new ArrayList<>();
    final List<SqlIdentifier> axisIdentifiers = new ArrayList<>();
    for (SqlNode axis : pivot.axisList) {
      SqlIdentifier identifier = (SqlIdentifier) axis;
      identifier.validate(this, scope);
      final RelDataType type = deriveType(scope, identifier);
      axisTypes.add(type);
      axisIdentifiers.add(identifier);
    }

    // Columns that have been seen as arguments to aggregates or as axes
    // do not appear in the output.
    final Set<String> columnNames = pivot.usedColumnNames();
    final RelDataTypeFactory.Builder typeBuilder = typeFactory.builder();
    scope.getChild().getRowType().getFieldList().forEach(field -> {
      if (!columnNames.contains(field.getName())) {
        typeBuilder.add(field);
      }
    });

    // Values, e.g. "IN (('CLERK', 10) AS c10, ('MANAGER, 20) AS m20)"
    pivot.forEachNameValues((alias, nodeList) -> {
      if (nodeList.size() != axisTypes.size()) {
        throw newValidationError(nodeList,
            RESOURCE.pivotValueArityMismatch(nodeList.size(),
                axisTypes.size()));
      }
      final SqlOperandTypeChecker typeChecker =
          OperandTypes.COMPARABLE_UNORDERED_COMPARABLE_UNORDERED;
      Pair.forEach(axisIdentifiers, nodeList, (identifier, subNode) -> {
        subNode.validate(this, scope);
        typeChecker.checkOperandTypes(
            new SqlCallBinding(this, scope,
                SqlStdOperatorTable.EQUALS.createCall(
                    subNode.getParserPosition(), identifier, subNode)),
            true);
      });
      aggNames.forEach((aggAlias, aggType) ->
          typeBuilder.add(aggAlias == null ? alias : alias + "_" + aggAlias,
              aggType));
    });

    final RelDataType rowType = typeBuilder.build();
    ns.setType(rowType);
  }

  public void validateUnpivot(SqlUnpivot unpivot) {
    final UnpivotScope scope = (UnpivotScope) getJoinScope(unpivot);

    final UnpivotNamespace ns =
        getNamespaceOrThrow(unpivot).unwrap(UnpivotNamespace.class);
    assert ns.rowType == null;

    // Given
    //   query UNPIVOT ((measure1, ..., measureM)
    //   FOR (axis1, ..., axisN)
    //   IN ((c11, ..., c1M) AS (value11, ..., value1N),
    //       (c21, ..., c2M) AS (value21, ..., value2N), ...)
    // the type is
    //   k1, ... kN, axis1, ..., axisN, measure1, ..., measureM
    // where k1, ... kN are columns that are not referenced as an argument to
    // an aggregate or as an axis.

    // First, And make sure that each
    final int measureCount = unpivot.measureList.size();
    final int axisCount = unpivot.axisList.size();
    unpivot.forEachNameValues((nodeList, valueList) -> {
      // Make sure that each (ci1, ... ciM) list has the same arity as
      // (measure1, ..., measureM).
      if (nodeList.size() != measureCount) {
        throw newValidationError(nodeList,
            RESOURCE.unpivotValueArityMismatch(nodeList.size(),
                measureCount));
      }

      // Make sure that each (vi1, ... viN) list has the same arity as
      // (axis1, ..., axisN).
      if (valueList != null && valueList.size() != axisCount) {
        throw newValidationError(valueList,
            RESOURCE.unpivotValueArityMismatch(valueList.size(),
                axisCount));
      }

      // Make sure that each IN expression is a valid column from the input.
      nodeList.forEach(node -> deriveType(scope, node));
    });

    // What columns from the input are not referenced by a column in the IN
    // list?
    final SqlValidatorNamespace inputNs =
        requireNonNull(getNamespace(unpivot.query));
    final Set<String> unusedColumnNames =
        catalogReader.nameMatcher().createSet();
    unusedColumnNames.addAll(inputNs.getRowType().getFieldNames());
    unusedColumnNames.removeAll(unpivot.usedColumnNames());

    // What columns will be present in the output row type?
    final Set<String> columnNames = catalogReader.nameMatcher().createSet();
    columnNames.addAll(unusedColumnNames);

    // Gather the name and type of each measure.
    final PairList<String, RelDataType> measureNameTypes = PairList.of();
    forEach(unpivot.measureList, (measure, i) -> {
      final String measureName = ((SqlIdentifier) measure).getSimple();
      final List<RelDataType> types = new ArrayList<>();
      final List<SqlNode> nodes = new ArrayList<>();
      unpivot.forEachNameValues((nodeList, valueList) -> {
        final SqlNode alias = nodeList.get(i);
        nodes.add(alias);
        types.add(deriveType(scope, alias));
      });
      final RelDataType type0 = typeFactory.leastRestrictive(types);
      if (type0 == null) {
        throw newValidationError(nodes.get(0),
            RESOURCE.unpivotCannotDeriveMeasureType(measureName));
      }
      final RelDataType type =
          typeFactory.createTypeWithNullability(type0,
              unpivot.includeNulls || unpivot.measureList.size() > 1);
      setValidatedNodeType(measure, type);
      if (!columnNames.add(measureName)) {
        throw newValidationError(measure,
            RESOURCE.unpivotDuplicate(measureName));
      }
      measureNameTypes.add(measureName, type);
    });

    // Gather the name and type of each axis.
    // Consider
    //   FOR (job, deptno)
    //   IN (a AS ('CLERK', 10),
    //       b AS ('ANALYST', 20))
    // There are two axes, (job, deptno), and so each value list ('CLERK', 10),
    // ('ANALYST', 20) must have arity two.
    //
    // The type of 'job' is derived as the least restrictive type of the values
    // ('CLERK', 'ANALYST'), namely VARCHAR(7). The derived type of 'deptno' is
    // the type of values (10, 20), namely INTEGER.
    final PairList<String, RelDataType> axisNameTypes = PairList.of();
    forEach(unpivot.axisList, (axis, i) -> {
      final String axisName = ((SqlIdentifier) axis).getSimple();
      final List<RelDataType> types = new ArrayList<>();
      unpivot.forEachNameValues((aliasList, valueList) ->
          types.add(
              valueList == null
                  ? typeFactory.createSqlType(SqlTypeName.VARCHAR,
                        SqlUnpivot.aliasValue(aliasList).length())
                  : deriveType(scope, valueList.get(i))));
      final RelDataType type = typeFactory.leastRestrictive(types);
      if (type == null) {
        throw newValidationError(axis,
            RESOURCE.unpivotCannotDeriveAxisType(axisName));
      }
      setValidatedNodeType(axis, type);
      if (!columnNames.add(axisName)) {
        throw newValidationError(axis, RESOURCE.unpivotDuplicate(axisName));
      }
      axisNameTypes.add(axisName, type);
    });

    // Columns that have been seen as arguments to aggregates or as axes
    // do not appear in the output.
    final RelDataTypeFactory.Builder typeBuilder = typeFactory.builder();
    scope.getChild().getRowType().getFieldList().forEach(field -> {
      if (unusedColumnNames.contains(field.getName())) {
        typeBuilder.add(field);
      }
    });
    typeBuilder.addAll(axisNameTypes);
    typeBuilder.addAll(measureNameTypes);

    final RelDataType rowType = typeBuilder.build();
    ns.setType(rowType);
  }

  /** Checks that all pattern variables within a function are the same,
   * and canonizes expressions such as {@code PREV(B.price)} to
   * {@code LAST(B.price, 0)}. */
  private SqlNode navigationInDefine(SqlNode node, String alpha) {
    Set<String> prefix = node.accept(new PatternValidator(false));
    Util.discard(prefix);
    node = new NavigationExpander().go(node);
    node = new NavigationReplacer(alpha).go(node);
    return node;
  }

  @Override public void validateAggregateParams(SqlCall aggCall,
      @Nullable SqlNode filter, @Nullable SqlNodeList distinctList,
      @Nullable SqlNodeList orderList, SqlValidatorScope scope) {
    // For "agg(expr)", expr cannot itself contain aggregate function
    // invocations.  For example, "SUM(2 * MAX(x))" is illegal; when
    // we see it, we'll report the error for the SUM (not the MAX).
    // For more than one level of nesting, the error which results
    // depends on the traversal order for validation.
    //
    // For a windowed aggregate "agg(expr)", expr can contain an aggregate
    // function. For example,
    //   SELECT AVG(2 * MAX(x)) OVER (PARTITION BY y)
    //   FROM t
    //   GROUP BY y
    // is legal. Only one level of nesting is allowed since non-windowed
    // aggregates cannot nest aggregates.

    // Store nesting level of each aggregate. If an aggregate is found at an invalid
    // nesting level, throw an assert.
    final AggFinder a;
    if (inWindow) {
      a = overFinder;
    } else {
      a = aggOrOverFinder;
    }

    for (SqlNode param : aggCall.getOperandList()) {
      if (a.findAgg(param) != null) {
        throw newValidationError(aggCall, RESOURCE.nestedAggIllegal());
      }
    }
    if (filter != null) {
      if (a.findAgg(filter) != null) {
        throw newValidationError(filter, RESOURCE.aggregateInFilterIllegal());
      }
    }
    if (distinctList != null) {
      for (SqlNode param : distinctList) {
        if (a.findAgg(param) != null) {
          throw newValidationError(aggCall,
              RESOURCE.aggregateInWithinDistinctIllegal());
        }
      }
    }
    if (orderList != null) {
      for (SqlNode param : orderList) {
        if (a.findAgg(param) != null) {
          throw newValidationError(aggCall,
              RESOURCE.aggregateInWithinGroupIllegal());
        }
      }
    }

    final SqlAggFunction op = (SqlAggFunction) aggCall.getOperator();
    switch (op.requiresGroupOrder()) {
    case MANDATORY:
      if (orderList == null || orderList.isEmpty()) {
        throw newValidationError(aggCall,
            RESOURCE.aggregateMissingWithinGroupClause(op.getName()));
      }
      break;
    case OPTIONAL:
      break;
    case IGNORED:
      // rewrite the order list to empty
      if (orderList != null) {
        orderList.clear();
      }
      break;
    case FORBIDDEN:
      if (orderList != null && !orderList.isEmpty()) {
        throw newValidationError(aggCall,
            RESOURCE.withinGroupClauseIllegalInAggregate(op.getName()));
      }
      break;
    default:
      throw new AssertionError(op);
    }

    // Because there are two forms of the PERCENTILE_CONT/PERCENTILE_DISC functions,
    // they are distinguished by their operand count and then validated accordingly.
    // For example, the standard single operand form requires group order while the
    // 2-operand form allows for null treatment and requires an OVER() clause.
    if (op.isPercentile()) {
      switch (aggCall.operandCount()) {
      case 1:
        assert op.requiresGroupOrder() == Optionality.MANDATORY;
        assert orderList != null;
        // Validate that percentile function have a single ORDER BY expression
        if (orderList.size() != 1) {
          throw newValidationError(orderList,
              RESOURCE.orderByRequiresOneKey(op.getName()));
        }
        // Validate that the ORDER BY field is of NUMERIC type
        SqlNode node = orderList.get(0);
        assert node != null;
        final RelDataType type = deriveType(scope, node);
        final @Nullable SqlTypeFamily family = type.getSqlTypeName().getFamily();
        if (family == null
            || family.allowableDifferenceTypes().isEmpty()) {
          throw newValidationError(orderList,
              RESOURCE.unsupportedTypeInOrderBy(
                  type.getSqlTypeName().getName(),
                  op.getName()));
        }
        break;
      case 2:
        assert op.allowsNullTreatment();
        assert op.requiresOver();
        assert op.requiresGroupOrder() == Optionality.FORBIDDEN;
        break;
      default:
        throw newValidationError(aggCall, RESOURCE.percentileFunctionsArgumentLimit());
      }
    }
  }

  @Override public void validateCall(
      SqlCall call,
      SqlValidatorScope scope) {
    final SqlOperator operator = call.getOperator();
    if ((call.operandCount() == 0)
        && (operator.getSyntax() == SqlSyntax.FUNCTION_ID)
        && !call.isExpanded()
        && !this.config.conformance().allowNiladicParentheses()) {
      // For example, "LOCALTIME()" is illegal. (It should be
      // "LOCALTIME", which would have been handled as a
      // SqlIdentifier.)
      throw handleUnresolvedFunction(call, operator,
          ImmutableList.of(), null);
    }

    SqlValidatorScope operandScope = scope.getOperandScope(call);

    if (operator instanceof SqlFunction
        && ((SqlFunction) operator).getFunctionType()
            == SqlFunctionCategory.MATCH_RECOGNIZE
        && !(operandScope instanceof MatchRecognizeScope)) {
      throw newValidationError(call,
          Static.RESOURCE.functionMatchRecognizeOnly(call.toString()));
    }
    // Delegate validation to the operator.
    operator.validateCall(call, this, scope, operandScope);
  }

  /**
   * Validates that a particular feature is enabled. By default, all features
   * are enabled; subclasses may override this method to be more
   * discriminating.
   *
   * @param feature feature being used, represented as a resource instance
   * @param context parser position context for error reporting, or null if
   */
  protected void validateFeature(
      Feature feature,
      SqlParserPos context) {
    // By default, do nothing except to verify that the resource
    // represents a real feature definition.
    assert feature.getProperties().get("FeatureDefinition") != null;
  }

  @Override public SqlLiteral resolveLiteral(SqlLiteral literal) {
    switch (literal.getTypeName()) {
    case UNKNOWN:
      final SqlUnknownLiteral unknownLiteral = (SqlUnknownLiteral) literal;
      final SqlIdentifier identifier =
          new SqlIdentifier(unknownLiteral.tag, SqlParserPos.ZERO);
      final @Nullable RelDataType type = catalogReader.getNamedType(identifier);
      final SqlTypeName typeName;
      if (type != null) {
        typeName = type.getSqlTypeName();
      } else {
        typeName = SqlTypeName.lookup(unknownLiteral.tag);
      }
      return unknownLiteral.resolve(typeName);

    default:
      return literal;
    }
  }
  // Apache Calcite 的验证阶段扮演着“表达式转换器”的角色。
  // 它的核心任务是对 SELECT 列表中的表达式进行改写，例如将简写的列名展开为全限定名（col -> table.col），或者处理一些特殊的内置函数
  public SqlNode expandSelectExpr(SqlNode expr, // 待处理的原始表达式节点。例子：用户输入的 deptno 或 deptno + 1。
      SelectScope scope,// 作用：当前 SELECT 语句的作用域。包含了 FROM 子句中定义的所有表信息。Expander（展开器）需要利用这个作用域来查找某个列名属于哪张表，从而实现列的全限定化。
      SqlSelect select) { // 作用：当前的 SELECT 语法树节点。
    // 继承自 SqlScopedShuttle。它本质上是一个访问者（Visitor），
    // 专门负责遍历 SqlNode 树并对特定节点进行替换。它持有 this（验证器本身）、scope 和 select 的引用，以便在遍历时进行查找和验证。
    final Expander expander = new SelectExpander(this, scope, select);
    // 列全限定化：如果 expr 是一个标识符（SqlIdentifier），展开器会去 scope 里找它的来源，将其改写为带表名前缀的形式。
    // 函数转换：处理一些特殊的 SQL 函数。
    // 子查询处理：如果表达式中包含标量子查询，也会在此处进行必要的预处理。
    final SqlNode newExpr = expander.go(expr);
    if (expr != newExpr) {
      setOriginal(newExpr, expr);
    }
    return newExpr;
  }

  @Override public SqlNode expand(SqlNode expr, SqlValidatorScope scope) {
    final Expander expander = new Expander(this, scope);
    SqlNode newExpr = expander.go(expr);
    if (expr != newExpr) {
      setOriginal(newExpr, expr);
    }
    return newExpr;
  }

  /** Expands an expression in a GROUP BY, HAVING or QUALIFY clause. */
  private SqlNode extendedExpand(SqlNode expr,
      SqlValidatorScope scope, SqlSelect select, Clause clause) {
    final Expander expander =
        new ExtendedExpander(this, scope, select, expr, clause);
    SqlNode newExpr = expander.go(expr);
    if (expr != newExpr) {
      setOriginal(newExpr, expr);
    }
    return newExpr;
  }

  public SqlNode extendedExpandGroupBy(SqlNode expr,
      SqlValidatorScope scope, SqlSelect select) {
    return extendedExpand(expr, scope, select, Clause.GROUP_BY);
  }

  @Override public boolean isSystemField(RelDataTypeField field) {
    return false;
  }

  @Override public List<@Nullable List<String>> getFieldOrigins(SqlNode sqlQuery) {
    if (sqlQuery instanceof SqlExplain) {
      return emptyList();
    }
    final RelDataType rowType = getValidatedNodeType(sqlQuery);
    final int fieldCount = rowType.getFieldCount();
    if (!sqlQuery.isA(SqlKind.QUERY)) {
      return Collections.nCopies(fieldCount, null);
    }
    final List<@Nullable List<String>> list = new ArrayList<>();
    for (int i = 0; i < fieldCount; i++) {
      list.add(getFieldOrigin(sqlQuery, i));
    }
    return ImmutableNullableList.copyOf(list);
  }

  private @Nullable List<String> getFieldOrigin(SqlNode sqlQuery, int i) {
    if (sqlQuery instanceof SqlSelect) {
      SqlSelect sqlSelect = (SqlSelect) sqlQuery;
      final SelectScope scope = getRawSelectScopeNonNull(sqlSelect);
      final List<SqlNode> selectList =
          requireNonNull(scope.getExpandedSelectList(),
              () -> "expandedSelectList for " + scope);
      final SqlNode selectItem = stripAs(selectList.get(i));
      if (selectItem instanceof SqlIdentifier) {
        final SqlQualified qualified =
            scope.fullyQualify((SqlIdentifier) selectItem);
        SqlValidatorNamespace namespace =
            requireNonNull(qualified.namespace,
                () -> "namespace for " + qualified);
        if (namespace.isWrapperFor(AliasNamespace.class)) {
          AliasNamespace aliasNs = namespace.unwrap(AliasNamespace.class);
          SqlNode aliased = requireNonNull(aliasNs.getNode(), () ->
              "sqlNode for aliasNs " + aliasNs);
          namespace = getNamespaceOrThrow(stripAs(aliased));
        }

        final SqlValidatorTable table = namespace.getTable();
        if (table == null) {
          return null;
        }
        final List<String> origin =
            new ArrayList<>(table.getQualifiedName());
        for (String name : qualified.suffix()) {
          if (namespace.isWrapperFor(UnnestNamespace.class)) {
            // If identifier is drawn from a repeated subrecord via unnest, add name of array field
            UnnestNamespace unnestNamespace = namespace.unwrap(UnnestNamespace.class);
            final SqlQualified columnUnnestedFrom = unnestNamespace.getColumnUnnestedFrom(name);
            if (columnUnnestedFrom != null) {
              origin.addAll(columnUnnestedFrom.suffix());
            }
          }
          namespace = namespace.lookupChild(name);

          if (namespace == null) {
            return null;
          }
          origin.add(name);
        }
        return origin;
      }
      return null;
    } else if (sqlQuery instanceof SqlOrderBy) {
      return getFieldOrigin(((SqlOrderBy) sqlQuery).query, i);
    } else {
      return null;
    }
  }

  @Override public RelDataType getParameterRowType(SqlNode sqlQuery) {
    // NOTE: We assume that bind variables occur in depth-first tree
    // traversal in the same order that they occurred in the SQL text.
    final List<RelDataType> types = new ArrayList<>();
    // NOTE: but parameters on fetch/offset would be counted twice
    // as they are counted in the SqlOrderBy call and the inner SqlSelect call
    final Set<SqlNode> alreadyVisited = new HashSet<>();
    sqlQuery.accept(
        new SqlShuttle() {

          @Override public SqlNode visit(SqlDynamicParam param) {
            if (alreadyVisited.add(param)) {
              RelDataType type = getValidatedNodeType(param);
              types.add(type);
            }
            return param;
          }
        });
    return typeFactory.createStructType(
        types,
        new AbstractList<String>() {
          @Override public String get(int index) {
            return "?" + index;
          }

          @Override public int size() {
            return types.size();
          }
        });
  }

  private static boolean isPhysicalNavigation(SqlKind kind) {
    return kind == SqlKind.PREV || kind == SqlKind.NEXT;
  }

  private static boolean isLogicalNavigation(SqlKind kind) {
    return kind == SqlKind.FIRST || kind == SqlKind.LAST;
  }

  private static boolean isAggregation(SqlKind kind) {
    return kind == SqlKind.SUM || kind == SqlKind.SUM0
        || kind == SqlKind.AVG || kind == SqlKind.COUNT
        || kind == SqlKind.MAX || kind == SqlKind.MIN;
  }

  private static boolean isRunningOrFinal(SqlKind kind) {
    return kind == SqlKind.RUNNING || kind == SqlKind.FINAL;
  }

  private static boolean isSingleVarRequired(SqlKind kind) {
    return isPhysicalNavigation(kind)
        || isLogicalNavigation(kind)
        || isAggregation(kind);
  }

  //~ Inner Classes ----------------------------------------------------------

  /**
   * Common base class for DML statement namespaces.
   */
  public static class DmlNamespace extends IdentifierNamespace {
    protected DmlNamespace(SqlValidatorImpl validator, SqlNode id,
        SqlNode enclosingNode, SqlValidatorScope parentScope) {
      super(validator, id, enclosingNode, parentScope);
    }
  }

  /**
   * Namespace for an INSERT statement.
   */
  private static class InsertNamespace extends DmlNamespace {
    private final SqlInsert node;

    InsertNamespace(SqlValidatorImpl validator, SqlInsert node,
        SqlNode enclosingNode, SqlValidatorScope parentScope) {
      super(validator, node.getTargetTable(), enclosingNode, parentScope);
      this.node = requireNonNull(node, "node");
    }

    @Override public @Nullable SqlNode getNode() {
      return node;
    }
  }

  /**
   * Namespace for an UPDATE statement.
   */
  private static class UpdateNamespace extends DmlNamespace {
    private final SqlUpdate node;

    UpdateNamespace(SqlValidatorImpl validator, SqlUpdate node,
        SqlNode enclosingNode, SqlValidatorScope parentScope) {
      super(validator, node.getTargetTable(), enclosingNode, parentScope);
      this.node = requireNonNull(node, "node");
    }

    @Override public @Nullable SqlNode getNode() {
      return node;
    }
  }

  /**
   * Namespace for a DELETE statement.
   */
  private static class DeleteNamespace extends DmlNamespace {
    private final SqlDelete node;

    DeleteNamespace(SqlValidatorImpl validator, SqlDelete node,
        SqlNode enclosingNode, SqlValidatorScope parentScope) {
      super(validator, node.getTargetTable(), enclosingNode, parentScope);
      this.node = requireNonNull(node, "node");
    }

    @Override public @Nullable SqlNode getNode() {
      return node;
    }
  }

  /**
   * Namespace for a MERGE statement.
   */
  private static class MergeNamespace extends DmlNamespace {
    private final SqlMerge node;

    MergeNamespace(SqlValidatorImpl validator, SqlMerge node,
        SqlNode enclosingNode, SqlValidatorScope parentScope) {
      super(validator, node.getTargetTable(), enclosingNode, parentScope);
      this.node = requireNonNull(node, "node");
    }

    @Override public @Nullable SqlNode getNode() {
      return node;
    }
  }

  /** Visitor that retrieves pattern variables defined. */
  private static class PatternVarVisitor implements SqlVisitor<Void> {
    private MatchRecognizeScope scope;
    PatternVarVisitor(MatchRecognizeScope scope) {
      this.scope = scope;
    }

    @Override public Void visit(SqlLiteral literal) {
      return null;
    }

    @Override public Void visit(SqlCall call) {
      for (int i = 0; i < call.getOperandList().size(); i++) {
        call.getOperandList().get(i).accept(this);
      }
      return null;
    }

    @Override public Void visit(SqlNodeList nodeList) {
      throw Util.needToImplement(nodeList);
    }

    @Override public Void visit(SqlIdentifier id) {
      checkArgument(id.isSimple());
      scope.addPatternVar(id.getSimple());
      return null;
    }

    @Override public Void visit(SqlDataTypeSpec type) {
      throw Util.needToImplement(type);
    }

    @Override public Void visit(SqlDynamicParam param) {
      throw Util.needToImplement(param);
    }

    @Override public Void visit(SqlIntervalQualifier intervalQualifier) {
      throw Util.needToImplement(intervalQualifier);
    }
  }

  /**
   * Visitor which derives the type of a given {@link SqlNode}.
   *
   * <p>Each method must return the derived type. This visitor is basically a
   * single-use dispatcher; the visit is never recursive.
   */
  private class DeriveTypeVisitor implements SqlVisitor<RelDataType> {
    private final SqlValidatorScope scope;

    DeriveTypeVisitor(SqlValidatorScope scope) {
      this.scope = scope;
    }

    @Override public RelDataType visit(SqlLiteral literal) {
      return resolveLiteral(literal).createSqlType(typeFactory);
    }

    @Override public RelDataType visit(SqlCall call) {
      final SqlOperator operator = call.getOperator();
      return operator.deriveType(SqlValidatorImpl.this, scope, call);
    }

    @Override public RelDataType visit(SqlNodeList nodeList) {
      // Operand is of a type that we can't derive a type for. If the
      // operand is of a peculiar type, such as a SqlNodeList, then you
      // should override the operator's validateCall() method so that it
      // doesn't try to validate that operand as an expression.
      throw Util.needToImplement(nodeList);
    }

    @Override public RelDataType visit(SqlIdentifier id) {
      // First check for builtin functions which don't have parentheses,
      // like "LOCALTIME".
      final SqlCall call = makeNullaryCall(id);
      if (call != null) {
        return call.getOperator().validateOperands(
            SqlValidatorImpl.this,
            scope,
            call);
      }

      RelDataType type = null;
      if (!(scope instanceof EmptyScope)) {
        id = scope.fullyQualify(id).identifier;
      }

      // Resolve the longest prefix of id that we can
      int i;
      for (i = id.names.size() - 1; i > 0; i--) {
        // REVIEW jvs 9-June-2005: The name resolution rules used
        // here are supposed to match SQL:2003 Part 2 Section 6.6
        // (identifier chain), but we don't currently have enough
        // information to get everything right.  In particular,
        // routine parameters are currently looked up via resolve;
        // we could do a better job if they were looked up via
        // resolveColumn.

        final SqlNameMatcher nameMatcher = catalogReader.nameMatcher();
        final SqlValidatorScope.ResolvedImpl resolved =
            new SqlValidatorScope.ResolvedImpl();
        scope.resolve(id.names.subList(0, i), nameMatcher, false, resolved);
        if (resolved.count() == 1) {
          // There's a namespace with the name we seek.
          final SqlValidatorScope.Resolve resolve = resolved.only();
          type = resolve.rowType();
          for (SqlValidatorScope.Step p : Util.skip(resolve.path.steps())) {
            type = type.getFieldList().get(p.i).getType();
          }
          break;
        }
      }

      // Give precedence to namespace found, unless there
      // are no more identifier components.
      if (type == null || id.names.size() == 1) {
        // See if there's a column with the name we seek in
        // precisely one of the namespaces in this scope.
        RelDataType colType = scope.resolveColumn(id.names.get(0), id);
        if (colType != null) {
          type = colType;
        }
        ++i;
      }

      if (type == null) {
        final SqlIdentifier last = id.getComponent(i - 1, i);
        throw newValidationError(last,
            RESOURCE.unknownIdentifier(last.toString()));
      }

      // Resolve rest of identifier
      for (; i < id.names.size(); i++) {
        String name = id.names.get(i);
        final RelDataTypeField field;
        if (name.equals("")) {
          // The wildcard "*" is represented as an empty name. It never
          // resolves to a field.
          name = "*";
          field = null;
        } else {
          final SqlNameMatcher nameMatcher = catalogReader.nameMatcher();
          field = nameMatcher.field(type, name);
        }
        if (field == null) {
          throw newValidationError(id.getComponent(i),
              RESOURCE.unknownField(name));
        }
        type = field.getType();
      }
      type =
          SqlTypeUtil.addCharsetAndCollation(
              type,
              getTypeFactory());
      return type;
    }

    @Override public RelDataType visit(SqlDataTypeSpec dataType) {
      // Q. How can a data type have a type?
      // A. When it appears in an expression. (Say as the 2nd arg to the
      //    CAST operator.)
      validateDataType(dataType);
      return dataType.deriveType(SqlValidatorImpl.this);
    }

    @Override public RelDataType visit(SqlDynamicParam param) {
      return unknownType;
    }

    @Override public RelDataType visit(SqlIntervalQualifier intervalQualifier) {
      return typeFactory.createSqlIntervalType(intervalQualifier);
    }
  }

  /**
   * Converts an expression into canonical form by fully-qualifying any
   * identifiers.
   */
  // 核心目标是将 SQL 表达式转换为规范形式（Canonical Form），
  // 最主要的操作就是将模糊的标识符（如 empno）重写为全限定标识符（如 EMP.EMPNO）。
  private static class Expander extends SqlScopedShuttle {
    // 持有所属验证器的引用
    protected final SqlValidatorImpl validator;
    // 初始化展开器，通过 super(scope) 将初始作用域压入 SqlScopedShuttle 的作用域栈中。
    Expander(SqlValidatorImpl validator, SqlValidatorScope scope) {
      super(scope);
      this.validator = validator;
    }
    // 启动展开流程。
    public SqlNode go(SqlNode root) {
      return requireNonNull(root.accept(this),
          () -> this + " returned null for " + root);
    }
    // 该类最重要的重写方法，实现了“全限定化”逻辑
    @Override public @Nullable SqlNode visit(SqlIdentifier id) {
      // First check for builtin functions which don't have
      // parentheses, like "LOCALTIME".
      // 处理像 LOCALTIME 或 CURRENT_DATE 这种不需要括号的内置函数。如果匹配，将其转换为 SqlCall 并继续遍历。
      final SqlCall call = validator.makeNullaryCall(id);
      if (call != null) {
        return call.accept(this);
      }
      // 利用当前的 Scope 查找 id 的来源，并返回带前缀的标识符（例如将 id 改写为 schema.table.column）。
      final SqlIdentifier fqId = getScope().fullyQualify(id).identifier;
      // 处理动态表与关联原始节点
      SqlNode expandedExpr = expandDynamicStar(id, fqId);
      validator.setOriginal(expandedExpr, id);
      return expandedExpr;
    }
    // 解析 SQL 字面量。
    @Override public @Nullable SqlNode visit(SqlLiteral literal) {
      return validator.resolveLiteral(literal);
    }
    // 定制化处理各种 SQL 调用。
    @Override protected SqlNode visitScoped(SqlCall call) {
      switch (call.getKind()) {
      // 针对 SCALAR_QUERY (标量子查询)、WITH 等类型，直接返回 call 不做处理。
      case SCALAR_QUERY:
      case CURRENT_VALUE:
      case NEXT_VALUE:
      case WITH:
      case LAMBDA:
        return call;
      default:
        break;
      }
      // Only visits arguments which are expressions. We don't want to
      // qualify non-expressions such as 'x' in 'empno * 5 AS x'.
      CallCopyingArgHandler argHandler =
          new CallCopyingArgHandler(call, false);
      call.getOperator().acceptCall(this, call, true, argHandler);
      final SqlNode result = argHandler.result();
      validator.setOriginal(result, call);
      return result;
    }
    // 处理 Schema-less（动态模式）表的列引用。
    protected SqlNode expandDynamicStar(SqlIdentifier id, SqlIdentifier fqId) {
      if (DynamicRecordType.isDynamicStarColName(Util.last(fqId.names))
          && !DynamicRecordType.isDynamicStarColName(Util.last(id.names))) {
        // Convert a column ref into ITEM(*, 'col_name')
        // for a dynamic star field in dynTable's rowType.
        return new SqlBasicCall(
            SqlStdOperatorTable.ITEM,
            ImmutableList.of(fqId,
                SqlLiteral.createCharString(Util.last(id.names),
                    id.getParserPosition())),
            id.getParserPosition());
      }
      return fqId;
    }
  }

  /**
   * Shuttle which walks over an expression in the ORDER BY clause, replacing
   * usages of aliases with the underlying expression.
   */
  class OrderExpressionExpander extends SqlScopedShuttle {
    private final List<String> aliasList;
    private final SqlSelect select;
    private final SqlNode root;

    OrderExpressionExpander(SqlSelect select, SqlNode root) {
      super(getOrderScope(select));
      this.select = select;
      this.root = root;
      this.aliasList = getNamespaceOrThrow(select).getRowType().getFieldNames();
    }

    public SqlNode go() {
      return requireNonNull(root.accept(this),
          () -> "OrderExpressionExpander returned null for " + root);
    }

    @Override public @Nullable SqlNode visit(SqlLiteral literal) {
      // Ordinal markers, e.g. 'select a, b from t order by 2'.
      // Only recognize them if they are the whole expression,
      // and if the dialect permits.
      if (literal == root && config.conformance().isSortByOrdinal()) {
        switch (literal.getTypeName()) {
        case DECIMAL:
        case DOUBLE:
          final int intValue = literal.intValue(false);
          if (intValue >= 0) {
            if (intValue < 1 || intValue > aliasList.size()) {
              throw newValidationError(
                  literal, RESOURCE.orderByOrdinalOutOfRange());
            }

            // SQL ordinals are 1-based, but Sort's are 0-based
            int ordinal = intValue - 1;
            return nthSelectItem(ordinal, literal.getParserPosition());
          }
          break;
        default:
          break;
        }
      }

      return super.visit(literal);
    }

    /**
     * Returns the <code>ordinal</code>th item in the select list.
     */
    private SqlNode nthSelectItem(int ordinal, final SqlParserPos pos) {
      // TODO: Don't expand the list every time. Maybe keep an expanded
      // version of each expression -- select lists and identifiers -- in
      // the validator.

      SqlNodeList expandedSelectList =
          expandStar(
              SqlNonNullableAccessors.getSelectList(select),
              select,
              false);
      SqlNode expr = expandedSelectList.get(ordinal);
      expr = stripAs(expr);
      if (expr instanceof SqlIdentifier) {
        expr = getScope().fullyQualify((SqlIdentifier) expr).identifier;
      }

      // Create a copy of the expression with the position of the order
      // item.
      return expr.clone(pos);
    }

    @Override public SqlNode visit(SqlIdentifier id) {
      // Aliases, e.g. 'select a as x, b from t order by x'.
      if (id.isSimple()
          && config.conformance().isSortByAlias()) {
        String alias = id.getSimple();
        final SqlValidatorNamespace selectNs = getNamespaceOrThrow(select);
        final RelDataType rowType =
            selectNs.getRowTypeSansSystemColumns();
        final SqlNameMatcher nameMatcher = catalogReader.nameMatcher();
        RelDataTypeField field = nameMatcher.field(rowType, alias);
        if (field != null) {
          return nthSelectItem(
              field.getIndex(),
              id.getParserPosition());
        }
      }

      // No match. Return identifier unchanged.
      return getScope().fullyQualify(id).identifier;
    }

    @Override protected @Nullable SqlNode visitScoped(SqlCall call) {
      // Don't attempt to expand sub-queries. We haven't implemented
      // these yet.
      if (call instanceof SqlSelect) {
        return call;
      }
      return super.visitScoped(call);
    }
  }

  /**
   * Converts an expression into canonical form by fully-qualifying any
   * identifiers. For common columns in USING, it will be converted to
   * COALESCE(A.col, B.col) AS col.
   */
  // 专门用于处理 SELECT 子句中的表达式展开，特别增加了一个关键的逻辑：处理 USING 子句或 NATURAL JOIN 产生的公共列（Common Columns）。
  // 在标准 SQL 中，当你使用 JOIN ... USING (col) 时，col 是属于两张表的。在 SELECT 列表中直接写 col 时，验证器需要将其展开为一种能够兼顾两张表数据的规范形式。
  // 根据该类的注释和代码，它会将公共列转换为如下形式：
  // col $\rightarrow$ COALESCE(tableA.col, tableB.col) AS col
  static class SelectExpander extends Expander {
    // 持有当前 SELECT 语句的引用。
    final SqlSelect select;

    SelectExpander(SqlValidatorImpl validator, SelectScope scope,
        SqlSelect select) {
      super(validator, scope);
      this.select = select;
    }
    // 重写了父类的标识符访问逻辑，通过“拦截”机制优先处理公共列
    @Override public @Nullable SqlNode visit(SqlIdentifier id) {
      // 1. 尝试将标识符作为公共列进行展开
      final SqlNode node =
          expandCommonColumn(select, id, (SelectScope) getScope(), validator);
      // 2. 判断是否发生了展开
      if (node != id) {
        // 如果 expandCommonColumn 返回了新节点（说明它是 USING/NATURAL JOIN 的公共列）
        // 此时 node 已经是类似 COALESCE(...) 的结构，直接返回
        return node;
      } else {
        // 3. 如果不是公共列，则退回到父类逻辑（执行全限定化，如 col -> table.col）
        return super.visit(id);
      }
    }
  }

  /**
   * Shuttle which walks over an expression in the GROUP BY/HAVING clause, replacing
   * usages of aliases or ordinals with the underlying expression.
   */
  static class ExtendedExpander extends Expander {
    final SqlSelect select;
    final SqlNode root;
    final Clause clause;

    ExtendedExpander(SqlValidatorImpl validator, SqlValidatorScope scope,
        SqlSelect select, SqlNode root, Clause clause) {
      super(validator, scope);
      this.select = select;
      this.root = root;
      this.clause = clause;
    }

    @Override public @Nullable SqlNode visit(SqlIdentifier id) {
      if (!id.isSimple()) {
        return super.visit(id);
      }

      final boolean replaceAliases = clause.shouldReplaceAliases(validator.config);
      if (!replaceAliases) {
        final SelectScope scope = validator.getRawSelectScopeNonNull(select);
        SqlNode node = expandCommonColumn(select, id, scope, validator);
        if (node != id) {
          return node;
        }
        return super.visit(id);
      }

      String name = id.getSimple();
      SqlNode expr = null;
      final SqlNameMatcher nameMatcher =
          validator.catalogReader.nameMatcher();
      int n = 0;
      for (SqlNode s : SqlNonNullableAccessors.getSelectList(select)) {
        final @Nullable String alias = SqlValidatorUtil.alias(s);
        if (alias != null && nameMatcher.matches(alias, name)) {
          expr = s;
          n++;
        }
      }

      if (n == 0) {
        return super.visit(id);
      } else if (n > 1) {
        // More than one column has this alias.
        throw validator.newValidationError(id,
            RESOURCE.columnAmbiguous(name));
      }
      Iterable<SqlCall> allAggList = validator.aggFinder.findAll(ImmutableList.of(root));
      for (SqlCall agg : allAggList) {
        if (clause == Clause.HAVING && containsIdentifier(agg, id)) {
          return super.visit(id);
        }
      }

      expr = stripAs(expr);
      if (expr instanceof SqlIdentifier) {
        SqlIdentifier sid = (SqlIdentifier) expr;
        final SqlIdentifier fqId = getScope().fullyQualify(sid).identifier;
        expr = expandDynamicStar(sid, fqId);
      }

      return expr;
    }

    @Override public @Nullable SqlNode visit(SqlLiteral literal) {
      if (clause != Clause.GROUP_BY
          || !validator.config().conformance().isGroupByOrdinal()) {
        return super.visit(literal);
      }
      boolean isOrdinalLiteral = literal == root;
      switch (root.getKind()) {
      case GROUPING_SETS:
      case ROLLUP:
      case CUBE:
        if (root instanceof SqlBasicCall) {
          List<SqlNode> operandList = ((SqlBasicCall) root).getOperandList();
          for (SqlNode node : operandList) {
            if (node.equals(literal)) {
              isOrdinalLiteral = true;
              break;
            }
          }
        }
        break;
      default:
        break;
      }
      if (isOrdinalLiteral) {
        switch (literal.getTypeName()) {
        case DECIMAL:
        case DOUBLE:
          final int intValue = literal.intValue(false);
          if (intValue >= 0) {
            if (intValue < 1 || intValue > SqlNonNullableAccessors.getSelectList(select).size()) {
              throw validator.newValidationError(literal,
                  RESOURCE.orderByOrdinalOutOfRange());
            }

            // SQL ordinals are 1-based, but Sort's are 0-based
            int ordinal = intValue - 1;
            return stripAs(SqlNonNullableAccessors.getSelectList(select)
                .get(ordinal));
          }
          break;
        default:
          break;
        }
      }

      return super.visit(literal);
    }

    /**
     * Returns whether a given node contains a {@link SqlIdentifier}.
     *
     * @param sqlNode a SqlNode
     * @param target a SqlIdentifier
     */
    private boolean containsIdentifier(SqlNode sqlNode, SqlIdentifier target) {
      try {
        SqlVisitor<Void> visitor =
            new SqlBasicVisitor<Void>() {
              @Override public Void visit(SqlIdentifier identifier) {
                if (identifier.equalsDeep(target, Litmus.IGNORE)) {
                  throw new Util.FoundOne(target);
                }
                return super.visit(identifier);
              }
            };
        sqlNode.accept(visitor);
        return false;
      } catch (Util.FoundOne e) {
        Util.swallow(e, null);
        return true;
      }
    }
  }


  /** Information about an identifier in a particular scope. */
  protected static class IdInfo {
    public final SqlValidatorScope scope;
    public final SqlIdentifier id;

    public IdInfo(SqlValidatorScope scope, SqlIdentifier id) {
      this.scope = scope;
      this.id = id;
    }
  }

  /**
   * Utility object used to maintain information about the parameters in a
   * function call.
   */
  // 主要作用是在解析和验证自定义函数（尤其是表函数 Table Functions）时，维护参数之间的关联信息。
  // 核心作用是管理函数调用中的复杂参数依赖关系。
  // 在高级 SQL 函数（如 Calcite 的表函数）中，参数不仅仅是简单的常量或列名，还可以是：
  // 游标参数 (Cursor Parameters)：一个完整的 SELECT 查询作为参数传入。
  // 列列表参数 (Column List Parameters)：一组列名，它们通常必须引用同一个函数调用中的某个游标参数。
  // 该类通过建立映射关系，确保验证器知道哪个 SELECT 语句对应哪个游标位置，以及哪些列列表属于哪个游标。
  protected static class FunctionParamInfo {
    /**
     * Maps a cursor (based on its position relative to other cursor
     * parameters within a function call) to the SELECT associated with the
     * cursor.
     */
    // 记录游标参数的位置与其对应的查询语句。
    // Key (Integer)：游标参数在函数参数列表中的相对位置（索引）。
    // Value (SqlSelect)：该游标对应的具体 SELECT 语法树节点。
    // 场景：当函数定义为 FUNC(CURSOR(SELECT ...), 'arg') 时，此 Map 会记录索引 0 对应后面的 SqlSelect 对象。
    public final Map<Integer, SqlSelect> cursorPosToSelectMap;

    /**
     * Maps a column list parameter to the parent cursor parameter it
     * references. The parameters are id'd by their names.
     */
    // 维护列列表参数与其所属游标参数之间的绑定关系。
    // Key (String)：列列表参数的名称。
    // Value (String)：该列列表所引用的父游标参数的名称。
    // 场景：在一些表函数中，你需要指定要处理的列，例如 SET_SEMANTICS_TABLE_FUNCTION(input_table, COLUMN_LIST(id, name))。验证器需要确保 (id, name) 这些列确实存在于 input_table 中。
    public final Map<String, String> columnListParamToParentCursorMap;

    public FunctionParamInfo() {
      cursorPosToSelectMap = new HashMap<>();
      columnListParamToParentCursorMap = new HashMap<>();
    }
  }

  /**
   * Modify the nodes in navigation function
   * such as FIRST, LAST, PREV AND NEXT.
   */
  private static class NavigationModifier extends SqlShuttle {
    public SqlNode go(SqlNode node) {
      return requireNonNull(node.accept(this),
          () -> "NavigationModifier returned for " + node);
    }
  }

  /**
   * Shuttle that expands navigation expressions in a MATCH_RECOGNIZE clause.
   *
   * <p>Examples:
   *
   * <ul>
   * <li>{@code PREV(A.price + A.amount)} &rarr;
   * {@code PREV(A.price) + PREV(A.amount)}
   *
   * <li>{@code FIRST(A.price * 2)} &rarr; {@code FIRST(A.PRICE) * 2}
   * </ul>
   */
  private static class NavigationExpander extends NavigationModifier {
    final @Nullable SqlOperator op;
    final @Nullable SqlNode offset;

    NavigationExpander() {
      this(null, null);
    }

    NavigationExpander(@Nullable SqlOperator operator, @Nullable SqlNode offset) {
      this.offset = offset;
      this.op = operator;
    }

    @Override public @Nullable SqlNode visit(SqlCall call) {
      SqlKind kind = call.getKind();
      List<SqlNode> operands = call.getOperandList();
      List<@Nullable SqlNode> newOperands = new ArrayList<>();

      if (call.getFunctionQuantifier() != null
          && call.getFunctionQuantifier().getValue() == SqlSelectKeyword.DISTINCT) {
        final SqlParserPos pos = call.getParserPosition();
        throw SqlUtil.newContextException(pos,
            Static.RESOURCE.functionQuantifierNotAllowed(call.toString()));
      }

      if (isLogicalNavigation(kind) || isPhysicalNavigation(kind)) {
        SqlNode inner = operands.get(0);
        SqlNode offset = operands.get(1);

        // merge two straight prev/next, update offset
        if (isPhysicalNavigation(kind)) {
          SqlKind innerKind = inner.getKind();
          if (isPhysicalNavigation(innerKind)) {
            List<SqlNode> innerOperands = ((SqlCall) inner).getOperandList();
            SqlNode innerOffset = innerOperands.get(1);
            SqlOperator newOperator = innerKind == kind
                ? SqlStdOperatorTable.PLUS : SqlStdOperatorTable.MINUS;
            offset =
              newOperator.createCall(SqlParserPos.ZERO, offset, innerOffset);
            inner =
              call.getOperator().createCall(SqlParserPos.ZERO,
                  innerOperands.get(0), offset);
          }
        }
        SqlNode newInnerNode =
            inner.accept(new NavigationExpander(call.getOperator(), offset));
        if (op != null) {
          newInnerNode =
              op.createCall(SqlParserPos.ZERO, newInnerNode, this.offset);
        }
        return newInnerNode;
      }

      if (operands.size() > 0) {
        for (SqlNode node : operands) {
          if (node != null) {
            SqlNode newNode = node.accept(new NavigationExpander());
            if (op != null) {
              newNode = op.createCall(SqlParserPos.ZERO, newNode, offset);
            }
            newOperands.add(newNode);
          } else {
            newOperands.add(null);
          }
        }
        return call.getOperator().createCall(SqlParserPos.ZERO, newOperands);
      } else {
        if (op == null) {
          return call;
        } else {
          return op.createCall(SqlParserPos.ZERO, call, offset);
        }
      }
    }

    @Override public SqlNode visit(SqlIdentifier id) {
      if (op == null) {
        return id;
      } else {
        return op.createCall(SqlParserPos.ZERO, id, offset);
      }
    }
  }

  /**
   * Shuttle that replaces {@code A as A.price > PREV(B.price)} with
   * {@code PREV(A.price, 0) > LAST(B.price, 0)}.
   *
   * <p>Replacing {@code A.price} with {@code PREV(A.price, 0)} makes the
   * implementation of
   * {@link RexVisitor#visitPatternFieldRef(RexPatternFieldRef)} more unified.
   * Otherwise, it's difficult to implement this method. If it returns the
   * specified field, then the navigation such as {@code PREV(A.price, 1)}
   * becomes impossible; if not, then comparisons such as
   * {@code A.price > PREV(A.price, 1)} become meaningless.
   */
  private static class NavigationReplacer extends NavigationModifier {
    private final String alpha;

    NavigationReplacer(String alpha) {
      this.alpha = alpha;
    }

    @Override public @Nullable SqlNode visit(SqlCall call) {
      SqlKind kind = call.getKind();
      if (isLogicalNavigation(kind)
          || isAggregation(kind)
          || isRunningOrFinal(kind)) {
        return call;
      }

      switch (kind) {
      case PREV:
        final List<SqlNode> operands = call.getOperandList();
        if (operands.get(0) instanceof SqlIdentifier) {
          String name = ((SqlIdentifier) operands.get(0)).names.get(0);
          return name.equals(alpha) ? call
              : SqlStdOperatorTable.LAST.createCall(SqlParserPos.ZERO, operands);
        }
        break;
      default:
        break;
      }
      return super.visit(call);
    }

    @Override public SqlNode visit(SqlIdentifier id) {
      if (id.isSimple()) {
        return id;
      }
      SqlOperator operator = id.names.get(0).equals(alpha)
          ? SqlStdOperatorTable.PREV : SqlStdOperatorTable.LAST;

      return operator.createCall(SqlParserPos.ZERO, id,
        SqlLiteral.createExactNumeric("0", SqlParserPos.ZERO));
    }
  }

  /** Validates that within one navigation function, the pattern var is the
   * same. */
  private class PatternValidator extends SqlBasicVisitor<@Nullable Set<String>> {
    private final boolean isMeasure;
    int firstLastCount;
    int prevNextCount;
    int aggregateCount;

    PatternValidator(boolean isMeasure) {
      this(isMeasure, 0, 0, 0);
    }

    PatternValidator(boolean isMeasure, int firstLastCount, int prevNextCount,
        int aggregateCount) {
      this.isMeasure = isMeasure;
      this.firstLastCount = firstLastCount;
      this.prevNextCount = prevNextCount;
      this.aggregateCount = aggregateCount;
    }

    @Override public Set<String> visit(SqlCall call) {
      boolean isSingle = false;
      Set<String> vars = new HashSet<>();
      SqlKind kind = call.getKind();
      List<SqlNode> operands = call.getOperandList();

      if (isSingleVarRequired(kind)) {
        isSingle = true;
        if (isPhysicalNavigation(kind)) {
          if (isMeasure) {
            throw newValidationError(call,
                Static.RESOURCE.patternPrevFunctionInMeasure(call.toString()));
          }
          if (firstLastCount != 0) {
            throw newValidationError(call,
                Static.RESOURCE.patternPrevFunctionOrder(call.toString()));
          }
          prevNextCount++;
        } else if (isLogicalNavigation(kind)) {
          if (firstLastCount != 0) {
            throw newValidationError(call,
                Static.RESOURCE.patternPrevFunctionOrder(call.toString()));
          }
          firstLastCount++;
        } else if (isAggregation(kind)) {
          // cannot apply aggregation in PREV/NEXT, FIRST/LAST
          if (firstLastCount != 0 || prevNextCount != 0) {
            throw newValidationError(call,
                Static.RESOURCE.patternAggregationInNavigation(call.toString()));
          }
          if (kind == SqlKind.COUNT && call.getOperandList().size() > 1) {
            throw newValidationError(call,
                Static.RESOURCE.patternCountFunctionArg());
          }
          aggregateCount++;
        }
      }

      if (isRunningOrFinal(kind) && !isMeasure) {
        throw newValidationError(call,
            Static.RESOURCE.patternRunningFunctionInDefine(call.toString()));
      }

      for (SqlNode node : operands) {
        if (node != null) {
          vars.addAll(
              requireNonNull(
                  node.accept(
                      new PatternValidator(isMeasure, firstLastCount, prevNextCount,
                          aggregateCount)),
                  () -> "node.accept(PatternValidator) for node " + node));
        }
      }

      if (isSingle) {
        switch (kind) {
        case COUNT:
          if (vars.size() > 1) {
            throw newValidationError(call,
                Static.RESOURCE.patternCountFunctionArg());
          }
          break;
        default:
          if (operands.size() == 0
              || !(operands.get(0) instanceof SqlCall)
              || ((SqlCall) operands.get(0)).getOperator() != SqlStdOperatorTable.CLASSIFIER) {
            if (vars.isEmpty()) {
              throw newValidationError(call,
                  Static.RESOURCE.patternFunctionNullCheck(call.toString()));
            }
            if (vars.size() != 1) {
              throw newValidationError(call,
                  Static.RESOURCE.patternFunctionVariableCheck(call.toString()));
            }
          }
          break;
        }
      }
      return vars;
    }

    @Override public Set<String> visit(SqlIdentifier identifier) {
      boolean check = prevNextCount > 0 || firstLastCount > 0 || aggregateCount > 0;
      Set<String> vars = new HashSet<>();
      if (identifier.names.size() > 1 && check) {
        vars.add(identifier.names.get(0));
      }
      return vars;
    }

    @Override public Set<String> visit(SqlLiteral literal) {
      return ImmutableSet.of();
    }

    @Override public Set<String> visit(SqlIntervalQualifier qualifier) {
      return ImmutableSet.of();
    }

    @Override public Set<String> visit(SqlDataTypeSpec type) {
      return ImmutableSet.of();
    }

    @Override public Set<String> visit(SqlDynamicParam param) {
      return ImmutableSet.of();
    }
  }

  /** Permutation of fields in NATURAL JOIN or USING. */
  private class Permute {
    final List<ImmutableIntList> sources;
    final RelDataType rowType;
    final boolean trivial;
    final int offset;

    Permute(SqlNode from, int offset) {
      this.offset = offset;
      switch (from.getKind()) {
      case JOIN:
        final SqlJoin join = (SqlJoin) from;
        final Permute left = new Permute(join.getLeft(), offset);
        final int fieldCount =
            getValidatedNodeType(join.getLeft()).getFieldList().size();
        final Permute right =
            new Permute(join.getRight(), offset + fieldCount);
        final List<String> names = usingNames(join);
        final List<ImmutableIntList> sources = new ArrayList<>();
        final Set<ImmutableIntList> sourceSet = new HashSet<>();
        final RelDataTypeFactory.Builder b = typeFactory.builder();
        if (names != null) {
          for (String name : names) {
            final RelDataTypeField f = left.field(name);
            final ImmutableIntList source = left.sources.get(f.getIndex());
            sourceSet.add(source);
            final RelDataTypeField f2 = right.field(name);
            final ImmutableIntList source2 = right.sources.get(f2.getIndex());
            sourceSet.add(source2);
            sources.add(source.appendAll(source2));
            final boolean nullable =
                (f.getType().isNullable()
                    || join.getJoinType().generatesNullsOnLeft())
                && (f2.getType().isNullable()
                    || join.getJoinType().generatesNullsOnRight());
            b.add(f).nullable(nullable);
          }
        }
        for (RelDataTypeField f : left.rowType.getFieldList()) {
          final ImmutableIntList source = left.sources.get(f.getIndex());
          if (sourceSet.add(source)) {
            sources.add(source);
            b.add(f);
          }
        }
        for (RelDataTypeField f : right.rowType.getFieldList()) {
          final ImmutableIntList source = right.sources.get(f.getIndex());
          if (sourceSet.add(source)) {
            sources.add(source);
            b.add(f);
          }
        }
        rowType = b.build();
        this.sources = ImmutableList.copyOf(sources);
        this.trivial = left.trivial
            && right.trivial
            && (names == null || names.isEmpty());
        break;

      default:
        rowType = getValidatedNodeType(from);
        this.sources =
            Functions.generate(rowType.getFieldCount(),
                i -> ImmutableIntList.of(offset + i));
        this.trivial = true;
      }
    }

    private RelDataTypeField field(String name) {
      RelDataTypeField field = catalogReader.nameMatcher().field(rowType, name);
      assert field != null : "field " + name + " was not found in " + rowType;
      return field;
    }

    /** Moves fields according to the permutation. */
    void permute(List<SqlNode> selectItems,
        PairList<String, RelDataType> fields) {
      if (trivial) {
        return;
      }

      final List<SqlNode> oldSelectItems = ImmutableList.copyOf(selectItems);
      selectItems.clear();
      selectItems.addAll(oldSelectItems.subList(0, offset));
      final PairList<String, RelDataType> oldFields = fields.immutable();
      fields.clear();
      fields.addAll(oldFields.subList(0, offset));
      for (ImmutableIntList source : sources) {
        final int p0 = source.get(0);
        Map.Entry<String, RelDataType> field = oldFields.get(p0);
        final String name = field.getKey();
        RelDataType type = field.getValue();
        SqlNode selectItem = oldSelectItems.get(p0);
        for (int p1 : Util.skip(source)) {
          final Map.Entry<String, RelDataType> field1 = oldFields.get(p1);
          final SqlNode selectItem1 = oldSelectItems.get(p1);
          final RelDataType type1 = field1.getValue();
          // output is nullable only if both inputs are
          final boolean nullable = type.isNullable() && type1.isNullable();
          RelDataType currentType = type;
          final RelDataType type2 =
              requireNonNull(
                  SqlTypeUtil.leastRestrictiveForComparison(typeFactory, type,
                      type1),
                  () -> "leastRestrictiveForComparison for types " + currentType
                      + " and " + type1);
          selectItem =
              SqlStdOperatorTable.AS.createCall(SqlParserPos.ZERO,
                  SqlStdOperatorTable.COALESCE.createCall(SqlParserPos.ZERO,
                      maybeCast(selectItem, type, type2),
                      maybeCast(selectItem1, type1, type2)),
                  new SqlIdentifier(name, SqlParserPos.ZERO));
          type = typeFactory.createTypeWithNullability(type2, nullable);
        }
        fields.add(name, type);
        selectItems.add(selectItem);
      }
    }
  }

  //~ Enums ------------------------------------------------------------------

  /**
   * Validation status.
   */
  public enum Status {
    /**
     * Validation has not started for this scope.
     */
    UNVALIDATED,

    /**
     * Validation is in progress for this scope.
     */
    IN_PROGRESS,

    /**
     * Validation has completed (perhaps unsuccessfully).
     */
    VALID
  }

  /** Allows {@link #clauseScopes} to have multiple values per SELECT. */
  // 主要用于管理 SELECT 语句中不同子句的范围（Scope） 以及 别名（Alias）的替换策略
  // Clause 枚举的主要作用是：
  // 标识上下文：在处理 SELECT 语句时，标记当前验证器正处于哪个子句中（如 WHERE 或 GROUP BY）。
  // 控制别名展开（Alias Expansion）：定义在特定的子句中，是否应该将“列别名”替换为其实际的“原始表达式”。
  // 例如，在某些数据库配置下，GROUP BY alias 是不允许的，必须将其重写为 GROUP BY original_expression。这个类负责判定是否需要执行这种转换。
  private enum Clause {
    WHERE, // 对应 WHERE 子句。
    GROUP_BY, // 对应 GROUP_BY 子句。
    SELECT, // 对应 SELECT 投影列表。
    ORDER, // 对应 ORDER BY 子句。
    CURSOR, // 用于处理 CURSOR 表达式（Calcite 特有的流式或多行处理概念）。
    HAVING, // 对应 HAVING 过滤子句。
    QUALIFY; // 对应 QUALIFY 子句（通常用于在执行窗口函数后进行过滤，类似于 HAVING 之于 GROUP BY）。

    /**
     * Determines if the extender should replace aliases with expanded values.
     * For example:
     *
     * <blockquote><pre>{@code
     * SELECT a + a as twoA
     * GROUP BY twoA
     * }</pre></blockquote>
     *
     * <p>turns into
     *
     * <blockquote><pre>{@code
     * SELECT a + a as twoA
     * GROUP BY a + a
     * }</pre></blockquote>
     *
     * <p>This is determined both by the clause and the config.
     *
     * @param config The configuration
     * @return Whether we should replace the alias with its expanded value
     */
    // 决定在当前的子句上下文中，是否需要将别名（Alias）替换为展开后的原始值。
    // 参数：Config config。Calcite 的配置对象，包含了 SqlConformance（SQL 兼容性标准），因为不同的 SQL 方言对别名的支持不同。
    boolean shouldReplaceAliases(Config config) {
      switch (this) {
      case GROUP_BY:
        return config.conformance().isGroupByAlias();

      case HAVING:
        return config.conformance().isHavingAlias();

      case QUALIFY:
        return true;

      default:
        throw Util.unexpected(this);
      }
    }
  }
}
