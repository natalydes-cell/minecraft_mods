@echo off
REM Downloads the companion mods from their authors' own files on Modrinth,
REM pinned to exact versions and checked by SHA-512.
REM
REM Nothing here is bundled: these are other people's mods under copyleft
REM licences, so this fetches them from source rather than redistributing them.
REM
REM All of them are OPTIONAL. RealEarth runs without any of them.
REM
REM   Better Clouds  - volumetric clouds that follow the real weather
REM   YACL           - required by Better Clouds, it will not load without it
REM   Cold Sweat     - body temperature driven by the real climate
REM   Distant Horizons - real Earth relief out to the horizon

setlocal
set MODS=%~dp0..\mods
echo Downloading into %MODS%
echo.
java -jar "%~dp0realearth-importer.jar" --mods "%MODS%" --manifest "%~dp0companion-mods.json" %*
echo.
pause
endlocal
