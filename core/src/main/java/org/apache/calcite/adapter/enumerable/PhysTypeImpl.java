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
import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.linq4j.function.Function1;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.MemberDeclaration;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.linq4j.tree.Primitive;
import org.apache.calcite.linq4j.tree.Types;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.runtime.Utilities;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.apache.calcite.adapter.enumerable.EnumUtils.generateCollatorExpression;
import static org.apache.calcite.adapter.enumerable.EnumUtils.overridingMethodDecl;

import static java.util.Objects.requireNonNull;

/** Implementation of {@link PhysType}. */
// PhysTypeImpl 是物理类型（Physical Type）的核心实现类。它充当了**逻辑关系类型（RelDataType）与物理 Java 表达（JavaRowFormat）**之间的桥梁。
// PhysTypeImpl 的核心作用是指导代码生成（Code Generation）。
// 在将 SQL 执行计划转换为 Java 源代码（基于 linq4j 表达式树）的过程中，Calcite 需要知道如何“操作”一行数据。PhysTypeImpl 封装了所有与行布局相关的逻辑：
// 数据访问：生成访问特定列的代码（如 obj[0] 或 obj.name）。
// 记录创建：生成构造新行的代码。
// 类型转换：在不同的物理格式（如从 Object[] 到自定义 POJO）之间转换。
// 比较与排序：生成用于 ORDER BY、GROUP BY 或 JOIN 的比较器（Comparator）和选择器（Selector）。
public class PhysTypeImpl implements PhysType {
  // 类型工厂，用于在 SQL 类型和 Java 类型之间进行映射。
  private final JavaTypeFactory typeFactory;
  // 逻辑行类型，定义了列的名称、类型和是否可为空。
  private final RelDataType rowType;
  // 该行在 Java 中的物理类（例如 Object[].class 或某个生成的 POJO 类）。
  private final Type javaRowClass;
  // 缓存每一列对应的 Java Class，提高代码生成效率。
  private final List<Class> fieldClasses = new ArrayList<>();
  // 物理布局格式（如 ARRAY, CUSTOM, SCALAR 等），决定了生成的代码风格。
  final JavaRowFormat format;

  /** Creates a PhysTypeImpl. */
  PhysTypeImpl(
      JavaTypeFactory typeFactory,
      RelDataType rowType,
      Type javaRowClass,
      JavaRowFormat format) {
    this.typeFactory = typeFactory;
    this.rowType = rowType;
    this.javaRowClass = javaRowClass;
    this.format = format;
    for (RelDataTypeField field : rowType.getFieldList()) {  //初始化每个字段的类型
      Type fieldType = typeFactory.getJavaClass(field.getType());
      fieldClasses.add(fieldType instanceof Class ? (Class) fieldType : Object[].class);
    }
  }

  public static PhysType of(
      JavaTypeFactory typeFactory,
      RelDataType rowType,
      JavaRowFormat format) {
    return of(typeFactory, rowType, format, true);
  }

  public static PhysType of(
      JavaTypeFactory typeFactory,
      RelDataType rowType,
      JavaRowFormat format,
      boolean optimize) {
    if (optimize) {
      format = format.optimize(rowType);
    }
    final Type javaRowClass = format.javaRowClass(typeFactory, rowType);
    return new PhysTypeImpl(typeFactory, rowType, javaRowClass, format);
  }

  static PhysType of(
      final JavaTypeFactory typeFactory,
      Type javaRowClass) {
    final RelDataTypeFactory.Builder builder = typeFactory.builder();
    if (javaRowClass instanceof Types.RecordType) {
      final Types.RecordType recordType = (Types.RecordType) javaRowClass;
      for (Types.RecordField field : recordType.getRecordFields()) {
        builder.add(field.getName(), typeFactory.createType(field.getType()));
      }
    }
    RelDataType rowType = builder.build();
    // Do not optimize if there are 0 or 1 fields.
    return new PhysTypeImpl(typeFactory, rowType, javaRowClass,
        JavaRowFormat.CUSTOM);
  }

