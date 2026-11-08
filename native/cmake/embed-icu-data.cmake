file(READ "${INPUT}" _hex HEX)
string(LENGTH "${_hex}" _hex_len)
math(EXPR _size "${_hex_len} / 2")
string(REGEX REPLACE "([0-9a-f][0-9a-f])" "0x\\1," _bytes "${_hex}")
string(REGEX REPLACE "((0x[0-9a-f][0-9a-f],){32})" "\\1\n" _bytes "${_bytes}")
file(WRITE "${OUTPUT}"
    "extern \"C\" {\n"
    "alignas(16) extern const unsigned char jpdfium_icu_common_data[${_size}];\n"
    "alignas(16) const unsigned char jpdfium_icu_common_data[${_size}] = {\n${_bytes}\n};\n"
    "}\n")
