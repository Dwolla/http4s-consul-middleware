package com.dwolla.consul.examples

import cats.effect._
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

trait ConsulMiddlewareAppPlatform extends IOApp.Simple {
  private implicit val noOpFactory: LoggerFactory[IO] = NoOpFactory[IO]

  override def run: IO[Unit] = new ConsulMiddlewareApp[IO].run
}
