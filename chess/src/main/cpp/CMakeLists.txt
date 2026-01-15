cmake_minimum_required(VERSION 3.18.1)

project("stockfish")

add_library(stockfishjni SHARED
        # Stockfish files:
        bridge.cpp
        stockfish/engine.cpp
        # stockfish/main.cpp
        stockfish/timeman.cpp
        stockfish/evaluate.cpp
        stockfish/position.cpp
        stockfish/uci.cpp
        stockfish/movepick.cpp
        stockfish/benchmark.cpp
        stockfish/ucioption.cpp
        stockfish/search.cpp
        stockfish/thread.cpp
        stockfish/score.cpp
        stockfish/memory.cpp
        stockfish/syzygy/tbprobe.cpp
        stockfish/nnue/network.cpp
        stockfish/nnue/nnue_misc.cpp
        stockfish/nnue/nnue_accumulator.cpp
        stockfish/nnue/features/half_ka_v2_hm.cpp
        stockfish/nnue/features/full_threats.cpp
        stockfish/bitboard.cpp
        stockfish/tt.cpp
        stockfish/movegen.cpp
        stockfish/misc.cpp
        stockfish/tune.cpp
)

# Path to the Android NDK
set(ANDROID_NDK $ENV{ANDROID_NDK_HOME})

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--build-id=none,-s")

# This strips the absolute path of the builder's machine
add_compile_options("-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.")

target_include_directories(stockfishjni PRIVATE
        stockfish
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/stockfish
)

target_compile_definitions(stockfishjni PRIVATE __ANDROID__ ANDROID NNUE_EMBEDDING_OFF)