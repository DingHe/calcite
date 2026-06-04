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

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandCountRanges;
import org.apache.calcite.sql.type.SqlOperandMetadata;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.validate.SqlNameMatcher;
import org.apache.calcite.sql.validate.SqlValidator;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

import static org.apache.calcite.util.Static.RESOURCE;

/**
 * Base class for a table-valued function that computes windows. Examples
 * include {@code TUMBLE}, {@code HOP} and {@code SESSION}.
 */
// SqlWindowTableFunction 是所有窗口表函数（Window TVF）的通用基类。它在流处理和时间序列数据分析中扮演着核心角色，最典型的子类实现包括 SQL 标准中的：
// TUMBLE（滚动窗口）
// HOP（滑动窗口）
// SESSION（会话窗口）
// 核心职责
// 统一窗口函数的底层行为：提供标准的元数据定义，如窗口函数常见的公共参数名称（如数据源、时间列、窗口大小等）。
// 动态行类型推导（Row Type Windowing）：窗口表函数在对上游表进行切片时，其返回结果不仅包含上游表的所有原始字段（列透传），还必须在末尾动态追加两个标准的时间戳字段：window_start（窗口起始时间）和 window_end（窗口结束时间）。该类统一实现了这一推导逻辑。
// 严格的校验脚手架（Operand Verification）：内部提供了一个抽象的元数据校验内部类 AbstractOperandMetadata，封装了对特殊 SQL 语法（如 TABLE 关键字、DESCRIPTOR 描述符、INTERVAL 间隔类型）的底层强类型检查算法，供具体的子类（如 TumbleOperandMetadata）直接调用。
public class SqlWindowTableFunction extends SqlFunction
    implements SqlTableFunction {

  /** The data source which the table function computes with. */
  // 代表函数的第一个表类型输入参数（上游数据源，即 TABLE orders）。
  protected static final String PARAM_DATA = "DATA";

  /** The time attribute column. Also known as the event time. */
  // 代表时间属性列参数（事件时间/处理时间），通常在 SQL 中被包裹在 DESCRIPTOR() 中（如 DESCRIPTOR(order_time)）。
  protected static final String PARAM_TIMECOL = "TIMECOL";

  /** The window duration INTERVAL. */
  // 代表窗口的宽度/时间跨度间隔（如 INTERVAL '1' HOUR）。
  protected static final String PARAM_SIZE = "SIZE";

  /** The optional align offset for each window. */
  // 可选参数，代表窗口的对齐偏移量。
  protected static final String PARAM_OFFSET = "OFFSET";

  /** The session key(s), only used for SESSION window. */
  // 专用于 SESSION 窗口，代表会话的分组键（Session Key）。
  protected static final String PARAM_KEY = "KEY";

  /** The slide interval, only used for HOP window. */
  // 专用于 HOP 窗口，代表窗口滑动的步长间隔。
  protected static final String PARAM_SLIDE = "SLIDE";

  /**
   * Type-inference strategy whereby the row type of a table function call is a
   * ROW, which is combined from the row type of operand #0 (which is a TABLE)
   * and two additional fields. The fields are as follows:
   *
   * <ol>
   * <li>{@code window_start}: TIMESTAMP type to indicate a window's start
   * <li>{@code window_end}: TIMESTAMP type to indicate a window's end
   * </ol>
   */
  // 指向内部的 inferRowType 方法。
  // 定义了该窗口函数标准的“追加窗口上下界”的返回类型推导策略。
  public static final SqlReturnTypeInference ARG0_TABLE_FUNCTION_WINDOWING =
      SqlWindowTableFunction::inferRowType;

  /** Creates a window table function with a given name. */
  // 初始化窗口表函数，将其声明为系统级函数（SqlFunctionCategory.SYSTEM），并将返回类型固定设置为 ReturnTypes.CURSOR（表示该函数在 SQL 树中返回一个游标关系表）。
  public SqlWindowTableFunction(String name, SqlOperandMetadata operandMetadata) {
    super(name, SqlKind.OTHER_FUNCTION, ReturnTypes.CURSOR, null,
        operandMetadata, SqlFunctionCategory.SYSTEM);
  }

  @Override public @Nullable SqlOperandMetadata getOperandTypeChecker() {
    return (@Nullable SqlOperandMetadata) super.getOperandTypeChecker();
  }

  @Override public SqlReturnTypeInference getRowTypeInference() {
    return ARG0_TABLE_FUNCTION_WINDOWING;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Overrides because the first parameter of
   * table-value function windowing is an explicit TABLE parameter,
   * which is not scalar.
   */
  // 控制参数是标量还是表（关系）。
  // 明确指出当 ordinal == 0（第一个参数）时，返回 false，表明第一个参数必须是一张关系表（如 TABLE my_table），而非常规的单值标量。对于其他位置的参数（ordinal != 0），则必须是标量值。
  @Override public boolean argumentMustBeScalar(int ordinal) {
    return ordinal != 0;
  }

  /** Helper for {@link #ARG0_TABLE_FUNCTION_WINDOWING}. */
  // SqlOperatorBinding opBinding SQL 运算符/函数在绑定解析阶段的“上下文大管家”。
  private static RelDataType inferRowType(SqlOperatorBinding opBinding) {
    // 抓取输入表的行结构（Schema）
    // 基类的 argumentMustBeScalar(int ordinal) 中做过强校验，规定第 0 个参数必须是一张表。
    // 所以，这里通过 opBinding.getOperandType(0) 拿到的，正是上游那张输入表的完整数据结构（例如包含 id:INT, name:VARCHAR, order_time:TIMESTAMP）。
    final RelDataType inputRowType = opBinding.getOperandType(0);
    // 获取 Calcite 全局的类型工厂实例，它是创建、拼接所有 SQL 数据类型的“中央造币厂”。
    final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
    return typeFactory.builder()
        // 确保新生成的行类型与输入表保持相同的结构体分类（比如是标准的 FULLY_QUALIFIED 显式全限定结构，还是普通的 PEEK_FIELDS 隐式结构），防止类型系统在后续的关系代数转换中发生不匹配。
        .kind(inputRowType.getStructKind())
        .addAll(inputRowType.getFieldList())
        .add("window_start", SqlTypeName.TIMESTAMP, 3)
        .add("window_end", SqlTypeName.TIMESTAMP, 3)
        .build();
  }

  /** Partial implementation of operand type checker. */
  // 专门用来做参数校验与元数据管理的抽象静态内部类。它继承并实现了 SqlOperandMetadata 接口。
  // 在 Calcite 中，窗口表函数（如 TUMBLE、HOP、SESSION）的参数极其复杂，它们包含了非标量（如 TABLE 关键字）、元数据指示器（如 DESCRIPTOR）以及时间跨度（如 INTERVAL）。
  // AbstractOperandMetadata 的核心作用是为所有具体的窗口表函数提供一套高度通用的参数校验脚手架与元数据骨架：
  // 抹平公共参数元数据：提供通用的参数数量区间推导（支持必填与选填）、参数名导出逻辑，从而天然支持 SQL 中的具名参数调用（Named Parameters）。
  // 抽象出公共的强类型检查网关：窗口函数往往有一些固定的前置参数模式。例如 TUMBLE 和 HOP 的前两个参数永远是 (TABLE, DESCRIPTOR)（即：一张表和一个包含时间列的描述符）。这个抽象类把这套复杂的“表结构核对、描述符提取、列名存在性交叉比对”的硬核逻辑封装成可复用的 check 方法，子类只需要根据自身特性把这些方法像积木一样组装起来即可。
  protected abstract static class AbstractOperandMetadata
      implements SqlOperandMetadata {
    // 存储该函数官方定义的参数名称序列。
    // 例如对于 TUMBLE 窗口，它按顺序存放了 ["DATA", "TIMECOL", "SIZE", "OFFSET"]。
    final List<String> paramNames;
    // 声明该函数最少必须传入的参数个数。
    // 区分哪些是必填项，哪些是选填项。例如 TUMBLE 至少需要前三个参数（数据源、时间列、窗口大小），那么该值为 3，第 4 个参数 OFFSET 就是选填的。
    final int mandatoryParamCount;

    AbstractOperandMetadata(List<String> paramNames,
        int mandatoryParamCount) {
      this.paramNames = ImmutableList.copyOf(paramNames);
      this.mandatoryParamCount = mandatoryParamCount;
      checkArgument(mandatoryParamCount >= 0
          && mandatoryParamCount <= paramNames.size());
    }
    // 声明参数数量的上下界。
    @Override public SqlOperandCountRange getOperandCountRange() {
      return SqlOperandCountRanges.between(mandatoryParamCount,
          paramNames.size());
    }
    // 放行初步类型检查。
    // 返回一个充满 SqlTypeName.ANY 的列表，长度与参数列表一致。这是因为窗口函数的参数类型高度动态（比如第一个参数是多变的 ROW 类型），不适合死板地在最外层写硬编码校验。
    // Calcite 借此告诉顶层框架：“请放行初步的类型比对，更严格的物理校验由我下面的 check 族方法内部全权负责”。
    @Override public List<RelDataType> paramTypes(RelDataTypeFactory typeFactory) {
      return Collections.nCopies(paramNames.size(),
          typeFactory.createSqlType(SqlTypeName.ANY));
    }

    @Override public List<String> paramNames() {
      return paramNames;
    }
    // 判断特定位置的参数是否为可选。
    // 传入索引 i。如果 i 严格大于必填参数线的最小值，且小于等于最大参数限制线，则判定为可选参数（true）。
    @Override public boolean isOptional(int i) {
      return i > getOperandCountRange().getMin()
          && i <= getOperandCountRange().getMax();
    }
    // 统一的校验失败异常处理分流器。
    // 当内部的各种 check 逻辑失败时调用。若 throwOnFailure 为 true，代表校验框架要求立刻中断并抛出精准的函数签名不匹配错误（newValidationSignatureError()）；否则默默返回 false，由上层调用链自行处理。
    boolean throwValidationSignatureErrorOrReturnFalse(SqlCallBinding callBinding,
        boolean throwOnFailure) {
      if (throwOnFailure) {
        throw callBinding.newValidationSignatureError();
      } else {
        return false;
      }
    }

    /**
     * Checks whether the heading operands are in the form
     * {@code (ROW, DESCRIPTOR, DESCRIPTOR ..., other params)},
     * returning whether successful, and throwing if any columns are not found.
     *
     * @param callBinding The call binding
     * @param descriptorCount The number of descriptors following the first
     * operand (e.g. the table)
     *
     * @return true if validation passes; throws if any columns are not found
     */
    // 确保用户在调用窗口表函数（如 TUMBLE、HOP）时，函数参数的前几位必须严格符合 (表, 描述符, 描述符...) 的物理排列格式，并且会交叉核对描述符里的列名是否真实存在。
    // SqlCallBinding callBinding：SQL 语法树绑定上下文。它包裹了当前函数调用的整个语法树节点（SqlCall），通过它能极其方便地抓取到用户具体传了哪些参数（SqlNode），也能随时呼叫校验器（SqlValidator）。
    // int descriptorCount：预期紧跟在表后面的 DESCRIPTOR（描述符）的数量。对于滚动窗口 TUMBLE：其语法是 TUMBLE(TABLE data, DESCRIPTOR(timecol), ...)，表后面只需要一个描述符来声明时间列，所以传入 1。
    // 返回值 boolean：返回 true：参数的基本框架（表 + 描述符）完全合法。
    boolean checkTableAndDescriptorOperands(SqlCallBinding callBinding,
        int descriptorCount) {
      // 审查第 0 个参数是否为“关系表”
      final SqlNode operand0 = callBinding.operand(0);
      final SqlValidator validator = callBinding.getValidator();
      final RelDataType type = validator.getValidatedNodeType(operand0);
      // 在 Calcite 的类型系统里，不管是物理表、视图还是子查询，它们在逻辑上吐出来的数据大纲都是 SqlTypeName.ROW（行集合/结构体体类型）。
      // 如果用户在第一个参数上胡写，传了个标量（如 TUMBLE(123, ...)），此处类型核对对不上，直接宣告第一防线失守，返回 false。
      if (type.getSqlTypeName() != SqlTypeName.ROW) {
        return false;
      }
      for (int i = 1; i < descriptorCount + 1; i++) {
        final SqlNode operand = callBinding.operand(i);
        // 检查这个参数的语法树类型（SqlKind）是不是标准的 DESCRIPTOR。
        // 如果用户在这个位置直接写了一个普通的字符串或字段引用，而没有用 DESCRIPTOR(...) 关键字包裹（例如写成了 TUMBLE(TABLE orders, order_time, ...)），对不起，语法模式错乱，直接返回 false。
        if (operand.getKind() != SqlKind.DESCRIPTOR) {
          return false;
        }
        // 发起列名一致性交叉“全面普查”。
        validateColumnNames(validator, type.getFieldNames(),
            ((SqlCall) operand).getOperandList());
      }
      return true;
    }

    /**
     * Checks whether the type that the operand of time col descriptor refers to is valid.
     *
     * @param callBinding The call binding
     * @param pos The position of the descriptor at the operands of the call
     * @return true if validation passes, false otherwise
     */
    // 专门用来校验时间属性列（Time Attribute Column）物理类型的方法。
    // 它的核心任务非常纯粹：确保用户在 DESCRIPTOR 中指定的那个时间字段，其底层物理数据类型必须是合法的“时间戳/时间（Timestamp/Date/Time）”类型。
    // int pos：时间列描述符所在的参数索引位置（从 0 开始）。对于滚动窗口 TUMBLE(TABLE data, DESCRIPTOR(timecol), ...)，时间描述符位于第 2 个参数，因此调用时传入 pos = 1。
    boolean checkTimeColumnDescriptorOperand(SqlCallBinding callBinding, int pos) {
      // 获取输入表的 Schema 结构
      SqlValidator validator = callBinding.getValidator();
      SqlNode operand0 = callBinding.operand(0);
      RelDataType type = validator.getValidatedNodeType(operand0);
      // 精准剥离出用户指定的“时间列名”
      List<SqlNode> operands = ((SqlCall) callBinding.operand(pos)).getOperandList();
      // 提纯字符串。
      SqlIdentifier identifier = (SqlIdentifier) operands.get(0);
      String columnName = identifier.getSimple();
      SqlNameMatcher matcher = validator.getCatalogReader().nameMatcher();
      for (RelDataTypeField field : type.getFieldList()) {
        if (matcher.matches(field.getName(), columnName)) {
          return SqlTypeUtil.isTimestamp(field.getType());
        }
      }
      return false;
    }

    /**
     * Checks whether the operands starting from position {@code startPos} are
     * all of type {@code INTERVAL}, returning whether successful.
     *
     * @param callBinding The call binding
     * @param startPos    The start position to validate (starting index is 0)
     *
     * @return true if validation passes
     */
    // 用于保障窗口函数“时间步长/窗口大小”参数合法的核心防御机制。
    // 它的逻辑十分纯粹：从指定的参数位置（startPos）开始，一直到整个函数调用的最后一个参数，强制检查它们的物理数据类型必须“全都是”合法的 INTERVAL（时间间隔）类型。
    boolean checkIntervalOperands(SqlCallBinding callBinding, int startPos) {
      final SqlValidator validator = callBinding.getValidator();
      for (int i = startPos; i < callBinding.getOperandCount(); i++) {
        final RelDataType type = validator.getValidatedNodeType(callBinding.operand(i));
        if (!SqlTypeUtil.isInterval(type)) {
          return false;
        }
      }
      return true;
    }
    // 唯一职责就是进行列名的真实性核对。如果用户在 DESCRIPTOR 描述符里手抖拼错了一个列名，这段代码就会立刻“引爆”编译期异常，并高亮标记出错误发生的具体位置。
    // List<String> fieldNames：标准的真实列名池。这是从上游输入表（第 0 个参数）里直接提取出来的、所有货真价实的字段名集合（如 ["order_id", "user_id", "event_time"]）。
    // List<SqlNode> columnNames：待审查的列名集合。这是用户写在 DESCRIPTOR(...) 内部的、期望进行绑定的列标识符语法树列表（如 [DESCRIPTOR(event_time)] 里的 event_time 节点）。
    void validateColumnNames(SqlValidator validator,
        List<String> fieldNames, List<SqlNode> columnNames) {
      // 获取名称匹配裁判（SqlNameMatcher）
      final SqlNameMatcher matcher = validator.getCatalogReader().nameMatcher();
      // 利用刚才拿到的裁判 matcher，去真实列名池 fieldNames 里检索用户填写的 name 的索引位置。如果返回值 < 0（即返回了 -1），说明用户在 DESCRIPTOR 里面写的列名，在真实表的字段池里根本不存在。
      Ord.forEach(SqlIdentifier.simpleNames(columnNames), (name, i) -> {
        if (matcher.indexOf(fieldNames, name) < 0) {
          final SqlIdentifier columnName = (SqlIdentifier) columnNames.get(i);
          throw SqlUtil.newContextException(columnName.getParserPosition(),
              RESOURCE.unknownIdentifier(name));
        }
      });
    }
  }
}
