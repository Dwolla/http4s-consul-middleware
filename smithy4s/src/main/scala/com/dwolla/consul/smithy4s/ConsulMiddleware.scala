package com.dwolla.consul.smithy4s

import cats.effect.syntax.all._
import cats.effect.{Trace => _, _}
import com.dwolla.consul.smithy._
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
 * In order for a request's URI to be rewritten, the service on which this middleware is
 * installed must be annotated with `@discoverable`, and the service name parameter on the
 * `@discoverable` trait must match the request's hostname.
 *
 * In other words, for the `HelloService` defined below, requests must have a hostname of
 * `hello-world` to be rewritten using Consul service discovery. Otherwise, the requests
 * will be ignored by this middleware and Smithy4s will attempt to use them directly.
 *
 * {{{
 * $$version: "2.0"
 * namespace com.dwolla.test
 * use com.dwolla.consul.smithy#discoverable
 *
 * @discoverable(serviceName: "hello-world")
 * service HelloService {
 *     operations: [Greet]
 * }
 * }}}
 *
 * @param resolver [[ConsulUriResolver]] responsible for rewriting a `consul://service/path` URI to a resolved HTTP URI
 */
class ConsulMiddleware[F[_] : MonadCancelThrow](resolver: ConsulUriResolver[F]) extends Endpoint.Middleware[Client[F]] {
  override def prepare[Alg[_[_, _, _, _, _]]](service: Service[Alg])
                                             (endpoint: service.Endpoint[_, _, _, _, _]): Client[F] => Client[F] =
    client => Client { (req: Request[F]) =>
      service.hints.get(Discoverable.tagInstance) match {
        case Some(Discoverable(ServiceName(serviceName))) if req.uri.authority.map(_.host.value).contains(serviceName) =>
          resolver.resolve(req.uri.copy(scheme = Option(scheme"consul")))
            .toResource
            .map(req.withUri)
            .flatMap(client.run)

        case _ => client.run(req)
      }
    }
}

object ConsulMiddleware {
  /**
   * Builds a Smithy4s middleware to rewrite URIs where the Authority section is a Consul service name.
   * The given [[ConsulServiceDiscoveryAlg]] is used to build a [[ConsulUriResolver]] that will resolve
   * `consul://service/path` to a normalized form.
   *
   * See the [[ConsulMiddleware]] Scaladoc for some important notes.
   *
   * @param consulServiceDiscoveryAlg [[ConsulServiceDiscoveryAlg]] used to build a [[ConsulUriResolver]]
   */
  def apply[F[_] : Temporal : LoggerFactory : Trace](consulServiceDiscoveryAlg: ConsulServiceDiscoveryAlg[F]): Resource[F, ConsulMiddleware[F]] =
    ConsulUriResolver(consulServiceDiscoveryAlg)
      .map(new ConsulMiddleware(_))
}
