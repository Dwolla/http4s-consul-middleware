package com.dwolla.consul.smithy4s

import cats._
import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all._
import com.comcast.ip4s
import com.comcast.ip4s.Arbitraries._
import com.comcast.ip4s.Port
import com.dwolla.consul.ConsulUriResolver
import com.dwolla.test._
import fs2.Chunk
import munit._
import org.http4s.Uri._
import org.http4s.client.Client
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import org.http4s.syntax.all._
import org.http4s.{EntityEncoder, HttpRoutes, MediaType, Uri}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck._
import org.scalacheck.effect.PropF
import smithy4s._
import smithy4s.http4s.SimpleRestJsonBuilder
import smithy4s.json.Json

class ConsulMiddlewareSpec
  extends CatsEffectSuite
    with ScalaCheckEffectSuite {

  private val genUserInfo: Gen[UserInfo] =
    for {
      username <- arbitrary[String]
      password <- arbitrary[Option[String]]
    } yield UserInfo(username, password)
  private implicit val arbUserInfo: Arbitrary[UserInfo] = Arbitrary(genUserInfo)

  private val genUriAuthority: Gen[Uri.Authority] =
    for {
      userinfo <- arbitrary[Option[UserInfo]]
      host <- Gen.oneOf[ip4s.Host](ip4s.Arbitraries.ipGenerator, ip4s.Arbitraries.idnGenerator, ip4s.Arbitraries.hostnameGenerator)
      port <- arbitrary[Option[Port]]
    } yield Uri.Authority(userinfo, Host.fromIp4sHost(host), port.map(_.value))
  private implicit val arbUriAuthority: Arbitrary[Uri.Authority] = Arbitrary(genUriAuthority)

  private val genGreetOutput: Gen[GreetOutput] = arbitrary[Option[String]].map(GreetOutput(_))
  private implicit val arbGreetOutput: Arbitrary[GreetOutput] = Arbitrary(genGreetOutput)

  private implicit def entityEncoder[F[_], A: Schema]: EntityEncoder[F, A] =
    EntityEncoder[F, Chunk[Byte]]
      .withContentType(`Content-Type`(MediaType.application.json))
      .contramap[Blob](b => Chunk.byteBuffer(b.asByteBuffer))
      .contramap(Json.writeBlob(_))

  test("ConsulMiddleware rewrites URI hostnames") {
    PropF.forAllNoShrinkF { (consulAuthority: Uri.Authority,
                             input: Option[String],
                             expected: GreetOutput) =>
      SimpleRestJsonBuilder(HelloService)
        .client(Client.fromHttpApp(new TestServiceImpl[IO](consulAuthority, expected).routes.orNotFound))
        .uri(UriFromService[HelloService])
        .middleware(new ConsulMiddleware(new FakeConsuleUriResolver[IO](consulAuthority)))
        .resource
        .use(_.greet(input))
        .attempt
        .map(assertEquals(_, expected.asRight))
    }
  }
}

class FakeConsuleUriResolver[F[_] : Applicative : Console](consulAuthority: Uri.Authority) extends ConsulUriResolver[F] {
  private val baseAuthority: Uri.Authority = UriAuthorityFromService[HelloService]
  private val consul = scheme"consul"

  override def resolve(uri: Uri): F[Uri] =
    uri match {
      case Uri(Some(`consul`), Some(`baseAuthority`), _, _, _) =>
        uri.copy(scheme = Uri.Scheme.http.some, authority = consulAuthority.some).pure[F]

      case _ => Console[F].println(s"found $uri but not rewriting").as(uri)
    }
}

class TestServiceImpl[F[_] : Monad](expectedAuthority: Uri.Authority,
                                    greetOutput: GreetOutput)
                                   (implicit ev: EntityEncoder[F, GreetOutput]) extends Http4sDsl[F] {
  def routes: HttpRoutes[F] = HttpRoutes.of {
    case req@POST -> Root / "greet" if req.uri.authority.contains(expectedAuthority) =>
      Ok(greetOutput)

    case req@POST -> Root / "greet" =>
      NotFound(s"request on wrong authority: ${req.uri.authority} which is not $expectedAuthority")
  }
}
