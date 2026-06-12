cmake_minimum_required(VERSION 3.18.1)
project(OctapadAudioEngine)

set(OBOE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/app/src/main/cpp/oboe")

# Try to download Oboe if not present
if(NOT EXISTS "${OBOE_DIR}")
    message(STATUS "Downloading Oboe library...")
    execute_process(
        COMMAND git clone --depth 1 --branch main https://github.com/google/oboe.git ${OBOE_DIR}
        WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/app/src/main/cpp"
    )
endif()

# Add Oboe as subdirectory
add_subdirectory("${OBOE_DIR}" oboe)

# Native library
add_library(
    oboe_audio_engine
    SHARED
    app/src/main/cpp/oboe_audio_engine.cpp
)

# Include Oboe headers
target_include_directories(
    oboe_audio_engine
    PUBLIC
    "${OBOE_DIR}/include"
)

# Link against Oboe
target_link_libraries(
    oboe_audio_engine
    oboe
    OpenSLES
    log
)

# C++17
set_target_properties(oboe_audio_engine PROPERTIES CXX_STANDARD 17)
