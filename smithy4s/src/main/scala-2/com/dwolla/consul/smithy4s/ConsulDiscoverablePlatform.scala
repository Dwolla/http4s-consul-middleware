package com.dwolla.consul.smithy4s

import cats.syntax.all._
import com.dwolla.consul.smithy._
import org.http4s.Uri.{Host, Scheme}
import org.typelevel.scalaccompat.annotation.nowarn212
import smithy4s.Hints

import scala.reflect.macros.whitebox
import scala.util.Try

trait ConsulDiscoverablePlatform {
  implicit def derivedInstance[Alg[_[_]]]: ConsulDiscoverable[Alg] =
    macro ConsulDiscoverableMacros.makeInstance[Alg]
}

object ConsulDiscoverableMacros {
  @nowarn212("msg=local val (liftableScheme|liftableHost) in method makeInstance is never used")
  def makeInstance[Alg[_[_]]](c: whitebox.Context): c.Expr[ConsulDiscoverable[Alg]] = {
    import c.universe.{Try => _, _}

    implicit val liftableScheme: Liftable[Scheme] = Liftable { scheme: Scheme =>
      q"""_root_.org.http4s.Uri.Scheme.unsafeFromString(${scheme.value})"""
    }
    implicit val liftableHost: Liftable[Host] = Liftable { host: Host =>
      q"""_root_.org.http4s.Uri.Host.unsafeFromString(${host.value})"""
    }

    val consulScheme = DiscoveryMacros.consulScheme.some

    def findHintsInTree(tpe: Tree): Either[String, Hints] =
      Try {
        tpe.collect {
            case x: TypTree =>
              c.eval(c.Expr[Hints](q"${x.symbol.companion}.hints"))
          }
          .headOption
          .toRight(s"could not find hints for $tpe")
      }
        .toEither
        .leftMap(_.toString ++ s" (is $tpe a Smithy4s Service?)")
        .flatten

    def getDiscoverableFromHints(tpe: Tree): Hints => Either[String, Discoverable] =
      _.get(Discoverable.tagInstance)
        .toRight(s"could not find Discoverable hint for $tpe")

    val getHostFromDiscoverable: PartialFunction[Discoverable, Either[String, Host]] = {
      case Discoverable(ServiceName(serviceName)) =>
        Host.fromString(serviceName)
          .leftMap(_.message)
    }

    def hostToConsulDiscoverableExpr(tpe: Tree): Host => c.Expr[ConsulDiscoverable[Alg]] = host =>
      c.Expr[ConsulDiscoverable[Alg]](
        q"""
          new _root_.com.dwolla.consul.smithy4s.ConsulDiscoverable[$tpe] {
            override def host: _root_.org.http4s.Uri.Host = $host
            override def uriAuthority: _root_.org.http4s.Uri.Authority = _root_.org.http4s.Uri.Authority(host = host)
            override def uri: _root_.org.http4s.Uri = _root_.org.http4s.Uri(scheme = $consulScheme, authority = _root_.scala.Option(uriAuthority))
          }
        """)

    c.macroApplication match {
      case TypeApply(_, List(tpe)) if tpe.symbol.companion != NoSymbol =>
        val maybeExpr = findHintsInTree(tpe)
          .flatMap(getDiscoverableFromHints(tpe))
          .flatMap(getHostFromDiscoverable)
          .map(hostToConsulDiscoverableExpr(tpe))

        maybeExpr.fold(c.abort(c.enclosingPosition, _), identity)
      case TypeApply(_, List(tpe)) if tpe.symbol.companion == NoSymbol =>
        c.abort(c.enclosingPosition, s"$tpe is not a Smithy4s Service")
      case other => c.abort(c.enclosingPosition, s"found $other, which is not a Smithy4s Service")
    }
  }
}
