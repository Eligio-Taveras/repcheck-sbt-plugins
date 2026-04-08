lazy val root = (project in file("."))
  .enablePlugins(com.repcheck.sbt.ExceptionUniquenessPlugin)
  .settings(
    scalaVersion                         := "3.4.1",
    exceptionUniquenessRootPackages      := Seq("com.example"),
    exceptionUniquenessIgnoreParseErrors := Seq("broken/Broken.scala"),
    // Exclude Broken.scala from compilation so scalac doesn't fail — the
    // scanner still picks it up, observes the parse error, and tolerates
    // it because its path matches the ignore allowlist.
    Compile / unmanagedSources / excludeFilter := {
      (Compile / unmanagedSources / excludeFilter).value || new sbt.SimpleFileFilter(f =>
        f.getName == "Broken.scala"
      )
    },
  )
