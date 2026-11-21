# Librero - Inventario de libros

Sistema de inventario para una librería: registro de llegadas y salidas de libros,
inventario con buscador y orden, historial de movimientos, panel de totales y
exportación a CSV. Base de datos real: SQLite.

## Libros de ejemplo (base de datos inicial)

La base de datos se crea automáticamente con 2 libros de ejemplo:

1. **El retrato de Dorian Gray** — Editorial: Sudamericana — Autor: Oscar Wilde — Stock: 8 — Cajas: 2
2. **El principito** — Editorial: Alianza Editorial — Autor: Antoine de Saint-Exupéry — Stock: 3 — Cajas: 1

El libro de ejemplo 2 (El principito) tiene stock menor a 5, así se ve el aviso de poco stock en el inventario.

> Nota: la base de datos real (`base-de-datos/librero.db`) puede contener
> más libros registrados (no solo los libros de ejemplo). Los libros de ejemplo
> incluyen sus movimientos ENTRADA iniciales para que el histórico concuerde con
> el stock sembrado.

## Tecnologías

- **Backend**: Java 17+ (OpenJDK 27 en este equipo), Java SE API, `java.net.http.HttpServer`
  (servidor HTTP local) y la librería `sqlite-jdbc` para la base de datos real.
- **Frontend**: HTML, CSS y JavaScript (sin librerías externas), con diseño formal
  en tonos azules y claros: azul marino, azul institucional, gris azulado y blanco.
- **Base de datos**: SQLite — archivo `base-de-datos/librero.db`. Esquema:
  `base-de-datos/esquema.sql` (crea las tablas `libros` y `movimientos`).

## Estructura de la carpeta

```
Librero/
- README.md
- Ejecutar Librero.bat              (Windows)
- Ejecutar Librero.sh               (Linux/Mac)
- pom.xml
- src/main/java/com/librero/
  - LibreroApplication.java          (arranque: servidor + base de datos)
  - modelo/Libro.java                (entidad libro: isbn, titulo, editorial, autor, stock_actual, total_cajas, fecha_creacion, activo, vendidos_acumulados)
  - modelo/Movimiento.java           (entidad movimiento: id, isbn, tipo, cantidad, cajas, fecha, hora, nota)
  - repositorio/LibroRepositorio.java     (acceso a la tabla libros)
  - repositorio/MovimientoRepositorio.java (acceso a la tabla movimientos)
  - controlador/LibreroControlador.java    (recibe peticiones HTTP, respuesta JSON)
  - servicio/LibreroServicio.java         (lógica de negocio)
- src/main/resources/
  - application.properties
  - static/index.html
  - static/estilos.css
  - static/app.js
- base-de-datos/
  - librero.db
  - esquema.sql
- target/                           (compilación de Maven: classes + libs)
```

## Cómo ejecutar

### Windows

Doble clic en: `Ejecutar Librero.bat`

### Linux/Mac

```sh
cd "/home/arch_gv/Desktop/Librero"
sh "Ejecutar Librero.sh"
```

Ambos scripts hacen todo automáticamente: verifican Java y Maven, compilan con Maven
(`mvn -DskipTests package`), inician el servidor en el puerto 8080 y abren el navegador.

El servidor se queda corriendo: abre tu navegador en **http://localhost:8080**.

### Token de acceso (G5)

Por defecto no hace falta token. Si quieres limitar quién puede usar la API a
nadie que conozca la IP de tu red WiFi, pon `LIBRERO_TOKEN` como variable de
entorno antes de ejecutar `Ejecutar Librero.sh` / `Ejecutar Librero.bat` (o al
arrancar con Maven):

```sh
LIBRERO_TOKEN=mi-secreto sh "Ejecutar Librero.sh"
```

Todos los endpoints `/api/...` exigen la cabecera `X-Librero-Token` con ese
valor. La interfaz web pide el token al entrar (se guarda en `localStorage` del
navegador; si caduca, se pide de nuevo). No guardes el token real en los
archivos:

```sh
export LIBRERO_TOKEN=mi-secreto   # mantenlo solo en tu entorno de ejecución
```

### Cambiar el puerto (O7)

Si el puerto 8080 está ocupado:

```sh
LIBRERO_PORT=8081 sh "Ejecutar Librero.sh"   # ejecuta en http://localhost:8081
```

O directamente: `mvn -Dserver.port=8081 package` /
`java -Dserver.port=8081 -cp target/classes:target/libs/* com.librero.LibreroApplication`.

### Abrirlo desde otra computadora en la misma red WiFi

Pon la IP local de esta computadora (que aparece en la consola al arrancar el servidor
y en el pie de la página) en el navegador de la otra computadora, por ejemplo:

```
http://192.168.1.23:8080
```

Ejemplo real de la IP local en esta computadora: `http://192.168.100.15:8080`.

