// SaveJson.cpp - salvataggio/caricamento della partita in JSON leggibile.
//
// Due direzioni:
//   SaveJson_SaveGame  : memoria viva -> JSON (stesso contenuto del CSBGAMEx.DAT
//                        nativo, ma con tutti i campi nominati e leggibili).
//   SaveJson_JsonToDat : JSON -> CSBGAMEx.DAT ricostruito byte-esatto; i
//                        checksum sono RICALCOLATI, quindi il JSON resta
//                        caricabile anche dopo modifiche a mano.
//
// La sequenza dei blocchi replica esattamente il percorso di scrittura di
// _DisplayDiskMenu (SaveGame.cpp). Si riusano le funzioni piane native
// (GenChecksum, WriteScrambled, ScrambleAndWrite, WriteAndChecksum), nessuna
// RESTARTABLE. Su questo build little-endian tutte le macro LE16/LE32/BE16/
// BE32 sono identita', quindi i byte in memoria coincidono coi byte nel file.
//
// Limiti (accettabili per Dungeon Master standard): niente DSA (numDSA!=0
// rifiutato), niente cifratura (encipheredDataFile/simpleEncipher rifiutati).

#include "stdafx.h"

#include "UI.h"

#include <stdio.h>
#include <string.h>
#include <time.h>

#include <string>
#include <vector>
#include <utility>

#include "Dispatch.h"
#include "CSB.h"
#include "Data.h"
#include "SaveJson.h"

// ---- globali e funzioni del resto del motore --------------------------------
extern unsigned char *encipheredDataFile;
extern bool simpleEncipher;
extern bool RecordDesignOption;
extern bool DMRulesDesignOption;
extern bool invisibleMonsters;
extern bool DM_rules;
extern bool drawAsSize4Monsters;
extern bool extendedWallDecorations;
extern bool disableSaves;
extern i32  dbEntrySizes[16];
extern const char *dbNames[16];
extern char hintKey[8];
extern OVERLAYDATA currentOverlay;
extern bool overlayActive;
extern i32  numGlobalVariables;
extern ui32 *globalVariables;
extern ui8 overlayPaletteRed[512];
extern ui8 overlayPaletteGreen[512];
extern ui8 overlayPaletteBlue[512];
extern char *gameInfo;

// funzioni piane di SaveGame.cpp / CSBCode.cpp riusate qui
i16  GenChecksum(ui8 *buf, i16 initSum, i32 numWord);
ui32 FormChecksum(ui8 *buf, i32 num);
i16  WriteDataFile(i16 *pwbuf, i32 length);
i16  WriteScrambled(pnt buf, i32 byteCount, i16 initialHash, i16 *checksum);
i16  WriteAndChecksum(ui8 *buf, i16 *pRunningChecksum, i32 numWord);
i16  ScrambleAndWrite(i16 *pwbuf);
ui16 countDSAs(void);
i32  GetObjectWeight(RN P1);

// ---- accesso friend ai membri privati (vedi CSB.h / Data.h) -----------------
struct SaveJsonAccess
{
  // GameTimers
  static i32   TQNum(void)    { return gameTimers.NumTimer(); }
  static i32   TQFirst(void)  { return gameTimers.FirstAvailTimer(); }
  static i32   TQMax(void)    { return gameTimers.MaxTimer(); }
  static ui16  TQSeq(void)    { return gameTimers.TimerSequence(); }
  static ui16 *TQQueue(void)  { return gameTimers.TimerQueue(); }
  static TIMER *TQTimers(void){ return gameTimers.pTimer(0); }
  // TIMER (16 byte, layout: time,fn,b5..b9,seq,level,unused[3])
  static void TimerGet(const TIMER &t, ui32 &tm, ui8 &fn, ui8 b[5],
                       ui16 &seq, ui8 &lvl, ui8 unused[3])
  {
    tm  = t.m_time;
    fn  = t.timerFunction;
    b[0]=t.m_timerUByte5; b[1]=t.m_timerUByte6; b[2]=t.m_timerUByte7;
    b[3]=t.m_timerUByte8; b[4]=t.m_timerUByte9;
    seq = t.m_timerSequence;
    lvl = t.m_timerLevel;
    unused[0]=t.unused13[0]; unused[1]=t.unused13[1]; unused[2]=t.unused13[2];
  }
  static void TimerSet(TIMER &t, ui32 tm, ui8 fn, const ui8 b[5],
                       ui16 seq, ui8 lvl, const ui8 unused[3])
  {
    t.m_time = tm;
    t.timerFunction = fn;
    t.m_timerUByte5=b[0]; t.m_timerUByte6=b[1]; t.m_timerUByte7=b[2];
    t.m_timerUByte8=b[3]; t.m_timerUByte9=b[4];
    t.m_timerSequence = seq;
    t.m_timerLevel = lvl;
    t.unused13[0]=unused[0]; t.unused13[1]=unused[1]; t.unused13[2]=unused[2];
  }
  // CHARDESC
  static i16 HP(const CHARDESC &c)        { return c.m_wHP; }
  static i16 MaxHP(const CHARDESC &c)     { return c.m_wMaxHP; }
  static i16 Stamina(const CHARDESC &c)   { return c.m_wStamina; }
  static i16 MaxStamina(const CHARDESC &c){ return c.m_wMaxStamina; }
  static i16 Mana(const CHARDESC &c)      { return c.m_wMana; }
  static i16 MaxMana(const CHARDESC &c)   { return c.m_wMaxMana; }
  static void SetVitals(CHARDESC &c, i16 hp, i16 maxHp, i16 st, i16 maxSt,
                        i16 mana, i16 maxMana)
  {
    c.m_wHP=hp; c.m_wMaxHP=maxHp; c.m_wStamina=st; c.m_wMaxStamina=maxSt;
    c.m_wMana=mana; c.m_wMaxMana=maxMana;
  }
  static const RN *Poss(const CHARDESC &c) { return c.possessions; }
  static RN *Poss(CHARDESC &c)             { return c.possessions; }
};

// ---- errore corrente ---------------------------------------------------------
static char sjError[256] = "";
const char *SaveJson_Error(void) { return sjError; }
static int sjFail(const char *fmt, ...)
{
  va_list ap; va_start(ap, fmt);
  vsnprintf(sjError, sizeof(sjError), fmt, ap);
  va_end(ap);
  fprintf(stderr, "[savejson] ERRORE: %s\n", sjError);
  return -1;
}

// =============================================================================
//  SCRITTURA JSON (memoria viva -> file)
// =============================================================================

// stampa un array di interi, 16 per riga
static void jIntArray(FILE *f, int indent, const char *key,
                      const ui8 *bytes, i32 n, const ui8 *bytes2 = NULL)
{
  (void)bytes2;
  fprintf(f, "%*s\"%s\": [", indent, "", key);
  for (i32 i = 0; i < n; i++)
  {
    if (i % 16 == 0) fprintf(f, "\n%*s", indent + 2, "");
    fprintf(f, "%d%s", (int)bytes[i], (i + 1 < n) ? "," : "");
  }
  fprintf(f, "\n%*s]", indent, "");
}

static void jU16Array(FILE *f, int indent, const char *key,
                      const ui16 *words, i32 n)
{
  fprintf(f, "%*s\"%s\": [", indent, "", key);
  for (i32 i = 0; i < n; i++)
  {
    if (i % 16 == 0) fprintf(f, "\n%*s", indent + 2, "");
    fprintf(f, "%u%s", (unsigned)words[i], (i + 1 < n) ? "," : "");
  }
  fprintf(f, "\n%*s]", indent, "");
}

static void jU32Array(FILE *f, int indent, const char *key,
                      const ui32 *vals, i32 n)
{
  fprintf(f, "%*s\"%s\": [", indent, "", key);
  for (i32 i = 0; i < n; i++)
  {
    if (i % 8 == 0) fprintf(f, "\n%*s", indent + 2, "");
    fprintf(f, "%u%s", (unsigned)vals[i], (i + 1 < n) ? "," : "");
  }
  fprintf(f, "\n%*s]", indent, "");
}

// stringa JSON con escape minimo (il set di caratteri DM e' ASCII)
static void jStr(FILE *f, const char *s, i32 maxLen)
{
  fputc('"', f);
  for (i32 i = 0; (maxLen < 0 || i < maxLen) && s[i]; i++)
  {
    unsigned char c = (unsigned char)s[i];
    if (c == '"' || c == '\\') fprintf(f, "\\%c", c);
    else if (c < 0x20) fprintf(f, "\\u%04x", c);
    else fputc(c, f);
  }
  fputc('"', f);
}

// nome/titolo campione: stringa fino al primo NUL; se dopo il NUL ci sono
// byte non zero (formato byte-esatto), li mette anche come array "<key>Raw".
static void jFixedString(FILE *f, int indent, const char *key,
                         const char *buf, i32 len, bool comma)
{
  bool dirty = false;
  i32 sl = 0;
  while (sl < len && buf[sl]) sl++;
  for (i32 i = sl; i < len; i++) if (buf[i]) { dirty = true; break; }
  fprintf(f, "%*s\"%s\": ", indent, "", key);
  jStr(f, buf, len);
  if (dirty)
  {
    fprintf(f, ",\n");
    char rawKey[64];
    snprintf(rawKey, sizeof(rawKey), "%sRaw", key);
    jIntArray(f, indent, rawKey, (const ui8 *)buf, len);
  }
  fprintf(f, comma ? ",\n" : "\n");
}

// un TIMER -> una riga JSON compatta
static void jTimer(FILE *f, int indent, const TIMER &t, bool comma)
{
  ui32 tm; ui8 fn, b[5], lvl, unused[3]; ui16 seq;
  SaveJsonAccess::TimerGet(t, tm, fn, b, seq, lvl, unused);
  fprintf(f, "%*s{\"time\": %u, \"function\": %d, \"b5\": %d, \"b6\": %d, "
             "\"b7\": %d, \"b8\": %d, \"b9\": %d, \"sequence\": %u, "
             "\"level\": %d, \"unused\": [%d,%d,%d]}%s\n",
          indent, "", (unsigned)tm, (int)fn, (int)b[0], (int)b[1], (int)b[2],
          (int)b[3], (int)b[4], (unsigned)seq, (int)lvl,
          (int)unused[0], (int)unused[1], (int)unused[2],
          comma ? "," : "");
}

