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
package org.apache.calcite.util.mapping;

/**
 * Describes the type of a mapping, from the most general
 * {@link #MULTI_FUNCTION} (every element in the source and target domain can
 * participate in many mappings) to the most restricted {@link #BIJECTION} (every
 * element in the source and target domain must be paired with precisely one
 * element in the other domain).
 *
 * <p>Some common types:
 *
 * <ul>
 * <li>A surjection is a mapping if every target has at least one source; also
 * known as an 'onto' mapping.
 * <li>A mapping is a partial function if every source has at most one target.
 * <li>A mapping is a function if every source has precisely one target.
 * <li>An injection is a mapping where a target has at most one source; also
 * somewhat confusingly known as a 'one-to-one' mapping.
 * <li>A bijection is a mapping which is both an injection and a surjection.
 * Every source has precisely one target, and vice versa.
 * </ul>
 *
 * <p>Once you know what type of mapping you want, call
 * {@link Mappings#create(MappingType, int, int)} to create an efficient
 * implementation of that mapping.
 */
// MappingType 的核心作用是描述源定义域（Source Domain）到目标值域（Target Domain）之间的数学映射约束类型。
// 在 SQL 优化器中，经常需要对字段（列）进行映射。例如，在执行投影（Project）、连接（Join）或聚合下推时，字段的索引位置会发生改变。Calcite 需要根据映射的严格程度来选择最优的数据结构：
// 如果映射是双射（BIJECTION）（1对1且无遗漏），可以使用极低内存的数组或平移操作。
// 如果映射是多值函数（MULTI_FUNCTION）（多对多），则必须使用底层的位图或复杂的链表结构。
// 通过 MappingType 定义好约束后，调用 Mappings.create(MappingType, int, int) 就能为当前场景自动、高效地构建出开销最小的底层实现。
// 理解这个类的关键在于代码最下方的 4 个 private static final int 常量。Calcite 将映射约束抽象为了 4 个二进制位（Bit Flags）：
// 第 0 位 (1 / 0001) - OPTIONAL_SOURCE：是否允许目标没有对应的源（即允许目标被漏掉）。
// 第 1 位 (2 / 0010) - MULTIPLE_SOURCE：是否允许一个目标对应多个源（多对一）。
// 第 2 位 (4 / 0100) - OPTIONAL_TARGET：是否允许源没有对应的目标（即源字段被过滤、抛弃）。
// 第 3 位 (8 / 1000) - MULTIPLE_TARGET：是否允许一个源对应多个目标（一对多）。
// 由于枚举的默认序号（ordinal()）是从 0 到 15 分配的，每一个枚举值的 ordinal() 对应的二进制码，正好完美代表了这 4 个约束的组合状态！
public enum MappingType {
  //            ordinal source target function inverse
  //            ======= ====== ====== ======== =================

  //                  0      1      1 true     0 Bijection
  // BIJECTION (序号 0 / 0000)：双射。每个源刚好对应一个目标，反之亦然。没有任何限制被放开（不允许 Optional，也不允许 Multiple）。
  BIJECTION,

  //                  1   <= 1      1 true     4 InverseSurjection
  // SURJECTION (序号 1 / 0001)：满射。每个目标至少有一个源，每个源刚好对应一个目标。放开了 OPTIONAL_SOURCE（允许一个目标被多次映射）。
  SURJECTION,

  //                  2   >= 1      1 true     8 InverseInjection
  // INJECTION (序号 2 / 0010)：单射。每个目标最多有一个源，每个源刚好对应一个目标。放开了 MULTIPLE_SOURCE（允许有些目标没被映射）。
  INJECTION,

  //                  3    any      1 true     12 InverseFunction
  // FUNCTION (序号 3 / 0011)：全函数。每个源有且仅有一个目标，但目标没有限制。
  FUNCTION,

  /**
   * An inverse surjection has a source for every target, and no source has
   * more than one target.
   */
  //                  4      1   <= 1 partial  1 Surjection
  // INVERSE_SURJECTION (序号 4 / 0100)：逆满射。每个目标刚好有一个源，每个源最多有一个目标（部分映射）。
  INVERSE_SURJECTION,

