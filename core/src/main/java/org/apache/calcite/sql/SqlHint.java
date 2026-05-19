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

import org.apache.calcite.sql.parser.SqlParserPos;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.collect.ImmutableList.toImmutableList;

import static java.util.Objects.requireNonNull;

/**
 * A <code>SqlHint</code> is a node of a parse tree which represents
 * a sql hint expression.
 *
 * <p>Basic hint grammar is: hint_name[(option1, option2 ...)].
 * The hint_name should be a simple identifier, the options part is optional.
 * Every option can be of four formats:
 *
 * <ul>
 *   <li>simple identifier</li>
 *   <li>literal</li>
 *   <li>key value pair whose key is a simple identifier and value is a string literal</li>
 *   <li>key value pair whose key and value are both string literal</li>
 * </ul>
 *
 * <p>The option format can not be mixed in, they should either be all simple identifiers
 * or all literals or all key value pairs.
 *
 * <p>We support 2 kinds of hints in the parser:
 * <ul>
 *   <li>Query hint, right after the select keyword, i.e.:
 *   <pre>
 *     select &#47;&#42;&#43; hint1, hint2, ... &#42;&#47; ...
 *   </pre>
 *   </li>
 *   <li>Table hint: right after the referenced table name, i.e.:
 *   <pre>
 *     select f0, f1, f2 from t1 &#47;&#42;&#43; hint1, hint2, ... &#42;&#47; ...
 *   </pre>
 *   </li>
 * </ul>
 */
// SqlHint 类是用于表示 SQL 提示（Hints）的语法树节点。SQL 提示通常用于干预优化器的行为，例如指定索引、强制连接顺序或传递特定配置。
// 语法支持：它支持 hint_name[(option1, option2 ...)] 格式。
// 应用场景：
// 查询提示（Query Hint）：紧跟在 SELECT 关键字之后，如 SELECT /*+ INDEX(t1 idx_name) */ ...。
// 表提示（Table Hint）：紧跟在表名之后，如 SELECT * FROM t1 /*+ BROADCAST */ ...。
// 格式规范：Calcite 要求一个 Hint 的所有选项必须格式统一，要么全是标识符，要么全是常量，要么全是键值对。
public class SqlHint extends SqlCall {
  //~ Instance fields --------------------------------------------------------
  // 存储 Hint 的名称。
  // 例如在 /*+ INDEX(t1 idx) */ 中，name 就是 INDEX。
  private final SqlIdentifier name;
  // 存储 Hint 携带的参数选项列表。
  // 如果是键值对格式，键和值会交替存储在这个列表中。
  private final SqlNodeList options;
  // 标识当前 Hint 选项的格式类型
  private final HintOptionFormat optionFormat;
  // 为 SqlHint 节点提供统一的运算符定义。
  private static final SqlOperator OPERATOR =
      new SqlSpecialOperator("HINT", SqlKind.HINT) {
        @Override public SqlCall createCall(
            @Nullable SqlLiteral functionQualifier,
            SqlParserPos pos,
            @Nullable SqlNode... operands) {
          return new SqlHint(pos,
              (SqlIdentifier) requireNonNull(operands[0], "name"),
              (SqlNodeList) requireNonNull(operands[1], "options"),
              ((SqlLiteral) requireNonNull(operands[2], "optionFormat"))
                  .getValueAs(HintOptionFormat.class));
        }
      };

  //~ Constructors -----------------------------------------------------------

  public SqlHint(
      SqlParserPos pos,
      SqlIdentifier name,
      SqlNodeList options,
      HintOptionFormat optionFormat) {
    super(pos);
    this.name = name;
    this.optionFormat = optionFormat;
    this.options = options;
  }

  //~ Methods ----------------------------------------------------------------

  @Override public SqlOperator getOperator() {
    return OPERATOR;
  }

  @Override public List<SqlNode> getOperandList() {
    return ImmutableList.of(name, options, optionFormat.symbol(SqlParserPos.ZERO));
  }

  /**
   * Returns the sql hint name.
   */
  public String getName() {
    return name.getSimple();
  }

