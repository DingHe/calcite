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
package org.apache.calcite.sql;

import org.apache.calcite.avatica.util.TimeUnit;
import org.apache.calcite.avatica.util.TimeUnitRange;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rel.type.TimeFrames;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlVisitor;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorScope;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.calcite.linq4j.Nullness.castNonNull;
import static org.apache.calcite.util.Static.RESOURCE;

import static java.util.Objects.requireNonNull;

/**
 * Represents an INTERVAL qualifier.
 *
 * <p>INTERVAL qualifier is defined as follows:
 *
 * <blockquote><code>
 * &lt;interval qualifier&gt; ::=<br>
 * &nbsp;&nbsp; &lt;start field&gt; TO &lt;end field&gt;<br>
 * &nbsp;&nbsp;| &lt;single datetime field&gt;<br>
 * &lt;start field&gt; ::=<br>
 * &nbsp;&nbsp; &lt;non-second primary datetime field&gt;<br>
 * &nbsp;&nbsp; [ &lt;left paren&gt; &lt;interval leading field precision&gt;
 * &lt;right paren&gt; ]<br>
 * &lt;end field&gt; ::=<br>
 * &nbsp;&nbsp; &lt;non-second primary datetime field&gt;<br>
 * &nbsp;&nbsp;| SECOND [ &lt;left paren&gt;
 * &lt;interval fractional seconds precision&gt; &lt;right paren&gt; ]<br>
 * &lt;single datetime field&gt; ::=<br>
 * &nbsp;&nbsp;&lt;non-second primary datetime field&gt;<br>
 * &nbsp;&nbsp;[ &lt;left paren&gt; &lt;interval leading field precision&gt;
 * &lt;right paren&gt; ]<br>
 * &nbsp;&nbsp;| SECOND [ &lt;left paren&gt;
 * &lt;interval leading field precision&gt;<br>
 * &nbsp;&nbsp;[ &lt;comma&gt; &lt;interval fractional seconds precision&gt; ]
 * &lt;right paren&gt; ]<br>
 * &lt;primary datetime field&gt; ::=<br>
 * &nbsp;&nbsp;&lt;non-second primary datetime field&gt;<br>
 * &nbsp;&nbsp;| SECOND<br>
 * &lt;non-second primary datetime field&gt; ::= YEAR | MONTH | DAY | HOUR
 * | MINUTE<br>
 * &lt;interval fractional seconds precision&gt; ::=
 * &lt;unsigned integer&gt;<br>
 * &lt;interval leading field precision&gt; ::= &lt;unsigned integer&gt;
 * </code></blockquote>
 *
 * <p>Examples include:
 *
 * <ul>
 * <li><code>INTERVAL '1:23:45.678' HOUR TO SECOND</code></li>
 * <li><code>INTERVAL '1 2:3:4' DAY TO SECOND</code></li>
 * <li><code>INTERVAL '1 2:3:4' DAY(4) TO SECOND(4)</code></li>
 * </ul>
 * <p>An instance of this class is immutable.
 */
// 用于表示和处理 SQL 中 时间间隔限定符（INTERVAL Qualifier） 的核心语法树节点（继承自 SqlNode）。
// 它在处理诸如 INTERVAL '1-2' YEAR TO MONTH 或 INTERVAL '5' DAY 这样的时间段语法时发挥关键作用。
// SqlIntervalQualifier 的核心作用是定义时间间隔的度量单位、结构、以及数字的精度。
// 当解析器解析到一个 INTERVAL 文本时，该类负责：
// 定义跨度范围：指明时间段是从哪个单位到哪个单位（例如：YEAR TO MONTH 表示从年到月，DAY TO SECOND 表示从天到秒）。
// 规范化精度系统：管理“前导单位精度”（如 DAY(4) 最多允许 4 位数，即 9999 天）和“秒的小数位精度”（如 SECOND(3) 表示毫秒）
// 字面量合法性校验：根据限定符的规则，强行校验时间字符串是否合法。例如，如果限定符是 YEAR TO MONTH，传入 '1-13' 就会报错，因为月份不能超过 12。

public class SqlIntervalQualifier extends SqlNode {
  //~ Static fields/initializers ---------------------------------------------
  // 用于内部高精度数值 BigDecimal 边界检查的计算常量。
  private static final BigDecimal ZERO = BigDecimal.ZERO;
  private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
  private static final BigDecimal INT_MAX_VALUE_PLUS_ONE =
      BigDecimal.valueOf(Integer.MAX_VALUE).add(BigDecimal.ONE);

  // 包含 HOUR, MINUTE, SECOND，定义基础时间（Time）单位集合。
  private static final Set<TimeUnitRange> TIME_UNITS =
      ImmutableSet.of(TimeUnitRange.HOUR,
          TimeUnitRange.MINUTE,
          TimeUnitRange.SECOND);

  // 月份单位，千年、世纪、十年、年、季度、月
  private static final Set<TimeUnitRange> MONTH_UNITS =
      ImmutableSet.of(TimeUnitRange.MILLENNIUM,
          TimeUnitRange.CENTURY,
          TimeUnitRange.DECADE,
          TimeUnitRange.YEAR,
          TimeUnitRange.ISOYEAR,
          TimeUnitRange.QUARTER,
          TimeUnitRange.MONTH);
  // 天单位，星期、天
  private static final Set<TimeUnitRange> DAY_UNITS =
      ImmutableSet.of(TimeUnitRange.WEEK,
          TimeUnitRange.DAY);

  // 日期单位，月份单位和天单位的合并
  private static final Set<TimeUnitRange> DATE_UNITS =
      ImmutableSet.<TimeUnitRange>builder()
          .addAll(MONTH_UNITS).addAll(DAY_UNITS).build();

  // 主要用于扩展和兼容非标准 SQL（或特定数据库方言、ODBC/JDBC 标准）中的时间框架名称（Time Frame Names）。
  // 定义所有被认定为“周（Week）”概念的字符串标识符集合。
  private static final Set<String> WEEK_FRAMES =
      ImmutableSet.<String>builder()
          .addAll(TimeFrames.WEEK_FRAME_NAMES)
          .add("ISOWEEK") // ISO-8601 标准的周（周一作为一周的第一天）
          .add("WEEK") // 常规的周（通常周日或周一作为第一天，取决于配置）。
          .add("SQL_TSI_WEEK") // 来自 ODBC/JDBC 规范的周常量（TSI 代表 Time Stamp Interval）。
          .build();

