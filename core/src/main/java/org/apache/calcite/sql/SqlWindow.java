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
import org.apache.calcite.rex.RexWindowBound;
import org.apache.calcite.rex.RexWindowBounds;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.sql.util.SqlVisitor;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorScope;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.util.ControlFlowException;
import org.apache.calcite.util.ImmutableNullableList;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import java.math.BigDecimal;
import java.util.List;

import static org.apache.calcite.linq4j.Nullness.castNonNull;
import static org.apache.calcite.util.Static.RESOURCE;

/**
 * SQL window specification.
 *
 * <p>For example, the query
 *
 * <blockquote>
 * <pre>SELECT sum(a) OVER (w ROWS 3 PRECEDING)
 * FROM t
 * WINDOW w AS (PARTITION BY x, y ORDER BY z),
 *     w1 AS (w ROWS 5 PRECEDING UNBOUNDED FOLLOWING)</pre>
 * </blockquote>
 *
 * <p>declares windows w and w1, and uses a window in an OVER clause. It thus
 * contains 3 {@link SqlWindow} objects.
 */
// 专门用来抽象和表示 SQL 标准中的窗口函数定义（即 OVER (...) 子句以及 WINDOW 命名窗口）
// SqlWindow 的主要作用是解析、存储、验证并渲染 SQL 中的窗口规范（Window Specification）。
// 在一条复杂的 SQL 中（例如：SELECT sum(a) OVER (w ROWS 3 PRECEDING) FROM t WINDOW w AS (PARTITION BY x)），Calcite 的解析器会将 OVER 内部的逻辑以及 WINDOW w AS ... 转化为 SqlWindow 对象。
// 完整地表达了窗口的四大核心要素：
// 分区（Partitioning）：PARTITION BY 列。
// 排序（Ordering）：ORDER BY 列。
// 框架（Framing）：度量类型（ROWS 物理行 或 RANGE 逻辑值）以及边界范围（如 3 PRECEDING）。
// 排除与允许部分（Exclusion & Allow Partial）：对高级窗口特性的支持（如 EXCLUDE CURRENT ROW）。

public class SqlWindow extends SqlCall {
  /**
   * The FOLLOWING operator used exclusively in a window specification.
   */
  // 在标准 SQL 的窗口规范中，我们经常使用 BETWEEN 3 PRECEDING AND 1 FOLLOWING 这样的子句来定义聚合计算的数据范围（Frame）
  // FOLLOWING_OPERATOR 对应关键字 FOLLOWING（向后/在后）。
  // 由于在 SQL 语法中，它们总是紧跟在具体的数值或表达式后面（例如 3 PRECEDING、1 FOLLOWING），因此在编译器设计中，它们被抽象为后置单目运算符。
  // 它们的作用是将一个普通的数值节点（如字面量 3）包装成一个具有窗口边界语义的调用节点（SqlCall）
  public static final SqlPostfixOperator FOLLOWING_OPERATOR =
      new SqlPostfixOperator("FOLLOWING", SqlKind.FOLLOWING, 20,
          ReturnTypes.ARG0, null,
          null);
  /**
   * The PRECEDING operator used exclusively in a window specification.
   */
  // PRECEDING_OPERATOR 对应关键字 PRECEDING（向前/在前）。
  public static final SqlPostfixOperator PRECEDING_OPERATOR =
      new SqlPostfixOperator("PRECEDING", SqlKind.PRECEDING, 20,
          ReturnTypes.ARG0, null,
          null);

  //~ Instance fields --------------------------------------------------------

  /** The name of the window being declared. */
  // 以下面的列子为例
  // SELECT AVG(salary) OVER (
  //    w
  //    ROWS BETWEEN 3 PRECEDING AND CURRENT ROW
  //    EXCLUDE CURRENT ROW
  //)
  //FROM employees
  //WINDOW w AS (PARTITION BY department_id ORDER BY hire_date)

  // 声明的窗口名称。当使用 WINDOW w AS (...) 显式定义一个命名窗口时，这个 w 就是被声明的名称。如果是在 OVER (...) 内部直接定义匿名窗口，则该值为 null。
  // 在 WINDOW w AS ... 中，该属性的值为 w。
  @Nullable SqlIdentifier declName;

  /** The name of the window being referenced, or null. */
  // 引用的窗口名称。在 OVER (w ...) 中，可以通过名字去继承或基准化一个已经存在的窗口配置。如果没有引用别的窗口，则为 null。
  @Nullable SqlIdentifier refName;

  /** The list of partitioning columns. */
  // 数据分区列列表。
  // 对应 PARTITION BY 后面的表达式集合，决定了窗口函数计算时的分组边界（数据流会被拆分成一个个独立的 Partition）。
  // 映射为 [department_id] 节点的列表。
  SqlNodeList partitionList;

  /** The list of ordering columns. */
  // 分区内排序列列表。对应 ORDER BY 后面的表达式集合，决定了在一个分区内部，数据行以怎样的顺序流过窗口（对排名函数和范围框架至关重要）。
  // 映射为 [hire_date] 节点的列表。
  SqlNodeList orderList;

