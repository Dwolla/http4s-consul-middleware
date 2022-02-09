import cats.effect.Sync
import sbt.{Logger => SLogger}
import org.typelevel.log4cats.Logger
import sbt.util.Level

import scala.language.higherKinds

object SbtLogger extends SbtLogger

trait SbtLogger {
  implicit def instance[F[_] : Sync](implicit log: SLogger): Logger[F] = new Logger[F] {
    private def logF(level: Level.Value)(msg: => String): F[Unit] =
      Sync[F].delay(log.log(level, msg))

    private def logT(level: Level.Value)(t: Throwable, message: => String): F[Unit] =
      logF(level)(
        s"""$message
           |
           |Caused by:
           |
           |${t.toString}
           |""".stripMargin)

    override def error(t: Throwable)(message: => String): F[Unit] =
      logT(Level.Error)(t, message)

    override def warn(t: Throwable)(message: => String): F[Unit] =
      logT(Level.Warn)(t, message)

    override def info(t: Throwable)(message: => String): F[Unit] =
      logT(Level.Info)(t, message)

    override def debug(t: Throwable)(message: => String): F[Unit] =
      logT(Level.Debug)(t, message)

    override def trace(t: Throwable)(message: => String): F[Unit] =
      logT(Level.Debug)(t, message)

    override def error(message: => String): F[Unit] =
      logF(Level.Error)(message)

    override def warn(message: => String): F[Unit] =
      logF(Level.Warn)(message)

    override def info(message: => String): F[Unit] =
      logF(Level.Info)(message)

    override def debug(message: => String): F[Unit] =
      logF(Level.Debug)(message)

    override def trace(message: => String): F[Unit] =
      logF(Level.Debug)(message)
  }
}
