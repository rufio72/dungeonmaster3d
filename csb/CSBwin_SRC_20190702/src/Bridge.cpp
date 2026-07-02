// Bridge.cpp - Phase 0 "world-state bridge" for CSBwin.  See Bridge.h.
//
// Legge lo stato del mondo dal singolo oggetto globale `d` (DBank) e dai record
// del dungeon, usando ESCLUSIVAMENTE gli accessori gia' esposti dal motore:
//   d.partyX / d.partyY / d.partyFacing / d.partyLevel   (posizione party)
//   d.width / d.height / d.LoadedLevel / d.LevelCellFlags (livello corrente)
//   GetCelltypeRelative(facing,fwd,right,x,y)             (tipo di cella, ROOMTYPE)
//   RelativeXY(facing,fwd,right,&mx,&my)                  (cella rel -> assoluta)
//   FindFirstObject(mx,my) + GetDBRecordLink(obj)         (lista oggetti su cella)
//   obj.dbType() == dbMONSTER, GetRecordAddressDB4(obj)   (riconoscimento mostro)
//   DB4::monsterType() / DB4::invisible()                 (tipo / visibilita')
//
// Nessuna modifica alla logica: e' un lettore puro, deterministico, a costo
// trascurabile. Due output: la MAPPA statica del livello (una volta) e lo STATO
// dinamico per-tick (party + personaggi + mostri vicini). Vedi sotto.

#include "stdafx.h"
#include "UI.h"
#include "CSBTypes.h"
#include "Dispatch.h"
#include "Objects.h"
#include "CSB.h"
#include "Data.h"

#include <cstdio>
#include <sys/stat.h>

// MinGW's mkdir takes only a path (no POSIX mode argument).
#ifdef _WIN32
#include <direct.h>
#define mkdir(path, mode) _mkdir(path)
#endif

// Server TCP (Fase 2): solo POSIX per ora (su Windows servirebbe Winsock).
#ifndef _WIN32
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#endif

static void BridgeSrv_Tick(bool levelReady);   // definiti in fondo al file
static void BridgeSrv_PushState(void);

// ---- estrazione ritratti campioni ----------------------------------------
// I ritratti NON sono in un file grafico: sono incorporati nel record del
// personaggio (CHARDESC.portrait), 32x28 pixel a 4 bit (16 byte/riga, 28 righe),
// colori dalla palette ST a 16 voci. Qui esportiamo i ritratti dei 4 campioni
// attivi del party (d.CH16482[]) come PPM (poi convertibili in PNG).
static void STtoRGB(int c, int *r, int *g, int *b)
{
  // palette Atari ST: 0x0RGB, 3 bit per canale
  *r = ((c >> 8) & 7) * 255 / 7;
  *g = ((c >> 4) & 7) * 255 / 7;
  *b = ((c >> 0) & 7) * 255 / 7;
}

// Tutti e 24 i ritratti dei campioni stanno nella grafica numero 26 (atlante
// 8 colonne x 3 righe, ogni cella 32x29), in formato 4-bit CHUNKY (2 px/byte).
// Vedi AddCharacter(): copia da GetBasicGraphicAddress(26) con offset
// (charID%8)*32 , (charID/8)*29. Dumpiamo l'atlante grezzo + palette; lo
// slicing in 24 PNG lo fa lo script Python.
static int Bridge_ExportPortraits(void)
{
  mkdir("portraits", 0755);

  // dimensioni dall'header non-espanso della grafica 26
  ui8 *unexp = (ui8 *)d.ppUnExpandedGraphics[26];
  if (unexp == NULL) return 0;
  int aw = (int)(ui16)LE16(*(i16 *)unexp);
  int ah = (int)(ui16)LE16(*(i16 *)(unexp + 2));
  if (aw <= 0 || ah <= 0 || aw > 2048 || ah > 2048) return 0;
  int stride = ((aw + 15) & ~15) / 2;          // byte/riga (4-bit, 2px/byte)

  ui8 *atlas = (ui8 *)GetBasicGraphicAddress(26);
  if (atlas == NULL) return 0;

  FILE *f = fopen("portraits/atlas26.raw", "wb");
  if (f == NULL) return 0;
  fwrite(atlas, 1, (size_t)stride * ah, f);
  fclose(f);

  FILE *h = fopen("portraits/atlas26.txt", "w");
  if (h != NULL) { fprintf(h, "%d %d %d\n", aw, ah, stride); fclose(h); }

  // palette ST corrente -> 16 righe "r g b"
  FILE *pf = fopen("portraits/palette.txt", "w");
  if (pf != NULL)
  {
    for (int i = 0; i < 16; i++)
    {
      int r, g, b; STtoRGB((int)d.Palette11946.color[i], &r, &g, &b);
      fprintf(pf, "%d %d %d\n", r, g, b);
    }
    fclose(pf);
  }

  printf("[bridge] atlante ritratti (graphic 26): %dx%d stride=%d -> portraits/\n",
         aw, ah, stride);
  fflush(stdout);
  return 1;
}

// ---- pulsanti di MOVIMENTO (grafica 13) ----------------------------------
// I 6 pulsanti freccia di DM sono una sola grafica (vedi DrawMovementButtons:
// GetBasicGraphicAddress(13)). La decodifichiamo (chunky 4-bit, 2px/byte +
// palette ST) e la salviamo come BMP 24-bit, cosi' dm3d puo' LoadImage()-arla
// e disegnare le frecce IDENTICHE a quelle del 2D.
static int Bridge_ExportMoveButtons(void)
{
  const int N = 13;
  ui8 *unexp = (ui8 *)d.ppUnExpandedGraphics[N];
  if (unexp == NULL) return 0;
  int w = (int)(ui16)LE16(*(i16 *)unexp);
  int h = (int)(ui16)LE16(*(i16 *)(unexp + 2));
  if (w <= 0 || h <= 0 || w > 2048 || h > 2048) return 0;
  int stride = ((w + 15) & ~15) / 2;            // chunky 4-bit (2 px/byte)
  ui8 *gfx = (ui8 *)GetBasicGraphicAddress(N);
  if (gfx == NULL) return 0;

  // Decodifica PLANARE Atari ST a 4 bitplane (word a 16 bit big-endian, una per
  // plane, gruppi da 16 pixel) -> BMP 24-bit. Verificato che e' il formato giusto
  // per la grafica 13 (le altre basic-graphics seguono lo stesso schema planare).
  int pr[16], pg[16], pb[16];
  for (int i = 0; i < 16; i++)
  { int r, g, b; STtoRGB((int)d.Palette11946.color[i], &r, &g, &b); pr[i]=r; pg[i]=g; pb[i]=b; }

  FILE *f = fopen("mvbuttons.bmp", "wb");
  if (f == NULL) return 0;
  int rowbytes = (w * 3 + 3) & ~3;
  int imgsize  = rowbytes * h;
  ui8 hdr[54]; memset(hdr, 0, 54);
  hdr[0]='B'; hdr[1]='M';
  *(int *)(hdr + 2)  = 54 + imgsize;
  *(int *)(hdr + 10) = 54;
  *(int *)(hdr + 14) = 40;
  *(int *)(hdr + 18) = w;
  *(int *)(hdr + 22) = h;
  *(short *)(hdr + 26) = 1;
  *(short *)(hdr + 28) = 24;
  *(int *)(hdr + 34) = imgsize;
  fwrite(hdr, 1, 54, f);

  int groups = (w + 15) / 16;
  ui8 *row = (ui8 *)malloc(rowbytes);
  for (int y = h - 1; y >= 0; y--)            // BMP bottom-up
  {
    memset(row, 0, rowbytes);
    ui8 *r = gfx + (size_t)y * stride;
    for (int g = 0; g < groups; g++)
    {
      ui8 *b = r + g * 8;
      unsigned p0=(b[0]<<8)|b[1], p1=(b[2]<<8)|b[3], p2=(b[4]<<8)|b[5], p3=(b[6]<<8)|b[7];
      for (int p = 0; p < 16; p++)
      {
        int x = g*16 + p; if (x >= w) break;
        int bit = 15 - p;
        int idx = ((p0>>bit)&1) | (((p1>>bit)&1)<<1) | (((p2>>bit)&1)<<2) | (((p3>>bit)&1)<<3);
        row[x*3+0]=(ui8)pb[idx]; row[x*3+1]=(ui8)pg[idx]; row[x*3+2]=(ui8)pr[idx];
      }
    }
    fwrite(row, 1, rowbytes, f);
  }
  free(row);
  fclose(f);
  printf("[bridge] pulsanti movimento (graphic 13): %dx%d -> mvbuttons.bmp\n", w, h);
  fflush(stdout);
  return 1;
}

