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
package org.apache.calcite.adapter.enumerable;

import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.util.ByteString;
import org.apache.calcite.avatica.util.DateTimeUtils;
import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.linq4j.function.Function1;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.BlockStatement;
import org.apache.calcite.linq4j.tree.CatchBlock;
import org.apache.calcite.linq4j.tree.ConstantExpression;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.linq4j.tree.Primitive;
import org.apache.calcite.linq4j.tree.Statement;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexCallBinding;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLambda;
import org.apache.calcite.rex.RexLambdaRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexLocalRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexPatternFieldRef;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.rex.RexRangeRef;
import org.apache.calcite.rex.RexSubQuery;
import org.apache.calcite.rex.RexTableInputRef;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.rex.RexVisitor;
import org.apache.calcite.runtime.SpatialTypeFunctions;
import org.apache.calcite.schema.FunctionContext;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlWindowTableFunction;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.ControlFlowException;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.locationtech.jts.geom.Geometry;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.apache.calcite.linq4j.tree.Expressions.constant;
import static org.apache.calcite.sql.fun.SqlLibraryOperators.TRANSLATE3;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.CASE;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.CHAR_LENGTH;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.OCTET_LENGTH;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.PREV;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.SEARCH;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.SUBSTRING;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.UPPER;

import static java.util.Objects.requireNonNull;

/**
 * Translates {@link org.apache.calcite.rex.RexNode REX expressions} to
 * {@link Expression linq4j expressions}.
 */
// RexToLixTranslator 是 Apache Calcite Enumerable 适配器中的核心组件。
// 它的名字揭示了它的使命：将 Rex（Row Expressions，关系表达式）转换为 Lix（Linq4j Expressions，Java 表达式树）。
// 简单来说，它是 Calcite 的“代码生成器入口”，负责把 SQL 逻辑（如 a + b）翻译成能够在 JVM 上运行的 Java 代码（如 aValue + bValue）。
// 在 Calcite 的查询优化流程中，最后一步通常是将逻辑执行计划转化为物理执行代码。RexToLixTranslator 的作用在于：
// 语义转换：将抽象的 RexNode 节点树遍历并翻译成 Linq4j 的 Expression 结构。
// 处理三值逻辑：自动处理 SQL 中的 NULL 逻辑（通过之前提到的 Result 类中的双变量模式）。
// 类型映射：将 SQL 数据类型映射为对应的 Java 类型（例如 VARCHAR 转 String）。
// 算子落地：调用 RexImpTable 来寻找每个 SQL 算子（如 +, CASE, CAST）对应的 Java 实现代码。
// 为什么没有实现visitSubQuery等方法
// RexNode（Row Expressions）处理的是行级运算（如 a + b，CASE WHEN）。RelNode（Relational Expressions）处理的是集合级运算（如 Join，Filter，Project）。
// 子查询（SubQuery）在本质上是一个集合运算。 当 SQL 中出现 WHERE x IN (SELECT ...) 时，虽然它在形式上嵌套在表达式里，但逻辑上它代表了一个完整的关系代数算子树。RexToLixTranslator 的定位是“标量翻译器”，它只负责把简单的 Java 表达式翻译出来，而没有能力去驱动一整套关系算子的执行引擎。
// 2. 预处理阶段：子查询的“去相关化”（Decorrelation）
// Calcite 在将逻辑计划（Logical Plan）转换为可执行代码之前，会通过一套名为 SubQueryRemoveRule 或 Decorrelate 的优化规则，将 RexSubQuery 节点从表达式树中剔除。
// IN / EXISTS 通常会被重写为 Semi-Join 或 Anti-Join。
// 标量子查询（Scalar SubQuery）通常会被重写为 Outer Join。
// 等到进入代码生成（Code Generation）阶段时，原本的 RexSubQuery 节点已经转化成了物理算子树中的一部分（通常是 EnumerableJoin 或 EnumerableCorrelate）。因此，RexToLixTranslator 在运行时根本不应该遇到这些节点。
public class RexToLixTranslator implements RexVisitor<RexToLixTranslator.Result> {
  // 建立 Java 内置方法（如 String.toUpperCase）与 SQL 算子（UPPER）之间的映射关系。
  public static final Map<Method, SqlOperator> JAVA_TO_SQL_METHOD_MAP =
      ImmutableMap.<Method, SqlOperator>builder()
          .put(BuiltInMethod.STRING_TO_UPPER.method, UPPER)
          .put(BuiltInMethod.SUBSTRING.method, SUBSTRING)
          .put(BuiltInMethod.OCTET_LENGTH.method, OCTET_LENGTH)
          .put(BuiltInMethod.CHAR_LENGTH.method, CHAR_LENGTH)
          .put(BuiltInMethod.TRANSLATE3.method, TRANSLATE3)
          .build();
  // 用于在翻译过程中处理 SQL 类型与 Java 类型之间的转换逻辑。
  final JavaTypeFactory typeFactory;
  // 用于在必要时构建辅助的 Rex 节点。
  final RexBuilder builder;
  // 代表一组待翻译的表达式序列，通常包含 Project（投影）和 Filter（过滤）逻辑。
  private final @Nullable RexProgram program;
  final SqlConformance conformance;
  // 代表生成的 Java 代码中的 DataContext 根对象，通过它可以访问运行时变量（如 ? 参数）。
  private final Expression root;
  // 核心组件，用于告诉翻译器“如何获取输入列的数据”（例如从一个 Object[] 数组中按索引取值）
  final RexToLixTranslator.@Nullable InputGetter inputGetter;
  // 代码收集器。翻译过程中生成的每一行 Java 变量声明和赋值语句都会存放在这里。
  private final BlockBuilder list;
  // 用于存放生成的静态成员或常量定义。
  private final @Nullable BlockBuilder staticList;
  // 用于处理相关子查询（Correlated Subquery）中的变量引用。
  private final @Nullable Function1<String, InputGetter> correlates;

  /**
   * Map from RexLiteral's variable name to its literal, which is often a
   * ({@link org.apache.calcite.linq4j.tree.ConstantExpression}))
   * It is used in the some {@code RexCall}'s implementors, such as
   * {@code ExtractImplementor}.
   *
   * @see #getLiteral
   * @see #getLiteralValue
   */
  // 缓存机制：记录已经翻译过的表达式。如果同一个 RexNode 被引用多次，翻译器会直接返回之前的变量引用，避免生成重复的 Java 代码。
  private final Map<Expression, Expression> literalMap = new HashMap<>();

  /** For {@code RexCall}, keep the list of its operand's {@code Result}.
   * It is useful when creating a {@code CallImplementor}. */
  private final Map<RexCall, List<Result>> callOperandResultMap =
      new HashMap<>();

  /** Map from RexNode under specific storage type to its Result, to avoid
   * generating duplicate code. For {@code RexInputRef}, {@code RexDynamicParam}
   * and {@code RexFieldAccess}. */
  // 缓存已经生成的代码片段。
  private final Map<Pair<RexNode, @Nullable Type>, Result> rexWithStorageTypeResultMap =
      new HashMap<>();

  /** Map from RexNode to its Result, to avoid generating duplicate code.
   * For {@code RexLiteral} and {@code RexCall}. */
  // 缓存机制：记录已经翻译过的表达式。如果同一个 RexNode 被引用多次，翻译器会直接返回之前的变量引用，避免生成重复的 Java 代码。
  private final Map<RexNode, Result> rexResultMap = new HashMap<>();
  // 期望的 Java 存储类型
  private @Nullable Type currentStorageType;

  private RexToLixTranslator(@Nullable RexProgram program,
      JavaTypeFactory typeFactory,
      Expression root,
      @Nullable InputGetter inputGetter,
      BlockBuilder list,
      @Nullable BlockBuilder staticList,
      RexBuilder builder,
      SqlConformance conformance,
      @Nullable Function1<String, InputGetter> correlates) {
    this.program = program; // may be null
    this.typeFactory = requireNonNull(typeFactory, "typeFactory");
    this.conformance = requireNonNull(conformance, "conformance");
    this.root = requireNonNull(root, "root");
    this.inputGetter = inputGetter;
    this.list = requireNonNull(list, "list");
    this.staticList = staticList;
    this.builder = requireNonNull(builder, "builder");
    this.correlates = correlates; // may be null
  }

  /**
   * Translates a {@link RexProgram} to a sequence of expressions and
   * declarations.
   *
   * @param program Program to be translated
   * @param typeFactory Type factory
   * @param conformance SQL conformance
   * @param list List of statements, populated with declarations
   * @param staticList List of member declarations
   * @param outputPhysType Output type, or null
   * @param root Root expression
   * @param inputGetter Generates expressions for inputs
   * @param correlates Provider of references to the values of correlated
   *                   variables
   * @return Sequence of expressions, optional condition
   */
  public static List<Expression> translateProjects(RexProgram program,
      JavaTypeFactory typeFactory, SqlConformance conformance,
      BlockBuilder list, @Nullable BlockBuilder staticList,
      @Nullable PhysType outputPhysType, Expression root,
      InputGetter inputGetter, @Nullable Function1<String, InputGetter> correlates) {
    List<Type> storageTypes = null;
    if (outputPhysType != null) {
      final RelDataType rowType = outputPhysType.getRowType();
      storageTypes = new ArrayList<>(rowType.getFieldCount());
      for (int i = 0; i < rowType.getFieldCount(); i++) {
        storageTypes.add(outputPhysType.getJavaFieldType(i));
      }
    }
    return new RexToLixTranslator(program, typeFactory, root, inputGetter,
        list, staticList, new RexBuilder(typeFactory), conformance,  null)
        .setCorrelates(correlates)
        .translateList(program.getProjectList(), storageTypes);
  }

  @Deprecated // to be removed before 2.0
  public static List<Expression> translateProjects(RexProgram program,
      JavaTypeFactory typeFactory, SqlConformance conformance,
      BlockBuilder list, @Nullable PhysType outputPhysType, Expression root,
      InputGetter inputGetter, @Nullable Function1<String, InputGetter> correlates) {
    return translateProjects(program, typeFactory, conformance, list, null,
        outputPhysType, root, inputGetter, correlates);
  }

  public static Expression translateTableFunction(JavaTypeFactory typeFactory,
      SqlConformance conformance, BlockBuilder list,
      Expression root, RexCall rexCall, Expression inputEnumerable,
      PhysType inputPhysType, PhysType outputPhysType) {
    final RexToLixTranslator translator =
        new RexToLixTranslator(null, typeFactory, root, null, list,
            null, new RexBuilder(typeFactory), conformance, null);
    return translator
        .translateTableFunction(rexCall, inputEnumerable, inputPhysType,
            outputPhysType);
  }

