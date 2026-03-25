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
package org.apache.calcite.linq4j;

import org.apache.calcite.linq4j.function.BigDecimalFunction1;
import org.apache.calcite.linq4j.function.DoubleFunction1;
import org.apache.calcite.linq4j.function.EqualityComparer;
import org.apache.calcite.linq4j.function.FloatFunction1;
import org.apache.calcite.linq4j.function.Function0;
import org.apache.calcite.linq4j.function.Function1;
import org.apache.calcite.linq4j.function.Function2;
import org.apache.calcite.linq4j.function.IntegerFunction1;
import org.apache.calcite.linq4j.function.LongFunction1;
import org.apache.calcite.linq4j.function.NullableBigDecimalFunction1;
import org.apache.calcite.linq4j.function.NullableDoubleFunction1;
import org.apache.calcite.linq4j.function.NullableFloatFunction1;
import org.apache.calcite.linq4j.function.NullableIntegerFunction1;
import org.apache.calcite.linq4j.function.NullableLongFunction1;
import org.apache.calcite.linq4j.function.Predicate1;
import org.apache.calcite.linq4j.function.Predicate2;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;
import org.checkerframework.framework.qual.Covariant;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Extension methods in {@link Enumerable}.
 *
 * @param <TSource> Element type
 */
// 在 Apache Calcite 的 Linq4j 框架中，ExtendedEnumerable<TSource> 是一个承载了 LINQ 标准查询算子 的核心接口。
// 它定义了大量用于对数据集进行转换、过滤、聚合和关联的方法。
// ExtendedEnumerable 的作用可以类比于 Java 8 的 Stream 接口，但它更加强大且直接服务于 SQL 引擎的内存计算：
// 算子库：它为 Enumerable（可枚举序列）提供了丰富的扩展方法，涵盖了 SQL 绝大部分的操作（如 JOIN、GROUP BY、WHERE、ORDER BY）。
// 链式编程：支持通过链式调用构建复杂的查询逻辑。
// 延迟执行支持：许多方法（如 select、where）返回的是一个新的 Enumerable 对象，这些操作通常是延迟（Lazy）执行的，只有在真正遍历数据时才会计算。
// 桥接功能：提供了将普通集合转换为 Queryable（可查询对象）的入口。
// TSource: 泛型参数，代表序列中元素的类型

@Covariant(0)
public interface ExtendedEnumerable<TSource> {

  /**
   * Performs an operation for each member of this enumeration.
   *
   * <p>Returns the value returned by the function for the last element in
   * this enumeration, or null if this enumeration is empty.
   *
   * @param func Operation
   * @param <R> Return type
   */
  // foreach 方法用于遍历当前序列中的每一个成员，并对每个成员执行指定的函数逻辑。
  // 它会记录并返回对序列中最后一个元素调用函数后得到的结果。
  // 这种设计常用于需要副作用（Side-effect）且需要获取最终状态的场景，例如在生成代码或构建复杂对象时，获取最后一个处理节点的引用。
  <R> @Nullable R foreach(Function1<TSource, R> func);

  /**
   * Applies an accumulator function over a
   * sequence.
   */
  // aggregate 方法是**最基础的序列归约（Reduction）**操作。它类似于 Java Stream 中的 reduce 方法，或者 SQL 中的自定义聚合逻辑
  // aggregate 方法通过一个累加器函数（Accumulator Function），将整个序列中的所有元素“折叠”成一个单一的结果。
  // 逻辑过程：它会从序列的第一个元素开始，将其作为初始值，然后依次与后续元素进行计算，不断更新中间结果，直到遍历完所有元素。
  // 数学类比：如果序列是 $[a, b, c, d]$，函数是 $f$，那么计算过程为 $f(f(f(a, b), c), d)$。
  @Nullable TSource aggregate(Function2<@Nullable TSource, TSource, TSource> func);

  /**
   * Applies an accumulator function over a
   * sequence. The specified seed value is used as the initial
   * accumulator value.
   *
   * <p>If {@code seed} is not null, the result is never null.
   */
  // 在 ExtendedEnumerable 接口中，这个带有 seed 参数的 aggregate 重载是功能更强大的**累加归约（Fold）**操作。
  // 它允许你指定一个初始值（种子），并且累加结果的类型可以与序列元素的类型不同。
  // 该方法通过一个初始种子值（Seed）和累加器函数（Accumulator Function），将序列中的所有元素处理成一个最终的聚合值。
  // 类型转换能力：这是它与基础 aggregate(func) 最大的区别。基础版本只能返回 TSource 类型，而此版本可以返回任意类型 TAccumulate（例如：将字符串序列 Enumerable<String> 聚合为长度总和 Integer）。
  <TAccumulate> @PolyNull TAccumulate aggregate(@PolyNull TAccumulate seed,
      Function2<@PolyNull TAccumulate, TSource, @PolyNull TAccumulate> func);

  /**
   * Applies an accumulator function over a
   * sequence. The specified seed value is used as the initial
   * accumulator value, and the specified function is used to select
   * the result value.
   */
  // 在 ExtendedEnumerable 接口中，这个重载版本是 aggregate 家族中最完整、功能最全 的方法。它在“种子累加”的基础上增加了一个“结果转换”步骤。
  // 该方法通过三个核心组件完成复杂的聚合计算：
  // 初始种子 (Seed)：定义计算的起点。
  // 累加器 (Accumulator)：定义如何将每个元素合并到中间状态。
  // 结果选择器 (Selector)：在遍历结束后，对最终的中间状态进行一次转换，输出最终结果。
  // 这种模式常用于 中间状态与最终结果类型不一致 的场景（例如：计算平均值时，中间状态需要同时记录“总和”和“个数”，而最终结果是一个“数值”）。
  <TAccumulate, TResult> TResult aggregate(TAccumulate seed,
      Function2<TAccumulate, TSource, TAccumulate> func,
      Function1<TAccumulate, TResult> selector);

  /**
   * Determines whether all elements of a sequence
   * satisfy a condition.
   */
  // 用于执行**逻辑量化判断（Logical Quantification）**的终端操作。它们分别对应 SQL 中的 ALL 谓词逻辑以及 EXISTS 检查。
  // 该方法用于判断序列中的所有元素是否都满足指定的条件。
  // 全真判定：只有当序列中每一个元素经过 predicate.apply() 计算后都返回 true 时，整个方法才返回 true。
  // 短路机制（Short-circuiting）：为了性能优化，一旦遇到第一个不满足条件（返回 false）的元素，方法会立即停止遍历并返回 false。
  boolean all(Predicate1<TSource> predicate);

  /**
   * Determines whether a sequence contains any
   * elements. (Defined by Enumerable.)
   */
  // 该方法用于判断序列中是否至少包含一个元素。
  // 存在性检查：它不关心元素的内容，只关心序列是否非空。
  // 极速响应：只要底层迭代器（Enumerator）能够成功执行一次 moveNext()，该方法就会立即停止并返回 true。
  boolean any();

