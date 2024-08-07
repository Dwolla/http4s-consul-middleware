package com.dwolla.consul.examples

import cats.effect._
import cats.syntax.all._
import io.jaegertracing.Configuration._
import natchez._
import natchez.jaeger._
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.net.URI

trait ConsulServiceDiscoveryAlgAppPlatform extends IOApp.Simple with LocalTracing {
  private def jaegerEntryPoint[F[_] : Sync]: Resource[F, EntryPoint[F]] =
    Jaeger.entryPoint("ConsulServiceDiscoveryAlgApp", Either.catchNonFatal(new URI("http://localhost:16686")).toOption) { c =>
      Sync[F].delay {
        c.withSampler(SamplerConfiguration.fromEnv)
          .withReporter(ReporterConfiguration.fromEnv)
          .getTracer
      }
    }

  override def run: IO[Unit] =
    jaegerEntryPoint[IO]
      .flatMap { ep =>
        ep.root("ConsulServiceDiscoveryAlgApp")
          .evalMap {
            implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]

            IOLocal(_).flatMap { implicit l =>
              new ConsulServiceDiscoveryAlgApp(ep).run
            }
          }
      }
      .use_
}
