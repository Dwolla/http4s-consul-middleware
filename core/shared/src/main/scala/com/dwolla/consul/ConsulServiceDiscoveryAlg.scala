package com.dwolla.consul

import cats.data.OptionT
import cats.effect.kernel.Resource.ExitCase
import cats.effect.std.Random
import cats.effect.syntax.all._
import cats.effect.{Trace => _, _}
import cats.mtl.Local
import cats.syntax.all._
import cats.{Applicative, Monad, ~>}
import com.dwolla.consul.ThirdPartyTypeCodecs._
import fs2.Stream
import io.circe._
import natchez.mtl.natchezMtlTraceForLocal
import natchez.{EntryPoint, Span, Trace}
import org.http4s.Method.GET
import org.http4s._
import org.http4s.circe.jsonOf
import org.http4s.client._
import org.typelevel.log4cats._

import scala.concurrent.duration._

trait ConsulServiceDiscoveryAlg[F[_]] { self =>
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

  def mapK[G[_]](fk: F ~> G)
                (implicit F: MonadCancel[F, _], G: MonadCancel[G, _]): ConsulServiceDiscoveryAlg[G] = new ConsulServiceDiscoveryAlg[G] {
    override def authoritiesForService(serviceName: ServiceName): Resource[G, G[Vector[Uri.Authority]]] =
      self.authoritiesForService(serviceName)
        .map(fk(_))
        .mapK(fk)

    override def authorityForService(serviceName: ServiceName): Resource[G, G[Uri.Authority]] =
      self.authorityForService(serviceName)
        .map(fk(_))
        .mapK(fk)
  }
}

object ConsulServiceDiscoveryAlg {
  /**
   * Constructs a new instance of `ConsulServiceDiscoveryAlg[F]`. Since an `EntryPoint[F]` is provided, background
   * requests will be traced in their own traces.
   *
   * @param consulBaseUri the base URI of the Consul API to call to resolve service addresses
   * @param longPollTimeout how long to hold an open connection to the Consul API, waiting for a state change to be reported
   * @param client the http4s `Client[F]` used to make requests of the Consul API
   * @param entryPoint an `EntryPoint[F]` used to create new root traces for background processes
   * @param L a `Local[F, Span[F]]` instance used to change the `Span[F]` context for specific scopes
   * @tparam F the effect in which to operate
   * @return a new instance of `ConsulServiceDiscoveryAlg[F]`
   */
  def apply[F[_] : Temporal : LoggerFactory : Random](consulBaseUri: Uri,
                                                      longPollTimeout: FiniteDuration,
                                                      client: Client[F],
                                                      entryPoint: EntryPoint[F])
                                                     (implicit L: Local[F, Span[F]]): F[ConsulServiceDiscoveryAlg[F]] =
    make(consulBaseUri, longPollTimeout, client, entryPoint.some)

  /**
   * Constructs a new instance of `ConsulServiceDiscoveryAlg[F]`. Since no `EntryPoint[F]` is provided, background
   * requests will not be traced.
   *
   * @param consulBaseUri the base URI of the Consul API to call to resolve service addresses
   * @param longPollTimeout how long to hold an open connection to the Consul API, waiting for a state change to be reported
   * @param client the http4s `Client[F]` used to make requests of the Consul API
   * @param L a `Local[F, Span[F]]` instance used to change the `Span[F]` context for specific scopes
   * @tparam F the effect in which to operate
   * @return a new instance of `ConsulServiceDiscoveryAlg[F]`
   */
  def apply[F[_] : Temporal : LoggerFactory : Random](consulBaseUri: Uri,
                                                      longPollTimeout: FiniteDuration,
                                                      client: Client[F])
                                                     (implicit L: Local[F, Span[F]]): F[ConsulServiceDiscoveryAlg[F]] =
    make(consulBaseUri, longPollTimeout, client, None)

