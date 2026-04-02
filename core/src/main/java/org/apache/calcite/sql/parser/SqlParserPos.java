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
package org.apache.calcite.sql.parser;

import org.apache.calcite.sql.SqlNode;

import com.google.common.collect.Lists;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static org.apache.calcite.util.Static.RESOURCE;

/**
 * SqlParserPos represents the position of a parsed token within SQL statement
 * text.
 */
// SqlParserPos 是一个极其基础且关键的类。它主要用于追踪 SQL 语句中每个标记（Token）或语法节点（SqlNode）在原始文本中的物理位置。
// SqlParserPos 的核心职责是定位与溯源：
// 报错定位：当 SQL 解析或验证出错时，利用该类记录的行列信息，精准地告知用户错误发生的具体位置。
// 节点合并：在构建抽象语法树（AST）时，它可以将多个子节点的范围合并，计算出父节点（如整个 SELECT 语句）在源码中的起止范围。
// 引用处理：标记某个标识符是否被引号包裹（Quoted），这在处理数据库大小写敏感性时至关重要。
public class SqlParserPos implements Serializable {
  //~ Static fields/initializers ---------------------------------------------

  /**
   * SqlParserPos representing line one, character one. Use this if the node
   * doesn't correspond to a position in piece of SQL text.
   */
  // 代表起始位置（1行1列）的常量。通常用于那些不是从原始 SQL 文本中解析出来的“虚拟”节点。
  public static final SqlParserPos ZERO = new SqlParserPos(0, 0);

  /** Same as {@link #ZERO} but always quoted. */
  // 同上，但标记为已加引号。
  public static final SqlParserPos QUOTED_ZERO = new QuotedParserPos(0, 0, 0, 0);

  private static final long serialVersionUID = 1L;

  //~ Instance fields --------------------------------------------------------
  // 起始行号，从1开始
  private final int lineNumber;
  // 起始列号，从1开始
  private final int columnNumber;
  //结束行号
  private final int endLineNumber;
  //结束列号
  private final int endColumnNumber;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a new parser position.
   */
  // 创建一个点位置（起点和终点相同）
  public SqlParserPos(
      int lineNumber,
      int columnNumber) {
    this(lineNumber, columnNumber, lineNumber, columnNumber);
  }

  /**
   * Creates a new parser range.
   */
  // 创建一个范围位置。内部包含断言，确保起点不晚于终点。
  public SqlParserPos(
      int startLineNumber,
      int startColumnNumber,
      int endLineNumber,
      int endColumnNumber) {
    this.lineNumber = startLineNumber;
    this.columnNumber = startColumnNumber;
    this.endLineNumber = endLineNumber;
    this.endColumnNumber = endColumnNumber;
    assert startLineNumber < endLineNumber
        || startLineNumber == endLineNumber
        && startColumnNumber <= endColumnNumber;
  }

  //~ Methods ----------------------------------------------------------------

  @Override public int hashCode() {
    return Objects.hash(lineNumber, columnNumber, endLineNumber, endColumnNumber);
  }

  @Override public boolean equals(@Nullable Object o) {
    return o == this
        || o instanceof SqlParserPos
        && this.lineNumber == ((SqlParserPos) o).lineNumber
        && this.columnNumber == ((SqlParserPos) o).columnNumber
        && this.endLineNumber == ((SqlParserPos) o).endLineNumber
        && this.endColumnNumber == ((SqlParserPos) o).endColumnNumber;
  }

  /** Returns 1-based starting line number. */
  public int getLineNum() {
    return lineNumber;
  }

  /** Returns 1-based starting column number. */
  public int getColumnNum() {
    return columnNumber;
  }

  /** Returns 1-based end line number (same as starting line number if the
   * ParserPos is a point, not a range). */
  public int getEndLineNum() {
    return endLineNumber;
  }

  /** Returns 1-based end column number (same as starting column number if the
   * ParserPos is a point, not a range). */
  public int getEndColumnNum() {
    return endColumnNumber;
  }

  /** Returns a {@code SqlParserPos} the same as this but quoted. */
  // 根据布尔值返回一个新的位置对象。
  // 如果要加引号，则返回内部类 QuotedParserPos 的实例。
  public SqlParserPos withQuoting(boolean quoted) {
    if (isQuoted() == quoted) {
      return this;
    } else if (quoted) {
      return new QuotedParserPos(lineNumber, columnNumber, endLineNumber,
          endColumnNumber);
    } else {
      return new SqlParserPos(lineNumber, columnNumber, endLineNumber,
          endColumnNumber);
    }
  }

  /** Returns whether this SqlParserPos is quoted. */
  public boolean isQuoted() {
    return false;
  }
  // 调用资源文件格式化输出位置字符串（例如 "line 1, column 5"）
  @Override public String toString() {
    return RESOURCE.parserContext(lineNumber, columnNumber).str();
  }

  /**
   * Combines this parser position with another to create a
   * position that spans from the first point in the first to the last point
   * in the other.
   */
  // 将当前位置与另一个位置合并。
  // 逻辑：新范围从当前点的起点开始，到传入点的终点结束。常用于按顺序拼接 Token。
  public SqlParserPos plus(SqlParserPos pos) {
    return new SqlParserPos(
        getLineNum(),
        getColumnNum(),
        pos.getEndLineNum(),
        pos.getEndColumnNum());
  }