// ---- estrazione grafiche delle PORTE -------------------------------------
// Le ante delle porte sono basic-graphics (stesso formato dei ritratti: planare
// Atari ST a 4 bitplane).  Il motore le risolve per livello in d.DoorGraphic[3][2]
// (2 tipi x 3 distanze F1/F2/F3) a partire da base = 108 + 3*style.  Le
// decorazioni murali iniziano alla grafica 121, quindi il blocco-porte sta sotto.
// Verificato empiricamente: le ante stanno nelle grafiche 108..119 = 4 STILI
// (legno-borchie / assi-legno / ferro / fulmine) x 3 distanze (108=F1,109=F2,110=F3
// per lo stile 0, poi +3 per stile). 104..107 e 120 sono frammenti muro/scala e il
// set di font, NON porte. anchor.txt annota i 6 numeri in uso nel livello corrente.
// Output (cartella doors/, accanto all'eseguibile):
//   doors/g<N>.raw    pixel grezzi planari 4-bitplane
//   doors/doors.txt   righe "N width height stride"
//   doors/palette.txt 16 righe "r g b" (palette ST corrente)
//   doors/anchor.txt  i 6 numeri di grafica porta usati: "dist type N"
[[maybe_unused]] static int Bridge_ExportDoors(void)
{
  mkdir("doors", 0755);

  FILE *m = fopen("doors/doors.txt", "w");
  if (m == NULL) return 0;
  int saved = 0;

  for (int N = 108; N <= 119; N++)
  {
    ui8 *unexp = (ui8 *)d.ppUnExpandedGraphics[N];
    if (unexp == NULL) continue;
    int w = (int)(ui16)LE16(*(i16 *)unexp);
    int h = (int)(ui16)LE16(*(i16 *)(unexp + 2));
    if (w <= 0 || h <= 0 || w > 512 || h > 512) continue;

    int stride = ((w + 15) & ~15) / 2;          // byte/riga (4 bitplane, 16px/8byte)
    ui8 *gfx = (ui8 *)GetBasicGraphicAddress(N); // espande on-demand
    if (gfx == NULL) continue;

    char p[64];
    snprintf(p, sizeof(p), "doors/g%d.raw", N);
    FILE *f = fopen(p, "wb");
    if (f == NULL) continue;
    fwrite(gfx, 1, (size_t)stride * h, f);
    fclose(f);

    fprintf(m, "%d %d %d %d\n", N, w, h, stride);
    saved++;
  }
  fclose(m);

  // palette ST corrente
  FILE *pf = fopen("doors/palette.txt", "w");
  if (pf != NULL)
  {
    for (int i = 0; i < 16; i++)
    {
      int r, g, b; STtoRGB((int)d.Palette11946.color[i], &r, &g, &b);
      fprintf(pf, "%d %d %d\n", r, g, b);
    }
    fclose(pf);
  }

  // ancore: i 6 numeri di grafica porta realmente risolti per questo livello
  FILE *af = fopen("doors/anchor.txt", "w");
  if (af != NULL)
  {
    const char *dn[3] = { "F3", "F2", "F1" };
    for (int dist = 0; dist < 3; dist++)
      for (int type = 0; type < 2; type++)
        fprintf(af, "%s type%d %d\n", dn[dist], type, (int)d.DoorGraphic[dist][type]);
    fclose(af);
  }

  printf("[bridge] grafiche porte: %d salvate (108..119, 4 stili x 3 dist) -> doors/\n", saved);
  fflush(stdout);
  return saved;
}

// ---- helper condivisi per estrarre basic-graphics ------------------------
// Dump grezzo di una basic-graphic (formato planare ST 4-bitplane) in
// <folder>/g<N>.raw, con riga "N w h stride" nel manifest. Ritorna 1 se salvata.
static int Bridge_DumpGfx(const char *folder, FILE *manifest, int N)
{
  ui8 *unexp = (ui8 *)d.ppUnExpandedGraphics[N];
  if (unexp == NULL) return 0;
  int w = (int)(ui16)LE16(*(i16 *)unexp);
  int h = (int)(ui16)LE16(*(i16 *)(unexp + 2));
  if (w <= 0 || h <= 0 || w > 512 || h > 512) return 0;

  int stride = ((w + 15) & ~15) / 2;
  ui8 *gfx = (ui8 *)GetBasicGraphicAddress(N);
  if (gfx == NULL) return 0;

  char p[96];
  snprintf(p, sizeof(p), "%s/g%d.raw", folder, N);
  FILE *f = fopen(p, "wb");
  if (f == NULL) return 0;
  fwrite(gfx, 1, (size_t)stride * h, f);
  fclose(f);
  fprintf(manifest, "%d %d %d %d\n", N, w, h, stride);
  return 1;
}

// Scrive la palette ST corrente in <folder>/palette.txt (16 righe "r g b").
static void Bridge_DumpPalette(const char *folder)
{
  char p[96];
  snprintf(p, sizeof(p), "%s/palette.txt", folder);
  FILE *pf = fopen(p, "w");
  if (pf == NULL) return;
  for (int i = 0; i < 16; i++)
  {
    int r, g, b; STtoRGB((int)d.Palette11946.color[i], &r, &g, &b);
    fprintf(pf, "%d %d %d\n", r, g, b);
  }
  fclose(pf);
}