  private def make[F[_] : Temporal : LoggerFactory : Random](consulBaseUri: Uri,
                                                             longPollTimeout: FiniteDuration,
                                                             client: Client[F],
                                                             entryPoint: Option[EntryPoint[F]])
                                                            (implicit L: Local[F, Span[F]]): F[ConsulServiceDiscoveryAlg[F]] =
    LoggerFactory[F]
      .create(LoggerName("com.dwolla.consul.ConsulServiceDiscoveryAlg"))
      .map { implicit l =>
        new AbstractConsulServiceDiscoveryAlg[F] {
          override def authoritiesForService(serviceName: ServiceName): Resource[F, F[Vector[Uri.Authority]]] =
            lookup[F](serviceName, consulBaseUri, None, longPollTimeout, client)
              .toResource
              .flatMap { case (initialValue, initialConsulIndex) =>
                continuallyUpdating(serviceName, initialValue, initialConsulIndex, consulBaseUri, longPollTimeout, client, entryPoint)
              }
              .onFinalize(Logger[F].trace(s"👋 shutting down authoritiesForService($serviceName)"))
        }
      }

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
  private def lookup[F[_] : Temporal : Logger : Trace](serviceName: ServiceName,
                                                       consulBase: Uri,
                                                       index: Option[ConsulIndex],
                                                       longPollTimeout: FiniteDuration,
                                                       client: Client[F],
                                                      ): F[(Vector[Uri.Authority], Option[ConsulIndex])] = {
    val requestUri = serviceListUri(consulBase, serviceName, index, longPollTimeout)

    Logger[F].trace(s"📡 getting services for $serviceName from $requestUri") >>
      Trace[F].span("com.dwolla.consul.ConsulServiceDiscoveryAlg.lookup") {
        val req = Request[F](GET, requestUri)

        Trace[F].put(
          "serviceName" -> serviceName.value,
          "consulBase" -> consulBase.toString(),
          "client.http.uri" -> req.uri.toString(),
          "client.http.method" -> req.method.toString,
        ) >>
          client
            .run(req)
            .onFinalizeCase(logFinalizeCase(serviceName))
            .onFinalizeCase(traceFinalizeCase)
            .use { resp =>
              Logger[F].trace(s"📠 ${AnsiColorCodes.red}Consul response ${AnsiColorCodes.reset}") >>
                resp
                  .as[Json]
                  .map {
                    _
                      .asArray
                      .toVector
                      .flatten
                      .flatMap {
                        _.asObject
                          .flatMap(_("Service"))
                          .flatMap(_.as[Uri.Authority].toOption)
                          .toVector
                      }
                  }
                  .tupleRight(resp.headers.get[ConsulIndex])
            }
            .timeout(longPollTimeout + 1.second)
            .handleErrorWith { ex =>
              Logger[F].error(ex)(s"📠 ${AnsiColorCodes.red}Consul response error ${AnsiColorCodes.reset}") >> ex.raiseError
            }
      }
  }

  private def traceFinalizeCase[F[_] : Trace]: ExitCase => F[Unit] =
    exitCase => Trace[F].put("ExitCase" -> exitCase.toString)

