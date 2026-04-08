# Changelog

## 0.2.0

- Added throw-site uniqueness check: each project `Throwable` may be thrown
  from at most one `throw new ClassName(...)` site across the source tree.
- Re-raises (`throw e`, `throw caught`) are allowed and intentionally
  ignored — only `Term.Throw(Term.New(...))` AST nodes count.
- Only throw sites whose constructed type's simple name matches a
  project-declared Throwable (under `exceptionUniquenessRootPackages`) are
  counted; non-project exceptions may appear freely.
- Case-class apply form `throw FooException(...)` without `new` is NOT
  checked in v0.2.0 (known gap; can be added later).
- Orphan detection (declared-but-never-thrown exceptions) is NOT in scope.
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