  /** Creates a translator for translating aggregate functions. */
  public static RexToLixTranslator forAggregation(JavaTypeFactory typeFactory,
      BlockBuilder list, @Nullable InputGetter inputGetter,
      SqlConformance conformance) {
    final ParameterExpression root = DataContext.ROOT;
    return new RexToLixTranslator(null, typeFactory, root, inputGetter, list,
        null, new RexBuilder(typeFactory), conformance, null);
  }
  // 负责将 RexNode（逻辑表达式）转换为 Expression（Java 表达式）。它体现了 Calcite 如何将 SQL 的“空值敏感性”自动映射到 Java 语言。
  // 根据表达式是否可能为空，选择最合适的 NullAs 策略。
  // isNullable(expr)：检查当前的逻辑节点在 SQL 语义中是否允许为 NULL。
  // 如果表达式不可为空（Nullable = false），它通常返回 NullAs.NOT_POSSIBLE。这意味着在后续翻译中，可以生成更精简的 Java 代码（例如直接用基本类型 int 而不是包装类型 Integer）。
  Expression translate(RexNode expr) {
    final RexImpTable.NullAs nullAs =
        RexImpTable.NullAs.of(isNullable(expr));
    return translate(expr, nullAs);
  }

  Expression translate(RexNode expr, RexImpTable.NullAs nullAs) {
    return translate(expr, nullAs, null);
  }

  Expression translate(RexNode expr, @Nullable Type storageType) {
    final RexImpTable.NullAs nullAs =
        RexImpTable.NullAs.of(isNullable(expr));
    return translate(expr, nullAs, storageType);
  }
  // 决定了逻辑节点最终如何变成一个可执行的 Java 表达式。
  Expression translate(RexNode expr, RexImpTable.NullAs nullAs,
      @Nullable Type storageType) {
    // 将当前翻译任务期望的 Java 物理类型（如 int.class 或 Integer.class）存入全局状态。
    currentStorageType = storageType;
    // 触发递归翻译。
    final Result result = expr.accept(this);
    // 将翻译得到的变量强制转换为目标 storageType。
    final Expression translated =
        requireNonNull(EnumUtils.toInternal(result.valueVariable, storageType));
    // When we asked for not null input that would be stored as box, avoid unboxing
    // 如果 nullAs 是 NOT_POSSIBLE，说明调用方确定此处不会为 null。
    if (RexImpTable.NullAs.NOT_POSSIBLE == nullAs
        && translated.type.equals(storageType)) {
      return translated;
    }
    return nullAs.handle(translated);
  }

  /**
   * Used for safe operators that return null if an exception is thrown.
   */
  private Expression expressionHandlingSafe(
      Expression body, boolean safe, RelDataType targetType) {
    return safe ? safeExpression(body, targetType) : body;
  }

  private Expression safeExpression(Expression body, RelDataType targetType) {
    final ParameterExpression e_ =
        Expressions.parameter(Exception.class, new BlockBuilder().newName("e"));

    // The type received for the targetType is never nullable.
    // But safe casts may return null
    RelDataType nullableTargetType = typeFactory.createTypeWithNullability(targetType, true);
    Expression result =
        Expressions.call(
            Expressions.lambda(
                Expressions.block(
                    Expressions.tryCatch(
                        Expressions.return_(null, body),
                        Expressions.catch_(e_,
                            Expressions.return_(null, constant(null)))))),
            BuiltInMethod.FUNCTION0_APPLY.method);
    // FUNCTION0 always returns Object, so we need a cast to the target type
    return EnumUtils.convert(result, typeFactory.getJavaClass(nullableTargetType));
  }

  Expression translateCast(
      RelDataType sourceType,
      RelDataType targetType,
      Expression operand,
      boolean safe,
      ConstantExpression format) {
    Expression convert = getConvertExpression(sourceType, targetType, operand, format);
    Expression convert2 = checkExpressionPadTruncate(convert, sourceType, targetType);
    Expression convert3 = expressionHandlingSafe(convert2, safe, targetType);
    return scaleValue(sourceType, targetType, convert3);
  }

  private Expression getConvertExpression(
      RelDataType sourceType,
      RelDataType targetType,
      Expression operand,
      ConstantExpression format) {
    final Supplier<Expression> defaultExpression = () ->
        EnumUtils.convert(operand, typeFactory.getJavaClass(targetType));

    switch (targetType.getSqlTypeName()) {
    case ANY:
      return operand;

    case VARBINARY:
    case BINARY:
      switch (sourceType.getSqlTypeName()) {
      case CHAR:
      case VARCHAR:
        return Expressions.call(BuiltInMethod.STRING_TO_BINARY.method, operand,
            new ConstantExpression(Charset.class, sourceType.getCharset()));

      default:
        return defaultExpression.get();
      }

    case GEOMETRY:
      switch (sourceType.getSqlTypeName()) {
      case CHAR:
      case VARCHAR:
        return Expressions.call(BuiltInMethod.ST_GEOM_FROM_EWKT.method, operand);

      default:
        return defaultExpression.get();
      }

    case DATE:
      return translateCastToDate(sourceType, operand, format, defaultExpression);

    case TIME:
      return translateCastToTime(sourceType, operand, format, defaultExpression);

    case TIME_WITH_LOCAL_TIME_ZONE:
      return translateCastToTimeWithLocalTimeZone(sourceType, operand, defaultExpression);

    case TIMESTAMP:
      return translateCastToTimestamp(sourceType, operand, format, defaultExpression);

    case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
      return translateCastToTimestampWithLocalTimeZone(sourceType, operand, defaultExpression);

    case BOOLEAN:
      switch (sourceType.getSqlTypeName()) {
      case CHAR:
      case VARCHAR:
        return Expressions.call(BuiltInMethod.STRING_TO_BOOLEAN.method, operand);

      default:
        return defaultExpression.get();
      }

    case CHAR:
    case VARCHAR:
      final SqlIntervalQualifier interval =
          sourceType.getIntervalQualifier();
      switch (sourceType.getSqlTypeName()) {
      // If format string is supplied, return formatted date/time/timestamp
      case DATE:
        return RexImpTable.optimize2(operand, Expressions.isConstantNull(format)
            ? Expressions.call(BuiltInMethod.UNIX_DATE_TO_STRING.method, operand)
            : Expressions.call(
                Expressions.new_(
                    BuiltInMethod.FORMAT_DATE.method.getDeclaringClass()),
                BuiltInMethod.FORMAT_DATE.method, format, operand));

      case TIME:
        return RexImpTable.optimize2(operand, Expressions.isConstantNull(format)
            ? Expressions.call(BuiltInMethod.UNIX_TIME_TO_STRING.method, operand)
            : Expressions.call(
                Expressions.new_(
                    BuiltInMethod.FORMAT_TIME.method.getDeclaringClass()),
                BuiltInMethod.FORMAT_TIME.method, format, operand));

      case TIME_WITH_LOCAL_TIME_ZONE:
        return RexImpTable.optimize2(operand, Expressions.isConstantNull(format)
            ? Expressions.call(BuiltInMethod.TIME_WITH_LOCAL_TIME_ZONE_TO_STRING.method, operand,
            Expressions.call(BuiltInMethod.TIME_ZONE.method, root))
            : Expressions.call(
                Expressions.new_(
                    BuiltInMethod.FORMAT_TIME.method.getDeclaringClass()),
                BuiltInMethod.FORMAT_TIME.method, format, operand));

      case TIMESTAMP:
        return RexImpTable.optimize2(operand, Expressions.isConstantNull(format)
            ? Expressions.call(BuiltInMethod.UNIX_TIMESTAMP_TO_STRING.method, operand)
            : Expressions.call(
                Expressions.new_(
                    BuiltInMethod.FORMAT_TIMESTAMP.method.getDeclaringClass()),
                BuiltInMethod.FORMAT_TIMESTAMP.method, format, operand));

      case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
        return RexImpTable.optimize2(operand, Expressions.isConstantNull(format)
            ? Expressions.call(BuiltInMethod.TIMESTAMP_WITH_LOCAL_TIME_ZONE_TO_STRING.method,
            operand, Expressions.call(BuiltInMethod.TIME_ZONE.method, root))
            : Expressions.call(
                Expressions.new_(
                    BuiltInMethod.FORMAT_TIMESTAMP.method.getDeclaringClass()),
                BuiltInMethod.FORMAT_TIMESTAMP.method, format, operand));

      case INTERVAL_YEAR:
      case INTERVAL_YEAR_MONTH:
      case INTERVAL_MONTH:
        return RexImpTable.optimize2(operand,
            Expressions.call(BuiltInMethod.INTERVAL_YEAR_MONTH_TO_STRING.method,
                operand,
                Expressions.constant(
                    requireNonNull(interval, "interval").timeUnitRange)));

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
        return RexImpTable.optimize2(operand,
            Expressions.call(BuiltInMethod.INTERVAL_DAY_TIME_TO_STRING.method,
                operand,
                Expressions.constant(
                    requireNonNull(interval, "interval").timeUnitRange),
                Expressions.constant(
                    interval.getFractionalSecondPrecision(
                        typeFactory.getTypeSystem()))));

      case BOOLEAN:
        return RexImpTable.optimize2(operand,
            Expressions.call(BuiltInMethod.BOOLEAN_TO_STRING.method,
                operand));

      default:
        return defaultExpression.get();
      }

    case DECIMAL: {
      int precision = targetType.getPrecision();
      int scale = targetType.getScale();
      if (precision != RelDataType.PRECISION_NOT_SPECIFIED
          && scale != RelDataType.SCALE_NOT_SPECIFIED) {
        if (sourceType.getFamily() == SqlTypeFamily.CHARACTER) {
          return Expressions.call(
              BuiltInMethod.CHAR_DECIMAL_CAST.method,
              operand,
              Expressions.constant(precision),
              Expressions.constant(scale));
        } else if (sourceType.getFamily() == SqlTypeFamily.INTERVAL_DAY_TIME) {
          return Expressions.call(
              BuiltInMethod.SHORT_INTERVAL_DECIMAL_CAST.method,
              operand,
              Expressions.constant(precision),
              Expressions.constant(scale),
              Expressions.constant(sourceType.getSqlTypeName().getEndUnit().multiplier));
        } else if (sourceType.getFamily() == SqlTypeFamily.INTERVAL_YEAR_MONTH) {
          return Expressions.call(
              BuiltInMethod.LONG_INTERVAL_DECIMAL_CAST.method,
              operand,
              Expressions.constant(precision),
              Expressions.constant(scale),
              Expressions.constant(sourceType.getSqlTypeName().getEndUnit().multiplier));
        }
      }
      return defaultExpression.get();
    }
    case BIGINT:
    case INTEGER:
    case TINYINT:
    case SMALLINT: {
      if (SqlTypeName.NUMERIC_TYPES.contains(sourceType.getSqlTypeName())) {
        return Expressions.call(
            BuiltInMethod.INTEGER_CAST.method,
            Expressions.constant(Primitive.of(typeFactory.getJavaClass(targetType))),
            operand);
      }
      return defaultExpression.get();
    }

    default:
      return defaultExpression.get();
    }
  }

