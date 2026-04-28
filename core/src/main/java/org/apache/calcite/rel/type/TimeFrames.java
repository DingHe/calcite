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
package org.apache.calcite.rel.type;

import org.apache.calcite.avatica.util.DateTimeUtils;
import org.apache.calcite.avatica.util.TimeUnit;
import org.apache.calcite.avatica.util.TimeUnitRange;
import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.util.MonotonicSupplier;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.TimestampString;

import org.apache.commons.math3.fraction.BigFraction;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigInteger;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.apache.calcite.avatica.util.DateTimeUtils.EPOCH_JULIAN;

import static java.lang.Math.floorDiv;
import static java.lang.Math.floorMod;
import static java.util.Objects.requireNonNull;

/** Utilities for {@link TimeFrame}. */
// TimeFrames 是一个工具类和实现容器。它不仅定义了系统中默认支持的所有时间单位（如秒、分、年、ISO周等），还提供了构建和操作这些单位的底层算法。
// TimeFrames 的主要作用可以概括为以下三点：
// 定义标准时间体系 (CORE)：它预定义了 SQL 标准和常见的非标时间框架（如 WEEK_SUNDAY 到 WEEK_SATURDAY，ISOYEAR 等）。
// 提供构建器实现 (BuilderImpl)：它是 TimeFrameSet.Builder 接口的具体实现，负责维护时间框架之间的拓扑结构（防止循环定义）。
// 计算工具集：提供了一系列静态工具方法，用于处理复杂的日期计算（如 ISO 年份的对齐、月份与年份的线性转换、单位是否可整除的判定等）。
// 本质上是为了解决计算机在处理“人类时间”时的模糊性与多样性。如果只用“秒”来衡量一切，计算机处理起来很简单，但 SQL 业务逻辑会崩溃。
// 为什么要设计这套复杂体系的核心原因：
// 1. 解决“不规则”的换算关系
// 在物理学中，单位换算通常是恒定的（如 1 米永远等于 100 厘米）。但在时间领域：
// 月与天：1 个月可能是 28、29、30 或 31 天。
// 年与天：1 年可能是 365 或 366 天。
// 周与月：一个月并不包含整数个周。
// TimeFrames 通过定义不同的 CoreFrame（如 MONTH 系统和 SECOND 系统）将这些不可直接换算的单位隔离开，并通过 BigFraction（分数）和特殊的 Epoch（基准点）逻辑来确保在执行 FLOOR(date TO MONTH) 时，由于每个月长度不等带来的计算误差被屏蔽。
// 2.保证“聚合（Rollup）”的准确性
// 在数据分析（OLAP）中，我们经常把“天”的数据汇总成“月”或“周”。
// 合法的汇总：天 $\rightarrow$ 月。因为任何一天的开始一定是某个月的一部分，边界是对齐的。
// 非法的汇总：周 $\rightarrow$ 月。如果本周跨越了月底，直接把这一周的总计加到某个月里就会导致数据错误。
// 3. 支持全球化的“周”定义
// 不同的国家和组织对“一周从哪天开始”有完全不同的定义：
// SQL标准/美国：通常周日（Sunday）是第一天。
// ISO-8601/欧洲：周一（Monday）是第一天，且定义了特殊的 ISOYEAR。
// 商业财务：某些公司可能定义周五为一周的起始。
// 4. 统一处理“周期性序数”
// 5. 高性能的计算路径优化
public class TimeFrames {
  private TimeFrames() {
  }

  /** The names of the frames that are WEEK starting on each week day.
   * Entry 0 is "WEEK_SUNDAY" and entry 6 is "WEEK_SATURDAY". */
  // 存储一周七天作为起始日的框架名称列表（从 WEEK_SUNDAY 到 WEEK_SATURDAY）
  public static final List<String> WEEK_FRAME_NAMES =
      ImmutableList.of("WEEK_SUNDAY",
          "WEEK_MONDAY",
          "WEEK_TUESDAY",
          "WEEK_WEDNESDAY",
          "WEEK_THURSDAY",
          "WEEK_FRIDAY",
          "WEEK_SATURDAY");

