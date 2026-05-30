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
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.function.Function1;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.BlockStatement;
import org.apache.calcite.linq4j.tree.Blocks;
import org.apache.calcite.linq4j.tree.ClassDeclaration;
import org.apache.calcite.linq4j.tree.ConditionalStatement;
import org.apache.calcite.linq4j.tree.ConstantExpression;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.ExpressionType;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.FunctionExpression;
import org.apache.calcite.linq4j.tree.GotoStatement;
import org.apache.calcite.linq4j.tree.MemberDeclaration;
import org.apache.calcite.linq4j.tree.MethodCallExpression;
import org.apache.calcite.linq4j.tree.NewArrayExpression;
import org.apache.calcite.linq4j.tree.NewExpression;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.linq4j.tree.Primitive;
import org.apache.calcite.linq4j.tree.Statement;
import org.apache.calcite.linq4j.tree.Types;
import org.apache.calcite.linq4j.tree.UnaryExpression;
import org.apache.calcite.linq4j.tree.VisitorImpl;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.runtime.Bindable;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.util.BuiltInMethod;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Subclass of {@link org.apache.calcite.plan.RelImplementor} for relational
 * operators of {@link EnumerableConvention} calling convention.
 */
// EnumerableRelImplementor 是 Apache Calcite 内存迭代流派（EnumerableConvention）的终极代码生成引擎（Code Generation Engine）。
// 在 Calcite 将 SQL 优化为 Enumerable 物理算子树（如 EnumerableFilter、EnumerableJoin）后，
// 该类通过深度优先遍历整棵树，利用 Linq4j 抽象语法树（AST）框架，在内存中动态组装出一流的 Java 源代码。随后，这些源码被 Janino 编译器动态编译为字节码，成为真正跑在 JVM 里的高并发、流式数据清洗管道。

public class EnumerableRelImplementor extends JavaRelImplementor {
  // 全局内部上下文/配置注入池
  // 存储外界传进来的硬核配置参数（如默认的 SQL 规范 _conformance），同时也用作 stash 机制下非字面量常量的全局中转站。
  public final Map<String, Object> map;
  // 关联查询变量（Correlated Variables）转换注册表
  // 处理类似 WHERE exists (SELECT 1 FROM t2 WHERE t2.id = t1.id) 的相关子查询。它保存了关联变量名（如 t1）到物理输入提取器（InputGetter）的映射，指导表达式翻译器（RexToLixTranslator）在遇到关联列时，知道去哪行、哪个字段提取代码。
  private final Map<String, RexToLixTranslator.InputGetter> corrVars =
      new HashMap<>();
  // 基于对象内存地址的等价判定器（引用相等）
  // 利用 Google Guava 的 Equivalence.identity()，确保比对对象时只认物理内存地址（==），不认 equals。防止不同算子传入内容相同但物理不同的长对象时发生误判。
  private static final Equivalence<Object> IDENTITY = Equivalence.identity();
  // A combination of IdentityHashMap + LinkedHashMap to ensure deterministic order
  // 非字面量常量藏匿池（Stashed Parameters）
  // 专门存放无法直接写死在 Java 源码里的复杂动态对象（例如一个已经实例化好的复杂 List、外部连接池实例）。
  // 采用 LinkedHashMap 结构是为了确保生成的 Java 变量顺序在每次执行时绝对确定（Deterministic Order），防止由于乱序生成代码导致编译产生的 Hash 值不稳定。
  private final Map<Equivalence.Wrapper<Object>, ParameterExpression> stashedParameters =
      new LinkedHashMap<>();

  @SuppressWarnings("methodref.receiver.bound.invalid")
  // 面向行表达式翻译器的关联变量检索函数指针。
  // 利用 Java 8 的方法引用 this::getCorrelVariableGetter 封装成 Linq4j 要求的单参转换函数，作为纽带传递给行级翻译器。
  protected final Function1<String, RexToLixTranslator.InputGetter> allCorrelateVariables =
      this::getCorrelVariableGetter;

