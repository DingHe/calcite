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

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexCallBinding;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.validate.SqlMonotonicity;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.mapping.MappingType;
import org.apache.calcite.util.mapping.Mappings;

import com.google.common.collect.ImmutableList;

import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Utilities for traits propagation.
 */
@API(since = "1.24", status = API.Status.INTERNAL)
class EnumerableTraitsUtils {

  private EnumerableTraitsUtils() {}

  /**
   * Determine whether there is mapping between project input and output fields.
   * Bail out if sort relies on non-trivial expressions.
   */
  private static boolean isCollationOnTrivialExpr(
      List<RexNode> projects, RelDataTypeFactory typeFactory,
      Mappings.TargetMapping map, RelFieldCollation fc, boolean passDown) {
    final int index = fc.getFieldIndex();
    int target = map.getTargetOpt(index);
    if (target < 0) {
      return false;
    }

    final RexNode node = passDown ? projects.get(index) : projects.get(target);
    if (node.isA(SqlKind.CAST)) {
      // Check whether it is a monotonic preserving cast
      final RexCall cast = (RexCall) node;
      RelFieldCollation newFieldCollation = Objects.requireNonNull(RexUtil.apply(map, fc));
      final RexCallBinding binding =
          RexCallBinding.create(typeFactory, cast,
              ImmutableList.of(RelCollations.of(newFieldCollation)));
      if (cast.getOperator().getMonotonicity(binding)
          == SqlMonotonicity.NOT_MONOTONIC) {
        return false;
      }
    }

    return true;
  }
  // 专门服务于 Project 算子，用来精确计算：当父算子索要某种排序特性（Collation）时，如何通过逆向列映射，将这个排序要求准确、安全地反弹给子算子。
  // RelTraitSet required：下游父算子对当前 Project 提出的硬性物理特性诉求（如：要求结果流必须按 [emp_id ASC] 排序）。
  // List<RexNode> exps：当前 Project 算子的投影表达式树列表（即 SELECT 后面跟的每一列的计算逻辑）。
  // RelDataType inputRowType：直接上游子算子的原始输出行类型（用来确定上游有哪些列，以及它们的初始序号）。
  // RelTraitSet currentTraits：当前 Project 算子现有的物理特性集。
  static @Nullable Pair<RelTraitSet, List<RelTraitSet>> passThroughTraitsForProject(
      RelTraitSet required,
      List<RexNode> exps,
      RelDataType inputRowType,
      RelDataTypeFactory typeFactory,
      RelTraitSet currentTraits) {
    // 从父算子的诉求 required 中把排序特性（RelCollation）剥离出来。
    // 如果发现父算子根本没有排序诉求，或者排序诉求为空（EMPTY，即不关心顺序），那么当前 Project 就不需要折腾和反向出题了，直接返回 null，交由优化器采取默认的无序路径。
    final RelCollation collation = required.getCollation();
    if (collation == null || collation == RelCollations.EMPTY) {
      return null;
    }
    // 构建投影列的逆向重排映射轴（Target Mapping）
    // 假设上游子算子的原始列是 [0:age, 1:name, 2:emp_id]。
    // 当前 Project 的 SQL 是 SELECT emp_id, age（即 exps 只有两列：第 0 列引用上游的 2，第 1 列引用上游的 0）。
    // permutationIgnoreCast 会自动忽略掉无谓的 CAST 强转，生成一个双向的投影列位置映射表（TargetMapping）。
    final Mappings.TargetMapping map =
        RelOptUtil.permutationIgnoreCast(
            exps, inputRowType);
    // collation.getFieldCollations() 拿到了父算子要求的所有排序键列表（可能包含多列联合排序）。代码开启了一个 Stream 流，对每一个排序键 rc 进行了严苛的审查。
    // 允许透传的情况（Trivial，平凡的）：父算子要求的排序列，在 exps 里必须是一个纯粹的列引用（RexInputRef）。
    // 无情扼杀的情况（Non-trivial，非平凡的）：如果父算子要求按当前 Project 的第 0 列排序，但第 0 列是个函数或代数计算（例如 SELECT price * qty 或 SELECT UPPER(name)）。
    // 由于这些非线性转换极易破坏原始列的单调有序性，工具类判定其绝对无法安全透传。
    // 结果：只要有一个排序键触碰红线（anyMatch 成立），方法立刻熔断，直接返回 null 宣告宣告透传失败。
    // 场景：
    //上游子算子输入：[0: dept_id, 1: name, 2: salary]
    //
    //投影算子 exps 的逻辑：SELECT salary, dept_id （对应的当前列为：0:salary（源于2）, 1:dept_id（源于0））
    //
    //父算子下达指标（required）：必须按照输出结果的第 0 列（即 salary）进行 ASC 升序。
    // 方法内部演进过程：
    // 1. 剥离父诉求 ──────► collation = [0 ASC]
    //
    //2. 矩阵映射器 ──────► permutationIgnoreCast 算出 map 轴线：
    //                     当前 Project 列 [0] ── 映射 ──► 上游原始列 [2]
    //                     当前 Project 列 [1] ── 映射 ──► 上游原始列 [0]
    //
    //3. 表达式安全排查 ──► 检查 exps.get(0) 是纯 salary 列引用，通过，不是 UPPER(salary)，允许透传！
    //
    //4. 逆向置换 ────────► newCollation = collation.apply(map)
    //                     将要求的 [0 ASC] 转换为针对上游的 [2 ASC]
    //
    //5. 终极打包返回 ────► Left (自身)  : 灌入 [0 ASC] 顺应父心
    //                     Right(子算子): 灌入 [2 ASC] 强推给孩子
    if (collation.getFieldCollations().stream().anyMatch(
        rc -> !isCollationOnTrivialExpr(exps, typeFactory,
            map, rc, true))) {
      return null;
    }
    // 父算子要求的排序是 [0 ASC]（要求当前第 0 列 emp_id 有序）。
    //调用 apply(map) 后，根据映射关系（当前列 0 ─► 原始列 2），映射器将其逆向置换为 [2 ASC]。这就是属于上游子算子的 newCollation。
    final RelCollation newCollation = collation.apply(map);
    return Pair.of(currentTraits.replace(collation),
        ImmutableList.of(currentTraits.replace(newCollation)));
  }
  // 承担的是 Calcite Volcano 优化器中自底向上（Bottom-Up）的物理特性衍生与顺藤摸瓜推导。
  // 当底层子算子（孩子）宣告它自身具备某种已知的排序特性时，当前 Project 算子通过顺向的列映射，计算出经过自己的投影（SELECT）变换后，最终输出的数据流能顺理成章地继承和保留下什么样的排序特性。
  // RelTraitSet childTraits：直接上游子算子（孩子节点）目前已经具备并宣告的物理特性集（例如子节点说：“我已经按我的第 2 列排好序了！”）。
  // int childId：子算子的序号。对于只有一个 input 的单目算子 Project 来说，这个值通常固定为 0。
  // List<RexNode> exps：当前 Project 算子的投影表达式树列表（即 SELECT 后面每一列的计算逻辑）。
  // RelDataType inputRowType：上游子算子的原始输出行类型（用来得知孩子节点一共有多少列）。
  // RelTraitSet currentTraits：当前 Project 算子现有的、默认的物理特性集。
  static @Nullable Pair<RelTraitSet, List<RelTraitSet>> deriveTraitsForProject(
      RelTraitSet childTraits, int childId, List<RexNode> exps,
      RelDataType inputRowType, RelDataTypeFactory typeFactory, RelTraitSet currentTraits) {
    // 从子算子的物理特性 childTraits 中抽取它的排序特性（RelCollation）
    // 如果发现底层的孩子算子输出的数据流本来就是无序的（EMPTY），那作为上层的 Project 自然无从继承任何顺序，直接返回 null，宣告没有新特性可衍生
    final RelCollation collation = childTraits.getCollation();
    if (collation == null || collation == RelCollations.EMPTY) {
      return null;
    }
    // 计算最大字段数以安全开辟内存矩阵。创建一个类型为 FUNCTION（函数映射，即允许一个输入映射到一个输出）的列位置转换器（TargetMapping）
    // 其映射方向：它是要建立 [上游原始列序号] -> [当前 Project 输出列序号] 的映射！
    final int maxField = Math.max(exps.size(), inputRowType.getFieldCount());
    Mappings.TargetMapping mapping = Mappings
        .create(MappingType.FUNCTION, maxField, maxField);
    // 利用 Ord.zip(exps) 带着索引同时遍历投影列表。node.i 是当前 Project 输出列的序号，node.e 是表达式。
    for (Ord<RexNode> node : Ord.zip(exps)) {
      // 情况 A（纯列引用）：如果是 RexInputRef，直接将它指向的原始列号（getIndex()）作为 Key，当前列号 node.i 作为 Value 写入映射表。
      if (node.e instanceof RexInputRef) {

        mapping.set(((RexInputRef) node.e).getIndex(), node.i);
        // 情况 B（带 CAST 强转）：如果是 CAST(列 AS 类型)，Calcite 认为类型强转（如 INT 转 BIGINT）不会改变数据原有的单调排列顺序。
        // 它会剥开外壳拿到里面的 operand，如果是列引用，同样建立映射。
      } else if (node.e.isA(SqlKind.CAST)) {
        final RexNode operand = ((RexCall) node.e).getOperands().get(0);
        if (operand instanceof RexInputRef) {
          mapping.set(((RexInputRef) operand).getIndex(), node.i);
        }
      }
      // 其余复杂表达式（如 UPPER(列)）不处理，保持映射缺失
    }
    // 前缀排序键的安全判定与链式熔断
    List<RelFieldCollation> collationFieldsToDerive = new ArrayList<>();
    // 循环依次扫描每一个排序键 rc。通过 isCollationOnTrivialExpr 验证该列是否成功被当前 Project 提取出来了、且没有遭遇非线性变换。
    for (RelFieldCollation rc : collation.getFieldCollations()) {
      if (isCollationOnTrivialExpr(exps, typeFactory, mapping, rc, false)) {
        collationFieldsToDerive.add(rc);
      } else {
        // 多列联合排序（如 ORDER BY a, b）中，如果作为排在前头的最核心主排序列 a 在当前投影层被丢弃了或被函数改写了，那么排在后面的次级排序列 b 的有序性也会在整体上瞬间失去意义。
        // 因此一旦某一列验证失败，直接 break 彻底终止循环，后面剩下的排序键直接丢弃。
        break;
      }
    }

    if (collationFieldsToDerive.size() > 0) {
      // 当前 Project 算子更新自己的物理属性，向优化器自豪地宣告：“因为我的孩子是有序的，经过我顺向映射后，我向你担保我的输出流也天然继承了按第 0 列有序的特性！”
      final RelCollation newCollation = RelCollations
          .of(collationFieldsToDerive).apply(mapping);
      return Pair.of(currentTraits.replace(newCollation),
          ImmutableList.of(currentTraits.replace(collation)));
    } else {
      return null;
    }
  }

