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
package org.apache.calcite.rex;

import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.enumerable.EnumUtils;
import org.apache.calcite.adapter.enumerable.RexToLixTranslator;
import org.apache.calcite.adapter.enumerable.RexToLixTranslator.InputGetter;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.config.CalciteSystemProperty;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.IndexExpression;
import org.apache.calcite.linq4j.tree.MethodCallExpression;
import org.apache.calcite.linq4j.tree.MethodDeclaration;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Evaluates a {@link RexNode} expression.
 *
 * <p>For this impl, all the public methods should be
 * static except that it inherits from {@link RexExecutor}.
 * This pretends that other code in the project assumes
 * the executor instance is {@link RexExecutorImpl}.
*/
// RexExecutorImpl 是 Apache Calcite 表达式计算/常数折叠的核心物理实现类。
// 它实现了 RexExecutor 接口，通过动态将 Calcite 关系表达式树（RexNode）翻译为 Java 表达式树（LINQ4J AST），进而生成纯 Java 源码字符串，最终交由 Janino 编译器动态编译执行来获取常数计算结果。
// 在 SQL 优化规则（如 ReduceExpressionsRule）运行时，如果发现常量表达式（如 1 + 2 或 CAST('2026-06-01' AS DATE)），需要对其进行折叠。Calcite 本身只是逻辑框架，不具备直接执行 + 或 CAST 物理算术的能力。
// RexExecutorImpl 的核心工作流如下：
// 表达式翻译：接收一组临时的 RexNode 表达式，通过 RexToLixTranslator 工具，将它们翻译并拼装成 Java 语言的 AST 代码块（BlockBuilder）。
// 源码生成：将 AST 代码块转化为一个符合 Java 语法的 public Object[] apply(DataContext root0) 方法源码字符串。
// 动态编译与执行：将源码交给 RexExecutable（内部依托 Janino 动态编译器），在 JVM 内存中实时编译成 Class 字节码并实例化。
// 结果回写：传入执行上下文 DataContext 运行该方法，直接拿到计算后的物理结果（如 3），再利用 RexBuilder 包装成等价的字面量（RexLiteral）写回，完成常量折叠。

public class RexExecutorImpl implements RexExecutor {
  // 运行时的动态上下文对象。
  // 承载了查询运行期的各种环境变量。当常数折叠涉及动态函数（如 CURRENT_TIMESTAMP、LOCALTIME）或上下文配置（如当前时区、当前 Schema）时，生成的 Java 代码必须通过访问 dataContext 才能捞到这些物理值。
  private final DataContext dataContext;

  public RexExecutorImpl(DataContext dataContext) {
    this.dataContext = dataContext;
  }
  // 入参：表达式构建器、待编译常数列表、输入获取器。
  private static String compile(RexBuilder rexBuilder, List<RexNode> constExps,
      RexToLixTranslator.InputGetter getter) {
    final RelDataTypeFactory typeFactory = rexBuilder.getTypeFactory();
    final RelDataType emptyRowType = typeFactory.builder().build();
    return compile(rexBuilder, constExps, getter, emptyRowType);
  }
  // 本质是一个从 SQL 关系表达式（RexNode）到 Java 内存源码（AST）的“翻译重组引擎”。
  // RexBuilder rexBuilder：Calcite 的表达式构建工厂。整个翻译过程中如果需要临时包装、创建或对齐某些节点类型，都需要依托这个工厂来处理。
  // List<RexNode> constExps：核心输入。传入的一组待折叠/待计算的逻辑常数表达式树（例如：[ 1 + 2, CAST('2026-06-01' AS DATE) ]）。
  // RexToLixTranslator.InputGetter getter：输入读取策略（常数折叠时通常传入一个不支持读取列的空实现；如果是常规算子执行则传入能够从运行期物理行读取数据的实现）。
  // RelDataType rowType：输入行的元数据结构大纲（描述了当前环境包含哪些字段及对应的 SQL 类型）。
  // 返回值 String：核心输出。最终生成的一段符合 Java 语法规范的、可执行的方法源码文本。
  private static String compile(RexBuilder rexBuilder, List<RexNode> constExps,
      RexToLixTranslator.InputGetter getter, RelDataType rowType) {
    // 构建临时的 RexProgram
    // 内幕：RexProgram 是 Calcite 对一个标准的 SELECT ... WHERE ... 逻辑结构的紧凑封装。这里要计算常数，
    // Calcite 的策略是将这组常数表达式伪装成一个标准 SQL 的 SELECT 投影列。
    final RexProgramBuilder programBuilder =
        new RexProgramBuilder(rowType, rexBuilder);
    // 批量注册常数表达式为输出投影列。
    for (RexNode node : constExps) {
      programBuilder.addProject(
          node, "c" + programBuilder.getProjectList().size());
    }
    // Calcite 内部逻辑层用的是 SQL 类型系统（如 SqlTypeName.BIGINT），但在生成 Java 源码时，必须知道它对应的 Java 类型（如 long.class 或 Long.class）。这一步确保类型转换网关可用。
    final RelDataTypeFactory typeFactory = rexBuilder.getTypeFactory();
    final JavaTypeFactory javaTypeFactory = typeFactory instanceof JavaTypeFactory
        ? (JavaTypeFactory) typeFactory
        : new JavaTypeFactoryImpl(typeFactory.getTypeSystem());
    // 初始化一个 LINQ4J 框架的代码块构建器。
    final BlockBuilder blockBuilder = new BlockBuilder();
    // 在内存 AST 中定义两个 Java 变量引用。
    // root0_：代表一个纯粹的 Object 类型的入参，变量名叫 "root0"。
    // root_：代表 Calcite 标准的 DataContext 上下文变量（通常变量名叫 "root"）。
    final ParameterExpression root0_ =
        Expressions.parameter(Object.class, "root0");
    final ParameterExpression root_ = DataContext.ROOT;
    // 作用：向代码沙盒里注入第一行货真价实的 Java 代码。
    // 最终生成的 Java 文本效果：
    // final org.apache.calcite.DataContext root = (org.apache.calcite.DataContext) root0;
    blockBuilder.add(
        Expressions.declare(
            Modifier.FINAL, root_,
            Expressions.convert_(root0_, DataContext.class)));
    // 设定 SQL 兼容性标准（采用默认标准），并从刚才的 Builder 中打包、冻结出一个不可变的 RexProgram 逻辑程序对象。
    final SqlConformance conformance = SqlConformanceEnum.DEFAULT;
    final RexProgram program = programBuilder.getProgram();
    // 调用翻译器静态方法。
    // 它会深度遍历 program 里的那些常数表达式，把每一个逻辑算子（如加减乘除、函数、Cast）转译为对应 Java 的原生计算表达式
    // 在转译过程中，表达式运行所需的中间变量声明，都会被自动、顺手追加到 blockBuilder 中。返回的 expressions 列表里则保存了每个常数表达式最终计算结果的 Java 变量指针。
    final List<Expression> expressions =
        RexToLixTranslator.translateProjects(program, javaTypeFactory,
            conformance, blockBuilder, null, null, root_, getter, null);
    // 作用：在代码块末尾追加 return 语句，将所有表达式的结果打包成一个 Java 对象数组返回。
    blockBuilder.add(
        Expressions.return_(null,
            Expressions.newArrayInit(Object[].class, expressions)));
    // 把上面已经完工的 { ... } 代码块（blockBuilder.toBlock()），
    // 正式打包封装成一个完整的、合法的 Java 方法声明（MethodDeclaration）。
    final MethodDeclaration methodDecl =
        Expressions.methodDecl(Modifier.PUBLIC, Object[].class,
            BuiltInMethod.FUNCTION1_APPLY.method.getName(),
            ImmutableList.of(root0_), blockBuilder.toBlock());
    // 将内存中的抽象语法树（AST）正式渲染拉平，输出为真正的、人类可读的 Java 源代码字符串。
    String code = Expressions.toString(methodDecl);
    if (CalciteSystemProperty.DEBUG.value()) {
      Util.debugCode(System.out, code);
    }
    return code;
  }

