package com.dwolla.consul

import cats.data.OptionT
import cats.effect.kernel.Resource.ExitCase
import cats.effect.std.{Random, Supervisor}
import cats.effect.syntax.all._
import cats.effect.{Trace => _, _}
import cats.mtl.Local
import cats.syntax.all._
import cats.{Applicative, Monad, ~>}
import com.dwolla.consul.ThirdPartyTypeCodecs._
import fs2.Stream
import fs2.concurrent.Signal
import io.circe._
import natchez.mtl._
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
   * Starts a background process that will continually refresh the set of available instances of the given service.
   * The background process will live as long as the `cats.effect.Resource[F, F[Uri.Authority]]` is in scope.
   * From the set of available instances, one will be selected randomly and returned each time the `F[Uri.Authority]`
   * effect is executed.
   *
   * If no instances are available, the getter semantically blocks until one is available.
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

private class ConsulServiceDiscoveryAlgImpl[F[_] : Temporal : Logger : Random](consulBaseUri: Uri,
                                                                               longPollTimeout: FiniteDuration,
                                                                               client: Client[F],
                                                                               entryPoint: Option[EntryPoint[F]])
                                                                              (implicit L: Local[F, Span[F]]) extends ConsulServiceDiscoveryAlg[F] {
  private sealed trait ConsulState
  private object ConsulState {
    def await: F[AwaitingValue] = Deferred[F, Vector[Uri.Authority]].map(AwaitingValue(_))
  }
  private case class KnownValue(uris: Vector[Uri.Authority]) extends ConsulState
  private case class AwaitingValue(deferred: Deferred[F, Vector[Uri.Authority]]) extends ConsulState

  private def buildStateRef(uriAuthoritySignal: Signal[F, Vector[Uri.Authority]],
                            supervisor: Supervisor[F],
                           ): F[Ref[F, ConsulState]] =
    ConsulState.await
      .flatMap(Ref[F].of[ConsulState])
      .flatTap { stateRef =>
        supervisor.supervise {
          uriAuthoritySignal
            .discrete
            .evalMap[F, Unit] {
              case v if v.isEmpty =>
                ConsulState.await // construct a new AwaitingValue in case we need to reset below
                  .flatMap { (nextAwait: AwaitingValue) =>
                    stateRef.update {
                      case KnownValue(_) =>
                        // we had a value, but now we don't, so reset
                        nextAwait
                      case stay@AwaitingValue(_) =>
                        // we don't have a value, so discard the AwaitingValue we created and use the old one
                        stay
                    }
                  }
              case services =>
                stateRef.flatModify {
                  case KnownValue(_) =>
                    // we had a previous known value, so replace it with the new value and don't do anything else
                    KnownValue(services) -> Applicative[F].unit
                  case AwaitingValue(d) =>
                    // state transition into KnownValue and complete the waiting Deferred with the new value
                    KnownValue(services) -> d.complete(services).void
                }
            }
            .compile
            .drain
        }
      }

  override def authorityForService(serviceName: ServiceName): Resource[F, F[Uri.Authority]] =
    Supervisor[F].flatMap { supervisor =>
      continuallyUpdating(serviceName, consulBaseUri, longPollTimeout, client, entryPoint)
        .evalMap[F[Uri.Authority]] {
          buildStateRef(_, supervisor).map { // the getter needs to stay in F, so don't flatMap here!
            _.get.flatMap[Vector[Uri.Authority]] {
                case KnownValue(services) =>
                  services.pure[F]
                case AwaitingValue(d) =>
                  Trace[F].span("awaiting_service_availability") {
                    Trace[F].put("peer.service" -> serviceName) >> d.get
                  }
              }
              .flatMap { services =>
                Random[F]
                  .betweenInt(0, services.length)
                  .map(services(_))
              }
          }
        }
        .onFinalize(Logger[F].trace(s"👋 shutting down authorityForService($serviceName)"))
    }

  override def authoritiesForService(serviceName: ServiceName): Resource[F, F[Vector[Uri.Authority]]] =
    continuallyUpdating(serviceName, consulBaseUri, longPollTimeout, client, entryPoint)
      .map(_.get)
      .onFinalize(Logger[F].trace(s"👋 shutting down authoritiesForService($serviceName)"))

  /**
   * [[https://www.consul.io/api-docs/health#blocking-queries Consul's documentation]] says
   * "A small random amount of additional `wait` time is added to the supplied maximum wait time
   * to spread out the wake up time of any concurrent requests. This adds up to `wait / 16` additional
   * time to the maximum duration."
   ]
   */
  private val consulAdditionalRandomWaitTimeFactor: Double = 17.0/16.0

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
  private def lookup(serviceName: ServiceName,
                     consulBase: Uri,
                     index: Option[ConsulIndex],
                     longPollTimeout: FiniteDuration,
                     client: Client[F],
                    ): F[(Vector[Uri.Authority], Option[ConsulIndex])] = {
    val requestUri = ConsulServiceDiscoveryAlg.serviceListUri(consulBase, serviceName, index, longPollTimeout)

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
            .timeout((longPollTimeout * consulAdditionalRandomWaitTimeFactor) + 1.second)
            .use { resp =>
              Logger[F].trace(s"📠 ${AnsiColorCodes.red}Consul response ${AnsiColorCodes.reset}") >>
                resp
                  .as[Json]
                  .flatMap {
                    _
                      .asArray
                      .toVector
                      .flatten
                      .traverse {
                        _.asAccumulating[Uri.Authority]
                          .toEither
                          .leftMap(Errors(_))
                          .liftTo[F]
                      }
                  }
                  .tupleRight(resp.headers.get[ConsulIndex])
            }
            .onError {
              case ex if !ex.isInstanceOf[java.util.concurrent.TimeoutException] =>
                Logger[F].error(ex)(s"📠 ${AnsiColorCodes.red}Consul response error${AnsiColorCodes.reset}")
            }
      }
  }

  private def traceFinalizeCase: ExitCase => F[Unit] =
    exitCase => Trace[F].put("ExitCase" -> exitCase.toString)

  private def logFinalizeCase(serviceName: ServiceName): ExitCase => F[Unit] = {
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
   * @param consulBase the base URI where the Consul API can be accessed, e.g. [[http://localhost:8500]]
   * @param longPollTimeout the maximum amount of time to wait before Consul should return a response
   * @param client the `org.http4s.client.Client[F]` used to interact with the Consul API. Should be configured not to timeout on blocking queries
   * @param entryPoint an optional EntryPoint used to construct new traces for background requests. If none, a no-op span is used instead.
   * @return a `cats.effect.Resource` managing the background process and containing an effect to view the current set of available instances
   */
  private def continuallyUpdating(serviceName: ServiceName,
                                  consulBase: Uri,
                                  longPollTimeout: FiniteDuration,
                                  client: Client[F],
                                  entryPoint: Option[EntryPoint[F]],
                                 ): Resource[F, Signal[F, Vector[Uri.Authority]]] =
    Stream.unfoldEval(none[ConsulIndex]) { maybeIndex =>
        inNewLinkedRootSpan(entryPoint) { // since this is a background task, it doesn't make sense
                                          // to directly attach it to the trace that initially started it,
                                          // but linking it to the new root span is helpful
          lookup(serviceName, consulBase, maybeIndex, longPollTimeout, client)
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
      .hold1Resource
      .map(_.changes)
      .onFinalize(Logger[F].trace(s"👋 shutting down continuallyUpdating($serviceName, …)"))

  private def inNewLinkedRootSpan[A](entryPoint: Option[EntryPoint[F]])
                                    (fa: F[A]): F[A] =
    OptionT.fromOption[Resource[F, *]](entryPoint)
      .semiflatMap { ep =>
        Trace[F]
          .kernel
          .map(Span.Options.Defaults.withLink)
          .toResource
          .flatMap {
            ep.root("com.dwolla.consul.ConsulServiceDiscoveryAlg.continuallyUpdating", _)
          }
      }
      .getOrElse(Span.noop)
      .use(Local[F, Span[F]].scope(fa))

  private implicit def jsonEntityDecoder[A: Decoder]: EntityDecoder[F, A] = jsonOf[F, A]
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
        new ConsulServiceDiscoveryAlgImpl(consulBaseUri, longPollTimeout, client, entryPoint)
      }

  private[consul] def serviceListUri(consulBase: Uri,
                                     serviceName: ServiceName,
                                     index: Option[ConsulIndex],
                                     longPollTimeout: FiniteDuration,
                                    ): Uri =
    consulBase / "v1" / "health" / "service" / serviceName +? OnlyHealthyServices +?? index +?? index.as(WaitPeriod(longPollTimeout))

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

@deprecated("no longer used since authorityForService semantics changed to block when no services are available", "v0.3.3")
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
