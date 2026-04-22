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
package org.apache.calcite.sql.validate;

import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.type.DynamicRecordType;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.StructKind;
import org.apache.calcite.schema.CustomColumnResolvingTable;
import org.apache.calcite.schema.Table;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlLambda;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWindow;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;

import static org.apache.calcite.util.Static.RESOURCE;

import static java.util.Objects.requireNonNull;

/**
 * A scope which delegates all requests to its parent scope. Use this as a base
 * class for defining nested scopes.
 */
// 实现了 SqlValidatorScope 接口，采用了 委托模式（Delegation Pattern）。
// 在 SQL 校验过程中，作用域（Scope）是嵌套的。DelegatingScope 的核心作用是：
// 提供嵌套支持：作为所有嵌套作用域（如 SelectScope、WhereScope 等）的父类，它默认将所有名称解析请求转发给其 parent 作用域。
// 实现“向上查找”逻辑：如果在当前层级找不到某个标识符（如列名、表名），该类确保查找逻辑能沿着 Scope 树向上递归，直到 EmptyScope。
// 提供通用工具方法：它封装了复杂的标识符全限定化（Fully Qualify）逻辑和在特定命名空间内解析字段的逻辑。
public abstract class DelegatingScope implements SqlValidatorScope {
  //~ Instance fields --------------------------------------------------------

  /**
   * Parent scope. This is where to look next to resolve an identifier; it is
   * not always the parent object in the parse tree.
   *
   * <p>This is never null: at the top of the tree, it is an
   * {@link EmptyScope}.
   */
  // 指向当前作用域的父级。
  // 查找链的关键，确保了如子查询可以访问外部查询的变量。
  protected final SqlValidatorScope parent;
  // 持有校验器的引用，用于抛出校验错误或访问类型工厂。
  protected final SqlValidatorImpl validator;

  /** Computes and stores information that cannot be computed on construction,
   * but only after sub-queries have been validated. */
  // 使用备忘录模式（Memoization）存储该作用域的分析结果。主要用于处理聚合（Aggregation）和度量值（Measure）的解析。
  @SuppressWarnings({"methodref.receiver.bound.invalid"})
  public final Supplier<AggregatingSelectScope.Resolved> resolved =
      Suppliers.memoize(this::resolve);

  /** Use while resolving. */
  // 在执行 resolve 分析过程中的临时状态记录器。
  SqlValidatorUtil.@Nullable GroupAnalyzer groupAnalyzer;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a <code>DelegatingScope</code>.
   *
   * @param parent Parent scope
   */
  DelegatingScope(SqlValidatorScope parent) {
    super();
    this.parent = requireNonNull(parent, "parent");
    this.validator = (SqlValidatorImpl) parent.getValidator();
  }

  //~ Methods ----------------------------------------------------------------
  // 默认抛出异常。
  // 只有特定子类（如 SelectScope）才允许向作用域添加子项（如 FROM 后的表）。
  @Override public void addChild(SqlValidatorNamespace ns, String alias,
      boolean nullable) {
    // By default, you cannot add to a scope. Derived classes can
    // override.
    throw new UnsupportedOperationException();
  }
  // 通过调用 parent.xxx 实现递归查找：
  @Override public void resolve(List<String> names, SqlNameMatcher nameMatcher,
      boolean deep, Resolved resolved) {
    parent.resolve(names, nameMatcher, deep, resolved);
  }