  /**
   * Creates an {@link RexExecutable} that allows to apply the
   * generated code during query processing (filter, projection).
   *
   * @param rexBuilder Rex builder
   * @param exps Expressions
   * @param rowType describes the structure of the input row.
   */
  public static RexExecutable getExecutable(RexBuilder rexBuilder, List<RexNode> exps,
      RelDataType rowType) {
    final JavaTypeFactoryImpl typeFactory =
        new JavaTypeFactoryImpl(rexBuilder.getTypeFactory().getTypeSystem());
    final InputGetter getter = new DataContextInputGetter(rowType, typeFactory);
    final String code = compile(rexBuilder, exps, getter, rowType);
    return new RexExecutable(code, "generated Rex code");
  }

  /**
   * Do constant reduction using generated code.
   */
  @Override public void reduce(RexBuilder rexBuilder, List<RexNode> constExps,
      List<RexNode> reducedValues) {
    String code;
    try {
      code = compile(rexBuilder, constExps, (list, index, storageType) -> {
        throw new UnsupportedOperationException();
      });
    } catch (RuntimeException ex) {
      // Give up on reduction and return expressions unchanged.
      // This effectively moves the error from compile time to runtime.
      // We could give a warning here if there was a mechanism for warnings.
      reducedValues.addAll(constExps);
      return;
    }
    //常量表达式直接执行，这样直接的到结果，不用在数据库再执行一遍
    final RexExecutable executable = new RexExecutable(code, constExps);
    executable.setDataContext(dataContext);
    executable.reduce(rexBuilder, constExps, reducedValues);
  }

  /**
   * Implementation of
   * {@link org.apache.calcite.adapter.enumerable.RexToLixTranslator.InputGetter}
   * that reads the values of input fields by calling
   * <code>{@link org.apache.calcite.DataContext#get}("inputRecord")</code>.
   */
  private static class DataContextInputGetter implements InputGetter {
    private final RelDataTypeFactory typeFactory;
    private final RelDataType rowType;

    DataContextInputGetter(RelDataType rowType,
        RelDataTypeFactory typeFactory) {
      this.rowType = rowType;
      this.typeFactory = typeFactory;
    }

    @Override public Expression field(BlockBuilder list, int index, @Nullable Type storageType) {
      MethodCallExpression recFromCtx =
          Expressions.call(DataContext.ROOT,
              BuiltInMethod.DATA_CONTEXT_GET.method,
              Expressions.constant("inputRecord"));
      Expression recFromCtxCasted =
          EnumUtils.convert(recFromCtx, Object[].class);
      IndexExpression recordAccess =
          Expressions.arrayIndex(recFromCtxCasted, Expressions.constant(index));
      if (storageType == null) {
        final RelDataType fieldType =
            rowType.getFieldList().get(index).getType();
        storageType = ((JavaTypeFactory) typeFactory).getJavaClass(fieldType);
      }
      return EnumUtils.convert(recordAccess, storageType);
    }
  }
}
