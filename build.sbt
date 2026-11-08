import org.typelevel.sbt.gha.MatrixExclude

ThisBuild / crossScalaVersions := Seq("3.3.8", "2.13.18", "2.12.21")
ThisBuild / scalaVersion := (ThisBuild / crossScalaVersions).value.head
ThisBuild / githubWorkflowBuildMatrixExclusions := Seq(
  MatrixExclude(Map(
    "scala" -> "2.12",
    "project" -> "rootJS",
  )),
  // smithy4s publishes Scala Native artifacts for Scala 3 only, and sbt's `++ 2.x` leaves those
  // Scala 3-only projects in rootNative, so they fail to resolve against the switched core project.
  // The core Native project gets its own 2.13 and 2.12 cells instead (see the matrix additions below).
  MatrixExclude(Map(
    "scala" -> "2.13",
    "project" -> "rootNative",
  )),
  MatrixExclude(Map(
    "scala" -> "2.12",
    "project" -> "rootNative",
  )),
  // rootNative already covers the core Native project on Scala 3
  MatrixExclude(Map(
    "scala" -> "3",
    "project" -> "http4s-consul-middlewareNative",
  )),
)
ThisBuild / githubWorkflowBuildMatrixAdditions ~= { matrix =>
  matrix.updated("project", matrix.getOrElse("project", Nil) :+ "http4s-consul-middlewareNative")
}
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
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
// Scala Native warns when it links with clang older than 16. The ubuntu-22.04 runner image tops out at
// clang 15, and every clang on the ubuntu-24.04 image is 16 or newer.
ThisBuild / githubWorkflowOSes := Seq("ubuntu-24.04")
ThisBuild / tlCiReleaseBranches := Seq("main")
ThisBuild / tlBaseVersion := "0.3"
ThisBuild / mergifyStewardConfig ~= { _.map {
  _.withAuthor("dwolla-oss-scala-steward[bot]")
    .withMergeMinors(true)
}}
ThisBuild / mergifySuccessConditions += MergifyCondition.Custom("#approved-reviews-by>=1")

lazy val log4catsVersion = "2.8.0"
lazy val http4sVersion = "0.23.38"
lazy val munitVersion = "1.3.6"

// Scala Native adds its compiler plugin with -Xplugin, which Scala 3's scaladoc ignores but warns about.
// Scala 2's scaladoc typechecks with the compiler plugins (e.g. kind-projector), so they stay there.
lazy val dropCompilerPluginsFromScala3Doc: Setting[Task[Seq[String]]] =
  Compile / doc / scalacOptions := {
    val options = (Compile / doc / scalacOptions).value
    if (scalaBinaryVersion.value == "3") options.filterNot(_.startsWith("-Xplugin")) else options
  }

lazy val root = tlCrossRootProject.aggregate(
  `http4s-consul-middleware`,
  `smithy4s-consul-middleware`,
  `smithy4s-consul-middleware-tests`,
  `consul-discoverable-smithy-spec`,
)

