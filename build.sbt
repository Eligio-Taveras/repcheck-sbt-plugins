ThisBuild / organization := "com.repcheck"
ThisBuild / version      := "0.2.0"
ThisBuild / scalaVersion := "2.12.19" // sbt 1.x plugins MUST be Scala 2.12

ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name      := "sbt-exception-uniqueness",
    sbtPlugin := true,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "scalameta" % "4.9.9"
    ),
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog := false,
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xlint",
      "-Ywarn-unused",
    ),
    // Publishing to GitHub Packages
    publishTo := Some(
      "GitHub Packages" at "https://maven.pkg.github.com/Eligio-Taveras/repcheck-sbt-plugins"
    ),
    credentials += Credentials(
      "GitHub Package Registry",
      "maven.pkg.github.com",
      sys.env.getOrElse("GITHUB_USERNAME", "Eligio-Taveras"),
      sys.env.getOrElse("GITHUB_TOKEN", ""),
    ),
    publishMavenStyle := true,
  )
