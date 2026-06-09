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
package org.apache.calcite.adapter.jdbc;

import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.QueryProviderImpl;
import org.apache.calcite.linq4j.Queryable;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

/**
 * Implementation of {@link QueryProvider} that talks to JDBC databases.
 */
// 虽然表面上看它几乎是一个空壳，但它在 Calcite 的 JDBC 适配器（JDBC Adapter）中扮演着极其特殊的“物理桩（Stub）”角色。
// 是跨异构数据源（Federated Query）的“物理方言隔离墙”
// 在 Calcite 实现联邦查询时，普通的 Linq4j 算子（如 EnumerableMemo）是在本地 JVM 内存中通过 Java 代码来执行过滤和连接的。
// 但是，如果我们要把计算下推（Push-down）到远程的物理关系型数据库（如 Oracle、MySQL），就绝对不能把所有数据全捞到本地来算。
// JdbcQueryProvider 的核心使命就是作为一个标记式的物理查询提供者。
// 它不负责在本地编织复杂的 Java 迭代器，它的存在是为了向外层 CodeGen 框架宣告：凡是挂在我名下的查询树（Expression），都会由特定的 JdbcToEnumerableConverter 算子整体拦截，并最终整体翻译成一段纯正的远程数据库 SQL 字符串（物理下推），交由远程数据库去全速跑完。
public final class JdbcQueryProvider extends QueryProviderImpl {
  public static final JdbcQueryProvider INSTANCE = new JdbcQueryProvider();

  private JdbcQueryProvider() {
  }

  @Override public <T> Enumerator<T> executeQuery(Queryable<T> queryable) {
    return castNonNull(null);
  }
}