  public EnumerableRelImplementor(RexBuilder rexBuilder,
      Map<String, Object> internalParameters) {
    super(rexBuilder);
    this.map = internalParameters;
  }
  // 用于向下递归、遍历子物理算子节点的核心骨干方法。
  // 在 Calcite 的物理代码生成阶段，算子树（由多个 EnumerableRel 节点构成的树状拓扑）的翻译本质上是一个自顶向下（Top-Down）调度、自底向上（Bottom-Up）聚合代码的深度优先遍历（DFS）过程。
  // 而 visitChild 就是在这条递归链条中，负责承上启下、维持遍历秩序的调度员。
  public EnumerableRel.Result visitChild(
      EnumerableRel parent, // 当前正在处理的父算子节点（例如 EnumerableJoin 或 EnumerableFilter）。
      // 当前子节点在父算子的输入列表（Inputs）中的物理序号/索引位置（从 0 开始）。
      // 工业场景：对于单目算子（如 Filter、Project），ordinal 永远是 0；对于双目算子（如 Join），0 代表左表（Left Input），1 代表右表（Right Input）。
      int ordinal,
      // 即将要被翻译的子算子节点（如底层的 EnumerableTableScan）。
      EnumerableRel child,
      // 行格式偏好传导器。告知子节点在生成 Java 代码时，上层父算子更倾向于接收什么格式的数据（如：ARRAY 数组格式、CUSTOM 自定义 POJO 类格式、还是 SCALAR 单列标量格式）。
      // 这能避免子算子生成 ARRAY、父算子又强行将其拆解为 POJO 的无谓内存开销。
      EnumerableRel.Prefer prefer) {
    if (parent != null) {
      assert child == parent.getInputs().get(ordinal);
    }
    return child.implement(this, prefer);
  }
  // implementRoot 方法是整个 Enumerable 适配器代码生成流程的总指挥部。
  // 它的核心作用是：接收优化器最终交付的物理关系算子树，通过自底向上的转译，将整树的代码片段拼装成一个包含完整类结构（Class Declaration）的抽象语法树（AST），以供随后的 Janino 编译器将其编译为真正的 JVM 字节码
  // EnumerableRel rootRel 物理执行计划的根节点算子（例如一棵算子树最顶层的 EnumerableLimit、EnumerableSort 或 EnumerableProject）。整个转译递归链条的入口。代码生成器会从这个节点开始，自顶向下调度子节点的转译。
  // EnumerableRel.Prefer prefer 数据行格式的期望偏好。
  // 作用：提示根节点（以及向下的子节点）输出的数据应该采用何种 Java 格式。可选值包括 ARRAY（数组，如 Object[]）、SCALAR（单列纯标量，如 Integer）、CUSTOM（生成的特定的 POJO 类实体）等。这在物理层能够有效减少不必要的行格式拆解与重新包装开销。
  public ClassDeclaration implementRoot(EnumerableRel rootRel,
      EnumerableRel.Prefer prefer) {
    EnumerableRel.Result result;
    try {
      // 调用根节点的 implement 方法，把自身（this 转换器大管家）传进去。该调用会以深度优先（DFS）方式遍历整棵算子树，
      // 最终返回一个 EnumerableRel.Result 对象 result。
      // 该对象内含全树生成的 Linq4j 代码块（result.block）和物理行类型。
      result = rootRel.implement(this, prefer);
    } catch (RuntimeException e) {
      IllegalStateException ex = new IllegalStateException("Unable to implement "
          + RelOptUtil.toString(rootRel, SqlExplainLevel.ALL_ATTRIBUTES));
      ex.addSuppressed(e);
      throw ex;
    }
    switch (prefer) {
    case ARRAY:
      // 如果父层（或最外层容器）偏好接收 ARRAY 格式，且当前算子树生成的实际物理格式恰好是 JavaRowFormat.ARRAY，同时发现结果集只有 1 列（getFieldCount() == 1）。
      // 为什么这时候要优化？：如果有一万条数据，Calcite 默认会生成一万个 new Object[]{ val }。对于单列数据，这种包装纯属内存浪费。
      if (result.physType.getFormat() == JavaRowFormat.ARRAY
          && rootRel.getRowType().getFieldCount() == 1) {
        // 创建一个新的代码块构建器，用来存放重新组装（重构）后的新 Java 语句序列。
        BlockBuilder bb = new BlockBuilder();
        // 声明一个 AST 表达式指针，用于在接下来的遍历中，精准拦截并捕获原本要被 return 的那个数组表达式。
        Expression e = null;
        // 利用 BlockBuilder 挨个遍历原本生成好的代码块中的每一行语句。
        for (Statement statement : result.block.statements) {
          // 在 Linq4j 的抽象语法树中，return 语句并没有独立的类，它被统一表示为一种特殊的 GotoStatement（意为改变控制流、跳转向方法出口）。
          if (statement instanceof GotoStatement) {
            final GotoStatement gotoStatement = (GotoStatement) statement;
            // 通过 gotoStatement.expression 拿到原本要被 return 出去的那个 Object[] 数组变量。
            // 起个别名：调用 bb.append("v", ...)，在新的代码块里生成一行中间变量代码，类似于 final Object[] v = ...;，并将这个变量表达式赋给 e。
            e =
                bb.append("v",
                    requireNonNull(gotoStatement.expression, "expression"));
          } else {
            // 对于非 return 的常规计算语句，老老实实地原样拷贝复制到新的构建器 bb 中，保证前置的物理计算逻辑不丢失。
            bb.add(statement);
          }
        }
        if (e != null) {
          // BuiltInMethod.SLICE0.method：这是 Calcite 运行时的一个内置工具方法，它的物理源码极其简单：
          // public static Object slice0(Object[] array) {
          //    return array[0];
          //}
          bb.add(
              Expressions.return_(null,
                  Expressions.call(null, BuiltInMethod.SLICE0.method, e)));
        }
        result =
            new EnumerableRel.Result(bb.toBlock(), result.physType,
                JavaRowFormat.SCALAR);
      }
      break;
    default:
      break;
    }
    // 声明一个成员列表。
    // 在 Java 类结构中，凡是属于类的组成部分（如内部类、成员变量、成员方法），在 Linq4j 树中都被抽象为 MemberDeclaration。
    final List<MemberDeclaration> memberDeclarations = new ArrayList<>();
    // 启动类型巡检官。
    // 背景：在多表 Join 或 Project 投影时，SQL 会产生全新的列组合（例如把 emp 表的 3 列和 dept 表的 2 列拼成 5 列的新行）。
    // 这个新行在现有的 JVM 内存里根本没有现成的类对应。
    // 物理作用：TypeRegistrar 会通篇扫描 result 代码块中的所有语句。
    // 一旦揪出 Calcite 在编译期动态派生出的合成中间类型（SyntheticRecordType），
    // 它就会调用 classDecl 方法手写出该 POJO 类的字段、无参构造、equals、hashCode、compareTo 以及 toString 源码，并作为内部类塞入 memberDeclarations 大池子里。
    new TypeRegistrar(memberDeclarations).go(result);

    // This creates the following code
    // final Integer v1stashed = (Integer) root.get("v1stashed")
    // It is convenient for passing non-literal "compile-time" constants
    // 为什么要“Stash（藏匿）”？：在生成 Java 源码时，像数字 10、字符串 "abc" 这种字面量很容易直接写死在代码里（如 int a = 10;）。
    // 但如果是一个在外部已经实例化好的、极为复杂的运行期大对象（例如一个装满规则的自定义 List，或者一个第三方连接池实体），你根本无法用一串纯文本源码在代码里表达它。
    final Collection<Statement> stashed =
        // 利用 Guava 的 transform 动态生成对应的 Java 代码语句（Statement），负责在 Java 类运行时解封这些对象：
        Collections2.transform(stashedParameters.values(),
            // 在生成的 Java 代码顶端，正式声明该局部变量：final Integer v1stashed = (Integer) root.get("v1stashed");。
            input -> Expressions.declare(Modifier.FINAL, input,
                // 生成类型强转代码，将从 DataContext 捞出来的 Object 强转回真正的 Java 物理强类型：(Integer) root.get("v1stashed")。
                Expressions.convert_(
                    // 生成调用大管家上下文的方法。运行时转译为：root.get("v1stashed")。
                    Expressions.call(DataContext.ROOT,
                        BuiltInMethod.DATA_CONTEXT_GET.method,
                        // 生成当前藏匿变量的唯一字符串名称（形如 "v1stashed"）。
                        Expressions.constant(input.name)),
                    input.type)));

    // 利用 Iterables.concat 将两部分语句序列串联。
    // 前部：刚刚拼装好的、用于在运行时去 root 里捞取并解封复杂常量的声明语句块（stashed）。
    // 后部：物理关系算子树自底向上深度优先遍历出来的、真正的流式计算核心逻辑语句块（result.block.statements）。
    final BlockStatement block =
        Expressions.block(
            Iterables.concat(stashed, result.block.statements));
    // 装配 bind 核心物理方法
    // 动态手写出 Bindable 接口要求的灵魂核心方法，并塞入成员列表中。
    memberDeclarations.add(
        Expressions.methodDecl(Modifier.PUBLIC, Enumerable.class,
            BuiltInMethod.BINDABLE_BIND.method.getName(),
            Expressions.list(DataContext.ROOT),
            block));
    // 装配 getElementType 强类型声明方法
    // 这能让 Linq4j 管道在流式迭代数据集时，能够通过该方法一目了然地知道迭代器里流淌的数据究竟是什么 Java 强类型。
    memberDeclarations.add(
        Expressions.methodDecl(Modifier.PUBLIC, Class.class,
            BuiltInMethod.TYPED_GET_ELEMENT_TYPE.method.getName(),
            ImmutableList.of(),
            Blocks.toFunctionBlock(
                Expressions.return_(null,
                    Expressions.constant(result.physType.getJavaRowType())))));
    // 极物理交付：生产类宣告（Class Declaration）
    // 调用 Expressions.classDecl，将前面塞满了“动态 POJO 内部类”、“bind 方法”、“getElementType 方法”的 memberDeclarations 大池子进行终极合龙，组装成一个完整的类结构定义并返回
    return Expressions.classDecl(Modifier.PUBLIC,
        "Baz",
        null,
        Collections.singletonList(Bindable.class),
        memberDeclarations);
  }
  // Calcite 中用来在堆内存中动态手写一个标准 Java POJO 实体类的核心孵化器
  // 在大数据复杂查询（如多表 Join 或复杂的 Select 投影）中，底层计算流淌的数据组合在已有的 JVM 里面根本找不到对应的 Class 类。该方法就是用来生成这些临时类的。
  // JavaTypeFactoryImpl.SyntheticRecordType type  Calcite 类型系统在编译期派生出的合成记录类型（Synthetic Record Type）。
  // 它包含了这个临时实体类需要具备的一切元数据信息，如类名（type.getName()）以及它包含的所有物理列、字段名、字段类型（type.getRecordFields()）
  // 该方法最终返回一个大聚合包裹 ClassDeclaration。
  // 通过这套高精密度的手写 AST 逻辑，Calcite 确保了后续动态生成的 Java 类具备极高的工业完备度：不仅解决了中间状态没类可用的尴尬，还完美兼顾了无参构造安全上限、全字段 Null 防御比对，以及针对无排序能力字段的宽容过滤，是 Calcite 代码生成的基石支撑。
  private static ClassDeclaration classDecl(
      JavaTypeFactoryImpl.SyntheticRecordType type) {
    // 孵化类外壳声明
    // 调用 Linq4j 语法树工具创建类声明。
    // 转译出的 Java 结构：
    // public static class Record2_0 implements java.io.Serializable { ... }
    ClassDeclaration classDeclaration =
        Expressions.classDecl(
            Modifier.PUBLIC | Modifier.STATIC,
            type.getName(),
            null,
            ImmutableList.of(Serializable.class),
            new ArrayList<>());

    // For each field:
    //   public T0 f0;
    //   ...
    // 批量孵化类的成员属性字段（Fields）
    // 通过 for 循环遍历这个合成类型里的每一个物理列。
    for (Types.RecordField field : type.getRecordFields()) {
      classDeclaration.memberDeclarations.add(
          // 生成字段声明代码。使用列原本的修饰符（通常是 public），把列类型（如 int.class 或 String.class）和列名（如 f0、f1）捆绑成字段，第三个参数初始化值为 null。
          // 转译出的 Java 结构：
          // public int f0;
          // public java.lang.String f1;
          Expressions.fieldDecl(
              field.getModifiers(),
              Expressions.parameter(
                  field.getType(), field.getName()),
              null));
    }

    // Constructor:
    //   Foo(T0 f0, ...) { this.f0 = f0; ... }
    // 生产安全的空参构造函数（Constructor）
    // 设计意图（重要内幕）：原本的注释提示这里曾考虑生成带参构造函数。但由于宽表或 OLAP 分析中字段极其繁多，如果强行把全字段塞入构造函数参数列表，
    // 在遭遇几百、上千列的宽表时，极易直接触发 JVM 编译期 "Method parameters count exceeds limit of 255"（方法参数超过255个限制） 的暴毙性错误。
    // Calcite 框架在这里极为务实地保持 parameters 列表为空，生成一个纯粹的 public 无参构造函数。后续字段赋值通过直接操作公开 Field 或反射完成。
    final BlockBuilder blockBuilder = new BlockBuilder();
    final List<ParameterExpression> parameters = new ArrayList<>();
    final ParameterExpression thisParameter =
        Expressions.parameter(type, "this");

    // Here a constructor without parameter is used because the generated
    // code could cause error if number of fields is too large.
    classDeclaration.memberDeclarations.add(
        Expressions.constructorDecl(
            Modifier.PUBLIC,
            type,
            parameters,
            blockBuilder.toBlock()));

    // equals method():
    //   public boolean equals(Object o) {
    //       if (this == o) return true;
    //       if (!(o instanceof MyClass)) return false;
    //       final MyClass that = (MyClass) o;
    //       return this.f0 == that.f0
    //         && equal(this.f1, that.f1)
    //         ...
    //   }
    // 手写重写 equals 方法
    // 开启第二个代码构建器 blockBuilder2，声明两个参数表达式：一个代表即将强转后的 that，另一个代表入参 Object o。
    final BlockBuilder blockBuilder2 = new BlockBuilder();
    final ParameterExpression thatParameter =
        Expressions.parameter(type, "that");
    final ParameterExpression oParameter =
        Expressions.parameter(Object.class, "o");
    // 转译出的 Java 结构：if (this == o) return true;（引用相同快速返回）
    blockBuilder2.add(
        Expressions.ifThen(
            Expressions.equal(thisParameter, oParameter),
            Expressions.return_(null, Expressions.constant(true))));
    // 转译出的 Java 结构：if (!(o instanceof Record2_0)) return false;（类型校验防御）。
    blockBuilder2.add(
        Expressions.ifThen(
            Expressions.not(
                Expressions.typeIs(oParameter, type)),
            Expressions.return_(null, Expressions.constant(false))));
    // 转译出的 Java 结构：final Record2_0 that = (Record2_0) o;（安全向下转型）
    blockBuilder2.add(
        Expressions.declare(
            Modifier.FINAL,
            thatParameter,
            Expressions.convert_(oParameter, type)));
    final List<Expression> conditions = new ArrayList<>();
    // 全字段值比对物理内幕：遍历所有字段，判定其物理属性：
    // 如果是基本数据类型（Primitive.is(...) 为 true），直接拼装 this.f0 == that.f0 的原生代码。
    // 如果是包装引用类型，为了防范 NullPointerException（空指针），引入内置的方法 BuiltInMethod.OBJECTS_EQUAL，拼装出 java.util.Objects.equals(this.f1, that.f1)。
    for (Types.RecordField field : type.getRecordFields()) {
      conditions.add(
          Primitive.is(field.getType())
              ? Expressions.equal(
                  Expressions.field(thisParameter, field.getName()),
                  Expressions.field(thatParameter, field.getName()))
              : Expressions.call(BuiltInMethod.OBJECTS_EQUAL.method,
                  Expressions.field(thisParameter, field.getName()),
                  Expressions.field(thatParameter, field.getName())));
    }
    // 极其精妙。它把 conditions 列表里的所有列比对表达式，用 &&（逻辑与） 优雅地焊接成一整行长表达式返回。
    blockBuilder2.add(
        Expressions.return_(null, Expressions.foldAnd(conditions)));
    // 最终装配 equals 成员方法：
    classDeclaration.memberDeclarations.add(
        Expressions.methodDecl(
            Modifier.PUBLIC,
            boolean.class,
            "equals",
            Collections.singletonList(oParameter),
            blockBuilder2.toBlock()));

    // hashCode method:
    //   public int hashCode() {
    //     int h = 0;
    //     h = hash(h, f0);
    //     ...
    //     return h;
    //   }
    // 手写重写 hashCode 方法
    // 转译出的 Java 结构：int h = 0;（初始化哈希种子）。
    final BlockBuilder blockBuilder3 = new BlockBuilder();
    final ParameterExpression hParameter =
        Expressions.parameter(int.class, "h");
    final ConstantExpression constantZero =
        Expressions.constant(0);
    blockBuilder3.add(
        Expressions.declare(0, hParameter, constantZero));
    // 哈希链式计算内幕：遍历每一个列字段，调用内置的哈希混合搅拌算子 BuiltInMethod.HASH（运行时实际执行：h = Utilities.hash(h, field)，
    // 内部采用类似 h * 31 + field.hashCode() 的标准滚动散列算法）。
    // 转译出的 Java 结构：
    // public int hashCode() {
    //  int h = 0;
    //  h = org.apache.calcite.runtime.Utilities.hash(h, this.f0);
    //  h = org.apache.calcite.runtime.Utilities.hash(h, this.f1);
    //  return h;
    //}
    for (Types.RecordField field : type.getRecordFields()) {
      final Method method = BuiltInMethod.HASH.method;
      blockBuilder3.add(
          Expressions.statement(
              Expressions.assign(
                  hParameter,
                  Expressions.call(
                      method.getDeclaringClass(),
                      method.getName(),
                      ImmutableList.of(
                          hParameter,
                          Expressions.field(thisParameter, field))))));
    }
    blockBuilder3.add(
        Expressions.return_(null, hParameter));
    classDeclaration.memberDeclarations.add(
        Expressions.methodDecl(
            Modifier.PUBLIC,
            int.class,
            "hashCode",
            Collections.emptyList(),
            blockBuilder3.toBlock()));

    // compareTo method:
    //   public int compareTo(MyClass that) {
    //     int c;
    //     c = compare(this.f0, that.f0);
    //     if (c != 0) return c;
    //     ...
    //     return 0;
    //   }
    // 手写重写 compareTo 方法（用于物理排序/聚合）
    // 转译出的 Java 结构：int c;（如果只有一列则带有 final 关键字，减少栈开销）。
    final BlockBuilder blockBuilder4 = new BlockBuilder();
    final ParameterExpression cParameter =
        Expressions.parameter(int.class, "c");
    final int mod = type.getRecordFields().size() == 1 ? Modifier.FINAL : 0;
    blockBuilder4.add(
        Expressions.declare(mod, cParameter, null));
    // 转译出的 Java 结构：if (c != 0) return c;（只要当前字段分出了胜负，立刻熔断返回排序结果）。
    final ConditionalStatement conditionalStatement =
        Expressions.ifThen(
            Expressions.notEqual(cParameter, constantZero),
            Expressions.return_(null, cParameter));
    // 排序比对物理内幕：逐字段拼装。
    // Null 安全防线：如果发现当前的物理列元数据标记允许为 null（field.nullable() 为 true），动态换装 COMPARE_NULLS_LAST 方法代码（防止排序时抛出 NPE）；否则使用常规的 COMPARE 方法代码。
    // 转译出的 Java 结构：
    // public int compareTo(Record2_0 that) {
    //  int c;
    //  c = org.apache.calcite.runtime.Utilities.compare(this.f0, that.f0);
    //  if (c != 0) return c;
    //  c = org.apache.calcite.runtime.Utilities.compareNullsLast(this.f1, that.f1);
    //  if (c != 0) return c;
    //  return 0;
    //}
    for (Types.RecordField field : type.getRecordFields()) {
      MethodCallExpression compareCall;
      try {
        final Method method = (field.nullable()
            ? BuiltInMethod.COMPARE_NULLS_LAST
            : BuiltInMethod.COMPARE).method;
        compareCall =
            Expressions.call(method.getDeclaringClass(), method.getName(),
                Expressions.field(thisParameter, field),
                Expressions.field(thatParameter, field));
      } catch (RuntimeException e) {
        if (e.getCause() instanceof NoSuchMethodException) {
          // Just ignore the field in compareTo
          // "create synthetic record class" blindly creates compareTo for
          // all the fields, however not all the records will actually be used
          // as sorting keys (e.g. temporary state for aggregate calculation).
          // In those cases it is fine if we skip the problematic fields.
          continue;
        }
        throw e;
      }
      blockBuilder4.add(
          Expressions.statement(
              Expressions.assign(
                  cParameter,
                  compareCall)));
      blockBuilder4.add(conditionalStatement);
    }
    blockBuilder4.add(
        Expressions.return_(null, constantZero));
    classDeclaration.memberDeclarations.add(
        Expressions.methodDecl(
            Modifier.PUBLIC,
            int.class,
            "compareTo",
            Collections.singletonList(thatParameter),
            blockBuilder4.toBlock()));

    // toString method:
    //   public String toString() {
    //     return "{f0=" + f0
    //       + ", f1=" + f1
    //       ...
    //       + "}";
    //   }
    // 手写重写 toString 方法
    // 利用 Expressions.add 在语法树层面不断叠加字符串拼接指令。如果合成类没有任何字段，直接返回常量 "{}"；否则滚动组装字段拼接代码。
    final BlockBuilder blockBuilder5 = new BlockBuilder();
    Expression expression5 = null;
    for (Types.RecordField field : type.getRecordFields()) {
      if (expression5 == null) {
        expression5 =
            Expressions.constant("{" + field.getName() + "=");
      } else {
        expression5 =
            Expressions.add(
                expression5,
                Expressions.constant(", " + field.getName() + "="));
      }
      expression5 =
          Expressions.add(
              expression5,
              Expressions.field(thisParameter, field.getName()));
    }
    expression5 =
        expression5 == null
            ? Expressions.constant("{}")
            : Expressions.add(
                expression5,
                Expressions.constant("}"));
    blockBuilder5.add(
        Expressions.return_(
            null,
            expression5));
    classDeclaration.memberDeclarations.add(
        Expressions.methodDecl(
            Modifier.PUBLIC,
            String.class,
            "toString",
            Collections.emptyList(),
            blockBuilder5.toBlock()));

    return classDeclaration;
  }

