# Kotlin Minilog

Kotlin Minilog is a simple Kotlin-based logging framework that provides enhanced logging capabilities. The framework utilizes a structured approach to logging, enriching log messages with contextual information.

## Installation

Add the following dependency to your project:

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.wabbit-corp:kotlin-minilog:1.0.0")
}
```

## Usage

```kotlin
fun main() {
    // Create a LogManager instance
    val logManager = LogManager()

    // Obtain a logger with a specific name
    val logger = logManager.getLogger("MyAppLogger")

    // Define tags
    val infoTag = Log.Tag("INFO", 'I', java.util.logging.Level.INFO)
    val errorTag = Log.Tag("ERROR", 'E', java.util.logging.Level.SEVERE)

    // Log a message with context
    logger.log(infoTag) { ctx ->
        ctx.message("This is an info message")
        ctx.data("userId", 12345)
        ctx.data("operation", "update")
    }

    // Log a message with multiple tags and an exception
    logger.log("01FQF6YFNZB92KPCHJEXAMPLE", errorTag) { ctx ->
        ctx.message("An error occurred during processing")
        ctx.exception(RuntimeException("Example exception"))
    }
}
```

## Licensing

This project is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0) for open source use.

For commercial use, please contact Wabbit Consulting Corporation (at wabbit@wabbit.one) for licensing terms.

## Contributing

Before we can accept your contributions, we kindly ask you to agree to our Contributor License Agreement (CLA).
