@echo off
setlocal EnableDelayedExpansion
rem Per-clone setup. Usage: setup-hooks.cmd [/y]   (/y = do not ask for confirmation)
rem Same existing-hook check as install.ps1: Git uses ONE hooks folder, so hooks in the
rem folder used today (.git\hooks or a global core.hooksPath) stop running once we switch.

set "ROOT="
for /f "delims=" %%i in ('git -C "%~dp0.." rev-parse --show-toplevel 2^>nul') do set "ROOT=%%i"
if not defined ROOT (
  echo This is not a Git repository, or git is not on PATH.
  exit /b 1
)
set "ROOT=!ROOT:/=\!"

set "LOCALP="
set "EFFP="
set "OLDDIR="
for /f "delims=" %%i in ('git -C "%~dp0.." config --local core.hooksPath 2^>nul') do set "LOCALP=%%i"
for /f "delims=" %%i in ('git -C "%~dp0.." config core.hooksPath 2^>nul') do set "EFFP=%%i"

if defined LOCALP (
  if /i not "!LOCALP!"==".githooks" set "OLDDIR=!LOCALP!"
) else if not defined EFFP (
  for /f "delims=" %%i in ('git -C "%~dp0.." rev-parse --git-path hooks 2^>nul') do set "OLDDIR=%%i"
) else if /i not "!EFFP!"==".githooks" (
  set "OLDDIR=!EFFP!"
)

set "FULL="
set "LOST="
if defined OLDDIR (
  set "OLDDIR=!OLDDIR:/=\!"
  set "FULL=!OLDDIR!"
  if not "!OLDDIR:~1,1!"==":" set "FULL=!ROOT!\!OLDDIR!"
  for %%f in ("!FULL!\*") do if /i not "%%~xf"==".sample" set "LOST=!LOST! %%~nxf"
)

if defined LOST (
  echo.
  echo Existing Git hooks would STOP running in this clone:
  echo   folder: !FULL!
  echo   hooks:  !LOST!
  echo Git can use only one hooks folder. Your hook files are not touched.
  if /i not "%~1"=="/y" (
    set "ANS="
    set /p "ANS=Enable Version Guard anyway? [y/N] "
    if /i not "!ANS!"=="y" (
      echo Nothing was changed.
      exit /b 1
    )
  )
)

git -C "%~dp0.." config --local core.hooksPath .githooks
git -C "%~dp0.." config --local push.followTags true
if defined OLDDIR if not defined LOST if defined EFFP if not defined LOCALP echo Note: a global core.hooksPath is set. In this clone it is now overridden.
echo Hooks enabled for this repository (core.hooksPath, push.followTags).
exit /b 0