  /**
   * Stashes a value for the executor. Given values are de-duplicated if
   * identical (see {@link java.util.IdentityHashMap}).
   *
   * <p>For instance, to pass {@code ArrayList} to your method, you can use
   * {@code Expressions.call(method, implementor.stash(arrayList))}.
   *
   * <p>For simple literals (strings, numbers) the result is equivalent to
   * {@link org.apache.calcite.linq4j.tree.Expressions#constant(Object, java.lang.reflect.Type)}.
   *
   * <p>Note: the input value is held in memory as long as the statement
   * is alive. If you are using just a subset of its content, consider creating
   * a slimmer holder.
   *
   * @param input Value to be stashed
   * @param clazz Java class type of the value when it is used
   * @param <T> Java class type of the value when it is used
   * @return Expression that will represent {@code input} in runtime
   */
  // 在动态生成 Java 代码时，如何把外部运行期的复杂 Java 对象（如已加载的规则集、第三方连接池、自定义复杂 UDF 状态）传递给即将动态编译且运行的 Java 字节码，是一个核心痛点。
  // stash 方法就是解决这个问题的“秘密通道”。
  // <T>：泛型标记，代表被藏匿对象的运行时真实强类型。
  // T input：即将被偷渡/藏匿的真实内存对象。
  // Class<? super T> clazz：该对象在未来生成的 Java 源代码中，声明该变量时所采用的 Java 类型（Class Type）。
  // 通常是 T 本身或其父类/接口（例如，传入 ArrayList，但声明类型可以指定为 List.class）。
  // 返回值 Expression：返回一个 Linq4j 的表达式（通常是一个变量表达式 ParameterExpression）。上层算子拿到这个返回值后，可以直接把它嵌入到动态生成的代码树中（如作为方法调用的入参）。
  public <T> Expression stash(T input, Class<? super T> clazz) {
    // Well-known final classes that can be used as literals
    // 首先拦截常见的基础内置类型（null、字符串、布尔值、以及各类数值型包装类）。
    // 这些类型在 Java 源码里具有标准的字面量表达形式（例如数字 10 在源码里就是 "10"，字符串 "abc" 在源码里就是 '"abc"'）。它们不需要走复杂的动态“偷渡”流程，直接调用 Expressions.constant(...) 转换成常规的代码常量表达式即可。
    if (input == null
        || input instanceof String
        || input instanceof Boolean
        || input instanceof Byte
        || input instanceof Short
        || input instanceof Integer
        || input instanceof Long
        || input instanceof Float
        || input instanceof Double) {
      return Expressions.constant(input, clazz);
    }
    // 利用 Guava 的 Equivalence.IDENTITY 将输入对象包裹起来。这意味着接下来的比对规则是严格的物理内存地址比对（即 == 判定），而不是对象的 equals() 方法。
    final Equivalence.Wrapper<Object> key = IDENTITY.wrap(input);
    // 去缓存池（类似于一个 IdentityHashMap）里查询这个一模一样的内存对象之前是否已经被偷渡过。
    ParameterExpression cached = stashedParameters.get(key);
    if (cached != null) {
      return cached;
    }
    // "stashed" avoids name clash since this name will be used as the variable
    // name at the very start of the method.
    // 为这个变量动态生成一个绝对不会冲突的唯一物理名称。
    // 例如："v0stashed"、"v1stashed"。带上 "stashed" 后缀是为了绝对避开用户 SQL 里的列名（如 v1、v2），防止 Janino 编译时发生命名冲突。
    final String name = "v" + map.size() + "stashed";
    final ParameterExpression x = Expressions.variable(clazz, name);
    map.put(name, input);
    stashedParameters.put(key, x);
    return x;
  }
  // 处理 SQL 相关变量（Correlate Variable，即嵌套子查询中的外表引用，如 t1.x） 的核心注册中心。
  // 在执行带有相关子查询（Correlate Query，如 WHERE EXISTS (SELECT 1 FROM t2 WHERE t2.id = t1.id)）的计算时，内层算子在翻译自身代码时，必须能够就地拦截、捕获并读取外层算子（当前行）的上下文数据。
  // registerCorrelVariable 就是外层算子用来向大管家注册“如何提取我当前行指定列数据”的物理契约。
  public void registerCorrelVariable(
      // final String name：相关变量的物理标识符名称（例如 SQL 语法树中的 $cor0 或 $cor1）。
      final String name,
      // 代表外层算子当前迭代行的 Java 变量表达式（通常在生成的代码里表现为类似 final Object[] current_row 或 Record2_0 current_row 的语法树节点）。
      final ParameterExpression pe,
      // 外层算子专用的代码块构建器。
      final BlockBuilder corrBlock,
      // 外层算子当前行的物理类型元数据（Physical Type）。
      // 它深知当前行究竟是个 Object[] 数组、一个 POJO 实体类，还是一个标量，并且知道每一列在内存中的具体排布与获取方式。
      final PhysType physType) {
    // orrVars 的物理身份：它在类内部是一个 Map<String, CorrelVariableLookup>。它的 Value 并不是一个死的数据，而是一个回调函数（函数式接口）。
    // 为什么要注册回调，而不是直接存数据？：因为外层算子在遍历时，并不知道内层子查询到底会用到自己的哪一列（index）。所以外层算子在这里布下一个“锦囊”（Lambda 表达式），
    // 对大管家说：“我把提取数据的秘籍传给你。待会儿内层算子找你要 $cor0 的第 index 列时，你直接运行我这个 Lambda 表达式就行！”
    // 当内层算子触发回调并传入 index（列序号）和 storageType（期望的 Java 存储类型）时，physType 会根据自身多态机制动态生成对应的取列代码表达式：
    // 如果外层行是数组（ARRAY）：孵化出 (Type) pe[index]（数组下标访问）；
    // 如果外层行是复合实体（POJO）：孵化出 pe.f0（直接访问成员属性）。
    corrVars.put(name, (list, index, storageType) -> {
      Expression fieldReference =
          physType.fieldReference(pe, index, storageType);
      return corrBlock.append(name + "_" + index, fieldReference);
    });
  }

