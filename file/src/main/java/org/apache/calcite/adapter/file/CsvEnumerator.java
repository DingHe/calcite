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
package org.apache.calcite.adapter.file;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.util.DateTimeUtils;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.ImmutableNullableList;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Source;
import org.apache.calcite.util.trace.CalciteLogger;

import org.apache.commons.lang3.time.FastDateFormat;

import au.com.bytecode.opencsv.CSVReader;

import com.google.common.annotations.VisibleForTesting;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

/** Enumerator that reads from a CSV file.
 *
 * @param <E> Row type
 */
// CsvEnumerator 是 Apache Calcite 官方文件适配器（calcite-file 模块）中最核心的物理数据读取类。它实现了 Calcite 自定义的 Enumerator 接口。
// CsvEnumerator 的核心作用是将一个本地的 .csv 文本文件转换为 Calcite 物理执行引擎能够直接识别的、强类型的行流（Row Stream）。
// 其主要承担以下三项职责：
// 元数据自推导（Schema Inference）：能够通过读取 CSV 文件的首行（Header），自动解析出表包含哪些列、每列叫什么名字，以及各列对应的数据类型。
// 流式物理驱动（Streaming / Cursor Driving）：基于底层的 CSVReader，每调用一次 moveNext() 就从磁盘或流中读取一行纯文本数据，并具有过滤和取消标记检测功能。
// 类型安全转换（Data Unmarshalling）：CSV 文件中的数据在物理上全部是字符串（String）。该类通过内部的 RowConverter 引擎，将这些非结构化的字符串动态地、安全地裁剪并转换为 Java 强类型对象（如 Integer, Long, BigDecimal, 日期时间戳等），以供上层 SQL 算子进行计算。
public class CsvEnumerator<E> implements Enumerator<E> {
  private static final CalciteLogger LOGGER =
      new CalciteLogger(LoggerFactory.getLogger(CsvEnumerator.class));
  // 底层的物理 CSV 读取器（来自开源组件 OpenCSV）。负责真正和磁盘文件打交道，按行切分文本。
  private final CSVReader reader;
  // 下推过滤值列表。如果优化器在扫描阶段下推了简单的等值条件（如 WHERE id = '10'），这里会存下对应列的期望值，在读取时直接在底层进行高效过滤。
  private final @Nullable List<@Nullable String> filterValues;
  // 取消/中断标记。异步线程可以通过修改此标记来中断当前的 SQL 查询。如果用户强行关闭了客户端连接，该标记变为 true，终止数据加载。
  private final AtomicBoolean cancelFlag;
  // 抽象行转换器策略。决定了将读取到的一行 String[] 数组转换成单列的 Object，还是多列的 Object[]。
  private final RowConverter<E> rowConverter;
  // 当前行状态缓存。保存最近一次通过 moveNext() 成功转换后的单行物理数据模型。
  private @Nullable E current;
  // 作用：指定标准的日期格式化器（yyyy-MM-dd），显式绑定 GMT 时区，用于将 CSV 的日期字符串转化为 Calcite 内部的 int（距 Epoch 的天数）。
  private static final FastDateFormat TIME_FORMAT_DATE;
  // 作用：时间格式化器（HH:mm:ss），用于处理 TIME 类型数据。
  private static final FastDateFormat TIME_FORMAT_TIME;
  // 作用：时间戳格式化器（yyyy-MM-dd HH:mm:ss），用于处理 TIMESTAMP 类型数据。
  private static final FastDateFormat TIME_FORMAT_TIMESTAMP;
  // 作用：正则表达式模式。用于从 CSV 首行元数据声明中抽取出高精度数字的精度和标度，如匹配字符串 "decimal(10,2)"。
  private static final Pattern DECIMAL_TYPE_PATTERN = Pattern
      .compile("\"decimal\\(([0-9]+),([0-9]+)\\)");

