package units.docker

import org.testcontainers.containers.GenericContainer as JGenericContrainer
import org.testcontainers.utility.DockerImageName

// Fixed autocompletion in IntelliJ IDEA
class GenericContainer(dockerImageName: DockerImageName) extends JGenericContrainer[GenericContainer](dockerImageName)
