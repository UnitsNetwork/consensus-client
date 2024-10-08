import sys
from logging import Logger
from time import sleep

from pywaves import pw


def force_success(log: Logger, r, text, wait=True, pw=pw):
    if not r or "error" in r:
        log.error(f"{text}: {r}")
        sys.exit(1)

    if wait:
        id = r["id"]
        wait_for(id, pw)
        log.info(f"{id} confirmed")


def wait_for(id, pw=pw):
    while True:
        tx = pw.tx(id)
        if "id" in tx:
            return tx
        sleep(2)