  public void clearCorrelVariable(String name) {
    assert corrVars.containsKey(name) : "Correlation variable " + name
        + " should be defined";
    corrVars.remove(name);
  }

  public RexToLixTranslator.InputGetter getCorrelVariableGetter(String name) {
    assert corrVars.containsKey(name) : "Correlation variable " + name
        + " should be defined";
    return corrVars.get(name);
  }

  public EnumerableRel.Result result(PhysType physType, BlockStatement block) {
    return new EnumerableRel.Result(
        block, physType, ((PhysTypeImpl) physType).format);
  }

  @Override public SqlConformance getConformance() {
    return (SqlConformance) map.getOrDefault("_conformance",
        SqlConformanceEnum.DEFAULT);
  }

  /** Visitor that finds types in an {@link Expression} tree. */
  // TypeFinder 就是 TypeRegistrar 手下的一名侦察兵（或探针）。它的唯一职责是：以只读的方式深度遍历整棵 Linq4j 表达式树（Expression Tree），全方位监控并捕获所有在代码中被使用的、被引用的、或者通过显式转换生成的 Java 类型（Type），然后把它们塞进一个集中的收集池中。
  // TypeFinder 并不直接执行任何修改树结构的操作。它只是在树上“爬行”，每当遇到特定的表达式（例如：实例化一个新对象、初始化一个数组、执行强制类型转换、或者定义 Lambda 表达式参数时），它就会拦截并把这个表达式对应的 Java 类型抽取出来。
  @VisibleForTesting
  static class TypeFinder extends VisitorImpl<Void> {
    // 类型收集池（外部结果容器）。
    // 通常是从外部传进来的一个 Set。TypeFinder 在遍历表达式树过程中捞到的所有 Type 都会放入这个集合中，供后续的 TypeRegistrar 进行合成类型的过滤与最终的源码声明。
    private final Collection<Type> types;

