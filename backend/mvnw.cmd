@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    https://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@REM ----------------------------------------------------------------------------
@REM Maven Start Up Batch script
@REM
@REM Required ENV vars:
@REM JAVA_HOME - location of a JDK home dir
@REM
@REM Optional ENV vars
@REM M2_HOME - location of maven2's installed home dir
@REM MAVEN_BATCH_ECHO - set to 'on' to enable the echoing of the batch commands
@REM MAVEN_BATCH_PAUSE - set to 'on' to wait for a keystroke before ending
@REM MAVEN_OPTS - parameters passed to the Java VM when running Maven
@REM     e.g. to debug Maven itself, use
@REM set MAVEN_OPTS=-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8000
@REM MAVEN_SKIP_RC - flag to disable loading of mavenrc files
@REM ----------------------------------------------------------------------------

@REM Begin all REM lines with '@' in case MAVEN_BATCH_ECHO is 'on'
@echo off
@REM set title of command window
title %0
@REM enable echoing by setting MAVEN_BATCH_ECHO to 'on'
@if "%MAVEN_BATCH_ECHO%" == "on"  echo %MAVEN_BATCH_ECHO%

@REM set %HOME% to equivalent of $HOME
if "%HOME%" == "" (set "HOME=%HOMEDRIVE%%HOMEPATH%")

@REM Execute a user defined script before this one
if not "%MAVEN_SKIP_RC%" == "" goto skipRcPre
@REM check for pre script, once with legacy .bat ending and once with .cmd ending
if exist "%USERPROFILE%\mavenrc_pre.bat" call "%USERPROFILE%\mavenrc_pre.bat" 2>nul
if exist "%USERPROFILE%\mavenrc_pre.cmd" call "%USERPROFILE%\mavenrc_pre.cmd" 2>nul
:skipRcPre

@setlocal

set LOCAL_JAVA_VERSION=%JAVA_HOME%
if "%LOCAL_JAVA_VERSION%" == "" (
    where java >nul 2>nul
    if %ERRORLEVEL% equ 0 (
        for /f "tokens=*" %%i in ('where java') do set "JAVA_CMD=%%i"
        for /f "tokens=3" %%j in ('"%JAVA_CMD%" -version 2^>^&1 ^| findstr /i "version"') do set LOCAL_JAVA_VERSION=%%j
    )
)

set MAVEN_JAVA_EXE="%JAVA_HOME%/bin/java.exe"
if "%JAVA_HOME%" == "" set MAVEN_JAVA_EXE=java

set MAVEN_HOME=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.6-bin\*

@REM Download Maven Wrapper if not present
if not exist "%MAVEN_HOME%" (
    if not exist "%USERPROFILE%\.m2\wrapper\dists\" mkdir "%USERPROFILE%\.m2\wrapper\dists\"
    echo Downloading Maven...
    powershell -Command "Invoke-WebRequest -Uri https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip -OutFile %TEMP%\maven.zip"
    powershell -Command "Expand-Archive -Path %TEMP%\maven.zip -DestinationPath %USERPROFILE%\.m2\wrapper\dists\ -Force"
)

@REM ==== START VALIDATION ====
if not "%JAVA_HOME%" == "" goto OkJHome
echo.
echo Error: JAVA_HOME not found in your environment. >&2
echo Please set the JAVA_HOME variable in your environment to match the >&2
echo location of your Java installation. >&2
echo.
goto error

:OkJHome
if exist "%JAVA_HOME%\bin\java.exe" goto init

echo.
echo Error: JAVA_HOME is set to an invalid directory. >&2
echo JAVA_HOME = "%JAVA_HOME%" >&2
echo Please set the JAVA_HOME variable in your environment to match the >&2
echo location of your Java installation. >&2
echo.
goto error

:init
@REM Get the directory of the project and set as MAVEN_PROJECTBASEDIR
set MAVEN_PROJECTBASEDIR=%CD%

@REM Find the mvnw.cmd directory
set WRAPPER_DIR=%~dp0
if "%WRAPPER_DIR%" == "" set WRAPPER_DIR=.\

@REM Resolve maven installation
for /d %%i in ("%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.6*") do set MAVEN_HOME=%%i
if "%MAVEN_HOME%" == "" (
    echo Maven installation not found. Trying to use embedded launcher.
    set "MAVEN_CMD=mvn"
) else (
    set "MAVEN_CMD=%MAVEN_HOME%\bin\mvn.cmd"
)

"%MAVEN_CMD%" %*
goto end

:error
endlocal
echo Error occurred while running Maven.
pause

:end
endlocal
