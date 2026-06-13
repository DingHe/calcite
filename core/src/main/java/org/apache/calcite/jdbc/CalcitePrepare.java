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
package org.apache.calcite.jdbc;

import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.AvaticaParameter;
import org.apache.calcite.avatica.ColumnMetaData;
import org.apache.calcite.avatica.Meta;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.EnumerableDefaults;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.linq4j.function.Function0;
import org.apache.calcite.linq4j.tree.ClassDeclaration;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.prepare.CalcitePrepareImpl;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.runtime.ArrayBindable;
import org.apache.calcite.runtime.Bindable;
import org.apache.calcite.schema.Table;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.validate.CyclicDefinitionException;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.tools.RelRunner;
import org.apache.calcite.util.ImmutableIntList;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

import static java.util.Objects.requireNonNull;

/**
 * API for a service that prepares statements for execution.
 */
// org.apache.calcite.jdbc.CalcitePrepare 是一个极其底层且核心的门面接口（Facade Interface）。
// 它是 Calcite 适配 JDBC 驱动（Avatica 框架）的桥梁，定义了如何将一条原始的 SQL 字符串或 Linq4j 表达式，逐步加工并转换为可执行的物理管道的完整标准。
// CalcitePrepare 的本质是 Calcite 编译器的“物理提纯与装配流水线”的终极抽象。
// 当用户通过 JDBC 驱动执行一条查询时，系统会调用该接口的实现类（通常是 CalcitePrepareImpl）。它在内部串联并调度了整个 SQL 解析与优化生命周期：
// 解析与校验（SQL Parse & Validate）：将字符串打碎并验证其语义。
// 关系代数转换（SQL Convert）：将基于语法树的 SqlNode 降维转换为逻辑计划树 RelNode。
// 物理规约与代码生成（Optimize & CodeGen）：调用 Planner（优化器）进行规则匹配，并利用 Linq4j 框架动态吐出 Java 物理字节码。
// 签名包装（Signature Out）：将执行所需的元数据、列定义、绑定参数、以及能拉动数据流的 Bindable 句柄打包成一个 CalciteSignature 抛给上游 JDBC 驱动。
// CalcitePrepare 是对外的“门面与执行接口”，而 Prepare 是内部的“流水线骨架与策略基类”。
// CalcitePrepare 对外契约 (Interface)。定义了 Calcite 引擎如何与 Avatica / JDBC 连接层进行交互的顶级标准。 面向调用者。主要面向 JDBC 驱动（如 CalciteConnection）和 Avatica 远程组件。
// Prepare 内部骨架 (Abstract Class)。实现了将 SQL 转换为可执行代码的核心编译流水线算法。 面向实现者。主要面向内核开发者，用来派生出特定后端（如 Java Enumerable、Bindable 或自定义执行引擎）的准备类。
// 在整个 SQL 编译执行的生命周期中，它们两个是上下游的协作关系。CalcitePrepare 处于最外层，它收到请求后，内部会委派具体的 Prepare 实现类去干脏活累活。
public interface CalcitePrepare {
  // 默认的反射工厂闭包。通过 CalcitePrepareImpl::new 提供默认实现类的无参实例化引用，供框架内部动态加载编译器实例时调用。
  Function0<CalcitePrepare> DEFAULT_FACTORY = CalcitePrepareImpl::new;
  // 线程本地的上下文双端队列栈。
  // 专门用于存放当前线程执行编译流时的上下文环境。之所以做成栈，
  // 是因为 SQL 在解析、优化过程中可能发生嵌套（例如解析 View 时，View 内部又嵌套查询了另外一个 View），栈结构能完美记录这种树状调用的上下层级关系。
  ThreadLocal<@Nullable Deque<Context>> THREAD_CONTEXT_STACK =
      ThreadLocal.withInitial(ArrayDeque::new);
  // 纯粹的 SQL 解析与语义验证。将 SQL 文本打碎并重组为语法树 SqlNode，同时推导出其返回的行数据类型。
  ParseResult parse(Context context, String sql);
  //比parse更进一步，把SqlNode转为RelNode节点
  // 关系代数化转换。比 parse 更进一步，不仅生成语法树，还强行将其脱壳转换为逻辑关系代数树 RelNode（即关系代数的根节点 RelRoot），为接下来的规则优化奠定基础。
  ConvertResult convert(Context context, String sql);

