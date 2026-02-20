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
package org.apache.calcite.schema;

/** 目的是让表支持时间维度查询
 * Table that is temporal.
 */
public interface TemporalTable extends Table {
 //标识每一行数据的开始有效时间（也就是该行数据从何时起开始生效）。在时间维度表中，通常会有一个表示数据生效起始时间的字段，例如，start_time 或者类似的字段名称
  /** Returns the name of the system column that contains the start effective
   * time of each row. */
  String getSysStartFieldName();
 //用于标识每一行数据的结束有效时间（即该行数据到何时为止仍然有效）。它通常用于表示数据的有效期终止时间
  /** Returns the name of the system column that contains the end effective
   * time of each row. */
  String getSysEndFieldName();
}
