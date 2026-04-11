package com.repcheck.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.collection.mutable.ListBuffer
import scala.meta._
import scala.util.control.NonFatal

/**
 * Enforces the RepCheck "only project exceptions" rule: all exceptions constructed and raised in production code must be
 * custom project-declared Throwable subclasses, never standard Java/Scala exceptions like `RuntimeException`,
 * `IOException`, `IllegalArgumentException`, etc.
 *
 * This check scans ONLY production (non-test) source directories. Test code is excluded because tests legitimately
 * construct standard exceptions to simulate external failures in mocks.
 *
 * Detected patterns:
 *   - `throw new ClassName(...)` — `Term.Throw(Term.New(...))`
 *   - `throw ClassName(...)` — `Term.Throw(Term.Apply(...))` (case-class apply)
 *   - `*.raiseError(new ClassName(...))` — `Term.Apply` with `.raiseError` and `Term.New` argument
 *   - `*.raiseError(ClassName(...))` — `Term.Apply` with `.raiseError` and `Term.Apply` argument
 *   - `*.raiseError[T](new ClassName(...))` — with type arguments
 *
 * Intentionally ignored:
 *   - Re-raises: `throw e`, `raiseError(err)` — variable references are not new constructions
 *   - `raiseError` calls whose argument is not a constructor (e.g. variable, method call returning Throwable)
 */
object ProjectExceptionsOnlyCheck {

  /**
   * Run the project-exceptions-only check against production source directories.
   *
   * @param productionSourceDirectories
   *   only production (Compile) source directories — NOT test directories
   * @param projectExceptionSimpleNames
   *   simple names of project-declared Throwable subclasses produced by
   *   [[ExceptionUniquenessCheck.checkDeclarationUniqueness]]
   * @param projectRootPackages
   *   the allowlisted dotted prefixes, used only for diagnostics
   * @param ignoreParseErrors
   *   substring patterns for files whose parse errors are tolerated
   */
  def run(
    productionSourceDirectories: Seq[File],
    projectExceptionSimpleNames: Set[String],
    projectRootPackages: Seq[String],
    ignoreParseErrors: Seq[String] = Seq.empty,
  ): Unit =
    if (projectExceptionSimpleNames.isEmpty) {
      println(
        "check-exception-uniqueness: no project exception classes declared; skipping project-exceptions-only scan."
      )
      ()
    } else {
      val scalaFiles = productionSourceDirectories.toList.flatMap(collectScalaFiles)
      if (scalaFiles.isEmpty) {
        println(
          "check-exception-uniqueness: no production Scala source files found; skipping project-exceptions-only scan."
        )
        ()
      } else {
        // (simpleName, filePath, lineNumber)
        val violations    = new ListBuffer[(String, String, Int)]()
        val parseFailures = new ListBuffer[(File, String)]()

        def recordViolation(simpleName: String, file: File, line: Int): Unit = {
          violations += ((simpleName, file.getAbsolutePath, line))
          ()
        }

        scalaFiles.foreach { file =>
          parseFile(file) match {
            case Right(tree) =>
              tree.traverse {
                // throw new ClassName(...)
                case t @ Term.Throw(Term.New(init)) =>
                  extractSimpleName(init.tpe).foreach { simpleName =>
                    if (!projectExceptionSimpleNames.contains(simpleName)) {
                      recordViolation(simpleName, file, t.pos.startLine + 1)
                    }
                  }
                // throw ClassName(...)
                case t @ Term.Throw(apply: Term.Apply) =>
                  extractAppliedSimpleName(apply.fun) match {
                    case Some(simpleName) if !projectExceptionSimpleNames.contains(simpleName) =>
                      recordViolation(simpleName, file, t.pos.startLine + 1)
                    case _ => ()
                  }
                // *.raiseError(new ClassName(...)) or *.raiseError[T](new ClassName(...))
                // *.raiseError(ClassName(...)) or *.raiseError[T](ClassName(...))
                case t @ (apply: Term.Apply) if isRaiseError(apply.fun) =>
                  apply.argClause.values match {
                    case List(Term.New(init)) =>
                      extractSimpleName(init.tpe).foreach { simpleName =>
                        if (!projectExceptionSimpleNames.contains(simpleName)) {
                          recordViolation(simpleName, file, t.pos.startLine + 1)
                        }
                      }
                    case List(innerApply: Term.Apply) =>
                      extractAppliedSimpleName(innerApply.fun) match {
                        case Some(simpleName) if !projectExceptionSimpleNames.contains(simpleName) =>
                          recordViolation(simpleName, file, t.pos.startLine + 1)
                        case _ => ()
                      }
                    case _ => () // variable reference (re-raise) — allowed
                  }
              }
            case Left(error) =>
              parseFailures += ((file, error))
              ()
          }
        }

        // Handle parse failures — fatal unless tolerated
        val (tolerated, fatal) =
          parseFailures.toList.partition {
            case (file, _) =>
              val normalized = file.getAbsolutePath.replace(File.separatorChar, '/')
              ignoreParseErrors.exists(pattern => normalized.contains(pattern))
          }

        tolerated.foreach {
          case (file, error) =>
            println(
              s"check-exception-uniqueness: [info] Skipped unparseable file (tolerated): ${file.getAbsolutePath}: $error"
            )
        }

        if (fatal.nonEmpty) {
          val message = new StringBuilder()
          message.append(
            "check-exception-uniqueness: FAIL — could not parse the following Scala source file(s) " +
              "during project-exceptions-only check:\n\n"
          )
          fatal.foreach {
            case (file, error) =>
              message.append(s"  ${file.getAbsolutePath}\n")
              message.append(s"    $error\n\n")
          }
          sys.error(message.toString)
        }

        if (violations.isEmpty) {
          println(
            s"check-exception-uniqueness: project-exceptions-only check passed — no non-project exceptions raised in production code."
          )
          ()
        } else {
          val rootsDisplay = projectRootPackages.mkString(", ")
          val message      = new StringBuilder()
          message.append(
            "check-exception-uniqueness: FAIL — non-project exceptions raised in production code:\n\n"
          )
          message.append(
            "RepCheck rule: all exceptions raised in production code must be custom project-declared\n"
          )
          message.append(
            "Throwable subclasses. Standard exceptions (RuntimeException, IOException, etc.) are not allowed.\n\n"
          )

          val grouped: Map[String, List[(String, Int)]] =
            violations.toList
              .groupBy(_._1)
              .map {
                case (simpleName, triples) =>
                  simpleName -> triples.map { case (_, path, line) => (path, line) }.distinct.sortBy {
                    case (p, l) => (p, l)
                  }
              }

          grouped.toList.sortBy(_._1).foreach {
            case (simpleName, sites) =>
              message.append(s"  $simpleName\n")
              sites.foreach { case (path, line) => message.append(s"    $path:$line\n") }
              message.append("\n")
          }

          message.append(
            s"Create a custom exception class under one of [$rootsDisplay] for each failure case,\n"
          )
          message.append(
            "then use that custom exception instead of the standard one."
          )
          sys.error(message.toString)
        }
      }
    }