  /** Executes a DDL statement.
   *
   * <p>The statement identified itself as DDL in the
   * {@link org.apache.calcite.jdbc.CalcitePrepare.ParseResult#kind} field. */
  // 执行 DDL 语句。
  // DDL（如 CREATE TABLE）不需要复杂的规则优化与代码生成。
  // 当 ParseResult 发现算子特征（kind）属于 DDL 类型时，会直接分流调用此方法直接修改元数据 Schema。
  void executeDdl(Context context, SqlNode node);

  /** Analyzes a view.
   * @param context Context
   * @param sql View SQL
   * @param fail Whether to fail (and throw a descriptive error message) if the
   *             view is not modifiable
   * @return Result of analyzing the view
   */
  // 全面剖析和拆解一个视图（View）的底层定义。
  // 它不仅分析出视图的 SqlNode 和 RelNode，还会探查该视图是否可被更新修改（modifiable），并剥离出其底层真实的物理表路径和过滤约束条件（constraint）。如果 fail 为真且视图不可修改，则直接报错。
  AnalyzeViewResult analyzeView(Context context, String sql, boolean fail);

  // 最核心的主力入口。 负责将给定的 SQL（或其他表达形式的 Query）通过 Planner 彻底优化，并动态生成可执行的 Java 字节码，最终将其打包封装为带有元数据护照的 CalciteSignature 交付给 JDBC。
  <T> CalciteSignature<T> prepareSql(
      Context context,
      Query<T> query,
      Type elementType,
      long maxRowCount);
  // 针对 Linq4j 的 Queryable 表达式（非原生 SQL 字符串）进行物理级别的编译与准备，其最终目的与 prepareSql 镜像一致。
  <T> CalciteSignature<T> prepareQueryable(
      Context context,
      Queryable<T> queryable);

  /** Context for preparing a statement. */
  // 代表当前 Statement 准备执行时的大局环境上下文句柄，提供各种编译器基础设施组件：
  interface Context {
    // 获取 Java 关系类型工厂，用于创建和管理 SQL 类型与 Java 强类型的映射。
    JavaTypeFactory getTypeFactory();

    /** Returns the root schema for statements that need a read-consistent
     * snapshot. */
    // 获取一致性读快照视图下的根元数据 Schema 目录树。
    CalciteSchema getRootSchema();

    /** Returns the root schema for statements that need to be able to modify
     * schemas and have the results available to other statements. Viz, DDL
     * statements. */
    // 获取可变（允许原地修改）的根 Schema，专供 DDL 语句使用。
    CalciteSchema getMutableRootSchema();
    // 返回默认的物理 Schema 寻址路径（例如确定当前缺省的 database 或 namespace）。
    List<String> getDefaultSchemaPath();
    // 获取当前物理连接的硬配置大纲（CalciteConnectionConfig）。
    CalciteConnectionConfig config();

    /** Returns the spark handler. Never null. */
    // 索要 Spark 处理器的勾子，永远不可为 null（如果没装，会返回一个不工作的替身）。
    SparkHandler spark();
    // 获取运行时动态参数传递的核心上下文环境（DataContext）。
    DataContext getDataContext();

    /** Returns the path of the object being analyzed, or null.
     *
     * <p>The object is being analyzed is typically a view. If it is already
     * being analyzed further up the stack, the view definition can be deduced
     * to be cyclic. */
    // 获取当前正在被分析的物理或逻辑对象（如视图）的层级路径。
    @Nullable List<String> getObjectPath();

    /** Gets a runner; it can execute a relational expression. */
    // 获取一个可以直接运行 RelNode 关系表达式的轻量级物理执行器指针。
    RelRunner getRelRunner();
  }

