package com.dwolla.consul.smithy4s

import cats.effect.MonadCancelThrow
import org.http4s.client.Client
import org.http4s.syntax.all._
import smithy4s.{Endpoint, Service}

class ReconsulifyMiddleware[F[_] : MonadCancelThrow] extends Endpoint.Middleware[Client[F]] {
  override def prepare[Alg[_[_, _, _, _, _]]](service: Service[Alg])
                                             (endpoint: service.Endpoint[_, _, _, _, _]): Client[F] => Client[F] =
    underlying => Client { req =>
      underlying.run(req.withUri(req.uri.copy(scheme = Option(scheme"consul"))))
    }
}

object ReconsulifyMiddleware {
  def apply[F[_] : MonadCancelThrow]: Endpoint.Middleware[Client[F]] = new ReconsulifyMiddleware[F]
}
