# consensus-client

## 👨‍💻 Development

1. Run unit tests:
   ```bash
   sbt test
   ``` 
   
2. Run integration tests:
   1. Build the Docker image:
      ```bash
      sbt docker
      ```
      Note: Build the Docker image whenever the consensus client code is updated, including after pulling from the repository.
   2. Run the integration tests:
      ```bash 
      sbt "consensus-client-it/test"
      ```
