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

import org.apache.calcite.jdbc.CalciteConnection;

import java.io.File;
import java.sql.*;
import java.util.Properties;

public class TutorialTest {
  public static void main(String[] args) throws Exception {
    String csvPath = "/Users/dinghehsiao/Downloads/code/calcite-main/example/csv/src/test/resources/sales";

    CsvSchema csvSchema = new CsvSchema(new File(csvPath), CsvTable.Flavor.TRANSLATABLE);
    Properties properties = new Properties();
    properties.setProperty("caseSensitive","false");

    Connection connection = DriverManager.getConnection("jdbc:calcite:",properties);
    CalciteConnection calciteConnection = connection.unwrap(CalciteConnection.class);

    calciteConnection.getRootSchema().add("sales",csvSchema);

    query(calciteConnection, "SELECT a.empno, coalesce(a.name,'Default') as name,max(b.deptno) as maxdeptno FROM sales.emps a\n" +
        "join sales.depts b on a.deptno = b.deptno\n" +
        "WHERE a.deptno > 20\n" +
        "group by a.empno, coalesce(a.name,'Default')");

  }

  public static void query(CalciteConnection calciteConnection, String sql) throws Exception {
    System.out.println("****** " + sql + " ******");

    try (Statement statement = calciteConnection.createStatement();
         ResultSet resultSet = statement.executeQuery(sql)) {
      dumpResultSet(resultSet);
    }
  }


  public static void dumpResultSet(ResultSet resultSet) throws Exception {
    int columnCnt = resultSet.getMetaData().getColumnCount();

    while (resultSet.next()) {
      StringBuilder rowString = new StringBuilder();

      for (int i = 1; i <= columnCnt; i++) {
        String label = resultSet.getMetaData().getColumnLabel(i);
        String value = resultSet.getObject(i).toString();
        rowString.append(label).append(":").append(value).append(" ");
      }

      System.out.println(rowString.toString().trim());
    }
  }


}
