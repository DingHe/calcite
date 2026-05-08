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

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.util.SqlVisitor;
import org.apache.calcite.sql.validate.SqlMoniker;
import org.apache.calcite.sql.validate.SqlMonotonicity;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorImpl;
import org.apache.calcite.sql.validate.SqlValidatorScope;
import org.apache.calcite.util.Litmus;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

/** 非叶子节点的Operator都是一个SqlCall
 * A <code>SqlCall</code> is a call to an {@link SqlOperator operator}.
 * (Operators can be used to describe any syntactic construct, so in practice,
 * every non-leaf node in a SQL parse tree is a <code>SqlCall</code> of some
 * kind.)
 */
// SqlCall 代表对一个 SqlOperator（操作符） 的调用。
// 在 Calcite 中，“操作符”是一个极其宽泛的概念：
// 传统操作符：如 +, -, *, /, AND, OR。
// SQL 函数：如 COUNT(), SUBSTR(), ABS()。
// SQL 语法结构：甚至整个 SELECT、JOIN、VALUES 语句在 Calcite 内部都被视为一种操作符的调用。
// 核心地位：如果说 SqlLiteral（常量）和 SqlIdentifier（标识符）是解析树的叶子节点，
// 那么 SqlCall 就是非叶子节点。它负责将多个子节点（操作数）按照某种逻辑组合在一起。
public abstract class SqlCall extends SqlNode {
  //~ Constructors -----------------------------------------------------------

