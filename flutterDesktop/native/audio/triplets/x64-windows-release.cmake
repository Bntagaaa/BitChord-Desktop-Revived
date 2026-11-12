set(VCPKG_TARGET_ARCHITECTURE x64)
set(VCPKG_CRT_LINKAGE dynamic)
set(VCPKG_LIBRARY_LINKAGE dynamic)
# The FFI boundary never shares CRT/STL allocations; the replaceable release
# DLL also serves developer hosts. CI does not need a second codec build.
set(VCPKG_BUILD_TYPE release)
