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

import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.BlockStatement;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.linq4j.tree.Primitive;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The base implementation of strict aggregate function.
 *
 * @see org.apache.calcite.adapter.enumerable.RexImpTable.CountImplementor
 * @see org.apache.calcite.adapter.enumerable.RexImpTable.SumImplementor
 */
// 实现了 AggImplementor 接口，专门用来为 SQL 中的 “严格聚合函数（Strict Aggregate Functions）”（如 SUM、COUNT、MIN、MAX）生成底层的 Java 表达式代码。
// 在 SQL 标准中，“严格聚合函数”有一个共同的铁律：它们在运算时会自动忽略 NULL 值。
// 例如，SUM(c) 只累加 c IS NOT NULL 的行。更关键的是，如果输入的数据集是空的，或者所有行的值全都是 NULL（即空集/Empty Set），除了 COUNT 以外，
// 大多数严格聚合函数应该返回 NULL，而不是返回初始值 0。
// StrictAggImplementor 的核心作用就是将这套复杂的“空集追踪”与“自动过滤 NULL 行”的通用语义包装起来，形成一套标准的代码生成骨架（模板方法模式）：
// 自动生成 NULL 过滤与 FILTER 子句判定：在每行迭代时（implementAdd），它会自动生成 if (arg != null) 的 Java 条件分支。子类（如 SumImplementor）只需要关注非空情况下的累加逻辑（implementNotNullAdd）。
// 动态追踪空集（Empty Set Tracking）：它会在累加器（Accumulator）的末尾动态追加一个布尔标志位（hasNotNulls 标记）。如果整个运行期间没有遇到过一个非空行，最终输出（implementResult）时就会准确地切分分支，为其吐出 SQL 标准的 NULL 返回值，完美规避了聚合函数“错把空集当 0 算”的典型 bug。

public abstract class StrictAggImplementor implements AggImplementor {
  // 作用：编译期决策标志。用来标记当前的聚合函数是否需要特殊追踪空集。
  // 内幕：如果函数的返回类型支持 nullable（可赋 null 值），则需要追踪。
  private boolean needTrackEmptySet;
  // 编译期决策标志。用来标记是否需要在累加器数组中分配一个显式的布尔标志位来记录“是否见过非空行”。
  // 如果是普通的 Group By 聚合或者参数有 nullable 的情况，该值为 true；
  // 但在某些窗口聚合（WinAggContext）中，底层窗口框架可以通过别的方式（如 hasRows() 判断窗口内是否有行）来提供这个信息，从而不需要重复开辟物理空间。
  private boolean trackNullsPerRow;
  // 记录子类聚合函数原本纯粹的、不含空集标志位的中间状态变量的个数（例如 SUM 原本的状态大小是 1）。
  private int stateSize;
  // 判断当遇到空集时，是否应该返回非默认值（即返回 NULL）。
  protected boolean nonDefaultOnEmptySet(AggContext info) {
    return info.returnRelType().isNullable();
  }

  protected final int getStateSize() {
    return stateSize;
  }

