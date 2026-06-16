#!/usr/bin/env bash
# Build riproducibile di CSBwin (Dungeon Master / Chaos Strikes Back) per Linux.
#
# - Compila il motore CSBwin (src 20190702) con g++.
# - Linka DINAMICAMENTE contro una SDL2 con i backend audio (alsa/pulse/pipewire),
#   con rpath $ORIGIN: l'eseguibile trova libSDL2-2.0.so.0 accanto a se' stesso
#   (pacchetto auto-contenuto, esportabile, senza LD_LIBRARY_PATH).
# - Headers SDL2 dal progetto Blitz3D; la .so runtime e' bundle in questa cartella.
#
# Modifiche rispetto al sorgente originale:
#   * Bridge.cpp/.h        - "ponte" Fase 0: espone lo stato del mondo in JSON
#   * CSBlinux.cpp         - chiama Bridge_Tick() dopo ogni tick
#   * LinCSBUI.cpp         - mixer audio nativo SDL2 (sostituisce SDL_mixer assente)
#                            + fix UB di LIN_PlaySound (mancava il return)
set -e

ROOT=/home/rufio72/blitx3d_linux
SRC="$ROOT/Dungeon_Master/csb/CSBwin_SRC_20190702/src"
PLAY="$ROOT/Dungeon_Master/play_DungeonMaster"
SDLINC="$ROOT/deps/sdl/tree/include"
SDLSO="$PLAY/libSDL2-2.0.so.0"          # SDL2 con audio, bundle accanto al binario

GLIBCF="$(pkg-config --cflags glib-2.0) -I/usr/lib/glib-2.0/include"
FORCE="-include cstring -include cstdio -include cstdlib"   # header impliciti per g++ moderno
CXXFLAGS="-O -fsigned-char -D_LINUX -DRGB655 -DSDL20 -I$SDLINC $GLIBCF $FORCE"

cd "$SRC"
echo "[build] compilo gli oggetti..."
make -j"$(nproc)" \
  gincludes="-I$SDLINC $GLIBCF $FORCE -fsigned-char" \
  glibs="$SDLSO $(pkg-config --libs glib-2.0) -lpthread -ldl -lm" >/dev/null

echo "[build] linko (dinamico, rpath \$ORIGIN)..."
g++ *.o "$SDLSO" $(pkg-config --libs glib-2.0) -lpthread -ldl -lm \
    -Wl,-rpath,'$ORIGIN' -o "$PLAY/CSBwin"

echo "[build] fatto: $PLAY/CSBwin"
readelf -d "$PLAY/CSBwin" | grep -iE "RUNPATH|NEEDED.*SDL"
