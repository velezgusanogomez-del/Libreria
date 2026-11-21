package com.librero.dto;

import java.util.List;

/** Totales del panel principal. */
public record Panel(
        int totalLibrosDiferentes,
        int totalStock,
        int totalCajasArribados,
        long totalLibrosVendidos,
        List<LibroVista> librosConPocoStock,
        int umbralPocoStock) {
}
