@echo off
chcp 65001 >nul 2>&1
title Librero - Inventario de libros
cd /d "%~dp0"
cls

echo.
echo =============================================
echo                   LIBRERO
echo    Inventario de libros - usuario librería
echo =============================================
echo.

echo [1/5] Verificando Java...
java -version >nul 2>&1
if %errorlevel% neq 0 goto sin_java
echo   OK - Java presente
goto verificar_maven

:verificar_maven
echo [2/5] Verificando Maven...
mvn -version >nul 2>&1
if %errorlevel% neq 0 goto sin_maven
echo   OK - Maven presente
goto compilar

:sin_java
echo   ERROR: Java no encontrado. Instale Java 17 o superior (https://openjdk.org/) y vuelva a ejecutar.
goto fin

:sin_maven
echo   ERROR: Maven no encontrado. Instale Maven (https://maven.apache.org/) y vuelva a ejecutar.
goto fin

:compilar
echo [3/5] Compilando el proyecto con Maven...
mvn -q -DskipTests package >nul 2>&1
if %errorlevel% neq 0 (
  echo   ERROR: Fallo al compilar. Corriga los errores y vuelva a ejecutar.
  goto fin
)
echo   OK - Compilado

:iniciando
echo [4/5] Iniciando el servidor local en http://localhost:8080 ...
echo   Desde otra computadora en la misma red WiFi, use: http://IP:8080
echo   (La IP local se muestra en la consola y en el pie de la pagina).
echo.

:ejecutar
echo [5/5] Servidor corriendo. Abra en el navegador: http://localhost:8080
echo.
echo   Formularios: Registrar llegada, Registrar salida, Inventario, Historial, Panel.
java -cp "target\classes;target\libs\*" com.librero.LibreroApplication

:fin
echo.
echo Presione una tecla para cerrar...
pause >nul
