lazy val scalaVersions = Seq("2.13.8")

ThisBuild / organization := "com.dwolla"
ThisBuild / homepage := Some(url("https://github.com/Dwolla/http4s-consul-middleware"))
ThisBuild / licenses += ("MIT", url("https://opensource.org/licenses/MIT"))
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

lazy val log4catsVersion = "2.4.0"

lazy val `http4s-consul-middleware` = (projectMatrix in file("core"))
  .jvmPlatform(scalaVersions, settings = Seq(
    libraryDependencies ++= {
      Seq(
        "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.18.0" % Test,
        "org.typelevel" %% "log4cats-slf4j" % log4catsVersion % Test,
      )
    }
  ))
  .jsPlatform(scalaVersions)
  .settings(
    description := "http4s middleware to discover the host and port for an HTTP request using Consul",
    tpolecatScalacOptions += ScalacOptions.release("8"),
    libraryDependencies ++= {
      val http4sVersion = "0.23.15"
      val munitVersion = "0.7.29"

      Seq(
        "org.http4s" %%% "http4s-client" % http4sVersion,
        "org.http4s" %%% "http4s-circe" % http4sVersion,
        "io.circe" %%% "circe-optics" % "0.14.1",
        "io.circe" %%% "circe-literal" % "0.14.2",
        "io.monix" %%% "newtypes-core" % "0.2.3",
        "org.typelevel" %%% "log4cats-core" % log4catsVersion,
        "org.typelevel" %%% "keypool" % "0.4.7",
        "org.typelevel" %%% "case-insensitive" % "1.3.0",
        "org.typelevel" %%% "cats-effect" % "3.3.14",
        "org.typelevel" %%% "log4cats-noop" % log4catsVersion % Test,
        "org.http4s" %%% "http4s-ember-client" % http4sVersion % Test,
        "org.http4s" %%% "http4s-dsl" % http4sVersion % Test,
        "org.http4s" %%% "http4s-laws" % http4sVersion % Test,
        "org.scalameta" %%% "munit" % munitVersion % Test,
        "org.scalameta" %%% "munit-scalacheck" % munitVersion % Test,
      )
    },
  )

lazy val root = (project in file("."))
  .aggregate(
    Seq(
      `http4s-consul-middleware`,
    ).flatMap(_.projectRefs): _*
  )
  .settings(
    publishArtifact := false,
    publish / skip := true
  )
