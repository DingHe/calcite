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
package org.apache.calcite;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.rel.type.TimeFrameSet;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.advise.SqlAdvisor;

import com.google.common.base.CaseFormat;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime context allowing access to the tables in a database.
 *
 * @see DataContexts
 */
// DataContext 接口是 Apache Calcite 项目中物理执行期（Runtime）的核心全局上下文容器。
// 在整个 SQL 编译生命周期里，CBO 优化器将逻辑树翻译为可执行的 Java 代码（或物理算子树）后，
// 这些代码在 JVM 内存中真正开始跑数据的那一刻，必须能够感知外部世界的一切动态状态（例如：当前是谁在查数据？当前时间是多少？超时时间配置了多久？底层的物理表在哪里？）。
// DataContext 就是充当连接编译静态逻辑与运行时动态现实的唯一信息纽带。
public interface DataContext {
  // 代码生成（Code Generation）的锚点
  // 利用 Linq4j 框架表达式树生成了一个名为 "root" 且被 final 修饰的参数表达式。
  // 在 Calcite 动态编译生产出的 Java 字节码里，每一个执行方法的主入口形参都会强行绑定这个变量。
  // 它是全套执行链路中用来在内存中提取元数据、过滤条件的“变量总钥匙”。
  ParameterExpression ROOT =
      Expressions.parameter(Modifier.FINAL, DataContext.class, "root");
  //java代码为 final DataContext root;
  /**
   * Returns a sub-schema with a given name, or null.
   */
  // 获取当前数据库连接的根元数据命名空间（Root Schema）
  // 返回的 SchemaPlus 对象是全量表、视图、自定义函数（UDF）的内存拓扑大账本。
  // 物理算子（例如 EnumerableTableScan）在真正跑数据时，必须调用该方法顺藤摸瓜找到实际存储数据的物理表对象、文件路径、或者底层 JDBC 连接池句柄。
  @Nullable SchemaPlus getRootSchema();

  /**
   * Returns the type factory.
   */
  // 获取运行时行数据物理类型映射工厂。
  // 虽然优化器在编译期就已经确定了数据类型，但是在最终物理计算、临时落盘序列化、或者通过内存交互（如对齐 Apache Arrow 数据块）时，仍然高频需要利用这个工厂来把 SQL 逻辑类型与 Java 宿主类型进行最终校准或动态反射。
  JavaTypeFactory getTypeFactory();

  /**
   * Returns the query provider.
   */
  // 获取底层查询执行环境提供者。
  // QueryProvider 来自 Linq4j 框架，它定义了如何在内存中拉起流式迭代器（Enumerable）、如何调度并发线程。它为生成的代码提供了一个统一的、底层引擎无关的流式计算生命周期管理环境。
  QueryProvider getQueryProvider();

  /**
   * Returns a context variable.
   *
   * <p>Supported variables include: "sparkContext", "currentTimestamp",
   * "localTimestamp".
   *
   * @param name Name of variable
   */
  // 通用动态变量索取池（无类型约束）
  // 一个通用的 Map 型后备接口。上层算子只需传入特定的变量名字符串（如 "currentTimestamp"），就能从中捕获外界系统灌入的、变化莫测的动态执行状态值。由于返回值是通用的 Object，使用者需要自行强转。
  @Nullable Object get(String name);

  /** Variable that may be asked for in a call to {@link DataContext#get}. */
  // 为了消除通用 get(String name) 字符串拼写易错、需要人肉强转的弊端，
  // Calcite 在内部高度抽象并规范化了一个强类型的核心参数配置池 —— Variable 枚举。
  // 它规定了哪些变量是当前查询执行必须感知或推荐感知的。
  enum Variable {
    UTC_TIMESTAMP("utcTimestamp", Long.class), //表示当前 UTC 时间戳。单位是毫秒，表示自 1970 年 1 月 1 日 00:00:00 UTC 至今的毫秒数

    /** The time at which the current statement started executing. In
     * milliseconds after 1970-01-01 00:00:00, UTC. Required. */
    CURRENT_TIMESTAMP("currentTimestamp", Long.class), //表示当前查询开始执行的时间戳，单位同样是毫秒，表示自 1970 年 1 月 1 日 00:00:00 UTC 起的毫秒数

    /** The time at which the current statement started executing. In
     * milliseconds after 1970-01-01 00:00:00, in the time zone of the current
     * statement. Required. */
    LOCAL_TIMESTAMP("localTimestamp", Long.class), //与 CURRENT_TIMESTAMP 类似，但时间戳是以当前系统的本地时区为基准。

    /** The Spark engine. Available if Spark is on the class path. */
    SPARK_CONTEXT("sparkContext", Object.class), //当使用 Spark 执行查询时，该变量代表 Spark 引擎的上下文

    /** A mutable flag that indicates whether user has requested that the
     * current statement be canceled. Cancellation may not be immediate, but
     * implementations of relational operators should check the flag fairly
     * frequently and cease execution (e.g. by returning end of data). */
    CANCEL_FLAG("cancelFlag", AtomicBoolean.class), //表示查询是否被取消的标志。如果查询正在执行，其他系统可以通过设置这个标志来请求取消查询的执行

    /** Query timeout in milliseconds.
     * When no timeout is set, the value is 0 or not present. */
    TIMEOUT("timeout", Long.class), //查询的超时时间，单位为毫秒。设置为 0 表示没有超时限制

    /** Advisor that suggests completion hints for SQL statements. */
    SQL_ADVISOR("sqlAdvisor", SqlAdvisor.class), //SQL 建议器，用于提供 SQL 语句的自动补全或建议

    /** Writer to the standard error (stderr). */
    STDERR("stderr", OutputStream.class),

    /** Reader on the standard input (stdin). */
    STDIN("stdin", InputStream.class),

    /** Writer to the standard output (stdout). */
    STDOUT("stdout", OutputStream.class),

    /** Locale in which the current statement is executing.
     * Affects the behavior of functions such as {@code DAYNAME} and
     * {@code MONTHNAME}. Required; defaults to the root locale if the
     * connection does not specify a locale. */
    LOCALE("locale", Locale.class), //当前查询执行的区域设置（Locale），影响日期、时间和货币等数据格式化操作

    /** Time zone in which the current statement is executing. Required;
     * defaults to the time zone of the JVM if the connection does not specify a
     * time zone. */
    TIME_ZONE("timeZone", TimeZone.class), //当前查询执行的时区。影响查询过程中与时间相关的计算。

    /** Set of built-in and custom time frames for use in functions such as
     * {@code FLOOR} and {@code EXTRACT}. Required; defaults to
     * {@link org.apache.calcite.rel.type.TimeFrames#CORE}. */
    TIME_FRAME_SET("timeFrameSet", TimeFrameSet.class), //时间框架集合，用于处理时间相关的函数，如 FLOOR 和 EXTRACT

    /** The query user.
     *
     * <p>Default value is "sa". */
    USER("user", String.class), //执行查询的用户，默认值为 "sa"。

    /** The system user.
     *
     * <p>Default value is "user.name" from
     * {@link System#getProperty(String)}. */
    SYSTEM_USER("systemUser", String.class); //系统用户，默认值为 System.getProperty("user.name")。

    public final String camelName;
    public final Class clazz;

    Variable(String camelName, Class clazz) {
      this.camelName = camelName;
      this.clazz = clazz;
      assert camelName.equals(
          CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, name()));
    }

    /** Returns the value of this variable in a given data context. */
    public <T> T get(DataContext dataContext) {
      //noinspection unchecked
      return (T) clazz.cast(dataContext.get(camelName));
    }
  }
}
