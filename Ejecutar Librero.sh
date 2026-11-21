#!/bin/sh
# Librero - Iniciador del sistema de inventario de libros.
# Ejecuta: verifica Java/Maven, compila con Maven, inicia el servidor local
# en http://localhost:8080 y abre el navegador. Mantiene el servidor corriendo.

cd "$(dirname "$0")" || exit 1

echo "============================================="
echo "                    LIBRERO"
echo "   Inventario de libros - usuario librería"
echo "============================================="
echo

echo "[1/5] Verificando Java..."
java -version >/dev/null 2>&1 || { echo "  ERROR: Java no encontrado (se necesita Java 17 o superior). Instale Java y vuelva a ejecutar."; exit 1; }
echo "  OK - Java presente:"
java -version 2>&1 | head -n 1

echo "[2/5] Verificando Maven..."
mvn -version >/dev/null 2>&1 || { echo "  ERROR: Maven no encontrado. Instale Maven y vuelva a ejecutar."; exit 1; }
echo "  OK - Maven presente"

echo "[3/5] Compilando el proyecto con Maven..."
mvn -q -DskipTests package || { echo "  ERROR: Fallo al compilar. Corrige los errores y vuelva a ejecutar."; exit 1; }
echo "  OK - Compilado"

echo "[4/5] Iniciando el servidor local en http://localhost:8080 ..."
IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
[ -z "$IP" ] && IP="127.0.0.1"
echo "  IP local para entrar desde otra computadora en la misma red WiFi: http://$IP:8080"

echo "[5/5] Iniciando el servidor y abriendo el navegador..."
echo
echo "  Formularios: Registrar llegada, Registrar salida, Inventario, Historial, Panel."

# Servidor corriendo en segundo plano, espera al puerto, abre navegador.
java -cp "target/classes:target/libs/*" com.librero.LibreroApplication &
SERVIDOR_PID=$!
for i in $(seq 1 40); do
  if curl -s -m 2 http://localhost:8080/api/info >/dev/null 2>&1; then break; fi
  sleep 0.5
done
echo "  URL: http://localhost:8080"
xdg-open "http://localhost:8080" >/dev/null 2>&1 || true
echo "Servidor corriendo (pid $SERVIDOR_PID). Abrió el navegador en http://localhost:8080"
wait $SERVIDOR_PID
