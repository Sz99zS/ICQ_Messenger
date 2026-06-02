@echo off
rem ============================================================
rem  Run the ICQ CLIENT (JavaFX, org.example.client.ChatClientApp)
rem  Sets JAVA_HOME to JDK 26 and runs it via the Maven Wrapper.
rem  No global system configuration is required.
rem  NOTE: keep this file ASCII-only. cmd.exe parses batch files
rem  by codepage, and non-ASCII text here breaks parsing.
rem ============================================================
setlocal

rem JDK 26 used by IntelliJ (not on the system PATH).
rem If you move the JDK, update this path.
set "JAVA_HOME=C:\Users\User\.jdks\openjdk-26"

rem UTF-8 console so Cyrillic program output is readable.
chcp 65001 >nul

rem %~dp0 is this script's folder, so it works from anywhere.
cd /d "%~dp0"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK 26 not found at: %JAVA_HOME%
    echo Edit the JAVA_HOME line at the top of run-client.cmd
    exit /b 1
)

rem %* forwards any extra Maven arguments.
call "%~dp0mvnw.cmd" -B javafx:run %*

endlocal
