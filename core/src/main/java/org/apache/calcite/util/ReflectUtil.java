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
package org.apache.calcite.util;

import org.apache.calcite.linq4j.function.Parameter;
import org.apache.calcite.linq4j.tree.Primitive;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

/**
 * Static utilities for Java reflection.
 */
// 实现动态多态分发（Multi-Method Dispatch）：
// 在 SQL 语法树（SqlNode）或关系表达式树（RelNode）的遍历中，Calcite 广泛使用 Visitor 模式。
// 然而，Java 原生只支持单分发（Single Dispatch）（即只根据运行时调用者对象的实际类型决定调用哪个方法，而方法参数的类型在编译期就已经确定）。
// ReflectUtil 允许开发者编写类似 visit(SqlSelect)、visit(SqlJoin) 的重载方法，并在运行期根据参数的实际运行时类型，动态匹配并调用到最精确、最具体的那个重载方法上。
// 打通基本类型与 ByteBuffer 的二进制序列化通道：
// 类中维护了原生基本类型（int, float, byte 等）与 java.nio.ByteBuffer 读写方法（get/put）的动态映射，用于底层数据在内存缓冲中的快速存取和传输。
public abstract class ReflectUtil {
  //~ Static fields/initializers ---------------------------------------------
  // 存储基本类型（Primitive）到其对应的包装类（Boxing Class）的映射字典。
  // 映射内容：如 int.class (Integer.TYPE) -> Integer.class。
  private static Map<Class, Class> primitiveToBoxingMap;
  // 存储基本类型到 ByteBuffer 中对应的“绝对读取（Absolute Get）”方法的映射。
  // 映射内容：如 int.class -> ByteBuffer.getMethod("getInt", int.class)。特别处理了 boolean.class，在底层由于没有 getBoolean，被统一映射到了 get(int index)（读字节）方法上。
  private static Map<Class, Method> primitiveToByteBufferReadMethod;
  // 存储基本类型到 ByteBuffer 中对应的“绝对写入（Absolute Put）”方法的映射。
  // 如 double.class -> ByteBuffer.getMethod("putDouble", int.class, double.class)。同样将 boolean.class 转换为字节写入。
  private static Map<Class, Method> primitiveToByteBufferWriteMethod;