// un ITEM16 (mostro attivo) -> una riga JSON compatta
static void jItem16(FILE *f, int indent, ITEM16 &m, bool comma)
{
  ui8 status[4];
  memcpy(status, &m.singleMonsterStatus[0], 4);
  fprintf(f, "%*s{\"monster\": %d, \"facings\": %d, \"positions\": %d, "
             "\"b4\": %d, \"b5\": %d, \"targetX\": %d, \"targetY\": %d, "
             "\"prevX\": %d, \"prevY\": %d, \"mapX\": %d, \"mapY\": %d, "
             "\"status\": [%d,%d,%d,%d]}%s\n",
          indent, "", (int)m.word0, (int)m.facings(), (int)m.positions(),
          (int)m.uByte4, (int)m.uByte5, (int)m.uByte6, (int)m.uByte7,
          (int)m.ubPrevX, (int)m.ubPrevY, (int)m.uByte10, (int)m.uByte11,
          (int)status[0], (int)status[1], (int)status[2], (int)status[3],
          comma ? "," : "");
}

// un campione (CHARDESC) -> oggetto JSON con tutti i campi nominati
static void jChampion(FILE *f, int ind, const CHARDESC &c, bool comma)
{
  fprintf(f, "%*s{\n", ind, "");
  int in2 = ind + 2;
  jFixedString(f, in2, "name",  c.name,  8,  true);
  jFixedString(f, in2, "title", c.title, 16, true);
  fprintf(f, "%*s\"wordx24\": %d,\n", in2, "", (int)c.wordx24);
  jIntArray(f, in2, "fill26", (const ui8 *)c.FILL26, 2); fprintf(f, ",\n");
  fprintf(f, "%*s\"facing\": %d, \"position\": %d, \"byte30\": %d, \"byte31\": %d,\n",
          in2, "", (int)c.facing, (int)c.charPosition, (int)c.byte30, (int)c.byte31);
  fprintf(f, "%*s\"attackType\": %d, \"byte33\": %d,\n",
          in2, "", (int)c.attackType, (int)c.byte33);
  fprintf(f, "%*s\"incantation\": [%d,%d,%d,%d],\n", in2, "",
          (int)c.incantation[0], (int)c.incantation[1],
          (int)c.incantation[2], (int)c.incantation[3]);
  jIntArray(f, in2, "fill38", (const ui8 *)c.FILL38, 2); fprintf(f, ",\n");
  fprintf(f, "%*s\"facing3\": %d, \"maxRecentDamage\": %d, \"poisonCount\": %d, \"byte43\": %d,\n",
          in2, "", (int)c.facing3, (int)c.maxRecentDamage,
          (int)c.poisonCount, (int)c.uByte43);
  fprintf(f, "%*s\"busyTimer\": %d, \"timerIndex\": %d, \"charFlags\": %d, \"ouches\": %d,\n",
          in2, "", (int)c.busyTimer, (int)c.timerIndex,
          (int)c.charFlags, (int)c.ouches);
  fprintf(f, "%*s\"hp\": %d, \"maxHp\": %d, \"stamina\": %d, \"maxStamina\": %d, "
             "\"mana\": %d, \"maxMana\": %d,\n",
          in2, "",
          (int)SaveJsonAccess::HP(c), (int)SaveJsonAccess::MaxHP(c),
          (int)SaveJsonAccess::Stamina(c), (int)SaveJsonAccess::MaxStamina(c),
          (int)SaveJsonAccess::Mana(c), (int)SaveJsonAccess::MaxMana(c));
  fprintf(f, "%*s\"word64\": %d, \"food\": %d, \"water\": %d,\n",
          in2, "", (int)c.word64, (int)c.food, (int)c.water);
  // attributi: 7 x (max, corrente, minimo)
  static const char *attrNames[7] =
    { "luck", "strength", "dexterity", "wisdom", "vitality",
      "antiMagic", "antiFire" };
  fprintf(f, "%*s\"attributes\": {", in2, "");
  for (int a = 0; a < 7; a++)
  {
    const ui8 *p = (const ui8 *)&c.Attributes[a];
    fprintf(f, "%s\n%*s\"%s\": [%d,%d,%d]", a ? "," : "", in2 + 2, "",
            attrNames[a], (int)p[0], (int)p[1], (int)p[2]);
  }
  fprintf(f, "\n%*s},\n", in2, "");
  fprintf(f, "%*s\"fill91\": %d,\n", in2, "", (int)c.FILL91);
  // skill: 20 x {tempAdjust, experience}
  fprintf(f, "%*s\"skills\": [", in2, "");
  for (int s = 0; s < 20; s++)
  {
    fprintf(f, "%s\n%*s{\"tempAdjust\": %d, \"experience\": %d}",
            s ? "," : "", in2 + 2, "",
            (int)c.skills92[s].tempAdjust, (int)c.skills92[s].experience);
  }
  fprintf(f, "\n%*s],\n", in2, "");
  // possessi: 30 riferimenti oggetto (RN come intero)
  {
    ui16 poss[30];
    memcpy(poss, SaveJsonAccess::Poss(c), sizeof(poss));
    jU16Array(f, in2, "possessions", poss, 30); fprintf(f, ",\n");
  }
  fprintf(f, "%*s\"load\": %d, \"shieldStrength\": %d, \"talents\": %u,\n",
          in2, "", (int)c.load, (int)c.shieldStrength, (unsigned)c.talents);
  fprintf(f, "%*s\"fingerPrint\": %u, \"causeOfDamage\": %u, "
             "\"monsterCausingDamage\": %u,\n",
          in2, "", (unsigned)c.fingerPrint, (unsigned)c.causeOfDamage,
          (unsigned)c.monsterCausingDamage);
  jIntArray(f, in2, "fill286", (const ui8 *)c.FILL286, 336 - 286); fprintf(f, ",\n");
  // ritratto: 464 byte di bitmap (portrait[1] + FILL337)
  jIntArray(f, in2, "portraitData", (const ui8 *)c.portrait, 800 - 336);
  fprintf(f, "\n%*s}%s\n", ind, "", comma ? "," : "");
}