// ---- estrazione STIPITI (telai/assi laterali delle porte) -----------------
// I telai porta NON sono accessibili come basic-graphics (ppUnExpandedGraphics
// 77.. e' NULL: il motore li espande direttamente nei buffer pDoorBitmaps[0..7]
// e libera l'originale).  Quindi dumpiamo i BUFFER gia' espansi (formato planare
// ST 4-bitplane).  Le dimensioni stanno nei DoorFrameRect[]: uByte4=larghezza,
// uByte5=altezza (come usate in MakeMirror).  I buffer hanno dimensioni fisse
// (allocateMemory in CSBCode.cpp:10183-10190); le elenchiamo per il decoder, che
// puo' verificare width*height contro la dimensione del buffer.
//   pDoorBitmaps[]: 0=FrameRight F1, 1=EdgeFrame F0, 2=FrameLeft F1, 3=FrameLeft F2,
//                   4=FrameLeft F3, 5=FrameLeft F3L1, 6=TopTrack F1, 7=TopTrack F2
[[maybe_unused]] static int Bridge_ExportJambs(void)
{
  mkdir("jambs", 0755);
  static const int bufSize[8] = { 1504, 1968, 1504, 1560, 704, 688, 256, 144 };

  FILE *m = fopen("jambs/jambs.txt", "w");
  if (m == NULL) return 0;
  int saved = 0;
  for (int i = 0; i < 8; i++)
  {
    if (d.pDoorBitmaps[i] == NULL) continue;
    char p[96];
    snprintf(p, sizeof(p), "jambs/p%d.raw", i);
    FILE *f = fopen(p, "wb");
    if (f == NULL) continue;
    fwrite(d.pDoorBitmaps[i], 1, (size_t)bufSize[i], f);
    fclose(f);
    // dimensioni dal rect corrispondente (best-effort, il decoder le verifica)
    int w = d.DoorFrameRect[i].b.uByte4;
    int h = d.DoorFrameRect[i].b.uByte5;
    fprintf(m, "%d %d %d %d\n", i, w, h, bufSize[i]);
    saved++;
  }
  fclose(m);
  Bridge_DumpPalette("jambs");

  // dump completo degli 8 RectPos (8 byte ciascuno) per analisi offline
  FILE *rf = fopen("jambs/rects.txt", "w");
  if (rf != NULL)
  {
    for (int i = 0; i < 8; i++)
      fprintf(rf, "rect%d x1=%d x2=%d y1=%d y2=%d w=%d h=%d b6=%d b7=%d\n", i,
              d.DoorFrameRect[i].b.x1, d.DoorFrameRect[i].b.x2,
              d.DoorFrameRect[i].b.y1, d.DoorFrameRect[i].b.y2,
              d.DoorFrameRect[i].b.uByte4, d.DoorFrameRect[i].b.uByte5,
              d.DoorFrameRect[i].b.uByte6, d.DoorFrameRect[i].b.uByte7);
    fclose(rf);
  }
  printf("[bridge] stipiti porte: %d buffer pDoorBitmaps salvati -> jambs/\n", saved);
  fflush(stdout);
  return saved;
}

// ---- estrazione BOTTONI (attuatori sul muro: pulsanti/serrature) ----------
// Ogni decorazione murale tipo T usa le grafiche 121 + 2*T (2 per decorazione).
// I dispositivi che aprono le porte: interruttori/pulsanti (7,14,15,16,47,48,49,50,54),
// leve (44,45) e serrature/keyhole (5,17..32).  Tipo 54 -> grafica 121+2*54=229,
// quindi dumpiamo 121..235 (tutte le decorazioni); il decoder etichetta dal tipo.
[[maybe_unused]] static int Bridge_ExportButtons(void)
{
  mkdir("buttons", 0755);
  FILE *m = fopen("buttons/buttons.txt", "w");
  if (m == NULL) return 0;
  int saved = 0;
  for (int N = 121; N <= 235; N++) saved += Bridge_DumpGfx("buttons", m, N);
  fclose(m);
  Bridge_DumpPalette("buttons");
  printf("[bridge] bottoni/serrature: %d salvati (121..235) -> buttons/\n", saved);
  fflush(stdout);
  return saved;
}

// ---- etichette leggibili -------------------------------------------------

static const char *RoomTypeName(int t)
{
  switch (t)
  {
  case roomSTONE:       return "wall";
  case roomOPEN:        return "floor";
  case roomPIT:         return "pit";
  case roomSTAIRS:      return "stairs";
  case roomDOOR:        return "door";
  case roomTELEPORTER:  return "teleporter";
  case roomFALSEWALL:   return "falsewall";
  case roomDOOREDGE:    return "door_edge";
  case roomDOORFACING:  return "door_facing";
  case roomSTAIREDGE:   return "stair_edge";
  case roomSTAIRFACING: return "stair_facing";
  case roomEXTERIOR:    return "exterior";
  default:              return "unknown";
  }
}

static const char *MonsterName(int m)
{
  switch (m)
  {
  case mon_Scorpion:       return "Scorpion";
  case mon_SlimeDevil:     return "SlimeDevil";
  case mon_Giggler:        return "Giggler";
  case mon_FlyingEye:      return "FlyingEye";
  case mon_Hellhound:      return "Hellhound";
  case mon_Screamer:       return "Screamer";
  case mon_RockPile:       return "RockPile";
  case mon_Rive:           return "Rive";
  case mon_StoneGolem:     return "StoneGolem";
  case mon_Mummy:          return "Mummy";
  case mon_BlackFlame:     return "BlackFlame";
  case mon_Skeleton:       return "Skeleton";
  case mon_Couatl:         return "Couatl";
  case mon_Vexirk:         return "Vexirk";
  case mon_Worm:           return "Worm";
  case mon_AntMan:         return "AntMan";
  case mon_Muncher:        return "Muncher";
  case mon_DethKnight:     return "DethKnight";
  case mon_Zytaz:          return "Zytaz";
  case mon_WaterElemental: return "WaterElemental";
  case mon_Oitu:           return "Oitu";
  case mon_Demon:          return "Demon";
  case mon_LordChaos:      return "LordChaos";
  case mon_Dragon:         return "Dragon";
  case mon_GreyLord:       return "GreyLord";
  default:                 return "monster";
  }
}

static const char *FacingName(int f)
{
  switch (f & 3)
  {
  case 0:  return "north";
  case 1:  return "east";
  case 2:  return "south";
  default: return "west";
  }
}

// Scrive una stringa JSON "sicura" da un buffer char di lunghezza fissa
// (i nomi nel record non sono garantiti null-terminati e possono contenere
// byte non ASCII della codifica Atari): emette solo ASCII stampabile.
static void EmitJsonStr(FILE *f, const char *s, int maxLen)
{
  fputc('"', f);
  for (int i = 0; i < maxLen && s[i] != '\0'; i++)
  {
    unsigned char c = (unsigned char)s[i];
    if (c == '"' || c == '\\') { fputc('\\', f); fputc(c, f); }
    else if (c >= 0x20 && c < 0x7f) fputc(c, f);
    else fputc('?', f);
  }
  fputc('"', f);
}

// ===========================================================================
// MODELLO DATI (ristrutturato):
//   - MAPPA STATICA del livello: emessa UNA VOLTA per livello (dm_level_<N>.json)
//     come griglia compatta grid[y][x] = tipo cella. Il client la carica e tiene.
//   - STATO DINAMICO per-tick (dm_world_state.json): party + personaggi +
//     mostri "vicini" (entro un raggio). Pochi dati, ad alta frequenza.
// Cosi' il "server" (logica) dice solo dove sei e cosa c'e' intorno; la geometria
// la conosce gia' il client. Ottimo anche per il multiplayer (fog of war).
// ===========================================================================

// Raggio (in celle) entro cui esporre i mostri "vicini" al party.
#define BRIDGE_MON_RADIUS 8

// ---- MAPPA COMPLETA del livello (statica) --------------------------------
// Tipo di cella = bit 5-7 della CELLFLAG (vedi enum ROOMTYPE):
//   0=wall 1=floor 2=pit 3=stairs 4=door 5=teleporter 6=falsewall
static void DumpPortraits(void);   // definita piu' sotto
static void DumpPlates(void);      // pressure plate (sensori-pavimento, type 47)
static void DumpTexts(void);       // scritte sui muri (record dbTEXT)