  /** Whether it is a physical (rows) or logical (values) range. */
  // 框架度量类型（物理/逻辑）。这是一个布尔类型的字面量：
  // 语法中显式写了 ROWS，所以该属性的值为 true。
  // true：表示 ROWS（物理行，基于行数计算边界）。
  // false：表示 RANGE（逻辑范围，基于排序列的值计算边界）。
  SqlLiteral isRows;

  /** The lower bound of the window. */
  // 窗口框架的下界（起点）。定义了当前计算窗口从哪里开始。它可以是一个符号（如 UNBOUNDED PRECEDING）或者一个具体的表达式计算节点（如 3 PRECEDING）。
  // 映射为 3 PRECEDING 的 SqlCall 节点。
  @Nullable SqlNode lowerBound;

  /** The upper bound of the window. */
  // 窗口框架的上界（终点）。定义了当前计算窗口到哪里结束。它可以是 CURRENT ROW、1 FOLLOWING 等。如果 SQL 中省略了 BETWEEN...，Calcite 会在后续将其默认解析为 CURRENT ROW。
  @Nullable SqlNode upperBound;

  /** Exclude rows from the frame.   */
  // 行排除规则。对应 SQL:2003 标准中的 EXCLUDE 子句。
  // 用于指定在当前窗口框架内，哪些行应该被排除在聚合计算之外。其内部值对应 Exclusion 枚举（如 EXCLUDE CURRENT ROW, EXCLUDE TIES 等）。
  // 映射为 EXCLUDE CURRENT ROW 对应的字面量。
  SqlLiteral exclude;

  /** Whether to allow partial results. It may be null.   */
  // 是否允许部分/不完整的结果。这是一个布尔类型的字面量。主要用于流处理（Streaming SQL）或复杂滚动窗口中。
  // 如果设为 false，当窗口内的数据样本不足（例如定义了1小时窗口，但目前只有45分钟数据）时，聚合函数会将其视为空窗口。
  // 在传统的批处理 SQL 中通常不显式指定，此时为 null（系统默认按 true 处理）。
  @Nullable SqlLiteral allowPartial;
  // 绑定的聚合函数调用。这是一个内部私有的辅助属性。它指向使用该窗口的函数本身（例如 AVG(salary)）。
  // 在运行时会被反向绑定指向 AVG(salary) 这个 SqlCall 节点。
  private @Nullable SqlCall windowCall = null;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a window.
   */
  public SqlWindow(SqlParserPos pos, @Nullable SqlIdentifier declName,
      @Nullable SqlIdentifier refName, SqlNodeList partitionList, SqlNodeList orderList,
      SqlLiteral isRows, @Nullable SqlNode lowerBound, @Nullable SqlNode upperBound,
      @Nullable SqlLiteral allowPartial) {
    this(pos, declName, refName, partitionList, orderList,
        isRows, lowerBound, upperBound, allowPartial, createExcludeNoOthers(SqlParserPos.ZERO));
  }

  /**
   * Creates a window.
   */
  public SqlWindow(SqlParserPos pos, @Nullable SqlIdentifier declName,
      @Nullable SqlIdentifier refName, SqlNodeList partitionList, SqlNodeList orderList,
      SqlLiteral isRows, @Nullable SqlNode lowerBound, @Nullable SqlNode upperBound,
      @Nullable SqlLiteral allowPartial, SqlLiteral exclude) {
    super(pos);
    this.declName = declName;
    this.refName = refName;
    this.partitionList = partitionList;
    this.orderList = orderList;
    this.isRows = isRows;
    this.lowerBound = lowerBound;
    this.upperBound = upperBound;
    this.allowPartial = allowPartial;
    this.exclude = exclude;

    assert exclude.symbolValue(Exclusion.class) == Exclusion.EXCLUDE_NO_OTHER
        || (lowerBound != null || upperBound != null);
    assert declName == null || declName.isSimple();
    assert partitionList != null;
    assert orderList != null;
  }

  public static SqlWindow create(@Nullable SqlIdentifier declName, @Nullable SqlIdentifier refName,
      SqlNodeList partitionList, SqlNodeList orderList, SqlLiteral isRows,
      @Nullable SqlNode lowerBound, @Nullable SqlNode upperBound, @Nullable SqlLiteral allowPartial,
      SqlParserPos pos) {
    return create(declName, refName, partitionList, orderList, isRows, lowerBound, upperBound,
        allowPartial, createExcludeNoOthers(SqlParserPos.ZERO), pos);
  }

