#!/bin/sh
mkdir -p logs data/besu data/waves
mkdir -p logs/besu logs/waves
sudo chown 1000 logs data/besu data/waves logs/besu logs/waves
sudo chmod 777 logs/waves
