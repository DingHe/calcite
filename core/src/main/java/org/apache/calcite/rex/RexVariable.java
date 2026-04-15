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
package org.apache.calcite.rex;

import org.apache.calcite.rel.type.RelDataType;

import java.util.Objects;

/**
 * A row-expression which references a field.
 */
// RexVariable（行表达式变量）是所有命名变量引用的基类。
// 抽象定义：它代表了在行表达式中指向某个“变量”或“列”的引用。它本身不存储具体的值，而是指向一个可以获取值的地方。
// 元数据持有者：它最核心的作用是为这些变量引用提供统一的名称和数据类型管理。
public abstract class RexVariable extends RexNode {
  //~ Instance fields --------------------------------------------------------
  // 存储变量的名称。
  protected final String name;
  // 存储该变量的数据类型。
  protected final RelDataType type;

  //~ Constructors -----------------------------------------------------------

  protected RexVariable(
      String name,
      RelDataType type) {
    this.name = Objects.requireNonNull(name, "name");
    this.digest = Objects.requireNonNull(name, "name");
    this.type = Objects.requireNonNull(type, "type");
  }

  //~ Methods ----------------------------------------------------------------

  @Override public RelDataType getType() {
    return type;
  }

  /**
   * Returns the name of this variable.
   */
  public String getName() {
    return name;
  }
}