  public static SqlWindow create(@Nullable SqlIdentifier declName, @Nullable SqlIdentifier refName,
      SqlNodeList partitionList, SqlNodeList orderList, SqlLiteral isRows,
      @Nullable SqlNode lowerBound, @Nullable SqlNode upperBound, @Nullable SqlLiteral allowPartial,
      SqlLiteral exclude, SqlParserPos pos) {
    // If there's only one bound and it's 'FOLLOWING', make it the upper
    // bound.
    if (upperBound == null
        && lowerBound != null
        && lowerBound.getKind() == SqlKind.FOLLOWING) {
      upperBound = lowerBound;
      lowerBound = null;
    }
    return new SqlWindow(pos, declName, refName, partitionList, orderList,
        isRows, lowerBound, upperBound, allowPartial, exclude);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public SqlOperator getOperator() {
    return SqlWindowOperator.INSTANCE;
  }

  @Override public SqlKind getKind() {
    return SqlKind.WINDOW;
  }

  @SuppressWarnings("nullness")
  @Override public List<SqlNode> getOperandList() {
    return ImmutableNullableList.of(declName, refName, partitionList, orderList,
        isRows, lowerBound, upperBound, allowPartial, exclude);
  }

  @SuppressWarnings("assignment.type.incompatible")
  @Override public void setOperand(int i, @Nullable SqlNode operand) {
    switch (i) {
    case 0:
      this.declName = (SqlIdentifier) operand;
      break;
    case 1:
      this.refName = (SqlIdentifier) operand;
      break;
    case 2:
      this.partitionList = (SqlNodeList) operand;
      break;
    case 3:
      this.orderList = (SqlNodeList) operand;
      break;
    case 4:
      this.isRows = (SqlLiteral) operand;
      break;
    case 5:
      this.lowerBound = operand;
      break;
    case 6:
      this.upperBound = operand;
      break;
    case 7:
      this.allowPartial = (SqlLiteral) operand;
      break;
    case 8:
      this.exclude = (SqlLiteral) operand;
      break;
    default:
      throw new AssertionError(i);
    }
  }

  @Override public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
    if (null != declName) {
      declName.unparse(writer, 0, 0);
      writer.keyword("AS");
    }

    // Override, so we don't print extra parentheses.
    getOperator().unparse(writer, this, 0, 0);
  }

  public @Nullable SqlIdentifier getDeclName() {
    return declName;
  }

  public void setDeclName(SqlIdentifier declName) {
    assert declName.isSimple();
    this.declName = declName;
  }

  public @Nullable SqlNode getLowerBound() {
    return lowerBound;
  }

  public void setLowerBound(@Nullable SqlNode lowerBound) {
    this.lowerBound = lowerBound;
  }

  public @Nullable SqlNode getUpperBound() {
    return upperBound;
  }

  public void setUpperBound(@Nullable SqlNode upperBound) {
    this.upperBound = upperBound;
  }

  public SqlLiteral getExclude() {
    return exclude;
  }

  /**
   * Returns if the window is guaranteed to have rows.
   * This is useful to refine data type of window aggregates.
   * For instance sum(non-nullable) over (empty window) is NULL.
   *
   * @return true when the window is non-empty
   *
   * @see org.apache.calcite.rel.core.Window.Group#isAlwaysNonEmpty()
   * @see SqlOperatorBinding#getGroupCount()
   * @see org.apache.calcite.sql.validate.SqlValidatorImpl#resolveWindow(SqlNode, SqlValidatorScope)
   */
  public boolean isAlwaysNonEmpty() {
    final RexWindowBound lower;
    final RexWindowBound upper;
    if (lowerBound == null) {
      if (upperBound == null) {
        lower = RexWindowBounds.UNBOUNDED_PRECEDING;
      } else {
        lower = RexWindowBounds.CURRENT_ROW;
      }
    } else if (lowerBound instanceof SqlLiteral) {
      lower = RexWindowBounds.create(lowerBound, null);
    } else {
      return false;
    }
    if (upperBound == null) {
      upper = RexWindowBounds.CURRENT_ROW;
    } else if (upperBound instanceof SqlLiteral) {
      upper = RexWindowBounds.create(upperBound, null);
    } else {
      return false;
    }
    return isAlwaysNonEmpty(lower, upper);
  }

  public static boolean isAlwaysNonEmpty(RexWindowBound lower,
      RexWindowBound upper) {
    final int lowerKey = lower.getOrderKey();
    final int upperKey = upper.getOrderKey();
    return lowerKey > -1 && lowerKey <= upperKey;
  }

  public void setRows(SqlLiteral isRows) {
    this.isRows = isRows;
  }

  @Pure
  public boolean isRows() {
    return isRows.booleanValue();
  }

  public SqlNodeList getOrderList() {
    return orderList;
  }

  public void setOrderList(SqlNodeList orderList) {
    this.orderList = orderList;
  }

  public SqlNodeList getPartitionList() {
    return partitionList;
  }

  public void setPartitionList(SqlNodeList partitionList) {
    this.partitionList = partitionList;
  }

  public @Nullable SqlIdentifier getRefName() {
    return refName;
  }

  public void setWindowCall(@Nullable SqlCall windowCall) {
    this.windowCall = windowCall;
    assert windowCall == null
        || windowCall.getOperator() instanceof SqlAggFunction;
  }

  public @Nullable SqlCall getWindowCall() {
    return windowCall;
  }

