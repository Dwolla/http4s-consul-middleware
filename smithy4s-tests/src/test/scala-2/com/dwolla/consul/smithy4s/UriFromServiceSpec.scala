package com.dwolla.consul.smithy4s

import com.comcast.ip4s._
import munit.FunSuite
import org.http4s.Uri

class UriFromServiceSpec extends FunSuite {
  test("Service URI is derived from Smithy hint at compile time") {
    import org.http4s.syntax.all._

    val uri = UriFromService(com.dwolla.test.HelloService)
    assertEquals(uri, uri"consul://hello-world")
  }

  test("Service Host is derived from Smithy hint at compile time") {
    val uri = HostFromService(com.dwolla.test.HelloService)
    assertEquals(uri, Uri.Host.fromIp4sHost(host"hello-world"))
  }

  test("Service URI.Authority is derived from Smithy hint at compile time") {
    val uri = UriAuthorityFromService(com.dwolla.test.HelloService)
    assertEquals(uri, Uri.Authority(host = Uri.Host.fromIp4sHost(host"hello-world")))
  }

  test("unannotated Smithy services are rejected at compile time") {
    assert {
      compileErrors("UriFromService(com.dwolla.test.UnannotatedService)")
        .contains("could not find Discoverable hint for com.dwolla.test.UnannotatedService")
    }
  }
}

trait NotASmithy4sService[F[_]]
object NotASmithy4sService
trait NotASmithy4sServiceAndHasNoCompanionObject[F[_]]
