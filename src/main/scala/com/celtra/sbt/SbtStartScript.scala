package com.celtra.sbt

import java.io.File
import java.util.regex.Pattern

import _root_.sbt._
import sbt.Defaults._
import sbt.Keys._

object SbtStartScript extends Plugin {
    override lazy val settings = Seq(commands += addStartScriptTasksCommand)

    case class RelativeClasspathString(value: String)

    ///// The "stage" setting is generic and may also be used by other plugins
    ///// to accomplish staging in a different way (other than a start script)

    val stage = TaskKey[Unit]("stage", "Prepares the project to be run, in environments that deploy source trees rather than packages.")

    ///// Settings keys

    object StartScriptKeys {
        val startScriptFile = SettingKey[File]("start-script-name")
        val testScriptFile = SettingKey[File]("test-script-name")
        // this is newly-added to make the val name consistent with the
        // string name, and preferred over startScriptFile
        val startScriptName = startScriptFile
        val relativeDependencyClasspathString = TaskKey[RelativeClasspathString]("relative-dependency-classpath-string", "Dependency classpath as colon-separated string with each entry relative to the build root directory.")
        val relativeFullClasspathString = TaskKey[RelativeClasspathString]("relative-full-classpath-string", "Full classpath as colon-separated string with each entry relative to the build root directory.")
        val relativeTestClasspathString = TaskKey[RelativeClasspathString]("relative-test-classpath-string", "Test classpath as colon-separated string with each entry relative to the build root directory.")
        val startScriptBaseDirectory = SettingKey[File]("start-script-base-directory", "All start scripts must be run from this directory.")
        val startScriptForClasses = TaskKey[File]("start-script-for-classes", "Generate a shell script to launch from classes directory")
        val startScriptForTests = TaskKey[File]("start-script-for-tests", "Generate a shell script to launch tests from classes directory")
        val startScriptNotDefined = TaskKey[File]("start-script-not-defined", "Generate a shell script that just complains that the project is not launchable")
        val startScript = TaskKey[File]("start-script", "Generate a shell script that runs the application")
        val testPackage = TaskKey[String]("test-package", "Base package in which test suites will be looked for")
    }

    import com.celtra.sbt.SbtStartScript.StartScriptKeys._

    val scriptName: String = "start"
    val testScriptName: String = "test"

    // apps can manually add these settings (in the way you'd use WebPlugin.webSettings),
    // or you can install the plugin globally and use add-start-script-tasks to add
    // these settings to any project.
    val genericStartScriptSettings: Seq[Def.Setting[_]] = Seq(
        startScriptFile <<= target { (target) => target / scriptName },
        testScriptFile <<= target { (target) => target / testScriptName },
        // maybe not the right way to do this...
        startScriptBaseDirectory <<= thisProjectRef { (ref) => new File(ref.build) },
        startScriptNotDefined in Compile <<= (streams, startScriptFile in Compile) map startScriptNotDefinedTask,
        relativeDependencyClasspathString in Compile <<= (startScriptBaseDirectory, dependencyClasspath in Runtime) map relativeClasspathStringTask,
        relativeFullClasspathString in Compile <<= (startScriptBaseDirectory, fullClasspath in Runtime) map relativeClasspathStringTask,
        relativeTestClasspathString in Compile <<= (startScriptBaseDirectory, fullClasspath in Test) map relativeClasspathStringTask,
        stage in Compile <<= (startScript in Compile) map stageTask,
        stage in Test <<= (startScript in Test) map stageTestsTask)

