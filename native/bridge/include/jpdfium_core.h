// jpdfium_core.h - PDFium library init, destroy and authority, without PDFium headers.
#pragma once

#include <cstdint>

// Which serializer owns PDFium in this process; the first claim wins for the process lifetime.
enum class PdfiumAuthority : std::uint8_t { None = 0, Legacy = 1, Safe = 2 };
bool jpdfium_claim_authority(PdfiumAuthority who) noexcept;

// FPDF_InitLibrary/FPDF_DestroyLibrary without the authority claim; callers serialize. 0 = OK.
int jpdfium_init_pdfium_core(int renderer);
void jpdfium_destroy_pdfium_core() noexcept;
bool jpdfium_core_uses_skia() noexcept;