  /** If a record type allows implicit references to fields, recursively looks
   * into the fields. Otherwise, returns immediately. */
  // 作用是：在一个给定的命名空间（SqlValidatorNamespace）内部，根据用户提供的名称列表（names）寻找对应的字段，并处理嵌套结构（如结构体、行对象）。
  // 处理 table.column.field 这种多级标识符的“发动机”。
  // 设计目标是：层层剥开数据结构的“外壳”，直到匹配完所有的名称组件。
  void resolveInNamespace(SqlValidatorNamespace ns, boolean nullable,
      List<String> names, SqlNameMatcher nameMatcher, Path path,
      Resolved resolved) {
    // 如果 names 列表为空，说明已经成功匹配了用户提供的所有名称组件。此时调用 resolved.found 记录结果，完成解析。
    if (names.isEmpty()) {
      resolved.found(ns, nullable, this, path, names);
      return;
    }
    final RelDataType rowType = ns.getRowType();
    if (rowType.isStruct()) {
      SqlValidatorTable validatorTable = ns.getTable();
      if (validatorTable instanceof Prepare.PreparingTable) {
        Table t = ((Prepare.PreparingTable) validatorTable).unwrap(Table.class);
        // 特殊表自定义解析 (CustomColumnResolvingTable)
        // 支持一些非标准行为的表。例如，某些表可能支持动态列，或者列名包含特殊前缀。
        // 允许表自己决定如何将 names 映射到内部字段，并返回匹配后的“剩余名称”（remainder）。
        if (t instanceof CustomColumnResolvingTable) {

          final List<Pair<RelDataTypeField, List<String>>> entries =
              ((CustomColumnResolvingTable) t).resolveColumn(
                  rowType, validator.getTypeFactory(), names);
          for (Pair<RelDataTypeField, List<String>> entry : entries) {
            final RelDataTypeField field = entry.getKey();
            final List<String> remainder = entry.getValue();
            final SqlValidatorNamespace ns2 =
                new FieldNamespace(validator, field.getType());
            final Step path2 =
                path.plus(rowType, field.getIndex(), field.getName(),
                    StructKind.FULLY_QUALIFIED);
            resolveInNamespace(ns2, nullable, remainder, nameMatcher, path2,
                resolved);
          }
          return;
        }
      }
      // 第三阶段：直接字段匹配 (Direct Match)
      // 取出 names 的第一个组件（如 deptno）。
      final String name = names.get(0);
      // 在当前 rowType 中查找是否存在该字段。
      final RelDataTypeField field0 = nameMatcher.field(rowType, name);
      if (field0 != null) {
        // 如果找到，则进入该字段的子命名空间，并将 names 列表中剩下的部分继续递归查找。
        // 获取当前字段对应的子命名空间（Namespace）
        // 在 SQL 中，一个字段可能不仅是一个简单的值（如 INT），它也可能是一个复合对象（如 Struct 或 Row）。为了继续解析 names 中的剩余部分，必须先进入这个字段内部的“小世界”。
        final SqlValidatorNamespace ns2 =
            requireNonNull(ns.lookupChild(field0.getName()),
                () -> "field " + field0.getName() + " is not found in " + ns);
        // 在当前的解析路径上追加一个新的“步骤（Step）”。
        final Step path2 =
            path.plus(rowType, field0.getIndex(),
                field0.getName(), StructKind.FULLY_QUALIFIED);
        // names.subList(1, names.size() 将名字列表向后推移一位。例如，如果原始输入是 ["info", "id"]，匹配完 info 后，下一轮递归处理的就只剩下 ["id"]。
        resolveInNamespace(ns2, nullable, names.subList(1, names.size()),
            nameMatcher, path2, resolved);
      } else {
        // 当 names.get(0) 直接匹配失败时触发。
        for (RelDataTypeField field : rowType.getFieldList()) {
          // 检查当前 Namespace 里的每一个列，看看有没有哪一列能“帮我们”找到目标。
          switch (field.getType().getStructKind()) {
          case PEEK_FIELDS: // 允许隐式访问其内部成员
          case PEEK_FIELDS_DEFAULT: // 默认行为允许 PEEK。
          case PEEK_FIELDS_NO_EXPAND: // 虽然不展开（如在 SELECT * 时），但手动引用时仍允许探测内部。
            final Step path2 =
                path.plus(rowType, field.getIndex(),
                    field.getName(), field.getType().getStructKind());
            final SqlValidatorNamespace ns2 =
                requireNonNull(ns.lookupChild(field.getName()),
                    () -> "field " + field.getName() + " is not found in " + ns);
            // 因为当前这一层（field）只是一个“容器”或“中间层”，它并没有消耗掉用户提供的任何一个名字。我们只是借道这个字段，进入它的内部去匹配同一个名字。
            resolveInNamespace(ns2, nullable, names, nameMatcher, path2,
                resolved);
            break;
          default:
            break;
          }
        }
      }
    }
  }
  // 主要功能是将特定命名空间（Namespace）中的所有列名提取出来，并封装为可供自动补全或元数据查询使用的 SqlMoniker 对象。
  protected void addColumnNames(
      SqlValidatorNamespace ns,
      List<SqlMoniker> colNames) {
    final RelDataType rowType;
    try {
      rowType = ns.getRowType();
    } catch (Error e) {
      // namespace is not good - bail out.
      return;
    }

    for (RelDataTypeField field : rowType.getFieldList()) {
      colNames.add(
          new SqlMonikerImpl(
              field.getName(),
              SqlMonikerType.COLUMN));
    }
  }
  // 搜集在当前作用域及其所有父级作用域中可见的所有列名。这通常用于 SQL 自动补全（IntelliSense）或在解析发生错误时为用户提供建议。
  // 它直接调用 parent.findAllColumnNames(result)。由于 DelegatingScope 本身不持有任何表或列（它只是一个包装层），它必须询问它的父级。
  @Override public void findAllColumnNames(List<SqlMoniker> result) {
    parent.findAllColumnNames(result);
  }
  // 用于搜集当前上下文中所有合法的表别名（Table Aliases）或命名空间名称。
  @Override public void findAliases(Collection<SqlMoniker> result) {
    parent.findAliases(result);
  }

