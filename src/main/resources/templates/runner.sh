#!/bin/sh
#

@SCRIPT_ROOT_CHECK@

@MAIN_CLASS_SETUP@

exec java $JAVA_OPTS -cp "@CLASSPATH@" "$MAINCLASS" "$@"
