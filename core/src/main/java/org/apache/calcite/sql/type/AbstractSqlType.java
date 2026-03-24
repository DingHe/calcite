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
package org.apache.calcite.sql.type;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFamily;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelDataTypeImpl;
import org.apache.calcite.rel.type.RelDataTypePrecedenceList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Abstract base class for SQL implementations of {@link RelDataType}.
 */
// 在 Apache Calcite 的类型系统中，AbstractSqlType 是一个承上启下的核心类。
// 它继承自RelDataTypeImpl，专门为 SQL 标准类型（如 INT, VARCHAR, BOOLEAN 等）提供基础实现。
// 如果说 RelDataTypeImpl 是所有数据类型的通用基类，那么 AbstractSqlType 就是 SQL 原生类型 的基座。
// 明确 SQL 语义：它将通用的关系类型细化为具体的 SQL 枚举类型（SqlTypeName）。
// 管理可空性（Nullability）：在 SQL 层面统一处理字段是否允许为 NULL 的逻辑。
// 接入 SQL 类型族与优先级：它实现了 SQL 特有的类型兼容性逻辑（如 INT 和 BIGINT 属于同一个 Family），并引入了 SQL 标准的类型转换优先级。
// 作为具体实现的模板：它是 BasicSqlType（普通 SQL 类型）和 IntervalSqlType（时间间隔类型）等类的直接父类。
// 与 JavaType 的区别
// 1、 所属范畴	SQL 标准类型	JVM 原生类型
// 2、 核心标识	SqlTypeName (枚举)	java.lang.Class (类对象)
// 3、 典型示例	INT, VARCHAR(20), DECIMAL	int.class, java.util.Map, MyPojo.class
// 4、 元数据依据	精度 (Precision)、标度 (Scale)	反射 (Reflection)、字段 (Fields)
// 5、 可空性定义	显式标记 (Nullable/Not Null)	基本类型不可为空，对象类型可为空
// 6、 主要用途	SQL 解析、验证、优化过程	读取 Java Bean、外部系统集成 (如 Flink/Spark)
public abstract class AbstractSqlType
    extends RelDataTypeImpl
    implements Cloneable, Serializable {
  //~ Instance fields --------------------------------------------------------
  // 存储该类型的 SQL 枚举值。
  // 重要性：它是识别类型的核心，例如 SqlTypeName.INTEGER 或 SqlTypeName.VARCHAR。它是 final 的，一旦确定不可更改。
  protected final SqlTypeName typeName;
  // 标记该 SQL 类型实例是否允许存储 NULL 值。
  // 特性：这是一个实例级别的属性。在 Calcite 中，同一个 INTEGER 类型会有两个实例：一个 INTEGER NULL，一个 INTEGER NOT NULL。
  protected boolean isNullable;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates an AbstractSqlType.
   *
   * @param typeName   Type name
   * @param isNullable Whether nullable
   * @param fields     Fields of type, or null if not a record type
   */
  protected AbstractSqlType(
      SqlTypeName typeName,
      boolean isNullable,
      @Nullable List<? extends RelDataTypeField> fields) {
    // 首先调用 super(fields) 初始化字段列表（如果是结构化 SQL 类型）。
    super(fields);
    this.typeName = Objects.requireNonNull(typeName, "typeName");
    this.isNullable = isNullable || (typeName == SqlTypeName.NULL);
  }

  //~ Methods ----------------------------------------------------------------
  // 直接返回构造时传入的 typeName 枚举。这是对父类抽象方法的具体实现。
  @Override public SqlTypeName getSqlTypeName() {
    return typeName;
  }

  @Override public boolean isNullable() {
    return isNullable;
  }
  // 获取该类型所属的 SQL 类型族。
  @Override public RelDataTypeFamily getFamily() {
    SqlTypeFamily family = typeName.getFamily();
    // If typename does not have family, treat the current type as the only member its family
    return family != null ? family : this;
  }
  // 定义该类型在进行隐式类型转换时的“地位”。
  @Override public RelDataTypePrecedenceList getPrecedenceList() {
    RelDataTypePrecedenceList list =
        SqlTypeExplicitPrecedenceList.getListForType(this);
    if (list != null) {
      return list;
    }
    return super.getPrecedenceList();
  }
}
