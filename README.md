# Project-Terminal

Una terminal ligera y moderna para Android diseñada con **Material Design 3**, potenciada por un entorno Linux nativo gracias a **PRoot** y un sistema base de **Debian**.

## Características

- **Entorno Linux Real:** Corre un tarball de Debian de manera interna sin necesidad de acceso root.
- **Aislamiento Seguro:** Gestión del entorno a través de PRoot compilado de forma nativa.
- **Bypass W^X:** Intercepción de `execve()` vía `LD_PRELOAD` para eludir restricciones SELinux en Android 12+.
- **Mitigación Phantom Process:** Servicio en primer plano con WakeLock y WifiLock para evitar la eliminación de procesos en segundo plano.
- **Pipeline de Logging Asíncrono:** Sistema estructurado de diagnóstico con canales, sinks (disco, logcat, DebugLogger) y contextos MDC.
- **Interfaz Moderna:** Diseñada completamente en Jetpack Compose siguiendo las pautas estéticas de Material 3 con un estado de superficie reutilizable (`TerminalSurfaceState`).
- **Extracción Nativa de Rootfs:** Soporte para descompresión mediante `libbsdtar.so` con fallback a Apache Commons Compress.
- **Eficiencia Interna:** Arquitectura modular optimizada para evitar la duplicación de funciones (DRY).

## Componentes Utilizados

Este proyecto integra y agradece a las siguientes tecnologías de código abierto:

- **Terminal Core:** Módulos `terminal-view` y `terminal-emulator` (Bifurcados del ecosistema Termux / Apache 2.0).
- **Motor de Emulación:** PRoot (GPL v2+).
- **Sistema Operativo Base:** Tarball de Debian GNU/Linux (Descargado desde Docker Hub).
- **UI Framework:** Android Jetpack Compose & Material 3 (Apache 2.0).
- **W^X Bypass:** Implementación propia de `libtermux_exec` (GPL v3).

## Estructura del Proyecto

- `/app`: Código fuente de la interfaz de usuario en Kotlin Compose.
- `/terminal_core`: Módulo compartido con la lógica de terminal, logging, gestión de rootfs y nativos (C++/JNI).
- `/terminal-view` & `/terminal-emulator`: Módulos encargados de la renderización y lógica de la terminal.
- `/rootfs`: Scripts de automatización para la descarga y despliegue del Tarball de Debian.

## Hoja de Ruta

Consulta [Hoja de ruta.txt](./Hoja%20de%20ruta.txt) para conocer los objetivos actuales y futuros del proyecto.

## Licencia

Este proyecto es software libre y está bajo la licencia **GNU GPL v3**. Puedes consultar el texto legal completo en el archivo [LICENSE](./LICENSE).

Para ver los detalles de las excepciones de las librerías de la terminal (Apache 2.0), PRoot y los componentes de Material 3, revisa el archivo de [Avisos de Terceros](./NOTICE.md).

*Aviso: Este es un proyecto independiente y no está afiliado, respaldado ni asociado oficialmente con el equipo de Termux.*
