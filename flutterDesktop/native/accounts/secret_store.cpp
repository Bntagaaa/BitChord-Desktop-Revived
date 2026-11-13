// Isolated OS-secret helper. Secret values travel only over stdin/stdout pipes.
// Never print a value, path, OS exception, cookie or provider response on stderr.
#include <algorithm>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <vector>
#ifdef _WIN32
#include <windows.h>
#include <wincrypt.h>
#include <io.h>
#include <fcntl.h>
#else
#include <libsecret/secret.h>
#endif

namespace {
constexpr size_t kLimit = 256 * 1024;
constexpr int kUnavailable = 2, kMissing = 3, kInvalid = 64;
void wipe(std::string& value) {
  volatile char* p = value.empty() ? nullptr : &value[0];
  for (size_t i = 0; i < value.size(); ++i) p[i] = 0;
}
struct Sensitive {
  std::string value;
  ~Sensitive() { wipe(value); }
};
bool valid_key(const std::string& value) {
  return !value.empty() && value.size() <= 80 &&
    std::all_of(value.begin(), value.end(), [](unsigned char c) {
      return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
    });
}
bool read_input(Sensitive& input) {
  char buffer[4096];
  while (std::cin) {
    std::cin.read(buffer, sizeof(buffer));
    const auto count = std::cin.gcount();
    if (count <= 0) break;
    if (input.value.size() + size_t(count) > kLimit) return false;
    input.value.append(buffer, size_t(count));
    std::fill(std::begin(buffer), std::end(buffer), 0);
  }
  return !input.value.empty() && input.value.find('\0') == std::string::npos;
}
#ifdef _WIN32
int operate(const std::string& operation, const std::string& root,
            const std::string& key, Sensitive& input) {
  const auto directory = std::filesystem::u8path(root);
  if (!directory.is_absolute()) return kInvalid;
  std::error_code ec;
  if (std::filesystem::is_symlink(directory, ec)) return kInvalid;
  const auto file = directory / (key + ".dpapi");
  ec.clear();
  if (std::filesystem::is_symlink(file, ec)) return kInvalid;
  if (operation == "delete") {
    ec.clear(); std::filesystem::remove(file, ec);
    return ec ? kUnavailable : 0;
  }
  Sensitive entropy;
  entropy.value = "BitChord-Flutter-Account-v1|" + directory.u8string() + "|" + key;
  DATA_BLOB extra{DWORD(entropy.value.size()), reinterpret_cast<BYTE*>(entropy.value.data())};
  DATA_BLOB output{};
  if (operation == "write") {
    DATA_BLOB plaintext{DWORD(input.value.size()), reinterpret_cast<BYTE*>(input.value.data())};
    if (!CryptProtectData(&plaintext, L"BitChord desktop account", &extra,
                          nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &output)) return kUnavailable;
    ec.clear(); std::filesystem::create_directories(directory, ec);
    const auto temporary = directory / (key + ".new-" + std::to_string(GetCurrentProcessId()) + "-" + std::to_string(GetTickCount64()));
    bool saved = false;
    if (!ec) {
      const HANDLE handle = CreateFileW(temporary.c_str(), GENERIC_WRITE, 0, nullptr,
                                      CREATE_NEW, FILE_ATTRIBUTE_NORMAL, nullptr);
      if (handle != INVALID_HANDLE_VALUE) {
        DWORD written = 0;
        saved = WriteFile(handle, output.pbData, output.cbData, &written, nullptr) && written == output.cbData;
        if (saved) saved = FlushFileBuffers(handle) != FALSE;
        CloseHandle(handle);
        if (saved) saved = MoveFileExW(temporary.c_str(), file.c_str(),
                                      MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH) != FALSE;
      }
    }
    SecureZeroMemory(output.pbData, output.cbData); LocalFree(output.pbData);
    if (!saved) { ec.clear(); std::filesystem::remove(temporary, ec); }
    return saved ? 0 : kUnavailable;
  }
  ec.clear();
  if (!std::filesystem::exists(file, ec)) return ec ? kUnavailable : kMissing;
  const auto size = std::filesystem::file_size(file, ec);
  if (ec || size == 0 || size > kLimit * 2) return kUnavailable;
  Sensitive ciphertext;
  ciphertext.value.resize(size_t(size));
  std::ifstream stream(file, std::ios::binary);
  if (!stream.read(ciphertext.value.data(), std::streamsize(size))) return kUnavailable;
  DATA_BLOB encrypted{DWORD(size), reinterpret_cast<BYTE*>(ciphertext.value.data())};
  if (!CryptUnprotectData(&encrypted, nullptr, &extra, nullptr, nullptr,
                          CRYPTPROTECT_UI_FORBIDDEN, &output)) return kUnavailable;
  const bool valid = output.cbData > 0 && output.cbData <= kLimit;
  if (valid) std::cout.write(reinterpret_cast<char*>(output.pbData), output.cbData);
  SecureZeroMemory(output.pbData, output.cbData); LocalFree(output.pbData);
  return valid && std::cout.good() ? 0 : kUnavailable;
}
#else
const SecretSchema schema = {
  "com.music.bitchord.FlutterAccounts", SECRET_SCHEMA_NONE,
  {{"profile", SECRET_SCHEMA_ATTRIBUTE_STRING}, {"key", SECRET_SCHEMA_ATTRIBUTE_STRING},
   {nullptr, SECRET_SCHEMA_ATTRIBUTE_STRING}}
};
int operate(const std::string& operation, const std::string& root,
            const std::string& key, Sensitive& input) {
  if (!g_utf8_validate(root.c_str(), -1, nullptr)) return kInvalid;
  GError* error = nullptr;
  if (operation == "write") {
    if (!g_utf8_validate(input.value.data(), input.value.size(), nullptr)) return kInvalid;
    const bool stored = secret_password_store_sync(&schema, SECRET_COLLECTION_DEFAULT,
      "BitChord desktop accounts", input.value.c_str(), nullptr, &error,
      "profile", root.c_str(), "key", key.c_str(), nullptr);
    if (error) g_error_free(error);
    return stored ? 0 : kUnavailable;
  }
  if (operation == "delete") {
    secret_password_clear_sync(&schema, nullptr, &error,
      "profile", root.c_str(), "key", key.c_str(), nullptr);
    const bool failed = error != nullptr;
    if (error) g_error_free(error);
    return failed ? kUnavailable : 0;
  }
  char* value = secret_password_lookup_sync(&schema, nullptr, &error,
    "profile", root.c_str(), "key", key.c_str(), nullptr);
  if (error) { g_error_free(error); if (value) secret_password_free(value); return kUnavailable; }
  if (!value) return kMissing;
  const auto length = std::char_traits<char>::length(value);
  const bool valid = length > 0 && length <= kLimit;
  if (valid) std::cout.write(value, std::streamsize(length));
  secret_password_free(value);
  return valid && std::cout.good() ? 0 : kUnavailable;
}
#endif
} // namespace
int main(int argc, char** argv) {
#ifdef _WIN32
  _setmode(_fileno(stdin), _O_BINARY); _setmode(_fileno(stdout), _O_BINARY);
#endif
  try {
    if (argc != 4) return kInvalid;
    const std::string operation(argv[1]), root(argv[2]), key(argv[3]);
    if ((operation != "read" && operation != "write" && operation != "delete") ||
        !valid_key(key) || root.empty() || root.size() > 4096) return kInvalid;
    Sensitive input;
    if (operation == "write" && !read_input(input)) return kInvalid;
    return operate(operation, root, key, input);
  } catch (...) { return kUnavailable; }
}
