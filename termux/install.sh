#!/bin/bash
# Installation script for Termux (Android)
# Run this ONCE in Termux to install dependencies
# Connects over WiFi/4G — NO USB required

echo "=== Installation Remote Control (Termux) ==="
echo "Mode: WiFi / 4G / Internet (NO USB)"
echo ""

# Update packages
pkg update -y
pkg install -y nodejs imagemagick

# Install npm dependencies
npm install ws

echo ""
echo "=== Installation terminée ==="
echo ""
echo "Usage: node capture.js <SERVER_URL> <SESSION_ID>"
echo ""
echo "Exemples:"
echo "  node capture.js ws://192.168.1.100:3000 my-session-id"
echo "  node capture.js ws://mon-serveur.render.com my-session-id"
echo "  node capture.js wss://remote.example.com my-session-id"
echo ""
echo "Le téléphone se connecte au serveur via WiFi ou 4G."
echo "Aucun cable USB nécessaire."