  /** Callback to register Spark as the main engine. */
  // Calcite 与 Apache Spark 深度混编和物理接管的专属控制勾子：
  // 核心作用是允许 Apache Spark 作为 Calcite 的分布式物理执行后端（Backend）加入到编译与执行流中。
  // 当 Calcite 完成了逻辑层面的 SQL 优化后，
  // 如果发现某些算子更适合交给 Spark 或者是分布式大数据引擎来跑，它就会通过这个 Handler 将 Calcite 的 AST（算子树）无缝翻译并编译为 Spark 认可的分布式执行算子。
  interface SparkHandler {
    // RelOptPlanner planner：Calcite 的核心优化器实例。
    // RelNode rootRel：当前处于逻辑阶段或部分物理阶段的关系代数表达式树根节点。
    // boolean restructure：一个布尔标记，指示是否需要对算子树的物理结构进行激进的重组。
    // 返回值：RelNode（类型拉平或重组后的新关系代数表达式树）。
    // Spark 的内置数据类型（如复杂的 StructType、Row 结构）和数据流向与 Calcite 原生的 Java 物理类型可能存在阻抗失配。这个方法专门给 Spark 适配器提供了一个原地改造算子树的勾子。
    // 在真正进入物理图编译前，Spark 可以利用它将复杂的嵌套类型、特定行类型“拉平（Flatten）”或者按照 Spark 的口味进行重构，确保对接时的类型安全。
    RelNode flattenTypes(RelOptPlanner planner, RelNode rootRel,
        boolean restructure);
    // 入参：RuleSetBuilder builder（用于添加或删除规则的规则集构建器）。
    // 向 Calcite 的优化器中动态注入 Spark 专属的优化规则（Optimization Rules）。
    // Calcite 的核心能力来自于基于规则的优化（RBO/CBO）。
    // Spark 想要接管执行，就必须把“怎么把 Calcite 逻辑算子转成 Spark 算子”的秘密告诉 Calcite。
    // 通过这个方法，Spark 适配器会向 builder 注入一系列物理转换规则（例如 SparkProjectRule、SparkFilterRule）。
    // 这样，Calcite 的 Planner 在运行时就能把普通的 LogicalFilter 物理替换成由 Spark 驱动的过滤算子。
    void registerRules(RuleSetBuilder builder);
    // 运行时探针，检测当前环境是否真的激活了 Spark 执行引擎。
    // 如果类路径（ClassPath）里根本没有引入 calcite-spark 适配器，或者用户在 JDBC 连接串、环境变量中明确关闭了 Spark 支持，该方法会返回 false。
    // 此时，上游的 Dummy.getSparkHandler 就会知道该切换回本地单机执行的 TrivialSparkHandler 了。
    boolean enabled();
    // ClassDeclaration expr：Linq4j 框架生成的 Java 类声明语法树（包含了需要执行的计算逻辑）。
    // String s：辅助描述生成的 Java 源码字符串或类名标识。
    // 返回值：ArrayBindable（Calcite 运行时可直接调用 .bind() 的可执行句柄，吐出的是数组形式的行数据）。
    // 将 Linq4j 语法树字节码化，并编译生成 Spark RDD/Dataset 任务。
    // 这是全接口最核心的物理落地方法。Calcite 默认的代码生成机制（Code Generation）是生成一个在当前单机 JVM 内存里跑的迭代器。但通过这个方法，Spark 接管了编译期。它会把这段 Class 语法树接过去，利用 Spark 内部的 Runtime 编译器进行处理，将其包装成可以分发到分布式集群各个 Executor 上去分布式跑的算子代码，并最终返回一个标准的、被外层统一认领的 ArrayBindable 执行句柄。
    ArrayBindable compile(ClassDeclaration expr, String s);
    // Object（为了避免直接依赖 Spark 核心包，这里采用了泛化的 Object 返回，在物理实现中它就是 JavaSparkContext 或 SparkSession）
    Object sparkContext();

    /** Allows Spark to declare the rules it needs. */
    interface RuleSetBuilder {
      void addRule(RelOptRule rule);
      void removeRule(RelOptRule rule);
    }
  }

  /** Namespace that allows us to define non-abstract methods inside an
   * interface. */
  // 核心作用是在 Java 8 时代打破“接口内不能定义具体成员变量和非抽象方法”的语法限制。
  // Calcite 开发者将所有与 CalcitePrepare 紧密相关的、非公开的、带有状态控制的静态工具方法全部打包压缩到了这个 Dummy 类中。
  // 内部主要承包了两大核心系统：Spark 引擎的反射加载与优雅降级系统，以及基于 ThreadLocal 栈的视图循环引用防御系统。
  class Dummy {
    // 单例缓存 Spark 处理器实例。
    // 全局的静态缓存指针。无论上游 JDBC 提请多少次编译准备，真实的 SparkHandler 只会被初始化一次（无论加载成功还是退化为替身），避免高频重复创建对象或高频触发反射带来的 CPU 损耗。
    private static @Nullable SparkHandler sparkHandler;

