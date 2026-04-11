package com.repcheck.sbt

import sbt.Keys._
import sbt._

object ExceptionUniquenessPlugin extends AutoPlugin {
  override def trigger  = noTrigger // opt-in via enablePlugins
  override def requires = plugins.JvmPlugin

  object autoImport {

    val exceptionUniquenessRootPackages =
      settingKey[Seq[String]](
        "Root packages (allowlist) to scan for Throwable subclasses. Required; empty fails fast."
      )

    val exceptionUniquenessIgnoreParseErrors =
      settingKey[Seq[String]](
        "Substring patterns identifying source files whose parse errors should be tolerated " +
          "(the file is skipped instead of failing the task). Empty by default — parse errors fail the build."
      )

    val checkExceptionUniqueness =
      taskKey[Unit](
        "Fail if two Throwable subclasses under the project's root packages share a simple name."
      )

  }

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    exceptionUniquenessRootPackages      := Seq.empty,
    exceptionUniquenessIgnoreParseErrors := Seq.empty,
    checkExceptionUniqueness := Def
      .task {
        ExceptionUniquenessCheck.run(
          (Compile / classDirectory).value,
          (Test / classDirectory).value,
          (Test / fullClasspath).value.files,
          exceptionUniquenessRootPackages.value,
          (Compile / unmanagedSourceDirectories).value ++ (Test / unmanagedSourceDirectories).value,
          (Compile / unmanagedSourceDirectories).value,
          exceptionUniquenessIgnoreParseErrors.value,
        )
      }
      .dependsOn(Compile / compile, Test / compile)
      .value,
    (Test / test) := ((Test / test) dependsOn checkExceptionUniqueness).value,
  )

}
