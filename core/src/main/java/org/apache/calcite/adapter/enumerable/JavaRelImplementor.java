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

import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.plan.RelImplementor;
import org.apache.calcite.rex.RexBuilder;

/**
 * Abstract base class for implementations of {@link RelImplementor}
 * that generate java code.
 */
// 实现了顶层标志接口 RelImplementor，专门为那些以生成 Java 源代码（Code Generation）为核心物理目标的转译大管家提供通用的底层基础设施。
// 统一 Java 代码生成流派的底座：
// 在 Calcite 中，并不是只有一种算子树需要生成 Java 代码。虽然最常见的是内存迭代流派的 EnumerableRelImplementor，但在一些高级定制或混合引擎扩展中，也可能存在其他需要利用 Linq4j 语法树生成 Java 机器码的实现。JavaRelImplementor 将这些流派中关于类型工厂转换、表达式构建、以及运行时上下文访问的公共逻辑抽取了出来。
// 管理 Java 物理执行期的上下文纽带：
// 关系代数模型（Relational Model）在转换成能在 JVM 中跑的真实代码时，最大的难点在于类型系统的对接（SQL 类型如何映射为 Java 强类型）以及运行时动态参数的传递（如 ? 占位符、用户 Session 变量）。该类就是为了在代码生成期给编译器提供这些“物理化学催化剂”。

public abstract class JavaRelImplementor implements RelImplementor {
  // 行级表达式（Scalar Expression）的构建基石。
  // 在 Calcite 中，算子树（RelNode）负责宏观的拓扑结构（如 Join、Filter），而算子内部具体的计算逻辑、条件判定、数学公式（如 age > 18，salary * 1.1）则由 RexNode（行表达式） 来表达。
  // rexBuilder 就是用来管理、孵化、和复用这些行级计算因子的工厂。在生成 Java 代码时，大管家需要高频地依赖它去拆解或重组底层的过滤和投影逻辑。
  private final RexBuilder rexBuilder;

  protected JavaRelImplementor(RexBuilder rexBuilder) {
    this.rexBuilder = rexBuilder;
    assert rexBuilder.getTypeFactory() instanceof JavaTypeFactory
        : "Type factory of rexBuilder should be a JavaTypeFactory";
  }

  public RexBuilder getRexBuilder() {
    return rexBuilder;
  }
  // 获取强类型转译专用的 Java关系数据类型工厂。
  public JavaTypeFactory getTypeFactory() {
    return (JavaTypeFactory) rexBuilder.getTypeFactory();
  }

  /**
   * Returns the expression used to access
   * {@link org.apache.calcite.DataContext}.
   * @return expression used to access {@link org.apache.calcite.DataContext}.
   */
  // 提供访问运行时动态上下文（DataContext）的“万能变量钥匙”。
  // 物理内幕：这是 Calcite 动态代码生成方案的灵魂之一。生成的 Java 代码在运行的时候，绝对不能是一池死水，它必须能读取外界传进来的参数（比如 SELECT * FROM t WHERE id = ? 里的问候参数、或者是当前执行的用户是谁、当前的时间戳等）。
  // 代码生成映射：该方法返回一个固定的 Linq4j 参数表达式 DataContext.ROOT。在最终生成的 Java 源码中，它对应的就是每一个查询主方法的入口形参：final org.apache.calcite.DataContext root;。
  public ParameterExpression getRootExpression() {
    return DataContext.ROOT;
  }
}
