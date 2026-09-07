#include "sfizz/ResourceSet.h"
#include <cassert>
#include <thread>

int main() {
    struct State { int opens = 0; int releases = 0; } state;
    const sfizz_resource_t index[] = {
        {"instruments/main.sfz", nullptr, 0},
        {"samples/Used.wav", nullptr, 0},
        {"samples/unused-huge.wav", nullptr, 0},
    };
    sfizz_resource_pack_t pack {2, "instruments/main.sfz", index, 3, &state,
        [](void* p) { ++static_cast<State*>(p)->releases; },
        [](void* p, const char* name, const void** bytes, size_t* size) -> int {
            ++static_cast<State*>(p)->opens;
            assert(std::string(name) != "samples/unused-huge.wav");
            *bytes = "data";
            *size = 4;
            return 1;
        }};
    {
        sfz::ResourceSet resources(pack);
        assert(state.opens == 0); // Even entry validation must not read bytes.
        assert(resources.find("samples/unused-huge.wav", true, false));
        assert(state.opens == 0); // Existence/case checks use only the index.
        assert(resources.find("instruments/main.sfz"));
        assert(state.opens == 1);
        std::thread a([&] { assert(resources.find("samples/used.wav")); });
        std::thread b([&] { assert(resources.find("samples/Used.wav")); });
        a.join(); b.join();
        assert(state.opens == 2); // One open per requested resource, including concurrent readers.
        assert(!resources.find("../../outside.wav"));
        assert(!resources.find("missing.wav"));
        assert(state.opens == 2);
        assert(state.releases == 0);
    }
    assert(state.releases == 1);
}