  // 核心作用是：在类加载时，通过反射技术自动扫描 Java 的 ByteBuffer 类，建立起“原生基本类型”到“高效读写方法”的映射字典，并缓存起来以供性能优化。
  static {
    // 建立基本类型装箱映射（Primitive to Boxing Map）
    // XXXX.TYPE 是什么：在 Java 中，基本类型（如 int）不是对象，但它们有对应的类元数据表示。Integer.TYPE 实际上等价于 int.class。
    primitiveToBoxingMap = new HashMap<>();
    primitiveToBoxingMap.put(Boolean.TYPE, Boolean.class);
    primitiveToBoxingMap.put(Byte.TYPE, Byte.class);
    primitiveToBoxingMap.put(Character.TYPE, Character.class);
    primitiveToBoxingMap.put(Double.TYPE, Double.class);
    primitiveToBoxingMap.put(Float.TYPE, Float.class);
    primitiveToBoxingMap.put(Integer.TYPE, Integer.class);
    primitiveToBoxingMap.put(Long.TYPE, Long.class);
    primitiveToBoxingMap.put(Short.TYPE, Short.class);

    primitiveToByteBufferReadMethod = new HashMap<>();
    primitiveToByteBufferWriteMethod = new HashMap<>();
    // 动态反射扫描 ByteBuffer 读写方法
    Method[] methods = ByteBuffer.class.getDeclaredMethods();
    for (Method method : methods) {
      Class[] paramTypes = method.getParameterTypes();
      // 扫描与筛选“绝对读取（Absolute Get）”方法
      if (method.getName().startsWith("get")) {
        // 如果一个 get 方法返回的不是基本类型（比如返回的是 ByteBuffer 本身或者某个对象），说明它不是我们想要的获取数据的工具方法，直接跳过（continue）。
        if (!method.getReturnType().isPrimitive()) {
          continue;
        }
        // ByteBuffer 的 get 方法有两种：
        // 相对读取：get() 自动读取当前指针位置，无参数。
        // 绝对读取：getInt(int index) 读取指定索引位置的数据，有且仅有 1 个 int 型参数。
        // 这里的过滤条件确保了只保留带 1 个参数的“绝对读取”方法。
        if (paramTypes.length != 1) {
          continue;
        }
        primitiveToByteBufferReadMethod.put(
            method.getReturnType(), method);

        // special case for Boolean:  treat as byte
        // 当扫描到返回 byte 的 get(int) 方法时，同时将其绑定到 boolean.class 上。这意味着未来你需要从内存读取布尔值时，实际调用的是读字节的方法。
        if (method.getReturnType().equals(Byte.TYPE)) {
          primitiveToByteBufferReadMethod.put(Boolean.TYPE, method);
        }
      } else if (method.getName().startsWith("put")) {
        if (paramTypes.length != 2) {
          continue;
        }
        if (!paramTypes[1].isPrimitive()) {
          continue;
        }
        primitiveToByteBufferWriteMethod.put(paramTypes[1], method);

        // special case for Boolean:  treat as byte
        if (paramTypes[1].equals(Byte.TYPE)) {
          primitiveToByteBufferWriteMethod.put(Boolean.TYPE, method);
        }
      }
    }
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Uses reflection to find the correct java.nio.ByteBuffer "absolute get"
   * method for a given primitive type.
   *
   * @param clazz the Class object representing the primitive type
   * @return corresponding method
   */
  // 获取指定基本类型对应的 ByteBuffer 读取方法对象。
  public static Method getByteBufferReadMethod(Class clazz) {
    assert clazz.isPrimitive();
    return castNonNull(primitiveToByteBufferReadMethod.get(clazz));
  }

  /**
   * Uses reflection to find the correct java.nio.ByteBuffer "absolute put"
   * method for a given primitive type.
   *
   * @param clazz the Class object representing the primitive type
   * @return corresponding method
   */
  // 获取指定基本类型对应的 ByteBuffer 写入方法对象。
  public static Method getByteBufferWriteMethod(Class clazz) {
    assert clazz.isPrimitive();
    return castNonNull(primitiveToByteBufferWriteMethod.get(clazz));
  }

  /**
   * Gets the Java boxing class for a primitive class.
   *
   * @param primitiveClass representative class for primitive (e.g.
   *                       java.lang.Integer.TYPE)
   * @return corresponding boxing Class (e.g. java.lang.Integer)
   */
  // 获取基本类型的包装类 Class。
  public static Class getBoxingClass(Class primitiveClass) {
    assert primitiveClass.isPrimitive();
    return castNonNull(primitiveToBoxingMap.get(primitiveClass));
  }

  /**
   * Gets the name of a class with no package qualifiers; if it's an inner
   * class, it will still be qualified by the containing class (X$Y).
   *
   * @param c the class of interest
   * @return the unqualified name
   */
  // 获取不包含包名（Package）限定的前缀类名。
  // 通过 c.getName() 获取类的全限定名，找到最后一个点（.）的位置并向后截取。如果是内部类（如 A 类中的 B），则会保留为 A$B 的形式。
  public static String getUnqualifiedClassName(Class c) {
    String className = c.getName();
    int lastDot = className.lastIndexOf('.');
    if (lastDot < 0) {
      return className;
    }
    return className.substring(lastDot + 1);
  }

  /**
   * Composes a string representing a human-readable method name (with neither
   * exception nor return type information).
   *
   * @param declaringClass class on which method is defined
   * @param methodName     simple name of method without signature
   * @param paramTypes     method parameter types
   * @return unmangled method name
   */
  // 将方法的基本信息拼装成人类可读的字符串（不带返回值和异常信息）。
  // 使用 StringBuilder 拼装类名、方法名以及参数类型列表。输出格式类似于 org.apache.calcite.util.ReflectUtil.getUnmangledMethodName(java.lang.Class, java.lang.String)。
  public static String getUnmangledMethodName(
      Class declaringClass,
      String methodName,
      Class[] paramTypes) {
    StringBuilder sb = new StringBuilder();
    sb.append(declaringClass.getName());
    sb.append(".");
    sb.append(methodName);
    sb.append("(");
    for (int i = 0; i < paramTypes.length; ++i) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(paramTypes[i].getName());
    }
    sb.append(")");
    return sb.toString();
  }

  /**
   * Composes a string representing a human-readable method name (with neither
   * exception nor return type information).
   *
   * @param method method whose name is to be generated
   * @return unmangled method name
   */
  public static String getUnmangledMethodName(
      Method method) {
    return getUnmangledMethodName(
        method.getDeclaringClass(),
        method.getName(),
        method.getParameterTypes());
  }

