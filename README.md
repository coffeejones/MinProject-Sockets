# Mini Chat

Et chatprogram i Java 21 med TCP-sockets, en server og klienter til konsol og JavaFX. Projektet udvikles i små, testede trin efter [planen](PLAN.md).

## Åbn i IntelliJ

1. Åbn projektets `pom.xml` som et Maven-projekt.
2. Vælg Java 21 som Project SDK og som JDK for Maven Runner.
3. Lad IntelliJ indlæse Maven-afhængighederne.
4. Start kørselskonfigurationen **ChatServer** og derefter **ChatClient**.

Klientkonfigurationen tillader flere samtidige processer. Projektet bruger Maven-standardmapperne `src/main/java` og `src/test/java`.

## Udviklingsstatus

Første milepæl er afprøvet: én server og én konsolklient udveksler en UTF-8-linje over TCP. Den fulde protokol, flerbrugerchat og JavaFX tilføjes i de næste trin.
