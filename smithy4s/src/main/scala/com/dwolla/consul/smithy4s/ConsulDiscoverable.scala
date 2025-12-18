package com.dwolla.consul.smithy4s

import cats.syntax.all._
import com.dwolla.consul.ServiceName
import org.http4s.Uri
import org.http4s.syntax.all._

import scala.annotation.implicitNotFound

@implicitNotFound("Instances are only available for Smithy4s Services annotated with @discoverable")
sealed trait ConsulDiscoverable[Alg[_[_]]] {
  def host: Uri.Host
  def uriAuthority: Uri.Authority
  def uri: Uri
  def serviceName: ServiceName = ServiceName(host.value) // default implementation for MiMa compatibility, but it's never used in practice
}

object ConsulDiscoverable extends ConsulDiscoverablePlatform {
  def apply[Alg[_[_]] : ConsulDiscoverable]: ConsulDiscoverable[Alg] = implicitly

  def make[Alg[_[_]]](name: ServiceName, dest: Uri.Host): ConsulDiscoverable[Alg] = new ConsulDiscoverable[Alg] {
    override def host: Uri.Host = dest
    override def uriAuthority: Uri.Authority = Uri.Authority(host = host)
    override def uri: Uri = Uri(scheme = scheme"consul".some, authority = uriAuthority.some)
    override def serviceName: ServiceName = name
  }
}