  // CHECKSTYLE: IGNORE 1
  /** @see Util#deprecated(Object, boolean) */
  static void checkSpecialLiterals(SqlWindow window, SqlValidator validator) {
    final SqlNode lowerBound = window.getLowerBound();
    final SqlNode upperBound = window.getUpperBound();
    Object lowerLitType = null;
    Object upperLitType = null;
    SqlOperator lowerOp = null;
    SqlOperator upperOp = null;
    if (null != lowerBound) {
      if (lowerBound.getKind() == SqlKind.LITERAL) {
        lowerLitType = ((SqlLiteral) lowerBound).getValue();
        if (Bound.UNBOUNDED_FOLLOWING == lowerLitType) {
          throw validator.newValidationError(lowerBound,
              RESOURCE.badLowerBoundary());
        }
      } else if (lowerBound instanceof SqlCall) {
        lowerOp = ((SqlCall) lowerBound).getOperator();
      }
    }
    if (null != upperBound) {
      if (upperBound.getKind() == SqlKind.LITERAL) {
        upperLitType = ((SqlLiteral) upperBound).getValue();
        if (Bound.UNBOUNDED_PRECEDING == upperLitType) {
          throw validator.newValidationError(upperBound,
              RESOURCE.badUpperBoundary());
        }
      } else if (upperBound instanceof SqlCall) {
        upperOp = ((SqlCall) upperBound).getOperator();
      }
    }

    if (Bound.CURRENT_ROW == lowerLitType) {
      if (null != upperOp) {
        if (upperOp == PRECEDING_OPERATOR) {
          throw validator.newValidationError(castNonNull(upperBound),
              RESOURCE.currentRowPrecedingError());
        }
      }
    } else if (null != lowerOp) {
      if (lowerOp == FOLLOWING_OPERATOR) {
        if (null != upperOp) {
          if (upperOp == PRECEDING_OPERATOR) {
            throw validator.newValidationError(castNonNull(upperBound),
                RESOURCE.followingBeforePrecedingError());
          }
        } else if (null != upperLitType) {
          if (Bound.CURRENT_ROW == upperLitType) {
            throw validator.newValidationError(castNonNull(upperBound),
                RESOURCE.currentRowFollowingError());
          }
        }
      }
    }
  }

  public static SqlLiteral createExcludeNoOthers(SqlParserPos pos) {
    return Exclusion.EXCLUDE_NO_OTHER.symbol(pos);
  }

  public static SqlLiteral createExcludeCurrentRow(SqlParserPos pos) {
    return Exclusion.EXCLUDE_CURRENT_ROW.symbol(pos);
  }

  public static SqlLiteral createExcludeTies(SqlParserPos pos) {
    return Exclusion.EXCLUDE_TIES.symbol(pos);
  }

  public static SqlLiteral createExcludeGroup(SqlParserPos pos) {
    return Exclusion.EXCLUDE_GROUP.symbol(pos);
  }

  public static boolean isExcludeNoOthers(SqlLiteral node) {
    return node.symbolValue(Exclusion.class) == Exclusion.EXCLUDE_NO_OTHER;
  }

  public static boolean isExcludeCurrentRow(SqlLiteral node) {
    return node.symbolValue(Exclusion.class) == Exclusion.EXCLUDE_CURRENT_ROW;
  }

  public static boolean isExcludeGroup(SqlLiteral node) {
    return node.symbolValue(Exclusion.class) == Exclusion.EXCLUDE_GROUP;
  }

  public static boolean isExcludeTies(SqlLiteral node) {
    return node.symbolValue(Exclusion.class) == Exclusion.EXCLUDE_TIES;
  }

  public static SqlNode createCurrentRow(SqlParserPos pos) {
    return Bound.CURRENT_ROW.symbol(pos);
  }

  public static SqlNode createUnboundedFollowing(SqlParserPos pos) {
    return Bound.UNBOUNDED_FOLLOWING.symbol(pos);
  }

  public static SqlNode createUnboundedPreceding(SqlParserPos pos) {
    return Bound.UNBOUNDED_PRECEDING.symbol(pos);
  }

  public static SqlNode createFollowing(SqlNode e, SqlParserPos pos) {
    return FOLLOWING_OPERATOR.createCall(pos, e);
  }

  public static SqlNode createPreceding(SqlNode e, SqlParserPos pos) {
    return PRECEDING_OPERATOR.createCall(pos, e);
  }

  public static SqlNode createBound(SqlLiteral range) {
    return range;
  }

  /**
   * Returns whether an expression represents the "CURRENT ROW" bound.
   */
  public static boolean isCurrentRow(SqlNode node) {
    return (node instanceof SqlLiteral)
        && ((SqlLiteral) node).symbolValue(Bound.class) == Bound.CURRENT_ROW;
  }

  /**
   * Returns whether an expression represents the "UNBOUNDED PRECEDING" bound.
   */
  public static boolean isUnboundedPreceding(SqlNode node) {
    return (node instanceof SqlLiteral)
        && ((SqlLiteral) node).symbolValue(Bound.class) == Bound.UNBOUNDED_PRECEDING;
  }

