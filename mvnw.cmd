@echo off
setlocal
set BASE_DIR=%~dp0
set PROPS=%BASE_DIR%.mvn\wrapper\maven-wrapper.properties
for /f "tokens=1,* delims==" %%A in (%PROPS%) do if "%%A"=="distributionUrl" set DIST_URL=%%B
set MAVEN_HOME=%BASE_DIR%.mvn\wrapper\apache-maven
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing '%DIST_URL%' -OutFile '%BASE_DIR%.mvn\wrapper\maven.zip'"
  powershell -NoProfile -Command "Expand-Archive -Force '%BASE_DIR%.mvn\wrapper\maven.zip' '%BASE_DIR%.mvn\wrapper'"
  for /d %%D in ("%BASE_DIR%.mvn\wrapper\apache-maven-*") do move "%%D" "%MAVEN_HOME%"
  del "%BASE_DIR%.mvn\wrapper\maven.zip"
)
call "%MAVEN_HOME%\bin\mvn.cmd" %*
