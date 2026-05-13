cmake_minimum_required(VERSION 4.1.2)
project("offlinerouter")

add_library(offlinerouter SHARED native-lib.cpp)

target_compile_options(offlinerouter PRIVATE
        -O3                 # Maximum optimization
        -ffast-math         # Speed up coordinate math (breaks strict IEEE compliance)
        -fomit-frame-pointer # Frees up register x29 for general use
        -mbranch-protection=none # Removes NOPs used for pointer authentication
        -fno-plt            # Encourages direct calls over PLT jumps
)

if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_options(offlinerouter PRIVATE -march=armv8-a+simd+crypto)
endif()

find_library(log-lib log)
find_library(z-lib z)

# SQLite3 is not standard in Android NDK sysroot.
# Recommended approach is to bundle sqlite3.c/h (amalgamation).
find_library(sqlite-lib sqlite3)

if(sqlite-lib)
    target_link_libraries(offlinerouter ${log-lib} ${sqlite-lib} ${z-lib})
else()
    # Check if sqlite3.c is bundled in the source directory
    if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/sqlite3.c")
        # Update the library to include sqlite3.c
        set_target_properties(offlinerouter PROPERTIES SOURCES "native-lib.cpp;sqlite3.c")
        target_link_libraries(offlinerouter ${log-lib} ${z-lib})
    else()
        message(FATAL_ERROR "sqlite3 library not found in NDK and sqlite3.c is missing. "
                "Please download the SQLite amalgamation (sqlite3.c and sqlite3.h) "
                "from https://www.sqlite.org/download.html and place them in "
                "${CMAKE_CURRENT_SOURCE_DIR}")
    endif()
endif()