  // 核心功能是向当前生成的 Java 代码块中，注入一行安全、带类型转换的“状态推进/赋值”表达式（即 acc = next;）。
  // AggAddContext add：迭代累加操作的上下文大管家。
  // 代码生成器通过它能够实时获取到当前正在构建的 Java 抽象语法树（AST）代码块（BlockBuilder），进而将生成的代码行追加进去。
  // Expression acc：当前累加器（Accumulator）变量的物理表达式。它代表了目前在内存中开辟出来的、用于存放中间状态的那个变量（例如：代表 double sum; 的语法树节点）。
  // Expression next：下一步即将要覆盖或融入的新值表达式。它通常是基于新输入的列数据计算出来的最新状态（例如：累加之后得到的最新结果 sum + next_row_value）。
  protected static void accAdvance(AggAddContext add, Expression acc,
      Expression next) {
    add.currentBlock().add(
        // Expressions.statement([第二层构建的赋值节点]) 作用：将一个“表达式（Expression）”规整为一行合法的“语句（Statement）”。
        Expressions.statement(
            // EnumUtils.convert(next, acc.type) 抹平 Java 类型擦除或隐式转换的鸿沟。
            // Expressions.assign(acc, [第一层转换后的Expression]) 生成 Java 的等号赋值语法树。
            // 物理效果：此时在内存的语法树中，已经完美捏合出了形如 acc = (TargetType) next 的逻辑结构。
            Expressions.assign(acc, EnumUtils.convert(next, acc.type))));
  }
  // 决定当前的聚合函数在运行期到底需要开辟几个、什么类型的局部变量来充当累加器（Accumulator）
  // 更关键的是，它会根据 SQL 语义，动态决定是否要在累加器数组的末尾强行追加一个 boolean.class 标志位，用来追踪“是否见过非空行”，从而完美支持 SQL 标准中“空集返回 NULL”的严格定义。
  // 返回值 List<Type>：一个由 Java Type 组成的列表。它将直接决定在接下来生成的 Java 物理代码中，累加器数组（或变量列表）的长度和具体物理类型。
  @Override public final List<Type> getStateType(AggContext info) {
    // 向具体子类索要它们“纯粹核心计算”所需的数据类型。
    List<Type> subState = getNotNullState(info);
    // 将子类原本的、纯粹的状态个数缓存到类的成员变量中。
    stateSize = subState.size();
    // 判定当前聚合在遇到空数据集时，是否需要特殊追踪并返回非默认值（即返回 NULL）。
    needTrackEmptySet = nonDefaultOnEmptySet(info);
    // 如果确认不需要追踪空集（比如 COUNT 算子），那么子类原本需要什么状态，这就直接返回什么状态（如 [long.class]），后续无需做任何包装，直接退场。
    if (!needTrackEmptySet) {
      return subState;
    }
    // 检查用户传入该聚合函数的 SQL 参数中，是否包含允许为 NULL 的列。
    final boolean hasNullableArgs = anyNullable(info.parameterRelTypes());
    // 分支 A：如果不是开窗函数（普通的 GROUP BY），由于没有外层框架帮忙，必须依赖一个物理变量来标记，所以 trackNullsPerRow = true。
    // 分支 B：如果是窗口聚合（info instanceof WinAggContext），且输入的参数列在 Schema 里被声明为 NOT NULL（hasNullableArgs 为 false），这意味着只要窗口内有行，就绝对等同于见到了非空行！
    trackNullsPerRow = !(info instanceof WinAggContext) || hasNullableArgs;
    // 申请一个容量大 1 位的全新列表。
    List<Type> res = new ArrayList<>(subState.size() + 1);
    res.addAll(subState);
    res.add(boolean.class); // has not nulls
    return res;
  }

  private static boolean anyNullable(List<? extends RelDataType> types) {
    for (RelDataType type : types) {
      if (type.isNullable()) {
        return true;
      }
    }
    return false;
  }

