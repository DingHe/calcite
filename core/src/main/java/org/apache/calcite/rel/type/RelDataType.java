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
package org.apache.calcite.rel.type;

import org.apache.calcite.sql.SqlCollation;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.type.SqlTypeName;

import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import java.nio.charset.Charset;
import java.util.List;

/**
 * RelDataType represents the type of a scalar expression or entire row returned
 * from a relational expression.
 * <p>This is a somewhat "fat" interface which unions the attributes of many
 * different type classes into one. Inelegant, but since our type system was
 * defined before the advent of Java generics, it avoids a lot of typecasting.
 */
// RelDataType 是一个至关重要的接口。它不仅定义了 SQL 数据的类型系统，还承载了关系表达式（Relational Expression）的元数据结构。
// RelDataType 代表了 Calcite 中的类型系统。它的作用主要体现在两个维度：
// 标量类型（Scalar Type）：描述单个表达式的类型（如 INTEGER, VARCHAR(20), BOOLEAN）。
// 结构化类型（Row Type / Struct Type）：描述关系运算结果的一整行结构。在这种情况下，它相当于一张表的“模式”（Schema），包含多个字段（Field），每个字段有自己的名称和类型。
public interface RelDataType {
  // 如果没有说明scale，则默认是整数最小值
  int SCALE_NOT_SPECIFIED = Integer.MIN_VALUE;
  // 精度没有说明，则为-1
  int PRECISION_NOT_SPECIFIED = -1;

  //~ Methods ----------------------------------------------------------------

  /**
   * Queries whether this is a structured type.
   *
   * @return whether this type has fields; examples include rows and
   * user-defined structured types in SQL, and classes in Java
   */
  // 判断当前类型是否为结构化类型（即是否包含多个字段）。
  // 返回 true 通常表示这是一行数据。
  @Pure
  boolean isStruct();

  // NOTE jvs 17-Dec-2004:  once we move to Java generics, getFieldList()
  // will be declared to return a read-only List<RelDataTypeField>,
  // and getFields() will be eliminated.  Currently,
  // anyone can mutate a type by poking into the array returned
  // by getFields!

  /**
   * Gets the fields in a struct type. The field count is equal to the size of
   * the returned list.
   *
   * @return read-only list of fields
   */
  // 获取字段列表。每个 RelDataTypeField 包含字段名、索引和类型。
  List<RelDataTypeField> getFieldList();

  /**
   * Returns the names of the fields in a struct type. The field count is
   * equal to the size of the returned list.
   *
   * @return read-only list of field names
   */
  // 快捷方法，仅获取所有字段的名称列表。
  List<String> getFieldNames();

  /**
   * Returns the number of fields in a struct type.
   *
   * <p>This method is equivalent to
   * <code>{@link #getFieldList}.size()</code>.
   */
  // 返回字段的数量。等同于 getFieldList().size()。
  int getFieldCount();

  /**
   * Returns the rule for resolving the fields of a structured type,
   * or {@link StructKind#NONE} if this is not a structured type.
   *
   * @return the StructKind that determines how this type's fields are resolved
   */
  // 返回结构体的种类（如 FULLY_QUALIFIED 或 PEEK_FIELDS），决定了在 SQL 转换中字段名如何被解析和展开。
  StructKind getStructKind();

  /**
   * Looks up a field by name.
   *
   * <p>NOTE: Be careful choosing the value of {@code caseSensitive}:
   * <ul>
   * <li>If the field name was supplied by an end-user (e.g. as a column alias
   * in SQL), use your session's case-sensitivity setting.</li>
   * <li>Only hard-code {@code true} if you are sure that the field name is
   * internally generated.</li>
   * <li>Hard-coding {@code false} is almost certainly wrong.</li>
   * </ul>
   * 根据field name找某个字段
   * @param fieldName Name of field to find
   * @param caseSensitive Whether match is case-sensitive
   * @param elideRecord Whether to find fields nested within records
   * @return named field, or null if not found
   */
  // 核心查询方法。
  // 通过名称查找特定字段。参数 caseSensitive 控制大小写敏感，elideRecord 控制是否在嵌套记录中递归查找。
  @Nullable RelDataTypeField getField(String fieldName, boolean caseSensitive,
      boolean elideRecord);

  /**
   * Queries whether this type allows null values.
   * @return whether type allows null values
   */
  // 判断该类型是否允许为 NULL。
  @Pure
  boolean isNullable();

  /**
   * Gets the component type if this type is a collection, otherwise null.
   * @return canonical type descriptor for components
   */
  // 如果是数组（ARRAY）或多集（MULTISET），返回其元素的类型。
  @Pure
  @Nullable RelDataType getComponentType();

  /**
   * Gets the key type if this type is a map, otherwise null.
   * @return canonical type descriptor for key
   */
  // 当类型为 MAP 时，分别获取其键和值的类型。
  @Nullable RelDataType getKeyType();

  /**
   * Gets the value type if this type is a map, otherwise null.
   * @return canonical type descriptor for value
   */
  // 当类型为 MAP 时，分别获取其键和值的类型。
  @Nullable RelDataType getValueType();

  /**
   * Gets the element type if this type is a measure, otherwise null.
   *
   * @return canonical type descriptor for the value used in the measure
   */
  // 如果是 MEASURE 类型（Calcite 特有的计算度量类型），返回其底层值的类型。
  default @Nullable RelDataType getMeasureElementType() {
    return null;
  }

