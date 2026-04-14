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
package org.apache.calcite.schema.impl;

import org.apache.calcite.adapter.enumerable.CallImplementor;
import org.apache.calcite.adapter.enumerable.NullPolicy;
import org.apache.calcite.adapter.enumerable.ReflectiveCallNotNullImplementor;
import org.apache.calcite.adapter.enumerable.RexImpTable;
import org.apache.calcite.linq4j.function.SemiStrict;
import org.apache.calcite.linq4j.function.Strict;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.Function;
import org.apache.calcite.schema.ImplementableFunction;
import org.apache.calcite.schema.ScalarFunction;
import org.apache.calcite.schema.TableFunction;
import org.apache.calcite.sql.SqlOperatorBinding;

import com.google.common.collect.ImmutableMultimap;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Method;

import static org.apache.calcite.util.ReflectUtil.isStatic;
import static org.apache.calcite.util.Static.RESOURCE;

/**
* Implementation of {@link org.apache.calcite.schema.ScalarFunction}.
*/
// ScalarFunctionImpl 的本质是一个桥梁：
// 反射转换：它将一个普通的 Java 方法（Method）包装成 Calcite 能够理解的标量函数模型。
// 执行转换：它不仅定义了函数的元数据（参数、返回类型），还负责生成该函数在内存中执行时的 Java 代码实现（Implementor）。
// 空值策略处理：它能够识别 Java 方法上的注解（如 @Strict），从而自动处理 SQL 中的 NULL 值逻辑。
public class ScalarFunctionImpl extends ReflectiveFunctionBase
    implements ScalarFunction, ImplementableFunction {
  // 存储该函数的“执行器”。
  // 当 Calcite 将 SQL 编译为可执行的 Java 字节码时，这个属性决定了如何生成调用该 Java 方法的代码。它封装了反射调用逻辑，并处理了参数传递和空值判断。
  private final CallImplementor implementor;

  /** Private constructor. */
  private ScalarFunctionImpl(Method method, CallImplementor implementor) {
    super(method);
    this.implementor = implementor;
  }

  /**
   * Creates {@link org.apache.calcite.schema.ScalarFunction} for each method in
   * a given class.
   */
  // 扫描指定类中的所有公共方法，并为每个方法创建一个 ScalarFunction。
  // 当你有一个包含多个数学计算方法的类（例如 MyMathFunctions）时，你不需要手动一个个创建函数，只需调用 createAll(MyMathFunctions.class)，
  // 它就能自动把类里所有的 Java 方法提取出来，生成一个映射表（Multimap）。
  @Deprecated // to be removed before 2.0
  public static ImmutableMultimap<String, ScalarFunction> createAll(
      Class<?> clazz) {
    // 创建一个不可变多重映射（Multimap）的构建器
    // 为什么用 Multimap？ 因为 Java 支持方法重载（同名但参数不同）。
    // 使用 Multimap<String, ScalarFunction> 可以让同一个方法名对应多个不同的 ScalarFunction 实现。
    final ImmutableMultimap.Builder<String, ScalarFunction> builder =
        ImmutableMultimap.builder();
    for (Method method : clazz.getMethods()) {
      // 排除基础 Object 方法
      if (method.getDeclaringClass() == Object.class) {
        continue;
      }
      // 如果方法是 static 的，Calcite 可以直接通过类名调用，无需检查构造函数。
      if (!isStatic(method)
          && !classHasPublicZeroArgsConstructor(clazz)) {
        continue;
      }
      // 方法名作为 Key，生成的函数对象作为 Value 存入构建器
      final ScalarFunction function = create(method);
      builder.put(method.getName(), function);
    }
    return builder.build();
  }

  /**
   * Returns a map of all functions based on the methods in a given class.
   * It is keyed by method names and maps to both
   * {@link org.apache.calcite.schema.ScalarFunction}
   * and {@link org.apache.calcite.schema.TableFunction}.
   */
  public static ImmutableMultimap<String, Function> functions(Class<?> clazz) {
    final ImmutableMultimap.Builder<String, Function> builder =
        ImmutableMultimap.builder();
    for (Method method : clazz.getMethods()) {
      if (method.getDeclaringClass() == Object.class) {
        continue;
      }
      if (!isStatic(method)
          && !classHasPublicZeroArgsConstructor(clazz)) {
        continue;
      }
      final TableFunction tableFunction = TableFunctionImpl.create(method);
      if (tableFunction != null) {
        builder.put(method.getName(), tableFunction);
      } else {
        final ScalarFunction function = create(method);
        builder.put(method.getName(), function);
      }
    }
    return builder.build();
  }

  /**
   * Creates {@link org.apache.calcite.schema.ScalarFunction} from given class.
   *
   * <p>If a method of the given name is not found, or it does not suit,
   * returns {@code null}.
   *
   * @param clazz class that is used to implement the function
   * @param methodName Method name (typically "eval")
   * @return created {@link ScalarFunction} or null
   */
  public static @Nullable ScalarFunction create(Class<?> clazz, String methodName) {
    final Method method = findMethod(clazz, methodName);
    if (method == null) {
      return null;
    }
    return create(method);
  }

  /**
   * Creates {@link org.apache.calcite.schema.ScalarFunction} from given method.
   * When {@code eval} method does not suit, {@code null} is returned.
   *
   * @param method method that is used to implement the function
   * @return created {@link ScalarFunction} or null
   */
  public static ScalarFunction create(Method method) {
    // 核心逻辑：如果该方法是一个实例方法（非 static），Calcite 在运行时需要创建一个该类的对象来发起调用。
    // 硬性要求：该类必须具备以下两种构造函数之一：
    // 公共无参构造函数：最常见的 UDF 类形式。
    // FunctionContext 构造函数：允许 UDF 在初始化时获取环境上下文（如分配内存缓冲区或读取配置）。
    if (!isStatic(method)) {
      Class<?> clazz = method.getDeclaringClass();
      if (!classHasPublicZeroArgsConstructor(clazz)
          && !classHasPublicFunctionContextConstructor(clazz)) {
        throw RESOURCE.requireDefaultConstructor(clazz.getName()).ex();
      }
    }
    // 深层含义：这一步非常关键。它通过分析方法的签名、注解（如 @Strict）以及参数类型，生成一个 CallImplementor。
    // 这个执行器告诉 Calcite 的代码生成器：“在生成的 Java 代码中，应该如何写这一行调用逻辑（包括处理空值、转换参数类型等）。”
    CallImplementor implementor = createImplementor(method);
    return new ScalarFunctionImpl(method, implementor);
  }


  /**
   * Creates unsafe version of {@link ScalarFunction} from any method. The method
   * does not need to be static or belong to a class with default constructor. It is
   * the responsibility of the underlying engine to initialize the UDF object that
   * contain the method.
   *
   * @param method method that is used to implement the function
   */
  public static ScalarFunction createUnsafe(Method method) {
    CallImplementor implementor = createImplementor(method);
    return new ScalarFunctionImpl(method, implementor);
  }

  @Override public RelDataType getReturnType(RelDataTypeFactory typeFactory) {
    return typeFactory.createJavaType(method.getReturnType());
  }

  @Override public CallImplementor getImplementor() {
    return implementor;
  }
  // 在 Calcite 的 Enumerable 执行引擎中，SQL 并不是解释执行的，而是被翻译成 Java 代码并动态编译。
  // createImplementor 的任务就是创建一个“模板工具”（CallImplementor），它告诉编译器：
  // 核心动作：如何通过反射或直接调用来执行这个 method。
  // 防护逻辑：在调用这个方法之前，是否需要先检查参数是不是 NULL。
  private static CallImplementor createImplementor(final Method method) {
    // 检查方法名或类上的注解（如 @Strict）。
    // 如果是 STRICT（严格模式），只要参数里有一个 NULL，Calcite 生成的代码就会直接返回 NULL，根本不会去执行你的 Java 方法。
    // 这能避免你的 Java 代码报 NullPointerException
    final NullPolicy nullPolicy = getNullPolicy(method);
    // 调用 RexImpTable（Calcite 的算子实现大本营）的工厂方法
    return RexImpTable.createImplementor(
        // 负责最纯粹的一件事——“假设参数都不为空，生成调用该 Java 方法的代码”。它不关心逻辑判断，只负责物理上的方法触发。
        new ReflectiveCallNotNullImplementor(method), nullPolicy, false);
  }

  private static NullPolicy getNullPolicy(Method m) {
    if (m.getAnnotation(Strict.class) != null) {
      return NullPolicy.STRICT;
    } else if (m.getAnnotation(SemiStrict.class) != null) {
      return NullPolicy.SEMI_STRICT;
    } else if (m.getDeclaringClass().getAnnotation(Strict.class) != null) {
      return NullPolicy.STRICT;
    } else if (m.getDeclaringClass().getAnnotation(SemiStrict.class) != null) {
      return NullPolicy.SEMI_STRICT;
    } else {
      return NullPolicy.NONE;
    }
  }

  public RelDataType getReturnType(RelDataTypeFactory typeFactory,
      SqlOperatorBinding opBinding) {
    // Strict and semi-strict functions can return null even if their Java
    // functions return a primitive type. Because when one of their arguments
    // is null, they won't even be called.
    final RelDataType returnType = getReturnType(typeFactory);
    switch (getNullPolicy(method)) {
    case STRICT:
      for (RelDataType type : opBinding.collectOperandTypes()) {
        if (type.isNullable()) {
          return typeFactory.createTypeWithNullability(returnType, true);
        }
      }
      break;
    case SEMI_STRICT:
      return typeFactory.createTypeWithNullability(returnType, true);
    default:
      break;
    }
    return returnType;
  }
}
