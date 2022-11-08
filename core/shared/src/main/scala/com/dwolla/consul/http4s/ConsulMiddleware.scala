package com.dwolla.consul.http4s

import cats.effect.syntax.all._
import cats.effect.{Trace => _, _}
import com.dwolla.consul._
import natchez.Trace
import org.http4s._
import org.http4s.client._
import org.typelevel.log4cats._

object ConsulMiddleware {
  /**
   * Returns a new `org.http4s.client.Client[F]` that will rewrite URIs of the
   * form `consul://{service}` by looking up the `service` using Consul's
   * HTTP API.
   *
   * This uses a `KeyPool` and [[ConsulServiceDiscoveryAlg]] to monitor changes
   * to the list of services in Consul in the background, so that requests after
   * the first one are made quickly and don't have to wait for a Consul lookup
   * before a URI can be rewritten.
   *
   * @param consulServiceDiscoveryAlg: the [[ConsulServiceDiscoveryAlg]] used to construct the background processes
   * @param client the `org.http4s.client.Client[F]` being wrapped, which will be used to make the eventual service requests
   */
  def apply[F[_] : Async : LoggerFactory : Trace](consulServiceDiscoveryAlg: ConsulServiceDiscoveryAlg[F])
                                                 (client: Client[F]): Resource[F, Client[F]] =
    LoggerFactory[F]
      .create(LoggerName("com.dwolla.consul.http4s.ConsulMiddleware"))
      .toResource
      .flatMap { implicit l =>
        ConsulUriResolver(consulServiceDiscoveryAlg)
          .map { resolver: ConsulUriResolver[F] =>
            Client { req: Request[F] =>
              resolver.resolve(req.uri)
                .toResource
                .flatMap { uri =>
                  client.run(req.withUri(uri))
                }
            }
          }
          .onFinalize(Logger[F].trace("👋 shutting down ConsulMiddleware"))
      }

  @deprecated("used traced version", "0.2.0")
  def apply[F[_]](consulServiceDiscoveryAlg: ConsulServiceDiscoveryAlg[F],
                  client: Client[F],
                  F: Async[F],
                  L: LoggerFactory[F]): Resource[F, Client[F]] = {
    ConsulMiddleware(consulServiceDiscoveryAlg)(client)(F, L, natchez.Trace.Implicits.noop(F))
  }

}
