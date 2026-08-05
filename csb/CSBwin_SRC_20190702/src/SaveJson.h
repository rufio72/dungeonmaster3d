// SaveJson.h - salvataggio/caricamento della partita in JSON leggibile.
//
// Usato dal bridge TCP (comandi SAVE/SAVES/LOAD di Bridge.cpp):
//   - SaveJson_SaveGame:  memoria viva -> file JSON con tutti i campi nominati
//                         (equivalente al contenuto di CSBGAMEx.DAT).
//   - SaveJson_JsonToDat: file JSON -> CSBGAMEx.DAT byte-esatto (checksum
//                         ricalcolati), che il motore nativo puo' ricaricare
//                         dal titolo con "Resume".
//
// Nessuna RESTARTABLE viene chiamata: si riusano solo le funzioni piane di
// SaveGame.cpp (GenChecksum, WriteScrambled, ScrambleAndWrite, ...).
#ifndef SAVEJSON_H
#define SAVEJSON_H

// Ritornano 0 se ok, -1 su errore (dettaglio in SaveJson_Error()).
int SaveJson_SaveGame(const char *jsonPath, const char *saveName);
int SaveJson_JsonToDat(const char *jsonPath, const char *datPath);
const char *SaveJson_Error(void);

#endif
