package com.dwolla.consul

import cats.syntax.all._
import munit.ScalaCheckSuite
import org.http4s.ParseFailure
import org.http4s.QueryParameterValue
import org.scalacheck.Prop._
import org.scalacheck.Gen

import scala.concurrent.duration._

class WaitPeriodSpec extends ScalaCheckSuite {
  property("WaitPeriod decoder should parse seconds") {
    forAll(Gen.chooseNum(0, Int.MaxValue)) { (seconds: Int) =>
      val input = s"${seconds}s"
      val result = WaitPeriod.waitQueryParamDecoder.decode(QueryParameterValue(input))
      assertEquals(result, WaitPeriod(seconds.seconds).validNel)
    }
  }

  property("WaitPeriod decoder should parse minutes") {
    forAll(Gen.chooseNum(0, Int.MaxValue / 60)) { (minutes: Int) =>
      val input = s"${minutes}m"
      val result = WaitPeriod.waitQueryParamDecoder.decode(QueryParameterValue(input))
      assertEquals(result, WaitPeriod((minutes * 60).seconds).validNel)
    }
  }

  property("WaitPeriod decoder should fail on invalid input") {
    val invalidWaitPeriodGen: Gen[String] = {
      val symbols = " !@#$%^&*()_+-=[]{};':\",./<>?\\|"
      val letters = "abcdefghijklnopqrtuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
      val nonUnits = letters + symbols + "0123456789"

      Gen.oneOf(
        // Numbers ending in anything except s or m
        for {
          d <- Gen.numStr
          u <- Gen.oneOf(nonUnits)
        } yield d + u,

        // Alphanumeric strings without valid numeric prefix for units
        for {
          prefix <- Gen.alphaStr
          nonDigit <- Gen.oneOf(letters)
          suffix <- Gen.alphaStr
          unit <- Gen.oneOf("s", "m")
        } yield prefix + nonDigit + suffix + unit,

        // Empty string
        Gen.const(""),
      )
    }

    forAll(invalidWaitPeriodGen) { (input: String) =>
      assertEquals(
        WaitPeriod.waitQueryParamDecoder.decode(QueryParameterValue(input)),
        ParseFailure("Query decoding WaitPeriod failed", s"$input (of class java.lang.String)").invalidNel
      )
    }
  }
}
