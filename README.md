# Schema
Schema is a Scala DSL for expressing and [optimistically executing](https://en.wikipedia.org/wiki/Optimistic_concurrency_control) database transactions. It is intended to serve as a replacement for SQL, which has reigned as the language of choice for codifying database interactions for the past 43 years. As a motivating example, let's write the same transaction in MySQL and Schema. Both transactions atomically increment a counter ```x```; however, unlike MySQL, Schema does not require an explicit table definition and runs on *any* [transactional key-value store](https://en.wikipedia.org/wiki/Key-value_database) (including MySQL). Check out the [User Guide](https://github.com/ashwin153/schema/wiki/User-Guide) for more examples of Schema in action.

```sql
CREATE TABLE `counters` (
  `key` varchar(250) NOT NULL,
  `value` BIGINT,
  PRIMARY KEY (`key`)
)

START TRANSACTION;
INSERT INTO `counters` (`key`, `value`) VALUES ("x", 1) 
ON DUPLICATE KEY UPDATE `value` = `value` + 1
COMMIT;
```

```scala
Schema { implicit ctx =>
  val counter = Select("x")
  If (!counter.exists) {
    counter.value = 1
  } Else {
    counter.value += 1
  }
}
```

# Build
Artifacts are published to the [Sonatype OSS Repository Hosting Service](https://oss.sonatype.org/index.html#nexus-search;quick~com.madavan) and synced to [Maven Central](https://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.madavan%22).

## Pants
```python
jar_library(name='schema', jars=[
    jar(org='com.madavan', name='schema-runtime_2.12', rev='1.0.3'),
    jar(org='com.madavan', name='schema-postgresql_2.12', rev='1.0.3'),
    jar(org='com.madavan', name='schema-mysql_2.12', rev='1.0.3'),
])
```

## SBT
```scala
scalaVersion := "2.12.1"

resolvers ++= Seq(
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("snapshots")
)

libraryDependencies ++= Seq(
  "com.madavan" %% "schema-runtime" % "1.0.3",
  "com.madavan" %% "schema-mysql" % "1.0.3",
  "com.madavan" %% "schema-postgresql" % "1.0.3"
)
```

## Maven
```xml
<dependency>
  <groupId>com.madavan</groupId>
  <artifactId>schema-runtime_2.12</artifactId>
  <version>1.0.3</version>
</dependency>

<dependency>
  <groupId>com.madavan</groupId>
  <artifactId>schema-mysql_2.12</artifactId>
  <version>1.0.3</version>
</dependency>

<dependency>
  <groupId>com.madavan</groupId>
  <artifactId>schema-postgresql_2.12</artifactId>
  <version>1.0.3</version>
</dependency>
```

# Documentation
Refer to the [User Guide](https://github.com/ashwin153/schema/wiki/User-Guide) to learn about how to use the system, the [Appendix](https://github.com/ashwin153/schema/wiki/Appendix) for an exhaustive list of the various library features, the [Implementation](https://github.com/ashwin153/schema/wiki/Implementation) for more detail about how the system works, and the [Benchmarks](https://github.com/ashwin153/schema/wiki/Benchmarks) to see how it performs.

# Release
- Setup GPG: http://macgpg.sourceforge.net/docs/howto-build-gpg-osx.txt.asc
  - Pants configuration isn't compatible with GPG 2.1.
  - Be sure to run ```gpg --keyserver hkp://pgp.mit.edu --send-keys <KEY_ID>```.
- Setup PGP Pants Config: http://www.pantsbuild.org/release_jvm.html#pgp-credentials
- Publish Build Artifacts: ```./pants publish.jar --publish-jar-no-dryrun ::```
  - Pants automatically increments the patch version each release. To change the major or minor versions use ```--publish-jar-override=<GROUP_ID>#<ARTIFACT_ID>=<VERSION>```
- Promote to Maven Central: http://www.pantsbuild.org/release_jvm.html#promoting-to-maven-central