  /**
   * Implements the {@link org.apache.calcite.util.Glossary#VISITOR_PATTERN} via
   * reflection. The basic technique is taken from <a
   * href="http://www.javaworld.com/javaworld/javatips/jw-javatip98.html">a
   * Javaworld article</a>. For an example of how to use it, see
   * {@code ReflectVisitorTest}.
   *
   * <p>Visit method lookup follows the same rules as if
   * compile-time resolution for VisitorClass.visit(VisiteeClass) were
   * performed. An ambiguous match due to multiple interface inheritance
   * results in an IllegalArgumentException. A non-match is indicated by
   * returning false.
   *
   * @param visitor         object whose visit method is to be invoked
   * @param visitee         object to be passed as a parameter to the visit
   *                        method
   * @param hierarchyRoot   if non-null, visitor method will only be invoked if
   *                        it takes a parameter whose type is a subtype of
   *                        hierarchyRoot
   * @param visitMethodName name of visit method, e.g. "visit"
   * @return true if a matching visit method was found and invoked
   */
  // 动态反射访问者模式（Reflective Visitor Pattern）的核心入口。
  // 传统的访问者模式（Visitor Pattern）需要被访问的对象实现 accept(Visitor v) 方法，并在内部硬编码调用 v.visit(this)，这被称为双分发（Double Dispatch）。
  // 而 Calcite 为了避免在数以百计的 AST 节点（如各个 SqlNode 或 RelNode 子类）中重复编写 accept 方法，
  // 通过 ReflectUtil.invokeVisitor 在运行期动态获取对象的实际类型，并利用反射路由到最匹配的重载方法上。
  public static boolean invokeVisitor(
      // 具体的访问者实体对象。
      ReflectiveVisitor visitor,
      // 被访问的元素对象。
      // 虽然它在编译期可能被声明为通用的 Object 或 SqlNode 基类，但该函数的核心任务就是在运行期获取它的实际运行时类型，并将其作为参数传递给对应的访问方法。
      Object visitee,
      // 类层次结构的根节点限制（可传 null）。
      // 这是一个安全边界契约。如果传入了某个特定的类或接口（例如 SqlNode.class），则要求路由到的具体访问方法，
      // 其第一个参数的类型必须是该根节点的子类或子接口。如果不满足，则拒绝调用。如果传入 null，则不做限制。
      Class hierarchyRoot,
      // 访问方法的名称。
      // 指定反射时去寻找哪个名字的方法，最常见的传参通常是 "visit"。
      String visitMethodName) {
    return invokeVisitorInternal(
        visitor,
        visitee,
        hierarchyRoot,
        visitMethodName);
  }

  /**
   * Shared implementation of the two forms of invokeVisitor.
   *
   * @param visitor         object whose visit method is to be invoked
   * @param visitee         object to be passed as a parameter to the visit
   *                        method
   * @param hierarchyRoot   if non-null, visitor method will only be invoked if
   *                        it takes a parameter whose type is a subtype of
   *                        hierarchyRoot
   * @param visitMethodName name of visit method, e.g. "visit"
   * @return true if a matching visit method was found and invoked
   */
  // 实现动态反射访问者模式（Reflective Visitor Pattern）的底层核心。
  // 它通过在运行期动态解析对象的实际类型，实现了优雅的多态动态分发（Polymorphic Dispatch），避免了传统 Visitor 模式中每个 AST 节点都需要硬编码 accept 方法的痛点。
  private static boolean invokeVisitorInternal(
      Object visitor,
      Object visitee,
      Class hierarchyRoot,
      String visitMethodName) {
    // 获取当前访问者对象的具体 Class 类型。
    Class<?> visitorClass = visitor.getClass();
    // 多态分发的最关键一步。
    // 获取被访问对象的实际运行时类型。
    // 通过它，程序可以突破编译期静态类型的限制，拿到最底层的真实物理类（如 SqlSelect.class）。
    Class visiteeClass = visitee.getClass();
    // 调用 Calcite 的核心分发路由算法（DFS 深度优先搜索）。
    // 它会拿着 visitorClass 去寻找一个名为 visitMethodName、且接收的第一个参数最完美契合 visiteeClass 的方法句柄（Method）。
    // 这个检索会递归扫描 visiteeClass 的父类和所有实现的接口。
    Method method =
        lookupVisitMethod(
            visitorClass,
            visiteeClass,
            visitMethodName);
    if (method == null) {
      return false;
    }

    if (hierarchyRoot != null) {
      Class paramType = method.getParameterTypes()[0];
      if (!hierarchyRoot.isAssignableFrom(paramType)) {
        return false;
      }
    }

    try {
      method.invoke(
          visitor,
          visitee);
    } catch (IllegalAccessException ex) {
      throw new RuntimeException(ex);
    } catch (InvocationTargetException ex) {
      // visit methods aren't allowed to have throws clauses,
      // so the only exceptions which should come
      // to us are RuntimeExceptions and Errors
      throw Util.throwAsRuntime(Util.causeOrSelf(ex));
    }
    return true;
  }

