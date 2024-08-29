package com.dwolla.consul.smithy4s

import com.dwolla.test.HelloService
import org.http4s.Uri

trait FakeConsulUriResolverPlatform {
  protected val baseAuthority: Uri.Authority = UriAuthorityFromService(HelloService)
}
