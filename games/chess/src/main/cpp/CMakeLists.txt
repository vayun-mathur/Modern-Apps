cmake_minimum_required(VERSION 4.1.2)
project(stockfish_lib)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Stockfish engine sources live in the pinned git submodule. Keep the build
# config here in the parent repo (like the other native modules, e.g. maps) so
# the submodule stays pristine at its pinned commit and clean checkouts /
# reproducible builds pick up these flags.
set(SF ${CMAKE_CURRENT_SOURCE_DIR}/Stockfish-Library)

set(STOCKFISH_SOURCES
    ${SF}/src/attacks.cpp
    ${SF}/src/benchmark.cpp
    ${SF}/src/bitboard.cpp
    ${SF}/src/engine.cpp
    ${SF}/src/evaluate.cpp
    ${SF}/src/main.cpp
    ${SF}/src/memory.cpp
    ${SF}/src/misc.cpp
    ${SF}/src/movegen.cpp
    ${SF}/src/movepick.cpp
    ${SF}/src/nnue/features/full_threats.cpp
    ${SF}/src/nnue/features/half_ka_v2_hm.cpp
    ${SF}/src/nnue/network.cpp
    ${SF}/src/nnue/nnue_accumulator.cpp
    ${SF}/src/nnue/nnue_misc.cpp
    ${SF}/src/position.cpp
    ${SF}/src/score.cpp
    ${SF}/src/search.cpp
    ${SF}/src/syzygy/tbprobe.cpp
    ${SF}/src/thread.cpp
    ${SF}/src/timeman.cpp
    ${SF}/src/tt.cpp
    ${SF}/src/tune.cpp
    ${SF}/src/uci.cpp
    ${SF}/src/ucioption.cpp
    ${SF}/src/libstockfish.cpp
    ${SF}/src/bridge.cpp
)

add_library(stockfish SHARED ${STOCKFISH_SOURCES})

target_include_directories(stockfish PRIVATE
    ${SF}/src
    ${SF}/src/nnue
)

# Pin the version-string git info to the submodule's pinned commit so the binary
# does not embed __DATE__ (which changes every build and breaks reproducible
# builds). These mirror what the Stockfish Makefile derives from git for the
# pinned commit c2e562e7.
target_compile_definitions(stockfish PRIVATE
    STOCKFISH_LIBRARY
    NNUE_EMBEDDING_OFF
    __ANDROID__
    ANDROID
    GIT_SHA=c2e562e7
    GIT_DATE=20260619
)

target_compile_options(stockfish PRIVATE
        -O3                  # Maximum optimization
        -fomit-frame-pointer # Frees up register x29 for general use
        -fno-plt             # Encourages direct calls over PLT jumps
        -DNDEBUG
)

if(${ANDROID_ABI} MATCHES "arm.*")
    target_compile_options(stockfish PRIVATE -mbranch-protection=none)
endif()

if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_options(stockfish PRIVATE -march=armv8-a+simd+crypto)
endif()
