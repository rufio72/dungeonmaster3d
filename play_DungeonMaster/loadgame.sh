#!/bin/sh
# Ciclo di CARICAMENTO partita, lanciato dal pannello CARICA di dm3d
# (via ExecFile, processo staccato). Il LOAD del bridge funziona SOLO al
# menu master (CSBwin appena avviato), quindi il giro completo e':
#   dm3d si chiude da solo -> QUIT a CSBwin -> riavvio CSBwin ->
#   LOAD <nome> (con retry) -> attesa livello in gioco -> riavvio dm3d.
# Uso: loadgame.sh <nome-salvataggio>     Log: loadgame.log
cd "$(dirname "$0")" || exit 1
NAME="$1"
[ -n "$NAME" ] || exit 1

{
echo "== loadcycle '$NAME' $(date '+%H:%M:%S') =="

# 1. chiudi CSBwin con il QUIT pulito del bridge (UIM_TERMINATE)
printf 'QUIT\n' | nc -w 2 127.0.0.1 5417 >/dev/null 2>&1 || true
i=0
while [ $i -lt 50 ] && nc -z -w 1 127.0.0.1 5417 2>/dev/null; do
  sleep 0.2; i=$((i+1))
done
pkill -x CSBwin 2>/dev/null || true
sleep 0.5

# 2. riavvia CSBwin (al menu master: e' li' che il LOAD e' permesso)
./CSBwin >csbwin.log 2>&1 &
i=0
while [ $i -lt 100 ] && ! nc -z -w 1 127.0.0.1 5417 2>/dev/null; do
  sleep 0.2; i=$((i+1))
done

# 3. LOAD con retry (appena avviato puo' rispondere ERR per un attimo)
i=0
while [ $i -lt 20 ]; do
  R=$(printf 'LOAD %s\n' "$NAME" | nc -w 2 127.0.0.1 5417 | head -1)
  echo "LOAD -> ${R:-nessuna risposta}"
  case "$R" in OK*) break;; esac
  sleep 0.5; i=$((i+1))
done

# 4. attesa: la macchina a stati del bridge guida i menu; quando STATE
#    risponde OK siamo in gioco col salvataggio caricato
i=0
while [ $i -lt 150 ]; do
  R=$(printf 'STATE\n' | nc -w 2 127.0.0.1 5417 | head -1)
  case "$R" in OK*) echo "in gioco"; break;; esac
  sleep 0.4; i=$((i+1))
done

# 5. rilancia il viewer 3D (ricostruisce il mondo dal livello caricato)
./dm3d >dm3d.log 2>&1 &
echo "== fine loadcycle =="
} >>loadgame.log 2>&1