  /** The core time frame set. Includes the time frames for all Avatica time
   * units plus ISOWEEK and week offset for each week day:
   *
   * <ul>
   *   <li>SECOND, and multiples MINUTE, HOUR, DAY, WEEK (starts on a Sunday),
   *   sub-multiples MILLISECOND, MICROSECOND, NANOSECOND,
   *   quotients DOY, DOW;
   *   <li>MONTH, and multiples QUARTER, YEAR, DECADE, CENTURY, MILLENNIUM;
   *   <li>ISOYEAR, and sub-unit ISOWEEK (starts on a Monday), quotient ISODOW;
   *   <li>WEEK(<i>weekday</i>) with <i>weekday</i> being one of
   *   SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY.
   * </ul>
   *
   * <p>Does not include EPOCH.
   */
  // Calcite 默认的内置时间框架集
  // 它整合了 SECOND 系统（秒、分、时、日、周、毫秒、微秒、纳秒）、MONTH 系统（月、季、年、世纪、千年）和 ISOYEAR 系统。
  public static final TimeFrameSet CORE =
      addTsi(addCore(new BuilderImpl())).build();

  private static BuilderImpl addCore(BuilderImpl b) {
    b.addCore(TimeUnit.SECOND);
    b.addSub(TimeUnit.MINUTE, false, 60, TimeUnit.SECOND);
    b.addSub(TimeUnit.HOUR, false, 60, TimeUnit.MINUTE);
    b.addSub(TimeUnit.DAY, false, 24, TimeUnit.HOUR);
    b.addSub(TimeUnit.WEEK, false, 7, TimeUnit.DAY,
        new TimestampString(1970, 1, 4, 0, 0, 0)); // a sunday
    b.addSub(TimeUnit.MILLISECOND, true, 1_000, TimeUnit.SECOND);
    b.addSub(TimeUnit.MICROSECOND, true, 1_000, TimeUnit.MILLISECOND);
    b.addSub(TimeUnit.NANOSECOND, true, 1_000, TimeUnit.MICROSECOND);

    b.addSub(TimeUnit.EPOCH, false, 1, TimeUnit.SECOND,
        new TimestampString(1970, 1, 1, 0, 0, 0));

    b.addCore(TimeUnit.MONTH);
    b.addSub(TimeUnit.QUARTER, false, 3, TimeUnit.MONTH);
    b.addSub(TimeUnit.YEAR, false, 12, TimeUnit.MONTH);
    b.addSub(TimeUnit.DECADE, false, 10, TimeUnit.YEAR);
    b.addSub(TimeUnit.CENTURY, false, 100, TimeUnit.YEAR,
        new TimestampString(2001, 1, 1, 0, 0, 0));
    b.addSub(TimeUnit.MILLENNIUM, false, 1_000, TimeUnit.YEAR,
        new TimestampString(2001, 1, 1, 0, 0, 0));

    b.addCore(TimeUnit.ISOYEAR);
    b.addSub("ISOWEEK", false, 7, TimeUnit.DAY.name(),
        new TimestampString(1970, 1, 5, 0, 0, 0)); // a monday

    // Add "WEEK(SUNDAY)" through "WEEK(SATURDAY)"
    Ord.forEach(WEEK_FRAME_NAMES, (frameName, i) ->
        b.addSub(frameName, false, 7,
            "DAY", new TimestampString(1970, 1, 4 + i, 0, 0, 0)));

    b.addQuotient(TimeUnit.DOY, TimeUnit.DAY, TimeUnit.YEAR);
    b.addQuotient(TimeUnit.DOW, TimeUnit.DAY, TimeUnit.WEEK);
    b.addQuotient(TimeUnit.ISODOW.name(), TimeUnit.DAY.name(), "ISOWEEK");

    b.addRollup(TimeUnit.DAY, TimeUnit.MONTH);
    b.addRollup("ISOWEEK", TimeUnit.ISOYEAR.name());

    return b;
  }

  /** Adds abbreviations used by {@code TIMESTAMPADD}, {@code TIMESTAMPDIFF}
   * functions. */
  private static BuilderImpl addTsi(BuilderImpl b) {
    b.addAlias("FRAC_SECOND", TimeUnit.MICROSECOND.name());
    b.addAlias("SQL_TSI_FRAC_SECOND", TimeUnit.NANOSECOND.name());
    b.addAlias("SQL_TSI_MICROSECOND", TimeUnit.MICROSECOND.name());
    b.addAlias("SQL_TSI_SECOND", TimeUnit.SECOND.name());
    b.addAlias("SQL_TSI_MINUTE", TimeUnit.MINUTE.name());
    b.addAlias("SQL_TSI_HOUR", TimeUnit.HOUR.name());
    b.addAlias("SQL_TSI_DAY", TimeUnit.DAY.name());
    b.addAlias("SQL_TSI_WEEK", TimeUnit.WEEK.name());
    b.addAlias("SQL_TSI_MONTH", TimeUnit.MONTH.name());
    b.addAlias("SQL_TSI_QUARTER", TimeUnit.QUARTER.name());
    b.addAlias("SQL_TSI_YEAR", TimeUnit.YEAR.name());
    return b;
  }

