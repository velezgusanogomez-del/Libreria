/*
 * Librero - Interfaz web del inventario de libros.
 * JavaScript puro, sin dependencias. Contratos DOM y API intactos.
 */

const $ = (sel) => document.querySelector(sel);
const $all = (sel) => Array.from(document.querySelectorAll(sel));
const valor = (sel) => {
  const el = $(sel);
  return el ? el.value.trim() : "";
};

let editando = null; // {isbn, titulo, editorial, autor} cuando se edita un libro en Inventario
let panelActivo = "panel";
const cargado = { panel: false, inventario: false, historial: false };
let pidiendoToken = false;
let temporizadorSugerencias = null;

/* ------------------------------ Toast (R3) ------------------------------ */
function toast(mensaje, esError) {
  const el = $("#toast");
  el.textContent = mensaje;
  el.className = "toast mostrar" + (esError ? " error" : "");
  clearTimeout(el._timer);
  el._timer = setTimeout(() => { el.className = "toast"; }, 4200);
}

/* ------------------------------ Token / API ------------------------------ */
const TOKEN_KEY = "LIBRERO_TOKEN";
function tokenGuardia() {
  try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
}
function guardarToken(token) {
  try { localStorage.setItem(TOKEN_KEY, token); } catch { /* sin localStorage */ }
}

/** R3: notifica al usuario con toast(...) y solicita el token cuando hace falta. */
function manejarTokenInvalido() {
  toast("Token de Librero requerido. Ingresa el token y recarga la página.", true);
  if (pidiendoToken) return;
  pidiendoToken = true;
  setTimeout(() => {
    const t = window.prompt("Ingresa el token de Librero (LIBRERO_TOKEN):");
    if (t) {
      guardarToken(t);
      location.reload();
    } else {
      pidiendoToken = false;
    }
  }, 300);
}

function cabecerasConToken() {
  const headers = {};
  const token = tokenGuardia();
  if (token) headers["X-Librero-Token"] = token;
  return headers;
}

async function api(url, opciones) {
  const config = opciones || {};
  const headers = cabecerasConToken();
  let cuerpo = null;
  if (config.body !== undefined) {
    headers["Content-Type"] = "application/json";
    cuerpo = JSON.stringify(config.body);
  }
  const res = await fetch(url, { method: config.method || "GET", headers, body: cuerpo });
  const json = await res.json().catch(() => ({}));
  if (res.status === 401) {
    manejarTokenInvalido();
    throw new Error(json.error || "Token inválido o ausente.");
  }
  if (!res.ok) {
    throw new Error(json.error || "Error HTTP " + res.status);
  }
  return json;
}

/** R2: petición de texto (CSV) con token y manejo explícito de 401 / res.ok. */
async function apiTexto(url) {
  const res = await fetch(url, { headers: cabecerasConToken() });
  if (res.status === 401) {
    manejarTokenInvalido();
    throw new Error("Token inválido o ausente.");
  }
  if (!res.ok) {
    throw new Error("Error HTTP " + res.status);
  }
  return res.text();
}

/** UX2: deshabilita el botón mientras dura la petición. */
async function conCarga(boton, tarea) {
  if (!boton || boton.disabled) return undefined;
  const textoOriginal = boton.textContent;
  boton.disabled = true;
  boton.classList.add("cargando");
  try {
    return await tarea();
  } finally {
    boton.disabled = false;
    boton.classList.remove("cargando");
    boton.textContent = textoOriginal;
  }
}

/* ------------------------------ Validación ------------------------------ */
function validarFormulario(formulario) {
  let ok = true;
  formulario.querySelectorAll("input[required], select[required]").forEach((el) => {
    if (el.value === null || el.value.trim() === "") {
      el.classList.add("error-validacion");
      ok = false;
    } else {
      el.classList.remove("error-validacion");
    }
  });
  if (!ok) toast("Completa todos los campos obligatorios.", true);
  return ok;
}