  /**
   * Looks up a visit method.
   *
   * @param visitorClass    class of object whose visit method is to be invoked
   * @param visiteeClass    class of object to be passed as a parameter to the
   *                        visit method
   * @param visitMethodName name of visit method
   * @return method found, or null if none found
   */
  public static @Nullable Method lookupVisitMethod(
      // 访问者（Visitor）的类元数据。
      // 指定要在哪个类（以及它的父类、实现的接口）中去搜寻处理方法。例如：SqlShuttle.class 或自定义的 MySqlVisitor.class。
      Class<?> visitorClass,
      // 被访问者（Visitee）的运行时具体类元数据。
      // 检索的关键依据。分发引擎将依据这个 Class 开始，顺着它的继承树（向上查找父类和接口）去匹配最精确的重载签名。
      Class<?> visiteeClass,
      // 期望的方法名称。
      // 指定要查找的方法名字符串（例如 "visit"）。
      String visitMethodName) {
    return lookupVisitMethod(
        visitorClass,
        visiteeClass,
        visitMethodName,
        Collections.emptyList());
  }

  /**
   * Looks up a visit method taking additional parameters beyond the
   * overloaded visitee type.
   *
   * @param visitorClass             class of object whose visit method is to be
   *                                 invoked
   * @param visiteeClass             class of object to be passed as a parameter
   *                                 to the visit method
   * @param visitMethodName          name of visit method
   * @param additionalParameterTypes list of additional parameter types
   * @return method found, or null if none found
   * @see #createDispatcher(Class, Class)
   */
  // 普通的 Visitor 模式（如 3 参数的重载）只支持传递被访问者本身（Visitee），而这个方法打破了单参数的限制，
  // 允许你的 visit 方法携带更多的业务上下文参数（例如 visit(SqlSelect node, int depth, List<String> paths)）。
  public static @Nullable Method lookupVisitMethod(
      Class<?> visitorClass,
      Class<?> visiteeClass,
      String visitMethodName,
      // 除被访问者外，方法所需的附加参数类型列表。
      // 指定重载方法后面必须跟着的、严格匹配（编译期固定）的额外参数槽位。例如，若传入 [int.class, List.class]，代表检索的方法签名必须类似于 visit(SomeVisitee v, int i, List l)。
      List<Class> additionalParameterTypes) {
    // Prepare an array to re-use in recursive calls.  The first argument
    // will have the visitee class substituted into it.
    // 这里故意创建了一个长度比附加参数多 1 的数组。因为在反射匹配中，访问方法的第一个参数（索引为 0 的槽位）永远留给动态变化的 visiteeClass。
    // 设计意图：正如代码中的 如注释所述，这个数组将在后续的递归树深度搜索（DFS）中被反复重用。
    // 每当算法向上追溯父类或接口时，只需直接将新的父类/接口 Class 覆盖写入 paramTypes[0] 槽位，即可直接传给下层匹配，
    // 避免了在庞大的类继承树递归过程中重复创建数组对象，极大地提升了 GC 性能和执行效率。
    Class<?>[] paramTypes = new Class[1 + additionalParameterTypes.size()];
    int iParam = 1;
    for (Class<?> paramType : additionalParameterTypes) {
      paramTypes[iParam++] = paramType;
    }

    // Cache Class to candidate Methods, to optimize the case where
    // the original visiteeClass has a diamond-shaped interface inheritance
    // graph. (This is common, for example, in JMI.) The idea is to avoid
    // iterating over a single interface's method more than once in a call.
    Map<Class<?>, @Nullable Method> cache = new HashMap<>();

    return lookupVisitMethod(
        visitorClass,
        visiteeClass,
        visitMethodName,
        paramTypes,
        cache);
  }
  // 这个 5 参数的私有重载方法 lookupVisitMethod 是整个多态动态分发的核心引擎。
  // 通过深度优先搜索（DFS）和动态树特化抉择（Specificity Resolution），在运行期模拟了 Java 编译期的重载解析机制。它不仅能沿着继承树向上寻找最合适的访问方法，还能在面对复杂的 Java 多重接口继承时，
  // 自动挑选出“最具体（Most Specific）”的方法，并提供完美的歧义校验。
  private static @Nullable Method lookupVisitMethod(
      final Class<?> visitorClass,
      final Class<?> visiteeClass,
      final String visitMethodName,
      // 高度复用的方法参数类型数组。paramTypes[0] 动态存放当前探测的 visiteeClass，之后的元素是固定的附加上下文参数类型。
      final Class<?>[] paramTypes,
      // 防菱形继承死循环的局部剪枝缓存。Key 是探测过的 visiteeClass，Value 是为该 Class 找到的最佳匹配方法（找不到则是 null）。
      final Map<Class<?>, @Nullable Method> cache) {
    // Use containsKey since the result for a Class might be null.
    // 首先去 cache 检索。注意这里必须用 containsKey，因为一个类找了一圈没找到匹配的方法会存入一个 null 值。如果命中缓存，直接返回。
    if (cache.containsKey(visiteeClass)) {
      return cache.get(visiteeClass);
    }

    Method candidateMethod = null;

    paramTypes[0] = visiteeClass;

    // 参数动态绑定与当前类精确匹配
    try {
      // 如果找到了，说明当前类有直击的重载方法，将其塞入缓存并直接返回，不再向上追溯（例如找到了 visit(SqlSelect) 就不用再去管 SqlNode 了）
      candidateMethod =
          visitorClass.getMethod(
              visitMethodName,
              paramTypes);

      cache.put(visiteeClass, candidateMethod);

      return candidateMethod;
    } catch (NoSuchMethodException ex) {
      // not found:  carry on with lookup
    }
    // 通过 getSuperclass() 拿到当前类的直接父类（若存在）。然后递归调用自身，将 superClass 作为新的探测节点传进去。
    Class<?> superClass = visiteeClass.getSuperclass();
    if (superClass != null) {
      candidateMethod =
          lookupVisitMethod(
              visitorClass,
              superClass,
              visitMethodName,
              paramTypes,
              cache);
    }
    // 拿到当前类直接实现的所有接口，进行 for 循环遍历，并再次递归调用自身去接口树里搜寻可用的访问方法。
    Class<?>[] interfaces = visiteeClass.getInterfaces();
    for (Class<?> anInterface : interfaces) {
      final Method method =
          lookupVisitMethod(visitorClass, anInterface,
              visitMethodName, paramTypes, cache);
      // 如果从某个接口分支里捞出了一个有效的 method，且我们之前在父类或其他接口分支里也捞到了一个 candidateMethod，
      // 并且这两个方法不是同一个方法（!method.equals(candidateMethod)），就必须进行以下裁决：
      if (method != null) {
        if (candidateMethod != null) {
          if (!method.equals(candidateMethod)) {
            Class<?> c1 = method.getParameterTypes()[0];
            Class<?> c2 = candidateMethod.getParameterTypes()[0];
            // c1.isAssignableFrom(c2) 意味着 c2 是 c1 的子类/子接口。
            // 也就是说，原候选方法 candidateMethod 的参数类型 c2 更加具体（More Specific），而新方法 method 的参数更倾向于泛化的基类。
            if (c1.isAssignableFrom(c2)) {
              // c2 inherits from c1, so keep candidateMethod
              // (which is more specific than method)
              continue;
             // 反过来，c2 是 c1 的父类。这意味着新方法 method 的参数 c1 比之前的候选方法更为具体。
            } else if (c2.isAssignableFrom(c1)) {
              // c1 inherits from c2 (method is more specific
              // than candidate method), so fall through
              // to set candidateMethod = method
            } else {
              // c1 and c2 are not directly related
              throw new IllegalArgumentException("dispatch ambiguity between "
                  + candidateMethod + " and " + method);
            }
          }
        }
        candidateMethod = method;
      }
    }

    cache.put(visiteeClass, candidateMethod);

    return candidateMethod;
  }

