@echo off
REM =====================================================================
REM  Installs the Portal Monitor AGENT as an auto-start Windows service.
REM
REM  Run this from an ELEVATED command prompt (Run as administrator).
REM
REM  Before running:
REM    1. Install Java 17 or newer (Temurin JRE is fine) and make sure
REM       `java -version` works.
REM    2. Put portal-monitor.jar and application.properties in the folder
REM       set as APPDIR below.
REM    3. Download NSSM from https://nssm.cc/download and set NSSM below
REM       to the win64\nssm.exe you unzipped.
REM    4. Edit application.properties: agent.serverUrl and agent.token.
REM =====================================================================

setlocal

REM ---- edit these four lines ----
set "SERVICE=PortalMonitorAgent"
set "APPDIR=C:\portal-monitor"
set "NSSM=C:\tools\nssm\win64\nssm.exe"
set "JAVA=C:\Program Files\Eclipse Adoptium\jdk-17\bin\java.exe"
REM -------------------------------

if not exist "%NSSM%" (
  echo ERROR: nssm.exe not found at %NSSM%
  echo Download it from https://nssm.cc/download and update the NSSM line.
  exit /b 1
)
if not exist "%JAVA%" (
  echo ERROR: java.exe not found at %JAVA%
  echo Install Java 17+ and update the JAVA line. Check with: where java
  exit /b 1
)
if not exist "%APPDIR%\portal-monitor.jar" (
  echo ERROR: %APPDIR%\portal-monitor.jar not found.
  exit /b 1
)
if not exist "%APPDIR%\application.properties" (
  echo ERROR: %APPDIR%\application.properties not found.
  exit /b 1
)

if not exist "%APPDIR%\logs" mkdir "%APPDIR%\logs"

echo Installing service %SERVICE% ...
"%NSSM%" install %SERVICE% "%JAVA%" "-jar %APPDIR%\portal-monitor.jar --mode agent --config %APPDIR%\application.properties"
"%NSSM%" set %SERVICE% AppDirectory "%APPDIR%"
"%NSSM%" set %SERVICE% DisplayName "Portal Monitor Agent"
"%NSSM%" set %SERVICE% Description "Reports Docker container status to the Portal Monitor server"
"%NSSM%" set %SERVICE% Start SERVICE_AUTO_START

REM Roll the log daily so it cannot grow without limit.
"%NSSM%" set %SERVICE% AppStdout "%APPDIR%\logs\agent.log"
"%NSSM%" set %SERVICE% AppStderr "%APPDIR%\logs\agent.log"
"%NSSM%" set %SERVICE% AppRotateFiles 1
"%NSSM%" set %SERVICE% AppRotateOnline 1
"%NSSM%" set %SERVICE% AppRotateBytes 10485760

REM Restart on failure, after a short pause.
"%NSSM%" set %SERVICE% AppExit Default Restart
"%NSSM%" set %SERVICE% AppRestartDelay 10000

REM ---------------------------------------------------------------------
REM  IMPORTANT: the service account must be able to run `docker`.
REM
REM  LocalSystem (the NSSM default) usually CANNOT talk to Docker Desktop,
REM  because Docker Desktop runs per-user. Two options:
REM
REM   a) Run the service as the user who is logged into Docker Desktop:
REM        nssm set %SERVICE% ObjectName ".\YourWindowsUser" "YourPassword"
REM      That user must be in the local "docker-users" group.
REM
REM   b) Switch Docker Desktop on to start with Windows and expose the
REM      engine, or install Docker Engine as a Windows service instead.
REM
REM  Verify before starting the service, as that account:
REM      java -jar %APPDIR%\portal-monitor.jar --test-docker
REM ---------------------------------------------------------------------

echo.
echo Service installed but NOT started.
echo.
echo 1) Confirm docker works for this account:
echo      java -jar %APPDIR%\portal-monitor.jar --test-docker
echo 2) Confirm one report reaches the server:
echo      java -jar %APPDIR%\portal-monitor.jar --mode agent --once
echo 3) If the account needs changing, see the notes in this script, then:
echo      "%NSSM%" start %SERVICE%
echo.
echo Useful later:
echo      "%NSSM%" status  %SERVICE%
echo      "%NSSM%" restart %SERVICE%
echo      "%NSSM%" edit    %SERVICE%
echo      "%NSSM%" remove  %SERVICE% confirm
echo.

endlocal
