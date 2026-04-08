lazy val root = (project in file("."))
  .enablePlugins(com.repcheck.sbt.ExceptionUniquenessPlugin)
  .settings(
    scalaVersion := "3.4.1",
    exceptionUniquenessRootPackages := Seq("com.example"),
  )