// Mappa del livello corrente in plain-text (stesso formato di dm_level_<N>.txt):
// riga 1 = W, riga 2 = H, poi H righe di W cifre (0-6 = tipo cella). Scrive su
// un FILE* qualsiasi cosi' la riusano sia il dump su file sia il server TCP.
static void EmitLevelMapText(FILE *ft)
{
  int W = d.width, H = d.height;
  fprintf(ft, "%d\n%d\n", W, H);
  for (int y = 0; y < H; y++)
  {
    for (int x = 0; x < W; x++)
      fputc('0' + ((GetCellFlags(x, y) >> 5) & 7), ft);
    fputc('\n', ft);
  }
}

static void DumpLevelMap(void)
{
  char path[64];
  snprintf(path, sizeof(path), "dm_level_%d.json", (int)d.LoadedLevel);
  FILE *f = fopen(path, "w");
  if (f == NULL) return;

  int W = d.width, H = d.height;
  fprintf(f, "{\n");
  fprintf(f, "  \"level\": %d,\n  \"width\": %d,\n  \"height\": %d,\n",
          (int)d.LoadedLevel, W, H);
  fprintf(f, "  \"legend\": { \"0\":\"wall\", \"1\":\"floor\", \"2\":\"pit\", "
             "\"3\":\"stairs\", \"4\":\"door\", \"5\":\"teleporter\", "
             "\"6\":\"falsewall\" },\n");
  // grid[y][x] = typeId (room type della cella, indipendente dal facing).
  fprintf(f, "  \"grid\": [\n");
  for (int y = 0; y < H; y++)
  {
    fprintf(f, "    [");
    for (int x = 0; x < W; x++)
    {
      int rt = (GetCellFlags(x, y) >> 5) & 7;
      fprintf(f, "%d%s", rt, (x < W - 1) ? "," : "");
    }
    fprintf(f, "]%s\n", (y < H - 1) ? "," : "");
  }
  fprintf(f, "  ]\n}\n");
  fclose(f);

  // Variante plain-text, banale da leggere in Blitz3D (un valore per riga):
  //   riga 1: W
  //   riga 2: H
  //   poi H righe, ognuna una stringa di W cifre (0-6 = tipo cella)
  char tpath[64];
  snprintf(tpath, sizeof(tpath), "dm_level_%d.txt", (int)d.LoadedLevel);
  FILE *ft = fopen(tpath, "w");
  if (ft != NULL)
  {
    EmitLevelMapText(ft);
    fclose(ft);
  }

  printf("[bridge] MAPPA livello %d (%dx%d) -> %s (+ .txt)\n",
         (int)d.LoadedLevel, W, H, path);
  fflush(stdout);

  DumpPortraits();   // posizioni reali dei ritratti dei campioni (statico)
  DumpPlates();      // pressure plate sul pavimento (sensori type 47)
}

// ---- ritratti dei campioni (statico, posizioni REALI dal dungeon) --------
// Nella Hall of Champions ogni ritratto e' un attuatore "wall decoration" di
// tipo 127 montato su una cella-MURO (stone). Per ognuno:
//   - obj.pos()      = direzione (0..3) dal muro VERSO la cella calpestabile da
//                      cui lo si guarda  =>  cella = muro + delta(pos())
//   - DB3::value()&511 = indice campione 0..23 (atlante grafico 26)
// Esportiamo in COORDINATE GRIGLIA (le stesse di dm_level_<N>.txt) la cella
// calpestabile (fx,fy) + la direzione del muro vista da quella cella
// (0=ovest 1=est 2=nord 3=sud) + l'indice ritratto. Cosi' il visualizzatore li
// piazza esattamente sulla faccia di muro giusta, come nel gioco originale.
static void EmitPortraitsText(FILE *f)
{
  int W = d.width, H = d.height;
  int fxA[256], fyA[256], dirA[256], ordA[256];
  int cnt = 0;

  for (int wy = 0; wy < H && cnt < 256; wy++)
    for (int wx = 0; wx < W && cnt < 256; wx++)
      for (RN o = FindFirstObject(wx, wy); o != RNeof; o = GetDBRecordLink(o))
      {
        if (o.dbType() != dbACTUATOR) continue;
        DBCOMMON *a = GetCommonAddress(o);
        if (a == NULL) continue;
        DB3 *p = a->CastToDB3();
        if (p == NULL) continue;
        if (p->actuatorType() != 127) continue;            // 127 = portrait
        if ((p->State() & PORTRAIT_HidePortrait) != 0) continue;

        int s  = o.pos() & 3;
        int dx = (int)d.DeltaX[s], dy = (int)d.DeltaY[s];   // muro -> cella
        int fx = wx + dx, fy = wy + dy;                     // cella calpestabile
        if (fx < 0 || fx >= W || fy < 0 || fy >= H) continue;

        // direzione del muro vista DALLA cella calpestabile (= -delta)
        int dir = -1;
        if      (dx ==  1 && dy ==  0) dir = 0;             // muro a ovest (fx-1)
        else if (dx == -1 && dy ==  0) dir = 1;             // muro a est   (fx+1)
        else if (dx ==  0 && dy ==  1) dir = 2;             // muro a nord  (fy-1)
        else if (dx ==  0 && dy == -1) dir = 3;             // muro a sud   (fy+1)
        if (dir < 0) continue;

        int ord = p->value() & 511;
        if (ord > 23) continue;

        fxA[cnt] = fx; fyA[cnt] = fy; dirA[cnt] = dir; ordA[cnt] = ord;
        cnt++;
      }

  fprintf(f, "%d\n", cnt);
  for (int i = 0; i < cnt; i++)
    fprintf(f, "%d\n%d\n%d\n%d\n", fxA[i], fyA[i], dirA[i], ordA[i]);
}

static void DumpPortraits(void)
{
  char path[64];
  snprintf(path, sizeof(path), "dm_portraits_%d.txt", (int)d.LoadedLevel);
  FILE *f = fopen(path, "w");
  if (f == NULL) return;
  EmitPortraitsText(f);
  fclose(f);
  printf("[bridge] ritratti livello %d -> %s\n", (int)d.LoadedLevel, path);
  fflush(stdout);
}

// ---- pressure plate (sensori-pavimento) ----------------------------------
// Una "pressure plate" e' un attuatore con actuatorType()==47 posato sul
// PAVIMENTO di una cella (lo cerca il codice movimento/peso del party per far
// scattare la DSA, es. aprire una porta). A differenza dei ritratti (su muro,
// type 127) la cella e' direttamente quella dove si trova l'attuatore.
// Esportiamo le celle (coord. griglia, come dm_level) in dm_plates_<N>.txt:
//   riga 1: N piastre; poi per ognuna due righe (x, y).
static void EmitPlatesText(FILE *f)
{
  int W = d.width, H = d.height;
  // DIAGNOSTICA: dumpo TUTTI gli attuatori (cella, pos, actuatorType, value) per
  // identificare il tipo della pressure plate dalla posizione nota.
  fprintf(f, "# x y pos type value (TUTTI gli attuatori, diagnostica)\n");
  for (int wy = 0; wy < H; wy++)
    for (int wx = 0; wx < W; wx++)
      for (RN o = FindFirstObject(wx, wy); o != RNeof; o = GetDBRecordLink(o))
      {
        if (o.dbType() != dbACTUATOR) continue;
        DBCOMMON *a = GetCommonAddress(o);
        if (a == NULL) continue;
        DB3 *p = a->CastToDB3();
        if (p == NULL) continue;
        fprintf(f, "%d %d %d %d %d\n", wx, wy, (int)(o.pos() & 3),
                (int)p->actuatorType(), (int)(p->value() & 511));
      }
}

