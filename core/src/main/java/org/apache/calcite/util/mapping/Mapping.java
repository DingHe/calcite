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

import java.util.Iterator;

/**
 * A <dfn>Mapping</dfn> is a relationship between a source domain to target
 * domain of integers.
 *
 * <p>This interface represents the most general possible mapping. Depending on
 * the {@link MappingType} of a particular mapping, some of the operations may
 * not be applicable. If you call the method, you will receive a runtime error.
 * For instance:
 *
 * <ul>
 * <li>If a target has more than one source, then the method
 *     {@link #getSource(int)} will throw
 *     {@link Mappings.TooManyElementsException}.
 * <li>If a source has no targets, then the method {@link #getTarget} will throw
 *     {@link Mappings.NoElementException}.
 * </ul>
 */
// Mapping 接口的核心作用是定义一个全功能、多视角的通用整数映射协议。
// 在数学上，它代表了源整数域（Source Domain）到目标整数域（Target Domain）之间最普遍的关系。它的设计哲学非常特殊：
// 多面一体：它通过同时继承 FunctionMapping、SourceMapping 和 TargetMapping，使得同一个 Mapping 对象既能执行 getTarget(src) 正向查找，也能执行 getSource(tg) 逆向追溯，还能执行 set(src, tg) 动态修改。
// 运行时契约约束：因为它是“最通用”的接口，包含了所有可能的映射操作，但特定的底层实现（由它的 MappingType 决定）可能并不支持全部操作。如果对一个不支持某操作的映射强行调用了该方法，它会在运行时抛出对应的异常（例如：对一个“多对一”的映射调用 getSource(target) 就会抛出 TooManyElementsException，因为一个目标对应了多个源，无法返回单个确定的源）。
// 通过这种设计，Calcite 的上层优化规则可以统一声明 Mapping 变量，而不需要频繁地在各种子接口之间进行强转。
public interface Mapping
    extends Mappings.FunctionMapping,
    Mappings.SourceMapping,
    Mappings.TargetMapping,
    Iterable<IntPair> {
  //~ Methods ----------------------------------------------------------------

  /**
   * Returns an iterator over the elements in this mapping.
   *
   * <p>This method is optional; implementations may throw
   * {@link UnsupportedOperationException}.
   */
  // 返回当前映射中所有实体映射对（IntPair）的迭代器。
  @Override Iterator<IntPair> iterator();

  /**
   * Returns the number of sources. Valid sources will be in the range 0 ..
   * sourceCount.
   */
  // 返回当前映射中源定义域（Source Domain）的容量上限。
  // 声明合法的 source 索引必须处于 0 .. sourceCount - 1 的闭区间内。这为优化器在进行字段遍历和越界安全检查时提供了明确的基准线。
  @Override int getSourceCount();

  /**
   * Returns the number of targets. Valid targets will be in the range 0 ..
   * targetCount.
   */
  // 回当前映射中目标值域（Target Domain）的容量上限。
  @Override int getTargetCount();
  // 取当前映射实例在运行期所具备的具体数学约束类型。
  @Override MappingType getMappingType();

  /**
   * Returns whether this mapping is the identity.
   */
  // 判断当前映射是否属于恒等映射（即不发生任何物理位移和裁剪，f(x) = x 且源与目标数量完全相等）。
  @Override boolean isIdentity();

  /**
   * Removes all elements in the mapping.
   */
  // 彻底清空当前映射对象中的所有映射关系。
  void clear();

  /**
   * Returns the number of elements in the mapping.
   */
  // 返回当前映射关系中实际存在的、处于激活状态的 IntPair（映射对）的总数量。
  @Override int size();
}