  protected SqlCall(SqlParserPos pos) {
    super(pos);
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Whether this call was created by expanding a parentheses-free call to
   * what was syntactically an identifier.
   */
  // 判断该调用是否由标识符扩展而来。默认返回 false。
  public boolean isExpanded() {
    return false;
  }

  /**
   * Changes the value of an operand. Allows some rewrite by
   * {@link SqlValidator}; use sparingly.
   * @param i Operand index
   * @param operand Operand value
   */
  // 修改第 i 个操作数。
  // 默认抛出不支持异常。主要用于验证器在进行 SQL 重写或简化时，小规模调整语法树。
  public void setOperand(int i, @Nullable SqlNode operand) {
    throw new UnsupportedOperationException();
  }
  // 返回节点的类型枚举。
  @Override public SqlKind getKind() {
    return getOperator().getKind();
  }

  // 获取该调用关联的操作符对象（如 SqlSelectOperator 或 SqlPlusOperator）。
  @Pure
  public abstract SqlOperator getOperator();

  /**
   * Returns the list of operands. The set and order of operands is
   * call-specific.
   *
   * <p>Note: the proper type would be {@code List<@Nullable SqlNode>}, however,
   * it would trigger too many changes to the current codebase.
   * @return the list of call operands, never null, the operands can be null
   */
  // 获取该调用的所有操作数列表。
  // 子类必须实现此方法。例如 SqlSelect 会返回查询的各组成部分（Select 列表、From、Where 等）。
  public abstract List</*Nullable*/ SqlNode> getOperandList();

  /**
   * Returns i-th operand (0-based).
   *
   * <p>Note: the result might be null, so the proper signature would be
   * {@code <S extends @Nullable SqlNode>}, however, it would trigger to many
   * changes to the current codebase.
   *
   * @param i operand index (0-based)
   * @param <S> type of the result
   * @return i-th operand (0-based), the result might be null
   */
  // 获取第 i 个操作数。
  // 内部通过 getOperandList() 获取列表并进行索引访问。
  @SuppressWarnings("unchecked")
  public <S extends /*Nullable*/ SqlNode> S operand(int i) {
    // Note: in general, null elements exist in the list, however, the code
    // assumes operand(..) is non-nullable, so we add a cast here
    return (S) castNonNull(getOperandList().get(i));
  }

  // 返回操作数的总个数。
  public int operandCount() {
    return getOperandList().size();
  }

  @Override public SqlNode clone(SqlParserPos pos) {
    return getOperator().createCall(getFunctionQuantifier(), pos,
        getOperandList());
  }
  // 将 SqlCall 对象转回 SQL 字符串。
  // 将内存中的抽象语法树（AST）节点重新转换回 SQL 字符串，并根据操作符优先级自动决定是否需要添加括号
  // SQL 转换必须保证语义一致，例如 2 + (3 * 4) 不能变成 2 + 3 * 4。Calcite 使用左优先级（Left Precedence）和右优先级（Right Precedence）来处理。
  @Override public void unparse(
      SqlWriter writer,
      int leftPrec,
      int rightPrec) {
    // 获取当前调用关联的操作符（决定了优先级规则）和方言（如 Oracle、MySQL，决定了具体的语法输出）。
    final SqlOperator operator = getOperator();
    final SqlDialect dialect = writer.getDialect();
    // 左侧外部操作符的优先级高于当前操作符。
    // 例子：在 5 * (2 + 3) 中，当处理 + 时，左侧 * 的优先级（通常是 60）大于 + 的左优先级（通常是 40），所以 2 + 3 必须加括号。
    if (leftPrec > operator.getLeftPrec()
        // 当前操作符的右优先级低于或等于右侧外部操作符。
        // 例子：处理结合性。对于左结合操作符，通过微调左右优先级，确保 (a - b) - c 在反解析时不需要括号，而 a - (b - c) 需要。
        || (operator.getRightPrec() <= rightPrec && (rightPrec != 0))
        // 含义：如果配置了“始终使用括号”且当前节点是一个表达式（如 SqlKind.EXPRESSION），则强制加括号。这通常用于生成更易读或更严谨的 SQL。
        || writer.isAlwaysUseParentheses() && isA(SqlKind.EXPRESSION)
        // 针对比较操作符（如 =, <）的特殊微调。为了防止在某些方言中出现歧义，比较操作符在嵌套时往往倾向于加括号。
        || (operator.getRightPrec() <= rightPrec + 1 && isA(SqlKind.COMPARISON))) {
      // 在输出流中开启一个“帧（Frame）”，并在开头写入左括号 (。
      final SqlWriter.Frame frame = writer.startList("(", ")");
      // 调用方言的解析逻辑。注意这里的优先级传入了 0, 0。
      // 因为已经在外层加了括号，内部表达式就像是在一个全新的、无竞争的环境下运行，不再受外部优先级影响。
      dialect.unparseCall(writer, this, 0, 0);
      writer.endList(frame);
    } else {
      dialect.unparseCall(writer, this, leftPrec, rightPrec);
    }
  }

  /**
   * Validates this call.
   * 校验这个节点
   * <p>The default implementation delegates the validation to the operator's
   * {@link SqlOperator#validateCall}. Derived classes may override (as do,
   * for example {@link SqlSelect} and {@link SqlUpdate}).
   */
  // 对该节点进行语义校验。
  // 默认行为是调用 validator.validateCall(this, scope)。它会检查参数类型是否匹配、函数是否存在等。
  @Override public void validate(SqlValidator validator, SqlValidatorScope scope) {
    validator.validateCall(this, scope);
  }
  // 在特定的解析位置寻找有效的选项。
  // 主要用于 IDE 的自动补全（SQL 智能提示）。
  // 核心作用是：当用户在 SQL 编辑器中输入代码并请求提示时，判断光标是否位于某个操作数上，并根据上下文提供合法的补全选项（Hints）。
  @Override public void findValidOptions(
      SqlValidator validator,
      SqlValidatorScope scope,
      SqlParserPos pos,
      Collection<SqlMoniker> hintList) {
    // SqlCall 是一个非叶子节点，它包含多个操作数（operand）。该循环会逐一检查这个调用中的每一个参数。
    for (SqlNode operand : getOperandList()) {
      // 只有当操作数是一个标识符（SqlIdentifier，即尚未完成的列名、表名等文字）时，
      // 才需要进行名字补全。如果是常量（Literal），通常不需要提示。
      if (operand instanceof SqlIdentifier) {
        SqlIdentifier id = (SqlIdentifier) operand;
        SqlParserPos idPos = id.getParserPosition();
        if (idPos.toString().equals(pos.toString())) {
          // 根据当前的 scope（作用域，决定了哪些表和列可见）和 id.names（用户已经输入的部分名字），去元数据中查找匹配的候选项。
          ((SqlValidatorImpl) validator).lookupNameCompletionHints(
              scope, id.names, pos, hintList);
          return;
        }
      }
    }
    // no valid options
  }
  // 支持访问者模式。
  @Override public <R> R accept(SqlVisitor<R> visitor) {
    return visitor.visit(this);
  }

  @Override public boolean equalsDeep(@Nullable SqlNode node, Litmus litmus) {
    if (node == this) {
      return true;
    }
    if (!(node instanceof SqlCall)) {
      return litmus.fail("{} != {}", this, node);
    }
    SqlCall that = (SqlCall) node;

    // Compare operators by name, not identity, because they may not
    // have been resolved yet. Use case insensitive comparison since
    // this may be a case insensitive system.
    if (!this.getOperator().getName().equalsIgnoreCase(that.getOperator().getName())) {
      return litmus.fail("{} != {}", this, node);
    }
    if (!equalDeep(this.getFunctionQuantifier(), that.getFunctionQuantifier(), litmus)) {
      return litmus.fail("{} != {} (function quantifier differs)", this, node);
    }
    return equalDeep(this.getOperandList(), that.getOperandList(), litmus);
  }

  /**
   * Returns a string describing the actual argument types of a call, e.g.
   * "SUBSTR(VARCHAR(12), NUMBER(3,2), INTEGER)".
   */
  // 获取该调用的“签名”字符串。
  // 示例：SUM(INTEGER)。常用于生成错误信息。
  // 构造并返回当前 SQL 调用（SqlCall）的类型签名字符串。
  // 该方法通过遍历操作数，利用校验器（Validator）推导出每个参数的物理数据类型，最后将其拼接成一个标准化的签名字符串。
  public String getCallSignature(
      SqlValidator validator,
      @Nullable SqlValidatorScope scope) {
    // 创建一个字符串列表，用于按顺序存储每个操作数转换后的类型名称。
    List<String> signatureList = new ArrayList<>();
    // 获取当前函数或操作符的所有参数
    for (final SqlNode operand : getOperandList()) {
      // 要求校验器根据当前的上下文（scope）去“推导”操作数的类型。
      // 如果操作数是一个字段名，它去元数据查表结构。
      // 如果操作数是另一个表达式（如 1 + 1），它递归计算出结果类型。
      final RelDataType argType =
          validator.deriveType(Objects.requireNonNull(scope, "scope"),
              operand);
      if (null == argType) {
        continue;
      }
      // 如果推导出的类型有效，则将其转换为字符串（如 "VARCHAR(10)", "INTEGER"）放入列表中。
      signatureList.add(argType.toString());
    }
    // 会将操作符的名字（如 SUM）和参数类型列表（如 [DECIMAL]）格式化为标准的 SQL 签名格式：SUM(DECIMAL)。
    return SqlUtil.getOperatorSignature(getOperator(), signatureList);
  }
  // 获取该调用的单调性（递增/递减/常量）。
  @Override public SqlMonotonicity getMonotonicity(SqlValidatorScope scope) {
    // Delegate to operator.
    // SqlCallBinding 的意义：它是一个临时的包装器，把 SqlValidator、SqlValidatorScope 和当前这个 SqlCall 实例绑定在一起。
    final SqlCallBinding binding =
        new SqlCallBinding(scope.getValidator(), scope, this);
    return getOperator().getMonotonicity(binding);
  }

  /**
   * Returns whether it is the function {@code COUNT(*)}.
   * @return true if function call to COUNT(*)
   */
  // 快速判断当前调用是否为 COUNT(*)。
  // 检查操作符名是否为 "COUNT"，操作数是否只有一个且为 *。
  public boolean isCountStar() {
    SqlOperator sqlOperator = getOperator();
    if (sqlOperator.getName().equals("COUNT")
        && operandCount() == 1) {
      final SqlNode parm = operand(0);
      if (parm instanceof SqlIdentifier) {
        SqlIdentifier id = (SqlIdentifier) parm;
        if (id.isStar() && id.names.size() == 1) {
          return true;
        }
      }
    }

    return false;
  }
  // 获取函数的限定符（如 DISTINCT 或 ALL）。
  // 在基类中返回 null，具体的聚合函数子类会重写它。
  @Pure
  public @Nullable SqlLiteral getFunctionQuantifier() {
    return null;
  }
}
