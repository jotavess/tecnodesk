@echo off
setlocal
cd /d "%~dp0"

echo ========================================
echo   TecnoDesk - inicializador
echo ========================================
echo.

where java >nul 2>nul
if errorlevel 1 (
  echo ERRO: Java nao foi encontrado. Instale o JDK 17 ou superior.
  echo Download: https://adoptium.net/
  pause
  exit /b 1
)

where mvn >nul 2>nul
if errorlevel 1 (
  echo ERRO: Maven nao foi encontrado.
  echo Instale o Apache Maven e configure-o no PATH, ou execute os comandos do README no terminal do VS Code.
  echo Download: https://maven.apache.org/download.cgi
  pause
  exit /b 1
)

echo Compilando o projeto; na primeira vez, as bibliotecas serao baixadas...
call mvn clean package
if errorlevel 1 (
  echo.
  echo ERRO: a compilacao falhou. Confira a mensagem acima.
  pause
  exit /b 1
)

echo.
echo Abrindo o sistema em http://localhost:8080
java -jar target\cadastro-clientes-8.0.0.jar
pause
