ThisBuild / organization := "com.dwolla"
ThisBuild / description := "http4s middleware to discover the host and port for an HTTP request using Consul"
ThisBuild / homepage := Some(url("https://github.com/Dwolla/http4s-consul-middleware"))
ThisBuild / licenses += ("MIT", url("https://opensource.org/licenses/MIT"))
ThisBuild / crossScalaVersions := Seq("2.13.8")
ThisBuild / scalaVersion := crossScalaVersions.value.head
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

ThisBuild / githubWorkflowBuild := Seq(WorkflowStep.Sbt(List("test", "doc")))
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("8"), JavaSpec.temurin("11"))
ThisBuild / githubWorkflowTargetTags ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches :=
  Seq(RefPredicate.StartsWith(Ref.Tag("v")))
ThisBuild / githubWorkflowPublish := Seq(
  WorkflowStep.Sbt(
    List("ci-release"),
    env = Map(
      "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
      "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}",
      "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
      "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
    )
  )
)

lazy val `http4s-consul-middleware` = (project in file("."))
  .settings(
    libraryDependencies ++= {
      val http4sVersion = "0.23.15"
      val circeVersion = "0.14.2"
      val log4catsVersion = "2.4.0"
      val munitVersion = "0.7.29"
      val catsVersion = "2.8.0"
      val catsEffectVersion = "3.3.14"

      Seq(
        "org.http4s" %% "http4s-core" % http4sVersion,
        "org.http4s" %% "http4s-client" % http4sVersion,
        "org.http4s" %% "http4s-circe" % http4sVersion,
        "io.circe" %% "circe-optics" % "0.14.1",
        "io.circe" %% "circe-literal" % circeVersion,
        "io.monix" %% "newtypes-core" % "0.2.3",
        "org.typelevel" %% "log4cats-core" % log4catsVersion,
        "org.typelevel" %% "keypool" % "0.4.7",
        "co.fs2" %% "fs2-core" % "3.2.12",
        "com.comcast" %% "ip4s-core" % "3.1.3",
        "com.github.julien-truffaut" %% "monocle-core" % "2.1.0",
        "io.circe" %% "circe-core" % "0.14.2",
        "org.typelevel" %% "case-insensitive" % "1.2.0",
        "org.typelevel" %% "cats-core" % catsVersion,
        "org.typelevel" %% "cats-kernel" % catsVersion,
        "org.typelevel" %% "cats-effect" % catsEffectVersion,
        "org.typelevel" %% "cats-effect-kernel" % catsEffectVersion,
        "org.typelevel" %% "cats-effect-std" % catsEffectVersion,
        "org.typelevel" %% "vault" % "3.2.1",
        "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.18.0" % Test,
        "org.typelevel" %% "log4cats-slf4j" % log4catsVersion % Test,
        "org.typelevel" %% "log4cats-noop" % log4catsVersion % Test,
        "org.http4s" %% "http4s-ember-client" % http4sVersion % Test,
        "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
        "org.http4s" %% "http4s-laws" % http4sVersion % Test,
        "org.scalameta" %% "munit" % munitVersion % Test,
        "org.scalameta" %% "munit-scalacheck" % munitVersion % Test,
        "org.typelevel" %% "cats-laws" % catsVersion % Test,
        "org.typelevel" %% "discipline-munit" % "1.0.9" % Test,
      )
    },
  )
