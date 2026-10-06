@echo off
setlocal
set "JAVACMD=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVACMD=%JAVA_HOME%\bin\java.exe"
"%JAVACMD%" "%~dp0VersionCheck.java" bump