  private static Expression checkExpressionPadTruncate(
      Expression operand,
      RelDataType sourceType,
      RelDataType targetType) {
    // Going from anything to CHAR(n) or VARCHAR(n), make sure value is no
    // longer than n.
    boolean pad = false;
    boolean truncate = true;
    switch (targetType.getSqlTypeName()) {
    case CHAR:
    case BINARY:
      pad = true;
      // fall through
    case VARCHAR:
    case VARBINARY:
      final int targetPrecision = targetType.getPrecision();
      if (targetPrecision < 0) {
        return operand;
      }
      switch (sourceType.getSqlTypeName()) {
      case CHAR:
      case VARCHAR:
      case BINARY:
      case VARBINARY:
        // If this is a widening cast, no need to truncate.
        final int sourcePrecision = sourceType.getPrecision();
        if (SqlTypeUtil.comparePrecision(sourcePrecision, targetPrecision)
            <= 0) {
          truncate = false;
        }
        // If this is a widening cast, no need to pad.
        if (SqlTypeUtil.comparePrecision(sourcePrecision, targetPrecision)
            >= 0) {
          pad = false;
        }
        // fall through
      default:
        if (truncate || pad) {
          final Method method =
              pad ? BuiltInMethod.TRUNCATE_OR_PAD.method
                  : BuiltInMethod.TRUNCATE.method;
          return Expressions.call(method, operand,
              Expressions.constant(targetPrecision));
        }
        return operand;
      }

      // Checkstyle thinks that the previous branch should have a break, but it
      // is mistaken.
      // CHECKSTYLE: IGNORE 1
    case TIMESTAMP:
      int targetScale = targetType.getScale();
      if (targetScale == RelDataType.SCALE_NOT_SPECIFIED) {
        targetScale = 0;
      }
      if (targetScale < sourceType.getScale()) {
        return Expressions.call(BuiltInMethod.ROUND_LONG.method, operand,
            Expressions.constant((long) Math.pow(10, 3 - targetScale)));
      }
      return operand;

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
      final SqlTypeFamily family =
          requireNonNull(sourceType.getSqlTypeName().getFamily(),
              () -> "null SqlTypeFamily for " + sourceType + ", SqlTypeName "
                  + sourceType.getSqlTypeName());
      switch (family) {
      case NUMERIC:
        final BigDecimal multiplier =
            targetType.getSqlTypeName().getEndUnit().multiplier;
        final BigDecimal divider = BigDecimal.ONE;
        return RexImpTable.multiplyDivide(operand, multiplier, divider);

      default:
        return operand;
      }

    default:
      return operand;
    }
  }

  private Expression translateCastToDate(RelDataType sourceType,
      Expression operand, ConstantExpression format,
      Supplier<Expression> defaultExpression) {

    switch (sourceType.getSqlTypeName()) {
    case CHAR:
    case VARCHAR:
      // If format string is supplied, parse formatted string into date
      return Expressions.isConstantNull(format)
          ? Expressions.call(BuiltInMethod.STRING_TO_DATE.method, operand)
          : Expressions.call(Expressions.new_(BuiltInMethod.PARSE_DATE.method.getDeclaringClass()),
              BuiltInMethod.PARSE_DATE.method, format, operand);

    case TIMESTAMP:
      return
          Expressions.convert_(
              Expressions.call(BuiltInMethod.FLOOR_DIV.method,
                  operand, Expressions.constant(DateTimeUtils.MILLIS_PER_DAY)),
              int.class);

    case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
      return
          RexImpTable.optimize2(
              operand, Expressions.call(
                  BuiltInMethod.TIMESTAMP_WITH_LOCAL_TIME_ZONE_TO_DATE.method,
                  operand,
                  Expressions.call(BuiltInMethod.TIME_ZONE.method, root)));

    default:
      return defaultExpression.get();
    }
  }

  private Expression translateCastToTime(RelDataType sourceType,
      Expression operand, ConstantExpression format, Supplier<Expression> defaultExpression) {

    switch (sourceType.getSqlTypeName()) {
    case CHAR:
    case VARCHAR:
      // If format string is supplied, parse formatted string into time
      return Expressions.isConstantNull(format)
          ? Expressions.call(BuiltInMethod.STRING_TO_TIME.method, operand)
          : Expressions.call(Expressions.new_(BuiltInMethod.PARSE_TIME.method.getDeclaringClass()),
              BuiltInMethod.PARSE_TIME.method, format, operand);

    case TIME_WITH_LOCAL_TIME_ZONE:
      return
          RexImpTable.optimize2(
              operand, Expressions.call(
                  BuiltInMethod.TIME_WITH_LOCAL_TIME_ZONE_TO_TIME.method,
                  operand,
                  Expressions.call(BuiltInMethod.TIME_ZONE.method, root)));

    case TIMESTAMP:
      return
          Expressions.convert_(
              Expressions.call(BuiltInMethod.FLOOR_MOD.method,
                  operand,
                  Expressions.constant(DateTimeUtils.MILLIS_PER_DAY)),
              int.class);


    case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
      return
          RexImpTable.optimize2(
              operand, Expressions.call(
                  BuiltInMethod.TIMESTAMP_WITH_LOCAL_TIME_ZONE_TO_TIME.method,
                  operand,
                  Expressions.call(BuiltInMethod.TIME_ZONE.method, root)));

    default:
      return defaultExpression.get();
    }
  }

  private Expression translateCastToTimeWithLocalTimeZone(RelDataType sourceType,
      Expression operand, Supplier<Expression> defaultExpression) {

    switch (sourceType.getSqlTypeName()) {
    case CHAR:
    case VARCHAR:
      return
          Expressions.call(BuiltInMethod.STRING_TO_TIME_WITH_LOCAL_TIME_ZONE.method, operand);

    case TIME:
      return
          Expressions.call(BuiltInMethod.TIME_STRING_TO_TIME_WITH_LOCAL_TIME_ZONE.method,
              RexImpTable.optimize2(operand,
                  Expressions.call(BuiltInMethod.UNIX_TIME_TO_STRING.method,
                      operand)),
              Expressions.call(BuiltInMethod.TIME_ZONE.method, root));

    case TIMESTAMP:
      return
          Expressions.call(BuiltInMethod.TIMESTAMP_STRING_TO_TIMESTAMP_WITH_LOCAL_TIME_ZONE.method,
              RexImpTable.optimize2(operand,
                  Expressions.call(BuiltInMethod.UNIX_TIMESTAMP_TO_STRING.method,
                      operand)),
              Expressions.call(BuiltInMethod.TIME_ZONE.method, root));

    case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
      return
          RexImpTable.optimize2(
              operand, Expressions.call(
                  BuiltInMethod
                      .TIMESTAMP_WITH_LOCAL_TIME_ZONE_TO_TIME_WITH_LOCAL_TIME_ZONE
                      .method,
                  operand));

    default:
      return defaultExpression.get();
    }
  }

  private Expression translateCastToTimestamp(RelDataType sourceType,
      Expression operand, ConstantExpression format, Supplier<Expression> defaultExpression) {

    switch (sourceType.getSqlTypeName()) {
    case CHAR:
    case VARCHAR:
      // If format string is supplied, parse formatted string into timestamp
      return Expressions.isConstantNull(format)
          ? Expressions.call(BuiltInMethod.STRING_TO_TIMESTAMP.method, operand)
          : Expressions.call(
              Expressions.new_(BuiltInMethod.PARSE_TIMESTAMP.method.getDeclaringClass()),
              BuiltInMethod.PARSE_TIMESTAMP.method, format, operand);

    case DATE:
      return
          Expressions.multiply(Expressions.convert_(operand, long.class),
              Expressions.constant(DateTimeUtils.MILLIS_PER_DAY));

    case TIME:
      return
          Expressions.add(
              Expressions.multiply(
                  Expressions.convert_(
                      Expressions.call(BuiltInMethod.CURRENT_DATE.method, root),
                      long.class),
                  Expressions.constant(DateTimeUtils.MILLIS_PER_DAY)),
              Expressions.convert_(operand, long.class));

    case TIME_WITH_LOCAL_TIME_ZONE:
      return
          RexImpTable.optimize2(
              operand, Expressions.call(
                  BuiltInMethod.TIME_WITH_LOCAL_TIME_ZONE_TO_TIMESTAMP.method,
                  Expressions.call(BuiltInMethod.UNIX_DATE_TO_STRING.method,
                      Expressions.call(BuiltInMethod.CURRENT_DATE.method, root)),
                  operand,
                  Expressions.call(BuiltInMethod.TIME_ZONE.method, root)));

    case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
      return
          RexImpTable.optimize2(
              operand, Expressions.call(
                  BuiltInMethod.TIMESTAMP_WITH_LOCAL_TIME_ZONE_TO_TIMESTAMP.method,
                  operand,
                  Expressions.call(BuiltInMethod.TIME_ZONE.method, root)));

    default:
      return defaultExpression.get();
    }
  }

