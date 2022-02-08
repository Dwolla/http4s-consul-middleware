package com.dwolla.http4s.consul

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

  implicit val uriAuthorityDecoder: Decoder[Uri.Authority] = (c: HCursor) =>
    for {
      host <- c.downField("Address").as[IpAddress].map(Host.fromIpAddress)
      port <- c.downField("Port").as[Port].map(_.value.some)
    } yield Uri.Authority(None, host, port)

  implicit val uriAuthorityEncoder: Encoder[Uri.Authority] = (a: Uri.Authority) =>
    json"""
          {
            "Address": ${a.host.value},
            "Port": ${a.port}
          }
        """
}

object ThirdPartyTypeCodecs extends ThirdPartyTypeCodecs