int SaveJson_SaveGame(const char *jsonPath, const char *saveName)
{
  sjError[0] = 0;
  if (disableSaves)          return sjFail("i salvataggi sono disabilitati");
  if (encipheredDataFile)    return sjFail("dungeon cifrato non supportato");
  if (simpleEncipher)        return sjFail("cifratura semplice non supportata");
  if (countDSAs() != 0)      return sjFail("dungeon con DSA non supportato");
  if (d.dungeonDatIndex == NULL) return sjFail("nessuna partita in corso");

  FILE *f = fopen(jsonPath, "w");
  if (!f) return sjFail("impossibile creare %s", jsonPath);

  // ---- meta (per il comando SAVES: leggibile in testa al file) ----
  {
    char dateBuf[64];
    time_t now = time(NULL);
    struct tm tmv;
    localtime_r(&now, &tmv);
    strftime(dateBuf, sizeof(dateBuf), "%Y-%m-%d %H:%M:%S", &tmv);
    fprintf(f, "{\n\"meta\": {\n");
    fprintf(f, "  \"format\": \"dm-savegame-json\",\n  \"version\": 1,\n");
    fprintf(f, "  \"name\": "); jStr(f, saveName, -1); fprintf(f, ",\n");
    fprintf(f, "  \"date\": \"%s\",\n", dateBuf);
    fprintf(f, "  \"partyLevel\": %d, \"partyX\": %d, \"partyY\": %d, "
               "\"partyFacing\": %d,\n",
            (int)d.partyLevel, (int)d.partyX, (int)d.partyY, (int)d.partyFacing);
    fprintf(f, "  \"champions\": [");
    for (int i = 0; i < d.NumCharacter; i++)
    {
      fprintf(f, "%s", i ? ", " : "");
      jStr(f, d.CH16482[i].name, 8);
    }
    fprintf(f, "]\n},\n");
  }

  // ---- opzioni / extended features (l'EFB viene ricostruito al load) ----
  fprintf(f, "\"options\": {\n");
  fprintf(f, "  \"efbVersion\": %d,\n", (int)ExtendedFeaturesVersion);
  fprintf(f, "  \"dmRulesDesign\": %s, \"dmRules\": %s, \"autoRecord\": %s,\n",
          DMRulesDesignOption ? "true" : "false",
          DM_rules ? "true" : "false",
          RecordDesignOption ? "true" : "false");
  fprintf(f, "  \"invisibleMonsters\": %s, \"extendedWallDecorations\": %s, "
             "\"drawAsSize4Monsters\": %s,\n",
          invisibleMonsters ? "true" : "false",
          extendedWallDecorations ? "true" : "false",
          drawAsSize4Monsters ? "true" : "false");
  fprintf(f, "  \"overlayActive\": %s,\n", overlayActive ? "true" : "false");
  fprintf(f, "  \"overlayOrdinal\": %d, \"overlayP1\": %d, \"overlayP2\": %d, "
             "\"overlayP3\": %d, \"overlayP4\": %d,\n",
          (int)(currentOverlay.m_overlayNumber + 1),
          (int)currentOverlay.m_p1, (int)currentOverlay.m_p2,
          (int)currentOverlay.m_p3, (int)currentOverlay.m_p4);
  fprintf(f, "  \"graphicsSignature1\": %u, \"graphicsSignature2\": %u,\n",
          (unsigned)expectedGraphicSignature1, (unsigned)expectedGraphicSignature2);
  fprintf(f, "  \"csbGraphicsSignature1\": %u, \"csbGraphicsSignature2\": %u,\n",
          (unsigned)expectedCSBgraphicSignature1,
          (unsigned)expectedCSBgraphicSignature2);
  fprintf(f, "  \"spellFilterLocation\": %u,\n", (unsigned)spellFilterLocation);
  fprintf(f, "  \"hintKey\": [%d,%d,%d,%d,%d,%d,%d,%d]\n",
          (int)(ui8)hintKey[0], (int)(ui8)hintKey[1], (int)(ui8)hintKey[2],
          (int)(ui8)hintKey[3], (int)(ui8)hintKey[4], (int)(ui8)hintKey[5],
          (int)(ui8)hintKey[6], (int)(ui8)hintKey[7]);
  fprintf(f, "},\n");

  // ---- mappe dei tipi (parallele ai cellflag) ----
  jIntArray(f, 0, "dataTypeMap", dataTypeMap, dataMapLength); fprintf(f, ",\n");
  jU16Array(f, 0, "dataIndexMap", dataIndexMap, dataMapLength); fprintf(f, ",\n");

  // ---- game info (testo libero del designer) ----
  // MAI null: con gameInfoSize=0 nel DAT ricostruito il load nativo rifiuta
  // il salvataggio con "SAVED GAME DAMAGED!" (die 0xbad). Il global puo'
  // essere NULL quando la sessione e' partita da un LOAD json (il percorso
  // di load del DAT ricostruito non lo ripopola): fallback sulla stessa
  // stringa di default di ReadGameInfo.
  fprintf(f, "\"gameInfo\": ");
  jStr(f, gameInfo ? gameInfo : "No game information was provided", -1);
  fprintf(f, ",\n");

  // ---- indice DSA per livello (DM standard: vuoto) ----
  fprintf(f, "\"dsaLevelIndex\": [");
  {
    bool first = true;
    for (int lvl = 0; lvl < 64; lvl++)
      for (int idx = 0; idx < 32; idx++)
        if (DSALevelIndex[lvl][idx] < 256)
        {
          fprintf(f, "%s\n  [%d,%d,%d]", first ? "" : ",",
                  lvl, idx, (int)DSALevelIndex[lvl][idx]);
          first = false;
        }
    fprintf(f, first ? "],\n" : "\n],\n");
  }

  // ---- header (GAMEBLOCK1, senza i checksum: ricalcolati al load) ----
  fprintf(f, "\"header\": {\n");
  fprintf(f, "  \"saveOption\": 1,\n");
  fprintf(f, "  \"randomGameID\": %d,\n", (int)d.RandomGameID);
  fprintf(f, "  \"byte22596\": %d, \"byte22598\": %d,\n",
          (int)ub(d.Word22596), (int)(i8)d.Word22598);
  fprintf(f, "  \"word22592\": %d, \"word22594\": %d,\n",
          (int)d.Word22592, (int)d.Word22594);
  fprintf(f, "  \"totalMoveCount\": %d,\n", (int)totalMoveCount);
  jIntArray(f, 2, "byte22808", (const ui8 *)d.Byte22808, 132);
  fprintf(f, "\n},\n");

  // ---- stato di gioco (GAMEBLOCK2) ----
  fprintf(f, "\"state\": {\n");
  fprintf(f, "  \"time\": %d, \"randomSeed\": %d,\n",
          (int)d.Time, (int)d.RandomNumber);
  fprintf(f, "  \"objectInHand\": %d, \"numCharacters\": %d,\n",
          (int)d.objectInHand.ConvertToInteger(), (int)d.NumCharacter);
  fprintf(f, "  \"partyX\": %d, \"partyY\": %d, \"partyFacing\": %d, "
             "\"partyLevel\": %d,\n",
          (int)d.partyX, (int)d.partyY, (int)d.partyFacing, (int)d.partyLevel);
  fprintf(f, "  \"handChar\": %d, \"magicCaster\": %d,\n",
          (int)d.HandChar, (int)d.MagicCaster);
  fprintf(f, "  \"numTimers\": %d, \"firstAvailTimer\": %d, \"maxTimers\": %d,\n",
          (int)SaveJsonAccess::TQNum(), (int)SaveJsonAccess::TQFirst(),
          (int)SaveJsonAccess::TQMax());
  fprintf(f, "  \"item16QueLen\": %d,\n", (int)d.ITEM16QueLen);
  fprintf(f, "  \"lastMonsterAttackTime\": %d, \"lastPartyMoveTime\": %d,\n",
          (int)d.LastMonsterAttackTime, (int)d.LastPartyMoveTime);
  fprintf(f, "  \"partyMoveDisableTimer\": %d,\n", (int)d.partyMoveDisableTimer);
  fprintf(f, "  \"word11712\": %d, \"word11714\": %d,\n",
          (int)d.Word11712, (int)d.Word11714);
  fprintf(f, "  \"maxItem16\": %d, \"timerSequence\": %u\n",
          (int)d.MaxITEM16, (unsigned)SaveJsonAccess::TQSeq());
  fprintf(f, "},\n");

  // ---- mostri attivi (ITEM16) ----
  fprintf(f, "\"activeMonsters\": [\n");
  for (i32 i = 0; i < d.MaxITEM16; i++)
    jItem16(f, 2, d.Item16[i], i + 1 < d.MaxITEM16);
  fprintf(f, "],\n");

  // ---- campioni ----
  // Come nel salvataggio nativo: il peso dell'oggetto in mano va sottratto
  // dal carico del personaggio che lo regge (al load lo riprende in mano).
  {
    i16 savedLoad = 0;
    bool adjusted = false;
    if (d.EmptyHanded == 0 && d.HandChar >= 0 && d.HandChar < 4)
    {
      savedLoad = d.CH16482[d.HandChar].load;
      d.CH16482[d.HandChar].load =
          (ui16)(d.CH16482[d.HandChar].load - GetObjectWeight(d.objectInHand));
      adjusted = true;
    }
    fprintf(f, "\"champions\": [\n");
    for (int i = 0; i < 4; i++)
      jChampion(f, 2, d.CH16482[i], i < 3);
    fprintf(f, "],\n");
    if (adjusted) d.CH16482[d.HandChar].load = savedLoad;
  }

  // ---- coda del blocco personaggi (stato del party) ----
  fprintf(f, "\"partyState\": {\n");
  fprintf(f, "  \"brightness\": %d, \"seeThruWalls\": %d, "
             "\"magicFootprintsActive\": %d,\n",
          (int)d.Brightness, (int)d.SeeThruWalls, (int)d.MagicFootprintsActive);
  fprintf(f, "  \"partyShield\": %d, \"fireShield\": %d, \"spellShield\": %d,\n",
          (int)d.PartyShield, (int)d.FireShield, (int)d.SpellShield);
  fprintf(f, "  \"numFootprintEnt\": %d, \"freezeLifeTimer\": %d,\n",
          (int)d.NumFootprintEnt, (int)d.freezeLifeTimer);
  fprintf(f, "  \"footprintFirst\": %d, \"footprintLast\": %d,\n",
          (int)d.IndexOfFirstMagicFootprint, (int)d.IndexOfLastMagicFootprint);
  jU16Array(f, 2, "partyFootprints", (const ui16 *)d.PartyFootprints, 24);
  fprintf(f, ",\n");
  jIntArray(f, 2, "byte13220", (const ui8 *)d.Byte13220, 24); fprintf(f, ",\n");
  fprintf(f, "  \"invisible\": %d,\n", (int)d.Invisible);
  jIntArray(f, 2, "byte13195", (const ui8 *)d.Byte13195, 41);
  fprintf(f, "\n},\n");

  // ---- timer ----
  {
    i32 maxT = SaveJsonAccess::TQMax();
    TIMER *timers = SaveJsonAccess::TQTimers();
    fprintf(f, "\"timers\": [\n");
    for (i32 i = 0; i < maxT; i++)
      jTimer(f, 2, timers[i], i + 1 < maxT);
    fprintf(f, "],\n");
    jU16Array(f, 0, "timerQueue", SaveJsonAccess::TQQueue(), maxT);
    fprintf(f, ",\n");
  }

  // ---- aggiornamento expool PRIMA di leggere l'indice del dungeon, come nel
  //      save nativo: le expool.Write possono AGGIUNGERE record al database 11
  //      e dungeonDatIndex->dbSizes[11] viene aggiornato di conseguenza ----
  {
    for (i32 i = 0; i < numGlobalVariables / 16; i++)
    {
      ui32 *pRecord;
      i32 size = expool.Locate((EDT_Database << 24) |
                               (EDBT_GlobalVariables << 16) | i, &pRecord);
      if (size < 16)
        fprintf(stderr, "[savejson] attenzione: record globalVariables %d "
                        "non trovato in expool\n", (int)i);
      else
        memcpy(pRecord, globalVariables + 16 * i, 16 * sizeof(ui32));
    }
    ui8 palette[3 * 512];
    memcpy(palette, overlayPaletteRed, 512);
    memcpy(palette + 512, overlayPaletteGreen, 512);
    memcpy(palette + 1024, overlayPaletteBlue, 512);
    for (int i = 0; i < 24; i++)
    {
      ui32 key = (EDT_Palette << 24) + i;
      ui32 junk[16];
      expool.Read(key, junk, 16);
      expool.Write(key, (ui32 *)(palette + 64 * i), 16);
    }
  }

  // ---- indice del dungeon (44 byte = 22 word) ----
  {
    const ui16 *w = (const ui16 *)d.dungeonDatIndex;
    fprintf(f, "\"dungeonIndex\": {\n");
    fprintf(f, "  \"sentinel\": %u, \"legacyCellFlagSize\": %u, \"word4\": %u,\n",
            (unsigned)w[0], (unsigned)w[1], (unsigned)w[2]);
    fprintf(f, "  \"numTextWords\": %u, \"word8\": %u, \"objectListLength\": %u,\n",
            (unsigned)w[3], (unsigned)w[4], (unsigned)w[5]);
    jU16Array(f, 2, "dbSizes", w + 6, 16);
    fprintf(f, "\n},\n");
  }

  // ---- descrittori dei livelli (16 byte l'uno) ----
  {
    i32 numLevel = d.dungeonDatIndex->NumLevel();
    fprintf(f, "\"levels\": [\n");
    for (i32 i = 0; i < numLevel; i++)
    {
      const ui8 *b = (const ui8 *)&d.pLevelDescriptors[i];
      const ui16 *w = (const ui16 *)b;
      fprintf(f, "  {\"cellOffset\": %u, \"fill2\": [%d,%d,%d,%d], "
                 "\"offsetX\": %d, \"offsetY\": %d, "
                 "\"word8\": %u, \"word10\": %u, \"word12\": %u, \"word14\": %u}%s\n",
              (unsigned)w[0], (int)b[2], (int)b[3], (int)b[4], (int)b[5],
              (int)b[6], (int)b[7],
              (unsigned)w[4], (unsigned)w[5], (unsigned)w[6], (unsigned)w[7],
              (i + 1 < numLevel) ? "," : "");
    }
    fprintf(f, "],\n");
  }

  // ---- liste oggetti per colonna ----
  jU16Array(f, 0, "objectListIndex", (const ui16 *)d.objectListIndex,
            d.numColumnPointers);
  fprintf(f, ",\n");
  jU16Array(f, 0, "objectList", (const ui16 *)d.objectList,
            d.dungeonDatIndex->ObjectListLength());
  fprintf(f, ",\n");

  // ---- testi ----
  jU32Array(f, 0, "indirectTextIndex", (const ui32 *)d.indirectTextIndex,
            d.dungeonDatIndex->NumWordsInTextArray());
  fprintf(f, ",\n");
  jU16Array(f, 0, "compressedText", (const ui16 *)d.compressedText,
            d.sizeOfCompressedText);
  fprintf(f, ",\n");

  // ---- i 16 database (record come parole a 16 bit; l'expool e' gia'
  //      stato aggiornato prima della sezione dungeonIndex) ----
  fprintf(f, "\"databases\": {\n");
  for (int dbNum = 0; dbNum < 16; dbNum++)
  {
    i32 numEnt = d.dungeonDatIndex->DBSize(dbNum);
    i32 entryWords = dbEntrySizes[dbNum] / 2;
    fprintf(f, "  \"%02d_%s\": [", dbNum, dbNames[dbNum]);
    for (i32 e = 0; e < numEnt; e++)
    {
      const ui16 *w = (const ui16 *)db.GetCommonAddress((DBTYPE)dbNum, e);
      fprintf(f, "%s\n    [", e ? "," : "");
      for (i32 k = 0; k < entryWords; k++)
        fprintf(f, "%u%s", (unsigned)w[k], (k + 1 < entryWords) ? "," : "");
      fprintf(f, "]");
    }
    fprintf(f, numEnt ? "\n  ]%s\n" : "]%s\n", (dbNum < 15) ? "," : "");
  }
  fprintf(f, "},\n");

  // ---- cellflag (la mappa vera e propria) ----
  jIntArray(f, 0, "cellFlags", (const ui8 *)d.cellFlagArray, cellflagArraySize);
  fprintf(f, "\n}\n");

  if (fclose(f) != 0) return sjFail("errore in scrittura su %s", jsonPath);
  fprintf(stderr, "[savejson] salvato %s\n", jsonPath);
  return 0;
}

