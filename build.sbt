ThisBuild / crossScalaVersions := Seq("2.13.8", "2.12.16")
ThisBuild / scalaVersion := crossScalaVersions.value.head
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
tpolecatScalacOptions += ScalacOptions.release("8")
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
ThisBuild / tlCiReleaseBranches := Seq("main")
ThisBuild / tlBaseVersion := "0.1"
ThisBuild / tlSonatypeUseLegacyHost := true
ThisBuild / mergifyStewardConfig ~= {
  _.map(_.copy(mergeMinors = true, author = "dwolla-oss-scala-steward[bot]"))
}
ThisBuild / mergifySuccessConditions += MergifyCondition.Custom("#approved-reviews-by>=1")
ThisBuild / mergifyPrRules += MergifyPrRule(
  "assign scala-steward's PRs for review",
  List(MergifyCondition.Or(List(
    MergifyCondition.Custom("author=dwolla-oss-scala-steward[bot]"),
    MergifyCondition.Custom("author=scala-steward"),
  ))),
  List(
    MergifyAction.RequestReviews(developers.value)
  )
)

lazy val log4catsVersion = "2.4.0"

lazy val `http4s-consul-middleware` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
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
