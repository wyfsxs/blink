/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.expressions

import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.RowTypeInfo
import org.apache.flink.table.api.Types
import org.apache.flink.table.api.functions.ScalarFunction
import org.apache.flink.table.api.scala._
import org.apache.flink.table.expressions.utils.{ExpressionTestBase, Func3}
import org.apache.flink.types.Row
import org.junit.Test

class LiteralTest extends ExpressionTestBase {

  @Test
  def testFieldWithBooleanPrefix(): Unit = {

    testTableApi(
      'trUeX,
      "trUeX",
      "trUeX_value"
    )

    testTableApi(
      'FALSE_A,
      "FALSE_A",
      "FALSE_A_value"
    )

    testTableApi(
      'FALSE_AB,
      "FALSE_AB",
      "FALSE_AB_value"
    )

    testTableApi(
      true,
      "trUe",
      "true"
    )

    testTableApi(
      false,
      "FALSE",
      "false"
    )
  }

  @Test
  def testNonAsciiLiteral(): Unit = {
    testAllApis(
      'f4.like("%测试%"),
      "f4.like('%测试%')",
      "f4 LIKE '%测试%'",
      "true")

    testAllApis(
      "Абвгде" + "谢谢",
      "'Абвгде' + '谢谢'",
      "'Абвгде' || '谢谢'",
      "Абвгде谢谢")
  }

  @Test
  def testDoubleQuote(): Unit = {
    val hello = "\"<hello>\""
    testAllApis(
      Func3(42, hello),
      s"Func3(42, '$hello')",
      s"Func3(42, '$hello')",
      s"42 and $hello")
  }

  @Test
  def testStringLiterals(): Unit = {

    // these tests use Java/Scala escaping for non-quoting unicode characters

    testAllApis(
      ">\n<",
      "'>\n<'",
      "'>\n<'",
      ">\n<")

    testAllApis(
      ">\u263A<",
      "'>\u263A<'",
      "'>\u263A<'",
      ">\u263A<")

    testAllApis(
      ">\u263A<",
      "'>\u263A<'",
      "'>\u263A<'",
      ">\u263A<")

    testAllApis(
      ">\\<",
      "'>\\<'",
      "'>\\<'",
      ">\\<")

    testAllApis(
      ">'<",
      "'>''<'",
      "'>''<'",
      ">'<")

    testAllApis(
      " ",
      "' '",
      "' '",
      " ")

    testAllApis(
      "",
      "''",
      "''",
      "")

    testAllApis(
      ">foo([\\w]+)<",
      "'>foo([\\w]+)<'",
      "'>foo([\\w]+)<'",
      ">foo([\\w]+)<")

    testAllApis(
      ">\\'\n<",
      "\">\\'\n<\"",
      "'>\\''\n<'",
      ">\\'\n<")

    testAllApis(
      "It's me.",
      "'It''s me.'",
      "'It''s me.'",
      "It's me.")

    testTableApi(
      """I "like" dogs.""",
      """"I ""like"" dogs."""",
      """I "like" dogs.""")

    // these test use SQL for describing unicode characters

    testSqlApi(
      "U&'>\\263A<'", // default escape backslash
      ">\u263A<")

    testSqlApi(
      "U&'>#263A<' UESCAPE '#'", // custom escape '#'
      ">\u263A<")

    testSqlApi(
      """'>\\<'""",
      ">\\\\<")
  }

  override def functions: Map[String, ScalarFunction] = Map(
    "Func3" -> Func3
  )

  override def rowTestData: Row = {
    val testData = new Row(4)
    testData.setField(0, "trUeX_value")
    testData.setField(1, "FALSE_A_value")
    testData.setField(2, "FALSE_AB_value")
    testData.setField(3, "这是个测试字符串")
    testData
  }

  override def rowType : RowTypeInfo = {
    new RowTypeInfo(
      Array(
        Types.STRING,
        Types.STRING,
        Types.STRING,
        Types.STRING
      ).asInstanceOf[Array[TypeInformation[_]]],
      Array("trUeX", "FALSE_A", "FALSE_AB", "f4")
    )
  }
}
