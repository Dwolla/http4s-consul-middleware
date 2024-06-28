package com.dwolla.consul.examples

import cats.effect._
import natchez.Span
import natchez.mtl.natchezMtlTraceForLocal
import natchez.noop.NoopEntrypoint
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

trait ConsulMiddlewareAppPlatform extends IOApp.Simple with LocalTracing {
  private implicit val noOpFactory: LoggerFactory[IO] = NoOpFactory[IO]

  override def run: IO[Unit] = IOLocal(Span.noop[IO]).map(catsMtlEffectLocalForIO(_)).flatMap { implicit L =>
    new ConsulMiddlewareApp[IO](NoopEntrypoint[IO]()).run
  }
}
