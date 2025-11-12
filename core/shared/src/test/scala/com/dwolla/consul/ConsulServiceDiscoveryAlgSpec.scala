package com.dwolla.consul

import cats._
import cats.data._
import cats.effect._
import cats.effect.std._
import cats.effect.syntax.all._
import cats.effect.testkit.TestControl
import cats.syntax.all._
import com.comcast.ip4s.Arbitraries._
import com.comcast.ip4s.{IpAddress, Port}
import com.dwolla.consul.ConsulApi.Service._
import com.dwolla.consul.ConsulApi.ServiceProgression
import com.dwolla.consul.arbitraries._
import io.circe.literal._
import io.circe.syntax._
import io.circe.{Encoder, Json}
import fs2._
import fs2.concurrent.SignallingRef
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import natchez.Span
import natchez.noop.NoopEntrypoint
import org.http4s.Uri.Host
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.dsl.Http4sDsl
import org.http4s.laws.discipline.arbitrary.http4sTestingArbitraryForUri
import org.http4s.syntax.all._
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck._
import org.scalacheck.effect.PropF
import org.typelevel.log4cats.testing.TestingLoggerFactory

import scala.concurrent.duration._
import scala.util

class ConsulServiceDiscoveryAlgSpec
  extends CatsEffectSuite
    with ScalaCheckEffectSuite
    with cats.effect.std.ArbitraryRandom {
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
      implicit val random: Random[IO] = randomInstance

      val program =
        TestingLoggerFactory.ref[IO]()
          .flatMap { implicit loggerFactory: TestingLoggerFactory[IO] =>
            ConsulApi[IO](NonEmptyChain.of(services), 10.seconds)
              .map(consulApi => Client.fromHttpApp(consulApi.app))
              .use { http4sClient =>
                IO.local(Span.noop[IO]).flatMap { implicit ioLocal =>
                  for {
                    alg <- ConsulServiceDiscoveryAlg(uri"/", 5.seconds, http4sClient)
                    serviceName <- Random[IO].shuffleVector(services).map(_.headOption.map(_.name).getOrElse(ServiceName("missing")))
                    output <- alg.authoritiesForService(serviceName).use(_.delayBy(10.millis)) // delay a bit to make sure the background request is also made
                    expected = services.collect {
                      case ConsulApi.Service(`serviceName`, Some(host), _, port, true) =>
                        Uri.Authority(None, Host.fromIpAddress(host), port.value.some)
                      case ConsulApi.Service(`serviceName`, None, host, port, true) =>
                        Uri.Authority(None, Host.fromIpAddress(host), port.value.some)
                    }
                  } yield {
                    assertEquals(output, expected)
                  }
                }
              }
          }

      TestControl.executeEmbed(program)
    }
  }

  test("authorityForService returns a randomly selected instance, and blocks when no instances are available") {
    val forceHealthy = true.some
    val entryPoint = NoopEntrypoint[IO]()

    PropF.forAllF { (serviceName: ServiceName,
                     serviceProgressionGenerator: ServiceProgression,
                     randomInstance: Random[IO],
                    ) =>
      implicit val random: Random[IO] = randomInstance

      val serviceProgression = serviceProgressionGenerator(serviceName, forceHealthy)

      val program =
        TestingLoggerFactory.ref[IO]()
          .flatMap { implicit loggerFactory: TestingLoggerFactory[IO] =>
            entryPoint
              .root("authorityForService returns a randomly selected instance, and blocks when no instances are available")
              .evalMap(IO.local)
              .flatMap { implicit ioLocal =>
                ConsulApi[IO](serviceProgression, stateChangeRate = 10.seconds)
                  .map(consulApi => Client.fromHttpApp(consulApi.app))
                  .evalMap(ConsulServiceDiscoveryAlg(uri"/", longPollTimeout = 5.seconds, _, entryPoint))
                  .flatMap(_.authorityForService(serviceName))
              }
              .use { getRandomAuthority =>
                serviceProgression.traverse(_ => getRandomAuthority)
              }
              .product(loggerFactory.logged)
          }

      TestControl.executeEmbed(program)
        .map { case (output: NonEmptyChain[Uri.Authority] @unchecked, logged) =>
          // TODO could we do more assertions? it's not obvious how because the selected instances will be randomly picked
          assertEquals(output.length, serviceProgression.length)
          assert(output.toList.forall(_.host.toString.nonEmpty))
          assert(!logged.exists(_.throwOpt.isDefined), logged.filter(message => message.throwOpt.isDefined).mkString("\n"))
        }
    }
  }
}

object ConsulApi {
  type ServiceProgression = (ServiceName, Option[Boolean]) => NonEmptyChain[Vector[ConsulApi.Service]]

  case class Service(name: ServiceName, address: Option[IpAddress], nodeAddress: IpAddress, port: Port, isHealthy: Boolean)

  object Service {
    implicit val showService: Show[Service] = Show.fromToString
    implicit val eqService: Eq[Service] = Eq.fromUniversalEquals

    val genService: Gen[(ServiceName, Option[Boolean]) => Service] =
      for {
        address <- arbitrary[Option[IpAddress]]
        nodeAddress <- arbitrary[IpAddress]
        port <- arbitrary[Port]
        healthy <- arbitrary[Boolean]
      } yield { (serviceName: ServiceName, healthOverride: Option[Boolean]) =>
        Service(serviceName, address, nodeAddress, port, healthOverride.getOrElse(healthy))
      }

    implicit val arbService: Arbitrary[Service] = Arbitrary {
      arbitrary[ServiceName].flatMap { (sn: ServiceName) =>
        genService.map(_(sn, None))
      }
    }

