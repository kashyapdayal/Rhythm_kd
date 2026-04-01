// [SIPHON_CUSTOM_ENGINE]
#pragma once

#include <atomic>
#include <array>
#include <cstddef>
#include <algorithm>

// Lock-free single-producer, single-consumer (SPSC) ring buffer
template<typename T, size_t N>
class RingBuffer {
    static_assert((N & (N - 1)) == 0, "N must be a power of 2 for fast modulo arithmetic");

public:
    RingBuffer() : head(0), tail(0) {}

    // Decoder thread calls this
    bool push(const T* data, size_t count) {
        size_t current_tail = tail.load(std::memory_order_acquire);
        size_t current_head = head.load(std::memory_order_relaxed);
        
        size_t available_space = N - (current_head - current_tail);
        if (available_space < count) {
            return false; // Not enough space
        }

        for (size_t i = 0; i < count; ++i) {
            buf[(current_head + i) & (N - 1)] = data[i];
        }

        head.store(current_head + count, std::memory_order_release);
        return true;
    }

    // USB Transfer thread calls this
    bool pop(T* data, size_t count) {
        size_t current_head = head.load(std::memory_order_acquire);
        size_t current_tail = tail.load(std::memory_order_relaxed);
        
        size_t available_data = current_head - current_tail;
        if (available_data < count) {
            return false; // Not enough data (underrun)
        }

        for (size_t i = 0; i < count; ++i) {
            data[i] = buf[(current_tail + i) & (N - 1)];
        }

        tail.store(current_tail + count, std::memory_order_release);
        return true;
    }

    size_t available() const {
        return head.load(std::memory_order_acquire) - tail.load(std::memory_order_acquire);
    }
    
    void flush() {
        head.store(0, std::memory_order_release);
        tail.store(0, std::memory_order_release);
    }

private:
    std::array<T, N> buf;
    alignas(64) std::atomic<size_t> head; // written by producer
    alignas(64) std::atomic<size_t> tail; // read by consumer
};
