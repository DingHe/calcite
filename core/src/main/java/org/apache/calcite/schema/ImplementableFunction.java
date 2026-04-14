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
package org.apache.calcite.schema;

import org.apache.calcite.adapter.enumerable.CallImplementor;

/**
 * Function that can be translated to java code.
 *
 * @see ScalarFunction
 * @see TableFunction
 */
// ImplementableFunction 的核心作用是提供一种将 SQL 函数调用转换为可执行 Java 代码（linq4j 表达式）的方法。
// 在 Calcite 默认的 Enumerable 规则下，SQL 会被编译成一段动态生成的 Java 代码并编译执行。如果你的自定义函数实现了这个接口，Calcite 就知道在生成 Java 代码时，应该“填入”什么样的逻辑代码来调用你的函数。
public interface ImplementableFunction extends Function {
  /**
   * Returns implementor that translates the function to linq4j expression.
   *
   * @return implementor that translates the function to linq4j expression.
   */
  // 返回该函数的“实现器”（CallImplementor）。
  // 转换任务：CallImplementor 负责具体的转换工作。
  // 它会告诉 Calcite：当在 SQL 中看到 my_func(a, b) 时，对应的 Java 代码应该是 MyClass.myStaticMethod(a, b) 还是其它的逻辑。
  // 并不是所有的 Function 都需要实现 ImplementableFunction，这取决于你的执行引擎：
  // 外部引擎推送（Push-down）：如果你的函数是推送到远程数据库（如通过 JDBC 推送到 MySQL）执行的，那么 Calcite 只需要知道函数的签名（Function 接口），然后生成对应的 SQL 字符串即可，不需要 ImplementableFunction。
  // Calcite 内存计算（Enumerable）：如果你是在 Calcite 的内存中处理数据（通常是 Enumerable 适配器），Calcite 必须生成具体的 Java 字节码来运行。此时，你的函数必须是 ImplementableFunction，否则 Calcite 无法生成调用代码，会导致编译报错。
  CallImplementor getImplementor();
}
