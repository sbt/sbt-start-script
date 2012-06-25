package com.typesafe.startscript

import _root_.sbt._
import Project.Initialize
import Keys._
import Defaults._
import Scope.GlobalScope
import java.io.File.pathSeparator

object StartScriptPlugin extends Plugin {
  ///// The "stage" setting is generic and may also be used by other plugins
  ///// to accomplish staging in a different way (other than a start script)
  val stage = TaskKey[Unit]("stage", "Prepares the project to be run, in environments that deploy source trees rather than packages.")

  ///// Settings keys
  val startScriptFile          = SettingKey[File]("start-script-name")
  val startScriptTemplate      = SettingKey[File]("start-script-template")
  val startScriptBaseDirectory = SettingKey[File]("start-script-base-directory", "All start scripts must be run from this directory.")
  val startScriptForWar        = TaskKey[File]("start-script-for-war", "Generate a shell script to launch the war file")
  val startScriptForJar        = TaskKey[File]("start-script-for-jar", "Generate a shell script to launch the jar file")
  val startScriptForClasses    = TaskKey[File]("start-script-for-classes", "Generate a shell script to launch from classes directory")
  val startScriptNotDefined    = TaskKey[File]("start-script-not-defined",
      "Generate a shell script that just complains that the project is not launchable")
  val startScript              = TaskKey[File]("start-script", "Generate a shell script that runs the application")

  // jetty-related settings keys
  val startScriptJettyVersion     = SettingKey[String]("start-script-jetty-version", "Version of Jetty to use for running the .war")
  val startScriptJettyChecksum    = SettingKey[String]("start-script-jetty-checksum", "Expected SHA-1 of the Jetty distribution we intend to download")
  val startScriptJettyURL         = SettingKey[String]("start-script-jetty-url",
    "URL of the Jetty distribution to download (if set, then it overrides the start-script-jetty-version)")
  val startScriptJettyContextPath = SettingKey[String]("start-script-jetty-context-path", "Context path for the war file when deployed to Jetty")
  val startScriptJettyHome        = TaskKey[File]("start-script-jetty-home", "Download Jetty distribution and return JETTY_HOME")

  // FIXME - it would be better if these were in template files.
  private val runnerTemplate =
"""#!/bin/sh
#

@SCRIPT_ROOT_CHECK@

@MAIN_CLASS_SETUP@

exec java $JAVA_OPTS -cp "@CLASSPATH@" "$MAINCLASS" "$@"
"""
  private val warTemplate =
"""#!/bin/sh
#

@SCRIPT_ROOT_CHECK@

/bin/cp -f "@WARFILE@" "@JETTY_HOME@/webapps" || die "Failed to copy @WARFILE@ to @JETTY_HOME@/webapps"

if test x"$PORT" = x ; then
PORT=8080
fi

exec java $JAVA_OPTS -Djetty.port="$PORT" -Djetty.home="@JETTY_HOME@" -jar "@JETTY_HOME@/start.jar" "$@"
"""
  private val failTemplate =
"""#!/bin/sh
#

echo "No meaningful way to start this project was defined in the SBT build" 1>&2
exit 1
"""
  private val dieTemplate = """
die() {
  echo "$@" 1>&2
  exit 1
}
test -x '@RELATIVE_SCRIPT@' || die "'@RELATIVE_SCRIPT@' not found, this script must be run from the project base directory"
""".trim

