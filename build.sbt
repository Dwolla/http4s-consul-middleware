ThisBuild / organization := "com.dwolla"
ThisBuild / description := "http4s middleware to discover the host and port for an HTTP request using Consul"
ThisBuild / homepage := Some(url("https://github.com/Dwolla/http4s-consul-middleware"))
ThisBuild / licenses += ("MIT", url("https://opensource.org/licenses/MIT"))
ThisBuild / scalaVersion := "2.13.8"
ThisBuild / scalacOptions += "-Ymacro-annotations"
ThisBuild / developers := List(
  Developer(
    "bpholt",
    "Brian Holt",
    "bholt+http4s-consul-middleware@dwolla.com",
    url("https://dwolla.com")
  ),
)
ThisBuild / startYear := Option(2022)
ThisBuild / libraryDependencies ++= Seq(
  compilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full),
  compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
)

lazy val `http4s-consul-middleware` = (project in file("."))
  .settings(
    resolvers += "Dwolla" at "https://artifacts.dwolla.net/repository/maven-public",
    libraryDependencies ++= {
      val http4sVersion = "0.23.9"
      val circeVersion = "0.14.1"
      val log4catsVersion = "2.2.0"
      val munitVersion = "0.7.29"
      val scalacheckEffectVersion = "1.0.3"
      val monocleVersion = "2.1.0"
      val refinedV = "0.9.28"

      Seq(
        "com.dwolla" %% "consul-client-core-http4s" % "6.9.4",
        "org.http4s" %% "http4s-ember-client" % http4sVersion,
        "io.circe" %% "circe-parser" % circeVersion,
        "io.circe" %% "circe-generic" % circeVersion,
        "io.circe" %% "circe-refined" % circeVersion,
        "io.circe" %% "circe-optics" % circeVersion,
        "io.estatico" %% "newtype" % "0.4.4",
        "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
        "org.scalameta" %% "munit" % munitVersion % Test,
        "org.scalameta" %% "munit-scalacheck" % munitVersion % Test,
        "io.circe" %% "circe-literal" % circeVersion % Test,
        "org.typelevel" %% "munit-cats-effect-3" % "1.0.7" % Test,
        "org.typelevel" %% "scalacheck-effect" % scalacheckEffectVersion % Test,
        "org.typelevel" %% "scalacheck-effect-munit" % scalacheckEffectVersion % Test,
        "org.typelevel" %% "log4cats-noop" % log4catsVersion % Test,
        "io.circe" %% "circe-testing" % circeVersion % Test,
        "com.github.julien-truffaut" %% "monocle-core" % monocleVersion % Test,
        "com.github.julien-truffaut" %% "monocle-macro" % monocleVersion % Test,
        "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
        "com.eed3si9n.expecty" %% "expecty" % "0.15.4" % Test,
        "eu.timepit" %% "refined-scalacheck" % refinedV % Test,
        "org.typelevel" %% "cats-laws" % "2.7.0" % Test,
        "org.typelevel" %% "discipline-munit" % "1.0.9" % Test,
      )
    },
  )
