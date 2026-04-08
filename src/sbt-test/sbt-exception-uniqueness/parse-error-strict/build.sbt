lazy val root = (project in file("."))
  .enablePlugins(com.repcheck.sbt.ExceptionUniquenessPlugin)
  .settings(
    scalaVersion                      := "3.4.1",
    exceptionUniquenessRootPackages   := Seq("com.example"),
    // Exclude Broken.scala from compilation so scalac doesn't fail — the
    // scanner still picks it up from the source directory and must fail
    // on the parse error (v0.4.0 strict behavior).
    Compile / unmanagedSources / excludeFilter := {
      (Compile / unmanagedSources / excludeFilter).value || new sbt.SimpleFileFilter(f =>
        f.getName == "Broken.scala"
      )
    },
  )
