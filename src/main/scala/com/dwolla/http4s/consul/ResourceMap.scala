package com.dwolla.http4s.consul

import cats.data.OptionT
import cats.effect._
import cats.effect.syntax.all._
import cats.syntax.all._
import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.Logger

/**
 * A data structure to map keys to (1) an effect that can view a most recent value
 * for the key and (2) a background process that refreshes the most recent value
 * over time.
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
  def apply[F[_] : Async : Logger, K, V](startRefreshingK: K => Resource[F, GetCurrentValue[F, V]]): Resource[F, ResourceMap[F, K, V]] =
    makeMapRefAndKeyList[F, K, (GetCurrentValue[F, V], F[Unit])]
      .toResource
      .flatMap { case (mapRef, keys) =>
        Resource.pure {
          new ResourceMap[F, K, V] {
            override def get(k: K): F[V] =
              mapRef(k)
                .get
                .flatMap {
                  case Some((viewer, _)) => // we've seen this key before, and we started its background process. use its viewer to view the value
                    viewer.fa
                  case None =>              // we haven't seen this key, so start refreshing its value and stick the effects into the MapRef
                    startRefreshingK(k)
                      .allocated            // allocate the resource so it starts processing; we'll call the finalizer when the ResourceMap's Resource is finalized
                      .flatTap { viewerAndFinalizer =>
                        Logger[F].trace(s"📥 setting viewer and finalizer for $k") >>
                          mapRef(k).set(viewerAndFinalizer.some)
                      }
                      .flatMap { case (viewer, _) =>
                        viewer.fa
                      }
                }
          }
        }.onFinalize {
          keys
            .flatMap { keyChain: List[K] =>
              keyChain.foldMapA { key: K =>
                OptionT(mapRef(key).get)
                  .semiflatMap { case (_, finalizer) =>
                    Logger[F].trace(s"📤 using finalizer for key $key") >> finalizer
                  }
                  .getOrElseF(Logger[F].warn(s"🔥 no finalizer found for key $key"))
              }
            }
        }
      }

  private def makeMapRefAndKeyList[F[_] : Concurrent, K, V]: F[(MapRef[F, K, Option[V]], F[List[K]])] =
    Ref[F].of(Map.empty[K, V]).map { ref =>
      (MapRef.fromSingleImmutableMapRef(ref), ref.get.map(_.keys.toList))
    }
}