  /**
   * This function can be reused when a Join's traits pass-down shall only
   * pass through collation to left input.
   *
   * @param required required trait set for the join
   * @param joinType the join type
   * @param leftInputFieldCount number of field count of left join input
   * @param joinTraitSet trait set of the join
   */
  static @Nullable Pair<RelTraitSet, List<RelTraitSet>> passThroughTraitsForJoin(
      RelTraitSet required, JoinRelType joinType,
      int leftInputFieldCount, RelTraitSet joinTraitSet) {
    RelCollation collation = required.getCollation();
    if (collation == null
        || collation == RelCollations.EMPTY
        || joinType == JoinRelType.FULL
        || joinType == JoinRelType.RIGHT) {
      return null;
    }

    for (RelFieldCollation fc : collation.getFieldCollations()) {
      // If field collation belongs to right input: cannot push down collation.
      if (fc.getFieldIndex() >= leftInputFieldCount) {
        return null;
      }
    }

    RelTraitSet passthroughTraitSet = joinTraitSet.replace(collation);
    return Pair.of(passthroughTraitSet,
        ImmutableList.of(
            passthroughTraitSet,
            passthroughTraitSet.replace(RelCollations.EMPTY)));
  }

  /**
   * This function can be reused when a Join's traits derivation shall only
   * derive collation from left input.
   *
   * @param childTraits trait set of the child
   * @param childId id of the child (0 is left join input)
   * @param joinType the join type
   * @param joinTraitSet trait set of the join
   * @param rightTraitSet trait set of the right join input
   */
  static @Nullable Pair<RelTraitSet, List<RelTraitSet>> deriveTraitsForJoin(
      RelTraitSet childTraits, int childId, JoinRelType joinType,
      RelTraitSet joinTraitSet, RelTraitSet rightTraitSet) {
    // should only derive traits (limited to collation for now) from left join input.
    assert childId == 0;

    RelCollation collation = childTraits.getCollation();
    if (collation == null
        || collation == RelCollations.EMPTY
        || joinType == JoinRelType.FULL
        || joinType == JoinRelType.RIGHT) {
      return null;
    }

    RelTraitSet derivedTraits = joinTraitSet.replace(collation);
    return Pair.of(
        derivedTraits,
        ImmutableList.of(derivedTraits, rightTraitSet));
  }
}
