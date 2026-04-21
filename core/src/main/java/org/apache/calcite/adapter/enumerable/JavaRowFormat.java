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
package org.apache.calcite.adapter.enumerable;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.interpreter.Row;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.IndexExpression;
import org.apache.calcite.linq4j.tree.MemberExpression;
import org.apache.calcite.linq4j.tree.MethodCallExpression;
import org.apache.calcite.linq4j.tree.Types;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.runtime.FlatLists;
import org.apache.calcite.runtime.Unit;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.BuiltInMethod;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Type;
import java.util.List;

/**
 * How a row is represented as a Java value.
 */
// 定义了逻辑上的 SQL 行数据在物理上如何映射为 Java 对象。
// JavaRowFormat 的主要作用是提供代码生成的策略。
// 当 Calcite 将关系代数（RelNode）转换成 Java 代码时，它需要决定用什么 Java 数据结构来表示“一行”。不同的存储方式（如数组、POJO 类、单值）在访问字段和创建记录时生成的代码逻辑完全不同。
// JavaRowFormat 封装了这些差异，使得生成的代码能够正确地访问字段、实例化行对象以及比较数据。
public enum JavaRowFormat {
  CUSTOM {
    // 确定表示整行的 Java 类型。
    @Override Type javaRowClass(
        JavaTypeFactory typeFactory,
        RelDataType type) {
      // // 确保字段数大于1（否则通常会优化为SCALAR）
      assert type.getFieldCount() > 1;
      return typeFactory.getJavaClass(type);
    }
    // 获取行中某个特定位置字段的 Java 类型。
    @Override Type javaFieldClass(JavaTypeFactory typeFactory, RelDataType type,
        int index) {
      return typeFactory.getJavaClass(type.getFieldList().get(index).getType());
    }
    // 生成创建行的表达式
    @Override public Expression record(
        Type javaRowClass, List<Expression> expressions) {
      switch (expressions.size()) {
      case 0:
        assert javaRowClass == Unit.class;
        // // 返回单例实例
        return Expressions.field(null, javaRowClass, "INSTANCE");
      default:
        // 生成 new MyClass(arg1, arg2...)
        return Expressions.new_(javaRowClass, expressions);
      }
    }

    @Override public MemberExpression field(Expression expression, int field,
        @Nullable Type fromType, Type fieldType) {
      final Type type = expression.getType();
      if (type instanceof Types.RecordType) {
        Types.RecordType recordType = (Types.RecordType) type;
        Types.RecordField recordField =
            recordType.getRecordFields().get(field);
        return Expressions.field(expression, recordField.getDeclaringClass(),
            recordField.getName());
      } else {
        return Expressions.field(expression, Types.nthField(field, type));
      }
    }
  },
  // 当 SQL 查询结果只有一列时（例如 SELECT count(*) FROM table），Calcite 会为了性能和内存优化，选择不将这个单值包装在数组或对象中，而是直接使用该值本身。
  //
  SCALAR {
    @Override Type javaRowClass(
        JavaTypeFactory typeFactory,
        RelDataType type) {
      // // 强制断言：字段数必须为 1
      assert type.getFieldCount() == 1;
      RelDataType field0Type = type.getFieldList().get(0).getType();
      // nested ROW type is always represented as array.
      // 嵌套的 ROW 类型始终表示为数组
      if (field0Type.getSqlTypeName() == SqlTypeName.ROW) {
        return Object[].class;
      }
      // // 否则返回该唯一列对应的 Java 类型（如 Integer.class, String.class）
      return typeFactory.getJavaClass(
          type.getFieldList().get(0).getType());
    }

    @Override Type javaFieldClass(JavaTypeFactory typeFactory, RelDataType type,
        int index) {
      return javaRowClass(typeFactory, type);
    }

    @Override public Expression record(Type javaRowClass, List<Expression> expressions) {
      assert expressions.size() == 1;
      return expressions.get(0);
    }

    @Override public Expression field(Expression expression, int field, @Nullable Type fromType,
        Type fieldType) {
      assert field == 0;
      return expression;
    }
  },

  /** A list that is comparable and immutable. Useful for records with 0 fields
   * (empty list is a good singleton) but sometimes also for records with 2 or
   * more fields that you need to be comparable, say as a key in a lookup. */
  LIST {
    @Override Type javaRowClass(
        JavaTypeFactory typeFactory,
        RelDataType type) {
      return FlatLists.ComparableList.class;
    }

    @Override Type javaFieldClass(JavaTypeFactory typeFactory, RelDataType type,
        int index) {
      return Object.class;
    }

    @Override public Expression record(
        Type javaRowClass, List<Expression> expressions) {
      switch (expressions.size()) {
      case 0:
        return Expressions.field(
          null,
          FlatLists.class,
          "COMPARABLE_EMPTY_LIST");
      case 2:
        return Expressions.convert_(
            Expressions.call(
                List.class,
                null,
                BuiltInMethod.LIST2.method,
                expressions),
            List.class);
      case 3:
        return Expressions.convert_(
            Expressions.call(
                List.class,
                null,
                BuiltInMethod.LIST3.method,
                expressions),
            List.class);
      case 4:
        return Expressions.convert_(
            Expressions.call(
                List.class,
                null,
                BuiltInMethod.LIST4.method,
                expressions),
            List.class);
      case 5:
        return Expressions.convert_(
            Expressions.call(
                List.class,
                null,
                BuiltInMethod.LIST5.method,
                expressions),
            List.class);
      case 6:
        return Expressions.convert_(
            Expressions.call(
                List.class,
                null,
                BuiltInMethod.LIST6.method,
                expressions),
            List.class);
      default:
        return Expressions.convert_(
            Expressions.call(
                List.class,
                null,
                BuiltInMethod.LIST_N.method,
                Expressions.newArrayInit(
                    Comparable.class,
                    expressions)),
            List.class);
      }
    }

    @Override public Expression field(Expression expression, int field,
        @Nullable Type fromType, Type fieldType) {
      final MethodCallExpression e =
          Expressions.call(expression, BuiltInMethod.LIST_GET.method,
              Expressions.constant(field));
      if (fromType == null) {
        fromType = e.getType();
      }
      return EnumUtils.convert(e, fromType, fieldType);
    }
  },

