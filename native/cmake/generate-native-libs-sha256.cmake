# Generate native-libs.sha256 covering exactly the libraries named in
# native-libs.txt.
# Called as: cmake -DNATIVES_DIR=<dir> -DLIB_SUFFIX=<.so/.dylib/.dll> -P generate-native-libs-sha256.cmake
#
# The runtime loader refuses to load natives whose manifest is missing, so the
# stub bundle needs the same integrity manifest the real natives get from
# writeNativeManifest in the Gradle natives conventions.

if(NOT EXISTS "${NATIVES_DIR}/native-libs.txt")
    message(FATAL_ERROR "generate-native-libs-sha256: no native-libs.txt in ${NATIVES_DIR}")
endif()

file(STRINGS "${NATIVES_DIR}/native-libs.txt" LIB_NAMES)

set(MANIFEST "")
foreach(LIB_NAME ${LIB_NAMES})
    string(STRIP "${LIB_NAME}" LIB_NAME)
    # Skip blank lines and the "# ..." header the txt manifest starts with.
    if(LIB_NAME STREQUAL "" OR LIB_NAME MATCHES "^#")
        continue()
    endif()
    set(LIB_PATH "${NATIVES_DIR}/${LIB_NAME}")
    if(NOT EXISTS "${LIB_PATH}")
        message(FATAL_ERROR "generate-native-libs-sha256: listed library missing: ${LIB_NAME}")
    endif()
    file(SHA256 "${LIB_PATH}" LIB_HASH)
    string(APPEND MANIFEST "${LIB_HASH}  ${LIB_NAME}\n")
endforeach()

file(WRITE "${NATIVES_DIR}/native-libs.sha256" "${MANIFEST}")