  /**
   * Returns whether an expression represents the "UNBOUNDED FOLLOWING" bound.
   */
  public static boolean isUnboundedFollowing(SqlNode node) {
    return (node instanceof SqlLiteral)
        && ((SqlLiteral) node).symbolValue(Bound.class) == Bound.UNBOUNDED_FOLLOWING;
  }

  /**
   * Creates a new window by combining this one with another.
   *
   * <p>For example,
   *
   * <blockquote><pre>WINDOW (w PARTITION BY x ORDER BY y)
   *   overlay
   *   WINDOW w AS (PARTITION BY z)</pre></blockquote>
   *
   * <p>yields
   *
   * <blockquote><pre>WINDOW (PARTITION BY z ORDER BY y)</pre></blockquote>
   *
   * <p>Does not alter this or the other window.
   *
   * @return A new window
   */
  public SqlWindow overlay(SqlWindow that, SqlValidator validator) {
    // check 7.11 rule 10c
    final SqlNodeList partitions = getPartitionList();
    if (0 != partitions.size()) {
      throw validator.newValidationError(partitions.get(0),
          RESOURCE.partitionNotAllowed());
    }

    // 7.11 rule 10d
    final SqlNodeList baseOrder = getOrderList();
    final SqlNodeList refOrder = that.getOrderList();
    if ((0 != baseOrder.size()) && (0 != refOrder.size())) {
      throw validator.newValidationError(baseOrder.get(0),
          RESOURCE.orderByOverlap());
    }

    // 711 rule 10e
    final SqlNode lowerBound = that.getLowerBound();
    final SqlNode upperBound = that.getUpperBound();
    final SqlLiteral exclude = that.getExclude();
    if ((null != lowerBound) || (null != upperBound)
        || exclude.symbolValue(Exclusion.class) != Exclusion.EXCLUDE_NO_OTHER) {
      throw validator.newValidationError(that.isRows,
          RESOURCE.refWindowWithFrame());
    }

    SqlIdentifier declNameNew = declName;
    SqlIdentifier refNameNew = refName;
    SqlNodeList partitionListNew = partitionList;
    SqlNodeList orderListNew = orderList;
    SqlLiteral isRowsNew = isRows;
    SqlNode lowerBoundNew = lowerBound;
    SqlNode upperBoundNew = upperBound;
    SqlLiteral allowPartialNew = allowPartial;

    // Clear the reference window, because the reference is now resolved.
    // The overlaying window may have its own reference, of course.
    refNameNew = null;

    // Overlay other parameters.
    if (setOperand(partitionListNew, that.partitionList, validator)) {
      partitionListNew = that.partitionList;
    }
    if (setOperand(orderListNew, that.orderList, validator)) {
      orderListNew = that.orderList;
    }
    if (setOperand(lowerBoundNew, that.lowerBound, validator)) {
      lowerBoundNew = that.lowerBound;
    }
    if (setOperand(upperBoundNew, that.upperBound, validator)) {
      upperBoundNew = that.upperBound;
    }
    return new SqlWindow(
        SqlParserPos.ZERO,
        declNameNew,
        refNameNew,
        partitionListNew,
        orderListNew,
        isRowsNew,
        lowerBoundNew,
        upperBoundNew,
        allowPartialNew,
        exclude);
  }

  private static boolean setOperand(@Nullable SqlNode clonedOperand, @Nullable SqlNode thatOperand,
      SqlValidator validator) {
    if ((thatOperand != null) && !SqlNodeList.isEmptyList(thatOperand)) {
      if ((clonedOperand == null)
          || SqlNodeList.isEmptyList(clonedOperand)) {
        return true;
      } else {
        throw validator.newValidationError(clonedOperand,
            RESOURCE.cannotOverrideWindowAttribute());
      }
    }
    return false;
  }

  /**
   * Overridden method to specifically check only the right subtree of a window
   * definition.
   *
   * @param node The SqlWindow to compare to "this" window
   * @param litmus What to do if an error is detected (nodes are not equal)
   *
   * @return boolean true if all nodes in the subtree are equal
   */
  @Override public boolean equalsDeep(@Nullable SqlNode node, Litmus litmus) {
    // This is the difference over super.equalsDeep.  It skips
    // operands[0] the declared name fo this window.  We only want
    // to check the window components.
    return node == this
        || node instanceof SqlWindow
        && SqlNode.equalDeep(
            Util.skip(getOperandList()),
            Util.skip(((SqlWindow) node).getOperandList()), litmus);
  }

  /**
   * Returns whether partial windows are allowed. If false, a partial window
   * (for example, a window of size 1 hour which has only 45 minutes of data
   * in it) will appear to windowed aggregate functions to be empty.
   */
  @EnsuresNonNullIf(expression = "allowPartial", result = false)
  public boolean isAllowPartial() {
    // Default (and standard behavior) is to allow partial windows.
    return allowPartial == null
        || allowPartial.booleanValue();
  }

