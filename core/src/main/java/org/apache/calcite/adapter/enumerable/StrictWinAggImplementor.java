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

import org.apache.calcite.linq4j.tree.Expression;

import java.lang.reflect.Type;
import java.util.List;

/**
 * The base implementation of strict window aggregate function.
 *
 * @see org.apache.calcite.adapter.enumerable.RexImpTable.FirstLastValueImplementor
 * @see org.apache.calcite.adapter.enumerable.RexImpTable.RankImplementor
 * @see org.apache.calcite.adapter.enumerable.RexImpTable.RowNumberImplementor
 */
// StrictWinAggImplementor 的首要核心使命就是充当物理类型转换的安全桥梁。它覆写了父类所有接收常规 AggContext 的方法，并在内部强制类型转换（Downcast）为窗口特化的 WinAggContext，从而完美消除了常规聚合与窗口聚合之间的类型鸿沟。
// 所谓“Strict（严格）”，在 SQL 语义中意味着它对 NULL 值有严格的防御机制。例如 SUM(col)，如果进来的列是 NULL，它不应该参与累加，且全为 NULL 时要坍塌返回 NULL。
//该类通过模板方法模式（Template Method Pattern），把类型安全转换的代码固化在外部，同时留出干净的、强类型的抽象方法（如窗口特化的 implementNotNullAdd）让子类（如 SUM、RANK、ROW_NUMBER）去专心实现其核心的动态代码编织，免去了每个子类重复编写强转代码的痛苦。

public abstract class StrictWinAggImplementor extends StrictAggImplementor
    implements WinAggImplementor {
  // 整个类中最核心的抽象灵魂方法。 要求子类必须实现它。
  // 专门用来编织当输入数据行明确不为 NULL 时，窗口聚合函数应该如何执行累加（Add）的 Java 循环体代码。
  protected abstract void implementNotNullAdd(WinAggContext info,
      WinAggAddContext add);

  // 判定当前窗口函数在面对完全空的数据窗口帧（Empty Frame）时，是否需要返回非默认值（通常指返回 NULL）。
  protected boolean nonDefaultOnEmptySet(WinAggContext info) {
    return super.nonDefaultOnEmptySet(info);
  }
  // 获取当前窗口函数在纯核心计算时，需要在 JVM 内存中开辟的中间状态变量的 Java 类型列表（不含空集防御）。
  public List<Type> getNotNullState(WinAggContext info) {
    return super.getNotNullState(info);
  }
  // 动态编织、生成窗口状态在重置/初始化（Reset）时的非空 Java 代码块。
  protected void implementNotNullReset(WinAggContext info,
      WinAggResetContext reset) {
    super.implementNotNullReset(info, reset);
  }
  // 动态编织、生成窗口计算结束、导出最终聚合结果（Result）时的非空 Java 表达式指针。
  protected Expression implementNotNullResult(WinAggContext info,
      WinAggResultContext result) {
    return super.implementNotNullResult(info, result);
  }
  // 以下 5 个覆写（@Override）方法全部被声明为 final。这意味着子类绝对不允许篡改这部分的强转和路由逻辑。它们是安全转换的铁闸。
  @Override protected final void implementNotNullAdd(AggContext info,
      AggAddContext add) {
    implementNotNullAdd((WinAggContext) info, (WinAggAddContext) add);
  }

  @Override protected boolean nonDefaultOnEmptySet(AggContext info) {
    return nonDefaultOnEmptySet((WinAggContext) info);
  }

  @Override public final List<Type> getNotNullState(AggContext info) {
    return getNotNullState((WinAggContext) info);
  }

  @Override protected final void implementNotNullReset(AggContext info,
      AggResetContext reset) {
    implementNotNullReset((WinAggContext) info, (WinAggResetContext) reset);
  }

  @Override protected final Expression implementNotNullResult(AggContext info,
      AggResultContext result) {
    return implementNotNullResult((WinAggContext) info,
        (WinAggResultContext) result);
  }

  @Override public boolean needCacheWhenFrameIntact() {
    return true;
  }
}
