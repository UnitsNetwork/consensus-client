import os

_INSIDE_DOCKER = None


def in_docker() -> bool:
    global _INSIDE_DOCKER
    if _INSIDE_DOCKER is None:
        try:
            os.stat("/.dockerenv")
            _INSIDE_DOCKER = True
        except FileNotFoundError:
            _INSIDE_DOCKER = False
    return _INSIDE_DOCKER
