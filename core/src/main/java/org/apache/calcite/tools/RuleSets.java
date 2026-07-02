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
package org.apache.calcite.tools;

import org.apache.calcite.plan.RelOptRule;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Iterator;

/**
 * Utilities for creating and composing rule sets.
 *
 * @see org.apache.calcite.tools.RuleSet
 */
// RuleSets 类的核心职责是作为 RuleSet 的静态工厂。
// 它隐藏了底层具体实现类的细节（如内部私有类 ListRuleSet），为框架使用者提供了一套极其简洁的接口，
// 用于将零散的规则数组（Array/Varargs）或可迭代集合（Iterable）快速转换为不可变的、线程安全的 RuleSet 对象。
public class RuleSets {
  private RuleSets() {
  }

  /** Creates a rule set with a given array of rules. */
  // RelOptRule... rules —— 变长参数（数组），代表一个或多个优化规则。
  // 将用户在代码里以逗号分隔传入的一系列规则对象（或者一个规则数组），深拷贝并包装成一个不可变的 RuleSet。
  public static RuleSet ofList(RelOptRule... rules) {
    return new ListRuleSet(ImmutableList.copyOf(rules));
  }

  /** Creates a rule set with a given collection of rules. */
  // 专门用来将已有的集合类对象，安全地转换构建为 RuleSet。通过 ImmutableList.copyOf 阻断了外部对原集合修改可能引发的安全隐患。
  public static RuleSet ofList(Iterable<? extends RelOptRule> rules) {
    return new ListRuleSet(ImmutableList.copyOf(rules));
  }

  /** Rule set that consists of a list of rules. */
  private static class ListRuleSet implements RuleSet {
    private final ImmutableList<RelOptRule> rules;

    ListRuleSet(ImmutableList<RelOptRule> rules) {
      this.rules = rules;
    }

    @Override public int hashCode() {
      return rules.hashCode();
    }

    @Override public boolean equals(@Nullable Object obj) {
      return obj == this
          || obj instanceof ListRuleSet
          && rules.equals(((ListRuleSet) obj).rules);
    }

    @Override public Iterator<RelOptRule> iterator() {
      return rules.iterator();
    }
  }
}
