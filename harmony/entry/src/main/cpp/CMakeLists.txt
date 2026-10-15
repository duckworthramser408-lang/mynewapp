cmake_minimum_required(VERSION 3.5.0)
project(g23_harmony LANGUAGES CXX)
add_subdirectory(../../../../../core g23-core)
# core target is linked statically into the N-API library to avoid a name clash.
set_target_properties(g23 PROPERTIES OUTPUT_NAME g23_engine)
add_library(g23_napi SHARED bridge.cpp)
set_target_properties(g23_napi PROPERTIES OUTPUT_NAME g23)
target_link_libraries(g23_napi PUBLIC g23 libace_napi.z.so)
