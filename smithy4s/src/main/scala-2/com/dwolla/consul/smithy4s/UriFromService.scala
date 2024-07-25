package com.dwolla.consul.smithy4s

import com.dwolla.consul.smithy.{Discoverable, ServiceName}
import org.http4s.Uri
import smithy4s.Hints
import cats.syntax.all._
import org.http4s.Uri.{Host, Scheme}
import org.http4s.syntax.all._

import scala.reflect.macros.blackbox
import scala.util.Try

object DiscoveryMacros {
  private val consulScheme: Scheme = scheme"consul"

  def makeUri(c: blackbox.Context): c.Expr[Uri] = {
    import c.universe.{Try => _, _}

    c.Expr[Uri](q"org.http4s.Uri(scheme = scala.Option(org.http4s.Uri.Scheme.unsafeFromString(${consulScheme.value})), authority = scala.Option(org.http4s.Uri.Authority(None, ${makeHost(c)}, None)))")
  }

  def makeUriAuthority(c: blackbox.Context): c.Expr[Uri.Authority] = {
    import c.universe.{Try => _, _}

    c.Expr[Uri.Authority](q"org.http4s.Uri.Authority(host =${makeHost(c)})")
  }

  def makeHost(c: blackbox.Context): c.Expr[Host] = {
    import c.universe.{Try => _, _}

    c.macroApplication match {
      case TypeApply(_, List(tpe)) if tpe.symbol.companion != NoSymbol =>
        Try {
          tpe
            .collect {
              case x: TypTree =>
                c.eval(c.Expr[Hints](q"${x.symbol.companion}.hints"))
            }
            .headOption
            .toRight(s"could not find hints for $tpe")
        }
          .toEither
          .leftMap(_.toString ++ s" (is $tpe a Smithy4s Service?)")
          .flatten
          .flatMap {
            _.get(Discoverable.tagInstance)
              .toRight(s"could not find Discoverable hint for $tpe")
          }
          .flatMap {
            case Discoverable(ServiceName(serviceName)) =>
              Host.fromString(serviceName)
                .leftMap(_.message)
          }
          .map { host =>
            c.Expr[Host](q"org.http4s.Uri.Host.unsafeFromString(${host.value})")
          }
          .fold(c.abort(c.enclosingPosition, _), identity)
      case TypeApply(_, List(tpe)) if tpe.symbol.companion == NoSymbol =>
        c.abort(c.enclosingPosition, s"$tpe is not a Smithy4s Service")
      case other => c.abort(c.enclosingPosition, s"found $other, which is not a Smithy4s Service")
    }
  }
}

object UriAuthorityFromService {
  def apply[Alg[_[_]]]: Uri.Authority = macro DiscoveryMacros.makeUriAuthority
}

object HostFromService {
  def apply[Alg[_[_]]]: Host = macro DiscoveryMacros.makeHost
}

object UriFromService {
  def apply[Alg[_[_]]]: Uri = macro DiscoveryMacros.makeUri
}
