# repcheck-sbt-plugins

sbt plugins used across RepCheck Scala repositories.

## `sbt-exception-uniqueness`

Enforces RepCheck's "flat, unique exception per failure point" rule at two
levels:

1. **Declaration uniqueness** — no two `Throwable` subclasses under the
   project's root package(s) may share a simple class name. Implemented via
   classloading of compiled bytecode, so it is immune to Scala syntax
   variations.
2. **Throw-site uniqueness** (added in v0.2.0) — each project-declared
   `Throwable` subclass may be thrown from at most one `throw new ClassName(...)`
   site across the source tree. Implemented via scalameta source parsing.

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
addSbtPlugin("com.repcheck" % "sbt-exception-uniqueness" % "0.2.0")
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
on duplicate declarations or duplicate throw sites.

### Throw-site check — scope and known gaps (v0.2.0)

The throw-site scan is deliberately conservative:

- **Only `throw new ClassName(...)` sites count.** Internally, the scanner
  matches `Term.Throw(Term.New(...))` in the scalameta AST, so any throw of
  an already-constructed value (`throw e`, `throw caught`) is ignored.
- **Re-raises are allowed.** Catching an exception and re-throwing it via
  `throw e` does not count as an additional site.
- **Only project exceptions are checked.** A throw site is counted only
  when the constructed type's simple name matches a `Throwable` subclass
  declared under one of `exceptionUniquenessRootPackages`. Non-project
  exceptions (e.g. `throw new IllegalArgumentException(...)`) may appear
  many times and are intentionally ignored.
- **Known gap: case-class apply form is NOT checked.** A throw written as
  `throw FooException("msg")` without the `new` keyword is invisible to
  the v0.2.0 scan. If your codebase uses this form, the check can be
  extended in a later release.
- **Orphan detection is NOT included.** v0.2.0 does not flag declared
  exceptions that are never thrown.

### Dependencies

- scalameta 4.9.9 (source parsing for the throw-site scan, Scala 2.12
  compatible)
