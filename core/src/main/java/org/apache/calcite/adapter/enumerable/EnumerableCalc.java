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
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.BlockStatement;
import org.apache.calcite.linq4j.tree.Blocks;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.MemberDeclaration;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.linq4j.tree.Types;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPredicateList;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelDistributionTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Calc;
import org.apache.calcite.rel.metadata.RelMdCollation;
import org.apache.calcite.rel.metadata.RelMdDistribution;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.rex.RexSimplify;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.List;

import static org.apache.calcite.adapter.enumerable.EnumUtils.BRIDGE_METHODS;
import static org.apache.calcite.adapter.enumerable.EnumUtils.NO_EXPRS;
import static org.apache.calcite.adapter.enumerable.EnumUtils.NO_PARAMS;

/** Implementation of {@link org.apache.calcite.rel.core.Calc} in
 * {@link org.apache.calcite.adapter.enumerable.EnumerableConvention enumerable calling convention}. */
// 如果说 RexProgram 是行级计算微指令的“设计蓝图”，那么 org.apache.calcite.adapter.enumerable.EnumerableCalc 就是将这幅蓝图真正落地变成动态 Java 字节码的“执行工厂”。
// 在内存计算引擎中，传统的火山模型（Volcano Model）虚函数调用开销极大。EnumerableCalc 完美结合了 RexProgram 的多合一特性。它的核心使命是：
// 多算子合一降低开销：它在物理层代表一个集成了 Filter（过滤）和 Project（投影转换）的复合算子。它在处理一行数据时，会同时把过滤和投影全部做完，避免了数据在多个物理算子之间来回传递和包装的内存开销。
// 动态代码生成引擎：它利用 Calcite 的 Linq4j 抽象语法树（AST）框架，在优化器执行阶段，根据 RexProgram 里的过滤条件（Condition）和投影列（Projects），动态拼接出一段包含 while 循环、if 判定以及行组装的 Java 代码。
// 物性（Traits）传导控制：作为物理算子，它负责计算数据在经过过滤和投影后，原本的排序（Collation）和分布式打散特性（Distribution）应该如何继承、改写或丢失，并通知优化器。
public class EnumerableCalc extends Calc implements EnumerableRel {
  /**
   * Creates an EnumerableCalc.
   *
   * <p>Use {@link #create} unless you know what you're doing.
   */
  public EnumerableCalc(RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode input,
      RexProgram program) {
    super(cluster, traitSet, ImmutableList.of(), input, program);
    assert getConvention() instanceof EnumerableConvention;
    // Calc 算子的物理指令集中绝对不允许包含任何聚合函数（如 SUM, COUNT）。聚合函数必须由专门的 EnumerableAggregate 算子来处理。
    assert !program.containsAggs();
  }

  @Deprecated // to be removed before 2.0
  public EnumerableCalc(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode input,
      RexProgram program,
      List<RelCollation> collationList) {
    this(cluster, traitSet, input, program);
    Util.discard(collationList);
  }

  /** Creates an EnumerableCalc. */
  // CBO 优化器将逻辑计划转换为物理计划时，调用频率最高的核心工厂。
  public static EnumerableCalc create(final RelNode input,
      final RexProgram program) {

    final RelOptCluster cluster = input.getCluster();
    // 获取集群的元数据查询中枢 RelMetadataQuery (mq)。
    final RelMetadataQuery mq = cluster.getMetadataQuery();
    final RelTraitSet traitSet = cluster.traitSet()
        .replace(EnumerableConvention.INSTANCE)
        // 利用 RelMdCollation.calc(mq, input, program) 和 RelMdDistribution.calc(mq, input, program) 实时动态触发元数据双向推导。
        .replaceIfs(RelCollationTraitDef.INSTANCE,
            () -> RelMdCollation.calc(mq, input, program))
        .replaceIf(RelDistributionTraitDef.INSTANCE,
            () -> RelMdDistribution.calc(mq, input, program));
    // 如果 program 的投影只是原样透传了上游的排序字段，那么上游的排序物性（RelCollation）就会被安全地保留并叠加进 traitSet 中；
    // 如果投影洗牌了列或者破坏了排序，物性会自动降级丢弃。最终携带着精准物性的 traitSet 被灌入构造函数。
    return new EnumerableCalc(cluster, traitSet, input, program);
  }