static void DumpPlates(void)
{
  char path[64];
  snprintf(path, sizeof(path), "dm_plates_%d.txt", (int)d.LoadedLevel);
  FILE *f = fopen(path, "w");
  if (f == NULL) return;
  EmitPlatesText(f);
  fclose(f);
  printf("[bridge] attuatori livello %d -> %s (diagnostica)\n",
         (int)d.LoadedLevel, path);
  fflush(stdout);
}

// ---- scritte sui muri (record dbTEXT) ------------------------------------
// Le "scritte" di DM sono record dbTEXT posati su un lato di una cella-muro. La
// stringa e' impacchettata a 5 bit (vedi DecodeText/GetText) usando il
// dizionario di graphics.dat (graphic 0x22f); qui usiamo il decoder del gioco
// per ottenerla in chiaro.
//
// Formato dm_texts_<N>.txt IDENTICO a dm_portraits (cosi' il viewer riusa lo
// stesso piazzamento): prima riga = numero di scritte; poi per ognuna 4 righe
//   fx              cella CALPESTABILE da cui si vede il muro
//   fy
//   dir             0=ovest 1=est 2=nord 3=sud (muro visto dalla cella)
//   testo           stringa su singola riga
// Esportiamo SOLO le scritte visibili (show!=0) e non vuote.
static void EmitTextsText(FILE *f)
{
  int W = d.width, H = d.height;
  int   fxA[256], fyA[256], dirA[256];
  char  txA[256][1024];
  int cnt = 0;

  for (int wy = 0; wy < H && cnt < 256; wy++)
    for (int wx = 0; wx < W && cnt < 256; wx++)
      for (RN o = FindFirstObject(wx, wy); o != RNeof; o = GetDBRecordLink(o))
      {
        if (o.dbType() != dbTEXT) continue;
        DB2 *p = GetRecordAddressDB2(o);
        if (p == NULL) continue;
        if (p->show() == 0) continue;                       // solo scritte visibili

        char text[1024]; text[0] = 0;
        if (DecodeText(text, p, 1, (i32)sizeof(text) - 1) == -1) continue;
        // appiattisci su una riga e togli spazi di troppo ai bordi
        for (char *c = text; *c; c++) if (*c == '\n' || *c == '\r' || *c == '\t') *c = ' ';
        char *s = text; while (*s == ' ') s++;
        int L = (int)strlen(s); while (L > 0 && s[L-1] == ' ') s[--L] = 0;
        if (L == 0) continue;                               // niente targhe vuote

        // cella-muro (wx,wy) + lato -> cella calpestabile (fx,fy) + dir, come i ritratti
        int sd = o.pos() & 3;
        int dx = (int)d.DeltaX[sd], dy = (int)d.DeltaY[sd];
        int fx = wx + dx, fy = wy + dy;
        if (fx < 0 || fx >= W || fy < 0 || fy >= H) continue;
        int dir = -1;
        if      (dx ==  1 && dy ==  0) dir = 0;             // muro a ovest
        else if (dx == -1 && dy ==  0) dir = 1;             // muro a est
        else if (dx ==  0 && dy ==  1) dir = 2;             // muro a nord
        else if (dx ==  0 && dy == -1) dir = 3;             // muro a sud
        if (dir < 0) continue;

        fxA[cnt] = fx; fyA[cnt] = fy; dirA[cnt] = dir;
        snprintf(txA[cnt], sizeof(txA[cnt]), "%s", s);
        cnt++;
      }

  fprintf(f, "%d\n", cnt);
  for (int i = 0; i < cnt; i++)
    fprintf(f, "%d\n%d\n%d\n%s\n", fxA[i], fyA[i], dirA[i], txA[i]);
}

static void DumpTexts(void)
{
  char path[64];
  snprintf(path, sizeof(path), "dm_texts_%d.txt", (int)d.LoadedLevel);
  FILE *f = fopen(path, "w");
  if (f == NULL) return;
  EmitTextsText(f);
  fclose(f);
  printf("[bridge] scritte (dbTEXT) livello %d -> %s\n", (int)d.LoadedLevel, path);
  fflush(stdout);
}

// ---- mostri vicini (dinamico, per-tick) ----------------------------------
static void EmitMonstersNearby(FILE *f)
{
  int px = d.partyX, py = d.partyY;
  int x0 = px - BRIDGE_MON_RADIUS, x1 = px + BRIDGE_MON_RADIUS;
  int y0 = py - BRIDGE_MON_RADIUS, y1 = py + BRIDGE_MON_RADIUS;
  if (x0 < 0) x0 = 0;
  if (y0 < 0) y0 = 0;
  if (x1 >= d.width)  x1 = d.width - 1;
  if (y1 >= d.height) y1 = d.height - 1;

  fprintf(f, "  \"monsters\": [");
  bool first = true;
  for (int y = y0; y <= y1; y++)
    for (int x = x0; x <= x1; x++)
      for (RN obj = FindFirstObject(x, y); obj != RNeof; obj = GetDBRecordLink(obj))
      {
        if (obj.dbType() != dbMONSTER) continue;
        DB4 *pMon = GetRecordAddressDB4(obj);
        if (pMon == NULL || pMon->invisible()) continue;
        int mt = pMon->monsterType();
        fprintf(f, "%s\n    { \"type\": \"%s\", \"typeId\": %d, \"x\": %d, \"y\": %d }",
                first ? "" : ",", MonsterName(mt), mt, x, y);
        first = false;
      }
  fprintf(f, "%s],\n", first ? "" : "\n  ");
}

// Indici degli attributi in CHARDESC::Attributes[7] (vedi CSB.h:2564).
// 0=Luck, 1=Strength, 2=Dexterity, 3=Wisdom, 4=Vitality, 5=AntiMagic, 6=AntiFire.
static void EmitChampion(FILE *f, CHARDESC *c, int idx, bool active, bool *first)
{
  if (!*first) fprintf(f, ",\n");
  *first = false;

  fprintf(f, "    { \"slot\": %d, \"active\": %s, \"name\": ",
          idx, active ? "true" : "false");
  EmitJsonStr(f, c->name, (int)sizeof(c->name));
  fprintf(f, ", \"title\": ");
  EmitJsonStr(f, c->title, (int)sizeof(c->title));
  fprintf(f,
    ",\n      \"hp\": %d, \"maxHp\": %d, \"stamina\": %d, \"maxStamina\": %d, "
    "\"mana\": %d, \"maxMana\": %d,\n"
    "      \"food\": %d, \"water\": %d, \"load_dKg\": %d, "
    "\"poisonCount\": %d, \"ouches\": %d,\n"
    "      \"attributes\": {\n",
    c->HP(), c->MaxHP(), c->Stamina(), c->MaxStamina(),
    c->Mana(), c->MaxMana(),
    (int)c->food, (int)c->water, (int)c->load,
    (int)c->poisonCount, (int)c->ouches);

  static const char *attrName[7] =
    { "Luck", "Strength", "Dexterity", "Wisdom",
      "Vitality", "AntiMagic", "AntiFire" };
  for (int a = 0; a < 7; a++)
    fprintf(f, "        \"%s\": { \"cur\": %d, \"max\": %d }%s\n",
            attrName[a], (int)c->Attributes[a].Current(),
            (int)c->Attributes[a].Maximum(), (a < 6) ? "," : "");

  fprintf(f, "      } }");
}

