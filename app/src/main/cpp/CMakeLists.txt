cmake_minimum_required(VERSION 3.22.1)
project(usbaudio CXX)

add_library(usbaudio SHARED usbaudio.cpp phonedirect.cpp dsd.cpp)
target_compile_options(usbaudio PRIVATE -O2 -Wall)
# The DSD filter loops: vectorised (float sums may be reordered; that only changes rounding).
set_source_files_properties(dsd.cpp PROPERTIES COMPILE_FLAGS "-O3 -ffast-math")
target_link_libraries(usbaudio log aaudio dl)
