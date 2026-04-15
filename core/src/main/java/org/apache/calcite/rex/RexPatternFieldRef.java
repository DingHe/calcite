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
import org.apache.calcite.sql.SqlKind;

/**
 * Variable that references a field of an input relational expression.
 */
// 专门用于支持 SQL MATCH_RECOGNIZE 子句中的模式匹配操作。
// RexPatternFieldRef 代表对复杂事件处理（CEP）中模式变量字段的引用。
// 特定上下文：它仅出现在 SQL 模式匹配（MATCH_RECOGNIZE）的逻辑中。
// 多维引用：普通的 RexInputRef 只需要一个索引来定位字段，但模式匹配要求我们知道：“是哪个模式变量（Pattern Variable）匹配到的第几个字段？”。
// 示例场景：在 MATCH_RECOGNIZE 的 DEFINE 或 MEASURES 子句中，如果你写 A.price（假设 A 是定义的模式变量），Calcite 就会用 RexPatternFieldRef 来表示它。
public class RexPatternFieldRef extends RexInputRef {
  // 存储模式变量的名称（也称为 Alpha 变量）。
  // 代表了在模式匹配定义中给某个状态起的名字（如 A, B, STRK 等）。通过这个属性，执行引擎能够知道应该去哪个已经匹配成功的事件序列中提取数据。
  private final String alpha;

  public RexPatternFieldRef(String alpha, int index, RelDataType type) {
    super(index, type);
    this.alpha = alpha;
    digest = alpha + ".$" + index;
  }

  public String getAlpha() {
    return alpha;
  }

  public static RexPatternFieldRef of(String alpha, int index, RelDataType type) {
    return new RexPatternFieldRef(alpha, index, type);
  }

  public static RexPatternFieldRef of(String alpha, RexInputRef ref) {
    return new RexPatternFieldRef(alpha, ref.getIndex(), ref.getType());
  }

  @Override public <R> R accept(RexVisitor<R> visitor) {
    return visitor.visitPatternFieldRef(this);
  }

  @Override public <R, P> R accept(RexBiVisitor<R, P> visitor, P arg) {
    return visitor.visitPatternFieldRef(this, arg);
  }

  @Override public SqlKind getKind() {
    return SqlKind.PATTERN_INPUT_REF;
  }
}
