package com.librero.controlador;

import com.librero.dto.EditarLibroPeticion;
import com.librero.dto.FichaLibro;
import com.librero.dto.InfoServidor;
import com.librero.dto.LibroVista;
import com.librero.dto.LlegadaPeticion;
import com.librero.dto.MovimientoVista;
import com.librero.dto.Panel;
import com.librero.dto.ResultadoOperacion;
import com.librero.dto.SalidaPeticion;
import com.librero.servicio.LibreroServicio;
import jakarta.validation.Valid;
import org.springframework.core.env.Environment;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** API JSON de Librero. Todas las rutas exigen sesión iniciada (ver SeguridadConfig). */
@RestController
@RequestMapping("/api")
public class LibreroControlador {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final LibreroServicio servicio;
    private final Environment entorno;

    public LibreroControlador(LibreroServicio servicio, Environment entorno) {
        this.servicio = servicio;
        this.entorno = entorno;
    }

    @GetMapping("/info")
    public InfoServidor info(Authentication usuario) {
        int puerto = entorno.getProperty("local.server.port", Integer.class,
                entorno.getProperty("server.port", Integer.class, 8080));
        return new InfoServidor(ipLocal(), puerto, "Librero", usuario == null ? null : usuario.getName());
    }

    @GetMapping("/dashboard")
    public Panel panel() {
        return servicio.panel();
    }

    @PostMapping("/llegada")
    public ResultadoOperacion llegada(@Valid @RequestBody LlegadaPeticion peticion) {
        return servicio.registrarLlegada(peticion);
    }

    @PostMapping("/salida")
    public ResultadoOperacion salida(@Valid @RequestBody SalidaPeticion peticion) {
        return servicio.registrarSalida(peticion);
    }

    @GetMapping("/inventario")
    public List<LibroVista> inventario(@RequestParam(name = "q_isbn", required = false) String isbn,
                                       @RequestParam(name = "q_titulo", required = false) String titulo,
                                       @RequestParam(name = "q_editorial", required = false) String editorial,
                                       @RequestParam(name = "orden", required = false) String orden) {
        return servicio.inventario(isbn, titulo, editorial, orden);
    }

    @GetMapping("/inventario/{isbn}")
    public LibroVista libro(@PathVariable String isbn) {
        return servicio.buscarLibro(isbn);
    }

    @PutMapping("/inventario/{isbn}")
    public LibroVista editar(@PathVariable String isbn, @Valid @RequestBody EditarLibroPeticion peticion) {
        return servicio.actualizarLibro(isbn, peticion);
    }

    @DeleteMapping("/inventario/{isbn}")
    public ResultadoOperacion eliminar(@PathVariable String isbn) {
        return servicio.eliminarLibro(isbn);
    }

    @GetMapping("/movimientos")
    public List<MovimientoVista> movimientos(@RequestParam(required = false) String tipo,
                                             @RequestParam(required = false) String isbn,
                                             @RequestParam(required = false) String desde,
                                             @RequestParam(required = false) String hasta) {
        return servicio.historial(tipo, isbn, desde, hasta);
    }

    @GetMapping("/libros/sugerir")
    public List<LibroVista> sugerir(@RequestParam(name = "q", required = false) String consulta) {
        return servicio.sugerirLibros(consulta);
    }

    @GetMapping("/libros/{isbn}/ficha")
    public FichaLibro ficha(@PathVariable String isbn) {
        return servicio.ficha(isbn);
    }

    @GetMapping("/exportar/inventario.csv")
    public ResponseEntity<byte[]> exportarInventario() {
        return csv(servicio.inventarioCsv(), "inventario.csv");
    }

    @GetMapping("/exportar/movimientos.csv")
    public ResponseEntity<byte[]> exportarMovimientos() {
        return csv(servicio.movimientosCsv(), "movimientos.csv");
    }

    private static ResponseEntity<byte[]> csv(String contenido, String nombre) {
        // BOM UTF-8 para que Excel muestre bien las tildes y la ñ.
        byte[] cuerpo = ("﻿" + contenido).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nombre).build().toString())
                .body(cuerpo);
    }

    /** Adaptadores de máquinas virtuales/contenedores: su IP no sirve para entrar desde la WiFi. */
    private static final Pattern ADAPTADOR_VIRTUAL = Pattern.compile(
            "(?i)virtualbox|vmware|hyper-v|vethernet|wsl|docker|vbox|loopback|tailscale|zerotier|tap-|tun");

    /**
     * IPv4 de la red local para entrar desde otra computadora. Prefiere direcciones privadas
     * (192.168.x, 10.x, 172.16-31.x) de adaptadores físicos y descarta los virtuales.
     */
    public static String ipLocal() {
        String respaldo = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                String nombre = ni.getName() + " " + ni.getDisplayName();
                boolean virtual = ADAPTADOR_VIRTUAL.matcher(nombre).find();
                for (InetAddress d : Collections.list(ni.getInetAddresses())) {
                    if (!(d instanceof Inet4Address) || d.isLoopbackAddress() || d.isLinkLocalAddress()) {
                        continue;
                    }
                    if (!virtual && d.isSiteLocalAddress()) {
                        return d.getHostAddress();
                    }
                    if (respaldo == null) {
                        respaldo = d.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignorada) {
            // Sin interfaces de red: se usa loopback.
        }
        return respaldo != null ? respaldo : "127.0.0.1";
    }
}
