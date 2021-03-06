/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar

import slamdata.Predef._

import quasar.api.table.ColumnType
import quasar.common.{CPath, CPathField}

import org.specs2.matcher.Matcher

import scala.collection.immutable.{Map, Set}

import java.lang.String

abstract class ScalarStageSpec
    extends JsonSpec
    with ScalarStageSpec.IdsSpec
    with ScalarStageSpec.WrapSpec
    with ScalarStageSpec.ProjectSpec
    with ScalarStageSpec.MaskSpec
    with ScalarStageSpec.PivotSpec
    with ScalarStageSpec.CartesianSpec

object ScalarStageSpec {

  /*
   * Please note that this is currently *over*-specified.
   * We don't technically need monotonic ids or even numerical
   * ones, we just need *unique* identities. That assertion is
   * quite hard to encode though. If we find we need such an
   * implementation in the future, these assertions should be
   * changed.
   */
  trait IdsSpec extends JsonSpec {
    import IdStatus.{ExcludeId, IdOnly, IncludeId}

    "ExcludeId" should {
      "emit scalar rows unmodified" in {
        val input = ldjson("""
          1
          "hi"
          true
          """)

        input must interpretIdsAs(ExcludeId, input)
      }

      "emit vector rows unmodified" in {
        val input = ldjson("""
          [1, 2, 3]
          { "a": "hi", "b": { "c": null } }
          [{ "d": {} }]
          """)

        input must interpretIdsAs(ExcludeId, input)
      }
    }

    "IdOnly" should {
      "return monotonic integers for each scalar row" in {
        val input = ldjson("""
          1
          "hi"
          true
          """)

        val expected = ldjson("""
          0
          1
          2
          """)

        input must interpretIdsAs(IdOnly, expected)
      }

      "return monotonic integers for each vector row" in {
        val input = ldjson("""
          [1, 2, 3]
          { "a": "hi", "b": { "c": null } }
          [{ "d": {} }]
          """)

        val expected = ldjson("""
          0
          1
          2
          """)

        input must interpretIdsAs(IdOnly, expected)
      }
    }

    "IncludeId" should {
      "wrap each scalar row in monotonic integers" in {
        val input = ldjson("""
          1
          "hi"
          true
          """)

        val expected = ldjson("""
          [0, 1]
          [1, "hi"]
          [2, true]
          """)

        input must interpretIdsAs(IncludeId, expected)
      }

      "wrap each vector row in monotonic integers" in {
        val input = ldjson("""
          [1, 2, 3]
          { "a": "hi", "b": { "c": null } }
          [{ "d": {} }]
          """)

        val expected = ldjson("""
          [0, [1, 2, 3]]
          [1, { "a": "hi", "b": { "c": null } }]
          [2, [{ "d": {} }]]
          """)

        input must interpretIdsAs(IncludeId, expected)
      }
    }

    def evalIds(idStatus: IdStatus, stream: JsonStream): JsonStream

    def interpretIdsAs(idStatus: IdStatus, expected: JsonStream) : Matcher[JsonStream] =
      bestSemanticEqual(expected) ^^ { str: JsonStream => evalIds(idStatus, str) }
  }

  trait WrapSpec extends JsonSpec {
    protected final type Wrap = ScalarStage.Wrap
    protected final val Wrap = ScalarStage.Wrap

    "wrap" should {
      "nest scalars" in {
        val input = ldjson("""
          1
          "hi"
          true
          """)

        val expected = ldjson("""
          { "foo": 1 }
          { "foo": "hi" }
          { "foo": true }
          """)

        input must wrapInto("foo")(expected)
      }

      "nest vectors" in {
        val input = ldjson("""
          [1, 2, 3]
          { "a": "hi", "b": { "c": null } }
          [{ "d": {} }]
          """)

        val expected = ldjson("""
          { "bar": [1, 2, 3] }
          { "bar": { "a": "hi", "b": { "c": null } } }
          { "bar": [{ "d": {} }] }
          """)

        input must wrapInto("bar")(expected)
      }

      "nest empty objects" in {
        val input = ldjson("""
          "a"
          {}
          []
          1
          """)

        val expected = ldjson("""
          { "bar": "a" }
          { "bar": {} }
          { "bar": [] }
          { "bar": 1 }
          """)

        input must wrapInto("bar")(expected)
      }
    }

