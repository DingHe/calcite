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

import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.sql.SqlCollation;
import org.apache.calcite.util.SerializableCharset;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.Charset;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * BasicSqlType represents a standard atomic SQL type (excluding interval
 * types).
 *
 * <p>Instances of this class are immutable.
 */
// 在 Apache Calcite 的类型系统中，BasicSqlType 是最常用的具体实现类。它代表了 SQL 标准中的原子数据类型（Atomic Types），例如 INTEGER、VARCHAR(10)、DECIMAL(18, 2) 等，但不包括集合类型或时间间隔（Interval）类型。
// 具体化 SQL 类型：它是 AbstractSqlType 的具体实现，能够表达带有参数（如精度和标度）的 SQL 类型。
// 不可变性（Immutable）：该类的实例一旦创建就不可更改，这保证了类型在优化器各个阶段的一致性。
// 多维度描述：除了类型名称外，它还集成了精度（Precision）、标度（Scale）、字符集（Charset）和排序规则（Collation）。
// 类型系统感知：它持有 RelDataTypeSystem 的引用，这意味着它可以根据不同的数据库策略（如不同数据库的最大精度限制）来调整自身的默认行为。
public class BasicSqlType extends AbstractSqlType {
  //~ Static fields/initializers ---------------------------------------------

  //~ Instance fields --------------------------------------------------------
  // 精度。对于数值类型指数字总数，对于字符/二进制类型指最大长度
  private final int precision;
  // 标度。主要用于 DECIMAL 等数值类型，指小数点后的位数。
  private final int scale;
  // 关联的类型系统。用于获取当前系统环境下某种类型的默认精度、最大精度等元数据。
  protected final RelDataTypeSystem typeSystem;
  // 排序规则。定义了字符串比较和排序的逻辑。
  private final @Nullable SqlCollation collation;
  // 字符集。使用了包装类以支持 Java 序列化。
  private final @Nullable SerializableCharset wrappedCharset;

  //~ Constructors -----------------------------------------------------------

  /**
   * Constructs a type with no parameters. This should only be called from a
   * factory method.
   *
   * @param typeSystem Type system
   * @param typeName Type name
   */
  public BasicSqlType(RelDataTypeSystem typeSystem, SqlTypeName typeName) {
    this(typeSystem, typeName, false);
  }

  protected BasicSqlType(RelDataTypeSystem typeSystem, SqlTypeName typeName,
      boolean nullable) {
    this(typeSystem, typeName, nullable, PRECISION_NOT_SPECIFIED,
        SCALE_NOT_SPECIFIED, null, null);
    checkPrecScale(typeName, false, false);
  }

  /**
   * Constructs a type with precision/length but no scale.
   *
   * @param typeSystem Type system
   * @param typeName Type name
   * @param precision Precision (called length for some types)
   */
  public BasicSqlType(RelDataTypeSystem typeSystem, SqlTypeName typeName,
      int precision) {
    this(typeSystem, typeName, false, precision, SCALE_NOT_SPECIFIED, null,
        null);
    checkPrecScale(typeName, true, false);
  }

  /**
   * Constructs a type with precision/length and scale.
   *
   * @param typeSystem Type system
   * @param typeName Type name
   * @param precision Precision (called length for some types)
   * @param scale Scale
   */
  public BasicSqlType(RelDataTypeSystem typeSystem, SqlTypeName typeName,
      int precision, int scale) {
    this(typeSystem, typeName, false, precision, scale, null, null);
    checkPrecScale(typeName, true, true);
  }

  /** Internal constructor. */
  private BasicSqlType(
      RelDataTypeSystem typeSystem,
      SqlTypeName typeName,
      boolean nullable,
      int precision,
      int scale,
      @Nullable SqlCollation collation,
      @Nullable SerializableCharset wrappedCharset) {
    super(typeName, nullable, null);
    this.typeSystem = Objects.requireNonNull(typeSystem, "typeSystem");
    this.precision = precision;
    this.scale = scale;
    this.collation = collation;
    this.wrappedCharset = wrappedCharset;
    computeDigest();
  }

