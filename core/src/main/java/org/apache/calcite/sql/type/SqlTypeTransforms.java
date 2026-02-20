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
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.util.Util;

import java.util.ArrayList;
import java.util.List;

import static org.apache.calcite.sql.type.NonNullableAccessors.getCharset;
import static org.apache.calcite.sql.type.NonNullableAccessors.getCollation;

import static java.util.Objects.requireNonNull;

/**
 * SqlTypeTransforms defines a number of reusable instances of
 * {@link SqlTypeTransform}.
 *
 * <p>NOTE: avoid anonymous inner classes here except for unique,
 * non-generalizable strategies; anything else belongs in a reusable top-level
 * class. If you find yourself copying and pasting an existing strategy's
 * anonymous inner class, you're making a mistake.
 */
public abstract class SqlTypeTransforms {
  //~ Static fields/initializers ---------------------------------------------

  /** 如果操作数类型存在一个允许为空，则把typeToTransform转为可以允许为空的类型
   * Parameter type-inference transform strategy where a derived type is
   * transformed into the same type but nullable if any of a calls operands is
   * nullable.
   */
  public static final SqlTypeTransform TO_NULLABLE =
      (opBinding, typeToTransform) ->
          SqlTypeUtil.makeNullableIfOperandsAre(opBinding.getTypeFactory(),
              opBinding.collectOperandTypes(),
              requireNonNull(typeToTransform, "typeToTransform"));

  /**如果所有的操作数参数都允许为空，则返回允许为空的type
   * Parameter type-inference transform strategy where a derived type is
   * transformed into the same type, but nullable if and only if all of a call's
   * operands are nullable.
   */
  public static final SqlTypeTransform TO_NULLABLE_ALL = (opBinding, type) -> {
    final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
    return typeFactory.createTypeWithNullability(type,
        SqlTypeUtil.allNullable(opBinding.collectOperandTypes()));
  };

  /** 将目标类型转换为不可空类型，强制返回结果类型为不可空类型，忽略操作数的 nullability
   * Parameter type-inference transform strategy where a derived type is
   * transformed into the same type but not nullable.
   */
  public static final SqlTypeTransform TO_NOT_NULLABLE =
      (opBinding, typeToTransform) ->
          opBinding.getTypeFactory().createTypeWithNullability(
              requireNonNull(typeToTransform, "typeToTransform"), false);

  /** 将目标类型强制转换为可空类型，不论操作数的 nullability 如何
   * Parameter type-inference transform strategy where a derived type is
   * transformed into the same type with nulls allowed.
   */
  public static final SqlTypeTransform FORCE_NULLABLE =
      (opBinding, typeToTransform) ->
          opBinding.getTypeFactory().createTypeWithNullability(
              requireNonNull(typeToTransform, "typeToTransform"), true);

  /** 如果任何一个操作数不是可空类型，则返回一个不可空类型，否则返回原类型
   * Type-inference strategy whereby the result is NOT NULL if any of
   * the arguments is NOT NULL; otherwise the type is unchanged.
   */
  public static final SqlTypeTransform LEAST_NULLABLE =
      (opBinding, typeToTransform) -> {
        for (RelDataType type : opBinding.collectOperandTypes()) {
          if (!type.isNullable()) {
            return opBinding.getTypeFactory()
                .createTypeWithNullability(typeToTransform, false);
          }
        }
        return typeToTransform;
      };

  /** 根据第一个参数的类型推断结果类型的 nullability
   * Parameter type-inference transform strategy where a derived type is
   * transformed into the same type, but nullable if and only if the type
   * of a call's operand #0 (0-based) is nullable.
   */
  public static final SqlTypeTransform ARG0_NULLABLE =
      (opBinding, typeToTransform) -> {
        RelDataType arg0 = opBinding.getOperandType(0);
        if (arg0.isNullable()) {
          return opBinding.getTypeFactory()
              .createTypeWithNullability(typeToTransform, true);
        }
        return typeToTransform;
      };

  /** 如果集合类型中的任何一个元素类型是可空的，则将目标类型转换为可空类型
   * Parameter type-inference transform strategy where a derived type is
   * transformed into the same type but nullable if any of element of a calls operands is
   * nullable.
   */
  public static final SqlTypeTransform COLLECTION_ELEMENT_TYPE_NULLABLE =
      (opBinding, typeToTransform) -> {
        final List<RelDataType> argComponentTypes = new ArrayList<>();
        for (RelDataType arrayType : opBinding.collectOperandTypes()) {
          final RelDataType componentType = arrayType.getComponentType();
          if (componentType == null) {
            // NULL supplied for array
            return arrayType;
          }
          argComponentTypes.add(componentType);
        }

        if (argComponentTypes.stream().anyMatch(RelDataType::isNullable)) {
          return opBinding.getTypeFactory()
              .createTypeWithNullability(typeToTransform, true);
        }
        return typeToTransform;
      };

