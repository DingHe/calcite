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
package org.apache.calcite.linq4j.function;

/**
 * Base interface for all functions.
 *
 * @param <R> Result type
 */
// 在 Apache Calcite 的子项目 Linq4j 中，Function 接口是整个函数式编程体系的根接口。虽然它的代码极其简洁，但在框架的类型安全和逻辑抽象中起到了基石作用。
// Function<R> 是一个标记接口（Tagging Interface），它的主要作用是：
// 统一抽象：为 Linq4j 中所有可调用的“函数对象”提供一个共同的父类型。在 Calcite 将 SQL 算子（如 SELECT 中的表达式、WHERE 中的谓词）转化为 Java 代码时，这些逻辑都会被包装成 Function 的子类。
// 泛型约束：通过泛型 R 明确规定了函数的返回值类型。这使得 Calcite 在编译生成的 Java 代码时，能够进行严格的类型检查。
// 支持 Lambda 逻辑：它是实现 Function0（无参）、Function1（一元）、Function2（二元）等具体功能接口的基础。
public interface Function<R> {
}