  @SuppressWarnings("deprecation")
  @Override public Pair<String, SqlValidatorNamespace> findQualifyingTableName(
      String columnName, SqlNode ctx) {
    //noinspection deprecation
    return parent.findQualifyingTableName(columnName, ctx);
  }

  @Override public Map<String, ScopeChild> findQualifyingTableNames(String columnName,
      SqlNode ctx, SqlNameMatcher nameMatcher) {
    return parent.findQualifyingTableNames(columnName, ctx, nameMatcher);
  }

  @Override public @Nullable RelDataType resolveColumn(String name, SqlNode ctx) {
    return parent.resolveColumn(name, ctx);
  }

  @Override public RelDataType nullifyType(SqlNode node, RelDataType type) {
    return parent.nullifyType(node, type);
  }

  @SuppressWarnings("deprecation")
  @Override public @Nullable SqlValidatorNamespace getTableNamespace(List<String> names) {
    return parent.getTableNamespace(names);
  }

  @Override public void resolveTable(List<String> names, SqlNameMatcher nameMatcher,
      Path path, Resolved resolved) {
    parent.resolveTable(names, nameMatcher, path, resolved);
  }
  // 当解析一个函数调用或操作符（SqlCall）时，系统需要确定该操作符的**参数（Operands）**应该在哪个作用域内进行校验。
  // 方法的作用就是为特定的 SQL 调用分配正确的校验上下文。
  @Override public SqlValidatorScope getOperandScope(SqlCall call) {
    // 分支 A：处理子查询 (SqlSelect)
    // 场景：当一个表达式中嵌套了子查询时，例如 WHERE empno IN (SELECT id FROM ...)。
    // 逻辑：如果操作数是一个 SELECT 语句，不能使用当前的外部作用域，而必须通过校验器获取该子查询专属的 SelectScope。
    if (call instanceof SqlSelect) {
      return validator.getSelectScope((SqlSelect) call);
      // 分支 B：处理 Lambda 表达式 (SqlLambda)
    } else if (call instanceof SqlLambda) {
      return validator.getLambdaScope((SqlLambda) call);
    }
    return this;
  }

