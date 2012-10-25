import com.typesafe.sbt.SbtStartScript

seq(SbtStartScript.startScriptForClassesSettings: _*)

version := "0.1"

TaskKey[Unit]("check") <<= (target) map { (target) =>
  val process = sbt.Process((target / "start").toString)
  val out = (process!!)
  if (out.trim != "Hello") error("unexpected output: " + out)
  ()
}
