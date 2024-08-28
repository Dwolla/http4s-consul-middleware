package com.dwolla.consul.smithy4s

import com.dwolla.consul.smithy.{Discoverable, ServiceName}
import org.http4s.Uri
import smithy4s.{Hints, Service}
import cats.syntax.all._
import org.http4s.Uri.{Host, Scheme}
import org.http4s.syntax.all._

import scala.reflect.macros.blackbox

object DiscoveryMacros {
  private val consulScheme: Scheme = scheme"consul"

  def makeUri[Alg[_[_, _, _, _, _]], Op[_, _, _, _, _]](c: blackbox.Context)
                                                       (service: c.Expr[smithy4s.Service.Mixin[Alg, Op]]): c.Expr[Uri] = {
    import c.universe.{Try => _, _}

    c.Expr[Uri](q"org.http4s.Uri(scheme = scala.Option(org.http4s.Uri.Scheme.unsafeFromString(${consulScheme.value})), authority = scala.Option(org.http4s.Uri.Authority(None, ${makeHost[Alg, Op](c)(service)}, None)))")
  }

  def makeUriAuthority[Alg[_[_, _, _, _, _]], Op[_, _, _, _, _]](c: blackbox.Context)
                                                                (service: c.Expr[smithy4s.Service.Mixin[Alg, Op]]): c.Expr[Uri.Authority] = {
    import c.universe.{Try => _, _}

    c.Expr[Uri.Authority](q"org.http4s.Uri.Authority(host =${makeHost[Alg, Op](c)(service)})")
  }

  def makeHost[Alg[_[_, _, _, _, _]], Op[_, _, _, _, _]](c: blackbox.Context)
                                                        (service: c.Expr[smithy4s.Service.Mixin[Alg, Op]]): c.Expr[Host] = {
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
  def apply[Alg[_[_, _, _, _, _]], Op[_, _, _, _, _]](service: Service.Mixin[Alg, Op]): Uri.Authority =
    macro DiscoveryMacros.makeUriAuthority[Alg, Op]
}

object HostFromService {
  def apply[Alg[_[_, _, _, _, _]], Op[_, _, _, _, _]](service: Service.Mixin[Alg, Op]): Host =
    macro DiscoveryMacros.makeHost[Alg, Op]
}

object UriFromService {
  def apply[Alg[_[_, _, _, _, _]], Op[_, _, _, _, _]](service: Service.Mixin[Alg, Op]): Uri =
    macro DiscoveryMacros.makeUri[Alg, Op]
}
