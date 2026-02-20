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

import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelDistributionTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.rel.rules.PruneEmptyRules;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.sql2rel.RelDecorrelator;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;

import org.apache.calcite.tools.RelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Properties;

public class HepPlannerTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(HepPlannerTest.class);

  public static void main(String[] args) {
    SchemaPlus rootSchema = CalciteUtils.registerRootSchema();
    final FrameworkConfig frameworkConfig = Frameworks.newConfigBuilder()
        .parserConfig(SqlParser.Config.DEFAULT)
        .defaultSchema(rootSchema)
        .traitDefs(ConventionTraitDef.INSTANCE, RelDistributionTraitDef.INSTANCE)
        .build();

    String sql
        = "select u.id as user_id, u.name as user_name, j.company as user_company, u.age as user_age from users u"
        + " join jobs j on u.id=j.id where u.age > 30 and j.id>10 order by user_id";


    HepProgramBuilder builder = new HepProgramBuilder();
    builder.addRuleInstance(CoreRules.FILTER_INTO_JOIN);
    builder.addRuleInstance(CoreRules.PROJECT_REDUCE_EXPRESSIONS);
    builder.addRuleInstance(PruneEmptyRules.PROJECT_INSTANCE);

    HepPlanner planner = new HepPlanner((builder.build()));

    try{
      SqlTypeFactoryImpl factory = new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
      SqlParser parser = SqlParser.create(sql,SqlParser.Config.DEFAULT);
      SqlNode parsed = parser.parseStmt();
      LOGGER.info("The SqlNode after parsed is:\n{}", parsed.toString());

      CalciteCatalogReader catalogReader = new CalciteCatalogReader(
          CalciteSchema.from(rootSchema),
          CalciteSchema.from(rootSchema).path(null),
          factory,
          new CalciteConnectionConfigImpl(new Properties()));


      SqlValidator validator = SqlValidatorUtil.newValidator(
          SqlStdOperatorTable.instance(),
          catalogReader,
          factory,
          frameworkConfig.getSqlValidatorConfig()
      );

      SqlNode validated = validator.validate(parsed);
      LOGGER.info("The SqlNode after validated is:\n{}", validated.toString());


      final RexBuilder rexBuilder = CalciteUtils.createRexBuilder(factory);
      final RelOptCluster cluster = RelOptCluster.create(planner,rexBuilder);

      final SqlToRelConverter.Config config = frameworkConfig.getSqlToRelConverterConfig()
          .withTrimUnusedFields(false);

      final SqlToRelConverter sqlToRelConverter =
          new SqlToRelConverter(new CalciteUtils.ViewExpanderImpl(),
              validator,
              catalogReader,
              cluster,
              frameworkConfig.getConvertletTable(),
              config
              );

      RelRoot root = sqlToRelConverter.convertQuery(
          validated,
          false,true
      );
      root = root.withRel(sqlToRelConverter.flattenTypes(
          root.rel,true
      ));

      final RelBuilder relBuilder = config.getRelBuilderFactory()
          .create(cluster,null);
      root = root.withRel(RelDecorrelator.decorrelateQuery(root.rel,relBuilder));
      RelNode relNode = root.rel;
      LOGGER.info("The relational expression string before optimized is:\n{}", RelOptUtil.toString(relNode));

      planner.setRoot(relNode);
      relNode = planner.findBestExp();

      System.out.println("-----------------------------------------------------------");
      System.out.println("The Best relational expression string:");
      System.out.println(RelOptUtil.toString(relNode));
      System.out.println("-----------------------------------------------------------");

    } catch (SqlParseException e) {
      throw new RuntimeException(e);
    }
  }
}
