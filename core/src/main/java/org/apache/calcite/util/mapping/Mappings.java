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

import org.apache.calcite.util.BitSets;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Permutation;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.google.errorprone.annotations.CheckReturnValue;

import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Utility functions related to mappings.
 *
 * @see MappingType
 * @see Mapping
 * @see org.apache.calcite.util.Permutation
 */
public abstract class Mappings {
  //~ Constructors -----------------------------------------------------------

  private Mappings() {
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Creates a mapping with required properties.
   */
  public static Mapping create(
      MappingType mappingType,
      int sourceCount,
      int targetCount) {
    switch (mappingType) {
    case BIJECTION:
      assert sourceCount == targetCount;
      return new Permutation(sourceCount);
    case INVERSE_SURJECTION:
      assert sourceCount >= targetCount;
      return new SurjectionWithInverse(
          sourceCount,
          targetCount);
    case PARTIAL_SURJECTION:
    case SURJECTION:
      return new Mappings.PartialMapping(
          sourceCount,
          targetCount,
          mappingType);
    case PARTIAL_FUNCTION:
    case FUNCTION:
      return new PartialFunctionImpl(
          sourceCount,
          targetCount,
          mappingType);
    case INVERSE_FUNCTION:
    case INVERSE_PARTIAL_FUNCTION:
      return new InverseMapping(
          create(mappingType.inverse(), targetCount, sourceCount));
    default:
      throw Util.needToImplement(
          "no known implementation for mapping type " + mappingType);
    }
  }

  /**
   * Creates the identity mapping.
   *
   * <p>For example, {@code createIdentity(2)} returns the mapping
   * {0:0, 1:1, 2:2}.
   *
   * @param fieldCount Number of sources/targets
   * @return Identity mapping
   */
  public static IdentityMapping createIdentity(int fieldCount) {
    return new Mappings.IdentityMapping(fieldCount);
  }

  /**
   * Converts a mapping to its inverse.
   */
  public static Mapping invert(Mapping mapping) {
    if (mapping instanceof InverseMapping) {
      return ((InverseMapping) mapping).parent;
    }
    return new InverseMapping(mapping);
  }

  /**
   * Divides one mapping by another.
   *
   * <p>{@code divide(A, B)} returns a mapping C such that B . C (the mapping
   * B followed by the mapping C) is equivalent to A.
   *
   * @param mapping1 First mapping
   * @param mapping2 Second mapping
   * @return Mapping mapping3 such that mapping1 = mapping2 . mapping3
   */
  public static Mapping divide(Mapping mapping1, Mapping mapping2) {
    if (mapping1.getSourceCount() != mapping2.getSourceCount()) {
      throw new IllegalArgumentException();
    }
    Mapping remaining =
        create(
            MappingType.INVERSE_SURJECTION,
            mapping2.getTargetCount(),
            mapping1.getTargetCount());
    for (int target = 0; target < mapping1.getTargetCount(); ++target) {
      int source = mapping1.getSourceOpt(target);
      if (source >= 0) {
        int x = mapping2.getTarget(source);
        remaining.set(x, target);
      }
    }
    return remaining;
  }

  /**
   * Multiplies one mapping by another.
   *
   * <p>{@code multiply(A, B)} returns a mapping C such that A . B (the mapping
   * A followed by the mapping B) is equivalent to C.
   *
   * @param mapping1 First mapping
   * @param mapping2 Second mapping
   * @return Mapping mapping3 such that mapping1 = mapping2 . mapping3
   */
  public static Mapping multiply(Mapping mapping1, Mapping mapping2) {
    if (mapping1.getTargetCount() != mapping2.getSourceCount()) {
      throw new IllegalArgumentException();
    }
    Mapping product =
        create(
            MappingType.INVERSE_SURJECTION,
            mapping1.getSourceCount(),
            mapping2.getTargetCount());
    for (int source = 0; source < mapping1.getSourceCount(); ++source) {
      int x = mapping1.getTargetOpt(source);
      if (x >= 0) {
        int target = mapping2.getTarget(x);
        product.set(source, target);
      }
    }
    return product;
  }

  /**
   * Applies a mapping to a BitSet.
   *
   * <p>If the mapping does not affect the bit set, returns the original.
   * Never changes the original.
   *
   * @param mapping Mapping
   * @param bitSet  Bit set
   * @return Bit set with mapping applied
   */
  public static BitSet apply(Mapping mapping, BitSet bitSet) {
    final BitSet newBitSet = new BitSet();
    for (int source : BitSets.toIter(bitSet)) {
      final int target = mapping.getTarget(source);
      newBitSet.set(target);
    }
    if (newBitSet.equals(bitSet)) {
      return bitSet;
    }
    return newBitSet;
  }

  /**
   * Applies a mapping to an {@code ImmutableBitSet}.
   *
   * <p>If the mapping does not affect the bit set, returns the original.
   * Never changes the original.
   *
   * @param mapping Mapping
   * @param bitSet  Bit set
   * @return Bit set with mapping applied
   */
  public static ImmutableBitSet apply(Mapping mapping, ImmutableBitSet bitSet) {
    final ImmutableBitSet.Builder builder = ImmutableBitSet.builder();
    for (int source : bitSet) {
      final int target = mapping.getTarget(source);
      builder.set(target);
    }
    return builder.build(bitSet);
  }

  /**
   * Applies a mapping to a collection of {@code ImmutableBitSet}s.
   *
   * @param mapping Mapping
   * @param bitSets Collection of bit sets
   * @return Sorted bit sets with mapping applied
   */
  public static ImmutableList<ImmutableBitSet> apply2(final Mapping mapping,
      Iterable<ImmutableBitSet> bitSets) {
    return ImmutableList.copyOf(
        ImmutableBitSet.ORDERING.sortedCopy(
            Util.transform(bitSets, input1 -> apply(mapping, input1))));
  }

  /**
   * Applies a mapping to a list.
   *
   * @param mapping Mapping
   * @param list    List
   * @param <T>     Element type
   * @return List with elements permuted according to mapping
   */
  public static <T> List<T> apply(final Mapping mapping, final List<T> list) {
    if (mapping.getSourceCount() != list.size()) {
      // REVIEW: too strict?
      throw new IllegalArgumentException("mapping source count "
          + mapping.getSourceCount()
          + " does not match list size " + list.size());
    }
    final int targetCount = mapping.getTargetCount();
    final List<T> list2 = new ArrayList<>(targetCount);
    for (int target = 0; target < targetCount; ++target) {
      final int source = mapping.getSource(target);
      list2.add(list.get(source));
    }
    return list2;
  }

  public static List<Integer> apply2(
      final Mapping mapping,
      final List<Integer> list) {
    return new AbstractList<Integer>() {
      @Override public Integer get(int index) {
        final int source = list.get(index);
        return mapping.getTarget(source);
      }

      @Override public int size() {
        return list.size();
      }
    };
  }

  /**
   * Creates a view of a list, permuting according to a mapping.
   *
   * @param mapping Mapping
   * @param list    List
   * @param <T>     Element type
   * @return Permuted view of list
   */
  public static <T> List<T> apply3(
      final Mapping mapping,
      final List<T> list) {
    return new AbstractList<T>() {
      @Override public T get(int index) {
        return list.get(mapping.getSource(index));
      }

      @Override public int size() {
        return mapping.getTargetCount();
      }
    };
  }

  /**
   * Creates a view of a list, permuting according to a target mapping.
   *
   * @param mapping Mapping
   * @param list    List
   * @param <T>     Element type
   * @return Permuted view of list
   */
  public static <T> List<T> permute(final List<T> list,
      final TargetMapping mapping) {
    return new AbstractList<T>() {
      @Override public T get(int index) {
        return list.get(mapping.getTarget(index));
      }

      @Override public int size() {
        return mapping.getSourceCount();
      }
    };
  }

  /**
   * Returns a mapping as a list such that {@code list.get(source)} is
   * {@code mapping.getTarget(source)} and {@code list.size()} is
   * {@code mapping.getSourceCount()}.
   *
   * <p>Converse of {@link #target(List, int)}.
   *
   * @see #asListNonNull(TargetMapping)
   */
  @CheckReturnValue
  public static List<@Nullable Integer> asList(final TargetMapping mapping) {
    return new AbstractList<@Nullable Integer>() {
      @Override public @Nullable Integer get(int source) {
        int target = mapping.getTargetOpt(source);
        return target < 0 ? null : target;
      }

      @Override public int size() {
        return mapping.getSourceCount();
      }
    };
  }

  /**
   * Returns a mapping as a list such that {@code list.get(source)} is
   * {@code mapping.getTarget(source)} and {@code list.size()} is
   * {@code mapping.getSourceCount()}.
   *
   * <p>The resulting list never contains null elements.
   *
   * <p>Converse of {@link #target(List, int)}.
   *
   * @see #asList(TargetMapping)
   */
  @CheckReturnValue
  public static List<Integer> asListNonNull(final TargetMapping mapping) {
    return new AbstractList<Integer>() {
      @Override public Integer get(int source) {
        int target = mapping.getTargetOpt(source);
        if (target < 0) {
          throw new IllegalArgumentException("Element " + source + " is not found in mapping "
              + mapping);
        }
        return target;
      }

      @Override public int size() {
        return mapping.getSourceCount();
      }
    };
  }

  /**
   * Converts a {@link Map} of integers to a {@link TargetMapping}.
   */
  public static TargetMapping target(
      Map<Integer, Integer> map,
      int sourceCount,
      int targetCount) {
    final PartialFunctionImpl mapping =
        new PartialFunctionImpl(
            sourceCount, targetCount, MappingType.FUNCTION);
    for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
      mapping.set(entry.getKey(), entry.getValue());
    }
    return mapping;
  }

