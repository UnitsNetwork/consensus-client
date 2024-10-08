class TimeExhausted(Exception):
    def __init__(self, message: str):
        super().__init__(message)


class BlockNotFound(Exception):
    def __init__(self, block_hash: str):
        super().__init__(f"Block {block_hash} not found on contract")


class BlockDisappeared(Exception):
    def __init__(self, block_hash: str):
        super().__init__(f"Block {block_hash} disappeared from execution client")
