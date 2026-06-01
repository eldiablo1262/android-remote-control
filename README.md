# Android Remote Control

Application web pour contrôler votre Android à distance via WebRTC.

## Comment ça marche

1. Lancez le serveur : `npm start`
2. Ouvrez le dashboard sur votre PC : `http://localhost:3000`
3. Cliquez "Nouvelle Session" pour générer un lien
4. Envoyez le **lien mobile** sur votre Android (par SMS, WhatsApp, etc.)
5. Ouvrez le lien sur votre Android et cliquez "Partager mon écran"
6. Sur le PC, ouvrez le **lien Viewer** pour voir et contrôler l'écran

## Contrôles (dans le Viewer)

- **Clic** = Tap sur l'écran du téléphone
- **Glisser** = Swipe
- **Molette** = Scroll
- **Boutons latéraux** = Retour, Accueil, Apps récentes

## Important

- Le PC et l'Android doivent être sur le **même réseau WiFi**
- Utilisez l'adresse IP réseau (pas localhost) pour le lien mobile
- Android doit autoriser la capture d'écran quand demandé

## Lancer le serveur

```bash
npm start
```
