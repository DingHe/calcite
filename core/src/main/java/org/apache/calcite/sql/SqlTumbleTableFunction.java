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
 * SqlTumbleTableFunction implements an operator for tumbling.
 *
 * <p>It allows three parameters:
 *
 * <ol>
 *   <li>a table</li>
 *   <li>a descriptor to provide a watermarked column name from the input table</li>
 *   <li>an interval parameter to specify the length of window size</li>
 * </ol>
 */
// 支持 TUMBLE（滚动窗口表函数） 的核心实现类。它继承自 SqlWindowTableFunction，是将 SQL 标准中的滚动窗口语法转化为底层执行计划的元数据纽带。
// 在流处理或时序数据分析（如 Apache Flink SQL）中，滚动窗口（Tumbling Window） 是一种将无限流切分成一个个固定大小、且互不重叠的时间区间的窗口模式。
// 定义 TUMBLE 函数的语法规范：它规定了一个合法的 TUMBLE 函数调用的物理参数模板。允许接受最多 4 个参数（最少 3 个）：
// 第一参数：TABLE 关系数据源。
// 第二参数：DESCRIPTOR，声明带有水位线（Watermark）的时间列。
// 第三参数：INTERVAL，指定滚动窗口的宽度大小（例如 INTERVAL '10' MINUTE）。
// 第四参数（可选）：INTERVAL，指定窗口对齐的偏移量（Offset）
public class SqlTumbleTableFunction extends SqlWindowTableFunction {
  public SqlTumbleTableFunction() {
    super(SqlKind.TUMBLE.name(), new OperandMetadataImpl());
  }

  /** Operand type checker for TUMBLE. */
  private static class OperandMetadataImpl extends AbstractOperandMetadata {
    OperandMetadataImpl() {
      // 传入参数名序列：ImmutableList.of(PARAM_DATA, PARAM_TIMECOL, PARAM_SIZE, PARAM_OFFSET)，即对应的官方字段大纲为：["DATA", "TIMECOL", "SIZE", "OFFSET"]。
      // 传入 mandatoryParamCount = 3：意味着前三个参数（数据、时间列、大小）雷打不动必须必填，第四个参数 OFFSET 为选填。
      super(
          ImmutableList.of(PARAM_DATA, PARAM_TIMECOL, PARAM_SIZE, PARAM_OFFSET),
          3);
    }

    @Override public boolean checkOperandTypes(SqlCallBinding callBinding,
        boolean throwOnFailure) {
      // There should only be three operands, and number of operands are checked before
      // this call.
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
      return opName + "(TABLE table_name, DESCRIPTOR(timecol), datetime interval"
          + "[, datetime interval])";
    }
  }
}
