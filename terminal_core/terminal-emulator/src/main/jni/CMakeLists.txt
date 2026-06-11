cmake_minimum_required(VERSION 3.22.1)

# Nombre de tu proyecto nativo
project("libtermux_project")

# Define la librería dinámica y añade el archivo fuente .cpp
add_library(
    libtermux
    SHARED
    termux.cpp
)

# Inyecta las banderas de compilación que tenías originalmente
target_compile_options(libtermux PRIVATE
    -std=c++17
    -Wall
    -Wextra
    -Werror
    -Os
    -fno-stack-protector
)

# Inyecta las banderas del enlazador (linker)
target_link_options(libtermux PRIVATE
    -Wl,--gc-sections
)

# Vincula las librerías nativas estándar de Android (como logs si los usas)
find_library(log-lib log)
target_link_libraries(libtermux ${log-lib})
