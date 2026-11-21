# Librero - Inventario de libros

Sistema de inventario para una librería: registro de llegadas y salidas de libros,
inventario con buscador y orden, precios de venta, historial de movimientos, panel de
totales y exportación a CSV. Acceso con usuario y contraseña, también desde otras
computadoras de la misma red WiFi.

## Tecnologías

- **Backend**: Java 17+, Spring Boot 3.5 (Web, JDBC, Validation, Security).
- **Base de datos**: SQLite, en el archivo `base-de-datos/librero.db` (se crea solo).
- **Frontend**: HTML, CSS y JavaScript sin librerías externas.

## Cómo ejecutar

Requisitos: Java 17 o superior y Maven.

- **Windows**: doble clic en `Ejecutar Librero.bat`.
- **Linux/Mac**: `sh "Ejecutar Librero.sh"`.

El script compila el proyecto, inicia el servidor en **http://localhost:8080** y abre el
navegador.

### Primer arranque: contraseña

La primera vez, Librero genera una contraseña aleatoria y la muestra **una sola vez** en
la consola:

```
  PRIMER ARRANQUE: se generó una contraseña de acceso.
    Usuario:    admin
    Contraseña: ZJQEGRAmQcxA
```

Anótela. Para elegir otra contraseña (o si se perdió), inicie Librero con la variable de
entorno `LIBRERO_PASSWORD`. Esa contraseña queda guardada (solo su hash BCrypt) para los
siguientes arranques:

```bat
:: Windows (cmd)
set LIBRERO_PASSWORD=mi-clave-segura
"Ejecutar Librero.bat"
```

```sh
# Linux/Mac
LIBRERO_PASSWORD=mi-clave-segura sh "Ejecutar Librero.sh"
```

### Configuración (variables de entorno)

| Variable | Por defecto | Para qué |
|---|---|---|
| `LIBRERO_PORT` | `8080` | Puerto HTTP |
| `LIBRERO_DATOS` | `base-de-datos` | Carpeta donde se guarda `librero.db` |
| `LIBRERO_USUARIO` | `admin` | Nombre de usuario |
| `LIBRERO_PASSWORD` | *(generada)* | Contraseña (ver arriba) |
| `LIBRERO_ABRIR_NAVEGADOR` | `true` | `false` para no abrir el navegador al iniciar |

El umbral de "poco stock" se cambia en `src/main/resources/application.properties`
(`librero.umbral-poco-stock`).

### Desde otra computadora de la misma red

La consola y el pie de la página muestran la IP local, por ejemplo
`http://192.168.1.23:8080`. Ábrala en el navegador de la otra computadora e inicie sesión.

> La conexión es HTTP sin cifrar: úsela solo en una red de confianza (la WiFi de la
> tienda con contraseña). No exponga el puerto a internet.

## Estructura

```
src/main/java/com/librero/
  LibreroApplication.java         arranque de Spring Boot
  config/
    SeguridadConfig.java          login, CSRF, cabeceras de seguridad
    CredencialesServicio.java     usuario/contraseña (hash BCrypt en la tabla metadata)
    IntentosLogin.java            bloqueo de 5 min tras 5 intentos fallidos por IP
    LimiteCuerpoFiltro.java       rechaza peticiones de más de 64 KB
    BaseDeDatosInicializador.java esquema, migraciones y datos de ejemplo
    AlIniciar.java                muestra la IP y abre el navegador
  controlador/
    LibreroControlador.java       API REST (/api/...)
    ManejadorErrores.java         errores -> JSON {"ok": false, "error": "..."}
  servicio/LibreroServicio.java   lógica de negocio (transaccional)
  repositorio/                    acceso a SQLite con JdbcTemplate
  modelo/, dto/                   registros de datos
src/main/resources/
  application.properties
  db/esquema.sql, db/datos-ejemplo.sql
  static/                         interfaz web (index.html, login.html, app.js, estilos.css)
src/test/java/com/librero/        pruebas (servicio + seguridad/API con MockMvc)
```

## Funciones

1. **Registrar llegada**: ISBN, título y editorial (obligatorios para libros nuevos), autor,
   cantidad, cajas, precio de venta (opcional), fecha, hora y nota. Si el ISBN ya existe,
   suma stock y cajas (y reactiva el libro si estaba eliminado).
2. **Registrar salida (venta)**: busca el libro, muestra precio y total de la venta, y resta
   el stock. **Nunca deja el stock en negativo**, ni con ventas simultáneas.
3. **Inventario**: filtros por ISBN, título y editorial; orden por stock; edición de
   título, editorial, autor y precio; eliminación lógica (conserva el historial y las ventas).
4. **Historial**: movimientos con filtros por tipo, ISBN y rango de fechas.
5. **Consultar libro**: ficha con datos, precio, última llegada y última venta.
6. **Panel**: totales y libros con poco stock; exportación a CSV (compatible con Excel).

## API

Todas las rutas `/api/...` exigen sesión iniciada (si no hay sesión, responden 401). Las
peticiones que modifican datos exigen además la cabecera `X-XSRF-TOKEN` con el valor de la
cookie `XSRF-TOKEN`.

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/info` | IP local, puerto y usuario actual |
| GET | `/api/dashboard` | Totales del panel |
| POST | `/api/llegada` | Registra una llegada |
| POST | `/api/salida` | Registra una venta |
| GET | `/api/inventario?q_isbn=&q_titulo=&q_editorial=&orden=` | Libros activos |
| GET / PUT / DELETE | `/api/inventario/{isbn}` | Ver / editar / eliminar un libro |
| GET | `/api/movimientos?tipo=&isbn=&desde=&hasta=` | Historial |
| GET | `/api/libros/sugerir?q=` | Autocompletado |
| GET | `/api/libros/{isbn}/ficha` | Ficha del libro |
| GET | `/api/exportar/inventario.csv`, `/api/exportar/movimientos.csv` | Descargas CSV |

## Pruebas

```sh
mvn test
```

## Actualizar desde la versión 1

La base de datos existente se migra sola al iniciar: el precio pasa de `REAL` a centavos
(`precio_venta_centavos`) y se elimina un índice redundante. **Haga una copia de
`base-de-datos/librero.db` antes del primer arranque**, por precaución. El antiguo
`LIBRERO_TOKEN` ya no se usa: ahora se accede con usuario y contraseña.

La base de datos ya **no se guarda en git** (`.gitignore`): contiene datos reales del
negocio. Haga sus copias de seguridad aparte.
