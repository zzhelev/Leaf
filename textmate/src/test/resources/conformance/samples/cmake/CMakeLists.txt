cmake_minimum_required(VERSION 3.20)
project(leaf_askpass VERSION 1.0.0 LANGUAGES C)

# The askpass helper.
option(LEAF_STATIC "Link statically" OFF)
set(CMAKE_C_STANDARD 11)

add_executable(leaf-askpass src/askpass.c)
target_compile_definitions(leaf-askpass PRIVATE VERSION="${PROJECT_VERSION}")

if(LEAF_STATIC AND NOT APPLE)
  target_link_options(leaf-askpass PRIVATE -static)
endif()

foreach(dir IN ITEMS include src)
  message(STATUS "Source folder: ${CMAKE_CURRENT_SOURCE_DIR}/${dir}")
endforeach()

install(TARGETS leaf-askpass DESTINATION bin)
