.PHONY: sync help install deps install-deps

help:
	@echo "targets: install (publish skills) | sync (publish skills) | deps (check tools) | install-deps"

install:
	bash tools/sync.sh
	@git update-index --skip-worktree notebook/user.md 2>/dev/null || true

sync:
	bash tools/sync.sh

deps:
	bash tools/deps.sh

install-deps:
	bash tools/deps.sh install
