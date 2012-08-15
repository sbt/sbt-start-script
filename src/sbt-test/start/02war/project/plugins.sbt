addSbtPlugin("com.typesafe.startscript" % "xsbt-start-script-plugin" % "0.5.2-SNAPSHOT")

libraryDependencies <+= (sbtVersion)(sbtVersion =>
  "com.github.siasia" % "xsbt-web-plugin_2.9.2" % (sbtVersion + "-0.2.11.1")
)
