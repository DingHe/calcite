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

import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;

import com.google.common.collect.ImmutableSet;

import java.util.Set;

/**
 * An operator describing a LATERAL specification.
 */
// 门用于处理 SQL 中 LATERAL 关键字的类
// LATERAL 关键字在 SQL 中允许子查询引用同一 FROM 子句中在其左侧定义的表列（通常称为“侧向引用”或“相关子查询”）。
// SELECT * FROM dept, LATERAL (SELECT * FROM emp WHERE emp.deptno = dept.deptno)
// LATERAL 的解析是由 JavaCC 解析器直接硬编码（Hard-coded）处理的，而不是通过优先级爬升算法动态规约的。
// 所以没有实现没有实现reduceExpr方法
public class SqlLateralOperator extends SqlSpecialOperator {
  //~ Constructors -----------------------------------------------------------
  // kind: 传入的 SQL 类型（通常是 SqlKind.LATERAL）。
  public SqlLateralOperator(SqlKind kind) {
    super(kind.name(), kind, 200, true, ReturnTypes.ARG0, null,
        OperandTypes.ANY);
  }

  //~ Methods ----------------------------------------------------------------
  // 将 Calcite 内存中的 SqlCall 对象转换回人类可读的 SQL 文本。
  // writer: 用于构建 SQL 字符串的输出器。
  // call: 当前包含 LATERAL 关键字的调用节点。
  @Override public void unparse(SqlWriter writer, SqlCall call, int leftPrec,
      int rightPrec) {
    // 定义了一个集合 specialOperandKinds，包含 COLLECTION_TABLE (TABLE 函数), SELECT (子查询), 和 AS (别名)。
    final Set<SqlKind> specialOperandKinds =
        ImmutableSet.of(SqlKind.COLLECTION_TABLE, SqlKind.SELECT, SqlKind.AS);
    // 分支 A（特殊语法）：如果操作数只有一个，且属于specialOperandKinds三种类型之一。
    if (call.operandCount() == 1
        && specialOperandKinds.contains(call.operand(0).getKind())) {
      // Do not create ( ) around the following TABLE clause.
      // 先写入关键字 LATERAL
      writer.keyword(getName());
      // 然后直接对内部操作数调用 unparse，不添加额外的括号
      // 这符合 SQL 标准，例如 LATERAL TABLE(...) 或 LATERAL (SELECT ...) 本身已经带有必要的界定符。
      call.operand(0).unparse(writer, 0, 0);
    } else {
      // 分支 B（通用语法）：如果不满足上述条件。
      // 调用 SqlUtil.unparseFunctionSyntax。这会尝试以类似函数的格式 LATERAL(...) 来输出。
      SqlUtil.unparseFunctionSyntax(this, writer, call, false);
    }
  }
}