  /**
   * Gets this type's character set, or null if this type cannot carry a
   * character set or has no character set defined.
   * @return charset of type
   */
  // 针对字符类型（VARCHAR, CHAR），返回其字符集（如 UTF-8）。
  @Pure
  @Nullable Charset getCharset();

  /**
   * Gets this type's collation, or null if this type cannot carry a collation
   * or has no collation defined.
   *
   * @return collation of type
   */
  // 返回排序规则（Collation），决定字符比较的逻辑。
  @Pure
  @Nullable SqlCollation getCollation();

  /**
   * Gets this type's interval qualifier, or null if this is not an interval
   * type.
   *
   * @return interval qualifier
   */
  // 如果类型是时间间隔（INTERVAL），返回其限定符（如 YEAR TO MONTH）。
  @Pure
  @Nullable SqlIntervalQualifier getIntervalQualifier();

  /**
   * Gets the JDBC-defined precision for values of this type. Note that this
   * is not always the same as the user-specified precision. For example, the
   * type INTEGER has no user-specified precision, but this method returns 10
   * for an INTEGER type.
   *
   * <p>Returns {@link #PRECISION_NOT_SPECIFIED} (-1) if precision is not
   * applicable for this type.
   *
   * @return number of decimal digits for exact numeric types; number of
   * decimal digits in mantissa for approximate numeric types; number of
   * decimal digits for fractional seconds of datetime types; length in
   * characters for character types; length in bytes for binary types; length
   * in bits for bit types; 1 for BOOLEAN; -1 if precision is not valid for
   * this type
   */
  // 获取精度。对于数字是总位数，对于字符串是字符长度，对于时间戳是秒的小数位数。
  int getPrecision();

  /**
   * Gets the scale of this type. Returns {@link #SCALE_NOT_SPECIFIED} (-1) if
   * scale is not valid for this type.
   *
   * @return number of digits of scale
   */
  // 获取数值的小数点后位数。
  int getScale();

  /**
   * Gets the {@link SqlTypeName} of this type.
   *
   * @return SqlTypeName, never null
   */
  // 返回该类型对应的 SQL 标准类型枚举（如 BIGINT, TIMESTAMP, ARRAY）
  SqlTypeName getSqlTypeName();

  /**
   * Gets the {@link SqlIdentifier} associated with this type. For a
   * predefined type, this is a simple identifier based on
   * {@link #getSqlTypeName}. For a user-defined type, this is a compound
   * identifier which uniquely names the type.
   *
   * @return SqlIdentifier, or null if this is not an SQL type
   */
  // 获取该类型的 SQL 标识符。自定义类型（UDT）会有复杂的标识符，内置类型则较简单。
  @Pure
  @Nullable SqlIdentifier getSqlIdentifier();

  /**
   * Gets a string representation of this type without detail such as
   * character set and nullability.
   *
   * @return abbreviated type string
   */
  // 返回简短的字符串描述（如 INTEGER）。
  @Override String toString();

  /**
   * Gets a string representation of this type with full detail such as
   * character set and nullability. The string must serve as a "digest" for
   * this type, meaning two types can be considered identical iff their
   * digests are equal.
   *
   * @return full type string
   */
  // 返回详细的“指纹”字符串，包含字符集、可空性等。Calcite 用此字符串判断两个类型对象在逻辑上是否完全一致。
  String getFullTypeString();

  /**
   * Gets a canonical object representing the family of this type. Two values
   * can be compared if and only if their types are in the same family.
   *
   * @return canonical object representing type family, never null
   */
  // 返回类型族（Family）。例如 VARCHAR 和 CHAR 属于同一个 String 族，同族之间通常可以相互比较或转换。
  RelDataTypeFamily getFamily();

  /** Returns the precedence list for this type.*/
  // 返回类型优先级列表，用于隐含类型转换（Implicit Casting）。
  RelDataTypePrecedenceList getPrecedenceList();

  /** Returns the category of comparison operators that make sense when applied
   * to values of this type. */
  // 定义该类型支持哪些比较操作（相等、排序等）。
  RelDataTypeComparability getComparability();

  /** Returns whether this type has dynamic structure (for "schema-on-read"
   * table). */
  // 判断是否为动态结构。
  // 常见于“读时模式”（Schema-on-read）的数据源（如 MongoDB 或 JSON），其字段在运行时才确定。
  boolean isDynamicStruct();

  /** Returns whether the field types are equal with each other by ignoring the
   * field names. If it is not a struct, just return the result of {@code
   * #equals(Object)}. */
  // 实用方法。
  // 比较两个结构化类型是否一致，但忽略字段名称。只要字段的数量和对应位置的类型相同，即返回 true。
  @API(since = "1.24", status = API.Status.INTERNAL)
  default boolean equalsSansFieldNames(@Nullable RelDataType that) {
    if (this == that) {
      return true;
    }
    if (that == null || getClass() != that.getClass()) {
      return false;
    }
    if (isStruct()) {
      List<RelDataTypeField> l1 = this.getFieldList();
      List<RelDataTypeField> l2 = that.getFieldList();
      if (l1.size() != l2.size()) {
        return false;
      }
      for (int i = 0; i < l1.size(); i++) {
        if (!l1.get(i).getType().equals(l2.get(i).getType())) {
          return false;
        }
      }
      return true;
    } else {
      return equals(that);
    }
  }
}