  private Expression translateCastToTimestampWithLocalTimeZone(RelDataType sourceType,
      Expression operand, Supplier<Expression> defaultExpression) {

    switch (sourceType.getSqlTypeName()) {
    case CHAR:
    case VARCHAR:
      return
          Expressions.call(BuiltInMethod.STRING_TO_TIMESTAMP_WITH_LOCAL_TIME_ZONE.method, operand);

    case DATE:
      return
          Expressions.call(BuiltInMethod.TIMESTAMP_STRING_TO_TIMESTAMP_WITH_LOCAL_TIME_ZONE.method,
              RexImpTable.optimize2(operand,
                  Expressions.call(
                      BuiltInMethod.UNIX_TIMESTAMP_TO_STRING.method,
                      Expressions.multiply(
                          Expressions.convert_(operand, long.class),
                          Expressions.constant(DateTimeUtils.MILLIS_PER_DAY)))),
              Expressions.call(BuiltInMethod.TIME_ZONE.method, root));

    case TIME:
      return
          Expressions.call(BuiltInMethod.TIMESTAMP_STRING_TO_TIMESTAMP_WITH_LOCAL_TIME_ZONE.method,
              RexImpTable.optimize2(operand,
                  Expressions.call(BuiltInMethod.UNIX_TIMESTAMP_TO_STRING.method,
                      Expressions.add(
                          Expressions.multiply(
                              Expressions.convert_(
                                  Expressions.call(BuiltInMethod.CURRENT_DATE.method, root),
                                  long.class),
                              Expressions.constant(DateTimeUtils.MILLIS_PER_DAY)),
                          Expressions.convert_(operand, long.class)))),
              Expressions.call(BuiltInMethod.TIME_ZONE.method, root));

    case TIME_WITH_LOCAL_TIME_ZONE:
      return
          RexImpTable.optimize2(
              operand, Expressions.call(
                  BuiltInMethod
                      .TIME_WITH_LOCAL_TIME_ZONE_TO_TIMESTAMP_WITH_LOCAL_TIME_ZONE
                      .method,
                  Expressions.call(BuiltInMethod.UNIX_DATE_TO_STRING.method,
                      Expressions.call(BuiltInMethod.CURRENT_DATE.method, root)),
                  operand));

    case TIMESTAMP:
      return
          Expressions.call(BuiltInMethod.TIMESTAMP_STRING_TO_TIMESTAMP_WITH_LOCAL_TIME_ZONE.method,
              RexImpTable.optimize2(operand,
                  Expressions.call(
                      BuiltInMethod.UNIX_TIMESTAMP_TO_STRING.method,
                      operand)),
              Expressions.call(BuiltInMethod.TIME_ZONE.method, root));

    default:
      return defaultExpression.get();
    }
  }

  /**
   * Handle checked Exceptions declared in Method. In such case,
   * method call should be wrapped in a try...catch block.
   * "
   *      final Type method_call;
   *      try {
   *        method_call = callExpr
   *      } catch (Exception e) {
   *        throw new RuntimeException(e);
   *      }
   * "
   */
  Expression handleMethodCheckedExceptions(Expression callExpr) {
    // Try statement
    ParameterExpression methodCall =
        Expressions.parameter(callExpr.getType(), list.newName("method_call"));
    list.add(Expressions.declare(Modifier.FINAL, methodCall, null));
    Statement st = Expressions.statement(Expressions.assign(methodCall, callExpr));
    // Catch Block, wrap checked exception in unchecked exception
    ParameterExpression e = Expressions.parameter(0, Exception.class, "e");
    Expression uncheckedException = Expressions.new_(RuntimeException.class, e);
    CatchBlock cb = Expressions.catch_(e, Expressions.throw_(uncheckedException));
    list.add(Expressions.tryCatch(st, cb));
    return methodCall;
  }

  /** Dereferences an expression if it is a
   * {@link org.apache.calcite.rex.RexLocalRef}. */
  public RexNode deref(RexNode expr) {
    // 判断传入的表达式是否为“本地引用”（RexLocalRef）
    if (expr instanceof RexLocalRef) {
      RexLocalRef ref = (RexLocalRef) expr;
      final RexNode e2 = requireNonNull(program, "program")
          .getExprList().get(ref.getIndex());
      assert ref.getType().equals(e2.getType());
      return e2;
    } else {
      return expr;
    }
  }

  /** Translates a literal.
   *
   * @throws ControlFlowException if literal is null but {@code nullAs} is
   * {@link org.apache.calcite.adapter.enumerable.RexImpTable.NullAs#NOT_POSSIBLE}.
   */
  // 核心职责是：根据指定的 NullAs 策略处理空值，并根据 SQL 类型将逻辑常量转换为物理 Java 表达式。
  public static Expression translateLiteral(
      RexLiteral literal,
      RelDataType type,
      JavaTypeFactory typeFactory,
      RexImpTable.NullAs nullAs) {
    // 如果 SQL 值为 NULL，则根据上下文需求返回不同的结果。
    if (literal.isNull()) {
      switch (nullAs) {
      case TRUE:
      case IS_NULL:
        return RexImpTable.TRUE_EXPR;
      case FALSE:
      case IS_NOT_NULL:
        return RexImpTable.FALSE_EXPR;
      case NOT_POSSIBLE:
        throw new ControlFlowException();
      case NULL:
      default:
        return RexImpTable.NULL_EXPR;
      }
    } else {
      switch (nullAs) {
      // 处理非 NULL 时的布尔谓词优化
      case IS_NOT_NULL:
        return RexImpTable.TRUE_EXPR;
      case IS_NULL:
        return RexImpTable.FALSE_EXPR;
      default:
        break;
      }
    }
    // SQL 类型到 Java 物理类型的映射转换
    Type javaClass = typeFactory.getJavaClass(type);
    final Object value2;
    switch (literal.getType().getSqlTypeName()) {
    // 如果物理目标是 float/double，直接转。否则，生成 new BigDecimal("...") 语句，避免由于浮点数精度问题导致的数值漂移。
    case DECIMAL:
      final BigDecimal bd = literal.getValueAs(BigDecimal.class);
      if (javaClass == float.class) {
        return Expressions.constant(bd, javaClass);
      } else if (javaClass == double.class) {
        return Expressions.constant(bd, javaClass);
      }
      assert javaClass == BigDecimal.class;
      return Expressions.new_(BigDecimal.class,
          Expressions.constant(
              requireNonNull(bd,
                  () -> "value for " + literal).toString()));
    case DATE:
    case TIME:
    case TIME_WITH_LOCAL_TIME_ZONE:
    case INTERVAL_YEAR:
    case INTERVAL_YEAR_MONTH:
    case INTERVAL_MONTH:
      value2 = literal.getValueAs(Integer.class);
      javaClass = int.class;
      break;
    case TIMESTAMP:
    case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
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
      value2 = literal.getValueAs(Long.class);
      javaClass = long.class;
      break;
    case CHAR:
    case VARCHAR:
      value2 = literal.getValueAs(String.class);
      break;
    case BINARY:
    case VARBINARY:
      return Expressions.new_(
          ByteString.class,
          Expressions.constant(
              literal.getValueAs(byte[].class),
              byte[].class));
    case GEOMETRY:
      final Geometry geom =
          requireNonNull(literal.getValueAs(Geometry.class),
              () -> "getValueAs(Geometries.Geom) for " + literal);
      final String wkt = SpatialTypeFunctions.ST_AsWKT(geom);
      return Expressions.call(null, BuiltInMethod.ST_GEOM_FROM_EWKT.method,
          Expressions.constant(wkt));
    case SYMBOL:
      value2 =
          requireNonNull(literal.getValueAs(Enum.class),
              () -> "getValueAs(Enum.class) for " + literal);
      javaClass = value2.getClass();
      break;
    default:
      final Primitive primitive = Primitive.ofBoxOr(javaClass);
      final Comparable value = literal.getValueAs(Comparable.class);
      if (primitive != null && value instanceof Number) {
        value2 = primitive.number((Number) value);
      } else {
        value2 = value;
      }
    }
    return Expressions.constant(value2, javaClass);
  }

  public List<Expression> translateList(
      List<RexNode> operandList,
      RexImpTable.NullAs nullAs) {
    return translateList(operandList, nullAs,
        EnumUtils.internalTypes(operandList));
  }

  public List<Expression> translateList(
      List<RexNode> operandList,
      RexImpTable.NullAs nullAs,
      List<? extends @Nullable Type> storageTypes) {
    final List<Expression> list = new ArrayList<>();
    for (Pair<RexNode, ? extends @Nullable Type> e : Pair.zip(operandList, storageTypes)) {
      list.add(translate(e.left, nullAs, e.right));
    }
    return list;
  }

  /**
   * Translates the list of {@code RexNode}, using the default output types.
   * This might be suboptimal in terms of additional box-unbox when you use
   * the translation later.
   * If you know the java class that will be used to store the results, use
   * {@link org.apache.calcite.adapter.enumerable.RexToLixTranslator#translateList(java.util.List, java.util.List)}
   * version.
   *
   * @param operandList list of RexNodes to translate
   *
   * @return translated expressions
   */
  public List<Expression> translateList(List<? extends RexNode> operandList) {
    return translateList(operandList, EnumUtils.internalTypes(operandList));
  }

  /**
   * Translates the list of {@code RexNode}, while optimizing for output
   * storage.
   * For instance, if the result of translation is going to be stored in
   * {@code Object[]}, and the input is {@code Object[]} as well,
   * then translator will avoid casting, boxing, etc.
   *
   * @param operandList list of RexNodes to translate
   * @param storageTypes hints of the java classes that will be used
   *                     to store translation results. Use null to use
   *                     default storage type
   *
   * @return translated expressions
   */
  public List<Expression> translateList(List<? extends RexNode> operandList,
      @Nullable List<? extends @Nullable Type> storageTypes) {
    final List<Expression> list = new ArrayList<>(operandList.size());

    for (int i = 0; i < operandList.size(); i++) {
      RexNode rex = operandList.get(i);
      Type desiredType = null;
      if (storageTypes != null) {
        desiredType = storageTypes.get(i);
      }
      final Expression translate = translate(rex, desiredType);
      list.add(translate);
      // desiredType is still a hint, thus we might get any kind of output
      // (boxed or not) when hint was provided.
      // It is favourable to get the type matching desired type
      if (desiredType == null && !isNullable(rex)) {
        assert !Primitive.isBox(translate.getType())
            : "Not-null boxed primitive should come back as primitive: "
            + rex + ", " + translate.getType();
      }
    }
    return list;
  }

  private Expression translateTableFunction(RexCall rexCall, Expression inputEnumerable,
      PhysType inputPhysType, PhysType outputPhysType) {
    assert rexCall.getOperator() instanceof SqlWindowTableFunction;
    TableFunctionCallImplementor implementor =
        RexImpTable.INSTANCE.get((SqlWindowTableFunction) rexCall.getOperator());
    if (implementor == null) {
      throw Util.needToImplement("implementor of " + rexCall.getOperator().getName());
    }
    return implementor.implement(
        this, inputEnumerable, rexCall, inputPhysType, outputPhysType);
  }