    private Dummy() {}

    /** Returns a spark handler. Returns a trivial handler, for which
     * {@link SparkHandler#enabled()} returns {@code false}, if {@code enable}
     * is {@code false} or if Spark is not on the class path. Never returns
     * null. */
    // boolean enable（外部调用方或配置大纲中是否显式启用了 Spark 支持）
    // 返回值：SparkHandler（永远不会返回 null）。
    // 获取 SparkHandler 实例的同步线程安全工厂入口。
    public static synchronized SparkHandler getSparkHandler(boolean enable) {
      if (sparkHandler == null) {
        sparkHandler = enable ? createHandler() : new TrivialSparkHandler();
      }
      return sparkHandler;
    }
    // 通过全限定名动态反射加载真正的 Spark 执行官。
    // Calcite 作为一个独立的通用优化器，不能强依赖 calcite-spark 组件（否则不玩大数据的人也必须引入巨大的 Spark 依赖）。因此它在这里使用了经典的反射探测技术：
    private static SparkHandler createHandler() {
      try {
        final Class<?> clazz =
            Class.forName("org.apache.calcite.adapter.spark.SparkHandlerImpl");
        Method method = clazz.getMethod("instance");
        return (CalcitePrepare.SparkHandler) requireNonNull(
            method.invoke(null),
            () -> "non-null SparkHandler expected from " + method);
      } catch (ClassNotFoundException e) {
        return new TrivialSparkHandler();
      } catch (IllegalAccessException
          | ClassCastException
          | InvocationTargetException
          | NoSuchMethodException e) {
        throw new RuntimeException(e);
      }
    }
    // Context context（当前正准备编译的语句上下文）。
    // 将当前上下文压入追踪栈，并在压栈前死死掐断可能引发 OOM 死循环的“视图循环引用”。
    public static void push(Context context) {
      final Deque<Context> stack = castNonNull(THREAD_CONTEXT_STACK.get());
      final List<String> path = context.getObjectPath();
      if (path != null) {
        for (Context context1 : stack) {
          final List<String> path1 = context1.getObjectPath();
          if (path.equals(path1)) {
            throw new CyclicDefinitionException(stack.size(), path);
          }
        }
      }
      stack.push(context);
    }
    // 安全地窥探并返回当前线程本地栈最顶层的、正在活跃工作的那个 Context 环境。
    public static Context peek() {
      return castNonNull(castNonNull(THREAD_CONTEXT_STACK.get()).peek());
    }
    // Context context（预期应该被弹出的上下文）。
    // 当某个语句（或嵌套视图）编译彻底完成时，将其从栈顶清退弹出。
    public static void pop(Context context) {
      Context x = castNonNull(THREAD_CONTEXT_STACK.get()).pop();
      assert x == context;
    }

    /** Implementation of {@link SparkHandler} that either does nothing or
     * throws for each method. Use this if Spark is not installed. */
    // Spark 引擎缺失时的“平庸无作为”安全替身（Null Object Pattern 模式的应用）。
    private static class TrivialSparkHandler implements SparkHandler {
      @Override public RelNode flattenTypes(RelOptPlanner planner, RelNode rootRel,
          boolean restructure) {
        return rootRel;
      }

      @Override public void registerRules(RuleSetBuilder builder) {
      }

      @Override public boolean enabled() {
        return false;
      }

      @Override public ArrayBindable compile(ClassDeclaration expr, String s) {
        throw new UnsupportedOperationException();
      }

      @Override public Object sparkContext() {
        throw new UnsupportedOperationException();
      }
    }

  }

