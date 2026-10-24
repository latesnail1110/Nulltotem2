# Totem of NULL – Ultimate Edition (Fabric, Mojang names, Java 25)

> **Status: Quellcode vollständig, aber NICHT gebaut und NICHT im Spiel getestet.**
> Es gibt deshalb **keine .jar** in diesem Paket. Siehe „Teststatus“.

## Bauen
```
# einmalig (Wrapper fehlt, weil kein Netzwerk beim Erstellen):
gradle wrapper --gradle-version 9.5.1
# gradle.properties prüfen (minecraft_version, loader_version, loom_version, fabric_api_version)
# -> exakte Werte auf https://fabricmc.net/develop
./gradlew build          # JAR: build/libs/nulltotem-ultimate-1.0.0.jar (Java 25 nötig)
```
Mod muss auf Server **und** Clients installiert sein (neues Item + Textur).
Item holen: `/give @s nulltotem:null_totem` (auch im Creative-Tab „Tools & Utilities“).
Falls deine Basis-Mod schon ein Totem-Item/`/null`-Command hat: nicht beide Mods gleichzeitig laden –
entweder diese als Basis nehmen oder `NullManager`, `NullConfig`, `Fx` usw. in deine Klassen übernehmen
(Item-ID und Command-Wurzel in `NullTotemMod` / `NullCommands` anpassen).

## Bedienung
* Erstes Rechtsklick-Aktivieren: **NULL Awakening** (Fähigkeit wird dabei nicht ausgelöst).
* Rechtsklick: gewählte Fähigkeit. **Sneak + Rechtsklick**: Fähigkeit wechseln (Actionbar zeigt Auswahl).
* Fähigkeiten brauchen das Totem **irgendwo im Inventar**; Rechtsklick natürlich in der Hand.

| Befehl | Wer | Wirkung |
|---|---|---|
| `/null ability <cannon\|rift\|collapse\|shield>` | Spieler mit Totem | gleiche Prüfungen wie Item (Totem, Enabled, Awakened, Cooldown, Spam-Sperre) |
| `/null cooldown reset <player>` | Gamemaster | Cooldowns zurücksetzen |
| `/null mode <player> <survival\|spectator>` | Gamemaster | Spielmodus; löscht ein offenes Death-Protocol-Flag |
| `/null effects <on\|off>` | jeder | persönliche Partikel an/aus (pro Betrachter gefiltert) |
| `/null status` | Spieler | Awakened, Totem-Zustand, Cooldowns, Shield |

`/Null` ist ebenfalls registriert (Brigadier ist case-sensitive). Admin-Befehle umgehen **keine** Spielerprüfungen:
`/null ability` läuft durch dieselbe `activate()`-Methode wie das Item.

## Death Protocol – Spectator (wichtig)
1. Stirbt ein Spieler und hatte das Totem im Inventar, wird `deathPending` **persistent** gespeichert (Weltordner, `nulltotem_state.json`).
2. Beim Respawn (oder beim nächsten Join, falls Server dazwischen abstürzte) wird **einmalig** auf Spectator gewechselt, Flag zuerst gelöscht (kein Doppel-Auslösen), Nachricht `§5[NULL] §bYour existence has been disconnected.` + dunkler Sound.
3. **Der Spieler bleibt Spectator**, auch nach erneutem Respawn/Neustart (Vanilla speichert den Spielmodus). Es gibt **keine automatische Wiederbelebung**.
4. Zurück in Survival: `/null mode <player> survival` oder `/gamemode survival <player>` (Gamemaster).
5. Abschalten: `spectatorOnDeath=false` in `config/nulltotem.json`.
Hinweis: Hatte der Spieler das Totem nicht mehr im Inventar beim Tod (z. B. Items weg), greift das Protokoll nicht – Prüfung erfolgt im Moment des Todes.

## Konfiguration (`config/nulltotem.json`, Ticks: 20 = 1 s)
Jede Fähigkeit: `enable*`, Cooldown, Reichweite; Cannon: `cannonChargeTicks/Skulls/Radius/Damage(pro Skull)`; Collapse: `collapseRadius/Damage/DurationTicks/PullStrength/SlownessAmplifier`;
Shield: `shieldHealthThreshold/DurationTicks/ResistanceAmplifier/AutoActivate(default false)`;
Schutz: `protectSelf`, `protectAllies` (Team/Tamed), `blockDamage` (default **false**);
Corruption: `corruptionEnabled/DurationTicks`; Feedback: `particleIntensity`, `soundIntensity`, `showBossbar`;
Limits: `maxActiveProjectiles` (12), `maxEffectsPerPlayer` (3), `useLockTicks` (10). Werte werden beim Laden geklammert (sichere Grenzen).

