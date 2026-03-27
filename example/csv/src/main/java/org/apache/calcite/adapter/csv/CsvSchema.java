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
package org.apache.calcite.adapter.csv;

import org.apache.calcite.adapter.file.JsonScannableTable;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.util.Source;
import org.apache.calcite.util.Sources;

import com.google.common.collect.ImmutableMap;

import java.io.File;
import java.util.Map;

/**
 * Schema mapped onto a directory of CSV files. Each table in the schema
 * is a CSV file in that directory.
 */
// CsvSchema 是一个经典的实现。它继承了我们之前讨论过的 AbstractSchema，展示了如何将一个本地文件系统目录映射为一个关系型数据库模式。
// CsvSchema 的核心功能是目录到模式的映射（Directory-to-Schema Mapping）：
// 自动化表注册：它会扫描指定的操作系统目录，将其中的每个 .csv 或 .json 文件（包括压缩格式 .gz）自动识别并注册为 Calcite 中的一张“表”。
// 多格式支持：除了 CSV，它还支持简单的 JSON 格式文件。
public class CsvSchema extends AbstractSchema {
  // 作用：指向存放数据文件的物理目录路径。
  private final File directoryFile;
  // 决定了表的“特性层级”。
  // SCANNABLE：最基础的，只能全表扫描。
  // FILTERABLE：支持简单的谓词下推（如 WHERE 条件）。
  // TRANSLATABLE：支持完整的关系代数转译，优化器参与度最高。
  private final CsvTable.Flavor flavor;
  // 缓存该 Schema 下所有的表对象，避免重复扫描磁盘。
  private Map<String, Table> tableMap;

  /**
   * Creates a CSV schema.
   *
   * @param directoryFile Directory that holds {@code .csv} files
   * @param flavor     Whether to instantiate flavor tables that undergo
   *                   query optimization
   */
  // 作用：初始化 Schema，指定数据目录和表类型策略。调用 super() 初始化父类。
  public CsvSchema(File directoryFile, CsvTable.Flavor flavor) {
    super();
    this.directoryFile = directoryFile;
    this.flavor = flavor;
  }

  /** Looks for a suffix on a string and returns
   * either the string with the suffix removed
   * or the original string. */
  // 作用：尝试移除后缀。如果后缀存在则移除，不存在则返回原字符串。
  private static String trim(String s, String suffix) {
    String trimmed = trimOrNull(s, suffix);
    return trimmed != null ? trimmed : s;
  }

  /** Looks for a suffix on a string and returns
   * either the string with the suffix removed
   * or null. */
  private static String trimOrNull(String s, String suffix) {
    return s.endsWith(suffix)
        ? s.substring(0, s.length() - suffix.length())
        : null;
  }
  // 作用：实现父类的钩子方法。
  @Override protected Map<String, Table> getTableMap() {
    if (tableMap == null) {
      tableMap = createTableMap();
    }
    return tableMap;
  }
  // 扫描磁盘并构建表映射。
  private Map<String, Table> createTableMap() {
    // Look for files in the directory ending in ".csv", ".csv.gz", ".json",
    // ".json.gz".
    // 使用 directoryFile.listFiles 过滤出以 .csv、.json、.csv.gz 或 .json.gz 结尾的文件。
    final Source baseSource = Sources.of(directoryFile);
    // 遍历文件列表，利用 Sources.of(file) 抽象化文件来源。
    File[] files = directoryFile.listFiles((dir, name) -> {
      final String nameSansGz = trim(name, ".gz");
      return nameSansGz.endsWith(".csv")
          || nameSansGz.endsWith(".json");
    });
    if (files == null) {
      System.out.println("directory " + directoryFile + " not found");
      files = new File[0];
    }
    // Build a map from table name to table; each file becomes a table.
    final ImmutableMap.Builder<String, Table> builder = ImmutableMap.builder();
    for (File file : files) {
      Source source = Sources.of(file);
      Source sourceSansGz = source.trim(".gz");
      final Source sourceSansJson = sourceSansGz.trimOrNull(".json");
      if (sourceSansJson != null) {
      // JSON 处理：如果后缀是 .json，创建 JsonScannableTable 实例。
        final Table table = new JsonScannableTable(source);
        builder.put(sourceSansJson.relative(baseSource).path(), table);
      }
      final Source sourceSansCsv = sourceSansGz.trimOrNull(".csv");
      if (sourceSansCsv != null) {
      // CSV 处理：如果后缀是 .csv，调用 createTable(source) 创建对应 Flavor 的 CSV 表。
        final Table table = createTable(source);
        builder.put(sourceSansCsv.relative(baseSource).path(), table);
      }
    }
    return builder.build();
  }

  /** Creates different sub-type of table based on the "flavor" attribute. */
  // 根据构造时传入的 flavor 属性，选择实例化具体的表实现类。
  // 这体现了面向对象的多态性，同样的 CSV 文件在不同的配置下会有不同的查询能力。
  private Table createTable(Source source) {
    switch (flavor) {
    case TRANSLATABLE:
      return new CsvTranslatableTable(source, null);
    case SCANNABLE:
      return new CsvScannableTable(source, null);
    case FILTERABLE:
      return new CsvFilterableTable(source, null);
    default:
      throw new AssertionError("Unknown flavor " + this.flavor);
    }
  }
}