  private def logFinalizeCase[F[_] : Logger](serviceName: ServiceName): ExitCase => F[Unit] = {
    case Resource.ExitCase.Succeeded => Logger[F].trace(s"👋 finalized Succeeded lookup($serviceName, …).client.run")
    case Resource.ExitCase.Errored(e) => Logger[F].trace(e)(s"👋 finalized Errored lookup($serviceName, …).client.run")
    case Resource.ExitCase.Canceled => Logger[F].trace(s"👋 finalized Canceled lookup($serviceName, …).client.run")
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
   * @param entryPoint an optional EntryPoint used to construct new traces for background requests. If none, a no-op span is used instead.
   * @return a `cats.effect.Resource` managing the background process and containing an effect to view the current set of available instances
   */
  private def continuallyUpdating[F[_] : Temporal : Logger](serviceName: ServiceName,
                                                            initialValue: Vector[Uri.Authority],
                                                            initialConsulIndex: Option[ConsulIndex],
                                                            consulBase: Uri,
                                                            longPollTimeout: FiniteDuration,
                                                            client: Client[F],
                                                            entryPoint: Option[EntryPoint[F]],
                                                           )
                                                           (implicit L: Local[F, Span[F]]): Resource[F, F[Vector[Uri.Authority]]] =
    Stream.unfoldEval(initialConsulIndex) { maybeIndex =>
      inNewLinkedRootSpan(entryPoint) { // since this is a background task, it doesn't make sense
        import natchez.mtl._            // to directly attach it to the trace that initially started it,
                                        // but linking it to the new root span is helpful

        lookup[F](serviceName, consulBase, maybeIndex, longPollTimeout, client)
          .map(_.leftMap(_.some)) // if we successfully got values, wrap them in Some so we can unNone later
          .handleErrorWith {
            // TODO maybe we should introduce some kind of escalating delay here?
            Logger[F].warn(_)("🔥 An exception occurred getting service details from Consul; retrying")
              .as((none[Vector[Uri.Authority]], maybeIndex)) // continue successfully, but emit None so the failure can be filtered out later
          }
          .map(_.some) // this stream will unfold forever (well, until its Resource is finalized)
      }
    }
      .unNone          // errors returned by `lookup` are emitted as None, so filter them out
      .holdResource(initialValue)
      .onFinalize(Logger[F].trace(s"👋 shutting down continuallyUpdating($serviceName, …)"))
      .map(_.get)

  private def inNewLinkedRootSpan[F[_] : MonadCancelThrow, A](entryPoint: Option[EntryPoint[F]])
                                                             (fa: F[A])
                                                             (implicit L: Local[F, Span[F]]): F[A] =
    OptionT.fromOption[Resource[F, *]](entryPoint)
      .semiflatMap { ep =>
        natchez.mtl.natchezMtlTraceForLocal
          .kernel
          .map(Span.Options.Defaults.withLink)
          .toResource
          .flatMap {
            ep.root("com.dwolla.consul.ConsulServiceDiscoveryAlg.continuallyUpdating", _)
          }
      }
      .getOrElse(Span.noop)
      .use(Local[F, Span[F]].scope(fa))

  private[consul] def serviceListUri(consulBase: Uri,
                                     serviceName: ServiceName,
                                     index: Option[ConsulIndex],
                                     longPollTimeout: FiniteDuration,
                                    ): Uri =
    consulBase / "v1" / "health" / "service" / serviceName +? OnlyHealthyServices +?? index +?? index.as(WaitPeriod(longPollTimeout))

  private implicit def jsonEntityDecoder[F[_] : Concurrent, A: Decoder]: EntityDecoder[F, A] = jsonOf[F, A]

  @deprecated("maintained for binary compatibility: this version doesn't place background traces in the proper scope", "0.3.1")
  def apply[F[_]](consulBaseUri: Uri,
                  longPollTimeout: FiniteDuration,
                  client: Client[F],
                  F: Temporal[F],
                  L: LoggerFactory[F],
                  R: Random[F]): F[ConsulServiceDiscoveryAlg[F]] =
    apply(consulBaseUri, longPollTimeout, client)(F, L, R, new BrokenIllegalNoopLocalSpan(F))

  @deprecated("maintained for binary compatibility: this version doesn't place background traces in the proper scope", "0.3.1")
  def apply[F[_]](consulBaseUri: Uri,
                  longPollTimeout: FiniteDuration,
                  client: Client[F],
                  F: Temporal[F],
                  L: LoggerFactory[F],
                  R: Random[F],
                  T: Trace[F]): F[ConsulServiceDiscoveryAlg[F]] =
    apply(consulBaseUri, longPollTimeout, client)(F, L, R, new BrokenIllegalNoopLocalSpan(F))

  private class BrokenIllegalNoopLocalSpan[F[_]](A: Applicative[F]) extends Local[F, Span[F]] {
    override def local[A](fa: F[A])(f: Span[F] => Span[F]): F[A] = fa
    override def applicative: Applicative[F] = A
    override def ask[E2 >: Span[F]]: F[E2] = A.pure(Span.noop(A))
  }
}

abstract class AbstractConsulServiceDiscoveryAlg[F[_] : Random : Monad] extends ConsulServiceDiscoveryAlg[F] {
  override def authorityForService(serviceName: ServiceName): Resource[F, F[Uri.Authority]] =
    authoritiesForService(serviceName)
      .map { getCurrentValue =>
        for {
          services <- getCurrentValue
          randomIndex <- Random[F].betweenInt(0, services.length)
        } yield services(randomIndex)
      }
}

object AnsiColorCodes {
  val red = "\u001b[31m"
  val reset = "\u001b[0m"
}
