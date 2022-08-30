package com.dwolla.consul

import cats.effect._
import cats.syntax.all._
import org.http4s.Uri.Host
import org.http4s._
import org.http4s.syntax.all._
import org.typelevel.keypool.KeyPool
import org.typelevel.log4cats.Logger

trait ConsulUriResolver[F[_]] {
  def resolve(uri: Uri): F[Uri]
}

object ConsulUriResolver {
  def apply[F[_] : Async : Logger](backgroundResolver: ConsulServiceDiscoveryAlg[F]): Resource[F, ConsulUriResolver[F]] =
    KeyPool.Builder(backgroundResolver.authorityForService)
      .build
      .map(ConsulUriResolver(_))

  def apply[F[_] : Async : Logger](backgroundResolver: KeyPool[F, ServiceName, F[Uri.Authority]]): ConsulUriResolver[F] =
    new ConsulUriResolver[F] {
      override def resolve(uri: Uri): F[Uri] = uri match {
        case Uri(Some(scheme), Some(Uri.Authority(_, service, _)), _, _, _) if scheme == scheme"consul" =>
          rewriteUri(uri, service)
        case _ =>
          Logger[F].trace(s"⚡️ using original ${uri}").as(uri)
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
}
