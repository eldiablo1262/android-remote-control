#!/bin/bash
# Installation script for Termux (Android)
# Run this ONCE in Termux to install dependencies

echo "=== Installation Remote Control (Termux) ==="

# Update packages
pkg update -y
pkg install -y nodejs python

# Install npm dependencies
npm install ws

echo ""
echo "=== Installation terminée ==="
echo "Lancez: node capture.js <IP_SERVEUR> <PORT> <SESSION_ID>"
echo "Exemple: node capture.js 192.168.1.100 3000 abc-123-def"
