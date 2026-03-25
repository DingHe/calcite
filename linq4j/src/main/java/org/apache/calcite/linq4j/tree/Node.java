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
package org.apache.calcite.linq4j.tree;

/** Parse tree node. */
// 在 Apache Calcite 的 linq4j 模块中，org.apache.calcite.linq4j.tree.Node 是表达式树（Expression Tree）的核心基础接口。
// 所有构成 LINQ 查询逻辑的节点（如加法运算、方法调用、常量定义等）都必须实现这个接口。
// Node 接口的主要作用是定义了**解析树节点（Parse Tree Node）**的标准行为。
// 统一抽象：它是 linq4j 树结构中所有具体类（如 Expression、Statement、Declaration）的顶级父接口。
// 支持设计模式：它通过三个 accept 方法，完整地支持了 访问者模式（Visitor Pattern）。这使得 Calcite 能够通过不同的处理器对表达式树进行：
// 分析/求值（通过 Visitor）。
// 转换/重写（通过 Shuttle）。
// 代码生成（通过 ExpressionWriter）。
public interface Node {
  // 允许一个只读型访问者遍历并处理该节点。
  // Visitor<R> visitor：具体的访问者实例，定义了当访问到某种特定类型的节点时该执行什么操作。
  // 返回值 <R>：访问者处理后的返回结果（例如：求值结果、节点统计信息等）。
  // 典型场景：求值（Evaluation）：在内存中解释执行该表达式树并返回结果。
  <R> R accept(Visitor<R> visitor);
  // 作用：允许一个**重写型访问者（穿梭器）**遍历并可能修改该节点。
  // Shuttle shuttle：一种特殊的访问者，它在访问节点时可以返回一个新的 Node 对象来替换当前的节点。
  // 返回值 Node：返回处理后的节点。如果节点没有变化，通常返回 this；如果节点被修改或替换，返回新节点。
  // 典型场景：
  // 树转换：将树中的某种方法调用替换为另一种更高效的实现。
  // 表达式简化：例如将 1 + 2 节点在编译期直接替换为常量 3 的节点。
  Node accept(Shuttle shuttle);
  // 作用：将当前节点序列化为源代码文本。
  // ExpressionWriter expressionWriter：一个专门用于输出 Java 代码流的辅助类，它处理缩进、换行以及操作符优先级。
  // 典型场景： 代码生成（Code Generation）：Calcite 将 SQL 转换为 linq4j 树后，最后一步通常就是调用此方法，将整棵树转换成实际可执行的 Java 源代码（.java 文件或内存中的字节码）。
  void accept(ExpressionWriter expressionWriter);
}
