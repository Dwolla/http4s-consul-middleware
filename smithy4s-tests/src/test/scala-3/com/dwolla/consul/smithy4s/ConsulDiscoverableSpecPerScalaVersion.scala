package com.dwolla.consul.smithy4s

import munit._

// TODO implement tests with macro-derived implementation once Scala 3 macro is available
trait ConsulDiscoverableSpecPerScalaVersion { self: FunSuite =>
  test("ConsulDiscoverable typeclass macro returns no instance when the type parameter isn't a Smithy Service") {
    assertEquals(
      compileErrors("""ConsulDiscoverable[NotASmithy4sService]"""),
      """error: Instances are only available for Smithy4s Services annotated with @discoverable
        |ConsulDiscoverable[NotASmithy4sService]
        |                                      ^""".stripMargin
    )
  }
}
