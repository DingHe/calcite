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

/**
 * Enumeration of types of monotonicity.
 */
// SqlMonotonicity 的主要作用是描述 SQL 表达式值随时间或行顺序的变化趋势（单调性）。
// 在数据库中，了解一个列或表达式是否单调非常有用：
// 流式处理：在处理无限流数据时，如果排序键是单调递增的（如时间戳 rowtime），优化器知道旧的数据永远不会再出现，从而可以进行“状态清理”或触发窗口计算。
// 排序优化：如果数据已经按照某种单调性排列，优化器可以跳过昂贵的排序操作。
// 索引利用：单调性有助于判断是否可以利用索引来加速范围查询。
public enum SqlMonotonicity {
  STRICTLY_INCREASING, // 严格递增,每个后续值都必须大于前一个值。
  INCREASING, // 递增（非严格）,每个后续值大于或等于前一个值。
  STRICTLY_DECREASING, // 严格递减
  DECREASING, // 递减（非严格）
  CONSTANT, // 常量
  /**
   * Catch-all value for expressions that have some monotonic properties.
   * Maybe it isn't known whether the expression is increasing or decreasing;
   * or maybe the value is neither increasing nor decreasing but the value
   * never repeats.
   */
  MONOTONIC, // 单调（不确定方向）
  NOT_MONOTONIC; // 非单调

  /**
   * If this is a strict monotonicity (StrictlyIncreasing, StrictlyDecreasing)
   * returns the non-strict equivalent (Increasing, Decreasing).
   *
   * @return non-strict equivalent monotonicity
   */
  // 将“严格”单调性降低为相应的“非严格”单调性。
  public SqlMonotonicity unstrict() {
    switch (this) {
    case STRICTLY_INCREASING:
      return INCREASING;
    case STRICTLY_DECREASING:
      return DECREASING;
    default:
      return this;
    }
  }

  /**
   * Returns the reverse monotonicity.
   *
   * @return reverse monotonicity
   */
  // 返回单调性的反方向。
  public SqlMonotonicity reverse() {
    switch (this) {
    case STRICTLY_INCREASING:
      return STRICTLY_DECREASING;
    case INCREASING:
      return DECREASING;
    case STRICTLY_DECREASING:
      return STRICTLY_INCREASING;
    case DECREASING:
      return INCREASING;
    default:
      return this;
    }
  }

  /**
   * Whether values of this monotonicity are decreasing. That is, if a value
   * at a given point in a sequence is X, no point later in the sequence will
   * have a value greater than X.
   *
   * @return whether values are decreasing
   */
  // 判断当前单调性是否属于递减趋势。
  public boolean isDecreasing() {
    switch (this) {
    case STRICTLY_DECREASING:
    case DECREASING:
      return true;
    default:
      return false;
    }
  }

  /**
   * Returns whether values of this monotonicity may ever repeat after moving
   * to another value: true for {@link #NOT_MONOTONIC} and {@link #CONSTANT},
   * false otherwise.
   *
   * <p>If a column is known not to repeat, a sort on that column can make
   * progress before all of the input has been seen.
   *
   * @return whether values repeat
   */
  // 判断该单调性下的值序列是否可能会出现重复。
  public boolean mayRepeat() {
    switch (this) {
    case NOT_MONOTONIC:
    case CONSTANT:
      return true;
    default:
      return false;
    }
  }
}
