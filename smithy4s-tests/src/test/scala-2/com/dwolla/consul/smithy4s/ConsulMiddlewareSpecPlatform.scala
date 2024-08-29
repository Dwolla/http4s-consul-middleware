package com.dwolla.consul.smithy4s

import com.dwolla.test.HelloService
import org.http4s.Uri

trait ConsulMiddlewareSpecPlatform {
  val serviceUri: Uri = UriFromService(HelloService)
}