  public static Expression translateCondition(RexProgram program,
      JavaTypeFactory typeFactory, BlockBuilder list, InputGetter inputGetter,
      Function1<String, InputGetter> correlates, SqlConformance conformance) {
    RexLocalRef condition = program.getCondition();
    if (condition == null) {
      return RexImpTable.TRUE_EXPR;
    }
    final ParameterExpression root = DataContext.ROOT;
    RexToLixTranslator translator =
        new RexToLixTranslator(program, typeFactory, root, inputGetter, list,
            null, new RexBuilder(typeFactory), conformance, null);
    translator = translator.setCorrelates(correlates);
    return translator.translate(
        condition,
        RexImpTable.NullAs.FALSE);
  }

  /** Returns whether an expression is nullable.
   *
   * @param e Expression
   * @return Whether expression is nullable
   */
  public boolean isNullable(RexNode e) {
    return e.getType().isNullable();
  }

  public RexToLixTranslator setBlock(BlockBuilder list) {
    if (list == this.list) {
      return this;
    }
    return new RexToLixTranslator(program, typeFactory, root, inputGetter, list,
        staticList, builder, conformance, correlates);
  }

  public RexToLixTranslator setCorrelates(
      @Nullable Function1<String, InputGetter> correlates) {
    if (this.correlates == correlates) {
      return this;
    }
    return new RexToLixTranslator(program, typeFactory, root, inputGetter, list,
        staticList, builder, conformance, correlates);
  }

  public Expression getRoot() {
    return root;
  }

  /** If an expression is a {@code NUMERIC} derived from an {@code INTERVAL},
   * scales it appropriately; returns the operand unchanged if the conversion
   * is not from {@code INTERVAL} to {@code NUMERIC}.
   * Does <b>not</b> scale values of type DECIMAL, these are expected
   * to be already scaled. */
  private static Expression scaleValue(
      RelDataType sourceType,
      RelDataType targetType,
      Expression operand) {
    final SqlTypeFamily targetFamily = targetType.getSqlTypeName().getFamily();
    final SqlTypeFamily sourceFamily = sourceType.getSqlTypeName().getFamily();
    if (targetFamily == SqlTypeFamily.NUMERIC
        // multiplyDivide cannot handle DECIMALs, but for DECIMAL
        // destination types the result is already scaled.
        && targetType.getSqlTypeName() != SqlTypeName.DECIMAL
        && (sourceFamily == SqlTypeFamily.INTERVAL_YEAR_MONTH
            || sourceFamily == SqlTypeFamily.INTERVAL_DAY_TIME)) {
      // Scale to the given field.
      final BigDecimal multiplier = BigDecimal.ONE;
      final BigDecimal divider =
          sourceType.getSqlTypeName().getEndUnit().multiplier;
      return RexImpTable.multiplyDivide(operand, multiplier, divider);
    }
    return operand;
  }

  /**
   * Visit {@code RexInputRef}. If it has never been visited
   * under current storage type before, {@code RexToLixTranslator}
   * generally produces three lines of code.
   *
   * <p>For example, when visiting a column (named commission) in
   * table Employee, the generated code snippet is:
   *
   * <blockquote><pre>{@code
   * final Employee current = (Employee) inputEnumerator.current();
   * final Integer input_value = current.commission;
   * final boolean input_isNull = input_value == null;
   * }</pre></blockquote>
   */
  // 作用是处理对输入行中某一列的引用（例如 SQL 中的 SELECT col_a）
  @Override public Result visitInputRef(RexInputRef inputRef) {
    // 检查当前列引用是否已经被翻译过
    // inputRef 是列的索引，currentStorageType 是期望的 Java 存储类型。
    // Calcite 会缓存已经生成的代码片段。
    // 如果同一个字段在同一个表达式中被多次引用，直接返回缓存的 Result，避免生成重复的变量声明（如定义多个 input_value1, input_value2）。
    final Pair<RexNode, @Nullable Type> key = Pair.of(inputRef, currentStorageType);
    // If the RexInputRef has been visited under current storage type already,
    // it is not necessary to visit it again, just return the result.
    if (rexWithStorageTypeResultMap.containsKey(key)) {
      return rexWithStorageTypeResultMap.get(key);
    }
    // Generate one line of code to get the input, e.g.,
    // "final Employee current =(Employee) inputEnumerator.current();"
    // 生成“如何从输入流中获取这一列”的原始表达式
    final Expression valueExpression =
        requireNonNull(inputGetter, "inputGetter")
            .field(list, inputRef.getIndex(), currentStorageType);

    // Generate one line of code for the value of RexInputRef, e.g.,
    // "final Integer input_value = current.commission;"
    // 定义一个 Java 变量来存储该列的值，并正式将声明语句加入生成的代码中。
    final ParameterExpression valueVariable =
        Expressions.parameter(
            valueExpression.getType(), list.newName("input_value"));
    // 将这一行 Java 代码写入当前正在构建的代码块
    list.add(Expressions.declare(Modifier.FINAL, valueVariable, valueExpression));

    // Generate one line of code to check whether RexInputRef is null, e.g.,
    // "final boolean input_isNull = input_value == null;"
    // 生成 Null 检查逻辑
    final Expression isNullExpression = checkNull(valueVariable);
    final ParameterExpression isNullVariable =
        Expressions.parameter(
            Boolean.TYPE, list.newName("input_isNull"));
    // 生成判断该列是否为 NULL 的代码。
    list.add(Expressions.declare(Modifier.FINAL, isNullVariable, isNullExpression));
    // Result 对象同时包含了变量的值（valueVariable）和它的空状态（isNullVariable）。
    // 后续的计算（如加法、比较）会同时用到这两个变量。
    final Result result = new Result(isNullVariable, valueVariable);

    // Cache <RexInputRef, currentStorageType>'s result
    // Note: EnumerableMatch's PrevInputGetter changes index each time,
    // it is not right to reuse the result under such case.
    // 如果 inputGetter 是 EnumerableMatch.PrevInputGetter（用于 MATCH_RECOGNIZE 这种需要跨行引用的场景），则不进行缓存。
    // 因为在复杂匹配中，相同的索引在不同时间点可能指向不同的行（PREV 逻辑），复用缓存会导致数据错误。
    if (!(inputGetter instanceof EnumerableMatch.PrevInputGetter)) {
      rexWithStorageTypeResultMap.put(key, result);
    }
    return new Result(isNullVariable, valueVariable);
  }
  // 负责处理 Lambda 表达式中的参数引用（RexLambdaRef）。
  // 在 SQL 转换为 Java 代码的过程中，当遇到类似 ARRAY_MAP 或 FILTER 等高阶函数时，Calcite 会生成 Lambda 表达式。visitLambdaRef 就是用来定义这些 Lambda 内部引用的参数。
  @Override public Result visitLambdaRef(RexLambdaRef ref) {
    // 创建一个代表 Lambda 参数的变量定义。
    final ParameterExpression valueVariable =
        Expressions.parameter(
            typeFactory.getJavaClass(ref.getType()), ref.getName());

    // Generate one line of code to check whether lambdaRef is null, e.g.,
    // "final boolean input_isNull = $0 == null;"
    // 生成一个判断其是否为 NULL 的逻辑指令
    final Expression isNullExpression = checkNull(valueVariable);
    final ParameterExpression isNullVariable =
        Expressions.parameter(
            Boolean.TYPE, list.newName("input_isNull"));
    list.add(Expressions.declare(Modifier.FINAL, isNullVariable, isNullExpression));
    return new Result(isNullVariable, valueVariable);
  }
  // 在 SQL 优化过程中，如果一个复杂的计算（如 a + b）被多次使用，优化器会将其提取为一个“本地引用（Local Reference）”。
  // RexLocalRef 就像是一个指向“已计算结果”的指针。
  // RexLocalRef 本身只包含一个索引（Index），指向一个存储在 RexProgram 中的表达式列表。
  @Override public Result visitLocalRef(RexLocalRef localRef) {
    // deref 方法的作用是根据这个索引，从当前的上下文环境（通常是 RexProgram 的 exprs 列表）中找到真实的逻辑表达式（RexNode）
    // 获取到真实的 RexNode 后，调用其 accept 方法，将当前的翻译器实例（this，即 RexToLixTranslator）作为访客传入。
    return deref(localRef).accept(this);
  }

  /**
   * Visit {@code RexLiteral}. If it has never been visited before,
   * {@code RexToLixTranslator} will generate two lines of code. For example,
   * when visiting a primitive int (10), the generated code snippet is:
   * {@code
   *   final int literal_value = 10;
   *   final boolean literal_isNull = false;
   * }
   */
  // 负责将 SQL 中的字面量（如 10, 'Hello', NULL）转换为 Java 代码。它的逻辑不仅涉及值的翻译，还包含对常量池的优化和 NULL 值的处理。
  @Override public Result visitLiteral(RexLiteral literal) {
    // If the RexLiteral has been visited already, just return the result
    // 检查该字面量是否已经翻译过。
    // 避免为同一个常量（例如在 SQL 中多次出现的 10）生成重复的 Java 变量声明。
    if (rexResultMap.containsKey(literal)) {
      return rexResultMap.get(literal);
    }
    // Generate one line of code for the value of RexLiteral, e.g.,
    // "final int literal_value = 10;"
    // 字面量值翻译（转换为 Expression）
    // 如果是 SQL 中的 NULL 字面量，调用 getTypedNullLiteral。它会生成带类型的 null，例如 (Integer) null，确保 Java 类型系统不丢失信息。
    // 如果是普通值，则根据其 SQL 类型（literal.getType()）转换为对应的 Java 常量。例如：SQL DATE 可能会被转换为整数（天数），VARCHAR 转换为 String。
    final Expression valueExpression = literal.isNull()
        // Note: even for null literal, we can't loss its type information
        ? getTypedNullLiteral(literal)
        : translateLiteral(literal, literal.getType(),
            typeFactory, RexImpTable.NullAs.NOT_POSSIBLE);
    final ParameterExpression valueVariable;
    // 尝试将常量加入当前代码块的“常量池”。如果这个值之前已经以常量的形式存在，appendConstant 可能会返回一个已有的表达式。
    final Expression literalValue =
        appendConstant("literal_value", valueExpression);
    // 如果 appendConstant 返回的是一个变量引用（ParameterExpression），则直接复用。
    if (literalValue instanceof ParameterExpression) {
      valueVariable = (ParameterExpression) literalValue;
    } else {
      valueVariable =
          Expressions.parameter(valueExpression.getType(),
              list.newName("literal_value"));
      list.add(
          Expressions.declare(Modifier.FINAL, valueVariable, valueExpression));
    }

    // Generate one line of code to check whether RexLiteral is null, e.g.,
    // "final boolean literal_isNull = false;"
    // 生成 Null 检查标记
    final Expression isNullExpression =
        literal.isNull() ? RexImpTable.TRUE_EXPR : RexImpTable.FALSE_EXPR;
    final ParameterExpression isNullVariable =
        Expressions.parameter(Boolean.TYPE, list.newName("literal_isNull"));
    list.add(Expressions.declare(Modifier.FINAL, isNullVariable, isNullExpression));

    // Maintain the map from valueVariable (ParameterExpression) to real Expression
    literalMap.put(valueVariable, valueExpression);
    final Result result = new Result(isNullVariable, valueVariable);
    // Cache RexLiteral's result
    rexResultMap.put(literal, result);
    return result;
  }

