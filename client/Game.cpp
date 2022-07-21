#include "Game.hpp"

Game::Game():
    m_Window(sf::VideoMode(1280, 720), "ImGui + SFML = <3")
{
    m_Window.setVerticalSyncEnabled(true);
    m_Window.setKeyRepeatEnabled(false);
    m_Window.setFramerateLimit(60);

    ImGui::SFML::Init(m_Window);
}

Game::~Game()
{
    ImGui::SFML::Shutdown();
}

void Game::Run()
{

}

/*

int main() {
    sf::RenderWindow window(sf::VideoMode(1280, 720), "ImGui + SFML = <3");
    window.setFramerateLimit(60);
    ImGui::SFML::Init(window);

    sf::CircleShape shape(100.f);
    shape.setFillColor(sf::Color::Green);

    sf::Clock deltaClock;
    while (window.isOpen()) {
        sf::Event event;
        while (window.pollEvent(event)) {
            ImGui::SFML::ProcessEvent(event);

            if (event.type == sf::Event::Closed) {
                window.close();
            }
        }

        ImGui::SFML::Update(window, deltaClock.restart());

        ImGui::Begin("Hello, world!");
        ImGui::Button("Look at this pretty button");
        ImGui::End();

        ImGui::ShowDemoWindow();

        window.clear();
        window.draw(shape);
        ImGui::SFML::Render(window);
        window.display();
    }

    ImGui::SFML::Shutdown();

    return 0;
}
*/