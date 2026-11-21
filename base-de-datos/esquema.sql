-- Librero: esquema de la base de datos de inventario de libros.
-- Base de datos real: SQLite (archivo base-de-datos/librero.db).
-- Solo DDL: los datos de ejemplo estan en datos-ejemplo.sql (G4).

CREATE TABLE IF NOT EXISTS libros (
    isbn                TEXT PRIMARY KEY,
    titulo              TEXT NOT NULL,
    editorial           TEXT NOT NULL,
    autor               TEXT,
    stock_actual        INTEGER NOT NULL DEFAULT 0,
    total_cajas         INTEGER NOT NULL DEFAULT 0,
    fecha_creacion      TEXT NOT NULL,
    activo              INTEGER NOT NULL DEFAULT 1,
    vendidos_acumulados INTEGER NOT NULL DEFAULT 0,
    precio_venta REAL
);

CREATE TABLE IF NOT EXISTS movimientos (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    isbn        TEXT NOT NULL REFERENCES libros(isbn) ON DELETE CASCADE,
    tipo        TEXT NOT NULL CHECK (tipo IN ('ENTRADA', 'SALIDA')),
    cantidad    INTEGER NOT NULL CHECK (cantidad > 0),
    cajas       INTEGER,
    fecha       TEXT NOT NULL,
    hora        TEXT,
    nota        TEXT
);

CREATE TABLE IF NOT EXISTS metadata (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
INSERT OR IGNORE INTO metadata(key, value) VALUES ('seed_aplicado', '0');

CREATE INDEX IF NOT EXISTS idx_libros_isbn ON libros(isbn);
CREATE INDEX IF NOT EXISTS idx_libros_titulo ON libros(titulo);
CREATE INDEX IF NOT EXISTS idx_libros_editorial ON libros(editorial);
CREATE INDEX IF NOT EXISTS idx_movimientos_isbn ON movimientos(isbn);
CREATE INDEX IF NOT EXISTS idx_movimientos_tipo ON movimientos(tipo);
CREATE INDEX IF NOT EXISTS idx_movimientos_fecha ON movimientos(fecha);
