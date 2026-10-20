#!/usr/bin/env python3
"""Validate actual `docker compose config --format json` output, without reading private env files."""
import json
import sys


def validate(model):
    mounts = model["services"]["web"].get("tmpfs")
    if mounts != ["/tmp:rw,nosuid,nodev,size=64m"]:
        raise ValueError("web.tmpfs must be ONE complete /tmp:rw,nosuid,nodev,size=64m mount")


if __name__ == "__main__":
    validate(json.load(sys.stdin))
    print("Compose web.tmpfs: one complete rw/nosuid/nodev/64m mount verified")
