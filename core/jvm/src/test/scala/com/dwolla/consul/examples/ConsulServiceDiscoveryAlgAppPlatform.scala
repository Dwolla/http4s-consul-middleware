package com.dwolla.consul.examples

import cats.data.Kleisli
import cats.syntax.all._
import cats.effect.{Trace => _, _}
import io.jaegertracing.Configuration._
import natchez._
import natchez.jaeger._
import org.typelevel.log4cats.slf4j.loggerFactoryforSync

import java.net.URI

trait ConsulServiceDiscoveryAlgAppPlatform extends IOApp.Simple {
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
      .flatMap(_.root("ConsulServiceDiscoveryAlgApp"))
      .evalMap(new ConsulServiceDiscoveryAlgApp[Kleisli[IO, Span[IO], *]].run.run)
      .use_
}
