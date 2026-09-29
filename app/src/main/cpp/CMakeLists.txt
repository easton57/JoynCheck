cmake_minimum_required(VERSION 3.22.1)
project(joyncheck_native)

add_library(joyncheck_native SHARED
    joyncheck_native.cpp
)

find_library(log-lib log)

target_link_libraries(joyncheck_native
    ${log-lib}
)
