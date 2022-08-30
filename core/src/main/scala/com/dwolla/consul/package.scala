package com.dwolla

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
}
