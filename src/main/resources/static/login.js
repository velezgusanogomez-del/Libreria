/* Librero - pantalla de inicio de sesión. */
(function () {
  // Token CSRF que Spring Security deja en la cookie XSRF-TOKEN.
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  document.getElementById("login-csrf").value = m ? decodeURIComponent(m[1]) : "";

  const params = new URLSearchParams(window.location.search);
  const mensaje = document.getElementById("login-mensaje");
  if (params.has("error")) {
    mensaje.textContent = "Usuario o contraseña incorrectos.";
    mensaje.className = "login-mensaje error";
  } else if (params.has("bloqueado")) {
    mensaje.textContent = "Demasiados intentos fallidos. Espera unos minutos e inténtalo de nuevo.";
    mensaje.className = "login-mensaje error";
  } else if (params.has("salir")) {
    mensaje.textContent = "Sesión cerrada.";
    mensaje.className = "login-mensaje info";
  }
})();
