package com.dwolla.consul

import cats.Monad
import cats.effect._
import cats.effect.std.Random
import cats.effect.syntax.all._
import cats.syntax.all._
import com.dwolla.consul.ThirdPartyTypeCodecs._
import fs2.Stream
import io.circe.optics.JsonPath.root
import io.circe.{Decoder, Json}
import monocle.Traversal
import org.http4s.Method.GET
import org.http4s._
import org.http4s.circe.jsonOf
import org.http4s.client._
import org.typelevel.log4cats._

import scala.concurrent.duration._

trait ConsulServiceDiscoveryAlg[F[_]] {
  /**
   * Starts a background process that will continually refresh the set of available instances of the given service.
   * The background process will live as long as the `cats.effect.Resource[F, F[Vector[Uri.Authority]]]` is in scope.
   *
   * @return a `cats.effect.Resource[F, F[Vector[Uri.Authority]]]` containing an effect that, when executed, contains the current set of available instances
   */
  def authoritiesForService(serviceName: ServiceName): Resource[F, F[Vector[Uri.Authority]]]

  /**
   * Using [[authoritiesForService]] as the source of available instances, picks one at random to return each time the
   * `F[Uri.Authority]` effect is executed.
   *
   * @return a `cats.effect.Resource[F, F[Uri.Authority]]` containing an effect that, when executed, contains a randomly selected instance, taken from the current set of available instances
   */
  def authorityForService(serviceName: ServiceName): Resource[F, F[Uri.Authority]]
}

object ConsulServiceDiscoveryAlg {
  def apply[F[_] : Temporal : LoggerFactory : Random](consulBaseUri: Uri,
                                                      longPollTimeout: FiniteDuration,
                                                      client: Client[F]): F[ConsulServiceDiscoveryAlg[F]] =
    LoggerFactory[F]
      .create(LoggerName("com.dwolla.consul.ConsulServiceDiscoveryAlg"))
      .map { implicit l =>
        new ConsulServiceDiscoveryAlg[F] {
          override def authoritiesForService(serviceName: ServiceName): Resource[F, F[Vector[Uri.Authority]]] =
            lookup[F](serviceName, consulBaseUri, None, longPollTimeout, client)
              .toResource
              .flatMap { case (initialValue, initialConsulIndex) =>
                continuallyUpdating(serviceName, initialValue, initialConsulIndex, consulBaseUri, longPollTimeout, client)
              }
              .onFinalize(Logger[F].trace(s"👋 shutting down authoritiesForService($serviceName)"))

          override def authorityForService(serviceName: ServiceName): Resource[F, F[Uri.Authority]] =
            authoritiesForService(serviceName)
              .map { getCurrentValue =>
                for {
                  services <- getCurrentValue
                  randomIndex <- Random[F].betweenInt(0, services.length)
                } yield services(randomIndex)
              }
        }
      }

  private val serviceLens: Traversal[Json, Uri.Authority] =
    root.each.Service.as[Uri.Authority]