// =============================================================================
//  PARSER JSON minimale (DOM)
// =============================================================================
struct JV
{
  enum T { NUL, BOOL, NUM, STR, ARR, OBJ } t;
  bool b;
  double n;
  std::string s;
  std::vector<JV> a;
  std::vector<std::pair<std::string, JV> > o;
  JV(void) : t(NUL), b(false), n(0) {}
  const JV *get(const char *k) const
  {
    for (size_t i = 0; i < o.size(); i++)
      if (o[i].first == k) return &o[i].second;
    return NULL;
  }
};

struct JParser
{
  const char *p, *end;
  char err[128];
  JParser(const char *src, size_t len) : p(src), end(src + len) { err[0] = 0; }
  void ws(void) { while (p < end && (*p==' '||*p=='\t'||*p=='\n'||*p=='\r')) p++; }
  bool fail(const char *m) { snprintf(err, sizeof(err), "%s (offset %ld)", m, (long)0); return false; }
  bool parse(JV &v)
  {
    ws();
    if (p >= end) return fail("fine input inattesa");
    char c = *p;
    if (c == '{')
    {
      p++; v.t = JV::OBJ;
      ws();
      if (p < end && *p == '}') { p++; return true; }
      for (;;)
      {
        ws();
        if (p >= end || *p != '"') return fail("attesa chiave stringa");
        JV key;
        if (!parseString(key)) return false;
        ws();
        if (p >= end || *p != ':') return fail("atteso ':'");
        p++;
        JV val;
        if (!parse(val)) return false;
        v.o.push_back(std::make_pair(key.s, val));
        ws();
        if (p < end && *p == ',') { p++; continue; }
        if (p < end && *p == '}') { p++; return true; }
        return fail("atteso ',' o '}'");
      }
    }
    if (c == '[')
    {
      p++; v.t = JV::ARR;
      ws();
      if (p < end && *p == ']') { p++; return true; }
      for (;;)
      {
        JV val;
        if (!parse(val)) return false;
        v.a.push_back(val);
        ws();
        if (p < end && *p == ',') { p++; continue; }
        if (p < end && *p == ']') { p++; return true; }
        return fail("atteso ',' o ']'");
      }
    }
    if (c == '"') { v.t = JV::STR; return parseString(v); }
    if (c == 't') { if (end-p>=4 && !strncmp(p,"true",4))  { v.t=JV::BOOL; v.b=true;  p+=4; return true; } return fail("token"); }
    if (c == 'f') { if (end-p>=5 && !strncmp(p,"false",5)) { v.t=JV::BOOL; v.b=false; p+=5; return true; } return fail("token"); }
    if (c == 'n') { if (end-p>=4 && !strncmp(p,"null",4))  { v.t=JV::NUL; p+=4; return true; } return fail("token"); }
    // numero
    {
      char *endNum = NULL;
      double val = strtod(p, &endNum);
      if (endNum == p) return fail("numero non valido");
      v.t = JV::NUM; v.n = val; p = endNum;
      return true;
    }
  }
  bool parseString(JV &v)
  {
    p++; // apre '"'
    v.s.clear();
    while (p < end && *p != '"')
    {
      if (*p == '\\')
      {
        p++;
        if (p >= end) return fail("escape troncato");
        char c = *p++;
        switch (c)
        {
          case 'n': v.s += '\n'; break;
          case 't': v.s += '\t'; break;
          case 'r': v.s += '\r'; break;
          case 'b': v.s += '\b'; break;
          case 'f': v.s += '\f'; break;
          case '/': v.s += '/';  break;
          case '"': v.s += '"';  break;
          case '\\': v.s += '\\'; break;
          case 'u':
          {
            if (end - p < 4) return fail("\\u troncato");
            char hex[5] = { p[0], p[1], p[2], p[3], 0 };
            v.s += (char)(int)strtol(hex, NULL, 16);
            p += 4;
            break;
          }
          default: return fail("escape sconosciuto");
        }
      }
      else v.s += *p++;
    }
    if (p >= end) return fail("stringa non chiusa");
    p++; // chiude '"'
    return true;
  }
};

// ---- helper di lettura dal DOM (con errore su campo mancante) ---------------
static bool jvFail;
static char jvMissing[128];
static long jvI(const JV *obj, const char *key, long def = 0, bool required = true)
{
  const JV *v = obj ? obj->get(key) : NULL;
  if (!v || v->t != JV::NUM)
  {
    if (required && !jvFail)
    {
      jvFail = true;
      snprintf(jvMissing, sizeof(jvMissing), "%s", key ? key : "?");
    }
    return def;
  }
  return (long)v->n;
}
static bool jvB(const JV *obj, const char *key)
{
  const JV *v = obj ? obj->get(key) : NULL;
  return v && v->t == JV::BOOL && v->b;
}
// copia un array JSON di interi in un buffer di byte
static bool jvBytes(const JV *arr, ui8 *dst, i32 n)
{
  if (!arr || arr->t != JV::ARR || (i32)arr->a.size() != n) return false;
  for (i32 i = 0; i < n; i++) dst[i] = (ui8)(long)arr->a[i].n;
  return true;
}
static bool jvWords(const JV *arr, ui16 *dst, i32 n)
{
  if (!arr || arr->t != JV::ARR || (i32)arr->a.size() != n) return false;
  for (i32 i = 0; i < n; i++) dst[i] = (ui16)(unsigned long)arr->a[i].n;
  return true;
}
// stringa a lunghezza fissa (name/title): usa <key>Raw se presente
static void jvFixedString(const JV *obj, const char *key, char *dst, i32 len)
{
  memset(dst, 0, len);
  char rawKey[64];
  snprintf(rawKey, sizeof(rawKey), "%sRaw", key);
  const JV *raw = obj->get(rawKey);
  if (raw && jvBytes(raw, (ui8 *)dst, len)) return;
  const JV *s = obj->get(key);
  if (s && s->t == JV::STR)
    memcpy(dst, s->s.c_str(),
           (i32)s->s.size() < len ? s->s.size() : (size_t)len);
}

// =============================================================================
//  RICOSTRUZIONE DAT (JSON -> CSBGAMEx.DAT)
// =============================================================================

