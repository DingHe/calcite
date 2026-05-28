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
import org.apache.calcite.DataContexts;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.AvaticaFactory;
import org.apache.calcite.avatica.AvaticaSite;
import org.apache.calcite.avatica.AvaticaStatement;
import org.apache.calcite.avatica.Helper;
import org.apache.calcite.avatica.InternalProperty;
import org.apache.calcite.avatica.Meta;
import org.apache.calcite.avatica.MetaImpl;
import org.apache.calcite.avatica.NoSuchStatementException;
import org.apache.calcite.avatica.UnregisteredDriver;
import org.apache.calcite.avatica.remote.TypedValue;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.jdbc.CalcitePrepare.Context;
import org.apache.calcite.linq4j.BaseQueryable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.materialize.Lattice;
import org.apache.calcite.materialize.MaterializationService;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.type.DelegatingTypeSystem;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rel.type.TimeFrameSet;
import org.apache.calcite.rel.type.TimeFrames;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.SchemaVersion;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.LongSchemaVersion;
import org.apache.calcite.server.CalciteServer;
import org.apache.calcite.server.CalciteServerStatement;
import org.apache.calcite.sql.advise.SqlAdvisor;
import org.apache.calcite.sql.advise.SqlAdvisorValidator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorWithHints;
import org.apache.calcite.tools.RelRunner;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.Holder;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

import static java.util.Objects.requireNonNull;

/**
 * Implementation of JDBC connection
 * in the Calcite engine.
 *
 * <p>Abstract to allow newer versions of JDBC to add methods.
 */
