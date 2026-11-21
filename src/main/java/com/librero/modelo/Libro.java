package com.librero.modelo;

import com.google.gson.annotations.SerializedName;

/**
 * Representa un libro del inventario.
 *
 * - isbn: código identificador único (el usuario dijo "ICBN", el código correcto es ISBN).
 * - stockActual: cantidad de libros que hay en este momento.
 * - totalCajas: cantidad total de cajas que han llegado.
 * - fechaCreacion: fecha en que se registró el libro.
 */
public class Libro {

    @SerializedName("isbn")
    private String isbn;
    @SerializedName("titulo")
    private String titulo;
    @SerializedName("editorial")
    private String editorial;
    @SerializedName("autor")
    private String autor;
    @SerializedName("stock_actual")
    private int stockActual;
    @SerializedName("total_cajas")
    private int totalCajas;
    @SerializedName("fecha_creacion")
    private String fechaCreacion;
    /** Bandera de borrado lógico (G6): 0 = eliminado, 1 = activo. */
    @SerializedName("activo")
    private int activo = 1;
    /** Ventas acumuladas que sobreviven al borrado (G6). */
    @SerializedName("vendidos_acumulados")
    private int vendidosAcumulados = 0;

    public String getIsbn() {
        return isbn;
    }

    public void setIsbn(String isbn) {
        this.isbn = isbn;
    }

    public String getTitulo() {
        return titulo;
    }

    public void setTitulo(String titulo) {
        this.titulo = titulo;
    }

    public String getEditorial() {
        return editorial;
    }

    public void setEditorial(String editorial) {
        this.editorial = editorial;
    }

    public String getAutor() {
        return autor;
    }

    public void setAutor(String autor) {
        this.autor = autor;
    }

    public int getStockActual() {
        return stockActual;
    }

    public void setStockActual(int stockActual) {
        this.stockActual = stockActual;
    }

    public int getTotalCajas() {
        return totalCajas;
    }

    public void setTotalCajas(int totalCajas) {
        this.totalCajas = totalCajas;
    }

    public String getFechaCreacion() {
        return fechaCreacion;
    }

    public void setFechaCreacion(String fechaCreacion) {
        this.fechaCreacion = fechaCreacion;
    }

    public int getActivo() {
        return activo;
    }

    public void setActivo(int activo) {
        this.activo = activo;
    }

    /** true si el libro está activo (no fue eliminado). */
    public boolean isActivo() {
        return activo == 1;
    }

    public int getVendidosAcumulados() {
        return vendidosAcumulados;
    }

    public void setVendidosAcumulados(int vendidosAcumulados) {
        this.vendidosAcumulados = vendidosAcumulados;
    }

    @SerializedName("precio_venta")
    private Double precioVenta;
    public Double getPrecioVenta() { return precioVenta; }
    public void setPrecioVenta(Double precioVenta) { this.precioVenta = precioVenta; }
}
