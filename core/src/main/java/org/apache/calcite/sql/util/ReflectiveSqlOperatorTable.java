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

import org.apache.calcite.runtime.ConsList;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.fun.LibraryOperator;
import org.apache.calcite.sql.fun.SqlLibrary;
import org.apache.calcite.sql.validate.SqlNameMatcher;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ReflectiveSqlOperatorTable implements the {@link SqlOperatorTable} interface
 * by reflecting the public fields of a subclass.
 */
// ReflectiveSqlOperatorTable 是一个非常有创意且实用的工具类。它利用 Java 的反射机制，极大地简化了操作符表的定义过程。
// 该类的核心作用是：通过反射机制自动收集并注册子类中定义的 public 字段（操作符）到检索索引中。
// 在 Calcite 中，像 SqlStdOperatorTable（标准操作符表）包含数百个操作符。如果手动一个个调用 register 方法，代码会非常臃肿且难以维护。
//ReflectiveSqlOperatorTable 允许开发者直接在类中以 public static final SqlOperator PLUS = ... 的形式定义字段，
// 然后只需调用一次 init()，它就会自动把这些字段全部装载进父类 IndexedSqlOperatorTable 的高效索引（Multimap）中。
public abstract class ReflectiveSqlOperatorTable
    extends SqlOperatorTables.IndexedSqlOperatorTable
    implements SqlOperatorTable {
  // 定义 SQL 标准中的信息模式架构名。
  // 用于在查找操作符时处理限定名（Qualified Name）。根据 SQL 标准，如果一个函数是以 INFORMATION_SCHEMA.FUNC 这种方式调用的，需要特殊处理。
  public static final String IS_NAME = "INFORMATION_SCHEMA";

  //~ Instance fields --------------------------------------------------------

  //~ Constructors -----------------------------------------------------------
  // 初始化一个空的操作符表。
  // 首先调用父类的构造函数并传入一个空的列表。注意：此时表中还没有任何操作符。真正的加载动作必须在子类构造完成后通过 init() 触发。
  protected ReflectiveSqlOperatorTable() {
    // Initialize using an empty list of operators. After construction is
    // complete we will call init() and set the true operator list.
    super(ImmutableList.of());
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Performs post-constructor initialization of an operator table. It can't
   * be part of the constructor, because the subclass constructor needs to
   * complete first.
   */
  // 核心逻辑方法，执行反射加载和索引构建。
  public final SqlOperatorTable init() {
    // Use reflection to register the expressions stored in public fields.
    final List<SqlOperator> list = new ArrayList<>();
    // 获取当前类及其子类中所有的公有字段
    for (Field field : getClass().getFields()) {
      try {
        final Object o = field.get(this);
        // 检查字段值是否为 SqlOperator 的实例。
        if (o instanceof SqlOperator) {
          // Fields do not need the LibraryOperator tag, but if they have it,
          // we index them only if they contain STANDARD library.
          // 库过滤逻辑：检查字段是否有 @LibraryOperator 注解。
          // 如果有该注解，且标记的库中不包含 SqlLibrary.STANDARD（标准库），则跳过。这确保了只有标准操作符会被自动注册。
          LibraryOperator libraryOperator =
              field.getAnnotation(LibraryOperator.class);
          if (libraryOperator != null) {
            if (Arrays.stream(libraryOperator.libraries())
                .noneMatch(library -> library == SqlLibrary.STANDARD)) {
              continue;
            }
          }
          // 将符合条件的字段值加入 List<SqlOperator>。
          list.add((SqlOperator) o);
        }
      } catch (IllegalArgumentException | IllegalAccessException e) {
        throw Util.throwAsRuntime(Util.causeOrSelf(e));
      }
    }
    setOperators(buildIndex(list)); //把反射获取的标准操作加入operators
    return this;
  }
  // 在验证阶段根据 SQL 中的名称查找匹配的操作符。
  @Override public void lookupOperatorOverloads(SqlIdentifier opName,
      @Nullable SqlFunctionCategory category, SqlSyntax syntax,
      List<SqlOperator> operatorList, SqlNameMatcher nameMatcher) {
    // NOTE jvs 3-Mar-2005:  ignore category until someone cares

    String simpleName;
    // 负责解析 SqlIdentifier，并提取出真正的函数简名
    if (opName.names.size() > 1) {
      if (opName.names.get(opName.names.size() - 2).equals(IS_NAME)) {
        // per SQL99 Part 2 Section 10.4 Syntax Rule 7.b.ii.1
        simpleName = Util.last(opName.names);
      } else {
        return;
      }
    } else {
      simpleName = opName.getSimple();
    }
    // 核心查找委托给了父类的 lookUpOperators 方法。
    lookUpOperators(simpleName, nameMatcher.isCaseSensitive(), op -> {
      // 情况 A：语法完全匹配
      // 解释：如果算子定义的语法（如 BINARY）与调用时的语法需求完全一致，直接通过。例如 1 + 2 查找 +，语法都是 BINARY
      if (op.getSyntax() == syntax) {
        operatorList.add(op);
        // 情况 B：SqlFunction 的特殊处理（CAST 兼容性）
        // 背景：有些操作符（最典型的是 CAST）在语法定义上被标记为 SqlSyntax.SPECIAL，因为它有特殊的 SQL 格式（CAST(x AS INT)）。
        // 矛盾点：但在校验器（Validator）内部处理时，很多逻辑会以 SqlSyntax.FUNCTION 的身份去查找。
        // 解决：如果当前查找的是“函数”类算子，只要该算子本身是 SqlFunction 的子类，即便它的 getSyntax() 不是 FUNCTION（而是 SPECIAL 等），也允许它作为候选算子。
      } else if (syntax == SqlSyntax.FUNCTION
          && op instanceof SqlFunction) {
        // this special case is needed for operators like CAST,
        // which are treated as functions but have special syntax
        operatorList.add(op);
      }
    });

    // REVIEW jvs 1-Jan-2005:  why is this extra lookup required?
    // Shouldn't it be covered by search above?
    switch (syntax) {
    case BINARY:
    case PREFIX:
    case POSTFIX:
      // 核心动作：针对 BINARY（二元）、PREFIX（前缀）、POSTFIX（后缀）这三种语法类型，它会无视语法约束再次进行一次全量名称查找。
      // 回答注释中的疑问：“为什么需要额外查找？难道之前的搜索没覆盖吗？”
      // 答案是：为了兼容那些“既是函数又是运算符”的操作符。
      lookUpOperators(simpleName, nameMatcher.isCaseSensitive(), extra -> {
        // REVIEW: should only search operators added during this method?
        if (extra != null && !operatorList.contains(extra)) {
          operatorList.add(extra);
        }
      });
      break;
    default:
      break;
    }
  }

  /**
   * Registers a function or operator in the table.
   * @deprecated This table is designed to be initialized from the fields of
   * a class, and adding operators is not efficient
   */
  // 作用是向当前的操作符表中手动注册一个新的操作符或函数，并实时更新索引，使其在后续的 SQL 验证中可见。
  @Deprecated
  public void register(SqlOperator op) {
    // Rebuild the immutable collections with their current contents plus one.
    setOperators(buildIndex(ConsList.of(op, getOperatorList())));
  }
}