## Cómo funciona (UI)

1. **Registrar llegada** (`Registrar llegada`): ISBN (obligatorio, único), nombre del
   libro, editorial (obligatorias), autor (opcional), cantidad de libros que llegaron
   (obligatoria), cajas que llegaron (obligatorio), fecha (selector), hora (selector),
   nota (opcional). Si el ISBN ya existe, suma la cantidad al stock actual y las cajas
   al total de cajas; si no existe, crea el libro. Guarda un movimiento de tipo ENTRADA.

2. **Registrar salida / venta** (`Registrar salida`): ISBN (obligatorio; botón
   "Buscar libro" muestra título, editorial y stock actual), cantidad que se vendió
   (obligatoria), fecha, hora, nota. Resta el stock; **no permite quedar en negativo**
   (si se intentan vender más de los que hay, muestra un error con el stock disponible).
   Guarda un movimiento de tipo SALIDA.

3. **Inventario** (`Inventario`): tabla de libros con ISBN, título, editorial, autor,
   stock actual, total de cajas, última fecha de movimiento y botones de edición y
   eliminación. Filtros: buscar por ISBN, por título y por editorial; orden: primero
   los que tienen menos stock. Los libros con poco stock (< 5) tienen fila resaltada
   con un badge. Eliminar un libro es un **borrado lógico** (`activo=0`): desaparece del
   inventario pero **conserva sus movimientos y sus ventas acumuladas** en el histórico.

4. **Historial** (`Historial`): tabla de movimientos (fecha, hora, ISBN, título, tipo
   ENTRADA o SALIDA, cantidad, cajas para entradas, nota), más recientes primero.
   Filtros por ISBN, tipo y rango de fechas.

5. **Panel** (`Panel`): tarjetas con los totales reales de la base de datos:
   - Total de libros diferentes registrados
   - Total de libros en stock (suma de todos)
   - Total de cajas que han llegado
   - Total de libros vendidos (suma de salidas)
   - Libros con poco stock
   Y botones para exportar el inventario y el historial a CSV.

## API (REST)

El servidor expone una API simple que la interfaz usa:

| Método | Path | Descripción |
|---|---|---|
| GET | `/` | Interfaz web |
| GET | `/api/info` | Devuelve `{ip, port}` (IP local para entrar desde otra computadora) |
| GET | `/api/dashboard` | Totales del panel |
| POST | `/api/llegada` | Registra llegada de libros |
| POST | `/api/salida` | Registra salida / venta |
| GET | `/api/inventario?q_isbn=&q_titulo=&q_editorial=&orden=` | Lista libros con filtros y orden |
| GET | `/api/inventario/{isbn}` | Busca un libro por ISBN (título, editorial, stock actual) |
| PUT | `/api/inventario/{isbn}` | Edita título, editorial y/o autor |
| DELETE | `/api/inventario/{isbn}` | Elimina el libro (borrado lógico activo=0; los movimientos del historial se conservan) |
| GET | `/api/movimientos?tipo=&isbn=&desde=&hasta=` | Historial de movimientos con filtros |
| GET | `/api/libros/sugerir?q=` | Sugiere ISBNs/títulos para el buscador de salida |
| GET | `/api/exportar/inventario.csv` | CSV del inventario (descarga) |
| GET | `/api/exportar/movimientos.csv` | CSV del historial de movimientos (descarga) |

## Solución de problemas

- **Puerto 8080 ocupado**: verifica que otra instancia de Librero no se esté ejecutando
  (el propio servidor de memorias del proyecto anterior se movió a `OPENWEBUI_PORT`).
- **Errores al compilar**: ejecuta `mvn -DskipTests package` y revisa el log (`mvn.log`).
- **`target/` no existe o está vacío**: ejecuta `Ejecutar Librero.sh`/`.bat` (compila automáticamente).
- **`target/` estático o viejo**: `target/` y `target/libs/` son salida de la compilación
  con Maven: se regeneran con `mvn package`; puedes borrar `target/` a gana (el
  proyecto se vuelve a compilar).
- **Cambiar el umbral de "poco stock"**: edita `librero.umbral.poco-stock` en
  `src/main/resources/application.properties`.

## Verificación (pruebas realizadas)

- Llegada de libro nuevo → stock y cajas guardados en la DB.
- Llegada de libro existente → stock y cajas sumados.
- Venta → stock restado, movimiento SALIDA guardado; no se puede vender más que el stock.
- Historial ordenado más reciente primero, con filtros por tipo, ISBN y fechas.
- Panel de totales correcto frente a la base de datos (totales reales).
- Exportación de inventario y movimientos a CSV correcta.
- Eliminación de libro = borrado lógico (`activo=0`): conserva movimientos y ventas acumuladas.
- Validación de entradas con mensajes claros (ISBN, fechas, horas, cantidades).
