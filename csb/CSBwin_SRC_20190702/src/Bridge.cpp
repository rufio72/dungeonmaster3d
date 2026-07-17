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
#include <dirent.h>

#include "SaveJson.h"

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
static bool BridgeSrv_HasSubscribers(void);
static void EmitChampsText(FILE *ft);          // sezione CHAMPS del push (HUD)
// nomi azione del menu attacco (CSBCode.cpp, non dichiarata in CSB.h)
const char *ActionNameFilter(const char *defaultName, i32 charIndex,
                             i32 actionNumber, i32 numAttackOption);

// Log per-dump (~16/s con SUB attivo) solo se richiesto: DM_BRIDGE_VERBOSE=1.
// I messaggi una-tantum (mappa, ritratti, server in ascolto) restano sempre.
static int Bridge_Verbose(void)
{
  static int v = -1;
  if (v < 0)
  {
    const char *e = getenv("DM_BRIDGE_VERBOSE");
    v = (e != NULL && *e != '\0' && *e != '0') ? 1 : 0;
  }
  return v;
}

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

// ---- estrazione GRAFICHE decorazioni di pavimento (il "ciuffo d'erba") ------
// Le decorazioni random di pavimento sono basic-graphics (stesso formato planare
// delle porte). I numeri grafica base sono in d.LoadedFloorDecorations[i][0]
// (l'ordinale i+1 restituito da RandomWallDecoration -> DECOR). Dumpiamo tutte
// quelle caricate per il livello corrente in decorgfx/g<N>.raw + decor.txt +
// palette.txt: il decoder (gfx_decode.py) le converte in PNG, il 3D le pesca
// per numero grafica. Rispetta il modello degli altri export (doors/, jambs/).
static int Bridge_ExportDecorGfx(void)
{
  mkdir("decorgfx", 0755);
  FILE *m = fopen("decorgfx/decor.txt", "w");
  if (m == NULL) return 0;
  int saved = 0;
  for (int i = 0; i < 16; i++)
  {
    int N = (int)d.LoadedFloorDecorations[i][0];
    if (N <= 0) continue;
    if (Bridge_DumpGfx("decorgfx", m, N)) saved++;
  }
  fclose(m);
  Bridge_DumpPalette("decorgfx");
  printf("[bridge] grafiche decorazioni pavimento: %d salvate -> decorgfx/\n", saved);
  fflush(stdout);
  return saved;
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
// leve (44,45) e serrature/keyhole (5,17..32).  L'altare di Vi e' tipo 59 ->
// grafiche 121+2*59=239/240, quindi dumpiamo 121..255 (tutte le decorazioni,
// con margine); il decoder etichetta dal tipo.
[[maybe_unused]] static int Bridge_ExportButtons(void)
{
  mkdir("buttons", 0755);
  FILE *m = fopen("buttons/buttons.txt", "w");
  if (m == NULL) return 0;
  int saved = 0;
  for (int N = 121; N <= 255; N++) saved += Bridge_DumpGfx("buttons", m, N);
  fclose(m);
  Bridge_DumpPalette("buttons");
  printf("[bridge] bottoni/serrature: %d salvati (121..255) -> buttons/\n", saved);
  fflush(stdout);
  return saved;
}

// ---- estrazione grafiche INVENTARIO (schermata specchio/scheda campione) --
// Tutto cio' che serve a dm3d per replicare la VERA schermata inventario
// (vedi ANALISI_SCHEDA_CAMPIONI.md):
//   17       pannello/manichino (sfondo, riempie il viewport 224x136)
//   40       pannello bottoni RESURRECT/RE-INCARNATE/CANCEL
//   42..48   7 atlanti icone oggetti (celle 16x16, 16 colonne)
//   33/34/35 cornici slot (normale/"ouch"/mano che attacca)
//   37/38/39 icone shield, 28 icona pos/dir, 8 box campione morto
//   27       griglia lettere del name-entry (REINCARNATE, fase 3)
// Metadati (non-grafiche, esclusi dal decoder):
//   icondisplay.txt  46 righe "x y" (0-7 mani, 8-37 slot inventario, 38-45 chest)
//   layout.txt       rect di g40 nel viewport (wRectPos926), rect schermo del
//                    viewport (Word28), i bottoni RRC di Buttons18372 (spazio
//                    mouse: code x1 x2 y1 y2) e la tabella Word612 degli atlanti
[[maybe_unused]] static int Bridge_ExportInvGfx(void)
{
  // 20/30/31/32 = pannello FOOD/WATER (box, label FOOD, label WATER, veleno)
  // 9 = sfondo pannello magia (rune/backspace/cast, blit su Word12 quando
  //     viene selezionato un caster — SelectMagicCaster, Magic.cpp:728)
  // 10 = pannello del menu azioni d'attacco (blit su wRectPos20202/10/18
  //      quando si clicca un pannello arma — DrawLegalAttackTypes)
  static const int gfxList[] =
    { 8, 9, 10, 17, 20, 27, 28, 30, 31, 32, 33, 34, 35, 37, 38, 39, 40,
      42, 43, 44, 45, 46, 47, 48 };

  mkdir("invgfx", 0755);
  FILE *m = fopen("invgfx/gfx.txt", "w");
  if (m == NULL) return 0;
  int saved = 0;
  for (size_t i = 0; i < sizeof(gfxList) / sizeof(gfxList[0]); i++)
    saved += Bridge_DumpGfx("invgfx", m, gfxList[i]);
  fclose(m);
  if (saved == 0) return 0;              // graphics.dat non pronto: ritenta
  Bridge_DumpPalette("invgfx");

  FILE *f = fopen("invgfx/icondisplay.txt", "w");
  if (f != NULL)
  {
    for (int i = 0; i < 46; i++)
      fprintf(f, "%d %d\n", (int)d.IconDisplay[i].pixelX,
                            (int)d.IconDisplay[i].pixelY);
    fclose(f);
  }

  FILE *lf = fopen("invgfx/layout.txt", "w");
  if (lf != NULL)
  {
    fprintf(lf, "g40 %d %d %d %d\n",
            (int)d.wRectPos926.w.x1, (int)d.wRectPos926.w.x2,
            (int)d.wRectPos926.w.y1, (int)d.wRectPos926.w.y2);
    fprintf(lf, "viewport %d %d %d %d\n",
            (int)d.Word28[0], (int)d.Word28[1],
            (int)d.Word28[2], (int)d.Word28[3]);
    // pannello food/water: destinazioni delle label (il box g20 usa lo
    // stesso rect di g40, wRectPos926, gia' esportato sopra)
    fprintf(lf, "g30 %d %d %d %d\n",
            (int)d.wRectPos950.w.x1, (int)d.wRectPos950.w.x2,
            (int)d.wRectPos950.w.y1, (int)d.wRectPos950.w.y2);
    fprintf(lf, "g31 %d %d %d %d\n",
            (int)d.wRectPos958.w.x1, (int)d.wRectPos958.w.x2,
            (int)d.wRectPos958.w.y1, (int)d.wRectPos958.w.y2);
    fprintf(lf, "g32 %d %d %d %d\n",
            (int)d.wRectPos966.w.x1, (int)d.wRectPos966.w.x2,
            (int)d.wRectPos966.w.y1, (int)d.wRectPos966.w.y2);
    for (int i = 0; i < 4; i++)
    {
      if (d.Buttons18372[i].word0 == 0) break;
      fprintf(lf, "button %d %d %d %d %d\n",
              (int)d.Buttons18372[i].word0,
              (int)d.Buttons18372[i].xMin, (int)d.Buttons18372[i].xMax,
              (int)d.Buttons18372[i].yMin, (int)d.Buttons18372[i].yMax);
    }
    fprintf(lf, "word612");
    for (int i = 0; i < 7; i++) fprintf(lf, " %d", (int)d.Word612[i]);
    fprintf(lf, "\n");
    // button-list dell'inventario aperto (SecondaryButtonList = Buttons17760):
    // 38 slot possession, ognuno con codice + rect (spazio mouse 2D). Serve al
    // 3D per il drag-and-drop: iniettare il click sullo slot col codice reale.
    for (int i = 0; i < 38; i++)
    {
      if (d.Buttons17760[i].word0 == 0) break;
      fprintf(lf, "ibutton %d %d %d %d %d %d\n",
              (int)d.Buttons17760[i].word0,
              (int)d.Buttons17760[i].xMin, (int)d.Buttons17760[i].xMax,
              (int)d.Buttons17760[i].yMin, (int)d.Buttons17760[i].yMax,
              (int)d.Buttons17760[i].button);
    }
    // quadrato marching-order: i 4 rect-cella (spazio schermo 2D; il 3D usa
    // solo la geometria RELATIVA fra le celle) — Character.cpp:1460
    for (int i = 0; i < 4; i++)
      fprintf(lf, "march %d %d %d %d\n",
              (int)d.wRectPos1454[i].w.x1, (int)d.wRectPos1454[i].w.x2,
              (int)d.wRectPos1454[i].w.y1, (int)d.wRectPos1454[i].w.y2);
    // barra magie: rect del pannello g9 (Word12, SelectMagicCaster) e bottoni
    // rune/backspace/cast di Buttons18060 (spazio mouse: code x1 x2 y1 y2,
    // codici 101-106 rune, 107 backspace, 108 cast — Magic.cpp:701,556)
    fprintf(lf, "word12 %d %d %d %d\n",
            (int)((RectPos *)d.Word12)->w.x1, (int)((RectPos *)d.Word12)->w.x2,
            (int)((RectPos *)d.Word12)->w.y1, (int)((RectPos *)d.Word12)->w.y2);
    for (int i = 0; i < 9; i++)
    {
      if (d.Buttons18060[i].word0 == 0) break;
      fprintf(lf, "mbutton %d %d %d %d %d\n",
              (int)d.Buttons18060[i].word0,
              (int)d.Buttons18060[i].xMin, (int)d.Buttons18060[i].xMax,
              (int)d.Buttons18060[i].yMin, (int)d.Buttons18060[i].yMax);
    }
    // pannello attacco (HUD-3): rect del menu azioni per 3/2/1 opzioni
    // (wRectPos20202/20210/20218, dove DrawLegalAttackTypes blitta g10) e le
    // due button-list di TAG019fac (spazio mouse: code x1 x2 y1 y2):
    //   abutton = Buttons17952, pannelli 19x34 (116-119, menu CHIUSO)
    //   obutton = Buttons17892, menu APERTO (112 pass/nome, 113-115 opzioni)
    fprintf(lf, "amenu3 %d %d %d %d\n",
            (int)d.wRectPos20202.w.x1, (int)d.wRectPos20202.w.x2,
            (int)d.wRectPos20202.w.y1, (int)d.wRectPos20202.w.y2);
    fprintf(lf, "amenu2 %d %d %d %d\n",
            (int)d.wRectPos20210.w.x1, (int)d.wRectPos20210.w.x2,
            (int)d.wRectPos20210.w.y1, (int)d.wRectPos20210.w.y2);
    fprintf(lf, "amenu1 %d %d %d %d\n",
            (int)d.wRectPos20218.w.x1, (int)d.wRectPos20218.w.x2,
            (int)d.wRectPos20218.w.y1, (int)d.wRectPos20218.w.y2);
    for (int i = 0; i < 5; i++)
    {
      if (d.Buttons17952[i].word0 == 0) break;
      fprintf(lf, "abutton %d %d %d %d %d\n",
              (int)d.Buttons17952[i].word0,
              (int)d.Buttons17952[i].xMin, (int)d.Buttons17952[i].xMax,
              (int)d.Buttons17952[i].yMin, (int)d.Buttons17952[i].yMax);
    }
    for (int i = 0; i < 5; i++)
    {
      if (d.Buttons17892[i].word0 == 0) break;
      fprintf(lf, "obutton %d %d %d %d %d\n",
              (int)d.Buttons17892[i].word0,
              (int)d.Buttons17892[i].xMin, (int)d.Buttons17892[i].xMax,
              (int)d.Buttons17892[i].yMin, (int)d.Buttons17892[i].yMax);
    }
    fclose(lf);
  }

  // FONT originale del gioco: 768 byte gia' espansi in d.Pointer12926
  // (ReadAndExpandGraphic(0x822d), CSBCode.cpp:1804). Layout: 6 righe x 128
  // caratteri, byte[riga*128 + ascii] = 8 pixel orizzontali (BltOneChar,
  // Graphics.cpp:199: A2 += 128 per riga). Decodifica: dmfont_decode.py.
  if (d.Pointer12926 != NULL)
  {
    FILE *ff = fopen("invgfx/font.raw", "wb");
    if (ff != NULL) { fwrite(d.Pointer12926, 1, 768, ff); fclose(ff); }
  }

  printf("[bridge] grafiche inventario: %d salvate -> invgfx/ (+ icondisplay/layout/font)\n",
         saved);
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
  int fxA[256], fyA[256], dirA[256], ordA[256], actA[256];
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
        // NB: gli specchi gia' usati (HidePortrait, campione reclutato) NON
        // si saltano piu': si esportano con act=0, cosi' il viewer 3D
        // costruisce comunque il quadro col volto sbiadito anche se viene
        // (ri)avviato DOPO il reclutamento. Prima sparivano dalla lista e
        // al posto del quadro restava solo lo specchio vuoto T=43.
        int hidden = (p->State() & PORTRAIT_HidePortrait) != 0;

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
        // 5a riga: attivo/spento — il reclutamento disattiva l'attuatore
        // (PORTRAIT_InActive) e nel 2D lo specchio resta sbiadito
        actA[cnt] = (!hidden && (p->State() & PORTRAIT_InActive) == 0) ? 1 : 0;
        cnt++;
      }

  fprintf(f, "%d\n", cnt);
  for (int i = 0; i < cnt; i++)
    fprintf(f, "%d\n%d\n%d\n%d\n%d\n",
            fxA[i], fyA[i], dirA[i], ordA[i], actA[i]);
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

// ---- oggetti a terra vicini (dinamico, per-tick) -------------------------
// Scandisce le celle in un raggio attorno al party e, per ogni cella, elenca
// gli OGGETTI POSATI sul pavimento (weapon/cloth/scroll/potion/chest/misc).
// Salta porte/teleporter/testi/attuatori/mostri (dbType 0..4): quelli non sono
// "cose" prendibili. Per ognuno riporta: coordinate griglia (x,y), il quadrante
// sul tile (pos 0..3), il dbType, il NameIndex (objNI, 0..198) e il nome
// leggibile da d.ObjectNames[]. Questo e' l'esatto oggetto che il 2D disegna
// sdraiato sul pavimento (il "verde" su cui sta il party). Formato a righe:
//   <count>
//   poi per ogni oggetto 6 righe: x / y / pos / dbType / objNI / nome
static const char *ObjNameSafe(int objNI)
{
  if (objNI < 0 || objNI >= 199) return "?";
  const char *s = d.ObjectNames[objNI];
  return (s != NULL) ? s : "?";
}

static void EmitFloorText(FILE *f)
{
  int px = d.partyX, py = d.partyY;
  int x0 = px - BRIDGE_MON_RADIUS, x1 = px + BRIDGE_MON_RADIUS;
  int y0 = py - BRIDGE_MON_RADIUS, y1 = py + BRIDGE_MON_RADIUS;
  if (x0 < 0) x0 = 0;
  if (y0 < 0) y0 = 0;
  if (x1 >= d.width)  x1 = d.width - 1;
  if (y1 >= d.height) y1 = d.height - 1;

  int    xA[512], yA[512], posA[512], typA[512], niA[512];
  const char *nmA[512];
  int cnt = 0;

  for (int y = y0; y <= y1 && cnt < 512; y++)
    for (int x = x0; x <= x1 && cnt < 512; x++)
      for (RN o = FindFirstObject(x, y); o != RNeof && cnt < 512;
           o = GetDBRecordLink(o))
      {
        int t = (int)o.dbType();
        if (t <= dbMONSTER) continue;   // 0=door..4=monster: non sono cose a terra
        if (t > dbMISC) continue;       // >10: pool/missile/cloud, non oggetti posati
        int objNI = (int)o.NameIndex();
        xA[cnt]   = x;
        yA[cnt]   = y;
        posA[cnt] = (int)(o.pos() & 3);
        typA[cnt] = t;
        niA[cnt]  = objNI;
        nmA[cnt]  = ObjNameSafe(objNI);
        cnt++;
      }

  fprintf(f, "%d\n", cnt);
  for (int i = 0; i < cnt; i++)
    fprintf(f, "%d\n%d\n%d\n%d\n%d\n%s\n",
            xA[i], yA[i], posA[i], typA[i], niA[i], nmA[i]);
}

// ---- decorazioni di pavimento vicine (dinamico, per-tick) ----------------
// Il "ciuffo d'erba" del 2D NON e' un oggetto: e' una decorazione di pavimento
// PROCEDURALE. Per ogni cella di pavimento aperto il motore calcola un ordinale
// via RandomWallDecoration(cellFlags&8, NumRandomFloorDecorations, x, y, 30) =
// Hash deterministico di (x,y,livello,dimensioni). Qui chiamiamo la STESSA
// funzione del motore, cosi' il risultato e' identico bit-per-bit a quello che
// il viewport 2D disegna. Esportiamo, per le celle nel raggio attorno al party:
//   count, poi per cella 4 righe: x / y / ordinale / graphicNumber(base)
// dove graphicNumber = LoadedFloorDecorations[ord-1][0] e' il numero grafico
// reale in graphics.dat (utile per estrarre l'immagine del ciuffo).
static void EmitDecorText(FILE *f)
{
  // Le decorazioni sono statiche e deterministiche: scandiamo l'INTERO livello
  // (non solo il raggio attorno al party), cosi' il 3D costruisce tutti i decal
  // una volta sola al caricamento. ~361 celle: costo trascurabile.
  int x0 = 0, x1 = d.width - 1;
  int y0 = 0, y1 = d.height - 1;

  int nRand = (d.pCurLevelDesc != NULL)
                ? (int)d.pCurLevelDesc->NumRandomFloorDecorations() : 0;

  int xA[512], yA[512], ordA[512], gnA[512];
  int cnt = 0;

  for (int y = y0; y <= y1 && cnt < 512; y++)
    for (int x = x0; x <= x1 && cnt < 512; x++)
    {
      int cf = (int)GetCellFlags(x, y);
      int roomType = (cf >> 5) & 7;
      if (roomType != roomOPEN) continue;          // erba solo su pavimento aperto
      int ord = (int)RandomWallDecoration((i16)(cf & 8), (i16)nRand, x, y, 30);
      if (ord <= 0) continue;
      int gi = ord - 1;
      int gn = (gi >= 0 && gi < 16) ? (int)d.LoadedFloorDecorations[gi][0] : -1;
      xA[cnt] = x; yA[cnt] = y; ordA[cnt] = ord; gnA[cnt] = gn;
      cnt++;
    }

  fprintf(f, "%d\n", cnt);
  for (int i = 0; i < cnt; i++)
    fprintf(f, "%d\n%d\n%d\n%d\n", xA[i], yA[i], ordA[i], gnA[i]);
}

// ---- pedane (attuatori con grafica di pavimento) -------------------------
// La "pedana" (pressure pad/dais visibile) e' un ATTUATORE su cella di pavimento
// con wallGraphicOrdinal()>0: DM la disegna come floor-decoration esplicita
// (Codea59a.cpp:340, ramo attuatore -> decorations[2]). Nel 3D NON usiamo quella
// grafica: il viewer ci disegna sopra una lastra quadrata sottile texturizzata
// come il pavimento. Qui riportiamo solo DOVE stanno: scandiamo l'intero livello
// e, per ogni cella con un attuatore wallGraphicOrdinal in
// [1..NumFloorDecorations], emettiamo x, y, ordinale. Oltre al pavimento aperto
// contano anche le celle TELEPORTER: il 2D ci disegna la decorazione (il case
// roomTELEPORTER salta a tag00a85c e quindi al loop attuatori) e il DM
// originale ne ha 22 sui livelli 2/3/5/9/10 - verificato su dungeon.dat.
// Formato:
//   count, poi 3 righe/pedana: x / y / ordinale
static void EmitPedaneText(FILE *f)
{
  int nFloor = (d.pCurLevelDesc != NULL)
                 ? (int)d.pCurLevelDesc->NumFloorDecorations() : 0;
  int xA[512], yA[512], ordA[512];
  int cnt = 0;

  for (int y = 0; y < d.height && cnt < 512; y++)
    for (int x = 0; x < d.width && cnt < 512; x++)
    {
      int cf = (int)GetCellFlags(x, y);
      int room = (cf >> 5) & 7;
      if (room != roomOPEN && room != roomTELEPORTER) continue;
      for (RN o = FindFirstObject(x, y); o != RNeof; o = GetDBRecordLink(o))
      {
        if (o.dbType() != dbACTUATOR) continue;
        DBCOMMON *a = GetCommonAddress(o);
        if (a == NULL) continue;
        DB3 *p = a->CastToDB3();
        if (p == NULL) continue;
        int wgo = (int)p->wallGraphicOrdinal();
        if (wgo > 0 && wgo <= nFloor)
        {
          xA[cnt] = x; yA[cnt] = y; ordA[cnt] = wgo;
          cnt++;
          break;                                       // una pedana per cella
        }
      }
    }

  fprintf(f, "%d\n", cnt);
  for (int i = 0; i < cnt; i++)
    fprintf(f, "%d\n%d\n%d\n", xA[i], yA[i], ordA[i]);
}

// ---- scale (statico, per livello) ------------------------------------------
// La MAPPA dice solo DOVE sono le scale (tipo cella 3); direzione e asse stanno
// nei bit bassi della CELLFLAG (CSBTypes.h): bit 2 = salita, bit 3 = asse N/S.
// Formato: count, poi 4 righe/scala: x / y / up(0=giu' 1=su) / ns(0=E-O 1=N-S)
static void EmitStairsText(FILE *f)
{
  int xA[128], yA[128], upA[128], nsA[128];
  int cnt = 0;

  for (int y = 0; y < d.height && cnt < 128; y++)
    for (int x = 0; x < d.width && cnt < 128; x++)
    {
      int cf = (int)GetCellFlags(x, y);
      if (((cf >> 5) & 7) != roomSTAIRS) continue;
      xA[cnt]  = x;
      yA[cnt]  = y;
      upA[cnt] = (cf >> 2) & 1;
      nsA[cnt] = (cf >> 3) & 1;
      cnt++;
    }

  fprintf(f, "%d\n", cnt);
  for (int i = 0; i < cnt; i++)
    fprintf(f, "%d\n%d\n%d\n%d\n", xA[i], yA[i], upA[i], nsA[i]);
}

// ---- decorazioni murali random (statico, per livello) ---------------------
// Il 2D decora i muri di pietra con ornamenti PROCEDURALI per-faccia: i bit
// 0-3 dei cellflags della cella-muro abilitano la decorazione su UNA faccia
// (la maschera dipende dal facing: SummarizeStoneRoom, Codea59a.cpp:47-70) e
// l'ordinale esce da RandomWallDecoration con P4 funzione della faccia. Qui
// emettiamo, per ogni faccia di muro adiacente a una cella calpestabile, la
// decorazione che il 2D disegna guardandola FRONTALMENTE (decorations[1]:
// vista di lato l'originale usa hash diversi, ma nel 3D la faccia e' una
// sola e la vista frontale e' quella canonica). Un attuatore con grafica, un
// ritratto (tipo 127) o un testo visibile sulla stessa faccia SOVRASCRIVONO
// l'ornamento random nel 2D (stesso slot): quelle facce le saltiamo, hanno
// gia' i loro canali (PORTRAITS/TEXTS; i dispositivi arriveranno a parte).
// Formato (stessa convenzione dei ritratti: cella calpestabile + dir muro):
//   count, poi 7 righe/decorazione:
//   fx / fy / dir(0=O 1=E 2=N 3=S) / tipoT / graphicNear / wPix / hPix
static void EmitWallDecText(FILE *f)
{
  int W = d.width, H = d.height;
  static const int DDX[4]   = { -1, 1, 0, 0 };   // cella -> muro, per dir
  static const int DDY[4]   = { 0, 0, -1, 1 };
  static const int DFACE[4] = { 3, 1, 0, 2 };    // party facing che vede la faccia
  static const int DMASK[4] = { 4, 1, 2, 8 };    // bit cellflags della faccia (rear)
  int nRand = (d.pCurLevelDesc != NULL)
                ? (int)d.pCurLevelDesc->NumRandomWallDecoration() : 0;

  int fxA[1024], fyA[1024], dirA[1024], tA[1024], gA[1024], wA[1024], hA[1024];
  int cnt = 0;
  if (nRand > 0)
  for (int fy = 0; fy < H && cnt < 1024; fy++)
    for (int fx = 0; fx < W && cnt < 1024; fx++)
    {
      int cf = (int)GetCellFlags(fx, fy);
      int room = (cf >> 5) & 7;
      // cella di osservazione: calpestabile (tutto tranne pietra e falso
      // muro chiuso); da qui la faccia del muro adiacente e' visibile
      if (room == roomSTONE) continue;
      if (room == roomFALSEWALL && (cf & 4) == 0) continue;
      for (int dir = 0; dir < 4 && cnt < 1024; dir++)
      {
        int wx = fx + DDX[dir], wy = fy + DDY[dir];
        if (wx < 0 || wx >= W || wy < 0 || wy >= H) continue;
        int wcf = (int)GetCellFlags(wx, wy);
        if (((wcf >> 5) & 7) != roomSTONE) continue;   // solo pietra vera
        if ((wcf & DMASK[dir]) == 0) continue;         // faccia senza decorazione
        int face = (DFACE[dir] + 2) & 3;               // normale della faccia (pos DM)
        // ornamento esplicito sulla stessa faccia? allora il 2D lo disegna
        // al posto della decorazione random: qui la faccia va saltata
        bool overridden = false;
        for (RN o = FindFirstObject(wx, wy); o != RNeof; o = GetDBRecordLink(o))
        {
          if (((int)o.pos() & 3) != face) continue;
          if (o.dbType() == dbTEXT)
          {
            DBCOMMON *a = GetCommonAddress(o);
            if (a != NULL && a->CastToDB2() != NULL && a->CastToDB2()->show())
              { overridden = true; break; }
          }
          else if (o.dbType() == dbACTUATOR)
          {
            DBCOMMON *a = GetCommonAddress(o);
            DB3 *p = (a != NULL) ? a->CastToDB3() : NULL;
            if (p == NULL) continue;
            if (p->actuatorType() == 127) { overridden = true; break; }
            if (p->actuatorType() != 47 && p->wallGraphicOrdinal() > 0)
              { overridden = true; break; }
          }
        }
        if (overridden) continue;
        int fc = DFACE[dir];
        int ord = (int)RandomWallDecoration((i16)(wcf & DMASK[dir]), (i16)nRand,
                                            wx, (wy + 1) * (((fc + 2) & 3) + 1), 30);
        if (ord <= 0) continue;
        int T  = (int)d.WallDecorationTOC[ord - 1];
        int gn = 121 + 2 * T + 1;                      // variante near (grande)
        int gw = 0, gh = 0;
        ui8 *ux = (ui8 *)d.ppUnExpandedGraphics[gn];
        if (ux != NULL)
        {
          gw = (int)(ui16)LE16(*(i16 *)ux);
          gh = (int)(ui16)LE16(*(i16 *)(ux + 2));
        }
        fxA[cnt] = fx; fyA[cnt] = fy; dirA[cnt] = dir;
        tA[cnt] = T; gA[cnt] = gn; wA[cnt] = gw; hA[cnt] = gh;
        cnt++;
      }
    }

  fprintf(f, "%d\n", cnt);
  for (int i = 0; i < cnt; i++)
    fprintf(f, "%d\n%d\n%d\n%d\n%d\n%d\n%d\n",
            fxA[i], fyA[i], dirA[i], tA[i], gA[i], wA[i], hA[i]);
}

// ---- dispositivi murali (statico, per livello) -----------------------------
// Attuatori CON GRAFICA sulla faccia di un muro di pietra: altare di Vi,
// serrature, manette, porta-torce, interruttori, nicchie... Il 2D li disegna
// al posto della decorazione random (stesso slot); qui li esponiamo con la
// stessa convenzione di WALLDEC/ritratti: fx,fy = cella calpestabile da cui
// si vede la faccia, dir = 0=O 1=E 2=N 3=S. Ritratti (127) e pedane (47)
// restano sui loro canali. Una faccia puo' avere PIU' dispositivi (es.
// porta-torcia + interruttore): li emettiamo tutti.
// Formato: count, poi 8 righe/dispositivo:
//   fx / fy / dir / actuatorType / tipoT / graphicNear / wPix / hPix
static void EmitDevicesText(FILE *f)
{
  int W = d.width, H = d.height;
  static const int DDX[4]   = { -1, 1, 0, 0 };   // cella -> muro, per dir
  static const int DDY[4]   = { 0, 0, -1, 1 };
  static const int DFACE[4] = { 3, 1, 0, 2 };    // party facing che vede la faccia

  int fxA[256], fyA[256], dirA[256], atA[256], tA[256], gA[256], wA[256], hA[256];
  int cnt = 0;
  for (int fy = 0; fy < H && cnt < 256; fy++)
    for (int fx = 0; fx < W && cnt < 256; fx++)
    {
      int cf = (int)GetCellFlags(fx, fy);
      int room = (cf >> 5) & 7;
      if (room == roomSTONE) continue;
      if (room == roomFALSEWALL && (cf & 4) == 0) continue;
      for (int dir = 0; dir < 4 && cnt < 256; dir++)
      {
        int wx = fx + DDX[dir], wy = fy + DDY[dir];
        if (wx < 0 || wx >= W || wy < 0 || wy >= H) continue;
        int wcf = (int)GetCellFlags(wx, wy);
        if (((wcf >> 5) & 7) != roomSTONE) continue;   // solo pietra vera
        int face = (DFACE[dir] + 2) & 3;               // normale della faccia (pos DM)
        for (RN o = FindFirstObject(wx, wy); o != RNeof; o = GetDBRecordLink(o))
        {
          if (((int)o.pos() & 3) != face) continue;
          if (o.dbType() != dbACTUATOR) continue;
          DBCOMMON *a = GetCommonAddress(o);
          DB3 *p = (a != NULL) ? a->CastToDB3() : NULL;
          if (p == NULL) continue;
          int at = (int)p->actuatorType();
          if (at == 127 || at == 47) continue;         // ritratti/pedane: altri canali
          int wgo = (int)p->wallGraphicOrdinal();
          if (wgo <= 0) continue;                      // attuatore invisibile
          int T  = (int)d.WallDecorationTOC[wgo - 1];
          int gn = 121 + 2 * T + 1;                    // variante near (grande)
          int gw = 0, gh = 0;
          ui8 *ux = (ui8 *)d.ppUnExpandedGraphics[gn];
          if (ux != NULL)
          {
            gw = (int)(ui16)LE16(*(i16 *)ux);
            gh = (int)(ui16)LE16(*(i16 *)(ux + 2));
          }
          fxA[cnt] = fx; fyA[cnt] = fy; dirA[cnt] = dir; atA[cnt] = at;
          tA[cnt] = T; gA[cnt] = gn; wA[cnt] = gw; hA[cnt] = gh;
          if (++cnt >= 256) break;
        }
      }
    }

  fprintf(f, "%d\n", cnt);
  for (int i = 0; i < cnt; i++)
    fprintf(f, "%d\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n",
            fxA[i], fyA[i], dirA[i], atA[i], tA[i], gA[i], wA[i], hA[i]);
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
// Le POSIZIONI (e il tipo) delle porte sono statiche per livello: solo lo
// stato di apertura cambia. La scansione completa W*H + FindFirstDoor viene
// quindi fatta UNA volta per livello (cache auto-invalidante); a ogni
// emissione si rilegge soltanto GetCellFlags delle celle-porta note.
static int   bridgeDoorLevel = -999;   // livello a cui si riferisce la cache
static int   bridgeDoorCount = 0;
static short bridgeDoorX[512], bridgeDoorY[512], bridgeDoorType[512];
static short bridgeDoorOrn[512];       // ornateness() 0..15 (decoro per-porta: grata, ecc.)

static void BuildDoorCache(void)
{
  int W = d.width, H = d.height;
  bridgeDoorCount = 0;
  for (int y = 0; y < H && bridgeDoorCount < 512; y++)
    for (int x = 0; x < W && bridgeDoorCount < 512; x++)
    {
      if (((GetCellFlags(x, y) >> 5) & 7) != roomDOOR) continue;
      int type = 0, orn = 0;
      RN dn = FindFirstDoor(x, y);
      if (dn != RNeof)
      {
        DB0 *door = GetRecordAddressDB0(dn);
        if (door != NULL) { type = door->doorType(); orn = door->ornateness(); }
      }
      bridgeDoorX[bridgeDoorCount]    = (short)x;
      bridgeDoorY[bridgeDoorCount]    = (short)y;
      bridgeDoorType[bridgeDoorCount] = (short)type;
      bridgeDoorOrn[bridgeDoorCount]  = (short)orn;
      bridgeDoorCount++;
    }
  bridgeDoorLevel = (int)d.LoadedLevel;
}

// DOORORN: statico per livello, un blocco (x, y, ornateness) per porta. Serve al
// viewer 3D per distinguere porte dello STESSO doorType ma decoro diverso (es.
// una liscia/piena e una a grata): l'aspetto reale in DM = base(doorType) +
// overlay(ornateness) (CSBCode.cpp:DrawDoor/TAG004b26). Lo STATE caldo resta a
// 4 campi (x,y,state,type); qui l'informazione statica in piu', letta una volta.
static void EmitDoorOrnText(FILE *ft)
{
  if (bridgeDoorLevel != (int)d.LoadedLevel) BuildDoorCache();
  fprintf(ft, "%d\n", bridgeDoorCount);
  for (int i = 0; i < bridgeDoorCount; i++)
    fprintf(ft, "%d\n%d\n%d\n",
            (int)bridgeDoorX[i], (int)bridgeDoorY[i], (int)bridgeDoorOrn[i]);
}

static void EmitDoorsText(FILE *ft)
{
  if (bridgeDoorLevel != (int)d.LoadedLevel) BuildDoorCache();

  fprintf(ft, "%d\n", bridgeDoorCount);
  for (int i = 0; i < bridgeDoorCount; i++)
    fprintf(ft, "%d\n%d\n%d\n%d\n",
            (int)bridgeDoorX[i], (int)bridgeDoorY[i],
            GetCellFlags(bridgeDoorX[i], bridgeDoorY[i]) & 7,
            (int)bridgeDoorType[i]);
}

// Variante plain-text dello stato, per Blitz3D (un valore per riga):
//   px / py / facing / level / numMonsters / (per mostro: typeId,x,y)
//   / numDoors / (per porta: x,y,state,type)
// Scrive su un FILE* qualsiasi: la usano sia dm_state.txt sia il server TCP.
static void EmitStateText(FILE *ft)
{
  int px = d.partyX, py = d.partyY;
  fprintf(ft, "%d\n%d\n%d\n%d\n", px, py, (int)d.partyFacing, (int)d.LoadedLevel);

  // mostri nel raggio: UN solo passaggio (raccoglie, poi stampa conteggio+lista)
  int x0 = px - BRIDGE_MON_RADIUS, x1 = px + BRIDGE_MON_RADIUS;
  int y0 = py - BRIDGE_MON_RADIUS, y1 = py + BRIDGE_MON_RADIUS;
  if (x0 < 0) x0 = 0;
  if (y0 < 0) y0 = 0;
  if (x1 >= d.width)  x1 = d.width - 1;
  if (y1 >= d.height) y1 = d.height - 1;
  short mtA[512], mxA[512], myA[512];
  int count = 0;
  for (int y = y0; y <= y1 && count < 512; y++)
    for (int x = x0; x <= x1 && count < 512; x++)
      for (RN o = FindFirstObject(x, y); o != RNeof; o = GetDBRecordLink(o))
      {
        if (o.dbType() != dbMONSTER) continue;
        DB4 *m = GetRecordAddressDB4(o);
        if (m == NULL || m->invisible()) continue;
        mtA[count] = (short)m->monsterType();
        mxA[count] = (short)x;
        myA[count] = (short)y;
        if (++count >= 512) break;
      }
  fprintf(ft, "%d\n", count);
  for (int i = 0; i < count; i++)
    fprintf(ft, "%d\n%d\n%d\n", (int)mtA[i], (int)mxA[i], (int)myA[i]);

  EmitDoorsText(ft);   // porte del livello (stato di apertura + tipo)

  // stato inventario/scheda campione (vedi ANALISI_SCHEDA_CAMPIONI.md):
  //   PotentialCharacterOrdinal   != 0 -> campione provvisorio (menu resurrect)
  //   SelectedCharacterOrdinal    != 0 -> inventario aperto per il campione N
  //   DisplayResurrectChestOrScroll 5=menu resurrect 4=chest 2=pergamena 0=cibo
  //   keyboardMode                  2 = name-entry di REINCARNATE in corso
  // Appesi in FONDO cosi' il parse posizionale esistente dei client non cambia.
  fprintf(ft, "%d\n%d\n%d\n%d\n",
          (int)d.PotentialCharacterOrdinal,
          (int)d.SelectedCharacterOrdinal,
          (int)d.DisplayResurrectChestOrScroll,
          (int)keyboardMode);

  EmitChampsText(ft);   // barra campioni per l'HUD (4x17 + 6 + 5 righe, in coda)
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
  int facing = d.partyFacing, px = d.partyX, py = d.partyY;

  // I file sono il canale LEGACY: con almeno un client SUB collegato lo stato
  // viaggia sul push TCP e riscrivere json+txt ~16 volte/s sarebbe solo I/O
  // sprecato (misurato: ~350 write-syscall/s a riposo). Senza sottoscrittori
  // il comportamento su file resta identico a prima.
  if (!BridgeSrv_HasSubscribers())
  {
    FILE *f = fopen("dm_world_state.json", "w");
    if (f != NULL)
    {
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
    }
    DumpStateText();           // variante .txt per i client su file
  }

  BridgeSrv_PushState();       // push ai client TCP sottoscritti (SUB)

  if (Bridge_Verbose())
  {
    printf("[bridge] tick=%d level=%d party=(%d,%d) facing=%s\n",
           (int)d.Time, (int)d.LoadedLevel, px, py, FacingName(facing));
    fflush(stdout);
  }
}

// ---- canale INVERSO 3D -> 2D: iniezione comandi -----------------------------
// Il comando di movimento entra nella coda mouse come AZIONE gia' risolta
// (campo .num = codice), esattamente come fa un click reale dopo l'hit-test
// (vedi OnMouseClick -> .num = D4W e HandleMouseEvents -> D6W = .num ->
// TurnParty/MoveParty). Niente coordinate ne' contesto RESTARTABLE: il dispatch
// normale del main loop applica il movimento col timing/cooldown corretti.
//   1=gira sx  2=gira dx  3=avanti  4=strafe dx  5=indietro  6=strafe sx
//   7..10 = toggle inventario campione 1..4 (ShowHideInventory; il motore
//           stesso li ignora se c'e' un provvisorio pendente o slot vuoto)
static void Bridge_InjectCommand(int code)
{
  if (code < 1 || code > 10) return;
  int q = d.MouseQEnd + 1;
  if (q > MOUSEQLEN - 1) q -= MOUSEQLEN;
  pMouseQueue[q].num = (ui16)code;
  pMouseQueue[q].x = 0;
  pMouseQueue[q].y = 0;
  d.MouseQEnd = (i16)q;
}

// ---- comando "MIRROR <ord>": aggiunta provvisoria del campione --------------
// Chiama DAVVERO AddCharacter (funzione normale, lecita da Bridge_Tick — vedi
// §1.5-bis di ANALISI_SCHEDA_CAMPIONI.md: NON e' una RESTARTABLE), esattamente
// come il click sullo specchio nel 2D (Mouse.cpp:513, actuatorType 127).
// AddCharacter controlla da solo mano vuota / party pieno / eroe morto; qui
// le guardie che nel 2D sono garantite dal contesto e che lui NON controlla.
static int Bridge_MirrorGuard(int ord)
{
  if (d.PotentialCharacterOrdinal != 0) return 0;  // provvisorio gia' pendente
  if (d.SelectedCharacterOrdinal != 0) return 0;   // inventario aperto
  if (keyboardMode == 2) return 0;                 // name-entry REINCARNATE
  if (d.EmptyHanded == 0) return 0;                // oggetto in mano
  if (d.NumCharacter >= 4) return 0;               // party pieno

  // davanti al party deve esserci l'attuatore-ritratto ATTIVO con quel valore,
  // con la faccia rivolta al party (pos+2 == facing, come il disable in
  // _ResurrectReincarnateCancel): AddCharacter equipaggia dalla cella davanti.
  int wx = d.partyX + (int)d.DeltaX[d.partyFacing];
  int wy = d.partyY + (int)d.DeltaY[d.partyFacing];
  if (wx < 0 || wx >= d.width || wy < 0 || wy >= d.height) return 0;
  for (RN o = FindFirstObject(wx, wy); o != RNeof; o = GetDBRecordLink(o))
  {
    if (o.dbType() != dbACTUATOR) continue;
    if (((o.pos() + 2) & 3) != (int)d.partyFacing) continue;
    DBCOMMON *a = GetCommonAddress(o);
    if (a == NULL) continue;
    DB3 *p = a->CastToDB3();
    if (p == NULL || p->actuatorType() != 127) continue;
    if ((p->State() & PORTRAIT_InActive) != 0) continue;
    if ((int)(p->value() & 511) != ord) continue;
    return 1;
  }
  return 0;
}

// ---- comando "RRC <160|161|162>": bottoni RESURRECT/REINCARNATE/CANCEL ------
// NON chiama _ResurrectReincarnateCancel (RESTARTABLE: una chiamata diretta
// corromperebbe lo stateStack). Inietta invece il click gia' risolto nella coda
// mouse col codice 81 (pannello inventario) e le coordinate del CENTRO del
// bottone da Buttons18372: il dispatch normale ripercorre l'identico cammino
// del 2D (_HandleMouseEvents -> _TAG01a6ea -> CALL1) con guardie e contesto
// corretti. Stessa filosofia di Bridge_InjectCommand per il movimento.
static int Bridge_InjectRRC(int code)
{
  if (code < 160 || code > 162) return 0;
  if (d.PotentialCharacterOrdinal == 0) return 0;      // nessun provvisorio
  if (d.DisplayResurrectChestOrScroll != 5) return 0;  // menu non a schermo
  if (d.EmptyHanded == 0) return 0;                    // guardia di _TAG01a6ea
  if (keyboardMode == 2) return 0;                     // name-entry in corso
  for (int i = 0; i < 4; i++)
  {
    const btn *b = &d.Buttons18372[i];
    if (b->word0 == 0) break;
    if ((int)b->word0 != code) continue;
    int q = d.MouseQEnd + 1;
    if (q > MOUSEQLEN - 1) q -= MOUSEQLEN;
    pMouseQueue[q].num = 81;
    pMouseQueue[q].x = (ui16)((b->xMin + b->xMax) / 2);
    pMouseQueue[q].y = (ui16)((b->yMin + b->yMax) / 2);
    d.MouseQEnd = (i16)q;
    return 1;
  }
  return 0;
}

// ---- comando "MORDER <a> <b>": riordino party (marching order) --------------
// Sposta il campione dalla cella-schermo a alla cella b del quadrato in alto a
// destra (celle 0..3 RELATIVE al facing, come le disegna il 2D). Replica il
// ramo "secondo click" di TAG0025a6 (CSBCode.cpp) SENZA passare dal cursore:
// il percorso UI vero (codici 125..128) tiene un drag aperto (Word23144) che
// il VBL annulla appena il mouse REALE sta fuori dall'area (CSBCode.cpp:948)
// -> inutilizzabile da remoto. Qui sono solo assegnazioni di campo +
// DrawCharacterState (funzioni normali, non RESTARTABLE), identiche al 2D.
static int Bridge_MarchOrder(int a, int b)
{
  if (a < 0 || a > 3 || b < 0 || b > 3 || a == b) return 0;
  if (d.Word23144 != 0) return 0;                  // drag reale in corso nel 2D
  if (d.PotentialCharacterOrdinal != 0) return 0;  // scelta campione in corso
  if (d.PartySleeping) return 0;
  i32 ca = CharacterAtPosition((d.partyFacing + a) & 3);
  if (ca == -1) return 0;                          // cella sorgente vuota
  i32 cb = CharacterAtPosition((d.partyFacing + b) & 3);
  d.CH16482[ca].facing = (i8)d.partyFacing;
  if (cb >= 0)
  {
    // cella occupata: l'altro campione prende il posto del primo (swap)
    d.CH16482[cb].charPosition = (ui8)((a + d.partyFacing) & 3);
    d.CH16482[cb].charFlags |= CHARFLAG_positionChanged;
    DrawCharacterState(cb);
  }
  else
  {
    // cella libera: pulisci il quadratino della cella sorgente (come il 2D)
    FillRectangle(d.LogicalScreenBase, &d.wRectPos1454[a], 0, 160);
  }
  d.CH16482[ca].charPosition = (ui8)((b + d.partyFacing) & 3);
  d.CH16482[ca].charFlags |= CHARFLAG_positionChanged;
  DrawCharacterState(ca);
  return 1;
}

// ---- comando "GRAB <slot 0..29>": drag-and-drop inventario -------------------
// Prende/posa l'oggetto nello slot come farebbe un click fisico sul backpack:
// inietta un click SINISTRO alla coordinata dello slot via OnMouseClick, che
// risolve da solo Buttons17760 (SecondaryButtonList dell'inventario aperto) e
// produce il codice giusto -> HandleClothingClick fa lo swap objectInHand<->slot
// con i controlli CarryLocation. Passando per la stessa via del mouse reale non
// serve conoscere il codice ne' lo stato di HandChar. Coordinate: IconDisplay e'
// relativa al viewport (0-based); il viewport e' a schermo (0, 33), icona 16x16
// -> centro (+8,+8). Solo a campione CONFERMATO (niente drag sui provvisori).
static int Bridge_InjectSlotClick(int slot)
{
  if (slot < 0 || slot > 29) return 0;
  if (d.SelectedCharacterOrdinal == 0) return 0;   // inventario chiuso
  if (d.PotentialCharacterOrdinal != 0) return 0;  // campione provvisorio
  if (keyboardMode == 2) return 0;                 // name-entry REINCARNATE
  if (d.PartySleeping) return 0;
  int px = (int)d.IconDisplay[slot + 8].pixelX + 8;
  int py = (int)d.IconDisplay[slot + 8].pixelY + 8 + 33;  // viewport a y=33
  OnMouseClick(px, py, 2);   // 2 = tasto sinistro (btn.button)
  return 1;
}

// ---- comando "SPELL <n>": barra magie (caster, rune, backspace, cast) -------
// n = 0..3   -> seleziona il caster n: click sintetico {num:100, x:512+n, y:42}
//               (HandleMagicClick accetta nativamente clickX 512..515 come
//               selezione-caster senza coordinate reali, Magic.cpp:601-685).
// n = 101..108 -> bottone della barra: 101-106 rune, 107 backspace, 108 cast.
//               Click iniettato sul CENTRO del rect di Buttons18060, codice
//               100: il dispatch 2D (_HandleMouseEvents -> _HandleMagicClick
//               -> _MagicSelection) fa il resto con guardie e mana. Stessa
//               filosofia di Bridge_InjectRRC.
static int Bridge_InjectSpell(int n)
{
  if (d.PotentialCharacterOrdinal != 0) return 0;  // guardia del codice 100
  if (d.MagicCaster < 0) return 0;                 // nessun caster selezionato
  if (keyboardMode == 2) return 0;                 // name-entry in corso
  int x = -1, y = -1;
  if (n >= 0 && n <= 3)
  {
    if (n >= (int)d.NumCharacter) return 0;
    x = 512 + n; y = 42;
  }
  else if (n >= 101 && n <= 108)
  {
    for (int i = 0; i < 9; i++)
    {
      const btn *b = &d.Buttons18060[i];
      if (b->word0 == 0) break;
      if ((int)b->word0 != n) continue;
      x = (b->xMin + b->xMax) / 2;
      y = (b->yMin + b->yMax) / 2;
      break;
    }
    if (x < 0) return 0;
  }
  else return 0;
  int q = d.MouseQEnd + 1;
  if (q > MOUSEQLEN - 1) q -= MOUSEQLEN;
  pMouseQueue[q].num = 100;
  pMouseQueue[q].x = (ui16)x;
  pMouseQueue[q].y = (ui16)y;
  d.MouseQEnd = (i16)q;
  return 1;
}

// ---- comando "ATTACK <n>": pannello attacco / menu azioni (HUD-3) ----------
// n = 116..119 -> pannello arma del campione n-116: apre il menu azioni
// n = 112      -> "pass" (banda del nome): chiude il menu senza attaccare
// n = 113..115 -> opzione 0..2 del menu (ExecuteAttack)
// Click sintetico codice 111 sul CENTRO del rect della button-list che
// TAG019fac consulta nello stato corrente (Buttons17952 a menu chiuso,
// Buttons17892 a menu aperto); guardie, cooldown e numero opzioni valide
// (Word20248) li applica il dispatch 2D. Stessa filosofia di InjectSpell.
static int Bridge_InjectAttack(int n)
{
  if (d.PotentialCharacterOrdinal != 0) return 0;  // guardia del codice 111
  if (keyboardMode == 2) return 0;                 // name-entry in corso
  const btn *list;
  if (n >= 116 && n <= 119)
  {
    if (d.AttackingCharacterOrdinal != 0 || d.Word20252 == 0) return 0;
    if (n - 116 >= (int)d.NumCharacter) return 0;
    list = d.Buttons17952;
  }
  else if (n >= 112 && n <= 115)
  {
    if (d.AttackingCharacterOrdinal == 0) return 0;
    list = d.Buttons17892;
  }
  else return 0;
  int x = -1, y = -1;
  for (int i = 0; i < 5; i++)
  {
    if (list[i].word0 == 0) break;
    if ((int)list[i].word0 != n) continue;
    x = (list[i].xMin + list[i].xMax) / 2;
    y = (list[i].yMin + list[i].yMax) / 2;
    break;
  }
  if (x < 0) return 0;
  int q = d.MouseQEnd + 1;
  if (q > MOUSEQLEN - 1) q -= MOUSEQLEN;
  pMouseQueue[q].num = 111;
  pMouseQueue[q].x = (ui16)x;
  pMouseQueue[q].y = (ui16)y;
  d.MouseQEnd = (i16)q;
  return 1;
}

// ---- risoluzione slot come la disegnerebbe il 2D ---------------------------
// Replica DisplayBackpackItem (Character.cpp:1100): slot vuoto -> sagoma
// segnaposto objNI_Special_*, chest/pergamena aperta in mano-arma -> +1,
// cornice 33 (normale) / 34 (ouch) / 35 (mano che attacca). chOrd1 = chIdx+1.
static void Bridge_ResolveSlot(CHARDESC *c, int chOrd1, int i,
                               int *realNI, int *drawNI, int *frame)
{
  RN obj = c->Possession(i);
  *realNI = -1; *frame = 0;
  if (obj == RNnul)
  {
    if (i <= 5)
    {
      *drawNI = (int)objNI_Special_n + 2 * i;
      if (c->ouches & (1 << i)) { (*drawNI)++; *frame = 34; }
      else *frame = 33;
    }
    else if (i >= 10 && i <= 13) *drawNI = (int)objNI_Special_j + (i - 10);
    else                         *drawNI = (int)objNI_Special_f;
  }
  else
  {
    *realNI = *drawNI = (int)obj.NameIndex();
    if (i == 1 && (*drawNI == (int)objNI_Chest || *drawNI == (int)objNI_OpenScroll))
      (*drawNI)++;
    if (i <= 5) *frame = (c->ouches & (1 << i)) ? 34 : 33;
  }
  if (i == 1 && chOrd1 == (int)d.AttackingCharacterOrdinal) *frame = 35;
}

// ---- ordinale del ritratto dal NOME (per l'HUD) -----------------------------
// Il CHARDESC non conserva QUALE cella dell'atlante 26 e' stata copiata
// (AddCharacter copia solo i pixel): l'ordinale si ricava confrontando il
// nome del campione con le schede dbTEXT dei 24 specchi (stessa scansione
// del comando CHAMP), una volta per livello.
static int  bridgePortLevel = -999;
static int  bridgePortCount = 0;
static char bridgePortName[24][8];
static int  bridgePortOrd[24];

static void BuildPortraitNameCache(void)
{
  bridgePortCount = 0;
  int W = d.width, H = d.height;
  for (int wy = 0; wy < H && bridgePortCount < 24; wy++)
    for (int wx = 0; wx < W && bridgePortCount < 24; wx++)
      for (RN o = FindFirstObject(wx, wy); o != RNeof; o = GetDBRecordLink(o))
      {
        if (o.dbType() != dbACTUATOR) continue;
        DBCOMMON *a = GetCommonAddress(o);
        if (a == NULL) continue;
        DB3 *p = a->CastToDB3();
        if (p == NULL || p->actuatorType() != 127) continue;

        int s  = o.pos() & 3;
        int fx = wx + (int)d.DeltaX[s], fy = wy + (int)d.DeltaY[s];
        if (fx < 0 || fx >= W || fy < 0 || fy >= H) continue;
        for (RN t = FindFirstObject(fx, fy); t != RNeof; t = GetDBRecordLink(t))
        {
          if (t.dbType() != dbTEXT) continue;
          char text[1024]; text[0] = 0;
          if (DecodeText(text, GetRecordAddressDB2(t), (i16)0x8002,
                         (i32)sizeof(text) - 1) == -1) break;
          int n = 0;
          while (text[n] != 0 && text[n] != '\n' && n < 7)
          { bridgePortName[bridgePortCount][n] = text[n]; n++; }
          if (n == 0) break;
          bridgePortName[bridgePortCount][n] = 0;
          bridgePortOrd[bridgePortCount] = (int)(p->value() & 511);
          bridgePortCount++;
          break;
        }
      }
  bridgePortLevel = (int)d.LoadedLevel;
}

static int PortraitOrdForName(const char *nm)
{
  if (bridgePortLevel != (int)d.LoadedLevel) BuildPortraitNameCache();
  for (int i = 0; i < bridgePortCount; i++)
    if (strncmp(bridgePortName[i], nm, 7) == 0) return bridgePortOrd[i];
  return -1;
}

// ---- comando "SHEET": scheda completa del campione selezionato --------------
// Serializza dal CHARDESC VIVO (il provvisorio creato da AddCharacter, o un
// campione confermato con l'inventario aperto). Le 30 possession sono gia'
// RISOLTE come le disegnerebbe il 2D (DisplayBackpackItem, Character.cpp:1100):
// slot vuoti -> sagoma segnaposto objNI_Special_*, chest/pergamena aperta in
// mano-arma -> variante +1, cornice 33/34/35. Righe (1 valore per riga):
//   nome / titolo / hp / maxHp / stamina / maxStamina / mana / maxMana /
//   load(dKg) / maxLoad(dKg) / food / water / poisonCount / ouches /
//   PotentialCharacterOrdinal, poi 30 slot x 3 righe: realNI(-1=vuoto),
//   drawNI (icona da disegnare), frame (0=nessuna, 33/34/35).
static void EmitSheetText(FILE *f)
{
  int sel = (int)d.SelectedCharacterOrdinal;
  if (sel < 1 || sel > 4) return;                  // guardato dal chiamante
  CHARDESC *c = &d.CH16482[sel - 1];

  // nome/titolo filtrati ad ASCII stampabile su una riga
  char nm[20], ti[24];
  int n = 0;
  for (int i = 0; i < (int)sizeof(c->name) && c->name[i] != 0 && n < 19; i++)
  { unsigned char ch = (unsigned char)c->name[i]; nm[n++] = (ch >= 0x20 && ch < 0x7f) ? (char)ch : '?'; }
  nm[n] = 0;
  n = 0;
  for (int i = 0; i < (int)sizeof(c->title) && c->title[i] != 0 && n < 23; i++)
  { unsigned char ch = (unsigned char)c->title[i]; ti[n++] = (ch >= 0x20 && ch < 0x7f) ? (char)ch : '?'; }
  ti[n] = 0;

  fprintf(f, "%s\n%s\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n",
          nm, ti,
          (int)c->HP(), (int)c->MaxHP(),
          (int)c->Stamina(), (int)c->MaxStamina(),
          (int)c->Mana(), (int)c->MaxMana(),
          (int)c->load, (int)MaxLoad(c),
          (int)c->food, (int)c->water,
          (int)c->poisonCount, (int)c->ouches,
          (int)d.PotentialCharacterOrdinal);

  for (int i = 0; i < 30; i++)
  {
    int realNI, drawNI, frame;
    Bridge_ResolveSlot(c, sel, i, &realNI, &drawNI, &frame);
    fprintf(f, "%d\n%d\n%d\n", realNI, drawNI, frame);
  }

  // pannello dell'occhio (PressingEye -> TAG019036, Character.cpp:4602):
  // 4 righe titolo-mastery ("NOVICE FIGHTER", "-" se la classe non ha
  // livello) + 6 attributi (STRENGTH..ANTI-FIRE, indici 1..6; lo 0 e'
  // LUCK, nascosto anche nel 2D) come coppie cur/max. In coda: il parse
  // posizionale esistente dei client non cambia.
  for (int cl = 0; cl < 4; cl++)
  {
    int m = (int)DetermineMastery(sel - 1, cl | 0x8000);
    if (m > 16) m = 16;
    if (m <= 1) { fprintf(f, "-\n"); continue; }
    fprintf(f, "%s %s\n",
            TranslateLanguage((char *)d.Pointer16770[m]),
            TranslateLanguage(d.Pointer16596[cl]));
  }
  for (int i = 1; i <= 6; i++)
    fprintf(f, "%d\n%d\n", (int)c->Attributes[i].Current(),
                           (int)c->Attributes[i].Maximum());
}

// ---- sezione CHAMPS del push (HUD: barra campioni) --------------------------
// 4 slot FISSI da 17 righe (parse posizionale semplice; slot inattivo = zeri):
//   active / nome / portraitOrd / hp / maxHp / stamina / maxStamina / mana /
//   maxMana / charPosition / facing / colore(Byte1386) / shieldStrength /
//   mano0 drawNI / mano0 frame / mano1 drawNI / mano1 frame
// + 6 righe globali: FireShield / SpellShield / PartyShield / MagicCaster /
//   HandChar / objectInHand NI. Vedi ANALISI_HUD_3D.md §3.
// + 5 righe stato spell del caster (barra magie, HUD-4): byte33 (stadio
//   0..3 = n. rune gia' scelte; -1 se nessun caster) e incantation[0..3]
//   (codici glifo del font DM 96..119, 0 = vuoto).
static void EmitChampsText(FILE *ft)
{
  for (int i = 0; i < 4; i++)
  {
    CHARDESC *c = &d.CH16482[i];
    bool active = (i < (int)d.NumCharacter) && c->MaxHP() > 0;
    if (!active)
    {
      fprintf(ft, "0\n-\n-1\n0\n0\n0\n0\n0\n0\n0\n0\n0\n0\n-1\n0\n-1\n0\n");
      continue;
    }
    char nm[8];
    int n = 0;
    for (int k = 0; k < (int)sizeof(c->name) && c->name[k] != 0 && n < 7; k++)
    { unsigned char ch = (unsigned char)c->name[k];
      nm[n++] = (ch >= 0x20 && ch < 0x7f) ? (char)ch : '?'; }
    nm[n] = 0;

    int h0r, h0d, h0f, h1r, h1d, h1f;
    Bridge_ResolveSlot(c, i + 1, 0, &h0r, &h0d, &h0f);
    Bridge_ResolveSlot(c, i + 1, 1, &h1r, &h1d, &h1f);

    fprintf(ft, "1\n%s\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n%d\n",
            nm, PortraitOrdForName(nm),
            (int)c->HP(), (int)c->MaxHP(),
            (int)c->Stamina(), (int)c->MaxStamina(),
            (int)c->Mana(), (int)c->MaxMana(),
            (int)c->charPosition, (int)c->facing, (int)d.Byte1386[i],
            (int)c->shieldStrength,
            h0d, h0f, h1d, h1f);
  }
  fprintf(ft, "%d\n%d\n%d\n%d\n%d\n%d\n",
          (int)d.FireShield, (int)d.SpellShield, (int)d.PartyShield,
          (int)d.MagicCaster, (int)d.HandChar,
          (d.objectInHand == RNnul) ? -1 : (int)d.objectInHand.NameIndex());
  int mc = (int)d.MagicCaster;
  if (mc >= 0 && mc < 4)
  {
    CHARDESC *cc = &d.CH16482[mc];
    fprintf(ft, "%d\n", (int)(UI8)cc->byte33);
    for (int k = 0; k < 4; k++)
      fprintf(ft, "%d\n", (int)(UI8)cc->incantation[k]);
  }
  else
    fprintf(ft, "-1\n0\n0\n0\n0\n");

  // blocco ATTACCO (HUD-3, append-only). Per slot, l'icona del pannello
  // 19x34 come la sceglierebbe DrawAttackButton (Character.cpp:4830):
  //   -2 = slot vuoto/morto (box nero), -1 = arma senza AttackClass
  //   (riquadro pieno), altrimenti il NameIndex da disegnare (pugno =
  //   objNI_Special_c a mano-arma vuota); + flag disabled (cooldown
  //   CHARFLAG_attackDisable, provvisorio pendente o party addormentato).
  // Poi lo stato del menu: Word20252 (pannelli visibili), ordinale
  // dell'attaccante (0 = menu chiuso), numero opzioni e 3 nomi azione
  // (GetAttackTypeName + ActionNameFilter, come DrawLegalAttackTypes;
  // "-" = riga vuota).
  for (int i = 0; i < 4; i++)
  {
    CHARDESC *c = &d.CH16482[i];
    bool active = (i < (int)d.NumCharacter) && c->MaxHP() > 0;
    int ni = -2, dis = 1;
    if (active && c->HP() > 0)
    {
      RN w = c->Possession(1);
      if (w == RNnul) ni = (int)objNI_Special_c;
      else if ((UI8)d.ObjDesc[w.DescIndex()].AttackClass() == 0) ni = -1;
      else ni = (int)w.NameIndex();
      dis = ((c->charFlags & CHARFLAG_attackDisable) ||
             d.PotentialCharacterOrdinal || d.PartySleeping) ? 1 : 0;
    }
    fprintf(ft, "%d\n%d\n", ni, dis);
  }
  int aord = (int)d.AttackingCharacterOrdinal;
  int nopt = 0;
  if (aord >= 1 && aord <= 4)
  {
    nopt = 3;
    if ((i8)d.PossibleAttackTypes[2] == -1) nopt = 2;
    if ((i8)d.PossibleAttackTypes[1] == -1) nopt = 1;
  }
  fprintf(ft, "%d\n%d\n%d\n", (int)d.Word20252, aord, nopt);
  for (int k = 0; k < 3; k++)
  {
    const char *anm = "-";
    if (aord >= 1 && k < nopt)
    {
      anm = GetAttackTypeName((i32)(UI8)d.PossibleAttackTypes[k]);
      anm = ActionNameFilter(anm, aord - 1, k, nopt);
      anm = TranslateLanguage(anm);
      if (anm == NULL || anm[0] == 0) anm = "-";
    }
    fprintf(ft, "%s\n", anm);
  }
}

// ---- anteprima campione (per il comando TCP "CHAMP <ord>") ----------------
// Replica IN SOLA LETTURA il parse di AddCharacter (Character.cpp:457): lo
// specchio e' l'attuatore murale type 127 con value==ord (0..23); la sua
// scheda e' un record dbTEXT "invisibile" (show=0, per questo non appare tra
// le targhe) sulla cella calpestabile DAVANTI allo specchio, decodificato con
// DecodeText modo 0x8002. Formato scheda:
//   NOME \n TITOLO \n \n M|F \n hp(4)stam(4)mana(4) \n 7 attributi (2 cifre)
//   \n 16 skill ('A'+livello) ...
// dove le "cifre" sono esadecimali con lettere 'A'..'P' (A=0 .. P=15), lo
// stesso schema di DecodeCharacterValue. Qui nessuno stato di gioco e' toccato.

static int ChampValue(const char **p, int num)
{
  int result = 0;
  while (num-- > 0)
  {
    char ch = **p;
    if (ch < 'A' || ch > 'P') return -1;
    result = (result << 4) + (ch - 'A');
    (*p)++;
  }
  return result;
}

// Emette su f le righe della scheda: nome / titolo / sesso / hp / stamina /
// mana / 7 attributi (Fortuna,Forza,Destrezza,Saggezza,Vitalita',AntiMagia,
// AntiFuoco) / 16 skill (livello 0..17). Ritorna 1 se la scheda e' valida.
static int Bridge_EmitChampFields(FILE *f, const char *t)
{
  char name[8], title[20];
  const char *p = t;

  int n = 0;
  while (*p != '\0' && *p != '\n')
  {
    if (n >= 7) return 0;
    name[n++] = *p++;
  }
  if (*p != '\n' || n == 0) return 0;
  name[n] = 0;
  p++;

  // titolo: termina alla PRIMA coppia di '\n' (stessa logica di AddCharacter)
  n = 0;
  int seenNL = 0;
  for (;;)
  {
    char ch = *p++;
    if (ch == '\0') return 0;
    if (ch == '\n') { if (seenNL) break; seenNL = 1; continue; }
    if (n >= 19) return 0;
    title[n++] = ch;
  }
  title[n] = 0;

  char sex = *p++;
  if (sex != 'M' && sex != 'F') return 0;
  if (*p++ != '\n') return 0;

  int hp = ChampValue(&p, 4);
  int st = ChampValue(&p, 4);
  int ma = ChampValue(&p, 4);
  if (hp < 0 || st < 0 || ma < 0) return 0;
  if (*p++ != '\n') return 0;

  int attr[7];
  for (int i = 0; i < 7; i++)
  {
    attr[i] = ChampValue(&p, 2);
    if (attr[i] < 0) return 0;
    if (attr[i] > 224) attr[i] = 224;        // stesso cap di AddCharacter
  }
  if (*p++ != '\n') return 0;

  int skill[16];
  for (int i = 0; i < 16; i++)
  {
    char ch = *p++;
    if (ch == '\0') return 0;
    int v = ch - 'A';
    if (v < 0 || v > 17) v = 0;
    skill[i] = v;
  }

  fprintf(f, "%s\n%s\n%c\n%d\n%d\n%d\n", name, title, sex, hp, st, ma);
  for (int i = 0; i < 7; i++)  fprintf(f, "%d\n", attr[i]);
  for (int i = 0; i < 16; i++) fprintf(f, "%d\n", skill[i]);
  return 1;
}

// Trova specchio + scheda del campione `ord` ed emette su f. 1 = trovata.
static int Bridge_EmitChamp(FILE *f, int ord)
{
  int W = d.width, H = d.height;
  for (int wy = 0; wy < H; wy++)
    for (int wx = 0; wx < W; wx++)
      for (RN o = FindFirstObject(wx, wy); o != RNeof; o = GetDBRecordLink(o))
      {
        if (o.dbType() != dbACTUATOR) continue;
        DBCOMMON *a = GetCommonAddress(o);
        if (a == NULL) continue;
        DB3 *p = a->CastToDB3();
        if (p == NULL || p->actuatorType() != 127) continue;
        if ((int)(p->value() & 511) != ord) continue;

        int s  = o.pos() & 3;
        int fx = wx + (int)d.DeltaX[s];
        int fy = wy + (int)d.DeltaY[s];
        if (fx < 0 || fx >= W || fy < 0 || fy >= H) return 0;

        // scheda = primo dbTEXT sulla cella davanti (come AddCharacter, che
        // la cerca sulla cella del party quando si clicca lo specchio)
        for (RN t = FindFirstObject(fx, fy); t != RNeof; t = GetDBRecordLink(t))
        {
          if (t.dbType() != dbTEXT) continue;
          char text[1024];
          text[0] = 0;
          if (DecodeText(text, GetRecordAddressDB2(t), (i16)0x8002,
                         (i32)sizeof(text) - 1) == -1) return 0;
          return Bridge_EmitChampFields(f, text);
        }
        return 0;
      }
  return 0;
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
//   FLOOR       ->  "OK <n>" + oggetti a terra vicini (6 righe/oggetto:
//                   x/y/pos/dbType/objNI/nome)
//   FLOORP      ->  come FLOOR ma header "FLOOR <n>" (refresh asincrono)
//   DECOR       ->  "OK <n>" + decorazioni di pavimento vicine (4 righe/cella:
//                   x/y/ordinale/graphicNumber)
//   PEDANE      ->  "OK <n>" + pedane (attuatori con grafica di pavimento):
//                   3 righe/pedana: x/y/ordinale
//   STAIRS      ->  "OK <n>" + scale del livello (4 righe/scala:
//                   x/y/up/ns — up: 0=discesa 1=salita; ns: 0=asse E-O 1=N-S)
//   DEVICES     ->  "OK <n>" + dispositivi murali con grafica (8 righe:
//                   fx/fy/dir/actuatorType/tipoT/graphicNear/wPix/hPix)
//   BTNGFX      ->  "OK 0"; ri-estrae le grafiche murali in buttons/ (121..255)
//   WALLDEC     ->  "OK <n>" + decorazioni murali random per-faccia (7 righe:
//                   fx/fy/dir/tipoT/graphicNear/wPix/hPix)
//   PORTRAITS   ->  "OK <n>" + n righe (formato dm_portraits_<N>.txt)
//   CHAMP <ord> ->  "OK 29" + scheda campione: nome/titolo/sesso/hp/stamina/
//                   mana/7 attributi/16 skill ("ERR nochamp" se non esiste)
//   CMD <1..6>  ->  "OK 0"   (inietta il movimento nella coda mouse)
//   MORDER a b  ->  "OK 0"   riordino party: campione dalla cella-schermo a
//                   alla cella b del marching-order (0..3 rel. al facing)
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

// Risposta al comando CHAMP <ord>: come QueueFramed ma con esito — se la
// scheda non esiste non si accoda nulla e si ritorna 0 (il chiamante fa ERR).
static int BridgeSrv_QueueChamp(BridgeClient *c, int ord)
{
  char  *buf = NULL;
  size_t len = 0;
  FILE  *m = open_memstream(&buf, &len);
  if (m == NULL) return 0;
  int ok = Bridge_EmitChamp(m, ord);
  fclose(m);
  if (!ok || len == 0) { free(buf); return 0; }

  int lines = 0;
  for (size_t i = 0; i < len; i++) if (buf[i] == '\n') lines++;
  char h[32];
  int hl = snprintf(h, sizeof(h), "OK %d\n", lines);
  BridgeSrv_Queue(c, h, (size_t)hl);
  BridgeSrv_Queue(c, buf, len);
  free(buf);
  return 1;
}

// ============================================================================
// SALVATAGGIO/CARICAMENTO IN JSON (comandi SAVE / SAVES / LOAD, vedi SaveJson.cpp)
//
// SAVE <nome>  - scrive saves/<nome>.json direttamente dalla memoria viva
//                (nessun menu: tutto il percorso dati e' codice piano).
// SAVES        - elenca i salvataggi presenti con i metadati.
// LOAD <nome>  - SOLO al titolo (livello non caricato): ricostruisce
//                CSBGAME.DAT dal JSON e guida il resume nativo iniettando
//                nella coda mouse 201 ("Resume") e poi 210 (primo file nel
//                menu "SELECT GAME FILE" — stessa lista bottoni 210-213 del
//                menu master). A partita in corso risponde ERR ingame:
//                usare prima QUIT e riavviare CSBwin.
// QUIT         - uscita pulita (UIM_TERMINATE), per orchestrare il reload.
// ============================================================================

// inietta un codice-voce di menu nella coda mouse (pattern del comando START)
static void Bridge_InjectMenuCode(int code)
{
  int q = d.MouseQEnd + 1;
  if (q > MOUSEQLEN - 1) q -= MOUSEQLEN;
  pMouseQueue[q].num = (ui16)code;
  pMouseQueue[q].x = 0;
  pMouseQueue[q].y = 0;
  d.MouseQEnd = (i16)q;
}

// nome salvataggio: solo [A-Za-z0-9_-], 1..64 caratteri
static bool Bridge_SaveNameOK(const char *s)
{
  int n = 0;
  for (; s[n]; n++)
  {
    char ch = s[n];
    if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
          (ch >= '0' && ch <= '9') || ch == '_' || ch == '-'))
      return false;
  }
  return n >= 1 && n <= 64;
}

// macchina a stati del LOAD (avanza in BridgeSrv_Tick, anche al titolo).
// ATTENZIONE: il menu master (QUIT/DUNGEON/UTILITY/HINT, codici 210-213) e il
// menu "SELECT GAME FILE" usano la STESSA lista bottoni [1][3]; si distinguono
// solo per l'ORDINE nella sequenza (il menu file appare dopo il Resume 201).
//   0 = inattivo
//   1 = attende il menu master ([1][3] attiva) -> inietta 211 (DUNGEON)
//   2 = attende che il menu master venga consumato ([1][3] non attiva)
//   3 = al titolo: ritenta 201 (Resume) ~1/s finche' [1][3] riappare
//       (stavolta e' il menu file) -> inietta 210 (prima voce = CSBGAME.DAT)
//   4 = attende bridgeLevelReady (partita caricata)
static int bridgeLoadStage = 0;
static int bridgeLoadTicks = 0;          // per il timeout (~100 tick/s)

static void Bridge_LoadTick(void)
{
  if (bridgeLoadStage == 0) return;
  if (++bridgeLoadTicks > 6000)          // ~60 s: qualcosa e' andato storto
  {
    printf("[bridge] LOAD: timeout allo stadio %d\n", bridgeLoadStage);
    fflush(stdout);
    bridgeLoadStage = 0;
    return;
  }
  bool fileMenu = (d.PrimaryButtonList == d.pButn18970[1][3]);
  switch (bridgeLoadStage)
  {
  case 1:
    if (fileMenu)                        // qui e' il menu master
    {
      printf("[bridge] LOAD: menu master, inietto 211 (DUNGEON)\n");
      fflush(stdout);
      Bridge_InjectMenuCode(211);
      bridgeLoadStage = 2;
    }
    break;
  case 2:
    if (!fileMenu)                       // menu master consumato
    {
      bridgeLoadStage = 3;
      bridgeLoadTicks = 0;
    }
    break;
  case 3:
    if (fileMenu)                        // riapparsa [1][3]: menu file
    {
      printf("[bridge] LOAD: menu file, inietto 210 (CSBGAME.DAT)\n");
      fflush(stdout);
      Bridge_InjectMenuCode(210);
      bridgeLoadStage = 4;
      bridgeLoadTicks = 0;
    }
    else if (bridgeLoadTicks % 100 == 0) // ~1/s: Resume al titolo
    {
      Bridge_InjectMenuCode(201);
    }
    break;
  case 4:
    if (bridgeLevelReady)
    {
      printf("[bridge] LOAD: livello caricato, resume completato\n");
      fflush(stdout);
      bridgeLoadStage = 0;
    }
    break;
  }
}

// SAVES: una riga per salvataggio: <nome>\t<data>\t<livello>\t<campioni>
// (metadati estratti dalla sezione "meta" in testa al JSON)
static void EmitSavesText(FILE *f)
{
  DIR *dir = opendir("saves");
  if (!dir) return;
  struct dirent *de;
  while ((de = readdir(dir)) != NULL)
  {
    const char *n = de->d_name;
    size_t len = strlen(n);
    if (len < 6 || strcmp(n + len - 5, ".json") != 0) continue;

    char path[512];
    snprintf(path, sizeof(path), "saves/%s", n);
    FILE *sf = fopen(path, "r");
    if (!sf) continue;
    char head[1024];
    size_t rd = fread(head, 1, sizeof(head) - 1, sf);
    fclose(sf);
    head[rd] = 0;

    // estrazione grezza dei campi meta (sono nei primi byte del file)
    char date[64] = "?";
    int lvl = -1;
    char champs[256] = "";
    char *p;
    if ((p = strstr(head, "\"date\": \"")) != NULL)
      sscanf(p + 9, "%63[^\"]", date);
    if ((p = strstr(head, "\"partyLevel\": ")) != NULL)
      lvl = atoi(p + 14);
    if ((p = strstr(head, "\"champions\": [")) != NULL)
    {
      char *e = strchr(p, ']');
      if (e && (size_t)(e - p - 14) < sizeof(champs))
      {
        memcpy(champs, p + 14, (size_t)(e - p - 14));
        champs[e - p - 14] = 0;
        // togli le virgolette per compattezza
        char *w = champs;
        for (char *r = champs; *r; r++) if (*r != '"') *w++ = *r;
        *w = 0;
      }
    }
    char base[256];
    snprintf(base, sizeof(base), "%.*s", (int)(len - 5), n);
    fprintf(f, "%s\t%s\t%d\t%s\n", base, date, lvl, champs);
  }
  closedir(dir);
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
  else if (strcmp(line, "DOORORN") == 0)
  {
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else BridgeSrv_QueueFramed(c, "OK", EmitDoorOrnText);
  }
  else if (strcmp(line, "FLOOR") == 0)
  {
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else BridgeSrv_QueueFramed(c, "OK", EmitFloorText);
  }
  else if (strcmp(line, "FLOORP") == 0)
  {
    // come FLOOR ma con header "FLOOR <n>": il client la riconosce anche in
    // mezzo ai PUSH (refresh asincrono degli oggetti a terra, schema PORTS)
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else BridgeSrv_QueueFramed(c, "FLOOR", EmitFloorText);
  }
  else if (strcmp(line, "DECOR") == 0)
  {
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else BridgeSrv_QueueFramed(c, "OK", EmitDecorText);
  }
  else if (strcmp(line, "DECORGFX") == 0)
  {
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else { Bridge_ExportDecorGfx(); BridgeSrv_Queue(c, "OK 0\n", 5); }
  }
  else if (strcmp(line, "PEDANE") == 0)
  {
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else BridgeSrv_QueueFramed(c, "OK", EmitPedaneText);
  }
  else if (strcmp(line, "STAIRS") == 0)
  {
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else BridgeSrv_QueueFramed(c, "OK", EmitStairsText);
  }
  else if (strcmp(line, "DEVICES") == 0)
  {
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else BridgeSrv_QueueFramed(c, "OK", EmitDevicesText);
  }
  else if (strcmp(line, "BTNGFX") == 0)
  {
    // ri-estrae le grafiche delle decorazioni murali (buttons/, 121..255)
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else { Bridge_ExportButtons(); BridgeSrv_Queue(c, "OK 0\n", 5); }
  }
  else if (strcmp(line, "WALLDEC") == 0)
  {
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else BridgeSrv_QueueFramed(c, "OK", EmitWallDecText);
  }
  else if (strcmp(line, "PORTRAITS") == 0)
  {
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else BridgeSrv_QueueFramed(c, "OK", EmitPortraitsText);
  }
  else if (strcmp(line, "PORTS") == 0)
  {
    // come PORTRAITS ma con header "PORTS <n>": il client la riconosce anche
    // in mezzo ai PUSH (refresh asincrono dei ritratti sbiaditi dopo un
    // reclutamento, stesso schema di SHEET)
    if (!bridgeLevelReady) BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else BridgeSrv_QueueFramed(c, "PORTS", EmitPortraitsText);
  }
  else if (strncmp(line, "CHAMP ", 6) == 0)
  {
    int ord = atoi(line + 6);
    if (!bridgeLevelReady || ord < 0 || ord > 23)
      BridgeSrv_Queue(c, "ERR badchamp\n", 13);
    else if (!BridgeSrv_QueueChamp(c, ord))
      BridgeSrv_Queue(c, "ERR nochamp\n", 12);
  }
  else if (strncmp(line, "CMD ", 4) == 0)
  {
    int code = atoi(line + 4);
    if (!bridgeLevelReady || code < 1 || code > 10)
      BridgeSrv_Queue(c, "ERR badcmd\n", 11);
    else
    {
      Bridge_InjectCommand(code);
      BridgeSrv_Queue(c, "OK 0\n", 5);
    }
  }
  else if (strncmp(line, "MIRROR ", 7) == 0)
  {
    // click sullo specchio dal 3D: aggiunta PROVVISORIA del campione al party
    // (il 2D passa alla schermata inventario; il 3D la apre dal push).
    int ord = atoi(line + 7);
    if (!bridgeLevelReady || ord < 0 || ord > 23)
      BridgeSrv_Queue(c, "ERR badmirror\n", 14);
    else if (!Bridge_MirrorGuard(ord))
      BridgeSrv_Queue(c, "ERR nomirror\n", 13);
    else
    {
      AddCharacter((i16)ord);
      // AddCharacter e' void: successo = il provvisorio esiste davvero
      if (d.PotentialCharacterOrdinal != 0) BridgeSrv_Queue(c, "OK 0\n", 5);
      else BridgeSrv_Queue(c, "ERR refused\n", 12);
    }
  }
  else if (strcmp(line, "SHEET") == 0)
  {
    // scheda completa del campione con l'inventario aperto (header "SHEET <n>"
    // e non "OK <n>": cosi' il client la riconosce anche in mezzo ai PUSH)
    if (!bridgeLevelReady || d.SelectedCharacterOrdinal < 1 ||
        d.SelectedCharacterOrdinal > 4)
      BridgeSrv_Queue(c, "ERR nosheet\n", 12);
    else
      BridgeSrv_QueueFramed(c, "SHEET", EmitSheetText);
  }
  else if (strncmp(line, "RRC ", 4) == 0)
  {
    // bottoni RESURRECT(160)/REINCARNATE(161)/CANCEL(162): click iniettato
    int code = atoi(line + 4);
    if (!bridgeLevelReady || !Bridge_InjectRRC(code))
      BridgeSrv_Queue(c, "ERR badrrc\n", 11);
    else
      BridgeSrv_Queue(c, "OK 0\n", 5);
  }
  else if (strncmp(line, "MORDER ", 7) == 0)
  {
    // riordino party: "MORDER <cella-da> <cella-a>" (celle schermo 0..3)
    int a = -1, b = -1;
    sscanf(line + 7, "%d %d", &a, &b);
    if (!bridgeLevelReady || !Bridge_MarchOrder(a, b))
      BridgeSrv_Queue(c, "ERR badmorder\n", 14);
    else
      BridgeSrv_Queue(c, "OK 0\n", 5);
  }
  else if (strncmp(line, "GRAB ", 5) == 0)
  {
    // drag-and-drop inventario: prendi/posa l'oggetto nello slot (0..29)
    int slot = atoi(line + 5);
    if (!bridgeLevelReady || !Bridge_InjectSlotClick(slot))
      BridgeSrv_Queue(c, "ERR badgrab\n", 12);
    else
      BridgeSrv_Queue(c, "OK 0\n", 5);
  }
  else if (strncmp(line, "SPELL ", 6) == 0)
  {
    // barra magie: 0-3 seleziona caster, 101-106 rune, 107 bksp, 108 cast
    int n = atoi(line + 6);
    if (!bridgeLevelReady || !Bridge_InjectSpell(n))
      BridgeSrv_Queue(c, "ERR badspell\n", 13);
    else
      BridgeSrv_Queue(c, "OK 0\n", 5);
  }
  else if (strncmp(line, "ATTACK ", 7) == 0)
  {
    // pannello attacco: 116-119 apre il menu, 112 pass, 113-115 opzioni
    int n = atoi(line + 7);
    if (!bridgeLevelReady || !Bridge_InjectAttack(n))
      BridgeSrv_Queue(c, "ERR badattack\n", 14);
    else
      BridgeSrv_Queue(c, "OK 0\n", 5);
  }
  else if (strncmp(line, "START", 5) == 0)
  {
    // avvio della partita da remoto (test/launcher 3D) senza cliccare la
    // finestra 2D. I menu di CSBwin leggono la STESSA coda mouse del
    // gioco, con voci a codice diretto:
    //   211 = "DUNGEON" nel menu master (WaitForMenuSelect, 210-213)
    //   200 = "Enter Prison", 201 = "Resume" nel titolo DM (Mouse.cpp:1776)
    // "START" = 211; "START <code>" per gli altri. Sequenza tipica:
    // START, attesa, poi START 200 finche' STATE non risponde. A livello
    // caricato il comando e' rifiutato (mai re-iniettare 200 in partita).
    int code = 211;
    if (line[5] == ' ') code = atoi(line + 6);
    if (bridgeLevelReady || (code != 200 && code != 201 && code != 211))
      BridgeSrv_Queue(c, "ERR badstart\n", 13);
    else
    {
      int q = d.MouseQEnd + 1;
      if (q > MOUSEQLEN - 1) q -= MOUSEQLEN;
      pMouseQueue[q].num = (i16)code;
      pMouseQueue[q].x = 0;
      pMouseQueue[q].y = 0;
      d.MouseQEnd = (i16)q;
      BridgeSrv_Queue(c, "OK 0\n", 5);
    }
  }
  else if (strncmp(line, "SAVE ", 5) == 0)
  {
    // salvataggio in JSON leggibile, direttamente dalla memoria viva
    const char *name = line + 5;
    while (*name == ' ') name++;
    if (!bridgeLevelReady)
      BridgeSrv_Queue(c, "ERR nolevel\n", 12);
    else if (!Bridge_SaveNameOK(name))
      BridgeSrv_Queue(c, "ERR badname\n", 12);
    else
    {
      mkdir("saves", 0755);
      char path[256];
      snprintf(path, sizeof(path), "saves/%s.json", name);
      if (SaveJson_SaveGame(path, name) == 0)
        BridgeSrv_Queue(c, "OK 0\n", 5);
      else
      {
        char msg[320];
        int ml = snprintf(msg, sizeof(msg), "ERR save %s\n", SaveJson_Error());
        BridgeSrv_Queue(c, msg, (size_t)ml);
      }
    }
  }
  else if (strcmp(line, "SAVES") == 0)
  {
    BridgeSrv_QueueFramed(c, "OK", EmitSavesText);
  }
  else if (strncmp(line, "LOAD ", 5) == 0)
  {
    // caricamento: solo al titolo (vedi commento in testa alla sezione)
    const char *name = line + 5;
    while (*name == ' ') name++;
    if (bridgeLevelReady)
      BridgeSrv_Queue(c, "ERR ingame\n", 11);
    else if (bridgeLoadStage != 0)
      BridgeSrv_Queue(c, "ERR loading\n", 12);
    else if (!Bridge_SaveNameOK(name))
      BridgeSrv_Queue(c, "ERR badname\n", 12);
    else
    {
      char path[256];
      snprintf(path, sizeof(path), "saves/%s.json", name);
      if (SaveJson_JsonToDat(path, "CSBGAME.DAT") != 0)
      {
        char msg[320];
        int ml = snprintf(msg, sizeof(msg), "ERR load %s\n", SaveJson_Error());
        BridgeSrv_Queue(c, msg, (size_t)ml);
      }
      else
      {
        bridgeLoadStage = 1;               // la sequenza parte dal menu master
        bridgeLoadTicks = 0;
        BridgeSrv_Queue(c, "OK 0\n", 5);   // avviato: il client fa poll su STATE
      }
    }
  }
  else if (strcmp(line, "QUIT") == 0)
  {
    BridgeSrv_Queue(c, "OK 0\n", 5);
    UI_PushMessage(UIM_TERMINATE);
  }
  else if (strcmp(line, "MENUCODES") == 0)
  {
    // debug: codici (word0) delle liste bottoni dei menu DoMenu
    char msg[512];
    int ml = 0;
    ml += snprintf(msg + ml, sizeof(msg) - (size_t)ml, "OK 2\n");
    for (int p2 = 0; p2 < 2; p2++)
    {
      ml += snprintf(msg + ml, sizeof(msg) - (size_t)ml, "P2=%d:", p2);
      for (int nb = 0; nb < 4; nb++)
      {
        ml += snprintf(msg + ml, sizeof(msg) - (size_t)ml, " [%d]=", nb + 1);
        for (int i = 0; i <= nb; i++)
          ml += snprintf(msg + ml, sizeof(msg) - (size_t)ml, "%d%s",
                         (int)d.pButn18970[p2][nb][i].word0, i < nb ? "," : "");
      }
      ml += snprintf(msg + ml, sizeof(msg) - (size_t)ml, "\n");
    }
    BridgeSrv_Queue(c, msg, (size_t)ml);
  }
  else if (strncmp(line, "INJECT ", 7) == 0)
  {
    // debug/test: inietta un codice-voce grezzo nella coda mouse
    Bridge_InjectMenuCode(atoi(line + 7));
    BridgeSrv_Queue(c, "OK 0\n", 5);
  }
  else if (strncmp(line, "CONVERT ", 8) == 0)
  {
    // debug/test: CONVERT <nome> <file.dat> — JsonToDat su file arbitrario
    char nm[128], out[128];
    if (sscanf(line + 8, "%127s %127s", nm, out) != 2 ||
        !Bridge_SaveNameOK(nm))
      BridgeSrv_Queue(c, "ERR badargs\n", 12);
    else
    {
      char path[256];
      snprintf(path, sizeof(path), "saves/%s.json", nm);
      if (SaveJson_JsonToDat(path, out) == 0)
        BridgeSrv_Queue(c, "OK 0\n", 5);
      else
      {
        char msg[320];
        int ml = snprintf(msg, sizeof(msg), "ERR convert %s\n", SaveJson_Error());
        BridgeSrv_Queue(c, msg, (size_t)ml);
      }
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

// C'e' almeno un client sottoscritto (SUB)? Decide anche se i dump su file
// legacy servono ancora (vedi DumpWorldState).
static bool BridgeSrv_HasSubscribers(void)
{
  for (int i = 0; i < BRIDGE_MAX_CLIENTS; i++)
    if (bridgeClients[i].fd >= 0 && bridgeClients[i].sub) return true;
  return false;
}

// Push dello stato ("PUSH <n>" + payload STATE) ai client sottoscritti.
// Il payload e' IDENTICO per tutti: lo si serializza UNA volta e lo si accoda
// a ogni client, invece di rieseguire EmitStateText (scansione mostri + porte)
// per ciascuno. Chiamato da DumpWorldState: movimento, cambio livello, periodico.
static void BridgeSrv_PushState(void)
{
  if (!BridgeSrv_HasSubscribers()) return;

  char  *buf = NULL;
  size_t len = 0;
  FILE  *m = open_memstream(&buf, &len);
  if (m == NULL) return;
  EmitStateText(m);
  fclose(m);

  int lines = 0;
  for (size_t i = 0; i < len; i++) if (buf[i] == '\n') lines++;
  char hdr[32];
  int hl = snprintf(hdr, sizeof(hdr), "PUSH %d\n", lines);

  for (int i = 0; i < BRIDGE_MAX_CLIENTS; i++)
  {
    BridgeClient *c = &bridgeClients[i];
    if (c->fd < 0 || !c->sub) continue;
    BridgeSrv_Queue(c, hdr, (size_t)hl);
    BridgeSrv_Queue(c, buf, len);
  }
  free(buf);
}

static void BridgeSrv_Tick(bool levelReady)
{
  bridgeLevelReady = levelReady;
  Bridge_LoadTick();               // avanza l'eventuale LOAD in corso
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
static bool BridgeSrv_HasSubscribers(void) { return false; }

#endif

// ---- hook dal game loop --------------------------------------------------
static void Bridge_TickBody(void)
{
  // Il server TCP gira SEMPRE (anche al titolo/menu: risponde "ERR nolevel"
  // finche' un livello reale non e' caricato). Il resto del ponte richiede
  // il livello: al titolo level=-1 e gli indici oggetti non sono pronti.
  bool levelReady = !(d.LoadedLevel < 0 ||
                      d.LevelCellFlags == NULL || d.width <= 0 || d.height <= 0 ||
                      d.pCurrentLevelObjectIndex == NULL);
  BridgeSrv_Tick(levelReady);
  if (!levelReady) return;

  // Canale inverso: SOLO via TCP ("CMD <n>" -> Bridge_InjectCommand). Il
  // vecchio polling di "dm_command.txt" costava una open() fallita a OGNI
  // tick (~100/s) ed e' stato rimosso: dm3d e' migrato al comando TCP.

  // Esporta i ritratti dei campioni una volta (qui graphics.dat e' caricato).
  // Ritenta finche' non ne salva almeno uno.
  static bool portraitsDone = false;
  if (!portraitsDone && Bridge_ExportPortraits() > 0) portraitsDone = true;

  // pulsanti di movimento (grafica 13): esporta una volta come BMP per dm3d
  static bool mvBtnDone = false;
  if (!mvBtnDone && Bridge_ExportMoveButtons() > 0) mvBtnDone = true;

  // grafiche della schermata inventario/scheda campione: esporta una volta
  static bool invGfxDone = false;
  if (!invGfxDone && Bridge_ExportInvGfx() > 0) invGfxDone = true;

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

  // ...e SUBITO quando cambia lo stato inventario/scheda (apertura/chiusura,
  // menu resurrect, name-entry): il pannello 3D deve reagire senza aspettare
  // il push periodico.
  static int prevPot = -1, prevSel = -1, prevDRC = -1, prevKbd = -1;
  static int prevHand = -12345;
  // objectInHand: cambia a ogni presa/posa del drag-and-drop; senza questo il
  // cursore-oggetto del 3D si aggiornerebbe solo col push periodico (~1s).
  int curHand = (d.objectInHand == RNnul) ? -1 : (int)d.objectInHand.ConvertToInteger();
  bool invChanged = ((int)d.PotentialCharacterOrdinal    != prevPot ||
                     (int)d.SelectedCharacterOrdinal     != prevSel ||
                     (int)d.DisplayResurrectChestOrScroll != prevDRC ||
                     (int)keyboardMode                    != prevKbd ||
                     curHand                              != prevHand);
  if (invChanged)
  {
    prevPot = (int)d.PotentialCharacterOrdinal;
    prevSel = (int)d.SelectedCharacterOrdinal;
    prevDRC = (int)d.DisplayResurrectChestOrScroll;
    prevKbd = (int)keyboardMode;
    prevHand = curHand;
  }

  // ...e SUBITO quando cambia lo stato della barra magie (caster selezionato,
  // runa aggiunta/tolta): senza questo la runa cliccata nel 3D comparirebbe
  // solo col push periodico (~1s dopo).
  static int prevCaster = -2, prevStage = -2;
  static int prevInc[4] = { -1, -1, -1, -1 };
  int curCaster = (int)d.MagicCaster;
  int curStage = -1, curInc[4] = { 0, 0, 0, 0 };
  if (curCaster >= 0 && curCaster < 4)
  {
    CHARDESC *sc = &d.CH16482[curCaster];
    curStage = (int)(UI8)sc->byte33;
    for (int k = 0; k < 4; k++) curInc[k] = (int)(UI8)sc->incantation[k];
  }
  bool spellChanged = (curCaster != prevCaster || curStage != prevStage ||
                       curInc[0] != prevInc[0] || curInc[1] != prevInc[1] ||
                       curInc[2] != prevInc[2] || curInc[3] != prevInc[3]);
  if (spellChanged)
  {
    prevCaster = curCaster; prevStage = curStage;
    for (int k = 0; k < 4; k++) prevInc[k] = curInc[k];
  }

  if (levelChanged || moved || periodic || invChanged || spellChanged)
  {
    prevX = d.partyX; prevY = d.partyY; prevFacing = d.partyFacing;
    lastDumpTime = (int)d.Time;
    DumpWorldState();
  }
}

// Il tick del ponte gira FUORI dal try/catch di CSBUI: se un accessorio del
// motore raggiunto da qui chiamasse UI_Die (che fa `throw` di un int),
// l'eccezione risalirebbe non catturata fino a std::terminate (SIGABRT).
// Il ponte e' un lettore accessorio e non deve MAI abbattere il gioco:
// qualsiasi eccezione viene assorbita qui (avviso una sola volta).
void Bridge_Tick(void)
{
  try
  {
    Bridge_TickBody();
  }
  catch (...)
  {
    static bool warned = false;
    if (!warned)
    {
      warned = true;
      printf("[bridge] eccezione dal motore assorbita nel tick del ponte\n");
      fflush(stdout);
    }
  }
}
