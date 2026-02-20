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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CacheGeneratorUtilTest {
  private Method mockMethod;
  @BeforeEach
  void setUp() throws NoSuchMethodException {
    // 创建一个模拟的 Method 对象，用于测试
    mockMethod = ExampleClass.class.getMethod("exampleMethod", String.class, int.class);
  }

  @Test
  void testGenerateEnum() {
    // 生成枚举缓存键
    Object[] cacheKeys = CacheUtil.generateEnum("ExampleMethod", ExampleEnum.values());

    // 验证枚举值数量
    assertEquals(ExampleEnum.values().length, cacheKeys.length, "枚举值数量不匹配");

    // 验证每个枚举值的缓存键
    for (int i = 0; i < ExampleEnum.values().length; i++) {
      System.out.println(cacheKeys[i].toString());
      assertEquals("ExampleMethod(" + ExampleEnum.values()[i].name()+")", cacheKeys[i].toString());
    }
  }

  @Test
  void testGenerateRange() {
    int min = 0;
    int max = 10;

    // 生成整数范围的缓存键
    Object[] cacheKeys = CacheUtil.generateRange("ExampleMethod", min, max);

    // 验证生成的键数组长度是否正确
    assertEquals(max - min, cacheKeys.length, "生成的范围键数量不正确");

    // 验证键值是否按顺序生成
    for (int i = min; i < max; i++) {
      System.out.println(cacheKeys[i - min].toString());
      assertEquals("ExampleMethod(" + i + ")", cacheKeys[i - min].toString());
    }
  }

  @Test
  void testNewDescriptiveCacheKey() throws Exception {
    StringBuilder buff = new StringBuilder();
    String arg = "testArg";

    // 通过反射调用私有方法
    Method newDescriptiveCacheKeyMethod = CacheGeneratorUtil2.class.getDeclaredMethod("newDescriptiveCacheKey",
        StringBuilder.class, Method.class, String.class);
    newDescriptiveCacheKeyMethod.setAccessible(true); // 使私有方法可访问

    // 调用方法
    newDescriptiveCacheKeyMethod.invoke(null, buff, mockMethod, arg);

    // 生成的期望字符串
    String expected = "      new org.apache.calcite.rel.metadata.janino.DescriptiveCacheKey(\"String ExampleClass.exampleMethod(testArg)\");\n";

    // 验证生成的字符串是否匹配预期
    assertEquals(expected, buff.toString(), "生成的描述性缓存键不正确");
  }

  @Test
  void testCacheProperties() throws Exception {
    StringBuilder buff = new StringBuilder();

    // 使用一个简单的示例方法
    Method method = ExampleClass.class.getMethod("exampleMethod", String.class, int.class);
    int methodIndex = 0; // 方法的索引

    // 使用反射调用 cacheProperties 方法
    CacheGeneratorUtil2.class.getDeclaredMethod("cacheProperties", StringBuilder.class, Method.class, int.class)
        .setAccessible(true); // 允许访问私有方法
    CacheGeneratorUtil2.cacheProperties(buff, method, methodIndex);

    // 验证生成的代码
    String generatedCode = buff.toString();
    System.out.println(generatedCode);
    assertTrue(generatedCode.contains("private final Object methodKey0 ="));
  }

}

class ExampleClass {
  public String exampleMethod(String param1, int param2) {
    return param1 + param2;
  }

  public void noArgMethod() {
    // 无参方法
  }

  public boolean booleanMethod(boolean flag) {
    return flag;
  }
}


enum ExampleEnum {
  VALUE1, VALUE2, VALUE3;
}

