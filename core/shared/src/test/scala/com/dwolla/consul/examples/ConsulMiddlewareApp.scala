package com.dwolla.consul
package examples

import cats.effect._
import cats.effect.std.Random
import cats.syntax.all._
import com.dwolla.consul._
import com.dwolla.consul.examples.ConsulMiddlewareApp.consulAwareClient
import com.dwolla.consul.http4s.ConsulMiddleware
import fs2.Stream
import org.http4s.Method.GET
import org.http4s._
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.syntax.all._
import org.typelevel.log4cats.{Logger, LoggerFactory}

import scala.concurrent.duration._

class ConsulMiddlewareApp[F[_] : Async : LoggerFactory] extends Http4sClientDsl[F] {
  val exampleConsulUri: Uri = uri"consul://httpd/"

  def run: F[Unit] =
    Random.scalaUtilRandom[F].flatMap { implicit random =>
      LoggerFactory[F]
        .create
        .flatMap { implicit logger: Logger[F] =>
          (for {
            client <- Stream.resource(consulAwareClient[F])
            _ <- Stream.repeatEval {
              client
                .successful(GET(exampleConsulUri))
                .flatMap {
                  case true => Logger[F].info("🔮 success")
                  case false => Logger[F].info("🔮 failure")
                }
            }
              .metered(2.seconds)
          } yield ())
            .take(1)
            .compile
            .drain
        }
    }
}

object ConsulMiddlewareApp extends ConsulMiddlewareAppPlatform {

  private[ConsulMiddlewareApp] def consulAwareClient[F[_] : Async : Random : LoggerFactory]: Resource[F, Client[F]] =
    (consulServiceDiscoveryAlg[F], normalClient[F])
      .parMapN(ConsulMiddleware(_)(_))
      .flatten

  private def consulServiceDiscoveryAlg[F[_] : Async : Random : LoggerFactory]: Resource[F, ConsulServiceDiscoveryAlg[F]] =
    longPollClient[F].evalMap(ConsulServiceDiscoveryAlg(uri"http://localhost:8500", 1.minute, _))

  private def longPollClient[F[_] : Async]: Resource[F, Client[F]] = clientWithTimeout(75.seconds)

  private def normalClient[F[_] : Async]: Resource[F, Client[F]] = clientWithTimeout(20.seconds)

  private def clientWithTimeout[F[_] : Async](timeout: FiniteDuration): Resource[F, Client[F]] =
    EmberClientBuilder
      .default[F]
      .withTimeout(timeout)
      .withIdleConnectionTime(timeout)
      .build

}
