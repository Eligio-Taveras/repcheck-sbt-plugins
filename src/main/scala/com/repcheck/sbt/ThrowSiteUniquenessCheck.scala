package com.repcheck.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.collection.mutable.ListBuffer
import scala.meta._
import scala.util.control.NonFatal

/**
 * Enforces the RepCheck "unique exception per failure point" rule at the throw-site level: each project-declared
 * `Throwable` subclass may be thrown from at most one `throw new ClassName(...)` site across the source tree.
 *
 * This is the stricter form of the declaration-name uniqueness check in [[ExceptionUniquenessCheck]]. Where that check
 * guarantees each exception class has a unique name, this check guarantees each exception class is also instantiated at
 * a unique failure point — so every `throw` in the codebase uniquely identifies the code path that produced it.
 *
 * Scope notes (v0.3.0):
 *   - `throw new ClassName(...)` sites count. `Term.Throw(Term.New(...))` in scalameta.
 *   - `throw ClassName(...)` (case-class apply form, no `new`) also counts. `Term.Throw(Term.Apply(...))` where the
 *     applied function reduces to a known project exception simple name. Qualified names (`pkg.ClassName(...)`), type
 *     arguments (`ClassName[T](...)`), and curried arg lists (`ClassName(x)(y)`) are all handled.
 *   - Re-raises (`throw e`, `throw caught`) are allowed and intentionally ignored — the thrown term must be a
 *     `Term.New` or a `Term.Apply` resolving to a known project exception for the site to count.
 *   - Only throw sites whose constructed type resolves to a project-declared Throwable simple name (from the
 *     allowlisted root packages) are counted. Non-project exceptions like `throw new IllegalArgumentException(...)` may
 *     appear freely.
 *   - Known gap: bare case-object throws (`throw FooException` with no args) are not counted. Factory method calls
 *     (`throw ExceptionFactory.build(...)`) are not counted. Orphan detection (declared-but-never-thrown) is out of
 *     scope.
 */
object ThrowSiteUniquenessCheck {

  /**
   * Run the throw-site uniqueness check.
   *
   * @param sourceDirectories
   *   every Scala source directory to scan — typically `(Compile / unmanagedSourceDirectories).value ++ (Test /
   *   unmanagedSourceDirectories).value`
   * @param projectExceptionSimpleNames
   *   simple names of project-declared Throwable subclasses produced by
   *   [[ExceptionUniquenessCheck.checkDeclarationUniqueness]]. Only throw sites constructing a type whose simple name
   *   is in this set are counted.
   * @param projectRootPackages
   *   the allowlisted dotted prefixes, used only for diagnostics in the failure message.
   */
  def run(
    sourceDirectories: Seq[File],
    projectExceptionSimpleNames: Set[String],
    projectRootPackages: Seq[String],
  ): Unit =
    if (projectExceptionSimpleNames.isEmpty) {
      println(
        "check-exception-uniqueness: no project exception classes declared; skipping throw-site scan."
      )
      ()
    } else {
      val scalaFiles = sourceDirectories.toList.flatMap(collectScalaFiles)
      if (scalaFiles.isEmpty) {
        println(
          "check-exception-uniqueness: no Scala source files found; skipping throw-site scan."
        )
        ()
      } else {
        // (simpleName, filePath, lineNumber)
        val throwSites = new ListBuffer[(String, String, Int)]()

        def record(simpleName: String, file: File, line: Int): Unit = {
          throwSites += ((simpleName, file.getAbsolutePath, line))
          ()
        }

        scalaFiles.foreach { file =>
          parseFile(file) match {
            case Some(tree) =>
              tree.traverse {
                case t @ Term.Throw(Term.New(init)) =>
                  extractSimpleName(init.tpe).foreach { simpleName =>
                    if (projectExceptionSimpleNames.contains(simpleName)) {
                      val line = t.pos.startLine + 1 // scalameta is 0-indexed
                      record(simpleName, file, line)
                    }
                  }
                case t @ Term.Throw(apply: Term.Apply) =>
                  extractAppliedSimpleName(apply.fun) match {
                    case Some(simpleName) if projectExceptionSimpleNames.contains(simpleName) =>
                      val line = t.pos.startLine + 1 // scalameta is 0-indexed
                      record(simpleName, file, line)
                    case _ => () // not a project exception; ignore (factory methods etc.)
                  }
              }
            case None => ()
          }
        }

        val grouped: Map[String, List[(String, Int)]] =
          throwSites.toList
            .groupBy(_._1)
            .map {
              case (simpleName, triples) =>
                simpleName -> triples.map { case (_, path, line) => (path, line) }.distinct.sortBy {
                  case (p, l) => (p, l)
                }
            }

        val duplicates: List[(String, List[(String, Int)])] =
          grouped.toList
            .filter { case (_, sites) => sites.size > 1 }
            .sortBy(_._1)

        if (duplicates.isEmpty) {
          println(
            s"check-exception-uniqueness: ${throwSites.size} project throw site(s) scanned, all unique. OK."
          )
          ()
        } else {
          val rootsDisplay = projectRootPackages.mkString(", ")
          val message      = new StringBuilder()
          message.append(
            "check-exception-uniqueness: FAIL — duplicate `throw new` sites found for project exception(s):\n\n"
          )
          duplicates.foreach {
            case (simpleName, sites) =>
              message.append(s"  $simpleName\n")
              sites.foreach { case (path, line) => message.append(s"    $path:$line\n") }
              message.append("\n")
          }
          message.append(
            s"RepCheck rule: each project Throwable under [$rootsDisplay] must be thrown from at most one site.\n"
          )
          message.append(
            "Rename or split the duplicates so each `throw new ...` uniquely identifies its failure point.\n"
          )
          message.append(
            "(Re-raises like `throw e` are not counted. Both `throw new Foo(...)` and `throw Foo(...)` apply forms count as sites.)"
          )
          sys.error(message.toString)
        }
      }
    }

