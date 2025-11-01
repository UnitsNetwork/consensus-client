package units

package object test {
  extension (bh: BlockHash) {
    def take(n: Int): String = bh.str.take(n)
  }
}
