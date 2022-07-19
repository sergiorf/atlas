set(IMGUI_PATH ${CMAKE_SOURCE_DIR}/submodules/imgui)
set(IMGUI_DIR ${CMAKE_SOURCE_DIR}/submodules/imgui)

# ------ Core -------
add_library(im-core 
    ${IMGUI_PATH}/imgui.cpp
    ${IMGUI_PATH}/imgui_draw.cpp
    ${IMGUI_PATH}/imgui_widgets.cpp
    ${IMGUI_PATH}/imgui_tables.cpp
)
target_include_directories(im-core 
    PUBLIC
    $<BUILD_INTERFACE:${IMGUI_PATH}>
    $<INSTALL_INTERFACE:imgui.h>
    $<INSTALL_INTERFACE:imgui_internal.h>
)
# namespaced alias
add_library(${project}::im-core ALIAS im-core)

add_subdirectory(imgui-sfml)