function objetoDelFormulario(formulario) {
  const obj = {};
  for (const [clave, v] of new FormData(formulario).entries()) {
    if (v === "" || v === null) continue;
    const valorConvertido =
      clave.includes("cantidad") || clave.includes("cajas")
        ? parseInt(v, 10)
        : String(v).trim();
    obj[clave] = valorConvertido;
  }
  return obj;
}

/* ------------------------------ Helpers de DOM ------------------------------ */
function numero(v) {
  if (v === null || v === undefined || v === "") return "";
  return Number(v).toLocaleString("es-ES", { maximumFractionDigits: 0 });
}

function celda(valorCelda) {
  const td = document.createElement("td");
  if (valorCelda instanceof Node) td.appendChild(valorCelda);
  else td.textContent = valorCelda === null || valorCelda === undefined ? "" : String(valorCelda);
  return td;
}

function celdaPill(texto, clase) {
  const td = document.createElement("td");
  const span = document.createElement("span");
  span.className = "pill " + clase;
  span.textContent = texto || "-";
  td.appendChild(span);
  return td;
}

function filaVacia(texto) {
  const tr = document.createElement("tr");
  const td = celda(texto);
  td.colSpan = 8;
  td.className = "vacio";
  tr.appendChild(td);
  return tr;
}

function escaparHtml(texto) {
  return String(texto ?? "")
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

/* ------------------------------ Carga por panel (M10) ------------------------------ */
function invalidarDatos() {
  cargado.panel = false;
  cargado.inventario = false;
  cargado.historial = false;
}

async function cargarSeccion(id, forzar) {
  if (!forzar && cargado[id]) return;
  if (id === "panel") {
    await cargarPanel();
    cargado.panel = true;
  } else if (id === "inventario") {
    actualizarFiltrosInventario();
    await cargarTablaInventario();
    cargado.inventario = true;
  } else if (id === "historial") {
    actualizarFiltrosHistorial();
    await cargarTablaHistorial();
    cargado.historial = true;
  }
}

/** Tras una mutación se recarga solo el panel activo; los demás se invalidan. */
function cargarPanelActivo() {
  if (cargado[panelActivo] !== undefined) {
    return cargarSeccion(panelActivo, true);
  }
  invalidarDatos();
  return Promise.resolve();
}

function alCargar(id) {
  cargarSeccion(id).catch((err) => {
    if (!/token/i.test(String(err.message))) toast("No se pudo cargar la sección: " + err.message, true);
  });
}

/* ------------------------------ Autocompletado ISBN en Llegada ------------------------------ */
// Estado del autocompletado en Llegada.
const listaLlegada = [];
let itemFocoLlegada = -1;
let temporizadorLlegada = null;

function actualizarListaLlegada() {
  // Actualiza la lista de sugerencias y el índice de foco.
  listaLlegada.length = 0;
  itemFocoLlegada = -1;
}

function destacarLlegada(idx) {
  const items = $all("#sugerencias-llegada .sugerencia-item");
  if (idx < 0 || idx >= items.length) { itemFocoLlegada = -1; }
  else {
    itemFocoLlegada = idx;
    listaLlegada.forEach((l, i) => { if (i !== idx && items[i]) items[i].classList.remove("activa"); });
    items[idx].classList.add("activa");
  }
}

function seleccionarLibroLlegada(libro) {
  if (!libro) return;
  $("#llegada-isbn").value = libro.isbn || "";
  $("#llegada-titulo").value = libro.titulo || "";
  $("#llegada-editorial").value = libro.editorial || "";
  $("#llegada-autor").value = libro.autor || "";
  const stock = $("#llegada-stock-info");
  if (stock) stock.textContent = libro.stock_actual ? "Stock actual: " + libro.stock_actual : "";
  // No bloqueamos el formulario al seleccionar: cerrar sugerencias.
  $("#sugerencias-llegada").innerHTML = "";
}

async function actualizarSugerenciasLlegada() {
  const q = valor("#llegada-isbn");
  if (q.length < 2) { $("#sugerencias-llegada").innerHTML = ""; listaLlegada.length = 0; itemFocoLlegada = -1; return; }
  try {
    const r = await api("/api/libros/sugerir?q=" + encodeURIComponent(q));
    listaLlegada.length = 0;
    listaLlegada.push(...(r.slice(0, 8) || []));
    const cont = $("#sugerencias-llegada");
    cont.innerHTML = "";
    listaLlegada.forEach((libro, i) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "sugerencia-item" + (i === itemFocoLlegada ? " activa" : "");
      const titulo = document.createElement("strong");
      titulo.textContent = libro.titulo || "(sin título)";
      const det = document.createElement("span");
      det.textContent = (libro.isbn || "") + (libro.editorial ? " · " + libro.editorial : "");
      const stockInfo = document.createElement("em");
      stockInfo.textContent = "Stock: " + libro.stock_actual;
      btn.append(titulo, det, stockInfo);
      btn.addEventListener("click", () => seleccionarLibroLlegada(libro));
      cont.append(btn);
    });
    itemFocoLlegada = itemFocoLlegada >= 0 && itemFocoLlegada < listaLlegada.length ? itemFocoLlegada : -1;
  } catch { $("#sugerencias-llegada").innerHTML = ""; listaLlegada.length = 0; }
}

