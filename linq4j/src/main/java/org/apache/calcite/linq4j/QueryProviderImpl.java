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
package org.apache.calcite.linq4j;

import org.apache.calcite.linq4j.tree.Expression;

import java.lang.reflect.Type;

/**
 * Partial implementation of {@link QueryProvider}.
 *
 * <p>Derived class needs to implement {@link #executeQuery}.
 */
// QueryProviderImpl 是一个承上启下的骨架抽象类（Partial Implementation）。它实现了 QueryProvider 接口，专门用来简化查询提供者（Query Provider）的开发工作。
// 是 LINQ 延迟执行（Deferred Execution）模式的引擎底座
// 在 Linq4j 框架中，当用户编写类似 context.users.where(...).select(...) 的查询时，系统并不会立刻去数据库捞数据。
// 相反，它会在内存中把这些算子组装成一棵由 Expression（Linq4j 抽象语法树）构成的“逻辑执行蓝图”。
// QueryProviderImpl 的核心使命就是作为这张蓝图的“编织工厂”。
// 它负责把一段静态的语法树表达式（Expression）包装成一个具备流式迭代能力的、强类型的 Queryable 对象。
public abstract class QueryProviderImpl implements QueryProvider {
  /**
   * Creates a QueryProviderImpl.
   */
  protected QueryProviderImpl() {
    super();
  }

  // 接收一棵语法树 expression 和一个标准的 Java 强类型类对象 Class<T>（代表行数据的实体类型，如 User.class）。
  // 在内存中瞬间 new 一个专属的 QueryableImpl 容器，把当前的 this 指针（即当前的查询提供者自身）、行类型以及语法树无缝缝合在一起并返回。
  @Override public <T> Queryable<T> createQuery(Expression expression, Class<T> rowType) {
    return new QueryableImpl<>(this, rowType, expression);
  }
  // 与前一个方法形成重载，唯一的区别是将第二个参数从 Class<T> 降维扩展为了 Java 反射体系中的通用接口 java.lang.reflect.Type。
  @Override public <T> Queryable<T> createQuery(Expression expression, Type rowType) {
    return new QueryableImpl<>(this, rowType, expression);
  }

  @Override public <T> T execute(Expression expression, Class<T> type) {
    throw new UnsupportedOperationException();
  }

  @Override public <T> T execute(Expression expression, Type type) {
    throw new UnsupportedOperationException();
  }

  /**
   * Binds an expression to this query provider.
   *
   * @param <T> element type
   */
  public static class QueryableImpl<T> extends BaseQueryable<T> {
    public QueryableImpl(QueryProviderImpl provider, Type elementType,
        Expression expression) {
      super(provider, elementType, expression);
    }

    @Override public String toString() {
      return "Queryable(expr=" + expression + ")";
    }
  }
}
