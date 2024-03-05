addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.0")
addSbtPlugin("org.typelevel" % "sbt-typelevel-ci-release" % "0.5.4")
addSbtPlugin("org.typelevel" % "sbt-typelevel-mergify" % "0.5.4")
addSbtPlugin("org.portable-scala" % "sbt-crossproject" % "1.3.2")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.15.0")

libraryDependencies ++= {
  val http4sVersion = "0.23.26"

  Seq(
    "org.http4s" %% "http4s-ember-client" % http4sVersion,
    "org.http4s" %% "http4s-circe" % http4sVersion,
    "io.circe" %% "circe-generic" % "0.14.6",
  )
}
