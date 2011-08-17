import sbt._

import Keys._
import Project.Initialize

object StartScriptBuild extends Build {
    lazy val root =
        Project("root", file("."), settings = rootSettings)

    lazy val rootSettings = Defaults.defaultSettings ++
        Seq(sbtPlugin := true,
            organization := "com.typesafe",
            name := "xsbt-start-script-plugin",
            version := "0.1",
            libraryDependencies <++= sbtVersion {
		(version) =>
		    Seq("org.scala-tools.sbt" %% "io" % version % "provided",
			"org.scala-tools.sbt" %% "logging" % version % "provided",
			"org.scala-tools.sbt" %% "process" % version % "provided")
            })
}
