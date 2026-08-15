// Minimal, header-free smoke test for a built PDFium shared library.
// dlopen()s libpdfium by path (resolving sibling component libs via its
// RUNPATH=$ORIGIN, exactly like the JVM's System.load) and exercises the core
// FPDF API: init -> open a PDF -> page count -> close. Proves the native both
// LOADS and RUNS on the host libc (the real value on musl/Alpine, where a
// glibc-built PDFium would not even load).
//
// Usage: pdfium_smoke <path/to/libpdfium.so> <path/to/test.pdf>
// Exit:  0 ok, 2 load/symbol error, 3 document load failed, 4 unexpected pages.
#include <stdio.h>
#include <dlfcn.h>

typedef void  (*init_fn)(void);
typedef void *(*load_fn)(const char *path, const char *password);
typedef int   (*pagecount_fn)(void *doc);
typedef void  (*close_fn)(void *doc);
typedef void  (*destroy_fn)(void);

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s <libpdfium.so> <test.pdf>\n", argv[0]);
        return 2;
    }

    void *h = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!h) {
        fprintf(stderr, "dlopen failed: %s\n", dlerror());
        return 2;
    }

    init_fn      FPDF_InitLibrary   = (init_fn)      dlsym(h, "FPDF_InitLibrary");
    load_fn      FPDF_LoadDocument  = (load_fn)      dlsym(h, "FPDF_LoadDocument");
    pagecount_fn FPDF_GetPageCount  = (pagecount_fn) dlsym(h, "FPDF_GetPageCount");
    close_fn     FPDF_CloseDocument = (close_fn)     dlsym(h, "FPDF_CloseDocument");
    destroy_fn   FPDF_DestroyLibrary= (destroy_fn)   dlsym(h, "FPDF_DestroyLibrary");
    if (!FPDF_InitLibrary || !FPDF_LoadDocument || !FPDF_GetPageCount
            || !FPDF_CloseDocument || !FPDF_DestroyLibrary) {
        fprintf(stderr, "dlsym failed: missing FPDF symbol\n");
        return 2;
    }

    FPDF_InitLibrary();
    void *doc = FPDF_LoadDocument(argv[2], NULL);
    if (!doc) {
        fprintf(stderr, "FPDF_LoadDocument failed for %s\n", argv[2]);
        FPDF_DestroyLibrary();
        return 3;
    }
    int pages = FPDF_GetPageCount(doc);
    printf("pages=%d\n", pages);
    FPDF_CloseDocument(doc);
    FPDF_DestroyLibrary();
    return pages >= 1 ? 0 : 4;
}
