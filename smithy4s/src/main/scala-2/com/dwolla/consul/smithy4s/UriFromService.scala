package com.dwolla.consul.smithy4s

import com.dwolla.consul.smithy.{Discoverable, ServiceName}
import org.http4s.Uri
import smithy4s.{Hints, Service}
import cats.syntax.all._
import org.http4s.Uri.{Host, Scheme}
import org.http4s.syntax.all._

import scala.reflect.macros.blackbox

object DiscoveryMacros {
  private[smithy4s] val consulScheme: Scheme = scheme"consul"

  def makeUri[Alg[_[_, _, _, _, _]]](c: blackbox.Context)
                                    (service: c.Expr[smithy4s.Service[Alg]]): c.Expr[Uri] = {
    import c.universe.{Try => _, _}

    c.Expr[Uri](q"org.http4s.Uri(scheme = scala.Option(org.http4s.Uri.Scheme.unsafeFromString(${consulScheme.value})), authority = scala.Option(org.http4s.Uri.Authority(None, ${makeHost[Alg](c)(service)}, None)))")
  }

  def makeUriAuthority[Alg[_[_, _, _, _, _]]](c: blackbox.Context)
                                             (service: c.Expr[smithy4s.Service[Alg]]): c.Expr[Uri.Authority] = {
    import c.universe.{Try => _, _}

    c.Expr[Uri.Authority](q"org.http4s.Uri.Authority(host =${makeHost[Alg](c)(service)})")
  }

  def makeHost[Alg[_[_, _, _, _, _]]](c: blackbox.Context)
                                     (service: c.Expr[smithy4s.Service[Alg]]): c.Expr[Host] = {
    import c.universe.{Try => _, _}

    val cleanService = c.untypecheck(service.tree.duplicate)

    c.eval(c.Expr[Hints](q"$cleanService.hints"))
      .get(Discoverable.tagInstance)
      .toRight(s"could not find Discoverable hint for ${cleanService.symbol.fullName}")
      .flatMap {
        case Discoverable(ServiceName(serviceName)) =>
          Host.fromString(serviceName)
            .leftMap(_.message)
      }
      .map { host =>
        c.Expr[Host](q"org.http4s.Uri.Host.unsafeFromString(${host.value})")
      }
      .fold(c.abort(c.enclosingPosition, _), identity)
  }
}

object UriAuthorityFromService {
  def apply[Alg[_[_, _, _, _, _]]](service: Service[Alg]): Uri.Authority =
    macro DiscoveryMacros.makeUriAuthority[Alg]
}

object HostFromService {
  def apply[Alg[_[_, _, _, _, _]]](service: Service[Alg]): Host =
    macro DiscoveryMacros.makeHost[Alg]
}

object UriFromService {
  def apply[Alg[_[_, _, _, _, _]]](service: Service[Alg]): Uri =
    macro DiscoveryMacros.makeUri[Alg]
}
