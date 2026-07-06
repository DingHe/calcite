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
package org.apache.calcite.sql.validate;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.SingleColumnAliasRelDataType;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlUnnestOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

import static org.apache.calcite.util.Static.RESOURCE;

/**
 * Namespace for an <code>AS t(c1, c2, ...)</code> clause.
 *
 * <p>A namespace is necessary only if there is a column list, in order to
 * re-map column names; a <code>relation AS t</code> clause just uses the same
 * namespace as <code>relation</code>.
 */
// 在 Calcite 中，SqlValidatorNamespace（简称 Namespace）代表了一个可以产生关系数据的命名空间（如表、子查询、视图等），它维护了该数据源的行类型（Row Type）和结构。
// AliasNamespace 的主要作用是处理带有别名（AS 子句）的 SQL 节点。在 SQL 中，我们经常会为表、子查询或函数调用重命名，甚至在重命名的同时指定新的列名。
// 例如：
// SELECT * FROM emp AS e （仅重命名表/源）
// SELECT * FROM (VALUES (1, 'a')) AS t(id, name) （重命名表的同时显式重命名列）
// SELECT * FROM UNNEST(arr) AS t(col) （集合扁平化并命名）
// AliasNamespace 的核心职责就是：
// 派生（Derive）新的类型： 结合被别名包裹的原始数据源类型，以及别名系统显式指定的列名，计算出重命名后的新行类型（RelDataType）。
// 校验合规性： 如果显式指定了列名（如上面的 t(id, name)），它会校验列的数量是否与底层源匹配，以及新指定的列名是否存在重复。
// 名称映射与翻译： 当后续的查询逻辑引用了别名后的列名时，它负责将这些新列名翻译回底层真实的字段名称。
public class AliasNamespace extends AbstractNamespace {
  //~ Instance fields --------------------------------------------------------
  // 存储当前的别名表达式节点（即 AS 操作符对应的抽象语法树节点）。
  protected final SqlCall call;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates an AliasNamespace.
   *
   * @param validator     Validator
   * @param call          Call to AS operator
   * @param enclosingNode Enclosing node
   */
  // 初始化 AliasNamespace 实例。
  protected AliasNamespace(
      SqlValidatorImpl validator,
      // 当前的 AS 节点。
      SqlCall call,
      // 包裹当前节点的外部父节点（用于在上下文中确定范围）。
      SqlNode enclosingNode) {
    super(validator, enclosingNode);
    this.call = call;
    assert call.getOperator() == SqlStdOperatorTable.AS;
    assert call.operandCount() >= 2;
  }

