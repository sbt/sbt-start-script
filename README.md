This plugin allows you to generate a script `target/start` for a project
which will run that project with the proper classpath.

For now, the best way to use this plugin is to add it globally by
dropping `StartScriptPlugin.scala` into `~/.sbt/plugins` then
use `ensure-start-script-tasks` followed by `start-script`

It will be easier to use "normally" once it's published somewhere.

## Details

There are two ways to use the plugin.

The first is to install it globally, for now by dropping
StartScriptPlugin.scala in `~/.sbt/plugins/` (FIXME once we publish the
directions here change).

If you install the plugin globally, it will add a command
`ensure-start-script-tasks` to every project using SBT. You can run
this command to add the tasks from the plugin, such as `start-script`
(the `start-script` task won't exist until you
`ensure-start-script-tasks`, to avoid interfering with projects that
use the plugin directly and override `start-script`).

The second way to use it is to incorporate it into your project, and
then add the plugin settings to your settings. In build.sbt this might
look like `seq(startScriptClassesSettings :_*)` for example. (FIXME
describe how to add the plugin to libraryDependencies, once it's
published)

You have to choose which settings to add from:

 - `startScriptClassesSettings`  (the script will run from .class files)
 - `startScriptJarSettings`      (the script will run from .jar file from 'package')
 - `startScriptWarSettings`      (the script will run a .war with Jetty)

`startScriptWarSettings` requires
https://github.com/siasia/xsbt-web-plugin/ to provide the
`package-war` task.

After you add these settings, you also need to alias `start-script` to
the implementation task you selected, for example: `startScript in Compile <<= (startScriptForWar in Compile).identity`  (FIXME this should be automatic)

When you run it, the task `start-script` creates a file `target/start` which starts your app.
