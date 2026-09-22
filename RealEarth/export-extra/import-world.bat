@echo off
REM Imports the whole planet's elevation: 272 tiles, about 6 GB downloaded.
REM Expect this to take hours. It resumes if interrupted - just run it again.

setlocal
set CONFIG=%~dp0..\config\realearth
echo This downloads about 6 GB. Press Ctrl+C now to cancel, or
pause
java -Xmx1G -jar "%~dp0realearth-importer.jar" "%CONFIG%" --only elevation
endlocal