  /**
   * Creates a dispatcher for calls to {@link #lookupVisitMethod}. The
   * dispatcher caches methods between invocations.
   *
   * @param visitorBaseClazz Visitor base class
   * @param visiteeBaseClazz Visitee base class
   * @return cache of methods
   */
  // 利用“匿名内部类”现场实现该接口，并通过一个内部的 HashMap 为反射方法查找建立“高频本地缓存（Method Cache）”。
  // 在 SQL 优化过程中，算子树会经历千百次遍历。Java 原生反射（Class.getMethod 等）性能极低，且遍历其复杂的继承树、接口树去寻找最佳重载更是一个重工业操作。该方法通过建立缓存，确保任何一个节点类型的反射查找只发生一次，后续全部变成 $\mathcal{O}(1)$ 的 Map 查找。
  public static <R extends ReflectiveVisitor,
      E extends Object> ReflectiveVisitDispatcher<R, E> createDispatcher(
       // 规定了这个分派器要服务的访问者基类
      final Class<R> visitorBaseClazz,
      // 规定了要被遍历的节点基类（例如 RelNode.class 或 RexNode.class）。
      final Class<E> visiteeBaseClazz) {
    // 确保外部传入的 visitorBaseClazz 确实是 ReflectiveVisitor 接口的子类或实现类。
    assert ReflectiveVisitor.class.isAssignableFrom(visitorBaseClazz);
    assert Object.class.isAssignableFrom(visiteeBaseClazz);
    // 实例化匿名内部类与缓存结构定义
    return new ReflectiveVisitDispatcher<R, E>() {
      // 通过匿名内部类的方式，直接在内存中构建并返回一个 ReflectiveVisitDispatcher 接口的实例。
      // 核心缓存骨架。
      // 将方法的检索要素（visitorClass、visiteeClass、methodName、paramTypes）打包成一个 List<Object> 作为 Map 的 Key。
      // 由于 Java 中 List 的 equals 和 hashCode 是由其内部的所有元素共同决定的，所以这里利用 List 巧妙地充当了“复合键（Composite Key）”。
      final Map<List<Object>, @Nullable Method> map = new HashMap<>();

      // 标准的访问者通常只接收一个参数（即当前节点）。
      // 该方法提供了一个简化快捷入口，在内部将“额外参数”包装成一个空的只读列表 Collections.emptyList()，然后转发给下一个真正的核心四参数重载方法。
      @Override public @Nullable Method lookupVisitMethod(
          Class<? extends R> visitorClass,
          Class<? extends E> visiteeClass,
          String visitMethodName) {
        return lookupVisitMethod(
            visitorClass,
            visiteeClass,
            visitMethodName,
            Collections.emptyList());
      }
      // 带缓存的高性能检索（四参数重载方法）
      // 由于底层的 HashMap 不是线程安全的，在多线程优化场景下，加上 synchronized 锁住了当前分派器实例，确保并发修改缓存 map 时的线程安全性。
      // 将当前查找的四个核心维度组合，打包进一个不可变的 ImmutableList 中，生成当前检索的唯一“复合键（Key）”。
      @Override public synchronized @Nullable Method lookupVisitMethod(
          Class<? extends R> visitorClass,
          Class<? extends E> visiteeClass,
          String visitMethodName,
          List<Class> additionalParameterTypes) {
        final List<Object> key =
            ImmutableList.of(
                visitorClass,
                visiteeClass,
                visitMethodName,
                additionalParameterTypes);
        Method method = map.get(key);
        if (method == null) {
          // 这是对抗缓存穿透的精妙设计。
          // 如果 method 是 null，但 map 里其实包含了这个 Key，说明之前曾发起过反射查找，但结论是“该 Visitor 确实没写对应的 visit 方法”。此时什么都不做，直接跳过，直接返回 null。
          if (map.containsKey(key)) {
            // We already looked for the method and found nothing.
          } else {
            // 真正的反射发生点（只有在缓存彻底没有命中时才会执行一次）。调用外部工具类的原生反射方法，去遍历 Visitor 的阶层体系，计算出最精准匹配的 Java Method 实例。
            method =
                ReflectUtil.lookupVisitMethod(
                    visitorClass,
                    visiteeClass,
                    visitMethodName,
                    additionalParameterTypes);
            // 将辛辛苦苦查出来的反射结果（无论找到了还是 null）统统塞进缓存 map 中，供下一次访问享用，实现一劳永逸。
            map.put(key, method);
          }
        }
        return method;
      }

      @Override public boolean invokeVisitor(
          R visitor,
          E visitee,
          String visitMethodName) {
        return ReflectUtil.invokeVisitor(
            visitor,
            visitee,
            visiteeBaseClazz,
            visitMethodName);
      }
    };
  }

