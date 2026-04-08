# repcheck-sbt-plugins

sbt plugins used across RepCheck Scala repositories.

## `sbt-exception-uniqueness`

Enforces the RepCheck flat-unique-exception rule: no two `Throwable`
subclasses under the project's root package(s) may share a simple class
name.

### Usage

In `project/plugins.sbt`:
```scala
resolvers += "GitHub Packages" at
  "https://maven.pkg.github.com/Eligio-Taveras/repcheck-sbt-plugins"
credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  sys.env.getOrElse("GITHUB_USERNAME", ""),
  sys.env.getOrElse("GITHUB_TOKEN", "")
)
addSbtPlugin("com.repcheck" % "sbt-exception-uniqueness" % "0.1.0")
```

In `build.sbt`, enable on each project that should be checked:
```scala
lazy val myProject = project
  .enablePlugins(com.repcheck.sbt.ExceptionUniquenessPlugin)
  .settings(
    exceptionUniquenessRootPackages := Seq("com.repcheck")
  )
```

The check runs automatically on every `sbt test` and fails the build
on duplicates.
