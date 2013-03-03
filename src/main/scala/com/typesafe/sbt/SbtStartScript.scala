package com.typesafe.sbt

import _root_.sbt._

import Project.Initialize
import Keys._
import Defaults._
import Scope.GlobalScope

import java.util.regex.Pattern
import java.io.File

object SbtStartScript extends Plugin {
    override lazy val settings = Seq(commands += addStartScriptTasksCommand)

    case class RelativeClasspathString(value: String)

    ///// The "stage" setting is generic and may also be used by other plugins
    ///// to accomplish staging in a different way (other than a start script)

    val stage = TaskKey[Unit]("stage", "Prepares the project to be run, in environments that deploy source trees rather than packages.")

    ///// Settings keys

    object StartScriptKeys {
        val startScriptFile = SettingKey[File]("start-script-name")
        val relativeDependencyClasspathString = TaskKey[RelativeClasspathString]("relative-dependency-classpath-string", "Dependency classpath as colon-separated string with each entry relative to the build root directory.")
        val relativeFullClasspathString = TaskKey[RelativeClasspathString]("relative-full-classpath-string", "Full classpath as colon-separated string with each entry relative to the build root directory.")
        val startScriptBaseDirectory = SettingKey[File]("start-script-base-directory", "All start scripts must be run from this directory.")
        val startScriptForWar = TaskKey[File]("start-script-for-war", "Generate a shell script to launch the war file")
        val startScriptForJar = TaskKey[File]("start-script-for-jar", "Generate a shell script to launch the jar file")
        val startScriptForClasses = TaskKey[File]("start-script-for-classes", "Generate a shell script to launch from classes directory")
        val startScriptNotDefined = TaskKey[File]("start-script-not-defined", "Generate a shell script that just complains that the project is not launchable")
        val startScript = TaskKey[File]("start-script", "Generate a shell script that runs the application")

        // jetty-related settings keys
        val startScriptJettyVersion = SettingKey[String]("start-script-jetty-version", "Version of Jetty to use for running the .war")
        val startScriptJettyChecksum = SettingKey[String]("start-script-jetty-checksum", "Expected SHA-1 of the Jetty distribution we intend to download")
        val startScriptJettyURL = SettingKey[String]("start-script-jetty-url", "URL of the Jetty distribution to download (if set, then it overrides the start-script-jetty-version)")
        val startScriptJettyContextPath = SettingKey[String]("start-script-jetty-context-path", "Context path for the war file when deployed to Jetty")
        val startScriptJettyHome = TaskKey[File]("start-script-jetty-home", "Download Jetty distribution and return JETTY_HOME")
    }

    import StartScriptKeys._

    // this is in WebPlugin, but we don't want to rely on WebPlugin to build
    private val packageWar = TaskKey[File]("package-war")

    // apps can manually add these settings (in the way you'd use WebPlugin.webSettings),
    // or you can install the plugin globally and use add-start-script-tasks to add
    // these settings to any project.
    val genericStartScriptSettings: Seq[Project.Setting[_]] = Seq(
        startScriptFile <<= (target) { (target) => target / "start" },
        // maybe not the right way to do this...
        startScriptBaseDirectory <<= (thisProjectRef) { (ref) => new File(ref.build) },
        startScriptNotDefined in Compile <<= (streams, startScriptFile in Compile) map startScriptNotDefinedTask,
        relativeDependencyClasspathString in Compile <<= (startScriptBaseDirectory, dependencyClasspath in Runtime) map relativeClasspathStringTask,
        relativeFullClasspathString in Compile <<= (startScriptBaseDirectory, fullClasspath in Runtime) map relativeClasspathStringTask,
        stage in Compile <<= (startScript in Compile) map stageTask)

    // settings to be added to a web plugin project
    val startScriptForWarSettings: Seq[Project.Setting[_]] = Seq(
        // hardcoding these defaults is not my favorite, but I'm not sure what else to do exactly.
        startScriptJettyVersion in Compile := "7.3.1.v20110307",
        startScriptJettyChecksum in Compile := "10cb58096796e2f1d4989590a4263c34ae9419be",
        startScriptJettyURL in Compile <<= (startScriptJettyVersion in Compile) { (version) => "http://archive.eclipse.org/jetty/" + version + "/dist/jetty-distribution-" + version + ".zip" },
        startScriptJettyContextPath in Compile := "/",
        startScriptJettyHome in Compile <<= (streams, target, startScriptJettyURL in Compile, startScriptJettyChecksum in Compile) map startScriptJettyHomeTask,
        startScriptForWar in Compile <<= (streams, startScriptBaseDirectory, startScriptFile in Compile, packageWar in Compile, startScriptJettyHome in Compile, startScriptJettyContextPath in Compile) map startScriptForWarTask,
        startScript in Compile <<= startScriptForWar in Compile) ++ genericStartScriptSettings

