package com.dwolla.http4s.consul

import cats.data._
import cats.effect._
import cats.syntax.all._
import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.Logger

/**
 * A data structure to map keys to an effect that can view a most recent value
 * for the key, along with a background process that refreshes the most recent
 * value over time.
 *
 * Because the data structure tracks background processes, it's expected that
 * instances of [[ResourceMap]] will themselves only be available inside a
 * `cats.effect.Resource` managing the lifecycle of that [[ResourceMap]] and
 * its background processes.
 */
sealed trait ResourceMap[F[_], K, V] {
  def get(k: K): F[V]
}

object ResourceMap {
  def apply[F[_] : Async : Logger, K, V](shards: Int)
                                        (keyUpdater: K => Resource[F, GetCurrentValue[F, V]]): Resource[F, ResourceMap[F, K, V]] =
    (keyChain[F, K], viewerAndFinalizerMapRef[F, K, V](shards))
      .parMapN(buildResourceMap(keyUpdater)(_)(_))
      .flatten

  private def buildResourceMap[F[_] : Async : Logger, K, V](startRefreshingK: K => Resource[F, GetCurrentValue[F, V]])
                                                           (keys: Ref[F, Chain[K]])
                                                           (mapRef: MapRef[F, K, Option[(GetCurrentValue[F, V], F[Unit])]]): Resource[F, ResourceMap[F, K, V]] = {
    Resource.pure {
      new ResourceMap[F, K, V] {
        override def get(k: K): F[V] =
          mapRef(k)
            .get
            .flatMap {
              case Some((getter, _)) => // we've seen this key before, and we started its background process. use its getter to view the value
                getter.fa
              case None =>              // we haven't seen this key, so start refreshing its value and stick the effects into the MapRef
                startRefreshingK(k)
                  .allocated            // allocate the resource so it starts processing; we'll call the finalizer when the ResourceMap's Resource is finalized
                  .flatTap { getterAndCloser =>
                    Logger[F].trace(s"📥 setting getter and closer for $k") >>
                      mapRef(k).set(getterAndCloser.some)
                  }
                  .flatMap { case (getter, _) =>
                    getter.fa
                  }
            }
      }
    }.onFinalize {
      keys
        .get
        .flatMap { keyChain: Chain[K] =>
          keyChain.foldMapA { key: K =>
            mapRef(key)
              .get
              .flatMap {
                _.map(Logger[F].trace(s"📤 using closer for key $key") >> _._2)
                  .getOrElse(Logger[F].warn(s"🔥 no closer found for key $key"))
              }
          }
        }
    }
  }

  /**
   * Tracks the keys added to the [[ResourceMap]] so they can be finalized
   * when the outer `Resource[F, ResourceMap[F, K, V]]` is finalized.
   */
  private def keyChain[F[_]: Sync, K]: Resource[F, Ref[F, Chain[K]]] =
    Ref.in[Resource[F, *], F, Chain[K]](Chain.empty)

  /**
   * A `MapRef` that managed, for each key, the effect to view the current
   * value, along with the finalizer responsible for shutting down the
   * background process that refreshes the current value for the key.
   */
  private def viewerAndFinalizerMapRef[F[_] : Async, K, V](shards: Int): Resource[F, MapRef[F, K, Option[(GetCurrentValue[F, V], F[Unit])]]] =
    MapRef.inShardedImmutableMap[Resource[F, *], F, K, (GetCurrentValue[F, V], F[Unit])](shards)

}
