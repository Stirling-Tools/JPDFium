// simdutf.cpp - Compile the amalgamated implementation
// This file exists solely to compile the simdutf implementation once.
// All other translation units should only include the header without
// SIMDUTF_IMPLEMENTATION to see only declarations.

#define SIMDUTF_IMPLEMENTATION
#include "simdutf.h"
