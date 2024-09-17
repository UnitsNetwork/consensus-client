package object units {
  type BlockHash    = BlockHash.Type
  type JobResult[A] = Either[ClientError, A]
}