  /**
   * Returns an {@code Expression} for null literal without losing its type
   * information.
   */
  // 在 SQL 中，NULL 是有类型的（例如 CAST(NULL AS INTEGER) 与 CAST(NULL AS VARCHAR) 不同），而 Java 的裸 null 会丢失这些类型信息。
  // getTypedNullLiteral 的作用就是生成一个带有明确 Java 类型的 null 常量表达式，确保生成的 Java 代码在编译时不会出现歧义。
  private ConstantExpression getTypedNullLiteral(RexLiteral literal) {
    assert literal.isNull();
    Type javaClass = typeFactory.getJavaClass(literal.getType());
    switch (literal.getType().getSqlTypeName()) {
    case DATE:
    case TIME:
    case TIME_WITH_LOCAL_TIME_ZONE:
    case INTERVAL_YEAR:
    case INTERVAL_YEAR_MONTH:
    case INTERVAL_MONTH:
      javaClass = Integer.class;
      break;
    case TIMESTAMP:
    case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
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
      javaClass = Long.class;
      break;
    default:
      break;
    }
    return javaClass == null || javaClass == Void.class
        ? RexImpTable.NULL_EXPR
        : Expressions.constant(null, javaClass);
  }

  /**
   * Visit {@code RexCall}. For most {@code SqlOperator}s, we can get the implementor
   * from {@code RexImpTable}. Several operators (e.g., CaseWhen) with special semantics
   * need to be implemented separately.
   */
  // 负责将 SQL 中的函数调用、运算符和表达式（统称为 RexCall，如 a + b、ABS(x)、CASE WHEN...）翻译成对应的 Java 代码。
  @Override public Result visitCall(RexCall call) {
    // 检查该函数调用是否已经被翻译过
    if (rexResultMap.containsKey(call)) {
      return rexResultMap.get(call);
    }
    final SqlOperator operator = call.getOperator();
    // 处理 PREV 函数（通常用于 MATCH_RECOGNIZE 窗口操作），涉及跨行数据访问。
    if (operator == PREV) {
      return implementPrev(call);
    }
    // 处理 CASE WHEN。
    // CASE 具有短路效应（如果第一个条件成立，后面的表达式不应执行）。这需要生成 if-else 代码块，而不是简单的函数调用。
    if (operator == CASE) {
      return implementCaseWhen(call);
    }
    // 处理 SEARCH 算子（通常由 IN 或 BETWEEN 优化而来）
    // SEARCH 通常关联着复杂的 Sarg（搜索参数）。这里先将其展开为普通的逻辑判断（如 x >= 1 AND x <= 10），然后再递归调用 accept(this) 进行翻译。
    if (operator == SEARCH) {
      return RexUtil.expandSearch(builder, program, call).accept(this);
    }
    // 从实现映射表（RexImpTable）中查找该算子的翻译逻辑。
    // 大部分标准函数（如 +, -, SUBSTR, CAST）都在 RexImpTable 中注册了对应的 Implementor。
    // 如果找不到，说明 Calcite 目前还不支持将该函数翻译为 Enumerable Java 代码。
    final RexImpTable.RexCallImplementor implementor =
        RexImpTable.INSTANCE.get(operator);
    if (implementor == null) {
      throw new RuntimeException("cannot translate call " + call);
    }
    // 获取函数各个参数（操作数）的物理存储类型。
    final List<RexNode> operandList = call.getOperands();
    final List<@Nullable Type> storageTypes = EnumUtils.internalTypes(operandList);
    final List<Result> operandResults = new ArrayList<>();
    // 在翻译函数本身之前，先翻译它的参数。
    for (int i = 0; i < operandList.size(); i++) {
      final Result operandResult =
          implementCallOperand(operandList.get(i), storageTypes.get(i), this);
      operandResults.add(operandResult);
    }
    // 调用实现器生成最终的计算代码，并缓存结果。
    callOperandResultMap.put(call, operandResults);
    final Result result = implementor.implement(this, call, operandResults);
    rexResultMap.put(call, result);
    return result;
  }
  // 核心任务是：在翻译函数的参数（操作数）时，确保参数的物理存储类型（Storage Type）符合预期。
  private static Result implementCallOperand(final RexNode operand,
      final @Nullable Type storageType, final RexToLixTranslator translator) {
    final Type originalStorageType = translator.currentStorageType;
    // 暂时改变翻译器的“当前存储类型”状态。
    translator.currentStorageType = storageType;
    // 正式开始翻译该参数。
    Result operandResult = operand.accept(translator);
    if (storageType != null) {
      // 如果自动生成的变量类型与目标类型仍有差距，进行最后的“兜底”转换
      operandResult = translator.toInnerStorageType(operandResult, storageType);
    }
    translator.currentStorageType = originalStorageType;
    return operandResult;
  }
  // 直接返回一个 Expression 对象，而不是封装好的 Result 对象。
  // 常用于那些不需要处理 SQL 三值逻辑（即不需要 isNull 标记）或者已经知道结果必然不为空的内部调用场景。
  private static Expression implementCallOperand2(final RexNode operand,
      final @Nullable Type storageType, final RexToLixTranslator translator) {
    // 保存翻译器当前的 currentStorageType 状态，并将其切换为当前操作数期望的 storageType。
    final Type originalStorageType = translator.currentStorageType;
    translator.currentStorageType = storageType;
    // 调用 translate 方法将逻辑节点 RexNode 转换为物理表达式 Expression。
    final Expression result =  translator.translate(operand);
    translator.currentStorageType = originalStorageType;
    return result;
  }

  /**
   * For {@code PREV} operator, the offset of {@code inputGetter}
   * should be set first.
   */
  private Result implementPrev(RexCall call) {
    final RexNode node = call.getOperands().get(0);
    final RexNode offset = call.getOperands().get(1);
    final Expression offs =
        Expressions.multiply(translate(offset), Expressions.constant(-1));
    requireNonNull((EnumerableMatch.PrevInputGetter) inputGetter, "inputGetter")
        .setOffset(offs);
    return node.accept(this);
  }

  /**
   * The CASE operator is SQL’s way of handling if/then logic.
   * Different with other {@code RexCall}s, it is not safe to
   * implement its operands first.
   * For example: {@code
   *   select case when s=0 then false
   *          else 100/s > 0 end
   *   from (values (1),(0)) ax(s);
   * }
   */
  // 实现了 CASE WHEN 算子的代码生成逻辑。正如代码注释中所提到的，CASE 算子非常特殊，因为它必须遵循 短路效应（Short-circuiting）：
  // 只有当前面的 WHEN 条件不成立时，才去执行后面的表达式。如果像普通函数那样先翻译所有参数，可能会导致类似 100/0 的运行时错误。
  private Result implementCaseWhen(RexCall call) {
    // 初始化目标变量
    // 在 Java 代码中定义一个用于存储 CASE 最终结果的变量。
    final Type returnType = typeFactory.getJavaClass(call.getType());
    final ParameterExpression valueVariable =
        Expressions.parameter(returnType,
            list.newName("case_when_value"));
    list.add(Expressions.declare(0, valueVariable, null));
    final List<RexNode> operandList = call.getOperands();
    // 核心递归逻辑，用于生成嵌套的 if-else 结构。
    // operandList 包含了所有的 WHEN、THEN 以及最后的 ELSE 表达式。
    implementRecursively(this, operandList, valueVariable, 0);
    // 确定最终结果是否为 NULL。
    final Expression isNullExpression = checkNull(valueVariable);
    final ParameterExpression isNullVariable =
        Expressions.parameter(
            Boolean.TYPE, list.newName("case_when_isNull"));
    list.add(Expressions.declare(Modifier.FINAL, isNullVariable, isNullExpression));
    final Result result = new Result(isNullVariable, valueVariable);
    rexResultMap.put(call, result);
    return result;
  }

