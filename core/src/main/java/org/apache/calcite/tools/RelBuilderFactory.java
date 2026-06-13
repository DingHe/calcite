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
package org.apache.calcite.tools;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.rel.core.RelFactories;

import org.checkerframework.checker.nullness.qual.Nullable;

/** A partially-created RelBuilder.
 *
 * <p>Add a cluster, and optionally a schema,
 * when you want to create a builder.
 *
 * <p>A {@code ProtoRelBuilder} can be shared among queries, and thus can
 * be inside a {@link RelOptRule}. It is a nice way to encapsulate the policy
 * that this particular rule instance should create {@code DrillFilter}
 * and {@code DrillProject} versus {@code HiveFilter} and {@code HiveProject}.
 *
 * @see RelFactories#LOGICAL_BUILDER
 */
// RelBuilderFactory 的核心作用是作为一个“半成品”的 RelBuilder 工厂（也常被称为 ProtoRelBuilder）。
// 它主要用来解耦优化器规则（RelOptRule）与具体的底层关系表达式（RelNode）节点实现。
// RelBuilder 是 Calcite 中用来构建关系代数树（AST）的核心工具，它内部包含了状态（如当前构建的栈）。
// 因此，RelBuilder 是线程不安全且不能跨查询共享的，每次处理一个新查询或在特定的上下文里，都需要一个全新的 RelBuilder 实例。
// 但是，RelBuilder 的行为策略（即：这个 Builder 应该创建纯逻辑算子 LogicalProject，还是特定引擎的物理算子如 HiveProject 或 DrillProject）是由一堆底层工厂（RelFactories）决定的，这些策略是可以共享的。
// RelBuilderFactory 应运而生。它封装了这些创建策略（工厂配置），允许你在多个查询之间、甚至在优化规则（RelOptRule）的单例中安全地共享它。
// 当某个优化规则需要真正开始构建算子时，只需要把当前的集群上下文（RelOptCluster）和元数据架构（RelOptSchema）传给这个 RelBuilderFactory，它就能瞬间生产出一个干净的、线程私有的 RelBuilder 实例。
public interface RelBuilderFactory {
  /** Creates a RelBuilder. */
  // RelOptCluster cluster Calcite 优化器运行时的集群上下文/环境（不可为空）
  // @Nullable RelOptSchema schema 元数据表结构架构（Schema）。该参数带有了 @Nullable 注解，意味着它是可选的/可为空的。
  RelBuilder create(RelOptCluster cluster, @Nullable RelOptSchema schema);
}