  /**
   * Determines whether any element of a sequence
   * satisfies a condition.
   */
  // 这个带有参数的 any 重载是执行**存在性判定（Existential Quantification）**的逻辑算子。
  // 它对应 SQL 中的 WHERE EXISTS (SELECT ... WHERE condition) 或 ANY 谓词。
  // 该方法用于判断序列中是否至少有一个元素满足指定的条件。
  // 逻辑核心：只要在遍历过程中找到第一个使 predicate.apply() 返回 true 的元素，判断即告成功。
  boolean any(Predicate1<TSource> predicate);

  /**
   * Returns the input typed as {@code Enumerable<TSource>}.
   *
   * <p>This method has no effect
   * other than to change the compile-time type of source from a type that
   * implements {@code Enumerable<TSource>} to {@code Enumerable<TSource>}
   * itself.
   *
   * <p>{@code asEnumerable<TSource>(Enumerable<TSource>)} can be used to choose
   * between query implementations when a sequence implements

   * {@code Enumerable<TSource>} but also has a different set of public query
   * methods available. For example, given a generic class Table that implements
   * {@code Enumerable<TSource>} and has its own methods such as {@code where},
   * {@code select}, and {@code selectMany}, a call to {@code where} would
   * invoke the public {@code where} method of {@code Table}. A {@code Table}
   * type that represents a database table could have a {@code where} method
   * that takes the predicate argument as an expression tree and converts the
   * tree to SQL for remote execution. If remote execution is not desired, for
   * example because the predicate invokes a local method, the
   * {@code asEnumerable<TSource>} method can be used to hide the custom methods
   * and instead make the standard query operators available.
   */
  // 从底层代码实现来看，它几乎“什么都不做”，但在 Java 类型系统和 查询优化路径选择中，它起到了至关重要的“类型收窄”作用。
  // asEnumerable() 的核心作用是将一个具体的子类类型强制向上转型为基础的 Enumerable<TSource> 接口类型。
  // 隐藏自定义算子：有些类（例如 Table）既实现了 Enumerable 接口，又定义了自己同名的 where 或 select 方法（这些方法可能试图将查询转换为远程 SQL）。通过调用 asEnumerable()，你可以强制后续的链式调用执行 Linq4j 默认的本地内存算子。
  // 核心价值：本地执行 vs. 远程执行
  // 这是理解该方法的关键场景。假设你有一个 Table 对象：
  // 默认行为（远程驱动）：
  //如果你直接调用 table.where(predicate)，Table 类可能会尝试解析这个 predicate 并将其转化为 SELECT ... WHERE ... 发送给数据库（远程执行）。
  // 强制本地执行：
  //如果你发现 predicate 中包含数据库不支持的本地 Java 方法，你需要让 Calcite 在内存中处理。此时你可以调用：
  // table.asEnumerable().where(predicate)
  // 由于 asEnumerable() 返回的是标准的接口类型，编译器将不再调用 Table 的自定义方法，而是转而调用 Enumerable 的标准内存过滤实现
  Enumerable<TSource> asEnumerable();

  /**
   * Converts an Enumerable to a {@link Queryable}.
   *
   * <p>If the type of source implements {@code Queryable}, this method
   * returns it directly. Otherwise, it returns a {@code Queryable} that
   * executes queries by calling the equivalent query operator methods in
   * {@code Enumerable} instead of those in {@code Queryable}.
   *
   * <p>Analogous to the LINQ's Enumerable.AsQueryable extension method.
   *
   * @return A queryable
   */
  // 负责将一个简单的“可迭代序列”提升为支持“表达式树（Expression Tree）”的高级查询对象。
  // asQueryable() 的核心作用是将 Enumerable 转换为 Queryable。这两者在 Calcite 中的本质区别在于执行模式：
  // Enumerable (命令式执行)：像 Java Stream 一样，通过内存中的迭代器（Iterator）和 Lambda 表达式直接运行代码。
  // Queryable (声明式执行)：它不直接执行代码，而是将你的查询操作（如 .where(), .select()）记录为一棵表达式树（Expression Tree）。这棵树随后可以被 Calcite 优化器分析，并转化为 SQL 发送到远程数据库，或者转化为高效的字节码。
  Queryable<TSource> asQueryable();

  /**
   * Computes the average of a sequence of Decimal
   * values that are obtained by invoking a transform function on
   * each element of the input sequence.
   */
  // 都接收一个转换函数（Selector），将原始的 TSource 元素映射为数值，然后计算这些数值的平均值。
  // 由于 Calcite 追求极致的执行效率，它针对不同的数据类型和“可空性（Nullability）”提供了多个重载版本。
  // 计算高精度 BigDecimal 序列的平均值。
  // 参数：BigDecimalFunction1 是一个函数式接口，负责从 TSource 中提取非空的 BigDecimal。
  // 应用场景：财务计算、货币金额等对精度要求极高、不允许有舍入误差的场景。
  BigDecimal average(BigDecimalFunction1<TSource> selector);

  /**
   * Computes the average of a sequence of nullable
   * Decimal values that are obtained by invoking a transform
   * function on each element of the input sequence.
   */
  // 作用：计算**包含空值（null）**的 BigDecimal 序列的平均值。
  // 逻辑：在计算过程中，它会自动忽略 selector 返回的 null 值，只对有效的数值进行求和与计数。
  BigDecimal average(NullableBigDecimalFunction1<TSource> selector);

  /**
   * Computes the average of a sequence of Double
   * values that are obtained by invoking a transform function on
   * each element of the input sequence.
   */
  // 作用：基于 原生 double 类型 计算平均值。
  // 优势：性能极高。因为它完全避免了 Java 对象的装箱（Boxing）和拆箱（Unboxing）开销，直接在 CPU 寄存器和原生内存中进行加法运算。
  double average(DoubleFunction1<TSource> selector);

  /**
   * Computes the average of a sequence of nullable
   * Double values that are obtained by invoking a transform
   * function on each element of the input sequence.
   */
  // 作用：计算可空 Double 对象序列的平均值。
  // 逻辑：处理 Double 包装对象。计算时跳过 null，只累加有效数值。
  // SQL 对应：这直接对应了 SQL 中的 AVG(column) 行为，因为 SQL 数据库中的列通常都是允许 NULL 的。
  Double average(NullableDoubleFunction1<TSource> selector);

  /**
   * Computes the average of a sequence of int values
   * that are obtained by invoking a transform function on each
   * element of the input sequence.
   */
  int average(IntegerFunction1<TSource> selector);

  /**
   * Computes the average of a sequence of nullable
   * int values that are obtained by invoking a transform function
   * on each element of the input sequence.
   */
  Integer average(NullableIntegerFunction1<TSource> selector);

  /**
   * Computes the average of a sequence of long values
   * that are obtained by invoking a transform function on each
   * element of the input sequence.
   */
  long average(LongFunction1<TSource> selector);

  /**
   * Computes the average of a sequence of nullable
   * long values that are obtained by invoking a transform function
   * on each element of the input sequence.
   */
  Long average(NullableLongFunction1<TSource> selector);

  /**
   * Computes the average of a sequence of Float
   * values that are obtained by invoking a transform function on
   * each element of the input sequence.
   */
  float average(FloatFunction1<TSource> selector);