  /**
   * Case statements of the form:
   * {@code CASE WHEN a THEN b [WHEN c THEN d]* [ELSE e] END}.
   * When {@code a = true}, returns {@code b};
   * when {@code c = true}, returns {@code d};
   * else returns {@code e}.
   *
   * <p>We generate code that looks like:
   *
   * <blockquote><pre>{@code
   *      int case_when_value;
   *      ......code for a......
   *      if (!a_isNull && a_value) {
   *          ......code for b......
   *          case_when_value = res(b_isNull, b_value);
   *      } else {
   *          ......code for c......
   *          if (!c_isNull && c_value) {
   *              ......code for d......
   *              case_when_value = res(d_isNull, d_value);
   *          } else {
   *              ......code for e......
   *              case_when_value = res(e_isNull, e_value);
   *          }
   *      }
   * }</pre></blockquote>
   */
  // 实现 CASE WHEN 逻辑的核心递归方法。
  // 它的任务是生成嵌套的 if-else 结构，以确保 SQL 的短路语义（Short-circuiting）在 Java 代码中得到正确体现。
  private static void implementRecursively(RexToLixTranslator currentTranslator,
      List<RexNode> operandList, ParameterExpression valueVariable, int pos) {
    final BlockBuilder currentBlockBuilder =
        currentTranslator.getBlockBuilder();
    final List<@Nullable Type> storageTypes =
        EnumUtils.internalTypes(operandList);
    // [ELSE] clause
    // 当递归到操作数列表的最后一个元素时，说明已经到达了 ELSE 部分（或者没有显式 ELSE 时的隐式 ELSE）。
    if (pos == operandList.size() - 1) {
      // 翻译该表达式。
      // 生成赋值语句：case_when_value = res;。
      Expression res =
          implementCallOperand2(operandList.get(pos), storageTypes.get(pos),
              currentTranslator);
      currentBlockBuilder.add(
          Expressions.statement(
              Expressions.assign(valueVariable,
                  EnumUtils.convert(res, valueVariable.getType()))));
      return;
    }
    // Condition code: !a_isNull && a_value
    // 生成条件判断逻辑 (WHEN)
    // 生成 if 括号里的布尔表达式。
    // SQL 中的 WHEN a 意味着 a 必须为真且不能为 NULL。
    final RexNode testerNode = operandList.get(pos);
    final Result testerResult =
        implementCallOperand(testerNode, storageTypes.get(pos),
            currentTranslator);
    final Expression tester =
        Expressions.andAlso(Expressions.not(testerResult.isNullVariable),
            testerResult.valueVariable);
    // Code for {if} branch
    // 构建 THEN 分支块
    // 生成当条件成立时执行的代码块。
    // 创建一个新的 BlockBuilder 来隔离作用域。
    // 在该块内翻译 THEN 后的表达式 ifTrueNode。
    final RexNode ifTrueNode = operandList.get(pos + 1);
    final BlockBuilder ifTrueBlockBuilder =
        new BlockBuilder(true, currentBlockBuilder);
    // 进入新的代码块
    final RexToLixTranslator ifTrueTranslator =
        currentTranslator.setBlock(ifTrueBlockBuilder);
    final Expression ifTrueRes =
        implementCallOperand2(ifTrueNode, storageTypes.get(pos + 1),
            ifTrueTranslator);
    // Assign the value: case_when_value = ifTrueRes
    ifTrueBlockBuilder.add(
        Expressions.statement(
            Expressions.assign(valueVariable,
                EnumUtils.convert(ifTrueRes, valueVariable.getType()))));
    final BlockStatement ifTrue = ifTrueBlockBuilder.toBlock();
    // There is no [ELSE] clause
    // 递归处理下一个 WHEN 或结束
    // 如果没有后续的 WHEN 了，直接生成单分支 if 语句。
    if (pos + 1 == operandList.size() - 1) {
      currentBlockBuilder.add(
          Expressions.ifThen(tester, ifTrue));
      return;
    }
    // Generate code for {else} branch recursively
    // 构建 ELSE 分支块（递归核心）
    // 当当前 WHEN 不成立时，进入 ELSE 块继续判断下一个 WHEN。
    final BlockBuilder ifFalseBlockBuilder =
        new BlockBuilder(true, currentBlockBuilder);
    final RexToLixTranslator ifFalseTranslator =
        currentTranslator.setBlock(ifFalseBlockBuilder);
    implementRecursively(ifFalseTranslator, operandList, valueVariable, pos + 2);
    final BlockStatement ifFalse = ifFalseBlockBuilder.toBlock();
    currentBlockBuilder.add(
        Expressions.ifThenElse(tester, ifTrue, ifFalse));
  }

  private Result toInnerStorageType(Result result, Type storageType) {
    final Expression valueExpression =
        EnumUtils.toInternal(result.valueVariable, storageType);
    if (valueExpression.equals(result.valueVariable)) {
      return result;
    }
    final ParameterExpression valueVariable =
        Expressions.parameter(
            valueExpression.getType(),
            list.newName(result.valueVariable.name + "_inner_type"));
    list.add(Expressions.declare(Modifier.FINAL, valueVariable, valueExpression));
    final ParameterExpression isNullVariable = result.isNullVariable;
    return new Result(isNullVariable, valueVariable);
  }
  // 负责处理 SQL 中的动态参数（即占位符，如 SELECT * FROM emp WHERE id = ? 中的 ?）。
  // 在生成的 Java 代码中，这些参数需要从运行时的上下文（DataContext）中动态获取。
  @Override public Result visitDynamicParam(RexDynamicParam dynamicParam) {
    // 根据动态参数节点和当前的存储类型检查缓存
    final Pair<RexNode, @Nullable Type> key =
        Pair.of(dynamicParam, currentStorageType);
    if (rexWithStorageTypeResultMap.containsKey(key)) {
      return rexWithStorageTypeResultMap.get(key);
    }
    // 确定该参数在 Java 中应该表现为什么类型。如果是数值类型（如 INT, DOUBLE），则设置 isNumeric 标记。
    final Type storageType = currentStorageType != null
        ? currentStorageType : typeFactory.getJavaClass(dynamicParam.getType());

    final boolean isNumeric = SqlTypeFamily.NUMERIC.contains(dynamicParam.getType());

    // For numeric types, use java.lang.Number to prevent cast exception
    // when the parameter type differs from the target type
    // 代码通过 root（通常指 DataContext）获取参数值。针对数值类型做了一个特殊的两步转换（Double Cast）：
    final Expression valueExpression = isNumeric
        ? EnumUtils.convert(
            EnumUtils.convert(
                Expressions.call(root, BuiltInMethod.DATA_CONTEXT_GET.method,
                    Expressions.constant("?" + dynamicParam.getIndex())),
                java.lang.Number.class),
            storageType)
        : EnumUtils.convert(
            Expressions.call(root, BuiltInMethod.DATA_CONTEXT_GET.method,
                Expressions.constant("?" + dynamicParam.getIndex())),
            storageType);

    final ParameterExpression valueVariable =
        Expressions.parameter(valueExpression.getType(),
            list.newName("value_dynamic_param"));
    list.add(Expressions.declare(Modifier.FINAL, valueVariable, valueExpression));
    final ParameterExpression isNullVariable =
        Expressions.parameter(Boolean.TYPE, list.newName("isNull_dynamic_param"));
    list.add(
        Expressions.declare(Modifier.FINAL, isNullVariable,
            checkNull(valueVariable)));
    final Result result = new Result(isNullVariable, valueVariable);
    rexWithStorageTypeResultMap.put(key, result);
    return result;
  }

  @Override public Result visitFieldAccess(RexFieldAccess fieldAccess) {
    final Pair<RexNode, @Nullable Type> key =
        Pair.of(fieldAccess, currentStorageType);
    if (rexWithStorageTypeResultMap.containsKey(key)) {
      return rexWithStorageTypeResultMap.get(key);
    }
    final RexNode target = deref(fieldAccess.getReferenceExpr());
    int fieldIndex = fieldAccess.getField().getIndex();
    String fieldName = fieldAccess.getField().getName();
    switch (target.getKind()) {
    case CORREL_VARIABLE:
      if (correlates == null) {
        throw new RuntimeException("Cannot translate " + fieldAccess
            + " since correlate variables resolver is not defined");
      }
      final RexToLixTranslator.InputGetter getter =
          correlates.apply(((RexCorrelVariable) target).getName());
      final Expression input =
          getter.field(list, fieldIndex, currentStorageType);
      final Expression condition = checkNull(input);
      final ParameterExpression valueVariable =
          Expressions.parameter(input.getType(), list.newName("corInp_value"));
      list.add(Expressions.declare(Modifier.FINAL, valueVariable, input));
      final ParameterExpression isNullVariable =
          Expressions.parameter(Boolean.TYPE, list.newName("corInp_isNull"));
      final Expression isNullExpression =
          Expressions.condition(condition,
              RexImpTable.TRUE_EXPR,
              checkNull(valueVariable));
      list.add(
          Expressions.declare(Modifier.FINAL, isNullVariable, isNullExpression));
      final Result result1 = new Result(isNullVariable, valueVariable);
      rexWithStorageTypeResultMap.put(key, result1);
      return result1;
    default:
      RexNode rxIndex =
          builder.makeLiteral(fieldIndex, typeFactory.createType(int.class), true);
      RexNode rxName =
          builder.makeLiteral(fieldName, typeFactory.createType(String.class), true);
      RexCall accessCall =
          (RexCall) builder.makeCall(fieldAccess.getType(),
              SqlStdOperatorTable.STRUCT_ACCESS,
              ImmutableList.of(target, rxIndex, rxName));
      final Result result2 = accessCall.accept(this);
      rexWithStorageTypeResultMap.put(key, result2);
      return result2;
    }
  }

  @Override public Result visitOver(RexOver over) {
    throw new RuntimeException("cannot translate expression " + over);
  }

  @Override public Result visitCorrelVariable(RexCorrelVariable correlVariable) {
    throw new RuntimeException("Cannot translate " + correlVariable
        + ". Correlated variables should always be referenced by field access");
  }

  @Override public Result visitRangeRef(RexRangeRef rangeRef) {
    throw new RuntimeException("cannot translate expression " + rangeRef);
  }

  @Override public Result visitSubQuery(RexSubQuery subQuery) {
    throw new RuntimeException("cannot translate expression " + subQuery);
  }

  @Override public Result visitTableInputRef(RexTableInputRef fieldRef) {
    throw new RuntimeException("cannot translate expression " + fieldRef);
  }

  @Override public Result visitPatternFieldRef(RexPatternFieldRef fieldRef) {
    return visitInputRef(fieldRef);
  }

  @Override public Result visitLambda(RexLambda lambda) {
    final RexNode expression = lambda.getExpression();
    final List<RexLambdaRef> rexLambdaRefs = lambda.getParameters();

    // Prepare parameter expressions for lambda expression
    final ParameterExpression[] parameterExpressions =
        new ParameterExpression[rexLambdaRefs.size()];
    for (int i = 0; i < rexLambdaRefs.size(); i++) {
      final RexLambdaRef rexLambdaRef = rexLambdaRefs.get(i);
      parameterExpressions[i] =
          Expressions.parameter(
              typeFactory.getJavaClass(rexLambdaRef.getType()), rexLambdaRef.getName());
    }

    // Generate code for lambda expression body
    final RexToLixTranslator exprTranslator = this.setBlock(new BlockBuilder());
    final Result exprResult = expression.accept(exprTranslator);
    exprTranslator.list.add(
        Expressions.return_(null, exprResult.valueVariable));

    // Generate code for lambda expression
    final Expression functionExpression =
        Expressions.lambda(exprTranslator.list.toBlock(), parameterExpressions);
    final ParameterExpression valueVariable =
        Expressions.parameter(functionExpression.getType(), list.newName("function_value"));
    list.add(Expressions.declare(Modifier.FINAL, valueVariable, functionExpression));

    // Generate code for checking whether lambda expression is null
    final Expression isNullExpression = checkNull(valueVariable);
    final ParameterExpression isNullVariable =
        Expressions.parameter(Boolean.TYPE, list.newName("function_isNull"));
    list.add(Expressions.declare(Modifier.FINAL, isNullVariable, isNullExpression));

    return new Result(isNullVariable, valueVariable);
  }