  /** 将目标类型转换为可变类型，通常用于字符串类型（如 VARCHAR 或 VARBINARY）
   * Type-inference strategy whereby the result type of a call is VARYING the
   * type given. The length returned is the same as length of the first
   * argument. Return type will have same nullability as input type
   * nullability. First Arg must be of string type.
   */
  public static final SqlTypeTransform TO_VARYING =
      new SqlTypeTransform() {
        @Override public RelDataType transformType(
            SqlOperatorBinding opBinding,
            RelDataType typeToTransform) {
          switch (typeToTransform.getSqlTypeName()) {
          case VARCHAR:
          case VARBINARY:
            return typeToTransform;
          default:
            break;
          }

          SqlTypeName retTypeName = toVar(typeToTransform);

          RelDataType ret =
              opBinding.getTypeFactory().createSqlType(
                  retTypeName,
                  typeToTransform.getPrecision());
          if (SqlTypeUtil.inCharFamily(typeToTransform)) {
            ret =
                opBinding.getTypeFactory()
                    .createTypeWithCharsetAndCollation(
                        ret,
                        getCharset(typeToTransform),
                        getCollation(typeToTransform));
          }
          return opBinding.getTypeFactory().createTypeWithNullability(
              ret,
              typeToTransform.isNullable());
        }

        private SqlTypeName toVar(RelDataType type) {
          final SqlTypeName sqlTypeName = type.getSqlTypeName();
          switch (sqlTypeName) {
          case CHAR:
            return SqlTypeName.VARCHAR;
          case BINARY:
            return SqlTypeName.VARBINARY;
          case ANY:
            return SqlTypeName.ANY;
          case NULL:
            return SqlTypeName.NULL;
          case UNKNOWN:
            return SqlTypeName.UNKNOWN;
          default:
            throw Util.unexpected(sqlTypeName);
          }
        }
      };

  /**
   * Parameter type-inference transform strategy where a derived type must be
   * a multiset or array type and the returned type is element type.
   * 将集合类型转换为其元素类型
   * @see MultisetSqlType#getComponentType
   * @see ArraySqlType#getComponentType
   */
  public static final SqlTypeTransform TO_COLLECTION_ELEMENT_TYPE =
      (opBinding, typeToTransform) -> requireNonNull(
          typeToTransform.getComponentType(),
          () -> "componentType for " + typeToTransform + " in opBinding " + opBinding);

  /**
   * Parameter type-inference transform strategy that wraps a given type
   * in a multiset.
   * 将某种类型包装为多重集合（Multiset）
   * @see org.apache.calcite.rel.type.RelDataTypeFactory#createMultisetType(RelDataType, long)
   */
  public static final SqlTypeTransform TO_MULTISET =
      (opBinding, typeToTransform) ->
          opBinding.getTypeFactory().createMultisetType(typeToTransform, -1);

  /**
   * Parameter type-inference transform strategy that wraps a given type in a multiset or
   * wraps a field of the given type in a multiset if the given type is struct with one field.
   * It is used when a multiset input is a sub-query.
   */
  public static final SqlTypeTransform TO_MULTISET_QUERY =
      (opBinding, typeToTransform) ->
          TO_MULTISET.transformType(opBinding,
              SqlTypeUtil.deriveCollectionQueryComponentType(SqlTypeName.MULTISET,
                  typeToTransform));

  /**
   * Parameter type-inference transform strategy that wraps a given type
   * in an array.
   * 将某种类型包装为数组
   * @see org.apache.calcite.rel.type.RelDataTypeFactory#createArrayType(RelDataType, long)
   */
  public static final SqlTypeTransform TO_ARRAY =
      (opBinding, typeToTransform) ->
          opBinding.getTypeFactory().createArrayType(typeToTransform, -1);

  /**
   * Parameter type-inference transform strategy that wraps a given type in an array,
   * but nullable if any of element of a calls operands is nullable.
   */
  public static final SqlTypeTransform TO_ARRAY_NULLABLE =
      (opBinding, typeToTransform) ->
          TO_NULLABLE.transformType(opBinding, TO_ARRAY.transformType(opBinding, typeToTransform));