  /**
   * Makes a request of the Consul API to retrieve the set of healthy instances for the given service.
   *
   * @param serviceName the service to look up in the Consul API
   * @param consulBase the base URI where the Consul API can be accessed, e.g. [[http://localhost:8500]]
   * @param index the index of the last known Consul state. If provided, Consul will interpret the request as a blocking query, and no response will be returned until Consul's state changes, or the [[longPollTimeout]] expires
   * @param longPollTimeout the maximum amount of time to wait before Consul should return a response
   * @param client the `org.http4s.client.Client[F]` used to interact with the Consul API. Should be configured not to timeout on blocking queries
   * @return a `Vector` containing the currently available instances of the service, and optionally the Consul state index
   */
  private def lookup[F[_] : Temporal : Logger](serviceName: ServiceName,
                                               consulBase: Uri,
                                               index: Option[ConsulIndex],
                                               longPollTimeout: FiniteDuration,
                                               client: Client[F],
                                              ): F[(Vector[Uri.Authority], Option[ConsulIndex])] = {
    val requestUri = serviceListUri(consulBase, serviceName, index, longPollTimeout)

    Logger[F].trace(s"📡 getting services for $serviceName from $requestUri") >>
      client
        .run(Request[F](GET, requestUri))
        .onFinalizeCase {
          case Resource.ExitCase.Succeeded => Logger[F].trace(s"👋 finalized Succeeded lookup($serviceName, …).client.run")
          case Resource.ExitCase.Errored(e) => Logger[F].trace(e)(s"👋 finalized Errored lookup($serviceName, …).client.run")
          case Resource.ExitCase.Canceled => Logger[F].trace(s"👋 finalized Canceled lookup($serviceName, …).client.run")
        }
        .use { resp =>
          Logger[F].trace(s"📠 ${AnsiColorCodes.red}Consul response ${AnsiColorCodes.reset}") >>
            resp
              .as[Json]
              .map(serviceLens.getAll(_).toVector)
              .tupleRight(resp.headers.get[ConsulIndex])
        }
        .timeout(longPollTimeout + 1.second)
        .handleErrorWith { ex =>
          Logger[F].error(ex)(s"📠 ${AnsiColorCodes.red}Consul response error ${AnsiColorCodes.reset}") >> ex.raiseError
        }
  }

  /**
   * Starts a background process tied to the scope of the returned `Resource` that will
   * long-poll the Consul API for updates to the set of available instances of the given
   * named service.
   *
   * @param serviceName the service to look up in the Consul API
   * @param initialValue the initial list of available instances, typically obtained by calling [[lookup]]
   * @param initialConsulIndex the initial Consul index value, typically obtained by calling [[lookup]]
   * @param consulBase the base URI where the Consul API can be accessed, e.g. [[http://localhost:8500]]
   * @param longPollTimeout the maximum amount of time to wait before Consul should return a response
   * @param client the `org.http4s.client.Client[F]` used to interact with the Consul API. Should be configured not to timeout on blocking queries
   * @return a `cats.effect.Resource` managing the background process and containing an effect to view the current set of available instances
   */
  private def continuallyUpdating[F[_] : Temporal : Logger](serviceName: ServiceName,
                                                            initialValue: Vector[Uri.Authority],
                                                            initialConsulIndex: Option[ConsulIndex],
                                                            consulBase: Uri,
                                                            longPollTimeout: FiniteDuration,
                                                            client: Client[F]): Resource[F, F[Vector[Uri.Authority]]] =
    Stream.unfoldEval(initialConsulIndex) { maybeIndex =>
      lookup[F](serviceName, consulBase, maybeIndex, longPollTimeout, client)
        .map(_.leftMap(_.some))                               // if we successfully got values, wrap them in Some so we can unNone later
        .handleErrorWith {
          // TODO maybe we should introduce some kind of escalating delay here?
          Logger[F].warn(_)("🔥 An exception occurred getting service details from Consul; retrying")
            .as((none[Vector[Uri.Authority]], maybeIndex))    // continue successfully, but emit None so the failure can be filtered out later
        }
        .map(_.some)                                          // this stream will unfold forever (well, until its Resource is finalized)
    }
      .unNone                                                 // errors returned by `lookup` are emitted as None, so filter them out
      .holdResource(initialValue)
      .onFinalize(Logger[F].trace(s"👋 shutting down continuallyUpdating($serviceName, …)"))
      .map(_.get)

  private[consul] def serviceListUri(consulBase: Uri,
                                     serviceName: ServiceName,
                                     index: Option[ConsulIndex],
                                     longPollTimeout: FiniteDuration,
                                    ): Uri =
    consulBase / "v1" / "health" / "service" / serviceName +? OnlyHealthyServices +?? index +?? index.as(WaitPeriod(longPollTimeout))

  private implicit def jsonEntityDecoder[F[_] : Concurrent, A: Decoder]: EntityDecoder[F, A] = jsonOf[F, A]
}

object AnsiColorCodes {
  val red = "\u001b[31m"
  val reset = "\u001b[0m"
}
