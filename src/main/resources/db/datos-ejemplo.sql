-- Librero: datos de ejemplo (2 libros).
-- Se ejecutan una sola vez (G4): solo si metadata.seed_aplicado != '1'
-- (se ejecuta desde BaseDeDatosInicializador).

INSERT OR IGNORE INTO libros (isbn, titulo, editorial, autor, stock_actual, total_cajas, fecha_creacion)
VALUES
    ('978-60-505-9500-4', 'El retrato de Dorian Gray', 'Sudamericana', 'Oscar Wilde', 8, 2, '2026-11-20'),
    ('978-84-7776-042-2', 'El principito', 'Alianza Editorial', 'Antoine de Saint-Exupéry', 3, 1, '2026-11-20');

-- R4: movimientos ENTRADA que respaldan los saldos iniciales sembrados.
INSERT INTO movimientos (isbn, tipo, cantidad, cajas, fecha, hora, nota)
SELECT '978-60-505-9500-4', 'ENTRADA', 8, 2, '2026-11-20', '09:00', 'Stock inicial de ejemplo'
WHERE NOT EXISTS (SELECT 1 FROM movimientos WHERE isbn = '978-60-505-9500-4');

INSERT INTO movimientos (isbn, tipo, cantidad, cajas, fecha, hora, nota)
SELECT '978-84-7776-042-2', 'ENTRADA', 3, 1, '2026-11-20', '09:05', 'Stock inicial de ejemplo'
WHERE NOT EXISTS (SELECT 1 FROM movimientos WHERE isbn = '978-84-7776-042-2');
