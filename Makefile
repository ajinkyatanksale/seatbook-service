SHELL := /bin/bash
BASE_URL ?= http://localhost:8080
REQUESTS ?= 20000

.PHONY: burst deps

deps:
	@python3 -c "import aiohttp" 2>/dev/null || (echo "[*] Installing aiohttp..."; pip3 install --quiet aiohttp)

burst: deps
	@chmod +x burst\\burst_script.py
	python3 burst\\burst_script.py $(BASE_URL) $(REQUESTS)