  @Override public JavaRowFormat getFormat() {
    return format;
  }

  @Override public JavaTypeFactory getTypeFactory() {
    return typeFactory;
  }

  @Override public PhysType project(List<Integer> integers, JavaRowFormat format) {
    return project(integers, false, format);
  }
  // Enumerable 算子生成代码时处理 SELECT（投影） 逻辑的核心。其本质是根据索引列表创建一个新的“精简版”物理类型。
  // integers 存放了目标列在原始行中的索引
  // 什么是 Indicator：在 SQL 的某些操作（如 GROUPING SETS 或特定的 Join）中，需要额外的布尔列来标记对应的列是否为“聚合生成的 null”或是该行是否存在。
  // 逻辑：如果 indicator 为 true，它会为每一个投影列额外生成一个以 i$ 开头的布尔列。
  // 结果：如果投影了 Name 列，结果集中会多出一列 i$Name，类型为不可为空的 BOOLEAN。
  @Override public PhysType project(List<Integer> integers, boolean indicator,
      JavaRowFormat format) {
    final RelDataTypeFactory.Builder builder = typeFactory.builder();
    // 根据传入的 integers 列表（存放了目标列在原始行中的索引），从原始的 rowType 中提取对应的字段。
    // 示例：如果原始行是 [ID, Name, Age, Salary]，integers 为 [1, 0]，那么 builder 会构建出 [Name, ID]。
    for (int index : integers) {
      builder.add(rowType.getFieldList().get(index));
    }
    // Indicator（指示符）处理逻辑
    if (indicator) {
      final RelDataType booleanType =
          typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.BOOLEAN), false);
      for (int index : integers) {
        builder.add("i$" + rowType.getFieldList().get(index).getName(),
            booleanType);
      }
    }
    RelDataType projectedRowType = builder.build();
    return of(typeFactory, projectedRowType, format.optimize(projectedRowType));
  }

  @Override public Expression generateSelector(
      ParameterExpression parameter,
      List<Integer> fields) {
    return generateSelector(parameter, fields, format);
  }
  // 核心任务是生成一个 Lambda 表达式（选择器），用于从原始行中提取特定字段，并将其封装成目标格式。
  // 执行 Join 的 Key 提取、GroupBy 的分组键提取等操作时至关重要
  @Override public Expression generateSelector(
      ParameterExpression parameter,
      List<Integer> fields,
      JavaRowFormat targetFormat) {
    // Optimize target format
    switch (fields.size()) {
    case 0:
      // 如果不需要提取字段（0个），设为 LIST（通常返回空列表）。
      targetFormat = JavaRowFormat.LIST;
      break;
    case 1:
      // 如果只提取 1 个字段，强制转为 SCALAR。这意味着生成的代码不会返回 Object[]，而是直接返回该字段的值（如 Integer）
      targetFormat = JavaRowFormat.SCALAR;
      break;
    default:
      break;
    }
    // 创建目标物理类型
    // 通过 project 方法，基于选定的字段索引和优化后的格式，创建一个新的 PhysType 实例
    final PhysType targetPhysType =
        project(fields, targetFormat);
    switch (format) {
    case SCALAR:
      // 如果当前行本身就是一个标量（只有一列），且我们要提取的通常也是这一列，那么直接返回 identity 函数（即 x -> x）。不需要做任何转换。
      return Expressions.call(BuiltInMethod.IDENTITY_SELECTOR.method);
    default:
      // 路径 B：常规格式（如 ARRAY, CUSTOM）
      return Expressions.lambda(Function1.class,
          targetPhysType.record(fieldReferences(parameter, fields)), parameter);
    }
  }
  // 核心作用是生成一个带有 Indicator（指示符） 的选择器，通常用于处理 SQL 中的 Grouping Sets、Cube 或 Rollup 等高级聚合操作。
  // 在这种场景下，查询需要区分一个 NULL 值是数据本身存在的，还是因为聚合（汇总）而产生的“虚拟”空值。
  @Override public Expression generateSelector(final ParameterExpression parameter,
      final List<Integer> fields, List<Integer> usedFields,
      JavaRowFormat targetFormat) {
    // 注意这里的 true 参数，它对应 project 方法中的 indicator 形参。
    // 这意味着生成的 targetPhysType 结构将是：[原始字段列...] + [对应的布尔指示符列...]。
    final PhysType targetPhysType =
        project(fields, true, targetFormat);
    final List<Expression> expressions = new ArrayList<>();
    for (Ord<Integer> ord : Ord.zip(fields)) {
      final Integer field = ord.e;
      // 情况 1：该字段在当前聚合级别中被使用
      if (usedFields.contains(field)) {
        expressions.add(fieldReference(parameter, field));
      } else {
        // 情况 2：该字段未被使用（例如在汇总行中），填充默认值（通常是 null 或 0）
        final Primitive primitive =
            Primitive.of(targetPhysType.fieldClass(ord.i));
        expressions.add(
            Expressions.constant(
                primitive != null ? primitive.defaultValue : null));
      }
    }
    for (Integer field : fields) {
      // 如果字段未被使用，指示符为 true（表示这是一个由聚合产生的 null）
      expressions.add(Expressions.constant(!usedFields.contains(field)));
    }
    // 最后将合并后的 expressions（包含数据值和布尔标记）通过 targetPhysType.record 包装成目标 Java 对象。
    return Expressions.lambda(Function1.class,
        targetPhysType.record(expressions), parameter);
  }
  // generateSelector 生成的是一个完整的 Lambda 表达式对象，而 selector 返回的是构造该选择器所需的“原材料”（类型和表达式列表）
  @Override public Pair<Type, List<Expression>> selector(
      ParameterExpression parameter,
      List<Integer> fields,
      JavaRowFormat targetFormat) {
    // Optimize target format
    // 目标格式的自动优化
    switch (fields.size()) {
    case 0:
      targetFormat = JavaRowFormat.LIST;
      break;
    case 1:
      targetFormat = JavaRowFormat.SCALAR;
      break;
    default:
      break;
    }
    // 创建一个描述“提取后的行”的物理类型。这个 targetPhysType 决定了返回值的第一个元素 Type。
    final PhysType targetPhysType =
        project(fields, targetFormat);
    switch (format) {
    case SCALAR:
      // 如果当前行本身就是一个标量，那么提取的结果类型就是参数 parameter 的类型，表达式就是参数本身。
      return Pair.of(parameter.getType(), ImmutableList.of(parameter));
    default:
      // 左值 (Type)：targetPhysType.getJavaRowType()。这是提取后的 Java 类型（可能是 Object[]、Integer 或某个 POJO）。
      // 右值 (List<Expression>)：fieldReferences(parameter, fields)。这是一组访问指令，例如 [v1[0], v1[2]]。
      return Pair.of(targetPhysType.getJavaRowType(),
          fieldReferences(parameter, fields));
    }
  }
  // 作用是为一组给定的列索引（argList）生成对应的属性访问表达式列表，并确保这些表达式的结果类型与物理类型定义的 Java 类完全匹配。
  // 该方法接收一个基础表达式 v1（通常代表一行数据）和一个索引列表 argList（代表你想要提取哪些列）。
  @Override public List<Expression> accessors(Expression v1, List<Integer> argList) {
    final List<Expression> expressions = new ArrayList<>();
    for (int field : argList) {
      expressions.add(
          EnumUtils.convert(
              fieldReference(v1, field),
              fieldClass(field)));
    }
    return expressions;
  }
  // 作用是创建一个当前物理类型的“可空版本”。
  // 这在执行 Left/Right Outer Join 或处理带有 Filter 的聚合时非常重要，因为这些操作可能会产生原本定义为非空的列变为 NULL 的情况。
  @Override public PhysType makeNullable(boolean nullable) {
    if (!nullable) {
      return this;
    }
    return new PhysTypeImpl(typeFactory,
        typeFactory.createTypeWithNullability(rowType, true),
        Primitive.box(javaRowClass), format);
  }

  @SuppressWarnings("deprecation")
  @Override public Expression convertTo(Expression exp, PhysType targetPhysType) {
    return convertTo(exp, targetPhysType.getFormat());
  }
  // 核心作用是生成将数据从一种物理格式转换为另一种物理格式的代码。
  // 在 Calcite 的执行计划中，不同的算子可能期望不同的数据布局（例如，一个算子输出 Object[]，而下游算子为了性能希望接收 POJO 或单个 SCALAR 值）。
  // convertTo 就是连接这些不同布局的“适配器”。
  @Override public Expression convertTo(Expression exp, JavaRowFormat targetFormat) {
    // 首先检查源格式（this.format）是否已经与目标格式相同。如果是，则不需要任何转换，直接返回原始表达式 exp
    if (format == targetFormat) {
      return exp;
    }
    // 创建一个名为 o 的参数表达式，其类型是当前的 javaRowClass。
    // 它代表转换过程中的“输入行”。同时获取总列数，确保转换时覆盖所有字段。
    final ParameterExpression o_ =
        Expressions.parameter(javaRowClass, "o");
    final int fieldCount = rowType.getFieldCount();
    // The conversion must be strict so optimizations of the targetFormat should not be performed
    // by the code that follows. If necessary the target format can be optimized before calling
    // this method.
    // 构建目标物理类型（严格模式）
    PhysType targetPhysType =
        PhysTypeImpl.of(typeFactory, rowType, targetFormat, false);
    // fieldReferences(o_, ...)：生成从当前行 o 提取所有字段的指令。
    // targetPhysType.record(...)：将提取出的所有字段，按照目标格式的要求重新封装。
    final Expression selector =
        Expressions.lambda(Function1.class,
            targetPhysType.record(fieldReferences(o_, Util.range(fieldCount))),
            o_);
    // 在原始表达式 exp（通常是一个 Enumerable 对象）上调用 .select(selector) 方法。
    return Expressions.call(exp, BuiltInMethod.SELECT.method, selector);
  }
  // 核心作用是为排序（Sort）操作生成两个组件：一个是提取排序列的选择器（Selector），另一个是执行比较逻辑的比较器（Comparator）。
  // 当你在 SQL 中使用 ORDER BY 时，Enumerable 算子就会通过这个方法生成高效的 Java 字节码来处理数据的顺序。
  // 该方法根据排序列的数量（collations.size()）采取了两种完全不同的策略：
  // 单列排序优化：提取那一列，并使用预定义的通用比较器。
  // 多列复合排序：生成一个自定义的 Comparator 匿名类，内部手动实现逐列对比逻辑。
  @Override public Pair<Expression, Expression> generateCollationKey(
      final List<RelFieldCollation> collations) {
    final Expression selector;
    if (collations.size() == 1) {
      RelFieldCollation collation = collations.get(0);
      RelDataType fieldType = rowType.getFieldList() == null || rowType.getFieldList().isEmpty()
          ? rowType
          : rowType.getFieldList().get(collation.getFieldIndex()).getType();
      Expression fieldComparator = generateCollatorExpression(fieldType.getCollation());
      ParameterExpression parameter =
          Expressions.parameter(javaRowClass, "v");
      selector =
          Expressions.lambda(
              Function1.class,
              fieldReference(parameter, collation.getFieldIndex()),
              parameter);
      return Pair.of(selector,
          Expressions.call(
              fieldComparator == null ? BuiltInMethod.NULLS_COMPARATOR.method
                  : BuiltInMethod.NULLS_COMPARATOR2.method,
              Expressions.list(
                  (Expression) Expressions.constant(
                      collation.nullDirection
                          == RelFieldCollation.NullDirection.FIRST),
                  Expressions.constant(
                      collation.direction
                          == RelFieldCollation.Direction.DESCENDING))
                  .appendIfNotNull(fieldComparator)));
    }
    selector =
        Expressions.call(BuiltInMethod.IDENTITY_SELECTOR.method);

    // int c;
    // c = Utilities.compare(v0, v1);
    // if (c != 0) return c; // or -c if descending
    // ...
    // return 0;
    BlockBuilder body = new BlockBuilder();
    final ParameterExpression parameterV0 =
        Expressions.parameter(javaRowClass, "v0");
    final ParameterExpression parameterV1 =
        Expressions.parameter(javaRowClass, "v1");
    final ParameterExpression parameterC =
        Expressions.parameter(int.class, "c");
    final int mod = collations.size() == 1 ? Modifier.FINAL : 0;
    body.add(Expressions.declare(mod, parameterC, null));
    for (RelFieldCollation collation : collations) {
      final int index = collation.getFieldIndex();
      final RelDataType fieldType = rowType.getFieldList().get(index).getType();
      final Expression fieldComparator = generateCollatorExpression(fieldType.getCollation());
      Expression arg0 = fieldReference(parameterV0, index);
      Expression arg1 = fieldReference(parameterV1, index);
      switch (Primitive.flavor(fieldClass(index))) {
      case OBJECT:
        arg0 = EnumUtils.convert(arg0, Comparable.class);
        arg1 = EnumUtils.convert(arg1, Comparable.class);
        break;
      default:
        break;
      }
      final boolean nullsFirst =
          collation.nullDirection
              == RelFieldCollation.NullDirection.FIRST;
      final boolean descending =
          collation.getDirection()
              == RelFieldCollation.Direction.DESCENDING;
      body.add(
          Expressions.statement(
              Expressions.assign(
                  parameterC,
                  Expressions.call(
                      Utilities.class,
                      fieldNullable(index)
                          ? (nullsFirst != descending
                          ? "compareNullsFirst"
                          : "compareNullsLast")
                          : "compare",
                      Expressions.list(
                          arg0,
                          arg1)
                          .appendIfNotNull(fieldComparator)))));
      body.add(
          Expressions.ifThen(
              Expressions.notEqual(
                  parameterC, Expressions.constant(0)),
              Expressions.return_(
                  null,
                  descending
                      ? Expressions.negate(parameterC)
                      : parameterC)));
    }
    body.add(
        Expressions.return_(null, Expressions.constant(0)));

    final List<MemberDeclaration> memberDeclarations =
        Expressions.list(
            Expressions.methodDecl(
                Modifier.PUBLIC,
                int.class,
                "compare",
                ImmutableList.of(
                    parameterV0, parameterV1),
                body.toBlock()));

    if (EnumerableRules.BRIDGE_METHODS) {
      final ParameterExpression parameterO0 =
          Expressions.parameter(Object.class, "o0");
      final ParameterExpression parameterO1 =
          Expressions.parameter(Object.class, "o1");
      BlockBuilder bridgeBody = new BlockBuilder();
      bridgeBody.add(
          Expressions.return_(
              null,
              Expressions.call(
                  Expressions.parameter(
                      Comparable.class, "this"),
                  BuiltInMethod.COMPARATOR_COMPARE.method,
                  Expressions.convert_(
                      parameterO0,
                      javaRowClass),
                  Expressions.convert_(
                      parameterO1,
                      javaRowClass))));
      memberDeclarations.add(
          overridingMethodDecl(
              BuiltInMethod.COMPARATOR_COMPARE.method,
              ImmutableList.of(parameterO0, parameterO1),
              bridgeBody.toBlock()));
    }
    return Pair.of(selector,
        Expressions.new_(Comparator.class,
            ImmutableList.of(),
            memberDeclarations));
  }

  @Override public Expression generateComparator(RelCollation collation) {
    return this.generateComparator(collation, fieldCollation -> {
      final int index = fieldCollation.getFieldIndex();
      final boolean nullsFirst =
          fieldCollation.nullDirection
              == RelFieldCollation.NullDirection.FIRST;
      final boolean descending =
          fieldCollation.getDirection()
              == RelFieldCollation.Direction.DESCENDING;
      return fieldNullable(index)
          ? (nullsFirst != descending
          ? "compareNullsFirst"
          : "compareNullsLast")
          : "compare";
    });
  }

  private Expression generateComparator(RelCollation collation,
      Function1<RelFieldCollation, String> compareMethodNameFunction) {
    // int c;
    // c = Utilities.compare(v0, v1);
    // if (c != 0) return c; // or -c if descending
    // ...
    // return 0;
    BlockBuilder body = new BlockBuilder();
    final Type javaRowClass = Primitive.box(this.javaRowClass);
    final ParameterExpression parameterV0 =
        Expressions.parameter(javaRowClass, "v0");
    final ParameterExpression parameterV1 =
        Expressions.parameter(javaRowClass, "v1");
    final ParameterExpression parameterC =
        Expressions.parameter(int.class, "c");
    final int mod =
        collation.getFieldCollations().size() == 1 ? Modifier.FINAL : 0;
    body.add(Expressions.declare(mod, parameterC, null));
    for (RelFieldCollation fieldCollation : collation.getFieldCollations()) {
      final int index = fieldCollation.getFieldIndex();
      final RelDataType fieldType = rowType.getFieldList().get(index).getType();
      final Expression fieldComparator = generateCollatorExpression(fieldType.getCollation());
      Expression arg0 = fieldReference(parameterV0, index);
      Expression arg1 = fieldReference(parameterV1, index);
      switch (Primitive.flavor(fieldClass(index))) {
      case OBJECT:
        arg0 = EnumUtils.convert(arg0, Comparable.class);
        arg1 = EnumUtils.convert(arg1, Comparable.class);
        break;
      default:
        break;
      }
      final boolean descending =
          fieldCollation.getDirection()
              == RelFieldCollation.Direction.DESCENDING;
      body.add(
          Expressions.statement(
              Expressions.assign(
                  parameterC,
                  Expressions.call(
                      Utilities.class,
                      compareMethodNameFunction.apply(fieldCollation),
                      Expressions.list(
                          arg0,
                          arg1)
                          .appendIfNotNull(fieldComparator)))));
      body.add(
          Expressions.ifThen(
              Expressions.notEqual(
                  parameterC, Expressions.constant(0)),
              Expressions.return_(
                  null,
                  descending
                      ? Expressions.negate(parameterC)
                      : parameterC)));
    }
    body.add(
        Expressions.return_(null, Expressions.constant(0)));

    final List<MemberDeclaration> memberDeclarations =
        Expressions.list(
            Expressions.methodDecl(
                Modifier.PUBLIC,
                int.class,
                "compare",
                ImmutableList.of(parameterV0, parameterV1),
                body.toBlock()));

    if (EnumerableRules.BRIDGE_METHODS) {
      final ParameterExpression parameterO0 =
          Expressions.parameter(Object.class, "o0");
      final ParameterExpression parameterO1 =
          Expressions.parameter(Object.class, "o1");
      BlockBuilder bridgeBody = new BlockBuilder();
      bridgeBody.add(
          Expressions.return_(
              null,
              Expressions.call(
                  Expressions.parameter(
                      Comparable.class, "this"),
                  BuiltInMethod.COMPARATOR_COMPARE.method,
                  Expressions.convert_(
                      parameterO0,
                      javaRowClass),
                  Expressions.convert_(
                      parameterO1,
                      javaRowClass))));
      memberDeclarations.add(
          overridingMethodDecl(
              BuiltInMethod.COMPARATOR_COMPARE.method,
              ImmutableList.of(parameterO0, parameterO1),
              bridgeBody.toBlock()));
    }
    return Expressions.new_(
        Comparator.class,
        ImmutableList.of(),
        memberDeclarations);
  }

  @Override public Expression generateMergeJoinComparator(RelCollation collation) {
    return this.generateComparator(collation, fieldCollation -> {
      // merge join keys must be sorted in ascending order, nulls last
      assert fieldCollation.nullDirection == RelFieldCollation.NullDirection.LAST;
      assert fieldCollation.getDirection() == RelFieldCollation.Direction.ASCENDING;
      return fieldNullable(fieldCollation.getFieldIndex())
          ? "compareNullsLastForMergeJoin"
          : "compare";
    });
  }

  @Override public RelDataType getRowType() {
    return rowType;
  }

  @Override public Expression record(List<Expression> expressions) {
    return format.record(javaRowClass, expressions);
  }

  @Override public Type getJavaRowType() {
    return javaRowClass;
  }

  @Override public Type getJavaFieldType(int index) {
    return format.javaFieldClass(typeFactory, rowType, index);
  }

  @Override public PhysType component(int fieldOrdinal) {
    final RelDataTypeField field = rowType.getFieldList().get(fieldOrdinal);
    RelDataType componentType =
        requireNonNull(field.getType().getComponentType(),
            () -> "field.getType().getComponentType() for " + field);
    return PhysTypeImpl.of(typeFactory,
        toStruct(componentType), format, false);
  }

  @Override public PhysType field(int ordinal) {
    final RelDataTypeField field = rowType.getFieldList().get(ordinal);
    final RelDataType type = field.getType();
    return PhysTypeImpl.of(typeFactory, toStruct(type), format, false);
  }

  private RelDataType toStruct(RelDataType type) {
    if (type.isStruct()) {
      return type;
    }
    return typeFactory.builder()
        .add(SqlUtil.deriveAliasFromOrdinal(0), type)
        .build();
  }

  @Override public @Nullable Expression comparer() {
    return format.comparer();
  }

  // 针对一组指定的字段索引，批量生成访问这些字段的延迟加载（Lazy Loading）表达式列表。
  // final Expression parameter: 代表输入行对象的表达式（例如变量名 row 或 current）。
  // final List<Integer> fields: 一个整数列表，包含了你想要获取的字段在行结构中的索引位置。例如 [0, 2, 5] 表示你想获取第 1、3、6 列。
  private List<Expression> fieldReferences(
      final Expression parameter, final List<Integer> fields) {
    return new AbstractList<Expression>() {
      @Override public Expression get(int index) {
        return fieldReference(parameter, fields.get(index));
      }

      @Override public int size() {
        return fields.size();
      }
    };
  }
  // 返回列的java类型
  @Override public Class fieldClass(int field) {
    return fieldClasses.get(field);
  }

  @Override public boolean fieldNullable(int field) {
    return rowType.getFieldList().get(field).getType().isNullable();
  }

  @Override public Expression generateAccessor(
      List<Integer> fields) {
    ParameterExpression v1 =
        Expressions.parameter(javaRowClass, "v1");
    switch (fields.size()) {
    case 0:
      return Expressions.lambda(
          Function1.class,
          Expressions.field(
              null,
              BuiltInMethod.COMPARABLE_EMPTY_LIST.field),
          v1);
    case 1:
      int field0 = fields.get(0);

      // new Function1<Employee, Res> {
      //    public Res apply(Employee v1) {
      //        return v1.<fieldN>;
      //    }
      // }
      Class returnType = fieldClasses.get(field0);
      Expression fieldReference =
          EnumUtils.convert(
              fieldReference(v1, field0),
              returnType);
      return Expressions.lambda(
          Function1.class,
          fieldReference,
          v1);
    default:
      // new Function1<Employee, List> {
      //    public List apply(Employee v1) {
      //        return Arrays.asList(
      //            new Object[] {v1.<fieldN>, v1.<fieldM>});
      //    }
      // }
      Expressions.FluentList<Expression> list = Expressions.list();
      for (int field : fields) {
        list.add(fieldReference(v1, field));
      }
      return Expressions.lambda(Function1.class, getListExpression(list), v1);
    }
  }

  private static Expression getListExpression(Expressions.FluentList<Expression> list) {
    assert list.size() >= 2;

    switch (list.size()) {
    case 2:
      return Expressions.call(
              List.class,
              null,
              BuiltInMethod.LIST2.method,
              list);
    case 3:
      return Expressions.call(
              List.class,
              null,
              BuiltInMethod.LIST3.method,
              list);
    case 4:
      return Expressions.call(
              List.class,
              null,
              BuiltInMethod.LIST4.method,
              list);
    case 5:
      return Expressions.call(
              List.class,
              null,
              BuiltInMethod.LIST5.method,
              list);
    case 6:
      return Expressions.call(
              List.class,
              null,
              BuiltInMethod.LIST6.method,
              list);
    default:
      return Expressions.call(
              List.class,
              null,
              BuiltInMethod.LIST_N.method,
              Expressions.newArrayInit(Comparable.class, list));
    }
  }

  @Override public Expression generateAccessorWithoutNulls(List<Integer> fields) {
    if (fields.size() < 2) {
      return generateAccessor(fields);
    }

    ParameterExpression v1 = Expressions.parameter(javaRowClass, "v1");
    Expressions.FluentList<Expression> list = Expressions.list();
    for (int field : fields) {
      list.add(fieldReference(v1, field));
    }

    // (v1.<field0> == null)
    //   ? null
    //   : (v1.<field1> == null)
    //     ? null;
    //     : ...
    //         : FlatLists.of(...);
    Expression exp = getListExpression(list);
    for (int i = list.size() - 1; i >= 0; i--) {
      exp =
          Expressions.condition(
              Expressions.equal(list.get(i), Expressions.constant(null)),
              Expressions.constant(null),
              exp);
    }
    return Expressions.lambda(Function1.class, exp, v1);
  }

  @Override public Expression fieldReference(
      Expression expression, int field) {
    return fieldReference(expression, field, null);
  }
  // 作用是生成访问特定字段的表达式，并在此过程中处理复杂的类型映射，特别是针对 SQL 日期/时间类型与 Java 存储类型之间的差异。
  // Expression expression: 代表当前行对象的表达式（例如变量名 row）。
  // int field: 目标字段在行结构中的索引（从 0 开始）。
  // @Nullable Type storageType: 期望的 Java 存储类型。如果外部指定了类型，生成代码时会尽量符合该类型。
  @Override public Expression fieldReference(
      Expression expression, int field, @Nullable Type storageType) {
    Type fieldType;
    if (storageType == null) {
      storageType = fieldClass(field); // 获取该字段在物理层默认的 Java 类
      fieldType = null; // 逻辑类型设为 null，表示直接按物理类处理
    } else {
      fieldType = fieldClass(field); // 获取该字段真实的逻辑类型
      if (fieldType != java.sql.Date.class  // Calcite 的一个重要设计。SQL 的 DATE、TIME、TIMESTAMP 在 Java 内部通常存储为 int (天数) 或 long (毫秒数)。
          && fieldType != java.sql.Time.class
          && fieldType != java.sql.Timestamp.class) {
        fieldType = null;
      }
    }
    // 具体的“取数”代码生成交给了 format 对象。
    return format.field(expression, field, fieldType, storageType);
  }
}