    def evalWrap(wrap: Wrap, stream: JsonStream): JsonStream

    def wrapInto(name: String)(expected: JsonStream): Matcher[JsonStream] =
      bestSemanticEqual(expected) ^^ { str: JsonStream => evalWrap(Wrap(name), str)}
  }

  trait ProjectSpec extends JsonSpec {
    protected final type Project = ScalarStage.Project
    protected final val Project = ScalarStage.Project

    "project" should {
      "passthrough at identity" in {
        val input = ldjson("""
          1
          "two"
          false
          [1, 2, 3]
          { "a": 1, "b": "two" }
          []
          {}
          """)

        project(".", input) must resultIn(input)
      }

      "extract .a" in {
        val input = ldjson("""
          { "a": 1, "b": "two" }
          { "a": "foo", "b": "two" }
          { "a": true, "b": "two" }
          { "a": [], "b": "two" }
          { "a": {}, "b": "two" }
          { "a": [1, 2], "b": "two" }
          { "a": { "c": 3 }, "b": "two" }
          """)
        val expected = ldjson("""
          1
          "foo"
          true
          []
          {}
          [1, 2]
          { "c": 3 }
          """)

        project(".a", input) must resultIn(expected)
      }

      "extract .a.b" in {
        val input = ldjson("""
          { "a": { "b": 1 }, "b": "two" }
          { "a": { "b": "foo" }, "b": "two" }
          { "a": { "b": true }, "b": "two" }
          { "a": { "b": [] }, "b": "two" }
          { "a": { "b": {} }, "b": "two" }
          { "a": { "b": [1, 2] }, "b": "two" }
          { "a": { "b": { "c": 3 } }, "b": "two" }
          """)
        val expected = ldjson("""
          1
          "foo"
          true
          []
          {}
          [1, 2]
          { "c": 3 }
          """)

        project(".a.b", input) must resultIn(expected)
      }

      "extract .a[1]" in {
        val input = ldjson("""
          { "a": [3, 1], "b": "two" }
          { "a": [3, "foo"], "b": "two" }
          { "a": [3, true], "b": "two" }
          { "a": [3, []], "b": "two" }
          { "a": [3, {}], "b": "two" }
          { "a": [3, [1, 2]], "b": "two" }
          { "a": [3, { "c": 3 }], "b": "two" }
          """)
        val expected = ldjson("""
          1
          "foo"
          true
          []
          {}
          [1, 2]
          { "c": 3 }
          """)

        project(".a[1]", input) must resultIn(expected)
      }

      "extract [1]" in {
        val input = ldjson("""
          [0, 1]
          [0, "foo"]
          [0, true]
          [0, []]
          [0, {}]
          [0, [1, 2]]
          [0, { "c": 3 }]
          """)

        val expected = ldjson("""
          1
          "foo"
          true
          []
          {}
          [1, 2]
          { "c": 3 }
          """)

        project("[1]", input) must resultIn(expected)
      }

      "extract [1][0]" in {
        val input = ldjson("""
          [0, [1]]
          [0, ["foo"]]
          [0, [true]]
          [0, [[]]]
          [0, [{}]]
          [0, [[1, 2]]]
          [0, [{ "c": 3 }]]
          """)

        val expected = ldjson("""
          1
          "foo"
          true
          []
          {}
          [1, 2]
          { "c": 3 }
          """)

        project("[1][0]", input) must resultIn(expected)
      }

      "extract [1].a" in {
        val input = ldjson("""
          [0, { "a": 1 }]
          [false, { "a": "foo" }]
          [1, { "a": true }]
          [[], { "a": [] }]
          ["foo", { "a": {} }]
          [{}, { "a": [1, 2] }]
          [0, { "a": { "c": 3 } }]
          """)

        val expected = ldjson("""
          1
          "foo"
          true
          []
          {}
          [1, 2]
          { "c": 3 }
          """)

        project("[1].a", input) must resultIn(expected)
      }

      "elide rows not containing path" in {
        val input = ldjson("""
          { "x": 1 }
          { "x": 2, "y": 3 }
          { "y": 4, "z": 5 }
          ["a", "b"]
          4
          "seven"
          { "z": 4, "x": 8 }
          false
          { "y": "nope", "x": {} }
          { "one": 1, "two": 2 }
          """)

        val expected = ldjson("""
          1
          2
          8
          {}
          """)

        project(".x", input) must resultIn(expected)
      }

      "only extract paths starting from root" in {
        val input = ldjson("""
          { "z": "b", "x": { "y": 4 } }
          { "x": 2, "y": { "x": 1 } }
          { "a": { "x": { "z": false, "y": true } }, "b": "five" }
          { "x": { "y": 1, "z": 2 } }
          """)

        val expected = ldjson("""
          4
          1
          """)

        project(".x.y", input) must resultIn(expected)
      }
    }

