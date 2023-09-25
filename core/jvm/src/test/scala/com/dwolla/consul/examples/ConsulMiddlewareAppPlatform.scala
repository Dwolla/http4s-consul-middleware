package com.dwolla.consul.examples

import cats.data._
import cats.syntax.all._
import cats.effect.{Trace => _, _}
import io.jaegertracing.Configuration._
import natchez._
import natchez.jaeger._
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.net.URI

trait ConsulMiddlewareAppPlatform extends IOApp.Simple {
  private def jaegerEntryPoint[F[_] : Sync]: Resource[F, EntryPoint[F]] =
    Jaeger.entryPoint("ConsulMiddlewareApp", Either.catchNonFatal(new URI("http://localhost:16686")).toOption) { c =>
      Sync[F].delay {
        c.withSampler(SamplerConfiguration.fromEnv)
          .withReporter(ReporterConfiguration.fromEnv)
          .getTracer
      }
    }

  override def run: IO[Unit] =
    jaegerEntryPoint[IO]
      .flatMap(_.root("ConsulMiddlewareApp"))
      .evalMap {
        implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]

        new ConsulMiddlewareApp[ReaderT[IO, Span[IO], *]].run.run
      }
      .use_
}
