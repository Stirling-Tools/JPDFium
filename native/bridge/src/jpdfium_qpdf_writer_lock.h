// Shared coordination for qpdf writers.
//
// qpdf exposes the zlib compression level only as process-global Pl_Flate
// state. Writers that leave the level alone take a shared lock; a writer that
// requests an explicit level takes it exclusively and restores qpdf's default
// afterwards. This keeps concurrent jobs from recompressing each other's
// streams at the wrong level without serializing ordinary qpdf work.
#ifndef JPDFIUM_QPDF_WRITER_LOCK_H
#define JPDFIUM_QPDF_WRITER_LOCK_H

#ifdef JPDFIUM_HAS_QPDF

#include <cstdint>
#include <mutex>
#include <qpdf/Pl_Flate.hh>
#include <shared_mutex>

namespace jpdfium_qpdf_internal {

inline std::shared_mutex& writerMutex() {
    static std::shared_mutex mutex;
    return mutex;
}

class WriterLock {
   public:
    explicit WriterLock(int32_t compressionLevel) : level_(compressionLevel) {
        // 0 is accepted as a legacy spelling of "default": older JPDFium
        // callers passed it because the level used to be ignored.
        if (level_ > 0) {
            unique_ = std::unique_lock<std::shared_mutex>(writerMutex());
            Pl_Flate::setCompressionLevel(level_);
        } else {
            shared_ = std::shared_lock<std::shared_mutex>(writerMutex());
        }
    }

    ~WriterLock() {
        if (level_ > 0) Pl_Flate::setCompressionLevel(-1);
    }

    WriterLock(const WriterLock&) = delete;
    WriterLock& operator=(const WriterLock&) = delete;

   private:
    int32_t level_;
    std::unique_lock<std::shared_mutex> unique_;
    std::shared_lock<std::shared_mutex> shared_;
};

}  // namespace jpdfium_qpdf_internal

#endif  // JPDFIUM_HAS_QPDF

#endif  // JPDFIUM_QPDF_WRITER_LOCK_H