    // settings to be added to a project with an exported jar
    val startScriptForJarSettings: Seq[Project.Setting[_]] = Seq(
        startScriptForJar in Compile <<= (streams, startScriptBaseDirectory, startScriptFile in Compile, packageBin in Compile, relativeDependencyClasspathString in Compile, mainClass in Compile) map startScriptForJarTask,
        startScript in Compile <<= startScriptForJar in Compile) ++ genericStartScriptSettings

    // settings to be added to a project that doesn't export a jar
    val startScriptForClassesSettings: Seq[Project.Setting[_]] = Seq(
        startScriptForClasses in Compile <<= (streams, startScriptBaseDirectory, startScriptFile in Compile, relativeFullClasspathString in Compile, mainClass in Compile) map startScriptForClassesTask,
        startScript in Compile <<= startScriptForClasses in Compile) ++ genericStartScriptSettings

    // Extracted.getOpt is not in 10.1 and earlier
    private def inCurrent[T](extracted: Extracted, key: ScopedKey[T]): Scope = {
        if (key.scope.project == This)
            key.scope.copy(project = Select(extracted.currentRef))
        else
            key.scope
    }
    private def getOpt[T](extracted: Extracted, key: ScopedKey[T]): Option[T] = {
        extracted.structure.data.get(inCurrent(extracted, key), key.key)
    }

    // surely this is harder than it has to be
    private def extractedLabel(extracted: Extracted): String = {
        val ref = extracted.currentRef
        val structure = extracted.structure
        val project = Load.getProject(structure.units, ref.build, ref.project)
        Keys.name in ref get structure.data getOrElse ref.project
    }

