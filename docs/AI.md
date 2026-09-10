# AI-dokumentation

Dette er en teknisk kladde udarbejdet under udviklingen med Codex. Den beskriver forslag og kontroller, der kan efterprøves i koden og testene. Din personlige vurdering skal tilføjes efter din egen gennemgang; teksten hævder ikke, at du allerede har læst og godkendt alle ændringer.

| Opgave | AI-værktøj | Forslag | Teknisk vurdering og ændring | Kontrol |
| --- | --- | --- | --- | --- |
| Opdeling af konsol og JavaFX | Codex | Genbrug ChatConnection og ServerListener gennem callbacks. | Netværkskoden holdes fri for GUI-kontroller, så begge klienter benytter samme protokol. | Fælles klienttests og integration mellem klienttyper kontrolleres i testloggen. |
| Parsing af protokollen | Codex | Brug positive splitgrænser på 3 og 5. | Bevarer tomt slutfelt i QUIT og lodrette streger i payload. Format-metoderne validerer også records for at forhindre ekstra protokollinjer. | 99 parsertilfælde bestået med Java 21. |
| Beskyttelse af bruger- og rumdata | Codex | Saml registrene i ChatState under én lås. | Trådsikre enkeltoperationer alene gør ikke et samlet rumskifte atomisk. Modtagere kopieres under låsen; netværksarbejdet sker bagefter. | Samtidigheds- og rumtests kontrolleres i testloggen. |
| Nedlukning | Codex | Lad alle afbrydelser bruge samme oprydning. | Review præciserede, at socketlukning ikke må vente på en lås holdt af en blokeret skrivning, og at konsolinput ikke må holde processen i live. | EOF, serverstop og klientlukning kontrolleres i testloggen. |
| Hurtige kommandoer og svar | Codex | Første konsoludgave ventede kun på svar ved login og rumskifte. | Review fandt, at en fejl fra en tidligere privatbesked kunne frigive et senere rumskifte for tidligt. Egne kommandoer venter nu på deres relevante svar; fremmede beskeder vises stadig straks. | Regressionstest med fejlet privatbesked efterfulgt af hurtigt rumskifte bestået som separat konsolproces. |

Et muligt præsentationseksempel er forskellen mellem en trådsikker enkeltoperation og det samlede rumskifte: vis ChatState-metoden, forklar hvilke data der ændres under samme lås, og kør den relevante test. Brug først eksemplet, når du selv kan forklare det.

## Egen gennemgang

Udfyld efter at have læst, kørt og eventuelt ændret løsningen:

- Hvilket forslag valgte jeg at beholde, ændre eller afvise, og hvorfor?
- Hvilken konkret kode og test viser min kontrol?
- Hvad ville gå galt uden den valgte løsning?
