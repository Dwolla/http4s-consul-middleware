ThisBuild / crossScalaVersions := Seq("2.13.10", "2.12.17")
ThisBuild / scalaVersion := crossScalaVersions.value.head
ThisBuild / githubWorkflowScalaVersions := Seq("2.13", "2.12")
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
ThisBuild / libraryDependencies ++= {
  if (scalaVersion.value.startsWith("2.")) Seq(
    compilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full),
    compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
  )
  else Seq.empty
}
tpolecatScalacOptions += ScalacOptions.release("8")
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
ThisBuild / tlCiReleaseBranches := Seq("main")
ThisBuild / tlBaseVersion := "0.2"
ThisBuild / tlSonatypeUseLegacyHost := true
ThisBuild / mergifyStewardConfig ~= {
  _.map(_.copy(mergeMinors = true, author = "dwolla-oss-scala-steward[bot]"))
}
ThisBuild / mergifySuccessConditions += MergifyCondition.Custom("#approved-reviews-by>=1")

lazy val log4catsVersion = "2.5.0"

lazy val root = tlCrossRootProject.aggregate(`http4s-consul-middleware`)

lazy val `http4s-consul-middleware` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(
    description := "http4s middleware to discover the host and port for an HTTP request using Consul",
    tpolecatScalacOptions += ScalacOptions.release("8"),
    tlMimaPreviousVersions ++= {
      if (scalaVersion.value.startsWith("2.")) Set("0.1.0")
      else Set.empty
    },
    libraryDependencies ++= {
      val http4sVersion = "0.23.17"
      val munitVersion = "0.7.29"

      Seq(
        "org.http4s" %%% "http4s-client" % http4sVersion,
        "org.http4s" %%% "http4s-circe" % http4sVersion,
        "io.circe" %%% "circe-optics" % "0.14.1",
        "io.circe" %%% "circe-literal" % "0.14.3",
        "io.monix" %%% "newtypes-core" % "0.2.3",
        "org.typelevel" %%% "log4cats-core" % log4catsVersion,
        "org.typelevel" %%% "keypool" % "0.4.8",
        "org.typelevel" %%% "case-insensitive" % "1.3.0",
        "org.typelevel" %%% "cats-effect" % "3.4.4",
        "org.tpolecat" %%% "natchez-core" % "0.3.1",
        "org.tpolecat" %%% "natchez-http4s" % "0.4.0",
        "org.typelevel" %%% "log4cats-noop" % log4catsVersion % Test,
        "org.http4s" %%% "http4s-ember-client" % http4sVersion % Test,
        "org.http4s" %%% "http4s-dsl" % http4sVersion % Test,
        "org.http4s" %%% "http4s-laws" % http4sVersion % Test,
        "org.scalameta" %%% "munit" % munitVersion % Test,
        "org.scalameta" %%% "munit-scalacheck" % munitVersion % Test,
      )
    },
  )
  .jvmSettings(
    libraryDependencies ++= Seq(
      "org.tpolecat" %%% "natchez-jaeger" % "0.3.1" % Test,
    )
  )
  .jsSettings(
    libraryDependencies ++= Seq(
      "org.tpolecat" %%% "natchez-noop" % "0.3.1" % Test,
      "io.github.cquiroz" %%% "scala-java-time" % "2.5.0" % Test,
      "io.github.cquiroz" %%% "scala-java-time-tzdb" % "2.5.0" % Test,
    )
  )
