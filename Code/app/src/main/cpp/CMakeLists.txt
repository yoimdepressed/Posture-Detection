cmake_minimum_required(VERSION 3.22.1)

project("uvccamera")

# Set C++ standard
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Create the native library
add_library(uvccamera SHARED
        uvc_camera.cpp
        v4l2_camera.cpp)

# Include directories
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

# Find required libraries
find_library(log-lib log)
find_library(android-lib android)

# Link libraries
target_link_libraries(uvccamera
        ${log-lib}
        ${android-lib})
