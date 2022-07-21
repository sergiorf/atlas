add_executable(atlas-client
  main.cpp
  Game.cpp
  Game.hpp
)

target_link_libraries(atlas-client
  PUBLIC
    ImGui-SFML::ImGui-SFML
)

include(Install.cmake)
