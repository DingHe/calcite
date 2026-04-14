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
package org.apache.calcite.prepare;

import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.function.Hints;
import org.apache.calcite.model.ModelHandler;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.AggregateFunction;
import org.apache.calcite.schema.ScalarFunction;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.TableFunction;
import org.apache.calcite.schema.TableMacro;
import org.apache.calcite.schema.Wrapper;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandMetadata;
import org.apache.calcite.sql.type.SqlOperandTypeInference;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlOperatorTables;
import org.apache.calcite.sql.validate.SqlMoniker;
import org.apache.calcite.sql.validate.SqlMonikerImpl;
import org.apache.calcite.sql.validate.SqlMonikerType;
import org.apache.calcite.sql.validate.SqlNameMatcher;
import org.apache.calcite.sql.validate.SqlNameMatchers;
import org.apache.calcite.sql.validate.SqlUserDefinedAggFunction;
import org.apache.calcite.sql.validate.SqlUserDefinedFunction;
import org.apache.calcite.sql.validate.SqlUserDefinedTableFunction;
import org.apache.calcite.sql.validate.SqlUserDefinedTableMacro;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.util.Optionality;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.google.common.collect.ImmutableList.toImmutableList;

/**
 * Implementation of {@link org.apache.calcite.prepare.Prepare.CatalogReader}
 * and also {@link org.apache.calcite.sql.SqlOperatorTable} based on tables and
 * functions defined schemas.
 */
// CalciteCatalogReader 的本质是一个元数据访问适配器。
// 其核心作用包括：
// 名称解析（Name Resolution）：将 SQL 语句中的字符串（如表名、字段名、函数名）映射到内存中实际的 CalciteSchema 对象。
// 统一视图：它实现了 SqlValidatorCatalogReader（供校验器使用）和 SqlOperatorTable（供函数查找使用），确保校验和优化阶段看到的元数据是一致的。
// 路径搜索：支持类似操作系统 PATH 的机制，可以在默认 Schema 和根 Schema 之间按优先级搜索对象。
// 对象转换：将底层的 Schema 元素（如 Table、Function）转换为校验器和优化器需要的包装对象（如 PreparingTable、SqlOperator）。
public class CalciteCatalogReader implements Prepare.CatalogReader {
  // 整个元数据树的根节点。所有的表、子 Schema 和函数都挂载在这棵树下。
  protected final CalciteSchema rootSchema;
  // 类型工厂，用于在解析过程中创建和校验 SQL 数据类型（如 INT, VARCHAR）。
  protected final RelDataTypeFactory typeFactory;
  // 搜索路径列表。当用户在 SQL 中写一个不带前缀的表名时，程序会按照这个路径顺序去查找。
  private final List<List<String>> schemaPaths;
  // 名称匹配器。决定了查找表或列时是否大小写敏感。
  protected final SqlNameMatcher nameMatcher;
  // 连接配置信息，包含了大小写敏感性、符合性的 SQL 规范等设置。
  protected final CalciteConnectionConfig config;

  public CalciteCatalogReader(CalciteSchema rootSchema,
      List<String> defaultSchema, RelDataTypeFactory typeFactory, CalciteConnectionConfig config) {
    this(rootSchema, SqlNameMatchers.withCaseSensitive(config != null && config.caseSensitive()),
        ImmutableList.of(Objects.requireNonNull(defaultSchema, "defaultSchema"),
            ImmutableList.of()),
        typeFactory, config);
  }
  // 构造函数。初始化根架构、匹配器、搜索路径、类型工厂和配置。
  protected CalciteCatalogReader(CalciteSchema rootSchema,
      SqlNameMatcher nameMatcher, List<List<String>> schemaPaths,
      RelDataTypeFactory typeFactory, CalciteConnectionConfig config) {
    this.rootSchema = Objects.requireNonNull(rootSchema, "rootSchema");
    this.nameMatcher = nameMatcher;
    this.schemaPaths =
        Util.immutableCopy(Util.isDistinct(schemaPaths)
            ? schemaPaths
            : new LinkedHashSet<>(schemaPaths));
    this.typeFactory = typeFactory;
    this.config = config;
  }

