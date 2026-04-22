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

import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.ExtensibleTable;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.ModifiableViewTable;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

import static org.apache.calcite.util.ImmutableBitSet.toImmutableBitSet;
import static org.apache.calcite.util.Static.RESOURCE;

import static java.util.Objects.requireNonNull;

/** Namespace based on a table from the catalog. */
// Namespace（命名空间） 代表一个产生行数据的源（如表、子查询、JOIN 结果等）。
// 数据源标识：它封装了来自元数据目录（Catalog）的表对象（SqlValidatorTable）。
// 类型校验：负责解析并返回该表在 SQL 校验阶段的行类型（Row Type）。
// 动态扩展（Extension）：支持某些数据库特有的“扩展列”功能（如 Apache Phoenix 的 HBase 映射），允许在查询时动态声明表中原本未定义的隐藏列。

class TableNamespace extends AbstractNamespace {
  // 存储底层元数据表对象。
  // 是校验器与元数据层（Schema）之间的桥梁，包含了表的列信息、统计信息等。
  private final SqlValidatorTable table;
  // 存储该命名空间特有的“扩展列”列表
  // 这些列不属于标准表定义，而是通过 SQL 语法（如 EXTEND 子句）动态加入的。
  public final ImmutableList<RelDataTypeField> extendedFields;

  /** Creates a TableNamespace. */
  private TableNamespace(SqlValidatorImpl validator, SqlValidatorTable table,
      List<RelDataTypeField> fields) {
    super(validator, null);
    this.table = requireNonNull(table, "table");
    this.extendedFields = ImmutableList.copyOf(fields);
  }

  TableNamespace(SqlValidatorImpl validator, SqlValidatorTable table) {
    this(validator, table, ImmutableList.of());
  }
  // 负责在校验阶段确定表的最终结构（Row Type）。它主要完成了两件事：识别“必须过滤”的敏感字段，以及合并动态扩展的字段。
  @Override protected RelDataType validateImpl(RelDataType targetRowType) {
    this.mustFilterFields = ImmutableBitSet.of();
    // SemanticTable: 这是 Calcite 提供的一个接口，用于给表增加“语义”约束
    // 尝试将底层的 table 对象转换为 SemanticTable。
    table.maybeUnwrap(SemanticTable.class)
        .ifPresent(semanticTable ->
            this.mustFilterFields =
                table.getRowType().getFieldList().stream()
                    .map(RelDataTypeField::getIndex)//如果转换成功，则遍历该表的所有字段（getFieldList）。
                    .filter(semanticTable::mustFilter)// 询问：“这个索引处的字段是否强制要求在 SQL 的 WHERE 子句中出现？”
                    .collect(toImmutableBitSet()));

    if (extendedFields.isEmpty()) {
      return table.getRowType();
    }
    // 类型合并与构建 (Extended Fields Logic)
    final RelDataTypeFactory.Builder builder =
        validator.getTypeFactory().builder();
    builder.addAll(table.getRowType().getFieldList()); // 先添加原始字段
    builder.addAll(extendedFields);
    return builder.build();
  }

  @Override public @Nullable SqlNode getNode() {
    // This is the only kind of namespace not based on a node in the parse tree.
    return null;
  }

  @Override public SqlValidatorTable getTable() {
    return table;
  }

  @Override public SqlMonotonicity getMonotonicity(String columnName) {
    final SqlValidatorTable table = getTable();
    return table.getMonotonicity(columnName);
  }

  /** Creates a TableNamespace based on the same table as this one, but with
   * extended fields.
   *
   * <p>Extended fields are "hidden" or undeclared fields that may nevertheless
   * be present if you ask for them. Phoenix uses them, for instance, to access
   * rarely used fields in the underlying HBase table. */
  // 创建一个包含扩展列的新 TableNamespace。
  // 用于支持 SQL 中的 SELECT ... FROM table EXTEND (col1 type1, ...) 语法。
  // 校验扩展列是否重复，检查类型是否与原表冲突，并尝试调用底层 RelOptTable.extend（如果支持）。
  public TableNamespace extend(SqlNodeList extendList) {
    final List<SqlNode> identifierList = Util.quotientList(extendList, 2, 0);
    SqlValidatorUtil.checkIdentifierListForDuplicates(
        identifierList, validator.getValidationErrorFunction());
    final ImmutableList.Builder<RelDataTypeField> builder =
        ImmutableList.builder();
    builder.addAll(this.extendedFields);
    builder.addAll(
        SqlValidatorUtil.getExtendedColumns(validator,
            getTable(), extendList));
    final List<RelDataTypeField> extendedFields = builder.build();
    final Table schemaTable = table.unwrap(Table.class);
    if (table instanceof RelOptTable
        && (schemaTable instanceof ExtensibleTable
          || schemaTable instanceof ModifiableViewTable)) {
      checkExtendedColumnTypes(extendList);
      final RelOptTable relOptTable =
          ((RelOptTable) table).extend(extendedFields);
      final SqlValidatorTable validatorTable =
          requireNonNull(
            relOptTable.unwrap(SqlValidatorTable.class),
            () -> "cant unwrap SqlValidatorTable from " + relOptTable);
      return new TableNamespace(validator, validatorTable, ImmutableList.of());
    }
    return new TableNamespace(validator, table, extendedFields);
  }

  /**
   * Gets the data-type of all columns in a table. For a view table, includes
   * columns of the underlying table.
   */
  // 获取表的最基础行类型。
  // 如果是 ModifiableViewTable（可修改视图），则递归解包获取其底层原始表的类型。
  private RelDataType getBaseRowType() {
    final Table schemaTable =
        requireNonNull(table.unwrap(Table.class),
            () -> "can't unwrap Table from " + table);
    if (schemaTable instanceof ModifiableViewTable) {
      final Table underlying =
          ((ModifiableViewTable) schemaTable).unwrap(Table.class);
      assert underlying != null;
      return underlying.getRowType(validator.typeFactory);
    }
    return schemaTable.getRowType(validator.typeFactory);
  }

  /**
   * Ensures that extended columns that have the same name as a base column also
   * have the same data-type.
   */
  private void checkExtendedColumnTypes(SqlNodeList extendList) {
    final List<RelDataTypeField> extendedFields =
        SqlValidatorUtil.getExtendedColumns(validator, table, extendList);
    final List<RelDataTypeField> baseFields =
        getBaseRowType().getFieldList();
    final Map<String, Integer> nameToIndex =
        SqlValidatorUtil.mapNameToIndex(baseFields);

    for (final RelDataTypeField extendedField : extendedFields) {
      final String extFieldName = extendedField.getName();
      if (nameToIndex.containsKey(extFieldName)) {
        final Integer baseIndex = nameToIndex.get(extFieldName);
        final RelDataType baseType = baseFields.get(baseIndex).getType();
        final RelDataType extType = extendedField.getType();

        if (!extType.equals(baseType)) {
          // Get the extended column node that failed validation.
          final SqlNode extColNode =
              Iterables.find(extendList,
                  sqlNode -> sqlNode instanceof SqlIdentifier
                      && Util.last(((SqlIdentifier) sqlNode).names).equals(
                          extendedField.getName()));

          throw validator.getValidationErrorFunction().apply(extColNode,
              RESOURCE.typeNotAssignable(
                  baseFields.get(baseIndex).getName(), baseType.getFullTypeString(),
                  extendedField.getName(), extType.getFullTypeString()));
        }
      }
    }
  }
}
