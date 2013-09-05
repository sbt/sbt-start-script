addSbtPlugin("com.typesafe.sbt" % "sbt-scalariform" % "1.2.0")

libraryDependencies <+= (sbtVersion)(sbtVersion =>
  "org.scala-sbt" % "scripted-plugin" % sbtVersion
)