  Expression checkNull(Expression expr) {
    if (Primitive.flavor(expr.getType())
        == Primitive.Flavor.PRIMITIVE) {
      return RexImpTable.FALSE_EXPR;
    }
    return Expressions.equal(expr, RexImpTable.NULL_EXPR);
  }

  Expression checkNotNull(Expression expr) {
    if (Primitive.flavor(expr.getType())
        == Primitive.Flavor.PRIMITIVE) {
      return RexImpTable.TRUE_EXPR;
    }
    return Expressions.notEqual(expr, RexImpTable.NULL_EXPR);
  }

  BlockBuilder getBlockBuilder() {
    return list;
  }

  Expression getLiteral(Expression literalVariable) {
    return requireNonNull(literalMap.get(literalVariable),
        () -> "literalMap.get(literalVariable) for " + literalVariable);
  }

  /** Returns the value of a literal. */
  @Nullable Object getLiteralValue(@Nullable Expression expr) {
    if (expr instanceof ParameterExpression) {
      final Expression constantExpr = literalMap.get(expr);
      return getLiteralValue(constantExpr);
    }
    if (expr instanceof ConstantExpression) {
      return ((ConstantExpression) expr).value;
    }
    return null;
  }

  List<Result> getCallOperandResult(RexCall call) {
    return requireNonNull(callOperandResultMap.get(call),
        () -> "callOperandResultMap.get(call) for " + call);
  }

  /** Returns an expression that yields the function object whose method
   * we are about to call.
   *
   * <p>It might be 'new MyFunction()', but it also might be a reference
   * to a static field 'F', defined by
   * 'static final MyFunction F = new MyFunction()'.
   *
   * <p>If there is a constructor that takes a {@link FunctionContext}
   * argument, we call that, passing in the values of arguments that are
   * literals; this allows the function to do some computation at load time.
   *
   * <p>If the call is "f(1, 2 + 3, 'foo')" and "f" is implemented by method
   * "eval(int, int, String)" in "class MyFun", the expression might be
   * "new MyFunction(FunctionContexts.of(new Object[] {1, null, "foo"})".
   *
   * @param method Method that implements the UDF
   * @param call Call to the UDF
   * @return New expression
   */
  Expression functionInstance(RexCall call, Method method) {
    final RexCallBinding callBinding =
        RexCallBinding.create(typeFactory, call, program, ImmutableList.of());
    final Expression target = getInstantiationExpression(method, callBinding);
    return appendConstant("f", target);
  }

  /** Helper for {@link #functionInstance}. */
  private Expression getInstantiationExpression(Method method,
      RexCallBinding callBinding) {
    final Class<?> declaringClass = method.getDeclaringClass();
    // If the UDF class has a constructor that takes a Context argument,
    // use that.
    try {
      final Constructor<?> constructor =
          declaringClass.getConstructor(FunctionContext.class);
      final List<Expression> constantArgs = new ArrayList<>();
      //noinspection unchecked
      Ord.forEach(method.getParameterTypes(),
          (parameterType, i) ->
              constantArgs.add(
                  callBinding.isOperandLiteral(i, true)
                      ? appendConstant("_arg",
                      Expressions.constant(
                          callBinding.getOperandLiteralValue(i,
                              Primitive.box(parameterType))))
                      : Expressions.constant(null)));
      final Expression context =
          Expressions.call(BuiltInMethod.FUNCTION_CONTEXTS_OF.method,
              DataContext.ROOT,
              Expressions.newArrayInit(Object.class, constantArgs));
      return Expressions.new_(constructor, context);
    } catch (NoSuchMethodException e) {
      // ignore
    }
    // The UDF class must have a public zero-args constructor.
    // Assume that the validator checked already.
    return Expressions.new_(declaringClass);
  }

  /** Stores a constant expression in a variable. */
  // 核心逻辑是根据当前的执行上下文，决定将一个常量表达式存储在静态字段（类级别）还是局部变量（方法级别）中。
  private Expression appendConstant(String name, Expression e) {
    if (staticList != null) {
      // If name is "camelCase", upperName is "CAMEL_CASE".
      // 如果 staticList 不为空，说明 Calcite 认为这个常量应该被提取到类级别（作为 static final 字段），这样在整个查询执行期间它只会被初始化一次，从而提高性能。
      final String upperName =
          CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, name);
      return staticList.append(upperName, e);
    } else {
      // 如果不支持静态存储，则将常量作为局部变量存入当前的 BlockBuilder（即 list）。
      return list.append(name, e);
    }
  }

  /** Translates a field of an input to an expression. */
  // 主要用于 Linq4j 表达式生成 过程中，负责将输入数据源的列（字段）映射为 Java 表达式。
  // InputGetter 的核心任务是**“解耦”**逻辑列与物理存储。
  // 在 Calcite 将 SQL 逻辑转化为可执行的 Java 代码时，需要处理类似 SELECT name FROM emp 的逻辑。此时，emp 表的 name 字段在生成的 Java 代码中可能是多种形式：
  // 如果输入是 POJO 对象：生成 input_row.name。
  // 如果输入是数组：生成 ((Object[])input_row)[0]。
  // 如果输入是单个值：直接生成 input_row。
  // InputGetter 就负责定义：“当我需要第 index 个字段时，你应该给我生成什么样的 Java 代码表达式。”
  // BlockBuilder list (代码构建器),生成 Java 方法体的“容器”。
  // 如果获取字段的过程很复杂（例如需要先进行空值判断或复杂的类型转换），翻译器会向这个 list 中添加临时的变量声明语句，然后再返回最终的表达式。
  // int index (字段索引) 目标字段在输入行中的物理位置。
  // C. @Nullable Type storageType (存储类型)  期望的 Java 类型。告诉 Getter 你希望生成的表达式返回什么类型的值。
  public interface InputGetter {
    Expression field(BlockBuilder list, int index, @Nullable Type storageType);
  }

  /** Implementation of {@link InputGetter} that calls
   * {@link PhysType#fieldReference}. */
  // InputGetterImpl 是 InputGetter 接口的一个标准实现类。
  // 它的核心逻辑是：管理多个输入源（Inputs），并根据全局索引（index）计算出该字段属于哪一个输入源，然后生成访问代码。
  // 在 Calcite 的 Join（联接）操作中，这种实现非常常见，因为 Join 会把两个或多个输入表的字段合并在一起。
  // 类的作用：多数据源的字段映射器
  // InputGetterImpl 的主要作用是**“路由”**。
  // 当你调用 field(index) 时，它会遍历内部维护的所有输入源。如果第一个输入源有 5 个字段，你查第 6 个字段（index=5），它会自动跳到第二个输入源去查找。
  public static class InputGetterImpl implements InputGetter {
    // 存储 Java 表达式与物理类型（PhysType）的映射关系。
    // Key (Expression)：代表当前输入行的变量名（例如 left_row, right_row）。
    // Value (PhysType)：代表该输入行的物理类型信息（它知道该字段是存在数组里、对象里、还是其他地方）。
    private final ImmutableMap<Expression, PhysType> inputs;

    @Deprecated // to be removed before 2.0
    public InputGetterImpl(List<Pair<Expression, PhysType>> inputs) {
      this(mapOf(inputs));
    }
    // 用于单输入场景（如 Project 或 Filter），只管理一个数据源
    public InputGetterImpl(Expression e, PhysType physType) {
      this(ImmutableMap.of(e, physType));
    }
    // 用于多输入场景（如 Join），同时管理左右两个（或多个）输入源。
    public InputGetterImpl(Map<Expression, PhysType> inputs) {
      this.inputs = ImmutableMap.copyOf(inputs);
    }
    // 用于将 Pair 列表转换为 ImmutableMap。
    private static <K, V> Map<K, V> mapOf(
        Iterable<? extends Map.Entry<K, V>> entries) {
      ImmutableMap.Builder<K, V> b = ImmutableMap.builder();
      Pair.forEach(entries, b::put);
      return b.build();
    }

    @Override public Expression field(BlockBuilder list, int index, @Nullable Type storageType) {
      // 偏移量累加器
      int offset = 0;
      for (Map.Entry<Expression, PhysType> input : inputs.entrySet()) {
        final PhysType physType = input.getValue();
        int fieldCount = physType.getRowType().getFieldCount(); // 获取当前输入源的列总数
        // 1. 判断目标 index 是否落在当前输入源范围内
        if (index >= offset + fieldCount) {
          offset += fieldCount;
          continue;
        }
        // list.append("current", ...) 会在生成代码中创建一个临时变量（如 var current = input_row;）
        final Expression left = list.append("current", input.getKey());
        // 3. 委派给具体的物理类型（PhysType）去生成真正的字段访问表达式
        // index - offset 是将全局索引转换为当前源的局部索引
        return physType.fieldReference(left, index - offset, storageType);
      }
      throw new IllegalArgumentException("Unable to find field #" + index);
    }
  }

  /** Result of translating a {@code RexNode}. */
  // 代表了将一个 SQL 表达式（RexNode） 翻译成 Java 可执行代码（Linq4j 表达式） 后的最终产物
  // 核心作用：处理 SQL 的“三值逻辑”
  // SQL 逻辑与普通 Java 逻辑最大的区别在于 NULL 的处理。在 Java 中，一个原始类型（如 int）不能为 null，而 SQL 中的任何列都可能为 NULL。
  // 为了在生成的 Java 代码中高效且安全地表示这一点，Calcite 不使用包装类（如 Integer，因为会有装箱拆箱的性能损耗），而是采用了 “双变量表示法”：
  // 一个布尔变量：记录“是否为 NULL”。
  // 一个值变量：记录“实际的数值”（如果为 NULL，此值通常为默认值）。
  // Result 类就是用来同时持有这两个变量引用的。
  public static class Result {
    // 代表生成的 Java 代码中的一个布尔变量。
    // 含义：如果在运行时该变量为 true，则表示对应的 SQL 表达式计算结果为 NULL。
    final ParameterExpression isNullVariable;
    // 代表生成的 Java 代码中的结果值变量。
    // 存储表达式的实际计算结果。
    // 如果 SQL 表达式是 age + 1，它可能对应 Java 中的 int v1Value;。
    final ParameterExpression valueVariable;

    public Result(ParameterExpression isNullVariable,
        ParameterExpression valueVariable) {
      this.isNullVariable = isNullVariable;
      this.valueVariable = valueVariable;
    }
  }
}
