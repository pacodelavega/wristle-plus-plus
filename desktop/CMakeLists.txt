cmake_minimum_required(VERSION 3.15)
project(wristle_service CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SOURCES
    wristle_service.cpp
    src/crypto/chacha20poly1305.cpp
    src/crypto/sha256_hmac.cpp
    src/input/text_injector.cpp
    src/net/wristle_server.cpp
)

add_executable(wristle_service ${SOURCES})

target_include_directories(wristle_service PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)

target_link_libraries(wristle_service PRIVATE
    ws2_32
    bcrypt
    advapi32
    user32
)

if (MSVC)
    target_compile_options(wristle_service PRIVATE /W4 /EHsc /O2)
else()
    target_compile_options(wristle_service PRIVATE -Wall -Wextra -O3)
    target_link_options(wristle_service PRIVATE -municode -static -static-libgcc -static-libstdc++)
endif()