  //~ Methods ----------------------------------------------------------------
  // 检查当前命名空间是否支持指定的模态（Modality）。
  // 在流式 SQL（Streaming SQL）中，数据流有关系（Relation）模态和流（Stream）模态。因为别名只是一个“包装层”，它自身不改变数据的流属性。
  @Override public boolean supportsModality(SqlModality modality) {
    final List<SqlNode> operands = call.getOperandList();
    // 获取 call 的第 1 个操作数（即被包裹的底层节点），拿到其 Namespace，然后直接调用底层 Namespace 的 supportsModality。这意味着别名命名空间完全继承了底层数据源的模态
    final SqlValidatorNamespace childNs =
        validator.getNamespaceOrThrow(operands.get(0));
    return childNs.supportsModality(modality);
  }
  // 执行该命名空间的实际验证逻辑，并推导/返回重命名之后的最终行类型。这是该类中最核心、最复杂的逻辑。
  @Override protected RelDataType validateImpl(RelDataType targetRowType) {
    final List<SqlNode> operands = call.getOperandList();
    final SqlValidatorNamespace childNs =
        validator.getNamespaceOrThrow(operands.get(0));
    final RelDataType rowType = childNs.getRowTypeSansSystemColumns();
    final RelDataType aliasedType;
    if (operands.size() == 2) {
      // 分支一：仅有表别名，无显式列名列表（operands.size() == 2，如 AS t）：
      final SqlNode node = operands.get(0);
      // Alias is 'AS t' (no column list).
      // If the sub-query is UNNEST or VALUES,
      // and the sub-query has one column,
      // then the namespace's sole column is named after the alias.
      // 特殊情况 A（单列且为 UNNEST/VALUES）： 如果底层刚好只有一列（rowType.getFieldCount() == 1），Calcite 会自动将这一列的列名修改为表别名的名字（例如 UNNEST(arr) AS t，里面唯一的列名会变成 t）。
      if (rowType.getFieldCount() == 1) {
        final RelDataType singleColumnAlias = validator.getTypeFactory().builder()
            .kind(rowType.getStructKind())
            .add(((SqlIdentifier) operands.get(1)).getSimple(),
                rowType.getFieldList().get(0).getType())
            .build();
        aliasedType = node.getKind() == SqlKind.COLLECTION_TABLE
            ? new SingleColumnAliasRelDataType(rowType, singleColumnAlias) : singleColumnAlias;
        // If the sub-query is UNNEST with ordinality
        // and the sub-query has two columns: data column, ordinality column
        // then the namespace's sole column is named after the alias.
      // 特殊情况 B（带有 Ordinality 的 UNNEST）： 如果是 UNNEST(...) WITH ORDINALITY AS t 且恰好包含两列（数据列和序号列），则第 1 列（数据列）名字重命名为表别名 t，第 2 列（序号列）保持不变。
      } else if (node.getKind() == SqlKind.UNNEST && rowType.getFieldCount() == 2
          && ((SqlUnnestOperator) ((SqlBasicCall) node).getOperator()).withOrdinality) {
        aliasedType = validator.getTypeFactory().builder()
            .kind(rowType.getStructKind())
            .add(((SqlIdentifier) operands.get(1)).getSimple(),
                rowType.getFieldList().get(0).getType())
            .add(rowType.getFieldList().get(1))
            .build();
      } else {
        aliasedType = rowType;
      }
    // 分支二：带有显式列名列表（operands.size() > 2，如 AS t(c1, c2)）：
    } else {
      // Alias is 'AS t (c0, ..., cN)'
      final List<SqlNode> columnNames = Util.skip(operands, 2);
      final List<String> nameList = SqlIdentifier.simpleNames(columnNames);
      // 查重校验： 检查新的列别名中是否有重复名称（如 AS t(c1, c1)）。如果有，直接抛出 aliasListDuplicate 验证错误。
      final int i = Util.firstDuplicate(nameList);
      if (i >= 0) {
        final SqlIdentifier id = (SqlIdentifier) columnNames.get(i);
        throw validator.newValidationError(id,
            RESOURCE.aliasListDuplicate(id.getSimple()));
      }
      // 数量校验： 检查指定的列名个数是否等于底层数据源的实际列数。如果不匹配，组合出一个节点位置，抛出 aliasListDegree 错误。
      if (columnNames.size() != rowType.getFieldCount()) {
        // Position error over all column names
        final SqlNode node = operands.size() == 3
            ? operands.get(2)
            : new SqlNodeList(columnNames, SqlParserPos.sum(columnNames));
        throw validator.newValidationError(node,
            RESOURCE.aliasListDegree(rowType.getFieldCount(),
                getString(rowType), columnNames.size()));
      }
      // 构建新类型： 如果校验全部通过，使用 TypeFactory 构建新类型，依次将底层的字段类型与新的列名进行一对一绑定（Pair.of(newName, oldType)）。
      aliasedType = validator.getTypeFactory().builder()
          .addAll(
              Util.transform(rowType.getFieldList(), f ->
                  Pair.of(nameList.get(f.getIndex()), f.getType())))
          .kind(rowType.getStructKind())
          .build();
    }

    // As per suggestion in CALCITE-4085, JavaType has its special nullability handling.
    if (rowType instanceof RelDataTypeFactoryImpl.JavaType) {
      return aliasedType;
    } else {
      return validator.getTypeFactory()
          .createTypeWithNullability(aliasedType, rowType.isNullable());
    }
  }
  // 将一个行类型的各个字段名格式化拼接成一个可读的字符串，形如 ('field1', 'field2')。
  private static String getString(RelDataType rowType) {
    StringBuilder buf = new StringBuilder();
    buf.append("(");
    for (RelDataTypeField field : rowType.getFieldList()) {
      if (field.getIndex() > 0) {
        buf.append(", ");
      }
      buf.append("'");
      buf.append(field.getName());
      buf.append("'");
    }
    buf.append(")");
    return buf.toString();
  }

  @Override public @Nullable SqlNode getNode() {
    return call;
  }
  // 将外部别名后的列名，翻译（转换）回底层未别名之前的原始列名。
  @Override public String translate(String name) {
    final RelDataType underlyingRowType =
        validator.getValidatedNodeType(call.operand(0));
    int i = 0;
    for (RelDataTypeField field : getRowType().getFieldList()) {
      if (field.getName().equals(name)) {
        return underlyingRowType.getFieldList().get(i).getName();
      }
      ++i;
    }
    throw new AssertionError("unknown field '" + name
        + "' in rowtype " + underlyingRowType);
  }
}
