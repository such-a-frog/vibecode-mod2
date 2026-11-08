# Chest Gacha (Fabric, Minecraft 26.2)

Spielt vor dem Öffnen einer Kiste oder eines Fasses eine Gacha-Animation ab:
Walze dreht sich → Blitz in der Seltenheitsfarbe → großes Item mit Glow und Konfetti → Kiste öffnet sich.

## Bauen
Benötigt **Java 25**. Der Gradle-Wrapper (9.8.2) lädt alles Weitere selbst.

    ./gradlew runClient    # Minecraft zum Testen starten
    ./gradlew build        # fertige Mod: build/libs/chestgacha-1.0.0.jar

Zum Spielen: Fabric Loader (>= 0.19.3) und Fabric API (0.161.0+26.2) installieren.
Die Mod muss auf **Client und Server** liegen (in Singleplayer automatisch).

## Bedienung
- Rechtsklick auf Kiste/Fass: Animation, danach öffnet sich die Kiste.
- Klick / Leertaste / Enter: Walze überspringen, danach Animation beenden. ESC: sofort beenden.
- Schleichen (Shift) + Rechtsklick: Kiste ohne Animation öffnen.
- Leere Kisten haben keine Animation.

## Einstellungen (config/chestgacha.properties)
- `onlyLootChests=true`: Animation nur beim ersten Öffnen von Loot-Kisten (Dungeons usw.)

## Anpassen
- Welches Item welche Stufe (1-5 Sterne) bekommt: `GachaTier.java` (Klasse `Overrides`)
- Dauer, Farben, Effekte: `src/client/.../GachaRevealScreen.java` (Konstanten oben)

Die Lizenz des Projekts (LICENSE) ist die CC0 des Fabric-Templates, ändere sie nach Wunsch.

## Jar ohne lokale Installation (GitHub)
1. Neues Repository auf github.com anlegen und den Inhalt dieses Ordners hochladen (inkl. `.github`).
2. Reiter **Actions** → Workflow **build** → **Run workflow** (läuft auch automatisch bei jedem Push).
3. Nach ca. 3 Minuten den Run öffnen und unter **Artifacts** `chestgacha-jar` herunterladen.
   Darin liegt `chestgacha-1.0.0.jar` (die Datei mit `-sources` brauchst du nicht).