    private[StartScriptPlugin] abstract class ScriptOps(val streams: TaskStreams, val baseDirectory: File, val scriptFile: File) {
      def script: String      // produce the script content
      def logMessage: String  // you understand, for logging

      lazy val baseCanonical = baseDirectory.getCanonicalFile
      lazy val baseAbsolute  = baseCanonical.getAbsolutePath

      def renderTemplate(template: String)(fields: (String, Any)*): String = {
        // this is neither fast nor very correct (since if a value contains an @@ we'd substitute
        // on a substitution) but it's fine for private ad hoc use where we know it doesn't
        // matter
        val result = fields.foldLeft(template) { case (s, (k, v)) =>
          val k1 = "@" + k + "@"
          if (!(s contains k1))
            streams.log.warn("Template does not contain variable " + k1)

          s.replaceAllLiterally(k1, "" + v)
        }
        for (m <- """@[A-Z_]+@""".r findAllIn result)
          streams.log.warn("%s occurs in rendered output - missed substitution?" format m)

        result
      }

      def generateAndLog(): File = {
        IO.write(scriptFile, script)
        scriptFile setExecutable true
        streams.log.info("Wrote start script %s to %s".format(logMessage, scriptFile))
        scriptFile
      }

      def relativizeClasspath(cp: Classpath): String =
        cp.files map relativizeFile mkString pathSeparator

      private def shouldRelativize(f: File) = (
           (java.io.File.separatorChar == '/')
        && (directoryEqualsOrContains(baseCanonical, f))
      )
      // Because we want to still work if the project directory is built and then moved,
      // we change all file references pointing inside build's base directory to be relative
      // to the build (not the project) before placing them in the start script.
      // This is presumably unix-specific so we skip it if the separator char is not '/'
      // We never add ".." to make something relative, since we are only making relative
      // to basedir things that are already inside basedir. If basedir moves, we'd want
      // references to outside of it to be absolute, to keep working. We don't support
      // moving projects, just the entire build, which is generally a single git repo.
      // private
      def relativizeFile(f: File) = {
        val fCanonical = f.getCanonicalFile
        if (shouldRelativize(fCanonical)) {
          val basePath = baseAbsolute
          val fPath    = fCanonical.getAbsolutePath()
          if (fPath startsWith baseAbsolute)
            new File("." + fPath.stripPrefix(baseAbsolute))
          else
            sys.error("Internal bug: %s contains %s but is not a prefix of it".format(basePath, fPath))
        }
        else f  // leave it as-is, don't even canonicalize
      }
      // generate shell script that checks we're in the right directory
      // by checking that the script itself exists.
      def scriptRootCheck(otherFile: Option[File]): String = {
        val (template, arg) = otherFile match {
          case Some(f)  =>
            val append = """test -e '@OTHER_FILE@' || die "'@OTHER_FILE@' not found, this script must be run from the project base directory""""
            (dieTemplate + append, Some("OTHER_FILE" -> f))
          case _ =>
            (dieTemplate, None)
        }
        val args = Seq("RELATIVE_SCRIPT" -> relativizeFile(scriptFile)) ++ arg
        renderTemplate(template)(args: _*)
      }
    }

    override lazy val settings = Seq(commands += addStartScriptTasksCommand)

    // Extracted.getOpt is not in 10.1 and earlier
    private def inCurrent[T](extracted: Extracted, key: ScopedKey[T]): Scope = (
      key.scope.project match {
        case This => key.scope.copy(project = Select(extracted.currentRef))
        case _    => key.scope
      }
    )

    private def getOpt[T](extracted: Extracted, key: ScopedKey[T]): Option[T] = {
      extracted.structure.data.get(inCurrent(extracted, key), key.key)
    }

    // surely this is harder than it has to be
    private def extractedLabel(extracted: Extracted): String = {
	val ref       = extracted.currentRef
	val structure = extracted.structure
	val project   = Load.getProject(structure.units, ref.build, ref.project)

      Keys.name in ref get structure.data getOrElse ref.project
    }

    private def collectIfMissing(extracted: Extracted, toCollect: Setting[_]): Option[Setting[_]] =
      getOpt(extracted, toCollect.key) match {
        case Some(_) => None
        case _       => Some(toCollect)
      }