abstract class CalciteConnectionImpl
    extends AvaticaConnection
    implements CalciteConnection, QueryProvider {
  public final JavaTypeFactory typeFactory;

  final CalciteSchema rootSchema; //CalciteSchema内部封装了用户定义的schema。
  final Supplier<CalcitePrepare> prepareFactory;
  final CalciteServer server = new CalciteServerImpl(); //代表连接之间的共享状态

  // must be package-protected
  static final Trojan TROJAN = createTrojan(); //通过Trojan访问此类的内部状态

  /**
   * Creates a CalciteConnectionImpl.
   *
   * <p>Not public; method is called only from the driver.
   *
   * @param driver Driver
   * @param factory Factory for JDBC objects
   * @param url Server URL
   * @param info Other connection properties
   * @param rootSchema Root schema, or null
   * @param typeFactory Type factory, or null
   */
  protected CalciteConnectionImpl(Driver driver, AvaticaFactory factory,
      String url, Properties info, @Nullable CalciteSchema rootSchema,
      @Nullable JavaTypeFactory typeFactory) {
    super(driver, factory, url, info);
    CalciteConnectionConfig cfg = new CalciteConnectionConfigImpl(info);
    this.prepareFactory = driver::createPrepare;
    if (typeFactory != null) {
      this.typeFactory = typeFactory;
    } else {
      RelDataTypeSystem typeSystem =
          cfg.typeSystem(RelDataTypeSystem.class, RelDataTypeSystem.DEFAULT);
      if (cfg.conformance().shouldConvertRaggedUnionTypesToVarying()) {
        typeSystem =
            new DelegatingTypeSystem(typeSystem) {
              @Override public boolean
              shouldConvertRaggedUnionTypesToVarying() {
                return true;
              }
            };
      }
      this.typeFactory = new JavaTypeFactoryImpl(typeSystem);
    }
    this.rootSchema =
        requireNonNull(rootSchema != null
            ? rootSchema
            : CalciteSchema.createRootSchema(true));
    checkArgument(this.rootSchema.isRoot(), "must be root schema");
    this.properties.put(InternalProperty.CASE_SENSITIVE, cfg.caseSensitive());
    this.properties.put(InternalProperty.UNQUOTED_CASING, cfg.unquotedCasing());
    this.properties.put(InternalProperty.QUOTED_CASING, cfg.quotedCasing());
    this.properties.put(InternalProperty.QUOTING, cfg.quoting());
  }
  //默认使用CalciteMetaImpl
  CalciteMetaImpl meta() {
    return (CalciteMetaImpl) meta;
  }

  @Override public CalciteConnectionConfig config() {
    return new CalciteConnectionConfigImpl(info);
  }

  @Override public Context createPrepareContext() {
    return new ContextImpl(this);
  }

  /** Called after the constructor has completed and the model has been
   * loaded. */
  void init() {
    final MaterializationService service = MaterializationService.instance();
    for (CalciteSchema.LatticeEntry e : Schemas.getLatticeEntries(rootSchema)) {
      final Lattice lattice = e.getLattice();
      for (Lattice.Tile tile : lattice.computeTiles()) {
        service.defineTile(lattice, tile.bitSet(), tile.measures, e.schema,
            true, true);
      }
    }
  }

  @Override public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface == RelRunner.class) {
      return iface.cast((RelRunner) rel ->
          prepareStatement_(CalcitePrepare.Query.of(rel),
              ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
              getHoldability()));
    }
    return super.unwrap(iface);
  }
  //通过AvaticaFactory创建语句
  @Override public CalciteStatement createStatement(int resultSetType,
      int resultSetConcurrency, int resultSetHoldability) throws SQLException {
    return (CalciteStatement) super.createStatement(resultSetType,
        resultSetConcurrency, resultSetHoldability);
  }

  @Override public CalcitePreparedStatement prepareStatement(
      String sql,
      int resultSetType,
      int resultSetConcurrency,
      int resultSetHoldability) throws SQLException {
    final CalcitePrepare.Query<Object> query = CalcitePrepare.Query.of(sql);
    return prepareStatement_(query, resultSetType, resultSetConcurrency,
        resultSetHoldability);
  }

  private CalcitePreparedStatement prepareStatement_(
      CalcitePrepare.Query<?> query,
      int resultSetType,
      int resultSetConcurrency,
      int resultSetHoldability) throws SQLException {
    try {
      final Meta.Signature signature =
          parseQuery(query, createPrepareContext(), -1);
      final CalcitePreparedStatement calcitePreparedStatement =
          (CalcitePreparedStatement) factory.newPreparedStatement(this, null,
              signature, resultSetType, resultSetConcurrency, resultSetHoldability);
      server.getStatement(calcitePreparedStatement.handle).setSignature(signature);
      return calcitePreparedStatement;
    } catch (Exception e) {
      String message = query.rel == null
          ? "Error while preparing statement [" + query.sql + "]"
          : "Error while preparing plan [" + RelOptUtil.toString(query.rel) + "]";
      throw Helper.INSTANCE.createException(message, e);
    }
  }
   //通过CalcitePrepare解析sql语句，实际执行的地方
  <T> CalcitePrepare.CalciteSignature<T> parseQuery(
      CalcitePrepare.Query<T> query,
      CalcitePrepare.Context prepareContext, long maxRowCount) {
    CalcitePrepare.Dummy.push(prepareContext);
    try {
      final CalcitePrepare prepare = prepareFactory.get();
      return prepare.prepareSql(prepareContext, query, Object[].class,
          maxRowCount);
    } finally {
      CalcitePrepare.Dummy.pop(prepareContext);
    }
  }
 //server会缓存Statement，所以可以直接从server获取状态
  @Override public AtomicBoolean getCancelFlag(Meta.StatementHandle handle)
      throws NoSuchStatementException {
    final CalciteServerStatement serverStatement = server.getStatement(handle);
    return ((CalciteServerStatementImpl) serverStatement).cancelFlag;
  }

  // CalciteConnection methods
  //通过CalciteSchema获取用户定义的Schema
  @Override public SchemaPlus getRootSchema() {
    return rootSchema.plus();
  }

  @Override public JavaTypeFactory getTypeFactory() {
    return typeFactory;
  }

  @Override public Properties getProperties() {
    return info;
  }

  // QueryProvider methods
  //其实CalciteConnectionImpl也是一种QueryProvider
  @Override public <T> Queryable<T> createQuery(
      Expression expression, Class<T> rowType) {
    return new CalciteQueryable<>(this, rowType, expression);
  }

  @Override public <T> Queryable<T> createQuery(Expression expression, Type rowType) {
    return new CalciteQueryable<>(this, rowType, expression);
  }

  @Override public <T> T execute(Expression expression, Type type) {
    return castNonNull(null); // TODO:
  }

  @Override public <T> T execute(Expression expression, Class<T> type) {
    return castNonNull(null); // TODO:
  }
  //通过CalcitePrepare的接口来解析语句，最后返回迭代对象
  @Override public <T> Enumerator<T> executeQuery(Queryable<T> queryable) {
    try {
      CalciteStatement statement = (CalciteStatement) createStatement();
      CalcitePrepare.CalciteSignature<T> signature =
          statement.prepare(queryable);
      return enumerable(statement.handle, signature, null).enumerator();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public <T> Enumerable<T> enumerable(Meta.StatementHandle handle,
      CalcitePrepare.CalciteSignature<T> signature,
      @Nullable List<TypedValue> parameterValues0) throws SQLException {
    Map<String, Object> map = new LinkedHashMap<>();
    AvaticaStatement statement = lookupStatement(handle);
    final List<TypedValue> parameterValues;
    if (parameterValues0 == null || parameterValues0.isEmpty()) {
      parameterValues = TROJAN.getParameterValues(statement);
    } else {
      parameterValues = parameterValues0;
    }

    if (MetaImpl.checkParameterValueHasNull(parameterValues)) {
      throw new SQLException("exception while executing query: unbound parameter");
    }

    Ord.forEach(parameterValues,
        (e, i) -> map.put("?" + i, e.toLocal()));
    map.putAll(signature.internalParameters);
    final AtomicBoolean cancelFlag;
    try {
      cancelFlag = getCancelFlag(handle);
    } catch (NoSuchStatementException e) {
      throw new RuntimeException(e);
    }
    map.put(DataContext.Variable.CANCEL_FLAG.camelName, cancelFlag);
    int queryTimeout = statement.getQueryTimeout();
    // Avoid overflow
    if (queryTimeout > 0 && queryTimeout < Integer.MAX_VALUE / 1000) {
      map.put(DataContext.Variable.TIMEOUT.camelName, queryTimeout * 1000L);
    }
    final DataContext dataContext = createDataContext(map, signature.rootSchema);
    return signature.enumerable(dataContext);
  }

  public DataContext createDataContext(Map<String, Object> parameterValues,
      @Nullable CalciteSchema rootSchema) {
    if (config().spark()) {
      return DataContexts.EMPTY;
    }
    return new DataContextImpl(this, parameterValues, rootSchema);
  }

  // do not make public
  UnregisteredDriver getDriver() {
    return driver;
  }

  // do not make public
  AvaticaFactory getFactory() {
    return factory;
  }

  /** Implementation of Queryable.
   *
   * @param <T> element type */
  static class CalciteQueryable<T> extends BaseQueryable<T> {
    CalciteQueryable(CalciteConnection connection, Type elementType,
        Expression expression) {
      super(connection, elementType, expression);
    }

    public CalciteConnection getConnection() {
      return (CalciteConnection) provider;
    }
  }

  /** Implementation of Server. 代表连接之间的共享状态*/
  private static class CalciteServerImpl implements CalciteServer {
    final Map<Integer, CalciteServerStatement> statementMap = new HashMap<>();

    @Override public void removeStatement(Meta.StatementHandle h) {
      statementMap.remove(h.id);
    }

    @Override public void addStatement(CalciteConnection connection,
        Meta.StatementHandle h) {
      final CalciteConnectionImpl c = (CalciteConnectionImpl) connection;
      final CalciteServerStatement previous =
          statementMap.put(h.id, new CalciteServerStatementImpl(c));
      if (previous != null) {
        throw new AssertionError();
      }
    }

    @Override public CalciteServerStatement getStatement(Meta.StatementHandle h)
        throws NoSuchStatementException {
      CalciteServerStatement statement = statementMap.get(h.id);
      if (statement == null) {
        throw new NoSuchStatementException(h);
      }
      return statement;
    }
  }

  /** Schema that has no parents. */
  static class RootSchema extends AbstractSchema {
    RootSchema() {
      super();
    }

    @Override public Expression getExpression(@Nullable SchemaPlus parentSchema,
        String name) {
      return Expressions.call(
          DataContext.ROOT,
          BuiltInMethod.DATA_CONTEXT_GET_ROOT_SCHEMA.method);
    }
  }

  /** Implementation of DataContext. */
  // Calcite 在物理执行期（Runtime）的真实数据容器
  // DataContextImpl 的核心使命就是在 SQL 语句开始执行的那一刹那，把抓取到的时区、时间戳、用户信息、外界传入的动态参数（如 ? 占位符绑定的值）一次性强行锁死，并封装进一个线程安全的不可变 Map 中。这确保了整个 SQL 执行生命周期内参数的物理一致性。
  //
  static class DataContextImpl implements DataContext {
    // 核心后备参数查找池。
    // 采用 Google Guava 的 ImmutableMap（不可变映射表）。在构造函数中，它会被装满。所有关于时间、用户、时区、以及外部输入的动态绑定参数（Parameters）都会沉淀在这里。
    // 因为是不可变的，所以具备天然的线程安全特征，任凭多线程并发执行读取，都不会发生并发冲突。
    private final ImmutableMap<Object, Object> map;
    // 元数据命名空间句柄。
    // 它指向了系统的元数据总账本。物理算子（如物理表扫描算子）正是通过它才能顺藤摸瓜在内存中找到真实的物理表、视图以及存储管道。
    private final @Nullable CalciteSchema rootSchema;
    // 查询执行环境提供者句柄。
    // 传入的真实物理对象是 CalciteConnectionImpl（Calcite 的连接实现类），由于连接类实现了 Linq4j 的 QueryProvider 接口，它在这里直接扮演了运行时调度器和代码编译触发器的角色。
    private final QueryProvider queryProvider;
    // 行数据物理类型映射工厂。
    // 同样在初始化时从 Connection 中无缝提取，专职负责在执行期对数据列的 Java 强类型、序列化格式进行保驾护航。
    private final JavaTypeFactory typeFactory;

    DataContextImpl(CalciteConnectionImpl connection,
        Map<String, Object> parameters, @Nullable CalciteSchema rootSchema) {
      // 将传入的 Calcite 连接实例（CalciteConnectionImpl）向上转型并赋值给 queryProvider。因为连接持有底层的会话状态，所以它将作为运行期调度代码执行的代理人。
      this.queryProvider = connection;
      this.typeFactory = connection.getTypeFactory();
      this.rootSchema = rootSchema;

      // Store the time at which the query started executing. The SQL
      // standard says that functions such as CURRENT_TIMESTAMP return the
      // same value throughout the query.
      // 标准规定：在一个查询事务内，无论执行耗时多久，调用多少次时间函数（如 CURRENT_TIMESTAMP, NOW()），返回的物理时间必须是完全相同的一堵墙。
      final Holder<Long> timeHolder = Holder.of(System.currentTimeMillis());

      // Give a hook chance to alter the clock.
      // Calcite 框架高级架构师预留给开发者的运行时切面后门（Hook）
      // 在真实的分布式大数据测试中，测试 CURRENT_TIMESTAMP 这种动态时间函数是非常痛苦的（因为每次断言时间都不一样）。
      // 通过这段代码，你可以在测试用例运行前，向 Hook.CURRENT_TIME 注册一个回调函数，强行把 timeHolder 里的值改为一个固定的历史时间（比如 1716883200000L）。
      // 这样，timeHolder.get() 拿到的就是被你篡改后的“确定性时间”，让自动化集成测试变得稳定、可预测。
      Hook.CURRENT_TIME.run(timeHolder);
      final long time = timeHolder.get();
      // 从当前的 JDBC 会话配置中提取用户设置的时区（如 GMT+8 或 America/New_York）。
      final TimeZone timeZone = connection.getTimeZone();
      final TimeFrameSet timeFrameSet =
          connection.typeFactory.getTypeSystem()
              .deriveTimeFrameSet(TimeFrames.CORE);
      // 调用 Java 原生 TimeZone.getOffset(time)。
      // 它传入刚刚冻结的绝对时间戳 time，自动计算出当前时区在这一刻相对于 UTC 零时区的毫秒级时间差（Offset
      final long localOffset = timeZone.getOffset(time);
      final long currentOffset = localOffset;
      // 捕获环境元数据：用户与多语言区域
      final String user = "sa";
      final String systemUser = System.getProperty("user.name");
      final String localeName = connection.config().locale();
      final Locale locale = localeName != null
          ? Util.parseLocale(localeName) : Locale.ROOT;

      // Give a hook chance to alter standard input, output, error streams.
      // 物理内幕：再次利用 Holder 打包了 JVM 原生的标准输入输出流。接着调用 Hook.STANDARD_STREAMS 开辟拦截窗口。
      // 工业妙用：在生产环境中，我们绝不希望某个物理算子的报错或者控制台打印直接乱入到主 JVM 的 System.err 中导致日志炸裂。大数据引擎开发者可以通过这个 Hook，将 streamHolder 里的输出流动态替换为基于 Log4j/Logback 封装的自定义管道流（PipedOutputStream），从而实现长查询运行时日志的定向收集。
      final Holder<Object[]> streamHolder =
          Holder.of(new Object[] {System.in, System.out, System.err});
      Hook.STANDARD_STREAMS.run(streamHolder);
      // 注入不可变 Map 容器与“防空指针”大战
      ImmutableMap.Builder<Object, Object> builder = ImmutableMap.builder();
      builder.put(Variable.UTC_TIMESTAMP.camelName, time)
          .put(Variable.CURRENT_TIMESTAMP.camelName, time + currentOffset)
          .put(Variable.LOCAL_TIMESTAMP.camelName, time + localOffset)
          .put(Variable.TIME_ZONE.camelName, timeZone)
          .put(Variable.TIME_FRAME_SET.camelName, timeFrameSet)
          .put(Variable.USER.camelName, user)
          .put(Variable.SYSTEM_USER.camelName, systemUser)
          .put(Variable.LOCALE.camelName, locale)
          .put(Variable.STDIN.camelName, streamHolder.get()[0])
          .put(Variable.STDOUT.camelName, streamHolder.get()[1])
          .put(Variable.STDERR.camelName, streamHolder.get()[2]);
      // 这里的 parameters 里面装的是外界在连接执行时，通过 Prepared Statement 或者 API 灌进来的动态命名参数（如 ? 参数绑定的具体值）。
      for (Map.Entry<String, Object> entry : parameters.entrySet()) {
        Object e = entry.getValue();
        if (e == null) {
          // AvaticaSite.DUMMY_VALUE（最硬核的防御）
          // 解决：Calcite 采用了变通之道，一旦检测到参数值是 null，就将其偷梁换柱改写为一个专门的虚拟非空单例对象 AvaticaSite.DUMMY_VALUE。
          e = AvaticaSite.DUMMY_VALUE;
        }
        builder.put(entry.getKey(), e);
      }
      map = builder.build();
    }

    @Override public synchronized @Nullable Object get(String name) {
      Object o = map.get(name);
      if (o == AvaticaSite.DUMMY_VALUE) {
        return null;
      }
      if (o == null && Variable.SQL_ADVISOR.camelName.equals(name)) {
        return getSqlAdvisor();
      }
      return o;
    }
    // 要用于为 IDE、数据中台的 SQL 编辑器或者命令行终端提供 SQL 语句的自动补全（Auto-completion）、语法智能提示（Hints）以及错位纠正建议。
    // 这段代码的本质，就是在物理运行期现场临时拼装出一个“微型 SQL 编译器生态圈”。
    private SqlAdvisor getSqlAdvisor() {
      final CalciteConnectionImpl con = (CalciteConnectionImpl) queryProvider;
      final String schemaName;
      try {
        schemaName = con.getSchema();
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
      final List<String> schemaPath =
          schemaName == null
              ? ImmutableList.of()
              : ImmutableList.of(schemaName);
      final SqlValidatorWithHints validator =
          new SqlAdvisorValidator(SqlStdOperatorTable.instance(),
              new CalciteCatalogReader(requireNonNull(rootSchema, "rootSchema"),
                  schemaPath, typeFactory, con.config()),
              typeFactory, SqlValidator.Config.DEFAULT);
      final CalciteConnectionConfig config = con.config();
      // This duplicates org.apache.calcite.prepare.CalcitePrepareImpl.prepare2_
      final SqlParser.Config parserConfig = SqlParser.config()
          .withQuotedCasing(config.quotedCasing())
          .withUnquotedCasing(config.unquotedCasing())
          .withQuoting(config.quoting())
          .withConformance(config.conformance())
          .withCaseSensitive(config.caseSensitive());
      return new SqlAdvisor(validator, parserConfig);
    }

    @Override public @Nullable SchemaPlus getRootSchema() {
      return rootSchema == null ? null : rootSchema.plus();
    }

    @Override public JavaTypeFactory getTypeFactory() {
      return typeFactory;
    }

    @Override public QueryProvider getQueryProvider() {
      return queryProvider;
    }
  }

  /** Implementation of Context. 实现了CalcitePrepare中定义的接口Context，主要是通过CalciteConnectionImpl来获得各种属性*/
  static class ContextImpl implements CalcitePrepare.Context {
    private final CalciteConnectionImpl connection;
    private final CalciteSchema mutableRootSchema;
    private final CalciteSchema rootSchema;

    ContextImpl(CalciteConnectionImpl connection) {
      this.connection = requireNonNull(connection, "connection");
      long now = System.currentTimeMillis();
      SchemaVersion schemaVersion = new LongSchemaVersion(now);
      this.mutableRootSchema = connection.rootSchema;
      this.rootSchema = mutableRootSchema.createSnapshot(schemaVersion);
    }

    @Override public JavaTypeFactory getTypeFactory() {
      return connection.typeFactory;
    }

    @Override public CalciteSchema getRootSchema() {
      return rootSchema;
    }

    @Override public CalciteSchema getMutableRootSchema() {
      return mutableRootSchema;
    }

    @Override public List<String> getDefaultSchemaPath() {
      final String schemaName;
      try {
        schemaName = connection.getSchema();
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
      return schemaName == null
          ? ImmutableList.of()
          : ImmutableList.of(schemaName);
    }

    @Override public @Nullable List<String> getObjectPath() {
      return null;
    }

    @Override public CalciteConnectionConfig config() {
      return connection.config();
    }

    @Override public DataContext getDataContext() {
      return connection.createDataContext(ImmutableMap.of(),
          rootSchema);
    }

    @Override public RelRunner getRelRunner() {
      final RelRunner runner;
      try {
        runner = connection.unwrap(RelRunner.class);
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
      if (runner == null) {
        throw new UnsupportedOperationException();
      }
      return runner;
    }

    @Override public CalcitePrepare.SparkHandler spark() {
      final boolean enable = config().spark();
      return CalcitePrepare.Dummy.getSparkHandler(enable);
    }
  }

  /** Implementation of {@link CalciteServerStatement}. */
  static class CalciteServerStatementImpl
      implements CalciteServerStatement {
    private final CalciteConnectionImpl connection;
    private @Nullable Iterator<Object> iterator;
    private Meta.@Nullable Signature signature;
    private final AtomicBoolean cancelFlag = new AtomicBoolean();

    CalciteServerStatementImpl(CalciteConnectionImpl connection) {
      this.connection = requireNonNull(connection, "connection");
    }

    @Override public Context createPrepareContext() {
      return connection.createPrepareContext();
    }

    @Override public CalciteConnection getConnection() {
      return connection;
    }

    @Override public void setSignature(Meta.Signature signature) {
      this.signature = signature;
    }

    @Override public Meta.@Nullable Signature getSignature() {
      return signature;
    }

    @Override public @Nullable Iterator<Object> getResultSet() {
      return iterator;
    }

    @Override public void setResultSet(Iterator<Object> iterator) {
      this.iterator = iterator;
    }
  }

}
