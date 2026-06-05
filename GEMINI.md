# Contexto de Desarrollo: App de Terminal (Gemini AI Client)

Este documento centraliza las directrices de diseño, arquitectura y comportamiento para el desarrollo de la aplicación de terminal. Se rige estrictamente por el principio DRY y estándares modernos de interfaz.

---

## 1. Principio Arquitectural: DRY (Don't Repeat Yourself)
* **Centralización de Lógica:** Prohibido duplicar cadenas de texto, configuraciones de endpoints o lógica de parseo en múltiples archivos.
* **Módulos Core:** Toda petición a la API de Gemini, manejo de caché de sesión y formateo de respuestas debe residir en un único módulo de servicio reutilizable.
* **Single Source of Truth:** Los colores, rutas de configuración y flags de la CLI se definen en un único archivo de constantes/configuración.

---

## 2. Interfaz de Usuario: Directrices Material Design 3 (CLI Adaptado)
Aunque el entorno es una terminal, se emula la filosofía de **Material 3** mediante el uso de bloques visuales, espaciado semántico y jerarquía de color (utilizando códigos ANSI o librerías de estilos compatibles):

* **Jerarquía Visual:**
    * `Primary`: Para el prompt del usuario y comandos principales.
    * `Secondary` / `Tertiary`: Para metadatos (tiempo de respuesta, tokens) y estados secundarios.
    * `Error`: Reservado estrictamente para fallos críticos del sistema o de red.
* **Iconografía (Outlined):** Si la terminal soporta fuentes con iconos (Nerd Fonts o similares), se utilizarán **siempre variantes outlined (contorneadas)** para mantener la consistencia visual y ligereza de M3.
* **Contenedores:** Uso de líneas sutiles (`┌`, `─`, `┐`, `└`, `┘`) para delimitar las respuestas de la IA, simulando las "Cards" o superficies de Material 3.

---

## 3. Localización y Traducciones (i18n)
La aplicación está diseñada para ser global, por lo que **nunca** se deben hardcodear strings en el código fuente.

* **Estructura de Recursos:** Los mensajes de la CLI, errores y textos de ayuda se gestionan mediante archivos de recursos independientes (JSON/YAML/etc.).
* **Idiomas Target Prioritarios:**
    * `es_VE`: Español de Venezuela (Idioma nativo/principal de la interfaz).
    * `pt_PT`: Portugués de Portugal.
* **Fallback:** Si una clave de traducción falta en el locale del sistema, el sistema debe degradar elegantemente al idioma por defecto (`es_VE` o `en`).

---

## 4. Flujo de Trabajo en el Entorno
* **Entorno de Desarrollo:** Optimizado para entornos ligeros, ejecución remota y contenedores web (GitHub Codespaces / Android Code Studio).
* **Compilación Eficiente:** El código debe ser modular para permitir recargas rápidas o ejecuciones de prueba directas sin sobrecargar el entorno virtual.