  /** Given a date, returns the date of the first day of its ISO Year.
   * Usually occurs in the same calendar year, but may be as early as Dec 29
   * of the previous calendar year.
   *
   * <p>After
   * <a href="https://issues.apache.org/jira/browse/CALCITE-5369">[CALCITE-5369]
   * In Avatica DateTimeUtils, add support for FLOOR and CEIL to ISOYEAR</a> is
   * fixed, we can use {@link DateTimeUtils#unixDateFloor} instead of this
   * method. */
  // 计算给定日期所属 ISO 年份的起始日期。
  // 由于 ISO 年份不一定从 1 月 1 日开始（而是包含该年第一个周四的那一周的周一），该方法负责处理这种特殊的对齐逻辑。
  static int floorCeilIsoYear(int date, boolean ceil) {
    final int year =
        (int) DateTimeUtils.unixDateExtract(TimeUnitRange.YEAR, date);
    return (int) firstMondayOfFirstWeek(year + (ceil ? 1 : 0)) - EPOCH_JULIAN;
  }

  /** Returns the first day of the first week of a year.
   * Per ISO-8601 it is the Monday of the week that contains Jan 4,
   * or equivalently, it is a Monday between Dec 29 and Jan 4.
   * Sometimes it is in the year before the given year. */
  // Note: copied from DateTimeUtils
  // 计算指定年份的 ISO 第一周的周一。它是 ISO 日期计算的核心。
  static long firstMondayOfFirstWeek(int year) {
    final long janFirst = DateTimeUtils.ymdToJulian(year, 1, 1);
    final long janFirstDow = floorMod(janFirst + 1, (long) 7); // sun=0, sat=6
    return janFirst + (11 - janFirstDow) % 7 - 3;
  }

  /** Returns the number of months since 1 BCE.
   *
   * <p>Parameters mean the same as in
   * {@link DateTimeUtils#ymdToJulian(int, int, int)}.
   *
   * @param year Year (e.g. 2020 means 2020 CE, 0 means 1 BCE)
   * @param month Month (e.g. 1 means January)
   */
  // 将“年-月”转换为一个连续的整数值（自公元 1 年 1 月起的总月数）。
  // 解决了月份天数不等导致的计算难题，使得月份加减变成简单的整数运算。
  static int fullMonth(int year, int month) {
    return year * 12 + (month - 1);
  }

  /** Given a {@link #fullMonth(int, int)} value, returns the month
   * (1 means January). */
  // 逆向操作，从总月数中还原出具体的月份（1-12）或年份。
  static int fullMonthToMonth(int fullMonth) {
    return floorMod(fullMonth, 12) + 1;
  }

  /** Given a {@link #fullMonth(int, int)} value, returns the year
   * (2020 means 2020 CE). */
  // 逆向操作，从总月数中还原出具体的月份（1-12）或年份。
  static int fullMonthToYear(int fullMonth) {
    return floorDiv(fullMonth, 12);
  }

  /** As {@link DateTimeUtils#unixTimestamp(int, int, int, int, int, int)}
   * but based on a fullMonth value (per {@link #fullMonth(int, int)}). */
  // 基于 fullMonth 体系构建标准的 Unix 时间戳或日期值，内部调用 DateTimeUtils。
  static long unixTimestamp(int fullMonth, int day, int hour,
      int minute, int second) {
    final int year = fullMonthToYear(fullMonth);
    final int month = fullMonthToMonth(fullMonth);
    return DateTimeUtils.unixTimestamp(year, month, day, hour, minute, second);
  }

  static int mdToUnixDate(int fullMonth, int day) {
    final int year = fullMonthToYear(fullMonth);
    final int month = fullMonthToMonth(fullMonth);
    return DateTimeUtils.ymdToUnixDate(year, month, day);
  }
  // 判定两个框架是否可以直接聚合。
  // 检查它们是否基于同一个核心框架，且比例是否整除，且偏移量（Epoch）是否对齐。
  private static boolean canDirectlyRollUp(TimeFrameImpl from,
      TimeFrameImpl to) {
    if (from.core().equals(to.core())) {
      if (divisible(from.coreMultiplier(), to.coreMultiplier())) {
        BigFraction diff = new BigFraction(from.core().epochDiff(from, to));
        return divisible(from.coreMultiplier(), diff);
      }
      return false;
    }
    return false;
  }

