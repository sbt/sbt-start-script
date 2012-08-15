addSbtPlugin("com.typesafe.sbtscalariform" % "sbtscalariform" % "0.4.0")

libraryDependencies <+= (sbtVersion)(sbtVersion =>
  "org.scala-sbt" % "scripted-plugin" % sbtVersion
)
