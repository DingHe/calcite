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
package org.apache.calcite.adapter.java;

import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.schema.QueryableTable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.impl.AbstractTable;

import java.lang.reflect.Type;

/**
 * Abstract base class for implementing {@link org.apache.calcite.schema.Table}.
 */
// 在 AbstractTable 的基础上增加了对 LINQ 风格查询（Queryable） 的支持。
// AbstractQueryableTable 的核心作用是将“表”的概念与 linq4j 的“可查询表达式”连接起来：
// 桥接元数据与执行逻辑：它不仅定义了表的结构，还定义了如何在代码生成阶段（Code Generation）通过 Java 表达式找到并访问这张表。
// 支持强类型迭代：通过引入 elementType，它明确了表中每一行数据在 Java 语言层面对应的具体类型（如 Object[] 或自定义的 UserEntity 类）。
// 代码生成的起点：当 Calcite 将 SQL 转换为 Java 代码执行时，它需要知道如何引用这张表。该类提供的 getExpression 方法就是生成那段“引用代码”的关键。
public abstract class AbstractQueryableTable extends AbstractTable
    implements QueryableTable {
  // 存储表中元素（行）的 运行时 Java 类型。
  // 如果表的一行是一个对象数组，它可能是 Object[].class。
  // 如果表映射到了一个具体的 Java Bean，它就是该类的 Type。
  protected final Type elementType;

  protected AbstractQueryableTable(Type elementType) {
    super();
    this.elementType = elementType; //表元素的数据类型
  }
  // 实现自 QueryableTable 接口。
  @Override public Type getElementType() {
    return elementType;
  }
  // 实现自 QueryableTable 接口，用于生成访问该表的 Java 表达式树。
  @Override public Expression getExpression(SchemaPlus schema, String tableName,
      Class clazz) {
    return Schemas.tableExpression(schema, elementType, tableName, clazz);
  }
}
