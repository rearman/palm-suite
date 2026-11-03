cmake_minimum_required(VERSION 3.25)
project(palm_desktop C CXX)

# The C++ port of Palm Desktop. core/ ports desktop-rs/core (the port of sync-core) and app/ ports
# desktop-rs/app: one static library per module, so each module and its tests build alone. A module whose
# source is not written yet is skipped.
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
# compile_commands.json for clangd (.clangd names the build folder).
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
if(NOT CMAKE_BUILD_TYPE)
	set(CMAKE_BUILD_TYPE Debug)
endif()

find_package(OpenSSL REQUIRED)
find_package(ZLIB REQUIRED)
find_package(CURL REQUIRED)
find_package(nlohmann_json REQUIRED)
find_package(doctest REQUIRED)

# FLTK 1.4.5, the release fltk-rs 1.5.23 bundles, built as fltk-sys builds it: no OpenGL, no GDI+ (so 1 px
# lines stay sharp), bundled libpng and libjpeg, the system zlib (crypto links it too), and on Linux Wayland
# with Cairo and Pango, falling back to X11. For an offline build, point FETCHCONTENT_SOURCE_DIR_FLTK at a
# copy of the 1.4.5 source, e.g. the one in ~/.cargo/registry/src/*/fltk-sys-1.5.23/cfltk/fltk.
include(FetchContent)
foreach(option FLTK_BUILD_TEST FLTK_BUILD_EXAMPLES FLTK_BUILD_FLUID FLTK_BUILD_FLTK_OPTIONS FLTK_BUILD_GL
		FLTK_BUILD_HTML_DOCS FLTK_BUILD_PDF_DOCS FLTK_GRAPHICS_GDIPLUS FLTK_USE_SYSTEM_LIBPNG FLTK_USE_SYSTEM_LIBJPEG
		FLTK_USE_LIBDECOR_GTK FLTK_USE_SYSTEM_LIBDECOR)
	set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
set(FLTK_USE_SYSTEM_ZLIB ON CACHE BOOL "" FORCE)
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
	foreach(option FLTK_BACKEND_WAYLAND FLTK_GRAPHICS_CAIRO FLTK_USE_PANGO)
		set(${option} ON CACHE BOOL "" FORCE)
	endforeach()