  static {
    final TimeZone gmt = TimeZone.getTimeZone("GMT");
    TIME_FORMAT_DATE = FastDateFormat.getInstance("yyyy-MM-dd", gmt);
    TIME_FORMAT_TIME = FastDateFormat.getInstance("HH:mm:ss", gmt);
    TIME_FORMAT_TIMESTAMP =
        FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss", gmt);
  }
  // 入参：数据源路径（source）、取消标记、全局字段类型列表、当前查询所需的字段索引列表（fields，支持列裁剪）。
  public CsvEnumerator(Source source, AtomicBoolean cancelFlag,
      List<RelDataType> fieldTypes, List<Integer> fields) {
    //noinspection unchecked
    this(source, cancelFlag, false, null,
        (RowConverter<E>) converter(fieldTypes, fields));
  }
  // 入参：多增了 stream（是否作为无界流读取）以及 filterValues（等值过滤数组）。
  // 作用：执行物理初始化。根据 stream 选择实例化标准的 CSVReader 还是监控文件变动的 CsvStreamReader。
  // 并在最后自动执行 reader.readNext() 跳过首行（Header 标题行）。
  public CsvEnumerator(Source source, AtomicBoolean cancelFlag, boolean stream,
      @Nullable String @Nullable [] filterValues, RowConverter<E> rowConverter) {
    this.cancelFlag = cancelFlag;
    this.rowConverter = rowConverter;
    this.filterValues =
        filterValues == null ? null
            : ImmutableNullableList.copyOf(filterValues);
    try {
      if (stream) {
        this.reader = new CsvStreamReader(source);
      } else {
        this.reader = openCsv(source);
      }
      this.reader.readNext(); // skip header row
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
  // 如果上层 SQL 只查询了 1 列，它会聪明地实例化 SingleColumnRowConverter 节省内存；如果要查多列，则走 arrayConverter。
  private static RowConverter<?> converter(List<RelDataType> fieldTypes,
      List<Integer> fields) {
    if (fields.size() == 1) {
      final int field = fields.get(0);
      return new SingleColumnRowConverter(fieldTypes.get(field), field);
    } else {
      return arrayConverter(fieldTypes, fields, false);
    }
  }

  public static RowConverter<@Nullable Object[]> arrayConverter(
      List<RelDataType> fieldTypes, List<Integer> fields, boolean stream) {
    return new ArrayRowConverter(fieldTypes, fields, stream);
  }

  /** Deduces the names and types of a table's columns by reading the first line
   * of a CSV file. */
  // 核心静态元数据方法。传入类型工厂和文件源，它会打开文件读取第一行标题（如 id:int,name:string）。通过 colon 分隔符切分出列名与类型字符串，再利用大段 switch-case 将其转换映射为 Calcite 统一的逻辑类型（SqlTypeName），
  // 最后组装并返回一整个复合行结构（StructType）。
  public static RelDataType deduceRowType(JavaTypeFactory typeFactory,
      Source source, @Nullable List<RelDataType> fieldTypes, Boolean stream) {
    final List<RelDataType> types = new ArrayList<>();
    final List<String> names = new ArrayList<>();
    if (stream) {
      names.add(FileSchemaFactory.ROWTIME_COLUMN_NAME);
      types.add(typeFactory.createSqlType(SqlTypeName.TIMESTAMP));
    }
    try (CSVReader reader = openCsv(source)) {
      String[] strings = reader.readNext();
      if (strings == null) {
        strings = new String[]{"EmptyFileHasNoColumns:boolean"};
      }
      for (String string : strings) {
        final String name;
        final RelDataType fieldType;
        final int colon = string.indexOf(':');
        if (colon >= 0) {
          name = string.substring(0, colon);
          String typeString = string.substring(colon + 1);
          Matcher decimalMatcher = DECIMAL_TYPE_PATTERN.matcher(typeString);
          if (decimalMatcher.matches()) {
            int precision = Integer.parseInt(decimalMatcher.group(1));
            int scale = Integer.parseInt(decimalMatcher.group(2));
            fieldType = parseDecimalSqlType(typeFactory, precision, scale);
          } else {
            switch (typeString) {
            case "string":
              fieldType = toNullableRelDataType(typeFactory, SqlTypeName.VARCHAR);
              break;
            case "boolean":
              fieldType = toNullableRelDataType(typeFactory, SqlTypeName.BOOLEAN);
              break;
            case "byte":
              fieldType = toNullableRelDataType(typeFactory, SqlTypeName.TINYINT);
              break;
            case "char":
              fieldType = toNullableRelDataType(typeFactory, SqlTypeName.CHAR);
              break;
            case "short":
              fieldType = toNullableRelDataType(typeFactory, SqlTypeName.SMALLINT);
              break;
            case "int":
              fieldType = toNullableRelDataType(typeFactory, SqlTypeName.INTEGER);
              break;
            case "long":
              fieldType = toNullableRelDataType(typeFactory, SqlTypeName.BIGINT);
              break;
            case "float":
              fieldType = toNullableRelDataType(typeFactory, SqlTypeName.REAL);
              break;
            case "double":
              fieldType = toNullableRelDataType(typeFactory, SqlTypeName.DOUBLE);
              break;
            case "date":
              fieldType = toNullableRelDataType(typeFactory, SqlTypeName.DATE);
              break;
            case "timestamp":
              fieldType = toNullableRelDataType(typeFactory, SqlTypeName.TIMESTAMP);
              break;
            case "time":
              fieldType = toNullableRelDataType(typeFactory, SqlTypeName.TIME);
              break;
            default:
              LOGGER.warn(
                  "Found unknown type: {} in file: {} for column: {}. Will assume the type of "
                      + "column is string.",
                  typeString, source.path(), name);
              fieldType = toNullableRelDataType(typeFactory, SqlTypeName.VARCHAR);
              break;
            }
          }
        } else {
          name = string;
          fieldType = typeFactory.createSqlType(SqlTypeName.VARCHAR);
        }
        names.add(name);
        types.add(fieldType);
        if (fieldTypes != null) {
          fieldTypes.add(fieldType);
        }
      }
    } catch (IOException e) {
      // ignore
    }
    if (names.isEmpty()) {
      names.add("line");
      types.add(typeFactory.createSqlType(SqlTypeName.VARCHAR));
    }
    return typeFactory.createStructType(Pair.zip(names, types));
  }

  static CSVReader openCsv(Source source) throws IOException {
    Objects.requireNonNull(source, "source");
    return new CSVReader(source.reader());
  }
  // 返回当前游标指向的物理行对象 current（使用 castNonNull 确保非空检查）。
  @Override public E current() {
    return castNonNull(current);
  }
  // 核心状态机驱动方法
  @Override public boolean moveNext() {
    try {
    outer:
      for (;;) {
        if (cancelFlag.get()) {
          return false;
        }
        final String[] strings = reader.readNext();
        if (strings == null) {
          if (reader instanceof CsvStreamReader) {
            try {
              Thread.sleep(CsvStreamReader.DEFAULT_MONITOR_DELAY);
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            continue;
          }
          current = null;
          reader.close();
          return false;
        }
        if (filterValues != null) {
          for (int i = 0; i < strings.length; i++) {
            String filterValue = filterValues.get(i);
            if (filterValue != null) {
              if (!filterValue.equals(strings[i])) {
                continue outer;
              }
            }
          }
        }
        current = rowConverter.convertRow(strings);
        return true;
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override public void reset() {
    throw new UnsupportedOperationException();
  }

  @Override public void close() {
    try {
      reader.close();
    } catch (IOException e) {
      throw new RuntimeException("Error closing CSV reader", e);
    }
  }

  /** Returns an array of integers {0, ..., n - 1}. */
  public static int[] identityList(int n) {
    int[] integers = new int[n];
    for (int i = 0; i < n; i++) {
      integers[i] = i;
    }
    return integers;
  }

  private static RelDataType toNullableRelDataType(JavaTypeFactory typeFactory,
      SqlTypeName sqlTypeName) {
    return typeFactory.createTypeWithNullability(typeFactory.createSqlType(sqlTypeName), true);
  }

  /** Row converter.
   *
   * @param <E> element type */
  abstract static class RowConverter<E> {
    abstract E convertRow(@Nullable String[] rows);

    @SuppressWarnings("JavaUtilDate")
    protected @Nullable Object convert(@Nullable RelDataType fieldType, @Nullable String string) {
      if (fieldType == null || string == null) {
        return string;
      }
      switch (fieldType.getSqlTypeName()) {
      case BOOLEAN:
        if (string.length() == 0) {
          return null;
        }
        return Boolean.parseBoolean(string);
      case TINYINT:
        if (string.length() == 0) {
          return null;
        }
        return Byte.parseByte(string);
      case SMALLINT:
        if (string.length() == 0) {
          return null;
        }
        return Short.parseShort(string);
      case INTEGER:
        if (string.length() == 0) {
          return null;
        }
        return Integer.parseInt(string);
      case BIGINT:
        if (string.length() == 0) {
          return null;
        }
        return Long.parseLong(string);
      case REAL:
        if (string.length() == 0) {
          return null;
        }
        return Float.parseFloat(string);
      case FLOAT:
      case DOUBLE:
        if (string.length() == 0) {
          return null;
        }
        return Double.parseDouble(string);
      case DECIMAL:
        if (string.length() == 0) {
          return null;
        }
        return parseDecimal(fieldType.getPrecision(), fieldType.getScale(), string);
      case DATE:
        if (string.length() == 0) {
          return null;
        }
        try {
          Date date = TIME_FORMAT_DATE.parse(string);
          return (int) (date.getTime() / DateTimeUtils.MILLIS_PER_DAY);
        } catch (ParseException e) {
          return null;
        }
      case TIME:
        if (string.length() == 0) {
          return null;
        }
        try {
          Date date = TIME_FORMAT_TIME.parse(string);
          return (int) date.getTime();
        } catch (ParseException e) {
          return null;
        }
      case TIMESTAMP:
        if (string.length() == 0) {
          return null;
        }
        try {
          Date date = TIME_FORMAT_TIMESTAMP.parse(string);
          return date.getTime();
        } catch (ParseException e) {
          return null;
        }
      case VARCHAR:
      default:
        return string;
      }
    }
  }

  private static RelDataType parseDecimalSqlType(JavaTypeFactory typeFactory, int precision,
      int scale) {
    checkArgument(precision > 0, "DECIMAL type must have precision > 0. Found %s", precision);
    checkArgument(scale >= 0, "DECIMAL type must have scale >= 0. Found %s", scale);
    checkArgument(precision >= scale,
        "DECIMAL type must have precision >= scale. Found precision (%s) and scale (%s).",
        precision, scale);
    return typeFactory.createTypeWithNullability(
        typeFactory.createSqlType(SqlTypeName.DECIMAL, precision, scale), true);
  }

  @VisibleForTesting
  protected static BigDecimal parseDecimal(int precision, int scale, String string) {
    BigDecimal result = new BigDecimal(string);
    // If the parsed value has more fractional digits than the specified scale, round ties away
    // from 0.
    if (result.scale() > scale) {
      LOGGER.warn(
          "Decimal value {} exceeds declared scale ({}). Performing rounding to keep the "
              + "first {} fractional digits.",
          result, scale, scale);
      result = result.setScale(scale, RoundingMode.HALF_UP);
    }
    // Throws an exception if the parsed value has more digits to the left of the decimal point
    // than the specified value.
    if (result.precision() - result.scale() > precision - scale) {
      throw new IllegalArgumentException(String
          .format(Locale.ROOT, "Decimal value %s exceeds declared precision (%d) and scale (%d).",
              result, precision, scale));
    }
    return result;
  }

  /** Array row converter. */
  static class ArrayRowConverter extends RowConverter<@Nullable Object[]> {

    /** Field types. List must not be null, but any element may be null. */
    private final List<RelDataType> fieldTypes;
    private final ImmutableIntList fields;
    /** Whether the row to convert is from a stream. */
    private final boolean stream;

    ArrayRowConverter(List<RelDataType> fieldTypes, List<Integer> fields,
        boolean stream) {
      this.fieldTypes = ImmutableNullableList.copyOf(fieldTypes);
      this.fields = ImmutableIntList.copyOf(fields);
      this.stream = stream;
    }

    @Override public @Nullable Object[] convertRow(@Nullable String[] strings) {
      if (stream) {
        return convertStreamRow(strings);
      } else {
        return convertNormalRow(strings);
      }
    }

    public @Nullable Object[] convertNormalRow(@Nullable String[] strings) {
      final @Nullable Object[] objects = new Object[fields.size()];
      for (int i = 0; i < fields.size(); i++) {
        int field = fields.get(i);
        objects[i] = convert(fieldTypes.get(field), strings[field]);
      }
      return objects;
    }

    public @Nullable Object[] convertStreamRow(@Nullable String[] strings) {
      final @Nullable Object[] objects = new Object[fields.size() + 1];
      objects[0] = System.currentTimeMillis();
      for (int i = 0; i < fields.size(); i++) {
        int field = fields.get(i);
        objects[i + 1] = convert(fieldTypes.get(field), strings[field]);
      }
      return objects;
    }
  }

  /** Single column row converter. */
  private static class SingleColumnRowConverter extends RowConverter<Object> {
    private final RelDataType fieldType;
    private final int fieldIndex;

    private SingleColumnRowConverter(RelDataType fieldType, int fieldIndex) {
      this.fieldType = fieldType;
      this.fieldIndex = fieldIndex;
    }

    @Override public @Nullable Object convertRow(@Nullable String[] strings) {
      return convert(fieldType, strings[fieldIndex]);
    }
  }
}