    def evalProject(project: Project, stream: JsonStream): JsonStream

    def project(path: String, stream: JsonStream): JsonStream =
      evalProject(Project(CPath.parse(path)), stream)

    def resultIn(expected: JsonStream): Matcher[JsonStream] =
      bestSemanticEqual(expected)
  }

  trait MaskSpec extends JsonSpec {
    import ColumnType._

    protected final type Mask = ScalarStage.Mask
    protected final val Mask = ScalarStage.Mask

    "masks" should {
      "drop everything when empty" in {
        val input = ldjson("""
          1
          "hi"
          [1, 2, 3]
          { "a": "hi", "b": { "c": null } }
          true
          [{ "d": {} }]
          """)

        val expected = ldjson("")

        input must maskInto()(expected)
      }

      "retain two scalar types at identity" in {
        val input = ldjson("""
          1
          "hi"
          [1, 2, 3]
          { "a": "hi", "b": { "c": null } }
          true
          []
          [{ "d": {} }]
          """)

        val expected = ldjson("""
          1
          true
          """)

        input must maskInto("." -> Set(Number, Boolean))(expected)
      }

      "retain different sorts of numbers at identity" in {
        val input = ldjson("""
          42
          3.14
          null
          27182e-4
          "derp"
          """)

        val expected = ldjson("""
          42
          3.14
          27182e-4
          """)

        input must maskInto("." -> Set(Number))(expected)
      }

      "retain different sorts of objects at identity" in {
        val input = ldjson("""
          1
          "hi"
          [1, 2, 3]
          { "a": "hi", "b": { "c": null } }
          true
          {}
          [{ "d": {} }]
          { "a": true }
          """)

        val expected = ldjson("""
          { "a": "hi", "b": { "c": null } }
          {}
          { "a": true }
          """)

        input must maskInto("." -> Set(Object))(expected)
      }

      "retain different sorts of arrays at identity" in {
        val input = ldjson("""
          1
          "hi"
          [1, 2, 3]
          { "a": "hi", "b": { "c": null } }
          true
          []
          [{ "d": {} }]
          { "a": true }
          """)

        val expected = ldjson("""
          [1, 2, 3]
          []
          [{ "d": {} }]
          """)

        input must maskInto("." -> Set(Array))(expected)
      }

      "retain two scalar types at .a.b" in {
        val input = ldjson("""
          { "a": { "b": 1 } }
          null
          { "a": { "b": "hi" } }
          { "foo": true }
          { "a": { "b": [1, 2, 3] } }
          [1, 2, 3]
          { "a": { "b": { "a": "hi", "b": { "c": null } } } }
          { "a": { "c": 42 } }
          { "a": { "b": true } }
          { "a": { "b": [] } }
          { "a": { "b": [{ "d": {} }] } }
          """)

        val expected = ldjson("""
          { "a": { "b": 1 } }
          { "a": { "b": true } }
          """)

        input must maskInto(".a.b" -> Set(Number, Boolean))(expected)
      }

      "retain different sorts of numbers at .a.b" in {
        val input = ldjson("""
          { "a": { "b": 42 } }
          null
          { "foo": true }
          { "a": { "b": 3.14 } }
          [1, 2, 3]
          { "a": { "b": null } }
          { "a": { "b": 27182e-4 } }
          { "a": { "b": "derp" } }
          """)

        val expected = ldjson("""
          { "a": { "b": 42 } }
          { "a": { "b": 3.14 } }
          { "a": { "b": 27182e-4 } }
          """)

        input must maskInto(".a.b" -> Set(Number))(expected)
      }

      "retain different sorts of objects at .a.b" in {
        val input = ldjson("""
          { "a": { "b": 1 } }
          { "a": { "b": "hi" } }
          { "a": { "b": [1, 2, 3] } }
          { "a": { "b": { "a": "hi", "b": { "c": null } } } }
          { "a": { "b": true } }
          { "a": { "b": {} } }
          { "a": { "b": [{ "d": {} }] } }
          { "a": { "b": { "a": true } } }
          """)

        val expected = ldjson("""
          { "a": { "b": { "a": "hi", "b": { "c": null } } } }
          { "a": { "b": {} } }
          { "a": { "b": { "a": true } } }
          """)

        input must maskInto(".a.b" -> Set(Object))(expected)
      }

      "retain different sorts of arrays at .a.b" in {
        val input = ldjson("""
          { "a": { "b": 1 } }
          { "a": { "b": "hi" } }
          { "a": { "b": [1, 2, 3] } }
          { "a": { "b": { "a": "hi", "b": { "c": null } } } }
          { "a": { "b": true } }
          { "a": { "b": [] } }
          { "a": { "b": [{ "d": {} }] } }
          { "a": { "b": { "a": true } } }
          """)

        val expected = ldjson("""
          { "a": { "b": [1, 2, 3] } }
          { "a": { "b": [] } }
          { "a": { "b": [{ "d": {} }] } }
          """)

        input must maskInto(".a.b" -> Set(Array))(expected)
      }

      "discard unmasked structure" in {
        val input = ldjson("""
          { "a": { "b": 42, "c": true }, "c": [] }
          """)

        val expected = ldjson("""
          { "a": { "c": true } }
          """)

        input must maskInto(".a.c" -> Set(Boolean))(expected)
      }

      "compose disjunctively across paths" in {
        val input = ldjson("""
          { "a": { "b": 42, "c": true }, "c": [] }
          """)

        val expected = ldjson("""
          { "a": { "c": true }, "c": [] }
          """)

        input must maskInto(".a.c" -> Set(Boolean), ".c" -> Set(Array))(expected)
      }

      "compose disjunctively across suffix-overlapped paths" in {
        val input = ldjson("""
          { "a": { "x": 42, "b": { "c": true } }, "b": { "c": [] }, "c": [1, 2] }
          """)

        val expected = ldjson("""
          { "a": { "b": { "c": true } }, "b": { "c": [] } }
          """)

        input must maskInto(".a.b.c" -> Set(Boolean), ".b.c" -> Set(Array))(expected)
      }

      "compose disjunctively across paths where one side is false" in {
        val input = ldjson("""
          { "a": { "b": 42, "c": true } }
          """)

        val expected = ldjson("""
          { "a": { "c": true } }
          """)

        input must maskInto(".a.c" -> Set(Boolean), ".a" -> Set(Array))(expected)
      }

      "subsume inner by outer" in {
        val input = ldjson("""
          { "a": { "b": 42, "c": true }, "c": [] }
          """)

        val expected = ldjson("""
          { "a": { "b": 42, "c": true } }
          """)

        input must maskInto(".a.b" -> Set(Boolean), ".a" -> Set(Object))(expected)
      }

      "disallow the wrong sort of vector" in {
        val input = ldjson("""
          { "a": true }
          [1, 2, 3]
          """)

        val expected1 = ldjson("""
          { "a": true }
          """)

        val expected2 = ldjson("""
          [1, 2, 3]
          """)

        input must maskInto("." -> Set(Object))(expected1)
        input must maskInto("." -> Set(Array))(expected2)
      }

      "compact surrounding array" in {
        ldjson("[1, 2, 3]") must maskInto("[1]" -> Set(Number))(ldjson("[2]"))
      }

      "compact surrounding array with multiple values retained" in {
        val input = ldjson("""
          [1, 2, 3, 4, 5]
          """)

        val expected = ldjson("""
          [1, 3, 4]
          """)

        input must maskInto(
          "[0]" -> Set(Number),
          "[2]" -> Set(Number),
          "[3]" -> Set(Number))(expected)
      }

      "compact surrounding nested array with multiple values retained" in {
        val input = ldjson("""
          { "a": { "b": [1, 2, 3, 4, 5], "c" : null } }
          """)

        val expected = ldjson("""
          { "a": { "b": [1, 3, 4] } }
          """)

        input must maskInto(
          ".a.b[0]" -> Set(Number),
          ".a.b[2]" -> Set(Number),
          ".a.b[3]" -> Set(Number))(expected)
      }

      "compact array containing nested arrays with single nested value retained" in {
        val input = ldjson("""
          { "a": [[[1, 3, 5], "k"], "foo", { "b": [5, 6, 7], "c": [] }], "d": "x" }
          """)

        val expected = ldjson("""
          { "a": [{"b": [5, 6, 7] }] }
          """)

        input must maskInto(".a[2].b" -> Set(Array))(expected)
      }

      "remove object entirely when no values are retained" in {
        ldjson("""{ "a": 42 }""") must maskInto(".a" -> Set(Boolean))(ldjson(""))
      }

      "remove array entirely when no values are retained" in {
        ldjson("[42]") must maskInto("[0]" -> Set(Boolean))(ldjson(""))
      }

      "retain vector at depth and all recursive contents" in {
        val input = ldjson("""{ "a": { "b": { "c": { "e": true }, "d": 42 } } }""")
        input must maskInto(".a.b" -> Set(Object))(input)
      }
    }

