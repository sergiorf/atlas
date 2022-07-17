#include "config.h"
#include <SFML/GpuPreference.hpp>
#include "Game.hpp"

using namespace std;

// on windows causes warnings that cannot be suppressed
SFML_DEFINE_DISCRETE_GPU_PREFERENCE

int main()
{
    Game game;
    game.run();
    return 0;
}

