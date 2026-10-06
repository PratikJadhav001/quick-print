#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
if [ ! -f "lib/mysql-connector-j.jar" ]; then
  echo "Missing lib/mysql-connector-j.jar. Download MySQL Connector/J and place it there."
  exit 1
fi
mkdir -p out
javac -encoding UTF-8 -cp "lib/mysql-connector-j.jar" -d out src/Db.java src/Main.java
java -cp "out:lib/mysql-connector-j.jar" Main config.properties frontend
