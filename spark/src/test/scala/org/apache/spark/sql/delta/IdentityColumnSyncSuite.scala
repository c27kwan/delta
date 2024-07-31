/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta

import org.apache.spark.sql.delta.GeneratedAsIdentityType.GeneratedByDefault
import org.apache.spark.sql.delta.sources.DeltaSourceUtils

import org.apache.spark.sql.{AnalysisException, Row}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.types._

case class IdentityColumnTestTableRow(id: Long, value: String)

/**
 * Identity Column test suite for the SYNC IDENTITY command.
 */
trait IdentityColumnSyncSuiteBase
  extends IdentityColumnTestUtils {

  import testImplicits._
  private val tblName = "identity_test"

  /**
   * Create and manage a table with a single identity column "id" generated by default and a single
   * String "value" column.
   */
  private def withSimpleGeneratedByDefaultTable(
      startsWith: Long, incrementBy: Long)(f: => Unit): Unit = {
    withTable(tblName) {
      createTable(
        tblName,
        Seq(
          IdentityColumnSpec(
            GeneratedByDefault,
            startsWith = Some(startsWith),
            incrementBy = Some(incrementBy)),
          TestColumnSpec(colName = "value", dataType = StringType)
        )
      )

      f
    }
  }

  test("alter table sync identity delta") {
    val starts = Seq(-1, 1)
    val steps = Seq(-3, 3)
    val alterKeywords = Seq("ALTER", "CHANGE")
    for (start <- starts; step <- steps; alterKeyword <- alterKeywords) {
      withSimpleGeneratedByDefaultTable(start, step) {
        // Test empty table.
        val oldSchema = DeltaLog.forTable(spark, TableIdentifier(tblName)).snapshot.schema
        sql(s"ALTER TABLE $tblName $alterKeyword COLUMN id SYNC IDENTITY")
        assert(DeltaLog.forTable(spark, TableIdentifier(tblName)).snapshot.schema == oldSchema)

        // Test a series of values that are not all following start and step configurations.
        for (i <- start to (start + step * 10)) {
          sql(s"INSERT INTO $tblName VALUES($i, 'v')")
          sql(s"ALTER TABLE $tblName $alterKeyword COLUMN id SYNC IDENTITY")
          val expected = start + (((i - start) + (step - 1)) / step) * step
          val schema = DeltaLog.forTable(spark, TableIdentifier(tblName)).snapshot.schema
          assert(schema("id").metadata.getLong(DeltaSourceUtils.IDENTITY_INFO_HIGHWATERMARK) ==
            expected)
        }
      }
    }
  }

  test("sync identity with values before start") {
    withSimpleGeneratedByDefaultTable(startsWith = 100L, incrementBy = 2L) {
      sql(s"INSERT INTO $tblName (id, value) VALUES (1, 'a'), (2, 'b'), (99, 'c')")
      sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
      sql(s"INSERT INTO $tblName (value) VALUES ('d'), ('e'), ('f')")

      val result = spark.read.table(tblName)
        .as[IdentityColumnTestTableRow]
        .collect()
        .sortBy(_.id)
      assert(result.length === 6)
      assert(result.take(3) === Seq(IdentityColumnTestTableRow(1, "a"),
                                    IdentityColumnTestTableRow(2, "b"),
                                    IdentityColumnTestTableRow(99, "c")))
      checkGeneratedIdentityValues(
        sortedRows = result.takeRight(3),
        start = 100,
        step = 2,
        expectedLowerBound = 100,
        expectedUpperBound =
          highWaterMark(DeltaLog.forTable(spark, TableIdentifier(tblName)).snapshot, "id"),
        expectedDistinctCount = 3)
    }
  }

  test("sync identity with start in table") {
    withSimpleGeneratedByDefaultTable(startsWith = 100L, incrementBy = 2L) {
      sql(s"INSERT INTO $tblName (id, value) VALUES (1, 'a'), (2, 'b'), (100, 'c')")
      sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
      sql(s"INSERT INTO $tblName (value) VALUES ('d'), ('e'), ('f')")

      val result = spark.read.table(tblName)
        .as[IdentityColumnTestTableRow]
        .collect()
        .sortBy(_.id)
      assert(result.length === 6)
      assert(result.take(3) === Seq(IdentityColumnTestTableRow(1, "a"),
                                    IdentityColumnTestTableRow(2, "b"),
                                    IdentityColumnTestTableRow(100, "c")))
      checkGeneratedIdentityValues(
        sortedRows = result.takeRight(3),
        start = 100,
        step = 2,
        expectedLowerBound = 101,
        expectedUpperBound =
          highWaterMark(DeltaLog.forTable(spark, TableIdentifier(tblName)).snapshot, "id"),
        expectedDistinctCount = 3)
    }
  }

  test("sync identity with values before and after start") {
    withSimpleGeneratedByDefaultTable(startsWith = 100L, incrementBy = 2L) {
      sql(s"INSERT INTO $tblName (id, value) VALUES (1, 'a'), (2, 'b'), (101, 'c')")
      sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
      sql(s"INSERT INTO $tblName (value) VALUES ('d'), ('e'), ('f')")

      val result = spark.read.table(tblName)
        .as[IdentityColumnTestTableRow]
        .collect()
        .sortBy(_.id)
      assert(result.length === 6)
      assert(result.take(3) === Seq(IdentityColumnTestTableRow(1, "a"),
                                    IdentityColumnTestTableRow(2, "b"),
                                    IdentityColumnTestTableRow(101, "c")))
      checkGeneratedIdentityValues(
        sortedRows = result.takeRight(3),
        start = 100,
        step = 2,
        expectedLowerBound = 102,
        expectedUpperBound =
          highWaterMark(DeltaLog.forTable(spark, TableIdentifier(tblName)).snapshot, "id"),
        expectedDistinctCount = 3)
    }
  }

  test("sync identity with values before start and negative step") {
    withSimpleGeneratedByDefaultTable(startsWith = -10L, incrementBy = -2L) {
      sql(s"INSERT INTO $tblName (id, value) VALUES (1, 'a'), (2, 'b'), (-9, 'c')")
      sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
      sql(s"INSERT INTO $tblName (value) VALUES ('d'), ('e'), ('f')")

      val result = spark.read.table(tblName)
        .as[IdentityColumnTestTableRow]
        .collect()
        .sortBy(_.id)
      assert(result.length === 6)
      assert(result.takeRight(3) === Seq(IdentityColumnTestTableRow(-9, "c"),
                                         IdentityColumnTestTableRow(1, "a"),
                                         IdentityColumnTestTableRow(2, "b")))
      checkGeneratedIdentityValues(
        sortedRows = result.take(3),
        start = -10,
        step = -2,
        expectedLowerBound =
          highWaterMark(DeltaLog.forTable(spark, TableIdentifier(tblName)).snapshot, "id"),
        expectedUpperBound = -10,
        expectedDistinctCount = 3)
    }
  }

  test("alter table sync identity - deleting high watermark rows followed by sync identity" +
    " brings down the highWatermark") {
    for (generatedAsIdentityType <- GeneratedAsIdentityType.values) {
      withTable(tblName) {
        createTableWithIdColAndIntValueCol(tblName, generatedAsIdentityType, Some(1L), Some(10L))
        val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tblName))
        (0 to 4).foreach { v =>
          sql(s"INSERT INTO $tblName(value) VALUES ($v)")
        }

        checkAnswer(sql(s"SELECT max(id) FROM $tblName"), Row(41))
        sql(s"DELETE FROM $tblName WHERE value IN (0, 3, 4)")
        assert(highWaterMark(deltaLog.snapshot, "id") === 41L)
        sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
        assert(highWaterMark(deltaLog.snapshot, "id") === 21L)
        sql(s"INSERT INTO $tblName(value) VALUES (8)")
        checkAnswer(sql(s"SELECT max(id) FROM $tblName"), Row(31))
        checkAnswer(sql(s"SELECT COUNT(DISTINCT id) == COUNT(*) FROM $tblName"), Row(true))
      }
    }
  }

  test("alter table sync identity overflow") {
    withSimpleGeneratedByDefaultTable(startsWith = 1L, incrementBy = 10L) {
      sql(s"INSERT INTO $tblName VALUES (${Long.MaxValue}, 'a')")
      intercept[ArithmeticException](sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY"))
    }
  }

  test("alter table sync identity non delta") {
    withTable(tblName) {
      sql(
        s"""
           |CREATE TABLE $tblName (
           |  id BIGINT,
           |  value INT
           |) USING parquet;
           |""".stripMargin)
      val ex = intercept[AnalysisException] {
        sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
      }
      assert(ex.getMessage.contains("ALTER TABLE ALTER COLUMN SYNC IDENTITY is only supported by Delta."))
    }
  }

  test("alter table sync identity non identity column") {
    withTable(tblName) {
      createTable(
        tblName,
        Seq(
          TestColumnSpec(colName = "id", dataType = LongType),
          TestColumnSpec(colName = "value", dataType = IntegerType)
        )
      )
      val ex = intercept[AnalysisException] {
        sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
      }
      assert(ex.getMessage.contains("ALTER TABLE ALTER COLUMN SYNC IDENTITY cannot be called"))
    }
  }
}


class IdentityColumnSyncScalaSuite
  extends IdentityColumnSyncSuiteBase
  with ScalaDDLTestUtils

class IdentityColumnSyncScalaIdColumnMappingSuite
  extends IdentityColumnSyncSuiteBase
  with ScalaDDLTestUtils
  with DeltaColumnMappingEnableIdMode

class IdentityColumnSyncScalaNameColumnMappingSuite
  extends IdentityColumnSyncSuiteBase
  with ScalaDDLTestUtils
  with DeltaColumnMappingEnableNameMode
