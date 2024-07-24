package com.dwolla.consul

import cats.effect.syntax.all._
import cats.effect.{Trace => _, _}
import cats.syntax.all._
import cats.~>
import natchez.Trace
import natchez.noop.NoopTrace
import org.http4s.Uri.Host
import org.http4s._
import org.http4s.syntax.all._
import org.typelevel.keypool.KeyPool
import org.typelevel.log4cats.{Logger, LoggerFactory, LoggerName}

trait ConsulUriResolver[F[_]] { self =>
  def resolve(uri: Uri): F[Uri]

  def mapK[G[_]](fk: F ~> G): ConsulUriResolver[G] = new ConsulUriResolver[G] {
    override def resolve(uri: Uri): G[Uri] =
      fk(self.resolve(uri))
  }
}

object ConsulUriResolver {
  private final val name = "com.dwolla.consul.ConsulUriResolver"

  def apply[F[_] : Temporal : LoggerFactory : Trace](backgroundResolver: ConsulServiceDiscoveryAlg[F]): Resource[F, ConsulUriResolver[F]] =
    LoggerFactory[F]
      .create(LoggerName(name))
      .toResource
      .flatMap { implicit l =>
        KeyPool.Builder(backgroundResolver.authorityForService)
          .build
          .map(ConsulUriResolver(_))
      }

  private def apply[F[_] : MonadCancelThrow : Logger : Trace](backgroundResolver: KeyPool[F, ServiceName, F[Uri.Authority]]): ConsulUriResolver[F] =
    new ConsulUriResolver[F] {
      override def resolve(uri: Uri): F[Uri] = uri match {
        case Uri(Some(scheme), Some(Uri.Authority(_, service, _)), _, _, _) if scheme == scheme"consul" =>
          Trace[F].span(s"$name.resolve") {
            rewriteUri(uri, service)
              .flatTap { resolved =>
                Trace[F].put("uri.original" -> uri.toString(), "uri.resolved" -> resolved.toString())
              }
          }
        case _ =>
          Logger[F].trace(s"⚡️ using original $uri").as(uri)
      }

      private def rewriteUri(source: Uri,
                             service: Host): F[Uri] =
        Logger[F].trace(s"💫 rewriting $source") >>
          backgroundResolver
            .take(ServiceName(service.value))
            .use(_.value)
            .map(auth => source.copy(scheme = Uri.Scheme.http.some, authority = auth.some))
            .flatTap(newUri => Logger[F].trace(s"  rewrote $source to $newUri"))

    }

  @deprecated("maintained for binary compatibility: this version doesn't place background traces in the proper scope", "0.3.1")
  def apply[F[_]](backgroundResolver: ConsulServiceDiscoveryAlg[F],
                  F: Async[F],
                  L: LoggerFactory[F]): Resource[F, ConsulUriResolver[F]] =
    apply(backgroundResolver)(F, L, NoopTrace()(F))

  @deprecated("maintained for binary compatibility: this version requires a higher capability constraint than is actually required", "0.3.2")
  def apply[F[_]](backgroundResolver: ConsulServiceDiscoveryAlg[F],
                  F: Async[F],
                  L: LoggerFactory[F],
                  T: Trace[F]): Resource[F, ConsulUriResolver[F]] =
    apply(backgroundResolver)(F, L, T)

  @deprecated("maintained for binary compatibility: this version requires a higher capability constraint than is actually required", "0.3.2")
  private[consul] def apply[F[_]](backgroundResolver: KeyPool[F, ServiceName, F[Uri.Authority]],
                                  F: Async[F],
                                  L: Logger[F],
                                  T: Trace[F]
                                 ): ConsulUriResolver[F] =
    apply(backgroundResolver)(using F, L, T)
}