  /** Returns whether {@code numerator} is divisible by {@code denominator}.
   *
   * <p>For example, {@code divisible(6, 2)} returns {@code true};
   * {@code divisible(0, 2)} also returns {@code true};
   * {@code divisible(2, 6)} returns {@code false}. */
  // 判定两个分数是否具有整除关系，主要用于确定时间单位是否能完美嵌套（如 1 小时是否由整数个分钟组成）。
  private static boolean divisible(BigFraction numerator,
      BigFraction denominator) {
    return denominator.equals(BigFraction.ZERO)
        || numerator
        .divide(denominator)
        .getNumerator()
        .abs()
        .equals(BigInteger.ONE);
  }

  /** Implementation of {@link TimeFrameSet.Builder}. */
  static class BuilderImpl implements TimeFrameSet.Builder {
    BuilderImpl() {
    }

    final MonotonicSupplier<TimeFrameSet> frameSetSupplier =
        new MonotonicSupplier<>();
    final Map<String, TimeFrameImpl> map = new LinkedHashMap<>();
    final ImmutableMultimap.Builder<TimeFrameImpl, TimeFrameImpl> rollupList =
        ImmutableMultimap.builder();

    @Override public TimeFrameSet build() {
      final TimeFrameSet frameSet =
          new TimeFrameSet(ImmutableMap.copyOf(map), rollupList.build());
      frameSetSupplier.accept(frameSet);
      return frameSet;
    }

    /** Converts a number to an exactly equivalent {@code BigInteger}.
     * May silently lose precision if n is a {@code Float} or {@code Double}. */
    static BigInteger toBigInteger(Number number) {
      return number instanceof BigInteger ? (BigInteger) number
          : BigInteger.valueOf(number.longValue());
    }

    /** Returns the time frame with the given name,
     * or throws {@link IllegalArgumentException}. */
    TimeFrameImpl getFrame(String name) {
      final TimeFrameImpl timeFrame = map.get(name);
      if (timeFrame == null) {
        throw new IllegalArgumentException("unknown frame: " + name);
      }
      return timeFrame;
    }

    /** Adds a frame.
     *
     * <p>If a frame with this name already exists, throws
     * {@link IllegalArgumentException} and leaves the builder in the same
     * state.
     *
     * <p>It is very important that we don't allow replacement of frames.
     * If replacement were allowed, people would be able to create a DAG
     * (e.g. two routes from DAY to MONTH with different multipliers)
     * or a cycle (e.g. one SECOND equals 1,000 MILLISECOND
     * and one MILLISECOND equals 20 SECOND). Those scenarios give rise to
     * inconsistent multipliers. */
    private BuilderImpl addFrame(String name, TimeFrameImpl frame) {
      final TimeFrameImpl previousFrame =
          map.put(name, requireNonNull(frame, "frame"));
      if (previousFrame != null) {
        // There was already a frame with that name. Replace the old frame
        // (so that that builder is still valid usable) and throw.
        map.put(name, previousFrame);
        throw new IllegalArgumentException("duplicate frame: " + name);
      }
      return this;
    }

    @Override public BuilderImpl addCore(String name) {
      return addFrame(name, new CoreFrame(frameSetSupplier, name));
    }

    /** Defines a time unit that consists of {@code count} instances of
     * {@code baseUnit}. */
    BuilderImpl addSub(String name, boolean divide, Number count,
        String baseName, TimestampString epoch) {
      final TimeFrameImpl baseFrame = getFrame(baseName);
      final BigInteger factor = toBigInteger(count);

      final CoreFrame coreFrame = baseFrame.core();
      final BigFraction coreFactor = divide
          ? baseFrame.coreMultiplier().divide(factor)
          : baseFrame.coreMultiplier().multiply(factor);

      return addFrame(name,
          new SubFrame(name, baseFrame, divide, factor, coreFrame, coreFactor,
              epoch));
    }

    @Override public BuilderImpl addQuotient(String name,
        String minorName, String majorName) {
      final TimeFrameImpl minorFrame = getFrame(minorName);
      final TimeFrameImpl majorFrame = getFrame(majorName);
      return addFrame(name, new QuotientFrame(name, minorFrame, majorFrame));
    }

    @Override public BuilderImpl addMultiple(String name, Number count,
        String baseName) {
      return addSub(name, false, count, baseName, TimestampString.EPOCH);
    }

    @Override public BuilderImpl addDivision(String name, Number count,
        String baseName) {
      return addSub(name, true, count, baseName, TimestampString.EPOCH);
    }