    TypeFinder(Collection<Type> types) {
      this.types = types;
    }
     //访问new一个对象的表达式，把对象的类型放入types
    @Override public Void visit(NewExpression newExpression) {
      types.add(newExpression.type);
      return super.visit(newExpression);
    }
    //访问数组，并且把type放入types
    @Override public Void visit(NewArrayExpression newArrayExpression) {
      Type type = newArrayExpression.type;
      for (;;) {
        final Type componentType = Types.getComponentType(type);
        if (componentType == null) {
          break;
        }
        type = componentType;
      }
      types.add(type);
      return super.visit(newArrayExpression);
    }
    //访问常量，并放入types
    @Override public Void visit(ConstantExpression constantExpression) {
      final Object value = constantExpression.value;
      if (value instanceof Type) {
        types.add((Type) value);
      }
      if (value == null) {
        // null literal
        Type type = constantExpression.getType();
        types.add(type);
      }
      return super.visit(constantExpression);
    }

    @Override public Void visit(FunctionExpression functionExpression) {
      final List<ParameterExpression> list = functionExpression.parameterList;
      for (ParameterExpression pe : list) {
        types.add(pe.getType());
      }
      if (functionExpression.body == null) {
        return super.visit(functionExpression);
      }
      types.add(functionExpression.body.getType());
      return super.visit(functionExpression);
    }

