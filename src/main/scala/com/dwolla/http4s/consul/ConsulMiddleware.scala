package com.dwolla.http4s.consul

import cats.effect._
import cats.effect.syntax.all._
import cats.syntax.all._
import org.http4s.Uri.Host
import org.http4s._
import org.http4s.client._
import org.http4s.syntax.all._
import org.typelevel.keypool.KeyPool
import org.typelevel.log4cats.Logger

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
   * @param client the `org.http4s.client.Client[F]` being wrapped, which will be used both to interact with the Consul API and also to make the eventual service requests
   */
  def apply[F[_] : Async : Logger](consulServiceDiscoveryAlg: ConsulServiceDiscoveryAlg[F])
                                  (client: Client[F]): Resource[F, Client[F]] =
    KeyPool.Builder(consulServiceDiscoveryAlg.authorityForService)
      .build
      .map { kp: KeyPool[F, ServiceName, GetCurrentValue[F, Uri.Authority]] =>
        Client { req: Request[F] =>
          (req.uri match {
            case Uri(Some(scheme), Some(Uri.Authority(_, service, _)), _, _, _) if scheme == scheme"consul" =>
              rewriteUri(kp)(req, service)
            case _ =>
              Logger[F].trace(s"⚡️ using original ${req.uri}").as(req.uri)
          })
            .toResource
            .flatMap { uri =>
              client.run(req.withUri(uri))
            }
        }
      }
      .flatTap(_ => Resource.unit.onFinalize(Logger[F].trace("👋 shutting down ConsulMiddleware")))

  private def rewriteUri[F[_] : Logger : MonadCancelThrow](keyPool: KeyPool[F, ServiceName, GetCurrentValue[F, Uri.Authority]])
                                                          (req: Request[F],
                                                           service: Host): F[Uri] =
    Logger[F].trace(s"💫 rewriting ${req.uri}") >>
      keyPool
        .take(ServiceName(service.value))
        .use(_.value.fa)
        .map(auth => req.uri.copy(scheme = Uri.Scheme.http.some, authority = auth.some))
        .flatTap(newUri => Logger[F].trace(s"  rewrote ${req.uri} to $newUri"))
}
