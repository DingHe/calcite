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

/**
 * Specific type of RelRecordType that corresponds to a dynamic table,
 * where columns are created as they are requested.
 */
// 在 Apache Calcite 的类型系统中，DynamicRecordType 是一个非常特殊的抽象基类。它专门用于支持 动态 Schema（Dynamic Schema），
// 即在编译解析阶段尚不知道确切列名，而是根据查询请求“按需创建”列的情况。
// DynamicRecordType 主要解决的是“先有查询，后有定义”的问题，常见于以下场景：
// NoSQL 数据源：如 HBase、MongoDB 或 Phoenix，这些数据库的列是动态增加的，SQL 解析时无法预先获取完整的列清单。
// 流处理/临时视图：在处理像 SELECT * FROM STREAM 这种语句时，列可能随着数据的到来而确定。
// 延迟解析（Late Binding）：它充当一个占位符，告诉 Calcite 验证器（Validator）：“虽然我现在不知道这些列，但如果用户请求了某个列名，请不要报错，我会动态处理它。”
public abstract class DynamicRecordType extends RelDataTypeImpl {

  // The prefix string for dynamic star column name
  public static final String DYNAMIC_STAR_PREFIX = "**";

  @Override public boolean isDynamicStruct() {
    return true;
  }

  /**
   * Returns true if the column name starts with DYNAMIC_STAR_PREFIX.
   */
  public static boolean isDynamicStarColName(String name) {
    return name.startsWith(DYNAMIC_STAR_PREFIX);
  }

}