function iniciarSugerenciasLlegada() {
  clearTimeout(temporizadorLlegada);
  temporizadorLlegada = setTimeout(actualizarSugerenciasLlegada, 200);
}

// Autocompletado en Llegada: teclado (Enter/Flechas) y clic.
$("#llegada-isbn").addEventListener("input", () => iniciarSugerenciasLlegada());
$("#llegada-isbn").addEventListener("keydown", (e) => {
  const items = $all("#sugerencias-llegada .sugerencia-item");
  if (e.key === "ArrowDown") {
    e.preventDefault();
    destacarLlegada(itemFocoLlegada + 1);
  } else if (e.key === "ArrowUp") {
    e.preventDefault();
    destacarLlegada(itemFocoLlegada - 1);
  } else if (e.key === "Enter") {
    e.preventDefault();
    if (itemFocoLlegada >= 0 && listaLlegada[itemFocoLlegada]) {
      seleccionarLibroLlegada(listaLlegada[itemFocoLlegada]);
    }
    $("#sugerencias-llegada").innerHTML = "";
  } else if (e.key === "Escape") {
    e.preventDefault();
    $("#sugerencias-llegada").innerHTML = "";
  }
});

/* ------------------------------ Registrar llegada ------------------------------ */
$("#forma-llegada").addEventListener("submit", async (e) => {
  e.preventDefault();
  if (!validarFormulario($("#forma-llegada"))) return;
  const datos = objetoDelFormulario($("#forma-llegada"));
  const boton = $("#forma-llegada button[type=submit]");
  await conCarga(boton, async () => {
    try {
      const r = await api("/api/llegada", { method: "POST", body: datos });
      toast(r.mensaje || "Llegada registrada.", false);
      $("#forma-llegada").reset();
      invalidarDatos();
      await cargarPanelActivo();
    } catch (err) {
      toast("Error al registrar la llegada: " + err.message, true);
    }
  });
});
$("#llegada-limpiar").addEventListener("click", () => $("#forma-llegada").reset());

/* ------------------------------ Registrar salida (UX1) ------------------------------ */
async function buscarLibroSeleccionado(isbn) {
  if (!isbn) { toast("Escribe un ISBN para buscar el libro.", true); return null; }
  const r = await api("/api/inventario/" + encodeURIComponent(isbn));
  $("#salida-isbn").value = r.isbn; // M5: llena el ISBN del formulario de salida
  $("#datos-libro").textContent =
    "Libro: " + r.titulo + " | Editorial: " + r.editorial +
    (r.autor ? " | Autor: " + r.autor : "") + " | Stock actual: " + r.stock_actual;
  return r;
}