  /** The result of parsing and validating a SQL query. */
  // 核心作用是封装 SQL 语句经过“词法分析、语法分析（Parser）”以及“语义校验（Validator）”之后的沉淀结果。
  // 它是编译流水线前半程的终点站，为后续将其转换为关系代数（RelNode）或直接执行 DDL 提供了完备的元数据和语法树依据。
  class ParseResult {
    // 反向持有触发本次编译的准备服务实现类引用。
    public final CalcitePrepareImpl prepare;
    // 保存输入的原始 SQL 文本（主要用于 Debug 和日志审计）。
    public final String sql; // for debug
    // 保存核心战果——通过校验（Validate）后的抽象语法树（AST）根节点。
    // 这是该类最重要的资产。不管是 SELECT、INSERT 还是 CREATE TABLE，它们在这一阶段都被抽象成了 SqlNode 的具体子类（如 SqlSelect、SqlCreateTable）
    public final SqlNode sqlNode;
    // 保存当前 SQL 语句执行成功后，吐出的结果集的物理行类型（Schema 结构）。
    public final RelDataType rowType;
    // 保存当前编译器使用的类型工厂实体。
    public final RelDataTypeFactory typeFactory;

    public ParseResult(CalcitePrepareImpl prepare, SqlValidator validator,
        String sql,
        SqlNode sqlNode, RelDataType rowType) {
      super();
      this.prepare = prepare;
      this.sql = sql;
      this.sqlNode = sqlNode;
      this.rowType = rowType;
      this.typeFactory = validator.getTypeFactory();
    }

    /** Returns the kind of statement.
     *
     * <p>Possibilities include:
     *
     * <ul>
     *   <li>Queries: usually {@link SqlKind#SELECT}, but
     *   other query operators such as {@link SqlKind#UNION} and
     *   {@link SqlKind#ORDER_BY} are possible
     *   <li>DML statements: {@link SqlKind#INSERT}, {@link SqlKind#UPDATE} etc.
     *   <li>Session control statements: {@link SqlKind#COMMIT}
     *   <li>DDL statements: {@link SqlKind#CREATE_TABLE},
     *   {@link SqlKind#DROP_INDEX}
     * </ul>
     *
     * @return Kind of statement, never null
     */
    public SqlKind kind() {
      return sqlNode.getKind();
    } //获取SqlNode的kind
  }

  /** The result of parsing and validating a SQL query and converting it to
   * relational algebra. */
  // 核心作用是封装 SQL 语句不仅完成了解析、校验，而且成功跨越到“关系代数转换（Sql-to-Rel Conversion）”阶段后的完整战果。
  // 它是 Calcite 编译流水线中“逻辑计划生成”阶段的终点站，为后续送入 RelOptPlanner（优化器）进行 CBO/RBO 优化做好了全盘准备。
  class ConvertResult extends ParseResult {
    // 保存将 AST（语法树）成功脱壳、转换后生成的“逻辑计划算子树”的顶层根节点。
    // 为什么是 RelRoot 而不是直接一个 RelNode？因为 RelRoot 不仅包含了根部的 RelNode，
    // 还额外帮上游锁定了该查询的投影映射关系（Fields Map）、全局排序规则（Collation）以及各种暗示（Hints）。
    // 这确保了在复杂的 RBO/CBO 优化规则重组整棵树时，顶层的输出语义（比如列的物理顺序）绝对不会发生错乱或颠倒。
    public final RelRoot root;

    public ConvertResult(CalcitePrepareImpl prepare, SqlValidator validator,
        String sql, SqlNode sqlNode, RelDataType rowType, RelRoot root) {
      super(prepare, validator, sql, sqlNode, rowType);
      this.root = root;
    }
  }

