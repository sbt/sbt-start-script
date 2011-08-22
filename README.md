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

## Details

There are two ways to use the plugin.

The first is to install it globally, for now by dropping
StartScriptPlugin.scala in `~/.sbt/plugins/` (FIXME once we publish the
directions here change).

If you install the plugin globally, it will add a command
`add-start-script-tasks` to every project using SBT. You can run
this command to add the tasks from the plugin, such as `start-script`
(the `start-script` task won't exist until you
`add-start-script-tasks`, to avoid interfering with projects that
use the plugin directly and override `start-script`).

The second way to use it is to incorporate it into your project, and
then add the plugin settings to your settings. In build.sbt this might
look like `seq(startScriptForClassesSettings :_*)` for example. (FIXME
describe how to add the plugin to libraryDependencies, once it's
published)

You have to choose which settings to add from:

 - `startScriptForClassesSettings`  (the script will run from .class files)
 - `startScriptForJarSettings`      (the script will run from .jar file from 'package')
 - `startScriptForWarSettings`      (the script will run a .war with Jetty)

`startScriptForWarSettings` requires
https://github.com/siasia/xsbt-web-plugin/ to provide the
`package-war` task.
