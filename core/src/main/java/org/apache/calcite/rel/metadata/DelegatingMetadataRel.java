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
package org.apache.calcite.rel.metadata;

import org.apache.calcite.rel.RelNode;

/**
 * Interface for {@link RelNode} where the metadata is derived from another node.
 */
// 告诉 Calcite 的元数据提供者（Metadata Provider），当前关系表达式节点（RelNode）本身不直接计算元数据，
// 而是应当完全委派（Delegate）给另一个关联的节点来代为推导。
// 为什么要“委派”元数据？
// 在 SQL 优化过程中，Calcite 会生成很多“包装型”或“中间过渡型”的算子节点。
// 这些节点在结构上做了一些调整（例如：修改了物理特征 Convention、外包了一层统计专用的节点、或者是一个占位符），但它们产生的数据集的底层特征（如：行数 RowCount、唯一键 UniqueKeys、平均行大小 AverageRowSize）与它们内部持有的那个实际算子完全一致。
// 如果没有这个接口，开发人员在编写这些包装算子时，就必须为每一种元数据（几十种）都写一遍重复的转发代码。实现了 DelegatingMetadataRel 接口后，Calcite 的元数据缓存和路由机制就会自动拦截这个节点，并直接去追溯它委派的节点，极大地简化了开发并提高了复用性。
public interface DelegatingMetadataRel {
  // 取被委派的底层目标关系表达式节点（RelNode）
  // 返回真正持有或能够计算元数据的那个源头 RelNode 实例。
  // 当 Calcite 试图通过元数据查询器（如 RelMetadataQuery）去获取当前节点的某项元数据（例如 query.getRowCount(currentRel)）时，
  // 如果发现 currentRel 实现了 DelegatingMetadataRel 接口，
  // 查询引擎内部就会自动调用 getMetadataDelegateRel() 拿到目标节点，并在目标节点上继续进行元数据解析。
  RelNode getMetadataDelegateRel();
}
