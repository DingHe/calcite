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

import org.apache.calcite.linq4j.Enumerator;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Utilities for converting between {@link Expression}, {@link Type} and
 * {@link Class}.
 *
 * @see Primitive
 */
public abstract class Types {
  private Types() {}

  /**
   * Creates a type with generic parameters.
   */
  public static Type of(Type type, Type... typeArguments) {
    if (typeArguments.length == 0) {
      return type;
    }
    return new ParameterizedTypeImpl(type, toList(typeArguments), null);
  }

  /**
   * Returns the element type of a {@link Collection}, {@link Iterable}
   * (including {@link org.apache.calcite.linq4j.Queryable Queryable} and
   * {@link org.apache.calcite.linq4j.Enumerable Enumerable}), {@link Iterator},
   * {@link Enumerator}, or an array.
   *
   * <p>Returns null if the type is not one of these.
   */
  public static @Nullable Type getElementType(Type type) {
    if (type instanceof ArrayType) {
      return ((ArrayType) type).getComponentType();
    }
    if (type instanceof GenericArrayType) {
      return ((GenericArrayType) type).getGenericComponentType();
    }
    Class clazz = toClass(type);
    if (clazz.isArray()) {
      return clazz.getComponentType();
    }
    if (Collection.class.isAssignableFrom(clazz)
        || Iterable.class.isAssignableFrom(clazz)
        || Iterator.class.isAssignableFrom(clazz)
        || Enumerator.class.isAssignableFrom(clazz)) {
      if (type instanceof ParameterizedType) {
        return ((ParameterizedType) type).getActualTypeArguments()[0];
      }
      return Object.class;
    }
    return null;
  }

  /**
   * Returns a list backed by a copy of an array. The contents of the list
   * will not change even if the contents of the array are subsequently
   * modified.
   */
  private static <T> List<T> toList(T[] ts) {
    switch (ts.length) {
    case 0:
      return Collections.emptyList();
    case 1:
      return Collections.singletonList(ts[0]);
    default:
      return Arrays.asList(ts.clone());
    }
  }

  static Field getField(String fieldName, Class clazz) {
    try {
      return clazz.getField(fieldName);
    } catch (NoSuchFieldException e) {
      throw new RuntimeException(
          "Unknown field '" + fieldName + "' in class " + clazz, e);
    }
  }
  // 任务是：给定一个字段名和类型，返回一个能够访问该字段的统一描述符（PseudoField）。
  // fieldName: 想要获取的字段名称（如 "name" 或 "length"）。
  // type: 字段所属的宿主类型。
  static PseudoField getField(String fieldName, Type type) {
    if (type instanceof RecordType) {
      return getRecordField(fieldName, (RecordType) type);
    } else if (type instanceof Class && ((Class) type).isArray()) {
      return getSystemField(fieldName, (Class) type);
    } else {
      return field(getField(fieldName, toClass(type)));
    }
  }
  // 根据字段名称从给定的记录类型中查找对应的字段元数据。
  // fieldName: 参数 1，想要查找的字段的字符串名称（例如 "userId"）。
  // type: 参数 2，要从中进行查找的容器类型（即我们之前提到的 RecordType 或 SyntheticRecordType）。
  private static RecordField getRecordField(String fieldName, RecordType type) {
    for (RecordField field : type.getRecordFields()) {
      if (field.getName().equals(fieldName)) {
        return field;
      }
    }
    throw new RuntimeException(
        "Unknown field '" + fieldName + "' in type " + type);
  }
  // 主要作用是为 Java 数组类型创建一个特殊的、虚拟的“长度（length）”字段元数据。
  private static RecordField getSystemField(final String fieldName,
      final Class clazz) {
    // The "length" field of an array does not appear in Class.getFields().
    return new ArrayLengthRecordField(fieldName, clazz);
  }
  // java.lang.reflect.Type 是一个高级接口，包含了所有的类型（如普通类、泛型类、类型变量等）。
  // 而 java.lang.Class 则是它的一个子类，代表运行时的具体类。
  // 方法的核心作用是：通过递归和类型检查，将复杂的 Java 类型（Type）强制收敛或降级为最接近的物理类（Class）。
  public static Class toClass(Type type) {
    // 如果传入的 type 本身就已经是一个 Class 对象（例如 String.class 或 Integer.class），则不需要任何转换，直接强转并返回。
    if (type instanceof Class) {
      return (Class) type;
    }
    // ParameterizedType 代表带有泛型的类型，例如 List<String>。
    if (type instanceof ParameterizedType) {
      return toClass(((ParameterizedType) type).getRawType());
    }
    // 处理类型变量（泛型参数）
    // TypeVariable 代表泛型中的变量，例如 T 或 K, V。
    if (type instanceof TypeVariable) {
      TypeVariable typeVariable = (TypeVariable) type;
      // getBounds() 方法获取该变量的“上界”。
      return toClass(typeVariable.getBounds()[0]);
    }
    throw new RuntimeException("unsupported type " + type); // TODO:
  }

