## Consider sbt-native-packager instead

The more general native-packager plugin may replace this one in
the future: https://github.com/sbt/sbt-native-packager

The rough way to get a start script with sbt-native-packager,
modulo any details of your app, is:

 1. add sbt-native-packager plugin to your project
 2. remove start-script-plugin
 3. add `settings(com.typesafe.sbt.SbtNativePackager.packageArchetype.java_application: _*)`
 4. `stage` task will now generate a script `target/universal/stage/bin/project-name` instead of `target/start`
 5. the sbt-native-packager-generated script looks at a `java_opts` env var but you cannot pass Java opts as parameters to the script as you could with `target/start`
 6. the sbt-native-packager-generated script copies dependency jars into `target/`, so you don't need the Ivy cache

Many were using sbt-start-script with Heroku, sbt-native-packager has two tricky things on Heroku right now:

 1. Heroku sets `JAVA_OPTS` and not `java_opts`. See https://github.com/sbt/sbt-native-packager/issues/47 and https://github.com/sbt/sbt-native-packager/issues/48 ... for now you have to manually configure `java_opts` and not specify memory options, or hack sbt-native-packager.
 2. You need to hack the build pack to drop the Ivy cache, or your slug will be bloated or even exceed the max size.

Also of course you have to change your `Procfile` for the new name of the script.

## About this plugin (sbt-start-script)

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

To use the plugin with SBT 0.12.x:

    addSbtPlugin("com.typesafe.sbt" % "sbt-start-script" % "0.9.0")

You can place that code in `~/.sbt/plugins/build.sbt` to install the
plugin globally, or in `YOURPROJECT/project/plugins.sbt` to
install the plugin for your project.

To use with SBT 0.13.x:

    addSbtPlugin("com.typesafe.sbt" % "sbt-start-script" % "0.10.0")

Note: the global directory for 0.13.x is `~/.sbt/0.13` instead of `~/.sbt`.

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

## Key names

Note that all the keys (except `stage`) are in the
`SbtStartScript.StartScriptKeys` object, so the scala version of
the `start-script` key is
`SbtStartScript.StartScriptKeys.startScript`. This is the standard
convention for sbt plugins. Do an `import
SbtStartScript.StartScriptKeys._` if you want all the keys
unprefixed in your scope. Then, if you want to change a setting, you
can simply reference the key directly in your `build.sbt'.

For example, to change the filename of the generated script to 
something other than `target/start` (which is controlled by the
key `SbtStartScript.StartScriptKeys.startScriptName`), add the
following to `build.sbt` after the above import statement:

    startScriptName <<= target / "run"

There is also the possibility to define a list of arguments which is always
passed to the main class, prepended to the arguments specified when running the
start script:

    startScriptArgs := Seq("one arg", "another")

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
 - `StartScriptKeys.startScriptFile` did not match the string
   name of that settings `start-script-name` so now you should
   use `StartScriptKeys.startScriptName`

## License

sbt-start-script is open source software licensed under the
[Apache 2.0 License](http://www.apache.org/licenses/LICENSE-2.0.html).

## Contribution policy

Contributions via GitHub pull requests are gladly accepted from
their original author.  Before sending the pull request, please
agree to the Contributor License Agreement at
http://typesafe.com/contribute/cla (it takes 30 seconds; you use
your GitHub account to sign the agreement).
