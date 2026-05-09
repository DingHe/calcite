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

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.sql.SqlOperatorBinding;

/**
 * A {@link SqlReturnTypeInference} which always returns the same SQL type.
 */
// 这个类的作用是定义一种 “固定返回类型” 的策略。
// 在 SQL 中，大多数运算符的返回类型是根据操作数推导出来的（例如 + 号的结果取决于加数）。但有些函数的返回类型是预先确定且固定不变的。ExplicitReturnTypeInference 允许你直接指定一个类型，无论 SQL 调用的上下文是什么，它始终返回那个预设的类型。
// 返回布尔值的谓词函数（如 IS NULL，永远返回 BOOLEAN）。
// 返回特定数值类型的函数（如 COUNT，通常永远返回 BIGINT）。
public class ExplicitReturnTypeInference implements SqlReturnTypeInference {
  //~ Instance fields --------------------------------------------------------
  // 存储“原型类型”定义。
  // 原因：Calcite 中的类型对象（RelDataType）通常是绑定在特定的类型工厂（TypeFactory）上的。在不同的查询准备过程中，可能会使用不同的工厂。
  // RelProtoDataType 像是一个类型的“模板”或“配方”，它不属于任何工厂，但在需要时可以根据指定的工厂生产出真正的类型对象。
  protected final RelProtoDataType protoType;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates an inference rule which always returns the same type object.
   *
   * <p>If the requesting type factory is different, returns a copy of the
   * type object made using {@link RelDataTypeFactory#copyType(RelDataType)}
   * within the requesting type factory.
   *
   * <p>A copy of the type is required because each statement is prepared using
   * a different type factory; each type factory maintains its own cache of
   * canonical instances of each type.
   *
   * @param protoType Type object
   */
  protected ExplicitReturnTypeInference(RelProtoDataType protoType) {
    assert protoType != null;
    this.protoType = protoType;
  }

  //~ Methods ----------------------------------------------------------------

  @Override public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
    return protoType.apply(opBinding.getTypeFactory());
  }
}
