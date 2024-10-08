from hashlib import blake2b
from pymerkle import InmemoryTree as BaseMerkleTree
from typing import List


def blake2b_hash(data=None):
    if data:
        return blake2b(data, digest_size=32)
    else:
        return blake2b(digest_size=32)


# Custom hasher class with blake2b algorithm
class Blake2bHashMerkleTree(BaseMerkleTree):
    def __init__(self):
        super().__init__(algorithm="sha3_256", security=False)
        self.hashfunc = blake2b_hash
        self.prefx00 = b""
        self.prefx01 = b""


def get_merkle_proofs(
    hex_leaves: List[str], for_leaf_index: int, tree_size: int = 1024
) -> List[str]:
    tree = Blake2bHashMerkleTree()

    for leaf in hex_leaves:
        tree.append_entry(bytes.fromhex(leaf))

    empty_hashed_leaf = bytes([0])
    empty_leaves = tree_size - (tree.get_size() or 0)
    for _ in range(empty_leaves):
        tree.append_entry(empty_hashed_leaf)

    # A number is required instead of index
    proof = tree.prove_inclusion(for_leaf_index + 1)
    proof_path = proof.serialize()["path"]
    return proof_path[1:]