  static Class[] toClassArray(Collection<Type> types) {
    List<Class> classes = new ArrayList<>();
    for (Type type : types) {
      classes.add(toClass(type));
    }
    return classes.toArray(new Class[0]);
  }

  public static Class[] toClassArray(Iterable<? extends Expression> arguments) {
    List<Class> classes = new ArrayList<>();
    for (Expression argument : arguments) {
      classes.add(toClass(argument.getType()));
    }
    return classes.toArray(new Class[0]);
  }

  /**
   * Returns the component type of an array.
   */
  public static @Nullable Type getComponentType(Type type) {
    if (type instanceof Class) {
      return ((Class) type).getComponentType();
    }
    if (type instanceof ArrayType) {
      return ((ArrayType) type).getComponentType();
    }
    if (type instanceof GenericArrayType) {
      return ((GenericArrayType) type).getGenericComponentType();
    }
    if (type instanceof ParameterizedType) {
      return getComponentType(((ParameterizedType) type).getRawType());
    }
    if (type instanceof TypeVariable) {
      TypeVariable typeVariable = (TypeVariable) type;
      return getComponentType(typeVariable.getBounds()[0]);
    }
    return null; // not an array type
  }

  static Type getComponentTypeN(Type type) {
    for (;;) {
      Type componentType = getComponentType(type);
      if (componentType == null) {
        return type;
      }
      type = componentType;
    }
  }

  public static Type box(Type type) {
    return Primitive.box(type);
  }

  public static Type unbox(Type type) {
    return Primitive.unbox(type);
  }

  static String className(Type type) {
    if (type instanceof ArrayType) {
      return className(((ArrayType) type).getComponentType()) + "[]";
    }
    if (!(type instanceof Class)) {
      return type.toString();
    }
    Class clazz = (Class) type;
    if (clazz.isArray()) {
      return className(clazz.getComponentType()) + "[]";
    }
    String className = clazz.getName();
    if (!clazz.isPrimitive()
        && clazz.getPackage() != null
        && clazz.getPackage().getName().equals("java.lang")) {
      return className.substring("java.lang.".length());
    }
    return className.replace('$', '.');
  }

  public static boolean isAssignableFrom(Type type0, Type type) {
    return toClass(type0).isAssignableFrom(toClass(type));
  }

  public static boolean isArray(Type type) {
    return toClass(type).isArray();
  }

  public static Field nthField(int ordinal, Class clazz) {
    return clazz.getFields()[ordinal];
  }

  public static PseudoField nthField(int ordinal, Type clazz) {
    if (clazz instanceof RecordType) {
      RecordType recordType = (RecordType) clazz;
      return recordType.getRecordFields().get(ordinal);
    }
    return field(toClass(clazz).getFields()[ordinal]);
  }