  @Override public SqlValidator getValidator() {
    return validator;
  }

  /**
   * Converts an identifier into a fully-qualified identifier. For example,
   * the "empno" in "select empno from emp natural join dept" becomes
   * "emp.empno".
   *
   * <p>If the identifier cannot be resolved, throws. Never returns null.
   */
  @Override public SqlQualified fullyQualify(SqlIdentifier identifier) {
    if (identifier.isStar()) {
      return SqlQualified.create(this, 1, null, identifier);
    }

    final SqlIdentifier previous = identifier;
    final SqlNameMatcher nameMatcher = validator.catalogReader.nameMatcher();
    String columnName;
    final String tableName;
    final SqlValidatorNamespace namespace;
    switch (identifier.names.size()) {
    case 1: {
      columnName = identifier.names.get(0);
      final Map<String, ScopeChild> map =
          findQualifyingTableNames(columnName, identifier, nameMatcher);
      switch (map.size()) {
      case 0:
        if (nameMatcher.isCaseSensitive()) {
          final SqlNameMatcher liberalMatcher = SqlNameMatchers.liberal();
          final Map<String, ScopeChild> map2 =
              findQualifyingTableNames(columnName, identifier, liberalMatcher);
          if (!map2.isEmpty()) {
            final List<String> list = new ArrayList<>();
            for (ScopeChild entry : map2.values()) {
              final RelDataTypeField field =
                  liberalMatcher.field(entry.namespace.getRowType(),
                      columnName);
              if (field == null) {
                continue;
              }
              list.add(field.getName());
            }
            Collections.sort(list);
            throw validator.newValidationError(identifier,
                RESOURCE.columnNotFoundDidYouMean(columnName,
                    Util.sepList(list, "', '")));
          }
        }
        throw validator.newValidationError(identifier,
            RESOURCE.columnNotFound(columnName));
      case 1:
        tableName = map.keySet().iterator().next();
        namespace = map.get(tableName).namespace;
        break;
      default:
        throw validator.newValidationError(identifier,
            RESOURCE.columnAmbiguous(columnName));
      }

      final ResolvedImpl resolved = new ResolvedImpl();
      resolveInNamespace(namespace, false, identifier.names, nameMatcher,
          Path.EMPTY, resolved);
      final RelDataTypeField field =
          nameMatcher.field(namespace.getRowType(), columnName);
      if (field != null) {
        if (hasAmbiguousField(namespace.getRowType(), field,
            columnName, nameMatcher)) {
          throw validator.newValidationError(identifier,
              RESOURCE.columnAmbiguous(columnName));
        }

        columnName = field.getName(); // use resolved field name
      }
      // todo: do implicit collation here
      final SqlParserPos pos = identifier.getParserPosition();
      identifier =
          new SqlIdentifier(ImmutableList.of(tableName, columnName), null,
              pos, ImmutableList.of(SqlParserPos.ZERO, pos));
    }
    // fall through
    default: {
      SqlValidatorNamespace fromNs = null;
      Path fromPath = null;
      RelDataType fromRowType = null;
      final ResolvedImpl resolved = new ResolvedImpl();
      int size = identifier.names.size();
      int i = size - 1;
      for (; i > 0; i--) {
        final SqlIdentifier prefix = identifier.getComponent(0, i);
        resolved.clear();
        resolve(prefix.names, nameMatcher, false, resolved);
        if (resolved.count() == 1) {
          final Resolve resolve = resolved.only();
          fromNs = resolve.namespace;
          fromPath = resolve.path;
          fromRowType = resolve.rowType();
          break;
        }
        // Look for a table alias that is the wrong case.
        if (nameMatcher.isCaseSensitive()) {
          final SqlNameMatcher liberalMatcher = SqlNameMatchers.liberal();
          resolved.clear();
          resolve(prefix.names, liberalMatcher, false, resolved);
          if (resolved.count() == 1) {
            final Step lastStep = Util.last(resolved.only().path.steps());
            throw validator.newValidationError(prefix,
                RESOURCE.tableNameNotFoundDidYouMean(prefix.toString(),
                    lastStep.name));
          }
        }
      }
      if (fromNs == null || fromNs instanceof SchemaNamespace) {
        // Look for a column not qualified by a table alias.
        columnName = identifier.names.get(0);
        final Map<String, ScopeChild> map =
            findQualifyingTableNames(columnName, identifier, nameMatcher);
        switch (map.size()) {
        default:
          final SqlIdentifier prefix1 = identifier.skipLast(1);
          throw validator.newValidationError(prefix1,
              RESOURCE.tableNameNotFound(prefix1.toString()));
        case 1: {
          final Map.Entry<String, ScopeChild> entry =
              map.entrySet().iterator().next();
          final String tableName2 = map.keySet().iterator().next();
          fromNs = entry.getValue().namespace;
          fromPath = Path.EMPTY;

          // Adding table name is for RecordType column with StructKind.PEEK_FIELDS or
          // StructKind.PEEK_FIELDS only. Access to a field in a RecordType column of
          // other StructKind should always be qualified with table name.
          final RelDataTypeField field =
              nameMatcher.field(fromNs.getRowType(), columnName);
          if (field != null) {
            switch (field.getType().getStructKind()) {
            case PEEK_FIELDS:
            case PEEK_FIELDS_DEFAULT:
            case PEEK_FIELDS_NO_EXPAND:
              columnName = field.getName(); // use resolved field name
              resolve(ImmutableList.of(tableName2), nameMatcher, false,
                  resolved);
              if (resolved.count() == 1) {
                final Resolve resolve = resolved.only();
                fromNs = resolve.namespace;
                fromPath = resolve.path;
                fromRowType = resolve.rowType();
                identifier = identifier
                    .setName(0, columnName)
                    .add(0, tableName2, SqlParserPos.ZERO);
                ++i;
                ++size;
              }
              break;
            default:
              // Throw an error if the table was not found.
              // If one or more of the child namespaces allows peeking
              // (e.g. if they are Phoenix column families) then we relax the SQL
              // standard requirement that record fields are qualified by table alias.
              final SqlIdentifier prefix = identifier.skipLast(1);
              throw validator.newValidationError(prefix,
                  RESOURCE.tableNameNotFound(prefix.toString()));
            }
          }
        }
        }
      }

      // If a table alias is part of the identifier, make sure that the table
      // alias uses the same case as it was defined. For example, in
      //
      //    SELECT e.empno FROM Emp as E
      //
      // change "e.empno" to "E.empno".
      if (fromNs.getEnclosingNode() != null
          && !(this instanceof MatchRecognizeScope)) {
        @Nullable String alias =
            SqlValidatorUtil.alias(fromNs.getEnclosingNode());
        if (alias != null
            && i > 0
            && !alias.equals(identifier.names.get(i - 1))) {
          identifier = identifier.setName(i - 1, alias);
        }
      }
      if (requireNonNull(fromPath, "fromPath").stepCount() > 1) {
        assert fromRowType != null;
        for (Step p : fromPath.steps()) {
          fromRowType = fromRowType.getFieldList().get(p.i).getType();
        }
        ++i;
      }
      final SqlIdentifier suffix = identifier.getComponent(i, size);
      resolved.clear();
      resolveInNamespace(fromNs, false, suffix.names, nameMatcher, Path.EMPTY,
          resolved);
      final Path path;
      switch (resolved.count()) {
      case 0:
        // Maybe the last component was correct, just wrong case
        if (nameMatcher.isCaseSensitive()) {
          SqlNameMatcher liberalMatcher = SqlNameMatchers.liberal();
          resolved.clear();
          resolveInNamespace(fromNs, false, suffix.names, liberalMatcher,
              Path.EMPTY, resolved);
          if (resolved.count() > 0) {
            int k = size - 1;
            final SqlIdentifier prefix = identifier.getComponent(0, i);
            final SqlIdentifier suffix3 = identifier.getComponent(i, k + 1);
            final Step step = Util.last(resolved.resolves.get(0).path.steps());
            throw validator.newValidationError(suffix3,
                RESOURCE.columnNotFoundInTableDidYouMean(suffix3.toString(),
                    prefix.toString(), step.name));
          }
        }
        // Find the shortest suffix that also fails. Suppose we cannot resolve
        // "a.b.c"; we find we cannot resolve "a.b" but can resolve "a". So,
        // the error will be "Column 'a.b' not found".
        int k = size - 1;
        for (; k > i; --k) {
          SqlIdentifier suffix2 = identifier.getComponent(i, k);
          resolved.clear();
          resolveInNamespace(fromNs, false, suffix2.names, nameMatcher,
              Path.EMPTY, resolved);
          if (resolved.count() > 0) {
            break;
          }
        }
        final SqlIdentifier prefix = identifier.getComponent(0, i);
        final SqlIdentifier suffix3 = identifier.getComponent(i, k + 1);
        throw validator.newValidationError(suffix3,
            RESOURCE.columnNotFoundInTable(suffix3.toString(), prefix.toString()));
      case 1:
        path = resolved.only().path;
        break;
      default:
        final Comparator<Resolve> c =
            new Comparator<Resolve>() {
              @Override public int compare(Resolve o1, Resolve o2) {
                // Name resolution that uses fewer implicit steps wins.
                int c = Integer.compare(worstKind(o1.path), worstKind(o2.path));
                if (c != 0) {
                  return c;
                }
                // Shorter path wins
                return Integer.compare(o1.path.stepCount(), o2.path.stepCount());
              }

              private int worstKind(Path path) {
                int kind = -1;
                for (Step step : path.steps()) {
                  kind = Math.max(kind, step.kind.ordinal());
                }
                return kind;
              }
            };
        resolved.resolves.sort(c);
        if (c.compare(resolved.resolves.get(0), resolved.resolves.get(1)) == 0) {
          throw validator.newValidationError(suffix,
              RESOURCE.columnAmbiguous(suffix.toString()));
        }
        path = resolved.resolves.get(0).path;
      }

      // Normalize case to match definition, make elided fields explicit,
      // and check that references to dynamic stars ("**") are unambiguous.
      int k = i;
      for (Step step : path.steps()) {
        final String name = identifier.names.get(k);
        if (step.i < 0) {
          throw validator.newValidationError(
              identifier, RESOURCE.columnNotFound(name));
        }
        final RelDataTypeField field0 =
            requireNonNull(step.rowType, () -> "rowType of step " + step.name)
                .getFieldList().get(step.i);
        final String fieldName = field0.getName();
        switch (step.kind) {
        case PEEK_FIELDS:
        case PEEK_FIELDS_DEFAULT:
        case PEEK_FIELDS_NO_EXPAND:
          identifier = identifier.add(k, fieldName, SqlParserPos.ZERO);
          break;
        default:
          if (!fieldName.equals(name)) {
            identifier = identifier.setName(k, fieldName);
          }
          if (hasAmbiguousField(step.rowType, field0, name, nameMatcher)) {
            throw validator.newValidationError(identifier,
                RESOURCE.columnAmbiguous(name));
          }
        }
        ++k;
      }

      // Multiple name components may have been resolved as one step by
      // CustomResolvingTable.
      if (identifier.names.size() > k) {
        identifier = identifier.getComponent(0, k);
      }

      if (i > 1) {
        // Simplify overqualified identifiers.
        // For example, schema.emp.deptno becomes emp.deptno.
        //
        // It is safe to convert schema.emp or database.schema.emp to emp
        // because it would not have resolved if the FROM item had an alias. The
        // following query is invalid:
        //   SELECT schema.emp.deptno FROM schema.emp AS e
        identifier = identifier.getComponent(i - 1, identifier.names.size());
      }

      if (!previous.equals(identifier)) {
        validator.setOriginal(identifier, previous);
      }
      return SqlQualified.create(this, i, fromNs, identifier);
    }
    }
  }

