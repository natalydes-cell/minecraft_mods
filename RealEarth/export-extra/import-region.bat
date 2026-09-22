@echo off
REM Imports elevation for one region. Everything else is global and already included.
REM
REM Usage:  import-region.bat minLat maxLat minLon maxLon
REM Example (Europe):        import-region.bat 35 72 -12 45
REM Example (Nepal):         import-region.bat 27 30 85 88
REM Example (North America): import-region.bat 15 72 -170 -52
REM
REM Run it from the folder this file is in. The config folder must be the one your
REM Minecraft instance actually uses.

setlocal
if "%~4"=="" (
  echo Usage: import-region.bat minLat maxLat minLon maxLon
  echo Example: import-region.bat 35 72 -12 45
  exit /b 2
)
set CONFIG=%~dp0..\config\realearth
java -Xmx1G -jar "%~dp0realearth-importer.jar" "%CONFIG%" --bbox %1,%2,%3,%4 --only elevation
endlocal
