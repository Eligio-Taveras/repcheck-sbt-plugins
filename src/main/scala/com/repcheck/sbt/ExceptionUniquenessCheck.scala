package com.repcheck.sbt

import java.io.File
import java.net.{URL, URLClassLoader}

import scala.collection.mutable.ListBuffer

/**
 * Enforces the RepCheck "flat, unique exception per failure case" rule: no two `Throwable` subclasses under the
 * project's root package may share a simple class name.
 *
 * This replaces the earlier bash/perl-regex implementation with a classloading-based check that inspects the
 * actually-compiled `.class` files. Because it loads the real classes and asks the JVM whether each one is a
 * `Throwable`, it is immune to Scala syntax variations (multi-line class declarations, scaladoc comments, string
 * literals mentioning `extends Exception`, inner classes, companion objects, etc.).
 *
 * Invoked as an sbt task from `build.sbt`. See the `checkExceptionUniqueness` task definition for wiring.
 */
object ExceptionUniquenessCheck {

  /**
   * Run the uniqueness check.
   *
   * @param compileClasses
   *   the `(Compile / classDirectory).value` — root of compiled main-source `.class` files
   * @param testClasses
   *   the `(Test / classDirectory).value` — root of compiled test-source `.class` files
   * @param fullClasspath
   *   every jar and classes dir on the `Compile / fullClasspath` — needed so `URLClassLoader` can resolve superclasses
   *   (e.g. `java.lang.Throwable`) when loading project classes
   * @param projectRootPackages
   *   the dotted prefixes that define which classes are "ours" (e.g. `Seq("com.repcheck")`, or multiple entries if the
   *   repo legitimately spans several root namespaces). This is a pure allowlist: any class whose FQN does not start
   *   with one of these prefixes (followed by `.`) is ignored, so we do not fail on Throwables from the JDK or
   *   third-party libraries that happen to be on the classpath. Must be non-empty — an empty allowlist would scan every
   *   class on the classpath and is almost certainly a configuration mistake.
   */
  def run(
    compileClasses: File,
    testClasses: File,
    fullClasspath: Seq[File],
    projectRootPackages: Seq[String],
    sourceDirectories: Seq[File],
  ): Unit = {
    val declaredSimpleNames =
      checkDeclarationUniqueness(compileClasses, testClasses, fullClasspath, projectRootPackages)
    ThrowSiteUniquenessCheck.run(sourceDirectories, declaredSimpleNames, projectRootPackages)
    println("check-exception-uniqueness: declarations OK, throw sites OK.")
  }

