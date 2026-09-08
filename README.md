Readme

An implementation of ebms 2

## Quality checks

```bash
mvn package         # build, test, and write the coverage report to target/site/jacoco/
mvn verify          # the above, plus enforce the coverage floor
./scripts/sonar.sh  # start SonarQube, scan with coverage, print the dashboard URL
```

Coverage floors are the `jacoco.line.minimum` and `jacoco.branch.minimum` properties in
`pom.xml`; the build fails below them. Analysis settings live in `pom.xml` too — the Maven
scanner reads the POM, not a `sonar-project.properties` file.
