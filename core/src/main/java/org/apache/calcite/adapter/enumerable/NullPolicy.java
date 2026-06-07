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
package org.apache.calcite.adapter.enumerable;

/**
 * Describes when a function/operator will return null.
 *
 * <p>STRICT and ANY are similar. STRICT says f(a0, a1) will NEVER return
 * null if a0 and a1 are not null. This means that we can check whether f
 * returns null just by checking its arguments. Use STRICT in preference to
 * ANY whenever possible.
 */
public enum NullPolicy {
  /** Returns null if and only if all of the arguments are null;
   * If all of the arguments are false return false otherwise true. */
  // 物理语义：当且仅当所有入参全部为 NULL 时，函数才返回 NULL。
  ALL,
  /** Returns null if and only if one of the arguments are null. */
  // 当且仅当输入参数中存在至少一个 NULL 时，函数必定返回 NULL；反之，若所有入参均非 NULL，则函数绝对、永远不会返回 NULL。
  STRICT,
  /** Returns null if one of the arguments is null, and possibly other times. */
  // 如果参数中有一个是 NULL，函数会返回 NULL；但即便参数全部不为 NULL，函数在运行期也有可能因为内部业务逻辑而主动返回 NULL。
  SEMI_STRICT,
  /** If any of the arguments are null, return null. */
  // 只要输入的参数列表中有任意一个参数是 NULL，整个函数就直接熔断，无条件返回 NULL。
  ANY,
  /** If the first argument is null, return null. */
  // 只对第 0 个（第一个）输入参数表现出空值敏感。只要第一个参数是 NULL，函数立刻返回 NULL；至于后面的第 1、第 2 个参数是不是 NULL，函数完全不在乎，交由内部逻辑自行处理。

  ARG0,
  // 函数对输入的空值完全脱敏，不触发任何框架层面的自动熔断策略。不管入参是不是 NULL，都直接将参数原封不动地灌入函数体内部，由函数内部的业务逻辑代码自己去进行空值防御。
  NONE
}
