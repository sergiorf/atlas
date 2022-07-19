#pragma once

#include "headers.hpp"

class Game
{
public:
    sf::Text text;
    sf::RenderWindow window;
    sf::RectangleShape map;
    sf::Texture crosshair_texture;
    sf::Sprite crosshair;
    sf::UdpSocket UDPsocket;
    sf::TcpSocket TCPsocket;
    sf::Packet packet;
    //sf::IpAddress server_address;

    sf::Clock imguiClock;
    sf::Clock udpClock;
    sf::Clock pingClock;
    sf::Clock fpsClock;

    int fps;
    int ping;
    int ping_avg;
    int ping_count;
    float crosshair_distance;
    unsigned short server_port;

   // std::unordered_map<std::string, Player> players;
   // std::stack<Projectile> projectiles;
    std::vector<sf::RectangleShape> lines;

    std::string ID;
    std::string password;
    std::string join_error;

    static sf::Font font;
    static std::string nickname;
    static bool isGameRunning;
    static bool quit;
    static long tar_size;
    static char *tarFile;

    Game();
    ~Game();

    void play();
    void run();
    void imguiMapUI();
    void draw();
    void generateID();
    void countFpsAndPing();

    void send();
    void sendJoinRequest();
    void sendPlayerInput();

    void listen();
    void receiveJoinError();
    void receivePlayersList();
    void receiveProjectilesList();
    void receiveObjectsList();
    void receiveGameState();

    std::pair<sf::Int8, sf::Int8> mainPlayerInputMovement() const;
    std::pair<bool, bool> mainPlayerInputMouse() const;
    double mainPlayerInputRotation() const;
    sf::Int8 mainPlayerInputSlot() const;

    template <class T> static
    void loadAsset(T &asset, const char *path)
    {
        long file_size = 0;
        const char *asset_file = dxTarRead(tarFile, tar_size, path, &file_size);

        assert(file_size > 0);
        assert(file_size % 512 > 0);

        if (!asset.loadFromMemory(asset_file, tar_size))
            Game::quit = true;
    }
};