    def evalMask(mask: Mask, stream: JsonStream): JsonStream

    def maskInto(
        masks: (String, Set[ColumnType])*)(
        expected: JsonStream)
        : Matcher[JsonStream] =
      bestSemanticEqual(expected) ^^ { str: JsonStream =>
        evalMask(Mask(Map(masks.map({ case (k, v) => CPath.parse(k) -> v }): _*)), str)
      }
  }

  trait PivotSpec extends JsonSpec {

    protected final type Pivot = ScalarStage.Pivot
    protected final val Pivot = ScalarStage.Pivot

    "pivot" should {
      "shift an array" >> {
        val input = ldjson("""
          [1, 2, 3]
          [4, 5, 6]
          [7, 8, 9, 10]
          [11]
          []
          [12, 13]
          """)

        "ExcludeId" >> {
          val expected = ldjson("""
            1
            2
            3
            4
            5
            6
            7
            8
            9
            10
            11
            12
            13
            """)

          input must pivotInto(IdStatus.ExcludeId, ColumnType.Array)(expected)
        }

        "IdOnly" >> {
          val expected = ldjson("""
            0
            1
            2
            0
            1
            2
            0
            1
            2
            3
            0
            0
            1
            """)

          input must pivotInto(IdStatus.IdOnly, ColumnType.Array)(expected)
        }

        "IncludeId" >> {
          val expected = ldjson("""
            [0, 1]
            [1, 2]
            [2, 3]
            [0, 4]
            [1, 5]
            [2, 6]
            [0, 7]
            [1, 8]
            [2, 9]
            [3, 10]
            [0, 11]
            [0, 12]
            [1, 13]
            """)

          input must pivotInto(IdStatus.IncludeId, ColumnType.Array)(expected)
        }
      }

      "shift an object" >> {
        val input = ldjson("""
          { "a": 1, "b": 2, "c": 3 }
          { "d": 4, "e": 5, "f": 6 }
          { "g": 7, "h": 8, "i": 9, "j": 10 }
          { "k": 11 }
          {}
          { "l": 12, "m": 13 }
          """)

        "ExcludeId" >> {
          val expected = ldjson("""
            1
            2
            3
            4
            5
            6
            7
            8
            9
            10
            11
            12
            13
            """)

          input must pivotInto(IdStatus.ExcludeId, ColumnType.Object)(expected)
        }

        "IdOnly" >> {
          val expected = ldjson("""
            "a"
            "b"
            "c"
            "d"
            "e"
            "f"
            "g"
            "h"
            "i"
            "j"
            "k"
            "l"
            "m"
            """)

          input must pivotInto(IdStatus.IdOnly, ColumnType.Object)(expected)
        }

        "IncludeId" >> {
          val expected = ldjson("""
            ["a", 1]
            ["b", 2]
            ["c", 3]
            ["d", 4]
            ["e", 5]
            ["f", 6]
            ["g", 7]
            ["h", 8]
            ["i", 9]
            ["j", 10]
            ["k", 11]
            ["l", 12]
            ["m", 13]
            """)

          input must pivotInto(IdStatus.IncludeId, ColumnType.Object)(expected)
        }
      }

      "preserve empty arrays as values of an array pivot" in {
        val input = ldjson("""
          [ 1, "two", [] ]
          [ [] ]
          [ [], 3, "four" ]
          """)

        val expected = ldjson("""
          1
          "two"
          []
          []
          []
          3
          "four"
        """)

        input must pivotInto(IdStatus.ExcludeId, ColumnType.Array)(expected)
      }

      "preserve empty objects as values of an object pivot" in {
        val input = ldjson("""
          { "1": 1, "2": "two", "3": {} }
          { "4": {} }
          { "5": {}, "6": 3, "7": "four" }
          """)

        val expected = ldjson("""
          1
          "two"
          {}
          {}
          {}
          3
          "four"
        """)

        input must pivotInto(IdStatus.ExcludeId, ColumnType.Object)(expected)
      }
    }