  /**
   * Creates a dispatcher for calls to a single multi-method on a particular
   * object.
   *
   * <p>Calls to that multi-method are resolved by looking for a method on
   * the runtime type of that object, with the required name, and with
   * the correct type or a subclass for the first argument, and precisely the
   * same types for other arguments.
   *
   * <p>For instance, a dispatcher created for the method
   *
   * <blockquote>String foo(Vehicle, int, List)</blockquote>
   *
   * <p>could be used to call the methods
   *
   * <blockquote>String foo(Car, int, List)<br>
   * String foo(Bus, int, List)</blockquote>
   *
   * <p>(because Car and Bus are subclasses of Vehicle, and they occur in the
   * polymorphic first argument) but not the method
   *
   * <blockquote>String foo(Car, int, ArrayList)</blockquote>
   *
   * <p>(only the first argument is polymorphic).
   *
   * <p>You must create an implementation of the method for the base class.
   * Otherwise throws {@link IllegalArgumentException}.
   *
   * @param returnClazz     Return type of method
   * @param visitor         Object on which to invoke the method
   * @param methodName      Name of method
   * @param arg0Clazz       Base type of argument zero
   * @param otherArgClasses Types of remaining arguments
   */
  // 核心设计目标是：通过工厂模式 + 匿名内部类，将复杂的反射方法查找（Lookup）、菱形继承冲突校验以及高开销的缓存管理完全封装起来。
  // 它返回一个干净的 MethodDispatcher 句柄，供上层代码以接近原生调用的性能和体验，执行单参数多态动态分发（Single-Parameter Dynamic Dispatch）。
  // <E extends Object>：代表多态靶心参数（即第 0 个参数，Visitee）的基类类型。
  // <T>：代表目标方法的返回值类型。
  public static <E extends Object, T> MethodDispatcher<T> createMethodDispatcher(
      // final Class<T> returnClazz：期望的目标方法返回值类型的 Class（例如 String.class），用于最终结果的类型转换。
      final Class<T> returnClazz,
      // 包含一组具体重载方法的访问者实例对象。
      final ReflectiveVisitor visitor,
      // 期望的多态方法名（例如 "foo"、"visit"）。
      final String methodName,
      // 多态参数的法定边界（根节点）。规定传入的第 0 个参数必须是该类的实例（如 Vehicle.class）。
      final Class<E> arg0Clazz,
      // 可变参数列表。指定目标重载方法后面紧跟的、非多态的其余固定参数类型（例如 int.class, List.class）。
      final Class... otherArgClasses) {
    // 将可变的其余参数类型数组包装为一个不可变的 ImmutableList。
    // 为后续的递归方法检索（lookupVisitMethod）准备固定的参数列表模板，并保证多线程下的线程安全。
    final List<Class> otherArgClassList =
        ImmutableList.copyOf(otherArgClasses);
    @SuppressWarnings({"unchecked" })
    // 调用内部的 createDispatcher 方法，为当前的 visitor 类型和靶心根类型 arg0Clazz 初始化/获取一个全局的调度器缓存中心。
    final ReflectiveVisitDispatcher<ReflectiveVisitor, E>
        dispatcher =
        createDispatcher(
            (Class<ReflectiveVisitor>) visitor.getClass(), arg0Clazz);
    // 返回匿名内部类并重写 invoke（高频运行期）
    return new MethodDispatcher<T>() {
      @Override public T invoke(@Nullable Object... args) {
        // 取出用户传入的第 0 个实参（靶心对象），调用下方的私有辅助方法 lookupMethod。
        Method method = lookupMethod(castNonNull(args[0]));
        try {
          // castNonNull is here because method.invoke can return null, and we don't know if
          // T is nullable
          final Object o = castNonNull(method.invoke(visitor, args));
          return returnClazz.cast(o);
        } catch (IllegalAccessException e) {
          throw new RuntimeException("While invoking method '" + method + "'",
              e);
        } catch (InvocationTargetException e) {
          final Throwable target = e.getTargetException();
          if (target instanceof RuntimeException) {
            throw (RuntimeException) target;
          }
          if (target instanceof Error) {
            throw (Error) target;
          }
          throw new RuntimeException("While invoking method '" + method + "'",
              e);
        }
      }

      private Method lookupMethod(final Object arg0) {
        // 边界防御契约校验。使用 isInstance 检查传入的靶心实例 arg0 是不是当初限定的基类（如 Vehicle）的子类。如果有人传了个不相干的 Dog 实例进来，直接无情抛出 IllegalArgumentException。
        if (!arg0Clazz.isInstance(arg0)) {
          throw new IllegalArgumentException();
        }
        Method method =
            dispatcher.lookupVisitMethod(
                visitor.getClass(),
                (Class<? extends E>) arg0.getClass(),
                methodName,
                otherArgClassList);
        if (method == null) {
          List<Class> classList = new ArrayList<>();
          classList.add(arg0Clazz);
          classList.addAll(otherArgClassList);
          throw new IllegalArgumentException("Method not found: " + methodName
              + "(" + classList + ")");
        }
        return method;
      }
    };
  }