// GAMEBLOCK1/2: stessi layout di SaveGame.cpp (definiti li' dentro, quindi
// duplicati qui con nomi propri; il probe conferma 512/128 byte).
struct SJGB1
{
  i8  Byte740[300];
  i8  Byte22598;     //300
  ui8 Byte22596;     //301
  i8  FILL438[4];    //302
  i16 SaveOption;    //306
  i32 RandomGameID;  //308
  i16 Block2Hash;    //312
  i16 ITEM16Hash;    //314
  i16 CharacterHash; //316
  i16 TimersHash;    //318
  i16 TimerQueHash;  //320
  i32 totalMoveCount;//322
  i16 Hash326, Hash328, Hash330, Hash332, Hash334;
  i16 Hash336, Hash338, Hash340, Hash342;
  i16 Block2Checksum;   //344
  i16 ITEM16Checksum;   //346
  i16 CharacterChecksum;//348
  i16 TimersChecksum;   //350
  i16 TimerQueChecksum; //352
  i16 Checksum354, Checksum356, Checksum358, Checksum360, Checksum362;
  i16 Checksum364, Checksum366, Checksum368, Checksum370, Checksum372;
  i16 Checksum374;
  i16 Word22594;        //376
  i16 Word22592;        //378
  i8  Byte22808[132];   //380
} __attribute__((packed));

struct SJGB2
{
  i32 Time;
  i32 ranseed;
  ui16 ObjectInHand;
  i16 numcharacter, partyx, partyy, partyfacing, partyLevel;
  i16 handChar, MagicCaster, NumTimer, FirstAvailTimer, MaxTimers;
  i16 ITEM16QueLen;
  i32 LastMonsterAttackTime, LastPartyMoveTime;
  i16 partyMoveDisableTimer, Word11712, Word11714, MaxITEM16;
  ui16 TimerSequence;
  i8  FILL180[78];
} __attribute__((packed));

static_assert(sizeof(SJGB1) == 512, "SJGB1 deve essere 512 byte");
static_assert(sizeof(SJGB2) == 128, "SJGB2 deve essere 128 byte");
static_assert(sizeof(CHARDESC) == 800, "CHARDESC deve essere 800 byte");
static_assert(sizeof(TIMER) == 16, "TIMER deve essere 16 byte");
static_assert(sizeof(ITEM16) == 16, "ITEM16 deve essere 16 byte");

// ============================================================================
// BYTE-ORDER: su questo build _littleEndian, LE16 = scambio di byte e LE32 =
// inversione a 4 byte (il file conserva l'ordine big-endian Atari). I valori
// nel JSON sono in ordine di MEMORIA (leggibili); alla scrittura del DAT vanno
// convertiti replicando ESATTAMENTE le funzioni swap* native (stessi campi).
// ============================================================================

// swapBlock2 (SaveGame.cpp): converte il GB2 in ordine-file
static void sjSwapGB2(SJGB2 *b)
{
  b->Time = LE32(b->Time);
  b->ranseed = LE32(b->ranseed);
  b->ObjectInHand = LE16(b->ObjectInHand);
  b->numcharacter = LE16(b->numcharacter);
  b->partyx = LE16(b->partyx);
  b->partyy = LE16(b->partyy);
  b->partyfacing = LE16(b->partyfacing);
  b->partyLevel = LE16(b->partyLevel);
  b->handChar = LE16(b->handChar);
  b->MagicCaster = LE16(b->MagicCaster);
  b->NumTimer = LE16(b->NumTimer);
  b->FirstAvailTimer = LE16(b->FirstAvailTimer);
  b->MaxTimers = LE16(b->MaxTimers);
  b->ITEM16QueLen = LE16(b->ITEM16QueLen);
  b->LastMonsterAttackTime = LE32(b->LastMonsterAttackTime);
  b->LastPartyMoveTime = LE32(b->LastPartyMoveTime);
  b->partyMoveDisableTimer = LE16(b->partyMoveDisableTimer);
  b->Word11712 = LE16(b->Word11712);
  b->Word11714 = LE16(b->Word11714);
  b->MaxITEM16 = LE16(b->MaxITEM16);
  b->TimerSequence = LE16(b->TimerSequence);
}

// swapBlock1 (SaveGame.cpp): converte i campi swappati del GB1 in ordine-file
static void sjSwapGB1(SJGB1 *b)
{
  b->RandomGameID = LE32(b->RandomGameID);
  b->Block2Hash = LE16(b->Block2Hash);
  b->ITEM16Hash = LE16(b->ITEM16Hash);
  b->CharacterHash = LE16(b->CharacterHash);
  b->TimersHash = LE16(b->TimersHash);
  b->TimerQueHash = LE16(b->TimerQueHash);
  b->Block2Checksum = LE16(b->Block2Checksum);
  b->ITEM16Checksum = LE16(b->ITEM16Checksum);
  b->CharacterChecksum = LE16(b->CharacterChecksum);
  b->TimersChecksum = LE16(b->TimersChecksum);
  b->TimerQueChecksum = LE16(b->TimerQueChecksum);
  b->Word22594 = LE16(b->Word22594);
  b->Word22592 = LE16(b->Word22592);
}

// swapCharacter (SaveGame.cpp): campi swappati di un CHARDESC (via friend)
static void sjSwapChampion(CHARDESC *pc)
{
  pc->busyTimer = LE16(pc->busyTimer);
  pc->timerIndex = LE16(pc->timerIndex);
  pc->charFlags = LE16(pc->charFlags);
  pc->ouches = LE16(pc->ouches);
  SaveJsonAccess::SetVitals(*pc,
      LE16(SaveJsonAccess::HP(*pc)), LE16(SaveJsonAccess::MaxHP(*pc)),
      LE16(SaveJsonAccess::Stamina(*pc)), LE16(SaveJsonAccess::MaxStamina(*pc)),
      LE16(SaveJsonAccess::Mana(*pc)), LE16(SaveJsonAccess::MaxMana(*pc)));
  pc->word64 = LE16(pc->word64);
  pc->food = LE16(pc->food);
  pc->water = LE16(pc->water);
  for (int j = 0; j < 20; j++)
  {
    pc->skills92[j].tempAdjust = LE16(pc->skills92[j].tempAdjust);
    pc->skills92[j].experience = LE32(pc->skills92[j].experience);
  }
  {
    i16 *pp = (i16 *)SaveJsonAccess::Poss(*pc);
    for (int j = 0; j < 30; j++) pp[j] = LE16(pp[j]);
  }
  pc->load = LE16(pc->load);
  pc->shieldStrength = LE16(pc->shieldStrength);
}

// Replica di DBn::swap (data.cpp) su un buffer di record in ordine di memoria.
// DBn::swap sono privati (friend di DATABASES), quindi le liste di word
// swappate sono duplicate qui: word a 16 bit salvo dove indicato.
static void sjSwapDatabase(int dbNum, ui8 *buf, i32 numEnt)
{
  // word (indici a 16 bit) swappate per ogni tipo di record; -1 = fine lista
  static const int swapWords[16][8] = {
    /* 0 porte      */ {0, 1, -1},
    /* 1 teleporter */ {0, 1, 2, -1},
    /* 2 testi      */ {0, 1, -1},
    /* 3 attuatori  */ {0, 1, 2, 3, -1},          // word8 NON swappata
    /* 4 mostri     */ {0, 1, 3, 4, 5, 6, 7, -1}, // uByte4/5 NON swappati
    /* 5 armi       */ {0, 1, -1},
    /* 6 vestiti    */ {0, 1, -1},
    /* 7 pergamene  */ {0, 1, 2, -1},
    /* 8 pozioni    */ {0, 1, -1},
    /* 9 casse      */ {0, 1, 2, -1},
    /*10 varie      */ {0, 1, -1},
    /*11 expool     */ {0, 1, -1},                // + 63 ui32 (sotto)
    /*12 -          */ {-1},
    /*13 -          */ {-1},
    /*14 missili    */ {0, 1, 3, -1},             // range/damage NON swappati
    /*15 nuvole     */ {0, 1, -1},
  };
  i32 sz = dbEntrySizes[dbNum];
  for (i32 e = 0; e < numEnt; e++)
  {
    ui8 *p = buf + (size_t)e * sz;
    ui16 *w = (ui16 *)p;
    for (int k = 0; k < 8 && swapWords[dbNum][k] >= 0; k++)
      w[swapWords[dbNum][k]] = LE16(w[swapWords[dbNum][k]]);
    if (dbNum == 11)                              // DB11: payload di 63 ui32
    {
      i32 *dw = (i32 *)(p + 4);
      for (int k = 0; k < 63; k++) dw[k] = LE32(dw[k]);
    }
  }
}

