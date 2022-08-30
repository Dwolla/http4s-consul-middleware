package com.dwolla.consul

import org.scalacheck.{Arbitrary, Gen}

trait ConsulArbitraries {
  val genServiceName: Gen[ServiceName] = Gen.identifier.map(ServiceName(_))
  implicit val arbServiceName: Arbitrary[ServiceName] = Arbitrary(genServiceName)

  val genConsulIndex: Gen[ConsulIndex] = Gen.identifier.map(ConsulIndex(_))
  implicit val arbConsulIndex: Arbitrary[ConsulIndex] = Arbitrary(genConsulIndex)
}

object arbitraries extends ConsulArbitraries
