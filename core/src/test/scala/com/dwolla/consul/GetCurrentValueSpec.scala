package com.dwolla.consul

import cats.laws.discipline.MonadTests
import munit.DisciplineSuite
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}

class GetCurrentValueSpec extends DisciplineSuite {
  private def genGetCurrentValue[F[_], A](implicit F: Arbitrary[F[A]]): Gen[GetCurrentValue[F, A]] =
    arbitrary[F[A]].map(GetCurrentValue(_))
  private implicit def arbGetCurrentValue[F[_], A](implicit F: Arbitrary[F[A]]): Arbitrary[GetCurrentValue[F, A]] = Arbitrary(genGetCurrentValue[F, A])

  checkAll("GetCurrentValue.MonadLaws", MonadTests[GetCurrentValue[Option, *]].monad[Int, Int, Int])
}
