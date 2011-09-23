This plugin allows you to generate a script `target/start` for a
project.  The script will run the project "in-place" (without having
to build a package first).

The `target/start` script is similar to `sbt run` but it doesn't rely
on SBT. `sbt run` is not recommended for production use because it
keeps SBT itself in-memory. `target/start` is intended to run an
app in production.

The plugin adds a task `start-script` which generates `target/start`.
It also adds a `stage` task, aliased to the `start-script` task.

`stage` by convention performs any tasks needed to prepare an app to
be run in-place. Other plugins that use a different approach to
prepare an app to run could define `stage` as well, while
`start-script` is specific to this plugin.

The `target/start` script must be run from the root build directory
(note: NOT the root _project_ directory). This allows inter-project
dependencies within your build to work properly.

## Details

To add the plugin with SBT 0.10.x, use this code to depend on it:

    resolvers += {
      val typesafeRepoUrl = new java.net.URL("http://repo.typesafe.com/typesafe/ivy-releases")
      val pattern = Patterns(false, "[organisation]/[module]/[sbtversion]/[revision]/[type]s/[module](-[classifier])-[revision].[ext]")
      Resolver.url("Typesafe Ivy Snapshot Repository", typesafeRepoUrl)(pattern)
    }

    libraryDependencies <<= (libraryDependencies, sbtVersion) { (deps, version) =>
      deps :+ ("com.typesafe.startscript" %% "xsbt-start-script-plugin" % "0.2.0" extra("sbtversion" -> version))
    }

With SBT 0.11.x, you can use this simpler code:

    resolvers += Classpaths.typesafeSnapshots

    addSbtPlugin("com.typesafe.startscript" % "xsbt-start-script-plugin" % "0.2.1-SNAPSHOT")

You can place that code in `~/.sbt/plugins/build.sbt` to install the
plugin globally, or in YOURPROJECT/project/plugins/build.sbt to
install the plugin for your project.

If you install the plugin globally, it will add a command
`add-start-script-tasks` to every project using SBT. You can run this
command to add the tasks from the plugin, such as `start-script` (the
`start-script` task won't exist until you `add-start-script-tasks`).

If you incorporate the plugin into your project, then you'll want to
explicitly add the settings from the plugin, such as the
`start-script` task, to your project. In this case there's no need to
use `add-start-script-tasks` since you'll already add them in your
build.

Here's how you add the settings from the plugin in a `build.sbt`:

    import com.typesafe.startscript.StartScriptPlugin

    seq(StartScriptPlugin.startScriptForClassesSettings: _*)

In an SBT "full configuration" you would do something like:

    settings = StartScriptPlugin.startScriptForClassesSettings

You have to choose which settings to add from these options:

 - `startScriptForClassesSettings`  (the script will run from .class files)
 - `startScriptForJarSettings`      (the script will run from .jar file from 'package')
 - `startScriptForWarSettings`      (the script will run a .war with Jetty)

`startScriptForWarSettings` requires
https://github.com/siasia/xsbt-web-plugin/ to provide the
`package-war` task.

If you have an aggregate project, you may want a `stage` task even
though there's nothing to run, just so it will recurse into sub-projects.
One way to get a `stage` task that does nothing is:

    StartScriptPlugin.stage in Compile := Unit

which sets the `stage` key to `Unit`.
