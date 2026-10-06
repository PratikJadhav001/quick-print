@echo off
setlocal
cd /d "%~dp0"
if not exist "lib\mysql-connector-j.jar" (
  echo Missing lib\mysql-connector-j.jar
  echo Download MySQL Connector/J and place it in the lib folder with this exact name.
  pause
  exit /b 1
)
if not exist "out" mkdir out
javac -encoding UTF-8 -cp "lib\mysql-connector-j.jar" -d out src\Db.java src\Main.java
if errorlevel 1 (
  pause
  exit /b 1
)
java -cp "out;lib\mysql-connector-j.jar" Main config.properties frontend
endlocal