  /** The result of analyzing a view. */
  // 核心作用是封装对一个数据库“视图（View）”进行深度拓扑剖析后的全部底层内幕战果。
    // 在关系型数据库中，视图本质上只是一个“存储的查询句柄（Stored Query）”。当外界尝试对一个视图进行查询、或者激进地对视图进行 INSERT/UPDATE（可更新视图，Updatable View）时，Calcite 不能简单地把它当作普通 SQL 处理，必须像剥洋葱一样把视图“拆壳”，探查它底层究竟映射到了哪张真实的物理表，以及带有怎样的行级、列级约束。AnalyzeViewResult 就是用来完美容纳这些拆壳元数据的。
  class AnalyzeViewResult extends ConvertResult {
    /** Not null if and only if the view is modifiable. */
    // 保存该视图底层所映射的、真实的物理目标表实体。
    // 当且仅当该视图是“可修改/可更新的（Modifiable）”时，该属性才不为 null。 如果一个视图是由单表查询组成的（如 SELECT id, name FROM users WHERE age > 18），那么它就是可更新的，此属性就会指向 users 表的元数据对象；
    // 如果视图是由复杂的 JOIN 或 GROUP BY 聚合而成的，它通常不可直接更新，此属性则为 null。
    public final @Nullable Table table;
    // 保存上述物理目标表在元数据 Schema 目录树中的完整命名空间路径。
    // 例如物理表在源中的全路径是 catalog.schema.users，则该列表在内存中严格按顺序存储为 ["catalog", "schema", "users"]。
    // 这为上游执行器在后续重写 SQL 或下发 DML 时，精确定位物理表提供了坐标。
    public final @Nullable ImmutableList<String> tablePath;
    // 保存该视图隐含的、行级物理过滤约束条件（断言谓词）。
    // 如果视图定义为 SELECT * FROM users WHERE status = 'ACTIVE'，当你尝试通过这个视图去 INSERT 一条新数据时，Calcite 的安全审计机制需要确保插入的数据必须满足 status = 'ACTIVE' 这一内在约束。
    // 这个属性就是用行表达式（RexNode）把视图原生的 WHERE 条件扣出来单独锁存，供后续安全框架进行 WITH CHECK OPTION 的合规性校验。
    public final @Nullable RexNode constraint;
    // 保存视图暴露出来的列与底层真实物理表列之间的“物理位置投影映射图谱”。
    // 假设底层物理表有 3 列：0:id, 1:age, 2:name。视图的定义是 SELECT name, id FROM table。
    //那么视图暴露出来的列顺序是 [name, id]。此时 columnMapping 记录的整数阵列就是 [2, 0]。通过这个整数指针数组，Calcite 可以在转换物理执行计划时，瞬间把对视图列的操作精准重定向到物理表的对应列拷贝上。
    public final @Nullable ImmutableIntList columnMapping;
    // 一条指令宣称该视图在语义层面上是否允许被修改、更新、或插入（Is Updatable/Modifiable）。
    public final boolean modifiable;

    public AnalyzeViewResult(CalcitePrepareImpl prepare,
        SqlValidator validator, String sql, SqlNode sqlNode,
        RelDataType rowType, RelRoot root, @Nullable Table table,
        @Nullable ImmutableList<String> tablePath, @Nullable RexNode constraint,
        @Nullable ImmutableIntList columnMapping, boolean modifiable) {
      super(prepare, validator, sql, sqlNode, rowType, root);
      this.table = table;
      this.tablePath = tablePath;
      this.constraint = constraint;
      this.columnMapping = columnMapping;
      this.modifiable = modifiable;
      checkArgument(modifiable == (table != null));
    }
  }

  /** The result of preparing a query. It gives the Avatica driver framework
   * the information it needs to create a prepared statement, or to execute a
   * statement directly, without an explicit prepare step.
   *
   * @param <T> element type */
  // 核心作用是将 Calcite 内部复杂的编译器成果，包装成 JDBC 驱动框架（Avatica）能够直接理解并消费的“标准合约/护照”
  // 无论上游是通过普通的 Statement 直接执行 SQL，还是通过 PreparedStatement 预编译执行，Calcite 最终都会把“动态编译出来的可执行代码”和“结果集元数据”打包进 CalciteSignature 中交付给驱动层。驱动层拿到它后，就能无缝创建 JDBC 的 ResultSet 并拉取数据。
  class CalciteSignature<T> extends Meta.Signature {
    // 保存 Calcite 编译器内部使用的、强类型系统的行数据类型。
    // 虽然父类有 List<ColumnMetaData> columns 用于给 JDBC 提供面向用户的列信息，但 Calcite 内部物理层依然需要 rowType 来做严格的类型对照。由于它包含了复杂的内部类和闭包引用，无法（也没必要）跨网络序列化，因此加上了 @JsonIgnore。
    @JsonIgnore public final @Nullable RelDataType rowType;
    // 保存执行该查询时的根元数据命名空间（元数据树）。
    // 数据真正的拉取和计算可能依赖动态的 Schema 变更（如临时表、UDF 自定义函数注册）。保留这个引用，使得在最终数据消费阶段，执行引擎仍能回溯和查询所需的元数据上下文。
    @JsonIgnore public final @Nullable CalciteSchema rootSchema;
    // 记录该查询生成的数据流在物理层面上具备什么样的“排序特征（Collation/Trait）”。
    // 如果 SQL 语句带有 ORDER BY user_id DESC，经过物理转换后，该属性就会记录“数据流已按第一个字段降序排列”。上游的 Avatica 驱动可以利用这个物理 trait 进行高级流控或流式归并。
    @JsonIgnore private final List<RelCollation> collationList;
    // 保存当前查询允许返回的最大行数硬限制（Limit 阈值）。
    // 在原生 JDBC 规范中，限制行数设为 0 代表“无限制（No Limit）”。但在 Calcite 的底层物理世界中，-1 才代表“无限制”，而 0 继承了合法的数学语义（即只返回 0 行，通常用于探针或纯解析校验）。这个属性锁定了该限制，用于在数据拉取时实施精准裁剪。
    private final long maxRowCount;
    // 执行计划落地后的“终极代码执行句柄”（灵魂属性）。
    // 全类最核心的资产。Calcite 的动态代码生成（Code Generation）最终会吐出一段 Java 字节码，这段字节码编译出来的实例就被包装在这个 Bindable 中。
    // 它就像是一个“水泵”，一旦上游传入运行时参数并调用它的 bind 方法，这台水泵就会启动，疯狂地从各个数据源抽水（数据迭代）并吐给客户端。
    private final @Nullable Bindable<T> bindable;

