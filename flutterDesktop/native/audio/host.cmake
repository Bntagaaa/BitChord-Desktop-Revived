# Included after the official Flutter host defines INSTALL_BUNDLE_LIB_DIR.
set(BC_TESTS OFF CACHE BOOL "Build native tests separately" FORCE)
add_subdirectory("${CMAKE_CURRENT_LIST_DIR}" "${CMAKE_BINARY_DIR}/bitchord_audio")
add_dependencies(${BINARY_NAME} bitchord_audio)
install(TARGETS bitchord_audio LIBRARY DESTINATION "${INSTALL_BUNDLE_LIB_DIR}"
  RUNTIME DESTINATION "${INSTALL_BUNDLE_LIB_DIR}" COMPONENT Runtime)
install(TARGETS bitchord_audio_probe RUNTIME DESTINATION "${CMAKE_INSTALL_PREFIX}" COMPONENT Runtime)
if(WIN32)
  if(NOT DEFINED ENV{BC_DEPENDENCY_ROOT})
    message(FATAL_ERROR "Set BC_DEPENDENCY_ROOT to the pinned vcpkg x64-windows install")
  endif()
  install(DIRECTORY "$ENV{BC_DEPENDENCY_ROOT}/bin/" DESTINATION "${INSTALL_BUNDLE_LIB_DIR}"
    COMPONENT Runtime FILES_MATCHING PATTERN "*.dll")
  install(DIRECTORY "$ENV{BC_DEPENDENCY_ROOT}/share/ffmpeg/" DESTINATION "${CMAKE_INSTALL_PREFIX}/licenses/ffmpeg" COMPONENT Runtime)
  install(DIRECTORY "$ENV{BC_DEPENDENCY_ROOT}/share/sqlite3/" DESTINATION "${CMAKE_INSTALL_PREFIX}/licenses/sqlite3" COMPONENT Runtime)
endif()
install(FILES "${CMAKE_CURRENT_LIST_DIR}/NOTICE.md" DESTINATION "${CMAKE_INSTALL_PREFIX}/licenses" COMPONENT Runtime)