  /** Derives the name of the {@code i}th parameter of a method. */
  public static String getParameterName(Method method, int i) {
    for (Annotation annotation : method.getParameterAnnotations()[i]) {
      if (annotation.annotationType() == Parameter.class) {
        return ((Parameter) annotation).name();
      }
    }
    return method.getParameters()[i].getName();
  }

  /** Derives whether the {@code i}th parameter of a method is optional. */
  public static boolean isParameterOptional(Method method, int i) {
    for (Annotation annotation : method.getParameterAnnotations()[i]) {
      if (annotation.annotationType() == Parameter.class) {
        return ((Parameter) annotation).optional();
      }
    }
    return false;
  }

  /** Returns whether a parameter of a given type could possibly have an
   * argument of a given type.
   *
   * <p>For example, consider method
   *
   * <blockquote>
   *   {@code foo(Object o, String s, int i, Number n, BigDecimal d}
   * </blockquote>
   *
   * <p>To which of those parameters could I pass a value that is an
   * instance of {@link java.util.HashMap}? The answer:
   *
   * <ul>
   *   <li>{@code o} yes,
   *   <li>{@code s} no ({@code String} is a final class),
   *   <li>{@code i} no,
   *   <li>{@code n} yes ({@code Number} is an interface, and {@code HashMap} is
   *       a non-final class, so I could create a sub-class of {@code HashMap}
   *       that implements {@code Number},
   *   <li>{@code d} yes ({@code BigDecimal} is a non-final class).
   * </ul>
   */
  public static boolean mightBeAssignableFrom(Class<?> parameterType,
      Class<?> argumentType) {
    // TODO: think about arrays (e.g. int[] and String[])
    if (parameterType == argumentType) {
      return true;
    }
    if (Primitive.is(argumentType)) {
      return false;
    }
    if (!parameterType.isInterface()
        && Modifier.isFinal(parameterType.getModifiers())) {
      // parameter is a final class
      // e.g. parameter String, argument Serializable
      // e.g. parameter String, argument Map
      // e.g. parameter String, argument Object
      // e.g. parameter String, argument HashMap
      return argumentType.isAssignableFrom(parameterType);
    } else {
      // parameter is an interface or non-final class
      if (!argumentType.isInterface()
          && Modifier.isFinal(argumentType.getModifiers())) {
        // argument is a final class
        // e.g. parameter Object, argument String
        // e.g. parameter Serializable, argument String
        return parameterType.isAssignableFrom(argumentType);
      } else {
        // argument is an interface or non-final class
        // e.g. parameter Map, argument Number
        return true;
      }
    }
  }