  @Override public CalciteCatalogReader withSchemaPath(List<String> schemaPath) {
    return new CalciteCatalogReader(rootSchema, nameMatcher,
        ImmutableList.of(schemaPath, ImmutableList.of()), typeFactory, config);
  }
  // 负责将 SQL 解析得到的表名（字符串列表）转换为 PreparingTable 对象。
  @Override public Prepare.@Nullable PreparingTable getTable(final List<String> names) {
    // First look in the default schema, if any.
    // If not found, look in the root schema.
    // 根据 CalciteCatalogReader 中配置的 schemaPaths（搜索路径）进行查找。
    // 首先尝试在当前 Schema（Default Schema）中查找。
    // 如果找不到，再回到 Root Schema 进行全局查找。
    CalciteSchema.TableEntry entry = SqlValidatorUtil.getTableEntry(this, names);
    if (entry != null) {
      final Table table = entry.getTable();
      // 某些自定义的 Table 实现可能已经持有了 RelOptTable（优化器表）的引用。
      // 如果这个表实现了 Wrapper 接口，代码会尝试直接通过 unwrap 获取现成的 PreparingTable。
      if (table instanceof Wrapper) {
        final Prepare.PreparingTable relOptTable =
            ((Wrapper) table).unwrap(Prepare.PreparingTable.class);
        if (relOptTable != null) {
          return relOptTable;
        }
      }
      // 如果表不是 Wrapper 或者 unwrap 失败，则手动创建一个实现类。
      return RelOptTableImpl.create(this,
          table.getRowType(typeFactory), entry, null);
    }
    return null;
  }

  @Override public CalciteConnectionConfig getConfig() {
    return config;
  }
  // 任务是在复杂的 Schema 层级结构中，根据给定的名称（可能是简单的 abs，也可能是限定名 someschema.myfunc）寻找所有匹配的函数定义。
  // 之所以返回 Collection，是因为 SQL 支持函数重载（同一个名字有多个不同参数的实现）。
  private Collection<org.apache.calcite.schema.Function> getFunctionsFrom(
      List<String> names) {
    final List<org.apache.calcite.schema.Function> functions2 =
        new ArrayList<>();
    final List<List<String>> schemaNameList = new ArrayList<>();
    // 情况 A：带限定名的名称 (names.size() > 1)
    // 例如：SELECT my_schema.my_func(...)。
    if (names.size() > 1) {
      // Name qualified: ignore path. But we do look in "/catalog" and "/",
      // the last 2 items in the path.
      if (schemaPaths.size() > 1) {
        schemaNameList.addAll(Util.skip(schemaPaths));
      } else {
        schemaNameList.addAll(schemaPaths);
      }
    } else {
      // 情况 B：简单名称 (names.size() == 1)
      // 例如：SELECT abs(x)。
      for (List<String> schemaPath : schemaPaths) {
        CalciteSchema schema =
            SqlValidatorUtil.getSchema(rootSchema, schemaPath, nameMatcher);
        if (schema != null) {
          schemaNameList.addAll(schema.getPath());
        }
      }
    }
    // 执行实际查找
    for (List<String> schemaNames : schemaNameList) {
      // 拼接 Schema 路径和函数的前缀路径
      CalciteSchema schema =
          SqlValidatorUtil.getSchema(rootSchema,
              Iterables.concat(schemaNames, Util.skipLast(names)), nameMatcher);
      if (schema != null) {
        final String name = Util.last(names); // 获取函数名，如 "my_func"
        boolean caseSensitive = nameMatcher.isCaseSensitive();
        // 从该 Schema 中取出所有同名的函数实现（重载）
        functions2.addAll(schema.getFunctions(name, caseSensitive));
      }
    }
    return functions2;
  }
  // 在 Calcite 的元数据层中查找并返回用户定义类型（User-Defined Type, UDT）。
  // 在 SQL 中，除了 INT、VARCHAR 等内置类型外，用户可以创建自定义类型（例如 CREATE TYPE MyAddress AS (street VARCHAR, city VARCHAR)）。
  // 当 SQL 语句中引用这些类型时，校验器就会通过此方法获取其真实的结构定义。
  @Override public @Nullable RelDataType getNamedType(SqlIdentifier typeName) {
    CalciteSchema.TypeEntry typeEntry = SqlValidatorUtil.getTypeEntry(getRootSchema(), typeName);
    if (typeEntry != null) {
      return typeEntry.getType().apply(typeFactory);
    } else {
      return null;
    }
  }
  // 获取指定 Schema 路径下所有可用的对象列表。
  // 这些对象包括子 Schema、表（Table）、视图（View）和函数（Function）
  @Override public List<SqlMoniker> getAllSchemaObjectNames(List<String> names) {
    // 根据传入的路径 names（如 ["catalog", "sales"]）在元数据树中找到对应的 CalciteSchema 对象。
    final CalciteSchema schema =
        SqlValidatorUtil.getSchema(rootSchema, names, nameMatcher);
    if (schema == null) {
      return ImmutableList.of();
    }
    final ImmutableList.Builder<SqlMoniker> result = new ImmutableList.Builder<>();

    // Add root schema if not anonymous
    // 如果当前 Schema 不是匿名根节点（Root），则将其自身作为一个 SCHEMA 类型的 SqlMoniker 加入结果集。
    if (!schema.name.equals("")) {
      result.add(moniker(schema, null, SqlMonikerType.SCHEMA));
    }
    // 扫描子Schema（Sub-Schemas）
    final Map<String, CalciteSchema> schemaMap = schema.getSubSchemaMap();

    for (String subSchema : schemaMap.keySet()) {
      result.add(moniker(schema, subSchema, SqlMonikerType.SCHEMA));
    }
    // 扫描表（Tables）
    // 遍历并添加当前 Schema 下定义的所有表名。
    for (String table : schema.getTableNames()) {
      result.add(moniker(schema, table, SqlMonikerType.TABLE));
    }
    // 扫描函数与视图（Functions & Views）
    final NavigableSet<String> functions = schema.getFunctionNames();
    for (String function : functions) { // views are here as well
      result.add(moniker(schema, function, SqlMonikerType.FUNCTION));
    }
    return result.build();
  }

