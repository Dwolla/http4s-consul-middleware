package com.dwolla.consul.examples

import cats.effect.{IO, IOApp}
import org.typelevel.log4cats.slf4j.loggerFactoryforSync

trait ConsulServiceDiscoveryAlgAppPlatform extends IOApp.Simple {
  override def run: IO[Unit] = new ConsulServiceDiscoveryAlgApp[IO].run
}
