package cats.effect.std

import cats.syntax.all._
import cats.effect.Sync
import cats.effect.std.Random.ScalaRandom
import org.scalacheck.{Arbitrary, Gen, Shrink}

trait ArbitraryRandom {
  def genRandom[F[_] : Sync]: Gen[Random[F]] =
    Gen.long
      .map(new scala.util.Random(_).pure[F])
      .map(new ScalaRandom[F](_) {})

  implicit def arbRandom[F[_] : Sync]: Arbitrary[Random[F]] = Arbitrary(genRandom[F])

  implicit def shrinkRandom[F[_]]: Shrink[Random[F]] = Shrink.shrinkAny
}
