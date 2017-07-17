/*
 * Copyright 2014–2017 SlamData Inc.
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

package quasar.mimir

import slamdata.Predef._

import quasar.Data
import quasar.blueeyes.json.JValue
import quasar.contrib.scalacheck.gen
import quasar.fp.tree.{BinaryArg, TernaryArg, UnaryArg}
import quasar.precog.common.RValue
import quasar.qscript._
import quasar.std.StdLibSpec

import matryoshka.data.Fix

import org.scalacheck.{Arbitrary, Gen}

import org.specs2.execute.Result
import org.specs2.specification.{AfterAll, Scope}

import java.time.LocalDate

import matryoshka.implicits._
import matryoshka.patterns._

import scalaz.Id
import scalaz.syntax.applicative._

import java.nio.file.Files

import scala.concurrent.Await
import scala.concurrent.duration._

class MimirStdLibSpec extends StdLibSpec with PrecogCake {
  import scala.concurrent.ExecutionContext.Implicits.global

  private def run[A](cake: Precog)(
    freeMap: FreeMapA[Fix, A],
    hole: A => cake.trans.TransSpec1)
      : cake.trans.TransSpec1 =
    freeMap.cataM[Id.Id, cake.trans.TransSpec1](
      interpretM(hole(_).point[Id.Id], new MapFuncPlanner[Fix, Id.Id].plan(cake)))

  private def evaluate(cake: Precog)(transSpec: cake.trans.TransSpec1): cake.Table =
    cake.Table.constString(Set("")).transform(transSpec)

  private def dataToTransSpec(cake: Precog)(data: Data): cake.trans.TransSpec1 = {
    val jvalue: JValue = JValue.fromData(data)
    val rvalue: RValue = RValue.fromJValue(jvalue)
    cake.trans.transRValue(rvalue, cake.trans.TransSpec1.Id)
  }

  def runner(cake: Precog) = new MapFuncStdLibTestRunner {
    def nullaryMapFunc(prg: FreeMapA[Fix, Nothing], expected: Data): Result = ???

    def unaryMapFunc(prg: FreeMapA[Fix, UnaryArg], arg: Data, expected: Data): Result = {
      val table: cake.Table = evaluate(cake)(
        run[UnaryArg](cake)(prg, _.fold(dataToTransSpec(cake)(arg))))
      val actual = Await.result(table.toJson.map(_.toList.map(JValue.toData)), Duration.Inf)
      (actual must_== List(expected)).toResult
    }

    def binaryMapFunc(prg: FreeMapA[Fix, BinaryArg], arg1: Data, arg2: Data, expected: Data): Result = {
      val table: cake.Table =
        evaluate(cake)(run[BinaryArg](cake)(prg, _.fold(
	  dataToTransSpec(cake)(arg1),
	  dataToTransSpec(cake)(arg2))))
      val actual = Await.result(table.toJson.map(_.toList.map(JValue.toData)), Duration.Inf)
      (actual must_== List(expected)).toResult
    }

    def ternaryMapFunc(prg: FreeMapA[Fix, TernaryArg], arg1: Data, arg2: Data, arg3: Data, expected: Data): Result = {
      val table: cake.Table =
        evaluate(cake)(run[TernaryArg](cake)(prg, _.fold(
	  dataToTransSpec(cake)(arg1),
	  dataToTransSpec(cake)(arg2),
	  dataToTransSpec(cake)(arg3))))
      val actual = Await.result(table.toJson.map(_.toList.map(JValue.toData)), Duration.Inf)
      (actual must_== List(expected)).toResult
    }
    
    def decDomain: Gen[BigDecimal] = Arbitrary.arbitrary[Long].map(BigDecimal(_))
    def intDomain: Gen[BigInt] = Arbitrary.arbitrary[Long].map(BigInt(_))
    def stringDomain: Gen[String] = gen.printableAsciiString

    def dateDomain: Gen[LocalDate] =
      Gen.choose(
        LocalDate.of(1, 1, 1).toEpochDay,
        LocalDate.of(9999, 12, 31).toEpochDay
      ) ∘ (LocalDate.ofEpochDay(_))
  }

  tests(runner(cake))
}

trait PrecogCake extends Scope with AfterAll {
  val cake = Precog(Files.createTempDirectory("mimir").toFile()).unsafePerformSync

  def afterAll(): Unit = Await.result(cake.shutdown, Duration.Inf)
}