    @Deprecated // to be removed before 2.0
    public CalciteSignature(String sql, List<AvaticaParameter> parameterList,
        Map<String, Object> internalParameters, RelDataType rowType,
        List<ColumnMetaData> columns, Meta.CursorFactory cursorFactory,
        CalciteSchema rootSchema, List<RelCollation> collationList,
        long maxRowCount, Bindable<T> bindable) {
      this(sql, parameterList, internalParameters, rowType, columns,
          cursorFactory, rootSchema, collationList, maxRowCount, bindable,
          castNonNull(null));
    }

    public CalciteSignature(@Nullable String sql,
        List<AvaticaParameter> parameterList,
        Map<String, Object> internalParameters,
        @Nullable RelDataType rowType,
        List<ColumnMetaData> columns,
        Meta.CursorFactory cursorFactory,
        @Nullable CalciteSchema rootSchema,
        List<RelCollation> collationList,
        long maxRowCount,
        @Nullable Bindable<T> bindable,
        Meta.StatementType statementType) {
      super(columns, sql, parameterList, internalParameters, cursorFactory,
          statementType);
      this.rowType = rowType;
      this.rootSchema = rootSchema;
      this.collationList = collationList;
      this.maxRowCount = maxRowCount;
      this.bindable = bindable;
    }

    public Enumerable<T> enumerable(DataContext dataContext) {
      Enumerable<T> enumerable = castNonNull(bindable).bind(dataContext);
      if (maxRowCount >= 0) {
        // Apply limit. In JDBC 0 means "no limit". But for us, -1 means
        // "no limit", and 0 is a valid limit.
        enumerable = EnumerableDefaults.take(enumerable, maxRowCount);
      }
      return enumerable;
    }

    public List<RelCollation> getCollationList() {
      return collationList;
    }
  }

  /** A union type of the three possible ways of expressing a query: as a SQL
   * string, a {@link Queryable} or a {@link RelNode}. Exactly one must be
   * provided.
   * @param <T> element type */
  // 核心作用是将外部世界向 Calcite 发起查询时的三种异构输入长相，进行无缝的“大一统”标准化归纳。
  class Query<T> {
    public final @Nullable String sql;
    public final @Nullable Queryable<T> queryable;
    public final @Nullable RelNode rel;

    private Query(@Nullable String sql, @Nullable Queryable<T> queryable, @Nullable RelNode rel) {
      this.sql = sql;
      this.queryable = queryable;
      this.rel = rel;

      assert (sql == null ? 0 : 1)
          + (queryable == null ? 0 : 1)
          + (rel == null ? 0 : 1) == 1;
    }

    public static <T> Query<T> of(String sql) {
      return new Query<>(sql, null, null);
    }

    public static <T> Query<T> of(Queryable<T> queryable) {
      return new Query<>(null, queryable, null);
    }

    public static <T> Query<T> of(RelNode rel) {
      return new Query<>(null, null, rel);
    }
  }
}
