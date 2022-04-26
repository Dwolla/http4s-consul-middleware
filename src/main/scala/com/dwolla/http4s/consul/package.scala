package com.dwolla.http4s

import cats._
import cats.effect.std.Random
import cats.syntax.all._
import monix.newtypes.NewtypeWrapped
import org.http4s.Header.Single
import org.http4s.{Header, QueryParam, QueryParamEncoder, Uri}
import org.typelevel.ci._

import scala.concurrent.duration._

package object consul {
  case object OnlyHealthyServices {
    implicit val onlyHealthyServicesQueryParam: QueryParam[OnlyHealthyServices.type] = QueryParam.fromKey("passing")
  }

  type ServiceName = ServiceName.Type
  type ConsulIndex = ConsulIndex.Type
  type WaitPeriod = WaitPeriod.Type
}

package consul {
  object ServiceName extends NewtypeWrapped[String] {
    implicit val serviceNameSegmentEncoder: Uri.Path.SegmentEncoder[ServiceName] = Uri.Path.SegmentEncoder[String].contramap(_.value)
  }

  object ConsulIndex extends NewtypeWrapped[String] {
    implicit val consulIndexHeader: Header[ConsulIndex, Single] = Header.create(
      ci"X-Consul-Index",
      _.value,
      ConsulIndex(_).asRight
    )
    implicit val consulIndexQueryParam: QueryParam[ConsulIndex] = QueryParam.fromKey("index")
    implicit val consulIndexQueryParamEncoder: QueryParamEncoder[ConsulIndex] = QueryParamEncoder[String].contramap(_.value)
  }

  object WaitPeriod extends NewtypeWrapped[FiniteDuration] {
    implicit val waitQueryParam: QueryParam[WaitPeriod] = QueryParam.fromKey("wait")
    implicit val waitQueryParamEncoder: QueryParamEncoder[WaitPeriod] = QueryParamEncoder[String].contramap(d => s"${d.value.toSeconds}s")
  }

  /**
   * This newtype exists to introduce friction. It makes it harder to accidentally
   * evaluate the effect of getting the current value from a Signal.
   */
  case class GetCurrentValue[F[_], A](fa: F[A])
  object GetCurrentValue {
    def liftK[F[_]]: F ~> GetCurrentValue[F, *] = new (F ~> GetCurrentValue[F, *]) {
      override def apply[A](fa: F[A]): GetCurrentValue[F, A] = GetCurrentValue(fa)
    }

    implicit def GetCurrentValueMonad[F[_] : Monad]: Monad[GetCurrentValue[F, *]] = new Monad[GetCurrentValue[F, *]] {
      override def flatMap[A, B](gcv: GetCurrentValue[F, A])
                                (f: A => GetCurrentValue[F, B]): GetCurrentValue[F, B] =
        GetCurrentValue(gcv.fa.flatMap(f(_).fa))

      override def tailRecM[A, B](a: A)
                                 (f: A => GetCurrentValue[F, Either[A, B]]): GetCurrentValue[F, B] =
        GetCurrentValue(Monad[F].tailRecM(a)(f(_).fa))

      override def pure[A](x: A): GetCurrentValue[F, A] =
        GetCurrentValue(x.pure[F])
    }

    implicit def GetCurrentValueEq[F[_], A](implicit F: Eq[F[A]]): Eq[GetCurrentValue[F, A]] = Eq.by(_.fa)

    implicit def GetCurrentValueRandom[F[_] : Random]: Random[GetCurrentValue[F, *]] = Random[F].mapK(liftK)
  }
}