  public List<Type> getNotNullState(AggContext info) {
    Type type = info.returnType();
    type = EnumUtils.fromInternal(type);
    type = Primitive.unbox(type);
    return Collections.singletonList(type);
  }
  // 生成用于重置（清空）累加器状态的 Java 物理代码。无论是在单批次聚合开始前，还是在开窗函数切换分区（Partition）时，累加器都必须被清空或赋初值。
  // 该方法精妙地实现了“两阶段初始化”：先生成清空用于追踪空集的“布尔标志位”的代码，再调用子类去初始化核心的计算变量。
  @Override public final void implementReset(AggContext info, AggResetContext reset) {
    // 检查在getStateType 阶段中，是否决定了要在累加器数组中物理扩容并分配一个布尔标志位。
    // 如果是普通的 GROUP BY 聚合或需要严格追踪空集的场景，该值为 true，开启内部的重置逻辑。
    if (trackNullsPerRow) {
      // 捞出当前聚合函数在生成的 Java 代码中所拥有的物理累加器变量列表（类似于代表临时变量数组的抽象语法树节点）
      List<Expression> acc = reset.accumulator();
      // 精准锁定末尾的布尔标志位。
      Expression flag = acc.get(acc.size() - 1);
      BlockBuilder block = reset.currentBlock();
      // 生成将布尔标志位归零（赋初值 false）的 Java 独立语句并追加到代码块中。
      block.add(
          Expressions.statement(
              Expressions.assign(flag,
                  RexImpTable.getDefaultValue(flag.getType()))));
    }
    // 向下委派重置核心计算字段
    implementNotNullReset(info, reset);
  }
  // 循环遍历累加器中所有属于子类本身的计算变量（避开末尾追加的空集标志位），并在生成的 Java 代码中为它们安全地赋上物理初值（如数字型赋 0 或 0.0）。
  protected void implementNotNullReset(AggContext info,
      AggResetContext reset) {
    BlockBuilder block = reset.currentBlock();
    List<Expression> accumulator = reset.accumulator();
    // 开启“精准定界”的批量初始化循环。
    for (int i = 0; i < getStateSize(); i++) {
      // 根据当前索引 i，精准提取出第 i 个需要被初始化的累加器核心变量表达式（例如：代表 double sum_accumulator; 的节点）。
      Expression exp = accumulator.get(i);
      // 动态推导当前变量的默认初值，组装成带分号的 Java 赋值语句并注入代码块。
      block.add(
          Expressions.statement(
              Expressions.assign(exp,
                  RexImpTable.getDefaultValue(exp.getType()))));
    }
  }
  // 生成数据流迭代累加的物理 Java 代码，并自动化嵌套 NULL 过滤机制与 SQL 的 FILTER (WHERE...) 过滤子句。通过这套机制，子类（如 SumImplementor）在编写累加逻辑时，完全不需要人为去判断参数是否为空，
  // 父类会在外层自动套上高阶的 if (args != null) 条件分支。
  @Override public final void implementAdd(AggContext info, final AggAddContext add) {
    // 获取用户传入该聚合函数的所有逻辑参数表达式列表（RexNode 集合，例如 SUM(age) 中的 age 列节点）。
    final List<RexNode> args = add.rexArguments();
    final RexToLixTranslator translator = add.rowTranslator();
    // 申请一个物理表达式容器，用来打包这一行数据必须满足的所有拦截条件。
    final List<Expression> conditions = new ArrayList<>();
    // 自动生成参数的非空检查表达式。
    conditions.addAll(
        translator.translateList(args, RexImpTable.NullAs.IS_NOT_NULL));
    // 完美兼容 SQL 标准中的 FILTER (WHERE ...) 语法。
    // 检查该聚合算子末尾有没有带 FILTER 过滤子句（例如 SUM(age) FILTER (WHERE region = 'CN')）。
    // 如果有，利用行翻译器将其翻译出来。策略指定为 NullAs.FALSE（即如果过滤条件本身算出来是 null，直接等同于条件不成立）
    RexNode filterArgument = add.rexFilterArgument();
    if (filterArgument != null) {
      conditions.add(
          translator.translate(filterArgument,
              RexImpTable.NullAs.FALSE));
    }
    // 万剑归宗——将所有的过滤条件用 AND 运算符拧成一股绳。
    Expression condition = Expressions.foldAnd(conditions);
    if (Expressions.constant(false).equals(condition)) {
      return;
    }
    // 判定当前参数是否“天然非空”。如果 condition 表达式恒等于 true（例如所有的输入列在建表 Schema 大纲中都被强制声明为了 NOT NULL，且没有 FILTER），则 argsNotNull 为 true。
    boolean argsNotNull = Expressions.constant(true).equals(condition);
    // 如果 argsNotNull 为真（数据天然绝对非空），说明不需要任何 if 包装，直接在当前的物理主代码块 add.currentBlock() 注入累加逻辑即可。
    // 如果数据可能为空，它会单独开辟一个崭新的匿名局部代码块缓冲区 new BlockBuilder(...)，用来充当后续 if (condition) { ... } 内部的大括号内主体（thenBlock）。
    final BlockBuilder thenBlock =
        argsNotNull
        ? add.currentBlock()
        : new BlockBuilder(true, add.currentBlock());
    // 取出累加器列表里最后一位的布尔控制变量（即 seenNotNullRows），为其注入赋值语句：accumulator[last_index] = true;
    if (trackNullsPerRow) {
      List<Expression> acc = add.accumulator();
      thenBlock.add(
          Expressions.statement(
              Expressions.assign(acc.get(acc.size() - 1),
                  Expressions.constant(true))));
    }
    // 如果数据天然非空，直接就地调用子类的 implementNotNullAdd 往当前主代码块里写核心累加代码（如 sum += age;），然后利落地收工。
    if (argsNotNull) {
      implementNotNullAdd(info, add);
      return;
    }
    // 环境借调与子类代码抽样。
    add.nestBlock(thenBlock);
    // 向下委派。唤醒子类，让子类往当前的工作区内填入纯粹的累加代码（由于父类在外层已经做好了铜墙铁壁般的 NULL 过滤，子类在这里写核心代码时完全不需要考虑非空校验）。
    implementNotNullAdd(info, add);
    add.exitBlock();
    add.currentBlock().add(Expressions.ifThen(condition, thenBlock.toBlock()));
  }

