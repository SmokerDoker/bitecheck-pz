@echo off
rem BiteCheck launcher for Windows.
rem Uses the Java runtime that comes with Project Zomboid, so no separate Java install is needed.
setlocal EnableDelayedExpansion

set "JAR=%~dp0BiteCheck.jar"
set "JAVAW="

rem 1) Steam folder from the registry
for /f "tokens=2,*" %%a in ('reg query "HKCU\Software\Valve\Steam" /v SteamPath 2^>nul ^| find "SteamPath"') do set "STEAM=%%b"
if defined STEAM (
    set "STEAM=!STEAM:/=\!"
    call :try "!STEAM!\steamapps\common\ProjectZomboid"
    rem Other Steam libraries listed in libraryfolders.vdf
    if not defined JAVAW if exist "!STEAM!\steamapps\libraryfolders.vdf" (
        for /f "usebackq tokens=1,*" %%k in (`findstr /c:"\"path\"" "!STEAM!\steamapps\libraryfolders.vdf"`) do (
            if not defined JAVAW (
                set "LIB=%%~l"
                set "LIB=!LIB:\\=\!"
                call :try "!LIB!\steamapps\common\ProjectZomboid"
            )
        )
    )
)

rem 2) Usual install locations
if not defined JAVAW call :try "%ProgramFiles(x86)%\Steam\steamapps\common\ProjectZomboid"
if not defined JAVAW call :try "%ProgramFiles%\Steam\steamapps\common\ProjectZomboid"
for %%d in (C D E F G H) do if not defined JAVAW call :try "%%d:\SteamLibrary\steamapps\common\ProjectZomboid"

rem 3) Any Java on the PATH
if not defined JAVAW (
    where javaw >nul 2>nul && set "JAVAW=javaw"
)

if not defined JAVAW (
    echo BiteCheck could not find Project Zomboid's Java runtime or another Java 21+ installation.
    echo Install Project Zomboid through Steam, or install Java 21 or newer.
    pause
    exit /b 1
)

start "" "%JAVAW%" -jar "%JAR%" %*
exit /b 0

:try
if exist "%~1\jre64\bin\javaw.exe" set "JAVAW=%~1\jre64\bin\javaw.exe"
if not defined JAVAW if exist "%~1\jre\bin\javaw.exe" set "JAVAW=%~1\jre\bin\javaw.exe"
exit /b 0