  /**
   * A partial surjection has no more than one source for any target, and no
   * more than one target for any source.
   */
  //                  5   <= 1   <= 1 partial  5 PartialSurjection
  // PARTIAL_SURJECTION (序号 5 / 0101)：部分满射。每个目标最多一个源，每个源最多一个目标。
  PARTIAL_SURJECTION,

  //                  6   >= 1   <= 1 partial  9 InversePartialInjection
  // PARTIAL_INJECTION (序号 6 / 0110)：部分单射。
  PARTIAL_INJECTION,

  //                  7    any   <= 1 partial  13 InversePartialFunction
  // PARTIAL_FUNCTION (序号 7 / 0111)：部分函数。每个源最多有一个目标。
  PARTIAL_FUNCTION,

  //                  8      1   >= 1 multi    2 Injection
  // INVERSE_INJECTION (序号 8 / 1000)：逆单射。一个源可以对应多个目标。
  INVERSE_INJECTION,

  //                  9   <= 1   >= 1 multi    6 PartialInjection
  // INVERSE_PARTIAL_INJECTION (序号 9 / 1001)：逆部分单射。
  INVERSE_PARTIAL_INJECTION,

  //                 10   >= 1   >= 1 multi    10
  // TEN (序号 10 / 1010)：内部占位符（数学上对应特定约束组合，未分配特定英文名）。
  TEN,

  //                 11    any   >= 1 multi    14
  // ELEVEN (序号 11 / 1011)：内部占位符。
  ELEVEN,

  /**
   * An inverse function has a source for every target, but a source might
   * have 0, 1 or more targets.
   *
   * <p>Obeys the constraints {@link MappingType#isMandatorySource()},
   * {@link MappingType#isSingleSource()}.
   *
   * <p>Similar types:
   *
   * <ul>
   * <li> {@link #INVERSE_SURJECTION} is stronger (a source may not have
   * multiple targets);
   * <li>{@link #INVERSE_PARTIAL_FUNCTION} is weaker (a target may have 0 or 1
   * sources).
   * </ul>
   */
  //                 12      1    any multi    3 Function
  // INVERSE_FUNCTION (序号 12 / 1100)：逆函数。每个目标刚好对应一个源，但一个源可能对应 0 或多个目标。
  INVERSE_FUNCTION,

  //                 13   <= 1    any multi    7 PartialFunction
  // INVERSE_PARTIAL_FUNCTION (序号 13 / 1101)：逆部分函数。
  INVERSE_PARTIAL_FUNCTION,

  //                 14   >= 1    any multi    11
  // FOURTEEN (序号 14 / 1110)：内部占位符。
  FOURTEEN,

  //                 15    any    any multi    15 MultiFunction
  // MULTI_FUNCTION (序号 15 / 1111)：多值函数。没有任何约束，源和目标可以任意多对多连接。
  MULTI_FUNCTION;
  // 存储当前映射关系的逆映射（Inverse Mapping）对应的枚举序号。
  // 在构造方法中，通过位运算将前 2 位（Source 约束）与后 2 位（Target 约束）互换：
  // 例如，SURJECTION（序号 1, 0001）运算后变成 4（0100），即 INVERSE_SURJECTION。
  private final int inverseOrdinal;

  MappingType() {
    // 利用当前枚举对象的 ordinal()（0-15 的整型）进行位对调运算，计算出该映射在逆向状态下的枚举序号，并将其缓存至 inverseOrdinal 字段中。
    this.inverseOrdinal = ((ordinal() & 3) << 2)
        | ((ordinal() & 12) >> 2);
  }
  // 获取当前映射类型的逆映射类型。
  public MappingType inverse() {
    return MappingType.values()[this.inverseOrdinal];
  }

  /**
   * Returns whether this mapping type is (possibly a weaker form of) a given
   * mapping type.
   *
   * <p>For example, a {@link #BIJECTION} is a {@link #FUNCTION}, but not
   * every {link #Function} is a {@link #BIJECTION}.
   */
  // 判断当前映射类型是否属于（或兼容于）目标映射类型（判断当前约束是否比目标约束更严格或相同）。
  // 利用按位与 (ordinal() & mappingType.ordinal()) == ordinal() 进行判断。
  // 由于 0 代表最高约束，若当前值的位是目标值的子集，则说明当前类型完全满足目标的兼容要求。例如：BIJECTION.isA(FUNCTION) 将返回 true。
  public boolean isA(MappingType mappingType) {
    return (ordinal() & mappingType.ordinal()) == ordinal();
  }

