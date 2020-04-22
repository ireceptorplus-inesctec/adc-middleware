# ADC auth extensions middleware

Middleware server for handling UMA authorization and access control.

Project runs on java 11, with (modified) google java style guide.

## Instructions

### First time setup (dev):

- Configuring keycloak

You need to setup and configure a keycloak server.

Run:
```shell script
docker-compose --file docker-compose.dev.yml build
docker-compose --file docker-compose.dev.yml up keycloak
```

Then configure keycloak:

1. Go to http://localhost:8081. Login as admin with `admin:admin`. 
2. Go to master realm settings and enable `User-Managed Access`.
3. Create a new client in the Clients tab: load (import) and save the client from the file `./keycloak/adc-middleware.json`. Go to credentials tab in the client and note the generated `Secret` value which is the client secret while `adc-middleware` is the client ID.
4. In the `Users` tab create user with username `owner`, this is the resource owner. Create user with username `user` and `user2`, these are the users that will access resources. For each user in the user's `Credentials` tab create the password (equal to username).

You can use different values for these strings, but you would need to update the some variables and example code.

### To install, build and run for development:

```shell script
./gradlew bootRun
```

With arguments:

```shell script
./gradlew bootRun --args='--server.port=9999' # --server.port equivalent to java's -Dserver.port 
```

### Build deployable jar

The jar uses java 11

```shell script
./gradlew bootJar # jar will be placed in ./build/libs/ 
```

### Style checks

To run style checker run:

```shell script
./gradlew checkstyleMain
```

### Arguments

You can set these by either adding a custom properties file (using `--spring.config.location` to inject the file, see example below) or by passing them as CLI options (with `-D<property>=<value>`). In the properties files you can use the field names directly as displayed here, for the CLI prepend `-D`, for the gradle CLI prepend `--` (see above example).

Required:
- `adc.resourceServerUrl`: The url to the underlying resource server (ADC backend) including base path (example `http://localhost:80/airr/v1`).
- `uma.wellKnownUrl`: The url to the keycloak server's UMA well known document (example: `http://localhost:8082/auth/realms/master/.well-known/uma2-configuration`)
- `uma.clientId`: Client ID for this middleware in keycloak
- `uma.clientSecret`: Client Secret for the client ID
 
Optional:
- `server.servlet.context-path`: The base path of the middleware API, used to forward requests. Defaults to: `/airr/v1`
- `server.port`: The middleware server port, defaults to `8080`

Optional Dev:
- `spring.h2.console.enabled`: Will enable H2 web console on `http://localhost:8080/airr/v1/h2-console` (default). Defaults to false.

Running with custom properties file (using deployment jar):

```shell script
# ./config.properties is the custom file, in the current working directory
# MAKE sure to also include the MANDATORY default properties file 'classpath:/application.properties' 
java -jar ./build/libs/adc-auth-middleware-0.0.1-SNAPSHOT.jar \ 
--spring.config.location=classpath:/application.properties,./config.properties 
```