  public static TargetMapping target(
      IntFunction<? extends @Nullable Integer> function,
      int sourceCount,
      int targetCount) {
    final PartialFunctionImpl mapping =
        new PartialFunctionImpl(sourceCount, targetCount, MappingType.FUNCTION);
    for (int source = 0; source < sourceCount; source++) {
      Integer target = function.apply(source);
      if (target != null) {
        mapping.set(source, target);
      }
    }
    return mapping;
  }

  public static Mapping target(Iterable<IntPair> pairs, int sourceCount,
      int targetCount) {
    final PartialFunctionImpl mapping =
        new PartialFunctionImpl(sourceCount, targetCount, MappingType.FUNCTION);
    for (IntPair pair : pairs) {
      mapping.set(pair.source, pair.target);
    }
    return mapping;
  }

  public static Mapping source(List<Integer> targets, int targetCount) {
    final int sourceCount = targets.size();
    final PartialFunctionImpl mapping =
        new PartialFunctionImpl(sourceCount, targetCount, MappingType.FUNCTION);
    for (int source = 0; source < sourceCount; source++) {
      int target = targets.get(source);
      mapping.set(source, target);
    }
    return mapping;
  }

  public static Mapping target(List<Integer> sources, int sourceCount) {
    final int targetCount = sources.size();
    final PartialFunctionImpl mapping =
        new PartialFunctionImpl(sourceCount, targetCount, MappingType.FUNCTION);
    for (int target = 0; target < targetCount; target++) {
      int source = sources.get(target);
      mapping.set(source, target);
    }
    return mapping;
  }

  /** Creates a bijection.
   *
   * <p>Throws if sources and targets are not one to one. */
  public static Mapping bijection(List<Integer> targets) {
    return new Permutation(Ints.toArray(targets));
  }

  /** Creates a bijection.
   *
   * <p>Throws if sources and targets are not one to one. */
  public static Mapping bijection(Map<Integer, Integer> targets) {
    int[] ints = new int[targets.size()];
    for (int i = 0; i < targets.size(); i++) {
      Integer value = targets.get(i);
      if (value == null) {
        throw new NullPointerException("Index " + i + " is not mapped in " + targets);
      }
      ints[i] = value;
    }
    return new Permutation(ints);
  }

