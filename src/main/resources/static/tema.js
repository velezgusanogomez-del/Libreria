/*
 * Librero - tema claro/oscuro.
 * Se carga en <head> para aplicar el tema antes de pintar la página (sin parpadeo).
 * Sin preferencia guardada, se sigue el tema del sistema operativo.
 */
(function () {
  const CLAVE = "librero-tema";

  function guardado() {
    try { return localStorage.getItem(CLAVE); } catch { return null; }
  }

  function aplicar(tema) {
    if (tema === "claro" || tema === "oscuro") {
      document.documentElement.dataset.theme = tema === "oscuro" ? "dark" : "light";
    } else {
      delete document.documentElement.dataset.theme;
    }
  }

  function actual() {
    const t = document.documentElement.dataset.theme;
    if (t === "dark") return "oscuro";
    if (t === "light") return "claro";
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "oscuro" : "claro";
  }

  aplicar(guardado());

  window.LibreroTema = {
    actual,
    alternar() {
      const nuevo = actual() === "oscuro" ? "claro" : "oscuro";
      aplicar(nuevo);
      try { localStorage.setItem(CLAVE, nuevo); } catch { /* sin almacenamiento: solo esta sesión */ }
      return nuevo;
    },
  };
})();
