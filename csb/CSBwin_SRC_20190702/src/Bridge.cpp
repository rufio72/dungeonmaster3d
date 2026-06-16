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
    fprintf(ft, "%d\n%d\n", W, H);
    for (int y = 0; y < H; y++)
    {
      for (int x = 0; x < W; x++)
        fputc('0' + ((GetCellFlags(x, y) >> 5) & 7), ft);
      fputc('\n', ft);
    }
    fclose(ft);
  }

  printf("[bridge] MAPPA livello %d (%dx%d) -> %s (+ .txt)\n",
         (int)d.LoadedLevel, W, H, path);
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

// Variante plain-text dello stato, per Blitz3D (un valore per riga):
//   px / py / facing / level / numMonsters / (per mostro: typeId,x,y)
static void DumpStateText(void)
{
  FILE *ft = fopen("dm_state.txt", "w");
  if (ft == NULL) return;
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

  DumpStateText();   // variante .txt per il client Blitz3D

  printf("[bridge] tick=%d level=%d party=(%d,%d) facing=%s\n",
         (int)d.Time, (int)d.LoadedLevel, px, py, FacingName(facing));
  fflush(stdout);
}

// ---- hook dal game loop --------------------------------------------------
void Bridge_Tick(void)
{
  // Niente da leggere finche' un livello REALE non e' caricato (titolo / menu):
  // sulla schermata del titolo level=-1 e gli indici oggetti non sono pronti.
  if (d.LoadedLevel < 0) return;
  if (d.LevelCellFlags == NULL || d.width <= 0 || d.height <= 0) return;
  if (d.pCurrentLevelObjectIndex == NULL) return;

  // Esporta i ritratti dei campioni una volta (qui graphics.dat e' caricato).
  // Ritenta finche' non ne salva almeno uno.
  static bool portraitsDone = false;
  if (!portraitsDone && Bridge_ExportPortraits() > 0) portraitsDone = true;

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
