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
package org.apache.calcite.util;

/**
 * Exception intended to be used for control flow, as opposed to the usual
 * use of exceptions which is to signal an error condition.
 *
 * <p>{@code ControlFlowException} does not populate its own stack trace, which
 * makes instantiating one of these (or a sub-class) more efficient.
 */
// 打破了“异常仅用于错误处理”的常规逻辑，将其转变为一种高效的流程控制工具。
// ControlFlowException 的核心作用是作为一种“长跳转（Long Jump）”机制来控制程序流。
// 在遍历深层嵌套的树状结构（如 SQL 语法树 AST）时，如果我们在某个深层节点找到了想要的结果，按照常规逻辑，我们需要层层返回（return）。
// 而使用 ControlFlowException，我们可以直接从树的底层“跳”回顶层的 try-catch 块。
// 普通的 Java 异常在创建时会执行 fillInStackTrace()，这是一个非常耗时的操作（需要遍历 JVM 栈帧）。由于流程控制（如 AggFinder 寻找第一个聚合函数）是频繁发生的正常逻辑，性能开销是不可接受的。ControlFlowException 解决了这个问题。


public class ControlFlowException extends RuntimeException {
  // 覆盖了 Throwable 类的默认行为。
  // 默认行为：在普通异常中，此方法会抓取当前线程的堆栈快照，以便后续打印错误日志。
  // 当前实现：该方法被重写为直接 return this;。
  @Override public synchronized Throwable fillInStackTrace() {
    return this;
  }
}