    private def collectIfMissing(extracted: Extracted, settings: Seq[Setting[_]], toCollect: Setting[_]): Seq[Setting[_]] = {
        val maybeExisting = getOpt(extracted, toCollect.key)
        maybeExisting match {
            case Some(x) => settings
            case None => settings :+ toCollect
        }
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
        } else if (true /* can't figure out how to decide this ("is there a main class?") without compiling first */ ) {
            log.info("Aliasing start-script to start-script-for-classes in " + extractedLabel(extracted))
            startScriptForClassesSettings
        } else {
            log.info("Aliasing start-script to start-script-not-defined in " + extractedLabel(extracted))
            genericStartScriptSettings ++ Seq(startScript in Compile <<= startScriptNotDefined in Compile)
        }
    }

    private def makeAppendSettings(settings: Seq[Setting[_]], inProject: ProjectRef, extracted: Extracted) = {
        // transforms This scopes in 'settings' to be the desired project
        val appendSettings = Load.transformSettings(Load.projectScope(inProject), inProject.build, extracted.rootProject, settings)
        appendSettings
    }

    private def reloadWithAppended(state: State, appendSettings: Seq[Setting[_]]): State = {
        val session = Project.session(state)
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

        var settingsToAdd = Seq[Setting[_]]()
        for (s <- resolved) {
            settingsToAdd = collectIfMissing(extracted, settingsToAdd, s)
        }

        makeAppendSettings(settingsToAdd, ref, extracted)
    }

    // command to add the startScript tasks, avoiding overriding anything the
    // app already has, and intelligently selecting the right target for
    // the "start-script" alias
    lazy val addStartScriptTasksCommand =
        Command.command("add-start-script-tasks") { (state: State) =>
            val allRefs = Project.extract(state).structure.allProjectRefs
            val allAppendSettings = allRefs.foldLeft(Seq[Setting[_]]())({ (soFar, ref) =>
                soFar ++ getStartScriptTaskSettings(state, ref)
            })
            val newState = reloadWithAppended(state, allAppendSettings)

            //println(Project.details(Project.extract(newState).structure, false, GlobalScope, startScript.key))

            newState
        }

    private def directoryEqualsOrContains(d: File, f: File): Boolean = {
        if (d == f) {
            true
        } else {
            val p = f.getParentFile()
            if (p == null)
                false
            else
                directoryEqualsOrContains(d, p)
        }
    }

    // Because we want to still work if the project directory is built and then moved,
    // we change all file references pointing inside build's base directory to be relative
    // to the build (not the project) before placing them in the start script.
    // This is presumably unix-specific so we skip it if the separator char is not '/'
    // We never add ".." to make something relative, since we are only making relative
    // to basedir things that are already inside basedir. If basedir moves, we'd want
    // references to outside of it to be absolute, to keep working. We don't support
    // moving projects, just the entire build, which is generally a single git repo.
    private def relativizeFile(baseDirectory: File, f: File, prefix: String = ".") = {
        if (java.io.File.separatorChar != '/') {
            f
        } else {
            val baseCanonical = baseDirectory.getCanonicalFile()
            val fCanonical = f.getCanonicalFile()
            if (directoryEqualsOrContains(baseCanonical, fCanonical)) {
                val basePath = baseCanonical.getAbsolutePath()
                val fPath = fCanonical.getAbsolutePath()
                if (fPath.startsWith(basePath)) {
                    new File(prefix + fPath.substring(basePath.length))
                } else {
                    sys.error("Internal bug: %s contains %s but is not a prefix of it".format(basePath, fPath))
                }
            } else {
                // leave it as-is, don't even canonicalize
                f
            }
        }
    }

    private def renderTemplate(template: String, fields: Map[String, String]) = {
        val substRegex = """@[A-Z_]+@""".r
        for (m <- substRegex findAllIn template) {
            val withoutAts = m.substring(1, m.length - 1)
            if (!fields.contains(withoutAts))
                sys.error("Template has variable %s which is not in the substitution map %s".format(withoutAts, fields))
        }
        // this is neither fast nor very correct (since if a value contains an @@ we'd substitute
        // on a substitution) but it's fine for private ad hoc use where we know it doesn't
        // matter
        fields.iterator.foldLeft(template)({ (s, kv) =>
            val withAts = "@" + kv._1 + "@"
            if (!s.contains(withAts))
                sys.error("Template does not contain variable " + withAts)
            s.replace(withAts, kv._2)
        })
    }

    private def relativeClasspathStringTask(baseDirectory: File, cp: Classpath) = {
        RelativeClasspathString(cp.files map { f => relativizeFile(baseDirectory, f, "$PROJECT_DIR") } mkString ("", java.io.File.pathSeparator, ""))
    }

    // Generate shell script that calculates path to project directory from its own path.
    private def scriptRootDetect(baseDirectory: File, scriptFile: File, otherFile: Option[File]): String = {
        val baseDir = baseDirectory.getCanonicalPath
        val scriptDir = scriptFile.getParentFile.getCanonicalPath
        val pathFromScriptDirToBaseDir = if (scriptDir startsWith (baseDir + File.separator)) {
            val relativePath = scriptDir drop (baseDir.length + 1)
            var parts = relativePath split Pattern.quote(File.separator)
            Seq.fill(parts.length)("..").mkString(File.separator)
        } else {
            sys.error("Start script must be located inside project directory.")
        }

        renderTemplate(
            """PROJECT_DIR=$(dirname $(readlink -f "${BASH_SOURCE[0]}"))/@PATH_TO_PROJECT@""",
            Map(
                "PATH_TO_PROJECT" -> pathFromScriptDirToBaseDir))
    }

    private def mainClassSetup(maybeMainClass: Option[String]): String = {
        maybeMainClass match {
            case Some(mainClass) =>
                "MAINCLASS=" + mainClass + "\n"
            case None =>
                """MAINCLASS="$1"
shift
if test x"$MAINCLASS" = x; then
    die 'This "start" script requires a main class name as the first argument, because a mainClass was not specified in SBT and not autodetected by SBT (usually means you have zero, or more than one, main classes).  You could specify in your SBT build: mainClass in Compile := Some("Whatever")'
fi

"""
        }
    }

    private def writeScript(scriptFile: File, script: String) = {
        IO.write(scriptFile, script)
        scriptFile.setExecutable(true)
    }

    def startScriptForClassesTask(streams: TaskStreams, baseDirectory: File, scriptFile: File, cpString: RelativeClasspathString, maybeMainClass: Option[String]) = {
        val template = """#!/bin/bash
@SCRIPT_ROOT_DETECT@

@MAIN_CLASS_SETUP@

exec java $JAVA_OPTS -cp "@CLASSPATH@" "$MAINCLASS" "$@"

"""
        val script = renderTemplate(template, Map("SCRIPT_ROOT_DETECT" -> scriptRootDetect(baseDirectory, scriptFile, None),
            "CLASSPATH" -> cpString.value,
            "MAIN_CLASS_SETUP" -> mainClassSetup(maybeMainClass)))
        writeScript(scriptFile, script)
        streams.log.info("Wrote start script for mainClass := " + maybeMainClass + " to " + scriptFile)
        scriptFile
    }

    // the classpath string here is dependencyClasspath which includes the exported
    // jar, that is not what I was expecting... anyway it works out since we want
    // the jar on the classpath.
    // We put jar on the classpath and supply a mainClass because with "java -jar"
    // the deps have to be bundled in the jar (classpath is ignored), and SBT does
    // not normally do that.
    def startScriptForJarTask(streams: TaskStreams, baseDirectory: File, scriptFile: File, jarFile: File, cpString: RelativeClasspathString, maybeMainClass: Option[String]) = {
        val template = """#!/bin/bash
@SCRIPT_ROOT_DETECT@

@MAIN_CLASS_SETUP@

exec java $JAVA_OPTS -cp "@CLASSPATH@" "$MAINCLASS" "$@"

"""

        val relativeJarFile = relativizeFile(baseDirectory, jarFile)

        val script = renderTemplate(template, Map("SCRIPT_ROOT_DETECT" -> scriptRootDetect(baseDirectory, scriptFile, Some(relativeJarFile)),
            "CLASSPATH" -> cpString.value,
            "MAIN_CLASS_SETUP" -> mainClassSetup(maybeMainClass)))
        writeScript(scriptFile, script)
        streams.log.info("Wrote start script for jar " + relativeJarFile + " to " + scriptFile + " with mainClass := " + maybeMainClass)
        scriptFile
    }

    // FIXME implement this; it will be a little bit tricky because
    // we need to download and unpack the Jetty "distribution" which isn't
    // a normal jar dependency. Not sure if Ivy can do that, may have to just
    // have a configurable URL and checksum.
    def startScriptForWarTask(streams: TaskStreams, baseDirectory: File, scriptFile: File, warFile: File, jettyHome: File, jettyContextPath: String) = {

        // First we need a Jetty config to move us to the right context path
        val contextFile = jettyHome / "contexts" / "start-script.xml"

        // (I guess this could use Scala's XML support, feel free to clean up)
        val contextFileTemplate = """
<Configure class="org.eclipse.jetty.webapp.WebAppContext">
  <Set name="contextPath">@CONTEXTPATH@</Set>
  <Set name="war"><SystemProperty name="jetty.home" default="."/>/webapps/@WARFILE_BASENAME@</Set>
</Configure>
"""
        val contextFileContents = renderTemplate(contextFileTemplate,
            Map("WARFILE_BASENAME" -> warFile.getName,
                "CONTEXTPATH" -> jettyContextPath))
        IO.write(contextFile, contextFileContents)

        val template = """#!/bin/bash
@SCRIPT_ROOT_DETECT@

/bin/cp -f "@WARFILE@" "@JETTY_HOME@/webapps" || die "Failed to copy @WARFILE@ to @JETTY_HOME@/webapps"

if test x"$PORT" = x ; then
    PORT=8080
fi

exec java $JAVA_OPTS -Djetty.port="$PORT" -Djetty.home="@JETTY_HOME@" -jar "@JETTY_HOME@/start.jar" "$@"

"""
        val relativeWarFile = relativizeFile(baseDirectory, warFile)

        val script = renderTemplate(template,
            Map("SCRIPT_ROOT_DETECT" -> scriptRootDetect(baseDirectory, scriptFile, Some(relativeWarFile)),
                "WARFILE" -> relativeWarFile.toString,
                "JETTY_HOME" -> jettyHome.toString))
        writeScript(scriptFile, script)

        streams.log.info("Wrote start script for war " + relativeWarFile + " to " + scriptFile)
        scriptFile
    }

    // this is weird; but I can't figure out how to have a "startScript" task in the root
    // project that chains to child tasks, without having this dummy. For example "package"
    // works the same way, it even creates a bogus empty jar file in the root project!
    def startScriptNotDefinedTask(streams: TaskStreams, scriptFile: File) = {
        writeScript(scriptFile, """#!/bin/bash
echo "No meaningful way to start this project was defined in the SBT build" 1>&2
exit 1

""")
        streams.log.info("Wrote start script that always fails to " + scriptFile)
        scriptFile
    }

    private def basenameFromURL(url: URL) = {
        val path = url.getPath
        val slash = path.lastIndexOf('/')
        if (slash < 0)
            path
        else
            path.substring(slash + 1)
    }

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
                val contents = PathFinder(deleteContentsOf) ** new SimpleFileFilter({ f =>
                    f != deleteContentsOf
                })
                for (doNotWant <- contents.get) {
                    streams.log.debug("Deleting test noise " + doNotWant)
                    IO.delete(doNotWant)
                }
            }

            jettyHome
        } catch {
            case e: Exception =>
                streams.log.error("Failure obtaining Jetty distribution: " + e.getMessage)
                throw e
        }
    }

    def stageTask(startScriptFile: File) = {
        // we don't do anything for now
    }
}
