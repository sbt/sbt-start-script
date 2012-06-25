import sbt._

import Keys._
import Project.Initialize
import Classpaths._

object StartScriptBuild extends Build {
    // private def typesafeReleases = typesafeResolver // 0.11

    lazy val root =
        Project("root", file("."), settings = rootSettings)

    lazy val rootSettings = Defaults.defaultSettings ++
        Seq(sbtPlugin := true,
            resolvers += typesafeReleases,
            organization := "com.typesafe.startscript",
            name := "xsbt-start-script-plugin",
            scalaVersion := "2.9.2",
            scalacOptions := Seq("-unchecked", "-deprecation"),

            // to release, bump major/minor/micro as appropriate,
            // drop SNAPSHOT, tag and publish.
            // add snapshot back so git master is a SNAPSHOT.
            // when releasing a SNAPSHOT to the repo, bump the micro
            // version at least.
            // Also, change the version number in the README.md
            // Versions and git tags should follow: http://semver.org/
            // except using -SNAPSHOT instead of without hyphen.

            version := "0.5.3-SNAPSHOT",
            libraryDependencies <++= sbtVersion {
                          (version) => List("io", "logging", "process") map ("org.scala-sbt" % _ % version % "provided")
            },
            // publish stuff
            publishTo <<= (version) { v => Option(if (v endsWith "SNAPSHOT") typesafeSnapshots else typesafeReleases) },
            publishMavenStyle := false,
            credentials += Credentials(Path.userHome / ".ivy2" / ".typesafe-credentials"))
}
