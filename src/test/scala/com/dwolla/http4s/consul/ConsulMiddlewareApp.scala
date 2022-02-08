package com.dwolla.http4s.consul

import cats.effect._
import cats.effect.std.Random
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

object ConsulMiddlewareApp extends IOApp.Simple with Http4sClientDsl[IO] {
  val uri: Uri = uri"consul://httpd/"

  private def clientWithTimeout[F[_] : Async](timeout: FiniteDuration): Resource[F, Client[F]] =
    EmberClientBuilder
      .default[F]
      .withTimeout(timeout)
      .build

  private def longPollClient[F[_] : Async]: Resource[F, Client[F]] = clientWithTimeout(10.minutes)

  private def normalClient[F[_] : Async]: Resource[F, Client[F]] = clientWithTimeout(20.seconds)

  override def run: IO[Unit] = {
    Random.scalaUtilRandom[IO].flatMap { implicit random =>
      Slf4jLogger.create[IO].flatMap { implicit logger: Logger[IO] =>
        (for {
          normalClient <- Stream.resource(normalClient[IO])
          longPollClient <- Stream.resource(longPollClient[IO])
          client <- Stream.resource(ConsulMiddleware(1, ConsulServiceDiscoveryAlg(uri"http://localhost:8500", 1.minute, longPollClient))(normalClient))
          _ <- Stream.repeatEval(client.successful(GET(uri))
            .flatMap {
              case true => Logger[IO].info("🔮 success")
              case false => Logger[IO].info("🔮 failure")
            })
            .metered(2.seconds)
        } yield ())
          .compile
          .drain
      }
    }
  }
}
