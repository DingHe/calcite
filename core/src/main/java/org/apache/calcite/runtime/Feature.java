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
package org.apache.calcite.runtime;

import java.lang.reflect.Method;
import java.util.Locale;

/** SQL language feature. Expressed as the exception that would be thrown if it
 * were used while disabled. */
// Feature 类的核心作用是表示一个 SQL 语言特性的控制开关及其关联的异常。
// 特性标识：在 Calcite 中，某些 SQL 特性（如某些特定的语法或函数）可能是可选的或受限的。Feature 对象就代表了这样一个“特性”。
// 异常模板：根据其类文档（Javadoc）描述，它被表达为“如果该特性被禁用却被使用时将抛出的异常”。
// 固定异常类型：它将 ExInstWithCause 的泛型固定为 CalciteContextException。
// 这意味着所有的 Feature 对象在激活时，都会通过反射生成一个包含上下文信息（如 SQL 语句的位置、行号、列号）的 CalciteContextException。
public class Feature
    extends Resources.ExInstWithCause<CalciteContextException> {
  public Feature(String base, Locale locale, Method method, Object... args) {
    super(base, locale, method, args);
  }
}
