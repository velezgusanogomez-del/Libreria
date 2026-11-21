#!/bin/sh
# Librero - compila con Maven e inicia el servidor (Spring Boot) en http://localhost:8080.
# El navegador se abre solo. La primera vez se muestra en consola la contraseña de acceso.

cd "$(dirname "$0")" || exit 1

echo "============================================="
echo "                    LIBRERO"
echo "    Inventario de libros - Spring Boot"
echo "============================================="
echo

echo "[1/3] Verificando Java y Maven..."
java -version >/dev/null 2>&1 || { echo "  ERROR: Java no encontrado (se necesita Java 17 o superior)."; exit 1; }
mvn -version >/dev/null 2>&1 || { echo "  ERROR: Maven no encontrado."; exit 1; }
echo "  OK"

echo "[2/3] Compilando..."
mvn -q -DskipTests package || { echo "  ERROR: fallo al compilar. Revise los mensajes de arriba."; exit 1; }
echo "  OK"

echo "[3/3] Iniciando el servidor en http://localhost:8080 (Ctrl+C para salir)..."
echo
exec java -jar target/librero.jar
