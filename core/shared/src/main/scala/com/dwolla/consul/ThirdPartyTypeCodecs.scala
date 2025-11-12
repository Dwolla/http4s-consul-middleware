package com.dwolla.consul

import cats.syntax.all._
import com.comcast.ip4s
import com.comcast.ip4s.{IpAddress, Port}
import io.circe.literal._
import io.circe.{Decoder, Encoder, HCursor}
import org.http4s.Uri
import org.http4s.Uri.Host

trait ThirdPartyTypeCodecs {
  implicit val encodeIpAddress: Encoder[ip4s.IpAddress] = Encoder[String].contramap(_.toUriString)
  implicit val decodeIpAddress: Decoder[ip4s.IpAddress] = Decoder[String].emap(ip4s.IpAddress.fromString(_).toRight("IP address could not be decoded"))

  implicit val encodePort: Encoder[ip4s.Port] = Encoder[Int].contramap(_.value)
  implicit val decodePort: Decoder[ip4s.Port] = Decoder[Int].emap(ip4s.Port.fromInt(_).toRight("Port could not be decoded"))

  implicit val uriAuthorityDecoder: Decoder[Uri.Authority] = Decoder.accumulatingInstance { (c: HCursor) =>
    val host =
      c.downField("Service")
        .downField("Address")
        .asAcc[IpAddress]
        .findValid {
          c.downField("Node").downField("Address").asAcc[IpAddress]
        }
        .map(Host.fromIpAddress)

    val port = c.downField("Service").downField("Port").asAcc[Port].map(_.value.some)

    (host, port).mapN(Uri.Authority(None, _, _))
  }

  implicit val uriAuthorityEncoder: Encoder[Uri.Authority] = (a: Uri.Authority) =>
    json"""
          {
            "Address": ${a.host.value},
            "Port": ${a.port}
          }
        """
}

object ThirdPartyTypeCodecs extends ThirdPartyTypeCodecs
