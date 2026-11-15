// Isolated host test: NO Android native libraries or renderer loading.
#include "m8-callback-gate.hpp"
#include <atomic>
#include <cassert>
#include <chrono>
#include <future>
#include <thread>
#include <utility>

using anvildesk::m8::CallbackGate;
using namespace std::chrono_literals;

int main() {
    {
        CallbackGate gate;
        auto first = gate.enter();
        assert(first);
        auto moved = std::move(first);
        assert(!first && moved);
        moved.release();
        moved.release(); // double release is harmless
        gate.close_and_wait();
        gate.close_and_wait(); // repeated close is harmless
        assert(gate.is_closing());
        assert(!gate.enter());
    }
    {
        CallbackGate gate;
        auto first = gate.enter();
        auto second = gate.enter();
        assert(first && second);
        std::promise<void> finished;
        auto done = finished.get_future();
        std::thread closer([&] {
            gate.close_and_wait();
            finished.set_value();
        });
        // Bounded wait only for the closing flag; the test must not hang
        // indefinitely on an unexpectedly broken close implementation.
        const auto deadline = std::chrono::steady_clock::now() + 5s;
        while (!gate.is_closing() && std::chrono::steady_clock::now() < deadline)
            std::this_thread::yield();
        assert(gate.is_closing());
        assert(!gate.enter());
        assert(done.wait_for(20ms) == std::future_status::timeout);
        first.release();
        assert(done.wait_for(20ms) == std::future_status::timeout);
        second.release();
        assert(done.wait_for(5s) == std::future_status::ready);
        closer.join();
        assert(!gate.enter());
    }
    {
        CallbackGate gate;
        std::atomic<bool> running{true};
        std::atomic<int> entered{0};
        std::thread worker([&] {
            while (running.load()) {
                auto lease = gate.enter();
                if (lease) ++entered;
            }
        });
        gate.close_and_wait();
        assert(!gate.enter());
        running = false;
        worker.join();
        assert(gate.is_closing());
    }
}
