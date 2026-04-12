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
package org.apache.calcite.sql;

import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.util.Util;

import org.checkerframework.checker.initialization.qual.NotOnlyInitialized;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Enumeration of possible syntactic types of {@link SqlOperator operators}.
 */
// SqlSyntax 是一个枚举类，它定义了 SQL 操作符在“书写形式”上的物理外观。
// SqlSyntax 的核心作用是：定义 SQL 操作符的语法结构以及如何将操作符还原（Unparse）为 SQL 文本。
// 如果说 SqlOperator 是逻辑定义，那么 SqlSyntax 就是它的“外壳”。
// 它决定了操作符在 SQL 语句中是像函数一样写在括号前（如 ABS(x)），还是像运算符一样夹在中间（如 x + y）。
// 在 Calcite 将解析树（SqlNode Tree）重新转换回字符串（生成 SQL 方言）时，unparse 方法会严格遵循这里定义的语法规则。
public enum SqlSyntax {
  /**
   * Function syntax, as in "Foo(x, y)".
   */
  // 标准函数格式：名字 + 括号 + 参数列表。
  FUNCTION {
    @Override public void unparse(
        SqlWriter writer,
        SqlOperator operator,
        SqlCall call,
        int leftPrec,
        int rightPrec) {
      SqlUtil.unparseFunctionSyntax(operator, writer, call, false);
    }
  },

  /**
   * Function syntax, as in "Foo(x, y)", but uses "*" if there are no arguments,
   * for example "COUNT(*)".
   */
  // 与 FUNCTION 类似，但如果没有参数，内部逻辑会处理 *。
  FUNCTION_STAR {
    @Override public void unparse(
        SqlWriter writer,
        SqlOperator operator,
        SqlCall call,
        int leftPrec,
        int rightPrec) {
      SqlUtil.unparseFunctionSyntax(operator, writer, call, false);
    }
  },

  /**
   * Function syntax with optional ORDER BY, as in "STRING_AGG(x, y ORDER BY z)".
   */
  // 继承自 FUNCTION，但在括号内支持 ORDER BY 表达式。 例如 STR_AGG(x ORDER BY y)
  ORDERED_FUNCTION(FUNCTION) {
    @Override public void unparse(SqlWriter writer, SqlOperator operator,
        SqlCall call, int leftPrec, int rightPrec) {
      SqlUtil.unparseFunctionSyntax(operator, writer, call, true);
    }
  },

  /**
   * Binary operator syntax, as in "x + y".
   */
  // 中缀格式。在两个操作数之间插入操作符名字。
  BINARY {
    @Override public void unparse(
        SqlWriter writer,
        SqlOperator operator,
        SqlCall call,
        int leftPrec,
        int rightPrec) {
      SqlUtil.unparseBinarySyntax(operator, call, writer, leftPrec, rightPrec);
    }
  },

  /**
   * Prefix unary operator syntax, as in "- x".
   */
  // 前缀格式。先写操作符名字，再写操作数，例如- x, NOT b
  PREFIX {
    @Override public void unparse(
        SqlWriter writer,
        SqlOperator operator,
        SqlCall call,
        int leftPrec,
        int rightPrec) {
      assert call.operandCount() == 1;
      writer.keyword(operator.getName());
      call.operand(0).unparse(writer, operator.getLeftPrec(),
          operator.getRightPrec());
    }
  },

  /**
   * Postfix unary operator syntax, as in "x ++".
   */
  // 后缀格式。先写操作数，再写操作符名字。
  // 例如 x IS NULL
  POSTFIX {
    @Override public void unparse(
        SqlWriter writer,
        SqlOperator operator,
        SqlCall call,
        int leftPrec,
        int rightPrec) {
      assert call.operandCount() == 1;
      call.operand(0).unparse(writer, operator.getLeftPrec(),
          operator.getRightPrec());
      writer.keyword(operator.getName());
    }
  },

  /**
   * Special syntax, such as that of the SQL CASE operator, "CASE x WHEN 1
   * THEN 2 ELSE 3 END".
   */
  // 特殊情况。由于语法过于复杂（非固定模板），此方法默认抛出异常，要求具体的 SqlOperator 子类必须重写自己的 unparse。
  SPECIAL {
    @Override public void unparse(
        SqlWriter writer,
        SqlOperator operator,
        SqlCall call,
        int leftPrec,
        int rightPrec) {
      // You probably need to override the operator's unparse
      // method.
      throw Util.needToImplement(this);
    }
  },

  /**
   * Function syntax which takes no parentheses if there are no arguments, for
   * example "CURRENTTIME".
   *
   * @see SqlConformance#allowNiladicParentheses()
   */
  // 无参数函数。属于 FUNCTION 家族，但在没有参数时不需要写括号。
  FUNCTION_ID(FUNCTION) {
    @Override public void unparse(
        SqlWriter writer,
        SqlOperator operator,
        SqlCall call,
        int leftPrec,
        int rightPrec) {
      SqlUtil.unparseFunctionSyntax(operator, writer, call, false);
    }
  },

  /**
   * Syntax of an internal operator, which does not appear in the SQL.
   */
  // 内部操作符。仅用于 Calcite 内部计算，不存在对应的 SQL 文本，调用 unparse 会报错。
  INTERNAL {
    @Override public void unparse(
        SqlWriter writer,
        SqlOperator operator,
        SqlCall call,
        int leftPrec,
        int rightPrec) {
      throw new UnsupportedOperationException("Internal operator '"
          + operator + "' " + "cannot be un-parsed");
    }
  };

  /** Syntax to treat this syntax as equivalent to when resolving operators. */
  // 定义该语法的“家族”或“归属类”，用于操作符解析（Resolution）。
  // 有些语法在解析阶段可以视为等价。例如 FUNCTION_ID（如 CURRENT_DATE）和 ORDERED_FUNCTION 虽然展示形式不同，
  // 但它们在逻辑查找时都属于 FUNCTION 家族。如果 family 为 null，则其家族就是它自己。
  @NotOnlyInitialized
  public final SqlSyntax family;
  // 默认构造函数，将 family 指向自身。
  SqlSyntax() {
    this(null);
  }
  // 允许指定所属家族。例如 ORDERED_FUNCTION(FUNCTION) 将其家族设为 FUNCTION。
  SqlSyntax(@Nullable SqlSyntax family) {
    this.family = family == null ? this : family;
  }

  /**
   * Converts a call to an operator of this syntax into a string.
   */
  // 将一个特定的调用（SqlCall）按照当前语法规则转换成 SQL 文本流。
  public abstract void unparse(
      SqlWriter writer, // writer: 用于输出字符串的缓冲区。
      SqlOperator operator, // 当前的操作符定义。
      SqlCall call, // 当前具体的调用实例（包含操作数）。
      int leftPrec, // 左/右优先级，用于判断是否需要自动加括号。
      int rightPrec);
}