  /** Returns the hint option format. */
  public HintOptionFormat getOptionFormat() {
    return optionFormat;
  }

  /**
   * Returns a string list if the hint option is a list of
   * simple SQL identifier, or a list of literals,
   * else returns an empty list.
   */
  public List<String> getOptionList() {
    if (optionFormat == HintOptionFormat.ID_LIST) {
      return ImmutableList.copyOf(SqlIdentifier.simpleNames(options));
    } else if (optionFormat == HintOptionFormat.LITERAL_LIST) {
      return options.stream()
          .map(node -> {
            SqlLiteral literal = (SqlLiteral) node;
            return requireNonNull(literal.toValue(),
                () -> "null hint literal in " + options);
          })
          .collect(toImmutableList());
    } else {
      return ImmutableList.of();
    }
  }

  /**
   * Returns a key value string map if the hint option is a list of
   * pair, each pair contains a simple SQL identifier and a string literal;
   * else returns an empty map.
   */
  public Map<String, String> getOptionKVPairs() {
    if (optionFormat == HintOptionFormat.KV_LIST) {
      final Map<String, String> attrs = new HashMap<>();
      for (int i = 0; i < options.size() - 1; i += 2) {
        final SqlNode k = options.get(i);
        final SqlNode v = options.get(i + 1);
        attrs.put(getOptionKeyAsString(k), ((SqlLiteral) v).getValueAs(String.class));
      }
      return ImmutableMap.copyOf(attrs);
    } else {
      return ImmutableMap.of();
    }
  }

  @Override public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
    name.unparse(writer, leftPrec, rightPrec);
    if (this.options.size() > 0) {
      SqlWriter.Frame frame = writer.startList(SqlWriter.FrameTypeEnum.FUN_CALL, "(", ")");
      for (int i = 0; i < options.size(); i++) {
        SqlNode option = options.get(i);
        SqlNode nextOption = i < options.size() - 1 ? options.get(i + 1) : null;
        writer.sep(",", false);
        option.unparse(writer, leftPrec, rightPrec);
        if (optionFormat == HintOptionFormat.KV_LIST && nextOption != null) {
          writer.keyword("=");
          nextOption.unparse(writer, leftPrec, rightPrec);
          i += 1;
        }
      }
      writer.endList(frame);
    }
  }

  /** Enumeration that represents hint option format. */
  // 主要作用是规范化 SQL 提示（Hint）参数的存储和解析格式。
  public enum HintOptionFormat implements Symbolizable {
    /**
     * The hint has no options.
     */
    // 该 Hint 没有携带任何参数选项。
    // SQL 示例：/*+ NO_HASH_JOIN */
    // 内部表现：options 列表为空。
    EMPTY,
    /**
     * The hint options are as literal list.
     */
    // 参数由一个或多个字面量（常量）组成。
    // SQL 示例：/*+ MAX_EXECUTION_TIME(1000, 5000) */
    // 内部表现：options 列表中的每个元素都是 SqlLiteral（如整数、字符串、浮点数）。
    LITERAL_LIST,
    /**
     * The hint options are as simple identifier list.
     */
    // 参数由一个或多个简单标识符组成。
    // SQL 示例：/*+ INDEX(t1, idx_order_id) */
    ID_LIST,
    /**
     * The hint options are list of key-value pairs.
     * For each pair,
     * the key is a simple identifier or string literal,
     * the value is a string literal.
     */
    // 参数以键值对（Key-Value Pair）的形式存在。
    // SQL 示例：/*+ SET_VAR(optimizer_switch='mrr=on', max_heap_table_size=1024) */
    // options 列表采用交替存储方式：索引 0 是 Key，索引 1 是 Value，索引 2 是 Key... 依此类推。
    KV_LIST
  }

  //~ Tools ------------------------------------------------------------------

  private static String getOptionKeyAsString(SqlNode node) {
    assert node instanceof SqlIdentifier || SqlUtil.isLiteral(node);
    if (node instanceof SqlIdentifier) {
      return ((SqlIdentifier) node).getSimple();
    }
    return ((SqlLiteral) node).getValueAs(String.class);
  }
}
