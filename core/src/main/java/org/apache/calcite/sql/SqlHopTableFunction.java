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
package org.apache.calcite.sql;

import com.google.common.collect.ImmutableList;

/**
 * SqlHopTableFunction implements an operator for hopping.
 *
 * <p>It allows four parameters:
 *
 * <ol>
 *   <li>a table</li>
 *   <li>a descriptor to provide a watermarked column name from the input table</li>
 *   <li>an interval parameter to specify the length of window shifting</li>
 *   <li>an interval parameter to specify the length of window size</li>
 * </ol>
 */
// 用于支持 HOP（滑动窗口表函数） 的核心实现类
// 在流处理或时序数据分析中，滑动窗口（Hopping Window，常被称为 Sliding Window） 是一种由窗口大小（Size）和滑动步长（Slide/Shift）共同定义的窗口模式。
// 与滚动窗口不同，滑动窗口的各个窗口区间是可以相互重叠的。若一条数据落在了重叠的时间区间内，它会同时属于多个窗口。
// 定义 HOP 函数的语法规范：它规定了一个合法的 HOP 函数调用应该具备的物理参数模板。它允许接受最多 5 个参数（最少 4 个）：
// 第一参数（DATA）：TABLE 关系数据源。
// 第二参数（TIMECOL）：DESCRIPTOR，声明带有水位线的时间列。
// 第三参数（SLIDE）：INTERVAL，指定窗口滑动的步长（例如每隔 5 分钟滑动一次）。
// 第四参数（SIZE）：INTERVAL，指定窗口的总宽度大小（例如窗口大小为 1 小时）。
// 第五参数（OFFSET，可选）：INTERVAL，指定窗口对齐的物理偏移量。
public class SqlHopTableFunction extends SqlWindowTableFunction {
  public SqlHopTableFunction() {
    super(SqlKind.HOP.name(), new OperandMetadataImpl());
  }

  /** Operand type checker for HOP. */
  private static class OperandMetadataImpl extends AbstractOperandMetadata {
    OperandMetadataImpl() {
      // 对应的官方字段大纲顺序为：["DATA", "TIMECOL", "SLIDE", "SIZE", "OFFSET"]。
      // 请注意，这里的 SLIDE 位于 SIZE 之前，符合标准 SQL 语法中“先交代每隔多久滑一次，再交代窗户有多大”的习惯。
      super(
          ImmutableList.of(PARAM_DATA, PARAM_TIMECOL, PARAM_SLIDE,
              PARAM_SIZE, PARAM_OFFSET), 4);
    }

    @Override public boolean checkOperandTypes(SqlCallBinding callBinding,
        boolean throwOnFailure) {
      if (!checkTableAndDescriptorOperands(callBinding, 1)) {
        return throwValidationSignatureErrorOrReturnFalse(callBinding, throwOnFailure);
      }
      if (!checkTimeColumnDescriptorOperand(callBinding, 1)) {
        return throwValidationSignatureErrorOrReturnFalse(callBinding, throwOnFailure);
      }
      if (!checkIntervalOperands(callBinding, 2)) {
        return throwValidationSignatureErrorOrReturnFalse(callBinding, throwOnFailure);
      }
      return true;
    }

    @Override public String getAllowedSignatures(SqlOperator op, String opName) {
      return opName + "(TABLE table_name, DESCRIPTOR(timecol), "
          + "datetime interval, datetime interval[, datetime interval])";
    }
  }
}
