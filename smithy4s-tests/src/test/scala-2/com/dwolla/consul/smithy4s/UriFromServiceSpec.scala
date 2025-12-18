package com.dwolla.consul.smithy4s

import cats.syntax.all._
import com.dwolla.consul.ServiceName
import com.dwolla.consul.smithy.Discoverable
import com.dwolla.test.HelloService
import munit.FunSuite
import org.http4s.Uri
import org.http4s.syntax.all._

class UriFromServiceSpec extends FunSuite {
  test("Service URI is derived from Smithy hint at compile time") {
    val uri = UriFromService(com.dwolla.test.HelloService)
    assertEquals(uri.some, HelloService.hints.get[Discoverable].map(d => Uri(scheme = scheme"consul".some, authority = Uri.Authority(host = Uri.RegName(d.serviceName.value)).some)))
  }

  test("Service Host is derived from Smithy hint at compile time") {
    val uri = HostFromService(com.dwolla.test.HelloService)
    assertEquals(uri.some, HelloService.hints.get[Discoverable].map(d => Uri.RegName(d.serviceName.value)))
  }

  test("Service URI.Authority is derived from Smithy hint at compile time") {
    val uri = UriAuthorityFromService(com.dwolla.test.HelloService)
    assertEquals(uri.some, HelloService.hints.get[Discoverable].map(d => Uri.Authority(host = Uri.RegName(d.serviceName.value))))
  }

  test("ServiceName is derived from Smithy hint at compile time") {
    val serviceName = ServiceNameFromService(com.dwolla.test.HelloService)
    assertEquals(serviceName.some, HelloService.hints.get[Discoverable].map(d => ServiceName(d.serviceName.value)))
  }

  test("unannotated Smithy services are rejected at compile time") {
    assert {
      compileErrors("UriFromService(com.dwolla.test.UnannotatedService)")
        .contains("could not find Discoverable hint for com.dwolla.test.UnannotatedService")
    }
  }
}