  @Override public void validateExpr(SqlNode expr) {
    // Do not delegate to parent. An expression valid in this scope may not
    // be valid in the parent scope.
  }

  @Override public @Nullable SqlWindow lookupWindow(String name) {
    return parent.lookupWindow(name);
  }

  @Override public SqlMonotonicity getMonotonicity(SqlNode expr) {
    return parent.getMonotonicity(expr);
  }

  @Override public @Nullable SqlNodeList getOrderList() {
    return parent.getOrderList();
  }

  /** Returns whether {@code rowType} contains more than one star column or
   * fields with the same name, which implies ambiguous column. */
  private static boolean hasAmbiguousField(RelDataType rowType,
      RelDataTypeField field, String columnName, SqlNameMatcher nameMatcher) {
    if (field.isDynamicStar()
        && !DynamicRecordType.isDynamicStarColName(columnName)) {
      int count = 0;
      for (RelDataTypeField possibleStar : rowType.getFieldList()) {
        if (possibleStar.isDynamicStar()) {
          if (++count > 1) {
            return true;
          }
        }
      }
    } else { // check if there are fields with the same name
      int count = 0;
      for (RelDataTypeField f : rowType.getFieldList()) {
        if (Util.matches(nameMatcher.isCaseSensitive(), f.getName(), columnName)) {
          count++;
        }
      }
      if (count > 1) {
        return true;
      }
    }
    return false;
  }