## Bewusste Abweichungen / Grenzen (ehrlich)
* **Bildschirmeffekt „blau-violett“:** rein serverseitig nur mit Vanilla-Effekten (Darkness + kurze Übelkeit, Partikelspirale). Ein echter blauer Vollbild-Tint bräuchte Client-Code (HUD-Overlay + Netzwerk-Payload) – **nicht implementiert**.
* **Item-Zustände Dormant/Awakened/Overloaded** hängen am **Spieler** (persistiert), nicht am ItemStack → nach Neustart nie inkonsistent; Anzeige über Partikel an der Hand, keine eigenen Item-Texturen pro Zustand.
* **Cooldowns sind nicht persistent** (Neustart = bereit). Awakened-Status, Auswahl, Effekt-Toggle und Death-Flag sind persistent.
* **Totem-Aktivierungsanimation:** Partikel/Sound am Spieler; keine eigene GUI-Item-Pop-Animation.
* Cannon-Skulls sind echte, getaggte Vanilla-`WitherSkull`-Entities; per Mixin wird ihr Einschlag durch einen kontrollierten ersetzt (Schaden mit Eigen-/Team-Schutz, Block-Schaden optional).
* Bossbar: eine pro Spieler (Map), nur für Totem-Träger, Entfernung bei Abbruch/Tod/Disconnect/Totem-Verlust.
* Corruption ist reine Partikel-Logik; es werden nie Blöcke verändert.

## Teststatus
| Bereich | Status |
|---|---|
| Gradle-Build / JAR | **nicht ausgeführt** (Sandbox ohne Netzwerk, kein JDK 25) |
| Kompilierung gegen MC 26.x | **ungeprüft** |
| Registrierung, Befehle, Item, Fähigkeiten, Tod/Spectator, Multiplayer | **ungetestet** |

Wahrscheinlichste Compile-Stellen (API-Namen in 26.x aus dem Gedächtnis, nicht verifiziert):
1. `Perm.java` – Permission-API (`Permissions.COMMANDS_GAMEMASTER`, `source.permissions()`).
2. Package von `WitherSkull` (`...projectile.hurtingprojectile.WitherSkull`), `setOwner/getOwner`, `setDangerous`.
3. `ServerPlayer.teleportTo(level,x,y,z,Set,yRot,xRot,boolean)`, `hurtServer`, `hurtMarked`, `getGameProfile().name()`.
4. `Identifier` (früher `ResourceLocation`), `Item.Properties#setId`, `ServerBossEvent`-Konstruktor.
5. Fabric-API-Event-Packages; Versionsnummern in `gradle.properties`.
Fehler sind jeweils lokal und mit Hilfe der IDE-Vorschläge in Minuten behebbar.

## Test-Checkliste (Dedicated Server + 2 Clients)
1. Start ohne Fehler, `config/nulltotem.json` entsteht. 2. `/give` → Item sichtbar mit Textur. 3. Erstes Rechtsklick = Awakening einmalig; 2. Client sieht Partikel; `/null effects off` blendet sie nur lokal aus.
4. Cannon: Kreis, Portal, 6 Skulls, kein Blockschaden, kein Selbstschaden; schnelles Klicken startet nur 1 Channel. 5. Rift: nicht in Lava/Wand; Cooldown 5 s. 6. Collapse: Mobs gezogen, verlangsamt, Explosion; Spieler im Team unberührt.
7. Shield: bei >8 HP abgelehnt, bei ≤8 HP aktiv, kein Stapeln. 8. Bossbar: nur Träger, keine Duplikate, weg bei Tod/Disconnect/Totem wegwerfen.
9. Tod mit Totem → Respawn als Spectator, Nachricht einmal; Server-Neustart davor/danach; `/null mode <p> survival`.
10. `/null cooldown reset` nur als OP; `/null status`.