  /** Throws if {@code typeName} does not allow the given combination of
   * precision and scale. */
  protected static void checkPrecScale(SqlTypeName typeName,
      boolean precisionSpecified, boolean scaleSpecified) {
    if (!typeName.allowsPrecScale(precisionSpecified, scaleSpecified)) {
      throw new AssertionError("typeName.allowsPrecScale("
          + precisionSpecified + ", " + scaleSpecified + "): " + typeName);
    }
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Constructs a type with nullablity.
   */
  BasicSqlType createWithNullability(boolean nullable) {
    if (nullable == this.isNullable) {
      return this;
    }
    return new BasicSqlType(this.typeSystem, this.typeName, nullable,
        this.precision, this.scale, this.collation, this.wrappedCharset);
  }

  /**
   * Constructs a type with charset and collation.
   *
   * <p>This must be a character type.
   */
  BasicSqlType createWithCharsetAndCollation(Charset charset,
      SqlCollation collation) {
    checkArgument(SqlTypeUtil.inCharFamily(this));
    return new BasicSqlType(this.typeSystem, this.typeName, this.isNullable,
        this.precision, this.scale, collation,
        SerializableCharset.forCharset(charset));
  }

  @Override public int getPrecision() {
    if (precision == PRECISION_NOT_SPECIFIED) {
      return typeSystem.getDefaultPrecision(typeName);
    }
    return precision;
  }

  @Override public int getScale() {
    if (scale == SCALE_NOT_SPECIFIED) {
      switch (typeName) {
      case TINYINT:
      case SMALLINT:
      case INTEGER:
      case BIGINT:
      case DECIMAL:
        return 0;
      default:
        // fall through
      }
    }
    return scale;
  }

  @Override public @Nullable Charset getCharset() {
    return wrappedCharset == null ? null : wrappedCharset.getCharset();
  }

  @Override public @Nullable SqlCollation getCollation() {
    return collation;
  }

  // implement RelDataTypeImpl
  @Override protected void generateTypeString(StringBuilder sb, boolean withDetail) {
    // Called to make the digest, which equals() compares;
    // so equivalent data types must produce identical type strings.

    sb.append(typeName.name());
    boolean printPrecision = precision != PRECISION_NOT_SPECIFIED;
    boolean printScale = scale != SCALE_NOT_SPECIFIED;

    if (printPrecision) {
      sb.append('(');
      sb.append(getPrecision());
      if (printScale) {
        sb.append(", ");
        sb.append(getScale());
      }
      sb.append(')');
    }
    if (!withDetail) {
      return;
    }
    if (wrappedCharset != null
        && !SqlCollation.IMPLICIT.getCharset().equals(wrappedCharset.getCharset())) {
      sb.append(" CHARACTER SET \"");
      sb.append(wrappedCharset.getCharset().name());
      sb.append("\"");
    }
    if (collation != null
        && collation != SqlCollation.IMPLICIT && collation != SqlCollation.COERCIBLE) {
      sb.append(" COLLATE \"");
      sb.append(collation.getCollationName());
      sb.append("\"");
    }
  }

  /**
   * Returns a value which is a limit for this type.
   *
   * <p>For example,
   *
   * <table border="1">
   * <caption>Limits</caption>
   * <tr>
   * <th>Datatype</th>
   * <th>sign</th>
   * <th>limit</th>
   * <th>beyond</th>
   * <th>precision</th>
   * <th>scale</th>
   * <th>Returns</th>
   * </tr>
   * <tr>
   * <td>Integer</td>
   * <td>true</td>
   * <td>true</td>
   * <td>false</td>
   * <td>-1</td>
   * <td>-1</td>
   * <td>2147483647 (2 ^ 31 -1 = MAXINT)</td>
   * </tr>
   * <tr>
   * <td>Integer</td>
   * <td>true</td>
   * <td>true</td>
   * <td>true</td>
   * <td>-1</td>
   * <td>-1</td>
   * <td>2147483648 (2 ^ 31 = MAXINT + 1)</td>
   * </tr>
   * <tr>
   * <td>Integer</td>
   * <td>false</td>
   * <td>true</td>
   * <td>false</td>
   * <td>-1</td>
   * <td>-1</td>
   * <td>-2147483648 (-2 ^ 31 = MININT)</td>
   * </tr>
   * <tr>
   * <td>Boolean</td>
   * <td>true</td>
   * <td>true</td>
   * <td>false</td>
   * <td>-1</td>
   * <td>-1</td>
   * <td>TRUE</td>
   * </tr>
   * <tr>
   * <td>Varchar</td>
   * <td>true</td>
   * <td>true</td>
   * <td>false</td>
   * <td>10</td>
   * <td>-1</td>
   * <td>'ZZZZZZZZZZ'</td>
   * </tr>
   * </table>
   *
   * @param sign   If true, returns upper limit, otherwise lower limit
   * @param limit  If true, returns value at or near to overflow; otherwise
   *               value at or near to underflow
   * @param beyond If true, returns the value just beyond the limit, otherwise
   *               the value at the limit
   * @return Limit value
   */
  public @Nullable Object getLimit(
      boolean sign,
      SqlTypeName.Limit limit,
      boolean beyond) {
    int precision = typeName.allowsPrec() ? this.getPrecision() : -1;
    int scale = typeName.allowsScale() ? this.getScale() : -1;
    return typeName.getLimit(
        sign,
        limit,
        beyond,
        precision,
        scale);
  }
}
