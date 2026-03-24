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
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.BasicSqlType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

import static java.util.Objects.requireNonNull;

/**
 * RelDataTypeImpl is an abstract base for implementations of
 * {@link RelDataType}.
 *
 * <p>Identity is based upon the {@link #digest} field, which each derived class
 * should set during construction.
 */
// 在 Apache Calcite 的架构中，RelDataTypeImpl 是一个非常关键的抽象基类。它为所有的关系表达式数据类型（Relational Data Type）提供了基础实现。
// RelDataTypeImpl 的核心作用是实现数据类型的标识与管理逻辑。
// 统一的身份标识（Digest）：Calcite 是基于代价优化的，需要频繁比较两个类型是否一致。该类通过 digest 字段（摘要）确保了类型的唯一性标识。
// 结构化类型支持：它实现了处理“行（Row）”或“结构体（Struct）”类型的逻辑，包括字段（Field）的查找、计数和递归搜索。
// 默认行为定义：它为 RelDataType 接口中定义的大量方法（如是否可为空、字符集、精度等）提供了默认返回值，简化了子类（如 BasicSqlType）的实现。
// 类型转换原型（Proto）：提供了一套静态工具方法，用于快速定义某种类型的“原型”，便于在 RelDataTypeFactory 中生成实际类型。
public abstract class RelDataTypeImpl
    implements RelDataType, RelDataTypeFamily {

  /**
   * Suffix for the digests of non-nullable types.
   */
  // 静态常量，值为 " NOT NULL"。
  // 当类型不可为空时，会附加到摘要字符串后面。
  public static final String NON_NULLABLE_SUFFIX = " NOT NULL";

  //~ Instance fields --------------------------------------------------------
  // 存储结构化类型（如表的行）的所有字段。
  // 它是不可变的（Immutable），如果是标量类型（如 INT），则该值为 null。
  protected final @Nullable List<RelDataTypeField> fieldList;
  // 类型的“指纹”。
  // 重要性：两个 RelDataType 对象如果 digest 相同，则认为它们在逻辑上是同一个类型。
  protected @Nullable String digest;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a RelDataTypeImpl.
   * @param fieldList List of fields
   */
  // 结构化类型提供field列表
  protected RelDataTypeImpl(@Nullable List<? extends RelDataTypeField> fieldList) {
    if (fieldList != null) {
      // Create a defensive copy of the list.
      this.fieldList = ImmutableList.copyOf(fieldList);
    } else {
      this.fieldList = null;
    }
  }

  /**
   * Default constructor, to allow derived classes such as
   * {@link BasicSqlType} to be {@link Serializable}.
   *
   * <p>(The serialization specification says that a class can be serializable
   * even if its base class is not serializable, provided that the base class
   * has a public or protected zero-args constructor.)
   */
  // 默认无参构造，初始化 fieldList 为 null，供序列化或标量类型子类使用。
  protected RelDataTypeImpl() {
    this(null);
  }

  //~ Methods ----------------------------------------------------------------
  // 根据名称查找字段。
  // 字段查找（Field Lookup）。它的任务是在一个结构化类型（如 SQL 表的一行）中，根据给定的名称定位具体的列信息。
  @Override public @Nullable RelDataTypeField getField(String fieldName,
      boolean caseSensitive, boolean elideRecord) {
    if (fieldList == null) {
      throw new IllegalStateException("Trying to access field " + fieldName
          + " in a type with no fields: " + this);
    }
    // 尝试在当前层级寻找匹配的字段。
    final Map<String, RelDataTypeField> fieldMap = getFieldMap();
    if (caseSensitive && fieldMap != null) {
      RelDataTypeField field = fieldMap.get(fieldName);
      if (field != null) {
        return field;
      }
    } else {
      for (RelDataTypeField field : fieldList) {
        if (Util.matches(caseSensitive, field.getName(), fieldName)) {
          return field;
        }
      }
    }
    // 如果直接查找失败，且参数 elideRecord 为 true（意味着允许“隐去”中间层级直接访问子字段），则进入此阶段。
    if (elideRecord) {
      final List<Slot> slots = new ArrayList<>();
      // 深度优先搜索。它会尝试在嵌套的 STRUCT 类型中寻找目标。
      getFieldRecurse(slots, this, 0, fieldName, caseSensitive);
    loop:
      for (Slot slot : slots) {
        switch (slot.count) {
        case 0:
          break; // no match at this depth; try deeper
        case 1: // 如果某一深度（Depth）只找到了 1个 匹配项，直接返回。
          return slot.field;
        default:
          // 如果在同一深度找到了 多个 同名项（slot.count > 1），逻辑会立刻“放弃（abandon）”。这是为了防止歧义（Ambiguity），SQL 不允许在模糊匹配时存在多个候选者。
          break loop; // duplicate fields at this depth; abandon search
        }
      }
    }
    // Extra field
    if (fieldList.size() > 0) {
      // _extra 标记：如果 fieldList 的最后一个字段名为 _extra，说明该类型支持“虚拟列”。
      // 动态生成：即使当前字段列表中没有你要找的 fieldName，Calcite 也会为你实时创建一个同名的 RelDataTypeFieldImpl，其类型继承自这个 _extra 字段。
      final RelDataTypeField lastField = Iterables.getLast(fieldList);
      if (lastField.getName().equals("_extra")) {
        return new RelDataTypeFieldImpl(
            fieldName, -1, lastField.getType());
      }
    }

    // a dynamic * field will match any field name.

    if (fieldMap != null) {
      return fieldMap.get("");
    } else {
      for (RelDataTypeField field : fieldList) {
        // Dynamic Star：如果字段被标记为 isDynamicStar()（通常对应 SQL 中的 ** 或动态表），它会匹配任何请求的字段名。
        if (field.isDynamicStar()) {
          // the requested field could be in the unresolved star
          return field;
        }
      }
    }

    return null;
  }

  /** Returns a map from field names to fields.
   *
   * <p>Matching is case-sensitive.
   *
   * <p>If several fields have the same name, the map contains the first.
   *
   * <p>A {@link RelDataTypeField#isDynamicStar() dynamic star field} is indexed
   * under its own name and "" (the empty string).
   *
   * <p>If the map is null, the type must do lookup the long way.
   */
  protected @Nullable Map<String, RelDataTypeField> getFieldMap() {
    return null;
  }
  // 实现了 RelDataTypeImpl 中的递归字段查找逻辑。
  // 它的核心任务是在嵌套的结构化数据（如多层嵌套的 JSON 或复杂的 SQL ROW 类型）中，寻找指定名称的字段，并记录每一层深度的匹配情况。
  private static void getFieldRecurse(List<Slot> slots, RelDataType type,
      int depth, String fieldName, boolean caseSensitive) {
    // 方法首先确保 slots 列表的大小能够覆盖当前的 depth（深度）。
    // Slot 的作用：Slot 是一个简单的内部辅助类，包含 count（匹配计数）和 field（匹配到的字段引用）。
    while (slots.size() <= depth) {
      slots.add(new Slot());
    }
    final Slot slot = slots.get(depth);
    // 在当前传入的 type（类型）中，遍历其所有的 fieldList：
    for (RelDataTypeField field : type.getFieldList()) {
      if (Util.matches(caseSensitive, field.getName(), fieldName)) {
        // 计数与存取：如果匹配成功，slot.count 加 1，并将该字段存入 slot.field。
        slot.count++;
        slot.field = field;
      }
    }
    // No point looking to depth + 1 if there is a hit at depth.
    // if (slot.count == 0)：只有当在当前深度完全没有找到匹配项时，才会开启下一层的递归。
    if (slot.count == 0) {
      for (RelDataTypeField field : type.getFieldList()) {
        if (field.getType().isStruct()) {
          getFieldRecurse(slots, field.getType(), depth + 1,
              fieldName, caseSensitive);
        }
      }
    }
  }

  @Override public List<RelDataTypeField> getFieldList() {
    assert fieldList != null : "fieldList must not be null, type = " + this;
    return fieldList;
  }

  @Override public List<String> getFieldNames() {
    assert fieldList != null : "fieldList must not be null, type = " + this;
    return Pair.left(fieldList);
  }

  @Override public int getFieldCount() {
    assert fieldList != null : "fieldList must not be null, type = " + this;
    return fieldList.size();
  }
  //结构化类型默认为FULLY_QUALIFIED，其他为NONE
  @Override public StructKind getStructKind() {
    return isStruct() ? StructKind.FULLY_QUALIFIED : StructKind.NONE;
  }

  @Override public @Nullable RelDataType getComponentType() {
    // this is not a collection type
    return null;
  }

  @Override public @Nullable RelDataType getKeyType() {
    // this is not a map type
    return null;
  }

  @Override public @Nullable RelDataType getValueType() {
    // this is not a map type
    return null;
  }

  @Override public boolean isStruct() {
    return fieldList != null;
  }

  @Override public boolean equals(@Nullable Object obj) {
    return this == obj
        || obj instanceof RelDataTypeImpl
          && Objects.equals(this.digest, ((RelDataTypeImpl) obj).digest);
  }

  @Override public int hashCode() {
    return Objects.hashCode(digest);
  }

  @Override public String getFullTypeString() {
    return requireNonNull(digest, "digest");
  }

  @Override public boolean isNullable() {
    return false;
  }

  @Override public @Nullable Charset getCharset() {
    return null;
  }

  @Override public @Nullable SqlCollation getCollation() {
    return null;
  }

  @Override public @Nullable SqlIntervalQualifier getIntervalQualifier() {
    return null;
  }

  @Override public int getPrecision() {
    return PRECISION_NOT_SPECIFIED;
  }

  @Override public int getScale() {
    return SCALE_NOT_SPECIFIED;
  }

  /**
   * Gets the {@link SqlTypeName} of this type.
   * Sub-classes must override the method to ensure the resulting value is non-nullable.
   *
   * @return SqlTypeName, never null
   */
  @Override public SqlTypeName getSqlTypeName() {
    // The implementations must provide non-null value, however, we keep this for compatibility
    return castNonNull(null);
  }

  @Override public @Nullable SqlIdentifier getSqlIdentifier() {
    SqlTypeName typeName = getSqlTypeName();
    if (typeName == null) {
      return null;
    }
    return new SqlIdentifier(
        typeName.name(),
        SqlParserPos.ZERO);
  }

  @Override public RelDataTypeFamily getFamily() {
    // by default, put each type into its own family
    return this; //默认每种类型是自己的family
  }

  /**
   * Generates a string representation of this type.
   *
   * @param sb         StringBuilder into which to generate the string
   * @param withDetail when true, all detail information needed to compute a
   *                   unique digest (and return from getFullTypeString) should
   *                   be included;
   */
  protected abstract void generateTypeString(
      StringBuilder sb,
      boolean withDetail);

  /**
   * Computes the digest field. This should be called in every non-abstract
   * subclass constructor once the type is fully defined.
   */
  @SuppressWarnings("method.invocation.invalid")
  protected void computeDigest(@UnknownInitialization RelDataTypeImpl this) {
    StringBuilder sb = new StringBuilder();
    generateTypeString(sb, true);
    if (!isNullable()) {
      sb.append(NON_NULLABLE_SUFFIX);
    }
    digest = sb.toString();
  }

  @Override public String toString() {
    StringBuilder sb = new StringBuilder();
    generateTypeString(sb, false);
    return sb.toString();
  }

  @Override public RelDataTypePrecedenceList getPrecedenceList() {
    // by default, make each type have a precedence list containing
    // only other types in the same family
    return new RelDataTypePrecedenceList() {
      @Override public boolean containsType(RelDataType type) {
        return getFamily() == type.getFamily();
      }

      @Override public int compareTypePrecedence(
          RelDataType type1,
          RelDataType type2) {
        assert containsType(type1);
        assert containsType(type2);
        return 0;
      }
    };
  }

  @Override public RelDataTypeComparability getComparability() {
    return RelDataTypeComparability.ALL;
  }

  /**
   * Returns an implementation of
   * {@link RelProtoDataType}
   * that copies a given type using the given type factory.
   */
  public static RelProtoDataType proto(final RelDataType protoType) {
    assert protoType != null;
    return typeFactory -> typeFactory.copyType(protoType);
  }

  /** Returns a {@link org.apache.calcite.rel.type.RelProtoDataType}
   * that will create a type {@code typeName}.
   *
   * <p>For example, {@code proto(SqlTypeName.DATE), false}
   * will create {@code DATE NOT NULL}.
   *
   * @param typeName Type name
   * @param nullable Whether nullable
   * @return Proto data type
   */
  public static RelProtoDataType proto(final SqlTypeName typeName,
      final boolean nullable) {
    assert typeName != null;
    return typeFactory -> {
      final RelDataType type = typeFactory.createSqlType(typeName);
      return typeFactory.createTypeWithNullability(type, nullable);
    };
  }

  /** Returns a {@link org.apache.calcite.rel.type.RelProtoDataType}
   * that will create a type {@code typeName(precision)}.
   *
   * <p>For example, {@code proto(SqlTypeName.VARCHAR, 100, false)}
   * will create {@code VARCHAR(100) NOT NULL}.
   *
   * @param typeName Type name
   * @param precision Precision
   * @param nullable Whether nullable
   * @return Proto data type
   */
  public static RelProtoDataType proto(final SqlTypeName typeName,
      final int precision, final boolean nullable) {
    assert typeName != null;
    return typeFactory -> {
      final RelDataType type = typeFactory.createSqlType(typeName, precision);
      return typeFactory.createTypeWithNullability(type, nullable);
    };
  }

  /** Returns a {@link org.apache.calcite.rel.type.RelProtoDataType}
   * that will create a type {@code typeName(precision, scale)}.
   *
   * <p>For example, {@code proto(SqlTypeName.DECIMAL, 7, 2, false)}
   * will create {@code DECIMAL(7, 2) NOT NULL}.
   *
   * @param typeName Type name
   * @param precision Precision
   * @param scale Scale
   * @param nullable Whether nullable
   * @return Proto data type
   */
  public static RelProtoDataType proto(final SqlTypeName typeName,
      final int precision, final int scale, final boolean nullable) {
    return typeFactory -> {
      final RelDataType type =
          typeFactory.createSqlType(typeName, precision, scale);
      return typeFactory.createTypeWithNullability(type, nullable);
    };
  }

  /**
   * Returns the "extra" field in a row type whose presence signals that
   * fields will come into existence just by asking for them.
   *
   * @param rowType Row type
   * @return The "extra" field, or null
   */
  public static @Nullable RelDataTypeField extra(RelDataType rowType) {
    // Even in a case-insensitive connection, the name must be precisely
    // "_extra".
    return rowType.getField("_extra", true, false);
  }

  @Override public boolean isDynamicStruct() {
    return false;
  }

  /** Work space for {@link RelDataTypeImpl#getFieldRecurse}. */
  private static class Slot {
    int count;
    @Nullable RelDataTypeField field;
  }
}