  private static SqlMonikerImpl moniker(CalciteSchema schema, @Nullable String name,
      SqlMonikerType type) {
    final List<String> path = schema.path(name);
    if (path.size() == 1
        && !schema.root().name.equals("")
        && type == SqlMonikerType.SCHEMA) {
      type = SqlMonikerType.CATALOG;
    }
    return new SqlMonikerImpl(path, type);
  }

  @Override public List<List<String>> getSchemaPaths() {
    return schemaPaths;
  }

  @Override public Prepare.@Nullable PreparingTable getTableForMember(List<String> names) {
    return getTable(names);
  }

  @SuppressWarnings("deprecation")
  @Override public @Nullable RelDataTypeField field(RelDataType rowType, String alias) {
    return nameMatcher.field(rowType, alias);
  }

  @SuppressWarnings("deprecation")
  @Override public boolean matches(String string, String name) {
    return nameMatcher.matches(string, name);
  }

  @Override public RelDataType createTypeFromProjection(final RelDataType type,
      final List<String> columnNameList) {
    return SqlValidatorUtil.createTypeFromProjection(type, columnNameList,
        typeFactory, nameMatcher.isCaseSensitive());
  }
  // 作用是在 SQL 校验阶段查找并加载用户自定义函数（UDF），并将它们转化为校验器可以理解的操作符对象。
  // 当你在 SQL 中写下 SELECT my_func(a, b) ... 时，校验器需要知道 my_func 是什么。
  //由于 SQL 支持函数重载（同名但参数不同），这个方法会查找所有匹配名称的函数实现，并过滤出符合特定类别（如普通标量函数或表函数）的实例，最后填充到结果列表 operatorList 中。
  @Override public void lookupOperatorOverloads(final SqlIdentifier opName,
      @Nullable SqlFunctionCategory category,
      SqlSyntax syntax,
      List<SqlOperator> operatorList,
      SqlNameMatcher nameMatcher) {
    // 该方法在此实现中仅处理 函数式语法（如 f(x)）。如果传入的是二元操作符（如 +）或前缀操作符，则直接忽略，交给 Calcite 的标准操作符表处理。
    if (syntax != SqlSyntax.FUNCTION) {
      return;
    }
    // 构建分类过滤器（Predicate）
    final Predicate<org.apache.calcite.schema.Function> predicate;
    if (category == null) {
      predicate = function -> true;
    } else if (category.isTableFunction()) {
      // // 仅保留表函数（TableMacro 或 TableFunction）
      predicate = function ->
          function instanceof TableMacro
              || function instanceof TableFunction;
    } else {
      // // 仅保留非表函数（如标量函数 ScalarFunction）
      predicate = function ->
          !(function instanceof TableMacro
              || function instanceof TableFunction);
    }
    getFunctionsFrom(opName.names)
        .stream()
        .filter(predicate) // 2. 应用分类过滤
        .map(function -> toOp(opName, function)) // 3. 核心转换：将 Schema 函数转换为 SqlOperator
        .forEachOrdered(operatorList::add);
  }

