package com.dwolla.consul.smithy4s

import org.http4s.Uri
import org.http4s.syntax.all.*

trait ConsulMiddlewareSpecPlatform {
  // TODO replace stub with macro-derived implementation once Scala 3 macro is available
  val serviceUri: Uri = uri"consul://hello-world"
}
