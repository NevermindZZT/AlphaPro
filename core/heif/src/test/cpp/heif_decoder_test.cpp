#include "heif_decoder.h"
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void require(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}
void check(heif_error error) {
    if (error.code != heif_error_Ok) throw std::runtime_error(error.message);
}
std::vector<uint8_t> bytes(const std::string& path) {
    std::ifstream input(path, std::ios::binary);
    require(input.good(), "Missing fixture " + path);
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}
void verifySource(const std::string& path, heif_chroma expectedChroma) {
    auto context = std::unique_ptr<heif_context, decltype(&heif_context_free)>(heif_context_alloc(), heif_context_free);
    check(heif_context_read_from_file(context.get(), path.c_str(), nullptr));
    heif_image_handle* rawHandle = nullptr;
    check(heif_context_get_primary_image_handle(context.get(), &rawHandle));
    auto handle = std::unique_ptr<heif_image_handle, decltype(&heif_image_handle_release)>(rawHandle, heif_image_handle_release);
    require(heif_image_handle_get_luma_bits_per_pixel(handle.get()) == 10, "Fixture must be 10-bit");
    heif_image* rawImage = nullptr;
    check(heif_decode_image(handle.get(), &rawImage, heif_colorspace_YCbCr, heif_chroma_undefined, nullptr));
    alpha::Image image(rawImage, heif_image_release);
    require(heif_image_get_chroma_format(image.get()) == expectedChroma, "Fixture chroma mismatch");
    require(heif_image_get_bits_per_pixel_range(image.get(), heif_channel_Y) == 10, "Decoded source depth mismatch");
}
template<typename F> void expectFailure(F action, const std::string& expected) {
    bool failed = false;
    try { action(); }
    catch (const std::exception& error) {
        failed = true;
        require(std::string(error.what()).find(expected) != std::string::npos,
                "Wrong failure: " + std::string(error.what()));
    }
    require(failed, "Expected failure was not observed");
}
}
int main(int argc, char** argv) {
    try {
        require(argc == 2, "Usage: heif_decoder_test ASSET_DIRECTORY");
        const std::string root = argv[1];
        check(heif_init(nullptr));
        const auto file420 = root + "/synthetic-420-10bit.heic";
        const auto file422 = root + "/synthetic-422-10bit.heic";
        const auto large422 = root + "/synthetic-422-24mp.heic";
        verifySource(file420, heif_chroma_420);
        std::cout << "PASS actual source 10-bit 4:2:0\n";
        verifySource(file422, heif_chroma_422);
        std::cout << "PASS actual source 10-bit 4:2:2\n";
        for (const auto& path : {file420, file422}) {
            auto encoded = bytes(path);
            auto image = alpha::decodeHeif(nullptr, encoded.data(), encoded.size(), 2048,
                                          512ULL * 1024 * 1024, [] { return false; });
            require(heif_image_get_primary_width(image.get()) == 640 &&
                    heif_image_get_primary_height(image.get()) == 480, "Memory preview dimensions");
        }
        std::cout << "PASS memory previews 420 and 422\n";
        expectFailure([&] { alpha::decodeHeif(large422.c_str(), nullptr, 0, 2048,
                                              256ULL * 1024 * 1024, [] { return false; }); },
                      "Insufficient memory");
        std::cout << "PASS reproduced H2 256MiB budget rejection on 24MP 422\n";
        {
            auto image = alpha::decodeHeif(large422.c_str(), nullptr, 0, 2048,
                                           512ULL * 1024 * 1024, [] { return false; });
            require(heif_image_get_primary_width(image.get()) == 2048 &&
                    heif_image_get_primary_height(image.get()) == 1365, "Bounded file preview dimensions");
        }
        std::cout << "PASS 24MP 422 file preview returns bounded 2048x1365\n";
        expectFailure([&] { alpha::decodeHeif(file422.c_str(), nullptr, 0, 2048,
                                              512ULL * 1024 * 1024, [] { return true; }); }, "cancelled");
        std::cout << "PASS native cancellation\n";
        const uint8_t invalid[] = {0, 1, 2, 3};
        expectFailure([&] { alpha::decodeHeif(nullptr, invalid, sizeof invalid, 2048,
                                              512ULL * 1024 * 1024, [] { return false; }); }, "HEIF decode error");
        std::cout << "PASS malformed container rejected\n";
        expectFailure([&] { alpha::decodeHeif(file422.c_str(), nullptr, 0, 2048,
                                              512 * 1024, [] { return false; }); }, "limits");
        std::cout << "PASS invalid budget rejected\n";
        return 0;
    } catch (const std::exception& failure) {
        std::cerr << "FAIL " << failure.what() << '\n';
        return 1;
    }
}