  /**
   * Check whether a `Term` represents a `raiseError` call. Matches:
   *   - `x.raiseError` — `Term.Select(_, Term.Name("raiseError"))`
   *   - `X[T].raiseError` — `Term.Select(Term.ApplyType(...), Term.Name("raiseError"))`
   *   - `x.raiseError[T]` — `Term.ApplyType(Term.Select(_, Term.Name("raiseError")), _)`
   */
  private def isRaiseError(fun: Term): Boolean = fun match {
    case Term.Select(_, Term.Name("raiseError")) => true
    case at: Term.ApplyType =>
      at.fun match {
        case Term.Select(_, Term.Name("raiseError")) => true
        case inner: Term.ApplyType                   => isRaiseError(inner)
        case _                                       => false
      }
    case _ => false
  }

  /** Reuse the same name extraction logic as ThrowSiteUniquenessCheck. */
  private def extractSimpleName(tpe: Type): Option[String] = tpe match {
    case Type.Name(value)      => Some(value)
    case Type.Select(_, name)  => Some(name.value)
    case Type.Project(_, name) => Some(name.value)
    case applied: Type.Apply   => extractSimpleName(applied.tpe)
    case _                     => None
  }

  private def extractAppliedSimpleName(fun: Term): Option[String] = fun match {
    case Term.Name(name)                 => Some(name)
    case Term.Select(_, Term.Name(name)) => Some(name)
    case applyType: Term.ApplyType       => extractAppliedSimpleName(applyType.fun)
    case apply: Term.Apply               => extractAppliedSimpleName(apply.fun)
    case _                               => None
  }

  /** Recursively collect every `.scala` file under `root`. */
  private def collectScalaFiles(root: File): List[File] = {
    val acc = new ListBuffer[File]()
    def loop(dir: File): Unit =
      if (dir.exists() && dir.isDirectory) {
        val entries = dir.listFiles()
        if (entries != null) {
          entries.foreach { entry =>
            if (entry.isDirectory)
              loop(entry)
            else if (entry.isFile && entry.getName.endsWith(".scala"))
              acc += entry
          }
        }
      }
    loop(root)
    acc.toList
  }

  private def parseFile(file: File): Either[String, Tree] =
    try {
      val bytes = Files.readAllBytes(file.toPath)
      val text  = new String(bytes, StandardCharsets.UTF_8)
      val input = Input.VirtualFile(file.getAbsolutePath, text)
      dialects.Scala3(input).parse[Source] match {
        case Parsed.Success(tree)    => Right(tree)
        case Parsed.Error(_, msg, _) => Left(msg)
      }
    } catch {
      case NonFatal(e) =>
        Left(s"I/O error reading file: ${e.getMessage}")
    }

}
