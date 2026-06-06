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

/**
 * Implements a windowed aggregate function by generating expressions to
 * initialize, add to, and get a result from, an accumulator.
 * Windowed aggregate is more powerful than regular aggregate since it can
 * access rows in the current partition by row indices.
 * Regular aggregate can be used to implement windowed aggregate.
 *
 * <p>This interface does not define new methods: window-specific
 * sub-interfaces are passed when implementing window aggregate.
 *
 * @see org.apache.calcite.adapter.enumerable.StrictWinAggImplementor
 * @see org.apache.calcite.adapter.enumerable.RexImpTable.FirstLastValueImplementor
 * @see org.apache.calcite.adapter.enumerable.RexImpTable.RankImplementor
 * @see org.apache.calcite.adapter.enumerable.RexImpTable.RowNumberImplementor
 */
// 为什么常规聚合接口不够用？
// 在 SQL 中，常规分组聚合（Group By Aggregation）和窗口聚合（Window Aggregation，即带有 OVER (...) 子句的聚合）的底层计算模型存在本质区别：
// 常规聚合：数据流通常是一次性流过，算子来一行数据就累加（add）一行，算完了直接输出。它不需要、也无法反查数据分区内部的任意指定行。
// 窗口聚合：由于窗口框架（Window Frame，如 ROWS BETWEEN 2 PRECEDING AND CURRENT ROW）的存在，窗口函数在运行时经常需要根据行索引（Row Indices）随机或者相对寻址访问当前分区内的其他行数据。例如 LEAD、LAG、FIRST_VALUE 等函数，必须有能力在整个分区数据缓存块中“前后乱窜”。
// 核心作用是：扩展普通的聚合代码生成体系，通过引入“窗口特化上下文（Window Context）”和“物理寻址机制（SeekType）”，
// 指导代码生成器动态编译出能够支持“行级随机定位”、“滑动窗口缓存控制”的 Java 高性能迭代字节码。
public interface WinAggImplementor extends AggImplementor {
  /**
   * Allows to access rows in window partition relative to first/last and
   * current row.
   */
  // SeekType 是该接口内部定义的唯一一个物理寻址模式枚举。它是 Calcite 在动态生成窗口代码时，定位分区内数据行指针的“导航仪”
  // 当窗口函数需要向运行时容器索要某一行的数据时，必须传入 SeekType 来声明它是以哪里为物理参照物来进行偏移量（Offset）计算的。
  enum SeekType {
    /**
     * Start of window.
     *
     * @see WinAggFrameContext#startIndex()
     */
    // 物理内幕：代表以当前窗口分区的起点（Start of Partition/Window）作为寻址参照原点。
    // 深层作用：在生成的 Java 代码中，它指示指针直接路由到分区的第一行（即 WinAggFrameContext#startIndex()）。
    // 典型场景：实现 FIRST_VALUE(col) 函数。无论当前计算进行到了分区的哪一行，FIRST_VALUE 都要永远锁定分区的第 0 行，此时代码生成器就会使用 SeekType.START 配合 offset = 0 来抓取数据。
    START,
    /**
     * Row position in the frame.
     *
     * @see WinAggFrameContext#index()
     */
    // 物理内幕：代表以当前正在计算的窗口帧框架中的相对位置（Row position in the frame）作为寻址参照原点。
    // 深层作用：在代码生成期，它绑定的是动态迭代器当前滑到的位置（即 WinAggFrameContext#index()）。
    // 典型场景：当窗口发生滑动（如基于时间或行的滑动窗口），需要遍历当前窗口 Frame 内的所有有效行进行求和（SUM）时，代码生成器会利用 SeekType.SET 在 Frame 内部进行指针移动和状态累加。
    SET,
    /**
     * The index of row that is aggregated.
     * Valid only in {@link WinAggAddContext}.
     *
     * @see WinAggAddContext#currentPosition()
     */
    // 物理内幕：代表当前正在被拉入聚合累加器中的那一行原始行的绝对索引位置（The index of row that is aggregated）。
    // 深层作用：文档显式注明 “Valid only in WinAggAddContext”。
    // 它只在生成 add（累加）方法的代码块中合法。
    // 它指代的是当前正在被泵入累加器的那行数据的物理位置（WinAggAddContext#currentPosition()）。
    AGG_INDEX,
    /**
     * End of window.
     *
     * @see WinAggFrameContext#endIndex()
     */
    // 物理内幕：代表以当前窗口分区的终点（End of Partition/Window）作为寻址参照原点。
    END
  }
  // 指示当窗口的“帧结构保持完整、未发生破坏或滑动（Frame Intact）”时，动态生成的代码是否需要在内存中开启物化缓存（Materialized Cache）。
  // 在窗口计算时，如果窗口框架被定义为 ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING（即整个分区就是一块铁板，对任何一行来说，窗口范围都一模一样），这就是所谓的 Frame Intact。
  // 如果该方法返回 true：Calcite 在生成 Java 代码时，就会在内存中开辟一段强缓存区，把第一次计算好的聚合结果存起来。当迭代器走到分区的下一行时，直接从缓存里把上一次算好的值吐出来，免除重复遍历整个分区所有行的恐怖开销（将时间复杂度从 $O(N^2)$ 极限压榨到 $O(N)$）。
  boolean needCacheWhenFrameIntact();
}
