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
public interface DataContext {
  ParameterExpression ROOT =
      Expressions.parameter(Modifier.FINAL, DataContext.class, "root");
  //java代码为 final DataContext root;
  /**
   * Returns a sub-schema with a given name, or null.
   */
  @Nullable SchemaPlus getRootSchema();

  /**
   * Returns the type factory.
   */
  JavaTypeFactory getTypeFactory();

  /** 查询提供者负责提供查询的执行环境，可能包括查询的优化、执行计划等
   * Returns the query provider.
   */
  QueryProvider getQueryProvider();

  /**
   * Returns a context variable.
   *
   * <p>Supported variables include: "sparkContext", "currentTimestamp",
   * "localTimestamp".
   *
   * @param name Name of variable
   */
  @Nullable Object get(String name);

  /** Variable that may be asked for in a call to {@link DataContext#get}. */
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