  // 定义所有属于“纯时间（Time）”范畴的 ODBC/JDBC 扩展时间戳间隔常量（TSI）。这些单位都小于或等于“小时”。
  private static final Set<String> TSI_TIME_FRAMES =
      ImmutableSet.of(
          "SQL_TSI_FRAC_SECOND", // 小数值秒（如毫秒、纳秒）。
          "SQL_TSI_MICROSECOND", // 微秒。
          "SQL_TSI_SECOND", // 秒。
          "SQL_TSI_MINUTE", // 分钟。
          "SQL_TSI_HOUR"); // 小时。

  // 定义所有属于“日期（Date）”范畴的 ODBC/JDBC 扩展时间戳间隔常量（TSI）。这些单位都大于或等于“天”。
  private static final Set<String> TSI_DATE_FRAMES =
      ImmutableSet.of(
          "SQL_TSI_DAY", // 天。
          "SQL_TSI_WEEK", // 周。
          "SQL_TSI_MONTH", // 月。
          "SQL_TSI_QUARTER", // 季度。
          "SQL_TSI_YEAR"); // 年。

  //~ Instance fields --------------------------------------------------------
  // 表示时间间隔前导单位（起始单位）的数字精度（Precision）
  // 在 SQL 标准中，你可以指定时间间隔开头的数字最多可以有几位。例如：
  // INTERVAL '99' DAY：默认精度通常是 2（最大允许 99 天）。
  // INTERVAL '999' DAY(3)：此时 startPrecision 的值就是 3，意味着前导数字可以达到 3 位（最大允许 999 天）。
  private final int startPrecision;
  // 表示自定义或非标准的时间框架名称（Time Frame Name）
  // 当系统处理一些特殊的、非标准 ISO 定义的时间维度（或者特定方言、连接器扩展的单位，如 SQL_TSI_DAY、ISOWEEK 等）时，标准枚举 TimeUnitRange 可能无法完美表达。此时该属性用来记录这些文本名称。
  public final @Nullable String timeFrameName;
  // 作用：表示时间间隔的单位范围，是该类最核心的业务属性。
  // 使用的是 Calcite 底层的 TimeUnitRange 枚举，能够表达单一单位或组合单位。
  // 单单位：如 YEAR、MONTH、DAY、SECOND。
  // 组合单位：如 YEAR_TO_MONTH（年到月）、DAY_TO_SECOND（天到秒）。
  public final TimeUnitRange timeUnitRange;
  // 表示秒下方的小数位精度（Fractional Seconds Precision），即秒后面的微秒、毫秒或纳秒的保留位数。
  private final int fractionalSecondPrecision;

  //~ Constructors -----------------------------------------------------------

  private SqlIntervalQualifier(SqlParserPos pos, @Nullable String timeFrameName,
      TimeUnitRange timeUnitRange, int startPrecision,
      int fractionalSecondPrecision) {
    super(pos);
    this.timeFrameName = timeFrameName;
    this.timeUnitRange = requireNonNull(timeUnitRange, "timeUnitRange");
    this.startPrecision = startPrecision;
    this.fractionalSecondPrecision = fractionalSecondPrecision;
  }

  public SqlIntervalQualifier(
      TimeUnit startUnit,
      int startPrecision,
      @Nullable TimeUnit endUnit,
      int fractionalSecondPrecision,
      SqlParserPos pos) {
    this(pos, null,
        TimeUnitRange.of(requireNonNull(startUnit, "startUnit"),
            endUnit == startUnit ? null : endUnit),
        startPrecision, fractionalSecondPrecision);
  }

  public SqlIntervalQualifier(
      TimeUnit startUnit,
      @Nullable TimeUnit endUnit,
      SqlParserPos pos) {
    this(
        startUnit,
        RelDataType.PRECISION_NOT_SPECIFIED,
        endUnit,
        RelDataType.PRECISION_NOT_SPECIFIED,
        pos);
  }

