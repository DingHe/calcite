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

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.util.Util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Visitor that looks for an aggregate function inside a tree of
 * {@link SqlNode} objects and throws {@link Util.FoundOne} when it finds
 * one. */
// AggFinder 是 AggVisitor 的具体实现类。它利用了 Java 的异常机制来实现一种“快速短路”的搜索逻辑。
// AggFinder 的主要作用是在 SQL 语法树中查找聚合函数实例。
// 与一般的 Visitor 遍历整棵树不同，AggFinder 通常用于存在性检测。它的工作流程非常独特：
// 一旦在树中找到符合条件的第一个聚合函数，它会立即抛出一个特殊的异常 Util.FoundOne 来中断遍历。
// 这种设计在验证阶段非常高效。例如，在验证 WHERE 子句时，如果发现任何一个聚合函数（这是非法的），系统可以立即停止扫描并报错。
class AggFinder extends AggVisitor {
  /**
   * Creates an AggFinder.
   *
   * @param opTab Operator table
   * @param over Whether to find windowed function calls {@code agg(x) OVER
   *             windowSpec}
   * @param aggregate Whether to find non-windowed aggregate calls
   * @param group Whether to find group functions (e.g. {@code TUMBLE})
   * @param delegate Finder to which to delegate when processing the arguments
   * @param nameMatcher Whether to match the agg function case-sensitively
   */
  AggFinder(SqlOperatorTable opTab, boolean over, boolean aggregate,
      boolean group, @Nullable AggFinder delegate, SqlNameMatcher nameMatcher) {
    super(opTab, over, aggregate, group, delegate, nameMatcher);
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Finds an aggregate.
   *
   * @param node Parse tree to search
   * @return First aggregate function in parse tree, or null if not found
   */
  // 参数：SqlNode（要搜索的起始节点）。
  public @Nullable SqlCall findAgg(SqlNode node) {
    try {
      // 调用 node.accept(this) 开始遍历。
      node.accept(this);
      // 如果没有找到聚合函数，遍历正常结束，返回 null。
      return null;
    } catch (Util.FoundOne e) {
      // 如果找到了聚合函数，内部会触发 found 方法抛出 Util.FoundOne 异常。此处通过 catch 捕获该异常，从异常对象中提取出该节点并返回。
      Util.swallow(e, null);
      return (SqlCall) e.getNode();
    }
  }

  // SqlNodeList extends SqlNode and implements List<SqlNode>, so this method
  // disambiguates
  // 在节点列表（如 SELECT 的投影列列表）中查找聚合函数。
  public @Nullable SqlCall findAgg(SqlNodeList nodes) {
    return findAgg((List<SqlNode>) nodes);
  }
  // 在节点列表（如 SELECT 的投影列列表）中查找聚合函数。
  // SqlNodeList 既是 SqlNode 又是 List，因此这里提供了明确的重载来避免歧义。逻辑与单个节点的查找一致，即对列表中的每个元素进行 accept 遍历。
  public @Nullable SqlCall findAgg(List<SqlNode> nodes) {
    try {
      for (SqlNode node : nodes) {
        node.accept(this);
      }
      return null;
    } catch (Util.FoundOne e) {
      Util.swallow(e, null);
      return (SqlCall) e.getNode();
    }
  }
  // 实现父类的抽象钩子。
  @Override protected Void found(SqlCall call) {
    throw new Util.FoundOne(call);
  }

  /** Creates a copy of this finder that has the same parameters as this,
   * then returns the list of all aggregates found. */
  // 获取给定节点列表中所有的聚合函数，而不是只找第一个。
  Iterable<SqlCall> findAll(Iterable<SqlNode> nodes) {
    final AggIterable aggIterable =
        new AggIterable(opTab, over, aggregate, group, delegate, nameMatcher);
    for (SqlNode node : nodes) {
      node.accept(aggIterable);
    }
    return aggIterable.calls;
  }

  /** Iterates over all aggregates. */
  static class AggIterable extends AggVisitor implements Iterable<SqlCall> {
    private final List<SqlCall> calls = new ArrayList<>();

    AggIterable(SqlOperatorTable opTab, boolean over, boolean aggregate,
        boolean group, @Nullable AggFinder delegate, SqlNameMatcher nameMatcher) {
      super(opTab, over, aggregate, group, delegate, nameMatcher);
    }

    @Override protected Void found(SqlCall call) {
      calls.add(call);
      return null;
    }

    @Override public Iterator<SqlCall> iterator() {
      return calls.iterator();
    }
  }
}
