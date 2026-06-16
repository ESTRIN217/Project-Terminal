cmake_minimum_required(VERSION 3.22.1)
project("terminal_core")

add_library(
    terminal_core
    SHARED
    terminal_jni.cpp
    extract_jni.cpp
)

add_library(
    termux_exec
    SHARED
    termux_exec.cpp
)

find_library(
    log-lib
    log
)

find_library(
    dl-lib
    dl
)

target_link_libraries(
    terminal_core
    ${log-lib}
    ${dl-lib}
)

target_link_libraries(
    termux_exec
    ${log-lib}
)