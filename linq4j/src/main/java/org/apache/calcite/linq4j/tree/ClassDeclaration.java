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

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;

/** 类声明
 * Declaration of a class.
 */
public class ClassDeclaration extends MemberDeclaration {
  public final int modifier; //修饰符
  public final String classClass = "class";
  public final String name; //类名
  public final List<MemberDeclaration> memberDeclarations; //成员声明
  public final @Nullable Type extended; //继承的类
  public final List<Type> implemented; //实现的接口

  public ClassDeclaration(int modifier, String name, @Nullable Type extended,
      List<Type> implemented, List<MemberDeclaration> memberDeclarations) {
    assert name != null : "name should not be null";
    this.modifier = modifier;
    this.name = name;
    this.memberDeclarations = memberDeclarations;
    this.extended = extended;
    this.implemented = implemented;
  }
  //生成代码
  @Override public void accept(ExpressionWriter writer) {
    String modifiers = Modifier.toString(modifier);
    writer.append(modifiers);
    if (!modifiers.isEmpty()) {
      writer.append(' ');
    }
    writer.append(classClass).append(' ').append(name);
    if (extended != null) {
      writer.append(" extends ").append(extended);
    }
    if (!implemented.isEmpty()) {
      writer.list(" implements ", ", ", "", implemented);
    }
    writer.list(" {\n", "", "}", memberDeclarations);
    writer.newlineAndIndent();
  }

  @Override public ClassDeclaration accept(Shuttle shuttle) {
    shuttle = shuttle.preVisit(this);
    final List<MemberDeclaration> members1 =
        Expressions.acceptMemberDeclarations(memberDeclarations, shuttle);
    return shuttle.visit(this, members1);
  }

  @Override public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  @Override public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    ClassDeclaration that = (ClassDeclaration) o;

    if (modifier != that.modifier) {
      return false;
    }
    if (!classClass.equals(that.classClass)) {
      return false;
    }
    if (extended != null ? !extended.equals(that.extended) : that.extended
        != null) {
      return false;
    }
    if (implemented != null ? !implemented.equals(that.implemented) : that
        .implemented != null) {
      return false;
    }
    if (memberDeclarations != null ? !memberDeclarations.equals(that
        .memberDeclarations) : that.memberDeclarations != null) {
      return false;
    }
    if (!name.equals(that.name)) {
      return false;
    }

    return true;
  }

  @Override public int hashCode() {
    return Objects.hash(modifier, classClass, name, memberDeclarations,
        extended, implemented);
  }
}
