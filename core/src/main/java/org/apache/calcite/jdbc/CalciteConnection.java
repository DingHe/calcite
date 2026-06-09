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
package org.apache.calcite.jdbc;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.jdbc.CalcitePrepare.Context;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.schema.SchemaPlus;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Extension to Calcite's implementation of
// * {@link java.sql.Connection JDBC connection} allows schemas to be defined
 * dynamically.
 *
 * <p>You can start off with an empty connection (no schemas), define one
 * or two schemas, and start querying them.
 *
 * <p>Since a {@code CalciteConnection} implements the linq4j
 * {@link QueryProvider} interface, you can use a connection to execute
 * expression trees as queries.
 */
// 跨界缝合：JDBC 标准与 LINQ4J 运行时引擎的终极桥梁
// 继承了 java.sql.Connection，意味着它是一个标准的 JDBC 连接，完全可以无缝接入任何标准的 Java 数据库连接池（如 HikariCP、Druid）或 BI 客户端工具。
// 同时继承了 org.apache.calcite.linq4j.QueryProvider，这意味着它自身就是一个LINQ 表达式查询提供者，能够直接在内存中编织并流式驱动 Linq4j 语法树表达式。
// 支持联邦数据源的“动态 Schema 挂载中心”
// 正如源码注释所说，你可以从一个“空连接”开始，
// 在运行期动态地将 MySQL、Oracle、Elasticsearch 甚至是内存中的 CSV 文件等不同的数据源，作为虚拟 Schema 挂载到这个连接中，
// 然后直接用一句 SQL 实现跨异构存储的动态关联查询（Federated Query）。
public interface CalciteConnection extends Connection, QueryProvider {
  /**
   * Returns the root schema.
   * <p>You can define objects (such as relations) in this schema, and
   * also nested schemas.
   *
   * @return Root schema
   */
  // 获取当前 Calcite 连接的根 Schema（Root Schema）包装器。
  // 这是整个 Calcite 数据字典（Catalog）的生命之源。SchemaPlus 是一个层级树状结构，通过它，开发人员可以在运行期调用 rootSchema.add("my_mysql_db", new JdbcSchema(...))。
  SchemaPlus getRootSchema();

  /**
   * Returns the type factory.
   * @return Type factory
   */
  // 获取当前连接专用的 Java 类型工厂（Java Type Factory）。
  // Calcite 作为一套多方言、跨存储的联邦查询引擎，必须有一套底层机制把外部世界形形色色的类型（如 Oracle 的 NUMBER、MySQL 的 BIGINT）统一映射转换成 JVM 内存看得懂的强类型（如 long.class、Integer.class）。
  JavaTypeFactory getTypeFactory();

  /**
   * Returns an instance of the connection properties.
   * 连接属性
   * <p>NOTE: The resulting collection of properties is same collection used
   * by the connection, and is writable, but behavior if you modify the
   * collection is undefined. Some implementations might, for example, see
   * a modified property, but only if you set it before you create a
   * statement. We will remove this method when there are better
   * implementations of stateful connections and configuration.
   *
   * @return properties
   */
  // 返回当前物理连接底层的原生属性配置集合（Properties）。
  Properties getProperties();
  // 设置当前连接的默认工作空间/上下文 Schema（Default Schema）。
  // 当用户在客户端执行了 USE my_schema 或者在连接串里指定了默认 Schema 后，
  // SQL 解析器在遇到没有写前缀的表名（如直接写 SELECT * FROM users）时，就会自动把这一层设定的 schema 路径作为默认前缀补全上去。
  // in java.sql.Connection from JDK 1.7, but declare here to allow other JDKs
  @Override void setSchema(String schema) throws SQLException;
  // in java.sql.Connection from JDK 1.7, but declare here to allow other JDKs
  // 获取当前连接正在使用的默认 Schema 名称。
  @Override String getSchema() throws SQLException;
  // 获取当前连接的只读高级配置对象（CalciteConnectionConfig）。
  // 它把前面的 Properties 统一包装、封装成了强类型的、具备良好体系结构的配置引脚。
  CalciteConnectionConfig config();
  // 为即将投入执行的 SQL 语句（Statement）在内部孵化并创造一个专门的编译准备上下文（Context）。
  /** Creates a context for preparing a statement for execution. */
  Context createPrepareContext();
}
