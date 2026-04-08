# repcheck-sbt-plugins

sbt plugins used across RepCheck Scala repositories.

## `sbt-exception-uniqueness`

Enforces RepCheck's "flat, unique exception per failure point" rule at two
levels:

1. **Declaration uniqueness** — no two `Throwable` subclasses under the
   project's root package(s) may share a simple class name. Implemented via
   classloading of compiled bytecode, so it is immune to Scala syntax
   variations.
2. **Throw-site uniqueness** (added in v0.3.0) — each project-declared
   `Throwable` subclass may be thrown from at most one site across the
   source tree. Both `throw new ClassName(...)` and the case-class apply
   form `throw ClassName(...)` (without `new`) count as sites, and are
   unified under a single per-class limit. Implemented via scalameta
   source parsing.

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
addSbtPlugin("com.repcheck" % "sbt-exception-uniqueness" % "0.3.0")
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

### Throw-site check — scope and known gaps (v0.3.0)

The throw-site scan is deliberately conservative:

- **Counted sites:** `throw new ClassName(...)`, `throw ClassName(...)`
  (case-class apply form), `throw pkg.ClassName(...)` (qualified),
  `throw ClassName[T](...)` (type arguments), and curried forms like
  `throw ClassName(x)(y)`. Internally, the scanner matches
  `Term.Throw(Term.New(...))` and `Term.Throw(Term.Apply(...))` in the
  scalameta AST, where the applied function reduces to a project exception
  simple name. Both forms are unified — a class thrown once via `new` and
  once via the apply form fails the check.
- **Re-raises are allowed.** Catching an exception and re-throwing it via
  `throw e` does not count as a site — the thrown term is a bare
  `Term.Name`, not a `Term.New` or `Term.Apply`.
- **Only project exceptions are checked.** A site is counted only when
  the constructed or applied type's simple name matches a `Throwable`
  subclass declared under one of `exceptionUniquenessRootPackages`.
  Non-project exceptions (e.g. `throw new IllegalArgumentException(...)`)
  may appear many times and are intentionally ignored.
- **Known gap: bare case-object throws are NOT counted.** A throw written
  as `throw FooException` with no arg list (e.g. for a case-object
  exception) is invisible to the scan, because the thrown term is a bare
  `Term.Name` and is treated as a re-raise. If your codebase relies on
  case-object exceptions, case-object support can be added in a later
  release.
- **Known gap: factory method calls are NOT counted.** A throw written as
  `throw ExceptionFactory.build(...)` is ignored because the applied
  function's rightmost name does not match a project exception simple
  name.
- **Orphan detection is NOT included.** The scan does not flag declared
  exceptions that are never thrown.

### Dependencies

- scalameta 4.9.9 (source parsing for the throw-site scan, Scala 2.12
  compatible)
