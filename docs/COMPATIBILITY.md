# Compatibility

The baseline is Paper 1.21.4 and JDK 25 for both building and the plugin's server runtime. The JAR is compiled with Java 25 bytecode and requires Java 25 even when a server release has a lower minimum JVM requirement.

| Environment | Target | Status |
| --- | --- | --- |
| Build and plugin runtime | JDK 25 | Build and unit tests pass on JDK 25.0.2; live server not yet run |
| Minimum Paper API | 1.21.4 (`api-version: 1.21.4`) | Compiled against 1.21.4-R0.1-SNAPSHOT; live server not yet run |
| Newer Paper releases | Per-version acceptance | Not verified |
| Folia | Separate scheduler investigation | No support commitment |
| Java clients | Per-version pack/render profiles | Planned |
| Bedrock clients | Optional bridge and separate packs/adapters | Planned; feature differences expected |

Record exact server/client builds, Java version, integrations and pack hashes with future acceptance results. A `1.21.4+` target does not establish unlimited forward compatibility.
