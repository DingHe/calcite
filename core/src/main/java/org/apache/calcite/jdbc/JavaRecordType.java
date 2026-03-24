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
package org.apache.calcite.jdbc;

import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelRecordType;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Record type based on a Java class. The fields of the type are the fields
 * of the class.
 *
 * <p><strong>NOTE: This class is experimental and subject to
 * change/removal without notice</strong>.
 */
// JavaRecordType 的核心作用是建立 Java 类（POJO/Bean）与 SQL 行类型（Row/Record）之间的强绑定关系。
// 结构化映射：它不仅像 RelRecordType 那样拥有命名的字段列表，还明确知道这些字段来源于哪一个具体的 Java Class。
// 物理执行支持：在 Calcite 的 Enumerable 算子执行时，需要将 SQL 的行数据转换回 Java 对象。JavaRecordType 保存了原始的 clazz 信息，使得框架能够知道该将数据实例化为哪个类。
// 身份识别：它比普通的 RelRecordType 更严格。两个字段完全相同的记录类型，如果对应的 Java 类不同，在 JavaRecordType 的视角下它们是不相等的。
public class JavaRecordType extends RelRecordType {
  final Class clazz;

  public JavaRecordType(List<RelDataTypeField> fields, Class clazz) {
    super(fields);
    this.clazz = Objects.requireNonNull(clazz, "clazz");
  }

  @Override public boolean equals(@Nullable Object obj) {
    return this == obj
        || obj instanceof JavaRecordType
        && Objects.equals(fieldList, ((JavaRecordType) obj).fieldList)
        && clazz == ((JavaRecordType) obj).clazz;
  }

  @Override public int hashCode() {
    return Objects.hash(fieldList, clazz);
  }
}
