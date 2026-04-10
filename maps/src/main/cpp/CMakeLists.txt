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
target_link_libraries(offlinerouter ${log-lib})