    // settings to be added to a project that doesn't export a jar
    val startScriptForClassesSettings: Seq[Def.Setting[_]] = Seq(
        startScriptForClasses in Compile <<= (streams, startScriptBaseDirectory, startScriptFile in Compile, relativeFullClasspathString in Compile, mainClass in Compile) map startScriptForClassesTask,
        startScript in Compile <<= startScriptForClasses in Compile,
        startScriptForClasses in Test <<= (streams, startScriptBaseDirectory, testScriptFile in Test, relativeTestClasspathString in Test, testPackage, classDirectory in Test, envVars in Test) map startScriptForScalaTestTask,
        startScript in Test <<= startScriptForClasses in Test) ++ genericStartScriptSettings

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
        if (true /* can't figure out how to decide this ("is there a main class?") without compiling first */ ) {
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
            val p = f.getParentFile
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
            val baseCanonical = baseDirectory.getCanonicalFile
            val fCanonical = f.getCanonicalFile
            if (directoryEqualsOrContains(baseCanonical, fCanonical)) {
                val basePath = baseCanonical.getAbsolutePath
                val fPath = fCanonical.getAbsolutePath
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

        val template: String = """PROJECT_DIR=$(cd "${BASH_SOURCE[0]%/*}" && pwd -P)/@PATH_TO_PROJECT@"""
        renderTemplate(template, Map("PATH_TO_PROJECT" -> pathFromScriptDirToBaseDir))
    }

    private def mainClassSetup(maybeMainClass: Option[String]): String = {
        maybeMainClass match {
            case Some(mainClass) =>
                "MAINCLASS=" + mainClass + "\n"
            case None =>
                val errMsg = """This "start" script requires a main class name as the first argument, because a mainClass was not specified in SBT and not autodetected by SBT (usually means you have zero, or more than one, main classes).  You could specify in your SBT build: mainClass in Compile := Some("Whatever")"""

                """MAINCLASS="$1"
shift
function die() {
   echo $* 1>&2
   exit 1
}
if test x"$MAINCLASS" = x; then
    die '""" + errMsg + """'
fi

"""
        }
    }

    private def writeScript(scriptFile: File, script: String) = {
        IO.write(scriptFile, script)
        scriptFile.setExecutable(true, false)
    }

    def startScriptForClassesTask(streams: TaskStreams, baseDirectory: File, scriptFile: File, cpString: RelativeClasspathString, maybeMainClass: Option[String]) = {
        val template: String = """#!/bin/bash
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

    def startScriptForScalaTestTask(streams: TaskStreams, baseDirectory: File, scriptFile: File, cpString: RelativeClasspathString, testPackage: String, target: File, envVars: Map[String, String]) = {
        val template: String = """#!/bin/bash
@SCRIPT_ROOT_DETECT@

@ENV_VARS@

exec java $JAVA_OPTS -cp "@CLASSPATH@" org.scalatest.tools.Runner -oF -w @TEST_PACKAGE@ -R @TARGET@

"""
        val script = renderTemplate(template, Map("SCRIPT_ROOT_DETECT" -> scriptRootDetect(baseDirectory, scriptFile, None),
            "ENV_VARS" -> envVars.foldLeft("")( (acc, kv) => acc + s"export ${kv._1}=${kv._2}\n"),
            "CLASSPATH" -> cpString.value,
            "TEST_PACKAGE" -> testPackage,
            "TARGET" -> relativizeFile(baseDirectory, target, "$PROJECT_DIR").toString))
        writeScript(scriptFile, script)
        streams.log.info("Wrote test script for to " + scriptFile)
        scriptFile
    }

    // this is weird; but I can't figure out how to have a "startScript" task in the root
    // project that chains to child tasks, without having this dummy. For example "package"
    // works the same way, it even creates a bogus empty jar file in the root project!
    def startScriptNotDefinedTask(streams: TaskStreams, scriptFile: File) = {
        val errMsg = "No meaningful way to start this project was defined in the SBT build"
        val msg: String = """#!/bin/bash
echo '""" + errMsg + """' 1>&2
exit 1
"""
        writeScript(scriptFile, msg)
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

    def stageTask(startScriptFile: File) = {
        // we don't do anything for now
    }

    def stageTestsTask(startScriptFile: File) = {
        // we don't do anything for now
    }
}