endif()
FetchContent_Declare(fltk GIT_REPOSITORY https://github.com/fltk/fltk.git GIT_TAG release-1.4.5 GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(fltk)

# Warnings are errors in our code only: FLTK is added above, so it does not inherit these options.
add_compile_options(-Wall -Wextra -Wpedantic -Werror)

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

# The app modules without FLTK (app/src), after the core ones.
set(PALM_APP_MODULES platform settings store)
set(DEPS_platform palm_days)
set(DEPS_settings palm_platform palm_text)
set(DEPS_store palm_platform palm_codec palm_crypto palm_json palm_model palm_runner palm_text nlohmann_json::nlohmann_json)

# The FLTK modules (app/src), in dependency order. A module of more than one source lists them in SRCS_<m>:
# draw and theme, and the Date Book views, call each other. Each links the core and app libraries above and
# FLTK; DEPS_<m> names the FLTK modules it uses.
set(PALM_UI_MODULES assets draw msg mnemonics shot platform_ui dialogs combo text_edit split sync category records
	note_dialog date_picker printing sync_dialog window memo address_dialog date_shared todo address event_dialog
	options date find transfer)
set(PALM_UI_COMMON palm_store palm_settings palm_platform palm_session palm_ical palm_vcard palm_backup fltk::fltk
	fltk::images)
set(SRCS_draw draw.cpp theme.cpp)
set(SRCS_msg msg.cpp view.cpp)
# date.rs is ported in two files: date.cpp (the helpers, Scroller, TypeField, Drag, and the drawing) and
# date_view.cpp (impl DateView), sharing the private helpers through app/src/date_internal.hpp.
set(SRCS_date date.cpp date_view.cpp week.cpp month.cpp)
set(DEPS_draw palm_assets)
set(DEPS_dialogs palm_draw palm_mnemonics palm_platform_ui)
set(DEPS_combo palm_draw palm_msg)
set(DEPS_text_edit palm_draw palm_msg)
set(DEPS_split palm_draw palm_msg)
set(DEPS_sync palm_msg)
set(DEPS_category palm_dialogs palm_draw palm_msg)
set(DEPS_records palm_draw palm_msg palm_text_edit)
set(DEPS_note_dialog palm_dialogs palm_draw palm_text_edit)
set(DEPS_date_picker palm_dialogs palm_draw)
set(DEPS_printing palm_dialogs palm_draw)
set(DEPS_sync_dialog palm_dialogs palm_msg)
set(DEPS_window palm_dialogs palm_draw palm_mnemonics palm_msg palm_text_edit)
set(DEPS_memo palm_category palm_combo palm_dialogs palm_draw palm_msg palm_records palm_split palm_text_edit)
set(DEPS_address_dialog palm_category palm_combo palm_dialogs palm_draw palm_msg palm_note_dialog palm_text_edit)
set(DEPS_date_shared palm_combo palm_dialogs palm_draw palm_note_dialog)
set(DEPS_todo palm_category palm_combo palm_date_picker palm_dialogs palm_draw palm_msg palm_note_dialog palm_records
	palm_split palm_text_edit)
set(DEPS_address palm_address_dialog palm_category palm_combo palm_dialogs palm_draw palm_msg palm_records palm_split
	palm_text_edit)
set(DEPS_event_dialog palm_combo palm_date_picker palm_date_shared palm_dialogs palm_draw palm_msg palm_note_dialog
	palm_text_edit)
set(DEPS_options palm_date_shared palm_dialogs)
set(DEPS_date palm_address palm_address_dialog palm_category palm_combo palm_date_picker palm_date_shared palm_draw
	palm_event_dialog palm_msg palm_records palm_text_edit palm_todo)
set(DEPS_find palm_address palm_dialogs palm_draw palm_msg)
set(DEPS_transfer palm_address palm_dialogs palm_msg)

# The Palm Desktop 3.1.1 bitmaps of the Swing app, embedded as byte arrays: assets::png(name) gives the bytes
# of icons/<name>.png (see palm/assets.hpp).
file(GLOB PALM_ICONS CONFIGURE_DEPENDS
	${CMAKE_CURRENT_SOURCE_DIR}/../desktop/src/main/resources/com/rearman/palmdesktop/icons/*.png)
set(assets_cpp ${CMAKE_CURRENT_BINARY_DIR}/generated/assets_data.cpp)
set(assets_text "// Generated by CMakeLists.txt from desktop/src/main/resources/.../icons. Do not edit.\n")
string(APPEND assets_text "#include \"palm/assets.hpp\"\n\n#include <array>\n\nnamespace palm::assets {\n\nnamespace {\n\n")
set(assets_table "")
set(index 0)
foreach(icon IN LISTS PALM_ICONS)
	get_filename_component(stem ${icon} NAME_WE)
	file(READ ${icon} hex HEX)
	string(REGEX REPLACE "([0-9a-f][0-9a-f])" "0x\\1," bytes "${hex}")
	string(APPEND assets_text "constexpr unsigned char icon_${index}[] = {${bytes}};\n")
	string(APPEND assets_table "\tAsset{\"${stem}\", icon_${index}},\n")
	math(EXPR index "${index} + 1")
endforeach()
string(APPEND assets_text "\n} // namespace\n\nstd::span<const Asset> all() {\n")
string(APPEND assets_text "\tstatic const std::array<Asset, ${index}> table{{\n${assets_table}\t}};\n\treturn table;\n}\n\n")
string(APPEND assets_text "} // namespace palm::assets\n")
file(CONFIGURE OUTPUT ${assets_cpp} CONTENT "${assets_text}" @ONLY)
set(SRCS_assets assets.cpp ${assets_cpp})

enable_testing()
add_library(palm_test_main OBJECT core/tests/main.cpp)
target_link_libraries(palm_test_main PUBLIC doctest::doctest)

foreach(m IN LISTS PALM_MODULES PALM_APP_MODULES PALM_UI_MODULES)
	if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/core/src/${m}.cpp)
		set(dir core)
	elseif(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/app/src/${m}.cpp OR DEFINED SRCS_${m})
		set(dir app)
	else()
		continue()
	endif()
	if(NOT DEFINED SRCS_${m})
		set(SRCS_${m} ${m}.cpp)
	endif()
	# A multi-source module builds once all its sources exist.
	set(sources "")
	set(missing FALSE)
	foreach(src IN LISTS SRCS_${m})
		if(IS_ABSOLUTE ${src})
			list(APPEND sources ${src})
		elseif(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/${dir}/src/${src})
			list(APPEND sources ${dir}/src/${src})
		else()
			set(missing TRUE)
		endif()
	endforeach()
	if(missing)
		continue()
	endif()
	add_library(palm_${m} STATIC ${sources})
	target_include_directories(palm_${m} PUBLIC core/include app/include)
	if(m IN_LIST PALM_UI_MODULES)
		target_link_libraries(palm_${m} PUBLIC ${DEPS_${m}} ${PALM_UI_COMMON})
	else()
		target_link_libraries(palm_${m} PUBLIC ${DEPS_${m}})
	endif()
endforeach()

# The app: app/src/main.cpp, a GUI program on Windows (no console window).
if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/app/src/main.cpp)
	add_executable(palm-desktop WIN32 app/src/main.cpp)
	list(TRANSFORM PALM_UI_MODULES PREPEND palm_ OUTPUT_VARIABLE ui_libs)
	target_link_libraries(palm-desktop PRIVATE ${ui_libs})
endif()

# Each core/tests/test_<name>.cpp and app/tests/test_<name>.cpp is its own executable; its first line names
# the modules it links: // links: model codec
file(GLOB PALM_TESTS CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/core/tests/test_*.cpp ${CMAKE_CURRENT_SOURCE_DIR}/app/tests/test_*.cpp)
foreach(path IN LISTS PALM_TESTS)
	get_filename_component(name ${path} NAME_WE)
	file(STRINGS ${path} first LIMIT_COUNT 1)
	string(REGEX REPLACE "^// links: *" "" links "${first}")
	separate_arguments(links)
	list(TRANSFORM links PREPEND palm_)
	add_executable(${name} ${path})
	target_include_directories(${name} PRIVATE core/tests app/tests)
	target_link_libraries(${name} PRIVATE palm_test_main nlohmann_json::nlohmann_json ${links})
	target_compile_definitions(${name} PRIVATE PALM_TESTDATA="${CMAKE_CURRENT_SOURCE_DIR}/../sync-core/testdata"
		PALM_FIXTURES="${CMAKE_CURRENT_SOURCE_DIR}/../desktop-rs/app/tests/fixtures")
	add_test(NAME ${name} COMMAND ${name})
endforeach()
