# Generate native-libs.txt listing all shared libraries in dependency order.
# Called as: cmake -DNATIVES_DIR=<dir> -DLIB_SUFFIX=<.so/.dylib/.dll> -P generate-native-libs-txt.cmake

file(GLOB ALL_LIBS "${NATIVES_DIR}/*${LIB_SUFFIX}")

set(BRIDGE_LIBS "")
set(PDFIUM_LIBS "")
set(DEP_LIBS "")

foreach(LIB_PATH ${ALL_LIBS})
    get_filename_component(LIB_NAME "${LIB_PATH}" NAME)
    if(LIB_NAME MATCHES "^libjpdfium")
        list(APPEND BRIDGE_LIBS "${LIB_NAME}")
    elseif(LIB_NAME MATCHES "^libpdfium")
        list(APPEND PDFIUM_LIBS "${LIB_NAME}")
    else()
        list(APPEND DEP_LIBS "${LIB_NAME}")
    endif()
endforeach()

list(SORT DEP_LIBS)

# Write: dependencies first, then pdfium, then bridge
set(MANIFEST "# Native library load order (dependencies first)\n")
foreach(LIB ${DEP_LIBS})
    string(APPEND MANIFEST "${LIB}\n")
endforeach()
foreach(LIB ${PDFIUM_LIBS})
    string(APPEND MANIFEST "${LIB}\n")
endforeach()
foreach(LIB ${BRIDGE_LIBS})
    string(APPEND MANIFEST "${LIB}\n")
endforeach()

file(WRITE "${NATIVES_DIR}/native-libs.txt" "${MANIFEST}")
