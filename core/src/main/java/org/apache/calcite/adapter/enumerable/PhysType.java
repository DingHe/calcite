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
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.util.Pair;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Type;
import java.util.List;

/**
 * Physical type of a row.
 *
 * <p>Consists of the SQL row type (returned by {@link #getRowType()}), the Java
 * type of the row (returned by {@link #getJavaRowType()}), and methods to
 * generate expressions to access fields, generate records, and so forth.
 * Together, the records encapsulate how the logical type maps onto the physical
 * type.
 */
// 定义了逻辑数据类型（SQL 类型）与物理数据类型（Java 类型）之间的映射桥梁。
// PhysType（Physical Type 的缩写）的主要作用是描述一行数据在生成的 Java 代码中的物理形态。
// 在 Calcite 将 SQL 转换为可执行的 Java 代码（通过 Linq4j 框架）时，它需要知道：
// 数据怎么存：这一行是一个 Object[]、一个普通的 Java 类（POJO），还是一个简单的 int？
// 数据怎么取：给定一个行对象，如何生成获取“部门编号”列的 Java 表达式？
// 数据怎么比：如何生成用于排序（Order By）或连接（Join）的比较器（Comparator）代码？
public interface PhysType {
  /** Returns the Java type (often a Class) that represents a row. For
   * example, in one row format, always returns {@code Object[].class}. */
  // 返回表示整行的 Java 类型。
  // 例如，如果行格式是 ARRAY，则返回 Object[].class；如果是单个字段，可能是 Integer.class。
  Type getJavaRowType();

  /**
   * Returns the Java class that is used to store the field with the given
   * ordinal.
   *
   * <p>For instance, when the java row type is {@code Object[]}, the java
   * field type is {@code Object} even if the field is not nullable. */
  // 返回指定序号字段在 Java 中的物理类型。
  Type getJavaFieldType(int field);

  /** Returns the type factory. */
  // 返回用于处理 Java 类型与关系类型转换的 JavaTypeFactory
  JavaTypeFactory getTypeFactory();

  /** Returns the physical type of a field. */
  // 返回指定字段自身的物理类型（用于嵌套结构
  PhysType field(int ordinal);

  /** Returns the physical type of a given field's component type. */
  // 如果字段是集合类型，返回其元素的物理类型。
  PhysType component(int field);

  /** Returns the SQL row type. */
  // 返回逻辑上的 SQL 行类型（RelDataType），包含字段名和 SQL 类型（如 VARCHAR, INTEGER）。
  RelDataType getRowType();

  /** Returns the Java class of the field with the given ordinal.*/
  // 返回指定字段的 Java Class 对象。
  Class fieldClass(int field);

  /** Returns whether a given field allows null values. */
  // 判断指定索引的字段是否允许为 null
  boolean fieldNullable(int index);

  /** Generates a reference to a given field in an expression.
   *
   * <p>For example given {@code expression=employee} and {@code field=2},
   * generates
   *
   * <blockquote><pre>{@code employee.deptno}</pre></blockquote>
   *
   * @param expression Expression
   * @param field Ordinal of field
   * @return Expression to access the field of the expression
   */
  // 生成访问字段的代码。例：输入 employee 和索引 2，生成 employee.deptno 或 employee[2]。
  Expression fieldReference(Expression expression, int field);

  /** Generates a reference to a given field in an expression.
   *
   * <p>This method optimizes for the target storage type (i.e. avoids
   * casts).
   *
   * <p>For example given {@code expression=employee} and {@code field=2},
   * generates
   *
   * <blockquote><pre>{@code employee.deptno}</pre></blockquote>
   *
   * @param expression Expression
   * @param field Ordinal of field
   * @param storageType optional hint for storage class
   * @return Expression to access the field of the expression
   */
  // 同上，但提供了一个存储类型的提示，以优化生成的代码（例如减少强制类型转换）。
  Expression fieldReference(Expression expression, int field,
      @Nullable Type storageType);

  /** Generates an accessor function for a given list of fields.  The resulting
   * object is a {@link List} (implementing {@link Object#hashCode()} and
   * {@link Object#equals(Object)} per that interface) and also implements
   * {@link Comparable}.
   *
   * <p>For example:
   *
   * <blockquote><pre>
   * new Function1&lt;Employee, Object[]&gt; {
   *    public Object[] apply(Employee v1) {
   *        return FlatLists.of(v1.&lt;fieldN&gt;, v1.&lt;fieldM&gt;);
   *    }
   * }</pre></blockquote>
   */
  // 生成一个函数（Function1），该函数接收一行并返回指定列组成的列表或对象。
  Expression generateAccessor(List<Integer> fields);

  /** Similar to {@link #generateAccessor(List)}, but if one of the fields is <code>null</code>,
   * it will return <code>null</code>.
   *
   * <p>For example:
   *
   * <blockquote><pre>
   * new Function1&lt;Employee, Object[]&gt; {
   *    public Object[] apply(Employee v1) {
   *        return v1.&lt;fieldN&gt; == null
   *            ? null
   *            : v1.&lt;fieldM&gt; == null
   *                ? null
   *                : FlatLists.of(v1.&lt;fieldN&gt;, v1.&lt;fieldM&gt;);
   *    }
   * }</pre></blockquote>
   */
  // 类似于上面，但增加了非空检查。如果任何一列为 null，则整个结果返回 null。
  Expression generateAccessorWithoutNulls(List<Integer> fields);

