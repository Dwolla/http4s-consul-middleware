package com.dwolla.consul.smithy4s

import cats.effect.MonadCancelThrow
import com.dwolla.consul.smithy.{Discoverable, ServiceName}
import org.http4s.Request
import org.http4s.client.Client
import org.http4s.syntax.all._
import smithy4s.{Endpoint, Service}

/**
 * Rewrites the URI scheme on requests so that it is `consul://` when passed to the
 * underlying client. This works with [[com.dwolla.consul.http4s.ConsulMiddleware]] to
 * use Consul to discover the actual protocol, hostname, and port of the service's
 * implementation.
 *
 * '''Note: you should probably use [[ConsulMiddleware]] by itself''', instead of wrapping an
 * http4s `Client[F]` with [[com.dwolla.consul.http4s.ConsulMiddleware]] and using this
 * middleware, because it does everything in one step.
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
 */
class ReconsulifyMiddleware[F[_] : MonadCancelThrow] extends Endpoint.Middleware[Client[F]] {
  override def prepare[Alg[_[_, _, _, _, _]]](service: Service[Alg])
                                             (endpoint: service.Endpoint[_, _, _, _, _]): Client[F] => Client[F] =
    client => Client { (req: Request[F]) =>
      service.hints.get(Discoverable.tagInstance) match {
        case Some(Discoverable(ServiceName(serviceName))) if req.uri.authority.map(_.host.value).contains(serviceName) =>
          client.run(req.withUri(req.uri.copy(scheme = Option(scheme"consul"))))
        case _ => client.run(req)
      }
    }
}

object ReconsulifyMiddleware {
  def apply[F[_] : MonadCancelThrow]: Endpoint.Middleware[Client[F]] = new ReconsulifyMiddleware[F]
}