$("#btn-buscar-libro").addEventListener("click", async () => {
  await conCarga($("#btn-buscar-libro"), async () => {
    try {
      await buscarLibroSeleccionado(valor("#salida-isbn"));
      limpiarSugerencias();
    } catch (err) {
      $("#datos-libro").textContent = "";
      toast("No se encontró ningún libro con ese ISBN. Regístrelo antes en \"Registrar llegada\".", true);
    }
  });
});

function limpiarSugerencias() {
  const cont = $("#sugerencias-salida");
  if (cont) cont.innerHTML = "";
}

async function actualizarSugerencias() {
  const q = valor("#salida-isbn");
  if (q.length < 2) { limpiarSugerencias(); return; }
  try {
    const lista = await api("/api/libros/sugerir?q=" + encodeURIComponent(q));
    const cont = $("#sugerencias-salida");
    cont.innerHTML = "";
    lista.slice(0, 8).forEach((libro) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "sugerencia-item";
      btn.setAttribute("role", "option");
      const titulo = document.createElement("strong");
      titulo.textContent = libro.titulo || "(sin título)";
      const detalle = document.createElement("span");
      detalle.textContent = (libro.isbn || "") + (libro.editorial ? " · " + libro.editorial : "");
      btn.append(titulo, detalle);
      btn.addEventListener("click", async () => {
        $("#salida-isbn").value = libro.isbn;
        limpiarSugerencias();
        try {
          await buscarLibroSeleccionado(libro.isbn);
        } catch (err) {
          toast("No se pudo cargar el libro: " + err.message, true);
        }
      });
      cont.appendChild(btn);
    });
  } catch (err) {
    limpiarSugerencias();
  }
}

$("#salida-isbn").addEventListener("input", () => {
  clearTimeout(temporizadorSugerencias);
  temporizadorSugerencias = setTimeout(actualizarSugerencias, 220);
});
$("#salida-isbn").addEventListener("blur", () => {
  setTimeout(limpiarSugerencias, 180);
});

$("#forma-salida").addEventListener("submit", async (e) => {
  e.preventDefault();
  if (!validarFormulario($("#forma-salida"))) return;
  const datos = objetoDelFormulario($("#forma-salida"));
  // CORRECCIÓN (error "El ISBN es obligatorio"): el ISBN solo vive en #salida-isbn
  // (campo del buscador), que NO está dentro de forma-salida, por eso no iba en el
  // JSON. Sincronizamos el ISBN del campo que se escribe/autocompleta hacia el JSON.
  datos.isbn = valor("#salida-isbn").trim();
  const boton = $("#forma-salida button[type=submit]");
  await conCarga(boton, async () => {
    try {
      const r = await api("/api/salida", { method: "POST", body: datos });
      toast(r.mensaje || "Venta registrada.", false);
      $("#forma-salida").reset();
      $("#datos-libro").textContent = "";
      limpiarSugerencias();
      invalidarDatos();
      await cargarPanelActivo();
    } catch (err) {
      toast("Error al registrar la salida: " + err.message, true);
    }
  });
});
$("#salida-limpiar").addEventListener("click", () => {
  $("#forma-salida").reset();
  $("#datos-libro").textContent = "";
  limpiarSugerencias();
});

/* ------------------------------ Inventario ------------------------------ */
let filtrosInventario = { q_isbn: "", q_titulo: "", q_editorial: "", orden: "asc" };

function actualizarFiltrosInventario() {
  filtrosInventario = {
    q_isbn: valor("#inv-isbn"),
    q_titulo: valor("#inv-titulo"),
    q_editorial: valor("#inv-editorial"),
    orden: valor("#inv-orden") || "asc",
  };
}