  protected abstract void implementNotNullAdd(AggContext info,
      AggAddContext add);

  // 核心职责是：生成聚合计算结束后的最终输出 Java 代码，并根据“空集追踪标志位”动态切分分支。如果运行期发现数据集为空（或全是 NULL），
  // 除了 COUNT 以外，它会强制生成的代码输出 SQL 标准的 NULL，从而优雅地解决严格聚合函数“空集返回 NULL”的底层合规问题。
  @Override public final Expression implementResult(AggContext info,
      final AggResultContext result) {
    // 如果经过之前的 getStateType 阶段判定当前聚合不需要追踪空集（例如 COUNT 算子，空集直接返回默认值 0，永远不需要返回 NULL）。
    if (!needTrackEmptySet) {
      return EnumUtils.convert(
          implementNotNullResult(info, result), info.returnType());
    }
    String tmpName = result.accumulator().isEmpty()
        ? "ar"
        : (result.accumulator().get(0) + "$Res");
    ParameterExpression res =
        Expressions.parameter(0, info.returnType(),
            result.currentBlock().newName(tmpName));

    List<Expression> acc = result.accumulator();
    final BlockBuilder thenBlock = result.nestBlock();
    Expression nonNull =
        EnumUtils.convert(implementNotNullResult(info, result), info.returnType());
    result.exitBlock();
    thenBlock.add(Expressions.statement(Expressions.assign(res, nonNull)));
    BlockStatement thenBranch = thenBlock.toBlock();
    Expression seenNotNullRows =
        trackNullsPerRow
        ? acc.get(acc.size() - 1)
        : ((WinAggResultContext) result).hasRows();

    if (thenBranch.statements.size() == 1) {
      return Expressions.condition(seenNotNullRows,
          nonNull, RexImpTable.getDefaultValue(res.getType()));
    }
    result.currentBlock().add(Expressions.declare(0, res, null));
    result.currentBlock().add(
        Expressions.ifThenElse(seenNotNullRows,
            thenBranch,
            Expressions.statement(
                Expressions.assign(res,
                    RexImpTable.getDefaultValue(res.getType())))));
    return res;
  }

  protected Expression implementNotNullResult(AggContext info,
      AggResultContext result) {
    return result.accumulator().get(0);
  }
}
