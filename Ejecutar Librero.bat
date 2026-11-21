@echo off
chcp 65001 >nul 2>&1
title Librero - Inventario de libros
cd /d "%~dp0"
cls

echo.
echo =============================================
echo                   LIBRERO
echo    Inventario de libros - Spring Boot
echo =============================================
echo.

echo [1/3] Verificando Java y Maven...
java -version >nul 2>&1
if %errorlevel% neq 0 (
  echo   ERROR: Java no encontrado. Instale Java 17 o superior ^(https://adoptium.net/^).
  goto fin
)
mvn -version >nul 2>&1
if %errorlevel% neq 0 (
  echo   ERROR: Maven no encontrado. Instale Maven ^(https://maven.apache.org/^).
  goto fin
)
echo   OK

echo [2/3] Compilando...
call mvn -q -DskipTests package
if %errorlevel% neq 0 (
  echo.
  echo   ERROR: fallo al compilar. Revise los mensajes de arriba.
  goto fin
)
echo   OK

echo [3/3] Iniciando el servidor en http://localhost:8080 ...
echo   - El navegador se abrira solo.
echo   - La primera vez se muestra aqui la contrasena de acceso: anotela.
echo   - Para salir: Ctrl+C
echo.
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar target\librero.jar

:fin
echo.
echo Presione una tecla para cerrar...
pause >nul