  /**
   * See {@link org.apache.calcite.interpreter.Row}.
   */
  ROW {
    @Override Type javaRowClass(JavaTypeFactory typeFactory, RelDataType type) {
      return Row.class;
    }

    @Override Type javaFieldClass(JavaTypeFactory typeFactory, RelDataType type,
        int index) {
      return Object.class;
    }

    @Override public Expression record(Type javaRowClass,
        List<Expression> expressions) {
      return Expressions.call(BuiltInMethod.ROW_AS_COPY.method, expressions);
    }

    @Override public Expression field(Expression expression, int field,
        @Nullable Type fromType, Type fieldType) {
      final Expression e =
          Expressions.call(expression,
              BuiltInMethod.ROW_VALUE.method, Expressions.constant(field));
      if (fromType == null) {
        fromType = e.getType();
      }
      return EnumUtils.convert(e, fromType, fieldType);
    }
  },
  //数据类型的java类型直接返回Object数组
  ARRAY {
    @Override Type javaRowClass(
        JavaTypeFactory typeFactory,
        RelDataType type) {
      return Object[].class;
    }

    @Override Type javaFieldClass(JavaTypeFactory typeFactory, RelDataType type,
        int index) {
      return Object.class;
    }

    @Override public Expression record(Type javaRowClass, List<Expression> expressions) {
      return Expressions.newArrayInit(Object.class, expressions);
    }

    @Override public Expression comparer() {
      return Expressions.call(BuiltInMethod.ARRAY_COMPARER.method);
    }

    @Override public Expression field(Expression expression, int field,
        @Nullable Type fromType, Type fieldType) {
      final IndexExpression e =
          Expressions.arrayIndex(expression, Expressions.constant(field));
      if (fromType == null) {
        fromType = e.getType();
      }
      return EnumUtils.convert(e, fromType, fieldType);
    }
  };
  // 根据列的数量优化存储格式。
  // 如果是 0 列，优化为 LIST（空列表单例）。
  // 如果是 1 列，优化为 SCALAR。
  // 如果是多列且当前是 SCALAR（不合法状态），重置为 LIST 或保持原样。
  public JavaRowFormat optimize(RelDataType rowType) {
    switch (rowType.getFieldCount()) {
    case 0:
      return LIST;
    case 1:
      return SCALAR;
    default:
      if (this == SCALAR) {
        return LIST;
      }
      return this;
    }
  }
  // 返回表示整行的 Java 类型。
  // ARRAY 返回 Object[].class。
  // SCALAR 返回该列对应的原始 Java 类型。
  // CUSTOM 调用 typeFactory 获取生成的类。
  abstract Type javaRowClass(JavaTypeFactory typeFactory, RelDataType type);

  /**
   * Returns the java class that is used to physically store the given field.
   * For instance, a non-null int field can still be stored in a field of type
   * {@code Object.class} in {@link JavaRowFormat#ARRAY} case.
   *
   * @param typeFactory type factory to resolve java types
   * @param type row type
   * @param index field index
   * @return java type used to store the field
   */
  // 返回物理存储该字段所使用的 Java 类。
  // 在 ARRAY 模式下，即使 SQL 是 int，这里也可能返回 Object.class，因为数组声明为 Object[]。
  abstract Type javaFieldClass(JavaTypeFactory typeFactory, RelDataType type,
      int index);
  // 生成“创建一行”的 Java 表达式。
  // ARRAY 生成 new Object[] { ... }。
  // CUSTOM 生成 new MyClass( ... )。
  // SCALAR 直接返回第一个表达式。
  public abstract Expression record(
      Type javaRowClass, List<Expression> expressions);

  public @Nullable Expression comparer() {
    return null;
  }

  /** Returns a reference to a particular field.
   *
   * <p>{@code fromType} may be null; if null, uses the natural type of the
   * field.
   */
  // 生成“访问行中某个字段”的表达式。
  // ARRAY 生成 expression[field]。
  // CUSTOM 生成 expression.fieldName。
  // LIST 生成 expression.get(field)。
  public abstract Expression field(Expression expression, int field,
      @Nullable Type fromType, Type fieldType);
}