    @Override public BuilderImpl addRollup(String fromName, String toName) {
      final TimeFrameImpl fromFrame = getFrame(fromName);
      final TimeFrameImpl toFrame = getFrame(toName);
      rollupList.put(fromFrame, toFrame);
      return this;
    }

    @Override public BuilderImpl addAll(TimeFrameSet timeFrameSet) {
      timeFrameSet.map.values().forEach(frame -> frame.replicate(this));
      return this;
    }

    @Override public BuilderImpl withEpoch(TimestampString epoch) {
      final Map.Entry<String, TimeFrameImpl> entry =
          Iterables.getLast(map.entrySet());
      final String name = entry.getKey();
      final SubFrame value =
          requireNonNull((SubFrame) map.remove(name));
      value.replicateWithEpoch(this, epoch);
      return this;
    }

    @Override public BuilderImpl addAlias(String name, String originalName) {
      final TimeFrameImpl frame = getFrame(originalName);
      return addFrame(name, new AliasFrame(name, frame));
    }

    // Extra methods for Avatica's built-in time frames.

    void addCore(TimeUnit unit) {
      addCore(unit.name());
    }

    void addSub(TimeUnit unit, boolean divide, Number count,
        TimeUnit baseUnit) {
      addSub(unit, divide, count, baseUnit, TimestampString.EPOCH);
    }

    void addSub(TimeUnit unit, boolean divide, Number count,
        TimeUnit baseUnit, TimestampString epoch) {
      addSub(unit.name(), divide, count, baseUnit.name(), epoch);
    }

    void addRollup(TimeUnit fromUnit, TimeUnit toUnit) {
      addRollup(fromUnit.name(), toUnit.name());
    }

    void addQuotient(TimeUnit unit, TimeUnit minor, TimeUnit major) {
      addQuotient(unit.name(), minor.name(), major.name());
    }
  }

  /** Implementation of {@link TimeFrame}. */
  // TimeFrameImpl 是 TimeFrame 接口的核心抽象实现类。
  // 它为各种具体的时间框架（如核心单位、倍数单位、除法单位等）提供了通用的逻辑处理。
  // TimeFrameImpl 的主要作用是实现时间框架之间的数学换算逻辑和聚合（Rollup）规则。
  abstract static class TimeFrameImpl implements TimeFrame {
    // 存储时间框架的名称（如 "YEAR", "MINUTE15"）。
    final String name;
    // 延迟获取所属的 TimeFrameSet。
    final Supplier<TimeFrameSet> frameSetSupplier;

    TimeFrameImpl(Supplier<TimeFrameSet> frameSetSupplier, String name) {
      this.frameSetSupplier =
          requireNonNull(frameSetSupplier, "frameSetSupplier");
      this.name = requireNonNull(name, "name");
    }
    // 返回该框架的名称，便于调试和日志记录。
    @Override public String toString() {
      return name;
    }

    @Override public TimeFrameSet frameSet() {
      return frameSetSupplier.get();
    }

    @Override public String name() {
      return name;
    }
    // 计算“一个 timeFrame 包含多少个当前框架”。
    // 算法逻辑：
    // 1、通过 expand 方法将“自己”和“目标框架”分别展开为它们所基于的底层单位映射表。
    // 2、对比两张映射表，寻找它们共同拥有的基准单位（例如：分钟和小时共同基于“秒”）。
    // 3、如果找到公共单位，利用比例计算出结果；如果没有公共单位，说明两个框架不可换算（返回 null）。
    // 4、如果存在多个公共单位但比例不一致，说明系统定义存在冲突，抛出 AssertionError。
    @Override public @Nullable BigFraction per(TimeFrame timeFrame) {
      // Note: The following algorithm is not very efficient. It becomes less
      // efficient as the number of time frames increases. A more efficient
      // algorithm would be for TimeFrameSet.Builder.build() to call this method
      // for each pair of time frames and cache the results in a list:
      //
      //   (coreFrame,
      //   [(subFrame0, multiplier0),
      //    ...
      //    (subFrameN, multiplierN)])

      final Map<TimeFrame, BigFraction> map = new HashMap<>();
      final Map<TimeFrame, BigFraction> map2 = new HashMap<>();
      expand(map, BigFraction.ONE);
      ((TimeFrameImpl) timeFrame).expand(map2, BigFraction.ONE);
      final Set<BigFraction> fractions = new HashSet<>();
      for (Map.Entry<TimeFrame, BigFraction> entry : map.entrySet()) {
        final BigFraction value2 = map2.get(entry.getKey());
        if (value2 != null) {
          fractions.add(value2.divide(entry.getValue()));
        }
      }

      switch (fractions.size()) {
      case 0:
        // There is no path from this TimeFrame to that.
        return null;
      case 1:
        return Iterables.getOnlyElement(fractions);
      default:
        // If there are multiple units in common, the multipliers must be the
        // same for all. If they are not, the must have somehow created a
        // TimeFrameSet that has multiple paths between units (i.e. a DAG),
        // or has a cycle. TimeFrameSet.Builder is supposed to prevent all of
        // these, and so we throw an AssertionError.
        throw new AssertionError("inconsistent multipliers for " + this
            + ".per(" + timeFrame + "): " + fractions);
      }
    }
    // 递归展开时间框架的构成。
    protected void expand(Map<TimeFrame, BigFraction> map, BigFraction f) {
      map.put(this, f);
    }

