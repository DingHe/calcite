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

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.rel.metadata.MetadataDef;
import org.apache.calcite.rel.metadata.MetadataHandler;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

/**
 * Generates the {@link MetadataHandler} code.
 */
public class RelMetadataHandlerGeneratorUtil {
  private static final String LICENSE = "/*\n"
      + " * Licensed to the Apache Software Foundation (ASF) under one or more\n"
      + " * contributor license agreements.  See the NOTICE file distributed with\n"
      + " * this work for additional information regarding copyright ownership.\n"
      + " * The ASF licenses this file to you under the Apache License, Version 2.0\n"
      + " * (the \"License\"); you may not use this file except in compliance with\n"
      + " * the License.  You may obtain a copy of the License at\n"
      + " *\n"
      + " * http://www.apache.org/licenses/LICENSE-2.0\n"
      + " *\n"
      + " * Unless required by applicable law or agreed to in writing, software\n"
      + " * distributed under the License is distributed on an \"AS IS\" BASIS,\n"
      + " * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n"
      + " * See the License for the specific language governing permissions and\n"
      + " * limitations under the License.\n"
      + " */\n";

  private RelMetadataHandlerGeneratorUtil() {
  }

  //handlerClass是元数据定义的Handler接口，例如PercentageOriginalRows里面的Handler接口
  //handlers是以RelMd开头的元数据的实现类，例如RelMdPercentageOriginalRowsHandler类

  public static HandlerNameAndGeneratedCode generateHandler(
      Class<? extends MetadataHandler<?>> handlerClass,
      List<? extends MetadataHandler<?>> handlers) {
    final String classPackage =
        castNonNull(RelMetadataHandlerGeneratorUtil.class.getPackage())
            .getName();
    final String name =
        "GeneratedMetadata_" + simpleNameForHandler(handlerClass);
    //获取元数据定义的Handler接口定义的方法
    final SortedMap<String, Method> declaredMethods =
        MetadataHandler.handlerMethods(handlerClass);
    //给元数据的实现类定一个名称，provider+序号
    final Map<MetadataHandler<?>, String> handlerToName = new LinkedHashMap<>();
    for (MetadataHandler<?> provider : handlers) {
      handlerToName.put(provider,
          "provider" + handlerToName.size());
    }

    final StringBuilder buff = new StringBuilder();

    buff.append(LICENSE)
        .append("package ").append(classPackage).append(";\n\n");

    // Class definition
    //类定义，实现了参数handlerClass定义的接口
    buff.append("public final class ").append(name).append("\n")
        .append("  implements ").append(handlerClass.getCanonicalName()).append(" {\n");

    // Properties
    //声明如下的属性，作为查询mq中map的key
    //private final Object methodKey0 =
    //      new org.apache.calcite.rel.metadata.janino.DescriptiveCacheKey("Double Handler.getPercentageOriginalRows()");
    Ord.forEach(declaredMethods.values(), (declaredMethod, i) ->
        CacheGeneratorUtil.cacheProperties(buff, declaredMethod, i));
    //声明元数据实现类的属性
    //public final org.apache.calcite.rel.metadata.RelMdPercentageOriginalRows$RelMdPercentageOriginalRowsHandler provider0;
    for (Map.Entry<MetadataHandler<?>, String> handlerAndName : handlerToName.entrySet()) {
      buff.append("  public final ").append(handlerAndName.getKey().getClass().getName())
          .append(' ').append(handlerAndName.getValue()).append(";\n");
    }

    // Constructor
    //声明构造函数，参数是元数据实现类
    //public GeneratedMetadata_PercentageOriginalRowsHandler(
    //      org.apache.calcite.rel.metadata.RelMdPercentageOriginalRows$RelMdPercentageOriginalRowsHandler provider0) {
    //    this.provider0 = provider0;
    //  }
    buff.append("  public ").append(name).append("(\n");
    for (Map.Entry<MetadataHandler<?>, String> handlerAndName : handlerToName.entrySet()) {
      buff.append("      ")
          .append(handlerAndName.getKey().getClass().getName())
          .append(' ')
          .append(handlerAndName.getValue())
          .append(",\n");
    }
    if (!handlerToName.isEmpty()) {
      buff.setLength(buff.length() - 2);
    }
    buff.append(") {\n");
    for (String handlerName : handlerToName.values()) {
      buff.append("    this.").append(handlerName).append(" = ").append(handlerName)
          .append(";\n");
    }
    buff.append("  }\n");

    // Methods
    //实现元数据定义的方法getDef，如果元数据的实现类提供，则复用，没有则为空
    getDefMethod(buff,
        //找第一个元数据实现类的名称，元数据实现类的名称以provider开头，后面加序号
        handlerToName.values()
            .stream()
            .findFirst()
            .orElse(null));

    DispatchGenerator dispatchGenerator = new DispatchGenerator(handlerToName);
    Ord.forEach(declaredMethods.values(), (declaredMethod, i) -> {
      CacheGeneratorUtil.cachedMethod(buff, declaredMethod, i);
      dispatchGenerator.dispatchMethod(buff, declaredMethod, handlers);
    });
    // End of Class
    buff.append("\n}\n");
    return ImmutableHandlerNameAndGeneratedCode.builder()
        .withHandlerName(classPackage + "." + name)
        .withGeneratedCode(buff.toString())
        .build();
  }

  private static void getDefMethod(StringBuilder buff, @Nullable String handlerName) {
   //这里生成MetadataDef类的方法getDef,如果元数据的实现类有，则复用，没有则返回为空
    buff.append("  public ")
        .append(MetadataDef.class.getName())
        .append(" getDef() {\n");

    if (handlerName == null) {
      buff.append("    return null;");
    } else {
      buff.append("    return ")
          .append(handlerName)
          .append(".getDef();\n");
    }
    buff.append("  }\n");
  }

  private static String simpleNameForHandler(Class<? extends MetadataHandler<?>> clazz) {
    String simpleName = clazz.getSimpleName();
    // Previously the pattern was to have a nested in class named Handler.
    // So we need to add the parents class to get a unique name.
    if (simpleName.equals("Handler")) {
      String[] parts = clazz.getName().split("[.$]");
      return parts[parts.length - 2] + parts[parts.length - 1];
    } else {
      return simpleName;
    }
  }

  /** Contains Name and code that been generated for {@link MetadataHandler}.*/
  @Value.Immutable(singleton = false)
  public interface HandlerNameAndGeneratedCode {
    String getHandlerName();
    String getGeneratedCode();
  }
}