  @Override public EnumerableCalc copy(RelTraitSet traitSet, RelNode child,
      RexProgram program) {
    // we do not need to copy program; it is immutable
    return new EnumerableCalc(getCluster(), traitSet, child, program);
  }
  // 本质工作是：利用 Linq4j 框架，在运行时动态生成一段 Java 源代码表达式树（AST），最终编译成 JVM 字节码。
  // 这段生成的代码会返回一个 Enumerable 对象。
  // 当你迭代这个对象时，它会通过一个包含 while 循环的底层 Enumerator（迭代器），对上游数据源同时进行“过滤（Filter）”和“投影（Project）”操作。
  @Override public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
    final JavaTypeFactory typeFactory = implementor.getTypeFactory();
    // 实例化主代码块构建器（BlockBuilder）
    // inq4j 框架中用来承载、拼接生成 Java 代码的临时容器。可以将其类比为字节码和语法树层面的 StringBuilder。未来最终生成的外部包装类代码都会按顺序源源不断地追加到这个对象中。
    final BlockBuilder builder = new BlockBuilder();
    // 获取当前 Calc 物理节点在关系代数树中的直接下游子节点（Input 算子），并强制转换为物理执行层接口 EnumerableRel。
    final EnumerableRel child = (EnumerableRel) getInput();
    // 核心动作 —— 触发 DFS 深度优先下探编译。
    // 调用 implementor 去编译子节点。
    // 子节点会紧接着递归编译它自己的下游，并最终生成子节点那部分的 Java 代码块（封装在 result.block 中）。
    // 同时，它会带回子节点输出的物理行格式（result.physType），当前算子必须基于这个格式来读取数据。
    final Result result =
        implementor.visitChild(this, 0, child, pref);
    // 构建当前 Calc 物理节点最终输出给上游的物理行类型 physType
    // 它结合了当前算子经过逻辑推导后的行元数据（getRowType()，即有哪些列、叫什么名字）以及外界期望的数据排布倾向（pref.prefer(result.format)），
    // 从而锁定了最终生成的 Java 对象的具体存储形态（如 Object[]、单个纯标量对象或特定的 JavaBean 类）。
    final PhysType physType =
        PhysTypeImpl.of(
            typeFactory, getRowType(), pref.prefer(result.format));

    // final Enumerable<Employee> inputEnumerable = <<child adapter>>;
    // return new Enumerable<IntString>() {
    //     Enumerator<IntString> enumerator() {
    //         return new Enumerator<IntString>() {
    //             public void reset() {
    // ...
    // 通过刚才定义的物理输出行类型获取其对应的物理 JVM 类对象 outputJavaType。
    // 然后利用 Types.of 包装出最终动态生成的迭代器强类型：Enumerator<OutputJavaType>。
    Type outputJavaType = physType.getJavaRowType();
    final Type enumeratorType =
        Types.of(
            Enumerator.class, outputJavaType);
    // 从子节点（下游）编译返回的结果 result 中，提取出它吐出的物理数据行在 JVM 里的真实类型 inputJavaType
    Type inputJavaType = result.physType.getJavaRowType();
    // 利用 Linq4j 框架在抽象语法树（AST）中挖一个坑，定义一个未来在生成的 Java 类中可见的变量。变量名叫 "inputEnumerator"，它的硬编码强类型是 Enumerator<InputJavaType>
    // 物理上直接指代“上游那个源源不断吐出原始数据的迭代器”。
    ParameterExpression inputEnumerator =
        Expressions.parameter(
            Types.of(
                Enumerator.class, inputJavaType),
            "inputEnumerator");
    // 调用上游迭代器去抓取当前行的数据指针
    // EnumUtils.convert(..., inputJavaType) 紧接着在外面强行包裹一层类型转换（Cast），确保拿出来的对象被强转为上游真实的 inputJavaType。
    Expression input =
        EnumUtils.convert(
            Expressions.call(
                inputEnumerator,
                BuiltInMethod.ENUMERATOR_CURRENT.method),
            inputJavaType);