    private def resolveStartScriptSetting(extracted: Extracted, log: Logger): Seq[Setting[_]] = {
        val maybePackageWar = getOpt(extracted, (packageWar in Compile).scopedKey)
        val maybeExportJars = getOpt(extracted, (exportJars in Compile).scopedKey)

        if (maybePackageWar.isDefined) {
            log.info("Aliasing start-script to start-script-for-war in " + extractedLabel(extracted))
            startScriptForWarSettings
        } else if (maybeExportJars.isDefined && maybeExportJars.get) {
            log.info("Aliasing start-script to start-script-for-jar in " + extractedLabel(extracted))
            startScriptForJarSettings
        } else if (true /* can't figure out how to decide this ("is there a main class?") without compiling first */) {
            log.info("Aliasing start-script to start-script-for-classes in " + extractedLabel(extracted))
            startScriptForClassesSettings
        } else {
            log.info("Aliasing start-script to start-script-not-defined in " + extractedLabel(extracted))
            genericStartScriptSettings ++ Seq(startScript in Compile <<= startScriptNotDefined in Compile)
        }
    }

    private def makeAppendSettings(settings: Seq[Setting[_]], inProject: ProjectRef, extracted: Extracted) = {
      // transforms This scopes in 'settings' to be the desired project
            Load.transformSettings(Load.projectScope(inProject), inProject.build, extracted.rootProject, settings)
    }

    private def reloadWithAppended(state: State, appendSettings: Seq[Setting[_]]): State = {
      val session   = Project.session(state)
      val structure = Project.structure(state)
      implicit val display = Project.showContextKey(state)

      // reloads with appended settings
      val newStructure = Load.reapply(session.original ++ appendSettings, structure)

      // updates various aspects of State based on the new settings
      // and returns the updated State
      Project.setProject(session, newStructure, state)
    }

    private def getStartScriptTaskSettings(state: State, ref: ProjectRef): Seq[Setting[_]] = {
      implicit val display = Project.showContextKey(state)
      val extracted = Extracted(Project.structure(state), Project.session(state), ref)
      state.log.debug("Analyzing startScript tasks for " + extractedLabel(extracted))

      val resolved = resolveStartScriptSetting(extracted, state.log)
      val toAdd    = resolved flatMap (s => collectIfMissing(extracted, s))

      makeAppendSettings(toAdd, ref, extracted)
    }

    // command to add the startScript tasks, avoiding overriding anything the
    // app already has, and intelligently selecting the right target for
    // the "start-script" alias
    lazy val addStartScriptTasksCommand =
      Command.command("add-start-script-tasks") { (state: State) =>
        val allRefs = Project.extract(state).structure.allProjectRefs
        reloadWithAppended(state, allRefs flatMap (getStartScriptTaskSettings(state, _)))
      }

    // this is in WebPlugin, but we don't want to rely on WebPlugin to build
    private val packageWar = TaskKey[File]("package-war")

    private def directoryEqualsOrContains(d: File, f: File): Boolean = (
      (d == f) || (f.getParentFile() match {
        case null => false
        case p    => directoryEqualsOrContains(d, p)
      })
    )

    private def mainClassSetup(maybeMainClass: Option[String]) = {
      val main = maybeMainClass getOrElse "$1"
      "MAINCLASS=%s\n%s".format(main, if (maybeMainClass.isDefined) "" else
"""shift
if test x"$MAINCLASS" = x; then
    die 'This "start" script requires a main class name as the first argument, because a mainClass was not specified in SBT and not autodetected by SBT (usually means you have zero, or more than one, main classes).  You could specify in your SBT build: mainClass in Compile := Some("Whatever")'
fi
"""
      )
    }

    def startScriptForClassesTask(streams: TaskStreams, baseDirectory: File, scriptFile: File, classPath: Classpath, maybeMainClass: Option[String]) = {
      new ScriptOps(streams, baseDirectory, scriptFile) {
        def script = renderTemplate(runnerTemplate)(
          "SCRIPT_ROOT_CHECK" -> scriptRootCheck(None),
          "CLASSPATH"         -> relativizeClasspath(classPath),
          "MAIN_CLASS_SETUP"  -> mainClassSetup(maybeMainClass)
        )
        def logMessage = "with mainClass := " + maybeMainClass
      } generateAndLog
    }

