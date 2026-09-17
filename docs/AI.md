# AI-dokumentation

| Opgave | AI-værktøj | Forslag | Teknisk vurdering og ændring | Kontrol |
| --- | --- | --- | --- | --- |
| Opdeling af konsol og JavaFX | Codex | Genbrug ChatConnection og ServerListener gennem callbacks. | Netværkskoden holdes fri for GUI-kontroller, så begge klienter benytter samme protokol. | Fælles klienttests og integration mellem klienttyper kontrolleres i testloggen. |
| Parsing af protokollen | Codex | Brug positive splitgrænser på 3 og 5. | Bevarer tomt slutfelt i QUIT og lodrette streger i payload. Format-metoderne validerer også records for at forhindre ekstra protokollinjer. | 99 parsertilfælde bestået med Java 21. |
| Beskyttelse af bruger- og rumdata | Codex | Saml registrene i ChatState under én lås. | Trådsikre enkeltoperationer alene gør ikke et samlet rumskifte atomisk. Modtagere kopieres under låsen; netværksarbejdet sker bagefter. | Samtidigheds- og rumtests kontrolleres i testloggen. |
| Nedlukning | Codex | Lad alle afbrydelser bruge samme oprydning. | Review præciserede, at socketlukning ikke må vente på en lås holdt af en blokeret skrivning, og at konsolinput ikke må holde processen i live. | EOF, serverstop og klientlukning kontrolleres i testloggen. |
| Hurtige kommandoer og svar | Codex | Første konsoludgave ventede kun på svar ved login og rumskifte. | Review fandt, at en fejl fra en tidligere privatbesked kunne frigive et senere rumskifte for tidligt. Egne kommandoer venter nu på deres relevante svar; fremmede beskeder vises stadig straks. | Regressionstest med fejlet privatbesked efterfulgt af hurtigt rumskifte bestået som separat konsolproces. |