  /**
   * Returns whether a mapping is the identity.
   */
  public static boolean isIdentity(TargetMapping mapping) {
    if (mapping.getSourceCount() != mapping.getTargetCount()) {
      return false;
    }
    for (int i = 0; i < mapping.getSourceCount(); i++) {
      if (mapping.getTargetOpt(i) != i) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns whether a mapping keeps order.
   *
   * <p>For example, {0:0, 1:1} and {0:1, 1:1} keeps order,
   * and {0:1, 1:0} breaks the initial order.
   */
  public static boolean keepsOrdering(TargetMapping mapping) {
    int prevTarget = -1;
    for (int i = 0; i < mapping.getSourceCount(); i++) {
      int target = mapping.getTargetOpt(i);
      if (target != -1) {
        if (target < prevTarget) {
          return false;
        }
        prevTarget = target;
      }
    }
    return true;
  }

  /**
   * Creates a mapping that consists of a set of contiguous ranges.
   *
   * <p>For example,
   *
   * <blockquote><pre>createShiftMapping(60,
   *     100, 0, 3,
   *     200, 50, 5);
   * </pre></blockquote>
   *
   * <p>creates
   *
   * <table>
   * <caption>Example mapping</caption>
   * <tr><th>Source</th><th>Target</th></tr>
   * <tr><td>0</td><td>100</td></tr>
   * <tr><td>1</td><td>101</td></tr>
   * <tr><td>2</td><td>102</td></tr>
   * <tr><td>3</td><td>-1</td></tr>
   * <tr><td>...</td><td>-1</td></tr>
   * <tr><td>50</td><td>200</td></tr>
   * <tr><td>51</td><td>201</td></tr>
   * <tr><td>52</td><td>202</td></tr>
   * <tr><td>53</td><td>203</td></tr>
   * <tr><td>54</td><td>204</td></tr>
   * <tr><td>55</td><td>-1</td></tr>
   * <tr><td>...</td><td>-1</td></tr>
   * <tr><td>59</td><td>-1</td></tr>
   * </table>
   *
   * @param sourceCount Maximum value of {@code source}
   * @param ints        Collection of ranges, each
   *                    {@code (target, source, count)}
   * @return Mapping that maps from source ranges to target ranges
   */
  public static TargetMapping createShiftMapping(
      int sourceCount, int... ints) {
    int targetCount = 0;
    assert ints.length % 3 == 0;
    for (int i = 0; i < ints.length; i += 3) {
      final int target = ints[i];
      final int length = ints[i + 2];
      final int top = target + length;
      targetCount = Math.max(targetCount, top);
    }
    final TargetMapping mapping =
        create(
            MappingType.INVERSE_SURJECTION,
            sourceCount,
            targetCount);

    for (int i = 0; i < ints.length; i += 3) {
      final int target = ints[i];
      final int source = ints[i + 1];
      final int length = ints[i + 2];
      assert source + length <= sourceCount;
      for (int j = 0; j < length; j++) {
        assert mapping.getTargetOpt(source + j) == -1;
        mapping.set(source + j, target + j);
      }
    }
    return mapping;
  }

  /**
   * Creates a mapping by appending two mappings.
   *
   * <p>Sources and targets of the second mapping are shifted to the right.
   *
   * <p>For example, <pre>append({0:0, 1:1}, {0:0, 1:1, 2:2})</pre> yields
   * <pre>{0:0, 1:1, 2:2, 3:3, 4:4}</pre>.
   *
   * @see #merge
   */
  public static TargetMapping append(
      TargetMapping mapping0,
      TargetMapping mapping1) {
    final int s0 = mapping0.getSourceCount();
    final int s1 = mapping1.getSourceCount();
    final int t0 = mapping0.getTargetCount();
    final int t1 = mapping1.getTargetCount();
    final TargetMapping mapping =
        create(MappingType.INVERSE_SURJECTION, s0 + s1, t0 + t1);
    for (int s = 0; s < s0; s++) {
      int t = mapping0.getTargetOpt(s);
      if (t >= 0) {
        mapping.set(s, t);
      }
    }
    for (int s = 0; s < s1; s++) {
      int t = mapping1.getTargetOpt(s);
      if (t >= 0) {
        mapping.set(s0 + s, t0 + t);
      }
    }
    return mapping;
  }

  /**
   * Creates a mapping by merging two mappings. There must be no clashes.
   *
   * <p>Unlike {@link #append}, sources and targets are not shifted.
   *
   * <p>For example, <code>merge({0:0, 1:1}, {2:2, 3:3, 4:4})</code> yields
   * <code>{0:0, 1:1, 2:2, 3:3, 4:4}</code>.
   * <code>merge({0:0, 1:1}, {1:2, 2:3})</code> throws, because there are
   * two entries with source=1.
   */
  public static TargetMapping merge(
      TargetMapping mapping0,
      TargetMapping mapping1) {
    final int s0 = mapping0.getSourceCount();
    final int s1 = mapping1.getSourceCount();
    final int sMin = Math.min(s0, s1);
    final int sMax = Math.max(s0, s1);
    final int t0 = mapping0.getTargetCount();
    final int t1 = mapping1.getTargetCount();
    final int tMax = Math.max(t0, t1);
    final TargetMapping mapping =
        create(MappingType.INVERSE_SURJECTION, sMax, tMax);
    for (int s = 0; s < sMin; s++) {
      int t = mapping0.getTargetOpt(s);
      if (t >= 0) {
        mapping.set(s, t);
        assert mapping1.getTargetOpt(s) < 0;
      } else {
        t = mapping1.getTargetOpt(s);
        if (t >= 0) {
          mapping.set(s, t);
        }
      }
    }
    for (int s = sMin; s < sMax; s++) {
      int t = s < s0 ? mapping0.getTargetOpt(s) : -1;
      if (t >= 0) {
        mapping.set(s, t);
        assert s >= s1 || mapping1.getTargetOpt(s) < 0;
      } else {
        t = s < s1 ? mapping1.getTargetOpt(s) : -1;
        if (t >= 0) {
          mapping.set(s, t);
        }
      }
    }
    return mapping;
  }

  /**
   * Returns a mapping that shifts a given mapping's source by a given
   * offset, incrementing the number of sources by the minimum possible.
   *
   * @param mapping     Input mapping
   * @param offset      Offset to be applied to each source
   * @return Shifted mapping
   */
  public static TargetMapping offsetSource(
      final TargetMapping mapping, final int offset) {
    return offsetSource(mapping, offset, mapping.getSourceCount() + offset);
  }

  /**
   * Returns a mapping that shifts a given mapping's source by a given
   * offset.
   *
   * <p>For example, given {@code mapping} with sourceCount=2, targetCount=8,
   * and (source, target) entries {[0: 5], [1: 7]}, offsetSource(mapping, 3)
   * returns a mapping with sourceCount=5, targetCount=8,
   * and (source, target) entries {[3: 5], [4: 7]}.
   *
   * @param mapping     Input mapping
   * @param offset      Offset to be applied to each source
   * @param sourceCount New source count; must be at least {@code mapping}'s
   *                    source count plus {@code offset}
   * @return Shifted mapping
   */
  public static TargetMapping offsetSource(
      final TargetMapping mapping, final int offset, final int sourceCount) {
    if (sourceCount < mapping.getSourceCount() + offset) {
      throw new IllegalArgumentException("new source count too low");
    }
    return target(
        (IntFunction<@Nullable Integer>) source -> {
          int source2 = source - offset;
          return source2 < 0 || source2 >= mapping.getSourceCount()
              ? null
              : mapping.getTargetOpt(source2);
        },
        sourceCount,
        mapping.getTargetCount());
  }

  /**
   * Returns a mapping that shifts a given mapping's target by a given
   * offset, incrementing the number of targets by the minimum possible.
   *
   * @param mapping     Input mapping
   * @param offset      Offset to be applied to each target
   * @return Shifted mapping
   */
  public static TargetMapping offsetTarget(
      final TargetMapping mapping, final int offset) {
    return offsetTarget(mapping, offset, mapping.getTargetCount() + offset);
  }

  /**
   * Returns a mapping that shifts a given mapping's target by a given
   * offset.
   *
   * <p>For example, given {@code mapping} with sourceCount=2, targetCount=8,
   * and (source, target) entries {[0: 5], [1: 7]}, offsetTarget(mapping, 3)
   * returns a mapping with sourceCount=2, targetCount=11,
   * and (source, target) entries {[0: 8], [1: 10]}.
   *
   * @param mapping     Input mapping
   * @param offset      Offset to be applied to each target
   * @param targetCount New target count; must be at least {@code mapping}'s
   *                    target count plus {@code offset}
   * @return Shifted mapping
   */
  public static TargetMapping offsetTarget(
      final TargetMapping mapping, final int offset, final int targetCount) {
    if (targetCount < mapping.getTargetCount() + offset) {
      throw new IllegalArgumentException("new target count too low");
    }
    return target(
        (IntFunction<@Nullable Integer>) source -> {
          int target = mapping.getTargetOpt(source);
          return target < 0 ? null : target + offset;
        },
        mapping.getSourceCount(), targetCount);
  }

  /**
   * Returns a mapping that shifts a given mapping's source and target by a
   * given offset.
   *
   * <p>For example, given {@code mapping} with sourceCount=2, targetCount=8,
   * and (source, target) entries {[0: 5], [1: 7]}, offsetSource(mapping, 3)
   * returns a mapping with sourceCount=5, targetCount=8,
   * and (source, target) entries {[3: 8], [4: 10]}.
   *
   * @param mapping     Input mapping
   * @param offset      Offset to be applied to each source
   * @param sourceCount New source count; must be at least {@code mapping}'s
   *                    source count plus {@code offset}
   * @return Shifted mapping
   */
  public static TargetMapping offset(
      final TargetMapping mapping, final int offset, final int sourceCount) {
    if (sourceCount < mapping.getSourceCount() + offset) {
      throw new IllegalArgumentException("new source count too low");
    }
    return target(
        (IntFunction<@Nullable Integer>) source -> {
          final int source2 = source - offset;
          if (source2 < 0 || source2 >= mapping.getSourceCount()) {
            return null;
          }
          int target = mapping.getTargetOpt(source2);
          if (target < 0) {
            return null;
          }
          return target + offset;
        },
        sourceCount,
        mapping.getTargetCount() + offset);
  }
   //校验字段的序号是否跟数量一致
  /** Returns whether a list of integers is the identity mapping
   * [0, ..., n - 1]. */
  public static boolean isIdentity(List<Integer> list, int count) {
    if (list.size() != count) {
      return false;
    }
    for (int i = 0; i < count; i++) {
      final Integer o = list.get(i);
      if (o == null || o != i) {
        return false;
      }
    }
    return true;
  }

  /** Inverts an {@link java.lang.Iterable} over
   * {@link org.apache.calcite.util.mapping.IntPair}s. */
  public static Iterable<IntPair> invert(final Iterable<IntPair> pairs) {
    return () -> invert(pairs.iterator());
  }

  /** Inverts an {@link java.util.Iterator} over
   * {@link org.apache.calcite.util.mapping.IntPair}s. */
  public static Iterator<IntPair> invert(final Iterator<IntPair> pairs) {
    return new Iterator<IntPair>() {
      @Override public boolean hasNext() {
        return pairs.hasNext();
      }

      @Override public IntPair next() {
        final IntPair pair = pairs.next();
        return IntPair.of(pair.target, pair.source);
      }

      @Override public void remove() {
        throw new UnsupportedOperationException("remove");
      }
    };
  }

  /** Applies a mapping to an optional integer, returning an optional
   * result. */
  public static int apply(TargetMapping mapping, int i) {
    return i < 0 ? i : mapping.getTarget(i);
  }

  //~ Inner Interfaces -------------------------------------------------------

  /**
   * Core interface of all mappings.
   */
  // CoreMapping 的核心目的是定义一个统一的、可迭代的整数到整数（int -> int）的映射字典标准。
  // 在关系代数优化中，无论是把 Filter 算子中的字段索引进行整体平移，还是在 Project 中把多列合并、调换顺序，底层都需要一个“映射表”。CoreMapping 提供了对这个映射表最抽象的宣告：
  // 它说明了任何一个具体的映射结构，都可以被看作是一组 IntPair 的集合。
  // 它强制要求所有映射必须声明自己的“数学约束边界”（即 MappingType），以便优化器内部进行合规性检查（例如：一个要求是 BIJECTION 的重写算法，如果拿到了一个 FUNCTION 类型的映射，就可以直接报错拦截）。

  public interface CoreMapping extends Iterable<IntPair> {
    /**
     * Returns the mapping type.
     *
     * @return Mapping type
     */
    // 获取当前映射实例所严格遵守的数学映射类型与约束条件。
    // 应用场景：优化器在拿到一个映射对象时，会先调用此方法。如果返回的是 BIJECTION，优化器就知道这个映射是可逆的（可以调用 inverse()），并且源和目标是一对一等长的，从而可以采用极高效率的数组直接寻址算法。
    MappingType getMappingType();

    /**
     * Returns the number of elements in the mapping.
     */
    // 返回当前映射中所包含的有效映射对（Elements/Pairs）的数量。
    int size();
  }

  /**
   * Mapping where every source has a target. But:
   *
   * <ul>
   * <li>A target may not have a source.
   * <li>May not be finite.
   * </ul>
   */
  // 代表了一个标准的“全函数映射”（Total Function Mapping），即每一个源（Source）都必须有且仅有一个明确指向的目标（Target）。
  // 每一个源都有目标（Every source has a target）：
  // 对于定义域内的任意一个 source 索引，你都能通过它找到对应的 target。这就决定了它符合“函数（Function）”的定义。
  // 目标不一定有源（A target may not have a source）：
  // 允许值域（Target Domain）中的某些特定目标被留空。换句话说，它不一定是满射（Surjection）。在 SQL 场景中，这代表目标表（或上层算子）中多出来的列，在当前输入源中没有对应的数据来源。
  // 可能是无限的（May not be finite）：
  // 这个设计非常巧妙！这意味着 FunctionMapping 的底层实现不一定非要用一个固定大小的数组或集合把所有映射对死死存下来。它可以是一个基于公式计算的虚映射。例如：target = source + 2（字段整体平移偏移量），这种映射在数学上可以无限延伸。
  public interface FunctionMapping extends CoreMapping {
    /**
     * Returns the target that a source maps to, or -1 if it is not mapped.
     */
    // 尝试获取指定 source 对应的 target 目标索引。这是一个安全/可选（Optional）的获取方法。
    // 如果该 source 存在映射，返回对应的 target 整数值。
    // 如果该 source 在当前映射的有效定义域之外（未映射），则固定返回 -1。
    int getTargetOpt(int source);

    /**
     * Returns the target that a source maps to.
     *
     * @param source source
     * @return target
     * @throws NoElementException if source is not mapped
     */
    // 获取指定 source 严格对应的 target 目标索引。这是一个强约束的获取方法。
    // 如果该 source 存在映射，返回对应的 target 整数值。
    // 如果该 source 未映射，该方法会直接抛出 NoElementException 运行时异常。
    int getTarget(int source);
    // 覆盖（Override）父接口 CoreMapping 的方法，用以返回当前函数映射的具体类型约束。
    @Override MappingType getMappingType();
    // 返回当前映射中源定义域（Source Domain）的总元素数量。
    int getSourceCount();
  }

  /**
   * Mapping suitable for sourcing columns.
   *
   * <p>Properties:
   *
   * <ul>
   * <li>It has a finite number of sources and targets
   * <li>Each target has exactly one source
   * <li>Each source has at most one target
   * </ul>
   *
   * <p>TODO: figure out which interfaces this should extend
   */
  // 专为数据源追踪与列溯源（Column Sourcing）设计的核心接口
  // 在数学上，它对应的映射类型通常是单射（Injection）或部分单射（Partial Injection）。它最核心的表达视角是：以“目标”为基准，去寻找它唯一对应的“源头”。
  // 当你在优化器中构建或重写一个算子（比如 Project 或 Join）时，上层算子产生的输出列（Target）到底来自底层数据源（Source）的哪一列？这种逆向追溯的需求就是 SourceMapping 的核心舞台。
  // 有限性（Finite number of sources and targets）：与 FunctionMapping 可能无限延伸不同，SourceMapping 必须是有限的，因为数据库表的列数永远是有限的。
  // 每个目标有且仅有一个源（Each target has exactly one source）：这是最关键的属性。上层输出的某一列，其物理数据的来源必须是明确且唯一的，绝对不允许一列数据同时来自底层的多列（这符合关系代数中列的确定性）。
  // 每个源最多有一个目标（Each source has at most one target）：底层的某一列，在上层可以被保留（对应一个 Target），也可以被过滤掉（没有 Target）。
  public interface SourceMapping extends CoreMapping {
    // 返回当前映射中源定义域（Source Domain）的总元素数量（即底层有多少个可选的原始列）。
    int getSourceCount();

    /**
     * Returns the source that a target maps to.
     *
     * @param target target
     * @return source
     * @throws NoElementException if target is not mapped
     */
    // 根据给定的目标列索引 target，强约束地查找它对应的源列索引 source。
    // 如果该 target 存在来源，返回对应的 source 整数值。
    // 如果该 target 未映射（在当前契约下理论上不应发生，因为每个 target 都有 exactly one source），或者越界，则直接抛出 NoElementException 运行时异常。
    int getSource(int target);

    /**
     * Returns the source that a target maps to, or -1 if it is not mapped.
     */
    // 根据给定的目标列索引 target，安全/可选（Optional）地查找它对应的源列索引 source。
    // 找到则返回 source。
    // 未找到或未映射时，固定返回 -1，绝不抛出异常。
    int getSourceOpt(int target);
    // 返回当前映射中目标值域（Target Domain）的总元素数量（即上层算子最终输出了多少个列）。
    int getTargetCount();

    /**
     * Returns the target that a source maps to, or -1 if it is not mapped.
     */
    // 反向探测。给定一个底层的源列索引 source，尝试获取它被映射到了上层的哪一个目标列 target。
    // 如果这一列被上层保留了，返回对应的 target 索引。
    // 如果这一列被上层过滤、裁剪掉了（由于是 at most one target，允许为 0 个），则固定返回 -1。
    int getTargetOpt(int source);
    //明确宣告该映射的类型。由于具备“每个目标刚好一个源，每个源最多一个目标”的特性，其对应的具体类型通常是 MappingType.INVERSE_SURJECTION 或更严格的 BIJECTION。
    @Override MappingType getMappingType();
    // 验该映射是否是一个恒等映射（Identity Mapping）。
    // 如果返回 true，意味着映射没有发生任何位置错乱或裁剪，每一列都完美对应其自身（即 0->0, 1->1, 2->2 且 SourceCount == TargetCount）。优化器如果检测到 isIdentity() 为 true，通常可以做平移消除优化（直接丢弃该映射，不进行任何物理重排，从而提升性能）。
    boolean isIdentity();
    // 获取当前源映射的逆映射（Inverse Mapping）对象。
    // 既然 SourceMapping 是“从 Target 找 Source”的视角（单射），那么对它求逆（inverse()）后，就变成了“从 Source 找 Target”的视角（全函数，即前面介绍的 FunctionMapping 行为特征），从而实现了映射链条的完美调头。
    Mapping inverse();
  }

  /**
   * Mapping suitable for mapping columns to a target.
   *
   * <p>Properties:
   *
   * <ul>
   * <li>It has a finite number of sources and targets
   * <li>Each target has at most one source
   * <li>Each source has exactly one target
   * </ul>
   *
   * <p>TODO: figure out which interfaces this should extend
   */
  // org.apache.calcite.util.mapping.TargetMapping 是与 SourceMapping 形成完美镜像的另一个核心接口。
  // 代表了一个有限定义域内的“全函数映射”（通常表现为单射 Injection 或全函数 Function）。它的核心视角是：以“源”为基准，将数据或列确定地投射到“目标”中去。
  // 在 SQL 编译优化时，当你需要把当前算子的输出列推流/写入到上层算子或目标表（Target）中时，你需要明确知道“我手里的每一列，应该放到目标的哪个位置”。这种正向投射的需求就是 TargetMapping 的核心舞台。
  // 有限性（Finite number of sources and targets）：源列数和目标列数必须都是有限的。
  // 每个目标最多有一个源（Each target has at most one source）：顶层目标表中的某一列，可能接收来自底层的一列数据，也可能没有被任何数据源映射（留空/变为 NULL）。但绝不允许一个目标坑位同时塞入底层两个不同的列。
  // 每个源有且仅有一个目标（Each source has exactly one target）：当前手头的每一列数据，必须去往一个确定的目标坑位，不能凭空消失或去往多个地方（注意：如果某一列在业务上被过滤了，在 TargetMapping 的有限上下文中，它通常会被显式映射到一个代表丢弃的边界或者根本不包含在 SourceCount 的有效定义域内）。
  public interface TargetMapping extends FunctionMapping {
    // 返回当前映射中源定义域（Source Domain）的总元素数量（即当前有多少个原始列等待被投射）。
    @Override int getSourceCount();

    /**
     * Returns the source that a target maps to, or -1 if it is not mapped.
     */
    // 逆向安全探测。给定一个目标位置 target，尝试反查它是从哪一个源列 source 映射过来的。
    // 如果该目标位有数据源入驻，返回对应的 source 编码。
    // 如果该目标位是空闲的（因为每个目标最多有一个源，允许为 0 个），则固定返回 -1。
    int getSourceOpt(int target);
    // 返回当前映射中目标值域（Target Domain）的总元素数量（即容纳投射结果的容器总共有多少个列坑位）。
    int getTargetCount();

    /**
     * Returns the target that a source maps to.
     *
     * @param source source
     * @return target
     * @throws NoElementException if source is not mapped
     */
    // 正向强约束查找。给定一个源列索引 source，获取它被投射到的目标列 target。
    @Override int getTarget(int source);

    /**
     * Returns the target that a source maps to, or -1 if it is not mapped.
     */
    // 覆盖父接口方法。正向安全可选查找。
    @Override int getTargetOpt(int source);
    // 动态构建/修改映射关系（关键的可变性方法）
    // 显式地在映射表中织入一条线：将 source 列的数据投射目的地指定为 target。
    void set(int source, int target);
    // 获取当前目标映射的逆映射（Inverse Mapping）对象。
    // 既然 TargetMapping 的视角是“从 Source 找 Target”（每个 Source 刚好一个 Target），那么对其求逆后，就变成了“从 Target 找 Source”（每个 Target 最多一个 Source）。这个逆矩阵完美符合 SourceMapping 的行为特征，实现了在优化器中正向与逆向追溯的无缝切换。
    Mapping inverse();
  }

  //~ Inner Classes ----------------------------------------------------------

  /** Abstract implementation of {@link Mapping}. */
  public abstract static class AbstractMapping implements Mapping {
    @Override public void set(int source, int target) {
      throw new UnsupportedOperationException();
    }

    @Override public int getTargetOpt(int source) {
      throw new UnsupportedOperationException();
    }

    @Override public int getTarget(int source) {
      int target = getTargetOpt(source);
      if (target == -1) {
        throw new NoElementException(
            "source #" + source + " has no target in mapping " + toString());
      }
      return target;
    }

    @Override public int getSourceOpt(int target) {
      throw new UnsupportedOperationException();
    }

    @Override public int getSource(int target) {
      int source = getSourceOpt(target);
      if (source == -1) {
        throw new NoElementException(
            "target #" + target + " has no source in mapping " + toString());
      }
      return source;
    }

    @Override public int getSourceCount() {
      throw new UnsupportedOperationException();
    }

    @Override public int getTargetCount() {
      throw new UnsupportedOperationException();
    }

    @Override public boolean isIdentity() {
      int sourceCount = getSourceCount();
      int targetCount = getTargetCount();
      if (sourceCount != targetCount) {
        return false;
      }
      for (int i = 0; i < sourceCount; i++) {
        if (getSource(i) != i) {
          return false;
        }
      }
      return true;
    }

    /**
     * Returns a string representation of this mapping.
     *
     * <p>For example, the mapping
     *
     * <table border="1">
     * <caption>Example</caption>
     * <tr>
     * <th>source</th>
     * <td>0</td>
     * <td>1</td>
     * <td>2</td>
     * </tr>
     * <tr>
     * <th>target</th>
     * <td>-1</td>
     * <td>3</td>
     * <td>2</td>
     * </tr>
     * </table>
     *
     * <table border="1">
     * <caption>Example</caption>
     * <tr>
     * <th>target</th>
     * <td>0</td>
     * <td>1</td>
     * <td>2</td>
     * <td>3</td>
     * </tr>
     * <tr>
     * <th>source</th>
     * <td>-1</td>
     * <td>-1</td>
     * <td>2</td>
     * <td>1</td>
     * </tr>
     * </table>
     *
     * <p>is represented by the string "[1:3, 2:2]".
     *
     * <p>This method relies upon the optional method {@link #iterator()}.
     */
    @Override public String toString() {
      StringBuilder buf = new StringBuilder();
      buf.append("[size=").append(size())
          .append(", sourceCount=").append(getSourceCount())
          .append(", targetCount=").append(getTargetCount())
          .append(", elements=[");
      int i = 0;
      for (IntPair pair : this) {
        if (i++ > 0) {
          buf.append(", ");
        }
        buf.append(pair.source).append(':').append(pair.target);
      }
      buf.append("]]");
      return buf.toString();
    }
  }

  /** Abstract implementation of mapping where both source and target
   * domains are finite. */
  public abstract static class FiniteAbstractMapping extends AbstractMapping {
    @Override public Iterator<IntPair> iterator() {
      return new FunctionMappingIter(this);
    }

    @Override public int hashCode() {
      // not very efficient
      return toString().hashCode();
    }

    @Override public boolean equals(@Nullable Object obj) {
      // not very efficient
      return (obj instanceof Mapping)
          && toString().equals(obj.toString());
    }
  }

  /** Iterator that yields the (source, target) values in a
   * {@link FunctionMapping}. */
  static class FunctionMappingIter implements Iterator<IntPair> {
    private int i = 0;
    private final FunctionMapping mapping;

    FunctionMappingIter(FunctionMapping mapping) {
      this.mapping = mapping;
    }

    @Override public boolean hasNext() {
      return (i < mapping.getSourceCount())
          || (mapping.getSourceCount() == -1);
    }

    @Override public IntPair next() {
      int x = i++;
      return new IntPair(
          x,
          mapping.getTarget(x));
    }

    @Override public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Thrown when a mapping is expected to return one element but returns
   * several.
   */
  public static class TooManyElementsException extends RuntimeException {
  }

  /**
   * Thrown when a mapping is expected to return one element but returns none.
   */
  public static class NoElementException extends RuntimeException {
    /**
     * Creates a NoElementException.
     *
     * @param message Message
     */
    public NoElementException(String message) {
      super(message);
    }
  }

  /**
   * A mapping where a source has at most one target, and every target has at
   * most one source.
   */
  public static class PartialMapping extends FiniteAbstractMapping
      implements Mapping, FunctionMapping, TargetMapping {
    protected final int[] sources;
    protected final int[] targets;
    private final MappingType mappingType;

    /**
     * Creates a partial mapping.
     *
     * <p>Initially, no element is mapped to any other:
     *
     * <table border="1">
     * <caption>Example</caption>
     * <tr>
     * <th>source</th>
     * <td>0</td>
     * <td>1</td>
     * <td>2</td>
     * </tr>
     * <tr>
     * <th>target</th>
     * <td>-1</td>
     * <td>-1</td>
     * <td>-1</td>
     * </tr>
     * </table>
     *
     * <table border="1">
     * <caption>Example</caption>
     * <tr>
     * <th>target</th>
     * <td>0</td>
     * <td>1</td>
     * <td>2</td>
     * <td>3</td>
     * </tr>
     * <tr>
     * <th>source</th>
     * <td>-1</td>
     * <td>-1</td>
     * <td>-1</td>
     * <td>-1</td>
     * </tr>
     * </table>
     *
     * @param sourceCount Number of source elements
     * @param targetCount Number of target elements
     * @param mappingType Mapping type; must not allow multiple sources per
     *                    target or multiple targets per source
     */
    public PartialMapping(
        int sourceCount,
        int targetCount,
        MappingType mappingType) {
      this.mappingType = mappingType;
      assert mappingType.isSingleSource() : mappingType;
      assert mappingType.isSingleTarget() : mappingType;
      this.sources = new int[targetCount];
      this.targets = new int[sourceCount];
      Arrays.fill(sources, -1);
      Arrays.fill(targets, -1);
    }

    /**
     * Creates a partial mapping from a list. For example, <code>
     * PartialMapping({1, 2, 4}, 6)</code> creates the mapping
     *
     * <table border="1">
     * <caption>Example</caption>
     * <tr>
     * <th>source</th>
     * <td>0</td>
     * <td>1</td>
     * <td>2</td>
     * <td>3</td>
     * <td>4</td>
     * <td>5</td>
     * </tr>
     * <tr>
     * <th>target</th>
     * <td>-1</td>
     * <td>0</td>
     * <td>1</td>
     * <td>-1</td>
     * <td>2</td>
     * <td>-1</td>
     * </tr>
     * </table>
     *
     * @param sourceList  List whose i'th element is the source of target #i
     * @param sourceCount Number of elements in the source domain
     * @param mappingType Mapping type, must be
     *   {@link org.apache.calcite.util.mapping.MappingType#PARTIAL_SURJECTION}
     *   or stronger.
     */
    public PartialMapping(
        List<Integer> sourceList,
        int sourceCount,
        MappingType mappingType) {
      this.mappingType = mappingType;
      assert mappingType.isSingleSource();
      assert mappingType.isSingleTarget();
      int targetCount = sourceList.size();
      this.targets = new int[sourceCount];
      this.sources = new int[targetCount];
      Arrays.fill(sources, -1);
      for (int i = 0; i < sourceList.size(); ++i) {
        final int source = sourceList.get(i);
        sources[i] = source;
        if (source >= 0) {
          targets[source] = i;
        } else {
          assert !this.mappingType.isMandatorySource();
        }
      }
    }

    private PartialMapping(
        int[] sources,
        int[] targets,
        MappingType mappingType) {
      this.sources = sources;
      this.targets = targets;
      this.mappingType = mappingType;
    }

    @Override public MappingType getMappingType() {
      return mappingType;
    }

    @Override public int getSourceCount() {
      return targets.length;
    }

    @Override public int getTargetCount() {
      return sources.length;
    }

    @Override public void clear() {
      Arrays.fill(sources, -1);
      Arrays.fill(targets, -1);
    }

    @Override public int size() {
      int size = 0;
      int[] a = sources.length < targets.length ? sources : targets;
      for (int i1 : a) {
        if (i1 >= 0) {
          ++size;
        }
      }
      return size;
    }

    @Override public Mapping inverse() {
      return new PartialMapping(
          targets.clone(),
          sources.clone(),
          mappingType.inverse());
    }

    @Override public Iterator<IntPair> iterator() {
      return new MappingItr();
    }

    protected boolean isValid() {
      assertPartialValid(this.sources, this.targets);
      assertPartialValid(this.targets, this.sources);
      return true;
    }

    private static void assertPartialValid(int[] sources, int[] targets) {
      for (int i = 0; i < sources.length; i++) {
        final int source = sources[i];
        assert source >= -1;
        assert source < targets.length;
        assert (source == -1) || (targets[source] == i);
      }
    }

    @Override public void set(int source, int target) {
      assert isValid();
      final int prevTarget = targets[source];
      targets[source] = target;
      final int prevSource = sources[target];
      sources[target] = source;
      if (prevTarget != -1) {
        sources[prevTarget] = prevSource;
      }
      if (prevSource != -1) {
        targets[prevSource] = prevTarget;
      }
      assert isValid();
    }

    /**
     * Returns the source that a target maps to, or -1 if it is not mapped.
     */
    @Override public int getSourceOpt(int target) {
      return sources[target];
    }

    /**
     * Returns the target that a source maps to, or -1 if it is not mapped.
     */
    @Override public int getTargetOpt(int source) {
      return targets[source];
    }

    @Override public boolean isIdentity() {
      if (sources.length != targets.length) {
        return false;
      }
      for (int i = 0; i < sources.length; i++) {
        int source = sources[i];
        if (source != i) {
          return false;
        }
      }
      return true;
    }

    /** Mapping iterator. */
    private class MappingItr implements Iterator<IntPair> {
      int i = -1;

      MappingItr() {
        advance();
      }

      @Override public boolean hasNext() {
        return i < targets.length;
      }

      private void advance(
          @UnknownInitialization MappingItr this) {
        do {
          ++i;
        } while (i < targets.length && targets[i] == -1);
      }

      @Override public IntPair next() {
        final IntPair pair = new IntPair(i, targets[i]);
        advance();
        return pair;
      }

      @Override public void remove() {
        throw new UnsupportedOperationException();
      }
    }
  }

  /**
   * A surjection with inverse has precisely one source for each target.
   * (Whereas a general surjection has at least one source for each target.)
   * Every source has at most one target.
   *
   * <p>If you call {@link #set} on a target, the target's previous source
   * will be lost.
   */
  static class SurjectionWithInverse extends PartialMapping {
    SurjectionWithInverse(int sourceCount, int targetCount) {
      super(sourceCount, targetCount, MappingType.INVERSE_SURJECTION);
    }

    /**
     * Creates a mapping between a source and a target.
     *
     * <p>It is an error to map a target to a source which already has a
     * target.
     *
     * <p>If you map a source to a target which already has a source, the
     * old source becomes an orphan.
     *
     * @param source source
     * @param target target
     */
    @Override public void set(int source, int target) {
      assert isValid();
      final int prevTarget = targets[source];
      if (prevTarget != -1) {
        throw new IllegalArgumentException("source #" + source
            + " is already mapped to target #" + target);
      }
      targets[source] = target;
      sources[target] = source;
    }

    @Override public int getSource(int target) {
      return sources[target];
    }
  }

  /** The identity mapping, of a given size, or infinite. */
  public static class IdentityMapping extends AbstractMapping
      implements FunctionMapping, TargetMapping, SourceMapping {
    private final int size;

    /**
     * Creates an identity mapping.
     *
     * @param size Size, or -1 if infinite.
     */
    public IdentityMapping(int size) {
      this.size = size;
    }

    @Override public void clear() {
      throw new UnsupportedOperationException("Mapping is read-only");
    }

    @Override public int size() {
      return size;
    }

    @Override public Mapping inverse() {
      return this;
    }

    @Override public boolean isIdentity() {
      return true;
    }

    @Override public void set(int source, int target) {
      throw new UnsupportedOperationException();
    }

    @Override public MappingType getMappingType() {
      return MappingType.BIJECTION;
    }

    @Override public int getSourceCount() {
      return size;
    }

    @Override public int getTargetCount() {
      return size;
    }

    /**
     * Returns the target that a source maps to.
     *
     * @param source source
     * @return target
     */
    @Override public int getTarget(int source) {
      if (source < 0 || (size != -1 && source >= size)) {
        throw new IndexOutOfBoundsException("source #" + source
            + " has no target in identity mapping of size " + size);
      }
      return source;
    }

    /**
     * Returns the target that a source maps to, or -1 if it is not mapped.
     *
     * @param source source
     * @return target
     */
    @Override public int getTargetOpt(int source) {
      if (source < 0 || (size != -1 && source >= size)) {
        throw new IndexOutOfBoundsException("source #" + source
            + " has no target in identity mapping of size " + size);
      }
      return source;
    }

    /**
     * Returns the source that a target maps to.
     *
     * @param target target
     * @return source
     */
    @Override public int getSource(int target) {
      if (target < 0 || (size != -1 && target >= size)) {
        throw new IndexOutOfBoundsException("target #" + target
            + " has no source in identity mapping of size " + size);
      }
      return target;
    }

    /**
     * Returns the source that a target maps to, or -1 if it is not mapped.
     *
     * @param target target
     * @return source
     */
    @Override public int getSourceOpt(int target) {
      if (target < 0 || (size != -1 && target >= size)) {
        throw new IndexOutOfBoundsException("target #" + target
            + " has no source in identity mapping of size " + size);
      }
      return target;
    }

    @Override public Iterator<IntPair> iterator() {
      return new Iterator<IntPair>() {
        int i = 0;

        @Override public boolean hasNext() {
          return (size < 0) || (i < size);
        }

        @Override public IntPair next() {
          int x = i++;
          return new IntPair(x, x);
        }

        @Override public void remove() {
          throw new UnsupportedOperationException();
        }
      };
    }
  }

  /** Source mapping that returns the same result as a parent
   * {@link SourceMapping} except for specific overriding elements. */
  public static class OverridingSourceMapping extends AbstractMapping
      implements SourceMapping {
    private final SourceMapping parent;
    private final int source;
    private final int target;

    public OverridingSourceMapping(
        SourceMapping parent,
        int source,
        int target) {
      this.parent = parent;
      this.source = source;
      this.target = target;
    }

    @Override public void clear() {
      throw new UnsupportedOperationException("Mapping is read-only");
    }

    @Override public int size() {
      return parent.getSourceOpt(target) >= 0
          ? parent.size()
          : parent.size() + 1;
    }

    @Override public Mapping inverse() {
      return new OverridingTargetMapping(
          (TargetMapping) parent.inverse(),
          target,
          source);
    }

    @Override public MappingType getMappingType() {
      // FIXME: Mapping type might be weaker than parent.
      return parent.getMappingType();
    }

    @Override public int getSource(int target) {
      if (target == this.target) {
        return this.source;
      } else {
        return parent.getSource(target);
      }
    }

    @Override public boolean isIdentity() {
      // FIXME: It's possible that parent was not the identity but that
      // this overriding fixed it.
      return (source == target)
          && parent.isIdentity();
    }

    @Override public Iterator<IntPair> iterator() {
      throw Util.needToImplement(this);
    }
  }

  /** Target mapping that returns the same result as a parent
   * {@link TargetMapping} except for specific overriding elements. */
  public static class OverridingTargetMapping extends AbstractMapping
      implements TargetMapping {
    private final TargetMapping parent;
    private final int target;
    private final int source;

    public OverridingTargetMapping(
        TargetMapping parent,
        int target,
        int source) {
      this.parent = parent;
      this.target = target;
      this.source = source;
    }

    @Override public void clear() {
      throw new UnsupportedOperationException("Mapping is read-only");
    }

    @Override public int size() {
      return parent.getTargetOpt(source) >= 0
          ? parent.size()
          : parent.size() + 1;
    }

    @Override public void set(int source, int target) {
      parent.set(source, target);
    }

    @Override public Mapping inverse() {
      return new OverridingSourceMapping(
          parent.inverse(),
          source,
          target);
    }

    @Override public MappingType getMappingType() {
      // FIXME: Mapping type might be weaker than parent.
      return parent.getMappingType();
    }

    @Override public boolean isIdentity() {
      // FIXME: Possible that parent is not identity but this overriding
      // fixes it.
      return (source == target)
          && ((Mapping) parent).isIdentity();
    }

    @Override public int getTarget(int source) {
      if (source == this.source) {
        return this.target;
      } else {
        return parent.getTarget(source);
      }
    }

    @Override public Iterator<IntPair> iterator() {
      throw Util.needToImplement(this);
    }
  }

  /**
   * Implementation of {@link Mapping} where a source can have at most one
   * target, and a target can have any number of sources. The source count
   * must be finite, but the target count may be infinite.
   *
   * <p>The implementation uses an array for the forward-mapping, but does not
   * store the backward mapping.
   */
  private static class PartialFunctionImpl extends AbstractMapping
      implements TargetMapping {
    private final int sourceCount;
    private final int targetCount;
    private final MappingType mappingType;
    private final int[] targets;

    PartialFunctionImpl(
        int sourceCount,
        int targetCount,
        MappingType mappingType) {
      super();
      if (sourceCount < 0) {
        throw new IllegalArgumentException("Sources must be finite");
      }
      this.sourceCount = sourceCount;
      this.targetCount = targetCount;
      this.mappingType = mappingType;
      if (!mappingType.isSingleTarget()) {
        throw new IllegalArgumentException(
            "Must have at most one target");
      }
      this.targets = new int[sourceCount];
      Arrays.fill(targets, -1);
    }

    @Override public int getSourceCount() {
      return sourceCount;
    }

    @Override public int getTargetCount() {
      return targetCount;
    }

    @Override public void clear() {
      Arrays.fill(targets, -1);
    }

    @Override public int size() {
      int size = 0;
      for (int target : targets) {
        if (target >= 0) {
          ++size;
        }
      }
      return size;
    }

    @SuppressWarnings("method.invocation.invalid")
    @Override public Iterator<IntPair> iterator() {
      return new Iterator<IntPair>() {
        int i = -1;

        {
          advance();
        }

        private void advance() {
          while (true) {
            ++i;
            if (i >= sourceCount) {
              break; // end
            }
            if (targets[i] >= 0) {
              break; // found one
            }
          }
        }

        @Override public boolean hasNext() {
          return i < sourceCount;
        }

        @Override public IntPair next() {
          final IntPair pair = new IntPair(i, targets[i]);
          advance();
          return pair;
        }

        @Override public void remove() {
          throw new UnsupportedOperationException();
        }
      };
    }

    @Override public MappingType getMappingType() {
      return mappingType;
    }

    @Override public Mapping inverse() {
      return target(invert(this), targetCount, sourceCount);
    }

    @Override public void set(int source, int target) {
      if ((target < 0) && mappingType.isMandatorySource()) {
        throw new IllegalArgumentException("Target is required");
      }
      if ((target >= targetCount) && (targetCount >= 0)) {
        throw new IllegalArgumentException(
            "Target must be less than target count, " + targetCount);
      }
      targets[source] = target;
    }

    public void setAll(Mapping mapping) {
      for (IntPair pair : mapping) {
        set(pair.source, pair.target);
      }
    }

    /**
     * Returns the target that a source maps to, or -1 if it is not mapped.
     *
     * @return target
     */
    @Override public int getTargetOpt(int source) {
      return targets[source];
    }
  }

  /**
   * Decorator which converts any {@link Mapping} into the inverse of itself.
   *
   * <p>If the mapping does not have an inverse -- for example, if a given
   * source can have more than one target -- then the corresponding method
   * call of the underlying mapping will raise a runtime exception.
   */
  private static class InverseMapping implements Mapping {
    private final Mapping parent;

    InverseMapping(Mapping parent) {
      this.parent = parent;
    }

    @Override public Iterator<IntPair> iterator() {
      final Iterator<IntPair> parentIter = parent.iterator();
      return new Iterator<IntPair>() {
        @Override public boolean hasNext() {
          return parentIter.hasNext();
        }

        @Override public IntPair next() {
          IntPair parentPair = parentIter.next();
          return new IntPair(parentPair.target, parentPair.source);
        }

        @Override public void remove() {
          parentIter.remove();
        }
      };
    }

    @Override public void clear() {
      parent.clear();
    }

    @Override public int size() {
      return parent.size();
    }

    @Override public int getSourceCount() {
      return parent.getTargetCount();
    }

    @Override public int getTargetCount() {
      return parent.getSourceCount();
    }

    @Override public MappingType getMappingType() {
      return parent.getMappingType().inverse();
    }

    @Override public boolean isIdentity() {
      return parent.isIdentity();
    }

    @Override public int getTargetOpt(int source) {
      return parent.getSourceOpt(source);
    }

    @Override public int getTarget(int source) {
      return parent.getSource(source);
    }

    @Override public int getSource(int target) {
      return parent.getTarget(target);
    }

    @Override public int getSourceOpt(int target) {
      return parent.getTargetOpt(target);
    }

    @Override public Mapping inverse() {
      return parent;
    }

    @Override public void set(int source, int target) {
      parent.set(target, source);
    }
  }
}
