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

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.Function;
import org.apache.calcite.schema.FunctionContext;
import org.apache.calcite.schema.FunctionParameter;
import org.apache.calcite.util.ReflectUtil;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.apache.calcite.util.ReflectUtil.isPublic;

/**
 * Implementation of a function that is based on a method.
 * This class mainly solves conversion of method parameter types to {@code
 * List<FunctionParameter>} form.
 */
// 要负责将 Java 反射信息（Method 和 Class）桥接到 Calcite 的元数据系统（Function 和 FunctionParameter）中。
// 如果你想通过编写一个普通的 Java 方法来实现 SQL 函数，Calcite 内部就会利用这个类来理解你的方法有哪些参数、参数类型是什么以及如何调用。
// 是自动自动化元数据转换：
// 反射解析：它利用 Java 的反射机制（Reflection）读取 Java 方法的签名。
// 参数映射：它将 Java 方法的参数类型（如 int, String）自动转换为 Calcite 校验器所需的 FunctionParameter 列表。
//
public abstract class ReflectiveFunctionBase implements Function {
  /** Method that implements the function. */
  // 存储真正实现该函数逻辑的 Java 方法对象。
  // 在 SQL 执行阶段，Calcite 会通过这个 Method 对象进行反射调用或生成对应的字节码。
  public final Method method;
  /** Types of parameter for the function call. */
  // 存储该函数在 SQL 层面的参数元数据。
  // 用途：当校验器（Validator）检查 SQL 语句中的函数调用是否合法时，会读取这个列表来核对参数个数和类型。
  public final List<FunctionParameter> parameters;

  /**
   * Creates a ReflectiveFunctionBase.
   *
   * @param method Method that is used to get type information from
   */
  protected ReflectiveFunctionBase(Method method) {
    this.method = method;
    this.parameters = builder().addMethodParameters(method).build();
  }

  /**
   * Returns the parameters of this function.
   *
   * @return Parameters; never null
   */
  @Override public List<FunctionParameter> getParameters() {
    return parameters;
  }

  /**
   * Returns whether a class has a public constructor with zero arguments.
   *
   * @param clazz Class to verify
   * @return whether class has a public constructor with zero arguments
   */
  // 检查一个类是否具有公共的无参构造函数
  static boolean classHasPublicZeroArgsConstructor(Class<?> clazz) {
    for (Constructor<?> constructor : clazz.getConstructors()) {
      if (constructor.getParameterCount() == 0 && isPublic(constructor)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns whether a class has a public constructor with one argument
   * of type {@link FunctionContext}.
   *
   * @param clazz Class to verify
   * @return whether class has a public constructor with one FunctionContext
   * argument
   */
  // 检查类是否有接受一个 FunctionContext 参数的公共构造函数。
  static boolean classHasPublicFunctionContextConstructor(Class<?> clazz) {
    for (Constructor<?> constructor : clazz.getConstructors()) {
      if (constructor.getParameterCount() == 1
          && constructor.getParameterTypes()[0] == FunctionContext.class
          && isPublic(constructor)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Finds a method in a given class by name.
   *
   * @param clazz class to search method in
   * @param name name of the method to find
   * @return the first method with matching name or null when no method found
   */
  // 在指定的类中按名称查找第一个非桥接（non-bridge）方法。
  static @Nullable Method findMethod(Class<?> clazz, String name) {
    for (Method method : clazz.getMethods()) {
      if (method.getName().equals(name) && !method.isBridge()) {
        return method;
      }
    }
    return null;
  }

  /** Creates a ParameterListBuilder. */
  public static ParameterListBuilder builder() {
    return new ParameterListBuilder();
  }

  /** Helps build lists of
   * {@link org.apache.calcite.schema.FunctionParameter}. */
  // 参数构建器
  // 用于将 Java 方法参数转换为 Calcite 的 FunctionParameter 对象。
  public static class ParameterListBuilder {
    final List<FunctionParameter> builder = new ArrayList<>();

    public ImmutableList<FunctionParameter> build() {
      return ImmutableList.copyOf(builder);
    }

    public ParameterListBuilder add(final Class<?> type, final String name) {
      return add(type, name, false);
    }
    // 向构建器添加一个参数，默认是必选的。
    public ParameterListBuilder add(final Class<?> type, final String name,
        final boolean optional) {
      final int ordinal = builder.size();
      builder.add(
          new FunctionParameter() {
            @Override public String toString() {
              return ordinal + ": " + name + " " + type.getSimpleName()
                  + (optional ? "?" : "");
            }

            @Override public int getOrdinal() {
              return ordinal;
            }

            @Override public String getName() {
              return name;
            }

            @Override public RelDataType getType(RelDataTypeFactory typeFactory) {
              return typeFactory.createJavaType(type);
            }

            @Override public boolean isOptional() {
              return optional;
            }
          });
      return this;
    }

    public ParameterListBuilder addMethodParameters(Method method) {
      final Class<?>[] types = method.getParameterTypes();
      for (int i = 0; i < types.length; i++) {
        add(types[i], ReflectUtil.getParameterName(method, i),
            ReflectUtil.isParameterOptional(method, i));
      }
      return this;
    }
  }
}
