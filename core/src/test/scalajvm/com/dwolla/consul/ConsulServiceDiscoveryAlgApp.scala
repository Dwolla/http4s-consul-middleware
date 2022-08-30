package com.dwolla.consul

import cats.effect._
import cats.syntax.all._
import cats.effect.std.Random
import fs2.Stream
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.syntax.all._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration._

object ConsulServiceDiscoveryAlgApp extends IOApp.Simple with Http4sClientDsl[IO] {
  private implicit def loggerR[F[_] : Logger]: Logger[Resource[F, *]] = Logger[F].mapK(Resource.liftK)

  private val serviceName = ServiceName("httpd")

  override def run: IO[Unit] = {
    Random.scalaUtilRandom[IO].flatMap { implicit random =>
      Slf4jLogger.create[IO].flatMap { implicit logger: Logger[IO] =>
        Stream.resource {
          EmberClientBuilder
            .default[IO]
            .build
            .map(ConsulServiceDiscoveryAlg(uri"http://localhost:8500", 1.minute, _))
        }
          .flatMap { alg =>
            Stream
              .resource(Logger[Resource[IO, *]].info(s"🔭 checking service ${serviceName.value}") >> alg.authoritiesForService(serviceName))
              .flatMap { s =>
                Stream.eval(s.fa)
                  .repeatN(5)
                  .metered(5.seconds)
                  .changes
              }
          }
          .evalMap(a => Logger[IO].info(a.toString))
          .compile
          .drain
      }
    }
  }
}
