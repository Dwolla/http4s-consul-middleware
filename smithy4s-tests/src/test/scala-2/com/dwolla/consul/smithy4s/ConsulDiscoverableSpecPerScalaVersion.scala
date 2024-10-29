package com.dwolla.consul.smithy4s

import com.dwolla.test.HelloService
import munit.FunSuite
import org.http4s.Uri
import org.http4s.Uri.Host

trait ConsulDiscoverableSpecPerScalaVersion { self: FunSuite =>
  val serviceUri: Uri = UriFromService(HelloService)
  private val serviceHost: Host = HostFromService(HelloService)
  private val serviceUriAuthority: Uri.Authority = UriAuthorityFromService(HelloService)

  test("ConsulDiscoverable typeclass macro constructs a working instance of the typeclass") {
    assertEquals(ConsulDiscoverable[HelloService].host, serviceHost)
    assertEquals(ConsulDiscoverable[HelloService].uriAuthority, serviceUriAuthority)
    assertEquals(ConsulDiscoverable[HelloService].uri, serviceUri)
  }

  test("ConsulDiscoverable typeclass macro returns no instance when the type parameter isn't a Smithy Service") {
    assertEquals(
      compileErrors("""ConsulDiscoverable[NotASmithy4sService]"""),
      """error: Instances are only available for Smithy4s Services annotated with @discoverable
        |ConsulDiscoverable[NotASmithy4sService]
        |                  ^""".stripMargin
    )
  }
}