  /**
   * Combines this parser position with an array of positions to create a
   * position that spans from the first point in the first to the last point
   * in the other.
   */
  // 作用：将当前位置与一组节点的位置合并。
  // 实现：使用内部类 PosBuilder 计算足以包含所有节点的最小外包矩形范围。
  public SqlParserPos plusAll(@Nullable SqlNode[] nodes) {
    final PosBuilder b = new PosBuilder(this);
    for (SqlNode node : nodes) {
      if (node != null) {
        b.add(node.getParserPosition());
      }
    }
    return b.build(this);
  }

  /**
   * Combines this parser position with a list of positions.
   */
  public SqlParserPos plusAll(Collection<? extends @Nullable SqlNode> nodes) {
    final PosBuilder b = new PosBuilder(this);
    for (SqlNode node : nodes) {
      if (node != null) {
        b.add(node.getParserPosition());
      }
    }
    return b.build(this);
  }

  /**
   * Combines the parser positions of an array of nodes to create a position
   * which spans from the beginning of the first to the end of the last.
   */
  // 计算一组节点或位置的总和范围（从第一个的开始到最后一个的结束）。
  public static SqlParserPos sum(final SqlNode[] nodes) {
    if (nodes.length == 0) {
      throw new AssertionError();
    }
    final SqlParserPos pos0 = nodes[0].getParserPosition();
    if (nodes.length == 1) {
      return pos0;
    }
    final PosBuilder b = new PosBuilder(pos0);
    for (int i = 1; i < nodes.length; i++) {
      b.add(nodes[i].getParserPosition());
    }
    return b.build(pos0);
  }

  /**
   * Combines the parser positions of a list of nodes to create a position
   * which spans from the beginning of the first to the end of the last.
   */
  public static SqlParserPos sum(final List<? extends SqlNode> nodes) {
    if (nodes.size() == 0) {
      throw new AssertionError();
    }
    SqlParserPos pos0 = nodes.get(0).getParserPosition();
    if (nodes.size() == 1) {
      return pos0;
    }
    final PosBuilder b = new PosBuilder(pos0);
    for (int i = 1; i < nodes.size(); i++) {
      b.add(nodes.get(i).getParserPosition());
    }
    return b.build(pos0);
  }

  /** Returns a position spanning the earliest position to the latest.
   * Does not assume that the positions are sorted.
   * Throws if the list is empty. */
  public static SqlParserPos sum(Iterable<SqlParserPos> poses) {
    final List<SqlParserPos> list =
        poses instanceof List
            ? (List<SqlParserPos>) poses
            : Lists.newArrayList(poses);
    if (list.size() == 0) {
      throw new AssertionError();
    }
    final SqlParserPos pos0 = list.get(0);
    if (list.size() == 1) {
      return pos0;
    }
    final PosBuilder b = new PosBuilder(pos0);
    for (int i = 1; i < list.size(); i++) {
      b.add(list.get(i));
    }
    return b.build(pos0);
  }
  // 判断两个位置范围是否有交集。
  public boolean overlaps(SqlParserPos pos) {
    return startsBefore(pos) && endsAfter(pos)
        || pos.startsBefore(this) && pos.endsAfter(this);
  }

  private boolean startsBefore(SqlParserPos pos) {
    return lineNumber < pos.lineNumber
        || lineNumber == pos.lineNumber
        && columnNumber <= pos.columnNumber;
  }

  private boolean endsAfter(SqlParserPos pos) {
    return endLineNumber > pos.endLineNumber
        || endLineNumber == pos.endLineNumber
        && endColumnNumber >= pos.endColumnNumber;
  }
  // 判断两个位置的起点是否完全重合。
  public boolean startsAt(SqlParserPos pos) {
    return lineNumber == pos.lineNumber
        && columnNumber == pos.columnNumber;
  }

  /** Parser position for an identifier segment that is quoted. */
  // 这是 SqlParserPos 的一个简单子类，唯一的区别是它的 isQuoted() 永远返回 true。
  // 这体现了内存优化的思想：只有真正加了引号的节点才使用这个子类。
  private static class QuotedParserPos extends SqlParserPos {
    QuotedParserPos(int startLineNumber, int startColumnNumber,
        int endLineNumber, int endColumnNumber) {
      super(startLineNumber, startColumnNumber, endLineNumber,
          endColumnNumber);
    }

    @Override public boolean isQuoted() {
      return true;
    }
  }

  /** Builds a parser position. */
  private static class PosBuilder {
    private int line;
    private int column;
    private int endLine;
    private int endColumn;

    PosBuilder(SqlParserPos p) {
      this(p.lineNumber, p.columnNumber, p.endLineNumber, p.endColumnNumber);
    }

    PosBuilder(int line, int column, int endLine, int endColumn) {
      this.line = line;
      this.column = column;
      this.endLine = endLine;
      this.endColumn = endColumn;
    }

    void add(SqlParserPos pos) {
      if (pos.equals(SqlParserPos.ZERO)) {
        return;
      }
      int testLine = pos.getLineNum();
      int testColumn = pos.getColumnNum();
      if (testLine < line || testLine == line && testColumn < column) {
        line = testLine;
        column = testColumn;
      }

      testLine = pos.getEndLineNum();
      testColumn = pos.getEndColumnNum();
      if (testLine > endLine || testLine == endLine && testColumn > endColumn) {
        endLine = testLine;
        endColumn = testColumn;
      }
    }

    SqlParserPos build(SqlParserPos p) {
      return p.lineNumber == line
          && p.columnNumber == column
          && p.endLineNumber == endLine
          && p.endColumnNumber == endColumn
          ? p
          : build();
    }

    SqlParserPos build() {
      return new SqlParserPos(line, column, endLine, endColumn);
    }
  }
}
