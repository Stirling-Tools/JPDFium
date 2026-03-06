cmake_minimum_required(VERSION 3.20)
project(jpdfium-native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Platform detection
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    set(JPDFIUM_OS "linux")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Darwin")
    set(JPDFIUM_OS "darwin")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Windows")
    set(JPDFIUM_OS "windows")
else()
    message(WARNING "Unknown OS: ${CMAKE_SYSTEM_NAME}, defaulting to linux")
    set(JPDFIUM_OS "linux")
endif()

if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64|ARM64")
    set(JPDFIUM_ARCH "arm64")
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64|amd64")
    set(JPDFIUM_ARCH "x64")
else()
    message(WARNING "Unknown arch: ${CMAKE_SYSTEM_PROCESSOR}, defaulting to x64")
    set(JPDFIUM_ARCH "x64")
endif()

set(JPDFIUM_PLATFORM "${JPDFIUM_OS}-${JPDFIUM_ARCH}")
message(STATUS "JPDFium target platform: ${JPDFIUM_PLATFORM}")

set(NATIVES_RESOURCES
    ${CMAKE_SOURCE_DIR}/../jpdfium-natives/jpdfium-natives-${JPDFIUM_PLATFORM}/src/main/resources/natives/${JPDFIUM_PLATFORM})

# Library output naming
if(JPDFIUM_OS STREQUAL "windows")
    set(JPDFIUM_LIB_NAME "jpdfium")
    set(JPDFIUM_LIB_EXTENSION ".dll")
else()
    set(JPDFIUM_LIB_NAME "jpdfium")
    set(JPDFIUM_LIB_EXTENSION "${CMAKE_SHARED_LIBRARY_SUFFIX}")
endif()

# Off by default - stub is used for Java-layer unit tests.
# To use real PDFium:
#   cmake -DJPDFIUM_USE_PDFIUM=ON -DPDFIUM_DIR=/path/to/pdfium-linux-x64 ..
option(JPDFIUM_USE_PDFIUM "Build against real PDFium (requires PDFIUM_DIR)" OFF)

# Stub library (always built for testing)

add_library(jpdfium_stub SHARED bridge/src/jpdfium_stub.cpp)
target_include_directories(jpdfium_stub PUBLIC bridge/include)
set_target_properties(jpdfium_stub PROPERTIES
    OUTPUT_NAME "jpdfium_stub"
    POSITION_INDEPENDENT_CODE ON)


if(JPDFIUM_USE_PDFIUM)
    if(NOT DEFINED PDFIUM_DIR)
        message(FATAL_ERROR "PDFIUM_DIR must point to a pdfium-binaries extraction, e.g. /opt/pdfium")
    endif()

    # Typical bblanchon/pdfium-binaries layout:
    #   $PDFIUM_DIR/include/   - public headers
    #   $PDFIUM_DIR/lib/       - libpdfium.a (or libpdfium.so)
    set(PDFIUM_INCLUDE_DIR "${PDFIUM_DIR}/include")
    set(PDFIUM_LIB_DIR     "${PDFIUM_DIR}/lib")

    # Try static first, then shared
    find_library(PDFIUM_LIB
        NAMES pdfium
        PATHS "${PDFIUM_LIB_DIR}"
        NO_DEFAULT_PATH)
    if(NOT PDFIUM_LIB)
        message(FATAL_ERROR "Could not find libpdfium in ${PDFIUM_LIB_DIR}")
    endif()

    add_library(jpdfium SHARED bridge/src/jpdfium_document.cpp bridge/src/jpdfium_advanced.cpp)
    target_include_directories(jpdfium
        PUBLIC  bridge/include
        PRIVATE "${PDFIUM_INCLUDE_DIR}")
    target_link_libraries(jpdfium PRIVATE "${PDFIUM_LIB}")

    # Native library dependencies (all required for full functionality)
    find_package(PkgConfig REQUIRED)

    # PCRE2 - JIT-compiled regex for pattern matching
    pkg_check_modules(PCRE2 REQUIRED IMPORTED_TARGET libpcre2-8)
    target_link_libraries(jpdfium PRIVATE PkgConfig::PCRE2)
    target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_PCRE2)

    # FreeType - font extraction, classification, width repair
    find_package(Freetype REQUIRED)
    target_link_libraries(jpdfium PRIVATE Freetype::Freetype)
    target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_FREETYPE)

    # HarfBuzz - text shaping + hb-subset for font subsetting
    pkg_check_modules(HARFBUZZ REQUIRED IMPORTED_TARGET harfbuzz harfbuzz-subset)
    target_link_libraries(jpdfium PRIVATE PkgConfig::HARFBUZZ)
    target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_HARFBUZZ)

    # ICU4C - Unicode normalization, BiDi, sentence segmentation
    pkg_check_modules(ICU REQUIRED IMPORTED_TARGET icu-uc icu-i18n)
    target_link_libraries(jpdfium PRIVATE PkgConfig::ICU)
    target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_ICU)

    # qpdf - PDF structure manipulation, stream replacement
    pkg_check_modules(QPDF REQUIRED IMPORTED_TARGET libqpdf)
    target_link_libraries(jpdfium PRIVATE PkgConfig::QPDF)
    target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_QPDF)

    # pugixml - XMP metadata XML parsing
    pkg_check_modules(PUGIXML REQUIRED IMPORTED_TARGET pugixml)
    target_link_libraries(jpdfium PRIVATE PkgConfig::PUGIXML)
    target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_PUGIXML)

    # libunibreak - grapheme cluster boundaries
    pkg_check_modules(UNIBREAK REQUIRED IMPORTED_TARGET libunibreak)
    target_link_libraries(jpdfium PRIVATE PkgConfig::UNIBREAK)
    target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_UNIBREAK)

    message(STATUS "JPDFium native libraries: PCRE2, FreeType, HarfBuzz, ICU4C, qpdf, pugixml, libunibreak")

    set_target_properties(jpdfium PROPERTIES
        OUTPUT_NAME "jpdfium"
        POSITION_INDEPENDENT_CODE ON)

    # Platform-specific linker flags
    if(JPDFIUM_OS STREQUAL "linux")
        target_link_options(jpdfium PRIVATE -Wl,--no-undefined)
    elseif(JPDFIUM_OS STREQUAL "darwin")
        target_link_options(jpdfium PRIVATE -Wl,-undefined,error)
    endif()

    add_custom_command(TARGET jpdfium POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E make_directory ${NATIVES_RESOURCES}
        COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:jpdfium> ${NATIVES_RESOURCES}/$<TARGET_FILE_NAME:jpdfium>
        COMMENT "Copied lib${JPDFIUM_LIB_NAME}${JPDFIUM_LIB_EXTENSION} → jpdfium-natives-${JPDFIUM_PLATFORM} resources")

    # Also copy libpdfium shared lib if it exists (needed at runtime)
    if(EXISTS "${PDFIUM_LIB_DIR}/libpdfium${CMAKE_SHARED_LIBRARY_SUFFIX}")
        add_custom_command(TARGET jpdfium POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy
                "${PDFIUM_LIB_DIR}/libpdfium${CMAKE_SHARED_LIBRARY_SUFFIX}"
                ${NATIVES_RESOURCES}/
            COMMENT "Copied libpdfium → natives resources")
    endif()

else()
    # Stub target is the default "jpdfium" for Java tests
    add_library(jpdfium ALIAS jpdfium_stub)

    add_custom_command(TARGET jpdfium_stub POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E make_directory ${NATIVES_RESOURCES}
        COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:jpdfium_stub> ${NATIVES_RESOURCES}/libjpdfium.so
        COMMENT "Copied stub libjpdfium.so → jpdfium-natives-${JPDFIUM_PLATFORM} resources")
endif()

# Install target
if(JPDFIUM_USE_PDFIUM)
    install(TARGETS jpdfium
        LIBRARY DESTINATION lib
        RUNTIME DESTINATION bin)
endif()
install(DIRECTORY bridge/include/ DESTINATION include)