    @Override public Void visit(UnaryExpression unaryExpression) {
      if (unaryExpression.nodeType == ExpressionType.Convert) {
        types.add(unaryExpression.getType());
      }
      return super.visit(unaryExpression);
    }
  }

  /** Adds a declaration of each synthetic type found in a code block. */
  // TypeRegistrar 是 Apache Calcite 项目在执行物理计划到 Java 代码生成（Code Generation）阶段使用的一个极其关键的内部私有静态辅助类
  // 核心背景：什么是“合成类型（Synthetic Type）”？
  // 在 SQL 级别，用户可以随意对字段进行拼接、聚合或投影（例如 SELECT id, name, age + 10 FROM users）。
  // 当 Calcite 的 Enumerable 物理算子（如 EnumerableProject）准备把这行 SQL 编译成 Java 运行期代码时，Java 语言本身并没有一个原生的 Class 能完美对应这种临时拼接出来的行记录结构。
  // 为了打破这个限制，Calcite 的类型工厂（JavaTypeFactoryImpl）会在内存中动态合成一种临时的、特殊的隐式类，称为 SyntheticRecordType（合成记录类型）。
  // 这个类其实就像是动态生成的 POJO/JavaBean，里面包含了对应的字段定义。
  // TypeRegistrar 的核心使命
  // 虽然内存中有了 SyntheticRecordType 的类型定义，但如果我们要运行最终生成的 Java 代码，
  // 就必须在最终生成的匿名内部类的类体结构中，把这个动态生成的 POJO 类的完整源代码（即 class SyntheticRecordType_X { ... }）作为一个内部类给声明出来，否则 JVM 在现场编译时会报“找不到类符号”的错误。
  // TypeRegistrar（类型注册官）的唯一职责就是：像一个搜救队一样，深度扫描动态生成的代码块（Block），把所有隐藏在各处的“合成类型”全部揪出来，并将它们的类声明（ClassDeclaration）作为类成员挂载到主生成类的内部。
  private static class TypeRegistrar {
    // 宿主类的成员声明主容器。
    // 它是从外部（通常是主生成器）传进来的引用。一旦 TypeRegistrar 发现了新的合成类型，就会把这个类型的类声明（ClassDeclaration）直接 add 进这个列表中，从而成为最终 Java 文本的一部分。
    private final List<MemberDeclaration> memberDeclarations;
    // 防重过滤器（去重集合）。
    // 用于在扫描和递归处理时，记录哪些类型已经被分析并注册过了，防止同一个合成类型被重复注册，导致生成的 Java 代码中出现重名类的编译冲突。
    private final Set<Type> seen = new HashSet<>();

