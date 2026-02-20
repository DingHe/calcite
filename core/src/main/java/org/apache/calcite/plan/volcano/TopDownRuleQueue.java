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
package org.apache.calcite.plan.volcano;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.util.Pair;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A rule queue that manages rule matches for cascades planner.
 */
class TopDownRuleQueue extends RuleQueue {
  //每个 RelNode（表示一个关系节点）对应一个队列（Deque<VolcanoRuleMatch>）。
  // 每当某个关系节点匹配到一个规则时，规则匹配对象 (VolcanoRuleMatch) 会被添加到该节点对应的队列中。
  // 这种结构确保每个关系节点有自己的匹配队列

  private final Map<RelNode, Deque<VolcanoRuleMatch>> matches = new HashMap<>();

  private final Set<String> names = new HashSet<>();//存储所有已经添加的规则匹配的唯一名称，用于防止重复匹配。

  TopDownRuleQueue(VolcanoPlanner planner) {
    super(planner);
  }

  @Override public void addMatch(VolcanoRuleMatch match) {
    RelNode rel = match.rel(0);
    Deque<VolcanoRuleMatch> queue = matches.
        computeIfAbsent(rel, id -> new ArrayDeque<>());//使用 computeIfAbsent 方法获取或创建该 RelNode 对应的队列。如果队列不存在，则会新建一个 ArrayDeque
    addMatch(match, queue);
  }

  private void addMatch(VolcanoRuleMatch match, Deque<VolcanoRuleMatch> queue) {
    if (!names.add(match.toString())) {
      return;
    }

    // The substitution rule would be applied first though it is added at the end of the queue.
    // The process looks like:
    //   1) put the non-substitution rule at the front and substitution rule at the end of the queue
    //   2) get each rule from the queue in order from first to last and generate an ApplyRule task
    //   3) push each ApplyRule task into the task stack
    // As a result, substitution rule is executed first since the ApplyRule(substitution) task is
    // popped earlier than the ApplyRule(non-substitution) task from the stack.
    if (!planner.isSubstituteRule(match)) {  //非替换规则会被添加到队列的前面
      queue.addFirst(match);
    } else {
      queue.addLast(match); //替换规则会被添加到队列的末尾
    }
  }

  public @Nullable VolcanoRuleMatch popMatch(Pair<RelNode, Predicate<VolcanoRuleMatch>> category) {
    Deque<VolcanoRuleMatch> queue = matches.get(category.left);
    if (queue == null) { //如果 matches 中没有对应 RelNode 的队列，返回 null
      return null;
    }
    Iterator<VolcanoRuleMatch> iterator = queue.iterator();
    while (iterator.hasNext()) {
      VolcanoRuleMatch next = iterator.next();
      if (category.right != null && !category.right.test(next)) { //如果当前匹配不符合谓词条件，则跳过该匹配
        continue;
      }
      iterator.remove();
      if (!skipMatch(next)) {
        return next; //如果不跳过，则返回该规则匹配
      }
    }
    return null;
  }

  @Override public boolean clear() {
    boolean empty = matches.isEmpty();
    matches.clear();
    names.clear();
    return !empty;
  }
}
