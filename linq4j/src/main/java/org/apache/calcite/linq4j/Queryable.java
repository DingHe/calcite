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
package org.apache.calcite.linq4j;

import org.checkerframework.framework.qual.Covariant;

/**
 * Provides functionality to evaluate queries against a specific data source
 * wherein the type of the data is known.
 *
 * <p>Analogous to LINQ's System.Linq.IQueryable.
 *  范型参数 T是数据类型，通过实现该接口，需要实现下面的方法
 *  getElementType --返回数据类型
 *  getExpression  --返回关联的表达式
 *  getProvider    --数据源提供者
 *  asQueryable    --返回Queryable 对象
 *  enumerator     --返回枚举器
 * @param <T> Element type
 */
@Covariant(0)
public interface Queryable<T> extends RawQueryable<T>, ExtendedQueryable<T> {
}
