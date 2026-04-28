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

import org.apache.calcite.avatica.util.TimeUnit;

import org.apache.commons.math3.fraction.BigFraction;

import org.checkerframework.checker.nullness.qual.Nullable;

/** Time frame.
 *
 * <p>Belongs to a {@link TimeFrameSet}.
 * The default set is {@link TimeFrames#CORE};
 * to create custom time frame sets, call {@link TimeFrameSet#builder()}. */
// 时间计算系统的核心抽象单位。如果说 TimeFrameSet 是一个“单位系统表”（类似度量衡表），那么 TimeFrame 就是其中的具体刻度单位（如“分钟”、“周”或“自定义的15分钟采样窗口”）。
// TimeFrame 的核心作用是标准化时间粒度及其相互关系。
// 在 SQL 处理（尤其是 OLAP 场景）中，系统需要频繁进行时间维度的变换。TimeFrame 提供了实现以下功能的元数据支持：
// 时间对齐（Floor/Ceil）：确定一个时间点所属周期的起点或终点。
// 单位换算：定义不同时间粒度之间的数学比例（例如：1 小时 = 60 分钟）。
// 上卷逻辑（Rollup）：判断数据是否可以从细粒度无损地聚合到粗粒度（例如：天可以聚合到月，但通常周不能直接聚合到月）。
// 自定义周期支持：除了标准的年、月、日，它允许开发者定义业务相关的特定时间窗口。
public interface TimeFrame {
  /** Returns the time frame set that this frame belongs to. */
  // 获取该时间框架所属的集合。
  // 一个时间框架必须存在于一个上下文中（即 TimeFrameSet）。通过这个方法，TimeFrame 可以访问到同集合中的其他单位，从而进行比例计算或查找内置单位。
  TimeFrameSet frameSet();

  /** Name of this time frame.
   *
   * <p>A time frame based on a built-in Avatica
   * {@link org.apache.calcite.avatica.util.TimeUnit} will have the same
   * name.
   *
   * @see TimeFrameSet#get(TimeUnit) */
  // 返回该时间框架的唯一标识名称。
  // 对于内置单位，名称通常与 TimeUnit 枚举一致（如 "YEAR", "MONTH"）。对于自定义单位，则返回定义时的名称（如 "MINUTE15"）。该名称在 SQL 解析和验证阶段用于匹配 EXTRACT 或 FLOOR 函数的参数。
  String name();

  /** If this time frame has units in common with another time frame, returns
   * the number of this time frame in one of that time frame.
   *
   * <p>For example, {@code MONTH.per(YEAR)} returns 12;
   * {@code YEAR.per(MONTH)} returns 1 / 12.
   */
  // 计算当前框架与另一个框架之间的比例关系。
  // 示例：MONTH.per(YEAR) 会返回 $12/1$；YEAR.per(MONTH) 会返回 $1/12$。
  // 如果两个框架之间没有直接或间接的数学转换关系，则返回 null。使用分数（BigFraction）是为了保证在处理类似“月”这种相对于“年”不是整数倍的单位时，计算精度不丢失。
  @Nullable BigFraction per(TimeFrame timeFrame);

  /** Returns a date where this time frame is at the start of a cycle.
   *
   * <p>For example, the {@code WEEK} time frame starts on a Monday,
   * and {@code 1970-01-05} was a Monday,
   * and the date {@code 1970-01-05} is represented as integer 5,
   * so for the {@code WEEK} time frame this method returns 5.
   * But it would also be valid to return the date value of {@code 1900/01/01},
   * which was also a Monday.  Because we know that a week is 7 days, we can
   * compute every other point at which a week advances. */
  // int (默认返回 0)
  // 返回该框架周期的起始“日期基准”（Unix Epoch 以来的天数）。
  // 示例：WEEK（周）框架通常以周一为起始。1970-01-01 是周四，而 1970-01-05 是周一（整数值为 5）。因此，WEEK 的 dateEpoch 可能是 5。系统通过 (current_date - epoch) % 7 来找到当前周的起点。
  default int dateEpoch() {
    return 0;
  }

  /** Returns a timestamp where this time frame is at the start of a cycle.
   *
   * @see #dateEpoch() */
  // long (默认返回 0L)
  // 返回该框架周期的起始“时间戳基准”（Unix Epoch 以来的毫秒数）。
  // 逻辑与 dateEpoch 相同，但精度更高，适用于 TIMESTAMP 类型的对齐计算。例如，用于确定“每 15 分钟”这一周期的零点偏移。
  default long timestampEpoch() {
    return 0L;
  }

  /** Returns a month number where this time frame is at the start of a cycle.
   *
   * @see #dateEpoch()
   */
  // 返回该框架周期的起始“月份基准”。
  // 专门处理基于月的单位（如季度、年）。由于不同月份天数不等，基于天数的 dateEpoch 无法准确对齐月份。Calcite 使用一个线性的月份计数器（自纪元以来的总月数）来处理这类粗粒度单位。
  default int monthEpoch() {
    return 0;
  }

  /** Whether this frame can roll up to {@code toFrame}.
   *
   * <p>Examples:
   * <ul>
   *   <li>{@code SECOND} can roll up to {@code MINUTE}, {@code HOUR},
   *   {@code DAY}, {@code WEEK}, {@code MONTH}, {@code MILLENNIUM};
   *   <li>{@code SECOND} cannot roll up to {@code MILLISECOND} (because it is
   *   finer grained);
   *   <li>{@code WEEK} cannot roll up to {@code MONTH}, {@code YEAR},
   *   {@code MILLENNIUM} (because weeks cross month boundaries).
   * </ul>
   *
   * <p>If two time frames have the same core, and one is an integer simple
   * multiple of another, and they have the same offset, then they can roll up.
   * For example, suppose that {@code MINUTE15} and {@code HOUR3} are both based
   * on {@code SECOND};
   * {@code MINUTE15} is 15 * 60 seconds and
   * {@code HOUR3} is 3 * 60 * 60 seconds;
   * therefore one {@code HOUR3} interval equals twelve {@code MINUTE15}
   * intervals.
   * They have the same offset (both start at {@code 1970-01-01 00:00:00}) and
   * therefore {@code MINUTE15} can roll up to {@code HOUR3}.
   *
   * <p>Even if two frames are not multiples, if they are aligned then they can
   * roll up. {@code MONTH} and {@code DAY} are an example. For more about
   * alignment, see {@link TimeFrameSet.Builder#addRollup(String, String)}.
   */
  // 判断当前框架的数据是否可以直接聚合到目标框架。
  // 能上卷的条件：两个单位必须是“对齐”的。例如，DAY 可以上卷到 MONTH，因为每个月的开始必然是某一天的开始。
  boolean canRollUpTo(TimeFrame toFrame);

  /** Returns the built-in unit of this frame, or null if it does not correspond
   * to a built-in unit. */
  // 返回该框架对应的内置 TimeUnit 枚举。
  default @Nullable TimeUnit unit() {
    return frameSet().getUnit(this);
  }
}
