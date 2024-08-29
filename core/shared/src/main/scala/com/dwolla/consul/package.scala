package com.dwolla

import cats._
import io.circe.Encoder
import monix.newtypes.NewtypeWrapped
import natchez.TraceableValue
import org.http4s.Header.Single
import org.http4s.QueryParamDecoder.fromUnsafeCast
import org.http4s._
import org.typelevel.ci._

import scala.concurrent.duration._
import scala.util.matching.Regex

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

    def unapply(str: String): Some[ServiceName] =
      Some(ServiceName(str))

    implicit val serviceNameEncoder: Encoder[ServiceName] = Encoder[String].contramap(_.value)
    implicit val serviceNameTraceableValue: TraceableValue[ServiceName] = TraceableValue.stringToTraceValue.contramap(_.value)
  }

  /* The Consul documentation (https://developer.hashicorp.com/consul/api-docs/features/blocking#implementation-details) says
   * that "clients should sanity check that their index is at least 1 after each blocking response is handled"
   * so it should be safe to use a Long and confirm in the parser that the value is positive.
   */
  object ConsulIndex extends NewtypeWrapped[Long] {
    implicit val consulIndexHeader: Header[ConsulIndex, Single] = Header.create(
      ci"X-Consul-Index",
      _.value.toString,
      s =>
        ParseResult.fromTryCatchNonFatal("Consul index must be a positive integer value")(s.toLong)
          .flatMap {
            case x if x > 0 => Right(ConsulIndex(x))
            case x => Left(ParseFailure("Consul index must be greater than 0", s"Found $x <= 0"))
          }
    )
    implicit val consulIndexQueryParam: QueryParam[ConsulIndex] = QueryParam.fromKey("index")
    implicit val consulIndexQueryParamEncoder: QueryParamEncoder[ConsulIndex] = QueryParamEncoder[Long].contramap(_.value)
    implicit val consulIndexQueryParamDecoder: QueryParamDecoder[ConsulIndex] = QueryParamDecoder[Long].map(ConsulIndex(_))
    implicit val consulIndexTraceableValue: TraceableValue[ConsulIndex] = TraceableValue.longToTraceValue.contramap(_.value)
    implicit val consulIndexOrdering: Order[ConsulIndex] = Order.by(_.value)
    implicit class ConsulIndexIncrement(val idx: ConsulIndex) extends AnyVal {
      @inline def increment: ConsulIndex = ConsulIndex(idx.value + 1)
    }
  }

  object WaitPeriod extends NewtypeWrapped[FiniteDuration] {
    private val regex: Regex = """(?<value>\d+)(?<unit>[sm])""".r
    implicit val waitQueryParam: QueryParam[WaitPeriod] = QueryParam.fromKey("wait")
    implicit val waitQueryParamEncoder: QueryParamEncoder[WaitPeriod] = QueryParamEncoder[String].contramap(d => s"${d.value.toSeconds}s")
    implicit val waitQueryParamDecoder: QueryParamDecoder[WaitPeriod] =
      fromUnsafeCast[WaitPeriod] { _.value match {
        case regex(value, unit) =>
          val valueInt = value.toInt

          val seconds = unit match {
            case "s" => valueInt
            case "m" => valueInt * 60
          }

          WaitPeriod(seconds.seconds)
        case other => throw new MatchError(other)
      }}("WaitPeriod")
    implicit val waitPeriodTraceableValue: TraceableValue[WaitPeriod] =
      TraceableValue.stringToTraceValue.contramap(wp => s"${wp.value.toMillis} ms")
  }
}
