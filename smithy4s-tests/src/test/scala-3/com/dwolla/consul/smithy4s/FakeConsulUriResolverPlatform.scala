package com.dwolla.consul.smithy4s

import org.http4s.*

trait FakeConsulUriResolverPlatform {
  // TODO replace stub with macro-derived implementation once Scala 3 macro is available
  protected val baseAuthority: Uri.Authority = Uri.Authority(host = Uri.RegName("hello-world"))
}
