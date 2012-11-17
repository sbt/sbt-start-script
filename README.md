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

To use the plugin with SBT 0.12.0 and later:

    addSbtPlugin("com.typesafe.sbt" % "sbt-start-script" % "0.6.0")

You can place that code in `~/.sbt/plugins/build.sbt` to install the
plugin globally, or in `YOURPROJECT/project/plugins.sbt` to
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

    import com.typesafe.sbt.SbtStartScript

    seq(SbtStartScript.startScriptForClassesSettings: _*)

In an SBT "full configuration" you would do something like:

    settings = SbtStartScript.startScriptForClassesSettings

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

    SbtStartScript.stage in Compile := Unit

which sets the `stage` key to `Unit`.

## Migration from earlier versions of xsbt-start-script-plugin

After 0.5.2, the plugin and its APIs were renamed to use
consistent conventions (matching other plugins). The renamings
were:

 - the plugin itself is now `sbt-start-script` not
   `xsbt-start-script-plugin`; update this in your `plugins.sbt`
 - the Maven group and Java package are now `com.typesafe.sbt`
   rather than `com.typesafe.startscript`; update this in your
   `plugins.sbt` and in your build files
 - the plugin object is now `SbtStartScript` rather than
   `StartScriptPlugin`, update this in your build files
 - if you used any keys directly, they are now inside a nested
   object `StartScriptKeys` so for example rather than writing
   `startScriptFile` you would write
   `StartScriptKeys.startScriptFile` _or_ you need to `import
   StartScriptKeys._`

## License

sbt-start-script is open source software licensed under the
[Apache 2.0 License](http://www.apache.org/licenses/LICENSE-2.0.html).

## Contribution policy

Contributions via GitHub pull requests are gladly accepted from
their original author.  Before sending the pull request, please
agree to the Contributor License Agreement at
http://typesafe.com/contribute/cla (it takes 30 seconds; you use
your GitHub account to sign the agreement).
