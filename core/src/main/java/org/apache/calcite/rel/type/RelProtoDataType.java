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
package org.apache.calcite.rel.type;

import org.apache.calcite.linq4j.function.Function1;

/**
 * Can be converted into a {@link RelDataType} given a
 * {@link org.apache.calcite.rel.type.RelDataTypeFactory}.
 *
 * @see org.apache.calcite.rel.type.RelDataTypeImpl#proto
 */
// RelProtoDataType 的核心作用是作为一个数据类型的“原型”或“处方”。
// 在 Calcite 中，所有的 SQL 类型（如 INT, VARCHAR(20)）最终都必须由 RelDataTypeFactory 物理创建出来。然而，在很多场景下（例如定义表结构或 SQL 运算符时），我们只知道“想要什么类型”，
// 但此时手中并没有 RelDataTypeFactory 实例。
// 延迟实例化：它允许你定义一个类型逻辑，直到真正需要用到 RelDataType 时，才传入工厂进行实例化。
// 解耦：它将“类型的定义”与“类型的创建”解耦。
// 函数式包装：它继承了 linq4j 的 Function1，本质上是一个从 RelDataTypeFactory 到 RelDataType 的映射函数。
// RelDataTypeFactory factory：数据类型工厂，负责提供创建具体类型（如 createSqlType）的能力。
// 返回值：RelDataType。这是 Calcite 中表示物理数据类型的核心对象。
public interface RelProtoDataType
    extends Function1<RelDataTypeFactory, RelDataType> {
}
