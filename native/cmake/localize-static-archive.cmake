if(MSVC)
    execute_process(
        COMMAND "${NM}" -g --defined-only "${ARCHIVE}"
        OUTPUT_VARIABLE _symbols
        RESULT_VARIABLE _rc)
    if(NOT _rc EQUAL 0)
        message(FATAL_ERROR "${NM} failed on ${ARCHIVE}")
    endif()
    string(REPLACE "\n" ";" _lines "${_symbols}")
    set(_renames "")
    set(_seen "")
    foreach(_line IN LISTS _lines)
        if(NOT _line MATCHES "^[0-9a-fA-F]+ ([A-Z]) (.+)$")
            continue()
        endif()
        set(_type "${CMAKE_MATCH_1}")
        set(_name "${CMAKE_MATCH_2}")
        if(_type STREQUAL "T" AND _name MATCHES "${KEEP_REGEX}" AND NOT _name MATCHES "${DROP_REGEX}")
            continue()
        endif()
        if(_name IN_LIST _seen)
            continue()
        endif()
        list(APPEND _seen "${_name}")
        string(APPEND _renames "${_name} jpdfium_local_${_name}\n")
    endforeach()
    file(WRITE "${OUTPUT}.renames.txt" "${_renames}")
    execute_process(
        COMMAND "${OBJCOPY}" "--redefine-syms=${OUTPUT}.renames.txt" "${ARCHIVE}" "${OUTPUT}"
        RESULT_VARIABLE _rc)
    if(NOT _rc EQUAL 0)
        message(FATAL_ERROR "renaming internal symbols of ${ARCHIVE} failed")
    endif()
    return()
endif()

set(_partial "${OUTPUT}.partial.o")
set(_keep "${OUTPUT}.keep.txt")
if(APPLE)
    set(_whole "-Wl,-all_load" "${ARCHIVE}")
else()
    set(_whole "-Wl,--whole-archive" "${ARCHIVE}" "-Wl,--no-whole-archive")
endif()
execute_process(
    COMMAND "${CXX}" ${FLAGS} -r -nostdlib -o "${_partial}" ${_whole}
    RESULT_VARIABLE _rc)
if(NOT _rc EQUAL 0)
    message(FATAL_ERROR "partial link of ${ARCHIVE} failed")
endif()
execute_process(
    COMMAND "${NM}" -g "${_partial}"
    OUTPUT_VARIABLE _symbols
    RESULT_VARIABLE _rc)
if(NOT _rc EQUAL 0)
    message(FATAL_ERROR "${NM} failed on ${_partial}")
endif()
string(REPLACE "\n" ";" _lines "${_symbols}")
set(_kept "")
foreach(_line IN LISTS _lines)
    if(NOT _line MATCHES "^[0-9a-fA-F]+ ([A-Z]) (.+)$")
        continue()
    endif()
    set(_type "${CMAKE_MATCH_1}")
    set(_name "${CMAKE_MATCH_2}")
    if(_type STREQUAL "T" AND _name MATCHES "${KEEP_REGEX}" AND NOT _name MATCHES "${DROP_REGEX}")
        string(APPEND _kept "${_name}\n")
    endif()
endforeach()
file(WRITE "${_keep}" "${_kept}")
if(APPLE)
    execute_process(
        COMMAND "${CXX}" ${FLAGS} -r -nostdlib -o "${OUTPUT}" "${_partial}"
            "-Wl,-exported_symbols_list,${_keep}"
        RESULT_VARIABLE _rc)
else()
    execute_process(
        COMMAND "${OBJCOPY}" "--keep-global-symbols=${_keep}" "${_partial}" "${OUTPUT}"
        RESULT_VARIABLE _rc)
endif()
if(NOT _rc EQUAL 0)
    message(FATAL_ERROR "localizing symbols of ${ARCHIVE} failed")
endif()
file(REMOVE "${_partial}")