  /** Returns whether a member (constructor, method or field) is public. */
  public static boolean isPublic(Member member) {
    return Modifier.isPublic(member.getModifiers());
  }

  /** Returns whether a member (constructor, method or field) is static. */
  public static boolean isStatic(Member member) {
    return Modifier.isStatic(member.getModifiers());
  }

  //~ Inner Classes ----------------------------------------------------------

  /**
   * Can invoke a method on an object of type E with return type T.
   *
   * @param <T> Return type of method
   */
  // 多态动态分发器（Multi-Method Dispatcher）的最终执行手柄。
  // 它的核心设计目标是：将背后复杂的、高开销的反射方法查找（Lookup）与缓存逻辑完全隐藏起来，为上层调用者提供一个极其纯净、开箱即用的方法触发通道。
  // 在普通的 Java 代码中，如果你想调用一个对象的多态重载方法（例如根据传入的非固定子类执行不同的策略），你通常必须写很长的 if (obj instanceof ChildA) ... else if ...。
  // Calcite 通过 ReflectUtil.createMethodDispatcher(...) 可以在运行期动态生成这个接口的实现类（通常是一个匿名内部类）。
  // 一旦生成，上层业务代码只需要持有这个 MethodDispatcher 实例，在需要调用的地方直接执行 .invoke(args) 即可。
  // 泛型参数 <T> 指定通过该分发器执行的目标重载方法最终返回的数据类型。它利用了 Java 的泛型机制，避免了上层业务频繁进行强转（(TargetType) result）的麻烦。
  public interface MethodDispatcher<T> {
    /**
     * Invokes method on an object with a given set of arguments.
     *
     * @param args Arguments to method
     * @return Return value of method
     */
    // @Nullable Object... args 传递给目标重载方法的一组变长参数列表（Arguments）。
    // args[0]（第一个参数）：必须是触发多态分发的靶心对象（即被访问者 Visitee，如某个具体的 SqlNode 实例）。分发引擎在底层会通过 args[0].getClass() 拿到其运行时真实类型，去匹配最具体的重载方法。
    // args[1] 及之后的参数：对应目标方法所接收的那些固定的、不可变的其他业务上下文参数（如层次深度 int、路径列表 List 等）。
    T invoke(@Nullable Object... args);
  }
}