  /**
   * Run only the declaration-name uniqueness pass and return the set of simple names of project-declared Throwable
   * subclasses. Used by [[run]] to feed the throw-site scan, and exposed as its own method so callers can compose the
   * two passes differently if needed.
   *
   * Fails with `sys.error` if duplicates are found.
   */
  def checkDeclarationUniqueness(
    compileClasses: File,
    testClasses: File,
    fullClasspath: Seq[File],
    projectRootPackages: Seq[String],
  ): Set[String] = {
    if (projectRootPackages.isEmpty) {
      sys.error(
        "check-exception-uniqueness: projectRootPackages must be non-empty. " +
          "Provide at least one root package prefix (e.g. Seq(\"com.repcheck\")) " +
          "so the scan has a well-defined allowlist of namespaces to inspect."
      )
    }
    val scanRoots: List[File] =
      List(compileClasses, testClasses).filter(f => f.exists() && f.isDirectory)

    if (scanRoots.isEmpty) {
      println(
        "check-exception-uniqueness: no compiled class directories found; nothing to scan."
      )
      Set.empty[String]
    } else {
      val classpathUrls: Array[URL] =
        (scanRoots ++ fullClasspath).distinct.map(_.toURI.toURL).toArray

      val loader = new URLClassLoader(classpathUrls, getClass.getClassLoader)
      try {
        val throwableClass = classOf[Throwable]

        // (simpleName, fullyQualifiedName)
        val found = new ListBuffer[(String, String)]()

        scanRoots.foreach { root =>
          val rootPath   = root.toPath.toAbsolutePath
          val classFiles = collectClassFiles(root)
          classFiles.foreach { classFile =>
            val relative   = rootPath.relativize(classFile.toPath.toAbsolutePath).toString
            val normalized = relative.replace(File.separatorChar, '/')
            if (normalized.endsWith(".class")) {
              val withoutExt = normalized.substring(0, normalized.length - ".class".length)
              val fqn        = withoutExt.replace('/', '.')

              if (isEligible(fqn, projectRootPackages)) {
                val loadedOpt: Option[Class[_]] =
                  try
                    Some(Class.forName(fqn, false, loader))
                  catch {
                    // Skip anything we can't load — missing optional deps,
                    // malformed classfiles, etc. We only fail on duplicates
                    // we can actually prove are Throwable subclasses.
                    case _: Throwable => None
                  }

                loadedOpt.foreach { cls =>
                  if (
                    throwableClass.isAssignableFrom(cls) &&
                    !cls.isInterface &&
                    !isSyntheticOrAnonymous(cls)
                  ) {
                    found += ((cls.getSimpleName, cls.getName))
                  }
                }
              }
            }
          }
        }

        val grouped: Map[String, List[String]] =
          found.toList
            .groupBy(_._1)
            .map {
              case (simpleName, pairs) =>
                simpleName -> pairs.map(_._2).distinct.sorted
            }

        val duplicates: List[(String, List[String])] =
          grouped.toList
            .filter { case (_, fqns) => fqns.size > 1 }
            .sortBy(_._1)

        if (duplicates.isEmpty) {
          val rootsDisplay = projectRootPackages.mkString(", ")
          println(
            s"check-exception-uniqueness: ${found.size} Throwable subclass(es) scanned under [$rootsDisplay], all unique. OK."
          )
          grouped.keySet
        } else {
          val message = new StringBuilder()
          message.append(
            "check-exception-uniqueness: FAIL — duplicate Throwable subclass names found:\n\n"
          )
          duplicates.foreach {
            case (simpleName, fqns) =>
              message.append(s"  $simpleName\n")
              fqns.foreach(fqn => message.append(s"    $fqn\n"))
              message.append("\n")
          }
          message.append(
            "RepCheck rule: every failure point must have its own uniquely named exception.\n"
          )
          message.append(
            "Rename or remove the duplicates so each Throwable subclass has a distinct name."
          )
          sys.error(message.toString)
        }
      } finally
        loader.close()
    }
  }

  /** Recursively collect every `.class` file under `root`. */
  private def collectClassFiles(root: File): List[File] = {
    val acc = new ListBuffer[File]()
    def loop(dir: File): Unit = {
      val entries = dir.listFiles()
      if (entries != null) {
        entries.foreach { entry =>
          if (entry.isDirectory) {
            loop(entry)
          } else if (entry.isFile && entry.getName.endsWith(".class")) {
            acc += entry
          }
        }
      }
    }
    loop(root)
    acc.toList
  }

  /**
   * Pure allowlist filter: a class is eligible iff its fully-qualified name starts with one of the configured project
   * root package prefixes (followed by `.`). Anything outside the allowlist — JDK classes, Scala stdlib, third-party
   * libraries on the classpath — is ignored by construction, so we never need to maintain a blocklist of known
   * third-party prefixes (which could never be exhaustive).
   *
   * Synthetic / anonymous / companion-object class files are also filtered out: they are not exceptions a developer
   * "named" and would produce noisy false-positive collisions.
   */
  private def isEligible(fqn: String, projectRootPackages: Seq[String]): Boolean = {
    val underAllowedRoot =
      projectRootPackages.exists(p => fqn.startsWith(p + "."))
    val looksSynthetic =
      fqn.contains("$anon") || fqn.contains("$$anonfun") || fqn.endsWith("$")
    underAllowedRoot && !looksSynthetic
  }

  /**
   * Skip anonymous, local, and synthetic classes — they are not exceptions the developer "named" and would cause noisy
   * false-positive collisions.
   */
  private def isSyntheticOrAnonymous(cls: Class[_]): Boolean = {
    val simple = cls.getSimpleName
    cls.isAnonymousClass ||
    cls.isLocalClass ||
    cls.isSynthetic ||
    simple.isEmpty ||
    simple.contains("$")
  }

}
