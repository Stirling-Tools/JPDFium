// object_table.h - generation-checked handle table. No PDFium dependency, so tests use it alone.
#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

#include "jpdfium_api.h"

namespace jpdf {

enum class ObjectType : uint8_t { None = 0, Document = 1, Page = 2, Bitmap = 3, View = 4 };
constexpr size_t kObjectTypeCount = 5;

struct Object {
    explicit Object(ObjectType t) noexcept : type(t) {}
    virtual ~Object() = default;
    Object(const Object&) = delete;
    Object& operator=(const Object&) = delete;
    const ObjectType type;
};

// Token = [type:8][generation:24][slot:32]. A slot whose generation is exhausted is retired.
template <unsigned kGenerationBits>
class BasicObjectTable {
    static_assert(kGenerationBits >= 1 && kGenerationBits <= 24);

   public:
    static constexpr uint32_t kMaxGeneration = (1u << kGenerationBits) - 1;

    jpdf_status insert(std::shared_ptr<Object> object, jpdf_handle* out) {
        if (!object || !out) return JPDF_E_INVALID_ARGUMENT;
        std::lock_guard<std::mutex> lock(mutex_);
        uint32_t index;
        if (!free_.empty()) {
            index = free_.back();
            free_.pop_back();
        } else {
            if (slots_.size() >= kMaxSlots) return JPDF_E_LIMIT;
            // Reserving here keeps retire() allocation-free, so it cannot fail halfway.
            free_.reserve(slots_.size() + 1);
            slots_.emplace_back();
            index = static_cast<uint32_t>(slots_.size() - 1);
        }
        Slot& slot = slots_[index];
        slot.type = object->type;
        slot.object = std::move(object);
        slot.live = true;
        live_[static_cast<size_t>(slot.type)]++;
        *out = encode(slot.type, slot.generation, index);
        return JPDF_OK;
    }

    // Copies a strong reference; the caller's copy is its lease for the duration of the call.
    jpdf_status resolve(jpdf_handle token, ObjectType expected,
                        std::shared_ptr<Object>* out) const {
        std::lock_guard<std::mutex> lock(mutex_);
        uint32_t index = 0;
        jpdf_status st = locate(token, expected, &index);
        if (st == JPDF_OK && out) *out = slots_[index].object;
        return st;
    }

    template <typename T>
    jpdf_status resolve_as(jpdf_handle token, ObjectType expected, std::shared_ptr<T>* out) const {
        std::shared_ptr<Object> object;
        jpdf_status st = resolve(token, expected, &object);
        if (st == JPDF_OK) *out = std::static_pointer_cast<T>(std::move(object));
        return st;
    }

    // Invalidates the token and hands the object back so the caller destroys it where it must.
    jpdf_status retire(jpdf_handle token, ObjectType expected, std::shared_ptr<Object>* out) {
        std::shared_ptr<Object> released;  // declared first so it is destroyed after unlock
        std::lock_guard<std::mutex> lock(mutex_);
        uint32_t index = 0;
        jpdf_status st = locate(token, expected, &index);
        if (st != JPDF_OK) return st;
        Slot& slot = slots_[index];
        released = std::move(slot.object);
        if (out) *out = released;
        slot.live = false;
        live_[static_cast<size_t>(slot.type)]--;
        if (slot.generation == kMaxGeneration) {
            retired_slots_++;  // never reissued, so no old token can become valid again
        } else {
            slot.generation++;
            free_.push_back(index);
        }
        return JPDF_OK;
    }

    template <typename T>
    jpdf_status retire_as(jpdf_handle token, ObjectType expected, std::shared_ptr<T>* out) {
        std::shared_ptr<Object> object;
        jpdf_status st = retire(token, expected, &object);
        if (st == JPDF_OK) *out = std::static_pointer_cast<T>(std::move(object));
        return st;
    }

    uint64_t live(ObjectType type) const {
        std::lock_guard<std::mutex> lock(mutex_);
        return live_[static_cast<size_t>(type)];
    }

    uint64_t retired_slots() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return retired_slots_;
    }

    static uint32_t slot_of(jpdf_handle token) noexcept {
        return static_cast<uint32_t>(token & 0xFFFFFFFFu);
    }

   private:
    static constexpr size_t kMaxSlots = 0xFFFFFFFFu;

    struct Slot {
        uint32_t generation = 1;
        ObjectType type = ObjectType::None;
        bool live = false;
        std::shared_ptr<Object> object;
    };

    static jpdf_handle encode(ObjectType type, uint32_t generation, uint32_t index) noexcept {
        return (static_cast<uint64_t>(type) << 56) | (static_cast<uint64_t>(generation) << 32) |
               index;
    }

    jpdf_status locate(jpdf_handle token, ObjectType expected, uint32_t* index) const {
        const auto type = static_cast<uint8_t>(token >> 56);
        const auto generation = static_cast<uint32_t>((token >> 32) & 0xFFFFFFu);
        const uint32_t slot_index = slot_of(token);
        if (token == 0 || type == 0 || type >= kObjectTypeCount || generation == 0) {
            return JPDF_E_INVALID_HANDLE;
        }
        if (type != static_cast<uint8_t>(expected)) return JPDF_E_WRONG_HANDLE_TYPE;
        if (slot_index >= slots_.size()) return JPDF_E_INVALID_HANDLE;
        const Slot& slot = slots_[slot_index];
        if (generation > slot.generation) return JPDF_E_INVALID_HANDLE;
        if (generation != slot.generation || !slot.live) return JPDF_E_STALE_HANDLE;
        if (slot.type != expected) return JPDF_E_INTERNAL;
        *index = slot_index;
        return JPDF_OK;
    }

    mutable std::mutex mutex_;
    std::vector<Slot> slots_;
    std::vector<uint32_t> free_;
    std::array<uint64_t, kObjectTypeCount> live_{};
    uint64_t retired_slots_ = 0;
};

using ObjectTable = BasicObjectTable<24>;

// The process-wide table every binding resolves through.
ObjectTable& objects() noexcept;

}  // namespace jpdf
