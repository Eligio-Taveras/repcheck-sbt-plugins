lazy val root = (project in file("."))
  .enablePlugins(com.repcheck.sbt.ExceptionUniquenessPlugin)
  .settings(
    scalaVersion := "2.13.14",
    exceptionUniquenessRootPackages := Seq("com.example", "com.other"),
  )
