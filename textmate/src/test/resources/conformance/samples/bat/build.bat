@echo off
setlocal enabledelayedexpansion

rem Builds Leaf on Windows.
set "JAVA_HOME=%ProgramFiles%\Java\jdk-25"
set COUNT=0

if not exist gradlew.bat (
    echo gradlew.bat not found 1>&2
    exit /b 1
)

for %%T in (build test) do (
    call gradlew.bat %%T --no-daemon || goto :failed
    set /a COUNT+=1
)
echo Ran !COUNT! tasks
goto :eof

:failed
echo Build failed with %ERRORLEVEL%
:: double-colon comment
exit /b %ERRORLEVEL%
