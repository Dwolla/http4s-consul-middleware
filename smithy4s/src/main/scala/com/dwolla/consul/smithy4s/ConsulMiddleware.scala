package com.dwolla.consul.smithy4s

import cats.effect.syntax.all._
import cats.effect.{Trace => _, _}
import com.dwolla.consul.{ConsulServiceDiscoveryAlg, ConsulUriResolver}
import natchez.Trace
import org.http4s.Request
import org.http4s.client.Client
import org.http4s.syntax.all._
import org.typelevel.log4cats.LoggerFactory
import smithy4s.{Endpoint, Service}

/**
 * A Smithy4s middleware to rewrite URIs where the Authority section is a Consul service name.
 * The given [[ConsulUriResolver]] is used to rewrite the URI on each request.
 *
 * @param resolver [[ConsulUriResolver]] responsible for rewriting a `consul://service/path` URI to a resolved HTTP URI
 */
class ConsulMiddleware[F[_] : MonadCancelThrow](resolver: ConsulUriResolver[F]) extends Endpoint.Middleware[Client[F]] {
  override def prepare[Alg[_[_, _, _, _, _]]](service: Service[Alg])
                                             (endpoint: service.Endpoint[_, _, _, _, _]): Client[F] => Client[F] =
    client => Client { (req: Request[F]) =>
      resolver.resolve(req.uri.copy(scheme = Option(scheme"consul")))
        .toResource
        .flatMap { uri =>
          client.run(req.withUri(uri))
        }
    }
}

object ConsulMiddleware {
  /**
   * Builds a Smithy4s middleware to rewrite URIs where the Authority section is a Consul service name.
   * The given [[ConsulServiceDiscoveryAlg]] is used to build a [[ConsulUriResolver]] that will resolve
   * `consul://service/path` to a normalized form.
   *
   * @param consulServiceDiscoveryAlg [[ConsulServiceDiscoveryAlg]] used to build a [[ConsulUriResolver]]
   */
  def apply[F[_] : Temporal : LoggerFactory : Trace](consulServiceDiscoveryAlg: ConsulServiceDiscoveryAlg[F]): Resource[F, ConsulMiddleware[F]] =
    ConsulUriResolver(consulServiceDiscoveryAlg)
      .map(new ConsulMiddleware(_))
}
