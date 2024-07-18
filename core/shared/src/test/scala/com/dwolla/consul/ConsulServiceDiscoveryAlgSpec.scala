package com.dwolla.consul

import cats.effect.std._
import cats.effect.syntax.all._
import cats.effect.{IO, IOLocal, Temporal}
import cats.syntax.all._
import com.comcast.ip4s.Arbitraries._
import com.comcast.ip4s.{IpAddress, Port}
import com.dwolla.consul.arbitraries._
import com.dwolla.consul.examples.LocalTracing
import io.circe.Encoder
import io.circe.literal._
import io.circe.syntax._
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import natchez.Span
import natchez.mtl.natchezMtlTraceForLocal
import org.http4s.Uri.Host
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.dsl.Http4sDsl
import org.http4s.laws.discipline.arbitrary.http4sTestingArbitraryForUri
import org.http4s.syntax.all._
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.effect.PropF
import org.scalacheck._
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

import scala.concurrent.duration._

class ConsulServiceDiscoveryAlgSpec
  extends CatsEffectSuite
    with ScalaCheckEffectSuite
    with cats.effect.std.ArbitraryRandom
    with LocalTracing {

  private implicit val noopLoggerFactory: LoggerFactory[IO] = NoOpFactory[IO]

  test("Consul service lookup URI construction") {
    Prop.forAll { (consulBase: Uri,
                   serviceName: ServiceName,
                   index: Option[ConsulIndex],
                   longPollTimeout: FiniteDuration) => 
      val output = ConsulServiceDiscoveryAlg.serviceListUri(consulBase, serviceName, index, longPollTimeout)

      val expected =
        index
          .as(longPollTimeout)
          .foldLeft {
            (consulBase / "v1" / "health" / "service" / serviceName.value)
              .withQueryParam("passing")
              .withOptionQueryParam("index", index)
          } { (uri, wait) =>
            uri.withQueryParam("wait", s"${wait.toSeconds}s")
          }

      assertEquals(output, expected)
    }
  }

  test("authoritiesForService returns the URIs for the service returned by Consul") {
    PropF.forAllF { (services: Vector[ConsulApi.Service],
                     randomInstance: Random[IO],
                    ) =>
      val http4sClient: Client[IO] = Client.fromHttpApp(new ConsulApi[IO](services).app)
      implicit val random: Random[IO] = randomInstance

      IOLocal(Span.noop[IO]).flatMap { implicit ioLocal =>
        for {
          alg <- ConsulServiceDiscoveryAlg(uri"/", 5.seconds, http4sClient)
          serviceName <- Random[IO].shuffleVector(services).map(_.headOption.map(_.name).getOrElse(ServiceName("missing")))
          output <- alg.authoritiesForService(serviceName).use(_.delayBy(10.millis)) // delay a bit to make sure the background request is also made
          expected = services.collect {
            case ConsulApi.Service(`serviceName`, host, port, true) =>
              Uri.Authority(None, Host.fromIpAddress(host), port.value.some)
          }
        } yield {
          assertEquals(output, expected)
        }
      }
    }
  }
}

object ConsulApi {
  case class Service(name: ServiceName, address: IpAddress, port: Port, isHealthy: Boolean)

  object Service {
    val genService: Gen[Service] =
      for {
        serviceName <- arbitrary[ServiceName]
        address <- arbitrary[IpAddress]
        port <- arbitrary[Port]
        healthy <- arbitrary[Boolean]
      } yield Service(serviceName, address, port, healthy)

    implicit val arbService: Arbitrary[Service] = Arbitrary(genService)

    implicit val encoder: Encoder[Service] = a =>
      json"""{
          "Node": {},
          "Service": {
            "Service": ${a.name},
            "Address": ${a.address.toString},
            "Port": ${a.port.value}
          },
          "Checks": [
            {
              "Status": ${(if (a.isHealthy) "passing" else "critical"): String}
            }
          ]
        }"""
  }
}

class ConsulApi[F[_] : Temporal](services: Vector[ConsulApi.Service]) extends Http4sDsl[F] {
  object OnlyHealthyServices extends FlagQueryParamMatcher("passing")
  object ConsulIndex extends OptionalQueryParamDecoderMatcher[com.dwolla.consul.ConsulIndex]("index")
  object WaitPeriod extends OptionalQueryParamDecoderMatcher[com.dwolla.consul.WaitPeriod]("wait")

  def routes: HttpRoutes[F] = HttpRoutes.of {
    case GET -> Root / "v1" / "health" / "service" / serviceName :? OnlyHealthyServices(true) :? ConsulIndex(idx) :? WaitPeriod(timeout) =>
      val json = services.filter {
        case ConsulApi.Service(ServiceName(`serviceName`), _, _, true) => true
        case _ => false
      }.asJson

      val consulIndex = idx.getOrElse(com.dwolla.consul.ConsulIndex(1L))

      timeout.map(_.value).foldLeft(Ok(json, consulIndex))(_.delayBy(_))
  }

  def app: HttpApp[F] = routes.orNotFound
}