static void EmitChampions(FILE *f)
{
  fprintf(f, "  \"champions\": [\n");
  bool first = true;
  for (int i = 0; i < 4; i++)
  {
    CHARDESC *c = &d.CH16482[i];
    // Slot attivo: nome non vuoto e HP massimo > 0 (gli slot liberi sono nulli).
    bool active = (c->name[0] != '\0' && c->MaxHP() > 0);
    EmitChampion(f, c, i, active, &first);
  }
  fprintf(f, "\n  ],\n");
}

// ---- porte vicine (dinamico, per-tick) -----------------------------------
// Per ogni cella-porta del livello (room type 4 = roomDOOR) esporta:
//   x, y, state, type
// dove state = cellflags & 7 (0=aperta .. 4=chiusa, 5=foro/distrutta) e' lo
// stato di apertura che il motore aggiorna da solo quando la porta si muove, e
// type = DB0::doorType() (0/1, due stili di porta per livello).  Il viewer le
// anima facendole scorrere verso l'alto (portcullis), interpolando fra i tick.
// Lettura pura: nessuna logica di gioco, il motore decide quando aprire/chiudere.
static void EmitDoorsText(FILE *ft)
{
  int W = d.width, H = d.height;
  int dxA[512], dyA[512], stA[512], tyA[512];
  int cnt = 0;
  for (int y = 0; y < H && cnt < 512; y++)
    for (int x = 0; x < W && cnt < 512; x++)
    {
      int cf = GetCellFlags(x, y);
      if (((cf >> 5) & 7) != roomDOOR) continue;
      int state = cf & 7;
      int type = 0;
      RN dn = FindFirstDoor(x, y);
      if (dn != RNeof)
      {
        DB0 *door = GetRecordAddressDB0(dn);
        if (door != NULL) type = door->doorType();
      }
      dxA[cnt] = x; dyA[cnt] = y; stA[cnt] = state; tyA[cnt] = type;
      cnt++;
    }
  fprintf(ft, "%d\n", cnt);
  for (int i = 0; i < cnt; i++)
    fprintf(ft, "%d\n%d\n%d\n%d\n", dxA[i], dyA[i], stA[i], tyA[i]);
}

// Variante plain-text dello stato, per Blitz3D (un valore per riga):
//   px / py / facing / level / numMonsters / (per mostro: typeId,x,y)
//   / numDoors / (per porta: x,y,state,type)
// Scrive su un FILE* qualsiasi: la usano sia dm_state.txt sia il server TCP.
static void EmitStateText(FILE *ft)
{
  int px = d.partyX, py = d.partyY;
  fprintf(ft, "%d\n%d\n%d\n%d\n", px, py, (int)d.partyFacing, (int)d.LoadedLevel);

  // conta + elenca i mostri nel raggio
  int x0 = px - BRIDGE_MON_RADIUS, x1 = px + BRIDGE_MON_RADIUS;
  int y0 = py - BRIDGE_MON_RADIUS, y1 = py + BRIDGE_MON_RADIUS;
  if (x0 < 0) x0 = 0;
  if (y0 < 0) y0 = 0;
  if (x1 >= d.width)  x1 = d.width - 1;
  if (y1 >= d.height) y1 = d.height - 1;
  int count = 0;
  for (int y = y0; y <= y1; y++)
    for (int x = x0; x <= x1; x++)
      for (RN o = FindFirstObject(x, y); o != RNeof; o = GetDBRecordLink(o))
      {
        if (o.dbType() != dbMONSTER) continue;
        DB4 *m = GetRecordAddressDB4(o);
        if (m != NULL && !m->invisible()) count++;
      }
  fprintf(ft, "%d\n", count);
  for (int y = y0; y <= y1; y++)
    for (int x = x0; x <= x1; x++)
      for (RN o = FindFirstObject(x, y); o != RNeof; o = GetDBRecordLink(o))
      {
        if (o.dbType() != dbMONSTER) continue;
        DB4 *m = GetRecordAddressDB4(o);
        if (m == NULL || m->invisible()) continue;
        fprintf(ft, "%d\n%d\n%d\n", (int)m->monsterType(), x, y);
      }

  EmitDoorsText(ft);   // porte del livello (stato di apertura + tipo)
}

static void DumpStateText(void)
{
  FILE *ft = fopen("dm_state.txt", "w");
  if (ft == NULL) return;
  EmitStateText(ft);
  fclose(ft);
}

// ---- STATO DINAMICO per-tick (leggero) -----------------------------------
static void DumpWorldState(void)
{
  FILE *f = fopen("dm_world_state.json", "w");
  if (f == NULL) return;

  int facing = d.partyFacing, px = d.partyX, py = d.partyY;

  fprintf(f, "{\n");
  fprintf(f, "  \"tick\": %d,\n", (int)d.Time);
  fprintf(f, "  \"level\": %d,\n", (int)d.LoadedLevel);
  fprintf(f, "  \"map\": \"dm_level_%d.json\",\n", (int)d.LoadedLevel);
  fprintf(f, "  \"party\": { \"x\": %d, \"y\": %d, \"facing\": %d, "
             "\"facingName\": \"%s\" },\n",
          px, py, facing, FacingName(facing));

  EmitChampions(f);        // termina con "],\n"
  EmitMonstersNearby(f);   // termina con "],\n"

  fprintf(f, "  \"monsterRadius\": %d\n}\n", BRIDGE_MON_RADIUS);
  fclose(f);

  DumpStateText();      // variante .txt per il client Blitz3D (compatibilita')
  BridgeSrv_PushState(); // push dello stesso stato ai client TCP sottoscritti (SUB)

  printf("[bridge] tick=%d level=%d party=(%d,%d) facing=%s\n",
         (int)d.Time, (int)d.LoadedLevel, px, py, FacingName(facing));
  fflush(stdout);
}

// ---- canale INVERSO 3D -> 2D: iniezione comandi -----------------------------
// Il comando di movimento entra nella coda mouse come AZIONE gia' risolta
// (campo .num = codice), esattamente come fa un click reale dopo l'hit-test
// (vedi OnMouseClick -> .num = D4W e HandleMouseEvents -> D6W = .num ->
// TurnParty/MoveParty). Niente coordinate ne' contesto RESTARTABLE: il dispatch
// normale del main loop applica il movimento col timing/cooldown corretti.
//   1=gira sx  2=gira dx  3=avanti  4=strafe dx  5=indietro  6=strafe sx
static void Bridge_InjectCommand(int code)
{
  if (code < 1 || code > 6) return;
  int q = d.MouseQEnd + 1;
  if (q > MOUSEQLEN - 1) q -= MOUSEQLEN;
  pMouseQueue[q].num = (ui16)code;
  pMouseQueue[q].x = 0;
  pMouseQueue[q].y = 0;
  d.MouseQEnd = (i16)q;
}