// ricostruisce il CHARDESC (800 byte) di un campione dal JSON
static bool sjBuildChampion(const JV *c, ui8 *out)
{
  CHARDESC ch;
  memset((void *)&ch, 0, sizeof(ch));
  jvFixedString(c, "name",  ch.name,  8);
  jvFixedString(c, "title", ch.title, 16);
  ch.wordx24 = (i16)jvI(c, "wordx24");
  jvBytes(c->get("fill26"), (ui8 *)ch.FILL26, 2);
  ch.facing        = (ui8)jvI(c, "facing");
  ch.charPosition  = (ui8)jvI(c, "position");
  ch.byte30        = (ui8)jvI(c, "byte30");
  ch.byte31        = (ui8)jvI(c, "byte31");
  ch.attackType    = (i8)jvI(c, "attackType");
  ch.byte33        = (i8)jvI(c, "byte33");
  {
    const JV *inc = c->get("incantation");
    if (inc && inc->t == JV::ARR && inc->a.size() == 4)
      for (int i = 0; i < 4; i++) ch.incantation[i] = (i8)(long)inc->a[i].n;
  }
  jvBytes(c->get("fill38"), (ui8 *)ch.FILL38, 2);
  ch.facing3         = (ui8)jvI(c, "facing3");
  ch.maxRecentDamage = (ui8)jvI(c, "maxRecentDamage");
  ch.poisonCount     = (ui8)jvI(c, "poisonCount");
  ch.uByte43         = (ui8)jvI(c, "byte43");
  ch.busyTimer  = (i16)jvI(c, "busyTimer");
  ch.timerIndex = (i16)jvI(c, "timerIndex");
  ch.charFlags  = (i16)jvI(c, "charFlags");
  ch.ouches     = (i16)jvI(c, "ouches");
  SaveJsonAccess::SetVitals(ch,
      (i16)jvI(c, "hp"), (i16)jvI(c, "maxHp"),
      (i16)jvI(c, "stamina"), (i16)jvI(c, "maxStamina"),
      (i16)jvI(c, "mana"), (i16)jvI(c, "maxMana"));
  ch.word64 = (i16)jvI(c, "word64");
  ch.food   = (i16)jvI(c, "food");
  ch.water  = (i16)jvI(c, "water");
  {
    static const char *attrNames[7] =
      { "luck", "strength", "dexterity", "wisdom", "vitality",
        "antiMagic", "antiFire" };
    const JV *attrs = c->get("attributes");
    if (!attrs) return false;
    for (int a = 0; a < 7; a++)
    {
      const JV *v = attrs->get(attrNames[a]);
      if (!v || v->t != JV::ARR || v->a.size() != 3) return false;
      ui8 *p = (ui8 *)&ch.Attributes[a];
      for (int k = 0; k < 3; k++) p[k] = (ui8)(long)v->a[k].n;
    }
  }
  ch.FILL91 = (i8)jvI(c, "fill91");
  {
    const JV *sk = c->get("skills");
    if (!sk || sk->t != JV::ARR || sk->a.size() != 20) return false;
    for (int s = 0; s < 20; s++)
    {
      ch.skills92[s].tempAdjust = (i16)jvI(&sk->a[s], "tempAdjust");
      ch.skills92[s].experience = (i32)jvI(&sk->a[s], "experience");
    }
  }
  {
    ui16 poss[30];
    if (!jvWords(c->get("possessions"), poss, 30)) return false;
    memcpy(SaveJsonAccess::Poss(ch), poss, sizeof(poss));
  }
  ch.load           = (ui16)jvI(c, "load");
  ch.shieldStrength = (ui16)jvI(c, "shieldStrength");
  ch.talents        = (ui32)jvI(c, "talents");
  ch.fingerPrint    = (ui16)jvI(c, "fingerPrint");
  ch.causeOfDamage  = (ui16)jvI(c, "causeOfDamage");
  ch.monsterCausingDamage = (ui16)jvI(c, "monsterCausingDamage");
  jvBytes(c->get("fill286"), (ui8 *)ch.FILL286, 336 - 286);
  jvBytes(c->get("portraitData"), (ui8 *)ch.portrait, 800 - 336);
  sjSwapChampion(&ch);                   // ordine-file (come swapCharacter)
  memcpy(out, &ch, 800);
  return true;
}

