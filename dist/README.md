# Built plugin jar

`jmeter-bzm-correlation-recorder-3.1.1-har.1.jar` is the fork of the Auto Correlation Recorder
that can apply your Correlation Rules to a HAR file (see the *Import a HAR file instead of
recording* section of the [README](../README.md)).

It is kept here only for convenience until it is attached to the
[release](https://github.com/bernardo-dorze/VibeCorrelationRecorder/releases/tag/v3.1.1-har.1).

- Built from the sources of this branch with `mvn -DskipTests package`
- sha256: `49868e94707a5ef8be6636fcf8945849d65c1ec6ccc533b58880d51e3dff9399`
- Built and tested with Java 8, JMeter 5.5 and 5.6.3

## Install

1. Copy the jar into `$JMETER_HOME/lib/ext` (replacing any previous Auto Correlation Recorder jar).
2. The dependencies are not bundled (as in the upstream plugin), so `$JMETER_HOME/lib` needs
   `jmeter-bzm-commons`, `json`, `json-path`, `json-smart`, `maven-artifact` and the `xmlunit`
   jars. Installing the plugin with the JMeter Plugins Manager brings them in.