  /**
   * Computes the average of a sequence of nullable
   * Float values that are obtained by invoking a transform
   * function on each element of the input sequence.
   */
  Float average(NullableFloatFunction1<TSource> selector);

  /**
   * Converts the elements of this Enumerable to the specified type.
   *
   * <p>This method is implemented by using deferred execution. The immediate
   * return value is an object that stores all the information that is
   * required to perform the action. The query represented by this method is
   * not executed until the object is enumerated either by calling its
   * {@link Enumerable#enumerator} method directly or by using
   * {@code for (... in ...)}.
   *
   * <p>If an element cannot be cast to type TResult, the
   * {@link Enumerator#current()} method will throw a
   * {@link ClassCastException} a exception when the element it accessed. To
   * obtain only those elements that can be cast to type TResult, use the
   * {@link #ofType(Class)} method instead.
   *
   * @see EnumerableDefaults#cast
   * @see #ofType(Class)
   */
  // 该方法用于将当前序列中的所有元素强制转换为指定的 Java 类型 T2。
  // 强制类型重定义：当你在处理泛型丢失（如 Enumerable<Object>）或者需要将基类序列视为派生类序列时使用。
  // 延迟执行（Deferred Execution）：调用此方法时不会立即发生转换。它仅返回一个包装对象。真正的转换动作发生在后续遍历（迭代）数据的时候。
  <T2> Enumerable<T2> cast(Class<T2> clazz);

  /**
   * Concatenates two sequences.
   */
  // 该方法用于将两个同类型的序列首尾相连，形成一个更长的序列。
  // 序列合并：它对应 SQL 中的 UNION ALL 算子。
  // 流式处理：它不会将两个序列的数据全部加载到内存中。它采用逻辑连接：先遍历完当前序列的所有元素，紧接着无缝切换到 enumerable1 继续遍历。

  Enumerable<TSource> concat(Enumerable<TSource> enumerable1);

  /**
   * Determines whether a sequence contains a specified
   * element by using the default equality comparer.
   */
  // 用于执行成员归属判定（Membership Testing）。它对应 SQL 中的 IN 常量集合或简单的值存在性检查。
  // 判定标准：使用底层的 Objects.equals(a, b) 进行判断。这意味着它会优先调用 TSource 类重写的 equals() 方法。
  // 短路逻辑：一旦在序列中找到第一个与 element 相等的项，方法立即停止遍历并返回 true。
  boolean contains(TSource element);

  /**
   * Determines whether a sequence contains a specified
   * element by using a specified {@code EqualityComparer<TSource>}.
   */
  // 这是高级版本，允许你自定义“相等”的定义。
  boolean contains(TSource element, EqualityComparer<TSource> comparer);

  /**
   * Returns the number of elements in a
   * sequence.
   */
  // 该方法用于计算并返回序列中所有元素的总数。
  // 全量计数：遍历整个序列，统计元素的个数。
  int count();

  /**
   * Returns a number that represents how many elements
   * in the specified sequence satisfy a condition.
   */
  // 该方法用于计算并返回序列中满足特定条件的元素数量。
  int count(Predicate1<TSource> predicate);

  /**
   * Returns the elements of the specified sequence or
   * the type parameter's default value in a singleton collection if
   * the sequence is empty.
   */
  // 该方法用于确保序列在为空时，至少返回一个包含“默认值”的元素。
  // 作用：如果原始序列中有元素，则按原样返回；如果序列为空（即没有数据），则返回一个仅包含 null 的单元素序列。
  // 语义对应：在执行某些类型的 LEFT JOIN 或聚合操作时，如果结果集为空，该方法可以提供一个 null 占位符，以符合 SQL 对空结果集的处理规范。
  Enumerable<@Nullable TSource> defaultIfEmpty();

  /**
   * Returns the elements of the specified sequence or
   * the specified value in a singleton collection if the sequence
   * is empty.
   *
   * <p>If {@code value} is not null, the result is never null.
   */
  // 这是前一个方法的增强版，允许你自定义空序列时的填充值。
  // 参数 value：用户定义的默认值（例如，如果是一个数字序列，可以定义空时返回 0）。
  Enumerable<@PolyNull TSource> defaultIfEmpty(@PolyNull TSource value);

  /**
   * Returns distinct elements from a sequence by using
   * the default equality comparer to compare values.
   */
  // 该方法用于从序列中消除重复元素，仅保留唯一值。
  // 作用：对应 SQL 中的 SELECT DISTINCT。它会扫描整个序列，并利用哈希表（HashSet）或排序逻辑来剔除重复的项。
  Enumerable<TSource> distinct();

  /**
   * Returns distinct elements from a sequence by using
   * a specified {@code EqualityComparer<TSource>} to compare values.
   */
  // 这是 distinct 的高级版本，允许你定义业务层面的“唯一性”。
  Enumerable<TSource> distinct(EqualityComparer<TSource> comparer);

  /**
   * Returns the element at a specified index in a
   * sequence.
   */
  // 该方法用于直接获取序列中指定位置的元素。
  TSource elementAt(int index);

  /**
   * Returns the element at a specified index in a
   * sequence or a default value if the index is out of
   * range.
   */
  @Nullable TSource elementAtOrDefault(int index);

  /**
   * Produces the set difference of two sequences by
   * using the default equality comparer to compare values,
   * eliminate duplicates. (Defined by Enumerable.)
   */
  Enumerable<TSource> except(Enumerable<TSource> enumerable1);

  /**
   * Produces the set difference of two sequences by
   * using the default equality comparer to compare values,
   * using {@code all} to indicate whether to eliminate duplicates.
   * (Defined by Enumerable.)
   */
  Enumerable<TSource> except(Enumerable<TSource> enumerable1, boolean all);

  /**
   * Produces the set difference of two sequences by
   * using the specified {@code EqualityComparer<TSource>} to compare
   * values, eliminate duplicates.
   */
  Enumerable<TSource> except(Enumerable<TSource> enumerable1,
      EqualityComparer<TSource> comparer);

  /**
   * Produces the set difference of two sequences by
   * using the specified {@code EqualityComparer<TSource>} to compare
   * values, using {@code all} to indicate whether to eliminate duplicates.
   */
  Enumerable<TSource> except(Enumerable<TSource> enumerable1,
      EqualityComparer<TSource> comparer, boolean all);

  /**
   * Returns the first element of a sequence. (Defined
   * by Enumerable.)
   */
  TSource first();

  /**
   * Returns the first element in a sequence that
   * satisfies a specified condition.
   */
  TSource first(Predicate1<TSource> predicate);

  /**
   * Returns the first element of a sequence, or a
   * default value if the sequence contains no elements.
   */
  @Nullable TSource firstOrDefault();

  /**
   * Returns the first element of the sequence that
   * satisfies a condition or a default value if no such element is
   * found.
   */
  @Nullable TSource firstOrDefault(Predicate1<TSource> predicate);

