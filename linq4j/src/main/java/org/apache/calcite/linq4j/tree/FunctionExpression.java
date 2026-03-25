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
package org.apache.calcite.linq4j.tree;

import org.apache.calcite.linq4j.function.Function;
import org.apache.calcite.linq4j.function.Functions;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Represents a strongly typed lambda expression as a data structure in the form
 * of an expression tree. This class cannot be inherited.
 *
 * @param <F> Function type
 */
// FunctionExpression 是一个非常核心且复杂的类。它继承自 LambdaExpression，代表一个强类型的 Lambda 表达式。
// FunctionExpression 的作用是将 Java 函数逻辑（Lambda）转化为一种可被分析、可被编译、可被执行的数据结构。
// 双重表示：它可以同时以“代码块（body）”和“已存在的函数对象（function）”的形式存在。
// 代码生成中心：它是真正负责生成 Java 匿名内部类代码的地方。它不仅生成核心业务逻辑，还自动处理 Java 泛型中的 Bridge Method（桥接方法） 以及基本类型的装箱/拆箱。
// 运行时编译/求值：它提供了 compile() 方法，可以在没有预先生成 Java 文件的情况下，通过解释器（Evaluator）直接运行表达式树。
// 动态代理：它能通过 JDK Proxy 将一个复杂的表达式树伪装成一个标准的函数式接口（如 Function1）。
public final class FunctionExpression<F extends Function<?>>
    extends LambdaExpression {
  // 一个预定义的函数对象。如果表达式直接由现有的函数创建，则此字段不为空。
  public final @Nullable F function;
  // 表达式的主体逻辑（语句块）。这是生成代码和求值的核心依据。
  public final @Nullable BlockStatement body;
  // Lambda 的参数列表（如 (x, y) 中的 x 和 y）。
  public final List<ParameterExpression> parameterList;
  // 缓存通过动态代理生成的函数实例。
  private @Nullable F dynamicFunction;
  /** Cached hash code for the expression. */
  // 缓存哈希值，因为表达式树的哈希计算非常耗时。
  private int hash;
  // 统一初始化入口，包含大量断言确保 type、parameterList 不为空，且 function 和 body 至少有一个。
  private FunctionExpression(Class<F> type, @Nullable F function, @Nullable BlockStatement body,
      List<ParameterExpression> parameterList) {
    super(ExpressionType.Lambda, type);
    assert type != null : "type should not be null";
    assert function != null || body != null
        : "both function and body should not be null";
    assert parameterList != null : "parameterList should not be null";
    this.function = function;
    this.body = body;
    this.parameterList = parameterList;
  }
  // 通过现有的函数对象创建表达式。
  public FunctionExpression(F function) {
    this((Class) function.getClass(), function, null, ImmutableList.of());
  }
  // 通过逻辑树创建表达式，这是 Calcite 最常用的方式。
  public FunctionExpression(Class<F> type, BlockStatement body,
      List<ParameterExpression> parameters) {
    this(type, null, body, parameters);
  }
  // 支持树的转换。
  // 它会先对自身进行 preVisit，然后递归处理 body，最后返回转换后的节点。
  @Override public Expression accept(Shuttle shuttle) {
    shuttle = shuttle.preVisit(this);
    BlockStatement body = this.body == null ? null : this.body.accept(shuttle);
    return shuttle.visit(this, body);
  }

  @Override public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
  // 返回一个 Invokable 接口。
  // 其内部实现是：创建一个 Evaluator，将传入的实参绑定到 parameterList 的变量名上，然后解释执行 body。
  public Invokable compile() {
    return args -> {
      final Evaluator evaluator = new Evaluator();
      for (int i = 0; i < args.length; i++) {
        evaluator.push(parameterList.get(i), args[i]);
      }
      return evaluator.evaluate(requireNonNull(body, "body"));
    };
  }
  // 动态代理的妙处：它起到了**适配器（Adapter）**的作用。它让一个通用的 Invokable 实例“伪装”成了上层需要的任何特定接口。
  public F getFunction() {
    if (function != null) {
      return function;
    }
    if (dynamicFunction == null) {
      final Invokable x = compile();

      ClassLoader classLoader = requireNonNull(getClass().getClassLoader());
      //noinspection unchecked
      dynamicFunction =
          (F) Proxy.newProxyInstance(classLoader,
              new Class[]{Types.toClass(type)},
              (proxy, method, args) -> x.dynamicInvoke(args));
    }
    return dynamicFunction;
  }
  // 核心作用是：将内存中的表达式树（Expression Tree）序列化为合法的 Java 源代码。
  // 为了让生成的代码能够无缝适配 Java 的泛型和基本类型（Primitive），它在这里处理了极其复杂的 Bridge Method（桥接方法） 生成逻辑。
  // 为什么要生成这三层？
  // Java 的泛型在运行时会被擦除为 Object。如果 Calcite 只生成一个 apply(int p1)，那么当它被当作 Function1<Integer, Integer> 调用时，由于签名不匹配，程序会崩溃。
  // 通过这种“俄罗斯套娃”式的生成方式，Calcite 确保了：
  // 兼容性：可以被任何标准的 Java 泛型框架调用（通过 Object 方法进入）。
  // 性能：在内部高频执行时，如果已知类型，可以直接调用最内层的强类型方法，避免频繁的装箱拆箱开销。
  @Override void accept(ExpressionWriter writer, int lprec, int rprec) {
    // "new Function1() {
    //    public Result apply(T1 p1, ...) {
    //        <body>
    //    }
    //    // bridge method
    //    public Object apply(Object p1, ...) {
    //        return apply((T1) p1, ...);
    //    }
    // }
    //
    // if any arguments are primitive there is an extra bridge method:
    //
    //  new Function1() {
    //    public double apply(double p1, int p2) {
    //      <body>
    //    }
    //    // box bridge method
    //    public Double apply(Double p1, Integer p2) {
    //      return apply(p1.doubleValue(), p2.intValue());
    //    }
    //    // bridge method
    //    public Object apply(Object p1, Object p2) {
    //      return apply((Double) p1, (Integer) p2);
    //    }
    // 原始类型参数。例如 int p1。用于生成最底层的业务逻辑方法
    List<String> params = new ArrayList<>();
    // 擦除后的参数。统一为 Object p1。这是为了满足 Java 泛型接口在字节码层面的要求。
    List<String> bridgeParams = new ArrayList<>();
    List<String> bridgeArgs = new ArrayList<>();
    // 装箱后的参数。例如 Integer p1。当业务方法包含基本类型时，需要这个中间层来对接泛型接口。
    List<String> boxBridgeParams = new ArrayList<>();

    List<String> boxBridgeArgs = new ArrayList<>();
    // 核心变量准备阶段
    for (ParameterExpression parameterExpression : parameterList) {

      final Type parameterType = parameterExpression.getType();
      final Type parameterBoxType = Types.box(parameterType);
      final String parameterBoxTypeName = Types.className(parameterBoxType);
      // 获取原始类型、包装类型（如 int -> Integer）
      params.add(parameterExpression.declString()); // 生成 "int p1"
      bridgeParams.add(parameterExpression.declString(Object.class)); // 生成 "Object p1"
      // // 生成强转语句，如 "(Integer) p1"
      bridgeArgs.add("(" + parameterBoxTypeName + ") "
          + parameterExpression.name);

      boxBridgeParams.add(parameterExpression.declString(parameterBoxType));
      // 生成拆箱语句，如 "p1.intValue()"
      boxBridgeArgs.add(parameterExpression.name
          + (Primitive.is(parameterType)
          ? "." + requireNonNull(Primitive.of(parameterType)).primitiveName + "Value()"
          : ""));
    }
    requireNonNull(body, "body");
    Type bridgeResultType = Functions.FUNCTION_RESULT_TYPES.get(this.type);
    if (bridgeResultType == null) {
      bridgeResultType = body.getType();
    }
    Type resultType2 = bridgeResultType;
    if (bridgeResultType == Object.class
        && !params.equals(bridgeParams)
        && !(body.getType() instanceof TypeVariable)) {
      resultType2 = body.getType();
    }
    String methodName = getAbstractMethodName();
    // 开始拼接字符串，生成类的声明。
    // 这会生成类似 new Function1() { 的代码。
    writer.append("new ")
        .append(type)
        .append("()")
        .begin(" {\n")
        // 第一层：业务逻辑方法 (Implementation Method)
        .append("public ")
        .append(Types.className(resultType2)) // 返回值类型
        .list(" " + methodName + "(",
                ", ", ") ", params) // 方法名和强类型参数
        .append(Blocks.toFunctionBlock(body)); // 写入具体的代码块内容

    // Generate an intermediate bridge method if at least one parameter is
    // primitive.
    final String bridgeResultTypeName =
        isAbstractMethodPrimitive()
            ? Types.className(bridgeResultType)
            : Types.className(Types.box(bridgeResultType));
    // 第二层：装箱桥接方法 (Box Bridge Method)
    // 触发条件：参数中包含基本类型（如 int）。
    // 作用：作为中转站，将包装类（Integer）拆箱后调用第一层的原始方法。
    if (!boxBridgeParams.equals(params)) {
      writer
          .append("public ")
          .append(bridgeResultTypeName)
          .list(" " + methodName + "(", ", ", ") ", boxBridgeParams)
          .begin("{\n")
          .list("return " + methodName + "(\n", ",\n", ");\n", boxBridgeArgs)
          .end("}\n");
    }

    // Generate a bridge method. Argument types are looser (as if every
    // type parameter is set to 'Object').
    //
    // Skip the bridge method if there are no arguments. It would have the
    // same overload as the regular method.
    // 第三层：泛型桥接方法 (Generic Bridge Method)
    // 触发条件：参数类型不是 Object（绝大多数情况都会触发）。
    // 作用：满足 Java 接口的类型擦除要求。当外部通过 Function.apply(Object) 调用时，由这个方法承接。
    if (!bridgeParams.equals(params)) {
      writer
        .append("public ")
        .append(bridgeResultTypeName)
        .list(" " + methodName + "(", ", ", ") ", bridgeParams)
        .begin("{\n")
        .list("return " + methodName + "(\n", ",\n", ");\n", bridgeArgs) // 强转后调用
        .end("}\n");
    }

    writer.end("}\n");
  }
  // 判断该 Lambda 表达式所实现的接口方法，其返回值类型是否为基本类型（如 int, double, boolean 等）。
  private boolean isAbstractMethodPrimitive() {
    Method method = getAbstractMethod();
    return Primitive.is(method.getReturnType());
  }
  // 获取该 Lambda 表达式所实现的接口方法的名称。
  private String getAbstractMethodName() {
    final Method abstractMethod = getAbstractMethod();
    return abstractMethod.getName();
  }
  // 核心任务是：从指定的接口（type）中寻找唯一的抽象方法（Abstract Method）。
  // 在 Java 中，Lambda 表达式必须对应一个函数式接口（Functional Interface），而函数式接口的定义就是“有且仅有一个抽象方法”的接口。
  // 此方法正是为了确定这个“入口方法”的名字和签名，以便后续生成代码。
  private Method getAbstractMethod() {
    // 确保当前的 type 确实是一个 Java 接口类。如果是一个普通类（Class）或者不是类类型，则直接跳过逻辑进入最后的异常抛出。
    if (type instanceof Class
        && ((Class) type).isInterface()) {
      // 获取所有声明的方法
      final List<Method> declaredMethods =
          Lists.newArrayList(((Class) type).getDeclaredMethods());
      // 过滤“合成方法” (Synthetic Methods)
      // 合成方法是由编译器自动生成的（例如为了支持内部类访问私有成员而生成的 access$000）。这些方法对用户是不可见的，也不是 Lambda 表达式应该实现的业务逻辑。
      declaredMethods.removeIf(m -> (m.getModifiers() & 0x00001000) != 0);
      // 唯一性验证与返回
      if (declaredMethods.size() == 1) {
        return declaredMethods.get(0);
      }
    }
    throw new IllegalStateException("Method not found, type = " + type);
  }

  @Override public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }

    FunctionExpression that = (FunctionExpression) o;

    if (body != null ? !body.equals(that.body) : that.body != null) {
      return false;
    }
    if (function != null ? !function.equals(that.function) : that.function
        != null) {
      return false;
    }
    if (!parameterList.equals(that.parameterList)) {
      return false;
    }

    return true;
  }

  @Override public int hashCode() {
    int result = hash;
    if (result == 0) {
      result = Objects.hash(nodeType, type, function, body, parameterList);
      if (result == 0) {
        result = 1;
      }
      hash = result;
    }
    return result;
  }

  /** Function that can be invoked with a variable number of arguments. */
  // Invokable 的作用是将一段编译好的或解析好的逻辑（通常是一个 LambdaExpression 或 MethodCallExpression）封装成一个可执行的单元。
  // 它允许在不知道具体参数类型和数量的情况下，通过统一的入口进行调用。
  public interface Invokable {
    // 参数 args (可变参数)：它接收一个 Object 数组，代表传递给该逻辑的实际参数。
    // 返回值 @Nullable Object：返回执行后的结果。
    @Nullable Object dynamicInvoke(@Nullable Object... args);
  }
}