    def evalPivot(pivot: Pivot, stream: JsonStream): JsonStream

    def pivotInto(
        idStatus: IdStatus,
        structure: ColumnType.Vector)(
        expected: JsonStream)
        : Matcher[JsonStream] =
      bestSemanticEqual(expected) ^^ { str: JsonStream =>
        evalPivot(Pivot(idStatus, structure), str)
      }
  }

  trait CartesianSpec extends JsonSpec {

    protected final type Cartesian = ScalarStage.Cartesian
    protected final val Cartesian = ScalarStage.Cartesian

    "cartesian" should {
      // a0 as a1, b0 as b1, c0 as c1, d0 as d1
      "cross fields with no parse instructions" in {
        val input = ldjson("""
          { "a0": "hi", "b0": null, "c0": { "x": 42 }, "d0": [1, 2, 3] }
          """)

        val expected = ldjson("""
          { "a1": "hi", "b1": null, "c1": { "x": 42 }, "d1": [1, 2, 3] }
          """)

        val targets = Map(
          (CPathField("a1"), (CPathField("a0"), Nil)),
          (CPathField("b1"), (CPathField("b0"), Nil)),
          (CPathField("c1"), (CPathField("c0"), Nil)),
          (CPathField("d1"), (CPathField("d0"), Nil)))

        input must cartesianInto(targets)(expected)
      }

      // a0 as a1, b0 as b1
      "cross fields with no parse instructions ignoring extra fields" in {
        val input = ldjson("""
          { "a0": "hi", "b0": null, "c0": 42 }
          """)

        val expected = ldjson("""
          { "a1": "hi", "b1": null }
          """)

        val targets = Map(
          (CPathField("a1"), (CPathField("a0"), Nil)),
          (CPathField("b1"), (CPathField("b0"), Nil)))

        input must cartesianInto(targets)(expected)
      }

      // a0 as a1, b0 as b1, d0 as d1
      "cross fields with no parse instructions ignoring absent fields" in {
        val input = ldjson("""
          { "a0": "hi", "b0": null }
          """)

        val expected = ldjson("""
          { "a1": "hi", "b1": null }
          """)

        val targets = Map(
          (CPathField("a1"), (CPathField("a0"), Nil)),
          (CPathField("b1"), (CPathField("b0"), Nil)),
          (CPathField("d1"), (CPathField("d0"), Nil)))

        input must cartesianInto(targets)(expected)
      }

      // a0[_] as a1, b0 as b1, c0{_} as c1
      "cross fields with single pivot" in {
        import ScalarStage.Pivot

        val input = ldjson("""
          { "a0": [1, 2, 3], "b0": null, "c0": { "x": 4, "y": 5 } }
          """)

        val expected = ldjson("""
          { "a1": 1, "b1": null, "c1": 4 }
          { "a1": 1, "b1": null, "c1": 5 }
          { "a1": 2, "b1": null, "c1": 4 }
          { "a1": 2, "b1": null, "c1": 5 }
          { "a1": 3, "b1": null, "c1": 4 }
          { "a1": 3, "b1": null, "c1": 5 }
          """)

        val targets = Map(
          (CPathField("a1"),
            (CPathField("a0"), List(Pivot(IdStatus.ExcludeId, ColumnType.Array)))),
          (CPathField("b1"),
            (CPathField("b0"), Nil)),
          (CPathField("c1"),
            (CPathField("c0"), List(Pivot(IdStatus.ExcludeId, ColumnType.Object)))))

        input must cartesianInto(targets)(expected)
      }

      // a[_].x0.y0{_} as y, a[_].x1[_] as z, b{_:} as b, c as c
      "cross fields with multiple nested pivots" in {
        import ScalarStage.{Pivot, Project}

        val input = ldjson("""
          {
            "a": [ { "x0": { "y0": { "f": "eff", "g": "gee" }, "y1": { "h": 42 } }, "x1": [ "0", 0, null ] } ],
            "b": { "k1": null, "k2": null },
            "c": true
          }
          """)

        val expected = ldjson("""
          { "y": "eff", "z": "0" , "b": "k1", "c": true }
          { "y": "gee", "z": "0" , "b": "k1", "c": true }
          { "y": "eff", "z": 0   , "b": "k1", "c": true }
          { "y": "gee", "z": 0   , "b": "k1", "c": true }
          { "y": "eff", "z": null, "b": "k1", "c": true }
          { "y": "gee", "z": null, "b": "k1", "c": true }
          { "y": "eff", "z": "0" , "b": "k2", "c": true }
          { "y": "gee", "z": "0" , "b": "k2", "c": true }
          { "y": "eff", "z": 0   , "b": "k2", "c": true }
          { "y": "gee", "z": 0   , "b": "k2", "c": true }
          { "y": "eff", "z": null, "b": "k2", "c": true }
          { "y": "gee", "z": null, "b": "k2", "c": true }
          """)

        val targets = Map(
          (CPathField("y"),
            (CPathField("a"), List(
              Pivot(IdStatus.ExcludeId, ColumnType.Array),
              Project(CPath.parse("x0")),
              Project(CPath.parse("y0")),
              Pivot(IdStatus.ExcludeId, ColumnType.Object)))),
          (CPathField("z"),
            (CPathField("a"), List(
              Pivot(IdStatus.ExcludeId, ColumnType.Array),
              Project(CPath.parse("x1")),
              Pivot(IdStatus.ExcludeId, ColumnType.Array)))),
          (CPathField("b"),
            (CPathField("b"), List(
              Pivot(IdStatus.IdOnly, ColumnType.Object)))),
          (CPathField("c"),
            (CPathField("c"), Nil)))

        input must cartesianInto(targets)(expected)
      }

      "emit defined fields when some are undefined" in {
        import ScalarStage.{Mask, Pivot}

        val input = ldjson("""
          { "a": 1, "b": [ "two", "three" ] }
          { "a": 2, "b": { "x": "four", "y": "five" } }
          { "a": 3, "b": 42 }
          """)

        val expected = ldjson("""
          { "a": 1, "ba": "two" }
          { "a": 1, "ba": "three" }
          { "a": 2, "bm": "four" }
          { "a": 2, "bm": "five" }
          { "a": 3 }
          """)

        val targets = Map(
          (CPathField("a"), (CPathField("a"), Nil)),

          (CPathField("ba"), (CPathField("b"), List(
            Mask(Map(CPath.Identity -> Set(ColumnType.Array))),
            Pivot(IdStatus.ExcludeId, ColumnType.Array)))),

          (CPathField("bm"), (CPathField("b"), List(
            Mask(Map(CPath.Identity -> Set(ColumnType.Object))),
            Pivot(IdStatus.ExcludeId, ColumnType.Object)))))

        input must cartesianInto(targets)(expected)
      }
    }

    def evalCartesian(cartesian: Cartesian, stream: JsonStream): JsonStream

    def cartesianInto(
        cartouches: Map[CPathField, (CPathField, List[ScalarStage.Focused])])(
        expected: JsonStream)
        : Matcher[JsonStream] =
      bestSemanticEqual(expected) ^^ { str: JsonStream =>
        evalCartesian(Cartesian(cartouches), str)
      }
  }
}