  @Override public void validate(SqlValidator validator,
      SqlValidatorScope scope) {
    SqlValidatorScope operandScope = scope; // REVIEW

    @SuppressWarnings("unused")
    SqlIdentifier declName = this.declName;
    SqlIdentifier refName = this.refName;
    SqlNodeList partitionList = this.partitionList;
    SqlNodeList orderList = this.orderList;
    SqlLiteral isRows = this.isRows;
    SqlNode lowerBound = this.lowerBound;
    SqlNode upperBound = this.upperBound;
    SqlLiteral allowPartial = this.allowPartial;

    if (refName != null) {
      SqlWindow win = validator.resolveWindow(this, operandScope);
      partitionList = win.partitionList;
      orderList = win.orderList;
      isRows = win.isRows;
      lowerBound = win.lowerBound;
      upperBound = win.upperBound;
      allowPartial = win.allowPartial;
    }

    for (SqlNode partitionItem : partitionList) {
      try {
        partitionItem.accept(Util.OverFinder.INSTANCE);
      } catch (ControlFlowException e) {
        throw validator.newValidationError(this,
            RESOURCE.partitionbyShouldNotContainOver());
      }

      partitionItem.validateExpr(validator, operandScope);
    }

    for (SqlNode orderItem : orderList) {
      boolean savedColumnReferenceExpansion =
          validator.config().columnReferenceExpansion();
      validator.transform(config -> config.withColumnReferenceExpansion(false));
      try {
        orderItem.accept(Util.OverFinder.INSTANCE);
      } catch (ControlFlowException e) {
        throw validator.newValidationError(this,
            RESOURCE.orderbyShouldNotContainOver());
      }

      try {
        orderItem.validateExpr(validator, scope);
      } finally {
        validator.transform(config ->
            config.withColumnReferenceExpansion(savedColumnReferenceExpansion));
      }
    }

    // 6.10 rule 6a Function RANK & DENSE_RANK require ORDER BY clause
    if (orderList.size() == 0
        && !SqlValidatorUtil.containsMonotonic(scope)
        && windowCall != null
        && windowCall.getOperator().requiresOrder()) {
      throw validator.newValidationError(this, RESOURCE.funcNeedsOrderBy());
    }

    // Run framing checks if there are any
    if (upperBound != null || lowerBound != null) {
      // 6.10 Rule 6a RANK & DENSE_RANK do not allow ROWS or RANGE
      if (windowCall != null && !windowCall.getOperator().allowsFraming()) {
        throw validator.newValidationError(isRows, RESOURCE.rankWithFrame());
      }
      SqlTypeFamily orderTypeFam = null;

      // SQL03 7.10 Rule 11a
      if (orderList.size() > 0) {
        // if order by is a compound list then range not allowed
        if (orderList.size() > 1
            && !isRows()
            && !onlySymbolBounds(lowerBound, upperBound)) {
          throw validator.newValidationError(isRows,
              RESOURCE.compoundOrderByProhibitsRange());
        }

        // get the type family for the sort key for Frame Boundary Val.
        RelDataType orderType =
            validator.deriveType(
                operandScope,
                orderList.get(0));
        orderTypeFam = orderType.getSqlTypeName().getFamily();
      } else {
        // requires an ORDER BY clause if frame is logical(RANGE)
        // We relax this requirement if the table appears to be
        // sorted already
        if (!onlySymbolBounds(lowerBound, upperBound)
            && !isRows()
            && !SqlValidatorUtil.containsMonotonic(scope)) {
          throw validator.newValidationError(this,
              RESOURCE.overMissingOrderBy());
        }
      }

      // Let the bounds validate themselves
      validateFrameBoundary(
          lowerBound,
          isRows(),
          orderTypeFam,
          validator,
          operandScope);
      validateFrameBoundary(
          upperBound,
          isRows(),
          orderTypeFam,
          validator,
          operandScope);

      // Validate across boundaries. 7.10 Rule 8 a-d
      checkSpecialLiterals(this, validator);
    } else if (orderList.size() == 0
        && !SqlValidatorUtil.containsMonotonic(scope)
        && windowCall != null
        && windowCall.getOperator().requiresOrder()) {
      throw validator.newValidationError(this, RESOURCE.overMissingOrderBy());
    }

    if (!isRows() && !isAllowPartial()) {
      throw validator.newValidationError(castNonNull(allowPartial),
          RESOURCE.cannotUseDisallowPartialWithRange());
    }
  }

  private boolean onlySymbolBounds(@Nullable SqlNode lowerBound, @Nullable SqlNode upperBound) {
    return lowerBound != null && upperBound != null
        && (isCurrentRow(lowerBound) || isUnboundedPreceding(lowerBound))
        && (isCurrentRow(upperBound) || isUnboundedFollowing(upperBound));
  }

