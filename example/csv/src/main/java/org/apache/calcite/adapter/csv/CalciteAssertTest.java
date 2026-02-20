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

package org.apache.calcite.adapter.csv;

public class CalciteAssertTest {
  /*
  0、Calcite的系统属性放在
  /core/src/test/resources/saffron.properties配置文件中
  属性配置类CalciteSystemProperty
  1、ConnectionPostProcessor
  ConnectionPostProcessor是一个函数式接口，接收Connection参数，返回Connection
  @FunctionalInterface
  public interface ConnectionPostProcessor {
    Connection apply(Connection connection) throws SQLException;
  }
  2、SchemaSpec是一个枚举类，记录各个数据集，18个枚举值
  public enum SchemaSpec {
    REFLECTIVE_FOODMART("foodmart"),
    FAKE_FOODMART("foodmart"),
    JDBC_FOODMART("foodmart"),

  3、数据库的连接信息放在枚举类DatabaseInstance
  例如：
      MYSQL(
        new ConnectionSpec("jdbc:mysql://localhost/foodmart", "foodmart",
            "foodmart", "com.mysql.jdbc.Driver", "foodmart"), null),
    ORACLE(
        new ConnectionSpec("jdbc:oracle:thin:@localhost:1521:XE", "foodmart",
            "foodmart", "oracle.jdbc.OracleDriver", "FOODMART"), null),

  4、FoodmartSchema是构建jdbc连接的测试类

  5、AddSchemaSpecPostProcessor
  AddSchemaSpecPostProcessor类实现了ConnectionPostProcessor接口，
  成员变量
  SchemaSpec schemaSpec
  实现apply方法的代码如下：
  通过connection获取rootSchema，然后设置schema,
  addSchemaf负责把schema设置到connection的rootSchema里面。

   CalciteConnection con = connection.unwrap(CalciteConnection.class);
      SchemaPlus rootSchema = con.getRootSchema();
      switch (schemaSpec) {
      case CLONE_FOODMART:
      case JDBC_FOODMART_WITH_LATTICE:
        CalciteAssert.addSchema(rootSchema, CalciteAssert.SchemaSpec.JDBC_FOODMART);
        // fall through
      default:
        CalciteAssert.addSchema(rootSchema, schemaSpec);
      }
      con.setSchema(schemaSpec.schemaName);
      return connection;

  6、然后AddSchemaSpecPostProcessor会存入到ConnectFactory里面，
  例如ConnectFactory的实现MapConnectFactory里面有专门的ImmutableList字段存储，
  当创建connect的时候，会把schema设置到connect里面。
   Connection connection =
          DriverManager.getConnection("jdbc:calcite:", info);
      for (CalciteAssert.ConnectionPostProcessor postProcessor : postProcessors) {
        connection = postProcessor.apply(connection);
      }
      return connection;


  7、执行查询，
  查询执行在
   */

}
