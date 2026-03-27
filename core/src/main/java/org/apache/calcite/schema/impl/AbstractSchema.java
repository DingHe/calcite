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

import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.schema.Function;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.SchemaVersion;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.Table;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Abstract implementation of {@link Schema}.
 *
 * <p>Behavior is as follows:
 * <ul>
 *   <li>The schema has no tables unless you override
 *       {@link #getTableMap()}.</li>
 *   <li>The schema has no functions unless you override
 *       {@link #getFunctionMultimap()}.</li>
 *   <li>The schema has no sub-schemas unless you override
 *       {@link #getSubSchemaMap()}.</li>
 *   <li>The schema is mutable unless you override
 *       {@link #isMutable()}.</li>
 *   <li>The name and parent schema are as specified in the constructor
 *       arguments.</li>
 * </ul>
 */
// AbstractSchema 的核心设计思想是模板模式（Template Pattern）。
// 减少重复代码：Schema 接口定义了许多方法（如 getTable, getFunctions 等），如果直接实现接口，每个方法都要写一遍逻辑。AbstractSchema 通过提供默认实现（通常是返回空集合）和受保护的辅助方法，让开发者只需关注自己感兴趣的部分。
// 基于 Map 的默认行为：它将复杂的查询逻辑（如按名称查找表、函数）统一转化为对底层 Map 或 Multimap 的操作。开发者只需覆盖（Override）对应的 get...Map() 方法即可。
// 作为默认实现：如果一个 Schema 本身不包含任何预定义的对象，它可以直接作为空 Schema 使用。
public class AbstractSchema implements Schema {
  public AbstractSchema() {
  }
  // 作用: 定义该模式是否可变（即是否允许添加/删除表等）。
  // 默认实现: 返回 true。如果你的数据源是只读的，需要覆盖此方法并返回 false。
  @Override public boolean isMutable() {
    return true;
  }
  // 作用: 返回该模式在特定版本下的快照。
  // 默认实现: 返回 this。表示默认不支持多版本版本控制，当前状态即为快照。
  @Override public Schema snapshot(SchemaVersion version) {
    return this;
  }
  // 作用: 生成在代码生成阶段引用此模式的表达式。
  // 说明: 它利用 Schemas.subSchemaExpression 工具类，根据父模式和当前类名构建引用路径。
  @Override public Expression getExpression(@Nullable SchemaPlus parentSchema, String name) {
    requireNonNull(parentSchema, "parentSchema");
    return Schemas.subSchemaExpression(parentSchema, name, getClass());
  }

  /**
   * Returns a map of tables in this schema by name.
   *
   * <p>The implementations of {@link #getTableNames()}
   * and {@link #getTable(String)} depend on this map.
   * The default implementation of this method returns the empty map.
   * Override this method to change their behavior.
   *
   * @return Map of tables in this schema by name
   */
  // 作用: 核心钩子方法。
  // 返回包含所有表的 Map。
  protected Map<String, Table> getTableMap() {
    return ImmutableMap.of();
  }
  // 作用: 获取所有表名。
  // 实现: 调用 getTableMap().keySet()。被标记为 final，强制要求通过 getTableMap() 来改变行为。
  @Override public final Set<String> getTableNames() {
    //noinspection RedundantCast
    return (Set<String>) getTableMap().keySet();
  }
  // 作用: 根据名称获取表对象。
  @Override public final @Nullable Table getTable(String name) {
    return getTableMap().get(name);
  }

  /**
   * Returns a map of types in this schema by name.
   *
   * <p>The implementations of {@link #getTypeNames()}
   * and {@link #getType(String)} depend on this map.
   * The default implementation of this method returns the empty map.
   * Override this method to change their behavior.
   *
   * @return Map of types in this schema by name
   */
  // 作用: 钩子方法。返回自定义类型的映射。
  protected Map<String, RelProtoDataType> getTypeMap() {
    return ImmutableMap.of();
  }
  // 作用: 根据名称获取数据类型原型。
  @Override public @Nullable RelProtoDataType getType(String name) {
    return getTypeMap().get(name);
  }
  // 作用: 获取所有类型的名称。
  @Override public Set<String> getTypeNames() {
    //noinspection RedundantCast
    return (Set<String>) getTypeMap().keySet();
  }

  /**
   * Returns a multi-map of functions in this schema by name.
   * It is a multi-map because functions are overloaded; there may be more than
   * one function in a schema with a given name (as long as they have different
   * parameter lists).
   *
   * <p>The implementations of {@link #getFunctionNames()}
   * and {@link Schema#getFunctions(String)} depend on this map.
   * The default implementation of this method returns the empty multi-map.
   * Override this method to change their behavior.
   *
   * @return Multi-map of functions in this schema by name
   */

  // 作用: 核心钩子方法。
  // 返回函数的多值映射（Multimap）。
  // 说明: 使用 Multimap 是因为 SQL 支持函数重载，同一个名字可能对应多个函数签名。
  protected Multimap<String, Function> getFunctionMultimap() {
    return ImmutableMultimap.of();
  }
  // 作用: 获取指定名称的函数集合。
  @Override public final Collection<Function> getFunctions(String name) {
    return getFunctionMultimap().get(name); // never null
  }
  // 作用: 获取所有函数的名称。
  @Override public final Set<String> getFunctionNames() {
    return getFunctionMultimap().keySet();
  }

  /**
   * Returns a map of sub-schemas in this schema by name.
   *
   * <p>The implementations of {@link #getSubSchemaNames()}
   * and {@link #getSubSchema(String)} depend on this map.
   * The default implementation of this method returns the empty map.
   * Override this method to change their behavior.
   *
   * @return Map of sub-schemas in this schema by name
   */
  // 作用: 钩入方法。返回嵌套子模式的映射。
  protected Map<String, Schema> getSubSchemaMap() {
    return ImmutableMap.of();
  }
  // 作用: 获取所有子模式的名称。
  @Override public final Set<String> getSubSchemaNames() {
    //noinspection RedundantCast
    return (Set<String>) getSubSchemaMap().keySet();
  }
  // 作用: 根据名称获取子模式。
  @Override public final @Nullable Schema getSubSchema(String name) {
    return getSubSchemaMap().get(name);
  }

  /** Schema factory that creates an
   * {@link org.apache.calcite.schema.impl.AbstractSchema}. */
  public static class Factory implements SchemaFactory {
    public static final Factory INSTANCE = new Factory();

    private Factory() {}

    @Override public Schema create(SchemaPlus parentSchema, String name,
        Map<String, Object> operand) {
      return new AbstractSchema();
    }
  }
}
