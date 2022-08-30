package com.dwolla.http4s.consul

import cats.effect._
import cats.syntax.all._
import cats.effect.std.Random
import com.dwolla.http4s.consul.ConsulMiddlewareApp.consulAwareClient
import com.dwolla.consul._
import fs2.Stream
import org.http4s.Method.GET
import org.http4s._
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.syntax.all._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration._

class ConsulMiddlewareApp[F[_] : Async] extends Http4sClientDsl[F] {
  val exampleConsulUri: Uri = uri"consul://httpd/"

  def run: F[Unit] =
    Random.scalaUtilRandom[F].flatMap { implicit random =>
      Slf4jLogger.create[F].flatMap { implicit logger: Logger[F] =>
        (for {
          client <- Stream.resource(consulAwareClient[F])
          _ <- Stream.repeatEval(client.successful(GET(exampleConsulUri))
            .flatMap {
              case true => Logger[F].info("🔮 success")
              case false => Logger[F].info("🔮 failure")
            })
            .metered(2.seconds)
        } yield ())
          .take(1)
          .compile
          .drain
      }
    }
}

object ConsulMiddlewareApp extends IOApp.Simple {
  override def run: IO[Unit] = new ConsulMiddlewareApp[IO].run

  private[ConsulMiddlewareApp] def consulAwareClient[F[_] : Async : Logger : Random]: Resource[F, Client[F]] =
    (consulServiceDiscoveryAlg[F], normalClient[F])
      .parMapN(ConsulMiddleware(_)(_))
      .flatten

  private def consulServiceDiscoveryAlg[F[_] : Async : Logger : Random]: Resource[F, ConsulServiceDiscoveryAlg[F]] =
    longPollClient[F].map(ConsulServiceDiscoveryAlg(uri"http://localhost:8500", 1.minute, _))

  private def longPollClient[F[_] : Async]: Resource[F, Client[F]] = clientWithTimeout(75.seconds)

  private def normalClient[F[_] : Async]: Resource[F, Client[F]] = clientWithTimeout(20.seconds)

  private def clientWithTimeout[F[_] : Async](timeout: FiniteDuration): Resource[F, Client[F]] =
    EmberClientBuilder
      .default[F]
      .withTimeout(timeout)
      .withIdleConnectionTime(timeout)
      .build

}