  /**
   * Groups the elements of a sequence according to a
   * specified key selector function.
   */
  // groupBy 是最核心的数据聚合与重组算子。它对应 SQL 中的 GROUP BY 子句，负责将扁平的序列转化为按类别划分的逻辑组。
  // 该方法根据指定的 键选择器（Key Selector） 将原始序列中的元素分类。
  // 数据结构变换：它将 Enumerable<TSource> 转换为 Enumerable<Grouping<TKey, TSource>>。
  // 什么是 Grouping？：Grouping<TKey, TElement> 是一个特殊的接口，它既是一个 Enumerable（包含了该组的所有元素），又持有一个 getKey() 方法（标识该组的键）。
  // 延迟执行：这是一种中间操作。当你开始迭代返回的序列时，它会扫描整个源序列，并在内存中构建分组索引。
  // <TKey>：分组键的类型（例如按部门 ID 分组，TKey 就是 Integer）。
  // 映射逻辑：定义如何从原始对象 TSource 中提取用于分组的键。
  // 返回值： 每一个元素代表一个独立的分组。如果序列中有 3 个不同的部门 ID，返回的序列就包含 3 个 Grouping 对象。
  <TKey> Enumerable<Grouping<TKey, TSource>> groupBy(
      Function1<TSource, TKey> keySelector);

  /**
   * Groups the elements of a sequence according to a
   * specified key selector function and compares the keys by using
   * a specified comparer.
   */
  // 在 ExtendedEnumerable 接口中，这个 groupBy 重载版本引入了 自定义相等比较器 (EqualityComparer)。
  // 它解决了 Java 默认对象比较（equals 和 hashCode）在某些业务场景下不适用的问题。
  // 该方法根据 键选择器 (keySelector) 提取键，并使用 指定的比较器 (comparer) 来决定哪些键应该被归为同一组。
  // 解耦比较逻辑：默认情况下，Java 使用 Object.equals() 判定两个键是否相同。但在处理如“忽略大小写的字符串分组”或“基于特定 ID 字段的 POJO 分组”时，默认逻辑会失效。此方法允许你注入自定义逻辑。
  <TKey> Enumerable<Grouping<TKey, TSource>> groupBy(
      Function1<TSource, TKey> keySelector, EqualityComparer<TKey> comparer);

  /**
   * Groups the elements of a sequence according to a
   * specified key selector function and projects the elements for
   * each group by using a specified function.
   */
  // 引入了 元素转换 (Element Projection) 和 结果聚合 (Result Selection) 的功能。它们允许你在分组的同时直接处理数据，而不需要在分组后再进行昂贵的二次遍历。
  // 这个重载在分类的基础上，增加了对组内元素的预处理。
  // 作用：根据 keySelector 分组，但存入每个组的不再是原始对象 TSource，而是经过 elementSelector 转换后的 TElement。
  // 泛型变化：从 Grouping<TKey, TSource> 变为 Grouping<TKey, TElement>。
  // 典型场景：如果你有一个 Employee 序列，想按部门分组，但每组只需要存储员工的名字（String）而不是整个对象，这能显著减少内存占用。
  <TKey, TElement> Enumerable<Grouping<TKey, TElement>> groupBy(
      Function1<TSource, TKey> keySelector,
      Function1<TSource, TElement> elementSelector);

  /**
   * Groups the elements of a sequence according to a
   * key selector function. The keys are compared by using a
   * comparer and each group's elements are projected by using a
   * specified function.
   */
  // 这是功能最全的中间转换版本。
  //  作用：它结合了元素转换和自定义键比较逻辑。
  // 提取键 (keySelector)。
  // 根据自定义逻辑 (comparer) 判定键是否属于同一组。
  // 将转换后的值 (elementSelector) 存入对应的组。
  // 在 Calcite 中的应用：常用于处理复杂的 SQL，例如：SELECT name FROM emps GROUP BY UPPER(dept_name)。这里 UPPER 逻辑由 comparer 或 keySelector 处理，而 name 的提取由 elementSelector 处理。
  <TKey, TElement> Enumerable<Grouping<TKey, TElement>> groupBy(
      Function1<TSource, TKey> keySelector,
      Function1<TSource, TElement> elementSelector, EqualityComparer<TKey> comparer);

  /**
   * Groups the elements of a sequence according to a
   * specified key selector function and creates a result value from
   * each group and its key.
   */
  // 这是一个非常强大的版本，它改变了返回值的结构。
  // 作用：它不再返回 Grouping 对象，而是直接应用一个函数将“键”和“该组的所有元素”转换为一个最终的结果 TResult。
  // 语义对应：这非常接近 SQL 的完整聚合逻辑，即 SELECT key, AGG(col) FROM table GROUP BY key。
  <TKey, TResult> Enumerable<TResult> groupBy(
      Function1<TSource, TKey> keySelector,
      Function2<TKey, Enumerable<TSource>, TResult> resultSelector);

  /**
   * Groups the elements of a sequence according to a
   * specified key selector function and creates a result value from
   * each group and its key. The keys are compared by using a
   * specified comparer.
   */
  <TKey, TResult> Enumerable<TResult> groupBy(
      Function1<TSource, TKey> keySelector,
      Function2<TKey, Enumerable<TSource>, TResult> resultSelector,
      EqualityComparer<TKey> comparer);

  /**
   * Groups the elements of a sequence according to a
   * specified key selector function and creates a result value from
   * each group and its key. The elements of each group are
   * projected by using a specified function.
   */
  <TKey, TElement, TResult> Enumerable<TResult> groupBy(
      Function1<TSource, TKey> keySelector,
      Function1<TSource, TElement> elementSelector,
      Function2<TKey, Enumerable<TElement>, TResult> resultSelector);

  /**
   * Groups the elements of a sequence according to a
   * specified key selector function and creates a result value from
   * each group and its key. Key values are compared by using a
   * specified comparer, and the elements of each group are
   * projected by using a specified function.
   */
  <TKey, TElement, TResult> Enumerable<TResult> groupBy(
      Function1<TSource, TKey> keySelector,
      Function1<TSource, TElement> elementSelector,
      Function2<TKey, Enumerable<TElement>, TResult> resultSelector,
      EqualityComparer<TKey> comparer);

  /**
   * Groups the elements of a sequence according to a
   * specified key selector function, initializing an accumulator for each
   * group and adding to it each time an element with the same key is seen.
   * Creates a result value from each accumulator and its key using a
   * specified function.
   */
  <TKey, TAccumulate, TResult> Enumerable<TResult> groupBy(
      Function1<TSource, TKey> keySelector,
      Function0<TAccumulate> accumulatorInitializer,
      Function2<TAccumulate, TSource, TAccumulate> accumulatorAdder,
      Function2<TKey, TAccumulate, TResult> resultSelector);

  /**
   * Groups the elements of a sequence according to a
   * specified key selector function, initializing an accumulator for each
   * group and adding to it each time an element with the same key is seen.
   * Creates a result value from each accumulator and its key using a
   * specified function. Key values are compared by using a
   * specified comparer.
   */
  <TKey, TAccumulate, TResult> Enumerable<TResult> groupBy(
      Function1<TSource, TKey> keySelector,
      Function0<TAccumulate> accumulatorInitializer,
      Function2<TAccumulate, TSource, TAccumulate> accumulatorAdder,
      Function2<TKey, TAccumulate, TResult> resultSelector,
      EqualityComparer<TKey> comparer);