  /** Recursively collect every `.scala` file under `root`. */
  private def collectScalaFiles(root: File): List[File] = {
    val acc = new ListBuffer[File]()
    def loop(dir: File): Unit =
      if (dir.exists() && dir.isDirectory) {
        val entries = dir.listFiles()
        if (entries != null) {
          entries.foreach { entry =>
            if (entry.isDirectory) {
              loop(entry)
            } else if (entry.isFile && entry.getName.endsWith(".scala")) {
              acc += entry
            }
          }
        }
      }
    loop(root)
    acc.toList
  }

  /**
   * Parse a single file with scalameta. On parse failure, log a warning and return `None` so the overall scan can
   * continue — an individual unparseable file should not fail the build from this check (the Scala compiler will catch
   * genuine syntax errors separately).
   */
  private def parseFile(file: File): Option[Tree] =
    try {
      val bytes   = Files.readAllBytes(file.toPath)
      val text    = new String(bytes, StandardCharsets.UTF_8)
      val input   = Input.VirtualFile(file.getAbsolutePath, text)
      val dialect = dialects.Scala213Source3
      dialect(input).parse[Source] match {
        case Parsed.Success(tree) => Some(tree)
        case Parsed.Error(_, msg, _) =>
          println(
            s"check-exception-uniqueness: WARN — could not parse ${file.getAbsolutePath}: $msg (skipping)"
          )
          None
      }
    } catch {
      case NonFatal(e) =>
        println(
          s"check-exception-uniqueness: WARN — error reading ${file.getAbsolutePath}: ${e.getMessage} (skipping)"
        )
        None
    }

  /**
   * Extract the simple type name from a scalameta `Type` node. For qualified names like `pkg.sub.Foo`, returns `Foo`.
   * Returns `None` for types we cannot trivially reduce to a simple name (applied types, function types, etc. — none of
   * which would be valid `throw new` targets anyway).
   */
  private def extractSimpleName(tpe: Type): Option[String] = tpe match {
    case Type.Name(value)      => Some(value)
    case Type.Select(_, name)  => Some(name.value)
    case Type.Project(_, name) => Some(name.value)
    case applied: Type.Apply   => extractSimpleName(applied.tpe)
    case _                     => None
  }

  /**
   * Reduce the function term of a `Term.Apply` to a simple name when possible. Handles bare names, qualified names via
   * `Term.Select`, type-argument applications via `Term.ApplyType`, and curried arg lists via nested `Term.Apply`.
   * Returns `None` for anything that cannot be reduced to a simple name (e.g. function-value invocations).
   */
  private def extractAppliedSimpleName(fun: Term): Option[String] = fun match {
    case Term.Name(name)                 => Some(name)
    case Term.Select(_, Term.Name(name)) => Some(name)
    case applyType: Term.ApplyType       => extractAppliedSimpleName(applyType.fun)
    case apply: Term.Apply               => extractAppliedSimpleName(apply.fun)
    case _                               => None
  }

}
