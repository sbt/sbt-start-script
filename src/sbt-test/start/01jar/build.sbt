import com.typesafe.startscript.StartScriptPlugin

seq(StartScriptPlugin.startScriptForJarSettings: _*)

version := "0.1"

TaskKey[Unit]("check") <<= (target) map { (target) =>
  val process = sbt.Process((target / "start").toString)
  val out = (process!!)
  if (out.trim != "Hello") error("unexpected output: " + out)
  ()
}