int SaveJson_JsonToDat(const char *jsonPath, const char *datPath)
{
  sjError[0] = 0;
  jvFail = false;
  jvMissing[0] = 0;

  // ---- carica e parsa il JSON ----
  std::string src;
  {
    FILE *f = fopen(jsonPath, "rb");
    if (!f) return sjFail("impossibile aprire %s", jsonPath);
    fseek(f, 0, SEEK_END);
    long len = ftell(f);
    fseek(f, 0, SEEK_SET);
    src.resize(len);
    if (len > 0 && fread(&src[0], 1, len, f) != (size_t)len)
    { fclose(f); return sjFail("errore in lettura da %s", jsonPath); }
    fclose(f);
  }
  JV root;
  {
    JParser jp(src.c_str(), src.size());
    if (!jp.parse(root) || root.t != JV::OBJ)
      return sjFail("JSON non valido: %s", jp.err);
  }

  const JV *meta = root.get("meta");
  if (!meta || jvI(meta, "version") != 1 ||
      !meta->get("format") || meta->get("format")->s != "dm-savegame-json")
    return sjFail("non e' un salvataggio dm-savegame-json v1");

  const JV *options  = root.get("options");
  const JV *header   = root.get("header");
  const JV *state    = root.get("state");
  const JV *dngIdx   = root.get("dungeonIndex");
  const JV *partySt  = root.get("partyState");
  if (!options || !header || !state || !dngIdx || !partySt)
    return sjFail("sezione mancante (options/header/state/dungeonIndex/partyState)");

  // ---- dimensioni fondamentali ----
  const JV *jTypeMap  = root.get("dataTypeMap");
  const JV *jIdxMap   = root.get("dataIndexMap");
  const JV *jCells    = root.get("cellFlags");
  const JV *jMonsters = root.get("activeMonsters");
  const JV *jChamps   = root.get("champions");
  const JV *jTimers   = root.get("timers");
  const JV *jTQueue   = root.get("timerQueue");
  const JV *jLevels   = root.get("levels");
  const JV *jOLI      = root.get("objectListIndex");
  const JV *jOL       = root.get("objectList");
  const JV *jITI      = root.get("indirectTextIndex");
  const JV *jCText    = root.get("compressedText");
  const JV *jDBs      = root.get("databases");
  const JV *jDsaIdx   = root.get("dsaLevelIndex");
  if (!jTypeMap || !jIdxMap || !jCells || !jMonsters || !jChamps ||
      !jTimers || !jTQueue || !jLevels || !jOLI || !jOL || !jITI ||
      !jCText || !jDBs)
    return sjFail("array dati mancante nel JSON");

  i32 mapLen    = (i32)jTypeMap->a.size();
  i32 cellBytes = (i32)jCells->a.size();
  i32 maxItem16 = (i32)jvI(state, "maxItem16");
  i32 maxTimers = (i32)jvI(state, "maxTimers");
  if ((i32)jIdxMap->a.size() != mapLen)
    return sjFail("dataIndexMap: attesi %d elementi", (int)mapLen);
  if ((i32)jMonsters->a.size() != maxItem16)
    return sjFail("activeMonsters: attesi %d elementi", (int)maxItem16);
  if (jChamps->a.size() != 4) return sjFail("champions: attesi 4 elementi");
  if ((i32)jTimers->a.size() != maxTimers)
    return sjFail("timers: attesi %d elementi", (int)maxTimers);
  if ((i32)jTQueue->a.size() != maxTimers)
    return sjFail("timerQueue: attesi %d elementi", (int)maxTimers);

  // ---- extended features block ----
  EXTENDEDFEATURESBLOCK efb;
  memset(&efb, 0, sizeof(efb));
  strcpy(efb.sentinel, " Extended Features ");
  efb.dataMapLength = mapLen;
  efb.cellFlagArraySize = cellBytes;
  efb.version = (char)jvI(options, "efbVersion");
  efb.numDSA = 0;
  efb.graphicsSignature1 = (ui32)jvI(options, "graphicsSignature1");
  efb.graphicsSignature2 = (ui32)jvI(options, "graphicsSignature2");
  efb.CSBgraphicsSignature1 = (ui32)jvI(options, "csbGraphicsSignature1");
  efb.CSBgraphicsSignature2 = (ui32)jvI(options, "csbGraphicsSignature2");
  efb.spellFilterLocation = (ui32)jvI(options, "spellFilterLocation");
  jvBytes(options->get("hintKey"), (ui8 *)efb.hintKey, 8);
  efb.flags |= EXTENDEDFEATURESBLOCK::LevelDSAInfoPresent;
  if (jvB(options, "invisibleMonsters"))
    efb.extendedFlags |= EXTENDEDFEATURESBLOCK::InvisibleMonsters;
  if (jvB(options, "dmRules"))
    efb.extendedFlags |= EXTENDEDFEATURESBLOCK::DMRulesOption;
  if (jvB(options, "extendedWallDecorations"))
    efb.extendedFlags |= EXTENDEDFEATURESBLOCK::ExtendedWallDecorations;
  if (jvB(options, "dmRulesDesign"))
    efb.flags |= EXTENDEDFEATURESBLOCK::DMRules;
  if (jvB(options, "drawAsSize4Monsters"))
    efb.extendedFlags |= EXTENDEDFEATURESBLOCK::DrawAsSize4Monsters;
  if (jvB(options, "autoRecord"))
    efb.flags |= EXTENDEDFEATURESBLOCK::AutoRecord;
  efb.flags |= EXTENDEDFEATURESBLOCK::IndirectText;
  efb.flags |= EXTENDEDFEATURESBLOCK::ExpandedPortraits;
  efb.flags |= EXTENDEDFEATURESBLOCK::BigActuators;
  efb.extendedFlags |= EXTENDEDFEATURESBLOCK::SequencedTimers;
  efb.extendedFlags |= EXTENDEDFEATURESBLOCK::ExtendedTimers;
  efb.extendedFlags |= EXTENDEDFEATURESBLOCK::DefaultDirectX;
  if (jvB(options, "overlayActive"))
    efb.extendedFlags |= EXTENDEDFEATURESBLOCK::OverlayActive;
  efb.overlayOrdinal = (i32)jvI(options, "overlayOrdinal");
  efb.overlayP1 = (i32)jvI(options, "overlayP1");
  efb.overlayP2 = (i32)jvI(options, "overlayP2");
  efb.overlayP3 = (i32)jvI(options, "overlayP3");
  efb.overlayP4 = (i32)jvI(options, "overlayP4");

  // ---- mappe tipi/indici ----
  std::vector<ui8> typeMap(mapLen);
  std::vector<ui16> idxMap(mapLen);
  jvBytes(jTypeMap, &typeMap[0], mapLen);
  jvWords(jIdxMap, &idxMap[0], mapLen);
  efb.dataTypeMapChecksum = FormChecksum(&typeMap[0], mapLen);
  efb.dataIndexMapChecksum = FormChecksum((ui8 *)&idxMap[0], 2 * mapLen);

  // ---- game info ----
  // Tollera json con "gameInfo": null (salvataggi scritti prima del fix nel
  // writer): senza stringa il DAT avrebbe gameInfoSize=0 e il load nativo
  // fallirebbe con "SAVED GAME DAMAGED!"; si usa il default di ReadGameInfo.
  std::string gInfo = "No game information was provided";
  {
    const JV *gi = root.get("gameInfo");
    if (gi && gi->t == JV::STR && !gi->s.empty()) gInfo = gi->s;
  }
  efb.gameInfoSize = (ui32)gInfo.size();

  // checksum dell'EFB (campo a 0 durante il calcolo, come nel nativo)
  efb.extendedFeaturesChecksum = 0;
  efb.extendedFeaturesChecksum = FormChecksum((ui8 *)&efb, 512);

  // ---- GAMEBLOCK2 ----
  SJGB2 gb2;
  memset(&gb2, 0, sizeof(gb2));
  gb2.Time         = (i32)jvI(state, "time");
  gb2.ranseed      = (i32)jvI(state, "randomSeed");
  gb2.ObjectInHand = (ui16)jvI(state, "objectInHand");
  gb2.numcharacter = (i16)jvI(state, "numCharacters");
  gb2.partyx       = (i16)jvI(state, "partyX");
  gb2.partyy       = (i16)jvI(state, "partyY");
  gb2.partyfacing  = (i16)jvI(state, "partyFacing");
  gb2.partyLevel   = (i16)jvI(state, "partyLevel");
  gb2.handChar     = (i16)jvI(state, "handChar");
  gb2.MagicCaster  = (i16)jvI(state, "magicCaster");
  gb2.NumTimer     = (i16)jvI(state, "numTimers");
  gb2.FirstAvailTimer = (i16)jvI(state, "firstAvailTimer");
  gb2.MaxTimers    = (i16)maxTimers;
  gb2.ITEM16QueLen = (i16)jvI(state, "item16QueLen");
  gb2.LastMonsterAttackTime = (i32)jvI(state, "lastMonsterAttackTime");
  gb2.LastPartyMoveTime     = (i32)jvI(state, "lastPartyMoveTime");
  gb2.partyMoveDisableTimer = (i16)jvI(state, "partyMoveDisableTimer");
  gb2.Word11712    = (i16)jvI(state, "word11712");
  gb2.Word11714    = (i16)jvI(state, "word11714");
  gb2.MaxITEM16    = (i16)maxItem16;
  gb2.TimerSequence = (ui16)jvI(state, "timerSequence");

  // ---- mostri attivi ----
  std::vector<ui8> item16Buf(16 * maxItem16, 0);
  for (i32 i = 0; i < maxItem16; i++)
  {
    const JV *m = &jMonsters->a[i];
    ITEM16 it;
    memset(&it, 0, sizeof(it));
    it.word0 = (i16)jvI(m, "monster");
    it.facings()   = (ui8)jvI(m, "facings");
    it.positions() = (ui8)jvI(m, "positions");
    it.uByte4  = (ui8)jvI(m, "b4");
    it.uByte5  = (ui8)jvI(m, "b5");
    it.uByte6  = (ui8)jvI(m, "targetX");
    it.uByte7  = (ui8)jvI(m, "targetY");
    it.ubPrevX = (ui8)jvI(m, "prevX");
    it.ubPrevY = (ui8)jvI(m, "prevY");
    it.uByte10 = (ui8)jvI(m, "mapX");
    it.uByte11 = (ui8)jvI(m, "mapY");
    {
      ui8 status[4] = {0, 0, 0, 0};
      jvBytes(m->get("status"), status, 4);
      memcpy(&it.singleMonsterStatus[0], status, 4);
    }
    memcpy(&item16Buf[16 * i], &it, 16);
  }

  // ---- blocco personaggi (3328 byte: 4 campioni + stato party) ----
  std::vector<ui8> charBuf(3328, 0);
  for (int i = 0; i < 4; i++)
    if (!sjBuildChampion(&jChamps->a[i], &charBuf[800 * i]))
      return sjFail("champion %d: campi mancanti o malformati", i);
  {
    ui8 *t = &charBuf[3200];
    ui16 w;
    w = (ui16)jvI(partySt, "brightness");            memcpy(t + 0, &w, 2);
    t[2] = (ui8)jvI(partySt, "seeThruWalls");
    t[3] = (ui8)jvI(partySt, "magicFootprintsActive");
    w = (ui16)jvI(partySt, "partyShield");           memcpy(t + 4, &w, 2);
    w = (ui16)jvI(partySt, "fireShield");            memcpy(t + 6, &w, 2);
    w = (ui16)jvI(partySt, "spellShield");           memcpy(t + 8, &w, 2);
    t[10] = (ui8)jvI(partySt, "numFootprintEnt");
    t[11] = (ui8)jvI(partySt, "freezeLifeTimer");
    t[12] = (ui8)jvI(partySt, "footprintFirst");
    t[13] = (ui8)jvI(partySt, "footprintLast");
    if (!jvWords(partySt->get("partyFootprints"), (ui16 *)(t + 14), 24))
      return sjFail("partyFootprints: attesi 24 elementi");
    if (!jvBytes(partySt->get("byte13220"), t + 62, 24))
      return sjFail("byte13220: attesi 24 elementi");
    t[86] = (ui8)jvI(partySt, "invisible");
    if (!jvBytes(partySt->get("byte13195"), t + 87, 41))
      return sjFail("byte13195: attesi 41 elementi");
  }

  // ---- timer ----
  std::vector<ui8> timerBuf(16 * maxTimers, 0);
  for (i32 i = 0; i < maxTimers; i++)
  {
    const JV *jt = &jTimers->a[i];
    TIMER t;
    ui8 b[5], unused[3];
    b[0] = (ui8)jvI(jt, "b5"); b[1] = (ui8)jvI(jt, "b6");
    b[2] = (ui8)jvI(jt, "b7"); b[3] = (ui8)jvI(jt, "b8");
    b[4] = (ui8)jvI(jt, "b9");
    const JV *ju = jt->get("unused");
    unused[0] = unused[1] = unused[2] = 0xcd;
    if (ju && ju->t == JV::ARR && ju->a.size() == 3)
      for (int k = 0; k < 3; k++) unused[k] = (ui8)(long)ju->a[k].n;
    SaveJsonAccess::TimerSet(t, (ui32)jvI(jt, "time"),
                             (ui8)jvI(jt, "function"), b,
                             (ui16)jvI(jt, "sequence"),
                             (ui8)jvI(jt, "level"), unused);
    memcpy(&timerBuf[16 * i], &t, 16);
  }
  std::vector<ui16> timerQue(maxTimers, 0);
  jvWords(jTQueue, &timerQue[0], maxTimers);

  // ---- indice dungeon + descrittori livelli ----
  ui16 dngWords[22];
  dngWords[0] = (ui16)jvI(dngIdx, "sentinel");
  dngWords[1] = (ui16)jvI(dngIdx, "legacyCellFlagSize");
  dngWords[2] = (ui16)jvI(dngIdx, "word4");
  dngWords[3] = (ui16)jvI(dngIdx, "numTextWords");
  dngWords[4] = (ui16)jvI(dngIdx, "word8");
  dngWords[5] = (ui16)jvI(dngIdx, "objectListLength");
  if (!jvWords(dngIdx->get("dbSizes"), dngWords + 6, 16))
    return sjFail("dungeonIndex.dbSizes: attesi 16 elementi");
  i32 numLevel = (dngWords[2] >> 8) & 0xff; // NumLevel = BITS8_15(word4)
  if ((i32)jLevels->a.size() != numLevel)
    return sjFail("levels: attesi %d elementi", (int)numLevel);
  std::vector<ui8> levelBuf(16 * numLevel, 0);
  for (i32 i = 0; i < numLevel; i++)
  {
    const JV *lv = &jLevels->a[i];
    ui8 *b = &levelBuf[16 * i];
    ui16 w;
    w = (ui16)jvI(lv, "cellOffset"); memcpy(b, &w, 2);
    const JV *f2 = lv->get("fill2");
    if (f2 && f2->t == JV::ARR && f2->a.size() == 4)
      for (int k = 0; k < 4; k++) b[2 + k] = (ui8)(long)f2->a[k].n;
    b[6] = (ui8)jvI(lv, "offsetX");
    b[7] = (ui8)jvI(lv, "offsetY");
    w = (ui16)jvI(lv, "word8");  memcpy(b + 8, &w, 2);
    w = (ui16)jvI(lv, "word10"); memcpy(b + 10, &w, 2);
    w = (ui16)jvI(lv, "word12"); memcpy(b + 12, &w, 2);
    w = (ui16)jvI(lv, "word14"); memcpy(b + 14, &w, 2);
  }

  // ---- liste oggetti, testi ----
  i32 numOLI = (i32)jOLI->a.size();
  i32 numOL  = (i32)jOL->a.size();
  if (numOL != (i32)dngWords[5])
    return sjFail("objectList: attesi %d elementi", (int)dngWords[5]);
  i32 numITI = (i32)jITI->a.size();
  if (numITI != (i32)dngWords[3])
    return sjFail("indirectTextIndex: attesi %d elementi", (int)dngWords[3]);
  i32 numCText = (i32)jCText->a.size();
  std::vector<ui16> oliBuf(numOLI ? numOLI : 1);
  std::vector<ui16> olBuf(numOL ? numOL : 1);
  std::vector<ui32> itiBuf(numITI ? numITI : 1);
  std::vector<ui16> ctextBuf(numCText ? numCText : 1);
  jvWords(jOLI, &oliBuf[0], numOLI);
  jvWords(jOL, &olBuf[0], numOL);
  for (i32 i = 0; i < numITI; i++)
    itiBuf[i] = (ui32)(unsigned long)jITI->a[i].n;
  jvWords(jCText, &ctextBuf[0], numCText);

  // ---- database ----
  std::vector<ui8> dbBuf[16];
  for (int dbNum = 0; dbNum < 16; dbNum++)
  {
    char key[32];
    snprintf(key, sizeof(key), "%02d_%s", dbNum, dbNames[dbNum]);
    const JV *jdb = jDBs->get(key);
    i32 numEnt = dngWords[6 + dbNum];
    i32 entryWords = dbEntrySizes[dbNum] / 2;
    if (!jdb || jdb->t != JV::ARR || (i32)jdb->a.size() != numEnt)
      return sjFail("database %s: attesi %d record", key, (int)numEnt);
    dbBuf[dbNum].resize((size_t)numEnt * entryWords * 2, 0);
    for (i32 e = 0; e < numEnt; e++)
    {
      if (!jvWords(&jdb->a[e], (ui16 *)&dbBuf[dbNum][(size_t)e * entryWords * 2],
                   entryWords))
        return sjFail("database %s record %d: attese %d parole",
                      key, (int)e, (int)entryWords);
    }
  }

  // ---- cellflag ----
  std::vector<ui8> cellBuf(cellBytes);
  jvBytes(jCells, &cellBuf[0], cellBytes);

  if (jvFail)
    return sjFail("campo numerico mancante o non valido: %s", jvMissing);

  // ---- conversione dei buffer in ORDINE-FILE (come le swap* native) ----
  sjSwapGB2(&gb2);
  for (i32 i = 0; i < maxItem16; i++)    // swapITEM16s: solo word0
  {
    i16 *w0 = (i16 *)&item16Buf[16 * i];
    *w0 = LE16(*w0);
  }
  {
    // coda del blocco personaggi (swapCharacterData): Brightness + footprints
    ui8 *t = &charBuf[3200];
    ui16 *bw = (ui16 *)(t + 0);
    *bw = LE16(*bw);
    ui16 *fp = (ui16 *)(t + 14);
    for (int i = 0; i < 24; i++) fp[i] = LE16(fp[i]);
  }
  {
    // swapTimers: SOLO i timer attivi (handle in timerQueue[0..numTimers)),
    // con i campi funzione-dipendenti (word6/word8) come nel nativo
    i32 numT = (i32)jvI(state, "numTimers");
    for (i32 k = 0; k < numT && k < maxTimers; k++)
    {
      i32 h = timerQue[k];
      if (h < 0 || h >= maxTimers) continue;
      ui8 *tb = &timerBuf[16 * h];
      ui32 *tm = (ui32 *)(tb + 0);
      *tm = (ui32)LE32((i32)*tm);
      ui16 *seq = (ui16 *)(tb + 10);
      *seq = LE16(*seq);
      ui16 *w6 = (ui16 *)(tb + 6), *w8 = (ui16 *)(tb + 8);
      switch (tb[4])                     // timerFunction
      {
        case 13: case 24: case 25: case 60: case 61: case 65:
          *w8 = LE16(*w8); break;       // obj8 (ViAltar, generatori, ...)
        case 48: case 49:
          *w6 = LE16(*w6);              // obj6 + word8 (missili)
          *w8 = LE16(*w8); break;
        case 70: case 72: case 74: case 75: case 77: case 78:
          *w6 = LE16(*w6); break;       // word6
      }
    }
  }
  for (i32 i = 0; i < maxTimers; i++)    // swapTimerQue
    timerQue[i] = LE16(timerQue[i]);
  ((DUNGEONDATINDEX *)dngWords)->Swap(); // indice dungeon
  for (i32 i = 0; i < numLevel; i++)     // swapLevelDescriptors: 5 word su 8
  {
    ui16 *w = (ui16 *)&levelBuf[16 * i];
    w[0] = LE16(w[0]);                   // cellOffset
    w[4] = LE16(w[4]); w[5] = LE16(w[5]);// word8, word10
    w[6] = LE16(w[6]); w[7] = LE16(w[7]);// word12, word14
  }
  for (i32 i = 0; i < numOLI; i++) oliBuf[i] = LE16(oliBuf[i]);
  for (i32 i = 0; i < numOL; i++)  olBuf[i]  = LE16(olBuf[i]);
  for (int dbNum = 0; dbNum < 16; dbNum++)
    if (!dbBuf[dbNum].empty())
      sjSwapDatabase(dbNum, &dbBuf[dbNum][0], dngWords[6 + dbNum] ?
                     (i32)(dbBuf[dbNum].size() / dbEntrySizes[dbNum]) : 0);

  // ---- GAMEBLOCK1 con i checksum ricalcolati ----
  SJGB1 gb1;
  memset(&gb1, 0, sizeof(gb1));
  gb1.SaveOption   = (i16)jvI(header, "saveOption", 1, false);
  gb1.RandomGameID = (i32)jvI(header, "randomGameID");
  gb1.Byte22596    = (ui8)jvI(header, "byte22596");
  gb1.Byte22598    = (i8)jvI(header, "byte22598");
  gb1.Word22592    = (i16)jvI(header, "word22592");
  gb1.Word22594    = (i16)jvI(header, "word22594");
  gb1.totalMoveCount = (i32)jvI(header, "totalMoveCount");
  jvBytes(header->get("byte22808"), (ui8 *)gb1.Byte22808, 132);
  gb1.Block2Checksum    = GenChecksum((ui8 *)&gb2, 0, 128 / 2);
  gb1.ITEM16Checksum    = GenChecksum(&item16Buf[0], 0, (16 * maxItem16) / 2);
  gb1.CharacterChecksum = GenChecksum(&charBuf[0], 0, 3328 / 2);
  gb1.TimersChecksum    = GenChecksum(&timerBuf[0], 0, (16 * maxTimers) / 2);
  gb1.TimerQueChecksum  = GenChecksum((ui8 *)&timerQue[0], 0, maxTimers);
  sjSwapGB1(&gb1);                       // ordine-file (come swapBlock1)

  if (jvFail)
    return sjFail("campo numerico mancante o non valido: %s", jvMissing);

  // ---- scrittura del DAT (stessa sequenza del salvataggio nativo) ----
  i16 savedHandle = d.datafileHandle;
  d.datafileHandle = CREATE(datPath, "w+b", false);
  if (d.datafileHandle < 0)
  {
    d.datafileHandle = savedHandle;
    return sjFail("impossibile creare %s", datPath);
  }
  bool ok = true;
  #define SJ_CHECK(x) do { if (ok && !(x)) ok = false; } while (0)

  WRITE(d.datafileHandle, 512, (ui8 *)&efb);
  WRITE(d.datafileHandle, mapLen, &typeMap[0]);
  WRITE(d.datafileHandle, 2 * mapLen, (ui8 *)&idxMap[0]);
  // (niente DSA: numDSA == 0)
  if (efb.gameInfoSize != 0)
    WRITE(d.datafileHandle, efb.gameInfoSize, (ui8 *)gInfo.c_str());
  // indice DSA per livello + terminatore
  {
    unsigned char triple[3];
    if (jDsaIdx && jDsaIdx->t == JV::ARR)
      for (size_t i = 0; i < jDsaIdx->a.size(); i++)
      {
        const JV &e = jDsaIdx->a[i];
        if (e.t != JV::ARR || e.a.size() != 3) continue;
        triple[0] = (unsigned char)(long)e.a[0].n;
        triple[1] = (unsigned char)(long)e.a[1].n;
        triple[2] = (unsigned char)(long)e.a[2].n;
        WRITE(d.datafileHandle, 3, triple);
      }
    triple[0] = triple[1] = triple[2] = 255;
    WRITE(d.datafileHandle, 3, triple);
  }

  i16 scratchCS = 0;
  SJ_CHECK(ScrambleAndWrite((i16 *)&gb1) != 0);
  SJ_CHECK(WriteScrambled((pnt)&gb2, 128, gb1.Block2Hash, &scratchCS) != 0);
  SJ_CHECK(WriteScrambled((pnt)&item16Buf[0], 16 * maxItem16,
                          gb1.ITEM16Hash, &scratchCS) != 0);
  SJ_CHECK(WriteScrambled((pnt)&charBuf[0], 3328,
                          gb1.CharacterHash, &scratchCS) != 0);
  SJ_CHECK(WriteScrambled((pnt)&timerBuf[0], 16 * maxTimers,
                          gb1.TimersHash, &scratchCS) != 0);
  SJ_CHECK(WriteScrambled((pnt)&timerQue[0], 2 * maxTimers,
                          gb1.TimerQueHash, &scratchCS) != 0);

  i16 runningCS = 0;
  SJ_CHECK(WriteAndChecksum((ui8 *)dngWords, &runningCS, 44) != 0);
  SJ_CHECK(WriteAndChecksum(&levelBuf[0], &runningCS, 16 * numLevel) != 0);
  SJ_CHECK(WriteAndChecksum((ui8 *)&oliBuf[0], &runningCS, 2 * numOLI) != 0);
  SJ_CHECK(WriteAndChecksum((ui8 *)&olBuf[0], &runningCS, 2 * numOL) != 0);
  SJ_CHECK(WriteAndChecksum((ui8 *)&itiBuf[0], &runningCS, 4 * numITI) != 0);
  {
    ui32 szText = (ui32)numCText;
    SJ_CHECK(WriteAndChecksum((ui8 *)&szText, &runningCS, 4) != 0);
  }
  SJ_CHECK(WriteAndChecksum((ui8 *)&ctextBuf[0], &runningCS, 2 * numCText) != 0);
  for (int dbNum = 0; dbNum < 16; dbNum++)
  {
    if (dngWords[6 + dbNum] == 0) continue;
    SJ_CHECK(WriteAndChecksum(&dbBuf[dbNum][0], &runningCS,
                              (i32)dbBuf[dbNum].size()) != 0);
  }
  SJ_CHECK(WriteAndChecksum(&cellBuf[0], &runningCS, cellBytes) != 0);
  runningCS = LE16(runningCS);           // il nativo scrive il checksum swappato
  SJ_CHECK(WriteDataFile(&runningCS, 2) != 0);
  #undef SJ_CHECK

  CLOSE(d.datafileHandle);
  d.datafileHandle = savedHandle;
  if (!ok) return sjFail("errore di scrittura su %s", datPath);
  fprintf(stderr, "[savejson] ricostruito %s da %s\n", datPath, jsonPath);
  return 0;
}
