# Logback EDN-to-JSON Encoder

A custom Logback encoder for converting EDN-formatted logs to structured JSON, designed for seamless integration with Loki and Grafana.

## Overview

Logs in EDN format aren't easily parsed by log aggregation systems like Loki. This encoder converts EDN logs to structured JSON while preserving all semantic information, making your logs fully searchable and analyzable in Grafana.

### Features

- **EDN-to-JSON Conversion**: Automatically parses EDN-formatted log messages into structured JSON
- **MDC Support**: Extracts and parses EDN data from the Mapped Diagnostic Context
- **Exception Handling**: Preserves exception information with stack traces
- **Time Format Standardization**: Converts all timestamp types to ISO-8601 format
- **Preserves Original Data**: Option to keep original EDN for reference (configurable)

## Installation

### 1. Build the Encoder

```bash
# Clone the repository
git clone https://github.com/hamann/logback-edn-json-encoder.git
cd logback-edn-json-encoder

# Build the JAR
bb build

# The encoder JAR will be in target/logback.edn-json-encoder-0.1.0.jar
```

### 2. Add to deps.end

TODO

### 3. Configure Logback

Create a `logback.xml` file in the config directory:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration debug="false">
    <!-- Console appender for local development -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="logback.EdnToJsonEncoder" />
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
  },
  "exception": {
    "exception_class": "datomic.TransactionException",
    "exception_message": "Transaction validation failed",
    "stack_trace": [
      "datomic.tx.Validator.validate(Validator.java:42)",
      "datomic.tx.Manager.commit(Manager.java:156)",
      "..."
    ],
    "cause": {
      "class": "java.lang.IllegalArgumentException", 
      "message": "Entity ID cannot be null"
    }
  }
}
```

When `-Dlogback.edn-json-encoder.preserve-originals=true` is set, it also includes:

```json
{
  "_original_edn": "{:tx-id 12345 :operation :commit :entities [:user :account :preference]}",
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

## Contributing

Contributions welcome! Please feel free to submit a Pull Request.
