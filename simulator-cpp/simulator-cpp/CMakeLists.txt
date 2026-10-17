# CMakeList.txt : CMake project for simulator-cpp, include source and define
# project specific logic here.
#

# Add source to this project's executable.
add_executable (simulator-cpp "simulator-cpp.cpp" "simulator-cpp.h")

if (CMAKE_VERSION VERSION_GREATER 3.12)
  set_property(TARGET simulator-cpp PROPERTY CXX_STANDARD 20)
endif()

# TODO: Add tests and install targets if needed.