async function cargarTablaInventario() {
  const qs = new URLSearchParams(filtrosInventario);
  const lista = await api("/api/inventario?" + qs);
  const tbody = $("#tabla-inventario-tbody");
  tbody.innerHTML = "";
  if (lista.length === 0) {
    tbody.appendChild(filaVacia("No hay libros que coincidan con la búsqueda."));
    return;
  }
  for (const libro of lista) {
    const tr = document.createElement("tr");
    if (libro.poco_stock) tr.classList.add("poco-stock");
    tr.dataset.isbn = libro.isbn;

    if (editando && editando.isbn === libro.isbn) {
      renderFilaEdicion(tr, libro);
      tbody.appendChild(tr);
      continue;
    }

    tr.append(
      celda(libro.isbn),
      celda(libro.titulo),
      celda(libro.editorial),
      celda(libro.autor || ""),
      celda(numero(libro.stock_actual))
    );
    if (libro.poco_stock) {
      const badge = document.createElement("span");
      badge.className = "pill badge-poco-stock";
      badge.textContent = "Poco stock";
      tr.querySelectorAll("td")[4].appendChild(badge);
    }
    tr.append(
      celda(numero(libro.total_cajas)),
      celda(libro.ultima_fecha_movimiento || "-")
    );

    const acciones = document.createElement("td");
    const btnEditar = document.createElement("button");
    btnEditar.className = "btn-link btn-pequeño";
    btnEditar.textContent = "Editar";
    btnEditar.addEventListener("click", () => iniciarEdicion(libro));
    const btnEliminar = document.createElement("button");
    btnEliminar.className = "btn-link btn-link-error btn-pequeño";
    btnEliminar.textContent = "Eliminar";
    btnEliminar.addEventListener("click", async () => {
      if (!confirm("¿Eliminar el libro '" + libro.titulo + "' (ISBN " + libro.isbn + ")? El historial de movimientos se conserva.")) return;
      try {
        await api("/api/inventario/" + encodeURIComponent(libro.isbn), { method: "DELETE" });
        toast("Libro eliminado: " + libro.titulo, false);
        editando = null;
        invalidarDatos();
        await cargarPanelActivo();
      } catch (err) { toast("Error al eliminar: " + err.message, true); }
    });
    acciones.append(btnEditar, btnEliminar);
    tr.appendChild(acciones);

    tbody.appendChild(tr);
  }
}

function renderFilaEdicion(tr, libro) {
  tr.dataset.editarIsbn = libro.isbn;
  tr.innerHTML =
    '<td>' + escaparHtml(libro.isbn) + '</td>' +
    '<td><input id="edit-titulo" type="text" value="' + escaparHtml(editando.titulo) + '"></td>' +
    '<td><input id="edit-editorial" type="text" value="' + escaparHtml(editando.editorial) + '"></td>' +
    '<td><input id="edit-autor" type="text" value="' + escaparHtml(editando.autor || "") + '"></td>' +
    '<td colspan="5"><button type="button" id="edit-guardar" class="btn-principal btn-pequeño">Guardar</button> ' +
    '<button type="button" id="edit-cancelar" class="btn-secundario btn-pequeño">Cancelar</button></td>';
  tr.querySelector("#edit-guardar").addEventListener("click", () => guardarEdicion(tr.dataset.editarIsbn));
  tr.querySelector("#edit-cancelar").addEventListener("click", () => { editando = null; cargarPanelActivo(); });
}

function iniciarEdicion(libro) {
  editando = { isbn: libro.isbn, titulo: libro.titulo, editorial: libro.editorial, autor: libro.autor || "" };
  cargarPanelActivo();
}

async function guardarEdicion(isbn) {
  const datos = {
    titulo: valor("#edit-titulo"),
    editorial: valor("#edit-editorial"),
    autor: valor("#edit-autor"),
  };
  try {
    const r = await api("/api/inventario/" + encodeURIComponent(isbn), { method: "PUT", body: datos });
    toast("Libro actualizado: " + r.titulo, false);
    editando = null;
    invalidarDatos();
    await cargarPanelActivo();
  } catch (err) { toast("Error al editar: " + err.message, true); }
}

$("#btn-buscar-inventario").addEventListener("click", async () => {
  await conCarga($("#btn-buscar-inventario"), async () => {
    try {
      actualizarFiltrosInventario();
      cargado.inventario = false;
      await cargarSeccion("inventario", true);
    } catch (err) {
      toast("Error al buscar: " + err.message, true);
    }
  });
});

