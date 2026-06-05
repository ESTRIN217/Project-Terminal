cmake_minimum_required(VERSION 3.22.1)
project("terminal_core")

add_library(
    terminal_core
    SHARED
    terminal_jni.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    terminal_core
    ${log-lib}
)