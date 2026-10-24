@echo off
setlocal
for %%I in ("%~dp0.") do set "MAVEN_PROJECTBASEDIR=%%~fI"
set "MAVEN_WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar"

if not exist "%MAVEN_WRAPPER_JAR%" (
    echo Maven Wrapper JAR is missing: %MAVEN_WRAPPER_JAR%
    exit /b 1
)

java "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" -classpath "%MAVEN_WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
endlocal