  /**
   * Group keys are sorted already. Key values are compared by using a
   * specified comparator. Groups the elements of a sequence according to a
   * specified key selector function and initializing one accumulator at a time.
   * Go over elements sequentially, adding to accumulator each time an element
   * with the same key is seen. When key changes, creates a result value from the
   * accumulator and then re-initializes the accumulator. In the case of NULL values
   * in group keys, the comparator must be able to support NULL values by giving a
   * consistent sort ordering.
   */
  <TKey, TAccumulate, TResult> Enumerable<TResult> sortedGroupBy(
      Function1<TSource, TKey> keySelector,
      Function0<TAccumulate> accumulatorInitializer,
      Function2<TAccumulate, TSource, TAccumulate> accumulatorAdder,
      Function2<TKey, TAccumulate, TResult> resultSelector,
      Comparator<TKey> comparator);

  /**
   * Correlates the elements of two sequences based on
   * equality of keys and groups the results. The default equality
   * comparer is used to compare keys.
   */
  // groupJoin 是功能最强大的连接算子之一。它结合了 Join（关联） 和 Grouping（分组） 的特性，对应 SQL 中的一种特殊模式：为一个主表记录关联其匹配的所有从表记录集合。
  // groupJoin 的核心逻辑是：对于左序列（TSource）中的每一个元素，去右序列（TInner）中寻找所有匹配的元素，并将这些匹配项作为一个集合传递给结果处理器。
  // 一对多关联：与普通的 join（产生笛卡尔积对）不同，groupJoin 为每个左侧元素只产生 一个 结果。
  // 左外部连接行为：如果右序列中没有匹配项，groupJoin 仍会处理该左侧元素，只是传递给它的右侧集合为空。
  // Enumerable<TInner> inner：右侧（）数据源。
  // Function1<TSource, TKey> outerKeySelector：定义如何从左侧元素提取关联键。
  // Function1<TInner, TKey> innerKeySelector：定义如何从右侧元素提取关联键。
  // Function2<TSource, Enumerable<TInner>, TResult> resultSelector：
  //  输入 1：左侧的单个元素（TSource）。
  //  输入 2：右侧所有匹配该键的元素序列（Enumerable<TInner>）。
  //  输出：你希望得到的最终对象（TResult）。
  <TInner, TKey, TResult> Enumerable<TResult> groupJoin(
      Enumerable<TInner> inner, Function1<TSource, TKey> outerKeySelector,
      Function1<TInner, TKey> innerKeySelector,
      Function2<TSource, Enumerable<TInner>, TResult> resultSelector);

  /**
   * Correlates the elements of two sequences based on
   * key equality and groups the results. A specified
   * {@code EqualityComparer<TSource>} is used to compare keys.
   */
  // 这个 groupJoin 的重载版本是该算子的完全体。它在关联（Join）和分组（Grouping）的基础上，引入了自定义的 EqualityComparer，
  // 使得关联逻辑不再局限于 Java 默认的 equals 和 hashCode 协议。
  <TInner, TKey, TResult> Enumerable<TResult> groupJoin(
      Enumerable<TInner> inner, Function1<TSource, TKey> outerKeySelector,
      Function1<TInner, TKey> innerKeySelector,
      Function2<TSource, Enumerable<TInner>, TResult> resultSelector,
      EqualityComparer<TKey> comparer);

  /**
   * Produces the set intersection of two sequences by
   * using the default equality comparer to compare values,
   * eliminate duplicates. (Defined by Enumerable.)
   */
  Enumerable<TSource> intersect(Enumerable<TSource> enumerable1);

  /**
   * Produces the set intersection of two sequences by
   * using the default equality comparer to compare values,
   * using {@code all} to indicate whether to eliminate duplicates.
   * (Defined by Enumerable.)
   */
  Enumerable<TSource> intersect(Enumerable<TSource> enumerable1, boolean all);

  /**
   * Produces the set intersection of two sequences by
   * using the specified {@code EqualityComparer<TSource>} to compare
   * values, eliminate duplicates.
   */
  Enumerable<TSource> intersect(Enumerable<TSource> enumerable1,
      EqualityComparer<TSource> comparer);

  /**
   * Produces the set intersection of two sequences by
   * using the specified {@code EqualityComparer<TSource>} to compare
   * values, using {@code all} to indicate whether to eliminate duplicates.
   */
  Enumerable<TSource> intersect(Enumerable<TSource> enumerable1,
      EqualityComparer<TSource> comparer, boolean all);
  /**
   * Copies the contents of this sequence into a collection.
   */
  // 属于终端操作，用于将延迟执行的序列（Enumerable）与 Java 标准集合框架（Collection Framework）进行交互。
  // 结果物化 (Materialization)：将流式的 Enumerable 数据拉取到内存中的 List、Set 或其他 Collection 实现中。
  // 副作用操作：与大多数不改变数据源的 Enumerable 方法不同，into 的核心目的就是修改传入的 sink 参数。
  // 该方法将序列中的所有元素填充（Copy）到指定的集合容器中。
  <C extends Collection<? super TSource>> C into(C sink);

  /**
   * Removes the contents of this sequence from a collection.
   */
  // 该方法从指定的集合容器中移除序列中出现的所有元素。
  <C extends Collection<? super TSource>> C removeAll(C sink);

  /**
   * Correlates the elements of two sequences based on
   * matching keys. The default equality comparer is used to compare
   * keys.
   */
  // hashJoin 是实现 SQL INNER JOIN 最常用的物理算子。它利用哈希表（Hash Table）来避免 $O(N \times M)$ 的嵌套循环计算，从而在处理大规模数据集关联时表现出极高的效率。
  // 关联判定：根据左序列（TSource）和右序列（TInner）提取出的键（Key）是否相等来匹配行。
  // 内连接语义 (Inner Join)：只有当左右两侧都存在匹配的键时，才会生成结果行。
  // 结果合成：将匹配的一对对象交给 resultSelector 生成最终的目标对象。
  // hashJoin 的执行分为两个阶段，这在数据库内核中被称为 Hash Join 算法：
  // 阶段 1：构建阶段 (Build Phase)
  // 扫描右序列：遍历 inner 序列。
  // 填充哈希表：利用 innerKeySelector 提取键，并结合 comparer（默认为 Objects.equals）将右侧元素存入内存中的哈希映射（Lookup）中。
  // 注意：在 Calcite 中，通常会选择较小的序列作为 inner 以节省内存。
  // 阶段 2：探测阶段 (Probe Phase)
  // 扫描左序列：流式遍历 source（左侧）序列。
  // 查找匹配：为每个左侧元素提取 outerKey，并在哈希表中查找对应的右侧元素集合。
  // 生成结果：如果找到匹配项，则对每一个匹配的右侧元素调用一次 resultSelector。
  // inner：被关联的右侧序列（通常对应 SQL 中 JOIN 关键字右边的表）。
  // outerKeySelector / innerKeySelector：定义左右两表的关联字段（如 emp.deptId 和 dept.id）。
  // resultSelector：定义如何合并匹配的行。对于内连接，它接收一个左侧对象和一个右侧对象。
  <TInner, TKey, TResult> Enumerable<TResult> hashJoin(Enumerable<TInner> inner,
      Function1<TSource, TKey> outerKeySelector,
      Function1<TInner, TKey> innerKeySelector,
      Function2<TSource, TInner, TResult> resultSelector);

