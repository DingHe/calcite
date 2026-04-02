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

import com.google.common.collect.Sets;

import org.apiguardian.api.API;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Enumerates the possible types of {@link SqlNode}.
 *
 * <p>The values are immutable, canonical constants, so you can use Kinds to
 * find particular types of expressions quickly. To identity a call to a common
 * operator such as '=', use {@link org.apache.calcite.sql.SqlNode#isA}:
 *
 * <blockquote>
 * exp.{@link org.apache.calcite.sql.SqlNode#isA isA}({@link #EQUALS})
 * </blockquote>
 *
 * <p>Only commonly-used nodes have their own type; other nodes are of type
 * {@link #OTHER}. Some of the values, such as {@link #SET_QUERY}, represent
 * aggregates.
 *
 * <p>To quickly choose between a number of options, use a switch statement:
 *
 * <blockquote>
 * <pre>switch (exp.getKind()) {
 * case {@link #EQUALS}:
 *     ...;
 * case {@link #NOT_EQUALS}:
 *     ...;
 * default:
 *     throw new AssertionError("unexpected");
 * }</pre>
 * </blockquote>
 *
 * <p>Note that we do not even have to check that a {@code SqlNode} is a
 * {@link SqlCall}.
 *
 * <p>To identify a category of expressions, use {@code SqlNode.isA} with
 * an aggregate SqlKind. The following expression will return <code>true</code>
 * for calls to '=' and '&gt;=', but <code>false</code> for the constant '5', or
 * a call to '+':
 *
 * <blockquote>
 * <pre>exp.isA({@link #COMPARISON SqlKind.COMPARISON})</pre>
 * </blockquote>
 *
 * <p>RexNode also has a {@code getKind} method; {@code SqlKind} values are
 * preserved during translation from {@code SqlNode} to {@code RexNode}, where
 * applicable.
 *
 * <p>There is no water-tight definition of "common", but that's OK. There will
 * always be operators that don't have their own kind, and for these we use the
 * {@code SqlOperator}. But for really the common ones, e.g. the many places
 * where we are looking for {@code AND}, {@code OR} and {@code EQUALS}, the enum
 * helps.
 *
 * <p>(If we were using Scala, {@link SqlOperator} would be a case
 * class, and we wouldn't need {@code SqlKind}. But we're not.)
 */
// 在 Apache Calcite 中，SqlKind 是一个核心的枚举类。它为 SQL 语法树（AST）中的所有节点提供了一个标准化的分类标签。
// SqlKind 的本质是 SQL 节点的类型指纹。
// 在复杂的 SQL 解析和优化过程中，直接操作具体的 SqlNode 子类（如 SqlCall 或 SqlLiteral）往往不够灵活。SqlKind 提供了一种跨类别的识别机制：
// 快速分发：在 switch 语句中根据 getKind() 快速决定处理逻辑。
// 语义归类：通过预定义的集合（如 AGGREGATE 或 DML），一行代码即可判断一个节点是否属于“聚合函数”或“数据操作语句”。
// 统一性：由于 RexNode（逻辑表达式节点）也复用了 SqlKind，它保证了从 SQL 文本到逻辑计划转换过程中的语义一致性。
public enum SqlKind {
  //~ Static fields/initializers ---------------------------------------------

  // the basics

  /**
   * Expression not covered by any other {@link SqlKind} value.
   *
   * @see #OTHER_FUNCTION
   */
  // 不被定义的结点叫做other
  OTHER,

  /**
   * SELECT statement or sub-query.
   */
  // 标记一个节点为 SELECT 查询语句。
  // 它对应 SqlSelect 类。
  // 无论是顶层的查询语句，还是括号内的子查询（Sub-query），其 getKind() 都会返回 SELECT。
  // 它是 Calcite 中最复杂的节点之一，包含了 SELECT 列表、FROM 子句、WHERE 条件、GROUP BY、HAVING 等子结构。
  SELECT,

  /**
   * Sql Hint statement.
   */
  // 作用：标记 SQL 提示（Hints）。
  // 对应 SqlHint 类。
  // 用于在 SQL 中向优化器传递特定指令（例如 /*+ BROADCAST(table) */）。
  // 这些节点通常挂载在 SELECT 或 TABLE_REF 节点上，影响物理执行计划的生成。
  HINT,

  /**
   * Table reference.
   */
  // 标记一个表引用或表函数调用。
  // 在 FROM 子句中引用一张物理表或视图时，该节点会被标记为 TABLE_REF。
  // 它不仅仅指代简单的表名（那通常是 IDENTIFIER），还包括带别名的表引用或复杂的集合表函数（如 TABLE(my_func(x))）。
  TABLE_REF,

  /**
   * JOIN operator or compound FROM clause.
   *
   * <p>A FROM clause with more than one table is represented as if it were a
   * join. For example, "FROM x, y, z" is represented as
   * "JOIN(x, JOIN(x, y))".
   */
  // 作用：表示连接操作或复合 FROM 子句。
  // 隐式连接转换：这是 Calcite 解析器的一个重要特性。当你写 SELECT * FROM x, y 时，它在语法树中并不表示为一个简单的列表，而是被规范化为 JOIN(x, y)。
  // 递归结构：如注释所示，FROM x, y, z 会被处理成嵌套的二叉树结构：JOIN(x, JOIN(y, z))。
  // 连接类型：它涵盖了 INNER, LEFT, RIGHT, FULL 等所有类型的连接。
  JOIN,

  /** An identifier. */
  // 作用：表示一个标识符。
  // 对应 SqlIdentifier 类。
  // 它可以是简单的名称（如 empno），也可以是复合名称（如 scott.emp.empno）。
  // 它是 SQL 中最常见的节点，用于引用表、列、模式或变量。
  IDENTIFIER,

  /** A literal. */
  // 作用：表示一个字面量（常量）。
  // 对应 SqlLiteral 类及其子类。
  // 涵盖了数字（123）、字符串（'Hello'）、布尔值（TRUE）、甚至二进制位串。
  // 在优化器阶段，常量折叠（Constant Folding）通常会针对这些节点进行操作。
  LITERAL,

  /** Interval qualifier. */
  // 作用：表示时间间隔限定符。
  // 对应 SqlIntervalQualifier 类。
  // 用于定义 INTERVAL 表达式的单位和精度，例如 INTERVAL '1' DAY 中的 DAY 或 INTERVAL '1:2' HOUR TO MINUTE。
  // 它在处理时间加减运算和窗口函数（Window Functions）时起到关键的元数据定义作用。
  INTERVAL_QUALIFIER,

  /**
   * Function that is not a special function.
   *
   * @see #FUNCTION
   */
  // 作用：表示非内置或非特殊的通用函数。
  // 区分特殊函数：Calcite 对一些标准 SQL 函数（如 CAST, TRIM, EXTRACT）有专门的 Kind。
  // 通用容器：对于用户自定义函数（UDF）或大多数普通的数据库内置函数（如 ABS(), UPPER()），它们通常被归类为 OTHER_FUNCTION。
  // 操作符映射：此类节点通常由 SqlCall 表示，其关联的 SqlOperator 决定了具体的函数逻辑。
  OTHER_FUNCTION,

  /**
   * Input tables have either row semantics or set semantics.
   * <ul>
   * <li>Row semantics means that the result of the table function is
   * decided on a row-by-row basis.
   * <li>Set semantics means that the outcome of the function depends on how
   * the data is partitioned.
   * When the table function is called from a query, the table parameter can
   * optionally be extended with either a PARTITION BY clause or
   * an ORDER BY clause or both.
   * </ul>
   */
  // 作用：表示具有“集合语义”或“行语义”的输入表参数。
  // 这是专门为 多态表函数 (Polymorphic Table Functions, PTF) 设计的。
  // 行语义 (Row Semantics)：逻辑上类似于 map 操作，结果仅取决于当前行。
  // 集合语义 (Set Semantics)：结果取决于整个分区。在 SQL 中，这类表参数可以跟随 PARTITION BY 或 ORDER BY 子句。
  SET_SEMANTICS_TABLE,

  /** {@code CONVERT} function. */
  // 表示 CONVERT 函数。
  // 通常用于字符集转换（如将字符串从 UTF-8 转换为 GBK）或在某些方言（如 SQL Server）中进行类型转换。
  // 由于其语法在不同数据库中差异巨大（有的带 USING 关键字），Calcite 为其分配了独立的 Kind 以便特殊处理。
  CONVERT,

  /** {@code TRANSLATE} function. */
  // 作用：表示 TRANSLATE 函数。
  // 用于逐字符替换字符串。例如 TRANSLATE('abc', 'ax', '12') 会将 'a' 换成 '1'，'x' 换成 '2'。
  // 虽然功能上接近 REPLACE，但其逐位映射的逻辑在优化器进行常量折叠时需要特殊的解析规则。
  TRANSLATE,

  /** POSITION function. */
  // 作用：表示 POSITION 函数。
  // 标准 SQL 函数，用于查找子串在父串中的起始位置。
  // 对应语法：POSITION(substring IN string)。
  // 因为它使用了 IN 关键字这种特殊的语法结构（而不是逗号分隔参数），所以被赋予独立的 Kind，方便解析器识别其特殊的 SqlCall 结构。
  POSITION,

  /** EXPLAIN statement. */
  // 作用：表示 EXPLAIN 解释语句。
  // 用于查看查询的执行计划，而不是执行查询本身。
  // 对应 SqlExplain 类。它包装了一个底层的 SELECT 或 DML 语句，并记录了输出格式（如 WITHOUT IMPLEMENTATION, WITH TYPE 等）。
  EXPLAIN,

  /** DESCRIBE SCHEMA statement. */
  // 表示 DESCRIBE SCHEMA 语句。
  // 属于元数据查询指令。用于获取特定模式（Schema）的详细信息。
  // 它是管理类 SQL 的一部分，通常在交互式终端或管理工具中使用。
  DESCRIBE_SCHEMA,

  /** DESCRIBE TABLE statement. */
  // 表示 DESCRIBE TABLE 或 DESC [TABLE] 语句。
  // 属于元数据辅助命令。用于获取特定表的列定义、类型、是否为空等元数据信息。
  // 在执行阶段，Calcite 通常会将此转换为对 ResultSetMetaData 或系统视图（如 INFORMATION_SCHEMA.COLUMNS）的查询。
  DESCRIBE_TABLE,

  /** INSERT statement. */
  // 标准 DML 操作。
  // INSERT：向表中添加新行。
  // 这些 Kind 被统一包含在 SqlKind.DML 静态集合中。在优化器中，它们会被转换为 LogicalTableModify 节点，最终生成影响底层数据库状态的执行计划。
  INSERT,

  /** DELETE statement. */
  DELETE,

  /** UPDATE statement. */
  UPDATE,

  /** "{@code ALTER scope SET option = value}" statement. */
  // 作用：表示 ALTER SYSTEM/SESSION SET option = value 语句。
  // 用于修改运行时参数。例如修改时区、并行度或禁掉某个优化器规则。
  // 它对应 SqlSetOption 类，包含作用域（Scope）、选项名称和目标值。
  SET_OPTION,

  /** A dynamic parameter. */
  // 作用：表示动态参数（占位符 ?）。
  // 对应 SqlDynamicParam 类。
  // 在预编译 SQL（Prepared Statement）中，? 被解析为此 Kind。
  // 它不包含具体值，只包含一个索引位置（Index），具体值在执行时通过 PreparedStatement.setXXX() 绑定。
  DYNAMIC_PARAM,

  /** The DISTINCT keyword of the GROUP BY clause. */
  // 作用：表示 GROUP BY DISTINCT 子句中的 DISTINCT 关键字。
  // 某些 SQL 方言支持在 GROUP BY 后跟 DISTINCT 以去重分组集。
  // 虽然在标准 SQL 中较少见，但 Calcite 为了兼容性将其作为一个独立的语法标记进行追踪。
  GROUP_BY_DISTINCT,

  /**
   * ORDER BY clause.
   *
   * @see #DESCENDING
   * @see #NULLS_FIRST
   * @see #NULLS_LAST
   */
  // 作用：表示 ORDER BY 子句。
  // 它通常包装了一个查询节点（如 SELECT）并附带一组排序规范。
  // 它不仅出现在查询的最外层，也可以出现在窗口函数或集合聚合函数中。
  // 关联标记：排序通常伴随着 DESCENDING（降序）、NULLS_FIRST 和 NULLS_LAST 等后缀 Kind。
  ORDER_BY,

  /** WITH clause. */
  // 作用：支持 CTE (Common Table Expressions) 语法。
  // WITH：代表整个 WITH 子句。它通常包含一个或多个 WITH_ITEM。
  WITH,

  /** Item in WITH clause. */
  // WITH_ITEM：代表 CTE 中的单个定义，例如 WITH cte1 AS (SELECT ...) 中的 cte1 AS ... 部分。它由名称和子查询组成。
  WITH_ITEM,

  /** Represents a recursive CTE as a table ref. */
  // WITH_ITEM_TABLE_REF：专门用于表示递归 CTE 中的表引用。在递归查询中，它允许子查询引用自身定义的临时表名。
  WITH_ITEM_TABLE_REF,

  /** Item expression. */
  // 作用：表示成员访问操作（Item Access）。
  // 用于处理复杂数据类型（如数组 ARRAY 或映射 MAP）的元素提取。
  // 语法示例：my_array[1] 或 my_map['key']。在解析后，方括号内的访问会被标记为 ITEM Kind 的 SqlCall。
  ITEM,

  /** {@code UNION} relational operator. */
  // 作用：标准集合操作符。
  // UNION：并集（默认去重，UNION ALL 则保留重复）。
  UNION,

  /** {@code EXCEPT} relational operator (known as {@code MINUS} in some SQL
   * dialects). */
  // EXCEPT：差集（在某些方言如 Oracle 中称为 MINUS）。
  EXCEPT,

  /** {@code INTERSECT} relational operator. */
  // INTERSECT：交集。
  INTERSECT,

  /** {@code AS} operator. */
  // 作用：别名操作符。
  // 用于为表或列重命名。例如 SELECT col1 AS alias1 FROM table1 AS t1。
  // 在验证阶段，AS 节点会被拆解，将其右侧的标识符注册到当前作用域（Scope）的命名空间中。
  AS,

  /** Argument assignment operator, {@code =>}. */
  // 作用：具名参数赋值操作符（=>）。
  // 用于函数调用时指定参数名。语法示例：my_func(param1 => 'value')。
  // 这在处理参数较多且带有默认值的存储过程或 UDF 时非常有用，因为它允许跳过中间的默认参数。
  ARGUMENT_ASSIGNMENT,

  /** {@code DEFAULT} operator. */
  // 作用：表示 DEFAULT 关键字。
  // 用于 INSERT 语句或函数调用中，指示使用列或参数的预定义默认值。
  // 示例：INSERT INTO t (col) VALUES (DEFAULT)。
  DEFAULT,

  /** {@code OVER} operator. */
  // 作用：将一个普通的聚合函数（如 SUM）转换为窗口函数。
  // 作用：将一个普通的聚合函数（如 SUM）转换为窗口函数。
  // 结构：它是一个二元操作符，左侧是聚合函数调用，右侧是窗口定义（SqlWindow）。
  OVER,

  /** {@code RESPECT NULLS} operator. */
  // 作用：窗口函数（如 LEAD, LAG, FIRST_VALUE）的修饰符。
  // 功能：决定在滑动窗口计算时是否跳过 NULL 值。
  RESPECT_NULLS("RESPECT NULLS"),

  /** {@code IGNORE NULLS} operator. */
  IGNORE_NULLS("IGNORE NULLS"),

  /** {@code FILTER} operator. */
  // 作用：聚合过滤。语法如 SUM(c) FILTER (WHERE c > 0)。
  // 优势：比普通的 WHERE 更精准，因为它只影响该聚合函数的输入，而不过滤整行数据。
  FILTER,

  /** {@code WITHIN GROUP} operator. */
  // 作用：用于需要排序输入的聚合函数。
  // 示例：LISTAGG(name, ',') WITHIN GROUP (ORDER BY name)。它规定了聚合内的数据顺序。
  WITHIN_GROUP,

  /** {@code WITHIN DISTINCT} operator. */
  // 作用：非标准聚合修饰符，用于在特定分组集内进行去重计算。
  WITHIN_DISTINCT,

  /** Window specification. */
  // 作用：代表命名的窗口规范。
  // 用法：对应 WINDOW w AS (PARTITION BY ...) 这种在 SELECT 末尾定义的命名窗口，以便在多个 OVER 子句中复用。
  WINDOW,

  /** MERGE statement. */
  // 作用：UPSERT 操作。
  // 功能：根据条件判断，存在则更新（UPDATE），不存在则插入（INSERT）。
  MERGE,

  /** TABLESAMPLE relational operator. */
  // 作用：数据采样。
  // 语法：SELECT * FROM t TABLESAMPLE SYSTEM(10)。用于在大数据量下仅抽取百分比或固定行数的数据进行分析。
  TABLESAMPLE,

  /** PIVOT clause. */
  // 作用：行转列（Pivot）与列转行（Unpivot）。
  // 场景：用于报表展示，将分类数据维度进行旋转。
  PIVOT,

  /** UNPIVOT clause. */
  UNPIVOT,

  /** MATCH_RECOGNIZE clause. */
  // 作用：SQL 中的模式匹配（CEP - 复杂事件处理）。
  // 功能：允许在数据流或表中搜索特定的事件序列（如“股价连续三天上涨”）。
  MATCH_RECOGNIZE,

  /** SNAPSHOT operator. */
  // 作用：时态查询（Temporal Query）。
  // 语法：SELECT * FROM t FOR SYSTEM_TIME AS OF ...。用于查询表在过去某个时间点的快照状态。
  SNAPSHOT,

  // binary operators

  /** Arithmetic multiplication operator, "*". */
  // 乘法
  TIMES,

  /** Arithmetic division operator, "/". */
  // 除法。在 Calcite 中，除法会根据输入类型（如整数除法或浮点数除法）进行精细的类型推导。
  DIVIDE,

  /** Arithmetic remainder operator, "MOD" (and "%" in some dialects). */
  // 取模/取余运算。
  MOD,

  /**
   * Arithmetic plus operator, "+".
   *
   * @see #PLUS_PREFIX
   */
  // 加法与减法。
  PLUS,

  /**
   * Arithmetic minus operator, "-".
   *
   * @see #MINUS_PREFIX
   */
  MINUS,

  /**
   * Alternation operator in a pattern expression within a
   * {@code MATCH_RECOGNIZE} clause.
   */
  // 专门用于 MATCH_RECOGNIZE 子句中的 PATTERN 表达式。
  // 择一操作符。表示匹配模式 A 或者 模式 B。
  PATTERN_ALTER,

  /**
   * Concatenation operator in a pattern expression within a
   * {@code MATCH_RECOGNIZE} clause.
   */
  // 连接操作符。表示模式 A 后面紧跟模式 B。在 SQL 文本中通常直接通过空格体现（如 PATTERN (A B)），但在 AST 内部会被解析为连接节点。
  PATTERN_CONCAT,

  // comparison operators

  /** {@code IN} operator. */
  // 检查某个值是否存在于一组值或子查询结果中。
  // 二元属性：左侧是一个表达式，右侧是一个列表（SqlNodeList）或一个子查询。
  // 展开逻辑：在验证（Validation）之后、转化为关系代数（RelNode）之前，IN 通常会被重写为 EXISTS 或 JOIN 逻辑，以方便查询优化器处理。
  IN,

  /**
   * {@code NOT IN} operator.
   *
   * <p>Only occurs in SqlNode trees. Is expanded to NOT(IN ...) before
   * entering RelNode land.
   */
  // 作用：IN 的反义操作。
  // 特殊性：如注释所述，它仅存在于 SqlNode（AST）阶段。在进入 RelNode（关系代数）阶段前，会被规范化为 NOT(IN ...)。
  NOT_IN("NOT IN"),

  // 作用：表示 Druid 适配器特有的 IN 和 NOT IN 操作。
  // 适配器优化：Druid 数据库对大规模位图索引（Bitmap Index）下的集合过滤有极高的性能优化。
  // 转换逻辑：当 Calcite 识别到查询目标是 Druid 时，优化器（Planner）可能会将通用的 IN 节点转换为 DRUID_IN。
  // 这允许生成的 JSON 查询任务能够利用 Druid 原生的 in 过滤器，而不是将其拆解为多个 OR 条件。
  /** Variant of {@code IN} for the Druid adapter. */
  DRUID_IN,

  /** Variant of {@code NOT_IN} for the Druid adapter. */
  DRUID_NOT_IN,
  // 作用：小于比较。
  /** Less-than operator, "&lt;". */
  LESS_THAN("<"),
  // 作用：大于比较。
  /** Greater-than operator, "&gt;". */
  GREATER_THAN(">"),
  // 作用：小于或等于比较。
  /** Less-than-or-equal operator, "&lt;=". */
  LESS_THAN_OR_EQUAL("<="),
  // 作用：大于或等于比较。
  /** Greater-than-or-equal operator, "&gt;=". */
  GREATER_THAN_OR_EQUAL(">="),
  // 作用：等于比较。
  /** Equals operator, "=". */
  EQUALS("="),

  /**
   * Not-equals operator, "&#33;=" or "&lt;&gt;".
   * The latter is standard, and preferred.
   */
  // 作用：不等于比较运算。
  // 支持两种语法：标准的 <> 和非标准的 !=。Calcite 在解析后会统一标记为 NOT_EQUALS。
  NOT_EQUALS("<>"),
  // 作用：处理 NULL 值的“安全比较”。
  // IS_DISTINCT_FROM：如果两个值不相等，或者其中一个是 NULL 而另一个不是，则返回 TRUE。只有当两个值相等且都不是 NULL，或者两个都是 NULL 时，才返回 FALSE。
  /** {@code IS DISTINCT FROM} operator. */
  IS_DISTINCT_FROM,
  // IS_NOT_DISTINCT_FROM：等价于 MySQL 的 <=>。如果两个值相等，或者两个都是 NULL，则返回 TRUE。
  // 核心价值：它们消除了 SQL 中 NULL = NULL 返回 UNKNOWN 的困扰，是进行数据对比（如数据同步校验）时的必备工具。
  /** {@code IS NOT DISTINCT FROM} operator. */
  IS_NOT_DISTINCT_FROM,

  /** {@code SEARCH} operator. (Analogous to scalar {@code IN}, used only in
   * RexNode, not SqlNode.) */
  // 作用：高效搜索操作符（仅限 RexNode 层级）。
  // 非 SQL 语法：用户不能直接写 SEARCH 函数，它是 Calcite 优化器内部生成的。
  // Sarg 优化：它将复杂的范围判定（如 x > 10 AND x < 20）或大型列表判定（如 x IN (1, 2, ..., 100)）封装为一个 Sarg（Search Argument）对象。
  // 性能意义：通过 SEARCH Kind，后端存储引擎可以一次性判断复杂的区间重叠，而不需要逐个计算 OR 或 AND 节点。
  SEARCH,

  /** Logical "OR" operator. */
  // 作用：逻辑布尔操作符。
  // 它们是连接多个过滤条件的“粘合剂”。
  // 短路特性：在执行层级，如果 AND 的第一个操作数为 FALSE，则不再计算后续操作数；OR 同理。
  // 扁平化：Calcite 经常将 (a AND b) AND c 优化为单层的 AND(a, b, c) 以减少树深度。
  OR,

  /** Logical "AND" operator. */
  AND,

  // other infix
  // 作用：成员访问操作符。
  // 用于引用结构化对象的字段。例如 SELECT user.address.city 中的两个点都会生成 DOT 类型的节点。
  /** Dot. */
  DOT,

  /** {@code OVERLAPS} operator for periods. */
  // 作用：判断两个时间段是否有重叠。
  // 语义：只要两个周期有任何共同的时间点，即返回 TRUE。
  OVERLAPS,

  /** {@code CONTAINS} operator for periods. */
  // 作用：判断一个时间段是否完全包含另一个。
  // 语义：周期 A 的开始时间早于或等于 B，且结束时间晚于或等于 B。
  CONTAINS,

  /** {@code PRECEDES} operator for periods. */
  // 作用：判断周期 A 是否在周期 B 之前。
  // 语义：A 的结束时间早于或等于 B 的开始时间。
  PRECEDES,

  /** {@code IMMEDIATELY PRECEDES} operator for periods. */
  // 作用：判断 A 是否紧接着在 B 之前（无缝连接）。
  // 语义：A 的结束时间正好等于 B 的开始时间。
  IMMEDIATELY_PRECEDES("IMMEDIATELY PRECEDES"),

  /** {@code SUCCEEDS} operator for periods. */
  // 作用：判断周期 A 是否在周期 B 之后。
  // 语义：A 的开始时间晚于或等于 B 的结束时间。
  SUCCEEDS,

  /** {@code IMMEDIATELY SUCCEEDS} operator for periods. */
  // 作用：判断 A 是否紧接着在 B 之后。
  // 语义：A 的开始时间正好等于 B 的结束时间。
  IMMEDIATELY_SUCCEEDS("IMMEDIATELY SUCCEEDS"),

  /** {@code EQUALS} operator for periods. */
  // 作用：判断两个周期是否完全相等。
  // 语义：两个周期的开始时间和结束时间都必须一致。注意其 sql 属性为 "EQUALS"，但在 SqlKind 中为了与标量 = 区分，命名为 PERIOD_EQUALS。
  PERIOD_EQUALS("EQUALS"),

  // 作用：标准 SQL 模式匹配。
  // 特性：支持 %（通配任意字符）和 _（通配单个字符）。对应 SqlLikeOperator。
  /** {@code LIKE} operator. */
  LIKE,
  // 作用：正则模式匹配（常用于 Hive, MySQL, Spark）。
  // 特性：允许使用真正的正则表达式进行匹配。
  /** {@code RLIKE} operator. */
  RLIKE,

  /** {@code SIMILAR} operator. */
  // 作用：SQL 标准中的正则表达式匹配（SIMILAR TO）。
  // 特性：语法介于 LIKE 和 POSIX 正则之间，使用 SQL 标准定义的正则语法。
  SIMILAR,

  /** {@code ~} operator (for POSIX-style regular expressions). */
  // 作用：提供 POSIX 标准的正则表达式匹配，常见于 PostgreSQL。
  // 特征：区别于 LIKE 和 SIMILAR，它们允许在查询中直接使用标准的正则语法，并显式区分大小写敏感性。
  POSIX_REGEX_CASE_SENSITIVE,

  /** {@code ~*} operator (for case-insensitive POSIX-style regular
   * expressions). */
  POSIX_REGEX_CASE_INSENSITIVE,

  /** {@code BETWEEN} operator. */
  // 作用：范围判定（x BETWEEN a AND b）。
  BETWEEN,

  /** Variant of {@code BETWEEN} for the Druid adapter. */
  // 特征：它是一个三元操作符。DRUID_BETWEEN 是 Druid 适配器的变体，用于将范围查询直接下推为 Druid 的 bound 过滤器。
  DRUID_BETWEEN,

  /** {@code CASE} expression. */
  // 作用：SQL 中的条件分支逻辑（CASE WHEN ... THEN ... ELSE ... END）。
  // 内部结构：Calcite 会将 CASE 节点解析为操作数列表，其中奇数位通常是 WHEN 条件，偶数位是对应的 THEN 结果。
  CASE,

  /** {@code LAMBDA} expression. */
  // 作用：匿名函数（Lambda 表达式）。
  // 应用：多用于高阶函数，如 Spark 或 Presto 中的 transform(array, x -> x + 1)。它定义了输入变量及其映射逻辑。
  LAMBDA,

  /** {@code INTERVAL} expression. */
  // 作用：表示时间间隔字面量（如 INTERVAL '1' DAY）。
  // 特征：它结合了数值和时间单位（SqlIntervalQualifier），是时间加减运算的基础。
  INTERVAL,

  /** {@code SEPARATOR} expression. */
  SEPARATOR,

  /** {@code NULLIF} operator. */
  // 如果两个操作数相等，则返回 NULL；否则返回第一个操作数。
  NULLIF,

  /** {@code COALESCE} operator. */
  // 返回参数列表中的第一个非空值。在优化器阶段，它通常被重写为 CASE 表达式。
  COALESCE,

  /** {@code DECODE} function (Oracle). */
  // Oracle 特有的分支函数，逻辑上等同于简单的 CASE。
  DECODE,

  /** {@code NVL} function (Oracle, Spark). */
  // 如果 x 为空，返回 y；否则返回 x。
  NVL,

  /** {@code NVL2} function (Oracle, Spark). */
  // 如果 x 不为空，返回 y；否则返回 z。
  NVL2,

  /** {@code GREATEST} function (Oracle, Spark). */
  // 返回一组表达式中的最大值或最小值。
  GREATEST,

  /** {@code GREATEST} function (PostgreSQL). */
  // 作用：PostgreSQL 风格的 GREATEST 和 LEAST 函数。
  // 标准语义 (Oracle/Spark)：如果参数列表中包含 NULL，通常整个函数返回 NULL。
  // PostgreSQL 语义：只有当所有参数都为 NULL 时才返回 NULL；否则，它会忽略 NULL 并返回其余值中的最大/最小值。
  // Calcite 处理：通过定义 _PG 后缀的 Kind，Calcite 优化器可以根据当前的 SqlDialect 选择正确的化简（Simplify）逻辑。
  GREATEST_PG,

  /** The two-argument {@code CONCAT} function (Oracle). */
  // 仅接受两个参数。在 Oracle 中，CONCAT(a, b, c) 是非法的，必须嵌套使用。
  CONCAT2,

  /** The {@code CONCAT} function (Postgresql and MSSQL) that ignores NULL. */
  // 这种变体在处理 NULL 时具有特殊行为。通常，'A' || NULL 在标准 SQL 中返回 NULL，但某些方言（如 MSSQL 的特定设置）或函数会将其视为空字符串。
  CONCAT_WITH_NULL,

  /** The {@code CONCAT_WS} function (MSSQL). */
  // WS (With Separator)：带分隔符的拼接。
  // 差异点：MSSQL 和 Spark 在处理分隔符本身为 NULL 或参数列表全为 NULL 时的返回类型推导和跳过逻辑略有不同。独立的 Kind 确保了生成的物理计划符合目标引擎的预期。
  CONCAT_WS_MSSQL,


  /** The {@code CONCAT_WS} function (Spark). */
  CONCAT_WS_SPARK,

  /** The "IF" function (BigQuery, Hive, Spark). */
  // 作用：三元判断函数 IF(condition, value_if_true, value_if_false)。
  // 常见于 MySQL, BigQuery, Hive 和 Spark。
  // 逻辑转换：在 Calcite 内部，IF 节点通常在验证阶段之后被重写为 CASE WHEN condition THEN value_if_true ELSE value_if_false END。
  // 拥有独立的 Kind 可以让解析器（Parser）直接识别这种简写语法，而不必强制用户编写复杂的 CASE 语句。
  IF,

  /** {@code LEAST} function (Oracle). */
  LEAST,

  /** {@code LEAST} function (PostgreSQL). */
  LEAST_PG,

  /** {@code LOG} function. (Mysql, Spark). */
  // 作用：对数运算函数。
  // 在某些方言中，LOG(x) 指自然对数（底数为 $e$）。
  // 在 MySQL 和 Spark 中，LOG(base, x) 允许指定底数。
  // Calcite 通过独立 Kind 区分 LN（自然对数）和 LOG（带底数对数），确保在将查询推送到 Spark 等后端时，参数顺序和个数符合预期。
  LOG,

  /** {@code DATE_ADD} function (BigQuery Semantics). */
  // 这些 Kind 专门标记了符合 Google BigQuery 语义的操作，它们与标准 SQL 或 MySQL 的同名函数在参数结构上有显著区别。
  // 语法特征：DATE_ADD(date_expression, INTERVAL int64_expression part)。
  // 核心逻辑：向指定日期增加或减少一个时间间隔。
  // Calcite 角色：标记为 DATE_ADD 而不是通用的 PLUS，是因为 BigQuery 要求第一个参数必须是 DATE 类型，且不支持直接使用 + 运算符进行日期偏移。
  DATE_ADD,

  /** {@code DATE_TRUNC} function (BigQuery). */
  // 作用：日期截断。
  // 示例：DATE_TRUNC(DATE '2026-04-01', MONTH) 返回 2026-04-01。
  // 详细说明：它将日期降低到指定的粒度（如 YEAR, QUARTER, MONTH）。Calcite 利用此 Kind 来优化涉及时间窗口的聚合查询。
  DATE_TRUNC,

  /** {@code DATE_SUB} function (BigQuery). */
  DATE_SUB,

  /** {@code TIME_ADD} function (BigQuery). */
  // 作用：针对 TIME 类型（不含日期）的加法运算。
  // 区分点：在 BigQuery 中，DATETIME_ADD、TIMESTAMP_ADD 和 TIME_ADD 是严格区分的。Calcite 为每种类型分配 Kind，以便在校验阶段（Validation）强制执行严格的类型检查。
  TIME_ADD,

  /** {@code TIME_SUB} function (BigQuery). */
  TIME_SUB,

  /** {@code TIMESTAMP_ADD} function (ODBC, SQL Server, MySQL). */
  TIMESTAMP_ADD,

  /** {@code TIMESTAMP_DIFF} function (ODBC, SQL Server, MySQL). */
  TIMESTAMP_DIFF,

  /** {@code TIMESTAMP_SUB} function (BigQuery). */
  TIMESTAMP_SUB,

  // prefix operators

  /** Logical {@code NOT} operator. */
  // 作用：逻辑非运算。
  // 应用：反转布尔表达式的结果。对应 SqlPrefixOperator。
  NOT,

  /**
   * Unary plus operator, as in "+1".
   *
   * @see #PLUS
   */
  // 作用：一元正号（如 +5）。
  // 区分：与 PLUS（二元加法 a + b）物理符号相同，但在 AST 中必须区分，因为它的操作数只有一个。
  PLUS_PREFIX,

  /**
   * Unary minus operator, as in "-1".
   *
   * @see #MINUS
   */
  // 作用：一元负号（如 -x）。
  // 详细说明：用于数值取反。在优化器阶段，连续的负号（如 --5）通常会被常量折叠（Constant Folding）直接化简为 5。
  MINUS_PREFIX,

  /** {@code EXISTS} operator. */
  // 作用：判定子查询是否返回至少一行数据。
  // 优化：在转为 RelNode 时，EXISTS 经常被重写为 Semi-Join（半连接）。
  EXISTS,

  /** {@code SOME} quantification operator (also called {@code ANY}). */
  // 作用：判定左侧值是否与子查询返回的任一值满足比较关系。
  // 示例：x > SOME (SELECT ...)。
  SOME,

  /** {@code ALL} quantification operator. */
  // 作用：判定左侧值是否与子查询返回的所有值都满足比较关系。
  // 逻辑体现：x > ALL (...) 在语义上等价于 x > MAX(...)。
  ALL,

  /** {@code VALUES} relational operator. */
  // 作用：表示内联常量表。
  // 示例：VALUES (1, 'A'), (2, 'B')。
  // 应用：常用于测试查询或作为 INSERT INTO ... VALUES ... 的输入源。在关系代数中，它对应 LogicalValues 算子。
  VALUES,

  /**
   * Explicit table, e.g. <code>select * from (TABLE t)</code> or <code>TABLE
   * t</code>. See also {@link #COLLECTION_TABLE}.
   */
  // 作用：显式表引用。
  // 语法：TABLE my_table。
  // 详细说明：这是 SQL 标准中较少为人知的一种简写，等价于 SELECT * FROM my_table。Calcite 将其作为一类独立的 QUERY 类型处理。
  EXPLICIT_TABLE,

  /**
   * Scalar query; that is, a sub-query used in an expression context, and
   * returning one row and one column.
   */
  // 作用：标量子查询。
  // 详细说明：指在表达式上下文中使用的子查询（例如 SELECT (SELECT max(id) FROM t) AS max_id）。它必须严格返回一行一列。
  // 验证逻辑：Calcite 的验证器会检查其投影数是否为 1。在执行时，如果返回多行，系统通常会抛出运行时异常。
  SCALAR_QUERY,

  /** Procedure call. */
  // 作用：存储过程调用（CALL my_proc(arg)）。
  // 详细说明：它被归类为 DML 集合的一部分，因为它可能执行写操作。
  PROCEDURE_CALL,

  /** New specification. */
  // 作用：用于调用构造函数或实例化对象。常用于处理用户自定义类型（UDT）或 Java 互操作。
  NEW_SPECIFICATION,

  // special functions in MATCH_RECOGNIZE
  // 作用：定义聚合计算的时机。
  // 语义：RUNNING 指计算到当前匹配行时的累积值；FINAL 指在完成整个模式匹配后的最终结果。
  /** {@code FINAL} operator in {@code MATCH_RECOGNIZE}. */
  FINAL,

  /** {@code FINAL} operator in {@code MATCH_RECOGNIZE}. */
  RUNNING,

  /** {@code PREV} operator in {@code MATCH_RECOGNIZE}. */
  // 定位算子：PREV, NEXT, FIRST, LAST
  // 作用：在模式匹配过程中访问特定偏置的行。
  // 示例：PREV(A.price, 1) 获取上一个匹配到模式 A 的行的价格。
  // 区别：它们与普通的窗口函数 LAG/LEAD 类似，但只能在 MATCH_RECOGNIZE 的定义块（如 DEFINE 或 MEASURES）中使用。
  PREV,

  /** {@code NEXT} operator in {@code MATCH_RECOGNIZE}. */
  NEXT,

  /** {@code FIRST} operator in {@code MATCH_RECOGNIZE}. */
  FIRST,

  /** {@code LAST} operator in {@code MATCH_RECOGNIZE}. */
  LAST,

  /** {@code CLASSIFIER} operator in {@code MATCH_RECOGNIZE}. */
  // 返回当前行匹配到的模式变量名称（如 'A' 或 'B'）。
  CLASSIFIER,

  /** {@code MATCH_NUMBER} operator in {@code MATCH_RECOGNIZE}. */
  // 返回当前匹配组的序号（即第几次匹配成功）。
  MATCH_NUMBER,

  /** {@code SKIP TO FIRST} qualifier of restarting point in a
   * {@code MATCH_RECOGNIZE} clause. */
  // 定义匹配失败或成功后的重启策略（Restart Point）。
  // 语义：指示匹配引擎在找到一个匹配项后，直接跳到下一个匹配模式起始位置的首行进行探测。
  SKIP_TO_FIRST,

  /** {@code SKIP TO LAST} qualifier of restarting point in a
   * {@code MATCH_RECOGNIZE} clause. */
  SKIP_TO_LAST,

  // postfix operators

  /** {@code DESC} operator in {@code ORDER BY}. A parse tree, not a true
   * expression. */
  // 这些 Kind 被标注为“解析树（Parse tree）而非真实表达式”，意味着它们不能独立存在，必须作为 ORDER BY 子句中某个表达式的后缀。
  // 作用：指定降序排列。默认（ASC）通常不分配显式 Kind。
  DESCENDING,

  /** {@code NULLS FIRST} clause in {@code ORDER BY}. A parse tree, not a true
   * expression. */
  // 作用：控制 NULL 值在排序结果中的位置。
  // 差异：不同数据库对 NULL 的默认排序逻辑不同（如 Oracle 升序时 NULL 在最后，而 PostgreSQL 在最前）。这些 Kind 强制规定了跨平台的一致性行为。
  NULLS_FIRST,

  /** {@code NULLS LAST} clause in {@code ORDER BY}. A parse tree, not a true
   * expression. */
  NULLS_LAST,

  /** {@code IS TRUE} operator. */
  // 这些是标准 SQL 的一元谓词，用于处理包含 TRUE, FALSE, 和 UNKNOWN (即 NULL) 的逻辑判断。
  // 作用：显式检查布尔值。
  IS_TRUE,

  /** {@code IS FALSE} operator. */
  IS_FALSE,

  /** {@code IS NOT TRUE} operator. */
  IS_NOT_TRUE,

  /** {@code IS NOT FALSE} operator. */
  IS_NOT_FALSE,

  /** {@code IS UNKNOWN} operator. */
  // 作用：语义上等同于 IS_NULL，但专门用于布尔表达式的结果判定。
  IS_UNKNOWN,

  /** {@code IS NULL} operator. */
  // 作用：检查操作数是否为空。
  IS_NULL,

  /** {@code IS NOT NULL} operator. */
  IS_NOT_NULL,

  /** {@code PRECEDING} qualifier of an interval end-point in a window
   * specification. */
  // 这些 Kind 专门用于 OVER 子句中的 ROWS 或 RANGE 定义，描述滑动窗口的物理或逻辑边界。
  // 作用：定义窗口起始或结束点在当前行之前。
  // 示例：ROWS BETWEEN 5 PRECEDING AND CURRENT ROW。
  PRECEDING,

  /** {@code FOLLOWING} qualifier of an interval end-point in a window
   * specification. */
  // 作用：定义窗口起始或结束点在当前行之后。
  FOLLOWING,

  /**
   * The field access operator, ".".
   *
   * <p>(Only used at the RexNode level; at
   * SqlNode level, a field-access is part of an identifier.)
   */
  // 作用：在 RexNode 级别表示对结构化字段（如 Struct 或 Row）的访问。
  // 区别：在 SqlNode 阶段，a.b 只是一个标识符；在 RexNode 阶段，它变成了从一个对象中提取特定字段的操作。
  FIELD_ACCESS,

  /**
   * Reference to an input field.
   *
   * <p>(Only used at the RexNode level.)
   */
  // 作用：最常见的引用方式，通过索引访问输入流。
  // 示例：用 $0 表示输入的第一列。
  INPUT_REF,

  /**
   * Reference to an input field, with a qualified name and an identifier.
   *
   * <p>(Only used at the RexNode level.)
   */
  TABLE_INPUT_REF,

  /**
   * Reference to an input field, with pattern var as modifier.
   *
   * <p>(Only used at the RexNode level.)
   */
  PATTERN_INPUT_REF,
  /**
   * Reference to a sub-expression computed within the current relational
   * operator.
   *
   * <p>(Only used at the RexNode level.)
   */
  // 作用：引用当前算子（通常是 Project）中先前计算出的表达式结果，用于减少重复计算。
  LOCAL_REF,

  /** Reference to lambda expression parameter.
   *
   * <p>(Only used at the RexNode level.)
   */
  LAMBDA_REF,

  /**
   * Reference to correlation variable.
   *
   * <p>(Only used at the RexNode level.)
   */
  // 作用：关联变量引用。用于相关子查询，连接外部查询块与内部子查询。
  CORREL_VARIABLE,

  /**
   * the repetition quantifier of a pattern factor in a match_recognize clause.
   */
  PATTERN_QUANTIFIER,

  // functions

  /**
   * The row-constructor function. May be explicit or implicit:
   * {@code VALUES 1, ROW (2)}.
   */
  // 作用：行构造器，用于生成复合元组。
  ROW,

  /**
   * The non-standard constructor used to pass a
   * COLUMN_LIST parameter to a user-defined transform.
   */
  // COLUMN_LIST 充当了这些列名（标识符）的包装容器，将它们从简单的标识符转变为函数可以识别的参数对象。
  // 它通常出现在类似 TABLE(...) 或自定义转换函数的调用中。例如：
  // SELECT * FROM TABLE(my_transform_func(
  //  TABLE my_input_table,
  //  COLUMNS(id, name, salary)  -- 此处的 COLUMNS 会被解析为 COLUMN_LIST
  //))
  COLUMN_LIST,

  /**
   * The "CAST" operator, and also the PostgreSQL-style infix cast operator
   * "::".
   */
  // 作用：标准显式转换。支持 CAST(x AS INT) 和 PG 风格的 x::INT。
  CAST,

  /** The {@code SAFE_CAST} function, which is similar to {@link #CAST} but
   * returns NULL rather than throwing an error if the conversion fails. */
  // 作用：BigQuery 风格的转换。失败时返回 NULL 而非抛错。
  SAFE_CAST,

  /**
   * The "NEXT VALUE OF sequence" operator.
   */
  // 用于获取数据库 Sequence（序列）的值。
  NEXT_VALUE,

  /**
   * The "CURRENT VALUE OF sequence" operator.
   */
  // 用于获取数据库 Sequence（序列）的值。
  CURRENT_VALUE,

  /** {@code FLOOR} function. */
  // 作用：向下取整与向上取整。
  // 高级应用：在 Calcite 中常用于时间截断。例如 FLOOR(timestamp TO HOUR) 将时间点对齐到小时起始，这是定义滑动窗口聚合的关键。
  FLOOR,

  /** {@code CEIL} function. */
  CEIL,

  /** {@code TRIM} function. */
  // TRIM 是标准 SQL 函数，支持从两端去除指定字符。
  TRIM,

  /** {@code LTRIM} function (Oracle). */
  // LTRIM 和 RTRIM 是 Oracle/Spark 风格的单边去除。
  LTRIM,

  /** {@code RTRIM} function (Oracle). */
  RTRIM,

  /** {@code EXTRACT} function. */
  // 作用：从日期/时间中提取特定部分。
  // 示例：EXTRACT(YEAR FROM my_date)。
  EXTRACT,

  /** {@code ARRAY_APPEND} function (Spark semantics). */
  // 作用：将元素追加到数组末尾。
  // 语义：遵循 Spark 的数组处理逻辑。在转化为物理计划时，这通常涉及底层内存数组的重新分配或动态扩容。
  ARRAY_APPEND,

  /** {@code ARRAY_COMPACT} function (Spark semantics). */
  // 作用：移除数组中的所有 NULL 元素。
  ARRAY_COMPACT,

  /** {@code ARRAY_CONCAT} function (BigQuery semantics). */
  ARRAY_CONCAT,

  /** {@code ARRAY_CONTAINS} function (Spark semantics). */
  ARRAY_CONTAINS,

  /** {@code ARRAY_DISTINCT} function (Spark semantics). */
  // 作用：去重。返回一个包含原数组中唯一元素的数组，并保持原有顺序。
  ARRAY_DISTINCT,

  /** {@code ARRAY_EXCEPT} function (Spark semantics). */
  // 返回在第一个数组中存在但第二个数组中不存在的元素
  ARRAY_EXCEPT,

  /** {@code ARRAY_INSERT} function (Spark semantics). */
  ARRAY_INSERT,

  /** {@code ARRAY_INTERSECT} function (Spark semantics). */
  // 返回两个数组的交集。
  ARRAY_INTERSECT,

  /** {@code ARRAY_JOIN} function (Spark semantics). */
  ARRAY_JOIN,

  /** {@code ARRAY_LENGTH} function (Spark semantics). */
  ARRAY_LENGTH,

  /** {@code ARRAY_MAX} function (Spark semantics). */
  ARRAY_MAX,

  /** {@code ARRAY_MIN} function (Spark semantics). */
  ARRAY_MIN,

  /** {@code ARRAY_POSITION} function (Spark semantics). */
  ARRAY_POSITION,

  /** {@code ARRAY_PREPEND} function (Spark semantics). */
  ARRAY_PREPEND,

  /** {@code ARRAY_REMOVE} function (Spark semantics). */
  ARRAY_REMOVE,

  /** {@code ARRAY_REPEAT} function (Spark semantics). */
  ARRAY_REPEAT,

  /** {@code ARRAY_REVERSE} function (BigQuery semantics). */
  // 作用：反转数组中元素的顺序。
  ARRAY_REVERSE,

  /** {@code ARRAY_SIZE} function (Spark semantics). */
  ARRAY_SIZE,

  /** {@code ARRAY_TO_STRING} function (BigQuery semantics). */
  ARRAY_TO_STRING,

  /** {@code ARRAY_UNION} function (Spark semantics). */
  // 返回两个数组的并集（去重）
  ARRAY_UNION,

  /** {@code ARRAYS_OVERLAP} function (Spark semantics). */
  ARRAYS_OVERLAP,

  /** {@code ARRAYS_ZIP} function (Spark semantics). */
  ARRAYS_ZIP,

  /** {@code SORT_ARRAY} function (Spark semantics). */
  // 对数组元素进行排序。
  SORT_ARRAY,

  /** {@code MAP_CONCAT} function (Spark semantics). */
  // 合并多个 Map。如果 Key 重复，通常由后一个 Map 的 Value 覆盖。
  MAP_CONCAT,

  /** {@code MAP_ENTRIES} function (Spark semantics). */
  // 转换 Map 为结构化数组。将 Map 转换为一个由 STRUCT<K, V> 组成的数组。
  MAP_ENTRIES,

  /** {@code MAP_KEYS} function (Spark semantics). */
  // 分别提取所有的键集或值集，返回一个 ARRAY
  MAP_KEYS,

  /** {@code MAP_VALUES} function (Spark semantics). */
  MAP_VALUES,

  /** {@code MAP_CONTAINS_KEY} function (Spark semantics). */
  MAP_CONTAINS_KEY,

  /** {@code MAP_FROM_ARRAYS} function (Spark semantics). */
  // 将键数组和值数组合并
  MAP_FROM_ARRAYS,

  /** {@code MAP_FROM_ENTRIES} function (Spark semantics). */
  // 将键值对元组数组转换为 Map
  MAP_FROM_ENTRIES,

  /** {@code STR_TO_MAP} function (Spark semantics). */
  // 按照指定的分隔符（如 , 和 :）将字符串解析为 Map。
  STR_TO_MAP,

  /** {@code REVERSE} function (SQL Server, MySQL). */
  // 反转字符串字符。
  REVERSE,

  /** {@code SOUNDEX} function (Spark semantics). */
  // 语音算法。根据字符的发音将其转换为代码，用于搜索发音相似的名称。
  SOUNDEX_SPARK,

  /** {@code SUBSTR} function (BigQuery semantics). */
  SUBSTR_BIG_QUERY,

  /** {@code SUBSTR} function (MySQL semantics). */
  SUBSTR_MYSQL,

  /** {@code SUBSTR} function (Oracle semantics). */
  SUBSTR_ORACLE,

  /** {@code SUBSTR} function (PostgreSQL semantics). */
  SUBSTR_POSTGRESQL,

  /** {@code CHAR_LENGTH} function. */
  // 返回字符串的字符长度（区别于字节长度 OCTET_LENGTH）。
  CHAR_LENGTH,

  /** {@code ENDS_WITH} function. */
  // 提供比 LIKE 更直观的字符串边界匹配。
  // 语义等同于 str LIKE '%suffix'。
  ENDS_WITH,

  /** {@code STARTS_WITH} function. */
  // 提供比 LIKE 更直观的字符串边界匹配。
  // STARTS_WITH(str, prefix) 语义等同于 str LIKE 'prefix%'
  STARTS_WITH,

  /** Call to a function using JDBC function syntax. */
  // 逻辑体现：当解析器遇到 {fn CONCAT(a, b)} 这种 JDBC 转义语法时，会生成 JDBC_FN 节点。
  JDBC_FN,

  /** {@code MULTISET} value constructor. */
  // 通过列出的值构造集合。例如：MULTISET[1, 2, 2, 3]。
  MULTISET_VALUE_CONSTRUCTOR,

  /** {@code MULTISET} query constructor. */
  // 将子查询的结果集转换为一个单一的集合对象。
  // 常用于嵌套表操作，使得一行数据可以包含一个完整的“子表”。
  MULTISET_QUERY_CONSTRUCTOR,

  /** {@code JSON} value expression. */
  // 将标量值或字符串解析/标记为 JSON 格式。
  JSON_VALUE_EXPRESSION,

  /** {@code JSON_ARRAYAGG} aggregate function. */
  // 作用：在 GROUP BY 聚合过程中，将多行数据聚合为一个 JSON 数组或 JSON 对象。
  JSON_ARRAYAGG,

  /** {@code JSON_OBJECTAGG} aggregate function. */
  JSON_OBJECTAGG,

  /** {@code JSON} type function. */
  // 返回 JSON 值的类型（如 OBJECT, ARRAY, STRING 等）。
  JSON_TYPE,

  /** {@code UNNEST} operator. */
  // 将集合类型（ARRAY, MULTISET）拆分为多行数据
  // 核心地位：它是处理复杂嵌套数据的“解药”。
  UNNEST,

  /**
   * The "LATERAL" qualifier to relations in the FROM clause.
   */
  // 作用：横向关联限定符。
  // 核心能力：允许 FROM 子句右侧的项引用左侧已定义的表。
  // 场景：通常与 UNNEST 或表函数配合使用。例如：SELECT * FROM dept, LATERAL (SELECT * FROM emp WHERE emp.deptno = dept.deptno)。
  LATERAL,

  /**
   * Table operator which converts user-defined transform into a relation, for
   * example, <code>select * from TABLE(udx(x, y, z))</code>. See also the
   * {@link #EXPLICIT_TABLE} prefix operator.
   */
  // 作用：将用户自定义函数（UDX/UDTF）转换为关系表。
  // 语法：TABLE(my_func(x, y))。它是 Calcite 将函数调用包装为“表扫描”算子的关键。
  COLLECTION_TABLE,

  /**
   * Array Value Constructor, e.g. {@code Array[1, 2, 3]}.
   */
  // 作用：值构造器。通过显式列表创建，如 ARRAY[1, 2, 3] 或 MAP['key', 1]。
  ARRAY_VALUE_CONSTRUCTOR,

  /**
   * Array Query Constructor, e.g. {@code Array(select deptno from dept)}.
   */
  // 作用：查询构造器。将子查询的结果集压缩为单行中的一个 ARRAY 或 MAP 对象。
  ARRAY_QUERY_CONSTRUCTOR,

  /** MAP value constructor, e.g. {@code MAP ['washington', 1, 'obama', 44]}. */
  MAP_VALUE_CONSTRUCTOR,

  /** MAP query constructor,
   * e.g. {@code MAP (SELECT empno, deptno FROM emp)}. */
  // 作用：查询构造器。将子查询的结果集压缩为单行中的一个 ARRAY 或 MAP 对象。
  MAP_QUERY_CONSTRUCTOR,

  /** {@code CURSOR} constructor, for example, <code>SELECT * FROM
   * TABLE(udx(CURSOR(SELECT ...), x, y, z))</code>. */
  // 作用：游标构造器。将整个查询作为参数传递给表函数。它是实现高性能数据转换流（Transform）的核心。
  CURSOR,

  /** {@code CONTAINS_SUBSTR} function (BigQuery semantics). */
  // 作用：判定字符串是否包含特定子串（BigQuery 语义）。
  CONTAINS_SUBSTR,

  // internal operators (evaluated in validator) 200-299

  /** The {@code LITERAL_AGG} aggregate function that always returns the same
   * literal (even if the group is empty).
   *
   * <p>Useful during optimization because it allows you to, say, generate a
   * non-null value (to detect outer joins) in an Aggregate without an extra
   * Project. */
  // 作用：特殊的聚合函数。无论分组是否为空，始终返回预定义的常量。
  // 优化价值：在检测 OUTER JOIN 的空值生成时非常有用，可以避免在 Aggregate 算子之后额外增加一个 Project 节点。
  LITERAL_AGG,

  /**
   * Literal chain operator (for composite string literals).
   * An internal operator that does not appear in SQL syntax.
   */
  // 作用：处理跨行定义的长字符串字面量拼接（例如在某些 SQL 方言中允许 'abc' 'def' 自动拼接）。
  LITERAL_CHAIN,

  /**
   * Escape operator (always part of LIKE or SIMILAR TO expression).
   * An internal operator that does not appear in SQL syntax.
   */
  // 作用：转义标识符。它是 LIKE 或 SIMILAR TO 表达式内部的一部分，用于处理模式匹配中的特殊字符。
  ESCAPE,

  /**
   * The internal REINTERPRET operator (meaning a reinterpret cast).
   * An internal operator that does not appear in SQL syntax.
   */
  // 作用：底层重解释转换（Reinterpret Cast）。
  // 详细说明：这是一种“无损”的二进制位重解释。与 CAST 不同（CAST 可能会改变数据的表示，如 INT 转 VARCHAR），REINTERPRET 告诉引擎：“以另一种类型来读取这块内存”。
  REINTERPRET,

  /** The internal {@code EXTEND} operator that qualifies a table name in the
   * {@code FROM} clause. */
  // 作用：动态列扩展。
  // 语义：允许在 FROM 子句中临时为表定义额外的列。这在处理某些 NoSQL 存储或动态 Schema（如 HBase）时非常有用。
  EXTEND,

  /** The internal {@code CUBE} operator that occurs within a {@code GROUP BY}
   * clause. */
  // 作用：全维度聚合。生成指定列列表的所有可能子集的分组（即幂集）。
  // 示例：GROUP BY CUBE(a, b) 会生成 (a, b), (a), (b) 和 ()。
  CUBE,

  /** The internal {@code ROLLUP} operator that occurs within a {@code GROUP BY}
   * clause. */
  // 作用：层级聚合。生成指定列列表的所有前缀组合的分组。
  // 示例：GROUP BY ROLLUP(Year, Quarter, Month) 会生成 (Year, Quarter, Month), (Year, Quarter), (Year) 和 () 四种分组。
  ROLLUP,

  /** The internal {@code GROUPING SETS} operator that occurs within a
   * {@code GROUP BY} clause. */
  // 作用：显式指定分组集合。
  GROUPING_SETS,

  /** The {@code GROUPING(e, ...)} function. */
  // 作用：辅助函数。用于在结果集中标记某列是否是因为 ROLLUP/CUBE 而被聚合掉的（通常返回 1 或 0），以便前端区分“天然的 NULL”和“聚合产生的 NULL”。
  GROUPING,

  // CHECKSTYLE: IGNORE 1
  /** @deprecated Use {@link #GROUPING}. */
  @Deprecated // to be removed before 2.0
  GROUPING_ID,

  /** The {@code GROUP_ID()} function. */
  GROUP_ID,

  /** The internal "permute" function in a MATCH_RECOGNIZE clause. */
  // 作用：排列组合匹配。表示模式变量可以以任何顺序出现。
  PATTERN_PERMUTE,

  /** The special patterns to exclude enclosing pattern from output in a
   * MATCH_RECOGNIZE clause. */
  // 作用：排除输出。在模式匹配中，某些部分用于定位但不需要出现在最终结果集中。
  PATTERN_EXCLUDED,

  // Aggregate functions

  /** The {@code COUNT} aggregate function. */
  // 逻辑体现：支持 COUNT(*)（无参数）、COUNT(col)（忽略 NULL）以及 COUNT(DISTINCT col)。
  COUNT,

  /** The {@code SUM} aggregate function. */
  // 逻辑体现：对数值列求和。优化器（如 AggregateReduceFunctionsRule）经常会将复杂的聚合 Kind 拆解为这些基础 Kind。例如，AVG(x) 会被重写为 SUM(x) / COUNT(x)。
  SUM,

  /** The {@code SUM0} aggregate function. */
  SUM0,

  /** The {@code MIN} aggregate function. */
  MIN,

  /** The {@code MAX} aggregate function. */
  MAX,

  /** The {@code LEAD} aggregate function. */
  LEAD,

  /** The {@code LAG} aggregate function. */
  LAG,

  /** The {@code FIRST_VALUE} aggregate function. */
  FIRST_VALUE,

  /** The {@code LAST_VALUE} aggregate function. */
  LAST_VALUE,

  /** The {@code ANY_VALUE} aggregate function. */
  ANY_VALUE,

  /** The {@code COVAR_POP} aggregate function. */
  COVAR_POP,

  /** The {@code COVAR_SAMP} aggregate function. */
  COVAR_SAMP,

  /** The {@code REGR_COUNT} aggregate function. */
  REGR_COUNT,

  /** The {@code REGR_SXX} aggregate function. */
  REGR_SXX,

  /** The {@code REGR_SYY} aggregate function. */
  REGR_SYY,

  /** The {@code AVG} aggregate function. */
  AVG,

  /** The {@code STDDEV_POP} aggregate function. */
  STDDEV_POP,

  /** The {@code STDDEV_SAMP} aggregate function. */
  STDDEV_SAMP,

  /** The {@code VAR_POP} aggregate function. */
  VAR_POP,

  /** The {@code VAR_SAMP} aggregate function. */
  VAR_SAMP,

  /** The {@code NTILE} aggregate function. */
  NTILE,

  /** The {@code NTH_VALUE} aggregate function. */
  NTH_VALUE,

  /** The {@code LISTAGG} aggregate function. */
  LISTAGG,

  /** The {@code STRING_AGG} aggregate function. */
  STRING_AGG,

  /** The {@code COUNTIF} aggregate function. */
  COUNTIF,

  /** The {@code ARRAY_AGG} aggregate function. */
  ARRAY_AGG,

  /** The {@code ARRAY_CONCAT_AGG} aggregate function. */
  ARRAY_CONCAT_AGG,

  /** The {@code GROUP_CONCAT} aggregate function. */
  GROUP_CONCAT,

  /** The {@code COLLECT} aggregate function. */
  COLLECT,

  /** The {@code MODE} aggregate function. */
  MODE,

  /** The {@code ARG_MAX} aggregate function. */
  ARG_MAX,

  /** The {@code ARG_MIN} aggregate function. */
  ARG_MIN,

  /** The {@code PERCENTILE_CONT} aggregate function. */
  PERCENTILE_CONT,

  /** The {@code PERCENTILE_DISC} aggregate function. */
  PERCENTILE_DISC,

  /** The {@code FUSION} aggregate function. */
  FUSION,

  /** The {@code INTERSECTION} aggregate function. */
  INTERSECTION,

  /** The {@code SINGLE_VALUE} aggregate function. */
  SINGLE_VALUE,

  /** The {@code AGGREGATE} aggregate function. */
  AGGREGATE_FN,

  /** The {@code BIT_AND} aggregate function. */
  BIT_AND,

  /** The {@code BIT_OR} aggregate function. */
  BIT_OR,

  /** The {@code BIT_XOR} aggregate function. */
  BIT_XOR,

  /** The {@code ROW_NUMBER} window function. */
  ROW_NUMBER,

  /** The {@code RANK} window function. */
  RANK,

  /** The {@code PERCENT_RANK} window function. */
  PERCENT_RANK,

  /** The {@code DENSE_RANK} window function. */
  DENSE_RANK,

  /** The {@code ROW_NUMBER} window function. */
  CUME_DIST,

  /** The {@code DESCRIPTOR(column_name, ...)}. */
  DESCRIPTOR,

  /** The {@code TUMBLE} group function. */
  TUMBLE,

  // Group functions
  /** The {@code TUMBLE_START} auxiliary function of
   * the {@link #TUMBLE} group function. */
  // TODO: deprecate TUMBLE_START.
  TUMBLE_START,

  /** The {@code TUMBLE_END} auxiliary function of
   * the {@link #TUMBLE} group function. */
  // TODO: deprecate TUMBLE_END.
  TUMBLE_END,

  /** The {@code HOP} group function. */
  HOP,

  /** The {@code HOP_START} auxiliary function of
   * the {@link #HOP} group function. */
  HOP_START,

  /** The {@code HOP_END} auxiliary function of
   * the {@link #HOP} group function. */
  HOP_END,

  /** The {@code SESSION} group function. */
  SESSION,

  /** The {@code SESSION_START} auxiliary function of
   * the {@link #SESSION} group function. */
  SESSION_START,

  /** The {@code SESSION_END} auxiliary function of
   * the {@link #SESSION} group function. */
  SESSION_END,

  /** Column declaration. */
  COLUMN_DECL,

  /** Attribute definition. */
  ATTRIBUTE_DEF,

  /** {@code CHECK} constraint. */
  CHECK,

  /** {@code UNIQUE} constraint. */
  UNIQUE,

  /** {@code PRIMARY KEY} constraint. */
  PRIMARY_KEY,

  /** {@code FOREIGN KEY} constraint. */
  FOREIGN_KEY,

  // Spatial functions. They are registered as "user-defined functions" but it
  // is convenient to have a "kind" so that we can quickly match them in planner
  // rules.

  /** The {@code ST_DWithin} geo-spatial function. */
  ST_DWITHIN,

  /** The {@code ST_Point} function. */
  ST_POINT,

  /** The {@code ST_Point} function that makes a 3D point. */
  ST_POINT3,

  /** The {@code ST_MakeLine} function that makes a line. */
  ST_MAKE_LINE,

  /** The {@code ST_Contains} function that tests whether one geometry contains
   * another. */
  ST_CONTAINS,

  /** The {@code Hilbert} function that converts (x, y) to a position on a
   * Hilbert space-filling curve. */
  HILBERT,

  // DDL and session control statements follow. The list is not exhaustive: feel
  // free to add more.

  /** {@code COMMIT} session control statement. */
  COMMIT,

  /** {@code ROLLBACK} session control statement. */
  ROLLBACK,

  /** {@code ALTER SESSION} DDL statement. */
  ALTER_SESSION,

  /** {@code CREATE SCHEMA} DDL statement. */
  CREATE_SCHEMA,

  /** {@code CREATE FOREIGN SCHEMA} DDL statement. */
  CREATE_FOREIGN_SCHEMA,

  /** {@code DROP SCHEMA} DDL statement. */
  DROP_SCHEMA,

  /** {@code CREATE TABLE} DDL statement. */
  CREATE_TABLE,

  /** {@code CREATE TABLE LIKE} DDL statement. */
  CREATE_TABLE_LIKE,

  /** {@code ALTER TABLE} DDL statement. */
  ALTER_TABLE,

  /** {@code DROP TABLE} DDL statement. */
  DROP_TABLE,

  /** {@code TRUNCATE TABLE} DDL statement. */
  TRUNCATE_TABLE,

  /** {@code CREATE VIEW} DDL statement. */
  CREATE_VIEW,

  /** {@code ALTER VIEW} DDL statement. */
  ALTER_VIEW,

  /** {@code DROP VIEW} DDL statement. */
  DROP_VIEW,

  /** {@code CREATE MATERIALIZED VIEW} DDL statement. */
  CREATE_MATERIALIZED_VIEW,

  /** {@code ALTER MATERIALIZED VIEW} DDL statement. */
  ALTER_MATERIALIZED_VIEW,

  /** {@code DROP MATERIALIZED VIEW} DDL statement. */
  DROP_MATERIALIZED_VIEW,

  /** {@code CREATE SEQUENCE} DDL statement. */
  CREATE_SEQUENCE,

  /** {@code ALTER SEQUENCE} DDL statement. */
  ALTER_SEQUENCE,

  /** {@code DROP SEQUENCE} DDL statement. */
  DROP_SEQUENCE,

  /** {@code CREATE INDEX} DDL statement. */
  CREATE_INDEX,

  /** {@code ALTER INDEX} DDL statement. */
  ALTER_INDEX,

  /** {@code DROP INDEX} DDL statement. */
  DROP_INDEX,

  /** {@code CREATE TYPE} DDL statement. */
  CREATE_TYPE,

  /** {@code DROP TYPE} DDL statement. */
  DROP_TYPE,

  /** {@code CREATE FUNCTION} DDL statement. */
  CREATE_FUNCTION,

  /** {@code DROP FUNCTION} DDL statement. */
  DROP_FUNCTION,

  /** DDL statement not handled above.
   *
   * <p><b>Note to other projects</b>: If you are extending Calcite's SQL parser
   * and have your own object types you no doubt want to define CREATE and DROP
   * commands for them. Use OTHER_DDL in the short term, but we are happy to add
   * new enum values for your object types. Just ask!
   */
  OTHER_DDL;

  //~ Static fields/initializers ---------------------------------------------

  // Most of the static fields are categories, aggregating several kinds into
  // a set.

  /**
   * Category consisting of set-query node types.
   *
   * <p>Consists of:
   * {@link #EXCEPT},
   * {@link #INTERSECT},
   * {@link #UNION}.
   */
  public static final EnumSet<SqlKind> SET_QUERY =
      EnumSet.of(UNION, INTERSECT, EXCEPT);

  /**
   * Category consisting of all built-in aggregate functions.
   */
  public static final EnumSet<SqlKind> AGGREGATE =
      EnumSet.of(COUNT, SUM, SUM0, MIN, MAX, LEAD, LAG, FIRST_VALUE,
          LAST_VALUE, COVAR_POP, COVAR_SAMP, REGR_COUNT, REGR_SXX, REGR_SYY,
          AVG, STDDEV_POP, STDDEV_SAMP, VAR_POP, VAR_SAMP, NTILE, COLLECT,
          MODE, FUSION, SINGLE_VALUE, ROW_NUMBER, RANK, PERCENT_RANK, DENSE_RANK,
          CUME_DIST, JSON_ARRAYAGG, JSON_OBJECTAGG, BIT_AND, BIT_OR, BIT_XOR,
          LISTAGG, STRING_AGG, ARRAY_AGG, ARRAY_CONCAT_AGG, GROUP_CONCAT, COUNTIF,
          PERCENTILE_CONT, PERCENTILE_DISC,
          INTERSECTION, ANY_VALUE);

  /**
   * Category consisting of all DML operators.
   *
   * <p>Consists of:
   * {@link #INSERT},
   * {@link #UPDATE},
   * {@link #DELETE},
   * {@link #MERGE},
   * {@link #PROCEDURE_CALL}.
   *
   * <p>NOTE jvs 1-June-2006: For now we treat procedure calls as DML;
   * this makes it easy for JDBC clients to call execute or
   * executeUpdate and not have to process dummy cursor results.  If
   * in the future we support procedures which return results sets,
   * we'll need to refine this.
   */
  public static final EnumSet<SqlKind> DML =
      EnumSet.of(INSERT, DELETE, UPDATE, MERGE, PROCEDURE_CALL);

  /**
   * Category consisting of all DDL operators.
   */
  public static final EnumSet<SqlKind> DDL =
      EnumSet.of(COMMIT, ROLLBACK, ALTER_SESSION,
          CREATE_SCHEMA, CREATE_FOREIGN_SCHEMA, DROP_SCHEMA,
          CREATE_TABLE, CREATE_TABLE_LIKE,
          ALTER_TABLE, DROP_TABLE, TRUNCATE_TABLE,
          CREATE_FUNCTION, DROP_FUNCTION,
          CREATE_VIEW, ALTER_VIEW, DROP_VIEW,
          CREATE_MATERIALIZED_VIEW, ALTER_MATERIALIZED_VIEW,
          DROP_MATERIALIZED_VIEW,
          CREATE_SEQUENCE, ALTER_SEQUENCE, DROP_SEQUENCE,
          CREATE_INDEX, ALTER_INDEX, DROP_INDEX,
          CREATE_TYPE, DROP_TYPE,
          SET_OPTION, OTHER_DDL);

  /**
   * Category consisting of query node types.
   *
   * <p>Consists of:
   * {@link #SELECT},
   * {@link #EXCEPT},
   * {@link #INTERSECT},
   * {@link #UNION},
   * {@link #VALUES},
   * {@link #ORDER_BY},
   * {@link #EXPLICIT_TABLE}.
   */
  public static final EnumSet<SqlKind> QUERY =
      EnumSet.of(SELECT, UNION, INTERSECT, EXCEPT, VALUES, WITH, ORDER_BY,
          EXPLICIT_TABLE);

  /**
   * Category consisting of all expression operators.
   *
   * <p>A node is an expression if it is NOT one of the following:
   * {@link #AS},
   * {@link #ARGUMENT_ASSIGNMENT},
   * {@link #DEFAULT},
   * {@link #DESCENDING},
   * {@link #SELECT},
   * {@link #JOIN},
   * {@link #OTHER_FUNCTION},
   * {@link #CAST},
   * {@link #CONVERT},
   * {@link #TRIM},
   * {@link #LITERAL_CHAIN},
   * {@link #JDBC_FN},
   * {@link #PRECEDING},
   * {@link #FOLLOWING},
   * {@link #ORDER_BY},
   * {@link #COLLECTION_TABLE},
   * {@link #TABLESAMPLE},
   * {@link #UNNEST}
   * or an aggregate function, DML or DDL.
   */
  public static final Set<SqlKind> EXPRESSION =
      EnumSet.complementOf(
          concat(
              EnumSet.of(AS, ARGUMENT_ASSIGNMENT, CONVERT, TRANSLATE, DEFAULT,
                  RUNNING, FINAL, LAST, FIRST, PREV, NEXT,
                  FILTER, WITHIN_GROUP, IGNORE_NULLS, RESPECT_NULLS, SEPARATOR,
                  DESCENDING, CUBE, ROLLUP, GROUPING_SETS, EXTEND, LATERAL,
                  SELECT, JOIN, OTHER_FUNCTION, POSITION, CAST, TRIM, FLOOR, CEIL,
                  DATE_ADD, DATE_SUB, TIME_ADD, TIME_SUB,
                  TIMESTAMP_ADD, TIMESTAMP_DIFF, TIMESTAMP_SUB,
                  EXTRACT, INTERVAL,
                  LITERAL_CHAIN, JDBC_FN, PRECEDING, FOLLOWING, ORDER_BY,
                  NULLS_FIRST, NULLS_LAST, COLLECTION_TABLE, TABLESAMPLE,
                  VALUES, WITH, WITH_ITEM, ITEM, SKIP_TO_FIRST, SKIP_TO_LAST,
                  JSON_VALUE_EXPRESSION, UNNEST),
              SET_QUERY, AGGREGATE, DML, DDL));

  /**
   * Category of all SQL statement types.
   *
   * <p>Consists of all types in {@link #QUERY}, {@link #DML} and {@link #DDL}.
   */
  public static final EnumSet<SqlKind> TOP_LEVEL = concat(QUERY, DML, DDL);

  /**
   * Category consisting of regular and special functions.
   *
   * <p>Consists of regular functions {@link #OTHER_FUNCTION} and special
   * functions {@link #ROW}, {@link #TRIM}, {@link #CAST}, {@link #REVERSE},
   * {@link #JDBC_FN}.
   */
  public static final Set<SqlKind> FUNCTION =
      EnumSet.of(OTHER_FUNCTION, ROW, TRIM, LTRIM, RTRIM, CAST, REVERSE,
          JDBC_FN, POSITION, CONVERT);

  /**
   * Category of SqlAvgAggFunction.
   *
   * <p>Consists of {@link #AVG}, {@link #STDDEV_POP}, {@link #STDDEV_SAMP},
   * {@link #VAR_POP}, {@link #VAR_SAMP}.
   */
  public static final Set<SqlKind> AVG_AGG_FUNCTIONS =
      EnumSet.of(AVG, STDDEV_POP, STDDEV_SAMP, VAR_POP, VAR_SAMP);

  /**
   * Category of SqlCovarAggFunction.
   *
   * <p>Consists of {@link #COVAR_POP}, {@link #COVAR_SAMP}, {@link #REGR_SXX},
   * {@link #REGR_SYY}.
   */
  public static final Set<SqlKind> COVAR_AVG_AGG_FUNCTIONS =
      EnumSet.of(COVAR_POP, COVAR_SAMP, REGR_COUNT, REGR_SXX, REGR_SYY);

  /**
   * Category of comparison operators.
   *
   * <p>Consists of:
   * {@link #IN},
   * {@link #NOT_IN},
   * {@link #EQUALS},
   * {@link #NOT_EQUALS},
   * {@link #LESS_THAN},
   * {@link #GREATER_THAN},
   * {@link #LESS_THAN_OR_EQUAL},
   * {@link #GREATER_THAN_OR_EQUAL}.
   */
  public static final Set<SqlKind> COMPARISON =
      EnumSet.of(
          IN, NOT_IN, EQUALS, NOT_EQUALS,
          LESS_THAN, GREATER_THAN,
          GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL);

  /**
   * Category of binary arithmetic.
   *
   * <p>Consists of:
   * {@link #PLUS}
   * {@link #MINUS}
   * {@link #TIMES}
   * {@link #DIVIDE}
   * {@link #MOD}.
   */
  public static final Set<SqlKind> BINARY_ARITHMETIC =
      EnumSet.of(PLUS, MINUS, TIMES, DIVIDE, MOD);

  /**
   * Category of binary equality.
   *
   * <p>Consists of:
   * {@link #EQUALS}
   * {@link #NOT_EQUALS}
   */
  public static final Set<SqlKind> BINARY_EQUALITY =
      EnumSet.of(EQUALS, NOT_EQUALS);

  /**
   * Category of binary comparison.
   *
   * <p>Consists of:
   * {@link #EQUALS}
   * {@link #NOT_EQUALS}
   * {@link #GREATER_THAN}
   * {@link #GREATER_THAN_OR_EQUAL}
   * {@link #LESS_THAN}
   * {@link #LESS_THAN_OR_EQUAL}
   * {@link #IS_DISTINCT_FROM}
   * {@link #IS_NOT_DISTINCT_FROM}
   */
  public static final Set<SqlKind> BINARY_COMPARISON =
      EnumSet.of(
          EQUALS, NOT_EQUALS,
          GREATER_THAN, GREATER_THAN_OR_EQUAL,
          LESS_THAN, LESS_THAN_OR_EQUAL,
          IS_DISTINCT_FROM, IS_NOT_DISTINCT_FROM);

  /**
   * Category of operators that do not depend on the argument order.
   *
   * <p>For instance: {@link #AND}, {@link #OR}, {@link #EQUALS},
   * {@link #LEAST}.
   *
   * <p>Note: {@link #PLUS} does depend on the argument oder if argument types
   * are different.
   */
  @API(since = "1.22", status = API.Status.EXPERIMENTAL)
  public static final Set<SqlKind> SYMMETRICAL =
      EnumSet.of(AND, OR, EQUALS, NOT_EQUALS,
          IS_DISTINCT_FROM, IS_NOT_DISTINCT_FROM,
          GREATEST, LEAST);

  /**
   * Category of operators that do not depend on the argument order if argument
   * types are equal.
   *
   * <p>For instance: {@link #PLUS}, {@link #TIMES}.
   */
  @API(since = "1.22", status = API.Status.EXPERIMENTAL)
  public static final Set<SqlKind> SYMMETRICAL_SAME_ARG_TYPE =
      EnumSet.of(PLUS, TIMES);

  /**
   * Simple binary operators are those operators which expects operands from the same Domain.
   *
   * <p>Example: simple comparisons ({@code =}, {@code <}).
   *
   * <p>Note: it does not contain {@code IN} because that is defined on D x D^n.
   */
  @API(since = "1.24", status = API.Status.EXPERIMENTAL)
  public static final Set<SqlKind> SIMPLE_BINARY_OPS;

  static {
    EnumSet<SqlKind> kinds = EnumSet.copyOf(SqlKind.BINARY_ARITHMETIC);
    kinds.remove(SqlKind.MOD);
    kinds.addAll(SqlKind.BINARY_COMPARISON);
    SIMPLE_BINARY_OPS = Sets.immutableEnumSet(kinds);
  }

  /** Lower-case name. */
  public final String lowerName = name().toLowerCase(Locale.ROOT);
  // 存储该类型对应的 SQL 标准关键字或操作符文本
  // 说明：例如 EQUALS 对应的 sql 是 "="。如果枚举项没有显式指定字符串（使用默认构造函数），则该属性默认为空。
  public final String sql;

  SqlKind() {
    sql = name();
  }

  SqlKind(String sql) {
    this.sql = sql;
  }

  /** Returns the kind that corresponds to this operator but in the opposite
   * direction. Or returns this, if this kind is not reversible.
   *
   * <p>For example, {@code GREATER_THAN.reverse()} returns {@link #LESS_THAN}.
   */
  public SqlKind reverse() {
    switch (this) {
    case GREATER_THAN:
      return LESS_THAN;
    case GREATER_THAN_OR_EQUAL:
      return LESS_THAN_OR_EQUAL;
    case LESS_THAN:
      return GREATER_THAN;
    case LESS_THAN_OR_EQUAL:
      return GREATER_THAN_OR_EQUAL;
    default:
      return this;
    }
  }

  /** Returns the kind that you get if you apply NOT to this kind.
   *
   * <p>For example, {@code IS_NOT_NULL.negate()} returns {@link #IS_NULL}.
   *
   * <p>For {@link #IS_TRUE}, {@link #IS_FALSE}, {@link #IS_NOT_TRUE},
   * {@link #IS_NOT_FALSE}, nullable inputs need to be treated carefully.
   *
   * <p>{@code NOT(IS_TRUE(null))} = {@code NOT false} = {@code true},
   * while {@code IS_FALSE(null)} = {@code false},
   * so {@code NOT(IS_TRUE(X))} should be {@code IS_NOT_TRUE(X)}.
   * On the other hand,
   * {@code IS_TRUE(NOT(null))} = {@code IS_TRUE(null)} = {@code false}.
   *
   * <p>This is why negate() != negateNullSafe() for these operators.
   */
  public SqlKind negate() {
    switch (this) {
    case IS_TRUE:
      return IS_NOT_TRUE;
    case IS_FALSE:
      return IS_NOT_FALSE;
    case IS_NULL:
      return IS_NOT_NULL;
    case IS_NOT_TRUE:
      return IS_TRUE;
    case IS_NOT_FALSE:
      return IS_FALSE;
    case IS_NOT_NULL:
      return IS_NULL;
    case IS_DISTINCT_FROM:
      return IS_NOT_DISTINCT_FROM;
    case IS_NOT_DISTINCT_FROM:
      return IS_DISTINCT_FROM;
    default:
      return this;
    }
  }

  /** Returns the kind that you get if you negate this kind.
   * To conform to null semantics, null value should not be compared.
   *
   * <p>For {@link #IS_TRUE}, {@link #IS_FALSE}, {@link #IS_NOT_TRUE} and
   * {@link #IS_NOT_FALSE}, nullable inputs need to be treated carefully:
   *
   * <ul>
   * <li>NOT(IS_TRUE(null)) = NOT(false) = true
   * <li>IS_TRUE(NOT(null)) = IS_TRUE(null) = false
   * <li>IS_FALSE(null) = false
   * <li>IS_NOT_TRUE(null) = true
   * </ul>
   */
  public SqlKind negateNullSafe() {
    switch (this) {
    case EQUALS:
      return NOT_EQUALS;
    case NOT_EQUALS:
      return EQUALS;
    case LESS_THAN:
      return GREATER_THAN_OR_EQUAL;
    case GREATER_THAN:
      return LESS_THAN_OR_EQUAL;
    case LESS_THAN_OR_EQUAL:
      return GREATER_THAN;
    case GREATER_THAN_OR_EQUAL:
      return LESS_THAN;
    case IN:
      return NOT_IN;
    case NOT_IN:
      return IN;
    case DRUID_IN:
      return DRUID_NOT_IN;
    case DRUID_NOT_IN:
      return DRUID_IN;
    case IS_TRUE:
      return IS_FALSE;
    case IS_FALSE:
      return IS_TRUE;
    case IS_NOT_TRUE:
      return IS_NOT_FALSE;
    case IS_NOT_FALSE:
      return IS_NOT_TRUE;
     // (NOT x) IS NULL => x IS NULL
     // Similarly (NOT x) IS NOT NULL => x IS NOT NULL
    case IS_NOT_NULL:
    case IS_NULL:
      return this;
    default:
      return this.negate();
    }
  }

  /**
   * Returns whether this {@code SqlKind} belongs to a given category.
   *
   * <p>A category is a collection of kinds, not necessarily disjoint. For
   * example, QUERY is { SELECT, UNION, INTERSECT, EXCEPT, VALUES, ORDER_BY,
   * EXPLICIT_TABLE }.
   *
   * @param category Category
   * @return Whether this kind belongs to the given category
   */
  public final boolean belongsTo(Collection<SqlKind> category) {
    return category.contains(this);
  }

  @SafeVarargs
  private static <E extends Enum<E>> EnumSet<E> concat(EnumSet<E> set0,
      EnumSet<E>... sets) {
    EnumSet<E> set = set0.clone();
    for (EnumSet<E> s : sets) {
      set.addAll(s);
    }
    return set;
  }
}
