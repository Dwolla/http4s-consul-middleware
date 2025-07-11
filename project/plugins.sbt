addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.2")
addSbtPlugin("org.typelevel" % "sbt-typelevel-ci-release" % "0.8.0")
addSbtPlugin("org.typelevel" % "sbt-typelevel-mergify" % "0.8.0")
addSbtPlugin("org.portable-scala" % "sbt-crossproject" % "1.3.2")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.19.0")
addSbtPlugin("com.disneystreaming.smithy4s"  % "smithy4s-sbt-codegen" % "0.18.38")

libraryDependencies ++= {
  val http4sVersion = "0.23.30"

  Seq(
    "org.http4s" %% "http4s-ember-client" % http4sVersion,
    "org.http4s" %% "http4s-circe" % http4sVersion,
    "io.circe" %% "circe-generic" % "0.14.14",
  )
}
