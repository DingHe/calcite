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
package org.apache.calcite.adapter.innodb;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link org.apache.calcite.rel.core.Project}
 * relational expression for an InnoDB data source.
 */
// 专门为 InnoDB 数据源适配器（InnoDB Adapter） 定制的物理投影算子。它继承自抽象基类 Project 并实现了 InnodbRel 接口
// 在 Calcite 的多数据源适配体系中，InnodbProject 属于 InnodbRel.CONVENTION（InnoDB 物理执行流派）。
// 下推（Push-down）列选择：它代表一个准备直接在底层 InnoDB 存储引擎中执行的列裁剪或字段投影操作。通过它，Calcite 可以通知 InnoDB 引擎只读取和返回 SQL 中指定的字段，从而避免整行大字段（如 BLOB、TEXT 或几十个无关列）的磁盘 I/O 开销。
// 元数据映射与翻译：它负责将 Calcite 抽象的行级表达式（RexNode）与 InnoDB 底层实际的物理字段名（Field Names）和别名进行对齐和转换，并将其注册到 InnoDB 的执行上下文（Implementor）中。
public class InnodbProject extends Project implements InnodbRel {
  InnodbProject(RelOptCluster cluster, RelTraitSet traitSet,
      RelNode input, List<? extends RexNode> projects, RelDataType rowType) {
    super(cluster, traitSet, ImmutableList.of(), input, projects, rowType, ImmutableSet.of());
    // 强制确保当前投影算子的流派必须与它的子节点（input）完全一致
    assert getConvention() == InnodbRel.CONVENTION;
    assert getConvention() == input.getConvention();
  }

  @Override public Project copy(RelTraitSet traitSet, RelNode input,
      List<RexNode> projects, RelDataType rowType) {
    return new InnodbProject(getCluster(), traitSet, input, projects, rowType);
  }

  @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
      RelMetadataQuery mq) {
    return super.computeSelfCost(planner, mq).multiplyBy(0.1);
  }
  // 实现 InnodbRel 接口的核心行为，将 Calcite 算子树转换为 InnoDB 认识的投影查询信息。
  @Override public void implement(Implementor implementor) {
    implementor.visitChild(0, getInput());
    final InnodbRules.RexToInnodbTranslator translator =
        new InnodbRules.RexToInnodbTranslator(
            InnodbRules.innodbFieldNames(getInput().getRowType()));
    final Map<String, String> fields = new LinkedHashMap<>();
    for (Pair<RexNode, String> pair : getNamedProjects()) {
      final String name = pair.right;
      final String originalName = pair.left.accept(translator);
      fields.put(originalName, name);
    }
    implementor.addSelectFields(fields);
  }
}
