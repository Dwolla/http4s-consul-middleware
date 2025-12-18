package com.dwolla.consul.smithy4s

import cats.syntax.all._
import com.dwolla.consul.ServiceName
import com.dwolla.consul.smithy
import com.dwolla.consul.smithy.{ServiceName => _, _}
import org.http4s.Uri
import org.typelevel.scalaccompat.annotation.nowarn212
import smithy4s.Hints

import scala.reflect.macros.whitebox
import scala.util.Try

trait ConsulDiscoverablePlatform {
  implicit def derivedInstance[Alg[_[_]]]: ConsulDiscoverable[Alg] =
    macro ConsulDiscoverableMacros.makeInstance[Alg]
}

object ConsulDiscoverableMacros {
  @nowarn212("msg=local val (liftableServiceName|liftableHost) in method makeInstance is never used")
  def makeInstance[Alg[_[_]]](c: whitebox.Context): c.Expr[ConsulDiscoverable[Alg]] = {
    import c.universe.{Try => _, _}

    implicit val liftableHost: Liftable[Uri.Host] = Liftable { host: Uri.Host =>
      q"""_root_.org.http4s.Uri.Host.unsafeFromString(${host.value})"""
    }
    implicit val liftableServiceName: Liftable[ServiceName] = Liftable { serviceName: ServiceName =>
      q"""_root_.com.dwolla.consul.ServiceName(${serviceName.value})"""
    }

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

    val getHostFromDiscoverable: PartialFunction[Discoverable, Either[String, (ServiceName, Uri.Host)]] = {
      case Discoverable(smithy.ServiceName(serviceName)) =>
        Uri.Host.fromString(serviceName)
          .leftMap(_.message)
          .tupleLeft(ServiceName(serviceName))
    }

    def hostToConsulDiscoverableExpr(tpe: Tree): (ServiceName, Uri.Host) => c.Expr[ConsulDiscoverable[Alg]] = (serviceName, host) =>
      c.Expr[ConsulDiscoverable[Alg]](
        q"""
          _root_.com.dwolla.consul.smithy4s.ConsulDiscoverable.make[$tpe]($serviceName, $host)
        """)

    c.macroApplication match {
      case TypeApply(_, List(tpe)) if tpe.symbol.companion != NoSymbol =>
        val maybeExpr = findHintsInTree(tpe)
          .flatMap(getDiscoverableFromHints(tpe))
          .flatMap(getHostFromDiscoverable)
          .map(hostToConsulDiscoverableExpr(tpe).tupled)

        maybeExpr.fold(c.abort(c.enclosingPosition, _), identity)
      case TypeApply(_, List(tpe)) if tpe.symbol.companion == NoSymbol =>
        c.abort(c.enclosingPosition, s"$tpe is not a Smithy4s Service")
      case other => c.abort(c.enclosingPosition, s"found $other, which is not a Smithy4s Service")
    }
  }
}
