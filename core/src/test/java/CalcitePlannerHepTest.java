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

import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgram;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.externalize.RelWriterImpl;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.test.CalciteAssert;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import java.sql.ResultSet;

import org.apache.calcite.tools.RelRunners;


import java.io.PrintWriter;
import java.sql.SQLException;

public class CalcitePlannerHepTest {
  public static void main(String[] args) {
    SchemaPlus rootSchema = CalciteSchema.createRootSchema(true).plus();
    FrameworkConfig config = Frameworks.newConfigBuilder()
        .defaultSchema(
            CalciteAssert.addSchema(rootSchema, CalciteAssert.SchemaSpec.HR))
        .build();
     RelBuilder builder = RelBuilder.create(config);

     RelNode opTree = builder.scan("emps")
        .scan("depts")
        .join(JoinRelType.INNER, "deptno")
        .filter(builder.equals(builder.field("empid"), builder.literal(100)))
        .build();

     RelWriter rw = new RelWriterImpl(new PrintWriter(System.out, true));
 /*    opTree.explain(rw);*/

    HepProgram program = HepProgram.builder().addRuleInstance(CoreRules.FILTER_INTO_JOIN).build();
    HepPlanner hepPlanner = new HepPlanner(program);
    hepPlanner.setRoot(opTree);
    hepPlanner.findBestExp().explain(rw);

 /*    RelOptCluster cluster = opTree.getCluster();
    VolcanoPlanner planner = (VolcanoPlanner) cluster.getPlanner();

    RelTraitSet desiredTraits = cluster.traitSet().replace(EnumerableConvention.INSTANCE);
    RelNode newRoot = planner.changeTraits(opTree, desiredTraits);
    planner.setRoot(newRoot);

    RelNode optimized = planner.findBestExp();
    optimized.explain(rw);

    ResultSet result = null;
    try {
      result = RelRunners.run(optimized).executeQuery();
      int columns = result.getMetaData().getColumnCount();
      while (result.next()) {
        System.out.println(result.getString(1) + " " + result.getString(7));
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }

*/
  }
}
