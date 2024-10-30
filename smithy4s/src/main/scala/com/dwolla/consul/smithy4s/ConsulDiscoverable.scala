package com.dwolla.consul.smithy4s

import org.http4s.Uri
import org.http4s.Uri.Host

import scala.annotation.implicitNotFound

@implicitNotFound("Instances are only available for Smithy4s Services annotated with @discoverable")
trait ConsulDiscoverable[Alg[_[_]]] {
  def host: Host
  def uriAuthority: Uri.Authority
  def uri: Uri
}

object ConsulDiscoverable extends ConsulDiscoverablePlatform {
  def apply[Alg[_[_]] : ConsulDiscoverable]: ConsulDiscoverable[Alg] = implicitly
}