  private AggregatingSelectScope.Resolved resolve() {
    checkArgument(groupAnalyzer == null, "resolve already in progress");
    SqlValidatorUtil.GroupAnalyzer groupAnalyzer = new SqlValidatorUtil.GroupAnalyzer();
    this.groupAnalyzer = groupAnalyzer;
    try {
      analyze(groupAnalyzer);
      return groupAnalyzer.finish();
    } finally {
      this.groupAnalyzer = null;
    }
  }

  /** Analyzes expressions in this scope and populates a
   * {@code GroupAnalyzer}. */
  protected void analyze(SqlValidatorUtil.GroupAnalyzer analyzer) {
    final SelectScope selectScope = SqlValidatorUtil.getEnclosingSelectScope(this);
    if (selectScope != null) {
      // Find all expressions in this scope that reference measures
      for (ScopeChild child : selectScope.children) {
        final RelDataType rowType = child.namespace.getRowType();
        if (child.namespace instanceof SelectNamespace) {
          final SqlSelect select = ((SelectNamespace) child.namespace).getNode();
          Pair.forEach(select.getSelectList(),
              rowType.getFieldList(),
              (selectItem, field) -> {
                if (SqlValidatorUtil.isMeasure(selectItem)) {
                  analyzer.measureExprs.add(
                      new SqlIdentifier(
                          Arrays.asList(child.name, field.getName()),
                          SqlParserPos.ZERO));
                }
              });
        } else {
          rowType.getFieldList().forEach(field -> {
            if (field.getType().getSqlTypeName() == SqlTypeName.MEASURE) {
              analyzer.measureExprs.add(
                  new SqlIdentifier(
                      Arrays.asList(child.name, field.getName()),
                      SqlParserPos.ZERO));
            }
          });
        }
      }
    }
  }

  /**
   * Returns the parent scope of this <code>DelegatingScope</code>.
   */
  public SqlValidatorScope getParent() {
    return parent;
  }
}
