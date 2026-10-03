cmake_minimum_required(VERSION 3.22.1)

project("openreadera")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LIBMOBI_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/libmobi")
set(LIBMOBI_SRC "${LIBMOBI_ROOT}/src")
set(LIBMOBI_TOOLS "${LIBMOBI_ROOT}/tools")

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LIBMOBI_SRC}
    ${LIBMOBI_TOOLS}
)

set(LIBMOBI_SOURCES
    ${LIBMOBI_SRC}/buffer.c
    ${LIBMOBI_SRC}/compression.c
    ${LIBMOBI_SRC}/debug.c
    ${LIBMOBI_SRC}/encryption.c
    ${LIBMOBI_SRC}/index.c
    ${LIBMOBI_SRC}/memory.c
    ${LIBMOBI_SRC}/meta.c
    ${LIBMOBI_SRC}/opf.c
    ${LIBMOBI_SRC}/parse_rawml.c
    ${LIBMOBI_SRC}/read.c
    ${LIBMOBI_SRC}/sha1.c
    ${LIBMOBI_SRC}/structure.c
    ${LIBMOBI_SRC}/util.c
    ${LIBMOBI_SRC}/write.c
    ${LIBMOBI_SRC}/xmlwriter.c
    ${LIBMOBI_SRC}/miniz.c
    ${LIBMOBI_TOOLS}/common.c
)

add_library(
    openreadera_jni
    SHARED
    mobi-converter.cpp
    ${LIBMOBI_SOURCES}
)
target_compile_definitions(openreadera_jni PRIVATE USE_MINIZ USE_XMLWRITER)


find_library(log-lib log)

target_link_libraries(
    openreadera_jni
    ${log-lib}
)