  /**
   * A mapping is a total function if every source has precisely one target.
   */
  // 作用：判断该类型是否是一个全函数（Total Function）（每个源有且仅有一个目标）。
  // 检查二进制位中是否包含 OPTIONAL_TARGET（漏掉目标）或 MULTIPLE_TARGET（多个目标）。两者都不包含（即结果为 0）时返回 true。
  public boolean isFunction() {
    return (ordinal() & (OPTIONAL_TARGET | MULTIPLE_TARGET)) == 0;
  }

  /**
   * A mapping is a partial function if every source has at most one target.
   */
  // 判断该类型是否是一个部分函数（Partial Function）（每个源最多有一个目标，可以为 0 个）。
  public boolean isPartialFunction() {
    return (ordinal() & MULTIPLE_TARGET) == 0;
  }

  /**
   * A mapping is a surjection if it is a function and every target has at
   * least one source.
   */
  // 判断该类型是否属于数学上的满射。
  // 要求每个源刚好一个目标，且每个目标至少有一个源。检查 OPTIONAL_TARGET | MULTIPLE_TARGET | OPTIONAL_SOURCE 几位是否全部为 0，是则返回 true。
  public boolean isSurjection() {
    return (ordinal() & (OPTIONAL_TARGET | MULTIPLE_TARGET | OPTIONAL_SOURCE))
        == 0;
  }

  /**
   * A mapping is an injection if it is a function and no target has more than
   * one source. (In other words, every source has precisely one target.)
   */
  // 判断该类型是否属于数学上的单射（1 对 1，但目标定义域允许有留白）。
  // 要求每个源刚好一个目标，且目标最多只能有一个源。检查 OPTIONAL_TARGET | MULTIPLE_TARGET | MULTIPLE_SOURCE 几位是否全为 0，是则返回 true。
  public boolean isInjection() {
    return (ordinal() & (OPTIONAL_TARGET | MULTIPLE_TARGET | MULTIPLE_SOURCE))
        == 0;
  }

  /**
   * A mapping is a bijection if it is a surjection and it is an injection.
   * (In other words,
   */
  // 判断该类型是否属于最严格的双射（完美的 1 对 1 映射，两端均无留白）。
  // 检查 4 个二进制标志位是否全部为 0（即 ordinal() == 0）。满足则返回 true。
  public boolean isBijection() {
    return (ordinal()
        & (OPTIONAL_TARGET | MULTIPLE_TARGET | OPTIONAL_SOURCE
        | MULTIPLE_SOURCE)) == 0;
  }

  /**
   * Constraint that every source has at least one target.
   */
  public boolean isMandatoryTarget() {
    return !((ordinal() & OPTIONAL_TARGET) == OPTIONAL_TARGET);
  }

  /**
   * Constraint that every source has at most one target.
   */
  public boolean isSingleTarget() {
    return !((ordinal() & MULTIPLE_TARGET) == MULTIPLE_TARGET);
  }

  /**
   * Constraint that every target has at least one source.
   */
  public boolean isMandatorySource() {
    return !((ordinal() & OPTIONAL_SOURCE) == OPTIONAL_SOURCE);
  }

  /**
   * Constraint that every target has at most one source.
   */
  public boolean isSingleSource() {
    return !((ordinal() & MULTIPLE_SOURCE) == MULTIPLE_SOURCE);
  }

  /**
   * Allow less than one source for a given target.
   */
  private static final int OPTIONAL_SOURCE = 1;

  /**
   * Allow more than one source for a given target.
   */
  private static final int MULTIPLE_SOURCE = 2;

  /**
   * Allow less than one target for a given source.
   */
  private static final int OPTIONAL_TARGET = 4;

  /**
   * Allow more than one target for a given source.
   */
  private static final int MULTIPLE_TARGET = 8;
}