  /**
   * Correlates the elements of two sequences based on
   * matching keys. A specified {@code EqualityComparer<TSource>} is used to
   * compare keys.
   */
  <TInner, TKey, TResult> Enumerable<TResult> hashJoin(Enumerable<TInner> inner,
      Function1<TSource, TKey> outerKeySelector,
      Function1<TInner, TKey> innerKeySelector,
      Function2<TSource, TInner, TResult> resultSelector,
      EqualityComparer<TKey> comparer);

  /**
   * Correlates the elements of two sequences based on matching keys, with
   * optional outer join semantics. A specified
   * {@code EqualityComparer<TSource>} is used to compare keys.
   *
   * <p>A left join generates nulls on right, and vice versa:
   *
   * <table>
   *   <caption>Join types</caption>
   *   <tr>
   *     <td>Join type</td>
   *     <td>generateNullsOnLeft</td>
   *     <td>generateNullsOnRight</td>
   *   </tr>
   *   <tr><td>INNER</td><td>false</td><td>false</td></tr>
   *   <tr><td>LEFT</td><td>false</td><td>true</td></tr>
   *   <tr><td>RIGHT</td><td>true</td><td>false</td></tr>
   *   <tr><td>FULL</td><td>true</td><td>true</td></tr>
   * </table>
   */
  // 这是 ExtendedEnumerable 中最通用、也是功能最强大的 Hash Join 全能版。它不仅支持等值关联，还通过两个布尔标记位完美兼容了 SQL 的四种核心连接语义（INNER, LEFT, RIGHT, FULL）。
  // 该方法通过哈希算法将两个序列进行关联，其最大的特色在于外连接（Outer Join）的物理实现。它允许你在左侧或右侧没有匹配项时，通过填充 null 来保留原本会被丢弃的行。
  <TInner, TKey, TResult> Enumerable<TResult> hashJoin(Enumerable<TInner> inner,
      Function1<TSource, TKey> outerKeySelector,
      Function1<TInner, TKey> innerKeySelector,
      Function2<TSource, TInner, TResult> resultSelector,
      EqualityComparer<TKey> comparer,
      boolean generateNullsOnLeft, boolean generateNullsOnRight);

  /**
   * Correlates the elements of two sequences based on matching keys, with
   * optional outer join semantics. A specified
   * {@code EqualityComparer<TSource>} is used to compare keys.
   *
   * <p>A left join generates nulls on right, and vice versa:
   *
   * <table>
   *   <caption>Join types</caption>
   *   <tr>
   *     <td>Join type</td>
   *     <td>generateNullsOnLeft</td>
   *     <td>generateNullsOnRight</td>
   *   </tr>
   *   <tr><td>INNER</td><td>false</td><td>false</td></tr>
   *   <tr><td>LEFT</td><td>false</td><td>true</td></tr>
   *   <tr><td>RIGHT</td><td>true</td><td>false</td></tr>
   *   <tr><td>FULL</td><td>true</td><td>true</td></tr>
   * </table>
   *
   * <p>A predicate is used to filter the join result per-row
   */
  // hashJoin 算子的终极重载版本。它在支持全量外连接（Full Outer Join）的基础上，引入了 predicate（连接后置谓词），这使得它能够完美处理 SQL 中复杂的 ON 条件，尤其是那些包含“非等值（Non-Equi）”条件的场景。
  // 在 SQL 中，JOIN 条件通常分为两部分：
  // 等值条件（如 a.id = b.a_id）：通过 hashJoin 的 keySelector 和哈希表高效处理。
  // 非等值或复杂逻辑（如 a.price > b.min_price）：通过新增的 predicate 参数在匹配后进行二次过滤。
  <TInner, TKey, TResult> Enumerable<TResult> hashJoin(Enumerable<TInner> inner,
      Function1<TSource, TKey> outerKeySelector,
      Function1<TInner, TKey> innerKeySelector,
      Function2<TSource, TInner, TResult> resultSelector,
      EqualityComparer<TKey> comparer,
      boolean generateNullsOnLeft, boolean generateNullsOnRight,
      Predicate2<TSource, TInner> predicate);

  /**
   * For each row of the current enumerable returns the correlated rows
   * from the {@code inner} enumerable (nested loops join).
   *
   * @param joinType inner, left, semi or anti join type
   * @param inner generator of inner enumerable
   * @param resultSelector selector of the result. For semi/anti join
   *                       inner argument is always null.
   */
  <TInner, TResult> Enumerable<TResult> correlateJoin(
      JoinType joinType, Function1<TSource, Enumerable<TInner>> inner,
      Function2<TSource, TInner, TResult> resultSelector);

  /**
   * Returns the last element of a sequence. (Defined
   * by Enumerable.)
   */
  // 这组 last 方法是 ExtendedEnumerable 中的终端聚合算子，用于提取序列的最后一个元素。它们对应 SQL 中某些特定窗口函数（如 LAST_VALUE）或排序后的单行获取逻辑。
  // 该方法返回序列中的最后一个元素。
  // 物理触发：这是一个立即执行的操作。为了找到“最后一个”，它必须遍历整个序列直到结束。
  TSource last();

  /**
   * Returns the last element of a sequence that
   * satisfies a specified condition.
   */
  // 该方法返回序列中最后一个满足指定条件的元素。
  // 执行逻辑：它会遍历整个序列，不断记录最新一个符合 predicate 要求的元素。当序列遍历结束时，返回最后记录的那个值。
  TSource last(Predicate1<TSource> predicate);

  /**
   * Returns the last element of a sequence, or a
   * default value if the sequence contains no elements.
   */
  @Nullable TSource lastOrDefault();

  /**
   * Returns the last element of a sequence that
   * satisfies a condition or a default value if no such element is
   * found.
   */
  @Nullable TSource lastOrDefault(Predicate1<TSource> predicate);

  /**
   * Returns an long that represents the total number
   * of elements in a sequence.
   */
  long longCount();

  /**
   * Returns an long that represents how many elements
   * in a sequence satisfy a condition.
   */
  long longCount(Predicate1<TSource> predicate);

