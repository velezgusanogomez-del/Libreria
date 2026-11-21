package com.librero.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

/**
 * Usuario único de la librería.
 *
 * - Si LIBRERO_PASSWORD está definida, esa es la contraseña (y se guarda su hash).
 * - Si no, se usa el hash guardado en la tabla metadata.
 * - Si tampoco hay hash (primer arranque), se genera una contraseña aleatoria, se guarda
 *   su hash y se muestra UNA vez en la consola.
 *
 * Solo se guarda el hash BCrypt, nunca la contraseña en texto plano.
 */
@Service
public class CredencialesServicio implements UserDetailsService, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(CredencialesServicio.class);
    private static final String CLAVE_HASH = "password_hash";
    private static final String ALFABETO = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final BaseDeDatosInicializador baseDeDatos;
    private final PasswordEncoder encoder;
    private final String usuario;
    private final String passwordConfigurada;
    private volatile String hash;

    public CredencialesServicio(BaseDeDatosInicializador baseDeDatos, PasswordEncoder encoder,
                                @Value("${librero.seguridad.usuario:admin}") String usuario,
                                @Value("${librero.seguridad.password:}") String passwordConfigurada) {
        this.baseDeDatos = baseDeDatos;
        this.encoder = encoder;
        this.usuario = usuario.isBlank() ? "admin" : usuario.trim();
        this.passwordConfigurada = passwordConfigurada;
    }

    @Override
    public void afterPropertiesSet() {
        if (!passwordConfigurada.isBlank()) {
            hash = encoder.encode(passwordConfigurada);
            baseDeDatos.guardarMetadata(CLAVE_HASH, hash);
            log.info("Acceso: usuario '{}' con la contraseña de la variable LIBRERO_PASSWORD.", usuario);
            return;
        }
        hash = baseDeDatos.leerMetadata(CLAVE_HASH).orElse(null);
        if (hash != null) {
            log.info("Acceso: usuario '{}' con la contraseña guardada.", usuario);
            return;
        }
        String generada = generarPassword(12);
        hash = encoder.encode(generada);
        baseDeDatos.guardarMetadata(CLAVE_HASH, hash);
        log.warn("""

                ============================================================
                  PRIMER ARRANQUE: se generó una contraseña de acceso.
                    Usuario:    {}
                    Contraseña: {}
                  Anótela: no se volverá a mostrar. Para cambiarla, inicie
                  Librero con la variable de entorno LIBRERO_PASSWORD.
                ============================================================""", usuario, generada);
    }

    @Override
    public UserDetails loadUserByUsername(String nombre) {
        if (!usuario.equals(nombre)) {
            throw new UsernameNotFoundException("Usuario desconocido");
        }
        return User.withUsername(usuario).password(hash).roles("ADMIN").build();
    }

    private static String generarPassword(int largo) {
        SecureRandom azar = new SecureRandom();
        StringBuilder sb = new StringBuilder(largo);
        for (int i = 0; i < largo; i++) {
            sb.append(ALFABETO.charAt(azar.nextInt(ALFABETO.length())));
        }
        return sb.toString();
    }
}
