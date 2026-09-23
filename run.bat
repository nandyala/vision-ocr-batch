@echo off
REM Runs the batch job against Azure Document Intelligence.
REM Settings: src\main\resources\application.properties, overridden by application-local.properties
setlocal
cd /d "%~dp0"
set JAR=target\vision-ocr-batch.jar
if not exist "%JAR%" (
  call mvn -q -DskipTests package || exit /b 1
)
java -Dfile.encoding=UTF-8 -Dconfig.file=application-local.properties -jar "%JAR%" job-context.xml docExtractionJob -next
exit /b %ERRORLEVEL%