  private static void validateFrameBoundary(
      @Nullable SqlNode bound,
      boolean isRows,
      @Nullable SqlTypeFamily orderTypeFam,
      SqlValidator validator,
      SqlValidatorScope scope) {
    if (null == bound) {
      return;
    }
    bound.validate(validator, scope);
    switch (bound.getKind()) {
    case LITERAL:
      // is there really anything to validate here? this covers
      // "CURRENT_ROW","unbounded preceding" & "unbounded following"
      break;

    case OTHER:
    case FOLLOWING:
    case PRECEDING:
      assert bound instanceof SqlCall;
      final SqlNode boundVal = ((SqlCall) bound).operand(0);

      // SQL03 7.10 rule 11b Physical ROWS must be a numeric constant. JR:
      // actually it's SQL03 7.11 rule 11b "exact numeric with scale 0"
      // means not only numeric constant but exact numeric integral
      // constant. We also interpret the spec. to not allow negative
      // values, but allow zero.
      if (isRows) {
        if (boundVal instanceof SqlNumericLiteral) {
          final SqlNumericLiteral boundLiteral =
              (SqlNumericLiteral) boundVal;
          if (!boundLiteral.isExact()
              || (boundLiteral.getScale() != null
                && boundLiteral.getValueAs(BigDecimal.class).stripTrailingZeros().scale() > 0)
              || (0 > boundLiteral.longValue(true))) {
            // true == throw if not exact (we just tested that - right?)
            throw validator.newValidationError(boundVal,
                RESOURCE.rowMustBeNonNegativeIntegral());
          }
        } else {
          // Allow expressions in ROWS clause
        }
      }

      // if this is a range spec check and make sure the boundary type
      // and order by type are compatible
      if (orderTypeFam != null && !isRows) {
        final RelDataType boundType = validator.deriveType(scope, boundVal);
        final SqlTypeFamily boundTypeFamily =
            boundType.getSqlTypeName().getFamily();
        final List<SqlTypeFamily> allowableBoundTypeFamilies =
            orderTypeFam.allowableDifferenceTypes();
        if (allowableBoundTypeFamilies.isEmpty()) {
          throw validator.newValidationError(boundVal,
              RESOURCE.orderByDataTypeProhibitsRange());
        }
        if (!allowableBoundTypeFamilies.contains(boundTypeFamily)) {
          throw validator.newValidationError(boundVal,
              RESOURCE.orderByRangeMismatch());
        }
      }
      break;
    default:
      throw new AssertionError("Unexpected node type");
    }
  }

  /**
   * Creates a window <code>(RANGE <i>columnName</i> CURRENT ROW)</code>.
   *
   * @param columnName Order column
   */
  public SqlWindow createCurrentRowWindow(final String columnName) {
    return SqlWindow.create(
        null,
        null,
        new SqlNodeList(SqlParserPos.ZERO),
        new SqlNodeList(
            ImmutableList.of(
                new SqlIdentifier(columnName, SqlParserPos.ZERO)),
            SqlParserPos.ZERO),
        SqlLiteral.createBoolean(true, SqlParserPos.ZERO),
        SqlWindow.createCurrentRow(SqlParserPos.ZERO),
        SqlWindow.createCurrentRow(SqlParserPos.ZERO),
        SqlLiteral.createBoolean(true, SqlParserPos.ZERO),
        SqlParserPos.ZERO);
  }

  /**
   * Creates a window <code>(RANGE <i>columnName</i> UNBOUNDED
   * PRECEDING)</code>.
   *
   * @param columnName Order column
   */
  public SqlWindow createUnboundedPrecedingWindow(final String columnName) {
    return SqlWindow.create(
        null,
        null,
        new SqlNodeList(SqlParserPos.ZERO),
        new SqlNodeList(
            ImmutableList.of(
                new SqlIdentifier(columnName, SqlParserPos.ZERO)),
            SqlParserPos.ZERO),
        SqlLiteral.createBoolean(false, SqlParserPos.ZERO),
        SqlWindow.createUnboundedPreceding(SqlParserPos.ZERO),
        SqlWindow.createCurrentRow(SqlParserPos.ZERO),
        SqlLiteral.createBoolean(false, SqlParserPos.ZERO),
        SqlParserPos.ZERO);
  }

  @Deprecated // to be removed before 2.0
  public void populateBounds() {
    if (lowerBound == null && upperBound == null) {
      setLowerBound(SqlWindow.createUnboundedPreceding(pos));
    }
    if (lowerBound == null) {
      setLowerBound(SqlWindow.createCurrentRow(pos));
    }
    if (upperBound == null) {
      setUpperBound(SqlWindow.createCurrentRow(pos));
    }
  }

  /**
   * An enumeration of types of exclusion rows in a window: <code>EXCLUDE NO OTHERS</code>,
   * <code>EXCLUDE CURRENT ROW</code>, <code>EXCLUDE TIES</code> and <code>EXCLUDE GROUP</code>.
   */
  enum Exclusion implements Symbolizable {
    EXCLUDE_NO_OTHER("EXCLUDE NO OTHER"),
    EXCLUDE_CURRENT_ROW("EXCLUDE CURRENT ROW"),
    EXCLUDE_TIES("EXCLUDE TIES"),
    EXCLUDE_GROUP("EXCLUDE GROUP");