    /** Adds a time frame like this to a builder. */
    // 深度拷贝/复制。
    // 允许将一个已有的时间框架定义完整地复制到另一个 Builder 中，用于构建新的 TimeFrameSet。
    abstract void replicate(BuilderImpl b);
    // 返回该框架最终指向的“核心框架”（CoreFrame）。例如，所有基于时间的框架最终核心可能是“毫秒”。
    protected abstract CoreFrame core();
    // 返回该框架相对于其核心框架的乘数。
    protected abstract BigFraction coreMultiplier();
    // 判断数据能否从当前粒度聚合到目标粒度。
    @Override public boolean canRollUpTo(TimeFrame toFrame) {
      if (toFrame == this) {
        return true;
      }
      if (toFrame instanceof TimeFrameImpl) {
        final TimeFrameImpl toFrame1 = (TimeFrameImpl) toFrame;
        if (canDirectlyRollUp(this, toFrame1)) {
          return true;
        }
        final TimeFrameSet frameSet = frameSet();
        if (frameSet.rollupMap.entries().contains(Pair.of(this, toFrame1))) {
          return true;
        }
        // Hard-code roll-up via DAY-to-MONTH bridge, for now.
        final TimeFrameImpl day =
            requireNonNull(frameSet.map.get(TimeUnit.DAY.name()));
        final TimeFrameImpl month =
            requireNonNull(frameSet.map.get(TimeUnit.MONTH.name()));
        if (canDirectlyRollUp(this, day)
            && canDirectlyRollUp(month, toFrame1)) {
          return true;
        }
        // Hard-code roll-up via ISOWEEK-to-ISOYEAR bridge, for now.
        final TimeFrameImpl isoYear =
            requireNonNull(frameSet.map.get(TimeUnit.ISOYEAR.name()));
        final TimeFrameImpl isoWeek =
            requireNonNull(frameSet.map.get("ISOWEEK"));
        if (canDirectlyRollUp(this, isoWeek)
            && canDirectlyRollUp(isoYear, toFrame1)) {
          return true;
        }
      }
      return false;
    }
  }

  /** Core time frame (such as SECOND, MONTH, ISOYEAR). */
  // CoreFrame 是 TimeFrameImpl 的一个重要静态内部类。它代表了核心时间框架（原子单位），是整个时间换算体系的“根节点”。
  // CoreFrame 的主要作用是定义时间系统的基准单位。
  // 在 Calcite 中，并不是所有时间单位都是平等的。例如，“分钟”是基于“秒”定义的，“季度”是基于“月”定义的。
  // CoreFrame 就是那些不可再向下分解（或者作为逻辑根）的单位，如 SECOND（秒）、MONTH（月）和 ISOYEAR（ISO周年）。
  // 核心职能包括：
  // 作为换算终点：所有派生单位最终都会溯源到某一个 CoreFrame。
  // 提供计算基准：定义单位换算的比例为 1（因为它是基准本身）。
  // 计算纪元差异：负责计算不同框架之间在基准时间（Epoch）上的物理偏移量。
  static class CoreFrame extends TimeFrameImpl {
    CoreFrame(Supplier<TimeFrameSet> frameSetSupplier, String name) {
      super(frameSetSupplier, name);
    }
    // 实现框架的复制逻辑。
    @Override void replicate(BuilderImpl b) {
      b.addCore(name);
    }
    // 返回该框架所归属的核心单位。
    // 因为 CoreFrame 本身就是核心单位，所以它直接返回 this。这在递归溯源算法中标志着到达了底部。
    @Override protected CoreFrame core() {
      return this;
    }
    // 返回相对于核心单位的倍数。
    // 由于它本身就是核心，因此倍数恒等于 1 (BigFraction.ONE)。
    @Override protected BigFraction coreMultiplier() {
      return BigFraction.ONE;
    }

