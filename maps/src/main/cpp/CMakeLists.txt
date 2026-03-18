cmake_minimum_required(VERSION 3.10.2)
project("offlinerouter")

add_library(offlinerouter SHARED native-lib.cpp scratchpad.cpp)

find_library(log-lib log)
target_link_libraries(offlinerouter ${log-lib})