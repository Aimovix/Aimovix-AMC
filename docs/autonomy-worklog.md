# AMC Autonomie-Worklog

Protokoll der autonomen Arbeitssession vom 18.11.2026. Jeder Eintrag sagt: wann, was, warum, Testergebnis, offene Punkte.
Zeiten in UTC. Arbeitsbranch: `claude/new-session-z2yz2c`.

Reihenfolge laut Auftrag: Teil D (Fehlerprüfung) → Teil A + B (Unattended-Modus, Protokoll) → Teil C (lange Läufe) → Teil E (Funktionsvergleich) → Abschluss.

---

## 2026-11-18 22:05 – Bestandsaufnahme und Werkzeuge

**Was:** Repository gelesen (Engine, Sicherheitsfilter, Phone-Policy, Tools, Prompts, Worker, Room-Schicht, LlmClient, Bridge-Client, Python-Daemon, Telegram-Gateway, Teile der UI). Android-SDK (Plattform 35, Build-Tools 35) in der Session installiert, damit Gradle-Build und Unit-Tests hier wirklich laufen. Python-Abhängigkeit `websockets` installiert.

**Warum:** Ohne SDK hätte ich keinen Kotlin-Test ausführen können; der Auftrag verlangt, nichts als getestet zu melden, was nicht lief.

**Ergebnis Ausgangszustand:**
- Python-Bridge-Tests: 51 Tests, alle grün (ohne `websockets` scheitert `test_bridge_daemon` am Import – das ist eine fehlende Abhängigkeit der Umgebung, kein Code-Fehler).
- Shell-Syntax (`bash -n` für setup.sh, amc, local_model_manager.sh): ok.
- E2E-Suite `tests/e2e/runner.py --all`: 236 Tests, alle grün.
- Gradle `testDebugUnitTest assembleDebug`: läuft (erster Lauf lädt Abhängigkeiten).

**Offen:** Ergebnis des ersten Gradle-Laufs (folgt im nächsten Eintrag).

## 2026-11-18 22:30 – Ausgangszustand Gradle, Teil D: Befunde erfasst

**Was:** Erster vollständiger Gradle-Lauf (`testDebugUnitTest assembleDebug`) – 361 Unit-Tests grün, Debug-APK gebaut (6½ min inkl. Download). Danach die ganze App auf echte Fehler geprüft und elf Befunde (#29–#39) im Review-Format in `docs/AMC-Autonomie-Review.md` eingetragen.

**Wichtigste Funde in Alltagssprache:**
- #29: Bei Mehrfach-Bildschirmaktionen („screen_actions") wurde die vom Nutzer gepflegte Sperrliste ignoriert – freigegebene Apps waren mitten im Batch gesperrt.
- #30: Das geheime Bridge-Token konnte per Shell-Befehl gelesen oder überschrieben werden, ohne dass jemand gefragt wurde.
- #31/#32: Gemini meldete abgerissene Antworten als fertig, und der API-Schlüssel stand zusätzlich in der Adresszeile.
- #33: „Stopp" im Chat hat auch den Befehl im Terminal-Tab abgebrochen.
- #34–#39: kleinere Logik-, Bedien- und Barrierefreiheitsfehler (Regeln entfernen wirkt nicht, Tab springt beim Drehen zurück, zu kleine Tippflächen, http-Links scheitern, Audit-Tabelle wächst unbegrenzt, Telegram-Entkoppeln stoppt laufende Aufgaben nicht).

**Tests:** nur Doku geändert, kein Build nötig.

**Offen:** Behebung in Reihenfolge der Schwere (nächste Einträge).