  /** Creates a qualifier based on a time frame name. */
  public SqlIntervalQualifier(String timeFrameName,
      SqlParserPos pos) {
    this(pos, requireNonNull(timeFrameName, "timeFrameName"),
        // EPOCH is a placeholder because code expects a non-null TimeUnitRange.
        TimeUnitRange.EPOCH, RelDataType.PRECISION_NOT_SPECIFIED,
        RelDataType.PRECISION_NOT_SPECIFIED);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public SqlKind getKind() {
    return SqlKind.INTERVAL_QUALIFIER;
  }

  public SqlTypeName typeName() {
    switch (timeUnitRange) {
    case YEAR:
    case ISOYEAR:
    case CENTURY:
    case DECADE:
    case MILLENNIUM:
      return SqlTypeName.INTERVAL_YEAR;
    case YEAR_TO_MONTH:
      return SqlTypeName.INTERVAL_YEAR_MONTH;
    case MONTH:
    case QUARTER:
      return SqlTypeName.INTERVAL_MONTH;
    case DOW:
    case ISODOW:
    case DOY:
    case DAY:
    case WEEK:
      return SqlTypeName.INTERVAL_DAY;
    case DAY_TO_HOUR:
      return SqlTypeName.INTERVAL_DAY_HOUR;
    case DAY_TO_MINUTE:
      return SqlTypeName.INTERVAL_DAY_MINUTE;
    case DAY_TO_SECOND:
      return SqlTypeName.INTERVAL_DAY_SECOND;
    case HOUR:
      return SqlTypeName.INTERVAL_HOUR;
    case HOUR_TO_MINUTE:
      return SqlTypeName.INTERVAL_HOUR_MINUTE;
    case HOUR_TO_SECOND:
      return SqlTypeName.INTERVAL_HOUR_SECOND;
    case MINUTE:
      return SqlTypeName.INTERVAL_MINUTE;
    case MINUTE_TO_SECOND:
      return SqlTypeName.INTERVAL_MINUTE_SECOND;
    case SECOND:
    case MILLISECOND:
    case EPOCH:
    case MICROSECOND:
    case NANOSECOND:
      return SqlTypeName.INTERVAL_SECOND;
    default:
      throw new AssertionError(timeUnitRange);
    }
  }

  /** Whether this is a DATE interval (including all week intervals). */
  public boolean isDate() {
    return DATE_UNITS.contains(timeUnitRange)
        || timeFrameName != null && TSI_DATE_FRAMES.contains(timeFrameName)
        || isWeek();
  }

  /** Whether this is a TIME interval. */
  public boolean isTime() {
    return TIME_UNITS.contains(timeUnitRange)
        || timeFrameName != null && TSI_TIME_FRAMES.contains(timeFrameName);
  }

  /** Whether this is a TIMESTAMP interval (including all week intervals). */
  public boolean isTimestamp() {
    return isDate() || isTime();
  }

  /** Whether this qualifier represents {@code WEEK}, {@code ISOWEEK},
   * or {@code WEEK(}<i>weekday</i>{@code )}
   * (for <i>weekday</i> in {@code SUNDAY} .. {@code SATURDAY}). */
  public boolean isWeek() {
    return timeUnitRange == TimeUnitRange.WEEK
        || timeFrameName != null && WEEK_FRAMES.contains(timeFrameName);
  }

  @Override public void validate(
      SqlValidator validator,
      SqlValidatorScope scope) {
    validator.validateIntervalQualifier(this);
  }

  @Override public <R> R accept(SqlVisitor<R> visitor) {
    return visitor.visit(this);
  }

  @Override public boolean equalsDeep(@Nullable SqlNode node, Litmus litmus) {
    if (node == null) {
      return litmus.fail("other==null");
    }
    final String thisString = this.toString();
    final String thatString = node.toString();
    if (!thisString.equals(thatString)) {
      return litmus.fail("{} != {}", this, node);
    }
    return litmus.succeed();
  }

  public int getStartPrecision(RelDataTypeSystem typeSystem) {
    if (startPrecision == RelDataType.PRECISION_NOT_SPECIFIED) {
      return typeSystem.getMaxPrecision(typeName());
    } else {
      return startPrecision;
    }
  }

  public int getStartPrecisionPreservingDefault() {
    return startPrecision;
  }

  /** Returns {@code true} if start precision is not specified. */
  public boolean useDefaultStartPrecision() {
    return startPrecision == RelDataType.PRECISION_NOT_SPECIFIED;
  }

  public static int combineStartPrecisionPreservingDefault(
      RelDataTypeSystem typeSystem,
      SqlIntervalQualifier qual1,
      SqlIntervalQualifier qual2) {
    final int start1 = qual1.getStartPrecision(typeSystem);
    final int start2 = qual2.getStartPrecision(typeSystem);
    if (start1 > start2) {
      // qual1 is more precise, but if it has the default indicator
      // set, we need to return that indicator so result will also
      // use default
      return qual1.getStartPrecisionPreservingDefault();
    } else if (start1 < start2) {
      // qual2 is more precise, but if it has the default indicator
      // set, we need to return that indicator so result will also
      // use default
      return qual2.getStartPrecisionPreservingDefault();
    } else {
      // they are equal.  return default if both are default,
      // otherwise return exact precision
      if (qual1.useDefaultStartPrecision()
          && qual2.useDefaultStartPrecision()) {
        return qual1.getStartPrecisionPreservingDefault();
      } else {
        return start1;
      }
    }
  }

  public int getFractionalSecondPrecision(RelDataTypeSystem typeSystem) {
    if (fractionalSecondPrecision == RelDataType.PRECISION_NOT_SPECIFIED) {
      return typeName().getDefaultScale();
    } else {
      return fractionalSecondPrecision;
    }
  }

  public int getFractionalSecondPrecisionPreservingDefault() {
    if (useDefaultFractionalSecondPrecision()) {
      return RelDataType.PRECISION_NOT_SPECIFIED;
    } else {
      return fractionalSecondPrecision;
    }
  }

  /** Returns {@code true} if fractional second precision is not specified. */
  public boolean useDefaultFractionalSecondPrecision() {
    return fractionalSecondPrecision == RelDataType.PRECISION_NOT_SPECIFIED;
  }

  public static int combineFractionalSecondPrecisionPreservingDefault(
      RelDataTypeSystem typeSystem,
      SqlIntervalQualifier qual1,
      SqlIntervalQualifier qual2) {
    final int p1 = qual1.getFractionalSecondPrecision(typeSystem);
    final int p2 = qual2.getFractionalSecondPrecision(typeSystem);
    if (p1 > p2) {
      // qual1 is more precise, but if it has the default indicator
      // set, we need to return that indicator so result will also
      // use default
      return qual1.getFractionalSecondPrecisionPreservingDefault();
    } else if (p1 < p2) {
      // qual2 is more precise, but if it has the default indicator
      // set, we need to return that indicator so result will also
      // use default
      return qual2.getFractionalSecondPrecisionPreservingDefault();
    } else {
      // they are equal.  return default if both are default,
      // otherwise return exact precision
      if (qual1.useDefaultFractionalSecondPrecision()
          && qual2.useDefaultFractionalSecondPrecision()) {
        return qual1.getFractionalSecondPrecisionPreservingDefault();
      } else {
        return p1;
      }
    }
  }

  public TimeUnit getStartUnit() {
    return timeUnitRange.startUnit;
  }

  public TimeUnit getEndUnit() {
    return timeUnitRange.endUnit;
  }

  /** Returns {@code SECOND} for both {@code HOUR TO SECOND} and
   * {@code SECOND}. */
  public TimeUnit getUnit() {
    return Util.first(timeUnitRange.endUnit, timeUnitRange.startUnit);
  }

  @Override public SqlNode clone(SqlParserPos pos) {
    return new SqlIntervalQualifier(timeUnitRange.startUnit, startPrecision,
        timeUnitRange.endUnit, fractionalSecondPrecision, pos);
  }

  @Override public void unparse(
      SqlWriter writer,
      int leftPrec,
      int rightPrec) {
    writer.getDialect()
        .unparseSqlIntervalQualifier(writer, this, RelDataTypeSystem.DEFAULT);
  }

  /**
   * Returns whether this interval has a single datetime field.
   *
   * <p>Returns {@code true} if it is of the form {@code unit},
   * {@code false} if it is of the form {@code unit TO unit}.
   */
  public boolean isSingleDatetimeField() {
    return timeUnitRange.endUnit == null;
  }

  public final boolean isYearMonth() {
    return timeUnitRange.startUnit.yearMonth;
  }

  /**
   * Returns 1 or -1.
   */
  public int getIntervalSign(String value) {
    int sign = 1; // positive until proven otherwise

    if (!Util.isNullOrEmpty(value)) {
      if ('-' == value.charAt(0)) {
        sign = -1; // Negative
      }
    }

    return sign;
  }

  public static TimeUnit stringToDatePartTimeUnit(String stringValue) {
    final String timeUnitString = stringValue.toUpperCase(Locale.ROOT);

    switch (timeUnitString) {
    case "MICROSECOND":
      return TimeUnit.MICROSECOND;
    case "MILLISECOND":
      return TimeUnit.MILLISECOND;
    case "SECOND":
      return TimeUnit.SECOND;
    case "MINUTE":
      return TimeUnit.MINUTE;
    case "HOUR":
      return TimeUnit.HOUR;
    case "DAY":
      return TimeUnit.DAY;
    case "DAYOFWEEK":
    case "DOW":
      return TimeUnit.DOW;
    case "DAYOFYEAR":
    case "DOY":
      return TimeUnit.DOY;
    case "ISODOW":
      return TimeUnit.ISODOW;
    case "ISODOY":
      return TimeUnit.ISOYEAR;
    case "WEEK":
      return TimeUnit.WEEK;
    case "MONTH":
      return TimeUnit.MONTH;
    case "QUARTER":
      return TimeUnit.QUARTER;
    case "YEAR":
      return TimeUnit.YEAR;
    case "EPOCH":
      return TimeUnit.EPOCH;
    case "DECADE":
      return TimeUnit.DECADE;
    case "CENTURY":
      return TimeUnit.CENTURY;
    case "MILLENNIUM":
      return TimeUnit.MILLENNIUM;
    default:
      throw new IllegalArgumentException("Date/Time units \"" + stringValue + "\" not recognized");
    }
  }

  private static String stripLeadingSign(String value) {
    String unsignedValue = value;

    if (!Util.isNullOrEmpty(value)) {
      if (('-' == value.charAt(0)) || ('+' == value.charAt(0))) {
        unsignedValue = value.substring(1);
      }
    }

    return unsignedValue;
  }

  private boolean isLeadFieldInRange(RelDataTypeSystem typeSystem,
      BigDecimal value, @SuppressWarnings("unused") TimeUnit unit) {
    // we should never get handed a negative field value
    assert value.compareTo(ZERO) >= 0;

    // Leading fields are only restricted by startPrecision.
    final int startPrecision = getStartPrecision(typeSystem);
    return startPrecision < POWERS10.length
        ? value.compareTo(POWERS10[startPrecision]) < 0
        : value.compareTo(INT_MAX_VALUE_PLUS_ONE) < 0;
  }

  private void checkLeadFieldInRange(RelDataTypeSystem typeSystem, int sign,
      BigDecimal value, TimeUnit unit, SqlParserPos pos) {
    if (!isLeadFieldInRange(typeSystem, value, unit)) {
      throw fieldExceedsPrecisionException(
          pos, sign, value, unit, getStartPrecision(typeSystem));
    }
  }

  private static final BigDecimal[] POWERS10 = {
      ZERO,
    BigDecimal.valueOf(10),
    BigDecimal.valueOf(100),
    BigDecimal.valueOf(1000),
    BigDecimal.valueOf(10000),
    BigDecimal.valueOf(100000),
    BigDecimal.valueOf(1000000),
    BigDecimal.valueOf(10000000),
    BigDecimal.valueOf(100000000),
    BigDecimal.valueOf(1000000000),
  };

  private static boolean isFractionalSecondFieldInRange(BigDecimal field) {
    // we should never get handed a negative field value
    assert field.compareTo(ZERO) >= 0;

    // Fractional second fields are only restricted by precision, which
    // has already been checked for using pattern matching.
    // Therefore, always return true
    return true;
  }

  private static boolean isSecondaryFieldInRange(BigDecimal field, TimeUnit unit) {
    // we should never get handed a negative field value
    assert field.compareTo(ZERO) >= 0;

    // YEAR and DAY can never be secondary units,
    // nor can unit be null.
    assert unit != null;
    switch (unit) {
    case YEAR:
    case DAY:
    default:
      throw Util.unexpected(unit);

      // Secondary field limits, as per section 4.6.3 of SQL2003 spec
    case MONTH:
    case HOUR:
    case MINUTE:
    case SECOND:
      return unit.isValidValue(field);
    }
  }

  private static BigDecimal normalizeSecondFraction(String secondFracStr) {
    // Decimal value can be more than 3 digits. So just get
    // the millisecond part.
    return new BigDecimal("0." + secondFracStr).multiply(THOUSAND);
  }

  private static int[] fillIntervalValueArray(
      int sign,
      BigDecimal year,
      BigDecimal month) {
    int[] ret = new int[3];

    ret[0] = sign;
    ret[1] = year.intValue();
    ret[2] = month.intValue();

    return ret;
  }

  private static int[] fillIntervalValueArray(
      int sign,
      BigDecimal day,
      BigDecimal hour,
      BigDecimal minute,
      BigDecimal second,
      BigDecimal secondFrac) {
    int[] ret = new int[6];

    ret[0] = sign;
    ret[1] = day.intValue();
    ret[2] = hour.intValue();
    ret[3] = minute.intValue();
    ret[4] = second.intValue();
    ret[5] = secondFrac.intValue();

    return ret;
  }

  /**
   * Validates an INTERVAL literal against a YEAR interval qualifier.
   *
   * @throws org.apache.calcite.runtime.CalciteContextException if the interval
   * value is illegal
   */
  private int[] evaluateIntervalLiteralAsYear(
      RelDataTypeSystem typeSystem, int sign,
      String value,
      String originalValue,
      SqlParserPos pos) {
    BigDecimal year;

    // validate as YEAR(startPrecision), e.g. 'YY'
    String intervalPattern = "(\\d+)";

    Matcher m = Pattern.compile(intervalPattern).matcher(value);
    if (m.matches()) {
      // Break out  field values
      try {
        year = parseField(m, 1);
      } catch (NumberFormatException e) {
        throw invalidValueException(pos, originalValue);
      }

      // Validate individual fields
      checkLeadFieldInRange(typeSystem, sign, year, TimeUnit.YEAR, pos);

      // package values up for return
      return fillIntervalValueArray(sign, year, ZERO);
    } else {
      throw invalidValueException(pos, originalValue);
    }
  }

  /**
   * Validates an INTERVAL literal against a YEAR TO MONTH interval qualifier.
   *
   * @throws org.apache.calcite.runtime.CalciteContextException if the interval
   * value is illegal
   */
  private int[] evaluateIntervalLiteralAsYearToMonth(
      RelDataTypeSystem typeSystem, int sign,
      String value,
      String originalValue,
      SqlParserPos pos) {
    BigDecimal year;
    BigDecimal month;

    // validate as YEAR(startPrecision) TO MONTH, e.g. 'YY-DD'
    String intervalPattern = "(\\d+)-(\\d{1,2})";

    Matcher m = Pattern.compile(intervalPattern).matcher(value);
    if (m.matches()) {
      // Break out  field values
      try {
        year = parseField(m, 1);
        month = parseField(m, 2);
      } catch (NumberFormatException e) {
        throw invalidValueException(pos, originalValue);
      }

      // Validate individual fields
      checkLeadFieldInRange(typeSystem, sign, year, TimeUnit.YEAR, pos);
      if (!isSecondaryFieldInRange(month, TimeUnit.MONTH)) {
        throw invalidValueException(pos, originalValue);
      }

      // package values up for return
      return fillIntervalValueArray(sign, year, month);
    } else {
      throw invalidValueException(pos, originalValue);
    }
  }

  /**
   * Validates an INTERVAL literal against a MONTH interval qualifier.
   *
   * @throws org.apache.calcite.runtime.CalciteContextException if the interval
   * value is illegal
   */
  private int[] evaluateIntervalLiteralAsMonth(
      RelDataTypeSystem typeSystem, int sign,
      String value,
      String originalValue,
      SqlParserPos pos) {
    BigDecimal month;

    // validate as MONTH(startPrecision), e.g. 'MM'
    String intervalPattern = "(\\d+)";

    Matcher m = Pattern.compile(intervalPattern).matcher(value);
    if (m.matches()) {
      // Break out  field values
      try {
        month = parseField(m, 1);
      } catch (NumberFormatException e) {
        throw invalidValueException(pos, originalValue);
      }

      // Validate individual fields
      checkLeadFieldInRange(typeSystem, sign, month, TimeUnit.MONTH, pos);

      // package values up for return
      return fillIntervalValueArray(sign, ZERO, month);
    } else {
      throw invalidValueException(pos, originalValue);
    }
  }

  /**
   * Validates an INTERVAL literal against a QUARTER interval qualifier.
   *
   * @throws org.apache.calcite.runtime.CalciteContextException if the interval
   * value is illegal
   */
  private int[] evaluateIntervalLiteralAsQuarter(
      RelDataTypeSystem typeSystem, int sign,
      String value,
      String originalValue,
      SqlParserPos pos) {
    BigDecimal quarter;

    // validate as QUARTER(startPrecision), e.g. 'MM'
    String intervalPattern = "(\\d+)";

    Matcher m = Pattern.compile(intervalPattern).matcher(value);
    if (m.matches()) {
      // Break out  field values
      try {
        quarter = parseField(m, 1);
      } catch (NumberFormatException e) {
        throw invalidValueException(pos, originalValue);
      }

      // Validate individual fields
      checkLeadFieldInRange(typeSystem, sign, quarter, TimeUnit.QUARTER, pos);

      // package values up for return
      return fillIntervalValueArray(sign, ZERO, quarter);
    } else {
      throw invalidValueException(pos, originalValue);
    }
  }

  /**
   * Validates an INTERVAL literal against a WEEK interval qualifier.
   *
   * @throws org.apache.calcite.runtime.CalciteContextException if the interval
   * value is illegal
   */
  private int[] evaluateIntervalLiteralAsWeek(
      RelDataTypeSystem typeSystem, int sign,
      String value,
      String originalValue,
      SqlParserPos pos) {
    BigDecimal week;

    // validate as WEEK(startPrecision), e.g. 'MM'
    String intervalPattern = "(\\d+)";

    Matcher m = Pattern.compile(intervalPattern).matcher(value);
    if (m.matches()) {
      // Break out  field values
      try {
        week = parseField(m, 1);
      } catch (NumberFormatException e) {
        throw invalidValueException(pos, originalValue);
      }

      // Validate individual fields
      checkLeadFieldInRange(typeSystem, sign, week, TimeUnit.WEEK, pos);

      // package values up for return
      return fillIntervalValueArray(sign, ZERO, week);
    } else {
      throw invalidValueException(pos, originalValue);
    }
  }

  /**
   * Validates an INTERVAL literal against a DAY interval qualifier.
   *
   * @throws org.apache.calcite.runtime.CalciteContextException if the interval
   * value is illegal
   */
  private int[] evaluateIntervalLiteralAsDay(
      RelDataTypeSystem typeSystem, int sign,
      String value,
      String originalValue,
      SqlParserPos pos) {
    BigDecimal day;

    // validate as DAY(startPrecision), e.g. 'DD'
    String intervalPattern = "(\\d+)";

    Matcher m = Pattern.compile(intervalPattern).matcher(value);
    if (m.matches()) {
      // Break out  field values
      try {
        day = parseField(m, 1);
      } catch (NumberFormatException e) {
        throw invalidValueException(pos, originalValue);
      }

      // Validate individual fields
      checkLeadFieldInRange(typeSystem, sign, day, TimeUnit.DAY, pos);

      // package values up for return
      return fillIntervalValueArray(sign, day, ZERO, ZERO, ZERO, ZERO);
    } else {
      throw invalidValueException(pos, originalValue);
    }
  }

  /**
   * Validates an INTERVAL literal against a DAY TO HOUR interval qualifier.
   *
   * @throws org.apache.calcite.runtime.CalciteContextException if the interval
   * value is illegal
   */
  private int[] evaluateIntervalLiteralAsDayToHour(
      RelDataTypeSystem typeSystem, int sign,
      String value,
      String originalValue,
      SqlParserPos pos) {
    BigDecimal day;
    BigDecimal hour;

    // validate as DAY(startPrecision) TO HOUR, e.g. 'DD HH'
    String intervalPattern = "(\\d+) (\\d{1,2})";

    Matcher m = Pattern.compile(intervalPattern).matcher(value);
    if (m.matches()) {
      // Break out  field values
      try {
        day = parseField(m, 1);
        hour = parseField(m, 2);
      } catch (NumberFormatException e) {
        throw invalidValueException(pos, originalValue);
      }

      // Validate individual fields
      checkLeadFieldInRange(typeSystem, sign, day, TimeUnit.DAY, pos);
      if (!isSecondaryFieldInRange(hour, TimeUnit.HOUR)) {
        throw invalidValueException(pos, originalValue);
      }

      // package values up for return
      return fillIntervalValueArray(sign, day, hour, ZERO, ZERO, ZERO);
    } else {
      throw invalidValueException(pos, originalValue);
    }
  }

  /**
   * Validates an INTERVAL literal against a DAY TO MINUTE interval qualifier.
   *
   * @throws org.apache.calcite.runtime.CalciteContextException if the interval
   * value is illegal
   */
  private int[] evaluateIntervalLiteralAsDayToMinute(
      RelDataTypeSystem typeSystem, int sign,
      String value,
      String originalValue,
      SqlParserPos pos) {
    BigDecimal day;
    BigDecimal hour;
    BigDecimal minute;

    // validate as DAY(startPrecision) TO MINUTE, e.g. 'DD HH:MM'
    String intervalPattern = "(\\d+) (\\d{1,2}):(\\d{1,2})";

    Matcher m = Pattern.compile(intervalPattern).matcher(value);
    if (m.matches()) {
      // Break out  field values
      try {
        day = parseField(m, 1);
        hour = parseField(m, 2);
        minute = parseField(m, 3);
      } catch (NumberFormatException e) {
        throw invalidValueException(pos, originalValue);
      }

      // Validate individual fields
      checkLeadFieldInRange(typeSystem, sign, day, TimeUnit.DAY, pos);
      if (!isSecondaryFieldInRange(hour, TimeUnit.HOUR)
          || !isSecondaryFieldInRange(minute, TimeUnit.MINUTE)) {
        throw invalidValueException(pos, originalValue);
      }

      // package values up for return
      return fillIntervalValueArray(sign, day, hour, minute, ZERO, ZERO);
    } else {
      throw invalidValueException(pos, originalValue);
    }
  }

  /**
   * Validates an INTERVAL literal against a DAY TO SECOND interval qualifier.
   *
   * @throws org.apache.calcite.runtime.CalciteContextException if the interval
   * value is illegal
   */
  private int[] evaluateIntervalLiteralAsDayToSecond(
      RelDataTypeSystem typeSystem, int sign,
      String value,
      String originalValue,
      SqlParserPos pos) {
    BigDecimal day;
    BigDecimal hour;
    BigDecimal minute;
    BigDecimal second;
    BigDecimal secondFrac;
    boolean hasFractionalSecond;

    // validate as DAY(startPrecision) TO MINUTE,
    // e.g. 'DD HH:MM:SS' or 'DD HH:MM:SS.SSS'
    // Note: must check two patterns, since fractional second is optional
    final int fractionalSecondPrecision =
        getFractionalSecondPrecision(typeSystem);
    String intervalPatternWithFracSec =
        "(\\d+) (\\d{1,2}):(\\d{1,2}):(\\d{1,2})\\.(\\d{0,"
        + fractionalSecondPrecision + "})";
    String intervalPatternWithoutFracSec =
        "(\\d+) (\\d{1,2}):(\\d{1,2}):(\\d{1,2})";

    Matcher m = Pattern.compile(intervalPatternWithFracSec).matcher(value);
    if (m.matches()) {
      hasFractionalSecond = true;
    } else {
      m = Pattern.compile(intervalPatternWithoutFracSec).matcher(value);
      hasFractionalSecond = false;
    }

    if (m.matches()) {
      // Break out  field values
      try {
        day = parseField(m, 1);
        hour = parseField(m, 2);
        minute = parseField(m, 3);
        second = parseField(m, 4);
      } catch (NumberFormatException e) {
        throw invalidValueException(pos, originalValue);
      }

      if (hasFractionalSecond) {
        secondFrac = normalizeSecondFraction(castNonNull(m.group(5)));
      } else {
        secondFrac = ZERO;
      }

      // Validate individual fields
      checkLeadFieldInRange(typeSystem, sign, day, TimeUnit.DAY, pos);
      if (!isSecondaryFieldInRange(hour, TimeUnit.HOUR)
          || !isSecondaryFieldInRange(minute, TimeUnit.MINUTE)
          || !isSecondaryFieldInRange(second, TimeUnit.SECOND)
          || !isFractionalSecondFieldInRange(secondFrac)) {
        throw invalidValueException(pos, originalValue);
      }

      // package values up for return
      return fillIntervalValueArray(
          sign,
          day,
          hour,
          minute,
          second,
          secondFrac);
    } else {
      throw invalidValueException(pos, originalValue);
    }
  }

  /**
   * Validates an INTERVAL literal against an HOUR interval qualifier.
   *
   * @throws org.apache.calcite.runtime.CalciteContextException if the interval
   * value is illegal
   */
  private int[] evaluateIntervalLiteralAsHour(
      RelDataTypeSystem typeSystem, int sign,
      String value,
      String originalValue,
      SqlParserPos pos) {
    BigDecimal hour;

    // validate as HOUR(startPrecision), e.g. 'HH'
    String intervalPattern = "(\\d+)";

    Matcher m = Pattern.compile(intervalPattern).matcher(value);
    if (m.matches()) {
      // Break out  field values
      try {
        hour = parseField(m, 1);
      } catch (NumberFormatException e) {
        throw invalidValueException(pos, originalValue);
      }

      // Validate individual fields
      checkLeadFieldInRange(typeSystem, sign, hour, TimeUnit.HOUR, pos);

      // package values up for return
      return fillIntervalValueArray(sign, ZERO, hour, ZERO, ZERO, ZERO);
    } else {
      throw invalidValueException(pos, originalValue);
    }
  }

  /**
   * Validates an INTERVAL literal against an HOUR TO MINUTE interval
   * qualifier.
   *
   * @throws org.apache.calcite.runtime.CalciteContextException if the interval
   * value is illegal
   */
  private int[] evaluateIntervalLiteralAsHourToMinute(
      RelDataTypeSystem typeSystem, int sign,
      String value,
      String originalValue,
      SqlParserPos pos) {
    BigDecimal hour;
    BigDecimal minute;

    // validate as HOUR(startPrecision) TO MINUTE, e.g. 'HH:MM'
    String intervalPattern = "(\\d+):(\\d{1,2})";

    Matcher m = Pattern.compile(intervalPattern).matcher(value);
    if (m.matches()) {
      // Break out  field values
      try {
        hour = parseField(m, 1);
        minute = parseField(m, 2);
      } catch (NumberFormatException e) {
        throw invalidValueException(pos, originalValue);
      }

      // Validate individual fields
      checkLeadFieldInRange(typeSystem, sign, hour, TimeUnit.HOUR, pos);
      if (!isSecondaryFieldInRange(minute, TimeUnit.MINUTE)) {
        throw invalidValueException(pos, originalValue);
      }

      // package values up for return
      return fillIntervalValueArray(sign, ZERO, hour, minute, ZERO, ZERO);
    } else {
      throw invalidValueException(pos, originalValue);
    }
  }

  /**
   * Validates an INTERVAL literal against an HOUR TO SECOND interval
   * qualifier.
   *
   * @throws org.apache.calcite.runtime.CalciteContextException if the interval
   * value is illegal
   */
  private int[] evaluateIntervalLiteralAsHourToSecond(
      RelDataTypeSystem typeSystem, int sign,
      String value,
      String originalValue,
      SqlParserPos pos) {
    BigDecimal hour;
    BigDecimal minute;
    BigDecimal second;
    BigDecimal secondFrac;
    boolean hasFractionalSecond;

    // validate as HOUR(startPrecision) TO SECOND,
    // e.g. 'HH:MM:SS' or 'HH:MM:SS.SSS'
    // Note: must check two patterns, since fractional second is optional
    final int fractionalSecondPrecision =
        getFractionalSecondPrecision(typeSystem);
    String intervalPatternWithFracSec =
        "(\\d+):(\\d{1,2}):(\\d{1,2})\\.(\\d{0,"
        + fractionalSecondPrecision + "})";
    String intervalPatternWithoutFracSec =
        "(\\d+):(\\d{1,2}):(\\d{1,2})";

    Matcher m = Pattern.compile(intervalPatternWithFracSec).matcher(value);
    if (m.matches()) {
      hasFractionalSecond = true;
    } else {
      m = Pattern.compile(intervalPatternWithoutFracSec).matcher(value);
      hasFractionalSecond = false;
    }

    if (m.matches()) {
      // Break out  field values
      try {
        hour = parseField(m, 1);
        minute = parseField(m, 2);
        second = parseField(m, 3);
      } catch (NumberFormatException e) {
        throw invalidValueException(pos, originalValue);
      }

      if (hasFractionalSecond) {
        secondFrac = normalizeSecondFraction(castNonNull(m.group(4)));
      } else {
        secondFrac = ZERO;
      }

      // Validate individual fields
      checkLeadFieldInRange(typeSystem, sign, hour, TimeUnit.HOUR, pos);
      if (!isSecondaryFieldInRange(minute, TimeUnit.MINUTE)
          || !isSecondaryFieldInRange(second, TimeUnit.SECOND)
          || !isFractionalSecondFieldInRange(secondFrac)) {
        throw invalidValueException(pos, originalValue);
      }

      // package values up for return
      return fillIntervalValueArray(
          sign,
          ZERO,
          hour,
          minute,
          second,
          secondFrac);
    } else {
      throw invalidValueException(pos, originalValue);
    }
  }

  /**
   * Validates an INTERVAL literal against an MINUTE interval qualifier.
   *
   * @throws org.apache.calcite.runtime.CalciteContextException if the interval
   * value is illegal
   */
  private int[] evaluateIntervalLiteralAsMinute(
      RelDataTypeSystem typeSystem, int sign,
      String value,
      String originalValue,
      SqlParserPos pos) {
    BigDecimal minute;

    // validate as MINUTE(startPrecision), e.g. 'MM'
    String intervalPattern = "(\\d+)";

    Matcher m = Pattern.compile(intervalPattern).matcher(value);
    if (m.matches()) {
      // Break out  field values
      try {
        minute = parseField(m, 1);
      } catch (NumberFormatException e) {
        throw invalidValueException(pos, originalValue);
      }

      // Validate individual fields
      checkLeadFieldInRange(typeSystem, sign, minute, TimeUnit.MINUTE, pos);

      // package values up for return
      return fillIntervalValueArray(sign, ZERO, ZERO, minute, ZERO, ZERO);
    } else {
      throw invalidValueException(pos, originalValue);
    }
  }

  /**
   * Validates an INTERVAL literal against an MINUTE TO SECOND interval
   * qualifier.
   *
   * @throws org.apache.calcite.runtime.CalciteContextException if the interval
   * value is illegal
   */
  private int[] evaluateIntervalLiteralAsMinuteToSecond(
      RelDataTypeSystem typeSystem, int sign,
      String value,
      String originalValue,
      SqlParserPos pos) {
    BigDecimal minute;
    BigDecimal second;
    BigDecimal secondFrac;
    boolean hasFractionalSecond;

    // validate as MINUTE(startPrecision) TO SECOND,
    // e.g. 'MM:SS' or 'MM:SS.SSS'
    // Note: must check two patterns, since fractional second is optional
    final int fractionalSecondPrecision =
        getFractionalSecondPrecision(typeSystem);
    String intervalPatternWithFracSec =
        "(\\d+):(\\d{1,2})\\.(\\d{0," + fractionalSecondPrecision + "})";
    String intervalPatternWithoutFracSec =
        "(\\d+):(\\d{1,2})";

    Matcher m = Pattern.compile(intervalPatternWithFracSec).matcher(value);
    if (m.matches()) {
      hasFractionalSecond = true;
    } else {
      m = Pattern.compile(intervalPatternWithoutFracSec).matcher(value);
      hasFractionalSecond = false;
    }

    if (m.matches()) {
      // Break out  field values
      try {
        minute = parseField(m, 1);
        second = parseField(m, 2);
      } catch (NumberFormatException e) {
        throw invalidValueException(pos, originalValue);
      }

      if (hasFractionalSecond) {
        secondFrac = normalizeSecondFraction(castNonNull(m.group(3)));
      } else {
        secondFrac = ZERO;
      }

      // Validate individual fields
      checkLeadFieldInRange(typeSystem, sign, minute, TimeUnit.MINUTE, pos);
      if (!isSecondaryFieldInRange(second, TimeUnit.SECOND)
          || !isFractionalSecondFieldInRange(secondFrac)) {
        throw invalidValueException(pos, originalValue);
      }

      // package values up for return
      return fillIntervalValueArray(
          sign,
          ZERO,
          ZERO,
          minute,
          second,
          secondFrac);
    } else {
      throw invalidValueException(pos, originalValue);
    }
  }

  /**
   * Validates an INTERVAL literal against an SECOND interval qualifier.
   *
   * @throws org.apache.calcite.runtime.CalciteContextException if the interval
   * value is illegal
   */
  private int[] evaluateIntervalLiteralAsSecond(
      RelDataTypeSystem typeSystem,
      int sign,
      String value,
      String originalValue,
      SqlParserPos pos) {
    BigDecimal second;
    BigDecimal secondFrac;
    boolean hasFractionalSecond;

    // validate as SECOND(startPrecision, fractionalSecondPrecision)
    // e.g. 'SS' or 'SS.SSS'
    // Note: must check two patterns, since fractional second is optional
    final int fractionalSecondPrecision =
        getFractionalSecondPrecision(typeSystem);
    String intervalPatternWithFracSec =
        "(\\d+)\\.(\\d{0," + fractionalSecondPrecision + "})";
    String intervalPatternWithoutFracSec =
        "(\\d+)";

    Matcher m = Pattern.compile(intervalPatternWithFracSec).matcher(value);
    if (m.matches()) {
      hasFractionalSecond = true;
    } else {
      m = Pattern.compile(intervalPatternWithoutFracSec).matcher(value);
      hasFractionalSecond = false;
    }

    if (m.matches()) {
      // Break out  field values
      try {
        second = parseField(m, 1);
      } catch (NumberFormatException e) {
        throw invalidValueException(pos, originalValue);
      }

      if (hasFractionalSecond) {
        secondFrac = normalizeSecondFraction(castNonNull(m.group(2)));
      } else {
        secondFrac = ZERO;
      }

      // Validate individual fields
      checkLeadFieldInRange(typeSystem, sign, second, TimeUnit.SECOND, pos);
      if (!isFractionalSecondFieldInRange(secondFrac)) {
        throw invalidValueException(pos, originalValue);
      }

      // package values up for return
      return fillIntervalValueArray(
          sign, ZERO, ZERO, ZERO, second, secondFrac);
    } else {
      throw invalidValueException(pos, originalValue);
    }
  }

  /**
   * Validates an INTERVAL literal according to the rules specified by the
   * interval qualifier. The assumption is made that the interval qualifier has
   * been validated prior to calling this method. Evaluating against an
   * invalid qualifier could lead to strange results.
   *
   * @return field values, never null
   *
   * @throws org.apache.calcite.runtime.CalciteContextException if the interval
   * value is illegal
   */
  public int[] evaluateIntervalLiteral(String value, SqlParserPos pos,
      RelDataTypeSystem typeSystem) {
    // save original value for if we have to throw
    final String value0 = value;

    // First strip off any leading whitespace
    value = value.trim();

    // check if the sign was explicitly specified.  Record
    // the explicit or implicit sign, and strip it off to
    // simplify pattern matching later.
    final int sign = getIntervalSign(value);
    value = stripLeadingSign(value);

    // If we have an empty or null literal at this point,
    // it's illegal.  Complain and bail out.
    if (Util.isNullOrEmpty(value)) {
      throw invalidValueException(pos, value0);
    }

    // Validate remaining string according to the pattern
    // that corresponds to the start and end units as
    // well as explicit or implicit precision and range.
    switch (timeUnitRange) {
    case YEAR:
      return evaluateIntervalLiteralAsYear(typeSystem, sign, value, value0,
          pos);
    case YEAR_TO_MONTH:
      return evaluateIntervalLiteralAsYearToMonth(typeSystem, sign, value,
          value0, pos);
    case MONTH:
      return evaluateIntervalLiteralAsMonth(typeSystem, sign, value, value0,
          pos);
    case QUARTER:
      return evaluateIntervalLiteralAsQuarter(typeSystem, sign, value, value0,
          pos);
    case WEEK:
      return evaluateIntervalLiteralAsWeek(typeSystem, sign, value, value0,
          pos);
    case DAY:
      return evaluateIntervalLiteralAsDay(typeSystem, sign, value, value0, pos);
    case DAY_TO_HOUR:
      return evaluateIntervalLiteralAsDayToHour(typeSystem, sign, value, value0,
          pos);
    case DAY_TO_MINUTE:
      return evaluateIntervalLiteralAsDayToMinute(typeSystem, sign, value,
          value0, pos);
    case DAY_TO_SECOND:
      return evaluateIntervalLiteralAsDayToSecond(typeSystem, sign, value,
          value0, pos);
    case HOUR:
      return evaluateIntervalLiteralAsHour(typeSystem, sign, value, value0,
          pos);
    case HOUR_TO_MINUTE:
      return evaluateIntervalLiteralAsHourToMinute(typeSystem, sign, value,
          value0, pos);
    case HOUR_TO_SECOND:
      return evaluateIntervalLiteralAsHourToSecond(typeSystem, sign, value,
          value0, pos);
    case MINUTE:
      return evaluateIntervalLiteralAsMinute(typeSystem, sign, value, value0,
          pos);
    case MINUTE_TO_SECOND:
      return evaluateIntervalLiteralAsMinuteToSecond(typeSystem, sign, value,
          value0, pos);
    case SECOND:
      return evaluateIntervalLiteralAsSecond(typeSystem, sign, value, value0,
          pos);
    default:
      throw invalidValueException(pos, value0);
    }
  }

  private static BigDecimal parseField(Matcher m, int i) {
    return new BigDecimal(castNonNull(m.group(i)));
  }

  private CalciteContextException invalidValueException(SqlParserPos pos,
      String value) {
    return SqlUtil.newContextException(pos,
        RESOURCE.unsupportedIntervalLiteral(
            "'" + value + "'", "INTERVAL " + toString()));
  }

  private static CalciteContextException fieldExceedsPrecisionException(
      SqlParserPos pos, int sign, BigDecimal value, TimeUnit type,
      int precision) {
    if (sign == -1) {
      value = value.negate();
    }
    return SqlUtil.newContextException(pos,
        RESOURCE.intervalFieldExceedsPrecision(
            value, type.name() + "(" + precision + ")"));
  }

  /** Converts a {@link SqlIntervalQualifier} to a
   * {@link org.apache.calcite.sql.SqlIdentifier} if it is a time frame
   * reference.
   *
   * <p>Helps with unparsing of EXTRACT, FLOOR, CEIL functions. */
  public static SqlNode asIdentifier(SqlNode node) {
    if (node instanceof SqlIntervalQualifier) {
      SqlIntervalQualifier intervalQualifier = (SqlIntervalQualifier) node;
      if (intervalQualifier.timeFrameName != null) {
        return new SqlIdentifier(intervalQualifier.timeFrameName,
            node.getParserPosition());
      }
    }
    return node;
  }
}
