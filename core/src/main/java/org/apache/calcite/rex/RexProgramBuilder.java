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
package org.apache.calcite.rex;

import org.apache.calcite.plan.RelOptPredicateList;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.Pair;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Workspace for constructing a {@link RexProgram}.
 *
 * <p>RexProgramBuilder is necessary because a {@link RexProgram} is immutable.
 * (The {@link String} class has the same problem: it is immutable, so they
 * introduced {@link StringBuilder}.)
 */
// RexProgram 是不可变的（Immutable），就像 Java 中的 String；为了高效构建它，Calcite 引入了 RexProgramBuilder，其角色类似于 StringBuilder。
// 在 Calcite 中，RexProgram（行表达式程序）用于将一整套投影（Projects）和一个可选的过滤条件（Condition）打包压缩到一个单一的、平铺的、高度复用的表达式列表（exprList）中。它最典型的应用场景是承载 LogicalCalc / EnumerableCalc 算子的计算逻辑。
// 核心使命：
// 收集与去重：作为一个收集池，在构建 RexProgram 时，将代码中分散的表达式（如 a + b）统一收集起来。如果发现相同的表达式被多次使用，它会进行自动去重（Common Sub-expression Elimination, CSE），复用同一个 RexLocalRef（局部引用）。
// 规范化（Normalization）：将任意嵌套深度的表达式树（例如 a + b + c）打平成最大深度为 1 的平铺结构。所有的子参数都会被抽取为前置的 RexLocalRef。
// 合并程序（Merge Programs）：提供 mergePrograms 静态方法，能将两个上下级联的 Calc 算子逻辑（比如两个连续的 SELECT 嵌套嵌套）合并成一个单一的 RexProgram，实现底层的算子融合（Operator Fusion），从而极大提高执行效率。
public class RexProgramBuilder {
  //~ Instance fields --------------------------------------------------------
  // 表达式构建工厂。用于在内部构建或转换表达式节点（如构造 AND 呼叫）。
  private final RexBuilder rexBuilder;
  // 输入行类型元数据。定义了当前程序输入数据的字段结构（名称及类型）。
  private final RelDataType inputRowType;
  // 通用纯平表达式主容器。
  // 存放当前程序包含的所有基础表达式。前 $N$ 个固定为输入的 RexInputRef。
  private final List<RexNode> exprList = new ArrayList<>();
  // 去重路由表。
  // 键是表达式及其类型的描述，值是分配的局部引用。确保相同的表达式不会在 exprList 中重复录入。
  private final Map<Pair<RexNode, String>, RexLocalRef> exprMap =
      new HashMap<>();
  // 快速引用索引映射表。
  // 与 exprList 一一对应，保存对应位置表达式的 RexLocalRef 快捷指针
  private final List<RexLocalRef> localRefList = new ArrayList<>();
  // 投影引用列表。
  // 记录哪些位置的表达式将作为最终的输出字段流。里面全是索引。
  private final List<RexLocalRef> projectRefList = new ArrayList<>();
  // 输出字段别名列表。与 projectRefList 一一对应，记录每个输出字段的名称（若用户未指定则后续自动生成 $0, $1）。
  private final List<@Nullable String> projectNameList = new ArrayList<>();
  @SuppressWarnings("unused")
  // 表达式简化器。用于在构建过程中剔除无效逻辑（如把 1 + 1 变成 2，或把 IS_TRUE(true) 抹平）。
  private final @Nullable RexSimplify simplify;
  // 过滤条件引用指针。
  // 指向 exprList 中作为 WHERE 过滤标准的那个布尔表达式节点。若为 null 代表无过滤。
  private @Nullable RexLocalRef conditionRef = null;
  // 校验开关。当 JVM 开启 -ea（断言开启）时为 true，会在注册表达式时严格校验类型是否错乱。
  private boolean validating;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a program-builder that will not simplify.
   */
  public RexProgramBuilder(RelDataType inputRowType, RexBuilder rexBuilder) {
    this(inputRowType, rexBuilder, null);
  }

  /**
   * Creates a program-builder.
   */
  // 核心初始化私有构造。
  // 绑定输入行元数据及工厂。
  // 预分配输入列：如果 inputRowType 是结构体，直接循环遍历其所有字段，调用 RexInputRef.of(i, fields) 为每一列提前创建输入引用表达式，并通过 registerInternal 灌入 exprList 的最前排位置。
  @SuppressWarnings("method.invocation.invalid")
  private RexProgramBuilder(RelDataType inputRowType, RexBuilder rexBuilder,
      @Nullable RexSimplify simplify) {
    this.inputRowType = requireNonNull(inputRowType, "inputRowType");
    this.rexBuilder = requireNonNull(rexBuilder, "rexBuilder");
    this.simplify = simplify; // may be null
    this.validating = assertionsAreEnabled();

    // Pre-create an expression for each input field.
    if (inputRowType.isStruct()) {
      final List<RelDataTypeField> fields = inputRowType.getFieldList();
      for (int i = 0; i < fields.size(); i++) {
        registerInternal(RexInputRef.of(i, fields));
      }
    }
  }

