package com.dwolla.http4s.consul

import com.dwolla.http4s.consul.arbitraries._
import munit.ScalaCheckSuite
import org.http4s.Uri
import org.http4s.laws.discipline.arbitrary.http4sTestingArbitraryForUri
import org.scalacheck.Prop

import scala.concurrent.duration.FiniteDuration

class ConsulServiceDiscoveryAlgSpec extends ScalaCheckSuite {
  test("Consul service lookup URI construction") {
    Prop.forAll { (consulBase: Uri,
                   serviceName: ServiceName,
                   index: Option[ConsulIndex],
                   longPollTimeout: FiniteDuration) => 
      val output = ConsulServiceDiscoveryAlg.serviceListUri(consulBase, serviceName, index, longPollTimeout)

      val expected = (consulBase / "v1" / "health" / "service" / serviceName.value)
        .withQueryParam("passing")
        .withOptionQueryParam("index", index)
        .withQueryParam("wait", s"${longPollTimeout.toSeconds}s")

      assertEquals(output, expected)
    }
  }
}
