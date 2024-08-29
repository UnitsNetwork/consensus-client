package object units {
  type BlockHash = BlockHash.Type
  type Job[A]    = Either[ClientError, A]
}