/* ------------------------------ Historial ------------------------------ */
let filtrosHistorial = { tipo: "TODOS", isbn: "", desde: "", hasta: "" };

function actualizarFiltrosHistorial() {
  filtrosHistorial = {
    tipo: valor("#his-tipo"),
    isbn: valor("#his-isbn"),
    desde: valor("#his-desde"),
    hasta: valor("#his-hasta"),
  };
}

async function cargarTablaHistorial() {
  const qs = new URLSearchParams(filtrosHistorial);
  const lista = await api("/api/movimientos?" + qs);
  const tbody = $("#tabla-historial-tbody");
  tbody.innerHTML = "";
  if (lista.length === 0) {
    tbody.appendChild(filaVacia("No hay movimientos que coincidan con el filtro."));
    return;
  }
  for (const m of lista) {
    const tr = document.createElement("tr");
    const clasePill = m.tipo === "ENTRADA" ? "pill-entrada" : "pill-salida";
    tr.append(
      celda(m.fecha),
      celda(m.hora),
      celda(m.isbn),
      celda(m.titulo),
      celdaPill(m.tipo, clasePill),
      celda(numero(m.cantidad)),
      celda(m.cajas ? numero(m.cajas) : "-"),
      celda(m.nota || "-")
    );
    tbody.appendChild(tr);
  }
}

$("#btn-buscar-historial").addEventListener("click", async () => {
  await conCarga($("#btn-buscar-historial"), async () => {
    try {
      actualizarFiltrosHistorial();
      cargado.historial = false;
      await cargarSeccion("historial", true);
    } catch (err) {
      toast("Error al buscar: " + err.message, true);
    }
  });
});

/* ------------------------------ Panel principal ------------------------------ */
async function cargarPanel() {
  const dash = await api("/api/dashboard");
  const cards = $("#cards");
  cards.innerHTML = "";

  const stats = [
    { id: "total_libros_diferentes", label: "Total de libros diferentes registrados", clase: "destacado" },
    { id: "total_stock", label: "Total de libros en stock (suma de todos)" },
    { id: "total_cajas_arribados", label: "Total de cajas que han llegado" },
    { id: "total_libros_vendidos", label: "Total de libros vendidos (suma de salidas)" },
    { id: "libros_con_poco_stock", label: "Libros con poco stock", clase: "alerta" },
  ];
  for (const s of stats) {
    const val = Array.isArray(dash[s.id]) ? dash[s.id].length : dash[s.id];
    const card = document.createElement("div");
    card.className = "card" + (s.clase ? " " + s.clase : "");
    const h3 = document.createElement("h3");
    h3.textContent = s.label;
    const numeroEl = document.createElement("div");
    numeroEl.className = "numero";
    numeroEl.textContent = numero(val === undefined ? 0 : val);
    card.append(h3, numeroEl);
    cards.appendChild(card);
  }

  // Lista de libros con poco stock (card inferior).
  const container = $("#lista-poco-stock");
  container.innerHTML = "";
  const lista = dash.libros_con_poco_stock || [];
  if (lista.length === 0) {
    container.appendChild(celda("No hay libros con poco stock (umbral: " + (dash.umbral_poco_stock ?? 5) + " libros)."));
  } else {
    const tabla = document.createElement("table");
    tabla.className = "tabla mini-tabla";
    tabla.innerHTML = "<thead><tr><th>ISBN</th><th>Título</th><th>Editorial</th><th>Stock actual</th></tr></thead>";
    const tbody = document.createElement("tbody");
    for (const libro of lista) {
      const tr = document.createElement("tr");
      tr.append(celda(libro.isbn), celda(libro.titulo), celda(libro.editorial), celda(numero(libro.stock_actual)));
      tbody.appendChild(tr);
    }
    tabla.appendChild(tbody);
    container.appendChild(tabla);
  }
}

/* ------------------------------ Exportar CSV (R2) ------------------------------ */
async function exportarCSV(url, nombreArchivo) {
  const csvText = await apiTexto(url);
  const blob = new Blob([csvText], { type: "text/csv;charset=utf-8" });
  const urlObj = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = urlObj;
  a.download = nombreArchivo;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(urlObj), 1000);
}

