import org.typelevel.sbt.gha.MatrixExclude
import org.typelevel.scalacoptions.ScalacOptions

ThisBuild / crossScalaVersions := Seq("3.3.6", "2.13.16", "2.12.20")
ThisBuild / scalaVersion := (ThisBuild / crossScalaVersions).value.head
ThisBuild / githubWorkflowBuildMatrixExclusions := Seq(
  MatrixExclude(Map(
    "scala" -> "2.12",
    "project" -> "rootJS",
  )),
)
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
tpolecatScalacOptions += ScalacOptions.release("8")
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
ThisBuild / tlCiReleaseBranches := Seq("main")
ThisBuild / tlBaseVersion := "0.3"
ThisBuild / mergifyStewardConfig ~= { _.map {
  _.withAuthor("dwolla-oss-scala-steward[bot]")
    .withMergeMinors(true)
}}
ThisBuild / mergifySuccessConditions += MergifyCondition.Custom("#approved-reviews-by>=1")

lazy val log4catsVersion = "2.7.1"
lazy val http4sVersion = "0.23.30"
lazy val munitVersion = "1.1.2"

lazy val root = tlCrossRootProject.aggregate(
  `http4s-consul-middleware`,
  `smithy4s-consul-middleware`,
  `smithy4s-consul-middleware-tests`,
  `consul-discoverable-smithy-spec`,
)

lazy val `http4s-consul-middleware` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(
    description := "http4s middleware to discover the host and port for an HTTP request using Consul",
    tpolecatScalacOptions += ScalacOptions.release("8"),
    tlVersionIntroduced := Map("3" -> "0.3.1", "2.12" -> "0.0.1", "2.13" -> "0.0.1"),
    scalacOptions ++= List("-Vimplicits").filter(_ => scalaVersion.value.startsWith("2.13")),
    libraryDependencies ++= {
      Seq(
        "org.http4s" %%% "http4s-client" % http4sVersion,
        "org.http4s" %%% "http4s-circe" % http4sVersion,
        "io.circe" %%% "circe-literal" % "0.14.14",
        "io.monix" %%% "newtypes-core" % "0.2.3",
        "org.typelevel" %%% "log4cats-core" % log4catsVersion,
        "org.typelevel" %%% "keypool" % "0.4.10",
        "org.typelevel" %%% "case-insensitive" % "1.5.0",
        "org.typelevel" %%% "cats-effect" % "3.6.3",
        "org.typelevel" %%% "cats-mtl" % "1.6.0",
        "org.tpolecat" %%% "natchez-core" % "0.3.8",
        "org.tpolecat" %%% "natchez-mtl" % "0.3.8",
        "org.tpolecat" %%% "natchez-noop" % "0.3.8",
        "org.typelevel" %%% "log4cats-noop" % log4catsVersion % Test,
        "org.http4s" %%% "http4s-ember-client" % http4sVersion % Test,
        "org.http4s" %%% "http4s-dsl" % http4sVersion % Test,
        "org.http4s" %%% "http4s-laws" % http4sVersion % Test,
        "org.scalameta" %%% "munit" % munitVersion % Test,
        "org.scalameta" %%% "munit-scalacheck" % "1.1.0" % Test,
        "com.comcast" %%% "ip4s-test-kit" % "3.7.0" % Test,
      ) ++ (if (scalaVersion.value.startsWith("2.")) Seq(
        compilerPlugin("org.typelevel" %% "kind-projector" % "0.13.3" cross CrossVersion.full),
        compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
      )
      else Seq.empty)
    },
  )
  .jvmSettings(
    libraryDependencies ++= Seq(
      "org.tpolecat" %%% "natchez-jaeger" % "0.3.8" % Test,
    )
  )
  .jsSettings(
    libraryDependencies ++= Seq(
      "org.tpolecat" %%% "natchez-noop" % "0.3.8" % Test,
      "io.github.cquiroz" %%% "scala-java-time" % "2.6.0" % Test,
      "io.github.cquiroz" %%% "scala-java-time-tzdb" % "2.6.0" % Test,
    )
  )

lazy val `consul-discoverable-smithy-spec` = project
  .in(file("consul-discoverable-smithy-spec"))
  .settings(
    autoScalaLibrary := false,
    crossPaths := false,
    Compile / packageSrc / mappings := (Compile / packageSrc / mappings).value
      .filterNot { case (file, path) =>
        path.equalsIgnoreCase("META-INF/smithy/manifest")
      },
    resolvers += Resolver.mavenLocal,
    libraryDependencies += "software.amazon.smithy" % "smithy-model" % "1.61.0",
    tlVersionIntroduced := Map("3" -> "0.3.4", "2.12" -> "0.3.4", "2.13" -> "0.3.4"),
  )

lazy val `smithy4s-consul-middleware` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("smithy4s"))
  .configure(_.dependsOn(`consul-discoverable-smithy-spec`))
  .settings(
    description := "smithy4s middleware to rewrite URLs back to the consul://{service} format expected by http4s-consul-middleware",
    tpolecatScalacOptions += ScalacOptions.release("8"),
    tlVersionIntroduced := Map("3" -> "0.3.2", "2.12" -> "0.3.2", "2.13" -> "0.3.2"),
    libraryDependencies ++= Seq(
      "org.http4s" %%% "http4s-client" % http4sVersion,
      "com.disneystreaming.smithy4s" %%% "smithy4s-core" % smithy4sVersion.value,
      "org.typelevel" %% "scalac-compat-annotation" % "0.1.4",
    ),
  )
  .jsSettings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    crossScalaVersions ~= (_.filterNot(_.startsWith("2.12"))),
  )
  .dependsOn(`http4s-consul-middleware`)
  .enablePlugins(Smithy4sCodegenPlugin)

// put the smithy4s-consul-middleware tests in a different project
// because it has smithy files that we don't want to publish
lazy val `smithy4s-consul-middleware-tests` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("smithy4s-tests"))
  .settings(
    libraryDependencies ++= {
      Seq(
        "com.disneystreaming.smithy4s" %%% "smithy4s-http4s" % smithy4sVersion.value % Test,
        "org.scalameta" %%% "munit" % munitVersion % Test,
        "org.typelevel" %%% "munit-cats-effect" % "2.1.0" % Test,
        "org.typelevel" %%% "scalacheck-effect-munit" % "2.0.0-M2" % Test,
        "org.http4s" %%% "http4s-dsl" % http4sVersion % Test,
        "com.comcast" %%% "ip4s-test-kit" % "3.7.0" % Test,
      )
    },
    libraryDependencies ++= {
      (scalaBinaryVersion.value) match {
        case "2.12" | "2.13" =>
          Seq("org.scala-lang" % "scala-compiler" % scalaVersion.value % Test)
        case _ =>
          Nil
      }
    },
    Compile / smithy4sInputDirs := List(
      baseDirectory.value.getParentFile / "src" / "main" / "smithy",
    ),
  )
  .jsSettings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    crossScalaVersions ~= (_.filterNot(_.startsWith("2.12"))),
  )
  .dependsOn(`smithy4s-consul-middleware`)
  .enablePlugins(Smithy4sCodegenPlugin, NoPublishPlugin)
