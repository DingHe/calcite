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

import org.apache.calcite.sql.SqlIdentifier;

import com.google.common.collect.Ordering;

import java.util.Comparator;
import java.util.List;

/**
 * An interface of an object identifier that represents a SqlIdentifier.
 */
// SqlMoniker 是一个非常精简但关键的接口。它主要用于标识和描述 SQL 对象的“指纹”或“代号”。
// SqlMoniker 的字面意思是“绰号”或“名字”。在 Calcite 中，它的核心作用是 作为 SQL 对象（如表、列、架构、函数等）的唯一标识符快照。
// 元数据摘要：它将复杂的 SQL 标识符（SqlIdentifier）简化为一个包含类型信息和完全限定名（Fully Qualified Name）的轻量级对象。
// 自动补全与提示：它最常用于 IDE 插件或 SQL 编辑器的**自动补全（IntelliSense）**功能。当你在校验器中请求“此处有哪些可能的表名或列名”时，Calcite 会返回一组 SqlMoniker 对象。
// 排序与比较：它提供了一套标准的比较逻辑，允许对发现的 SQL 对象进行排序（例如按类型排序，再按字母顺序排序）。
public interface SqlMoniker {
  // 定义了 SqlMoniker 对象的排序规则。
  Comparator<SqlMoniker> COMPARATOR =
      new Comparator<SqlMoniker>() {
        final Ordering<Iterable<String>> listOrdering =
            Ordering.<String>natural().lexicographical();

        @Override public int compare(SqlMoniker o1, SqlMoniker o2) {
          // 类型优先：首先根据 getType() 返回的 SqlMonikerType 枚举进行比较（例如，架构 > 表 > 列）。
          int c = o1.getType().compareTo(o2.getType());
          if (c == 0) {
            // 名称次之：如果类型相同，则使用 listOrdering 对完全限定名（字符串列表）进行**字典序（Lexicographical）**比较。
            c =
                listOrdering.compare(o1.getFullyQualifiedNames(),
                    o2.getFullyQualifiedNames());
          }
          return c;
        }
      };

  //~ Methods ----------------------------------------------------------------

  /**
   * Returns the type of object referred to by this moniker. Never null.
   */
  // 返回该标识符所指向的对象类型。
  // 常见类型：
  //
  //CATALOG：目录
  //
  //SCHEMA：架构
  //
  //TABLE：表
  //
  //COLUMN：列
  //
  //FUNCTION：函数
  //
  //KEYWORD：关键字
  SqlMonikerType getType();

  /**
   * Returns the array of component names.
   */
  // 获取该对象的完整限定路径。
  // 如果该对象代表 SALES 架构下的 EMP 表，则返回的列表内容为 ["SALES", "EMP"]。
  List<String> getFullyQualifiedNames();

  /**
   * Creates a {@link SqlIdentifier} containing the fully-qualified name.
   */
  // 将此轻量级的 SqlMoniker 转换回重量级的 SqlIdentifier 对象。
  SqlIdentifier toIdentifier();
  // 返回该 Moniker 的字符串唯一标识。
  String id();
}