  /** Creates an operator table that contains functions in the given class
   * or classes.
   *
   * @see ModelHandler#addFunctions */
  public static SqlOperatorTable operatorTable(String... classNames) {
    // Dummy schema to collect the functions
    final CalciteSchema schema =
        CalciteSchema.createRootSchema(false, false);
    for (String className : classNames) {
      ModelHandler.addFunctions(schema.plus(), null, ImmutableList.of(),
          className, "*", true);
    }

    final List<SqlOperator> list = new ArrayList<>();
    for (String name : schema.getFunctionNames()) {
      schema.getFunctions(name, true).forEach(function -> {
        final SqlIdentifier id = new SqlIdentifier(name, SqlParserPos.ZERO);
        list.add(toOp(id, function));
      });
    }
    return SqlOperatorTables.of(list);
  }

  /** Converts a function to a {@link org.apache.calcite.sql.SqlOperator}. */
  // 核心作用是：将底层元数据层定义的 Function（通常是 Java 方法或 Schema 定义的对象）转换为 SQL 层能够理解的 SqlOperator（操作符/函数对象）。
  // 这种转换是必要的，因为 SQL 校验器并不直接操作 Java 方法，它需要知道函数的参数类型、返回类型、是否可选以及参数名等信息。
  private static SqlOperator toOp(SqlIdentifier name,
      final org.apache.calcite.schema.Function function) {
    // 获取函数参数的原始 RelDataType 列表。
    final Function<RelDataTypeFactory, List<RelDataType>> argTypesFactory =
        typeFactory -> function.getParameters()
            .stream()
            .map(o -> o.getType(typeFactory))
            .collect(toImmutableList());
    // 将参数类型映射为 SqlTypeFamily（类型族，如 NUMERIC, CHARACTER）。这用于宽泛的参数匹配。
    final Function<RelDataTypeFactory, List<SqlTypeFamily>> typeFamiliesFactory =
        typeFactory -> argTypesFactory.apply(typeFactory)
            .stream()
            .map(type ->
                Util.first(type.getSqlTypeName().getFamily(),
                    SqlTypeFamily.ANY))
            .collect(toImmutableList());
    // 将参数类型转换为标准的 SQL 类型（通过 toSql 方法），用于精确的校验。
    final Function<RelDataTypeFactory, List<RelDataType>> paramTypesFactory =
        typeFactory ->
            argTypesFactory.apply(typeFactory)
                .stream()
                .map(type -> toSql(typeFactory, type))
                .collect(toImmutableList());

    // Use a short-lived type factory to populate "typeFamilies" and "argTypes".
    // SqlOperandMetadata.paramTypes will use the real type factory, during
    // validation.
    // 由于某些元数据操作需要立即知道类型族，
    // 但此时可能还没进入正式的校验流程，因此代码创建了一个临时的 dummyTypeFactory 来提取这些元数据快照。
    final RelDataTypeFactory dummyTypeFactory = new JavaTypeFactoryImpl();
    final List<RelDataType> argTypes = argTypesFactory.apply(dummyTypeFactory);
    final List<SqlTypeFamily> typeFamilies =
        typeFamiliesFactory.apply(dummyTypeFactory);
    // 告诉 SQL 引擎：该函数的参数类型是显式定义的（Explicit）
    final SqlOperandTypeInference operandTypeInference =
        InferTypes.explicit(argTypes);

    final SqlOperandMetadata operandMetadata =
        OperandTypes.operandMetadata(typeFamilies, paramTypesFactory,
            i -> function.getParameters().get(i).getName(),
            i -> function.getParameters().get(i).isOptional());

    final SqlKind kind = kind(function);
    // 多态封装与转换
    // 根据 function 的具体子类型，将其封装为对应的 SQL 对象：
    if (function instanceof ScalarFunction) {
      // 标量函数): 映射为 SqlUserDefinedFunction。调用 infer 方法处理返回类型推导。
      final SqlReturnTypeInference returnTypeInference =
          infer((ScalarFunction) function);
      return new SqlUserDefinedFunction(name, kind, returnTypeInference,
          operandTypeInference, operandMetadata, function);
    } else if (function instanceof AggregateFunction) {
      // 聚合函数): 映射为 SqlUserDefinedAggFunction。
      final SqlReturnTypeInference returnTypeInference =
          infer((AggregateFunction) function);
      return new SqlUserDefinedAggFunction(name, kind,
          returnTypeInference, operandTypeInference,
          operandMetadata, (AggregateFunction) function, false, false,
          Optionality.FORBIDDEN);
    } else if (function instanceof TableMacro) {
      // 表函数): 映射为对应的 TableFunction 类，且返回类型固定为 CURSOR。
      return new SqlUserDefinedTableMacro(name, kind, ReturnTypes.CURSOR,
          operandTypeInference, operandMetadata, (TableMacro) function);
    } else if (function instanceof TableFunction) {
      return new SqlUserDefinedTableFunction(name, kind, ReturnTypes.CURSOR,
          operandTypeInference, operandMetadata, (TableFunction) function);
    } else {
      throw new AssertionError("unknown function type " + function);
    }
  }