  /**
   * Creates a program builder with the same contents as a program.
   *
   * @param rexBuilder     Rex builder
   * @param inputRowType   Input row type
   * @param exprList       Common expressions
   * @param projectList    Projections
   * @param condition      Condition, or null
   * @param outputRowType  Output row type
   * @param normalize      Whether to normalize
   * @param simplify       Simplifier, or null to not simplify
   */
  // 基于一组已有的表达式和结构（通常来自于一个现有的 RexProgram），在内存中重建、还原并可能优化（简化与规范化）出一个全新的、可变的 Builder 状态。
  @SuppressWarnings("method.invocation.invalid")
  private RexProgramBuilder(
      RexBuilder rexBuilder,
      final RelDataType inputRowType,
      final List<RexNode> exprList,
      final Iterable<? extends RexNode> projectList,
      @Nullable RexNode condition,
      final RelDataType outputRowType,
      boolean normalize,
      @Nullable RexSimplify simplify) {
    // 在基础构造中，会把 inputRowType、rexBuilder 和 simplify 绑定到实例属性上，
    // 并且自动将输入行（Input Row）的所有原始字段作为 RexInputRef 注册到 exprList 的最前排（完成基底占位）。
    this(inputRowType, rexBuilder, simplify);

    // Create a shuttle for registering input expressions.
    // RegisterMidputShuttle 是 Calcite 专门在这个阶段使用的一个内部穿梭器。它的名字很有趣（Midput，介于 Input 和 Output 之间）。
    // 因为传入的 exprList 本身可能已经是一个平铺的、带有局部引用（RexLocalRef）的列表。这个穿梭器在遍历表达式时，如果碰到 RexLocalRef，它知道该去传入的 exprList 参数的对应索引处去寻找真正的底层表达式，从而帮 Builder 重新建立去重映射表（exprMap）。
    final RexShuttle shuttle =
        new RegisterMidputShuttle(true, exprList);

    // If we are not normalizing, register all internal expressions. If we
    // are normalizing, expressions will be registered if and when they are
    // first used.
    // !normalize（不规范化）：说明我们想要原封不动地全盘继承过去的结构。于是调用 shuttle.visitEach(exprList)，不管三七二十一，
    // 把旧程序里所有的公共子表达式通通在全新的 Builder 里注册一遍，哪怕某些表达式其实后续根本没被投影或条件引用（保留死代码）。
    if (!normalize) {
      shuttle.visitEach(exprList);
    }
    // 旧程序中的投影或条件，其参数往往只是一个扁平的引用（如 $5）。如果我们要对它进行逻辑简化（Simplify），简化器必须看到这个表达式的完整树状全貌（比如知道 $5 实际上代表 a + b）。
    // expander 的工作就是把嵌套的 RexLocalRef 顺藤摸瓜地重新展开（Expand）成一棵完整的、没有被平铺的多层表达式树。
    final RexShuttle expander = new RexProgram.ExpansionShuttle(exprList);

    // Register project expressions
    // and create a named project item.
    // 重构并优化投影列表（Projects）
    final List<RelDataTypeField> fieldList = outputRowType.getFieldList();
    // 将传入的投影表达式列表和输出行的字段元数据一一配对绑定（保证表达式和它的别名能对上）。
    for (Pair<? extends RexNode, RelDataTypeField> pair
        : Pair.zip(projectList, fieldList)) {
      final RexNode project;
      // 如果启用了简化器（simplify != null），
      // 先用 pair.left.accept(expander) 把投影表达式打回树状原型，然后喂给 simplify.simplify(...) 剔除无效逻辑（例如把树状的 x + 0 简化为 x）。
      if (simplify != null) {
        project = simplify.simplify(pair.left.accept(expander));
      } else {
        project = pair.left;
      }
      final String name = pair.right.getName();
      final RexLocalRef ref = (RexLocalRef) project.accept(shuttle);
      // 将这个最终引用的索引和别名正式登记到新 Builder 的投影大名单中。
      addProject(ref.getIndex(), name);
    }

    // Register the condition, if there is one.
    // 重构并优化过滤条件（Condition）
    if (condition != null) {
      if (simplify != null) {
        // // 1. 展开、强转布尔并精简
        condition =
            simplify.simplify(
                rexBuilder.makeCall(SqlStdOperatorTable.IS_TRUE,
                    condition.accept(expander)));
        // 2. 恒真优化
        if (condition.isAlwaysTrue()) {
          condition = null;
        }
      }
      if (condition != null) {
        final RexLocalRef ref = (RexLocalRef) condition.accept(shuttle);
        addCondition(ref);
      }
    }
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Returns whether assertions are enabled in this class.
   */
  private static boolean assertionsAreEnabled() {
    boolean assertionsEnabled = false;
    //noinspection AssertWithSideEffects
    assert assertionsEnabled = true;
    return assertionsEnabled;
  }

  private void validate(final RexNode expr, final int fieldOrdinal) {
    final RexVisitor<Void> validator =
        new RexVisitorImpl<Void>(true) {
          @Override public Void visitInputRef(RexInputRef input) {
            final int index = input.getIndex();
            final List<RelDataTypeField> fields =
                inputRowType.getFieldList();
            if (index < fields.size()) {
              final RelDataTypeField inputField = fields.get(index);
              if (input.getType() != inputField.getType()) {
                throw new AssertionError("in expression " + expr
                    + ", field reference " + input + " has inconsistent type");
              }
            } else {
              if (index >= fieldOrdinal) {
                throw new AssertionError("in expression " + expr
                    + ", field reference " + input + " is out of bounds");
              }
              RexNode refExpr = exprList.get(index);
              if (refExpr.getType() != input.getType()) {
                throw new AssertionError("in expression " + expr
                    + ", field reference " + input + " has inconsistent type");
              }
            }
            return null;
          }
        };
    expr.accept(validator);
  }

  /**
   * Adds a project expression to the program.
   *
   * <p>The expression specified in terms of the input fields. If not, call
   * {@link #registerOutput(RexNode)} first.
   *
   * @param expr Expression to add
   * @param name Name of field in output row type; if null, a unique name will
   *             be generated when the program is created
   * @return the ref created
   */
  // 接收一个基于“输入视角（Input Fields）”定义的复杂表达式以及一个别名，将其打平注册到公共表达式池中，并正式声明为当前程序（RexProgram）的一个最终输出投影字段。
  // 参数 expr (RexNode)：想要作为投影（即 SQL 中 SELECT 后面跟着的计算列）输出的表达式。
  // 参数 name (@Nullable String)：该输出投影字段的别名（即 SQL 中的 AS alias_name）。如果传入 null，Builder 在最后生成完整程序时，会自动为其计算并补全一个形如 $0, $1 的全局唯一虚拟名字。
  public RexLocalRef addProject(RexNode expr, @Nullable String name) {
    // 将用户传进来的这棵可能非常复杂的表达式树 expr（例如 (a + b) * c），提交给核心中转引擎 registerInput。
    // 内部会启动穿梭器深度扫描 expr。
    final RexLocalRef ref = registerInput(expr);
    // 将索引绑定到输出投影名单
    return addProject(ref.getIndex(), name);
  }

  /**
   * Adds a projection based upon the <code>index</code>th expression.
   *
   * @param ordinal Index of expression to project
   * @param name    Name of field in output row type; if null, a unique name
   *                will be generated when the program is created
   * @return the ref created
   */
  public RexLocalRef addProject(int ordinal, final @Nullable String name) {
    final RexLocalRef ref = localRefList.get(ordinal);
    projectRefList.add(ref);
    projectNameList.add(name);
    return ref;
  }

  /**
   * Adds a project expression to the program at a given position.
   *
   * <p>The expression specified in terms of the input fields. If not, call
   * {@link #registerOutput(RexNode)} first.
   *
   * @param at   Position in project list to add expression
   * @param expr Expression to add
   * @param name Name of field in output row type; if null, a unique name will
   *             be generated when the program is created
   * @return the ref created
   */
  public RexLocalRef addProject(int at, RexNode expr, String name) {
    final RexLocalRef ref = registerInput(expr);
    projectRefList.add(at, ref);
    projectNameList.add(at, name);
    return ref;
  }

  /**
   * Adds a projection based upon the <code>index</code>th expression at a
   * given position.
   *
   * @param at      Position in project list to add expression
   * @param ordinal Index of expression to project
   * @param name    Name of field in output row type; if null, a unique name
   *                will be generated when the program is created
   * @return the ref created
   */
  public RexLocalRef addProject(int at, int ordinal, final String name) {
    return addProject(
        at,
        localRefList.get(ordinal),
        name);
  }

  /**
   * Sets the condition of the program.
   *
   * <p>The expression must be specified in terms of the input fields. If
   * not, call {@link #registerOutput(RexNode)} first.
   */
  // 主要职责是为当前的程序块（RexProgram）添加、追加或合并过滤条件（类似于 SQL 中的 WHERE 或 HAVING 子句）。
  // RexNode expr：代表要添加的行表达式节点。
  // 语义要求：正如代码注释所说，这个表达式在传入时必须是基于原始物理输入字段（Input Fields）进行描述的（例如引用关系算子的列 $0 > 10）。
  // 为什么？：因为该方法内部使用的是我们前面介绍过的 registerInput(expr)（其底层会激活 RegisterInputShuttle 访问器）。如果传入的表达式带有未被消解的、代表上层或中间层语义的变量，编译时将会直接崩溃。
  public void addCondition(RexNode expr) {
    assert expr != null;
    // 将当前类中记录的条件指针（this.conditionRef）赋值给一个局部变量 conditionRef，以便后续进行高效的空值判断与状态比对。
    RexLocalRef conditionRef = this.conditionRef;
    if (conditionRef == null) {
      // 调用 registerInput(expr) 方法。
      // 这会拉起 RegisterInputShuttle，将这个表达式树进行去重、平铺并正式注册到 exprList 池中，最终返回一个指向它的局部指针（RexLocalRef）。
      this.conditionRef = conditionRef = registerInput(expr);
    } else {
      // AND the new condition with the existing condition.
      // If the new condition is identical to the existing condition, skip it.
      // 如果程序之前已经存在了过滤条件（例如之前已经添加了 age > 18，现在又来了一个 status = 'ACTIVE'），代码就会进入 else 分支进行逻辑追加。
      RexLocalRef ref = registerInput(expr);
      if (!ref.equals(conditionRef)) {
        // 当判定新旧条件不同时，采用逻辑与（AND）将两个条件强行熔焊在一起，升级为全新的全局条件。
        this.conditionRef =
            registerInput(
                rexBuilder.makeCall(
                    SqlStdOperatorTable.AND,
                    conditionRef,
                    ref));
      }
    }
  }

  /**
   * Registers an expression in the list of common sub-expressions, and
   * returns a reference to that expression.
   *
   * <p>The expression must be expressed in terms of the <em>inputs</em> of
   * this program.
   */
  // 接收一个基于“输入字段引用（RexInputRef）”构建的表达式，
  // 通过专用的穿梭器（Shuttle）对其进行自底向上的扫描、边界校验与扁平化去重，最终将其转化为一个安全的、纯平的局部引用（RexLocalRef）。
  public RexLocalRef registerInput(RexNode expr) {
    // 创建一个 RegisterInputShuttle 实例，并向其构造函数传入 true，激活强校验模式。
    final RexShuttle shuttle = new RegisterInputShuttle(true);
    // 让传入的表达式树 expr 接受 shuttle 的访问（标准的访问者模式：Visitor Pattern）。
    final RexNode ref = expr.accept(shuttle);
    // 将穿梭器返回的 RexNode 强制类型转换为其子类 RexLocalRef 并作为结果返回。
    return (RexLocalRef) ref;
  }

  /**
   * Converts an expression expressed in terms of the <em>outputs</em> of this
   * program into an expression expressed in terms of the <em>inputs</em>,
   * registers it in the list of common sub-expressions, and returns a
   * reference to that expression.
   *
   * @param expr Expression to register
   */
  public RexLocalRef registerOutput(RexNode expr) {
    final RexShuttle shuttle = new RegisterOutputShuttle(exprList);
    final RexNode ref = expr.accept(shuttle);
    return (RexLocalRef) ref;
  }

  /**
   * Registers an expression in the list of common sub-expressions, and
   * returns a reference to that expression.
   *
   * @param expr Expression to register
   */
  // 本质是一个带逻辑简化功能且具有“追根溯源”机制的公共子表达式（CSE）去重登记引擎。
  // 参数 expr (RexNode)：要注册的行表达式节点（比如一个常量 10、一个字段引用 RexInputRef，或者一个函数调用 a + b）。这个表达式树可能是任意深度的。
  // 返回值 (RexLocalRef)：返回一个局部引用节点。它像一个“指针”或“Token”，内部只包含一个整数索引（index）和数据类型（type），代表该表达式在平铺列表 exprList 中的最终位置。
  private RexLocalRef registerInternal(RexNode expr) {
    // 实例化一个 Calcite 的表达式简化器（RexSimplify）
    final RexSimplify simplify =
        new RexSimplify(rexBuilder, RelOptPredicateList.EMPTY, RexUtil.EXECUTOR);
    // 在注册前，对传入的表达式树执行常数折叠和逻辑简化，同时严格保证简化前后的数据类型绝对不发生改变。
    // 示例：如果传入的 expr 是 1 + 1，经过这一行后会被直接削平简化为常量 2；如果是 x AND true，会被简化为 x。这从源头上阻止了冗余代码进入程序。
    expr = simplify.simplifyPreservingType(expr);

    RexLocalRef ref;
    final Pair<RexNode, String> key;
    // 检查简化后的表达式本身是不是已经是一个 RexLocalRef（局部引用）了。
    if (expr instanceof RexLocalRef) {
      // 如果是，说明它已经是别人的指针了，不需要生成唯一的 Key，直接把 key 设为 null，并将 ref 指向它自身。
      key = null;
      ref = (RexLocalRef) expr;
    } else {
      // 如果它是一个实质性的复杂表达式（如 a + b），则调用工具方法将表达式包装并序列化为一个唯一的双亲键 Pair<RexNode, String>（包含了表达式的结构与类型字符串）。
      // 然后去全局去重表 exprMap 中进行 get(key) 检索。
      key = RexUtil.makeKey(expr);
      ref = exprMap.get(key);
    }
    // 如果在 exprMap 里没找到，说明这是一个全新的公共子表达式，需要走入库流程。
    if (ref == null) {
      if (validating) {
        // 如果开启了断言校验开关，在正式入库前，紧急调用 validate 方法，对这个表达式内部的所有子节点的边界和类型一致性做一次全方位静态体检，防止带病入库
        validate(
            expr,
            exprList.size());
      }

      // Add expression to list, and return a new reference to it.
      // 调用 addExpr 方法，将这个表达式正式追加到平铺主容器 exprList 的末尾（它的索引就是当前列表的旧 size），并为它定制生成一个专属的 RexLocalRef。
      ref = addExpr(expr);
      exprMap.put(requireNonNull(key, "key"), ref);
    }

    for (;;) {
      // 拿到当前引用的目标索引。
      int index = ref.index;
      // 主列表 exprList 里把这个位置存放的真实节点捞出来。
      final RexNode expr2 = exprList.get(index);
      if (expr2 instanceof RexLocalRef) {
        // 惊奇地发现，这个位置存的居然不是具体的算术表达式，而是另一个引用指针（说明发生了指针重定向或代理别名）。
        // 如果是这样，说明还没到根源。把当前的 ref 顺藤摸瓜替换成新的引用指针，继续下一次循环。
        ref = (RexLocalRef) expr2;
      } else {
        // 如果捞出来的 expr2 是一个实实在在的运算节点（如 RexCall 或 RexLiteral），说明已经到了最纯正的物理源头。此时高高兴兴地返回这个彻底平铺化的、最底层的 ref，退出函数。
        return ref;
      }
    }
  }

  /**
   * Adds an expression to the list of common expressions, and returns a
   * reference to the expression. <b>DOES NOT CHECK WHETHER THE EXPRESSION
   * ALREADY EXISTS</b>.
   *
   * @param expr Expression
   * @return Reference to expression
   */
  // 核心作用是：不做任何去重校验，强行将一个表达式追加到程序容器的末尾，并为它发放一张专属的“局部索引准考证”（RexLocalRef）。
  public RexLocalRef addExpr(RexNode expr) {
    // 声明一个用于承载返回值的局部引用变量 ref。
    // 这个引用本质上就是后面用来代表该表达式的“Token”指针。
    RexLocalRef ref;
    // 获取当前主容器 exprList 的元素总数，并将其作为即将插入的表达式的目标索引（Index）。
    final int index = exprList.size();
    // 将传入的复杂表达式节点 expr（如一个具体的 RexCall 或 RexLiteral）真正塞进平铺的中央容器 exprList 的末尾。
    exprList.add(expr);
    // 为刚刚存进去的表达式定制化创建一个强类型的局部引用对象 RexLocalRef。
    // index：刚刚锁定的数组下标。未来任何人拿着这个 ref，只要看一眼里面的 index，就能瞬间去 exprList 对应的位置捞出原始表达式。
    ref =
        new RexLocalRef(
            index,
            expr.getType());
    // 将新生成的 ref 指针也追加到 localRefList 容器中。
    // localRefList 在 Builder 内部与 exprList 始终保持着严格的等长、一比一对应关系。
    // 其目的是为了让框架在后续根据索引查找 RexLocalRef 时，能够享受到 $O(1)$ 的极致速度，避免重复创建对象。
    localRefList.add(ref);
    return ref;
  }

  /**
   * Converts the state of the program builder to an immutable program,
   * normalizing in the process.
   *
   * <p>It is OK to call this method, modify the program specification (by
   * adding projections, and so forth), and call this method again.
   */
  public RexProgram getProgram() {
    return getProgram(true);
  }

  /**
   * Converts the state of the program builder to an immutable program.
   *
   * <p>It is OK to call this method, modify the program specification (by
   * adding projections, and so forth), and call this method again.
   *
   * @param normalize Whether to normalize
   */

  // 核心作用是：将当前构建器（RexProgramBuilder）中所维护的、可变的、零散的中间状态（如表达式池、投影列、过滤条件等），
  // 最终固化、组装并返回一个不可变的、生产就绪的逻辑程序块对象——RexProgram。
  // boolean normalize：代表是否在生成程序前开启“规范化/常数折叠优化”。
  // 如果传 true：Calcite 会在最终打包前，对当前的表达式树进行最后一轮大洗礼（如：把 1 + 1 直接优化变成 2，或者消除掉永远为真的 1 = 1 过滤条件，以及消除完全重复的无用表达式）。
  // 如果传 false：直接根据当前的中间状态按原样打包，不进行额外的优化推导。
  public RexProgram getProgram(boolean normalize) {
    assert projectRefList.size() == projectNameList.size();

    // Make sure all fields have a name.
    generateMissingNames();
    RelDataType outputRowType = computeOutputRowType();

    if (normalize) {
      return create(
          rexBuilder,
          inputRowType,
          exprList,
          projectRefList,
          conditionRef,
          outputRowType,
          true)
          .getProgram(false);
    }

    return new RexProgram(
        inputRowType,
        exprList,
        projectRefList,
        conditionRef,
        outputRowType);
  }

  private RelDataType computeOutputRowType() {
    return RexUtil.createStructType(rexBuilder.typeFactory, projectRefList,
        projectNameList, null);
  }

  private void generateMissingNames() {
    int i = -1;
    int j = 0;
    for (String projectName : projectNameList) {
      ++i;
      if (projectName == null) {
        while (true) {
          final String candidateName = "$" + j++;
          if (!projectNameList.contains(candidateName)) {
            projectNameList.set(i, candidateName);
            break;
          }
        }
      }
    }
  }

  /**
   * Creates a program builder and initializes it from an existing program.
   *
   * <p>Calling {@link #getProgram()} immediately after creation will return a
   * program equivalent (in terms of external behavior) to the existing
   * program.
   *
   * <p>The existing program will not be changed. (It cannot: programs are
   * immutable.)
   *
   * @param program    Existing program
   * @param rexBuilder Rex builder
   * @param normalize  Whether to normalize
   * @return A program builder initialized with an equivalent program
   */
  public static RexProgramBuilder forProgram(
      RexProgram program,
      RexBuilder rexBuilder,
      boolean normalize) {
    assert program.isValid(Litmus.THROW, null);
    final RelDataType inputRowType = program.getInputRowType();
    final List<RexLocalRef> projectRefs = program.getProjectList();
    final RexLocalRef conditionRef = program.getCondition();
    final List<RexNode> exprs = program.getExprList();
    final RelDataType outputRowType = program.getOutputRowType();
    return create(
        rexBuilder,
        inputRowType,
        exprs,
        projectRefs,
        conditionRef,
        outputRowType,
        normalize,
        false);
  }

  /**
   * Creates a program builder with the same contents as a program.
   *
   * <p>If {@code normalize}, converts the program to canonical form. In
   * canonical form, in addition to the usual constraints:
   *
   * <ul>
   * <li>The first N internal expressions are {@link RexInputRef}s to the N
   * input fields;
   * <li>Subsequent internal expressions reference only preceding expressions;
   * <li>Arguments to {@link RexCall}s must be {@link RexLocalRef}s (that is,
   * expressions must have maximum depth 1)
   * </ul>
   *
   * <p>there are additional constraints:
   *
   * <ul>
   * <li>Expressions appear in the left-deep order they are needed by
   * the projections and (if present) the condition. Thus, expression N+1
   * is the leftmost argument (literal or or call) in the expansion of
   * projection #0.
   * <li>There are no duplicate expressions
   * <li>There are no unused expressions
   * </ul>
   *
   * @param rexBuilder     Rex builder
   * @param inputRowType   Input row type
   * @param exprList       Common expressions
   * @param projectList    Projections
   * @param condition      Condition, or null
   * @param outputRowType  Output row type
   * @param normalize      Whether to normalize
   * @param simplify       Whether to simplify expressions
   * @return A program builder
   */
  public static RexProgramBuilder create(
      RexBuilder rexBuilder,
      final RelDataType inputRowType,
      final List<RexNode> exprList,
      final List<? extends RexNode> projectList,
      final @Nullable RexNode condition,
      final RelDataType outputRowType,
      boolean normalize,
      @Nullable RexSimplify simplify) {
    return new RexProgramBuilder(rexBuilder, inputRowType, exprList,
        projectList, condition, outputRowType, normalize, simplify);
  }

  @Deprecated // to be removed before 2.0
  public static RexProgramBuilder create(
      RexBuilder rexBuilder,
      final RelDataType inputRowType,
      final List<RexNode> exprList,
      final List<? extends RexNode> projectList,
      final @Nullable RexNode condition,
      final RelDataType outputRowType,
      boolean normalize,
      boolean simplify_) {
    RexSimplify simplify = null;
    if (simplify_) {
      simplify =
          new RexSimplify(rexBuilder, RelOptPredicateList.EMPTY, RexUtil.EXECUTOR);
    }
    return new RexProgramBuilder(rexBuilder, inputRowType, exprList,
        projectList, condition, outputRowType, normalize, simplify);
  }

  @Deprecated // to be removed before 2.0
  public static RexProgramBuilder create(
      RexBuilder rexBuilder,
      final RelDataType inputRowType,
      final List<RexNode> exprList,
      final List<? extends RexNode> projectList,
      final @Nullable RexNode condition,
      final RelDataType outputRowType,
      boolean normalize) {
    return create(rexBuilder, inputRowType, exprList, projectList, condition,
        outputRowType, normalize, null);
  }

  /**
   * Creates a program builder with the same contents as a program, applying a
   * shuttle first.
   *
   * <p>TODO: Refactor the above create method in terms of this one.
   *
   * @param rexBuilder     Rex builder
   * @param inputRowType   Input row type
   * @param exprList       Common expressions
   * @param projectRefList Projections
   * @param conditionRef   Condition, or null
   * @param outputRowType  Output row type
   * @param shuttle        Shuttle to apply to each expression before adding it
   *                       to the program builder
   * @param updateRefs     Whether to update references that changes as a result
   *                       of rewrites made by the shuttle
   * @return A program builder
   */
  public static RexProgramBuilder create(
      RexBuilder rexBuilder,
      final RelDataType inputRowType,
      final List<RexNode> exprList,
      final List<RexLocalRef> projectRefList,
      final @Nullable RexLocalRef conditionRef,
      final RelDataType outputRowType,
      final RexShuttle shuttle,
      final boolean updateRefs) {
    final RexProgramBuilder progBuilder =
        new RexProgramBuilder(inputRowType, rexBuilder);
    progBuilder.add(
        exprList,
        projectRefList,
        conditionRef,
        outputRowType,
        shuttle,
        updateRefs);
    return progBuilder;
  }

  @Deprecated // to be removed before 2.0
  public static RexProgram normalize(
      RexBuilder rexBuilder,
      RexProgram program) {
    return program.normalize(rexBuilder, null);
  }

  /**
   * Adds a set of expressions, projections and filters, applying a shuttle
   * first.
   *
   * @param exprList       Common expressions
   * @param projectRefList Projections
   * @param conditionRef   Condition, or null
   * @param outputRowType  Output row type
   * @param shuttle        Shuttle to apply to each expression before adding it
   *                       to the program builder
   * @param updateRefs     Whether to update references that changes as a result
   *                       of rewrites made by the shuttle
   */
  private void add(
      List<RexNode> exprList,
      List<RexLocalRef> projectRefList,
      @Nullable RexLocalRef conditionRef,
      final RelDataType outputRowType,
      RexShuttle shuttle,
      boolean updateRefs) {
    final List<RelDataTypeField> outFields = outputRowType.getFieldList();
    final RexShuttle registerInputShuttle = new RegisterInputShuttle(false);

    // For each common expression, first apply the user's shuttle, then
    // register the result.
    // REVIEW jpham 28-Apr-2006: if the user shuttle rewrites an input
    // expression, then input references may change
    List<RexLocalRef> newRefs = new ArrayList<>(exprList.size());
    RexShuttle refShuttle = new UpdateRefShuttle(newRefs);
    int i = 0;
    for (RexNode expr : exprList) {
      RexNode newExpr = expr;
      if (updateRefs) {
        newExpr = expr.accept(refShuttle);
      }
      newExpr = newExpr.accept(shuttle);
      newRefs.add(
          i++,
          (RexLocalRef) newExpr.accept(registerInputShuttle));
    }
    i = -1;
    for (RexLocalRef oldRef : projectRefList) {
      ++i;
      RexLocalRef ref = oldRef;
      if (updateRefs) {
        ref = (RexLocalRef) oldRef.accept(refShuttle);
      }
      ref = (RexLocalRef) ref.accept(shuttle);
      this.projectRefList.add(ref);
      final String name = outFields.get(i).getName();
      assert name != null;
      projectNameList.add(name);
    }
    if (conditionRef != null) {
      if (updateRefs) {
        conditionRef = (RexLocalRef) conditionRef.accept(refShuttle);
      }
      conditionRef = (RexLocalRef) conditionRef.accept(shuttle);
      addCondition(conditionRef);
    }
  }

  /**
   * Merges two programs together, and normalizes the result.
   *
   * @param topProgram    Top program. Its expressions are in terms of the
   *                      outputs of the bottom program.
   * @param bottomProgram Bottom program. Its expressions are in terms of the
   *                      result fields of the relational expression's input
   * @param rexBuilder    Rex builder
   * @return Merged program
   * @see #mergePrograms(RexProgram, RexProgram, RexBuilder, boolean)
   */
  public static RexProgram mergePrograms(
      RexProgram topProgram,
      RexProgram bottomProgram,
      RexBuilder rexBuilder) {
    return mergePrograms(topProgram, bottomProgram, rexBuilder, true);
  }

  /**
   * Merges two programs together.
   *
   * <p>All expressions become common sub-expressions. For example, the query
   *
   * <blockquote><pre>SELECT x + 1 AS p, x + y AS q FROM (
   *   SELECT a + b AS x, c AS y
   *   FROM t
   *   WHERE c = 6)}</pre></blockquote>
   *
   * <p>would be represented as the programs
   *
   * <blockquote><pre>
   *   Calc:
   *       Projects={$2, $3},
   *       Condition=null,
   *       Exprs={$0, $1, $0 + 1, $0 + $1})
   *   Calc(
   *       Projects={$3, $2},
   *       Condition={$4}
   *       Exprs={$0, $1, $2, $0 + $1, $2 = 6}
   * </pre></blockquote>
   *
   * <p>The merged program is
   *
   * <blockquote><pre>
   *   Calc(
   *      Projects={$4, $5}
   *      Condition=$6
   *      Exprs={0: $0       // a
   *             1: $1        // b
   *             2: $2        // c
   *             3: ($0 + $1) // x = a + b
   *             4: ($3 + 1)  // p = x + 1
   *             5: ($3 + $2) // q = x + y
   *             6: ($2 = 6)  // c = 6
   * </pre></blockquote>
   *
   * <p>Another example:
   *
   * <blockquote>
   * <pre>SELECT *
   * FROM (
   *   SELECT a + b AS x, c AS y
   *   FROM t
   *   WHERE c = 6)
   * WHERE x = 5</pre>
   * </blockquote>
   *
   * <p>becomes
   *
   * <blockquote>
   * <pre>SELECT a + b AS x, c AS y
   * FROM t
   * WHERE c = 6 AND (a + b) = 5</pre>
   * </blockquote>
   *
   * @param topProgram    Top program. Its expressions are in terms of the
   *                      outputs of the bottom program.
   * @param bottomProgram Bottom program. Its expressions are in terms of the
   *                      result fields of the relational expression's input
   * @param rexBuilder    Rex builder
   * @param normalize     Whether to convert program to canonical form
   * @return Merged program
   */
  public static RexProgram mergePrograms(
      RexProgram topProgram,
      RexProgram bottomProgram,
      RexBuilder rexBuilder,
      boolean normalize) {
    // Initialize a program builder with the same expressions, outputs
    // and condition as the bottom program.
    assert bottomProgram.isValid(Litmus.THROW, null);
    assert topProgram.isValid(Litmus.THROW, null);
    final RexProgramBuilder progBuilder =
        RexProgramBuilder.forProgram(bottomProgram, rexBuilder, false);

    // Drive from the outputs of the top program. Register each expression
    // used as an output.
    final List<RexLocalRef> projectRefList =
        progBuilder.registerProjectsAndCondition(topProgram);

    // Switch to the projects needed by the top program. The original
    // projects of the bottom program are no longer needed.
    progBuilder.clearProjects();
    final RelDataType outputRowType = topProgram.getOutputRowType();
    for (Pair<RexLocalRef, String> pair
        : Pair.zip(projectRefList, outputRowType.getFieldNames(), true)) {
      progBuilder.addProject(pair.left, pair.right);
    }
    RexProgram mergedProg = progBuilder.getProgram(normalize);
    assert mergedProg.isValid(Litmus.THROW, null);
    assert mergedProg.getOutputRowType() == topProgram.getOutputRowType();
    return mergedProg;
  }

  private List<RexLocalRef> registerProjectsAndCondition(RexProgram program) {
    final List<RexNode> exprList = program.getExprList();
    final List<RexLocalRef> projectRefList = new ArrayList<>();
    final RexShuttle shuttle = new RegisterOutputShuttle(exprList);

    // For each project, lookup the expr and expand it so it is in terms of
    // bottomCalc's input fields
    for (RexLocalRef topProject : program.getProjectList()) {
      final RexNode topExpr = exprList.get(topProject.getIndex());
      final RexLocalRef expanded = (RexLocalRef) topExpr.accept(shuttle);

      // Remember the expr, but don't add to the project list yet.
      projectRefList.add(expanded);
    }

    // Similarly for the condition.
    final RexLocalRef topCondition = program.getCondition();
    if (topCondition != null) {
      final RexNode topExpr = exprList.get(topCondition.getIndex());
      final RexLocalRef expanded = (RexLocalRef) topExpr.accept(shuttle);

      addCondition(registerInput(expanded));
    }
    return projectRefList;
  }

  /**
   * Removes all project items.
   *
   * <p>After calling this method, you may need to re-normalize.
   */
  public void clearProjects() {
    projectRefList.clear();
    projectNameList.clear();
  }

  /**
   * Clears the condition.
   *
   * <p>After calling this method, you may need to re-normalize.
   */
  public void clearCondition() {
    conditionRef = null;
  }

  /**
   * Adds a project item for every input field.
   *
   * <p>You cannot call this method if there are other project items.
   */
  public void addIdentity() {
    assert projectRefList.isEmpty();
    for (RelDataTypeField field : inputRowType.getFieldList()) {
      addProject(
          new RexInputRef(
              field.getIndex(),
              field.getType()),
          field.getName());
    }
  }

  /**
   * Creates a reference to a given input field.
   *
   * @param index Ordinal of input field, must be less than the number of
   *              fields in the input type
   * @return Reference to input field
   */
  public RexLocalRef makeInputRef(int index) {
    final List<RelDataTypeField> fields = inputRowType.getFieldList();
    assert index < fields.size();
    final RelDataTypeField field = fields.get(index);
    return new RexLocalRef(
        index,
        field.getType());
  }

  /**
   * Returns the row type of the input to the program.
   */
  public RelDataType getInputRowType() {
    return inputRowType;
  }

  /**
   * Returns the list of project expressions.
   */
  public List<RexLocalRef> getProjectList() {
    return projectRefList;
  }

  //~ Inner Classes ----------------------------------------------------------

  /** Shuttle that visits a tree of {@link RexNode} and registers them
   * in a program. */
  // RegisterShuttle 的核心作用是：通过深度遍历一棵表达式树（RexNode），
  // 将树中的各个子节点或整棵树“注册（Register）”到一个扁平化的、公共的表达式列表（通常是 RexProgram 的底层列表）中。
  // 解决的核心痛点：表达式复用与扁平化
  // 在编译优化中，为了避免重复计算（即公共子表达式消除，Common Subexpression Elimination），Calcite 倾向于把一棵复杂的、多层嵌套的树状表达式，拍平成一个线性的“代码程序块（Program）”。
  // 例如，表达式 (a + b) * (a + b) 是一棵树。通过 RegisterShuttle 遍历后：
  // 它首先发现并注册 a 和 b。
  // 接着处理 a + b，将其注册并分配一个局部引用编号（如 $t2）。
  // 遇到第二个 a + b 时，注册器发现它已经存在了，直接复用 $t2。
  // 最后注册乘法 $t2 * $t2。
  // 它先通过 super.visitXxx(node) 一直向下钻，确保一个节点的所有子节点都已经先被遍历并重写（注册）完毕。
  private abstract class RegisterShuttle extends RexShuttle {
    // 遍历并注册函数或操作符调用节点（如 a + b，x AND y）
    @Override public RexNode visitCall(RexCall call) {
      final RexNode expr = super.visitCall(call);
      return registerInternal(expr);
    }
    // 遍历并注册窗口聚合函数表达式（如 AVG(salary) OVER (...)）
    @Override public RexNode visitOver(RexOver over) {
      final RexNode expr = super.visitOver(over);
      return registerInternal(expr);
    }

    @Override public RexNode visitLiteral(RexLiteral literal) {
      final RexNode expr = super.visitLiteral(literal);
      return registerInternal(expr);
    }

    @Override public RexNode visitFieldAccess(RexFieldAccess fieldAccess) {
      final RexNode expr = super.visitFieldAccess(fieldAccess);
      return registerInternal(expr);
    }

    @Override public RexNode visitDynamicParam(RexDynamicParam dynamicParam) {
      final RexNode expr = super.visitDynamicParam(dynamicParam);
      return registerInternal(expr);
    }

    @Override public RexNode visitCorrelVariable(RexCorrelVariable variable) {
      final RexNode expr = super.visitCorrelVariable(variable);
      return registerInternal(expr);
    }

    @Override public RexNode visitLambda(RexLambda lambda) {
      super.visitLambda(lambda);
      return registerInternal(lambda);
    }
  }

  /**
   * Shuttle which walks over an expression, registering each sub-expression.
   * Each {@link RexInputRef} is assumed to refer to an <em>input</em> of the
   * program.
   */
  // RegisterInputShuttle 的主要职责是：在构建或规整 RexProgram 的过程中，对表达式树中的“输入引用（RexInputRef）”和“局部引用（RexLocalRef）”进行边界校验、类型一致性检查，并将它们安全地映射（解析）回底层程序真实的输入源或已注册的局部表达式中。
  // 特异化拦截处理：它专门重写了 visitInputRef 和 visitLocalRef。
  // 当遇到 RexInputRef 时，它会检查这个外部列索引有没有越界，类型对不对，然后将其转化为对应的局部引用。
  // 当遇到 RexLocalRef 时，它会通过一个 while(true) 循环沿着引用链向源头追溯，防止发生“循环引用”或“前序引用了后序未定义变量”的逻辑错误，并将其最终规整到合法的注册节点上。
  private class RegisterInputShuttle extends RegisterShuttle {
    // 控制是否开启严格的运行时断言校验（Validation Flag）
    // 当 valid 为 true 时，Shuttle 在遍历过程中会变得极其严苛，会对遇到的每一个输入和局部引用进行边界、索引以及数据类型的全方位强行断言检查（assert）。这通常用在关系代数重写的边界验证阶段。
    private final boolean valid;

    protected RegisterInputShuttle(boolean valid) {
      this.valid = valid;
    }
    // 遍历并处理外部输入列引用节点（RexInputRef）
    @Override public RexNode visitInputRef(RexInputRef input) {
      // 获得当前输入引用的物理列索引 final int index = input.getIndex();（例如 $0 代表物理第一列）。
      final int index = input.getIndex();
      if (valid) {
        // The expression should already be valid. Check that its
        // index is within bounds.
        // 边界检查：检查该列索引是否小于 0，或者是否大于等于当前程序被允许的外部输入总列数（inputRowType.getFieldCount()）。如果不合法，直接抛出断言错误 "out of range"。
        if ((index < 0) || (index >= inputRowType.getFieldCount())) {
          assert false
              : "RexInputRef index " + index + " out of range 0.."
              + (inputRowType.getFieldCount() - 1);
        }

        // Check that the type is consistent with the referenced
        // field. If it is an object type, the rules are different, so
        // skip the check.
        // 类型一致性检查：如果是结构体/对象类型（isStruct()），则跳过（因为对象类型的子类型规则较复杂）。
        // 如果是普通标量类型，利用 RelOptUtil.eq 强行比对：当前表达式声明的类型与外部输入行元数据中该索引处真正的字段类型是否完全一致。不一致则抛出类型异常。
        assert input.getType().isStruct()
            || RelOptUtil.eq("type1", input.getType(),
                "type2", inputRowType.getFieldList().get(index).getType(),
                Litmus.THROW);
      }

      // Return a reference to the N'th expression, which should be
      // equivalent.
      final RexLocalRef ref = localRefList.get(index);
      return ref;
    }
    // 遍历并处理内部局部变量引用节点（RexLocalRef）
    @Override public RexNode visitLocalRef(RexLocalRef local) {
      if (valid) {
        // The expression should already be valid.
        // 强行断言 index >= 0 且 index < exprList.size()（确保该指针指向的代码行在当前已有的表达式池范围内）。
        final int index = local.getIndex();
        assert index >= 0 : index;
        assert index < exprList.size()
            : "index=" + index + ", exprList=" + exprList;
        assert RelOptUtil.eq(
            "expr type",
            exprList.get(index).getType(),
            "ref type",
            local.getType(),
            Litmus.THROW);
      }

      // Resolve the expression to an input.
      // 追根溯源链条（while (true) 核心循环）
      // 局部引用可能存在“套娃”现象。例如：局部引用 $t5 指向了 $t3，而 $t3 又真正指向了表达式 a + b。为了将程序彻底扁平化，必须追溯到源头。
      while (true) {
        final int index = local.getIndex();
        final RexNode expr = exprList.get(index);
        // 情况 A：如果发现 expr 居然还是一个 RexLocalRef，说明遇到引用嵌套。
        // 将指针向前推进：local = (RexLocalRef) expr;。
        if (expr instanceof RexLocalRef) {
          local = (RexLocalRef) expr;
          if (local.index >= index) {
            throw new AssertionError(
                "expr " + local + " references later expr " + local.index);
          }
        } else {
          // 情况 B：如果发现 expr 是一个具体的计算表达式（如 a + b 等实质节点而非指针）。
          // 说明已经追溯到了物理源头。代码在这里做了一层紧凑处理：直接调用外层的 registerInternal(local) 将这个最终的局部引用注册到当前的构建器中，并打破循环返回。
          // Add expression to the list, just so that subsequent
          // expressions don't get screwed up. This expression is
          // unused, so will be eliminated soon.
          return registerInternal(local);
        }
      }
    }
  }

  /**
   * Extension to {@link RegisterInputShuttle} which allows expressions to be
   * in terms of inputs or previous common sub-expressions.
   */
  // RegisterMidputShuttle 是 RexProgramBuilder 类内部的一个私有非静态内部类。它继承自我们上一步详细拆解的 RegisterInputShuttle。
  // 它的命名非常风趣且富有编译器语义：“Input”代表最底层的原始物理输入，“Output”代表最终的输出，而 “Midput”（中间输出） 则形象地代指程序在计算过程中产生的“中间变量”或“公共子表达式”。
  // RegisterMidputShuttle 的核心作用是：在表达式重写和扁平化期间，允许新加入的表达式不仅可以引用最底层的原始物理输入（Inputs），还可以直接引用那些在它之前已经计算好的“中间公共子表达式（Previous Common Sub-expressions）”，并自动将这些中间引用展开或递归注册到当前的上下文程序中。
  // 解决的核心痛点
  // 在复杂的 SQL 优化过程中（例如多次谓词下推或投影合并），我们经常会遇到“表达式嵌套引用中间变量”的情况。
  //假设在之前的优化步骤中，已经计算出了一个中间变量 $t0 = a + b。
  //现在又有了一个新的关系算子，它产生的表达式是 $t0 * c（即拿之前的中间结果乘以 c）。
  //此时，如果直接把 $t0 * c 交给普通的 RegisterInputShuttle 处理，它会因为在最底层的原始物理输入中找不到 $t0 而报错崩溃。
  //RegisterMidputShuttle 正是为了打破这个局限性而设计的：它充当了一座桥梁，使得 Calcite 在拍平表达式树时，能够动态识别并递归展开这些属于“过去时”的中间变量。
  private class RegisterMidputShuttle extends RegisterInputShuttle {
    // 存储当前程序所有已知的、前序计算完毕的中间表达式集合（公共子表达式池）
    // 当 Shuttle 遇到一个局部变量指针（RexLocalRef）时，它会把这个指针的索引（index）作为下标，直接去 localExprList 列表中把对应的那个真正的物理表达式（如 a + b）给捞出来。
    private final List<RexNode> localExprList;

    protected RegisterMidputShuttle(
        boolean valid,
        List<RexNode> localExprList) {
      super(valid);
      this.localExprList = localExprList;
    }
    // 拦截并特殊处理中间变量的局部引用指针（RexLocalRef）。
    @Override public RexNode visitLocalRef(RexLocalRef local) {
      // Convert a local ref into the common-subexpression it references.
      // 获取索引：
      // 首先通过 local.getIndex() 拿到当前指针指向的中间变量槽位编号 index。
      final int index = local.getIndex();
      return localExprList.get(index).accept(this);
    }
  }

  /**
   * Shuttle which walks over an expression, registering each sub-expression.
   * Each {@link RexInputRef} is assumed to refer to an <em>output</em> of the
   * program.
   */
  // RegisterOutputShuttle 的核心作用是：当需要对两个连续的 Project（投影算子）进行上下合并（Merge/Pull Up）时，负责遍历上层算子的表达式，将其中的“物理列引用（RexInputRef）”识别并映射为下层算子的“输出结果表达式（Outputs）”，从而将两层表达式消解融合成一层。
  // 概念背景与核心痛点
  // 在关系代数优化中，我们经常会遇到连续两个 Project 嵌套的情况：
  //下层 Project (算子 B)：输出两个字段，分别是 $t0 = a + b（第 0 列） 和 $t1 = c * 2（第 1 列）。
  //上层 Project (算子 A)：基于下层的输出进行计算，其表达式为：$input0 * 5。这里的 $input0 是一个 RexInputRef，它在语义上指向了下层算子的第 0 列输出。
  // 如果我们要把这两个 Project 合并成一个，就需要将上层的 $input0 * 5 转换为 (a + b) * 5。
  //RegisterOutputShuttle 正是为此而生：它假设在当前遍历的表达式中，遇到的每一个 RexInputRef 都不是底层的原始列，而是指代了前一个程序的“输出列（Output Column）”。
  private class RegisterOutputShuttle extends RegisterShuttle {
    // 存储下层（前序）程序中已经注册好的扁平表达式池。
    // 它和 projectRefList 配合使用。当通过输入索引映射到下层的局部指针后，可以用这个 localExprList 捞出指针背后真正的物理计算公式（如 a + b），用于递归展开。
    private final List<RexNode> localExprList;

    RegisterOutputShuttle(List<RexNode> localExprList) {
      super();
      this.localExprList = localExprList;
    }
    // 拦截并重写输入列引用（RexInputRef）。这是该类最核心的特殊设计。
    @Override public RexNode visitInputRef(RexInputRef input) {
      // This expression refers to the Nth project column. Lookup that
      // column and find out what common sub-expression IT refers to.
      // 这里的 index 代表上层算子在引用下层算子的第几列输出。
      // 核心关键：它直接去外层类的 projectRefList（项目引用列表，即下层算子的输出列投影映射表）中寻找。
      // 从而成功将原本含义为“上层输入”的 input，偷梁换柱成指向下层对应计算结果的局部指针 local。
      final int index = input.getIndex();
      final RexLocalRef local = projectRefList.get(index);
      assert RelOptUtil.eq(
          "type1",
          local.getType(),
          "type2",
          input.getType(),
          Litmus.THROW);
      return local;
    }
    // 拦截并处理内部局部变量引用节点（RexLocalRef）。
    // 当 visitInputRef 成功把输入转换成下层的 RexLocalRef 指针后，该方法会被紧接着触发。
    // 它根据指针的 index，从下层的公共表达式池 localExprList 中把真正的物理表达式（如 a + b）捞出来。
    @Override public RexNode visitLocalRef(RexLocalRef local) {
      // Convert a local ref into the common-subexpression it references.
      final int index = local.getIndex();
      return localExprList.get(index).accept(this);
    }
  }

  /**
   * Shuttle that rewires {@link RexLocalRef} using a list of updated
   * references.
   */
  private static class UpdateRefShuttle extends RexShuttle {
    private List<RexLocalRef> newRefs;

    private UpdateRefShuttle(List<RexLocalRef> newRefs) {
      this.newRefs = newRefs;
    }

    @Override public RexNode visitLocalRef(RexLocalRef localRef) {
      return newRefs.get(localRef.getIndex());
    }
  }
}
