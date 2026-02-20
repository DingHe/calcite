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

package org.apache.calcite.adapter.innodb;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

public class test {

  private static final String INNODB_SCHEMA = "     {\n"
      + "       type: 'jdbc',\n"
      + "       name: 'test',\n"
      + "       jdbcDriver: " + "'com.mysql.jdbc.Driver'" + ",\n"
      + "       jdbcUser: " + "'root'" + ",\n"
      + "       jdbcPassword: " + "'1qaz!QAZ'" + ",\n"
      + "       jdbcUrl: " + "'jdbc:mysql://localhost/test'" + ",\n"
      + "       jdbcCatalog: " + "'null'" + ",\n"
      + "       jdbcSchema: " + "'test'" + "\n"
      + "     }\n";
  private static final ImmutableMap<String, String> INNODB_MODEL1 = ImmutableMap.of("model","{\n"
      + "  version: '1.0',\n"
      + "  defaultSchema: 'test',\n"
      + "   schemas: [\n"
      + INNODB_SCHEMA
      + "   ]\n"
      + "}");

  public static void main(String[] args) {
    for(Map.Entry<String,String> item : INNODB_MODEL1.entrySet()){
      System.out.println("key="+item.getKey());
      System.out.println("value="+item.getValue());
    }
  }
}