    /** Returns the difference between the epochs of two frames, in the
     * units of this core frame. */
    // from (来源框架), to (目标框架)。
    // 计算两个具有相同核心的框架之间的纪元（Epoch）差值。
    BigInteger epochDiff(TimeFrameImpl from, TimeFrameImpl to) {
      assert from.core() == this;
      assert to.core() == this;
      switch (name) {
        // 如果核心单位是 "MONTH"：它会提取两个框架的 monthEpoch()（总月数偏移）并做减法。
      case "MONTH":
        return BigInteger.valueOf(from.monthEpoch())
            .subtract(BigInteger.valueOf(to.monthEpoch()));
      // 默认情况（如秒、毫秒、天）：它会提取两个框架的 timestampEpoch()（毫秒级偏移）并做减法。
      default:
        return BigInteger.valueOf(from.timestampEpoch())
            .subtract(BigInteger.valueOf(to.timestampEpoch()));
      }
    }
  }

  /** A time frame that is composed of another time frame.
   *
   * <p>For example, {@code MINUTE} is composed of 60 {@code SECOND};
   * (factor = 60, divide = false);
   * {@code MILLISECOND} is composed of 1 / 1000 {@code SECOND}
   * (factor = 1000, divide = true).
   *
   * <p>A sub-time frame S is aligned with its parent frame P;
   * that is, every instance of S belongs to one instance of P.
   * Every {@code MINUTE} belongs to one {@code HOUR};
   * not every {@code WEEK} belongs to precisely one {@code MONTH} or
   * {@code MILLENNIUM}.
   */
  // 代表了基于另一个时间框架定义出来的派生框架。
  // SubFrame 的主要作用是描述时间单位之间的层级和倍数关系。
  // 通过 SubFrame，Calcite 可以构建出一个时间单位的树状结构。它的核心逻辑是：一个单位可以由另一个单位“乘”出来，或者“除”出来。
  // 向上定义（Multiple）：例如 MINUTE（分钟）是由 60 个 SECOND（秒）组成的。
  // 向下定义（Division）：例如 MILLISECOND（毫秒）是由 1 个 SECOND 除以 1000 得到的。
  static class SubFrame extends TimeFrameImpl {
    // 该框架所依赖的基础框架（父框架）。
    // 定义 MINUTE 时，base 就是 SECOND。
    private final TimeFrameImpl base;
    // 标识换算关系是“除法”还是“乘法”。
    // 定义 MILLISECOND 时为 true（1/1000）；定义 HOUR 时为 false（3600倍）。
    private final boolean divide;
    // 换算的系数值。
    // 定义 MINUTE 时，该值为 60。
    private final BigInteger multiplier;
    // 直接引用最终所属的原子核心框架（如 SECOND 或 MONTH）。
    private final CoreFrame coreFrame;

    /** The number of core frames that are equivalent to one of these. For
     * example, MINUTE, HOUR, MILLISECOND all have core = SECOND, and have
     * multipliers 60, 3,600, 1 / 1,000 respectively. */
    // 当前框架相对于核心框架（而非基础框架）的完整换算比例。
    // 如果 HOUR 基于 MINUTE (60)，MINUTE 基于 SECOND (60)，则 HOUR 的 coreMultiplier 是 3600。
    private final BigFraction coreMultiplier;
    // 定义该时间框架的起始对齐点（纪元时间）。
    // 它决定了 FLOOR 操作时，时间轴上的刻度从哪里开始切分。
    private final TimestampString epoch;

    SubFrame(String name, TimeFrameImpl base, boolean divide,
        BigInteger multiplier, CoreFrame coreFrame,
        BigFraction coreMultiplier, TimestampString epoch) {
      super(base.frameSetSupplier, name);
      this.base = requireNonNull(base, "base");
      this.divide = divide;
      this.multiplier = requireNonNull(multiplier, "multiplier");
      this.coreFrame = requireNonNull(coreFrame, "coreFrame");
      this.coreMultiplier = requireNonNull(coreMultiplier, "coreMultiplier");
      this.epoch = requireNonNull(epoch, "epoch");
    }

