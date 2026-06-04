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

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.validate.SqlValidator;

import com.google.common.collect.ImmutableList;

/**
 * SqlSessionTableFunction implements an operator for per-key sessionization. It allows
 * four parameters:
 *
 * <ol>
 *   <li>table as data source</li>
 *   <li>a descriptor to provide a watermarked column name from the input table</li>
 *   <li>a descriptor to provide a column as key, on which sessionization will be applied,
 *   optional</li>
 *   <li>an interval parameter to specify a inactive activity gap to break sessions</li>
 * </ol>
 */
// 用于支持 SESSION（会话窗口表函数） 的核心实现类。
// 在流处理和时序数据分析中，会话窗口（Session Window） 是一种非常特殊的窗口模式。与固定大小的滚动窗口（Tumble）或滑动窗口（Hop）不同，会话窗口根据数据的活跃度动态切分时间。
// 它由一个不活跃间隔（Inactive Gap）来定义。如果两物理条数据之间的时间差超过了这个设定的 Gap，则前一个会话结束，后一个会话开始。
// 定义 SESSION 函数的语法规范：它规定了会话窗口函数所能接受的参数模板，最大允许 4 个参数（最少 3 个）：
// 第一参数（DATA）：TABLE 关系数据源。
// 第二参数（TIMECOL）：DESCRIPTOR，声明带有水位线的时间列。
// 第三参数（KEY，可选/位置多变）：DESCRIPTOR，用于指定作为会话键的列（如 DESCRIPTOR(user_id)）。
// 第四参数（SIZE，代表 Gap）：INTERVAL，指定会话之间划分边界的不活跃时间间隔（如 INTERVAL '30' MINUTE）。
public class SqlSessionTableFunction extends SqlWindowTableFunction {
  public SqlSessionTableFunction() {
    super(SqlKind.SESSION.name(), new OperandMetadataImpl());
  }

  /** Operand type checker for SESSION. */
  private static class OperandMetadataImpl extends AbstractOperandMetadata {
    OperandMetadataImpl() {
      super(ImmutableList.of(PARAM_DATA, PARAM_TIMECOL, PARAM_KEY, PARAM_SIZE),
          3);
    }

    @Override public boolean checkOperandTypes(SqlCallBinding callBinding,
        boolean throwOnFailure) {
      if (!checkTableAndDescriptorOperands(callBinding, 1)) {
        return throwValidationSignatureErrorOrReturnFalse(callBinding, throwOnFailure);
      }
      if (!checkTimeColumnDescriptorOperand(callBinding, 1)) {
        return throwValidationSignatureErrorOrReturnFalse(callBinding, throwOnFailure);
      }

      final SqlValidator validator = callBinding.getValidator();
      final SqlNode operand2 = callBinding.operand(2);
      final RelDataType type2 = validator.getValidatedNodeType(operand2);
      if (operand2.getKind() == SqlKind.DESCRIPTOR) {
        final SqlNode operand0 = callBinding.operand(0);
        final RelDataType type = validator.getValidatedNodeType(operand0);
        validateColumnNames(
            validator, type.getFieldNames(), ((SqlCall) operand2).getOperandList());
      } else if (!SqlTypeUtil.isInterval(type2)) {
        return throwValidationSignatureErrorOrReturnFalse(callBinding, throwOnFailure);
      }
      if (callBinding.getOperandCount() > 3) {
        final RelDataType type3 = validator.getValidatedNodeType(callBinding.operand(3));
        if (!SqlTypeUtil.isInterval(type3)) {
          return throwValidationSignatureErrorOrReturnFalse(callBinding, throwOnFailure);
        }
      }
      return true;
    }

    @Override public String getAllowedSignatures(SqlOperator op, String opName) {
      return opName + "(TABLE table_name, DESCRIPTOR(timecol), "
          + "DESCRIPTOR(key) optional, datetime interval)";
    }
  }
}
