import SbtLogger._
import cats._
import cats.effect.{Concurrent, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax.EncoderOps
import io.circe.{Codec, Decoder, Encoder, Json}
import org.http4s.Method._
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.client.{Client, middleware}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.syntax.all._
import org.http4s.{EntityDecoder, EntityEncoder, Request, Status, Uri}
import org.typelevel.log4cats.Logger
import sbt.Keys.streams
import sbt.{AllRequirements, AutoPlugin, Def, Keys, PluginTrigger, Plugins, URI, inputKey, settingKey, taskKey, uri, Logger => SbtUtilLogger}

import java.util.UUID
import scala.language.higherKinds
import scala.util.Try

case class ClientWrapper(client: Client[IO])

case class ConsulService(id: UUID, name: String, address: URI, port: Int)
object ConsulService {
  implicit val uriEncoder: Encoder[URI] = Encoder[String].contramap(_.toString)
  implicit val uriDecoder: Decoder[URI] = Decoder[String].emapTry(s => Try(uri(s)))
  implicit val consulServiceCodec: Codec[ConsulService] = deriveCodec

  import sbt.internal.util.complete.DefaultParsers._
  import sbt.internal.util.complete.Parser

  private val uuid: Parser[UUID] = mapOrFail(token(NotSpace, "<Service ID>"))(UUID.fromString)

  val consulServiceParser: Parser[ConsulService] =
    Space ~>
      ((uuid <~ Space).? ~
        (token(NotSpace, "<Service Name>") <~ Space) ~
        (token(basicUri, "<Service Address>") <~ Space) ~
        Port
        ).map {
        case (((a, b), c), d) => ConsulService(a.getOrElse(UUID.randomUUID()), b, c, d)
      }

  val consulServiceId: Parser[String] =
    Space ~> token(NotSpace, "<Service ID>")
}

object ConsulPlugin extends AutoPlugin {
  object autoImport {
    val http4sClient = settingKey[ClientWrapper]("http4s Client")
    val register = inputKey[ConsulService]("Register a service with Consul")
    val deregister = inputKey[Unit]("Deregister a service from Consul")
    val consulApiBaseUri = taskKey[Uri]("URI where the Consul API is based")
  }

  import autoImport._

  override def trigger: PluginTrigger = AllRequirements

  override def requires: Plugins = empty

  override lazy val projectSettings = Seq(
    http4sClient := Def.setting {
      implicit val l: SbtUtilLogger = Keys.sLog.value

      ClientWrapper {
        EmberClientBuilder
          .default[IO]
          .build
          .map(middleware.Logger(logHeaders = true, logBody = true, logAction = (Logger[IO].trace(_: String)).some)(_))
          .allocated
          .unsafeRunSync()
          ._1
      }
    }.value,

    register := Def.inputTask {
      implicit val l: SbtUtilLogger = streams.value.log

      val service = ConsulService.consulServiceParser.parsed

      RegisterWithConsul(service, consulApiBaseUri.value, http4sClient.value.client)
    }.evaluated,

    deregister := Def.inputTask {
      implicit val l: SbtUtilLogger = streams.value.log

      val id = ConsulService.consulServiceId.parsed

      DeregisterFromConsul(id, consulApiBaseUri.value, http4sClient.value.client)
    }.evaluated,

    consulApiBaseUri := uri"http://localhost:8500"
  )
}

object RegisterWithConsul {
  def apply(service: ConsulService, consulBase: Uri, client: Client[IO])
           (implicit log: SbtUtilLogger): ConsulService = {
    import org.http4s.circe.jsonEncoder

    ConsulRegistrationAlgebra(client, consulBase)
      .register(service)
      .as(service)
      .unsafeRunSync()
  }
}

object DeregisterFromConsul {
  def apply(id: String, consulBase: Uri, client: Client[IO])
           (implicit log: SbtUtilLogger): Unit = {
    import org.http4s.circe.jsonEncoder

    ConsulRegistrationAlgebra(client, consulBase)
      .deregister(id)
      .unsafeRunSync()
  }
}

trait ConsulRegistrationAlgebra[F[_]] {
  def register(service: ConsulService): F[Unit]
  def deregister(id: String): F[Unit]
}

object ConsulRegistrationAlgebra {
  private def successOrRaise[F[_] : Concurrent](client: Client[F])
                                               (req: Request[F])
                                               (implicit E: EntityDecoder[F, Unit]): F[Unit] =
    client
      .expectOr[Unit](req) { resp =>
        resp
          .body
          .through(fs2.text.utf8.decode)
          .compile
          .string
          .map { body =>
            ConsulApiError(resp.status, body, req)
          }
      }
      .void

  def apply[F[_] : Concurrent : Logger](client: Client[F], consulApiBaseUri: Uri)
                                       (implicit
                                        EE: EntityEncoder[F, Json],
                                        ED: EntityDecoder[F, Unit]): ConsulRegistrationAlgebra[F] = new ConsulRegistrationAlgebra[F] with Http4sClientDsl[F] {
    override def register(service: ConsulService): F[Unit] =
      successOrRaise(client)(PUT(service.asJson, consulApiBaseUri / "v1" / "agent" / "service" / "register")) >>
        Logger[F].info(s"registered $service with Consul at $consulApiBaseUri")

    override def deregister(id: String): F[Unit] =
      successOrRaise(client)(PUT(consulApiBaseUri / "v1" / "agent" / "service" / "deregister" / id)) >>
        Logger[F].info(s"deregistered $id from Consul at $consulApiBaseUri")
  }
}

case class ConsulApiError[F[_]](status: Status, respBody: String, req: Request[F]) extends RuntimeException(
  s"""🔥 An error occurred during an interaction with the Consul API
     |🔥
     |🔥 The following request was made:
     |🔥   ${req.toString()}
     |🔥
     |🔥 Received $status response with body:
     |🔥
     |🔥 $respBody
     |""".stripMargin
)