function exportarConFeedback(boton, url, nombre) {
  conCarga(boton, async () => {
    try {
      await exportarCSV(url, nombre);
      toast("Descarga iniciada: " + nombre, false);
    } catch (err) {
      toast("Error al exportar: " + err.message, true);
    }
  });
}
$("#btn-exportar-inventario").addEventListener("click", () =>
  exportarConFeedback($("#btn-exportar-inventario"), "/api/exportar/inventario.csv", "inventario.csv"));
$("#btn-exportar-movimientos").addEventListener("click", () =>
  exportarConFeedback($("#btn-exportar-movimientos"), "/api/exportar/movimientos.csv", "movimientos.csv"));

/* ------------------------------ Navegación (M10) ------------------------------ */
function mostrarSeccion(id) {
  panelActivo = id;
  $all(".menu-btn").forEach((b) => b.classList.toggle("activo", b.dataset.panel === id));
  $all(".panel").forEach((p) => p.classList.toggle("activa", p.id === id));
  alCargar(id);
}
$all(".menu-btn").forEach((b) => b.addEventListener("click", () => mostrarSeccion(b.dataset.panel)));

/* ------------------------------ Inicio ------------------------------ */
api("/api/info")
  .then((info) => { $("#ip-local").textContent = info.ip + ":" + info.port; })
  .catch(() => {});

// Solo se carga el panel inicial; el resto se carga al visitarlo (M10).
mostrarSeccion("panel");

/* ------------------------------ Ficha del libro (Consultar libro) ------------------------------ */
async function buscarFichaLibro() {
  const isbn = valor("#ficha-isbn").trim();
  if (!isbn) { toast("Escribe un ISBN.", true); return; }
  try {
    const r = await api("/api/libros/" + encodeURIComponent(isbn) + "/ficha");
    const libro = r.libro, fichaResult = $("#ficha-resultado");
    fichaResult.textContent = "";
    fichaResult.append(document.createElement("div"));
    const T = fichaResult;
    const g = (v, d) => v ? String(v) : (d || "");
    const h = (t2, txt) => { const d = document.createElement("div"); d.className = "ficha-fila"; const l = document.createElement("b"); l.textContent = t2; d.append(l, document.createTextNode(txt)); T.append(d); };
    h("Título:", libro.titulo); h("Editorial:", libro.editorial); h("Autor:", libro.autor || "Sin autor registrado");
    h("Stock actual:", libro.stock_actual + " libros"); h("Cajas totales:", libro.total_cajas + " cajas");
    h("Precio de venta:", libro.precio_venta != null && libro.precio_venta !== "" ? String(libro.precio_venta) + " pesos" : "Sin precio registrado");
    h("Última llegada:", (r.ultima_entrada ? (r.ultima_entrada.fecha + " | " + r.ultima_entrada.cantidad + " libros, " + (r.ultima_entrada.cajas || "0") + " cajas") : "Sin llegadas registradas"));
    h("Última salida:", (r.ultima_salida ? (r.ultima_salida.fecha + " | " + r.ultima_salida.cantidad + " libros") : "Sin salidas registradas"));
  } catch (err) { $("#ficha-resultado").textContent = err.message; }
}
$("#ficha-buscar").addEventListener("click", () => buscarFichaLibro());

/* ------------------------------ Calculadora del total en salida ------------------------------ */
function calcTotalVenta() {
  const precio = parseFloat($("#salida-precio-venta").value || "0");
  const cant = parseInt($("#salida-cantidad").value || "0", 10);
  if (!precio) { $("#total-venta").textContent = "Este libro no tiene precio de venta registrado"; return; }
  const total = precio * cant;
  $("#total-venta").textContent = "Total: " + total.toFixed(2) + " pesos";
}
$("#btn-calcular-total").addEventListener("click", () => { $("#total-venta").textContent = ""; calcTotalVenta(); });
$("#salida-cantidad").addEventListener("input", () => calcTotalVenta());
