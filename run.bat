@echo off
REM run.bat      runs the batch job against Azure Document Intelligence
REM run.bat ui   starts the demo web app on http://localhost:8080
REM Settings: ocr-batch\src\main\resources\application.properties, overridden by application-local.properties
setlocal
cd /d "%~dp0"
set JOB_JAR=ocr-batch\target\vision-ocr-batch.jar
set UI_JAR=ocr-ui\target\vision-ocr-ui.jar
if /i "%~1"=="ui" goto ui
if not exist "%JOB_JAR%" (
  call mvn -q -DskipTests package || exit /b 1
)
java -Dfile.encoding=UTF-8 -Dconfig.file=application-local.properties -jar "%JOB_JAR%" job-context.xml docExtractionJob -next
exit /b %ERRORLEVEL%
:ui
if not exist "%UI_JAR%" (
  call mvn -q -DskipTests package || exit /b 1
)
java -Dfile.encoding=UTF-8 -Dconfig.file=application-local.properties -jar "%UI_JAR%"
exit /b %ERRORLEVEL%
