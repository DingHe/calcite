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
package org.apache.calcite.sql.util;

import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSpatialTypeOperatorTable;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Utilities for {@link SqlOperatorTable}s.
 */
public class SqlOperatorTables {
  private SqlOperatorTables() {}

  private static final Supplier<SqlOperatorTable> SPATIAL =
      Suppliers.memoize(SqlSpatialTypeOperatorTable::new);

  /** Returns the Spatial operator table, creating it if necessary. */
  public static SqlOperatorTable spatialInstance() {
    return SPATIAL.get();
  }

  /** Creates a composite operator table. */
  public static SqlOperatorTable chain(Iterable<SqlOperatorTable> tables) {
    final List<SqlOperatorTable> list = new ArrayList<>();
    for (SqlOperatorTable table : tables) {
      addFlattened(list, table);
    }
    if (list.size() == 1) {
      return list.get(0);
    }
    return new ChainedSqlOperatorTable(ImmutableList.copyOf(list));
  }

  @SuppressWarnings("StatementWithEmptyBody")
  private static void addFlattened(List<SqlOperatorTable> list,
      SqlOperatorTable table) {
    if (table instanceof ChainedSqlOperatorTable) {
      ChainedSqlOperatorTable chainedTable = (ChainedSqlOperatorTable) table;
      for (SqlOperatorTable table2 : chainedTable.tableList) {
        addFlattened(list, table2);
      }
    } else if (table instanceof ImmutableListSqlOperatorTable
        && table.getOperatorList().isEmpty()) {
      // Table is empty and will remain empty; don't add it.
    } else {
      list.add(table);
    }
  }

  /** Creates a composite operator table from an array of tables. */
  public static SqlOperatorTable chain(SqlOperatorTable... tables) {
    return chain(ImmutableList.copyOf(tables));
  }

  /** Creates an operator table that contains an immutable list of operators. */
  public static SqlOperatorTable of(Iterable<? extends SqlOperator> list) {
    return new ImmutableListSqlOperatorTable(ImmutableList.copyOf(list));
  }

  /** Creates an operator table that contains the given operator or
   * operators. */
  public static SqlOperatorTable of(SqlOperator... operators) {
    return of(ImmutableList.copyOf(operators));
  }

  /** Subclass of {@link ListSqlOperatorTable} that is immutable.
   * Operators cannot be added or removed after creation. */
  private static class ImmutableListSqlOperatorTable
      extends ListSqlOperatorTable {
    ImmutableListSqlOperatorTable(Iterable<? extends SqlOperator> operators) {
      super(operators);
    }
  }

  /** Base class for implementations of {@link SqlOperatorTable} whose list of
   * operators rarely changes. */
  // 在 Apache Calcite 中，IndexedSqlOperatorTable 是 SqlOperatorTable 接口的一个重要抽象基类。它通过引入“索引”机制，显著提升了在大量操作符中进行查找的效率。
  // 该类的核心作用是：通过内存索引（Multimap）优化 SQL 操作符的检索性能。
  // 在标准的 SqlOperatorTable 中，如果每次查找都遍历整个操作符列表，性能会随着函数数量（如 UDF 增多）而线性下降。
  // IndexedSqlOperatorTable 针对操作符列表很少发生变化的场景，在初始化时预先建立了一套以“操作符名称”为 Key 的索引映射。
  // 其设计优势在于：
  // 高效查找：利用 ImmutableMultimap 实现 $O(1)$ 或 $O(\log n)$ 的检索速度。
  // 处理重载：同一个 Key（操作符名）可以映射到多个 SqlOperator 实例（即支持函数重载）。
  // 大小写策略兼容：索引统一使用大写形式存储，但在查询时可以灵活支持大小写敏感或不敏感的匹配。
  abstract static class IndexedSqlOperatorTable implements SqlOperatorTable {
    /** Contains all (name, operator) pairs. Effectively a sorted immutable
     * multimap.
     *
     * <p>There can be several operators with the same name (case-insensitive or
     * case-sensitive) and these operators will lie in a contiguous range which
     * we can find efficiently using binary search. */
    // 该类的核心索引数据结构。
    // 将操作符的大写名称（String）映射到一组操作符对象（SqlOperator）。
    // 使用 Google Guava 的 ImmutableMultimap 确保了线程安全性和不可变性。对于同名但不同参数的重载函数，它们会被存储在同一个 Key 下的集合中。
    protected ImmutableMultimap<String, SqlOperator> operators;
    // 构造并初始化索引。
    protected IndexedSqlOperatorTable(Iterable<? extends SqlOperator> list) {
      operators = buildIndex(list);
    }
    //返回Operator列表
    @Override public List<SqlOperator> getOperatorList() {
      return operators.values().asList();
    }
    // 获取表中所有的操作符。
    protected void setOperators(Multimap<String, SqlOperator> operators) {
      this.operators = ImmutableMultimap.copyOf(operators);
    }

    /** Derives a value to be assigned to {@link #operators} from a given list
     * of operators. */
    // 构建索引的工厂方法。
    //把给定的operators添加到operators
    // 关键点：使用 op.getName().toUpperCase(Locale.ROOT) 将名称统一转为大写作为 Key。这为后续不区分大小写的查找奠定了基础。
    protected static ImmutableMultimap<String, SqlOperator> buildIndex(
        Iterable<? extends SqlOperator> operators) {
      final ImmutableMultimap.Builder<String, SqlOperator> map =
          ImmutableMultimap.builder();
      operators.forEach(op ->
          map.put(op.getName().toUpperCase(Locale.ROOT), op));
      return map.build();
    }

    /** Looks up operators, optionally matching case-sensitively. */
    // 执行具体的查找逻辑，并支持大小写敏感过滤。
    protected void lookUpOperators(String name,
        boolean caseSensitive, Consumer<SqlOperator> consumer) {
      // 先将输入名 name 转为大写 upperName。
      final String upperName = name.toUpperCase(Locale.ROOT);
      if (caseSensitive) {
        // 如果大小写敏感：遍历取出的操作符，只有当其原始名称与输入 name 严格相等（.equals）时，才触发 consumer。
        operators.get(upperName)
            .forEach(operator -> {
              if (operator.getName().equals(name)) {
                consumer.accept(operator);
              }
            });
      } else {
        // 如果大小写不敏感：直接将取出的所有操作符交给 consumer。
        operators.get(upperName).forEach(consumer);
      }
    }
  }
}