    val genServiceListThatMayContainTheGivenName: Gen[(ServiceName, Option[Boolean]) => Vector[Service]] =
      for {
        namedGen <- Gen.option(genService)
        otherServices <- Gen.listOf(arbService.arbitrary)
        random <- arbitrary[Long].map(new util.Random(_))
      } yield { (serviceName: ServiceName, healthOverride: Option[Boolean]) =>
        val namedService: Option[Service] = namedGen.map(_(serviceName, healthOverride))

        random.shuffle(namedService.foldl(Vector.newBuilder[Service])(_ += _).++=(otherServices).result())
      }

    val genServiceListThatDoesContainTheGivenName: Gen[(ServiceName, Option[Boolean]) => Vector[Service]] =
      for {
        namedGen <- genService
        otherServices <- Gen.listOf(arbService.arbitrary)
        random <- arbitrary[Long].map(new util.Random(_))
      } yield { (serviceName: ServiceName, healthOverride: Option[Boolean]) =>
        val namedService: Service = namedGen(serviceName, healthOverride)

        random.shuffle(Vector.newBuilder[Service].+=(namedService).++=(otherServices).result())
      }

    val genServiceProgression: Gen[ServiceProgression] =
      for {
        last <- genServiceListThatDoesContainTheGivenName
        others <- Gen.listOf(genServiceListThatMayContainTheGivenName)
      } yield { (serviceName: ServiceName, healthOverride: Option[Boolean]) =>
        NonEmptyChain
          .one(last)
          .prependChain(Chain.fromSeq(others)) // prepend the others so we always finish in a state where we have a service of the given name
          .map(_(serviceName, healthOverride))
      }

    implicit val arbServiceProgression: Arbitrary[ServiceProgression] =
      Arbitrary(genServiceProgression)

    implicit val encoder: Encoder[Service] = a =>
      json"""{
          "Node": {
            "Address": ${a.nodeAddress.toString}
          },
          "Service": {
            "Service": ${a.name},
            "Address": ${a.address.map(_.toString).getOrElse("")},
            "Port": ${a.port.value}
          },
          "Checks": [
            {
              "Status": ${(if (a.isHealthy) "passing" else "critical"): String}
            }
          ]
        }"""
  }

  class PartiallyAppliedConsulApi[F[_]](val dummy: Unit = ()) extends AnyVal {
    def apply[G[_] : Foldable : FunctorFilter](services: NonEmptyChain[G[Service]],
                                               stateChangeRate: FiniteDuration,
                                              )(implicit
                                                F: Temporal[F],
                                                R: Random[F],
                                                E: Eq[G[ConsulApi.Service]],
                                              ): Resource[F, ConsulApi[F]] =
      Supervisor[F].flatMap { supervisor =>
        Resource.eval(SignallingRef[F].of((services.head, ConsulIndex(1L))))
          .evalMap { ref =>
            supervisor.supervise {
              Stream.emits(services.tail.toList)
                .metered[F](stateChangeRate)
                .zipWith(Stream.unfold(ConsulIndex(2L) /* start at 2 because this will be the first updated value */) { idx =>
                  (idx, idx.increment).some
                })((a, b) => a -> b)
                .evalMap(ref.set)
                .compile
                .drain
            }.as(new ConsulApiImpl(ref))
          }
      }
  }

  def apply[F[_]] = new PartiallyAppliedConsulApi[F]()
}

trait ConsulApi[F[_]] {
  def routes: HttpRoutes[F]
  def app: HttpApp[F]
}

private class ConsulApiImpl[F[_] : Temporal : Random, G[_] : Foldable : FunctorFilter](state: SignallingRef[F, (G[ConsulApi.Service], ConsulIndex)])
                                                                                      (implicit E: Eq[G[ConsulApi.Service]])
  extends ConsulApi[F]
    with Http4sDsl[F] {

  object OnlyHealthyServices extends FlagQueryParamMatcher("passing")
  object ConsulIndexParam extends OptionalQueryParamDecoderMatcher[com.dwolla.consul.ConsulIndex]("index")
  object WaitPeriod extends OptionalQueryParamDecoderMatcher[com.dwolla.consul.WaitPeriod]("wait")

  private def servicesAsJson(services: G[ConsulApi.Service],
                             serviceName: ServiceName): Json =
    services.filter {
      case ConsulApi.Service(`serviceName`, _, _, _, isHealthy) => isHealthy
      case _ => false
    }.asJson(Encoder.encodeFoldable)

  override def routes: HttpRoutes[F] = HttpRoutes.of {
    case GET -> Root / "v1" / "health" / "service" / serviceName :? OnlyHealthyServices(true) :? ConsulIndexParam(idx) :? WaitPeriod(timeout) =>
      state.changes.getAndDiscreteUpdates.use {
        case ((services, currentIndex), _) if idx.exists(_ < currentIndex) =>
          // return immediately regardless of wait
          Ok(servicesAsJson(services, ServiceName(serviceName)), currentIndex)

        case ((services, currentIndex), updates) =>
          // if the wait timeout is set, wait to respond until a state change happens or the timeout expires.
          // otherwise respond immediately with the current state

          timeout
            .toOptionT
            .map(_.value)
            .semiflatMap { wait =>
              Random[F]
                .nextDouble.map(_ / 16.0)
                .map(_.millis)
                .map(wait + _)
            }
            .value
            .flatMap {
              _
                .flatTraverse(updates.head.compile.last.timeoutTo(_, none[(G[ConsulApi.Service], ConsulIndex)].pure[F]))
                .flatMap {
                  case Some((services, nextIndex)) =>
                    Ok(servicesAsJson(services, ServiceName(serviceName)), nextIndex)
                  case None =>
                    Ok(servicesAsJson(services, ServiceName(serviceName)), currentIndex)
                }
            }
      }
  }

  def app: HttpApp[F] = routes.orNotFound
}