  /** Deduces the {@link org.apache.calcite.sql.SqlKind} of a user-defined
   * function based on a {@link Hints} annotation, if present. */
  private static SqlKind kind(org.apache.calcite.schema.Function function) {
    if (function instanceof ScalarFunctionImpl) {
      Hints hints =
          ((ScalarFunctionImpl) function).method.getAnnotation(Hints.class);
      if (hints != null) {
        for (String hint : hints.value()) {
          if (hint.startsWith("SqlKind:")) {
            return SqlKind.valueOf(hint.substring("SqlKind:".length()));
          }
        }
      }
    }
    return SqlKind.OTHER_FUNCTION;
  }

  private static SqlReturnTypeInference infer(final ScalarFunction function) {
    return opBinding -> {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      final RelDataType type;
      if (function instanceof ScalarFunctionImpl) {
        type =
            ((ScalarFunctionImpl) function).getReturnType(typeFactory, opBinding);
      } else {
        type = function.getReturnType(typeFactory);
      }
      return toSql(typeFactory, type);
    };
  }

  private static SqlReturnTypeInference infer(
      final AggregateFunction function) {
    return opBinding -> {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      final RelDataType type = function.getReturnType(typeFactory);
      return toSql(typeFactory, type);
    };
  }

  private static RelDataType toSql(RelDataTypeFactory typeFactory,
      RelDataType type) {
    if (type instanceof RelDataTypeFactoryImpl.JavaType
        && ((RelDataTypeFactoryImpl.JavaType) type).getJavaClass()
        == Object.class) {
      return typeFactory.createTypeWithNullability(
          typeFactory.createSqlType(SqlTypeName.ANY), true);
    }
    return JavaTypeFactoryImpl.toSql(typeFactory, type);
  }

  @Override public List<SqlOperator> getOperatorList() {
    final ImmutableList.Builder<SqlOperator> builder = ImmutableList.builder();
    for (List<String> schemaPath : schemaPaths) {
      CalciteSchema schema =
          SqlValidatorUtil.getSchema(rootSchema, schemaPath, nameMatcher);
      if (schema != null) {
        for (String name : schema.getFunctionNames()) {
          schema.getFunctions(name, true).forEach(f ->
              builder.add(toOp(new SqlIdentifier(name, SqlParserPos.ZERO), f)));
        }
      }
    }
    return builder.build();
  }

  @Override public CalciteSchema getRootSchema() {
    return rootSchema;
  }

  @Override public RelDataTypeFactory getTypeFactory() {
    return typeFactory;
  }

  @Override public void registerRules(RelOptPlanner planner) {
  }

  @SuppressWarnings("deprecation")
  @Override public boolean isCaseSensitive() {
    return nameMatcher.isCaseSensitive();
  }

  @Override public SqlNameMatcher nameMatcher() {
    return nameMatcher;
  }

  @Override public <C extends Object> @Nullable C unwrap(Class<C> aClass) {
    if (aClass.isInstance(this)) {
      return aClass.cast(this);
    }
    return null;
  }
}
