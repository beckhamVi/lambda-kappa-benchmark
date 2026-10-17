# CMakeList.txt : Top-level CMake project file, do global configuration
# and include sub-projects here.
#
#cmake_minimum_required (VERSION 3.10)

# Enable Hot Reload for MSVC compilers if supported.
#if (POLICY CMP0141)
#  cmake_policy(SET CMP0141 NEW)
#  set(CMAKE_MSVC_DEBUG_INFORMATION_FORMAT "$<IF:$<AND:$<C_COMPILER_ID:MSVC>,$<CXX_COMPILER_ID:MSVC>>,$<$<CONFIG:Debug,RelWithDebInfo>:EditAndContinue>,$<$<CONFIG:Debug,RelWithDebInfo>:ProgramDatabase>>")
#endif()

#project ("simulator-cpp")

# Include sub-projects.
#add_subdirectory ("simulator-cpp")

cmake_minimum_required(VERSION 3.20)
project(event_simulator CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

find_package(RdKafka CONFIG REQUIRED)

add_executable(simulator src/main.cpp)
#target_link_libraries(simulator PRIVATE RdKafka::rdkafka++)
target_link_libraries(simulator PRIVATE RdKafka::rdkafka RdKafka::rdkafka++)
