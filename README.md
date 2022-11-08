# http4s Consul Middleware

Middleware for http4s to allow users to discover the host and port for an HTTP request using Consul service discovery.

The middleware rewrites URIs of the form `consul://service-name/path` to an HTTP URI, using Consul
to discover an available instance of the service `service-name`. For example, if Consul knew about
a service named `httpd` available at `127.0.0.1:80`, a request made to `consul://httpd/example/path`
would be rewritten and requested from `http://127.0.0.1:80/example/path`.

## Usage

http4s middleware wrap an underlying `Client[F]`, so we need to have such a client available. Consul's
API uses "[blocking queries](https://www.consul.io/api/features/blocking)" implemented via long polling
to immediately notify listeners when its internal state changes. For this reason, we recommend 
constructing a second `Client[F]` with a much longer timeout.

```scala
import cats.effect._, cats.syntax.all._, cats.effect.std._
import fs2.Stream
import org.http4s._, org.http4s.Method.GET, org.http4s.client.Client, org.http4s.ember.client.EmberClientBuilder, org.http4s.syntax.all._, org.http4s.client.dsl.io._
import org.typelevel.log4cats.Logger, org.typelevel.log4cats.slf4j.Slf4jLogger
import scala.concurrent.duration._
import com.dwolla.http4s.consul._
import cats.effect.unsafe.implicits.global

val exampleConsulUri = uri"consul://httpd/"

def clientWithTimeout[F[_] : Async](timeout: FiniteDuration): Resource[F, Client[F]] =
  EmberClientBuilder.default[F].withTimeout(timeout).build

def longPollClient[F[_] : Async]: Resource[F, Client[F]] = clientWithTimeout(10.minutes)

def typicalClient[F[_] : Async]: Resource[F, Client[F]] = clientWithTimeout(20.seconds)

def consulServiceDiscoveryAlg[F[_] : Async : Logger : Random]: Resource[F, ConsulServiceDiscoveryAlg[F]] =
  longPollClient[F].map(ConsulServiceDiscoveryAlg(uri"http://localhost:8500", 1.minute, _))

def consulAwareClient[F[_] : Async : Logger : Random]: Resource[F, Client[F]] =
  (consulServiceDiscoveryAlg[F], typicalClient[F])
    .parMapN(ConsulMiddleware(_)(_))
    .flatten

Random.scalaUtilRandom[IO].flatMap { implicit random =>
  Slf4jLogger.create[IO].flatMap { implicit logger =>
    // in a real app you'd probably want a real Trace implementation
    import natchez.Trace.Implicits.noop

    Stream.resource(consulAwareClient[IO])
      .flatMap { client =>
        // make a GET call to consul://httpd/, every 2 seconds, until shut down
        //
        // if you change the state of the Consul cluster by registering
        // or de-registering services named "httpd", you should see the
        // requests going to different endpoints over time.
        
        Stream.repeatEval(client.successful(GET(exampleConsulUri))
          .flatMap {
            case true => Logger[IO].info("🔮 success")
            case false => Logger[IO].info("🔮 failure")
          })
          .metered(2.seconds)
      }
      .compile
      .drain
  }
}
  .unsafeRunSync()
```
