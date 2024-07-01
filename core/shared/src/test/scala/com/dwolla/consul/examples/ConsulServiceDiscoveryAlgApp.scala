package com.dwolla.consul
package examples

import cats.effect.{Trace => _, _}
import cats.effect.std.Random
import cats.mtl.Local
import cats.syntax.all._
import fs2.Stream
import fs2.io.net.Network
import natchez.{EntryPoint, Span, Trace}
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.syntax.all._
import org.typelevel.log4cats.{Logger, LoggerFactory}

import scala.concurrent.duration._

class ConsulServiceDiscoveryAlgApp[F[_] : Async : LoggerFactory : Trace : Network](entryPoint: EntryPoint[F])
                                                                                  (implicit L: Local[F, Span[F]]) extends Http4sClientDsl[F] {
  private implicit def loggerR(implicit L: Logger[F]): Logger[Resource[F, *]] = Logger[F].mapK(Resource.liftK)

  private val serviceName = ServiceName("httpd")

  def run: F[Unit] =
    Random.scalaUtilRandom[F].flatMap { implicit random =>
      LoggerFactory[F].create.flatMap { implicit logger =>
        Stream.resource {
          EmberClientBuilder
            .default[F]
            .build
            .evalMap(ConsulServiceDiscoveryAlg(uri"http://localhost:8500", 1.minute, _, entryPoint))
        }
          .flatMap { alg =>
            Stream
              .resource(Logger[Resource[F, *]].info(s"🔭 checking service ${serviceName.value}") >> alg.authoritiesForService(serviceName))
              .flatMap { s =>
                Stream.eval(s)
                  .repeatN(5)
                  .metered(5.seconds)
                  .changes
              }
          }
          .evalMap(a => Logger[F].info(a.toString))
          .compile
          .drain
      }
    }
}

object ConsulServiceDiscoveryAlgApp extends ConsulServiceDiscoveryAlgAppPlatform
