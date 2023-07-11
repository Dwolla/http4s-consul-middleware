addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.4.4")
addSbtPlugin("org.typelevel" % "sbt-typelevel-ci-release" % "0.4.22")
addSbtPlugin("org.typelevel" % "sbt-typelevel-mergify" % "0.4.22")
addSbtPlugin("org.portable-scala" % "sbt-crossproject" % "1.3.2")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.13.2")

libraryDependencies ++= {
  val http4sVersion = "0.23.21"

  Seq(
    "org.http4s" %% "http4s-ember-client" % http4sVersion,
    "org.http4s" %% "http4s-circe" % http4sVersion,
    "io.circe" %% "circe-generic" % "0.14.5",
  )
}
