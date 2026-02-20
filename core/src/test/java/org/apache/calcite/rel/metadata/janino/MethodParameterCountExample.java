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

package org.apache.calcite.rel.metadata.janino;

import java.lang.reflect.Method;

public class MethodParameterCountExample {
    public static void main(String[] args) {
        try {
            // 获取示例类的 Class 对象
            Class<?> exampleClass = ExampleClass.class;

            // 获取所有方法
            Method[] methods = exampleClass.getDeclaredMethods();

            for (Method method : methods) {
                // 获取方法名称
                String methodName = method.getName();

                // 获取参数数量
                int parameterCount = method.getParameterCount();

                // 打印方法名称和参数数量
                System.out.println("Method: " + methodName + ", Parameter Count: " + parameterCount);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 示例类
    static class ExampleClass {
        public void exampleMethod1() {}
        public void exampleMethod2(String param1) {}
        public void exampleMethod3(String param1, int param2) {}
    }
}
