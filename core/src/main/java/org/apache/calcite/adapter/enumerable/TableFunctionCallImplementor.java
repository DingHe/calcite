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
package org.apache.calcite.adapter.enumerable;

import org.apache.calcite.linq4j.function.Experimental;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.rex.RexCall;

/**
 * Implements a table-valued function call.
 */
// 普通的 SQL 函数（如 ABS(x)、SUBSTRING(str)）属于标量函数，输入一行只能吐出一个值。
// 而表值函数（如 UNNEST(array)、EXPLODE(map)，以及滚动时间窗口函数 TUMBLE(table, ...)）可以将输入的一行数据膨胀拆分成多行、甚至演变成一个完整的虚拟表（数据集）。
// 由于这种算子改变了整条物理执行管道的数据形态，普通的标量翻译器（RexToLixTranslator）无法直接将其翻译为基础的算术表达式，必须有一个专门的实现器来编织能生成 Enumerable（数据流集装箱）的复杂 Java 逻辑。
// 当 Calcite 物理算子（例如 EnumerableTableFunctionScan）在编译期解析到一条表函数调用（RexCall）时，它不会自己去硬编码写死生成的 Java 字节码。
// 相反，它会去中央注册表（RexImpTable）中检索，找到与该表函数相匹配的 TableFunctionCallImplementor 实现类，并将代码生成的控制权毫无保留地交接出去。
@Experimental
public interface TableFunctionCallImplementor {
  /**
   * Implements a table-valued function call.
   *
   * @param translator Translator for the call.
   * @param inputEnumerable Table parameter of the call.
   * @param call Call that should be implemented.
   * @param inputPhysType Physical type of the table parameter.
   * @param outputPhysType Physical type of the call.
   * @return Expression that implements the call.
   */
  Expression implement(
      // 当前的标量表达式翻译器上下文。
      // 提供一个“翻译官”工具。当表值函数的入参包含复杂的标量计算时（例如 TUMBLE(stream_data, DESCRIPTOR(ts * 1000), ...)），
      // 实现器需要利用这个 translator 去把内部的 ts * 1000 翻译成合法的 Java 算术树。
      RexToLixTranslator translator,
      // 上游物理数据集（数据源）的 Linq4j 语法树表达式指针（代指 Enumerable 对象）。
      Expression inputEnumerable,
      // 当前的表函数逻辑调用树节点。
      // 包含了表函数的全部逻辑定义（如到底调用的是 UNNEST 还是 SESSION 窗口），以及该函数被调用时传入的各种逻辑参数，用于指导实现器解析业务规则。
      RexCall call,
      PhysType inputPhysType,
      PhysType outputPhysType);
}