  /**
   * Returns the maximum value in a generic
   * sequence.
   */
  @Nullable TSource max();

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the maximum Decimal value.
   */
  @Nullable BigDecimal max(BigDecimalFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the maximum nullable Decimal
   * value.
   */
  @Nullable BigDecimal max(NullableBigDecimalFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the maximum Double value.
   */
  double max(DoubleFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the maximum nullable Double
   * value.
   */
  @Nullable Double max(NullableDoubleFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the maximum int value.
   */
  int max(IntegerFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the maximum nullable int value. (Defined
   * by Enumerable.)
   */
  @Nullable Integer max(NullableIntegerFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the maximum long value.
   */
  long max(LongFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the maximum nullable long value. (Defined
   * by Enumerable.)
   */
  @Nullable Long max(NullableLongFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the maximum Float value.
   */
  float max(FloatFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the maximum nullable Float
   * value.
   */
  @Nullable Float max(NullableFloatFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * generic sequence and returns the maximum resulting
   * value.
   */
  <TResult extends Comparable<TResult>> @Nullable TResult max(
      Function1<TSource, TResult> selector);

  /**
   * Returns the minimum value in a generic
   * sequence.
   */
  @Nullable TSource min();

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the minimum Decimal value.
   */
  @Nullable BigDecimal min(BigDecimalFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the minimum nullable Decimal
   * value.
   */
  @Nullable BigDecimal min(NullableBigDecimalFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the minimum Double value.
   */
  double min(DoubleFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the minimum nullable Double
   * value.
   */
  @Nullable Double min(NullableDoubleFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the minimum int value.
   */
  int min(IntegerFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the minimum nullable int value. (Defined
   * by Enumerable.)
   */
  @Nullable Integer min(NullableIntegerFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the minimum long value.
   */
  long min(LongFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the minimum nullable long value. (Defined
   * by Enumerable.)
   */
  @Nullable Long min(NullableLongFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the minimum Float value.
   */
  float min(FloatFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * sequence and returns the minimum nullable Float
   * value.
   */
  @Nullable Float min(NullableFloatFunction1<TSource> selector);

  /**
   * Invokes a transform function on each element of a
   * generic sequence and returns the minimum resulting
   * value.
   */
  <TResult extends Comparable<TResult>> @Nullable TResult min(
      Function1<TSource, TResult> selector);

  /**
   * Filters the elements of an Enumerable based on a
   * specified type.
   *
   * <p>Analogous to LINQ's Enumerable.OfType extension method.
   *
   * @param clazz Target type
   * @param <TResult> Target type
   *
   * @return Collection of T2
   */
  <TResult> Enumerable<TResult> ofType(Class<TResult> clazz);

  /**
   * Sorts the elements of a sequence in ascending
   * order according to a key.
   */
  <TKey extends Comparable> Enumerable<TSource> orderBy(
      Function1<TSource, TKey> keySelector);

  /**
   * Sorts the elements of a sequence in ascending
   * order by using a specified comparer.
   */
  <TKey> Enumerable<TSource> orderBy(Function1<TSource, TKey> keySelector,
      Comparator<TKey> comparator);

  /**
   * Sorts the elements of a sequence in descending
   * order according to a key.
   */
  <TKey extends Comparable> Enumerable<TSource> orderByDescending(
      Function1<TSource, TKey> keySelector);

  /**
   * Sorts the elements of a sequence in descending
   * order by using a specified comparer.
   */
  <TKey> Enumerable<TSource> orderByDescending(
      Function1<TSource, TKey> keySelector, Comparator<TKey> comparator);

  /**
   * Inverts the order of the elements in a
   * sequence.
   */
  Enumerable<TSource> reverse();

  /**
   * Projects each element of a sequence into a new
   * form.
   */
  // 这组 select 方法是 ExtendedEnumerable 中最核心的**投影（Projection）**算子。它直接对应 SQL 中的 SELECT 子句，负责对每一行数据进行转换、映射或提取特定字段。
  // 将源序列中的每一个元素按 1:1 的比例转换为另一种形式。
  // 核心作用：
  // 字段提取：从一个复杂的 Employee 对象中提取 name 字符串。
  // 类型转换：将 String 序列转换为 Integer 序列。
  // 计算衍生值：根据 price 计算 price * 0.9（打折价）。
  <TResult> Enumerable<TResult> select(Function1<TSource, TResult> selector);

  /**
   * Projects each element of a sequence into a new
   * form by incorporating the element's index.
   */
  // 这是一个增强型投影，它在转换时引入了元素的索引（Index）。
  // TSource：当前的元素。
  // Integer：当前元素在序列中的位置（从 0 开始计数）。
  <TResult> Enumerable<TResult> select(
      Function2<TSource, Integer, TResult> selector);

  /**
   * Projects each element of a sequence to an
   * {@code Enumerable<TSource>} and flattens the resulting sequences into one
   * sequence.
   */
  // selectMany 是功能最强大的投影算子。如果说 select 是 1 对 1 的转换，那么 selectMany 就是 1 对 多 的转换，并且会自动执行 扁平化（Flattening） 操作。
  // 它在 SQL 中对应着处理嵌套集合、**数组拆解（UNNEST）以及交叉应用（CROSS APPLY）**的关键逻辑。
  // selectMany 执行以下两个步骤：
  // 投影 (Map)：将源序列中的每一个元素 TSource 转换为一个子序列 Enumerable<TResult>。
  // 扁平化 (Flatten)：将产生的所有子序列首尾相连，合并成一个统一的、扁平的 Enumerable<TResult>。
  <TResult> Enumerable<TResult> selectMany(
      Function1<TSource, Enumerable<TResult>> selector);

  /**
   * Projects each element of a sequence to an
   * {@code Enumerable<TSource>}, and flattens the resulting sequences into one
   * sequence. The index of each source element is used in the
   * projected form of that element.
   */
  <TResult> Enumerable<TResult> selectMany(
      Function2<TSource, Integer, Enumerable<TResult>> selector);

  /**
   * Projects each element of a sequence to an
   * {@code Enumerable<TSource>}, flattens the resulting sequences into one
   * sequence, and invokes a result selector function on each
   * element therein. The index of each source element is used in
   * the intermediate projected form of that element.
   */
  <TCollection, TResult> Enumerable<TResult> selectMany(
      Function2<TSource, Integer, Enumerable<TCollection>> collectionSelector,
      Function2<TSource, TCollection, TResult> resultSelector);

  /**
   * Projects each element of a sequence to an
   * {@code Enumerable<TSource>}, flattens the resulting sequences into one
   * sequence, and invokes a result selector function on each
   * element therein.
   */
  <TCollection, TResult> Enumerable<TResult> selectMany(
      Function1<TSource, Enumerable<TCollection>> collectionSelector,
      Function2<TSource, TCollection, TResult> resultSelector);

  /**
   * Determines whether two sequences are equal by
   * comparing the elements by using the default equality comparer
   * for their type.
   */
  boolean sequenceEqual(Enumerable<TSource> enumerable1);

  /**
   * Determines whether two sequences are equal by
   * comparing their elements by using a specified
   * {@code EqualityComparer<TSource>}.
   */
  boolean sequenceEqual(Enumerable<TSource> enumerable1,
      EqualityComparer<TSource> comparer);

  /**
   * Returns the only element of a sequence, and throws
   * an exception if there is not exactly one element in the
   * sequence.
   */
  TSource single();

  /**
   * Returns the only element of a sequence that
   * satisfies a specified condition, and throws an exception if
   * more than one such element exists.
   */
  TSource single(Predicate1<TSource> predicate);

  /**
   * Returns the only element of a sequence, or a
   * default value if the sequence is empty; this method throws an
   * exception if there is more than one element in the
   * sequence.
   */
  @Nullable TSource singleOrDefault();

  /**
   * Returns the only element of a sequence that
   * satisfies a specified condition or a default value if no such
   * element exists; this method throws an exception if more than
   * one element satisfies the condition.
   */
  @Nullable TSource singleOrDefault(Predicate1<TSource> predicate);

  /**
   * Bypasses a specified number of elements in a
   * sequence and then returns the remaining elements.
   */
  Enumerable<TSource> skip(int count);

  /**
   * Bypasses elements in a sequence as long as a
   * specified condition is true and then returns the remaining
   * elements.
   */
  Enumerable<TSource> skipWhile(Predicate1<TSource> predicate);

  /**
   * Bypasses elements in a sequence as long as a
   * specified condition is true and then returns the remaining
   * elements. The element's index is used in the logic of the
   * predicate function.
   */
  Enumerable<TSource> skipWhile(Predicate2<TSource, Integer> predicate);

  /**
   * Computes the sum of the sequence of Decimal values
   * that are obtained by invoking a transform function on each
   * element of the input sequence.
   */
  BigDecimal sum(BigDecimalFunction1<TSource> selector);

  /**
   * Computes the sum of the sequence of nullable
   * Decimal values that are obtained by invoking a transform
   * function on each element of the input sequence.
   */
  BigDecimal sum(NullableBigDecimalFunction1<TSource> selector);

  /**
   * Computes the sum of the sequence of Double values
   * that are obtained by invoking a transform function on each
   * element of the input sequence.
   */
  double sum(DoubleFunction1<TSource> selector);

  /**
   * Computes the sum of the sequence of nullable
   * Double values that are obtained by invoking a transform
   * function on each element of the input sequence.
   */
  Double sum(NullableDoubleFunction1<TSource> selector);

  /**
   * Computes the sum of the sequence of int values
   * that are obtained by invoking a transform function on each
   * element of the input sequence.
   */
  int sum(IntegerFunction1<TSource> selector);

  /**
   * Computes the sum of the sequence of nullable int
   * values that are obtained by invoking a transform function on
   * each element of the input sequence.
   */
  Integer sum(NullableIntegerFunction1<TSource> selector);

  /**
   * Computes the sum of the sequence of long values
   * that are obtained by invoking a transform function on each
   * element of the input sequence.
   */
  long sum(LongFunction1<TSource> selector);

  /**
   * Computes the sum of the sequence of nullable long
   * values that are obtained by invoking a transform function on
   * each element of the input sequence.
   */
  Long sum(NullableLongFunction1<TSource> selector);

  /**
   * Computes the sum of the sequence of Float values
   * that are obtained by invoking a transform function on each
   * element of the input sequence.
   */
  float sum(FloatFunction1<TSource> selector);

  /**
   * Computes the sum of the sequence of nullable
   * Float values that are obtained by invoking a transform
   * function on each element of the input sequence.
   */
  Float sum(NullableFloatFunction1<TSource> selector);

  /**
   * Returns a specified number of contiguous elements
   * from the start of a sequence.
   */
  Enumerable<TSource> take(int count);

  /**
   * Returns elements from a sequence as long as a
   * specified condition is true.
   */
  Enumerable<TSource> takeWhile(Predicate1<TSource> predicate);

  /**
   * Returns elements from a sequence as long as a
   * specified condition is true. The element's index is used in the
   * logic of the predicate function.
   */
  Enumerable<TSource> takeWhile(Predicate2<TSource, Integer> predicate);

  /**
   * Creates a {@code Map<TKey, TValue>} from an
   * {@code Enumerable<TSource>} according to a specified key selector
   * function.
   *
   * <p>NOTE: Called {@code toDictionary} in LINQ.NET.
   */
  <TKey> Map<TKey, TSource> toMap(Function1<TSource, TKey> keySelector);

  /**
   * Creates a {@code Map<TKey, TValue>} from an
   * {@code Enumerable<TSource>} according to a specified key selector function
   * and key comparer.
   */
  <TKey> Map<TKey, TSource> toMap(Function1<TSource, TKey> keySelector,
      EqualityComparer<TKey> comparer);

  /**
   * Creates a {@code Map<TKey, TValue>} from an
   * {@code Enumerable<TSource>} according to specified key selector and element
   * selector functions.
   */
  <TKey, TElement> Map<TKey, TElement> toMap(
      Function1<TSource, TKey> keySelector,
      Function1<TSource, TElement> elementSelector);

  /**
   * Creates a {@code Map<TKey, TValue>} from an
   * {@code Enumerable<TSource>} according to a specified key selector function,
   * a comparer, and an element selector function.
   */
  <TKey, TElement> Map<TKey, TElement> toMap(
      Function1<TSource, TKey> keySelector,
      Function1<TSource, TElement> elementSelector,
      EqualityComparer<TKey> comparer);

  /**
   * Creates a {@code List<TSource>} from an {@code Enumerable<TSource>}.
   */
  List<TSource> toList();

  /**
   * Creates a {@code Lookup<TKey, TElement>} from an
   * {@code Enumerable<TSource>} according to a specified key selector
   * function.
   */
  <TKey> Lookup<TKey, TSource> toLookup(Function1<TSource, TKey> keySelector);

  /**
   * Creates a {@code Lookup<TKey, TElement>} from an
   * {@code Enumerable<TSource>} according to a specified key selector function
   * and key comparer.
   */
  <TKey> Lookup<TKey, TSource> toLookup(Function1<TSource, TKey> keySelector,
      EqualityComparer<TKey> comparer);

  /**
   * Creates a {@code Lookup<TKey, TElement>} from an
   * {@code Enumerable<TSource>} according to specified key selector and element
   * selector functions.
   */
  <TKey, TElement> Lookup<TKey, TElement> toLookup(
      Function1<TSource, TKey> keySelector,
      Function1<TSource, TElement> elementSelector);

  /**
   * Creates a {@code Lookup<TKey, TElement>} from an
   * {@code Enumerable<TSource>} according to a specified key selector function,
   * a comparer and an element selector function.
   */
  <TKey, TElement> Lookup<TKey, TElement> toLookup(
      Function1<TSource, TKey> keySelector,
      Function1<TSource, TElement> elementSelector,
      EqualityComparer<TKey> comparer);

  /**
   * Produces the set union of two sequences by using
   * the default equality comparer.
   */
  Enumerable<TSource> union(Enumerable<TSource> source1);

  /**
   * Produces the set union of two sequences by using a
   * specified {@code EqualityComparer<TSource>}.
   */
  Enumerable<TSource> union(Enumerable<TSource> source1,
      EqualityComparer<TSource> comparer);

  /**
   * Filters a sequence of values based on a
   * predicate.
   */
  Enumerable<TSource> where(Predicate1<TSource> predicate);

  /**
   * Filters a sequence of values based on a
   * predicate. Each element's index is used in the logic of the
   * predicate function.
   */
  Enumerable<TSource> where(Predicate2<TSource, Integer> predicate);

  /**
   * Applies a specified function to the corresponding
   * elements of two sequences, producing a sequence of the
   * results.
   */
  <T1, TResult> Enumerable<TResult> zip(Enumerable<T1> source1,
      Function2<TSource, T1, TResult> resultSelector);
}
