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

import org.apache.calcite.avatica.util.TimeUnit;
import org.apache.calcite.sql.SqlCollation;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeMappingRule;
import org.apache.calcite.sql.type.SqlTypeMappingRules;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidatorUtil;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RelDataTypeFactory is a factory for datatype descriptors. It defines methods
 * for instantiating and combining SQL, Java, and collection types. The factory
 * also provides methods for return type inference for arithmetic in cases where
 * SQL 2003 is implementation defined or impractical.
 *
 * <p>This interface is an example of the
 * {@link org.apache.calcite.util.Glossary#ABSTRACT_FACTORY_PATTERN abstract factory pattern}.
 * Any implementation of <code>RelDataTypeFactory</code> must ensure that type
 * objects are canonical: two types are equal if and only if they are
 * represented by the same Java object. This reduces memory consumption and
 * comparison cost.
 */
// 在 Apache Calcite 中，RelDataTypeFactory 是整个类型系统的核心工厂接口。
// 它遵循抽象工厂模式（Abstract Factory Pattern），负责创建、组合和管理所有的类型描述符（RelDataType）。
// 统一入口：它是创建 SQL 类型、Java 类型、集合类型（Array/Map）以及结构化类型（Struct/Record）的唯一入口。
// 规范化（Canonicalization）：这是该接口最重要的设计原则。实现类必须保证相同语义的类型对应同一个 Java 对象。这样可以通过 == 快速比较类型，并极大节省内存。
// 桥接物理与逻辑：它提供了将 Java 类映射为 SQL 类型的方法，是内存数据源（如 Enumerable 算子）与 SQL 逻辑层之间的桥梁。
// 辅助类型推导：提供加减乘除、Union 等操作后的结果类型计算建议。
public interface RelDataTypeFactory {
  //~ Methods ----------------------------------------------------------------

  /**
   * Returns the type system.
   *
   * @return Type system
   */
  // 返回当前工厂绑定的 RelDataTypeSystem。工厂在创建类型（如 DECIMAL）时，需要参考类型系统的精度上限。
  RelDataTypeSystem getTypeSystem();

  /**
   * Creates a type that corresponds to a Java class.
   *
   * @param clazz the Java class used to define the type
   * @return canonical Java type descriptor
   */
  // 根据 Java 类反射信息创建类型（通常生成 JavaType 或 JavaRecordType）。
  RelDataType createJavaType(Class clazz);

  /**
   * Creates a cartesian product type.
   *
   * @return canonical join type descriptor
   * @param types array of types to be joined
   */
  // 将多个记录类型合并为一个 RelCrossType（用于 Join 操作产生的中间类型）。
  RelDataType createJoinType(RelDataType... types);

  /**
   * Creates a type that represents a structured collection of fields, given
   * lists of the names and types of the fields.
   *
   * @param  kind         Name resolution policy
   * @param typeList      types of the fields
   * @param fieldNameList names of the fields
   * @return canonical struct type descriptor
   */
  // 最基础的结构化创建方法，手动指定 StructKind（如 FULLY_QUALIFIED）。
  RelDataType createStructType(StructKind kind,
      List<RelDataType> typeList,
      List<String> fieldNameList);

  /** Creates a type that represents a structured collection of fields.
   * Shorthand for <code>createStructType(StructKind.FULLY_QUALIFIED, typeList,
   * fieldNameList)</code>. */
  // 便捷方法，默认创建标准结构体。
  RelDataType createStructType(
      List<RelDataType> typeList,
      List<String> fieldNameList);

  /**
   * Creates a type that represents a structured collection of fields,
   * obtaining the field information via a callback.
   *
   * @param fieldInfo callback for field information
   * @return canonical struct type descriptor
   */
  @Deprecated // to be removed before 2.0
  RelDataType createStructType(FieldInfo fieldInfo);

  /**
   * Creates a type that represents a structured collection of fieldList,
   * obtaining the field information from a list of (name, type) pairs.
   *
   * @param fieldList List of (name, type) pairs
   * @return canonical struct type descriptor
   */
  // 通过 Map.Entry 列表（包含名和类型对）创建。
  RelDataType createStructType(
      List<? extends Map.Entry<String, RelDataType>> fieldList);

  /**
   * Creates an array type. Arrays are ordered collections of elements.
   *
   * @param elementType    type of the elements of the array
   * @param maxCardinality maximum array size, or -1 for unlimited
   * @return canonical array type descriptor
   */
  // 创建数组类型。maxCardinality 为 -1 表示无限制。
  RelDataType createArrayType(
      RelDataType elementType,
      long maxCardinality);

  /**
   * Creates a map type. Maps are unordered collections of key/value pairs.
   *
   * @param keyType   type of the keys of the map
   * @param valueType type of the values of the map
   * @return canonical map type descriptor
   */
  // 创建键值对映射类型。
  RelDataType createMapType(
      RelDataType keyType,
      RelDataType valueType);

  /**
   * Creates a function type.
   *
   * @param parameterType type of parameters
   * @param returnType type of lambda expression return type
   * @return function type descriptor
   */
  // 创建函数/Lambda 类型。
  RelDataType createFunctionSqlType(
      RelDataType parameterType,
      RelDataType returnType);

  /**
   * Creates a measure type.
   *
   * @param valueType type of the values of the measure
   * @return canonical measure type descriptor
   */
  // 创建度量类型（用于处理 SQL 聚合增强功能）。
  RelDataType createMeasureType(RelDataType valueType);

  /**
   * Creates a multiset type. Multisets are unordered collections of elements.
   *
   * @param elementType    type of the elements of the multiset
   * @param maxCardinality maximum collection size, or -1 for unlimited
   * @return canonical multiset type descriptor
   */
  // 创建多重集类型（类似于无序数组）。
  RelDataType createMultisetType(
      RelDataType elementType,
      long maxCardinality);

  /**
   * Duplicates a type, making a deep copy. Normally, this is a no-op, since
   * canonical type objects are returned. However, it is useful when copying a
   * type from one factory to another.
   *
   * @param type input type
   * @return output type, a new object equivalent to input type
   */
  // 跨工厂复制类型，确保在新工厂中也是规范化的。
  RelDataType copyType(RelDataType type);

  /**
   * Creates a type that is the same as another type but with possibly
   * different nullability. The output type may be identical to the input
   * type. For type systems without a concept of nullability, the return value
   * is always the same as the input.
   *
   * @param type     input type
   * @param nullable true to request a nullable type; false to request a NOT
   *                 NULL type
   * @return output type, same as input type except with specified nullability
   * @throws NullPointerException if type is null
   */
  // 克隆一个类型并改变其可为空性（这是 Calcite 中处理 NULL 的常用手段）。
  RelDataType createTypeWithNullability(
      RelDataType type,
      boolean nullable);

  /**
   * Creates a type that is the same as another type but with possibly
   * different charset or collation. For types without a concept of charset or
   * collation this function must throw an error.
   *
   * @param type      input type
   * @param charset   charset to assign
   * @param collation collation to assign
   * @return output type, same as input type except with specified charset and
   * collation
   */
  // 为字符类型分配字符集和排序规则。
  RelDataType createTypeWithCharsetAndCollation(
      RelDataType type,
      Charset charset,
      SqlCollation collation);

  /** Returns the default {@link Charset} (valid if this is a string type). */
  // 获取工厂默认的字符集（通常是 UTF-16 或系统默认）。
  Charset getDefaultCharset();

  /**
   * Returns the most general of a set of types
   * using the default type mapping rule.
   *
   * @see #leastRestrictive(List, SqlTypeMappingRule)
   *
   * @param types input types to be combined using union (not null, not empty)
   * @return canonical union type descriptor
   */
  // 在一组类型中寻找“最小泛化类型”。例如输入 INT 和 FLOAT，返回 FLOAT；输入不同长度的 VARCHAR，返回最长的一个。
  default @Nullable RelDataType leastRestrictive(List<RelDataType> types) {
    return leastRestrictive(types, SqlTypeMappingRules.instance(false));
  }

  /**
   * Returns the most general of a set of types (that is, one type to which
   * they can all be cast), or null if conversion is not possible. The result
   * may be a new type that is less restrictive than any of the input types,
   * e.g. <code>leastRestrictive(INT, NUMERIC(3, 2))</code> could be
   * {@code NUMERIC(12, 2)}.
   *
   * <p>Accepts a {@link SqlTypeMappingRule} that can be used to change casting
   * behavior.
   *
   * @param types input types to be combined using union (not null, not empty)
   * @param mappingRule rule that determines whether types are convertible
   * @return canonical union type descriptor
   */
  // 在一组类型中寻找“最小泛化类型”。例如输入 INT 和 FLOAT，返回 FLOAT；输入不同长度的 VARCHAR，返回最长的一个。
  @Nullable RelDataType leastRestrictive(List<RelDataType> types,
      SqlTypeMappingRule mappingRule);

  /**
   * Creates a SQL type with no precision or scale.
   *
   * @param typeName Name of the type, for example {@link SqlTypeName#BOOLEAN},
   *   never null
   * @return canonical type descriptor
   */
  // 创建不带精度和标度的 SQL 类型（如 BOOLEAN, INTEGER）。
  RelDataType createSqlType(SqlTypeName typeName);

  /**
   * Creates a SQL type that represents the "unknown" type.
   * It is only equal to itself, and is distinct from the NULL type.
   *
   * @return unknown type
   */
  // 创建一个特殊的“未知”类型（通常用于解析阶段的 NULL 占位符）。
  RelDataType createUnknownType();

  /**
   * Creates a SQL type with length (precision) but no scale.
   *
   * @param typeName  Name of the type, for example {@link SqlTypeName#VARCHAR}.
   *                  Never null.
   * @param precision Maximum length of the value (non-numeric types) or the
   *                  precision of the value (numeric/datetime types).
   *                  Must be non-negative or
   *                  {@link RelDataType#PRECISION_NOT_SPECIFIED}.
   * @return canonical type descriptor
   */
  // 创建带长度/精度的类型（如 VARCHAR(20), CHAR(5)）。
  RelDataType createSqlType(
      SqlTypeName typeName,
      int precision);

  /**
   * Creates a SQL type with precision and scale.
   *
   * @param typeName  Name of the type, for example {@link SqlTypeName#DECIMAL}.
   *                  Never null.
   * @param precision Precision of the value.
   *                  Must be non-negative or
   *                  {@link RelDataType#PRECISION_NOT_SPECIFIED}.
   * @param scale     scale of the values, i.e. the number of decimal places to
   *                  shift the value. For example, a NUMBER(10,3) value of
   *                  "123.45" is represented "123450" (that is, multiplied by
   *                  10^3). A negative scale <em>is</em> valid.
   * @return canonical type descriptor
   */
  // 创建带精度和标度的类型（如 DECIMAL(10, 2)）。
  RelDataType createSqlType(
      SqlTypeName typeName,
      int precision,
      int scale);

  /**
   * Creates a SQL interval type.
   *
   * @param intervalQualifier contains information if it is a year-month or a
   *                          day-time interval along with precision information
   * @return canonical type descriptor
   */
  // 根据间隔限定符（如 YEAR TO MONTH）创建时间间隔类型。
  RelDataType createSqlIntervalType(
      SqlIntervalQualifier intervalQualifier);

  /**
   * Infers the return type of a decimal multiplication. Decimal
   * multiplication involves at least one decimal operand and requires both
   * operands to have exact numeric types.
   *
   * @param type1 type of the first operand
   * @param type2 type of the second operand
   * @return the result type for a decimal multiplication, or null if decimal
   * multiplication should not be applied to the operands.
   * @deprecated Use
   * {@link RelDataTypeSystem#deriveDecimalMultiplyType(RelDataTypeFactory, RelDataType, RelDataType)}
   */
  @Deprecated // to be removed before 2.0
  @Nullable RelDataType createDecimalProduct(
      RelDataType type1,
      RelDataType type2);

  /**
   * Returns whether a decimal multiplication should be implemented by casting
   * arguments to double values.
   *
   * <p>Pre-condition: <code>createDecimalProduct(type1, type2) != null</code>
   *
   * @deprecated Use
   * {@link RelDataTypeSystem#shouldUseDoubleMultiplication(RelDataTypeFactory, RelDataType, RelDataType)}
   */
  @Deprecated // to be removed before 2.0
  boolean useDoubleMultiplication(
      RelDataType type1,
      RelDataType type2);

  /**
   * Infers the return type of a decimal division. Decimal division involves
   * at least one decimal operand and requires both operands to have exact
   * numeric types.
   *
   * @param type1 type of the first operand
   * @param type2 type of the second operand
   * @return the result type for a decimal division, or null if decimal
   * division should not be applied to the operands.
   *
   * @deprecated Use
   * {@link RelDataTypeSystem#deriveDecimalDivideType(RelDataTypeFactory, RelDataType, RelDataType)}
   */
  @Deprecated // to be removed before 2.0
  @Nullable RelDataType createDecimalQuotient(
      RelDataType type1,
      RelDataType type2);

  /**
   * Create a decimal type equivalent to the numeric {@code type},
   * this is related to specific system implementation,
   * you can override this logic if it is required.
   *
   * @param type the numeric type to create decimal type with
   * @return decimal equivalence of the numeric type.
   */
  // 将一个普通的数值类型强制转换为等价的 DECIMAL 类型。
  RelDataType decimalOf(RelDataType type);

  /**
   * Creates a
   * {@link org.apache.calcite.rel.type.RelDataTypeFactory.FieldInfoBuilder}.
   * But since {@code FieldInfoBuilder} is deprecated, we recommend that you use
   * its base class {@link Builder}, which is not deprecated.
   */
  @SuppressWarnings("deprecation")
  FieldInfoBuilder builder();

  //~ Inner Interfaces -------------------------------------------------------

  /**
   * Callback that provides enough information to create fields.
   */
  @Deprecated // to be removed before 2.0
  interface FieldInfo { //结构化类型的Field信息
    /**
     * Returns the number of fields.
     *
     * @return number of fields
     */
    int getFieldCount();

    /**
     * Returns the name of a given field.
     *
     * @param index Ordinal of field
     * @return Name of given field
     */
    String getFieldName(int index);

    /**
     * Returns the type of a given field.
     *
     * @param index Ordinal of field
     * @return Type of given field
     */
    RelDataType getFieldType(int index);
  }

  /**
   * Implementation of {@link FieldInfo} that provides a fluid API to build
   * a list of fields.
   */
  // 由于手动构建复杂的 RelRecordType 比较繁琐，接口内部提供了流式 API：
  // 这是目前推荐的构建字段列表的方式：
  @Deprecated
  @SuppressWarnings("deprecation")
  class FieldInfoBuilder extends Builder implements FieldInfo {
    public FieldInfoBuilder(RelDataTypeFactory typeFactory) {
      super(typeFactory);
    }

    @Override public FieldInfoBuilder add(String name, RelDataType type) {
      return (FieldInfoBuilder) super.add(name, type);
    }

    @Override public FieldInfoBuilder add(String name, SqlTypeName typeName) {
      return (FieldInfoBuilder) super.add(name, typeName);
    }

    @Override public FieldInfoBuilder add(String name, SqlTypeName typeName,
        int precision) {
      return (FieldInfoBuilder) super.add(name, typeName, precision);
    }

    @Override public FieldInfoBuilder add(String name, SqlTypeName typeName,
        int precision, int scale) {
      return (FieldInfoBuilder) super.add(name, typeName, precision, scale);
    }

    @Override public FieldInfoBuilder add(String name, TimeUnit startUnit,
        int startPrecision, TimeUnit endUnit, int fractionalSecondPrecision) {
      return (FieldInfoBuilder) super.add(name, startUnit, startPrecision,
          endUnit, fractionalSecondPrecision);
    }

    @Override public FieldInfoBuilder nullable(boolean nullable) {
      return (FieldInfoBuilder) super.nullable(nullable);
    }

    @Override public FieldInfoBuilder add(RelDataTypeField field) {
      return (FieldInfoBuilder) super.add(field);
    }

    @Override public FieldInfoBuilder addAll(
        Iterable<? extends Map.Entry<String, RelDataType>> fields) {
      return (FieldInfoBuilder) super.addAll(fields);
    }

    @Override public FieldInfoBuilder kind(StructKind kind) {
      return (FieldInfoBuilder) super.kind(kind);
    }

    @Override public FieldInfoBuilder uniquify() {
      return (FieldInfoBuilder) super.uniquify();
    }
  }

  /** Fluid API to build a list of fields. */
  class Builder {
    private final List<String> names = new ArrayList<>(); //字段名称
    private final List<RelDataType> types = new ArrayList<>(); //对应的字段类型，1 v 1映射
    private StructKind kind = StructKind.FULLY_QUALIFIED;
    private final RelDataTypeFactory typeFactory;
    private boolean nullableRecord = false;

    /**
     * Creates a Builder with the given type factory.
     */
    public Builder(RelDataTypeFactory typeFactory) {
      this.typeFactory = Objects.requireNonNull(typeFactory, "typeFactory");
    }

    /**
     * Returns the number of fields.
     *
     * @return number of fields
     */
    public int getFieldCount() {
      return names.size();
    }

    /**
     * Returns the name of a given field.
     *
     * @param index Ordinal of field
     * @return Name of given field
     */
    public String getFieldName(int index) {
      return names.get(index);
    }

    /**
     * Returns the type of a given field.
     *
     * @param index Ordinal of field
     * @return Type of given field
     */
    public RelDataType getFieldType(int index) {
      return types.get(index);
    }

    /** 添加一个字段
     * Adds a field with given name and type.
     */
    public Builder add(String name, RelDataType type) {
      names.add(name);
      types.add(type);
      return this;
    }

    /**
     * Adds a field with a type created using
     * {@link org.apache.calcite.rel.type.RelDataTypeFactory#createSqlType(org.apache.calcite.sql.type.SqlTypeName)}.
     */
    public Builder add(String name, SqlTypeName typeName) {
      add(name, typeFactory.createSqlType(typeName));
      return this;
    }

    /**
     * Adds a field with a type created using
     * {@link org.apache.calcite.rel.type.RelDataTypeFactory#createSqlType(org.apache.calcite.sql.type.SqlTypeName, int)}.
     */
    public Builder add(String name, SqlTypeName typeName, int precision) {
      add(name, typeFactory.createSqlType(typeName, precision));
      return this;
    }

    /**
     * Adds a field with a type created using
     * {@link org.apache.calcite.rel.type.RelDataTypeFactory#createSqlType(org.apache.calcite.sql.type.SqlTypeName, int, int)}.
     */
    public Builder add(String name, SqlTypeName typeName, int precision,
        int scale) {
      add(name, typeFactory.createSqlType(typeName, precision, scale));
      return this;
    }

    /**
     * Adds a field with an interval type.
     */
    public Builder add(String name, TimeUnit startUnit, int startPrecision,
        TimeUnit endUnit, int fractionalSecondPrecision) {
      final SqlIntervalQualifier q =
          new SqlIntervalQualifier(startUnit, startPrecision, endUnit,
              fractionalSecondPrecision, SqlParserPos.ZERO);
      add(name, typeFactory.createSqlIntervalType(q));
      return this;
    }

    /**
     * Changes the nullability of the last field added.
     *
     * @throws java.lang.IndexOutOfBoundsException if no fields have been
     *                                             added
     */
    public Builder nullable(boolean nullable) {
      RelDataType lastType = types.get(types.size() - 1);
      if (lastType.isNullable() != nullable) {
        final RelDataType type =
            typeFactory.createTypeWithNullability(lastType, nullable);
        types.set(types.size() - 1, type);
      }
      return this;
    }

    /**
     * Adds a field. Field's ordinal is ignored.
     */
    public Builder add(RelDataTypeField field) {
      add(field.getName(), field.getType());
      return this;
    }

    /** 添加一个Map迭代器的所有字段
     * Adds all fields in a collection.
     */
    public Builder addAll(
        Iterable<? extends Map.Entry<String, RelDataType>> fields) {
      for (Map.Entry<String, RelDataType> field : fields) {
        add(field.getKey(), field.getValue());
      }
      return this;
    }

    public Builder kind(StructKind kind) {
      this.kind = kind;
      return this;
    }

    /** Sets whether the record type will be nullable. */
    public Builder nullableRecord(boolean nullableRecord) {
      this.nullableRecord = nullableRecord;
      return this;
    }

    /**
     * Makes sure that field names are unique.
     */
    public Builder uniquify() {
      final List<String> uniqueNames =
          SqlValidatorUtil.uniquify(names,
              typeFactory.getTypeSystem().isSchemaCaseSensitive());
      if (uniqueNames != names) {
        names.clear();
        names.addAll(uniqueNames);
      }
      return this;
    }

    /**
     * Creates a struct type with the current contents of this builder.
     */
    public RelDataType build() {
      return typeFactory.createTypeWithNullability(
          typeFactory.createStructType(kind, types, names),
          nullableRecord);
    }

    /** Creates a dynamic struct type with the current contents of this
     * builder. */
    public RelDataType buildDynamic() {
      final RelDataType dynamicType = new DynamicRecordTypeImpl(typeFactory);
      final RelDataType type = build();
      dynamicType.getFieldList().addAll(type.getFieldList());
      return dynamicType;
    }

    /** Returns whether a field exists with the given name. */
    public boolean nameExists(String name) {
      return names.contains(name);
    }
  }
}
