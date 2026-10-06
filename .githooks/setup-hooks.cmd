@echo off
setlocal
git -C "%~dp0.." config core.hooksPath .githooks
git -C "%~dp0.." config push.followTags true
echo Hooks enabled for this repository (core.hooksPath, push.followTags).
