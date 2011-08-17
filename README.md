This plugin allows you to generate a script "target/start" for a project
which will run that project with the proper classpath.

The plugin adds the task 'start-script', run this task to get your
start script.

If you install the plugin globally, it adds a command
'ensure-start-script-tasks' to all builds. 'ensure-start-script-tasks'
adds the 'start-script' task to a project if the project doesn't have
it already.

For now, the best way to use this plugin is to add it globally by
dropping StartScriptPlugin.scala into ~/.sbt/plugins then
use 'ensure-start-script-tasks' followed by 'start-script'

It will be easier to use "normally" once it's published somewhere.