lazy val `http4s-consul-middleware` = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(
    description := "http4s middleware to discover the host and port for an HTTP request using Consul",
    tlVersionIntroduced := Map("3" -> "0.3.1", "2.12" -> "0.0.1", "2.13" -> "0.0.1"),
    scalacOptions ++= SuppressKindPolymorphicInferAnyOn213(scalaVersion.value),
    libraryDependencies ++= {
      Seq(
        "org.http4s" %%% "http4s-client" % http4sVersion,
        "org.http4s" %%% "http4s-circe" % http4sVersion,
        "io.circe" %%% "circe-literal" % "0.14.17",
        "org.typelevel" %%% "log4cats-core" % log4catsVersion,
        "org.typelevel" %%% "keypool" % "0.4.11",
        "org.typelevel" %%% "case-insensitive" % "1.5.0",
        "org.typelevel" %%% "cats-effect" % "3.7.1",
        "org.typelevel" %%% "cats-mtl" % "1.7.0",
        "org.tpolecat" %%% "natchez-core" % "0.3.10",
        "org.tpolecat" %%% "natchez-mtl" % "0.3.10",
        "org.tpolecat" %%% "natchez-noop" % "0.3.10",
        "org.typelevel" %%% "log4cats-noop" % log4catsVersion % Test,
        "org.typelevel" %%% "log4cats-testing" % log4catsVersion % Test,
        "org.http4s" %%% "http4s-ember-client" % http4sVersion % Test,
        "org.http4s" %%% "http4s-dsl" % http4sVersion % Test,
        "org.http4s" %%% "http4s-laws" % http4sVersion % Test,
        "org.scalameta" %%% "munit" % munitVersion % Test,
        "org.scalameta" %%% "munit-scalacheck" % "1.3.1" % Test,
        "com.comcast" %%% "ip4s-test-kit" % "3.8.0" % Test,
      ) ++ (if (scalaVersion.value.startsWith("2.")) Seq(
        compilerPlugin("org.typelevel" %% "kind-projector" % "0.13.4" cross CrossVersion.full),
        compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
      )
      else Seq.empty)
    },
  )
  // newtypes-core is early-semver, so moving the JVM and JS artifacts past 0.2.x would cause
  // eviction errors for downstream builds that still use 0.2.x. Its first Scala Native release
  // is 0.3.0, and the native artifacts are new, so they can start on the current version.
  .platformsSettings(JVMPlatform, JSPlatform)(
    libraryDependencies += "io.monix" %%% "newtypes-core" % "0.2.3",
  )
  .nativeSettings(
    libraryDependencies += "io.monix" %%% "newtypes-core" % "1.0.0",
    tlVersionIntroduced := Map("3" -> "0.3.8", "2.12" -> "0.3.8", "2.13" -> "0.3.8"),
    dropCompilerPluginsFromScala3Doc,
  )
  .jvmSettings(
    libraryDependencies ++= Seq(
      "org.tpolecat" %%% "natchez-jaeger" % "0.3.10" % Test,
    )
  )
  .jsSettings(
    libraryDependencies ++= Seq(
      "org.tpolecat" %%% "natchez-noop" % "0.3.10" % Test,
      "io.github.cquiroz" %%% "scala-java-time" % "2.7.0" % Test,
      "io.github.cquiroz" %%% "scala-java-time-tzdb" % "2.7.0" % Test,
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
    libraryDependencies += "software.amazon.smithy" % "smithy-model" % "1.74.0",
    tlVersionIntroduced := Map("3" -> "0.3.4", "2.12" -> "0.3.4", "2.13" -> "0.3.4"),
  )

lazy val `smithy4s-consul-middleware` = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("smithy4s"))
  .configure(_.dependsOn(`consul-discoverable-smithy-spec`))
  .settings(
    description := "smithy4s middleware to rewrite URLs back to the consul://{service} format expected by http4s-consul-middleware",
    tlVersionIntroduced := Map("3" -> "0.3.2", "2.12" -> "0.3.2", "2.13" -> "0.3.2"),
    libraryDependencies ++= Seq(
      "org.http4s" %%% "http4s-client" % http4sVersion,
      "com.disneystreaming.smithy4s" %%% "smithy4s-core" % smithy4sVersion.value,
      "org.typelevel" %% "scalac-compat-annotation" % "0.1.5",
    ),
  )
  .jsSettings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    crossScalaVersions := (ThisBuild / crossScalaVersions).value.filterNot(_.startsWith("2.12")),
    scalaVersion := crossScalaVersions.value.head,
  )
  .nativeSettings(
    // smithy4s publishes Scala Native artifacts for Scala 3 only
    crossScalaVersions := (ThisBuild / crossScalaVersions).value.filter(_.startsWith("3.")),
    scalaVersion := crossScalaVersions.value.head,
    tlVersionIntroduced := Map("3" -> "0.3.8"),
    dropCompilerPluginsFromScala3Doc,
  )
  .dependsOn(`http4s-consul-middleware`)
  .enablePlugins(Smithy4sCodegenPlugin)

// put the smithy4s-consul-middleware tests in a different project
// because it has smithy files that we don't want to publish
lazy val `smithy4s-consul-middleware-tests` = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("smithy4s-tests"))
  .settings(
    libraryDependencies ++= {
      Seq(
        "com.disneystreaming.smithy4s" %%% "smithy4s-http4s" % smithy4sVersion.value % Test,
        "org.scalameta" %%% "munit" % munitVersion % Test,
        "org.typelevel" %%% "munit-cats-effect" % "2.2.1" % Test,
        "org.typelevel" %%% "scalacheck-effect-munit" % "2.1.0" % Test,
        "org.http4s" %%% "http4s-dsl" % http4sVersion % Test,
        "com.comcast" %%% "ip4s-test-kit" % "3.8.0" % Test,
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
    scalacOptions ++= SuppressKindPolymorphicInferAnyOn213(scalaVersion.value),
    Compile / smithy4sInputDirs := List(
      baseDirectory.value.getParentFile / "src" / "main" / "smithy",
    ),
  )
  .jsSettings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    crossScalaVersions := (ThisBuild / crossScalaVersions).value.filterNot(_.startsWith("2.12")),
    scalaVersion := crossScalaVersions.value.head,
  )
  .nativeSettings(
    // smithy4s publishes Scala Native artifacts for Scala 3 only
    crossScalaVersions := (ThisBuild / crossScalaVersions).value.filter(_.startsWith("3.")),
    scalaVersion := crossScalaVersions.value.head,
    dropCompilerPluginsFromScala3Doc,
  )
  .dependsOn(`smithy4s-consul-middleware`)
  .enablePlugins(Smithy4sCodegenPlugin, NoPublishPlugin)
