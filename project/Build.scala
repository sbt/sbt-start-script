import sbt._

import Keys._
import Project.Initialize

object StartScriptBuild extends Build {
    lazy val root =
        Project("root", file("."), settings = rootSettings)

    lazy val rootSettings = Defaults.defaultSettings ++
        Seq(sbtPlugin := true,
            organization := "com.typesafe.startscript",
            name := "xsbt-start-script-plugin",
            // to release, bump number and drop SNAPSHOT, tag and publish,
            // then add SNAPSHOT back so git master has SNAPSHOT.
            // Also, change the version number in the README.md
            // Versions and git tags should follow: http://semver.org/
            version := "0.2.0",
            libraryDependencies <++= sbtVersion {
		(version) =>
		    Seq("org.scala-tools.sbt" %% "io" % version % "provided",
			"org.scala-tools.sbt" %% "logging" % version % "provided",
			"org.scala-tools.sbt" %% "process" % version % "provided")
            },
            // publish stuff
            projectID <<= (projectID, sbtVersion) { (id, version) => id.extra("sbtversion" -> version.toString) },
            publishTo <<= (version) { version =>
                val typesafeRepoUrl =
                    if (version endsWith "SNAPSHOT")
                        new java.net.URL("http://repo.typesafe.com/typesafe/ivy-snapshots")
                    else
                        new java.net.URL("http://repo.typesafe.com/typesafe/ivy-releases")
                    val pattern = Patterns(false, "[organisation]/[module]/[sbtversion]/[revision]/[type]s/[module](-[classifier])-[revision].[ext]")
                    Some(Resolver.url("Typesafe Repository", typesafeRepoUrl)(pattern))
            },
            credentials += Credentials(Path.userHome / ".ivy2" / ".typesafe-credentials"))
}
