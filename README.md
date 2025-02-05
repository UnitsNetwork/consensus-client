# consensus-client

## 👨‍💻 Development

1. Run unit tests:
   ```bash
   sbt test
   ``` 
   
2. Run integration tests:
   1. Make sure your environment is properly [set up](https://java.testcontainers.org/supported_docker_environment/), esp. if you're running podman or colima.
   1. Build the Docker image:
      ```bash
      sbt docker
      ```
      Note: Build the Docker image whenever the consensus client code is updated, including after pulling from the repository.
   2. Run the integration tests:
      ```bash 
      sbt "consensus-client-it/test"
      ```
   3. See logs in `consensus-client-it/target/test-logs`.
