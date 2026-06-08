#!/bin/sh
# ----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------

# ----------------------------------------------------------------------------
# Apache Maven Wrapper startup batch script, version 3.3.2
# ----------------------------------------------------------------------------

# Required ENV vars:
# ------------------
#   JAVA_HOME - location of a JDK home dir

# Optional ENV vars
# -----------------
#   M2_HOME - location of maven2's installed home dir
#   MAVEN_OPTS - parameters passed to the Java VM when running Maven
#   MAVEN_SKIP_RC - flag to disable loading of mavenrc files
# ----------------------------------------------------------------------------

if [ -z "$MAVEN_SKIP_RC" ]; then
  if [ -f /etc/mavenrc ]; then
    . /etc/mavenrc
  fi
  if [ -f "$HOME/.mavenrc" ]; then
    . "$HOME/.mavenrc"
  fi
fi

# OS specific support
cygwin=false
mingw=false
case "$(uname)" in
CYGWIN*) cygwin=true ;;
MINGW*) mingw=true ;;
esac

# Use Java from JAVA_HOME
if [ -z "$JAVA_HOME" ]; then
  if [ -e /usr/libexec/java_home ]; then
    JAVA_HOME="$(/usr/libexec/java_home)"
  else
    echo "Error: JAVA_HOME is not set and no Java installation found." >&2
    exit 1
  fi
fi

JAVACMD="$JAVA_HOME/bin/java"

# Resolve links
if [ -L "$0" ]; then
  PRG="$0"
  while [ -h "$PRG" ]; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
      PRG="$link"
    else
      PRG="$(dirname "$PRG")/$link"
    fi
  done
  SAVED="$(pwd)"
  cd "$(dirname "$PRG")/"
  MAVEN_PROJECTBASEDIR="$(pwd -P)"
  cd "$SAVED"
else
  MAVEN_PROJECTBASEDIR="$(dirname "$0")"
fi

WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain

# Attempt to set APP_HOME
if [ -z "$MAVEN_BASEDIR" ]; then
  MAVEN_BASEDIR="$MAVEN_PROJECTBASEDIR"
fi

# Determine the Java command to use to start the JVM
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
fi

exec "$JAVACMD" \
  $MAVEN_OPTS \
  $MAVEN_DEBUG_OPTS \
  -classpath "$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar" \
  "-Dmaven.multiModuleProjectDirectory=$MAVEN_PROJECTBASEDIR" \
  ${WRAPPER_LAUNCHER} "$@"