    // the classpath string here is dependencyClasspath which includes the exported
    // jar, that is not what I was expecting... anyway it works out since we want
    // the jar on the classpath.
    // We put jar on the classpath and supply a mainClass because with "java -jar"
    // the deps have to be bundled in the jar (classpath is ignored), and SBT does
    // not normally do that.
    def startScriptForJarTask(streams: TaskStreams, baseDirectory: File, scriptFile: File, jarFile: File, classPath: Classpath, maybeMainClass: Option[String]) = {
      new ScriptOps(streams, baseDirectory, scriptFile) {
        def script = renderTemplate(runnerTemplate)(
          "SCRIPT_ROOT_CHECK" -> scriptRootCheck(Some(relativizeFile(jarFile))),
          "CLASSPATH"         -> relativizeClasspath(classPath),
          "MAIN_CLASS_SETUP"  -> mainClassSetup(maybeMainClass)
        )
        def logMessage = "for jar %s (mainClass := %s)".format(relativizeFile(jarFile), maybeMainClass)
      } generateAndLog
    }

    // FIXME implement this; it will be a little bit tricky because
    // we need to download and unpack the Jetty "distribution" which isn't
    // a normal jar dependency. Not sure if Ivy can do that, may have to just
    // have a configurable URL and checksum.
    def startScriptForWarTask(streams: TaskStreams, baseDirectory: File, scriptFile: File, warFile: File, jettyHome: File, jettyContextPath: String) = {
      new ScriptOps(streams, baseDirectory, scriptFile) {
        val relativeWarFile = relativizeFile(warFile)
        // First we need a Jetty config to move us to the right context path
        val contextFile = jettyHome / "contexts" / "start-script.xml"
        // (I guess this could use Scala's XML support, feel free to clean up)
        val contextFileTemplate = """
<Configure class="org.eclipse.jetty.webapp.WebAppContext">
<Set name="contextPath">@CONTEXTPATH@</Set>
<Set name="war"><SystemProperty name="jetty.home" default="."/>/webapps/@WARFILE_BASENAME@</Set>
</Configure>
"""
        IO.write(
          contextFile,
          renderTemplate(contextFileTemplate)(
            "WARFILE_BASENAME" -> warFile.getName,
            "CONTEXTPATH"      -> jettyContextPath
          )
        )
        def script = renderTemplate(warTemplate)(
          "SCRIPT_ROOT_CHECK" -> scriptRootCheck(Some(relativeWarFile)),
          "WARFILE"           -> relativeWarFile,
          "JETTY_HOME"        -> jettyHome
        )
        def logMessage = "for war " + relativeWarFile
      } generateAndLog
    }

    // this is weird; but I can't figure out how to have a "startScript" task in the root
    // project that chains to child tasks, without having this dummy. For example "package"
    // works the same way, it even creates a bogus empty jar file in the root project!
    def startScriptNotDefinedTask(streams: TaskStreams, scriptFile: File) = (
      new ScriptOps(streams, null, scriptFile) {
        def script = failTemplate
        def logMessage = "that always fails"
      } generateAndLog
    )

    private def basenameFromURL(url: URL): String =
      url.getPath match { case p => p drop p.lastIndexOf('/') + 1 }