  /** Parameter type-inference transform that transforms {@code T} to
   * {@code MEASURE<T>} for some type T. 将类型转换为 MEASURE 类型，或将 MEASURE 类型转换回其原始类型*/
  public static final SqlTypeTransform TO_MEASURE =
      (opBinding, typeToTransform) ->
          opBinding.getTypeFactory().createMeasureType(typeToTransform);

  /** Parameter type-inference transform that transforms {@code MEASURE<T>} to
   * {@code T} for some type T. Inverse of {@link #TO_MEASURE}.将类型转换为 MEASURE 类型，或将 MEASURE 类型转换回其原始类型 */
  public static final SqlTypeTransform FROM_MEASURE =
      (opBinding, typeToTransform) ->
          ((MeasureSqlType) typeToTransform).types.get(0);

  /**
   * Parameter type-inference transform strategy that wraps a given type in an array or
   * wraps a field of the given type in an array if the given type is struct with one field.
   * It is used when an array input is a sub-query.
   */
  public static final SqlTypeTransform TO_ARRAY_QUERY =
      (opBinding, typeToTransform) ->
        TO_ARRAY.transformType(opBinding,
            SqlTypeUtil.deriveCollectionQueryComponentType(SqlTypeName.ARRAY, typeToTransform));

  /**
   * Parameter type-inference transform strategy that converts a two-field
   * record type to a MAP type.
   * 将记录类型转换为键值对类型（如 MAP）
   * @see org.apache.calcite.rel.type.RelDataTypeFactory#createMapType
   */
  public static final SqlTypeTransform TO_MAP =
      (opBinding, typeToTransform) ->
          SqlTypeUtil.createMapTypeFromRecord(opBinding.getTypeFactory(),
              typeToTransform);

  /**
   * Parameter type-inference transform strategy that converts a two-field
   * record type to a MAP query type.
   *
   * @see org.apache.calcite.sql.fun.SqlMapQueryConstructor
   */
  public static final SqlTypeTransform TO_MAP_QUERY =
      (opBinding, typeToTransform) ->
          TO_MAP.transformType(opBinding,
              SqlTypeUtil.deriveCollectionQueryComponentType(SqlTypeName.MAP, typeToTransform));

  /**
   * Parameter type-inference transform strategy that converts a type to a MAP type,
   * which key and value type is same.
   *
   * @see org.apache.calcite.rel.type.RelDataTypeFactory#createMapType
   */
  public static final SqlTypeTransform IDENTITY_TO_MAP =
      (opBinding, typeToTransform) ->
          SqlTypeUtil.createMapType(opBinding.getTypeFactory(),
              typeToTransform, typeToTransform, false);

  /**
   * Parameter type-inference transform strategy that converts a MAP type
   * to a two-field record type.
   * 将 MAP 类型转换为具有字段的结构类型
   * @see org.apache.calcite.rel.type.RelDataTypeFactory#createStructType
   */
  public static final SqlTypeTransform TO_ROW =
      (opBinding, typeToTransform) ->
          SqlTypeUtil.createRecordTypeFromMap(opBinding.getTypeFactory(),
              typeToTransform);

  /**
   * Parameter type-inference transform strategy that converts a MAP type
   * to a ARRAY type.
   *
   * @see org.apache.calcite.rel.type.RelDataTypeFactory#createArrayType
   */
  public static final SqlTypeTransform TO_MAP_KEYS =
      (opBinding, typeToTransform) -> {
        RelDataType keyType =
            requireNonNull(typeToTransform.getKeyType(),
                () -> "keyType for " + typeToTransform + " in opBinding " + opBinding);
        return opBinding.getTypeFactory().createArrayType(keyType, -1);
      };

  /**
   * Parameter type-inference transform strategy that converts a MAP type
   * to a ARRAY type.
   *
   * @see org.apache.calcite.rel.type.RelDataTypeFactory#createArrayType
   */
  public static final SqlTypeTransform TO_MAP_VALUES =
      (opBinding, typeToTransform) -> {
        RelDataType keyType =
            requireNonNull(typeToTransform.getValueType(),
                () -> "valueType for " + typeToTransform + " in opBinding " + opBinding);
        return opBinding.getTypeFactory().createArrayType(keyType, -1);
      };

  /**
   * Parameter type-inference transform strategy where a derived type must be
   * a struct type with precisely one field and the returned type is the type
   * of that field.
   */
  public static final SqlTypeTransform ONLY_COLUMN =
      (opBinding, typeToTransform) -> {
        final List<RelDataTypeField> fields = typeToTransform.getFieldList();
        assert fields.size() == 1;
        return fields.get(0).getType();
      };

}
