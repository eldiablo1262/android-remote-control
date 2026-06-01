# Android Remote Control v2.0

Système complet de contrôle à distance Android via **WiFi / 4G / Internet**.  
**Aucun USB requis** — fonctionne même à l'autre bout du monde.

## Fonctionnalités

- **Streaming d'écran** : JPEG/H.264 avec adaptation dynamique de la qualité
- **Contrôle complet** : tap, swipe, scroll, pinch-to-zoom, touches système
- **Saisie de texte** à distance
- **Gestion d'applications** : lister, lancer, fermer des apps
- **Gestionnaire de fichiers** : naviguer, télécharger, uploader, supprimer
- **Adaptation réseau** : ajustement automatique FPS/qualité selon la bande passante

## Architecture

```
┌─────────────────┐     WiFi / 4G / Internet      ┌──────────────────┐
│  Android App    │ ═══ WebSocket (binary+JSON) ══►│  Node.js Server  │
│                 │◄═══════════════════════════════ │  (hébergé cloud) │
│  - MediaProjection (screen capture)              │                  │
│  - MediaCodec (H.264 encoding)                   │  - Relay frames  │
│  - AccessibilityService (control)                │  - Route commands │
│  - File operations                               │  - Keep-alive    │
└─────────────────┘                                └──────────────────┘
                                                           │
                                                    Socket.IO (viewers)
                                                           │
                                                           ▼
                                                    ┌──────────────┐
                                                    │  Viewer PC   │
                                                    │  (navigateur)│
                                                    │              │
                                                    │ - Écran live │
                                                    │ - Contrôle   │
                                                    │ - Fichiers   │
                                                    │ - Apps       │
                                                    └──────────────┘
```

## Installation & Utilisation

### 1. Serveur (PC ou Cloud)

```bash
npm install
npm start
```

Le serveur démarre sur `http://0.0.0.0:3000`.  
Déployez-le sur un VPS/Render/Railway pour un accès mondial.

### 2. Dashboard

Ouvrez `http://localhost:3000` (ou l'URL cloud).  
Créez une session → l'URL WebSocket s'affiche.

### 3. Android

Installez l'app companion Android :
1. Ouvrez l'app, collez l'URL WebSocket (`ws://votre-serveur:3000/ws/android/<session-id>`)
2. Accordez les permissions (Accessibilité + Capture d'écran)
3. Le streaming démarre automatiquement via WiFi ou 4G

### 4. Viewer (PC)

Ouvrez le lien Viewer depuis le dashboard.  
Vous verrez l'écran Android en direct et pourrez le contrôler.

### 5. Test (sans Android)

```bash
node android-client.js ws://localhost:3000 <SESSION_ID>
```

Ce client de test simule un appareil Android pour vérifier que tout fonctionne.

## Contrôles (Viewer)

| Action PC | Action Android |
|-----------|---------------|
| Clic | Tap |
| Glisser | Swipe |
| Molette | Scroll |
| Ctrl + / Ctrl - | Pinch zoom |
| Bouton ← | Retour |
| Bouton ○ | Accueil |
| Bouton □ | Apps récentes |
| Bouton ⏻ | Power |
| Bouton 🔊/🔉 | Volume +/- |
| Bouton 🔔 | Panneau notifications |
| Champ texte | Saisie de texte |

## Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /` | Dashboard - gestion des sessions |
| `GET /viewer/:id` | Viewer - affichage + contrôle complet |
| `GET /mobile/:id` | Page setup - affiche l'URL WebSocket |
| `WS /ws/android/:id` | WebSocket pour l'app Android |

## Protocole

Voir [PROTOCOL.md](./PROTOCOL.md) pour la spécification complète du protocole WebSocket.

## Notes techniques

- Streaming JPEG adaptatif (5-30 FPS, qualité 10-100%)
- Support H.264 via MediaCodec pour un streaming fluide
- Coordonnées normalisées (0.0-1.0) pour compatibilité multi-résolution
- Keep-alive ping/pong toutes les 15s (survie sur réseau mobile)
- Reconnexion automatique avec backoff exponentiel
- Transfert de fichiers en Base64 via WebSocket
- **Aucune dépendance USB** — tout passe par le réseau
