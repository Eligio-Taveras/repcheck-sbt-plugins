# Changelog

## 0.3.0

- Added throw-site uniqueness check: each project `Throwable` may be thrown
  from at most one site across the source tree. Both `throw new ClassName(...)`
  and the case-class apply form `throw ClassName(...)` (without `new`) count
  as sites, and are unified — so a mix of the two forms for the same exception
  fails the check.
- Apply form handles qualified names (`pkg.ClassName(...)`), type arguments
  (`ClassName[T](...)`), and curried arg lists (`ClassName(x)(y)`).
- Re-raises (`throw e`, `throw caught`) are allowed and intentionally
  ignored — only `Term.Throw(Term.New(...))` and
  `Term.Throw(Term.Apply(...))` (where the applied function reduces to a
  known project exception simple name) count.
- Only throw sites whose constructed type's simple name matches a
  project-declared Throwable (under `exceptionUniquenessRootPackages`) are
  counted; non-project exceptions may appear freely.
- Known gaps: bare case-object throws (`throw FooException` with no args)
  are NOT counted. Factory method calls
  (`throw ExceptionFactory.build(...)`) are NOT counted. Orphan detection
  (declared-but-never-thrown exceptions) is NOT in scope.
- Added scalameta 4.9.9 as a plugin dependency for source parsing.
- `ExceptionUniquenessCheck.run` now also takes `sourceDirectories` and
  runs both passes sequentially. A new
  `ExceptionUniquenessCheck.checkDeclarationUniqueness` method exposes the
  declaration pass on its own and returns the set of project Throwable
  simple names used by the throw-site scan.

## 0.1.0

- Initial release: declaration-name uniqueness check. No two `Throwable`
  subclasses under the project's root packages may share a simple class
  name. Implemented via classloading-based bytecode scan.