    @Override public String toString() {
      return name + ", composedOf " + multiplier + " " + base.name;
    }
    // 将属性 epoch 转换为 Unix 纪元以来的天数。
    // 通过毫秒数除以一天的毫秒数（86,400,000）并取整得到
    @Override public int dateEpoch() {
      return (int) floorDiv(epoch.getMillisSinceEpoch(),
          DateTimeUtils.MILLIS_PER_DAY);
    }
    // 将属性 epoch 转换为线性计数的月份值。
    // 解析 epoch 对应的年和月，计算出总月数，用于 YEAR 或 QUARTER 等单位的对齐。
    @Override public int monthEpoch() {
      final Calendar calendar = epoch.toCalendar();
      int y = calendar.get(Calendar.YEAR); // 2020 CE is represented by 2020
      int m = calendar.get(Calendar.MONTH) + 1; // January is represented by 1
      return fullMonth(y, m);
    }
    // 直接返回 epoch 对应的毫秒数。
    @Override public long timestampEpoch() {
      return epoch.getMillisSinceEpoch();
    }

    @Override void replicate(BuilderImpl b) {
      b.addSub(name, divide, multiplier, base.name, epoch);
    }

    /** Returns a copy of this TimeFrameImpl with a given epoch. */
    void replicateWithEpoch(BuilderImpl b, TimestampString epoch) {
      b.addSub(name, divide, multiplier, base.name, epoch);
    }
    // 展开换算关系。
    @Override protected void expand(Map<TimeFrame, BigFraction> map,
        BigFraction f) {
      super.expand(map, f);
      base.expand(map, divide ? f.divide(multiplier) : f.multiply(multiplier));
    }

    @Override protected CoreFrame core() {
      return coreFrame;
    }

    @Override protected BigFraction coreMultiplier() {
      return coreMultiplier;
    }
  }

  /** Frame that defines is based on a minor frame and resets whenever the major
   * frame resets. For example, "DOY" (day of year) is based on DAY and resets
   * every YEAR. */
  // 代表了一种特殊的时间度量概念——商框架（Quotient Frame），主要用于定义“周期内的序数”。
  // QuotientFrame 的主要作用是定义嵌套在粗粒度周期内的细粒度时间单位。
  // 在 SQL 和时间处理中，我们经常需要表达“某一年中的第几天”或“某一个月中的第几小时”。QuotientFrame 描述了这种关系：
  // 小单位（Minor Frame）：实际计数的单位。
  // 大单位（Major Frame）：计数的界限或重置周期。
  // DOY (Day of Year)：以 DAY 为小单位，以 YEAR 为大单位。当年份改变时，天数的计数重新从 1 开始。
  // DOW (Day of Week)：以 DAY 为小单位，以 WEEK 为大单位。
  static class QuotientFrame extends TimeFrameImpl {
    // 定义该框架所基于的基础细粒度单位。
    // 例如在“一年中的第几天”里，minorFrame 就是 DAY。它决定了该框架在进行物理计算（如加减、差值）时的步长。
    private final TimeFrameImpl minorFrame;
    // 定义该框架的重置周期或边界。
    // 例如在“一年中的第几天”里，majorFrame 就是 YEAR。虽然它不直接参与步长计算，但它定义了该时间单位的语义上限。
    private final TimeFrameImpl majorFrame;

    QuotientFrame(String name, TimeFrameImpl minorFrame,
        TimeFrameImpl majorFrame) {
      super(minorFrame.frameSetSupplier, name);
      this.minorFrame = requireNonNull(minorFrame, "minorFrame");
      this.majorFrame = requireNonNull(majorFrame, "majorFrame");
    }

    @Override void replicate(BuilderImpl b) {
      b.addQuotient(name, minorFrame.name, majorFrame.name);
    }

    @Override protected CoreFrame core() {
      return minorFrame.core();
    }

    @Override protected BigFraction coreMultiplier() {
      return minorFrame.coreMultiplier();
    }
  }

  /** Frame that defines an alias. */
  // AliasFrame 的主要作用是为现有的时间框架定义别名。
  // 在 SQL 引擎中，同一个时间单位可能有多种称呼。
  // 例如，你可能希望用户既可以使用标准名称 YEAR，也可以使用简写 Y；或者在特定的业务领域中，将 QUARTER 称为 FISCAL_PERIOD。
  static class AliasFrame extends TimeFrameImpl {
    final TimeFrameImpl frame;

    AliasFrame(String name, TimeFrameImpl frame) {
      super(frame.frameSetSupplier, name);
      this.frame = requireNonNull(frame, "frame");
    }

    @Override void replicate(BuilderImpl b) {
      b.addAlias(name, frame.name);
    }

    @Override protected CoreFrame core() {
      throw new UnsupportedOperationException();
    }

    @Override protected BigFraction coreMultiplier() {
      throw new UnsupportedOperationException();
    }
  }
}