// ===========================================================================
// SERVER TCP (Fase 2) — il ponte risponde su una porta invece che su file.
//
// Design: NESSUN thread. Lo stato globale `d` e' leggibile solo sul thread
// principale, quindi il server vive dentro Bridge_Tick (timer ~10ms): accept,
// read, dispatch e write sono tutti NON-bloccanti — qualsiasi attesa qui
// congelerebbe gioco e audio. I payload riusano gli emitter esistenti tramite
// open_memstream() (FILE* in memoria), stessi formati dei .txt su file.
//
// Protocollo a righe (pensato per Blitz3D OpenTCPStream + ReadLine/WriteLine):
//   PING        ->  "OK 0"
//   STATE       ->  "OK <n>" + n righe (formato dm_state.txt)
//   MAP         ->  "OK <n>" + n righe (formato dm_level_<N>.txt, livello corrente)
//   TEXTS       ->  "OK <n>" + n righe (formato dm_texts_<N>.txt)
//   PLATES      ->  "OK <n>" + n righe (formato dm_plates_<N>.txt)
//   PORTRAITS   ->  "OK <n>" + n righe (formato dm_portraits_<N>.txt)
//   CMD <1..6>  ->  "OK 0"   (inietta il movimento nella coda mouse)
//   SUB         ->  "OK 0"; da qui in poi il server invia spontaneamente
//                   "PUSH <n>" + n righe (formato STATE) a ogni cambiamento
//   errori      ->  "ERR nolevel" (titolo/menu), "ERR badcmd", "ERR unknown"
//
// NOTA (modello condiviso): esiste UN SOLO party. Tutti i client vedono lo
// stesso stato e un CMD di QUALSIASI client muove il party per tutti: in
// questa fase il movimento e' unico, non per-client (scelta voluta).
// ===========================================================================
#ifndef _WIN32

#define BRIDGE_PORT        5417        // override con env DM_BRIDGE_PORT
#define BRIDGE_MAX_CLIENTS 8
#define BRIDGE_INBUF       256
#define BRIDGE_OUTBUF      (256*1024)

typedef struct
{
  int   fd;                  // -1 = slot libero
  char  in[BRIDGE_INBUF];    // accumulo della riga di comando in arrivo
  int   inLen;
  char *out;                 // coda di uscita, drenata non-bloccante per tick
  int   outLen, outOff;
  bool  sub;                 // sottoscritto al push dello stato (SUB)
} BridgeClient;

static int          bridgeListenFd = -2;   // -2 mai inizializzato, -1 disabilitato
static BridgeClient bridgeClients[BRIDGE_MAX_CLIENTS];
static bool         bridgeLevelReady = false;

static void BridgeSrv_SetNonBlock(int fd)
{
  int fl = fcntl(fd, F_GETFL, 0);
  if (fl != -1) fcntl(fd, F_SETFL, fl | O_NONBLOCK);
}

static void BridgeSrv_Init(void)
{
  bridgeListenFd = -1;
  for (int i = 0; i < BRIDGE_MAX_CLIENTS; i++) bridgeClients[i].fd = -1;

  int port = BRIDGE_PORT;
  const char *env = getenv("DM_BRIDGE_PORT");
  if (env != NULL && atoi(env) > 0) port = atoi(env);

  int fd = socket(AF_INET, SOCK_STREAM, 0);
  if (fd < 0) return;
  int one = 1;
  setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
  struct sockaddr_in a;
  memset(&a, 0, sizeof(a));
  a.sin_family      = AF_INET;
  a.sin_addr.s_addr = htonl(INADDR_ANY);
  a.sin_port        = htons((unsigned short)port);
  if (bind(fd, (struct sockaddr *)&a, sizeof(a)) != 0 || listen(fd, 4) != 0)
  {
    printf("[bridge] server: porta %d non disponibile, server disabilitato\n", port);
    fflush(stdout);
    close(fd);
    return;
  }
  BridgeSrv_SetNonBlock(fd);
  bridgeListenFd = fd;
  printf("[bridge] server TCP in ascolto sulla porta %d\n", port);
  fflush(stdout);
}

static void BridgeSrv_CloseClient(BridgeClient *c)
{
  if (c->fd >= 0) close(c->fd);
  free(c->out);
  c->fd = -1; c->out = NULL;
  c->outLen = c->outOff = c->inLen = 0;
  c->sub = false;
}

// Accoda dati in uscita. Se il buffer e' pieno il client e' troppo lento:
// lo scolleghiamo (mai bloccare o accumulare all'infinito).
static void BridgeSrv_Queue(BridgeClient *c, const char *data, size_t len)
{
  if (c->fd < 0 || len == 0) return;
  if (c->out == NULL)
  {
    c->out = (char *)malloc(BRIDGE_OUTBUF);
    if (c->out == NULL) { BridgeSrv_CloseClient(c); return; }
    c->outLen = c->outOff = 0;
  }
  if (c->outOff > 0)                       // compatta la parte gia' inviata
  {
    memmove(c->out, c->out + c->outOff, (size_t)(c->outLen - c->outOff));
    c->outLen -= c->outOff;
    c->outOff  = 0;
  }
  if ((size_t)c->outLen + len > BRIDGE_OUTBUF) { BridgeSrv_CloseClient(c); return; }
  memcpy(c->out + c->outLen, data, len);
  c->outLen += (int)len;
}

// Cattura l'output di un emitter esistente in memoria (open_memstream) e lo
// accoda framed: riga "<hdr> <numero-righe-payload>" seguita dal payload.
static void BridgeSrv_QueueFramed(BridgeClient *c, const char *hdr, void (*emit)(FILE *))
{
  char  *buf = NULL;
  size_t len = 0;
  FILE  *m = open_memstream(&buf, &len);
  if (m == NULL) { BridgeSrv_Queue(c, "ERR internal\n", 13); return; }
  emit(m);
  fclose(m);
  int lines = 0;
  for (size_t i = 0; i < len; i++) if (buf[i] == '\n') lines++;
  char h[32];
  int hl = snprintf(h, sizeof(h), "%s %d\n", hdr, lines);
  BridgeSrv_Queue(c, h, (size_t)hl);
  BridgeSrv_Queue(c, buf, len);
  free(buf);
}

static void BridgeSrv_HandleLine(BridgeClient *c, char *line)
{
  while (*line == ' ') line++;                       // trim sinistro
  char *e = line + strlen(line);                     // trim destro (spazi + CR)
  while (e > line && (e[-1] == ' ' || e[-1] == '\r')) *--e = 0;

  if (strcmp(line, "PING") == 0)
    BridgeSrv_Queue(c, "OK 0\n", 5);
  else if (strcmp(line, "STATE") == 0)
  {
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else BridgeSrv_QueueFramed(c, "OK", EmitStateText);
  }
  else if (strcmp(line, "MAP") == 0)
  {
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else BridgeSrv_QueueFramed(c, "OK", EmitLevelMapText);
  }
  else if (strcmp(line, "TEXTS") == 0)
  {
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else BridgeSrv_QueueFramed(c, "OK", EmitTextsText);
  }
  else if (strcmp(line, "PLATES") == 0)
  {
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else BridgeSrv_QueueFramed(c, "OK", EmitPlatesText);
  }
  else if (strcmp(line, "PORTRAITS") == 0)
  {
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else BridgeSrv_QueueFramed(c, "OK", EmitPortraitsText);
  }
  else if (strncmp(line, "CMD ", 4) == 0)
  {
    int code = atoi(line + 4);
    if (!bridgeLevelReady || code < 1 || code > 6)
      BridgeSrv_Queue(c, "ERR badcmd\n", 11);
    else
    {
      Bridge_InjectCommand(code);
      BridgeSrv_Queue(c, "OK 0\n", 5);
    }
  }
  else if (strcmp(line, "SUB") == 0)
  {
    c->sub = true;
    BridgeSrv_Queue(c, "OK 0\n", 5);
  }
  else if (line[0] != 0)
    BridgeSrv_Queue(c, "ERR unknown\n", 12);
}

