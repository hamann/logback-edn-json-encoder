# Logback EDN-to-JSON Encoder

A custom Logback encoder for converting EDN-formatted logs to structured JSON, designed for seamless integration with Loki and Grafana.

## Overview

Logs in EDN format aren't easily parsed by log aggregation systems like Loki. This encoder converts EDN logs to structured JSON while preserving all semantic information, making your logs fully searchable and analyzable in Grafana.

### Features

- **EDN-to-JSON Conversion**: Automatically parses EDN-formatted log messages into structured JSON
- **MDC Support**: Extracts and parses EDN data from the Mapped Diagnostic Context
- **Preserves Original Data**: Option to keep original EDN for reference (configurable)

## Installation

### 1. Build the Encoder

```bash
# Clone the repository
git clone https://github.com/hamann/logback-edn-json-encoder.git
cd logback-edn-json-encoder

# Build the JAR
bb build

# The encoder JAR will be in target/edn-json-encoder-0.1.0.jar
```

### 2. Add to deps.end

```clojure
{:deps {com.github.hamann/edn-json-encoder {:mvn/version "0.1.0"}}}
```

### 3. Configure Logback

Create a `logback.xml` file in the config directory:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration debug="false">
    <!-- Console appender for local development -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="com.github.hamann.EdnToJsonEncoder">
            <!-- Optional: Set to true to preserve original EDN in output -->
            <preserveOriginals>false</preserveOriginals>
        </encoder>
    </appender>
    
    <!-- Root logger -->
    <root level="${ROOT_LOG_LEVEL:-WARN}">
        <appender-ref ref="${LOG_APPENDER:-CONSOLE}" />
    </root>
</configuration>
```

## JSON Output Format

The encoder generates JSON logs in this format:

```json
{
  "timestamp": "2023-12-25T10:30:45.123Z",
  "level": "INFO",
  "logger": "datomic.transaction",
  "thread": "datomic-tx-thread",
  "tx-id": 12345,
  "operation": "commit",
  "entities": ["user", "account", "preference"],
  "edn_parsed": true,
  "mdc": {
    "user-id": 789,
    "session-data": {
      "role": "admin",
      "team": "eng"
    }
  }
}
```

### Configuration Options

#### Preserve Original EDN

You can enable preservation of original EDN values in the logback.xml configuration:

```xml
<encoder class="com.github.hamann.EdnToJsonEncoder">
    <preserveOriginals>true</preserveOriginals>
</encoder>
```

When enabled, the encoder includes the original values in the output:

```json
{
  "_original_message": "{:tx-id 12345 :operation :commit :entities [:user :account :preference]}",
  "_original_mdc": {
    "user-id": "789",
    "session-data": "{:role :admin :team :eng}"
  }
}
```

## Loki/Grafana Queries

With this encoder, you can perform rich queries in Grafana:

```logql
# All errors for a specific transaction 
{app="datomic"} | json | level="ERROR" and tx_id=12345

# All transactions for a specific user
{app="datomic"} | json | mdc_user_id=789

# Errors with specific exception
{app="datomic"} | json | exception_class=~".*TransactionException.*"

# Find operations with more than X entities
{app="datomic"} | json | operation="commit" | length(entities) > 3
```

## Development

### Prerequisites

- [Babashka](https://github.com/babashka/babashka) for build tools
- JDK 11+
- [Clojure CLI tools](https://clojure.org/guides/getting_started)

### Build Commands

```bash
# Full build
bb build

# Just compile
bb compile

# Run tests
bb test

# Run linting
bb lint

# Development REPL
bb dev
```

## Nix Development Environment

A `flake.nix` is provided for reproducible development environments:

```bash
# Start a development shell
nix develop
```

## License

This project is licensed under the Eclipse Public License 2.0 - see the [LICENSE](LICENSE) file for details.

The EPL-2.0 is a copyleft license that:
- Allows commercial use and distribution
- Requires modifications to EPL-licensed files to be shared under the same license
- Includes patent protection for contributors
- Is compatible with GPL v2+ and business-friendly for larger applications

## Publishing to Maven

This project includes a `bb publish` task for publishing to Maven repositories (like Clojars). Before publishing:

### Prerequisites

1. **Set up Clojars account** (or your preferred Maven repository)
   - Create account at [clojars.org](https://clojars.org)
   - Generate a deploy token in your account settings

2. **Set up SOPS for credential management:**
   ```bash
   # Generate age key (recommended) or use existing GPG key
   age-keygen -o ~/.config/sops/age/keys.txt
   
   # Update .sops.yaml with your age public key
   # (The public key is printed when you generate it)
   ```

3. **Create encrypted secrets file:**
   ```bash
   # Create secrets file with your credentials
   cat > secrets.yaml << EOF
   clojars:
     username: your-clojars-username
     password: your-clojars-deploy-token
   EOF
   
   # Encrypt with SOPS (safe to commit)
   sops -e -i secrets.yaml
   ```

4. **Update project metadata** in `build.clj` if needed:
   - `group-id` and `artifact-id` (currently `com.github.hamann/edn-json-encoder`)
   - `version` (currently `0.1.0`)
   - `url` and SCM URLs (currently pointing to `github.com/hamann/edn-to-json-encoder`)
   - Developer information

### Publishing

```bash
# Build and publish to Maven repository
bb publish
```

The publish task will:
1. Build the project (`bb build`)
2. Decrypt credentials using SOPS
3. Generate proper pom.xml with metadata
4. Upload to the configured Maven repository using decrypted credentials

### Using the Published Library

Once published, users can add it to their `deps.edn`:

```clojure
{:deps {com.github.hamann/edn-json-encoder {:mvn/version "0.1.0"}}}
```

Or include the JAR in their Java classpath for Logback configuration.

## Contributing

Contributions welcome! Please feel free to submit a Pull Request.
