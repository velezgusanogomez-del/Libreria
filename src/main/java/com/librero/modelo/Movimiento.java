package com.librero.modelo;

import com.google.gson.annotations.SerializedName;

/**
 * Registro de un movimiento del inventario.
 *
 * - tipo: solo puede ser "ENTRADA" o "SALIDA".
 * - cajas: solo para entradas, cantidad de cajas que llegaron en esa entrada (puede ser null).
 * - fecha: día del movimiento (formato YYYY-MM-DD).
 * - hora: hora del movimiento (formato HH:MM).
 * - nota: observación opcional.
 */
public class Movimiento {

    @SerializedName("id")
    private long id;
    @SerializedName("isbn")
    private String isbn;
    @SerializedName("tipo")
    private String tipo;
    @SerializedName("cantidad")
    private int cantidad;
    @SerializedName("cajas")
    private Integer cajas;
    @SerializedName("fecha")
    private String fecha;
    @SerializedName("hora")
    private String hora;
    @SerializedName("nota")
    private String nota;

    public Movimiento() {
    }

    public Movimiento(String isbn, String tipo, int cantidad, Integer cajas, String fecha, String hora, String nota) {
        this.isbn = isbn;
        this.tipo = tipo;
        this.cantidad = cantidad;
        this.cajas = cajas;
        this.fecha = fecha;
        this.hora = hora;
        this.nota = nota;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getIsbn() {
        return isbn;
    }

    public void setIsbn(String isbn) {
        this.isbn = isbn;
    }

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public int getCantidad() {
        return cantidad;
    }

    public void setCantidad(int cantidad) {
        this.cantidad = cantidad;
    }

    public Integer getCajas() {
        return cajas;
    }

    public void setCajas(Integer cajas) {
        this.cajas = cajas;
    }

    public String getFecha() {
        return fecha;
    }

    public void setFecha(String fecha) {
        this.fecha = fecha;
    }

    public String getHora() {
        return hora;
    }

    public void setHora(String hora) {
        this.hora = hora;
    }

    public String getNota() {
        return nota;
    }

    public void setNota(String nota) {
        this.nota = nota;
    }
}