  public static boolean allAssignable(boolean varArgs, Class[] parameterTypes,
      Class[] argumentTypes) {
    if (varArgs) {
      if (argumentTypes.length < parameterTypes.length - 1) {
        return false;
      }
    } else {
      if (parameterTypes.length != argumentTypes.length) {
        return false;
      }
    }
    for (int i = 0; i < argumentTypes.length; i++) {
      Class
          parameterType =
          !varArgs || i < parameterTypes.length - 1
              ? parameterTypes[i]
              : Object.class;
      if (!assignableFrom(parameterType, argumentTypes[i])) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns whether a parameter is assignable from an argument by virtue
   * of (a) sub-classing (e.g. Writer is assignable from PrintWriter) and (b)
   * up-casting (e.g. int is assignable from short).
   *
   * @param parameter Parameter type
   * @param argument Argument type
   *
   * @return Whether parameter can be assigned from argument
   */
  @SuppressWarnings("nullness")
  private static boolean assignableFrom(Class parameter, Class argument) {
    return parameter.isAssignableFrom(argument)
           || parameter.isPrimitive()
        && argument.isPrimitive()
        && Primitive.of(parameter).assignableFrom(Primitive.of(argument));
  }

  /**
   * Finds a method of a given name that accepts a given set of arguments.
   * Includes in its search inherited methods and methods with wider argument
   * types.
   *
   * @param clazz Class against which method is invoked
   * @param methodName Name of method
   * @param argumentTypes Types of arguments
   *
   * @return A method with the given name that matches the arguments given
   * @throws RuntimeException if method not found
   */
  public static Method lookupMethod(Class clazz, String methodName,
      Class... argumentTypes) {
    try {
      return clazz.getMethod(methodName, argumentTypes);
    } catch (NoSuchMethodException e) {
      for (Method method : clazz.getMethods()) {
        if (method.getName().equals(methodName) && allAssignable(
            method.isVarArgs(), method.getParameterTypes(), argumentTypes)) {
          return method;
        }
      }
      throw new RuntimeException("while resolving method '" + methodName
          + Arrays.toString(argumentTypes) + "' in class " + clazz, e);
    }
  }

  /**
   * Finds a constructor of a given class that accepts a given set of
   * arguments. Includes in its search methods with wider argument types.
   *
   * @param type Class against which method is invoked
   * @param argumentTypes Types of arguments
   *
   * @return A method with the given name that matches the arguments given
   * @throws RuntimeException if method not found
   */
  public static Constructor lookupConstructor(Type type,
      Class... argumentTypes) {
    final Class clazz = toClass(type);
    Constructor[] constructors = clazz.getDeclaredConstructors();
    for (Constructor constructor : constructors) {
      if (allAssignable(constructor.isVarArgs(),
          constructor.getParameterTypes(), argumentTypes)) {
        return constructor;
      }
    }
    if (constructors.length == 0 && argumentTypes.length == 0) {
      try {
        return clazz.getConstructor();
      } catch (NoSuchMethodException e) {
        // ignore
      }
    }
    throw new RuntimeException(
        "while resolving constructor in class " + type + " with types " + Arrays
            .toString(argumentTypes));
  }

  public static Field lookupField(Type type, String name) {
    final Class clazz = toClass(type);
    try {
      return clazz.getField(name);
    } catch (NoSuchFieldException e) {
      throw new RuntimeException("while resolving field in class " + type);
    }
  }

  public static void discard(Object o) {
    if (false) {
      discard(o);
    }
  }

  /**
   * Returns the most restrictive type that is assignable from all given
   * types.
   */
  static Type gcd(Type... types) {
    // TODO: improve this
    if (types.length == 0) {
      return Object.class;
    }
    Type best = types[0];
    Primitive bestPrimitive = Primitive.of(best);
    if (bestPrimitive != null) {
      for (int i = 1; i < types.length; i++) {
        final Primitive primitive = Primitive.of(types[i]);
        if (primitive == null) {
          return Object.class;
        }
        if (primitive.assignableFrom(bestPrimitive)) {
          bestPrimitive = primitive;
        } else if (bestPrimitive.assignableFrom(primitive)) {
          // ok
        } else if (bestPrimitive == Primitive.CHAR
                   || bestPrimitive == Primitive.BYTE) {
          // 'char' and 'byte' are problematic, because they don't
          // assign to each other. 'char' can't even assign to
          // 'short'. Before we give up, try one last time with 'int'.
          bestPrimitive = Primitive.INT;
          --i;
        } else {
          return Object.class;
        }
      }
      return requireNonNull(bestPrimitive.primitiveClass);
    } else {
      for (int i = 1; i < types.length; i++) {
        if (types[i] != types[0]) {
          return Object.class;
        }
      }
    }
    return types[0];
  }

  /**
   * Wraps an expression in a cast if it is not already of the desired type,
   * or cannot be implicitly converted to it.
   *
   * @param returnType Desired type
   * @param expression Expression
   *
   * @return Expression of desired type
   */
  public static Expression castIfNecessary(Type returnType,
      Expression expression) {
    final Type type = expression.getType();
    if (!needTypeCast(type, returnType)) {
      return expression;
    }
    if (returnType instanceof Class
        && Number.class.isAssignableFrom((Class) returnType)
        && type instanceof Class
        && Number.class.isAssignableFrom((Class) type)) {
      // E.g.
      //   Integer foo(BigDecimal o) {
      //     return o.intValue();
      //   }
      return Expressions.unbox(expression, requireNonNull(Primitive.ofBox(returnType)));
    }
    if (Primitive.is(returnType) && !Primitive.is(type)) {
      // E.g.
      //   int foo(Object o) {
      //     return ((Integer) o).intValue();
      //   }
      return Expressions.unbox(
          Expressions.convert_(expression, Types.box(returnType)),
          requireNonNull(Primitive.of(returnType)));
    }
    if (!Primitive.is(returnType) && Primitive.is(type)) {
      // E.g.
      //   Short foo(Object o) {
      //     return (short) (int) o;
      //   }
      return Expressions.convert_(expression,
          Types.unbox(returnType));
    }
    return Expressions.convert_(expression, returnType);
  }

  /**
   * When trying to cast/convert a {@code Type} to another {@code Type},
   * it is necessary to pre-check whether the cast operation is needed.
   * We summarize general exceptions, including:
   *
   * <ol>
   *   <li>target Type {@code toType} equals with original Type {@code fromType}</li>
   *   <li>target Type can be assignable from original Type</li>
   *   <li>target Type is an instance of {@code RecordType},
   *   since the mapping Java Class might not generated yet</li>
   * </ol>
   *
   * @param fromType original type
   * @param toType   target type
   * @return Whether a cast operation is needed
   */
  public static boolean needTypeCast(Type fromType, Type toType) {
    return !(fromType.equals(toType)
        || toType instanceof RecordType
        || isAssignableFrom(toType, fromType));
  }

  public static PseudoField field(final Field field) {
    return new ReflectedPseudoField(field);
  }

  static Class arrayClass(Type type) {
    // REVIEW: Is there a way to do this without creating an instance? We
    //  just need the inverse of Class.getComponentType().
    return Array.newInstance(toClass(type), 0).getClass();
  }

  static Type arrayType(Type type, int dimension) {
    for (int i = 0; i < dimension; i++) {
      type = arrayType(type);
    }
    return type;
  }

  static Type arrayType(Type type) {
    if (type instanceof Class) {
      Class clazz = (Class) type;

      // REVIEW: Is there a way to do this without creating an instance?
      //   We just need the inverse of Class.getComponentType().
      return Array.newInstance(clazz, 0).getClass();
    }
    return new ArrayType(type);
  }

  public static Type stripGenerics(Type type) {
    if (type instanceof GenericArrayType) {
      final Type componentType =
          ((GenericArrayType) type).getGenericComponentType();
      return new ArrayType(stripGenerics(componentType));
    } else if (type instanceof ParameterizedType) {
      return ((ParameterizedType) type).getRawType();
    } else {
      return type;
    }
  }

  /** Implementation of {@link ParameterizedType}. */
  static class ParameterizedTypeImpl implements ParameterizedType {
    private final Type rawType;
    private final List<Type> typeArguments;
    private final @Nullable Type ownerType;

    ParameterizedTypeImpl(Type rawType, List<Type> typeArguments,
        @Nullable Type ownerType) {
      super();
      this.rawType = rawType;
      this.typeArguments = typeArguments;
      this.ownerType = ownerType;
      assert rawType != null;
      for (Type typeArgument : typeArguments) {
        assert typeArgument != null;
      }
    }

    @Override public String toString() {
      final StringBuilder buf = new StringBuilder();
      buf.append(className(rawType));
      buf.append("<");
      int i = 0;
      for (Type typeArgument : typeArguments) {
        if (i++ > 0) {
          buf.append(", ");
        }
        buf.append(className(typeArgument));
      }
      buf.append(">");
      return buf.toString();
    }

    @Override public Type[] getActualTypeArguments() {
      return typeArguments.toArray(new Type[0]);
    }

    @Override public Type getRawType() {
      return rawType;
    }

    @Override public @Nullable Type getOwnerType() {
      return ownerType;
    }
  }

  /**
   * Base class for record-like types that do not mapped to (currently
   * loaded) Java {@link Class} objects. Gives the opportunity to generate
   * code that references temporary types, then generate classes for those
   * types along with the code that uses them.
   */
  // RecordType 的核心作用是描述那些“尚未存在于 JVM 中的类”。
  // 在 SQL 查询优化和代码生成过程中，Calcite 经常需要创建临时的中间结构（例如：SELECT a, b + c FROM table 会产生一个包含两个字段的新结构）。
  // 非物理绑定：普通的 java.lang.Class 要求类必须已经加载到 JVM 中。而 RecordType 允许 Calcite 在生成真正的 Java 类文件之前，先在内存中构建出这个类的“蓝图”。
  // 代码生成导向：它给代码生成器提供了一个机会：先引用这些临时类型编写逻辑，最后再统一生成对应的物理 Java 类。
  public interface RecordType extends Type {
    // 获取该记录类型中包含的所有字段列表。
    List<RecordField> getRecordFields();
    // 获取该记录类型的名称。
    String getName();
  }

  /**
   * Field that belongs to a record.
   */
  // 标识该字段在逻辑上是否允许存储 null 值。
  public interface RecordField extends PseudoField {
    boolean nullable();
  }

  /**
   * Array type.
   */
  public static class ArrayType implements Type {
    private final Type componentType;
    private final boolean componentIsNullable;
    private final long maximumCardinality;

    public ArrayType(Type componentType, boolean componentIsNullable,
        long maximumCardinality) {
      this.componentType = componentType;
      this.componentIsNullable = componentIsNullable;
      this.maximumCardinality = Math.max(maximumCardinality, -1L);
    }

    public ArrayType(Type componentType) {
      this(componentType, !Primitive.is(componentType), -1L);
    }

    /** Returns the type of elements in the array. */
    public Type getComponentType() {
      return componentType;
    }

    /** Returns whether elements in the array may be null. */
    public boolean componentIsNullable() {
      return componentIsNullable;
    }

    /** Returns the maximum cardinality; -1 if there is no maximum. */
    public long maximumCardinality() {
      return maximumCardinality;
    }
  }

  /**
   * Map type.
   */
  public static class MapType implements Type {
    private final Type keyType;
    private final boolean keyIsNullable;
    private final Type valueType;
    private final boolean valueIsNullable;

    public MapType(Type keyType, boolean keyIsNullable,
        Type valueType, boolean valueIsNullable) {
      this.keyType = keyType;
      this.keyIsNullable = keyIsNullable;
      this.valueType = valueType;
      this.valueIsNullable = valueIsNullable;
    }

    /** Returns the type of keys. */
    public Type getKeyType() {
      return keyType;
    }

    /** Returns whether keys may be null. */
    public boolean keyIsNullable() {
      return keyIsNullable;
    }

    /** Returns the type of values. */
    public Type getValueType() {
      return valueType;
    }

    /** Returns whether values may be null. */
    public boolean valueIsNullable() {
      return valueIsNullable;
    }

  }
}
