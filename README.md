# Android Remote Control

Système de contrôle à distance Android via capture d'écran (MediaProjection) et simulation de clics (AccessibilityService).

## Architecture

```
┌─────────────────┐      WebSocket (JPEG frames)      ┌──────────────────┐
│  Android App    │ ──────────────────────────────────►│  Node.js Server  │
│                 │◄─────────────────────────────────── │                  │
│  - MediaProjection    Touch commands (JSON)          │  - Reçoit frames │
│  - AccessibilityService                             │  - Relay Socket.IO│
│  - WebSocket Client                                  │  - Sert le viewer│
└─────────────────┘                                    └──────────────────┘
                                                              │
                                                     Socket.IO (base64 frames)
                                                              │
                                                              ▼
                                                       ┌──────────────┐
                                                       │   Viewer PC  │
                                                       │  (navigateur)│
                                                       └──────────────┘
```

## Composants

### 1. Serveur Node.js (`server.js`)
- **Express** : sert le dashboard et le viewer
- **Socket.IO** : communication avec le viewer (frames + events)
- **WebSocket natif (ws)** : communication avec l'app Android (frames binaires JPEG)

### 2. App Android (`android/`)
- **ScreenCaptureService** : Service foreground avec MediaProjection, capture l'écran en JPEG
- **RemoteAccessibilityService** : Service d'accessibilité qui simule tap/swipe/scroll/navigation
- **WebSocketClient** : Envoie les frames JPEG et reçoit les commandes tactiles

### 3. Viewer Web (`public/viewer.html`)
- Affiche l'écran en temps réel (JPEG over Socket.IO)
- Capture les interactions souris → envoi au serveur → relay vers Android

## Installation & Utilisation

### Côté PC (serveur)

```bash
npm install
npm start
```

Le serveur démarre sur `http://0.0.0.0:3000`.

### Côté Android

1. Ouvrir le projet `android/` dans Android Studio
2. Build & installer l'APK sur le téléphone
3. Activer le service d'accessibilité : Paramètres → Accessibilité → RemoteControl → Activer
4. Dans l'app :
   - Entrer l'IP du PC (ex: `192.168.1.100`)
   - Port : `3000`
   - Créer une session sur le dashboard PC, copier le Session ID
   - Coller le Session ID → Démarrer la capture

### Workflow complet

1. `npm start` sur le PC
2. Ouvrir `http://localhost:3000` → créer une session
3. Sur Android : entrer IP + port + Session ID → Démarrer
4. Ouvrir le **Viewer** sur le PC → l'écran Android s'affiche
5. Cliquer/glisser sur le viewer → actions simulées sur Android

## Contrôles (Viewer)

- **Clic** = Tap
- **Glisser** = Swipe
- **Molette** = Scroll
- **Boutons** = Retour, Accueil, Apps récentes

## Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /` | Dashboard - gestion des sessions |
| `GET /viewer/:id` | Viewer - affichage + contrôle |
| `WS /ws/android/:id` | WebSocket natif Android |

## Notes techniques

- Frames JPEG qualité 50% à ~15 FPS
- Résolution réduite à 720px de large
- Coordonnées normalisées (0-1) converties en pixels par l'AccessibilityService
- Reconnexion WebSocket automatique
- PC et Android doivent être sur le **même réseau**
