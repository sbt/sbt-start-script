addSbtPlugin("com.typesafe.sbt" % "sbt-start-script" % "0.5.3-SNAPSHOT")

libraryDependencies <+= (sbtVersion)(sbtVersion =>
  "com.github.siasia" % "xsbt-web-plugin_2.9.2" % (sbtVersion + "-0.2.11.1")
)