// Push dello stato ("PUSH <n>" + payload STATE) ai client sottoscritti.
// Chiamato da DumpWorldState, cioe' negli stessi momenti in cui oggi viene
// riscritto dm_state.txt: movimento party, cambio livello, o periodico.
static void BridgeSrv_PushState(void)
{
  bool any = false;
  for (int i = 0; i < BRIDGE_MAX_CLIENTS; i++)
    if (bridgeClients[i].fd >= 0 && bridgeClients[i].sub) { any = true; break; }
  if (!any) return;

  for (int i = 0; i < BRIDGE_MAX_CLIENTS; i++)
  {
    BridgeClient *c = &bridgeClients[i];
    if (c->fd >= 0 && c->sub) BridgeSrv_QueueFramed(c, "PUSH", EmitStateText);
  }
}

static void BridgeSrv_Tick(bool levelReady)
{
  bridgeLevelReady = levelReady;
  if (bridgeListenFd == -2) BridgeSrv_Init();
  if (bridgeListenFd < 0) return;

  // nuovi client
  for (;;)
  {
    int fd = accept(bridgeListenFd, NULL, NULL);
    if (fd < 0) break;
    int slot = -1;
    for (int i = 0; i < BRIDGE_MAX_CLIENTS; i++)
      if (bridgeClients[i].fd < 0) { slot = i; break; }
    if (slot < 0) { close(fd); continue; }           // pieno: rifiuta
    BridgeSrv_SetNonBlock(fd);
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    BridgeClient *c = &bridgeClients[slot];
    c->fd = fd; c->inLen = 0; c->out = NULL;
    c->outLen = c->outOff = 0; c->sub = false;
  }

  for (int i = 0; i < BRIDGE_MAX_CLIENTS; i++)
  {
    BridgeClient *c = &bridgeClients[i];
    if (c->fd < 0) continue;

    // input non-bloccante -> processa le righe complete
    for (;;)
    {
      ssize_t n = recv(c->fd, c->in + c->inLen,
                       (size_t)(BRIDGE_INBUF - 1 - c->inLen), 0);
      if (n > 0)
      {
        c->inLen += (int)n;
        char *nl;
        while (c->fd >= 0 &&
               (nl = (char *)memchr(c->in, '\n', (size_t)c->inLen)) != NULL)
        {
          *nl = 0;
          BridgeSrv_HandleLine(c, c->in);
          if (c->fd < 0) break;                      // chiuso durante il dispatch
          int rest = (int)(c->inLen - (nl + 1 - c->in));
          memmove(c->in, nl + 1, (size_t)rest);
          c->inLen = rest;
        }
        if (c->fd < 0) break;
        if (c->inLen >= BRIDGE_INBUF - 1) { BridgeSrv_CloseClient(c); break; } // riga troppo lunga
      }
      else if (n == 0) { BridgeSrv_CloseClient(c); break; }   // chiuso dal peer
      else
      {
        if (errno == EINTR) continue;
        if (errno != EAGAIN && errno != EWOULDBLOCK) BridgeSrv_CloseClient(c);
        break;
      }
    }
    if (c->fd < 0) continue;

    // drena la coda di uscita (MSG_NOSIGNAL: niente SIGPIPE se il peer chiude)
    while (c->outOff < c->outLen)
    {
      ssize_t n = send(c->fd, c->out + c->outOff,
                       (size_t)(c->outLen - c->outOff), MSG_NOSIGNAL);
      if (n > 0) c->outOff += (int)n;
      else if (n < 0 && errno == EINTR) continue;
      else if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) break;
      else { BridgeSrv_CloseClient(c); break; }
    }
    if (c->fd >= 0 && c->out != NULL && c->outOff == c->outLen)
      c->outOff = c->outLen = 0;
  }
}

#else  // _WIN32: server non disponibile (servirebbe Winsock), bridge solo su file

static void BridgeSrv_Tick(bool) {}
static void BridgeSrv_PushState(void) {}

#endif

// ---- hook dal game loop --------------------------------------------------
void Bridge_Tick(void)
{
  // Il server TCP gira SEMPRE (anche al titolo/menu: risponde "ERR nolevel"
  // finche' un livello reale non e' caricato). Il resto del ponte richiede
  // il livello: al titolo level=-1 e gli indici oggetti non sono pronti.
  bool levelReady = !(d.LoadedLevel < 0 ||
                      d.LevelCellFlags == NULL || d.width <= 0 || d.height <= 0 ||
                      d.pCurrentLevelObjectIndex == NULL);
  BridgeSrv_Tick(levelReady);
  if (!levelReady) return;

  // ---------------------------------------------------------------------
  // Canale INVERSO legacy su file: dm3d scrive un codice in "dm_command.txt".
  // Resta per compatibilita' finche' dm3d non migra al comando TCP "CMD <n>"
  // (stessa iniezione: vedi Bridge_InjectCommand).
  {
    FILE *cf = fopen("dm_command.txt", "r");
    if (cf != NULL)
    {
      int code = 0;
      int n = fscanf(cf, "%d", &code);
      fclose(cf);
      remove("dm_command.txt");          // consuma (un comando per click)
      if (n == 1) Bridge_InjectCommand(code);
    }
  }

  // Esporta i ritratti dei campioni una volta (qui graphics.dat e' caricato).
  // Ritenta finche' non ne salva almeno uno.
  static bool portraitsDone = false;
  if (!portraitsDone && Bridge_ExportPortraits() > 0) portraitsDone = true;

  // pulsanti di movimento (grafica 13): esporta una volta come BMP per dm3d
  static bool mvBtnDone = false;
  if (!mvBtnDone && Bridge_ExportMoveButtons() > 0) mvBtnDone = true;

  // pressure plate: (ri)esporta quando il livello caricato cambia. Robusto anche
  // se il dump-mappa (legato al cambio livello) non scatta su auto-resume.
  static int platesLevel = -999;
  if (platesLevel != (int)d.LoadedLevel) { platesLevel = (int)d.LoadedLevel; DumpPlates(); DumpTexts(); }

  // Estrazione grafiche porte: DISABILITATA — l'abbiamo gia' fatta (vedi doors/).
  // Riabilitare queste righe solo per ri-estrarre da capo le ante.
  // static bool doorsDone = false;
  // if (!doorsDone && Bridge_ExportDoors() > 0) doorsDone = true;

  // Estrazione stipiti + bottoni: DISABILITATA — gia' fatta (vedi jambs/ e buttons/).
  // Riabilitare queste righe solo per ri-estrarre.
  // static bool jambsDone = false;
  // if (!jambsDone && Bridge_ExportJambs() > 0) jambsDone = true;
  // static bool buttonsDone = false;
  // if (!buttonsDone && Bridge_ExportButtons() > 0) buttonsDone = true;

  static int prevLevel = -999, prevX = -1, prevY = -1, prevFacing = -1;
  static int lastDumpTime = -1000;

  // Cambio di livello -> (ri)emetti la MAPPA completa una volta.
  bool levelChanged = (d.LoadedLevel != prevLevel);
  if (levelChanged) { prevLevel = d.LoadedLevel; DumpLevelMap(); }

  // Stato dinamico: su movimento del party, oppure periodicamente (cosi' i
  // mostri vicini restano aggiornati anche se il party sta fermo).
  bool moved = (d.partyX != prevX || d.partyY != prevY || d.partyFacing != prevFacing);
  bool periodic = ((int)d.Time - lastDumpTime >= 6);
  if (levelChanged || moved || periodic)
  {
    prevX = d.partyX; prevY = d.partyY; prevFacing = d.partyFacing;
    lastDumpTime = (int)d.Time;
    DumpWorldState();
  }
}