    private final String sql;

    Exclusion(String sql) {
      this.sql = sql;
    }

    @Override public String toString() {
      return sql;
    }

  }

  /**
   * An enumeration of types of bounds in a window: <code>CURRENT ROW</code>,
   * <code>UNBOUNDED PRECEDING</code>, and <code>UNBOUNDED FOLLOWING</code>.
   */
  enum Bound implements Symbolizable {
    CURRENT_ROW("CURRENT ROW"),
    UNBOUNDED_PRECEDING("UNBOUNDED PRECEDING"),
    UNBOUNDED_FOLLOWING("UNBOUNDED FOLLOWING");

    private final String sql;

    Bound(String sql) {
      this.sql = sql;
    }

    @Override public String toString() {
      return sql;
    }
  }

  /** An operator describing a window specification. */
  private static class SqlWindowOperator extends SqlOperator {
    private static final SqlWindowOperator INSTANCE = new SqlWindowOperator();

    private SqlWindowOperator() {
      super("WINDOW", SqlKind.WINDOW, 2, true, null, null, null);
    }

    @Override public SqlSyntax getSyntax() {
      return SqlSyntax.SPECIAL;
    }

    @SuppressWarnings("argument.type.incompatible")
    @Override public SqlCall createCall(
        @Nullable SqlLiteral functionQualifier,
        SqlParserPos pos,
        @Nullable SqlNode... operands) {
      assert functionQualifier == null;
      assert operands.length == 9;
      return create(
          (SqlIdentifier) operands[0],
          (SqlIdentifier) operands[1],
          (SqlNodeList) operands[2],
          (SqlNodeList) operands[3],
          (SqlLiteral) operands[4],
          operands[5],
          operands[6],
          (SqlLiteral) operands[7],
          (SqlLiteral) operands[8],
          pos);
    }

    @Override public <R> void acceptCall(
        SqlVisitor<R> visitor,
        SqlCall call,
        boolean onlyExpressions,
        SqlBasicVisitor.ArgHandler<R> argHandler) {
      if (onlyExpressions) {
        for (Ord<SqlNode> operand : Ord.zip(call.getOperandList())) {
          // if the second param is an Identifier then it's supposed to
          // be a name from a window clause and isn't part of the
          // group by check
          if (operand.e == null) {
            continue;
          }
          if (operand.i == 1 && operand.e instanceof SqlIdentifier) {
            // skip refName
            continue;
          }
          argHandler.visitChild(visitor, call, operand.i, operand.e);
        }
      } else {
        super.acceptCall(visitor, call, onlyExpressions, argHandler);
      }
    }

    @Override public void unparse(
        SqlWriter writer,
        SqlCall call,
        int leftPrec,
        int rightPrec) {
      final SqlWindow window = (SqlWindow) call;
      final SqlWriter.Frame frame =
          writer.startList(SqlWriter.FrameTypeEnum.WINDOW, "(", ")");
      if (window.refName != null) {
        window.refName.unparse(writer, 0, 0);
      }
      if (window.partitionList.size() > 0) {
        writer.sep("PARTITION BY");
        final SqlWriter.Frame partitionFrame = writer.startList("", "");
        window.partitionList.unparse(writer, 0, 0);
        writer.endList(partitionFrame);
      }
      if (window.orderList.size() > 0) {
        writer.sep("ORDER BY");
        final SqlWriter.Frame orderFrame = writer.startList("", "");
        window.orderList.unparse(writer, 0, 0);
        writer.endList(orderFrame);
      }
      SqlNode lowerBound = window.lowerBound;
      SqlNode upperBound = window.upperBound;
      SqlLiteral exclude = window.exclude;
      if (lowerBound == null) {
        // No ROWS or RANGE clause
      } else if (upperBound == null) {
        if (window.isRows()) {
          writer.sep("ROWS");
        } else {
          writer.sep("RANGE");
        }
        lowerBound.unparse(writer, 0, 0);
        if (!isExcludeNoOthers(exclude)) {
          exclude.unparse(writer, 0, 0);
        }
      } else {
        if (window.isRows()) {
          writer.sep("ROWS BETWEEN");
        } else {
          writer.sep("RANGE BETWEEN");
        }
        lowerBound.unparse(writer, 0, 0);
        writer.keyword("AND");
        upperBound.unparse(writer, 0, 0);
        if (!isExcludeNoOthers(exclude)) {
          exclude.unparse(writer, 0, 0);
        }
      }

      // ALLOW PARTIAL/DISALLOW PARTIAL
      if (window.allowPartial == null) {
        // do nothing
      } else if (window.isAllowPartial()) {
        // We could output "ALLOW PARTIAL", but this syntax is
        // non-standard. Omitting the clause has the same effect.
      } else {
        writer.keyword("DISALLOW PARTIAL");
      }

      writer.endList(frame);
    }
  }
}