    final RexBuilder rexBuilder = getCluster().getRexBuilder();
    final RelMetadataQuery mq = getCluster().getMetadataQuery();
    final RelOptPredicateList predicates = mq.getPulledUpPredicates(child);
    // 实例化一个高精度的表达式简化器 RexSimplify。
    // 把刚才收集到的运行时已知恒真谓词 predicates 强行灌入其中，同时绑定执行器 RexUtil.EXECUTOR（负责处理强力常量折叠）。
    final RexSimplify simplify =
        new RexSimplify(rexBuilder, predicates, RexUtil.EXECUTOR);
    final RexProgram program = this.program.normalize(rexBuilder, simplify);

    BlockStatement moveNextBody;
    // 分支 A：无过滤条件的“绿灯直通车”机制
    if (program.getCondition() == null) {
      moveNextBody =
          Blocks.toFunctionBlock(
              Expressions.call(
                  inputEnumerator,
                  BuiltInMethod.ENUMERATOR_MOVE_NEXT.method));
    } else {
    // 分支 B：带过滤条件的“死循环拦截”机制
      // 拉起 Filter 专属的二级代码块构建器 builder2。
      final BlockBuilder builder2 = new BlockBuilder();
      // 采用深度优先遍历（DFS），把 program 里的逻辑条件树彻底揉碎，翻译成 JVM 能直接识别的 Java 布尔表达式 condition。
      Expression condition =
          RexToLixTranslator.translateCondition(
              program,
              typeFactory,
              builder2,
              new RexToLixTranslator.InputGetterImpl(input, result.physType),
              implementor.allCorrelateVariables, implementor.getConformance());
      // 编织拦截闸门。
      // 向 builder2 中追加一段强力的 if 返回分支。一旦运行时算出来的 Java 布尔表达式 condition 的结果为 true，立刻打断当前执行，向更上游宣告：“我成功捕捉到了一行符合 SQL 语义的有效数据！”。
      builder2.add(
          Expressions.ifThen(
              condition,
              Expressions.return_(
                  null, Expressions.constant(true))));
      // 循环的判定条件是：inputEnumerator.moveNext()（即只要下游还有原始数据可以吐）。
      moveNextBody =
          Expressions.block(
              Expressions.while_(
                  Expressions.call(
                      inputEnumerator,
                      BuiltInMethod.ENUMERATOR_MOVE_NEXT.method),
                  builder2.toBlock()),
              Expressions.return_(
                  null,
                  Expressions.constant(false)));
    }
    // 专门负责生成行级投影（Project/SELECT）和行数据组装的“编制引擎”。
    // 它的核心任务是：动态编制未来在 JVM 中运行的 current() 方法的主体语句（currentBody）。
    // 承载投影转换逻辑的独立容器。因为在进行 SELECT a + b, SUBSTRING(c, 1, 3) 这种列级转换时，运行期会孵化出大量的中间临时变量和计算步骤，
    // 这些生成的 Java 语句都会被按顺序源源不断地塞进这个 builder3 中。
    final BlockBuilder builder3 = new BlockBuilder();
    final SqlConformance conformance =
        (SqlConformance) implementor.map.getOrDefault("_conformance",
            SqlConformanceEnum.DEFAULT);
    // 调用 RexToLixTranslator.translateProjects 方法，开始对 program 里的投影指令轴（Project List）进行全盘翻译。
    // 会遍历 SELECT 后面的每一个目标列，将它们对应的 RexNode 表达式树彻底打碎，翻译成 JVM 能直接跑的 Linq4j 标量算术/函数调用表达式。
    List<Expression> expressions =
        RexToLixTranslator.translateProjects(
            program,
            typeFactory,
            conformance,
            builder3,
            null,
            physType,
            DataContext.ROOT,
            // 引脚对接：同样通过 new InputGetterImpl(input, result.physType) 接入了我们在最开始埋好的 input 动作引脚（即 inputEnumerator.current()）。这意味着，当翻译器要计算类似 SELECT price * 1.1 的列时，它会动态生成代码去下游行的对应索引位置提取 price 列，然后乘以 1.1。
            new RexToLixTranslator.InputGetterImpl(input, result.physType),
            implementor.allCorrelateVariables);
    builder3.add(
        Expressions.return_(
            null, physType.record(expressions)));
    BlockStatement currentBody =
        builder3.toBlock();
    // 将子节点（下游算子）递归编译产生的完整 Java 代码块（result.block）强行追加泵入到当前主构建器 builder 中。
    final Expression inputEnumerable =
        builder.append(
            "inputEnumerable", result.block, false);
    final Expression body =
        Expressions.new_(
            enumeratorType,
            NO_EXPRS,
            Expressions.list(
                Expressions.fieldDecl(
                    Modifier.PUBLIC
                    | Modifier.FINAL,
                    inputEnumerator,
                    Expressions.call(
                        inputEnumerable,
                        BuiltInMethod.ENUMERABLE_ENUMERATOR.method)),
                EnumUtils.overridingMethodDecl(
                    BuiltInMethod.ENUMERATOR_RESET.method,
                    NO_PARAMS,
                    Blocks.toFunctionBlock(
                        Expressions.call(
                            inputEnumerator,
                            BuiltInMethod.ENUMERATOR_RESET.method))),
                EnumUtils.overridingMethodDecl(
                    BuiltInMethod.ENUMERATOR_MOVE_NEXT.method,
                    NO_PARAMS,
                    moveNextBody),
                EnumUtils.overridingMethodDecl(
                    BuiltInMethod.ENUMERATOR_CLOSE.method,
                    NO_PARAMS,
                    Blocks.toFunctionBlock(
                        Expressions.call(
                            inputEnumerator,
                            BuiltInMethod.ENUMERATOR_CLOSE.method))),
                Expressions.methodDecl(
                    Modifier.PUBLIC,
                    BRIDGE_METHODS
                        ? Object.class
                        : outputJavaType,
                    "current",
                    NO_PARAMS,
                    currentBody)));
    builder.add(
        Expressions.return_(
            null,
            Expressions.new_(
                BuiltInMethod.ABSTRACT_ENUMERABLE_CTOR.constructor,
                // TODO: generics
                //   Collections.singletonList(inputRowType),
                NO_EXPRS,
                ImmutableList.<MemberDeclaration>of(
                    Expressions.methodDecl(
                        Modifier.PUBLIC,
                        enumeratorType,
                        BuiltInMethod.ENUMERABLE_ENUMERATOR.method.getName(),
                        NO_PARAMS,
                        Blocks.toFunctionBlock(body))))));
    return implementor.result(physType, builder.toBlock());
  }

  @Override public @Nullable Pair<RelTraitSet, List<RelTraitSet>> passThroughTraits(
      final RelTraitSet required) {
    final List<RexNode> exps =
        Util.transform(program.getProjectList(), program::expandLocalRef);

    return EnumerableTraitsUtils.passThroughTraitsForProject(required, exps,
        input.getRowType(), input.getCluster().getTypeFactory(), traitSet);
  }

  @Override public @Nullable Pair<RelTraitSet, List<RelTraitSet>> deriveTraits(
      final RelTraitSet childTraits, final int childId) {
    final List<RexNode> exps =
        Util.transform(program.getProjectList(), program::expandLocalRef);

    return EnumerableTraitsUtils.deriveTraitsForProject(childTraits, childId, exps,
        input.getRowType(), input.getCluster().getTypeFactory(), traitSet);
  }

  @Override public RexProgram getProgram() {
    return program;
  }
}
