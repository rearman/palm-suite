cmake_minimum_required(VERSION 3.25)
project(palm_desktop CXX)

# The C++ port of Palm Desktop. core/ ports desktop-rs/core (the port of sync-core): one static library per
# module, so each module and its tests build alone. A module whose source is not written yet is skipped.
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
# compile_commands.json for clangd (.clangd names the build folder).
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
if(NOT CMAKE_BUILD_TYPE)
	set(CMAKE_BUILD_TYPE Debug)
endif()
add_compile_options(-Wall -Wextra -Wpedantic -Werror)

find_package(OpenSSL REQUIRED)
find_package(ZLIB REQUIRED)
find_package(CURL REQUIRED)
find_package(nlohmann_json REQUIRED)
find_package(doctest REQUIRED)

# The modules in dependency order, each with the modules and libraries it links.
set(PALM_MODULES text json days crypto logic model client codec backup ical vcard runner session)
set(DEPS_text)
set(DEPS_json palm_text nlohmann_json::nlohmann_json)
set(DEPS_days)
set(DEPS_crypto palm_text OpenSSL::Crypto ZLIB::ZLIB)
set(DEPS_logic)
set(DEPS_model palm_days palm_text)
set(DEPS_client palm_crypto palm_json CURL::libcurl)
set(DEPS_codec palm_json palm_model)
set(DEPS_backup palm_json palm_model palm_text)
set(DEPS_ical palm_days palm_model palm_text)
set(DEPS_vcard palm_ical palm_model palm_text)
set(DEPS_runner palm_client palm_codec palm_crypto palm_logic palm_model)
set(DEPS_session palm_client palm_runner)

enable_testing()
add_library(palm_test_main OBJECT core/tests/main.cpp)
target_link_libraries(palm_test_main PUBLIC doctest::doctest)

foreach(m IN LISTS PALM_MODULES)
	if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/core/src/${m}.cpp)
		add_library(palm_${m} STATIC core/src/${m}.cpp)
		target_include_directories(palm_${m} PUBLIC core/include)
		target_link_libraries(palm_${m} PUBLIC ${DEPS_${m}})
	endif()
endforeach()

# Each core/tests/test_<name>.cpp is its own executable; its first line names the modules it links:
# // links: model codec
file(GLOB PALM_TESTS CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/core/tests/test_*.cpp)
foreach(path IN LISTS PALM_TESTS)
	get_filename_component(name ${path} NAME_WE)
	file(STRINGS ${path} first LIMIT_COUNT 1)
	string(REGEX REPLACE "^// links: *" "" links "${first}")
	separate_arguments(links)
	list(TRANSFORM links PREPEND palm_)
	add_executable(${name} ${path})
	target_include_directories(${name} PRIVATE core/tests)
	target_link_libraries(${name} PRIVATE palm_test_main nlohmann_json::nlohmann_json ${links})
	target_compile_definitions(${name} PRIVATE PALM_TESTDATA="${CMAKE_CURRENT_SOURCE_DIR}/../sync-core/testdata")
	add_test(NAME ${name} COMMAND ${name})
endforeach()