    TypeRegistrar(List<MemberDeclaration> memberDeclarations) {
      this.memberDeclarations = memberDeclarations;
    }
    // 负责接收一个 Java 类型，研判其是否为合成类型，如果是则触发代码生成，同时提供深度的范型自适应降维拆解。
    private void register(Type type) {
      // 去重拦截。将类型塞入 seen 集合。如果添加失败，说明该类型此前已经走过此流程，直接触发熔断返回。
      if (!seen.add(type)) {
        return;
      }
      // 如果该类型属于 Calcite 的合成记录类型：
      if (type instanceof JavaTypeFactoryImpl.SyntheticRecordType) {
        memberDeclarations.add(
            // 强转后调用 classDecl(...) 方法（这是外部的一个辅助方法，
            // 用于将一个合成类型动态编译生成为 Linq4j 的 ClassDeclaration 节点）
            classDecl((JavaTypeFactoryImpl.SyntheticRecordType) type));
      }
      // 泛型深度剥离（重要细节）。
      // 如果类型是带泛型的（例如 List<SyntheticRecordType_A>）：
      // 这种情况下，type 本身并不是 SyntheticRecordType，但它的泛型实参（Actual Type Arguments）里面隐藏着合成类型！
      if (type instanceof ParameterizedType) {
        // 强行剥离出它所有的泛型参数，并递归调用 register(type1)。
        // 这确保了即便隐藏在 Map<String, List<SyntheticRecordType_B>> 这样极其复杂的嵌套泛型最深处的合成类，也能被精准捕获并注册。
        for (Type type1 : ((ParameterizedType) type).getActualTypeArguments()) {
          register(type1);
        }
      }
    }
    // 对某个物理算子的计算结果执行全盘扫描的执行总枢纽
    public void go(EnumerableRel.Result result) {
      // 创建一个有序的类型临时收集池（使用 LinkedHashSet 确保注册顺序的一致性）。
      final Set<Type> types = new LinkedHashSet<>();
      // 大范围拉网式扫描。调用本次物理算子生成的代码块（result.block），让其接受一个定制的访问者 TypeFinder。
      // TypeFinder 会像探针一样，遍历整个代码块里的每一行赋值、每一个方法调用、每一个变量声明，把里面见到的所有 Type 统统扔进 types 集合中。
      result.block.accept(new TypeFinder(types));
      // 补充兜底。
      // 把该算子最终物理输出的“行数据类型”（Java Row Type）也显式强制加入到 types 池子中，防止该类型由于某些极端情况没在 block 代码体内显式出现而被遗漏。
      types.add(result.physType.getJavaRowType());
      for (Type type : types) {
        register(type);
      }
    }
  }
}