  /** Generates a selector for the given fields from an expression, with the
   * default row format. */
  // 同上，但允许指定输出行的物理格式（如将数组格式转为类格式）。
  Expression generateSelector(
      ParameterExpression parameter,
      List<Integer> fields);

  /** Generates a lambda expression that is a selector for the given fields from
   * an expression. */
  // 针对 GROUP BY 等场景，生成带指示器（Indicator）的投影表达式，用于处理 GROUPING 函数。
  Expression generateSelector(
      ParameterExpression parameter,
      List<Integer> fields,
      JavaRowFormat targetFormat);

  /** Generates a lambda expression that is a selector for the given fields from
   * an expression.
   *
   * <p>{@code usedFields} must be a subset of {@code fields}.
   * For each field, there is a corresponding indicator field.
   * If a field is used, its value is assigned and its indicator is left
   * {@code false}.
   * If a field is not used, its value is not assigned and its indicator is
   * set to {@code true};
   * This will become a value of 1 when {@code GROUPING(field)} is called. */
  Expression generateSelector(
      ParameterExpression parameter,
      List<Integer> fields,
      List<Integer> usedFields,
      JavaRowFormat targetFormat);

  /** Generates a selector for the given fields from an expression.
   * Only used by EnumerableWindow. */
  // 专门供窗口函数（EnumerableWindow）使用的选择器生成方法。
  Pair<Type, List<Expression>> selector(
      ParameterExpression parameter,
      List<Integer> fields,
      JavaRowFormat targetFormat);

  /** Projects a given collection of fields from this input record, into
   * a particular preferred output format. The output format is optimized
   * if there are 0 or 1 fields. */
  // 返回一个新的 PhysType，表示经过投影（取子集）后的物理类型。
  PhysType project(
      List<Integer> integers,
      JavaRowFormat format);

  /** Projects a given collection of fields from this input record, optionally
   * with indicator fields, into a particular preferred output format.
   *
   * <p>The output format is optimized if there are 0 or 1 fields
   * and indicators are disabled. */
  // 带有指示器标志的投影。
  PhysType project(
      List<Integer> integers,
      boolean indicator,
      JavaRowFormat format);

  /** Returns a lambda to create a collation key and a comparator. The
   * comparator is sometimes null. */
  // 生成用于排序的“键”提取器和比较器。
  Pair<Expression, Expression> generateCollationKey(
      List<RelFieldCollation> collations);

  /** Returns a comparator. Unlike the comparator returned by
   * {@link #generateCollationKey(java.util.List)}, this comparator acts on the
   * whole element. */
  // 针对整行数据生成一个 Comparator 对象表达式。
  Expression generateComparator(
      RelCollation collation);

  /** Similar to {@link #generateComparator(RelCollation)}, but with some specificities for
   * MergeJoin algorithm: it will not consider two <code>null</code> values as equal.
   *
   * @see org.apache.calcite.linq4j.EnumerableDefaults#compareNullsLastForMergeJoin
   */
  // 为归并连接（Merge Join）生成特殊的比较器，该比较器通常不认为两个 null 是相等的。
  Expression generateMergeJoinComparator(RelCollation collation);

  /** Returns a expression that yields a comparer, or null if this type
   * is comparable. */
  // 返回一个比较器表达式。如果类型本身已实现 Comparable，则可能返回 null。
  @Nullable Expression comparer();

  /** Generates an expression that creates a record for a row, initializing
   * its fields with the given expressions. There must be one expression per
   * field.
   *
   * @param expressions Expression to initialize each field
   * @return Expression to create a row
   */
  // 生成构建一行数据的表达式。例如 new Employee(exp1, exp2) 或 new Object[] {exp1, exp2}。
  Expression record(List<Expression> expressions);

  /** Returns the format. */
  // 返回物理布局格式（JavaRowFormat），如 ARRAY（数组）、STRUCT（结构体）、SCALAR（标量）等
  JavaRowFormat getFormat();
  // 为一组字段生成一组访问表达式。
  List<Expression> accessors(Expression parameter, List<Integer> argList);

  /** Returns a copy of this type that allows nulls if {@code nullable} is
   * true. */
  // 返回当前物理类型的副本，但修改其是否允许为 null 的属性
  PhysType makeNullable(boolean nullable);

  /** Converts an enumerable of this physical type to an enumerable that uses a
   * given physical type for its rows.
   *
   * @deprecated Use {@link #convertTo(Expression, JavaRowFormat)}.
   * The use of PhysType as a second parameter is misleading since only the row
   * format of the expression is affected by the conversion. Moreover it requires
   * to have at hand a PhysType object which is not really necessary for achieving
   * the desired result. */
  // 生成将数据从当前格式转换到目标格式（如从 Object[] 转为 CustomClass）的代码。
  @Deprecated // to be removed before 2.0
  Expression convertTo(Expression expression, PhysType targetPhysType);

  /** Converts an enumerable of this physical type to an enumerable that uses
   * the <code>targetFormat</code> for representing its rows. */
  // 已过时。功能同上，但在 2.0 之前会被移除。
  Expression convertTo(Expression expression, JavaRowFormat targetFormat);
}
