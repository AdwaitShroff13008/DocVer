@echo off
if not exist "lib\sqlite-jdbc.jar" (
    echo Downloading SQLite JDBC Driver...
    powershell -Command "$ProgressPreference = 'SilentlyContinue'; Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.41.2.1/sqlite-jdbc-3.41.2.1.jar' -OutFile 'lib\sqlite-jdbc.jar' -UseBasicParsing"
)
if not exist "bin" mkdir "bin"

echo Compiling Java source files...
javac -cp "lib/*" -d bin src\*.java

if %errorlevel% equ 0 (
    echo Starting the server...
    java -cp "bin;lib/*" Main
) else (
    echo Compilation failed.
)
pause