    def startScriptJettyHomeTask(streams: TaskStreams, target: File, jettyURLString: String, jettyChecksum: String) = {
        try {
            val jettyURL = new URL(jettyURLString)
            val jettyDistBasename = basenameFromURL(jettyURL)
            if (!jettyDistBasename.endsWith(".zip"))
                sys.error("%s doesn't end with .zip".format(jettyDistBasename))
            val jettyHome = target / jettyDistBasename.substring(0, jettyDistBasename.length - ".zip".length)

            val zipFile = target / jettyDistBasename
            if (!zipFile.exists()) {
                streams.log.info("Downloading %s to %s".format(jettyURL.toExternalForm, zipFile))
                IO.download(jettyURL, zipFile)
            } else {
                streams.log.debug("%s already exists".format(zipFile))
            }
            val sha1 = Hash.toHex(Hash(zipFile))
            if (sha1 != jettyChecksum) {
                streams.log.error("%s has checksum %s expected %s".format(jettyURL.toExternalForm, sha1, jettyChecksum))
                sys.error("Bad checksum on Jetty distribution")
            }
            try {
                IO.delete(jettyHome)
            } catch {
                case e: Exception => // probably didn't exist
            }
            val files = IO.unzip(zipFile, target)
            val jettyHomePrefix = jettyHome.getCanonicalPath
            // check that all the unzipped files went where expected
            files foreach { f =>
                if (!f.getCanonicalPath.startsWith(jettyHomePrefix))
                    sys.error("Unzipped jetty file %s that isn't in %s".format(f, jettyHome))
            }
            streams.log.debug("Unzipped %d files to %s".format(files.size, jettyHome))

            // delete annoying test.war and associated gunge
            for (deleteContentsOf <- (Seq("contexts", "webapps") map { jettyHome / _ })) {
                val contents = PathFinder(deleteContentsOf) ** new SimpleFileFilter(_ != deleteContentsOf)
                for (doNotWant <- contents.get) {
                    streams.log.debug("Deleting test noise " + doNotWant)
                    IO.delete(doNotWant)
                }
            }
            jettyHome
        }
        catch {
            case e: Throwable =>
                streams.log.error("Failure obtaining Jetty distribution: " + e.getMessage)
            throw e
        }
    }

    def stageTask(startScriptFile: File) = {
        // we don't do anything for now
    }

    // apps can manually add these settings (in the way you'd use WebPlugin.webSettings),
    // or you can install the plugin globally and use add-start-script-tasks to add
    // these settings to any project.
    val genericStartScriptSettings: Seq[Project.Setting[_]] = Seq(
        startScriptFile <<= (target)(_ / "start"),
        // maybe not the right way to do this...
        startScriptBaseDirectory <<= (thisProjectRef) { (ref) => new File(ref.build) },
        startScriptNotDefined in Compile <<= (streams, startScriptFile in Compile) map startScriptNotDefinedTask,
        stage in Compile <<= (startScript in Compile) map stageTask
    )

    // settings to be added to a web plugin project
    val startScriptForWarSettings: Seq[Project.Setting[_]] = Seq(
        // hardcoding these defaults is not my favorite, but I'm not sure what else to do exactly.
        startScriptJettyVersion in Compile := "7.3.1.v20110307",
        startScriptJettyChecksum in Compile := "10cb58096796e2f1d4989590a4263c34ae9419be",
        startScriptJettyURL in Compile <<= (startScriptJettyVersion in Compile)(v =>
          "http://archive.eclipse.org/jetty/%s/dist/jetty-distribution-%s.zip".format(v, v)),
        startScriptJettyContextPath in Compile := "/",
        startScriptJettyHome in Compile <<= (streams, target, startScriptJettyURL in Compile, startScriptJettyChecksum in Compile) map startScriptJettyHomeTask,
        startScriptForWar in Compile <<= (streams, startScriptBaseDirectory, startScriptFile in Compile, packageWar in Compile, startScriptJettyHome in Compile, startScriptJettyContextPath in Compile) map startScriptForWarTask,
        startScript in Compile <<= startScriptForWar in Compile
    ) ++ genericStartScriptSettings

    // settings to be added to a project with an exported jar
    val startScriptForJarSettings: Seq[Project.Setting[_]] = Seq(
        startScriptForJar in Compile <<= (streams, startScriptBaseDirectory, startScriptFile in Compile, packageBin in Compile, dependencyClasspath in Runtime, mainClass in Compile) map startScriptForJarTask,
        startScript in Compile <<= startScriptForJar in Compile
    ) ++ genericStartScriptSettings

    // settings to be added to a project that doesn't export a jar
    val startScriptForClassesSettings: Seq[Project.Setting[_]] = Seq(
        startScriptForClasses in Compile <<= (streams, startScriptBaseDirectory, startScriptFile in Compile, fullClasspath in Runtime, mainClass in Compile) map startScriptForClassesTask,
        startScript in Compile <<= startScriptForClasses in Compile
    ) ++ genericStartScriptSettings
}
